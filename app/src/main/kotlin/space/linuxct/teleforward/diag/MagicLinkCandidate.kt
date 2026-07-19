package space.linuxct.teleforward.diag

import org.json.JSONArray
import org.json.JSONObject

/**
 * A compact, scannable "does this app leak a usable id?" summary for a diagnostics capture SESSION.
 *
 * The full [NotificationForensics] record is exhaustive but noisy — to decide whether a not-yet-supported
 * app (GitHub, Slack, Discord, Telegram, …) can get a magic link, you only need the handful of fields a
 * reconstruction would key off: the `tag`, `shortcutId`, `locusId`, `group`, whether it's a conversation,
 * and which id-bearing extras are present. This pulls exactly those into one object, plus a set of pure
 * heuristic **signals** (a Discord-shaped snowflake in the shortcut, a WhatsApp phone-JID in the tag, …),
 * so a session of captured notifications can be reviewed at a glance for which apps are worth wiring up.
 *
 * PII note: every value echoed here already appears verbatim in the record's `identity` section, so this
 * adds no new exposure — it only re-groups the load-bearing fields. Everything is pure and unit-testable.
 */
object MagicLinkCandidate {

    /** A 17–19 digit id (Discord/Twitter snowflake shape). */
    private val SNOWFLAKE = Regex("^\\d{17,19}$")

    /** A WhatsApp individual phone-JID: `<e164digits>@s.whatsapp.net` (the phone is recoverable). */
    private val PHONE_JID = Regex("^\\d{6,15}@s\\.whatsapp\\.net$")

    /**
     * Extras keys observed (or plausibly) to carry a reconstruction id. Surfaced as a present/absent
     * list; the actual values live in the record's full `extras` dump. Extend as sessions reveal more.
     */
    val INTERESTING_EXTRAS_KEYS: List<String> = listOf(
        "latestMessageId", // Discord: the message snowflake (readable; pairs with shortcutId==channelId)
        "chime.slot_key",  // YouTube: the channel/video id (already used by the YouTube magic link)
    )

    /** True when [value] is a bare 17–19 digit snowflake (Discord/Twitter id shape). Pure. */
    fun looksLikeSnowflake(value: String?): Boolean = value != null && SNOWFLAKE.matches(value)

    /** True when [value] is an individual WhatsApp phone-JID (phone recoverable). Pure. */
    fun looksLikePhoneJid(value: String?): Boolean = value != null && PHONE_JID.matches(value)

    /**
     * Build the summary object. Callers pass the already-extracted readable fields (see
     * [NotificationForensics]); this stays free of Android types so it is unit-testable on the JVM.
     */
    fun summarize(
        packageName: String,
        alreadySupported: Boolean,
        tag: String?,
        shortcutId: String?,
        locusId: String?,
        group: String?,
        isGroupConversation: Boolean?,
        conversationTitle: String?,
        extrasKeys: Set<String>,
        /**
         * The Wear `dismissalId` from the nested `android.wearable.EXTENSIONS` bundle. Worth surfacing
         * for **every** app: it is a known hiding place for ids that appear nowhere else — it is exactly
         * where Telegram leaks its chat + message id — and it is easy to miss in the raw dump because it
         * sits one bundle down.
         */
        wearableDismissalId: String? = null,
        /**
         * Any AT-URI / id-bearing text recovered from Expo's marshalled notification payload
         * (`expo.notification_request`). Worth surfacing for **every** app: Expo populates this for
         * *any* app built with it, and it is where Bluesky's post id hides — invisible in a raw dump
         * because it is a byte array, not a string.
         */
        expoPayloadUri: String? = null,
    ): JSONObject {
        val o = JSONObject()
        o.put("package", packageName)
        // Already covered by an existing MagicLinkKind — so a session can ignore it and focus on the rest.
        o.put("alreadySupported", alreadySupported)
        tag?.let { o.put("tag", it) }
        shortcutId?.let { o.put("shortcutId", it) }
        locusId?.let { o.put("locusId", it) }
        group?.let { o.put("group", it) }
        isGroupConversation?.let { o.put("isGroupConversation", it) }
        conversationTitle?.let { o.put("conversationTitle", it) }
        wearableDismissalId?.let { o.put("wearableDismissalId", it) }
        expoPayloadUri?.let { o.put("expoPayloadUri", it) }

        val present = JSONArray()
        for (key in INTERESTING_EXTRAS_KEYS) if (key in extrasKeys) present.put(key)
        if (present.length() > 0) o.put("interestingExtras", present)

        o.put(
            "signals",
            JSONObject()
                .put("snowflakeInShortcut", looksLikeSnowflake(shortcutId))
                .put("snowflakeInLocus", looksLikeSnowflake(locusId))
                .put("snowflakeInTag", looksLikeSnowflake(tag))
                .put("phoneJidInTag", looksLikePhoneJid(tag))
                .put("phoneJidInShortcut", looksLikePhoneJid(shortcutId))
                .put("hasShortcutId", shortcutId != null)
                .put("hasLocusId", locusId != null)
                .put("hasWearableDismissalId", wearableDismissalId != null)
                .put("hasExpoPayloadUri", expoPayloadUri != null),
        )
        return o
    }
}
