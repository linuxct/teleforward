package space.linuxct.teleforward.data.link

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxStatus

/**
 * Tests the Signal branch of [LinkResolverImpl.resolve]. Signal publishes no recoverable id of its own
 * (its shortcut / locus / Person.key are all a device-local `RecipientId` integer), so the only paths
 * are a phone-shaped title and the opt-in contacts resolver — and a message from an unsaved contact is
 * correctly not linkable. No Android, no network.
 *
 * All numbers below are synthetic placeholders.
 */
class LinkResolverSignalTest {

    private class FakePhoneResolver(private val phone: String?) : ContactPhoneResolver {
        override fun resolve(contactLookupUri: String): String? = phone
    }

    private fun signalItem(
        title: String? = null,
        senderContactUri: String? = null,
    ) = OutboxEntity(
        dedupeKey = "k",
        packageName = "org.thoughtcrime.securesms",
        channelId = "messages",
        appLabel = "Signal",
        channelName = null,
        title = title,
        body = "hi",
        senderContactUri = senderContactUri,
        postTime = 0L,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastError = null,
        createdAt = 0L,
    )

    @Test
    fun savedContactResolvesToSignalMeUrl() = runTest {
        val resolver = LinkResolverImpl(FakePhoneResolver("34600112233"))
        val result = resolver.resolve(
            signalItem(title = "Alice", senderContactUri = "content://com.android.contacts/contacts/lookup/x/1"),
            disabledPackages = emptySet(),
        )
        assertEquals("https://signal.me/#p/+34600112233", result.url)
        assertEquals(MagicLinkOutcome.MATCHED, result.trace.outcome)
        assertEquals(Signal.SERVICE, result.trace.service)
        assertEquals(Signal.SOURCE_CONTACTS, result.trace.source)
    }

    @Test
    fun aPhoneShapedTitleIsDeliberatelyNotAFallback() = runTest {
        // Unlike WhatsApp, Signal has no title fallback: its display-name chain puts the profile name
        // above the E.164 fallback, and every account sets one at registration. Without a contact uri
        // there is nothing to build, even if the title happens to look like a number.
        val result = LinkResolverImpl().resolve(
            signalItem(title = "+34 600 11 22 33"),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
    }

    @Test
    fun theBuiltUrlSatisfiesSignalsOwnStrictParser() {
        // Signal's SignalMeUtil regex, verbatim. A percent-encoded `+`, a trailing slash or a query
        // string all silently no-op in the app, so pin the exact shape.
        val signalParser = Regex("""^(https|sgnl)://signal\.me/#p/(\+[0-9]+)$""")
        assertTrue(signalParser.matches(Signal.chatUrl("34600112233")))
        assertTrue(signalParser.matches(Signal.chatUrl("15555555555")))
    }

    @Test
    fun unsavedContactWithoutPhoneIsNotLinkable() = runTest {
        // A display name with no contact uri: Signal's own ids carry no number, so nothing to build.
        val result = LinkResolverImpl().resolve(
            signalItem(title = "Alice"),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
        assertEquals(Signal.SERVICE, result.trace.service)
    }

    @Test
    fun contactsPermissionDeniedIsNotLinkable() = runTest {
        // The no-op resolver stands in for "permission not granted" -> no phone recovered.
        val result = LinkResolverImpl().resolve(
            signalItem(title = "Alice", senderContactUri = "content://com.android.contacts/contacts/lookup/x/1"),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
    }

    @Test
    fun traceNeverLeaksTheNumberOrUrl() = runTest {
        val resolver = LinkResolverImpl(FakePhoneResolver("34600112233"))
        val result = resolver.resolve(
            signalItem(senderContactUri = "content://com.android.contacts/contacts/lookup/x/1"),
            disabledPackages = emptySet(),
        )
        // Diagnostics are shareable, so the trace records only HOW it resolved, never the number.
        assertNull(result.trace.url)
        assertNull(result.trace.error)
    }

    @Test
    fun optedOutPackageSkipsResolution() = runTest {
        val resolver = LinkResolverImpl(FakePhoneResolver("34600112233"))
        val result = resolver.resolve(
            signalItem(senderContactUri = "content://com.android.contacts/contacts/lookup/x/1"),
            disabledPackages = setOf("org.thoughtcrime.securesms"),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.SKIPPED_DISABLED, result.trace.outcome)
    }
}
