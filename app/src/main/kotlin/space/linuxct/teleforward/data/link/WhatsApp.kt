package space.linuxct.teleforward.data.link

/**
 * WhatsApp constants and the pure phone-extraction helpers used by the "magic link" reconstruction.
 *
 * WhatsApp Web/desktop can only *open a chat* by phone number — via `web.whatsapp.com/send/`, which
 * opens the chat directly (unlike `wa.me`, which shows a "Continue to Chat" interstitial first);
 * there is no URL that addresses a chat by its internal id. So the whole feature is "find the peer's
 * phone number, build the send url" (see [chatUrl]). A notification
 * exposes an identity in one of three shapes, in decreasing convenience:
 *
 *  1. A **phone JID** `"<e164digits>@s.whatsapp.net"` (older WhatsApp) — the phone is right there.
 *  2. A **phone-shaped title** — an *unsaved* contact is shown as its raw number ("+34 6…").
 *  3. A **LID** `"<opaque>@lid"` (current WhatsApp) — a privacy id that deliberately hides the
 *     phone; it is NOT derivable to a number. The only recovery is resolving the sender's saved
 *     contact (a `content://com.android.contacts/…` uri) via READ_CONTACTS — done elsewhere
 *     ([ContactPhoneResolver]); this object just normalizes the number that lookup returns.
 *
 * Groups (`@g.us`) are never linkable (no public URL). All helpers are pure and unit-testable.
 */
object WhatsApp {

    /** Known WhatsApp app packages (consumer + business). */
    val PACKAGES: Set<String> = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
    )

    const val SERVICE = "whatsapp"

    /** Source that produced the phone, recorded in the trace (never the number itself — that's PII). */
    const val SOURCE_JID = "jid"
    const val SOURCE_TITLE = "title"
    const val SOURCE_CONTACTS = "contacts"

    /**
     * The click-to-chat url that opens [e164Digits] (international, no `+`) **directly** in WhatsApp
     * Web — `web.whatsapp.com/send/` jumps straight into the chat, unlike `wa.me`'s interstitial.
     */
    fun chatUrl(e164Digits: String): String =
        "https://web.whatsapp.com/send/?phone=$e164Digits&text&type=phone_number&app_absent=0"

    /**
     * Pure: the E.164 digits (no `+`) from a WhatsApp identifier IF it is an individual phone-JID
     * (`<digits>@s.whatsapp.net` / `<digits>@c.us`, where the local part is already E.164 without the
     * `+`). Returns null for LIDs (`@lid`), groups (`@g.us`), or anything not phone-shaped.
     */
    fun phoneFromJid(id: String?): String? {
        val at = id?.indexOf('@') ?: return null
        if (at <= 0) return null
        when (id.substring(at + 1)) {
            "s.whatsapp.net", "c.us" -> {}
            else -> return null
        }
        return sanitize(id.substring(0, at))
    }

    /**
     * Pure: the E.164 digits from a phone-shaped display [title] (an unsaved contact shows its raw
     * number). Requires a leading `+` so a saved contact NAME is never mistaken for a number.
     */
    fun phoneFromTitle(title: String?): String? {
        val t = title?.trim() ?: return null
        if (!t.startsWith("+")) return null
        return sanitize(t)
    }

    /**
     * Pure: the E.164 digits from a contact number. Prefers the platform-normalized value
     * (`Phone.NORMALIZED_NUMBER`, already `+<e164>`); falls back to a raw number only when it is
     * itself explicitly international (leading `+`), so we never guess a country code wrongly.
     */
    fun phoneFromContactNumber(normalizedNumber: String?, rawNumber: String?): String? {
        normalizedNumber?.trim()?.let { if (it.startsWith("+")) return sanitize(it) }
        val raw = rawNumber?.trim() ?: return null
        if (!raw.startsWith("+")) return null
        return sanitize(raw)
    }

    /** Keep digits only; accept as a phone only within the E.164 length range. */
    private fun sanitize(value: String): String? {
        val digits = value.filter { it.isDigit() }
        return digits.takeIf { it.length in PHONE_MIN_DIGITS..PHONE_MAX_DIGITS }
    }

    /** E.164 subscriber numbers run up to 15 digits; the floor guards against junk short matches. */
    private const val PHONE_MIN_DIGITS = 8
    private const val PHONE_MAX_DIGITS = 15
}
