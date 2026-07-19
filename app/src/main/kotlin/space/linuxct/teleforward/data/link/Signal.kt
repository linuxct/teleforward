package space.linuxct.teleforward.data.link

/**
 * Signal constants and the pure helpers used by the "magic link" reconstruction.
 *
 * Signal is the strictest case of all. Every identifier it puts in a notification — the `shortcutId`,
 * the `locusId`, and the `Person.key` — is its internal **`RecipientId`**, a serialized SQLite row
 * integer. It is device-local, carries no phone number, no ACI and no username, and is not convertible
 * to any url by anyone (including Signal) without that device's database. Its notification `id` is
 * likewise `50000 + threadId`, and it sets no tag at all. So the usual "find the hidden id" trick has
 * nothing to find.
 *
 * The one recoverable identity is the same one WhatsApp falls back to: Signal sets **`Person.uri`** to
 * the sender's Android contact lookup uri (`content://com.android.contacts/…`) — which the app already
 * captures as `senderContactUri` and already resolves to a phone number through
 * [ContactPhoneResolver] + the opt-in READ_CONTACTS grant. From a phone number, [chatUrl] builds
 * Signal's own click-to-chat link.
 *
 * That path is deliberately narrow: Signal only populates `Person.uri` when the "show contact"
 * notification-privacy setting is on AND the peer is a saved system contact AND contacts permission is
 * granted. A message from an unsaved number, or with notification privacy tightened, is simply not
 * linkable — which is the correct outcome, not a bug.
 *
 * All helpers are pure and unit-testable.
 */
object Signal {

    /** The Signal app package. */
    val PACKAGES: Set<String> = setOf(
        "org.thoughtcrime.securesms",
    )

    const val SERVICE = "signal"

    /** Source that produced the phone, recorded in the trace (never the number itself — that's PII). */
    const val SOURCE_CONTACTS = "contacts"
    const val SOURCE_TITLE = "title"

    /**
     * Signal's click-to-chat url for [e164Digits] (international digits, no `+` — this adds it).
     * The `#p/` fragment is resolved client-side by signal.me / the app.
     */
    fun chatUrl(e164Digits: String): String = "https://signal.me/#p/+$e164Digits"
}
