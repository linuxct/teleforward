package space.linuxct.teleforward.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Parcel]: checksum-validated, structurally-anchored tracking-number detection.
 *
 * The valid numbers are real published carrier examples. The adversarial cases matter just as much —
 * a check digit is only a 10× filter, so the point of these is to prove the *structural* anchors keep
 * ordinary numbers (order ids, phone numbers) from becoming wrong links.
 */
class ParcelTest {

    // --- UPS ------------------------------------------------------------------------------------

    @Test
    fun `accepts real ups numbers`() {
        listOf(
            "1Z5R89390357567127",
            "1Z879E930346834440",
            "1Z410E7W0392751591",
            "1Z8V92A70367203024",
            "1ZXX3150YW44070023",
        ).forEach { assertTrue("should validate: $it", Parcel.isValidUps(it)) }
    }

    @Test
    fun `rejects ups-shaped strings with a bad check digit`() {
        assertFalse(Parcel.isValidUps("1Z1111111111111111"))
        // Mutating the last digit of a valid number must break it.
        assertFalse(Parcel.isValidUps("1Z5R89390357567128"))
    }

    @Test
    fun `rejects malformed ups shapes`() {
        assertFalse("too short", Parcel.isValidUps("1Z5R8939035756712"))
        assertFalse("too long", Parcel.isValidUps("1Z5R893903575671277"))
        assertFalse("wrong prefix", Parcel.isValidUps("2Z5R89390357567127"))
    }

    // --- USPS IMpb ------------------------------------------------------------------------------

    @Test
    fun `accepts real usps numbers`() {
        listOf(
            "9400111206206406260787",
            "9434611206206406227577",
            "9405803699300124287899",
            "92748931507708513018050063",
        ).forEach { assertTrue("should validate: $it", Parcel.isValidUsps(it)) }
    }

    @Test
    fun `accepts a legacy 20 digit number needing the 91 prefix rule`() {
        // Signature Confirmation: the body doesn't start 9[1-5], so "91" is prepended before
        // checksumming. Without that rule this valid number would be wrongly rejected.
        assertTrue(Parcel.isValidUsps("71969010756003077385"))
    }

    @Test
    fun `rejects usps-shaped strings with a bad check digit`() {
        assertFalse(Parcel.isValidUsps("9400111206206406260788"))
    }

    @Test
    fun `rejects long digit runs that are not impb`() {
        assertFalse("21 digits is not a valid length", Parcel.isValidUsps("940011120620640626078"))
        // Modern forms must start with 9 — an ordinary 22-digit number must not slip through.
        assertFalse("no leading 9", Parcel.isValidUsps("1234567890123456789012"))
    }

    // --- UPU S10 (international post) ------------------------------------------------------------

    @Test
    fun `accepts real s10 numbers`() {
        assertTrue(Parcel.isValidS10("RR287043775IN"))
        assertTrue(Parcel.isValidS10("RB123456785GB"))
    }

    @Test
    fun `rejects s10 with a bad check digit`() {
        assertFalse(Parcel.isValidS10("RR287043774IN"))
    }

    @Test
    fun `rejects a reserved s10 service indicator`() {
        // JA-JZ, KA-KZ, SA-SZ, TA-TZ and WA-WZ are never assigned by the UPU. Without this guard any
        // two capitals + nine digits + two capitals would pass.
        assertFalse("SA is reserved", Parcel.isValidS10("SA287043775IN"))
        assertFalse("TA is reserved", Parcel.isValidS10("TA287043775IN"))
        assertFalse("WZ is reserved", Parcel.isValidS10("WZ287043775IN"))
    }

    @Test
    fun `rejects an unreal origin country`() {
        assertFalse(Parcel.isValidS10("RR287043775XX"))
        assertFalse(Parcel.isValidS10("RR287043775ZZ"))
    }

    @Test
    fun `rejects an all-zero serial`() {
        // 00000000 checksums to 5, so without this guard XX000000005YY would validate.
        assertFalse(Parcel.isValidS10("RR000000005IN"))
    }

    @Test
    fun `extracts an s10 number and links the aggregator`() {
        val urls = Parcel.trackingUrls(listOf("Tu envío RR287043775IN está en camino"))
        assertEquals(listOf("https://t.17track.net/en#nums=RR287043775IN"), urls)
    }

    // --- the adversarial cases that motivated the carrier allowlist -----------------------------

    @Test
    fun `an ordinary 12 digit order id never becomes a link`() {
        // These validate as FedEx Express, which is exactly why FedEx is excluded: a bare 12-digit
        // number has no structural anchor, so ~1 in 10 order ids would produce a wrong link.
        val urls = Parcel.trackingUrls(listOf("Your order 123456789012 has shipped"))
        assertTrue(urls.isEmpty())
        assertTrue(Parcel.trackingUrls(listOf("ref 987654321098")).isEmpty())
    }

    @Test
    fun `a phone number never becomes a link`() {
        // 2125551234 validates as a DHL Express AWB — the reason DHL Express is excluded.
        assertTrue(Parcel.trackingUrls(listOf("Call us on 2125551234")).isEmpty())
        assertTrue(Parcel.trackingUrls(listOf("8005551212")).isEmpty())
    }

    // --- end-to-end extraction ------------------------------------------------------------------

    @Test
    fun `extracts a ups number from real notification prose`() {
        val urls = Parcel.trackingUrls(listOf("Your parcel 1Z5R89390357567127 is out for delivery"))
        assertEquals(listOf("https://www.ups.com/track?loc=en_US&tracknum=1Z5R89390357567127"), urls)
    }

    @Test
    fun `extracts a usps number and strips the 420 zip routing prefix`() {
        // Labels carry a `420` + ZIP5 routing prefix that is not part of the trackable number.
        // "420" + ZIP5 "92103" + the 22-digit IMpb number.
        val urls = Parcel.trackingUrls(listOf("Shipped: 420921039400111206206406260787"))
        assertEquals(
            listOf("https://tools.usps.com/go/TrackConfirmAction?qtc_tLabels1=9400111206206406260787"),
            urls,
        )
    }

    @Test
    fun `deduplicates and caps the number of tracking links`() {
        val n = "1Z5R89390357567127"
        val urls = Parcel.trackingUrls(listOf("$n and again $n"))
        assertEquals(1, urls.size)
    }

    @Test
    fun `text with no tracking number yields nothing`() {
        assertTrue(Parcel.trackingUrls(listOf("Your package is on its way")).isEmpty())
        assertTrue(Parcel.trackingUrls(listOf(null, "")).isEmpty())
    }
}
