package space.linuxct.teleforward.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageBuilderTest {

    private val builder =
        MessageBuilderImpl(TelegramStrings(RuntimeEnvironment.getApplication()))

    private fun outbox(
        title: String? = "Some Title",
        body: String? = "Some body text",
        postTime: Long = 0L,
        extractedLinks: String? = null,
    ) = OutboxEntity(
        id = 1L,
        dedupeKey = "d",
        packageName = "com.google.android.youtube",
        channelId = "Subscriptions",
        appLabel = "YouTube",
        channelName = "Subscriptions",
        title = title,
        body = body,
        extractedLinks = extractedLinks,
        postTime = postTime,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastError = null,
        createdAt = 0L,
    )

    private val link = "https://www.youtube.com/watch?v=ELInDLq_Yjk"

    @Test
    fun appendsLinkAsFinalLine() {
        val text = builder.buildText(outbox(), extraLink = link)
        assertEquals("Link: $link", text.lines().last())
    }

    @Test
    fun keepsLinkWhenBodyIsTruncated() {
        val hugeBody = "x".repeat(6000)
        val text = builder.buildText(outbox(body = hugeBody), extraLink = link)
        // The body forced truncation, yet the full link line survives as the final line...
        assertEquals("Link: $link", text.lines().last())
        // ...and the whole message still respects the Telegram text limit.
        assertTrue(text.length <= MessageBuilder.TEXT_LIMIT)
    }

    @Test
    fun keepsLinkWithinCaptionLimit() {
        val hugeBody = "y".repeat(3000)
        val caption = builder.buildCaption(outbox(body = hugeBody), extraLink = link)
        assertEquals("Link: $link", caption.lines().last())
        assertTrue(caption.length <= MessageBuilder.CAPTION_LIMIT)
    }

    @Test
    fun noLinkLineWhenAbsent() {
        val text = builder.buildText(outbox(), extraLink = null)
        assertFalse(text.contains("Link:"))
    }

    // --- Tier-0 extracted links --------------------------------------------------------------------

    @Test
    fun appendsExtractedLinkNotInBody() {
        val extracted = "https://harvested.example/one"
        val text = builder.buildText(
            outbox(body = "no link in this body", extractedLinks = extracted),
        )
        assertTrue(text.lines().contains(extracted))
    }

    @Test
    fun doesNotDuplicateExtractedLinkAlreadyInBody() {
        val url = "https://inline.example/x"
        val text = builder.buildText(
            outbox(body = "See $url for details", extractedLinks = url),
        )
        // The url appears once (inline in the body), never appended a second time.
        assertEquals(1, text.split(url).size - 1)
    }

    @Test
    fun appendsMultipleExtractedLinksEachOnItsOwnLine() {
        val a = "https://harvested.example/one"
        val b = "https://harvested.example/two"
        val text = builder.buildText(
            outbox(body = "body", extractedLinks = "$a\n$b"),
        )
        val lines = text.lines()
        assertTrue(lines.contains(a))
        assertTrue(lines.contains(b))
    }

    @Test
    fun extractedLinksSurviveBodyTruncationWithinLimit() {
        val hugeBody = "z".repeat(6000)
        val extracted = "https://harvested.example/keep-me"
        val text = builder.buildText(outbox(body = hugeBody, extractedLinks = extracted))
        // The body forced truncation, yet the harvested link survives...
        assertTrue(text.lines().contains(extracted))
        // ...and the whole message still respects the Telegram text limit.
        assertTrue(text.length <= MessageBuilder.TEXT_LIMIT)
    }

    @Test
    fun extractedLinksCoexistWithMagicLinkAsFinalLine() {
        val extracted = "https://harvested.example/one"
        val text = builder.buildText(
            outbox(body = "body", extractedLinks = extracted),
            extraLink = link,
        )
        val lines = text.lines()
        // Magic Link: line stays last; the extracted link appears earlier as a bare url.
        assertEquals("Link: $link", lines.last())
        assertTrue(lines.contains(extracted))
    }

    @Test
    fun extractedLinkEqualToMagicLinkIsNotDuplicated() {
        val text = builder.buildText(
            outbox(body = "body", extractedLinks = link),
            extraLink = link,
        )
        // Only the reconstructed magic `Link:` line carries it — no bare duplicate line.
        assertEquals(1, text.split(link).size - 1)
    }

    @Test
    fun extractedLinksStayWithinCaptionLimitUnderPressure() {
        val hugeBody = "y".repeat(3000)
        val extracted = "https://harvested.example/one"
        val caption = builder.buildCaption(outbox(body = hugeBody, extractedLinks = extracted))
        assertTrue(caption.lines().contains(extracted))
        assertTrue(caption.length <= MessageBuilder.CAPTION_LIMIT)
    }

    // --- appendLink (edit-after-send retry) --------------------------------------------------------

    @Test
    fun appendLinkAddsFinalLinkLineKeepingBody() {
        val result = builder.appendLink("Some body", link, isCaption = false)
        assertEquals("Some body", result.lines().first())
        assertEquals("Link: $link", result.lines().last())
    }

    @Test
    fun appendLinkEscapesUrl() {
        val url = "https://x.example/a?b=1&c=2<d>"
        val result = builder.appendLink("body", url, isCaption = false)
        assertEquals("Link: https://x.example/a?b=1&amp;c=2&lt;d&gt;", result.lines().last())
    }

    @Test
    fun appendLinkBlankUrlReturnsTextUnchanged() {
        val text = "body\n<i>time</i>"
        assertEquals(text, builder.appendLink(text, "   ", isCaption = false))
    }

    @Test
    fun appendLinkEmptyTextReturnsLinkOnly() {
        assertEquals("Link: $link", builder.appendLink("", link, isCaption = true))
    }

    @Test
    fun appendLinkStaysWithinCaptionLimitDroppingWholeLines() {
        // A multi-line caption over the 1024 ceiling; appending must trim yet stay within the limit
        // and never split a line's HTML markup (surviving lines are whole).
        val markupLine = "<b>0123456789</b>"
        val text = generateSequence { markupLine }.take(70).joinToString("\n")
        assertTrue(text.length > MessageBuilder.CAPTION_LIMIT)

        val result = builder.appendLink(text, link, isCaption = true)

        assertTrue(result.length <= MessageBuilder.CAPTION_LIMIT)
        assertEquals("Link: $link", result.lines().last())
        result.lines().dropLast(1).forEach { assertEquals(markupLine, it) }
    }

    @Test
    fun appendLinkStaysWithinTextLimitForLongSingleLine() {
        // A single markup-free line far over the 4096 text limit: char-trimmed but still within limit.
        val body = "z".repeat(5000)
        val result = builder.appendLink(body, link, isCaption = false)
        assertTrue(result.length <= MessageBuilder.TEXT_LIMIT)
        assertEquals("Link: $link", result.lines().last())
    }

    @Test
    fun appendLinkNeverSplitsHtmlEntityWhenCharTrimming() {
        // A markup-free single line that is nothing but repeated `&amp;` entities: wherever char-level
        // truncation lands it must back off to an entity boundary, never leaving a dangling `&`/`&amp`.
        val body = "&amp;".repeat(300) // 1500 chars, no newline, no markup
        val result = builder.appendLink(body, link, isCaption = true)
        assertTrue(result.length <= MessageBuilder.CAPTION_LIMIT)
        val trimmedBody = result.lines().dropLast(1).joinToString("\n")
        assertFalse(trimmedBody.endsWith("&"))
        assertFalse(trimmedBody.endsWith("&amp"))
        assertFalse(trimmedBody.endsWith("&am"))
        assertFalse(trimmedBody.endsWith("&a"))
    }
}
