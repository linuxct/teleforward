package space.linuxct.teleforward.domain

/**
 * Immutable, Android-free snapshot of a single observed notification, produced by the
 * notification listener / [space.linuxct.teleforward.service.NotificationMapper] and consumed by
 * the [FilterEngine] and the intake pipeline.
 *
 * Every incoming notification on API 26+ belongs to a channel, so [channelId] is non-null; the
 * *rule* scope, by contrast, uses a null channelId to mean "whole app" (see [SelectionRule]).
 *
 * @property packageName source app package (e.g. `com.whatsapp`).
 * @property appLabel human-readable app name resolved from PackageManager (falls back to packageName).
 * @property channelId the notification channel id (always present on API 26+).
 * @property channelName resolved channel display name from the RankingMap, or null if unavailable.
 * @property conversationId the conversation shortcut id (WhatsApp/Messages-style per-chat identity),
 *   or null when this notification is not a conversation. Apps like WhatsApp put every chat on one
 *   channel ("Messages") and distinguish chats by this id.
 * @property title notification title (EXTRA_TITLE), or null.
 * @property body notification body (EXTRA_BIG_TEXT preferred, else EXTRA_TEXT), or null.
 * @property senderContactUri the message sender's contact uri (`content://com.android.contacts/…`)
 *   for a 1:1 MessagingStyle conversation, or null. Backs the opt-in WhatsApp contact→phone resolver.
 * @property postTime StatusBarNotification.postTime (epoch millis).
 * @property userSerial serial number of the posting user/profile (multi-user / work profile aware).
 * @property imagePaths absolute file paths of images already persisted to app-private cache.
 * @property key StatusBarNotification.key (stable identity of the posted notification).
 * @property dedupeKey local idempotency key: `key + hash(title + body + imageCount)`.
 * @property youtubeChannelId best-effort YouTube channel id (`UC…`) extracted from a supported
 *   YouTube app's notification, used later for "magic link" reconstruction; null otherwise.
 * @property youtubeVideoId best-effort YouTube video id (11 chars) — live-stream/premiere
 *   notifications key themselves by video id, so the watch url needs no feed lookup; null otherwise.
 * @property actions the notification's action buttons as re-fireable metadata (Reply / Mark as read
 *   …), used to offer remote action buttons on the forwarded message. Empty when it has none.
 * @property extractedLinks Tier-0 harvest of every real `http`/`https` link found anywhere in the
 *   notification's readable content (text lines, all MessagingStyle messages, URLSpans, …), so a
 *   link is forwarded even when it lives in a field we don't otherwise surface. Empty when none.
 */
data class RawNotification(
    val packageName: String,
    val appLabel: String,
    val channelId: String,
    val channelName: String?,
    val conversationId: String?,
    val title: String?,
    val body: String?,
    val senderContactUri: String? = null,
    val postTime: Long,
    val userSerial: Long,
    val imagePaths: List<String>,
    val key: String,
    val dedupeKey: String,
    val youtubeChannelId: String? = null,
    val youtubeVideoId: String? = null,
    val extractedLinks: List<String> = emptyList(),
    val actions: List<NotificationActionInfo> = emptyList(),
    /**
     * True when this was a media/transport notification. Captured here rather than re-derived at
     * delivery time, where the live notification may already be gone and the answer silently wrong.
     */
    val isMedia: Boolean = false,
)
