package space.linuxct.teleforward.data.telegram

import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.linuxct.teleforward.data.db.dao.NowPlayingSessionDao
import space.linuxct.teleforward.data.db.entity.NowPlayingSessionEntity
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.domain.NotificationActionInfo
import space.linuxct.teleforward.domain.remoteButtons
import space.linuxct.teleforward.util.BoundedCache
import space.linuxct.teleforward.work.TelegramPollWorker
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
    private val messageBuilder: MessageBuilder,
    private val settings: SettingsRepository,
    private val workManager: WorkManager,
) {

    private companion object {
        /** Hard cap on the in-process mirror: only a handful of players are ever active at once. */
        const val MAX_TRACKED_MEDIA_APPS = 16
    }

    /** Serialises updates so two rapid track changes can't both create a message. */
    private val mutex = Mutex()

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

            val body = renderText(appLabel, title, text)
            val trackKey = trackKeyOf(title, text)
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
    suspend fun clear(packageName: String, stoppedLabel: String = "⏹ Playback ended") {
        runCatching {
            val chatId = settings.chatId.first() ?: return
            mutex.withLock {
                val session = sessionDao.find(packageName) ?: return
                sessionDao.delete(packageName)
                renderedTracks.remove(packageName)
                keyboards.clearKeyboardForMessage(chatId, session.messageId)
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

    /** `🎵 <b>Track</b>` / artist / app — mirrors the notification without pretending to be a forward. */
    private fun renderText(appLabel: String, title: String?, text: String?): String = buildString {
        append("🎵 <b>").append(messageBuilder.escapeHtml(appLabel)).append("</b>")
        title?.takeUnless { it.isBlank() }?.let {
            append('\n').append(messageBuilder.escapeHtml(it))
        }
        text?.takeUnless { it.isBlank() }?.let {
            append('\n').append("<i>").append(messageBuilder.escapeHtml(it)).append("</i>")
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
            remoteButtons(actions, includeDismiss = false, stableLabels = true).forEach {
                append('|').append(it.actionIndex).append(':').append(it.label)
            }
        }
}
