package space.linuxct.teleforward.data.telegram

import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.linuxct.teleforward.data.db.dao.NowPlayingSessionDao
import space.linuxct.teleforward.data.db.entity.NowPlayingSessionEntity
import space.linuxct.teleforward.data.link.LinkResolver
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.domain.NotificationActionInfo
import space.linuxct.teleforward.domain.remoteButtons
import space.linuxct.teleforward.util.BoundedCache
import space.linuxct.teleforward.work.TelegramPollWorker
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What [NowPlayingController]'s song-link step should do for one update. See [mediaLinkAction]. */
internal enum class MediaLinkAction {
    /** Song link is off globally, or magic links are opted out for this package. */
    SKIP_DISABLED,

    /** Same track as last rendered — only the transport state changed. Reuse, don't look up again. */
    REUSE_CACHED,

    /** No usable track/artist text to look anything up with. */
    SKIP_BLANK,

    /** A new track with usable text: do the (network) lookup. */
    RESOLVE,
}

/**
 * Pure: decide the song-link step for one now-playing update.
 *
 * Extracted from [NowPlayingController] precisely so the **order** of these checks is testable. Putting
 * the cache check first was a real bug: because the rendered-track cache is written after every send or
 * edit, turning the song link off mid-song kept serving the cached `🔗` line on every subsequent
 * play/pause edit until the track changed, so the setting looked like it hadn't applied. The opt-outs
 * must therefore be evaluated **before** the cache.
 */
internal fun mediaLinkAction(
    songLinkEnabled: Boolean,
    packageOptedOut: Boolean,
    sameTrackAsRendered: Boolean,
    track: String,
    artist: String,
): MediaLinkAction = when {
    !songLinkEnabled || packageOptedOut -> MediaLinkAction.SKIP_DISABLED
    sameTrackAsRendered -> MediaLinkAction.REUSE_CACHED
    track.isBlank() || artist.isBlank() -> MediaLinkAction.SKIP_BLANK
    else -> MediaLinkAction.RESOLVE
}

/**
 * Keeps **one** "now playing" message per media app in the chat and edits it in place.
 *
 * Media notifications are ongoing and re-post on every track change and every play/pause, so routing
 * them through the outbox would mean a message per tick (and would pollute the delivery log, and
 * fight the dedupe index). They deliberately bypass delivery entirely: this controller owns the
 * message, and each update rewrites its text and rebuilds its buttons — the rebuild is what keeps the
 * button tokens pointing at the notification that is live now.
 *
 * The transport toggle is always drawn as "⏯ Play/Pause", never as the player's current word, so the
 * label can't contradict itself between the press and the edit that would have corrected it.
 *
 * Everything is best-effort: a dropped update is harmless, the next one corrects it.
 */
@Singleton
class NowPlayingController @Inject constructor(
    private val sessionDao: NowPlayingSessionDao,
    private val sender: TelegramSender,
    private val keyboards: RemoteActionKeyboards,
    private val pinner: ChatPinner,
    private val strings: TelegramStrings,
    private val messageBuilder: MessageBuilder,
    private val settings: SettingsRepository,
    private val workManager: WorkManager,
    private val linkResolver: LinkResolver,
) {

    private companion object {
        /** Hard cap on the in-process mirror: only a handful of players are ever active at once. */
        const val MAX_TRACKED_MEDIA_APPS = 16
    }

    /** Serialises updates so two rapid track changes can't both create a message. */
    private val mutex = Mutex()

    /**
     * Scope for the off-critical-path song-link lookup ([scheduleLinkResolve]). A `SupervisorJob` so one
     * failed lookup can't cancel later ones; process-lifetime, matching this `@Singleton`.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * In-memory mirror of the track each package's live control is showing, so [needsArtwork] can be
     * answered instantly — the caller must decide whether to extract artwork *before* doing anything
     * slow, because the notification's bitmap is only reliably readable right after it arrives.
     *
     * Hard-capped, and a miss deliberately means "yes, extract": an evicted or not-yet-populated entry
     * must never cause a message to be posted without its cover art.
     */
    private val renderedTracks = BoundedCache<String, String>(MAX_TRACKED_MEDIA_APPS)

    /**
     * The resolved "now playing" universal song link per package, mirrored so a play/pause edit reuses
     * the link instead of re-hitting the network — it is looked up once per *track* change (see
     * [mediaLink]). In-memory only: after a process restart the next track change re-resolves it.
     */
    private val resolvedLinks = BoundedCache<String, String>(MAX_TRACKED_MEDIA_APPS)

    /**
     * Would a message be posted for this track, and therefore need artwork?
     *
     * True when the track differs from what the control currently shows — and also when nothing is
     * known about it, since that is exactly the case (fresh process, cleared session, evicted entry)
     * where a new message is about to be sent.
     */
    fun needsArtwork(packageName: String, title: String?, text: String?): Boolean {
        val known = renderedTracks[packageName] ?: return true
        return known != trackKeyOf(title, text)
    }

    /**
     * Render the current media state for [packageName]. Sends the control message the first time and
     * edits it thereafter. Identical repeats (media notifications repeat constantly) are ignored.
     */
    suspend fun update(
        packageName: String,
        appLabel: String,
        notificationKey: String,
        title: String?,
        text: String?,
        actions: List<NotificationActionInfo>,
        /**
         * Album art already persisted to a file, or null when this update doesn't carry any. The
         * caller extracts it eagerly (while the notification's bitmap is certainly still valid) and
         * only when the track changed; anything unused here is deleted.
         */
        albumArtPath: String? = null,
    ) {
        var sentNewMessage = false
        runCatching {
            val chatId = settings.chatId.first() ?: return
            if (!settings.nowPlayingEnabled.first()) return

            val trackKey = trackKeyOf(title, text)
            // Decide the song link WITHOUT blocking on the network: a same-track edit reuses the cached
            // link, a new track renders without one and resolves in the background (see below), so the
            // card appears immediately instead of waiting on the iTunes lookup.
            val linkAction = mediaLinkAction(
                songLinkEnabled = settings.nowPlayingSongLink.first(),
                packageOptedOut = packageName in settings.magicLinkDisabledPackages.first(),
                sameTrackAsRendered = renderedTracks[packageName] == trackKey,
                track = title?.trim().orEmpty(),
                artist = text?.trim().orEmpty(),
            )
            val link = if (linkAction == MediaLinkAction.REUSE_CACHED) resolvedLinks[packageName] else null
            // A new or unusable track must not keep showing the previous track's link. (An opted-out
            // package keeps its cache, so re-enabling restores the link without another lookup.)
            if (linkAction == MediaLinkAction.RESOLVE || linkAction == MediaLinkAction.SKIP_BLANK) {
                resolvedLinks.remove(packageName)
            }
            val body = renderText(appLabel, title, text, link)
            val fingerprint = fingerprintOf(trackKey, notificationKey, actions)
            val now = System.currentTimeMillis()

            mutex.withLock {
                val existing = sessionDao.find(packageName)
                when {
                    existing == null -> {
                        sentNewMessage = true
                        sendNew(
                            packageName, chatId, notificationKey, body, actions,
                            trackKey, fingerprint, now, albumArtPath,
                        )
                    }

                    // A NEW TRACK gets a new message, so Telegram actually notifies you that
                    // something else is playing — an edit would be silent. The previous track's
                    // message is deleted first, so only the newest control has live buttons.
                    // (A track played earlier is "new" again here, since we only compare against
                    // what is playing right now — no history, so repeats are never swallowed.)
                    existing.trackKey != trackKey -> {
                        retire(existing)
                        sentNewMessage = true
                        sendNew(
                            packageName, chatId, notificationKey, body, actions,
                            trackKey, fingerprint, now, albumArtPath,
                        )
                    }

                    // Same track, different state (play ⇄ pause): edit in place so the controls stay
                    // correct without pinging you for every button press.
                    existing.fingerprint != fingerprint ->
                        editExisting(
                            packageName, existing, chatId, notificationKey,
                            body, actions, trackKey, fingerprint, now,
                        )

                    // Byte-identical re-post — media notifications repeat constantly. Do nothing.
                    else -> Unit
                }

                // A brand-new track that wants a link: resolve off the critical path and edit it in.
                if (sentNewMessage && linkAction == MediaLinkAction.RESOLVE) {
                    scheduleLinkResolve(
                        packageName, chatId, trackKey, appLabel, title, text, notificationKey, actions,
                    )
                }
            }
        }
        // The control's transport buttons are useless unless something is listening for the press —
        // only DeliveryWorker used to arm the poller, so now-playing buttons rendered but did nothing.
        //
        // Armed only when a NEW message is posted (a track change), not on every play/pause edit:
        // re-arming on every render kept the poller running continuously for the whole listening
        // session, which is a real battery cost. "Always listening" is the setting for people who
        // want a press to land at any moment.
        if (sentNewMessage) {
            runCatching { TelegramPollWorker.scheduleBurst(workManager) }
        } else {
            // Artwork was extracted for a track change the caller saw but that didn't result in a new
            // message (e.g. its in-memory view disagreed with the stored session). Don't leak the file.
            deleteArt(albumArtPath)
        }
    }

    private fun deleteArt(path: String?) {
        if (path == null) return
        runCatching { File(path).delete() }
    }

    /** Playback ended: retire the control so a dead message can't sit there offering buttons. */
    suspend fun clear(packageName: String, stoppedLabel: String = strings.playbackEnded) {
        runCatching {
            val chatId = settings.chatId.first() ?: return
            mutex.withLock {
                val session = sessionDao.find(packageName) ?: return
                sessionDao.delete(packageName)
                renderedTracks.remove(packageName)
                resolvedLinks.remove(packageName)
                keyboards.clearKeyboardForMessage(chatId, session.messageId)
                // Playback is over, so the pin bar should stop advertising it. The message itself
                // stays (rewritten to "playback ended") — only its prominence is withdrawn.
                pinner.unpin(chatId, session.messageId)
                sender.editMessage(chatId, session.messageId, stoppedLabel, replyMarkup = null)
            }
        }
    }

    /**
     * Retire the previous track's message: delete it so the chat keeps only the newest control, and
     * drop its tokens so any button that outlives the delete resolves to "expired" rather than
     * quietly driving the player.
     */
    private suspend fun retire(session: NowPlayingSessionEntity) {
        keyboards.clearKeyboardForMessage(session.chatId, session.messageId)
        // Silent, so it costs nothing; done explicitly so the pin can't outlive the message it names.
        pinner.unpin(session.chatId, session.messageId)
        sender.deleteMessage(session.chatId, session.messageId)
    }

    private suspend fun sendNew(
        packageName: String,
        chatId: Long,
        notificationKey: String,
        body: String,
        actions: List<NotificationActionInfo>,
        trackKey: String,
        fingerprint: String,
        now: Long,
        albumArtPath: String?,
    ) {
        // Album art rides the message as a photo with the track as its caption. Sent bare first
        // because the buttons need a message id to bind their tokens to.
        val hasArt = albumArtPath != null && File(albumArtPath).exists()
        val result = if (hasArt) {
            sender.sendPhotoMessage(chatId, albumArtPath!!, body)
        } else {
            sender.sendMessage(chatId, body)
        }
        deleteArt(albumArtPath)

        val messageId = (result as? SendResult.Success)?.messageIds?.firstOrNull() ?: return
        val markup = keyboards.replaceKeyboardForMessage(notificationKey, actions, chatId, messageId, now)
        if (markup != null) {
            if (hasArt) {
                sender.editCaption(chatId, messageId, body, markup)
            } else {
                sender.editMessage(chatId, messageId, body, markup)
            }
        }
        renderedTracks[packageName] = trackKey
        // Keep whatever is playing reachable above the chat, however much arrives under it.
        pinner.pin(chatId, messageId)
        sessionDao.upsert(
            NowPlayingSessionEntity(
                sessionKey = packageName,
                chatId = chatId,
                messageId = messageId,
                notificationKey = notificationKey,
                trackKey = trackKey,
                photoMessage = hasArt,
                fingerprint = fingerprint,
                updatedAt = now,
            ),
        )
    }

    private suspend fun editExisting(
        packageName: String,
        existing: NowPlayingSessionEntity,
        chatId: Long,
        notificationKey: String,
        body: String,
        actions: List<NotificationActionInfo>,
        trackKey: String,
        fingerprint: String,
        now: Long,
    ) {
        val markup = keyboards.replaceKeyboardForMessage(
            notificationKey = notificationKey,
            actions = actions,
            chatId = chatId,
            messageId = existing.messageId,
            now = now,
        )
        // A control carrying album art is a photo message, so its text lives in the caption.
        val edit = if (existing.photoMessage == true) {
            sender.editCaption(chatId, existing.messageId, body, markup)
        } else {
            sender.editMessage(chatId, existing.messageId, body, markup)
        }
        when (edit) {
            // The message is gone (deleted by the user, or too old to edit): start a fresh one.
            is SendResult.Terminal, is SendResult.BadRequest -> {
                sessionDao.delete(packageName)
                renderedTracks.remove(packageName)
                resolvedLinks.remove(packageName)
                sendNew(
                    packageName, chatId, notificationKey, body, actions,
                    trackKey, fingerprint, now, albumArtPath = null,
                )
            }

            else -> {
                renderedTracks[packageName] = trackKey
                sessionDao.upsert(
                    existing.copy(
                        notificationKey = notificationKey,
                        trackKey = trackKey,
                        fingerprint = fingerprint,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    /**
     * The universal song link for the current track — best-effort, and cached per package so it is
     * resolved once per *track change* and reused across play/pause edits (the network lookup must not
     * run on every re-post). Honours the same per-app magic-link opt-out as forwarded messages. A blank
     * track/artist, an opted-out package, or a non-confident match all yield null → no `🔗` line.
     *
     * [trackKey] equal to the last rendered track means "same song, only the transport state changed",
     * so the cached link is reused without a lookup; otherwise the (title=track, text=artist) pair is
     * resolved via [LinkResolver.resolveMediaLink].
     */
    /**
     * Resolve the universal song link **off the critical path** and edit it into the card once it
     * lands. The lookup is a network round trip (usually sub-second, but up to the client's timeout), and
     * doing it inline delayed the whole now-playing message behind it — the control is the point of the
     * feature, the link is a bonus, so the control must not wait.
     *
     * Guarded by re-reading the session under the mutex: if the user skipped on, the stored `trackKey`
     * no longer matches and the edit is dropped rather than stamping the old song's link onto the new
     * card. The keyboard is rebuilt in the same edit because Telegram drops an omitted `reply_markup`,
     * which would otherwise strip the transport buttons.
     *
     * Best-effort throughout: any failure simply leaves the card without a link.
     */
    private fun scheduleLinkResolve(
        packageName: String,
        chatId: Long,
        trackKey: String,
        appLabel: String,
        title: String?,
        text: String?,
        notificationKey: String,
        actions: List<NotificationActionInfo>,
    ) {
        scope.launch {
            runCatching {
                val track = title?.trim().orEmpty()
                val artist = text?.trim().orEmpty()
                if (track.isBlank() || artist.isBlank()) return@runCatching
                val link = linkResolver.resolveMediaLink(track, artist) ?: return@runCatching
                mutex.withLock {
                    val session = sessionDao.find(packageName) ?: return@withLock
                    // Still the same song? If not, the link belongs to a track that is no longer shown.
                    if (session.trackKey != trackKey) return@withLock
                    resolvedLinks[packageName] = link
                    val body = renderText(appLabel, title, text, link)
                    val markup = keyboards.replaceKeyboardForMessage(
                        notificationKey = notificationKey,
                        actions = actions,
                        chatId = chatId,
                        messageId = session.messageId,
                        now = System.currentTimeMillis(),
                    )
                    if (session.photoMessage == true) {
                        sender.editCaption(chatId, session.messageId, body, markup)
                    } else {
                        sender.editMessage(chatId, session.messageId, body, markup)
                    }
                }
            }
        }
    }

    /** `🎵 <b>Track</b>` / artist / app / 🔗 link — mirrors the notification without pretending to be a forward. */
    private fun renderText(appLabel: String, title: String?, text: String?, link: String? = null): String = buildString {
        append("🎵 <b>").append(messageBuilder.escapeHtml(appLabel)).append("</b>")
        title?.takeUnless { it.isBlank() }?.let {
            append('\n').append(messageBuilder.escapeHtml(it))
        }
        text?.takeUnless { it.isBlank() }?.let {
            append('\n').append("<i>").append(messageBuilder.escapeHtml(it)).append("</i>")
        }
        // The link is a song.link url whose payload is fully percent-encoded (no HTML-special chars),
        // so it's safe as bare text and Telegram auto-links it.
        link?.takeUnless { it.isBlank() }?.let {
            append('\n').append("🔗 ").append(it)
        }
    }

    /** Identity of the song itself — a change here means a different track is playing. */
    private fun trackKeyOf(title: String?, text: String?): String =
        "${title.orEmpty()}${text.orEmpty()}"

    /**
     * Signature of everything we render: [trackKeyOf] plus the buttons **as they will be drawn**.
     *
     * Fingerprinting the rendered buttons rather than the raw action titles is what makes a play ⇄
     * pause press cost nothing. Both states now draw the identical "⏯ Play/Pause" label at the same
     * index, so the signature doesn't move and the update is dropped as an unchanged re-post — no
     * edit call for something that would look exactly the same. (Telegram would answer "message is
     * not modified" anyway, but the request is pure waste on a notification that re-posts constantly.)
     *
     * Two things are folded in beyond the labels so this can only ever skip a genuine no-op — both
     * cover cases where the buttons would *look* unchanged while their tokens had gone stale:
     *  - the action **index**, in case a player moves its transport controls around;
     *  - the **notification key**, in case it re-posts under a new id rather than updating in place.
     * Either would leave the tokens pointing at something that no longer exists (or worse, at the
     * wrong control), and neither is visible in the rendered text.
     */
    private fun fingerprintOf(
        trackKey: String,
        notificationKey: String,
        actions: List<NotificationActionInfo>,
    ): String =
        buildString {
            append(trackKey).append('|').append(notificationKey)
            remoteButtons(actions, strings.buttonLabels, includeDismiss = false, stableLabels = true).forEach {
                append('|').append(it.actionIndex).append(':').append(it.label)
            }
        }
}
