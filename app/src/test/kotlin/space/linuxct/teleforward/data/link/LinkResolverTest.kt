package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkResolverTest {

    private val resolver = LinkResolverImpl()

    // Trimmed sample of the real Atom uploads feed. The first entry's title carries `&#39;`
    // (apostrophe) numeric character references, which the DOM parser auto-decodes. Both entries
    // carry a `<published>` timestamp (newest first, as YouTube serves them).
    private val sampleFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015"
              xmlns:media="http://search.yahoo.com/mrss/"
              xmlns="http://www.w3.org/2005/Atom">
          <title>Uploads</title>
          <entry>
            <id>yt:video:ELInDLq_Yjk</id>
            <yt:videoId>ELInDLq_Yjk</yt:videoId>
            <yt:channelId>UCg40OxZ1GYh3u3jBntB6DLg</yt:channelId>
            <title>Lindsey Graham Dies At 71 After Battling &#39;Brief And Sudden Illness&#39;</title>
            <published>2026-07-12T10:00:00+00:00</published>
          </entry>
          <entry>
            <id>yt:video:AbCdEfGhIjk</id>
            <yt:videoId>AbCdEfGhIjk</yt:videoId>
            <yt:channelId>UCg40OxZ1GYh3u3jBntB6DLg</yt:channelId>
            <title>An Entirely Different Video</title>
            <published>2026-07-10T08:00:00+00:00</published>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parsesEntriesAndDecodesEntities() {
        val entries = resolver.parseFeed(sampleFeed)
        assertEquals(2, entries.size)
        assertEquals("ELInDLq_Yjk", entries[0].videoId)
        assertEquals(
            "Lindsey Graham Dies At 71 After Battling 'Brief And Sudden Illness'",
            entries[0].title,
        )
        assertEquals("AbCdEfGhIjk", entries[1].videoId)
    }

    @Test
    fun parseFeedCapturesPublished() {
        val entries = resolver.parseFeed(sampleFeed)
        assertEquals("2026-07-12T10:00:00+00:00", entries[0].published)
        assertEquals("2026-07-10T08:00:00+00:00", entries[1].published)
    }

    @Test
    fun matchesTitleIncludingDecodedApostrophe() {
        val entries = resolver.parseFeed(sampleFeed)
        // The notification body (video title) contains a real apostrophe.
        val match = resolver.matchTitle(
            entries,
            "Lindsey Graham Dies At 71 After Battling 'Brief And Sudden Illness'",
        )
        assertNotNull(match)
        assertEquals("ELInDLq_Yjk", match!!.videoId)
    }

    @Test
    fun matchIsCaseInsensitiveAndPunctuationTolerant() {
        val entries = resolver.parseFeed(sampleFeed)
        // Different case + stripped punctuation still resolves via the normalized comparison.
        val match = resolver.matchTitle(
            entries,
            "lindsey graham dies at 71 after battling brief and sudden illness",
        )
        assertNotNull(match)
        assertEquals("ELInDLq_Yjk", match!!.videoId)
    }

    @Test
    fun noConfidentMatchReturnsNull() {
        val entries = resolver.parseFeed(sampleFeed)
        assertNull(resolver.matchTitle(entries, "A title that appears in no entry whatsoever"))
    }

    @Test
    fun malformedXmlParsesToEmpty() {
        assertEquals(emptyList<VideoEntry>(), resolver.parseFeed("not xml at all <<<"))
    }

    @Test
    fun feedUrlIsCacheBusted() {
        // The feed URL must carry a unique `nocache=` param so YouTube's URL-keyed CDN cache is
        // bypassed and the fresh (just-published) feed is returned.
        val url = resolver.feedUrl("UCg40OxZ1GYh3u3jBntB6DLg", nocache = 1_752_000_000_000L)
        assertTrue(url, url.startsWith("https://www.youtube.com/feeds/videos.xml?"))
        assertTrue(url, url.contains("channel_id=UCg40OxZ1GYh3u3jBntB6DLg"))
        assertTrue(url, url.contains("nocache=1752000000000"))
    }

    @Test
    fun feedUrlNocacheChangesEachCall() {
        val a = resolver.feedUrl("UCg40OxZ1GYh3u3jBntB6DLg", nocache = 1L)
        val b = resolver.feedUrl("UCg40OxZ1GYh3u3jBntB6DLg", nocache = 2L)
        assertTrue(a != b)
    }

    @Test
    fun matchedResultCarriesVideoIdAndUrlInTrace() {
        val entries = resolver.parseFeed(sampleFeed)
        val result = resolver.resultFromEntries(
            channelId = "UCg40OxZ1GYh3u3jBntB6DLg",
            title = "An Entirely Different Video",
            entries = entries,
        )
        assertEquals("https://www.youtube.com/watch?v=AbCdEfGhIjk", result.url)
        val trace = result.trace
        assertEquals(MagicLinkOutcome.MATCHED, trace.outcome)
        assertEquals("AbCdEfGhIjk", trace.videoId)
        assertEquals("https://www.youtube.com/watch?v=AbCdEfGhIjk", trace.url)
        assertEquals(2, trace.feedEntryCount)
        assertTrue(trace.cacheBusted)
    }

    @Test
    fun noMatchResultCarriesFeedMetadataInTrace() {
        val entries = resolver.parseFeed(sampleFeed)
        val result = resolver.resultFromEntries(
            channelId = "UCg40OxZ1GYh3u3jBntB6DLg",
            title = "A title that appears in no entry whatsoever",
            entries = entries,
        )
        assertNull(result.url)
        val trace = result.trace
        assertEquals(MagicLinkOutcome.NO_MATCH, trace.outcome)
        assertEquals(2, trace.feedEntryCount)
        // Newest-first ordering: first entry is the newest published, last is the oldest.
        assertEquals("2026-07-12T10:00:00+00:00", trace.feedNewestPublished)
        assertEquals("2026-07-10T08:00:00+00:00", trace.feedOldestPublished)
        assertEquals(
            "Lindsey Graham Dies At 71 After Battling 'Brief And Sudden Illness'",
            trace.feedNewestTitle,
        )
        assertNull(trace.videoId)
        assertNull(trace.url)
    }
}
