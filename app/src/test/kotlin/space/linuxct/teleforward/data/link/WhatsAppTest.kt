package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure unit tests for the WhatsApp phone-extraction helpers (no Android / network).
 *
 * All identifiers below are synthetic placeholders — not real phone numbers, LIDs, or contacts.
 */
class WhatsAppTest {

    @Test
    fun extractsPhoneFromIndividualJid() {
        assertEquals("12345678901", WhatsApp.phoneFromJid("12345678901@s.whatsapp.net"))
        assertEquals("12345678901", WhatsApp.phoneFromJid("12345678901@c.us"))
    }

    @Test
    fun lidAndGroupAndJunkAreNotPhones() {
        // LIDs are opaque privacy ids — must never be treated as phone numbers.
        assertNull(WhatsApp.phoneFromJid("11112222333344@lid"))
        assertNull(WhatsApp.phoneFromJid("55556666777788@lid"))
        assertNull(WhatsApp.phoneFromJid("120000000000000000@g.us"))
        assertNull(WhatsApp.phoneFromJid(null))
        assertNull(WhatsApp.phoneFromJid("nonsense"))
        assertNull(WhatsApp.phoneFromJid("@s.whatsapp.net"))
    }

    @Test
    fun groupJidsNeverProduceAPhone() {
        // Regression guard. WhatsApp Web cannot open a group by JID (only invite links can), so groups
        // must stay unlinked. The LEGACY group form is the trap: its leading digits are the group
        // CREATOR's phone number, so a naive parse would link a 1:1 chat with the wrong person.
        assertNull(WhatsApp.phoneFromJid("120363000000000000@g.us")) // modern group
        assertNull(WhatsApp.phoneFromJid("12345678901-123123123@g.us")) // legacy: <creator>-<created>
        // The same string must not sneak through the title path either.
        assertNull(WhatsApp.phoneFromTitle("12345678901-123123123@g.us"))
    }

    @Test
    fun extractsPhoneFromInternationalTitle() {
        assertEquals("12345678901", WhatsApp.phoneFromTitle("+12 345 678 901"))
        assertEquals("98765432109", WhatsApp.phoneFromTitle("+98 765 432 109"))
    }

    @Test
    fun savedContactNameIsNotAPhone() {
        assertNull(WhatsApp.phoneFromTitle("Contact Name"))
        assertNull(WhatsApp.phoneFromTitle("Some Business (Support)"))
        // No leading '+': refuse, so we never guess a country code and link the wrong chat.
        assertNull(WhatsApp.phoneFromTitle("123456789"))
        assertNull(WhatsApp.phoneFromTitle(null))
    }

    @Test
    fun contactNumberPrefersNormalizedE164() {
        assertEquals(
            "12345678901",
            WhatsApp.phoneFromContactNumber(normalizedNumber = "+12345678901", rawNumber = "345 678 901"),
        )
    }

    @Test
    fun contactRawUsedOnlyWhenExplicitlyInternational() {
        assertEquals("12345678901", WhatsApp.phoneFromContactNumber(null, "+12 345 678 901"))
        // National-format raw with no country code → refuse (wrong-country risk).
        assertNull(WhatsApp.phoneFromContactNumber(null, "345 678 901"))
        assertNull(WhatsApp.phoneFromContactNumber(null, null))
    }

    @Test
    fun rejectsOutOfRangeLengths() {
        assertNull(WhatsApp.phoneFromJid("123@s.whatsapp.net"))
        assertNull(WhatsApp.phoneFromTitle("+12"))
        assertNull(WhatsApp.phoneFromTitle("+1234567890123456")) // 16 digits > E.164 max
    }

    @Test
    fun buildsWhatsAppWebSendUrl() {
        assertEquals(
            "https://web.whatsapp.com/send/?phone=12345678901&text&type=phone_number&app_absent=0",
            WhatsApp.chatUrl("12345678901"),
        )
    }
}
