package space.linuxct.teleforward.data.link

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Locale
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
 * A single video parsed from a YouTube search results page's `ytInitialData`: the video id, its
 * title, and the uploader's channel id ([channelId], null when the byline carried no `UC…`
 * browseId). Used by the search fallback to pick the channel-scoped, title-matched result.
 */
data class SearchVideo(val videoId: String, val title: String, val channelId: String?)

/**
 * The [LinkResolver] implementation. Dispatches by package ([magicLinkKind]): YouTube upload
 * notifications reconstruct a `watch?v=` url from the channel id + title (this file's bulk), and
 * Apple Music now-playing notifications reconstruct a `music.apple.com` song url from the track +
 * artist via the iTunes Search API (see [reconstructAppleMusic] + [AppleMusic]).
 *
 * There is deliberately NO in-app feed cache: YouTube serves `videos.xml` from a URL-keyed CDN cache
 * that lags ~1h, so a just-published video (the one the notification is about) is absent from the
 * plain URL. Appending a unique `nocache=` query param busts that CDN cache and returns the fresh
 * feed (a `Cache-Control: no-cache` header alone is ignored by the CDN). Every fetch is therefore a
 * fresh, cache-busted network GET — an in-memory cache would just reintroduce the staleness we fix.
 *
 * Even cache-busted, the `videos.xml` CONTENT itself lags ~25–30 min for high-volume channels, so a
 * just-published video can still be absent from the feed. When the (authoritative, cheap) RSS attempt
 * finds no title match, we fall back to scraping YouTube's search results page — which surfaces the
 * new video within minutes — and pick the result whose uploader channel id AND title both match.
 *
 * Every step is wrapped so a network/parse failure can only ever produce a null url — it never
 * throws into the delivery worker.
 */
@Singleton
class LinkResolverImpl @Inject constructor(
    private val contactPhoneResolver: ContactPhoneResolver,
) : LinkResolver {

    /**
     * Test-only convenience: constructs with no contacts resolution (the no-op resolver). NOT
     * `@Inject` — Hilt uses only the primary constructor above and injects the real binding.
     */
    constructor() : this(NoopContactPhoneResolver)

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

    /** Outcome of a single raw-body GET (the iTunes Search API): the body, or an error. */
    private sealed interface BodyFetch {
        data class Success(val body: String) : BodyFetch
        data class Error(val httpStatus: Int?, val error: String?) : BodyFetch
    }

    override suspend fun resolve(item: OutboxEntity, disabledPackages: Set<String>): MagicLinkResult = try {
        val kind = magicLinkKind(item.packageName)
        when {
            kind == null ->
                MagicLinkResult(null, MagicLinkTrace(MagicLinkOutcome.SKIPPED_UNSUPPORTED))

            item.packageName in disabledPackages ->
                MagicLinkResult(null, MagicLinkTrace(MagicLinkOutcome.SKIPPED_DISABLED))

            kind == MagicLinkKind.YOUTUBE -> reconstruct(item)

            kind == MagicLinkKind.APPLE_MUSIC -> reconstructAppleMusic(item)

            else -> reconstructWhatsApp(item)
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
     * Apple Music reconstruction: from the now-playing track ([OutboxEntity.title]) + artist
     * ([OutboxEntity.body]), query Apple's public iTunes Search API and, on a confident track+artist
     * match, return the canonical `music.apple.com` song url. Never emits a wrong-song link — a miss
     * yields NO_MATCH (no `Link:` line). One cheap JSON GET; not lagged like YouTube's feed, so there
     * is no search fallback and no edit-after-send retry.
     */
    private fun reconstructAppleMusic(item: OutboxEntity): MagicLinkResult {
        val track = item.title?.trim()
        val artist = item.body?.trim()
        if (track.isNullOrBlank() || artist.isNullOrBlank()) {
            return MagicLinkResult(
                null,
                MagicLinkTrace(
                    outcome = MagicLinkOutcome.NO_TITLE,
                    service = AppleMusic.SERVICE,
                    mediaTrack = track?.takeUnless { it.isBlank() },
                    mediaArtist = artist?.takeUnless { it.isBlank() },
                ),
            )
        }

        val storefront = AppleMusic.storefront(Locale.getDefault().country)
        return when (val fetch = fetchBody(AppleMusic.searchUrl(artist, track, storefront))) {
            is BodyFetch.Error -> MagicLinkResult(
                null,
                MagicLinkTrace(
                    outcome = MagicLinkOutcome.FEED_ERROR,
                    service = AppleMusic.SERVICE,
                    mediaTrack = track,
                    mediaArtist = artist,
                    storefront = storefront,
                    httpStatus = fetch.httpStatus,
                    error = fetch.error,
                ),
            )

            is BodyFetch.Success -> {
                val tracks = AppleMusic.parseTracks(fetch.body)
                val match = AppleMusic.pickTrack(tracks, artist, track)
                if (match != null) {
                    MagicLinkResult(
                        match.url,
                        MagicLinkTrace(
                            outcome = MagicLinkOutcome.MATCHED,
                            service = AppleMusic.SERVICE,
                            mediaTrack = track,
                            mediaArtist = artist,
                            storefront = storefront,
                            url = match.url,
                            matchedTrack = match.track,
                            matchedArtist = match.artist,
                        ),
                    )
                } else {
                    MagicLinkResult(
                        null,
                        MagicLinkTrace(
                            outcome = MagicLinkOutcome.NO_MATCH,
                            service = AppleMusic.SERVICE,
                            mediaTrack = track,
                            mediaArtist = artist,
                            storefront = storefront,
                            error = "no confident match among ${tracks.size} results",
                        ),
                    )
                }
            }
        }
    }

    /**
     * WhatsApp reconstruction → a `web.whatsapp.com/send/` url (opens the chat directly in WhatsApp
     * Web/desktop). Tries, in
     * order: an individual phone-JID identifier, an unsaved contact's phone-shaped title (both
     * permission-free), then — only if the user opted into Contacts access — the sender's saved
     * contact behind its lookup uri. LIDs and groups yield no phone → NO_MATCH (no `Link:` line). No
     * network, and the trace omits the number/url (PII; diagnostics are shareable).
     */
    private fun reconstructWhatsApp(item: OutboxEntity): MagicLinkResult {
        val jidPhone = WhatsApp.phoneFromJid(item.conversationId)
        val (phone, source) = when {
            jidPhone != null -> jidPhone to WhatsApp.SOURCE_JID
            else -> {
                val titlePhone = WhatsApp.phoneFromTitle(item.title)
                if (titlePhone != null) {
                    titlePhone to WhatsApp.SOURCE_TITLE
                } else {
                    val uri = item.senderContactUri
                    val contactPhone = if (uri != null) contactPhoneResolver.resolve(uri) else null
                    if (contactPhone != null) contactPhone to WhatsApp.SOURCE_CONTACTS else null to null
                }
            }
        }

        return if (phone != null) {
            MagicLinkResult(
                WhatsApp.chatUrl(phone),
                MagicLinkTrace(
                    outcome = MagicLinkOutcome.MATCHED,
                    service = WhatsApp.SERVICE,
                    source = source,
                ),
            )
        } else {
            MagicLinkResult(
                null,
                MagicLinkTrace(
                    outcome = MagicLinkOutcome.NO_MATCH,
                    service = WhatsApp.SERVICE,
                    error = whatsAppMissReason(item),
                ),
            )
        }
    }

    /** PII-free reason a WhatsApp reconstruction found no phone (records the id KIND, never its value). */
    private fun whatsAppMissReason(item: OutboxEntity): String {
        val idKind = item.conversationId?.substringAfterLast('@', "")?.takeUnless { it.isBlank() } ?: "none"
        return "no phone (id=@$idKind, contactUri=${item.senderContactUri != null})"
    }

    /**
     * Shared tail of both resolution paths: try the authoritative RSS attempt first (one fresh
     * cache-busted feed fetch for [channelId] + confident title match against [title]); on an RSS miss
     * (NO_MATCH or FEED_ERROR) fall back to the YouTube-search scrape, which surfaces just-published
     * videos the lagging feed hasn't caught up to yet. [channelId]/[title] are validated by the caller.
     *
     * - RSS match → MATCHED, `source = "rss"`.
     * - RSS miss, search match → MATCHED, `source = "search"` (feed metadata from the RSS attempt is
     *   preserved in the trace, so it still shows the feed had entries but none matched).
     * - Both miss → the RSS miss trace, annotated with the search diagnostics (attempted / counts).
     */
    private fun fetchAndMatch(channelId: String, title: String): MagicLinkResult {
        val rss = when (val fetch = fetchFeed(channelId)) {
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

        // RSS is authoritative: a confident feed match wins and short-circuits the search.
        if (rss.url != null) {
            return rss.copy(trace = rss.trace.copy(source = SOURCE_RSS))
        }

        // RSS missed — try the search fallback (best-effort; never throws).
        val search = searchVideoId(channelId, title)
        if (search.videoId != null) {
            val url = watchUrl(search.videoId)
            return MagicLinkResult(
                url,
                rss.trace.copy(
                    outcome = MagicLinkOutcome.MATCHED,
                    videoId = search.videoId,
                    url = url,
                    source = SOURCE_SEARCH,
                    searchAttempted = true,
                    searchResultCount = search.resultCount,
                    searchChannelMatched = search.channelMatched,
                ),
            )
        }

        // Both sources missed: keep the RSS miss trace, annotate why the search missed too.
        return MagicLinkResult(
            null,
            rss.trace.copy(
                source = null,
                searchAttempted = true,
                searchResultCount = search.resultCount,
                searchChannelMatched = search.channelMatched,
            ),
        )
    }

    /** Outcome of the search fallback: the picked (channel+title matched) video id plus diagnostics. */
    private data class SearchResult(
        val videoId: String?,
        val resultCount: Int?,
        val channelMatched: Int?,
    )

    /**
     * The YouTube-search fallback: GET the desktop results page for [title], extract `ytInitialData`,
     * and pick the video whose uploader channel id == [channelId] AND whose title matches [title].
     * Entirely try/caught → a network/parse failure yields a null videoId (never throws). The returned
     * [SearchResult] also carries diagnostics for the trace (result count, channel-matched count).
     *
     * A desktop browser User-Agent + `Cookie: SOCS=CAISAiAD` are sent so YouTube serves the real
     * results page rather than the EU-consent interstitial (which carries no `ytInitialData`).
     */
    private fun searchVideoId(channelId: String, title: String): SearchResult = try {
        val request = Request.Builder()
            .url(searchUrl(title))
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Cookie", CONSENT_COOKIE)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        val html = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
        when {
            html == null -> SearchResult(videoId = null, resultCount = null, channelMatched = null)
            else -> {
                val json = extractYtInitialData(html)
                if (json == null) {
                    // Consent / unexpected page: no results parsed.
                    SearchResult(videoId = null, resultCount = 0, channelMatched = 0)
                } else {
                    val videos = parseSearchVideos(json)
                    SearchResult(
                        videoId = pickVideoId(videos, channelId, title),
                        resultCount = videos.size,
                        channelMatched = videos.count { it.channelId == channelId },
                    )
                }
            }
        }
    } catch (t: Throwable) {
        SearchResult(videoId = null, resultCount = null, channelMatched = null)
    }

    /** The desktop search-results URL for [title] (English/US locale). Pure/unit-testable. */
    fun searchUrl(title: String): String =
        "$SEARCH_BASE?search_query=${URLEncoder.encode(title, "UTF-8")}&hl=en&gl=US"

    /**
     * Extract the `ytInitialData` JSON object from a YouTube results page's HTML: locate the
     * `ytInitialData =` assignment (fallback marker `"ytInitialData":`), then balance braces from the
     * first `{` (respecting string literals + escapes) to isolate the JSON substring. Returns null when
     * the marker is absent (e.g. a consent page). Pure and unit-testable.
     */
    fun extractYtInitialData(html: String): String? {
        var marker = html.indexOf("ytInitialData =")
        if (marker < 0) marker = html.indexOf("\"ytInitialData\":")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null
        return balancedJson(html, start)
    }

    /** Return the substring of [text] from the `{` at [start] to its matching `}`, or null if unbalanced. */
    private fun balancedJson(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Pure: recursively walk a `ytInitialData`-shaped [json] object collecting every node that carries
     * an 11-char `videoId` AND a `title` (`title.runs[0].text` or `title.simpleText`), tagging each
     * with the uploader channel id (first `UC…` `browseId` under `ownerText` / `longBylineText` /
     * `shortBylineText`). Unit-testable without the network. Any parse failure yields an empty list.
     */
    fun parseSearchVideos(json: String): List<SearchVideo> = try {
        val out = ArrayList<SearchVideo>()
        collectSearchVideos(JSONObject(json), out)
        out
    } catch (t: Throwable) {
        emptyList()
    }

    /** DFS over a parsed JSON tree, appending every video-renderer-shaped node to [out]. */
    private fun collectSearchVideos(node: Any?, out: MutableList<SearchVideo>) {
        when (node) {
            is JSONObject -> {
                val videoId = (node.opt("videoId") as? String)?.takeIf { it.length == VIDEO_ID_LENGTH }
                if (videoId != null) {
                    val title = searchNodeTitle(node)
                    if (title != null) out += SearchVideo(videoId, title, searchNodeChannelId(node))
                }
                val keys = node.keys()
                while (keys.hasNext()) collectSearchVideos(node.opt(keys.next()), out)
            }

            is JSONArray -> {
                for (i in 0 until node.length()) collectSearchVideos(node.opt(i), out)
            }
        }
    }

    /** The renderer node's title text: `title.runs[0].text`, else `title.simpleText`, else null. */
    private fun searchNodeTitle(node: JSONObject): String? {
        val titleObj = node.optJSONObject("title") ?: return null
        titleObj.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeUnless { it.isEmpty() }
            ?.let { return it }
        return titleObj.optString("simpleText").takeUnless { it.isEmpty() }
    }

    /** The first `UC…` browseId under the node's byline fields (the uploader channel id), or null. */
    private fun searchNodeChannelId(node: JSONObject): String? {
        for (key in BYLINE_KEYS) {
            val runs = node.optJSONObject(key)?.optJSONArray("runs") ?: continue
            for (i in 0 until runs.length()) {
                val browseId = runs.optJSONObject(i)
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")
                if (browseId != null && YouTube.channelIdRegex.matches(browseId)) return browseId
            }
        }
        return null
    }

    /**
     * Pure: from parsed search [videos], return the videoId whose channel id == [channelId] AND whose
     * title matches [title] (exact trimmed case-insensitive, then normalized lowercase-alphanumeric —
     * mirrors [matchTitle]). Channel-scoping is REQUIRED: the same query returns same-title videos from
     * OTHER channels, so a wrong-channel match is never returned. Null when nothing channel+title matches.
     */
    fun pickVideoId(videos: List<SearchVideo>, channelId: String, title: String): String? {
        val channelMatched = videos.filter { it.channelId == channelId }
        if (channelMatched.isEmpty()) return null
        val wanted = title.trim()
        channelMatched.firstOrNull { it.title.trim().equals(wanted, ignoreCase = true) }
            ?.let { return it.videoId }
        val normWanted = normalize(wanted)
        if (normWanted.isEmpty()) return null
        return channelMatched.firstOrNull { normalize(it.title) == normWanted }?.videoId
    }

    /** A single GET returning the raw response body (used for the iTunes Search API JSON). */
    private fun fetchBody(url: String): BodyFetch = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            when {
                !response.isSuccessful -> BodyFetch.Error(httpStatus = response.code, error = null)
                else -> response.body?.string()?.let { BodyFetch.Success(it) }
                    ?: BodyFetch.Error(httpStatus = response.code, error = "empty body")
            }
        }
    } catch (t: Throwable) {
        BodyFetch.Error(httpStatus = null, error = t.message ?: t.javaClass.simpleName)
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

        /** Base URL of the desktop search-results page scraped by the fallback. */
        const val SEARCH_BASE = "https://www.youtube.com/results"

        /** Desktop browser UA so YouTube serves the `ytInitialData`-bearing results page. */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/121.0 Safari/537.36"

        /** Pre-accepted EU consent cookie so the results page isn't replaced by a consent interstitial. */
        const val CONSENT_COOKIE = "SOCS=CAISAiAD"

        /** A YouTube video id is always 11 url-safe base64 characters. */
        const val VIDEO_ID_LENGTH = 11

        /** Byline fields (in preference order) under a search renderer that carry the uploader browseId. */
        val BYLINE_KEYS = listOf("ownerText", "longBylineText", "shortBylineText")

        const val SOURCE_RSS = "rss"
        const val SOURCE_SEARCH = "search"
    }
}
