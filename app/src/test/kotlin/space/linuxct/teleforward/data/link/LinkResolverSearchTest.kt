package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the YouTube-search fallback's pure, network-free halves: the `ytInitialData`
 * extraction, the recursive `parseSearchVideos` walk, and the channel-scoped `pickVideoId` pick.
 *
 * Runs under Robolectric (like the Room DAO test) so the real `org.json` implementation is available
 * on the JVM — the mockable `android.jar` used by plain unit tests stubs `org.json` out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkResolverSearchTest {

    private val resolver = LinkResolverImpl()

    private val rightChannel = "UCqlYzSgsh5jdtWYfVIBoTDw"
    private val wrongChannel = "UC1111111111111111111111"
    private val targetTitle = "Treasury Department says millions of Trump Accounts have been opened"

    // A trimmed but realistically-nested `ytInitialData` payload: three videoRenderers under the usual
    // twoColumnSearchResults → sectionList → itemSection path.
    //  - the RIGHT channel + target title (the correct hit),
    //  - a DIFFERENT channel carrying the SAME title (must never be returned),
    //  - the right channel with an unrelated title via `simpleText` (proves title-scoping).
    private val searchJson = """
        {
          "contents": {
            "twoColumnSearchResultsRenderer": {
              "primaryContents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "itemSectionRenderer": {
                        "contents": [
                          {
                            "videoRenderer": {
                              "videoId": "0CCzavfU154",
                              "title": { "runs": [ { "text": "$targetTitle" } ] },
                              "ownerText": {
                                "runs": [
                                  {
                                    "text": "Right Channel",
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "$rightChannel" }
                                    }
                                  }
                                ]
                              }
                            }
                          },
                          {
                            "videoRenderer": {
                              "videoId": "WRONGvid123",
                              "title": { "runs": [ { "text": "$targetTitle" } ] },
                              "longBylineText": {
                                "runs": [
                                  {
                                    "text": "Some Other News",
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "$wrongChannel" }
                                    }
                                  }
                                ]
                              }
                            }
                          },
                          {
                            "videoRenderer": {
                              "videoId": "OTHERvid456",
                              "title": { "simpleText": "Some unrelated clip" },
                              "shortBylineText": {
                                "runs": [
                                  {
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "$rightChannel" }
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun parseSearchVideosWalksNestedRenderers() {
        val videos = resolver.parseSearchVideos(searchJson)
        assertEquals(3, videos.size)

        assertEquals("0CCzavfU154", videos[0].videoId)
        assertEquals(targetTitle, videos[0].title)
        assertEquals(rightChannel, videos[0].channelId)

        // Same title, different channel — parsed, but distinguishable by channelId.
        assertEquals("WRONGvid123", videos[1].videoId)
        assertEquals(targetTitle, videos[1].title)
        assertEquals(wrongChannel, videos[1].channelId)

        // simpleText title + shortBylineText channel.
        assertEquals("OTHERvid456", videos[2].videoId)
        assertEquals("Some unrelated clip", videos[2].title)
        assertEquals(rightChannel, videos[2].channelId)
    }

    @Test
    fun pickVideoIdReturnsChannelAndTitleMatch() {
        val videos = resolver.parseSearchVideos(searchJson)
        // Both the right video AND the same-channel unrelated clip match the channel; only the correct
        // title picks the right one — never the wrong-channel same-title video.
        assertEquals("0CCzavfU154", resolver.pickVideoId(videos, rightChannel, targetTitle))
    }

    @Test
    fun pickVideoIdIsCaseAndPunctuationTolerant() {
        val videos = resolver.parseSearchVideos(searchJson)
        // The notification body may differ in case/punctuation; the normalized comparison still matches.
        val picked = resolver.pickVideoId(
            videos,
            rightChannel,
            "treasury department says millions of trump accounts have been opened!!!",
        )
        assertEquals("0CCzavfU154", picked)
    }

    @Test
    fun pickVideoIdNullWhenOnlyWrongChannelSameTitleExists() {
        val videos = resolver.parseSearchVideos(searchJson)
        // Restrict to the non-target channels: a same-title video from a different channel must NOT be
        // returned for the target channel — channel-scoping is required.
        val onlyOtherChannels = videos.filter { it.channelId != rightChannel }
        assertNull(resolver.pickVideoId(onlyOtherChannels, rightChannel, targetTitle))
    }

    @Test
    fun pickVideoIdNullWhenTitleDoesNotMatch() {
        val videos = resolver.parseSearchVideos(searchJson)
        assertNull(resolver.pickVideoId(videos, rightChannel, "A title no video in the results has"))
    }

    @Test
    fun extractYtInitialDataBalancesBracesFromHtml() {
        // The JSON is embedded in a script tag (with trailing markup) exactly like the real page.
        val html = "<html><body><script>var ytInitialData = $searchJson;</script>" +
            "<div>{\"unrelated\":true}</div></body></html>"
        val extracted = resolver.extractYtInitialData(html)
        assertEquals(searchJson, extracted)
        // And the extracted JSON round-trips through the parser to the same three videos.
        assertEquals(3, resolver.parseSearchVideos(extracted!!).size)
    }

    @Test
    fun extractYtInitialDataNullOnConsentPage() {
        // A consent interstitial carries no ytInitialData marker → null (search reports 0 results).
        assertNull(resolver.extractYtInitialData("<html><body>Before you continue to YouTube</body></html>"))
    }

    @Test
    fun searchUrlIsEncodedAndLocalePinned() {
        val url = resolver.searchUrl(targetTitle)
        assert(url.startsWith("https://www.youtube.com/results?search_query="))
        assert(url.contains("hl=en"))
        assert(url.contains("gl=US"))
        // Spaces are percent/plus-encoded — no raw spaces in the query.
        assert(!url.contains(' '))
    }
}
