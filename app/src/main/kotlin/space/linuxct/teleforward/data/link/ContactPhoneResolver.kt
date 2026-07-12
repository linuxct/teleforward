package space.linuxct.teleforward.data.link

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the WhatsApp peer's phone number from the sender's saved-contact uri — the ONLY recovery
 * for current-WhatsApp chats, whose notifications carry a privacy `@lid` (no phone) but do carry the
 * matched contact's `content://com.android.contacts/…` lookup uri on the message [android.app.Person].
 *
 * Strictly opt-in: every call is a no-op returning null unless the user has granted `READ_CONTACTS`
 * (checked live per call, so a later revoke silently disables it). Best-effort — any failure yields
 * null and the item forwards without a `Link:` line.
 */
interface ContactPhoneResolver {
    /** E.164 digits (no `+`) for the contact behind [contactLookupUri], or null (no permission / none). */
    fun resolve(contactLookupUri: String): String?
}

/** Default used when nothing is injected (unit tests) — never resolves. */
object NoopContactPhoneResolver : ContactPhoneResolver {
    override fun resolve(contactLookupUri: String): String? = null
}

@Singleton
class ContactPhoneResolverImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContactPhoneResolver {

    override fun resolve(contactLookupUri: String): String? {
        if (!hasContactsPermission()) return null
        return runCatching { query(contactLookupUri) }.getOrNull()
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Resolve [contactLookupUri] to its aggregated contact, then read that contact's phone numbers,
     * preferring a MOBILE number and the platform-normalized (`+e164`) form. Returns the first number
     * that normalizes to E.164 digits, or null.
     */
    private fun query(contactLookupUri: String): String? {
        val resolver = context.contentResolver
        val contactId = lookupContactId(contactLookupUri) ?: return null

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val normalizedIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
            val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

            var firstMatch: String? = null
            while (cursor.moveToNext()) {
                val raw = numberIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
                val normalized = normalizedIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
                val phone = WhatsApp.phoneFromContactNumber(normalized, raw) ?: continue
                val isMobile = typeIdx >= 0 &&
                    cursor.getInt(typeIdx) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                // Prefer a mobile number (the WhatsApp one); else remember the first usable number.
                if (isMobile) return phone
                if (firstMatch == null) firstMatch = phone
            }
            return firstMatch
        }
        return null
    }

    /** The aggregated contact `_ID` behind a lookup uri, or null if it can't be resolved. */
    private fun lookupContactId(contactLookupUri: String): Long? {
        val uri = runCatching { Uri.parse(contactLookupUri) }.getOrNull() ?: return null
        val contactUri = runCatching {
            ContactsContract.Contacts.lookupContact(context.contentResolver, uri)
        }.getOrNull() ?: uri
        return context.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts._ID),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }
}
