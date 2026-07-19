package space.linuxct.teleforward.data.link

/**
 * Bluesky constants and the pure helpers used by the "magic link" reconstruction.
 *
 * Bluesky is built with Expo/React Native, which changes where its identifiers live. It sets **no**
 * `shortcutId`, `locusId`, `Person` or Wear extras — the usual hiding places are all empty. Instead,
 * Expo's notification builder marshals the **entire incoming FCM message** (a flat, data-only payload)
 * into a single byte array under [EXPO_REQUEST_EXTRA], and that payload carries the AT-URI of the post
 * the notification is about. So the id *is* readable, just wrapped in a parcel blob.
 *
 * The blob is scanned as **UTF-16LE text** rather than unmarshalled: `Parcel` encodes Strings as
 * UTF-16LE, so the URIs appear verbatim in the bytes, while `Parcel.unmarshall()` + typed reads depends
 * on a layout that is not stable across Expo/Android versions. Scanning is the robust option.
 *
 * Which URI to use resolves itself. A payload carries `uri` and/or `subject`, and the right one depends
 * on the notification kind — but filtering to the **`app.bsky.feed.post` collection** does that for
 * free: on a *like* only `subject` is a post (`uri` is the `app.bsky.feed.like` record), while on a
 * *reply* or *mention* only `uri` is. So the first post-collection URI found is always the right one.
 *
 * Only posts are linkable here. A *follow* names an `app.bsky.graph.follow` record and a chat message
 * carries a `convoId` with no public web URL, so both correctly yield nothing.
 *
 * All helpers are pure and unit-testable.
 */
object Bluesky {

    /** The official Bluesky app (applicationId confirmed from its `app.config.js`). */
    val PACKAGES: Set<String> = setOf(
        "xyz.blueskyweb.app",
    )

    const val SERVICE = "bluesky"

    /** Expo's marshalled `NotificationRequest`, holding the whole FCM data payload. */
    const val EXPO_REQUEST_EXTRA = "expo.notification_request"

    /**
     * An AT-URI naming a **post**. The `app.bsky.feed.post` collection is load-bearing: it is what makes
     * the first match the correct one regardless of notification kind (see the class note).
     */
    private val POST_URI =
        Regex("""at://(did:[a-z]+:[a-zA-Z0-9._%:-]+)/app\.bsky\.feed\.post/([a-zA-Z0-9._~-]+)""")

    private const val BASE = "https://bsky.app/profile"

    /**
     * Pure: the post AT-URI carried by Expo's marshalled notification [bytes], or null when the blob is
     * absent, unreadable, or names no post (a follow, a chat message, a like whose subject is missing).
     * Decodes as UTF-16LE, which is how `Parcel` writes Strings.
     */
    fun postUriFromExpoPayload(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val decoded = runCatching { String(bytes, Charsets.UTF_16LE) }.getOrNull() ?: return null
        return POST_URI.find(decoded)?.value
    }

    /**
     * Pure: the shareable web url for a post [atUri], or null when it is not a post AT-URI. Emits the
     * **DID** form (`/profile/<did>/post/<rkey>`), which bsky.app resolves exactly like a handle — so no
     * handle-resolution round trip is needed, and the link can't go stale if the author renames.
     */
    fun postUrl(atUri: String?): String? {
        val match = POST_URI.find(atUri?.trim().orEmpty()) ?: return null
        val (did, rkey) = match.destructured
        return "$BASE/$did/post/$rkey"
    }
}
