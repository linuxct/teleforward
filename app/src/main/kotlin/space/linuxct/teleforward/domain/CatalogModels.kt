package space.linuxct.teleforward.domain

/**
 * An app shown in the app-selection list. The catalog is the union of apps discovered from
 * observed notifications ([isSeen] == true) and, where available, installed apps from
 * PackageManager.
 *
 * @property packageName app package id.
 * @property label human-readable app name.
 * @property isSeen true if at least one notification from this app has been observed.
 * @property lastSeen epoch millis of the most recent observed notification, or null if never seen.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isSeen: Boolean,
    val lastSeen: Long?,
)

/**
 * A notification channel discovered from an observed notification (the only way channels can be
 * enumerated — see plan). Unknown/absent channel ids surface as a synthetic "Default" channel.
 *
 * @property packageName owning app package.
 * @property channelId channel id.
 * @property name channel display name resolved from the RankingMap, or null.
 * @property importance NotificationManager importance constant, or null if unknown.
 * @property userSerial serial of the user/profile that posted it.
 * @property firstSeen epoch millis first observed.
 * @property lastSeen epoch millis most recently observed.
 */
data class SeenChannel(
    val packageName: String,
    val channelId: String,
    val name: String?,
    val importance: Int?,
    val userSerial: Long,
    val firstSeen: Long,
    val lastSeen: Long,
)

/**
 * A conversation (individual chat, e.g. a single WhatsApp thread) discovered from an observed
 * notification. Apps that use conversation shortcuts put every chat on a single channel and
 * distinguish them by [conversationId]; this catalog is the only way to enumerate them (a listener
 * cannot query conversations up front).
 *
 * @property packageName owning app package.
 * @property channelId the channel the conversation lives on (its parent channel).
 * @property conversationId conversation shortcut id (stable per-chat identity).
 * @property title resolved conversation display title (chat/sender name), or null if unavailable.
 * @property userSerial serial of the user/profile that posted it.
 * @property firstSeen epoch millis first observed.
 * @property lastSeen epoch millis most recently observed.
 */
data class SeenConversation(
    val packageName: String,
    val channelId: String,
    val conversationId: String,
    val title: String?,
    val userSerial: Long,
    val firstSeen: Long,
    val lastSeen: Long,
)
