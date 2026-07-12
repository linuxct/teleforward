package space.linuxct.teleforward.data.link

/** Which service a package's magic-link reconstruction targets. */
enum class MagicLinkKind {
    /** YouTube upload notifications → `watch?v=` url (via the uploads feed / search). */
    YOUTUBE,

    /** Apple Music now-playing notifications → `music.apple.com` song url (via the iTunes Search API). */
    APPLE_MUSIC,

    /** WhatsApp chat notifications → a `web.whatsapp.com/send/` url (from a phone JID/title/contact). */
    WHATSAPP,
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
    else -> null
}
