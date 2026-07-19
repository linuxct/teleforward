package space.linuxct.teleforward.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the [LinkHarvest] ↔ [Parcel] wiring — the invariant the harvest comment claims but which
 * neither class's own tests cover: a synthesised tracking link is appended **last**, so a genuine link
 * never loses a slot to one, and the whole result still respects [LinkHarvest.MAX_LINKS].
 */
class LinkHarvestParcelTest {

    private val ups = "1Z5R89390357567127"
    private val upsUrl = "https://www.ups.com/track?loc=en_US&tracknum=$ups"

    @Test
    fun `a tracking number in the text becomes a link`() {
        val out = LinkHarvest.harvest(listOf("Your parcel $ups is out for delivery"))
        assertEquals(listOf(upsUrl), out)
    }

    @Test
    fun `real links come first, the synthesised one last`() {
        val out = LinkHarvest.harvest(listOf("See https://example.com/order and parcel $ups"))
        assertEquals(listOf("https://example.com/order", upsUrl), out)
    }

    @Test
    fun `a genuine link never loses a slot to a synthesised one`() {
        // MAX_LINKS real urls already fill the budget, so the tracking link must be dropped, not
        // displace one of them.
        val texts = listOf(
            (1..LinkHarvest.MAX_LINKS).joinToString(" ") { "https://example.com/$it" } +
                " parcel $ups",
        )
        val out = LinkHarvest.harvest(texts)
        assertEquals(LinkHarvest.MAX_LINKS, out.size)
        assertTrue("real links kept", out.all { it.startsWith("https://example.com/") })
        assertTrue("synthesised link dropped", upsUrl !in out)
    }

    @Test
    fun `text with no url and no tracking number yields nothing`() {
        assertTrue(LinkHarvest.harvest(listOf("Your package is on its way")).isEmpty())
    }

    @Test
    fun `an order id is not mistaken for a tracking number`() {
        // 12-digit FedEx-shaped ids are deliberately not detected (no structural anchor).
        assertTrue(LinkHarvest.harvest(listOf("Order 123456789012 confirmed")).isEmpty())
    }
}
