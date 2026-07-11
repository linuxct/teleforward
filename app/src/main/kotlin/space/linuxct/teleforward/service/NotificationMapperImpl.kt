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
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.linuxct.teleforward.data.db.entity.OutboxImageKind
import space.linuxct.teleforward.domain.RawNotification
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

    /** Cached PackageManager labels; the listener resolves the same package on every notification. */
    private val labelCache = ConcurrentHashMap<String, String>()

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
            // Secondary image (avatar / large icon).
            runCatching {
                persistBitmap(iconToBitmap(notification.getLargeIcon()), cacheDir, OutboxImageKind.LARGE_ICON)
                    ?.let { out += it }
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
        }
        return RawNotification(
            packageName = sbn.packageName,
            appLabel = appLabel,
            channelId = channel.channelId,
            channelName = channel.name,
            conversationId = conversationId,
            title = content.title,
            body = content.body,
            postTime = sbn.postTime,
            userSerial = resolveUserSerial(context, sbn.user),
            imagePaths = imagePaths,
            key = sbn.key,
            dedupeKey = "${sbn.key}:${stableHash(fingerprint)}",
        )
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
        const val MAX_EDGE = 2048
        const val JPEG_QUALITY = 80
        const val IMAGE_SUBDIR = "notif_images"
        const val DEFAULT_ICON_SIZE = 128
        const val MIME_JPEG = "image/jpeg"
    }
}
