package space.linuxct.teleforward.service

import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import space.linuxct.teleforward.domain.NotificationActionInfo
import space.linuxct.teleforward.data.db.entity.OutboxImageKind
import space.linuxct.teleforward.domain.RawNotification
import java.io.File

/**
 * Channel identity resolved from a notification's RankingMap (best-effort).
 */
data class ResolvedChannel(
    val channelId: String,
    val name: String?,
    val importance: Int?,
)

/**
 * Conversation identity resolved from a notification (conversation shortcut id + best-effort title).
 * Non-null only when the notification is a conversation (e.g. a specific WhatsApp chat).
 */
data class ResolvedConversation(
    val conversationId: String,
    val title: String?,
)

/**
 * Text content extracted from a notification (BIG_TEXT preferred over TEXT; MessagingStyle body
 * where applicable).
 */
data class ExtractedContent(
    val title: String?,
    val body: String?,
)

/**
 * An image extracted from a notification and persisted to app-private cache.
 */
data class PersistedImage(
    val filePath: String,
    val mime: String,
    val sizeBytes: Long,
    val kind: OutboxImageKind,
)

/**
 * Converts an Android [StatusBarNotification] into the app's Android-free [RawNotification], and
 * extracts/persists images. Composed of granular steps so the listener can short-circuit (e.g.
 * skip content/image extraction when the filter rejects — privacy + perf).
 */
interface NotificationMapper {

    /** Cheap eligibility check (own package, ongoing/group-summary/foreground-service, etc.). */
    fun isEligible(sbn: StatusBarNotification, skipOngoing: Boolean): Boolean

    /** Resolve (channelId, name, importance) from the ranking map, with a "Default" fallback. */
    fun resolveChannel(sbn: StatusBarNotification, rankingMap: RankingMap?): ResolvedChannel

    /**
     * Resolve the conversation identity (shortcut id + best-effort title) for [sbn], or null when the
     * notification is not a conversation. Guards conversation APIs by API level (they are 30+).
     */
    fun resolveConversation(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
    ): ResolvedConversation?

    /** Human-readable app label for [packageName] (cached from PackageManager). */
    fun appLabel(packageName: String): String

    /** Extract title/body (EXTRA_BIG_TEXT preferred, MessagingStyle where present). */
    fun extractContent(sbn: StatusBarNotification): ExtractedContent

    /**
     * The notification's action buttons as re-fireable metadata (never the `PendingIntent`s, which
     * can't be persisted). Empty when it exposes none. Used both for the forwarded message's buttons
     * and for the now-playing control's transport buttons.
     */
    fun extractActions(sbn: StatusBarNotification): List<NotificationActionInfo>

    /**
     * Persist the notification's large icon and return its file path, or null if it has none.
     *
     * For a media notification the large icon *is* the album art, so this is deliberately unconditional
     * — unlike the avatar handling in [extractImages], which is opt-in because a contact photo merely
     * dwarfs the message it belongs to. Here the artwork is the point of the control.
     */
    suspend fun extractLargeIcon(sbn: StatusBarNotification, cacheDir: File): String?

    /**
     * Extract images (EXTRA_PICTURE + large icon), copy hardware bitmaps to ARGB_8888, downscale,
     * JPEG-compress, and persist to [cacheDir]. Returns the persisted files.
     */
    suspend fun extractImages(
        sbn: StatusBarNotification,
        cacheDir: File,
        includeImages: Boolean,
        /**
         * Whether to also persist the large icon (contact photo / app logo). Off by default: Telegram
         * renders any photo at full bubble width, so an avatar dominates the message it belongs to.
         */
        includeAvatars: Boolean = false,
    ): List<PersistedImage>

    /** Assemble the final [RawNotification] (including dedupeKey) from the parts above. */
    fun buildRawNotification(
        sbn: StatusBarNotification,
        channel: ResolvedChannel,
        conversationId: String?,
        appLabel: String,
        content: ExtractedContent,
        imagePaths: List<String>,
    ): RawNotification
}
