package space.linuxct.teleforward.data.link

/** Which service a package's magic-link reconstruction targets. */
enum class MagicLinkKind {
    /** YouTube upload notifications → `watch?v=` url (via the uploads feed / search). */
    YOUTUBE,

    /** Apple Music now-playing notifications → `music.apple.com` song url (via the iTunes Search API). */
    APPLE_MUSIC,

    /** WhatsApp chat notifications → a `web.whatsapp.com/send/` url (from a phone JID/title/contact). */
    WHATSAPP,

    /**
     * Discord **direct-message** notifications → a `discord.com/channels/@me/…` url (from the
     * conversation shortcut, which is the channel id, plus the `latestMessageId` extra). Server
     * channels are deliberately not linkable — their url needs a guild id no readable field carries.
     */
    DISCORD,

    /**
     * Telegram **group / supergroup** notifications → a `t.me/c/<channelId>/<messageId>` url (from the
     * Wear `dismissalId`). Private and secret chats are not linkable — Telegram exposes no shareable
     * per-message link for them.
     */
    TELEGRAM,

    /**
     * GitHub notifications → a `github.com/<owner>/<repo>/issues/<n>` url, parsed straight out of the
     * readable `owner/repo#123` reference. No hidden id and no network: GitHub redirects `/issues/<n>`
     * to `/pull/<n>` when the number is a pull request.
     */
    GITHUB,

    /**
     * Signal chat notifications → a `signal.me/#p/+<e164>` url. Signal's own ids are device-local
     * integers, so the only recoverable identity is the sender's saved contact (opt-in READ_CONTACTS) —
     * the same fallback WhatsApp uses for `@lid` chats.
     */
    SIGNAL,
}

/**
 * The single source of truth for magic-link support: classify a [packageName] into its service, or
 * null when magic-link reconstruction isn't supported for it. Used to gate resolution, diagnostics,
 * and the per-app toggle card so they never drift apart.
 */
fun magicLinkKind(packageName: String): MagicLinkKind? = when (packageName) {
    in YouTube.PACKAGES -> MagicLinkKind.YOUTUBE
    in AppleMusic.PACKAGES -> MagicLinkKind.APPLE_MUSIC
    in WhatsApp.PACKAGES -> MagicLinkKind.WHATSAPP
    in Discord.PACKAGES -> MagicLinkKind.DISCORD
    in Telegram.PACKAGES -> MagicLinkKind.TELEGRAM
    in GitHub.PACKAGES -> MagicLinkKind.GITHUB
    in Signal.PACKAGES -> MagicLinkKind.SIGNAL
    else -> null
}

/**
 * Does this service's reconstruction depend on the opt-in READ_CONTACTS resolver?
 *
 * WhatsApp (`@lid` chats) and Signal (whose own ids are device-local integers) both hide the peer's
 * phone number, so their only recovery is the sender's saved contact. Kept here beside [magicLinkKind]
 * so the settings UI that offers the Contacts affordance can't drift from the resolvers that need it.
 */
fun usesContactsResolution(kind: MagicLinkKind?): Boolean =
    kind == MagicLinkKind.WHATSAPP || kind == MagicLinkKind.SIGNAL
