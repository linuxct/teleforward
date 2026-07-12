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
