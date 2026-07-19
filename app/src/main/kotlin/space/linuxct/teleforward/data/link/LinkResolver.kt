package space.linuxct.teleforward.data.link

import space.linuxct.teleforward.data.db.entity.OutboxEntity

/**
 * Best-effort reconstruction of a "magic link" for an outbox item just before it is sent.
 *
 * Two services are supported (see [magicLinkKind]):
 *  - **YouTube** subscription-upload notifications: given the channel id (extracted into
 *    [OutboxEntity.youtubeChannelId]) and the video title (the body), fetch YouTube's uploads feed /
 *    search and map to the video's `watch?v=` url.
 *  - **Apple Music** now-playing notifications: given the track ([OutboxEntity.title]) and artist
 *    ([OutboxEntity.body]), look them up via Apple's public iTunes Search API and map to the
 *    `music.apple.com` song url.
 *
 * Everything is best-effort — any failure/timeout yields a null url, and the item is forwarded
 * normally without a `Link:` line.
 */
interface LinkResolver {

    /**
     * Attempt to reconstruct a link for [item]. Never throws: returns a [MagicLinkResult] whose
     * [MagicLinkResult.url] is null when the package is not a supported magic-link app, magic-link is
     * disabled for it ([disabledPackages]), or the item cannot be confidently reconstructed. The
     * accompanying [MagicLinkResult.trace] always explains why (and, on success, how).
     */
    suspend fun resolve(item: OutboxEntity, disabledPackages: Set<String>): MagicLinkResult

    /**
     * Re-resolve directly from a [channelId] + video [title] (the background retry path). Same
     * cache-busted fetch + confident-title match as [resolve], but keyed off the persisted
     * (channelId, title) pair rather than an [OutboxEntity], so it can run after the item left the
     * outbox. Never throws: a fetch/parse failure or missing match yields a null-url [MagicLinkResult].
     */
    suspend fun resolveChannelVideo(channelId: String, title: String): MagicLinkResult

    /**
     * Best-effort "now playing → universal song link" for ANY media player. Given the [track] +
     * [artist] a media notification exposes as plain text, look the song up via the iTunes Search API
     * (the same keyless lookup Apple Music uses) and, on a confident match, wrap the resulting
     * `music.apple.com` url in an Odesli [SongLink] universal page — one link that routes each recipient
     * into their own service (Spotify, Deezer, Tidal, YouTube Music, …). This is why it works for
     * players with no keyless API of their own, and for offline players.
     *
     * Never throws and never emits a wrong-song link: a blank input, a fetch failure, or no confident
     * match all yield null (the now-playing card simply carries no link line).
     */
    suspend fun resolveMediaLink(track: String, artist: String): String?
}

/** Why a magic-link resolution ended the way it did — one terminal outcome per [MagicLinkTrace]. */
enum class MagicLinkOutcome {
    /** Package isn't one of the supported magic-link apps; resolution was never attempted. */
    SKIPPED_UNSUPPORTED,

    /** Magic-link reconstruction is opted out for this package. */
    SKIPPED_DISABLED,

    /** No usable `UC…` channel id was present on the item (YouTube only). */
    NO_CHANNEL_ID,

    /** No usable query text was present — the video title, or the Apple Music track/artist. */
    NO_TITLE,

    /** The upstream source (uploads feed / iTunes Search API) could not be fetched/parsed. */
    FEED_ERROR,

    /** The source was fetched but nothing matched confidently (title / track+artist). */
    NO_MATCH,

    /** A confident match produced a url (`watch?v=` / `music.apple.com`; see `url`). */
    MATCHED,
}

/**
 * A release-safe, structured trace of a single resolution, dumped into diagnostics so a missing
 * `Link:` line can be explained and feed staleness measured (`feedNewestPublished` vs the
 * notification's `postTime`). Every field is optional except [outcome]; only the fields relevant to
 * that outcome are populated.
 */
data class MagicLinkTrace(
    val outcome: MagicLinkOutcome,
    val channelId: String? = null,
    val videoTitle: String? = null,
    val feedEntryCount: Int? = null,
    val feedNewestPublished: String? = null,
    val feedOldestPublished: String? = null,
    val feedNewestTitle: String? = null,
    val httpStatus: Int? = null,
    val error: String? = null,
    val videoId: String? = null,
    val url: String? = null,
    /** Always true when a network fetch happened: the feed URL carried a `nocache=` cache-buster. */
    val cacheBusted: Boolean = false,
    /** Which source produced the resolved url: `"rss"` (authoritative feed) or `"search"`; null on a miss. */
    val source: String? = null,
    /** True when the YouTube-search fallback was attempted (after an RSS miss). */
    val searchAttempted: Boolean = false,
    /** Number of parsed video results the search returned (0 on a consent/no-`ytInitialData` page). */
    val searchResultCount: Int? = null,
    /** How many of those search results belonged to the target channel id. */
    val searchChannelMatched: Int? = null,

    /** Which service produced this trace: `"youtube"` or `"appleMusic"`; null on a bare skip. */
    val service: String? = null,
    // --- Apple Music ---
    /** The now-playing track title queried against the iTunes Search API. */
    val mediaTrack: String? = null,
    /** The now-playing artist queried. */
    val mediaArtist: String? = null,
    /** The iTunes storefront (2-letter country) the lookup used. */
    val storefront: String? = null,
    /** The matched result's track / artist (confidence audit); null unless MATCHED. */
    val matchedTrack: String? = null,
    val matchedArtist: String? = null,
)

/** The resolved url (null when none) plus the [trace] describing how it was reached. */
data class MagicLinkResult(val url: String?, val trace: MagicLinkTrace)
