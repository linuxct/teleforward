package space.linuxct.teleforward.data.link

/**
 * YouTube-specific constants and the pure channel-id extraction used by the "magic link"
 * reconstruction feature.
 *
 * A subscription-upload notification never carries the video id in any readable field, but it does
 * expose the channel id (`UC…`) in `extras["chime.slot_key"]` and, redundantly, as the prefix of
 * `sbn.tag` (`"UC…::<uuid>"`). Combined with the video title (the notification body) and YouTube's
 * public uploads feed, the channel id lets us best-effort reconstruct the video url.
 */
object YouTube {

    /** Known YouTube app packages (stock + common re-packaged clients) that post upload notifications. */
    val PACKAGES: Set<String> = setOf(
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "app.morphe.android.youtube",
    )

    /** A YouTube channel id: literal `UC` followed by 22 url-safe base64 characters. */
    val channelIdRegex: Regex = Regex("UC[0-9A-Za-z_-]{22}")

    /** A YouTube video id: exactly 11 url-safe base64 characters. */
    val videoIdRegex: Regex = Regex("[0-9A-Za-z_-]{11}")

    /**
     * Extract a **video** id from a notification's `chime.slot_key` / `sbn.tag`.
     *
     * Live-stream and premiere notifications key themselves by the VIDEO id rather than the channel
     * id — e.g. `chime.slot_key = "PremiereVid"` with `tag = "PremiereVid::<uuid>"` — so the watch url
     * can be built directly, with no feed/search lookup at all. Uploads instead carry a `UC…` channel
     * id (see [extractChannelId]); the two never collide (24 chars vs 11).
     *
     * Matching is deliberately **whole-string** (not a substring search like [extractChannelId]): an
     * 11-char window exists inside every channel id, so a `find()` here would produce garbage. The
     * all-digits guard rejects numeric keys such as the group-summary key `1234567890`.
     */
    fun extractVideoId(chimeSlotKey: String?, tag: String?): String? =
        videoIdOrNull(chimeSlotKey) ?: videoIdOrNull(tag?.substringBefore("::"))

    /** [candidate] as a video id, or null when it isn't exactly one (whole-string, non-numeric). */
    private fun videoIdOrNull(candidate: String?): String? {
        val value = candidate?.trim().orEmpty()
        if (!videoIdRegex.matches(value)) return null
        // A purely numeric key is never a real video id (YouTube's own group keys look like this).
        if (value.all { it.isDigit() }) return null
        return value
    }

    /**
     * Extract a channel id (`UC…`) from a notification, preferring the `chime.slot_key` extra (which
     * is the bare channel id) and falling back to `sbn.tag` (`"UC…::<uuid>"`). Pure and null-safe:
     * returns the first `UC…` match found, or null when neither input contains one.
     */
    fun extractChannelId(chimeSlotKey: String?, tag: String?): String? {
        chimeSlotKey?.let { channelIdRegex.find(it)?.let { match -> return match.value } }
        tag?.let { channelIdRegex.find(it)?.let { match -> return match.value } }
        return null
    }
}
