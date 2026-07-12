package space.linuxct.teleforward.data.link

import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A single uploads-feed entry: the video id, its (entity-decoded) title, and the entry's raw
 * `<published>` timestamp (ISO-8601, may be null) used to measure feed staleness in the trace.
 */
data class VideoEntry(val videoId: String, val title: String, val published: String? = null)

/**
 * YouTube-only [LinkResolver]. Reconstructs a video url from the channel id + video title via the
 * public uploads feed.
 *
 * There is deliberately NO in-app feed cache: YouTube serves `videos.xml` from a URL-keyed CDN cache
 * that lags ~1h, so a just-published video (the one the notification is about) is absent from the
 * plain URL. Appending a unique `nocache=` query param busts that CDN cache and returns the fresh
 * feed (a `Cache-Control: no-cache` header alone is ignored by the CDN). Every fetch is therefore a
 * fresh, cache-busted network GET — an in-memory cache would just reintroduce the staleness we fix.
 *
 * Every step is wrapped so a network/parse failure can only ever produce a null url — it never
 * throws into the delivery worker.
 */
@Singleton
class LinkResolverImpl @Inject constructor() : LinkResolver {

    /** Dedicated, interceptor-free client with short timeouts (must NOT reuse the Telegram client). */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Outcome of a single feed fetch: parsed entries, or an error with http status / message. */
    private sealed interface FeedFetch {
        data class Success(val entries: List<VideoEntry>) : FeedFetch
        data class Error(val httpStatus: Int?, val error: String?) : FeedFetch
    }

    override suspend fun resolve(item: OutboxEntity, disabledPackages: Set<String>): MagicLinkResult = try {
        when {
            item.packageName !in YouTube.PACKAGES ->
                MagicLinkResult(null, MagicLinkTrace(MagicLinkOutcome.SKIPPED_NOT_YOUTUBE))

            item.packageName in disabledPackages ->
                MagicLinkResult(null, MagicLinkTrace(MagicLinkOutcome.SKIPPED_DISABLED))

            else -> reconstruct(item)
        }
    } catch (t: Throwable) {
        // Absolutely never let magic-link resolution surface an error into the send path.
        MagicLinkResult(
            null,
            MagicLinkTrace(MagicLinkOutcome.FEED_ERROR, error = t.message ?: t.javaClass.simpleName),
        )
    }

    /**
     * Retry-path resolution keyed off a persisted (channelId, title) pair rather than an
     * [OutboxEntity]. Validates the channel id + title, then runs the same cache-busted fetch +
     * confident match as [resolve]. Never throws (mirrors [resolve]'s guarantee).
     */
    override suspend fun resolveChannelVideo(channelId: String, title: String): MagicLinkResult = try {
        val cleanTitle = title.trim()
        when {
            !YouTube.channelIdRegex.matches(channelId) -> MagicLinkResult(
                null,
                MagicLinkTrace(MagicLinkOutcome.NO_CHANNEL_ID, channelId = channelId),
            )

            cleanTitle.isBlank() -> MagicLinkResult(
                null,
                MagicLinkTrace(MagicLinkOutcome.NO_TITLE, channelId = channelId),
            )

            else -> fetchAndMatch(channelId, cleanTitle)
        }
    } catch (t: Throwable) {
        MagicLinkResult(
            null,
            MagicLinkTrace(
                MagicLinkOutcome.FEED_ERROR,
                channelId = channelId,
                error = t.message ?: t.javaClass.simpleName,
            ),
        )
    }

    /**
     * Tier-1 reconstruction: requires a valid channel id AND a non-blank title (the video title, which
     * is the forwarded body). Fetches the (cache-busted) uploads feed and returns the watch url only
     * when a title matches confidently — no newest/fuzzy fallback, to avoid emitting the wrong video.
     */
    private fun reconstruct(item: OutboxEntity): MagicLinkResult {
        val channelId = item.youtubeChannelId
        if (channelId == null || !YouTube.channelIdRegex.matches(channelId)) {
            return MagicLinkResult(
                null,
                MagicLinkTrace(MagicLinkOutcome.NO_CHANNEL_ID, channelId = channelId),
            )
        }
        val title = item.body?.trim()
        if (title.isNullOrBlank()) {
            return MagicLinkResult(
                null,
                MagicLinkTrace(MagicLinkOutcome.NO_TITLE, channelId = channelId),
            )
        }

        return fetchAndMatch(channelId, title)
    }

    /**
     * Shared tail of both resolution paths: one fresh cache-busted feed fetch for [channelId], then a
     * confident title match against [title] (both are already validated by the caller). Returns a
     * FEED_ERROR result on fetch failure, otherwise MATCHED / NO_MATCH via [resultFromEntries].
     */
    private fun fetchAndMatch(channelId: String, title: String): MagicLinkResult =
        when (val fetch = fetchFeed(channelId)) {
            is FeedFetch.Error -> MagicLinkResult(
                null,
                MagicLinkTrace(
                    outcome = MagicLinkOutcome.FEED_ERROR,
                    channelId = channelId,
                    videoTitle = title,
                    httpStatus = fetch.httpStatus,
                    error = fetch.error,
                    cacheBusted = true,
                ),
            )

            is FeedFetch.Success -> resultFromEntries(channelId, title, fetch.entries)
        }

    /** One fresh, cache-busted network GET of the uploads feed for [channelId]. */
    private fun fetchFeed(channelId: String): FeedFetch = try {
        val request = Request.Builder()
            .url(feedUrl(channelId))
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            when {
                !response.isSuccessful -> FeedFetch.Error(httpStatus = response.code, error = null)
                else -> {
                    val xml = response.body?.string()
                    if (xml == null) {
                        FeedFetch.Error(httpStatus = response.code, error = "empty body")
                    } else {
                        FeedFetch.Success(parseFeed(xml))
                    }
                }
            }
        }
    } catch (t: Throwable) {
        FeedFetch.Error(httpStatus = null, error = t.message ?: t.javaClass.simpleName)
    }

    /**
     * The uploads-feed URL for [channelId] with a per-fetch cache-buster ([nocache]) appended so the
     * URL-keyed CDN returns a FRESH copy that includes the just-published video. Pure/unit-testable.
     */
    fun feedUrl(channelId: String, nocache: Long = System.currentTimeMillis()): String =
        "$FEED_BASE?channel_id=$channelId&nocache=$nocache"

    /**
     * Pure: map an already-fetched feed to a [MagicLinkResult] (MATCHED / NO_MATCH) with a fully
     * populated trace. Extracted so the outcome/trace shaping is unit-testable without the network.
     * Entries are newest-first (as YouTube serves them), so `first()` is newest and `last()` oldest.
     */
    fun resultFromEntries(
        channelId: String?,
        title: String,
        entries: List<VideoEntry>,
        cacheBusted: Boolean = true,
    ): MagicLinkResult {
        val newest = entries.firstOrNull()
        val oldest = entries.lastOrNull()
        val match = matchTitle(entries, title)
        return if (match != null) {
            val url = watchUrl(match.videoId)
            MagicLinkResult(
                url,
                MagicLinkTrace(
                    outcome = MagicLinkOutcome.MATCHED,
                    channelId = channelId,
                    videoTitle = title,
                    feedEntryCount = entries.size,
                    feedNewestPublished = newest?.published,
                    feedOldestPublished = oldest?.published,
                    feedNewestTitle = newest?.title,
                    videoId = match.videoId,
                    url = url,
                    cacheBusted = cacheBusted,
                ),
            )
        } else {
            MagicLinkResult(
                null,
                MagicLinkTrace(
                    outcome = MagicLinkOutcome.NO_MATCH,
                    channelId = channelId,
                    videoTitle = title,
                    feedEntryCount = entries.size,
                    feedNewestPublished = newest?.published,
                    feedOldestPublished = oldest?.published,
                    feedNewestTitle = newest?.title,
                    cacheBusted = cacheBusted,
                ),
            )
        }
    }

    private fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    /**
     * Parse an Atom uploads feed into its [VideoEntry] list. Pure and side-effect free (JVM +
     * Android), so it is unit-testable. XML numeric/named entities are auto-decoded by the DOM parser.
     */
    fun parseFeed(xml: String): List<VideoEntry> = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Not namespace-aware: entry children keep their literal `yt:` prefix, so we can look them
            // up by the exact tag name. Harden against XXE while we're here (feeds carry no DOCTYPE).
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = true
        }
        val doc = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val entryNodes = doc.getElementsByTagName("entry")
        val out = ArrayList<VideoEntry>(entryNodes.length)
        for (i in 0 until entryNodes.length) {
            val entry = entryNodes.item(i) as? Element ?: continue
            val videoId = firstText(entry, "yt:videoId") ?: firstText(entry, "videoId")
            val title = firstText(entry, "title")
            val published = firstText(entry, "published")
            if (videoId != null && title != null) out += VideoEntry(videoId, title, published)
        }
        out
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Find the entry whose title matches [target]: first a trimmed case-insensitive equality, then a
     * normalized comparison (lowercase, non-alphanumerics stripped) to absorb punctuation/whitespace
     * differences. Returns null when nothing matches — never a "closest" guess.
     */
    fun matchTitle(entries: List<VideoEntry>, target: String): VideoEntry? {
        val wanted = target.trim()
        entries.firstOrNull { it.title.trim().equals(wanted, ignoreCase = true) }?.let { return it }
        val normWanted = normalize(wanted)
        if (normWanted.isEmpty()) return null
        return entries.firstOrNull { normalize(it.title) == normWanted }
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun firstText(element: Element, tag: String): String? {
        val nodes = element.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent?.trim()?.takeUnless { it.isEmpty() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val READ_TIMEOUT_SECONDS = 5L
        const val CALL_TIMEOUT_SECONDS = 6L
        const val USER_AGENT = "TeleForward (Android)"
        const val FEED_BASE = "https://www.youtube.com/feeds/videos.xml"
    }
}
