package space.linuxct.teleforward.data.link

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests [LinkResolverImpl.resolveMediaLink]'s **network-free** guards: a blank track or artist must
 * short-circuit before any lookup is attempted, so a media notification that exposes only one of the two
 * (or neither) can never fire a pointless request.
 *
 * The remaining branches — an upstream fetch error, and a fetch that returns no confident match — both
 * require the HTTP client and so are not unit-testable without injecting it. They are covered
 * indirectly: the parse/match half is exercised by [AppleMusicTest] (`parseTracks` + the
 * confidence-gated `pickTrack`, which is the same code this calls) and the url half by [SongLinkTest].
 * The composition is deliberately trivial for that reason.
 */
class ResolveMediaLinkTest {

    @Test
    fun `a blank track never triggers a lookup`() = runTest {
        assertNull(LinkResolverImpl().resolveMediaLink(track = "", artist = "Daft Punk"))
        assertNull(LinkResolverImpl().resolveMediaLink(track = "   ", artist = "Daft Punk"))
    }

    @Test
    fun `a blank artist never triggers a lookup`() = runTest {
        assertNull(LinkResolverImpl().resolveMediaLink(track = "Get Lucky", artist = ""))
        assertNull(LinkResolverImpl().resolveMediaLink(track = "Get Lucky", artist = "   "))
    }

    @Test
    fun `both blank yields nothing`() = runTest {
        assertNull(LinkResolverImpl().resolveMediaLink(track = "", artist = ""))
    }
}
