package space.linuxct.teleforward.data.link

/**
 * Telegram constants and the pure helpers used by the "magic link" reconstruction.
 *
 * Telegram never puts a chat id in the obvious places — it posts per-dialog notifications with **no
 * tag**, folds the dialog id into a lossy 32-bit notification id, and uses a constant group key. But it
 * *does* leak the peer AND the message in one readable field: the **Wear `dismissalId`**, which
 * `NotificationsController` builds as
 *
 *  - `tgchat<rawChannelId>_<messageId>` — a group / supergroup / channel, or
 *  - `tguser<userId>_<messageId>` — a 1:1 chat, or
 *  - `tgenc<encryptedChatId>_<messageId>` — a secret chat.
 *
 * AndroidX writes it into `extras["android.wearable.EXTENSIONS"]["dismissalId"]`, which a
 * NotificationListenerService can read.
 *
 * **Only the `tgchat` form is linkable**, as `https://t.me/c/<rawChannelId>/<messageId>` — Telegram's
 * own private message-link format. The other two deliberately yield nothing:
 *  - `tguser` — Telegram has **no shareable per-message link for private chats**, and no `t.me` form
 *    addressing a user by numeric id (`tg://openmessage` is device-local).
 *  - `tgenc` — a secret chat must never be linked.
 *
 * Two honest caveats. A `t.me/c/` link opens only for **members** of that chat and has no web preview,
 * so it is a link *for you*, not for a stranger. And a legacy *basic* group also produces
 * `tgchat<chatId>_…` while having no valid `c/` link; that is indistinguishable from a supergroup here,
 * but nearly all groups are supergroups, so it is a rare dead link rather than a common failure.
 *
 * All helpers are pure and unit-testable.
 */
object Telegram {

    /** The official app plus the common forks, which carry byte-identical notification code. */
    val PACKAGES: Set<String> = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "tw.nekomimi.nekogram",
        "nekox.messenger",
        "xyz.nextalone.nagram",
    )

    const val SERVICE = "telegram"

    /** The AndroidX Wear extras bundle, and the key holding the dismissal id inside it. */
    const val WEARABLE_EXTENSIONS_EXTRA = "android.wearable.EXTENSIONS"
    const val DISMISSAL_ID_KEY = "dismissalId"

    private const val CHAT_PREFIX = "tgchat"
    private const val USER_PREFIX = "tguser"
    private const val ENCRYPTED_PREFIX = "tgenc"

    /** A Telegram numeric id (dialog / message). Bounded so junk can't be mistaken for an id. */
    private val ID_REGEX = Regex("\\d{1,19}")

    /**
     * Pure, PII-free classification of a dismissal id for the diagnostics trace: which *kind* of peer it
     * named, never the id itself. `"chat"` is the only linkable kind.
     */
    fun peerKind(dismissalId: String?): String {
        val value = dismissalId?.trim().orEmpty()
        return when {
            value.isEmpty() -> "none"
            value.startsWith(CHAT_PREFIX) -> "chat"
            value.startsWith(USER_PREFIX) -> "user"
            value.startsWith(ENCRYPTED_PREFIX) -> "encrypted"
            else -> "unknown"
        }
    }

    /**
     * Pure: the `t.me/c/<channelId>/<messageId>` url for a `tgchat…` dismissal id, or null for every
     * other shape (`tguser`, `tgenc`, malformed, missing) — so a private or secret chat can never be
     * turned into a link, and a stray string can never be pasted into a `t.me` url.
     */
    fun messageUrl(dismissalId: String?): String? {
        val value = dismissalId?.trim().orEmpty()
        if (!value.startsWith(CHAT_PREFIX)) return null
        val rest = value.removePrefix(CHAT_PREFIX)
        val separator = rest.indexOf('_')
        if (separator <= 0) return null
        val channelId = rest.substring(0, separator)
        val messageId = rest.substring(separator + 1)
        if (!ID_REGEX.matches(channelId) || !ID_REGEX.matches(messageId)) return null
        return "https://t.me/c/$channelId/$messageId"
    }
}
