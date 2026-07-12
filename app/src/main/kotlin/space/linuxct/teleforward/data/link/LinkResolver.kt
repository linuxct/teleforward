package space.linuxct.teleforward.data.link

import space.linuxct.teleforward.data.db.entity.OutboxEntity

/**
 * Best-effort reconstruction of a "magic link" for an outbox item just before it is sent.
 *
 * Currently only YouTube subscription-upload notifications are supported: given the channel id
 * (extracted from the notification into [OutboxEntity.youtubeChannelId]) and the video title (the
 * notification body), the resolver fetches YouTube's public uploads feed and maps the two to the
 * video's `watch?v=` url. Everything is best-effort — any failure/timeout yields a null url, and the
 * item is forwarded normally without a `Link:` line.
 */
interface LinkResolver {

    /**
     * Attempt to reconstruct a link for [item]. Never throws: returns a [MagicLinkResult] whose
     * [MagicLinkResult.url] is null when the package is not a supported YouTube app, magic-link is
     * disabled for it ([disabledPackages]), or the video cannot be confidently reconstructed. The
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
}

/** Why a magic-link resolution ended the way it did — one terminal outcome per [MagicLinkTrace]. */
enum class MagicLinkOutcome {
    /** Package isn't one of the supported YouTube apps; resolution was never attempted. */
    SKIPPED_NOT_YOUTUBE,

    /** Magic-link reconstruction is opted out for this package. */
    SKIPPED_DISABLED,

    /** No usable `UC…` channel id was present on the item. */
    NO_CHANNEL_ID,

    /** No usable video title (the notification body) was present. */
    NO_TITLE,

    /** The uploads feed could not be fetched/parsed (see `httpStatus` / `error`). */
    FEED_ERROR,

    /** The feed was fetched but no entry title matched the video title. */
    NO_MATCH,

    /** A confident title match produced a `watch?v=` url (see `videoId` / `url`). */
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
)

/** The resolved url (null when none) plus the [trace] describing how it was reached. */
data class MagicLinkResult(val url: String?, val trace: MagicLinkTrace)
