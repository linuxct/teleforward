package space.linuxct.teleforward.service

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.text.Spanned
import android.text.style.URLSpan
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.linuxct.teleforward.data.db.entity.OutboxImageKind
import space.linuxct.teleforward.data.link.YouTube
import space.linuxct.teleforward.domain.NotificationActionInfo
import space.linuxct.teleforward.domain.RawNotification
import space.linuxct.teleforward.domain.isMediaNotification
import space.linuxct.teleforward.util.BoundedCache
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wave 1 implementation: turns a framework [StatusBarNotification] into the Android-free
 * [RawNotification], resolving channel identity from the RankingMap, extracting title/body
 * (BIG_TEXT / MessagingStyle) and images (BIG_PICTURE + large icon), and persisting images to
 * app-private cache. Every step is best-effort and never throws into the listener.
 */
@Singleton
class NotificationMapperImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationMapper {

    /**
     * Cached PackageManager labels; the listener resolves the same package on every notification.
     * Hard-capped — on eviction the label is simply looked up again.
     */
    private val labelCache = BoundedCache<String, String>(MAX_CACHED_LABELS)

    /**
     * Last media track seen per package, with the `postTime` of the moment it started.
     *
     * Media notifications re-post the same track constantly — captured dumps show 20 re-posts of one
     * track, each with a *fresh* `postTime`, ten of them inside 33 seconds. So `postTime` alone can't
     * tell a genuine replay from a re-post. Instead the play is stamped with the postTime of the
     * update that first showed it, which stays constant while the track does and changes the moment it
     * doesn't. See [mediaPlaySalt].
     */
    private val lastMediaPlay = BoundedCache<String, MediaPlay>(MAX_TRACKED_MEDIA_APPS)

    private data class MediaPlay(val trackKey: String, val salt: Long)

    override fun isEligible(sbn: StatusBarNotification, skipOngoing: Boolean): Boolean {
        // Never forward our own notifications (feedback loop).
        if (sbn.packageName == context.packageName) return false

        val flags = sbn.notification.flags
        // Group summaries carry no unique content — always drop to avoid duplicates.
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false

        if (skipOngoing) {
            if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
            if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
            when (sbn.notification.category) {
                Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS -> return false
            }
        }
        return true
    }

    override fun resolveChannel(sbn: StatusBarNotification, rankingMap: RankingMap?): ResolvedChannel {
        val channelId = sbn.notification.channelId ?: ""
        var name: String? = null
        var importance: Int? = null
        // Best-effort: the Ranking() public constructor / getChannel() are not available on every
        // build, so guard the whole lookup and fall back to the bare channelId.
        try {
            if (rankingMap != null) {
                val ranking = NotificationListenerService.Ranking()
                if (rankingMap.getRanking(sbn.key, ranking)) {
                    val channel = ranking.channel
                    if (channel != null) {
                        name = channel.name?.toString()?.takeUnless { it.isBlank() }
                        importance = channel.importance
                    }
                }
            }
        } catch (t: Throwable) {
            // Degrade gracefully to channelId with no name/importance.
        }
        return ResolvedChannel(channelId = channelId, name = name, importance = importance)
    }

    override fun resolveConversation(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
    ): ResolvedConversation? {
        val title = resolveConversationTitle(sbn.notification)
        // Key-source precedence: prefer a real conversation shortcut id (stable + framework-blessed);
        // fall back to sbn.tag, then a title-derived key, ONLY for message-style notifications, so the
        // same chat keys consistently across notifications. Some apps (e.g. certain WhatsApp builds)
        // use MessagingStyle with a stable per-chat tag but never publish a shortcut id.
        val conversationId = resolveConversationId(sbn, rankingMap)
            ?: resolveFallbackConversationId(sbn, title)
            ?: return null
        return ResolvedConversation(conversationId = conversationId, title = title)
    }

    /**
     * Fallback conversation id for message-style notifications that carry no shortcut id. Gated on
     * "conversation-like" (MessagingStyle present OR CATEGORY_MESSAGE) to avoid false positives on
     * ordinary notifications. Derives a stable key from `sbn.tag` (preferred) or, failing that, the
     * resolved [title] (`"t:" + title`). Returns null when neither yields a non-blank value.
     */
    private fun resolveFallbackConversationId(sbn: StatusBarNotification, title: String?): String? {
        val notification = sbn.notification
        val conversationLike = notification.category == Notification.CATEGORY_MESSAGE ||
            runCatching {
                NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(notification) != null
            }.getOrDefault(false)
        if (!conversationLike) return null

        sbn.tag?.takeUnless { it.isBlank() }?.let { return it }
        title?.takeUnless { it.isBlank() }?.let { return "t:$it" }
        return null
    }

    /**
     * Best-effort conversation shortcut id, in order of reliability:
     *  1. `Ranking.conversationShortcutInfo.id` (API 30+ — the framework's own conversation identity).
     *  2. `Notification.shortcutId` (API 26+ — set by apps that publish conversation shortcuts).
     *  3. `Ranking.channel.conversationId` (API 30+ — the parent conversation channel's id).
     * Returns null when none resolve (the notification is not a conversation).
     */
    private fun resolveConversationId(sbn: StatusBarNotification, rankingMap: RankingMap?): String? {
        // 1. The framework's own conversation identity (most reliable), API 30+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && rankingMap != null) {
            runCatching {
                val ranking = NotificationListenerService.Ranking()
                if (rankingMap.getRanking(sbn.key, ranking)) {
                    ranking.conversationShortcutInfo?.id
                        ?.takeUnless { it.isBlank() }
                        ?.let { return it }
                }
            }
        }
        // 2. The app-published conversation shortcut id, API 26+.
        runCatching {
            sbn.notification.shortcutId?.takeUnless { it.isBlank() }?.let { return it }
        }
        // 3. The parent conversation channel's id (only set for conversation-specific channels), 30+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && rankingMap != null) {
            runCatching {
                val ranking = NotificationListenerService.Ranking()
                if (rankingMap.getRanking(sbn.key, ranking)) {
                    ranking.channel?.conversationId
                        ?.takeUnless { it.isBlank() }
                        ?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * Best-effort conversation title:
     *  1. `EXTRA_CONVERSATION_TITLE` (group chats).
     *  2. MessagingStyle conversation title.
     *  3. For 1:1 chats, the latest message's sender [Person] name.
     *  4. `EXTRA_TITLE` as a last resort.
     */
    private fun resolveConversationTitle(notification: Notification): String? {
        notification.extras.charSequence(Notification.EXTRA_CONVERSATION_TITLE)?.let { return it }
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()
        style?.conversationTitle?.toString()?.takeUnless { it.isBlank() }?.let { return it }
        style?.messages?.lastOrNull()?.person?.name?.toString()
            ?.takeUnless { it.isBlank() }
            ?.let { return it }
        return notification.extras.charSequence(Notification.EXTRA_TITLE)
    }

    override fun appLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val label = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                .takeUnless { it.isBlank() } ?: packageName
        } catch (t: Throwable) {
            packageName
        }
        labelCache[packageName] = label
        return label
    }

    override fun extractContent(sbn: StatusBarNotification): ExtractedContent {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.charSequence(Notification.EXTRA_TITLE)
        // BIG_TEXT preferred, else TEXT, else the latest MessagingStyle message, else SUB_TEXT.
        val body = extras.charSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.charSequence(Notification.EXTRA_TEXT)
            ?: extractMessagingBody(notification)
            ?: extras.charSequence(Notification.EXTRA_SUB_TEXT)
        return ExtractedContent(title = title, body = body)
    }

    override suspend fun extractImages(
        sbn: StatusBarNotification,
        cacheDir: File,
        includeImages: Boolean,
        includeAvatars: Boolean,
    ): List<PersistedImage> {
        if (!includeImages) return emptyList()
        return withContext(Dispatchers.IO) {
            val notification = sbn.notification
            val extras = notification.extras
            val out = ArrayList<PersistedImage>(2)
            // Primary image (BigPictureStyle) — Bitmap on all versions, Icon on API 31+.
            runCatching {
                persistBitmap(extractBigPicture(extras), cacheDir, OutboxImageKind.BIG_PICTURE)
                    ?.let { out += it }
            }
            // Secondary image (avatar / large icon) — opt-in. Telegram lays every photo out at full
            // bubble width, so a small contact photo is upscaled into a blurry block that dwarfs the
            // message, and when there's no BigPicture it becomes the message's ONLY image. There is no
            // Bot API way to send it smaller or inline, so the only fix is not to send it.
            //
            // Media notifications are the exception: there the large icon is the ALBUM ART, which is
            // wanted whether or not the now-playing control is in use, so it always rides along.
            val isMedia = isMediaNotification(
                category = notification.category,
                template = extras.getString(TEMPLATE_EXTRA),
                hasMediaSession = extras.containsKey(MEDIA_SESSION_EXTRA),
            )
            if (includeAvatars || isMedia) {
                runCatching {
                    persistBitmap(
                        iconToBitmap(notification.getLargeIcon()),
                        cacheDir,
                        OutboxImageKind.LARGE_ICON,
                    )?.let { out += it }
                }
            }
            out
        }
    }

    override fun buildRawNotification(
        sbn: StatusBarNotification,
        channel: ResolvedChannel,
        conversationId: String?,
        appLabel: String,
        content: ExtractedContent,
        imagePaths: List<String>,
    ): RawNotification {
        // Stable idempotency key: notification key + a content fingerprint so an in-place update
        // that changes the text/image count is treated as a new item.
        val fingerprint = buildString {
            append(content.title.orEmpty()).append('\u0001')
            append(content.body.orEmpty()).append('\u0001')
            append(imagePaths.size)
            // For media the content alone is not enough: a track played twice is byte-identical to
            // the first play and would be swallowed as a duplicate. The play salt separates the two
            // while still collapsing the many re-posts that make up a single play.
            mediaPlaySalt(sbn, content)?.let { salt -> append('').append(salt) }
        }
        return RawNotification(
            packageName = sbn.packageName,
            appLabel = appLabel,
            channelId = channel.channelId,
            channelName = channel.name,
            conversationId = conversationId,
            title = content.title,
            body = content.body,
            senderContactUri = extractSenderContactUri(sbn),
            postTime = sbn.postTime,
            userSerial = resolveUserSerial(context, sbn.user),
            imagePaths = imagePaths,
            key = sbn.key,
            dedupeKey = "${sbn.key}:${stableHash(fingerprint)}",
            youtubeChannelId = extractYoutubeChannelId(sbn),
            youtubeVideoId = extractYoutubeVideoId(sbn),
            extractedLinks = extractLinks(sbn),
            actions = extractActions(sbn),
        )
    }

    /**
     * Capture the notification's action buttons as re-fireable metadata (never the `PendingIntent`
     * itself, which cannot be persisted and dies with the process). At action time the live
     * notification is re-resolved by key and the action located again — see [NotificationActionGateway].
     *
     * Records `semanticAction` (identifies Reply / Mark-as-read regardless of label or language) and
     * whether the intent is **immutable**, because a reply can only be injected into a mutable one.
     * Actions without a title or intent are skipped: they can't be rendered or fired.
     * Fully try/caught — this must never break notification capture.
     */
    /**
     * A value that stays constant for one play of a track and changes when a different track starts —
     * null for anything that isn't a media notification.
     *
     * This is what lets a song you replay be forwarded again. Content alone can't: the second play is
     * byte-identical to the first, so the outbox's dedupe index swallows it. `postTime` alone can't
     * either, in the opposite direction — captured dumps show one track re-posting 20 times with 20
     * different postTimes (ten of them within 33 seconds), so keying on it would forward a single play
     * ten times over.
     *
     * So the play is stamped with the `postTime` of the update that *first* showed the track. Every
     * re-post of that same track reuses the stamp and is correctly deduped; a different track (or the
     * same one started again later) gets a new stamp and is forwarded. In-memory only: a process
     * restart costs at most one duplicate message.
     */
    private fun mediaPlaySalt(sbn: StatusBarNotification, content: ExtractedContent): Long? {
        val extras = sbn.notification.extras
        val isMedia = isMediaNotification(
            category = sbn.notification.category,
            template = extras?.getString(TEMPLATE_EXTRA),
            hasMediaSession = extras?.containsKey(MEDIA_SESSION_EXTRA) == true,
        )
        if (!isMedia) return null

        val trackKey = "${content.title.orEmpty()}${content.body.orEmpty()}"
        val existing = lastMediaPlay[sbn.packageName]
        if (existing != null && existing.trackKey == trackKey) return existing.salt

        val play = MediaPlay(trackKey = trackKey, salt = sbn.postTime)
        lastMediaPlay[sbn.packageName] = play
        return play.salt
    }

    override suspend fun extractLargeIcon(sbn: StatusBarNotification, cacheDir: File): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                persistBitmap(
                    iconToBitmap(sbn.notification.getLargeIcon()),
                    cacheDir,
                    OutboxImageKind.LARGE_ICON,
                )?.filePath
            }.getOrNull()
        }

    override fun extractActions(sbn: StatusBarNotification): List<NotificationActionInfo> = try {
        sbn.notification.actions.orEmpty().mapIndexedNotNull { index, action ->
            val title = action.title?.toString()?.takeUnless { it.isBlank() }
            val intent = action.actionIntent
            if (title == null || intent == null) {
                null
            } else {
                NotificationActionInfo(
                    index = index,
                    title = title,
                    semantic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        action.semanticAction
                    } else {
                        0
                    },
                    remoteInput = !action.remoteInputs.isNullOrEmpty(),
                    immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && intent.isImmutable,
                    // An activity intent opens the app on the phone; service/broadcast ones act
                    // silently, which is what makes them genuinely useful remotely.
                    opensApp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && intent.isActivity,
                )
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * The message sender's contact uri for a **1:1** MessagingStyle chat — the latest message's
     * `Person.getUri()` (a `content://com.android.contacts/…` lookup uri for a saved contact). This is
     * the only recovery path for current-WhatsApp `@lid` chats (which hide the phone number). Null for
     * group chats (where the per-message sender isn't the chat) or when no such uri is present. Fully
     * try/caught — it must never break capture; it reads only the uri string, never contacts data.
     */
    private fun extractSenderContactUri(sbn: StatusBarNotification): String? {
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        }.getOrNull() ?: return null
        if (style.isGroupConversation) return null
        return style.messages.asReversed()
            .firstNotNullOfOrNull { it.person?.uri?.takeUnless { uri -> uri.isBlank() } }
    }

    /**
     * Tier-0 link harvest (app-agnostic, always on): flatten every readable text field of the
     * notification into plain strings plus any `URLSpan` urls (link-behind-text), then let the pure
     * [LinkHarvest] keep only the deduped `http`/`https` links. Fully try/caught — harvesting a link
     * must never break notification capture.
     */
    private fun extractLinks(sbn: StatusBarNotification): List<String> = try {
        val notification = sbn.notification
        val extras = notification.extras
        val texts = ArrayList<CharSequence?>()
        val spanUrls = ArrayList<String?>()

        fun add(cs: CharSequence?) {
            if (cs.isNullOrEmpty()) return
            texts += cs
            if (cs is Spanned) {
                runCatching {
                    for (span in cs.getSpans(0, cs.length, URLSpan::class.java)) {
                        spanUrls += span.url
                    }
                }
            }
        }

        add(extras.getCharSequence(Notification.EXTRA_TITLE))
        add(extras.getCharSequence(Notification.EXTRA_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        // InboxStyle text lines — often where the real links hide behind a summary android.text.
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        // Every MessagingStyle message (all of them, not just the latest we forward as the body).
        runCatching {
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
                ?.messages
                ?.forEach { add(it.text) }
        }
        add(notification.tickerText)

        LinkHarvest.harvest(texts, spanUrls)
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Best-effort YouTube channel id (`UC…`) for a supported YouTube app, from `chime.slot_key`
     * (preferred) or `sbn.tag`. Fully try/caught: extraction must never break notification capture.
     */
    private fun extractYoutubeChannelId(sbn: StatusBarNotification): String? = try {
        if (sbn.packageName in YouTube.PACKAGES) {
            YouTube.extractChannelId(
                chimeSlotKey = sbn.notification.extras.getString("chime.slot_key"),
                tag = sbn.tag,
            )
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Best-effort YouTube **video** id for a supported YouTube app. Live-stream/premiere notifications
     * key themselves by the video id instead of the channel id, which lets the magic link be built
     * directly with no feed/search lookup. Null for ordinary uploads (they carry a `UC…` channel id).
     * Fully try/caught: extraction must never break notification capture.
     */
    private fun extractYoutubeVideoId(sbn: StatusBarNotification): String? = try {
        if (sbn.packageName in YouTube.PACKAGES) {
            YouTube.extractVideoId(
                chimeSlotKey = sbn.notification.extras.getString("chime.slot_key"),
                tag = sbn.tag,
            )
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }

    // --- content helpers -----------------------------------------------------------------------

    private fun extractMessagingBody(notification: Notification): String? = try {
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
        val last = style?.messages?.lastOrNull()
        val text = last?.text?.toString()?.takeUnless { it.isBlank() }
        when {
            text == null -> null
            else -> {
                val sender = last.person?.name?.toString()?.takeUnless { it.isBlank() }
                if (sender != null) "$sender: $text" else text
            }
        }
    } catch (t: Throwable) {
        null
    }

    // --- image helpers -------------------------------------------------------------------------

    private fun extractBigPicture(extras: Bundle): Bitmap? {
        extras.parcelableCompat<Bitmap>(Notification.EXTRA_PICTURE)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return iconToBitmap(extras.parcelableCompat<Icon>(Notification.EXTRA_PICTURE_ICON))
        }
        return null
    }

    private fun iconToBitmap(icon: Icon?): Bitmap? {
        if (icon == null) return null
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            drawableToBitmap(drawable)
        } catch (t: Throwable) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap
            if (bmp != null && !bmp.isRecycled) return bmp
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE
        return try {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Copy hardware bitmaps to ARGB_8888, downscale to [MAX_EDGE], JPEG-compress, and write a
     * uniquely-named file under [cacheDir]. Never recycles [source] (may be system-owned).
     */
    private fun persistBitmap(source: Bitmap?, cacheDir: File, kind: OutboxImageKind): PersistedImage? {
        if (source == null || source.isRecycled) return null
        var software: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            software = toSoftwareArgb(source)
            if (software.isRecycled) return null
            scaled = downscale(software, MAX_EDGE)
            val dir = File(cacheDir, IMAGE_SUBDIR).apply { mkdirs() }
            val file = File(dir, "img_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (!file.exists() || file.length() == 0L) {
                file.delete()
                null
            } else {
                PersistedImage(
                    filePath = file.absolutePath,
                    mime = MIME_JPEG,
                    sizeBytes = file.length(),
                    kind = kind,
                )
            }
        } catch (t: Throwable) {
            null
        } finally {
            // Recycle only the intermediates we allocated — never the (possibly system-owned) source.
            if (scaled != null && scaled !== software && scaled !== source && !scaled.isRecycled) {
                scaled.recycle()
            }
            if (software != null && software !== source && !software.isRecycled) {
                software.recycle()
            }
        }
    }

    private fun toSoftwareArgb(src: Bitmap): Bitmap {
        val config = src.config
        val needsCopy = config == null ||
            config == Bitmap.Config.HARDWARE ||
            config != Bitmap.Config.ARGB_8888
        return if (needsCopy) src.copy(Bitmap.Config.ARGB_8888, false) ?: src else src
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge || longest <= 0) return src
        val scale = maxEdge.toFloat() / longest.toFloat()
        val width = (src.width * scale).toInt().coerceAtLeast(1)
        val height = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, width, height, true)
    }

    // --- misc helpers --------------------------------------------------------------------------

    private fun Bundle.charSequence(key: String): String? =
        getCharSequence(key)?.toString()?.trim()?.takeUnless { it.isEmpty() }

    @Suppress("DEPRECATION")
    private inline fun <reified T> Bundle.parcelableCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            getParcelable(key) as? T
        }

    /** Deterministic, run-stable 64-bit hash (FNV-style) rendered as hex, for the dedupe key. */
    private fun stableHash(value: String): String {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325 FNV offset basis
        for (ch in value) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L // FNV prime
        }
        return java.lang.Long.toHexString(hash)
    }

    private companion object {
        /** Hard caps on the in-process caches, so neither can grow without bound. */
        const val MAX_CACHED_LABELS = 128
        const val MAX_TRACKED_MEDIA_APPS = 16

        /** `Notification.EXTRA_TEMPLATE` / `EXTRA_MEDIA_SESSION` — identify a media notification. */
        const val TEMPLATE_EXTRA = "android.template"
        const val MEDIA_SESSION_EXTRA = "android.mediaSession"

        const val MAX_EDGE = 2048
        const val JPEG_QUALITY = 80
        const val IMAGE_SUBDIR = "notif_images"
        const val DEFAULT_ICON_SIZE = 128
        const val MIME_JPEG = "image/jpeg"
    }
}
