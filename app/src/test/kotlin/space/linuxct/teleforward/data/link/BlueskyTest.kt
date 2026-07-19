package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [Bluesky]'s pure halves: pulling a post AT-URI out of Expo's marshalled payload and
 * turning it into a bsky.app url. Plain JUnit — no Android, no network.
 *
 * Payloads are built by encoding representative FCM JSON as UTF-16LE, which is how `Parcel` writes
 * Strings, so these exercise the same decode path as a real notification blob.
 */
class BlueskyTest {

    private val did = "did:plc:6kos45lixtga3pdwuncvh32x"
    private val rkey = "3mqc36slinc2m"

    /** Encode like a Parcel would: UTF-16LE, with some binary noise around the strings. */
    private fun parcelBlob(json: String): ByteArray =
        byteArrayOf(0x10, 0x00, 0x2A, 0x00) + json.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)

    @Test
    fun `extracts the post uri from a reply payload`() {
        val blob = parcelBlob("""{"reason":"reply","uri":"at://$did/app.bsky.feed.post/$rkey"}""")
        assertEquals("at://$did/app.bsky.feed.post/$rkey", Bluesky.postUriFromExpoPayload(blob))
    }

    @Test
    fun `on a like it picks subject, not the like record`() {
        // The collection filter does the disambiguation: `uri` here is an app.bsky.feed.LIKE record,
        // so only `subject` is a post — which is exactly the post we want to link.
        val blob = parcelBlob(
            """{"reason":"like","uri":"at://$did/app.bsky.feed.like/3likerkey00","""" +
                """subject":"at://$did/app.bsky.feed.post/$rkey"}""",
        )
        assertEquals("at://$did/app.bsky.feed.post/$rkey", Bluesky.postUriFromExpoPayload(blob))
    }

    @Test
    fun `a follow names no post and yields nothing`() {
        val blob = parcelBlob("""{"reason":"follow","uri":"at://$did/app.bsky.graph.follow/3flwrkey00"}""")
        assertNull(Bluesky.postUriFromExpoPayload(blob))
    }

    @Test
    fun `a chat message yields nothing`() {
        val blob = parcelBlob("""{"reason":"chat-message","convoId":"abc123","messageId":"m1"}""")
        assertNull(Bluesky.postUriFromExpoPayload(blob))
    }

    @Test
    fun `an absent or unreadable blob yields nothing`() {
        assertNull(Bluesky.postUriFromExpoPayload(null))
        assertNull(Bluesky.postUriFromExpoPayload(ByteArray(0)))
        assertNull("random bytes", Bluesky.postUriFromExpoPayload(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `builds the did form of the post url`() {
        // The DID form resolves on bsky.app exactly like a handle, and can't go stale on a rename.
        assertEquals(
            "https://bsky.app/profile/$did/post/$rkey",
            Bluesky.postUrl("at://$did/app.bsky.feed.post/$rkey"),
        )
    }

    @Test
    fun `postUrl refuses anything that is not a post at-uri`() {
        assertNull(Bluesky.postUrl(null))
        assertNull(Bluesky.postUrl(""))
        assertNull(Bluesky.postUrl("at://$did/app.bsky.graph.follow/3flwrkey00"))
        assertNull(Bluesky.postUrl("https://example.com/not-an-at-uri"))
    }

    @Test
    fun `supports a did-web author`() {
        val webDid = "did:web:example.com"
        val blob = parcelBlob("""{"reason":"mention","uri":"at://$webDid/app.bsky.feed.post/$rkey"}""")
        assertEquals(
            "https://bsky.app/profile/$webDid/post/$rkey",
            Bluesky.postUrl(Bluesky.postUriFromExpoPayload(blob)),
        )
    }
}
