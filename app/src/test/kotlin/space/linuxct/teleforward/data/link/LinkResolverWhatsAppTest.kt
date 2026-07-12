package space.linuxct.teleforward.data.link

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxStatus

/**
 * Tests the WhatsApp branch of [LinkResolverImpl.resolve]: JID / phone-title (permission-free) and
 * the opt-in contacts fallback (via a fake [ContactPhoneResolver]). No Android/network involved.
 *
 * All identifiers below are synthetic placeholders — not real phone numbers, LIDs, or contacts.
 */
class LinkResolverWhatsAppTest {

    private class FakePhoneResolver(private val phone: String?) : ContactPhoneResolver {
        var lastUri: String? = null
        override fun resolve(contactLookupUri: String): String? {
            lastUri = contactLookupUri
            return phone
        }
    }

    private fun waItem(
        conversationId: String? = null,
        title: String? = null,
        senderContactUri: String? = null,
    ) = OutboxEntity(
        dedupeKey = "k",
        packageName = "com.whatsapp",
        channelId = "msg",
        appLabel = "WhatsApp",
        channelName = null,
        title = title,
        body = "hi",
        conversationId = conversationId,
        senderContactUri = senderContactUri,
        postTime = 0L,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastError = null,
        createdAt = 0L,
    )

    @Test
    fun individualJidResolvesToSendUrl() = runTest {
        val result = LinkResolverImpl().resolve(
            waItem(conversationId = "12345678901@s.whatsapp.net"),
            disabledPackages = emptySet(),
        )
        assertEquals(
            "https://web.whatsapp.com/send/?phone=12345678901&text&type=phone_number&app_absent=0",
            result.url,
        )
        assertEquals(MagicLinkOutcome.MATCHED, result.trace.outcome)
        assertEquals(WhatsApp.SOURCE_JID, result.trace.source)
    }

    @Test
    fun unsavedPhoneTitleResolvesToSendUrl() = runTest {
        val result = LinkResolverImpl().resolve(
            waItem(conversationId = "11112222333344@lid", title = "+12 345 678 901"),
            disabledPackages = emptySet(),
        )
        assertEquals(
            "https://web.whatsapp.com/send/?phone=12345678901&text&type=phone_number&app_absent=0",
            result.url,
        )
        assertEquals(WhatsApp.SOURCE_TITLE, result.trace.source)
    }

    @Test
    fun lidWithGrantedContactsResolvesViaLookup() = runTest {
        val fake = FakePhoneResolver("99988877766")
        val result = LinkResolverImpl(fake).resolve(
            waItem(
                conversationId = "11112222333344@lid",
                title = "Contact Name",
                senderContactUri = "content://com.android.contacts/contacts/lookup/abc/1012",
            ),
            disabledPackages = emptySet(),
        )
        assertEquals(
            "https://web.whatsapp.com/send/?phone=99988877766&text&type=phone_number&app_absent=0",
            result.url,
        )
        assertEquals(WhatsApp.SOURCE_CONTACTS, result.trace.source)
        assertEquals("content://com.android.contacts/contacts/lookup/abc/1012", fake.lastUri)
    }

    @Test
    fun lidWithoutContactsPermissionYieldsNoLink() = runTest {
        // Default resolver is the no-op (no permission / not opted in): saved-contact LID → no link.
        val result = LinkResolverImpl().resolve(
            waItem(
                conversationId = "11112222333344@lid",
                title = "Contact Name",
                senderContactUri = "content://com.android.contacts/contacts/lookup/abc/1012",
            ),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
        assertEquals(WhatsApp.SERVICE, result.trace.service)
    }

    @Test
    fun disabledPackageSkips() = runTest {
        val result = LinkResolverImpl().resolve(
            waItem(conversationId = "12345678901@s.whatsapp.net"),
            disabledPackages = setOf("com.whatsapp"),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.SKIPPED_DISABLED, result.trace.outcome)
    }
}
