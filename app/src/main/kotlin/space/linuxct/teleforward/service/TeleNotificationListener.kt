package space.linuxct.teleforward.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import space.linuxct.teleforward.data.repo.IntakeRepository
import space.linuxct.teleforward.data.repo.RulesRepository
import space.linuxct.teleforward.data.repo.SeenChannelRepository
import space.linuxct.teleforward.data.repo.SeenConversationRepository
import space.linuxct.teleforward.data.settings.SettingsKeys
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.DismissedNotificationSync
import space.linuxct.teleforward.data.telegram.NowPlayingController
import space.linuxct.teleforward.diag.DiagStore
import space.linuxct.teleforward.diag.NotificationForensics
import space.linuxct.teleforward.domain.FilterDecision
import space.linuxct.teleforward.domain.FilterEngine
import space.linuxct.teleforward.domain.RawNotification
import space.linuxct.teleforward.domain.SelectionRule
import space.linuxct.teleforward.domain.isMediaNotification
import java.io.File
import javax.inject.Inject

/**
 * Wave 1 implementation of the binder-thread handoff.
 *
 * On [onNotificationPosted] the cheap work runs synchronously on the binder thread (eligibility,
 * channel resolution, and the [FilterEngine] decision against cached rules/settings); only when the
 * decision is FORWARD does it hand off to an owned IO scope for content/image extraction and
 * enqueue. Rules and the settings we need on the binder thread are cached from collected flows so
 * no suspend/IO call blocks the callback.
 *
 * Declared in the manifest with BIND_NOTIFICATION_LISTENER_SERVICE + the listener intent-filter.
 */
@AndroidEntryPoint
class TeleNotificationListener : NotificationListenerService() {

    @Inject lateinit var intakeRepository: IntakeRepository
    @Inject lateinit var seenChannelRepository: SeenChannelRepository
    @Inject lateinit var seenConversationRepository: SeenConversationRepository
    @Inject lateinit var notificationMapper: NotificationMapper
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var rulesRepository: RulesRepository
    @Inject lateinit var filterEngine: FilterEngine
    @Inject lateinit var notificationForensics: NotificationForensics
    @Inject lateinit var diagStore: DiagStore
    @Inject lateinit var actionGateway: NotificationActionGateway
    @Inject lateinit var nowPlayingController: NowPlayingController
    @Inject lateinit var mediaDebouncer: MediaDispatchDebouncer
    @Inject lateinit var dismissedNotificationSync: DismissedNotificationSync
    @Inject lateinit var screenOnPoller: ScreenOnPoller

    /** Owned scope for all heavy work; cancelled in [onDestroy]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The handle [NotificationActionGateway] acts through — the only bridge from background code
     * (workers handling Telegram button presses) back into this live service. Attached while
     * connected; a notification is always re-resolved by key, never cached.
     */
    private val actionHost = object : NotificationHost {
        override fun activeNotificationByKey(key: String): StatusBarNotification? = runCatching {
            // The keyed overload is the cheap path; fall back to a scan if the framework returns null.
            getActiveNotifications(arrayOf(key))?.firstOrNull()
                ?: activeNotifications?.firstOrNull { it.key == key }
        }.getOrNull()

        override fun cancel(key: String) {
            cancelNotification(key)
        }
    }

    /** Snapshot of the settings the binder thread needs, kept current by a collector. */
    @Volatile private var listenerSettings = ListenerSettings(
        skipOngoing = SettingsKeys.Defaults.SKIP_ONGOING,
        forwardingEnabled = SettingsKeys.Defaults.FORWARDING_ENABLED,
        includeImages = SettingsKeys.Defaults.INCLUDE_IMAGES,
        diagnosticsEnabled = SettingsKeys.Defaults.DIAGNOSTICS_ENABLED,
        nowPlayingEnabled = SettingsKeys.Defaults.NOW_PLAYING_ENABLED,
    )

    /** Current rule set, kept in sync so [FilterEngine] can be evaluated without a suspend call. */
    @Volatile private var rules: List<SelectionRule> = emptyList()

    override fun onCreate() {
        super.onCreate()
        // Cache the settings + rules the binder thread reads. Collectors live for the service's
        // lifetime; started here (once) rather than in onListenerConnected (which can re-fire).
        scope.launch {
            // combine() only has typed overloads up to five flows, so the rest are folded on after.
            combine(
                settingsRepository.skipOngoing,
                settingsRepository.forwardingEnabled,
                settingsRepository.includeImages,
                settingsRepository.diagnosticsEnabled,
                settingsRepository.nowPlayingEnabled,
            ) { skipOngoing, forwardingEnabled, includeImages, diagnosticsEnabled, nowPlaying ->
                ListenerSettings(
                    skipOngoing = skipOngoing,
                    forwardingEnabled = forwardingEnabled,
                    includeImages = includeImages,
                    diagnosticsEnabled = diagnosticsEnabled,
                    nowPlayingEnabled = nowPlaying,
                )
            }
                .combine(settingsRepository.includeAvatars) { base, includeAvatars ->
                    base.copy(includeAvatars = includeAvatars)
                }
                .collect { listenerSettings = it }
        }
        scope.launch {
            rulesRepository.observeAllRules().collect { rules = it }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val notification = sbn ?: return
        val settings = listenerSettings

        // 0. Forensic capture (diagnostics): independent of the forward filter, covers ALL apps.
        //    Off by default; when enabled, probe/dump on the IO scope from the retained reference.
        if (settings.diagnosticsEnabled) {
            scope.launch {
                runCatching { diagStore.append(notificationForensics.capture(notification, rankingMap)) }
            }
        }

        // 1. Cheap eligibility reject on the binder thread. A media/transport notification is normally
        //    dropped here as "ongoing"; when the now-playing control is on we let it through instead
        //    (skipOngoing = false still rejects our own app and group summaries) and divert it below.
        val isMedia = isMediaNotification(
            category = notification.notification.category,
            template = notification.notification.extras.getString(TEMPLATE_EXTRA),
            hasMediaSession = notification.notification.extras.containsKey(MEDIA_SESSION_EXTRA),
        )
        val nowPlaying = isMedia && settings.nowPlayingEnabled
        if (!notificationMapper.isEligible(notification, settings.skipOngoing && !nowPlaying)) return

        // 2/3. Resolve channel + conversation identity (both cheap) and record them into the
        //       seen-channels / seen-conversations catalogs (off-thread).
        val channel = notificationMapper.resolveChannel(notification, rankingMap)
        val conversation = notificationMapper.resolveConversation(notification, rankingMap)
        val packageName = notification.packageName
        val userHandle = notification.user
        scope.launch {
            val now = System.currentTimeMillis()
            val userSerial = resolveUserSerial(this@TeleNotificationListener, userHandle)
            runCatching {
                seenChannelRepository.recordSeen(
                    packageName = packageName,
                    channelId = channel.channelId,
                    channelName = channel.name,
                    importance = channel.importance,
                    userSerial = userSerial,
                    seenAt = now,
                )
            }
            if (conversation != null) {
                runCatching {
                    seenConversationRepository.recordSeen(
                        packageName = packageName,
                        channelId = channel.channelId,
                        conversationId = conversation.conversationId,
                        title = conversation.title,
                        userSerial = userSerial,
                        seenAt = now,
                    )
                }
            }
        }

        // 4. Filter using a cheap probe (FilterEngine reads packageName + channelId + conversationId).
        //    If this isn't a FORWARD we return WITHOUT extracting content/images (privacy + perf).
        val probe = RawNotification(
            packageName = packageName,
            appLabel = packageName,
            channelId = channel.channelId,
            channelName = channel.name,
            conversationId = conversation?.conversationId,
            title = null,
            body = null,
            postTime = notification.postTime,
            userSerial = 0L,
            imagePaths = emptyList(),
            key = notification.key,
            dedupeKey = notification.key,
        )
        if (filterEngine.decide(probe, rules, settings.forwardingEnabled) != FilterDecision.FORWARD) {
            return
        }

        // 4b. Media notifications never enter the outbox: they re-post on every track change and
        //     play/pause, so instead they drive ONE control message that is edited in place.
        if (nowPlaying) {
            val content = notificationMapper.extractContent(notification)
            val actions = notificationMapper.extractActions(notification)
            val artCacheDir = cacheDir
            // Decide here whether artwork is even needed, from an in-memory check — the alternative
            // (resolving it lazily inside the controller) defers extraction past the settings reads,
            // the mutex and a DB lookup, by which point the system may have recycled or replaced the
            // notification's bitmap. That showed up as the wrong cover art when skipping backwards,
            // where a player posts several updates in quick succession.
            // Asked of the controller (an instant in-memory check) rather than tracked here, so the
            // answer reflects what the control has actually rendered. It errs towards extracting: an
            // unknown package must never produce a message without its cover art.
            val trackChanged = nowPlayingController.needsArtwork(
                packageName = packageName,
                title = content.title,
                text = content.body,
            )
            scope.launch {
                runCatching {
                    // On a media notification the large icon IS the album art. Extracted right away
                    // (the first thing this coroutine does) so the bitmap is read while it is
                    // certainly valid, but only when the track actually changed — media notifications
                    // re-post constantly and those need no artwork.
                    val albumArtPath = if (trackChanged) {
                        notificationMapper.extractLargeIcon(notification, artCacheDir)
                    } else {
                        null
                    }
                    val label = notificationMapper.appLabel(packageName)
                    // Only the track you land on is worth a message. Skipping through five songs
                    // would otherwise retire, re-send and re-pin five times over — five bursts of
                    // Telegram calls for four messages that get deleted moments later.
                    mediaDebouncer.submit(
                        key = packageName,
                        discard = { discardArtwork(albumArtPath) },
                        action = {
                            nowPlayingController.update(
                                packageName = packageName,
                                appLabel = label,
                                notificationKey = notification.key,
                                title = content.title,
                                text = content.body,
                                actions = actions,
                                albumArtPath = albumArtPath,
                            )
                        },
                    )
                }
            }
            return
        }

        // 5-7. Extract content + images and enqueue, all off the binder thread. The bitmaps in the
        //       notification can be recycled or replaced out from under us — media notifications
        //       re-post several times a second — so the read happens FIRST, before anything that
        //       might make it wait. Resolving the app label ahead of it used to put a PackageManager
        //       lookup between arrival and the pixels; this is the same ordering rule the now-playing
        //       path already follows, and for the same reason.
        val imageCacheDir = cacheDir
        scope.launch {
            runCatching {
                val content = notificationMapper.extractContent(notification)
                val images = notificationMapper.extractImages(
                    sbn = notification,
                    cacheDir = imageCacheDir,
                    includeImages = settings.includeImages,
                    includeAvatars = settings.includeAvatars,
                )
                // Safe to be slow now: the pixels are already on disk.
                val label = notificationMapper.appLabel(packageName)
                val raw = notificationMapper.buildRawNotification(
                    sbn = notification,
                    channel = channel,
                    conversationId = conversation?.conversationId,
                    appLabel = label,
                    content = content,
                    imagePaths = images.map { it.filePath },
                )
                if (isMedia) {
                    // Same reasoning as the now-playing branch: coalesce a skip-through into the one
                    // track that was landed on. It also closes a race the merge alone couldn't — a
                    // player announces a track with no art and re-posts ~50ms later carrying it, and
                    // whichever arrives last within the window is the one that gets forwarded.
                    mediaDebouncer.submit(
                        key = packageName,
                        discard = { images.forEach { discardArtwork(it.filePath) } },
                        action = { intakeRepository.enqueue(raw) },
                    )
                } else {
                    intakeRepository.enqueue(raw)
                }
            }
        }
    }

    /**
     * Playback ended (or the media notification was cleared): retire that app's now-playing control so
     * a dead message can't sit in the chat still offering transport buttons.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        val notification = sbn ?: return
        val isMedia = isMediaNotification(
            category = notification.notification.category,
            template = notification.notification.extras.getString(TEMPLATE_EXTRA),
            hasMediaSession = notification.notification.extras.containsKey(MEDIA_SESSION_EXTRA),
        )
        val packageName = notification.packageName

        if (isMedia) {
            if (listenerSettings.nowPlayingEnabled) {
                scope.launch { runCatching { nowPlayingController.clear(packageName) } }
            }
            return
        }

        // The notification has left the phone, so any buttons still offered for it are dead. Saying so
        // needs no listener: outbound calls always work, which is what makes this the one half of the
        // problem solvable without holding a connection open.
        //
        // Gated on the reason. An app that *updates* a notification removes and re-posts it, and
        // treating that as "gone" would strip the buttons off a conversation that is very much alive.
        // Only a genuine departure counts — the user swiping it away, or it being clicked or cleared.
        if (reason !in DEPARTURE_REASONS) return
        val key = notification.key
        scope.launch { runCatching { dismissedNotificationSync.onDismissed(key) } }
    }

    /**
     * Release artwork extracted for a media update that a newer one superseded.
     *
     * The extraction has to happen eagerly (the notification's bitmap may not survive the wait), so a
     * coalesced update always leaves a file behind. Without this, skipping through an album would
     * quietly fill the image cache with covers for tracks that were never forwarded.
     */
    private fun discardArtwork(path: String?) {
        if (path == null) return
        runCatching { File(path).delete() }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Expose this connected instance for remote actions (dismiss / fire action button).
        actionGateway.attach(actionHost)
        // Listen for presses while the screen is on — the window in which the user is actually
        // looking at the chat, and the only way a press gets an immediate answer without a
        // permanently-held connection.
        screenOnPoller.attach(this, scope)
        // Seed the catalog from what's already on screen (best-effort; a listener can't enumerate
        // channels up front).
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        scope.launch {
            for (sbn in active) {
                runCatching {
                    val channel = notificationMapper.resolveChannel(sbn, ranking)
                    seenChannelRepository.recordSeen(
                        packageName = sbn.packageName,
                        channelId = channel.channelId,
                        channelName = channel.name,
                        importance = channel.importance,
                        userSerial = resolveUserSerial(this@TeleNotificationListener, sbn.user),
                        seenAt = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // No live connection: remote actions must report "unavailable" rather than act on a dead handle.
        actionGateway.detach(actionHost)
        // Mitigate OEM battery-killers: ask the framework to rebind us.
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(this, TeleNotificationListener::class.java),
            )
        }
    }

    override fun onDestroy() {
        actionGateway.detach(actionHost)
        screenOnPoller.detach(this)
        scope.cancel()
        super.onDestroy()
    }

    private data class ListenerSettings(
        val skipOngoing: Boolean,
        val forwardingEnabled: Boolean,
        val includeImages: Boolean,
        val diagnosticsEnabled: Boolean,
        val nowPlayingEnabled: Boolean,
        val includeAvatars: Boolean = SettingsKeys.Defaults.INCLUDE_AVATARS,
    )

    private companion object {
        /** `Notification.EXTRA_TEMPLATE` — identifies a MediaStyle notification. */
        const val TEMPLATE_EXTRA = "android.template"

        /**
         * `Notification.EXTRA_MEDIA_SESSION` — present on any player's notification regardless of the
         * app, so third-party players are picked up even with an unusual category/template.
         */
        const val MEDIA_SESSION_EXTRA = "android.mediaSession"

        /**
         * Removal reasons that mean the notification is genuinely **gone**, as opposed to being
         * replaced.
         *
         * Apps update a notification by removing and re-posting it, which arrives here as a removal
         * with `REASON_APP_CANCEL`. Treating that as a departure would strip the buttons off a live
         * conversation every time a new message arrived in it — so only a real ending counts: the user
         * swiping it away, tapping it, or clearing the shade.
         */
        val DEPARTURE_REASONS = setOf(
            REASON_CANCEL, // the user dismissed this one
            REASON_CANCEL_ALL, // "clear all"
            REASON_CLICK, // opened, and the app auto-cancelled it
            REASON_LISTENER_CANCEL, // dismissed from Telegram — our own remote Dismiss
            REASON_TIMEOUT,
        )
    }
}
