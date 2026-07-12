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
import space.linuxct.teleforward.diag.DiagStore
import space.linuxct.teleforward.diag.NotificationForensics
import space.linuxct.teleforward.domain.FilterDecision
import space.linuxct.teleforward.domain.FilterEngine
import space.linuxct.teleforward.domain.RawNotification
import space.linuxct.teleforward.domain.SelectionRule
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

    /** Owned scope for all heavy work; cancelled in [onDestroy]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Snapshot of the settings the binder thread needs, kept current by a collector. */
    @Volatile private var listenerSettings = ListenerSettings(
        skipOngoing = SettingsKeys.Defaults.SKIP_ONGOING,
        forwardingEnabled = SettingsKeys.Defaults.FORWARDING_ENABLED,
        includeImages = SettingsKeys.Defaults.INCLUDE_IMAGES,
        diagnosticsEnabled = SettingsKeys.Defaults.DIAGNOSTICS_ENABLED,
    )

    /** Current rule set, kept in sync so [FilterEngine] can be evaluated without a suspend call. */
    @Volatile private var rules: List<SelectionRule> = emptyList()

    override fun onCreate() {
        super.onCreate()
        // Cache the settings + rules the binder thread reads. Collectors live for the service's
        // lifetime; started here (once) rather than in onListenerConnected (which can re-fire).
        scope.launch {
            combine(
                settingsRepository.skipOngoing,
                settingsRepository.forwardingEnabled,
                settingsRepository.includeImages,
                settingsRepository.diagnosticsEnabled,
            ) { skipOngoing, forwardingEnabled, includeImages, diagnosticsEnabled ->
                ListenerSettings(skipOngoing, forwardingEnabled, includeImages, diagnosticsEnabled)
            }.collect { listenerSettings = it }
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

        // 1. Cheap eligibility reject on the binder thread.
        if (!notificationMapper.isEligible(notification, settings.skipOngoing)) return

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

        // 5-7. Extract content + images and enqueue, all off the binder thread. The bitmaps in the
        //       notification are recycled once this callback returns, so persist eagerly.
        val imageCacheDir = cacheDir
        scope.launch {
            runCatching {
                val label = notificationMapper.appLabel(packageName)
                val content = notificationMapper.extractContent(notification)
                val images = notificationMapper.extractImages(notification, imageCacheDir, settings.includeImages)
                val raw = notificationMapper.buildRawNotification(
                    sbn = notification,
                    channel = channel,
                    conversationId = conversation?.conversationId,
                    appLabel = label,
                    content = content,
                    imagePaths = images.map { it.filePath },
                )
                intakeRepository.enqueue(raw)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
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
        // Mitigate OEM battery-killers: ask the framework to rebind us.
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(this, TeleNotificationListener::class.java),
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class ListenerSettings(
        val skipOngoing: Boolean,
        val forwardingEnabled: Boolean,
        val includeImages: Boolean,
        val diagnosticsEnabled: Boolean,
    )
}
