package space.linuxct.teleforward.diag

import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import space.linuxct.teleforward.data.link.magicLinkKind
import space.linuxct.teleforward.service.resolveUserSerial
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds an exhaustive [ForensicRecord] (plan §1–§12) from a `(StatusBarNotification, RankingMap?)`
 * pair. Pure public-API extraction; no I/O (the [DiagStore] persists) and no hidden-API/reflection
 * access. Every section is isolated in try/catch so a probe failure never crashes the notification
 * listener, and no bitmap pixels are ever read (only URIs, types and metadata).
 */
@Singleton
class NotificationForensics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val iconProbe: IconProbe,
    private val pendingIntentProbe: PendingIntentProbe,
    private val intentDump: IntentDump,
) {

    fun capture(sbn: StatusBarNotification, rankingMap: RankingMap?): ForensicRecord {
        val env = ProbeEnv(context, iconProbe, pendingIntentProbe, intentDump)
        val root = JSONObject()
        runCatching { root.put("capturedAt", System.currentTimeMillis()) }
        val n = sbn.notification

        put(root, "identity") { identity(sbn, n, env) }
        put(root, "magicLinkCandidate") { magicLinkCandidate(sbn, n) }
        put(root, "ranking") { ranking(sbn, rankingMap, env) }
        put(root, "bubble") { bubble(n, env) }
        put(root, "people") { people(n, env) }
        put(root, "messagingStyle") { messagingStyle(n, env) }
        put(root, "extras") { env.dumpBundle(n.extras, 0) }
        put(root, "icons") { icons(n, env) }
        put(root, "actions") { actions(n, env) }
        put(root, "pendingIntents") { pendingIntents(n, env) }
        put(root, "remoteViews") { remoteViews(n) }

        // §11 candidate harvest + §12 normalization.
        runCatching {
            val harvested = UriHarvest.harvest(env.candidates)
            root.put("candidateUris", JSONArray().apply { harvested.forEach { put(it) } })
            val links = JSONArray()
            for (c in harvested) {
                val nl = DeepLinkHeuristics.normalize(c)
                links.put(
                    JSONObject()
                        .put("raw", nl.raw)
                        .put("normalized", nl.normalized ?: JSONObject.NULL)
                        .put("heuristic", nl.heuristic)
                        .put("appGuess", nl.appGuess ?: JSONObject.NULL)
                        .put("confidence", nl.confidence),
                )
            }
            root.put("deepLinks", links)
        }

        return ForensicRecord(root.toString())
    }

    private inline fun put(root: JSONObject, key: String, block: () -> Any?) {
        runCatching { block()?.let { root.put(key, it) } }
    }

    // --- §1 identity & flags -------------------------------------------------------------------

    private fun identity(sbn: StatusBarNotification, n: Notification, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        o.put("packageName", sbn.packageName)
        runCatching { o.put("opPkg", sbn.opPkg) }
        o.put("id", sbn.id)
        runCatching { sbn.tag?.let { o.put("tag", it) } }
        o.put("key", sbn.key)
        runCatching { sbn.groupKey?.let { o.put("groupKey", it) } }
        runCatching { sbn.overrideGroupKey?.let { o.put("overrideGroupKey", it) } }
        runCatching { o.put("user", sbn.user.toString()) }
        runCatching { o.put("userSerial", resolveUserSerial(context, sbn.user)) }
        o.put("postTime", sbn.postTime)
        runCatching { o.put("when", n.`when`) }
        runCatching { n.category?.let { o.put("category", it) } }
        runCatching { o.put("flags", decodeFlags(n.flags)) }
        runCatching { o.put("visibility", n.visibility) }
        runCatching { o.put("color", n.color) }
        runCatching { o.put("number", n.number) }
        runCatching { n.tickerText?.let { o.put("tickerText", it.toString()); env.addCandidate(it.toString()) } }
        runCatching { n.shortcutId?.let { o.put("shortcutId", it); env.addCandidate(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { n.locusId?.let { o.put("locusId", it.id); env.addCandidate(it.id) } }
        }
        runCatching { o.put("isGroupSummary", n.flags and Notification.FLAG_GROUP_SUMMARY != 0) }
        @Suppress("DEPRECATION")
        runCatching { o.put("isGroup", sbn.isGroup) }
        runCatching { o.put("hasPublicVersion", n.publicVersion != null) }
        runCatching { o.put("timeoutAfter", n.timeoutAfter) }
        runCatching { o.put("groupAlertBehavior", n.groupAlertBehavior) }
        return o
    }

    // --- magic-link candidate summary (capture-session aid) ------------------------------------

    /**
     * A compact [MagicLinkCandidate] summary of the reconstruction-relevant readable fields, so a
     * diagnostics capture session can be scanned for which apps leak a usable id (a Discord-shaped
     * snowflake in the shortcut, a WhatsApp phone-JID in the tag, …). Purely re-groups fields already
     * present elsewhere in the record — no pixels, no hidden APIs. Isolated in its own try/catch by the
     * [put] wrapper, so a probe failure never disturbs the capture.
     */
    private fun magicLinkCandidate(sbn: StatusBarNotification, n: Notification): JSONObject {
        val shortcutId = runCatching { n.shortcutId }.getOrNull()
        val locusId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { n.locusId?.id }.getOrNull()
        } else {
            null
        }
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull()
        return MagicLinkCandidate.summarize(
            packageName = sbn.packageName,
            alreadySupported = magicLinkKind(sbn.packageName) != null,
            tag = runCatching { sbn.tag }.getOrNull(),
            shortcutId = shortcutId,
            locusId = locusId,
            group = runCatching { n.group }.getOrNull(),
            isGroupConversation = style?.isGroupConversation,
            conversationTitle = style?.conversationTitle?.toString(),
            extrasKeys = runCatching { n.extras.keySet() }.getOrNull() ?: emptySet(),
            wearableDismissalId = runCatching {
                n.extras.getBundle(WEARABLE_EXTENSIONS_EXTRA)?.getString(DISMISSAL_ID_KEY)
            }.getOrNull(),
        )
    }

    private fun decodeFlags(flags: Int): JSONObject {
        val names = JSONArray()
        for ((bit, name) in FLAG_NAMES) if (flags and bit != 0) names.put(name)
        return JSONObject()
            .put("raw", "0x" + Integer.toHexString(flags))
            .put("names", names)
    }

    // --- §2 ranking ----------------------------------------------------------------------------

    private fun ranking(sbn: StatusBarNotification, rankingMap: RankingMap?, env: ProbeEnv): JSONObject? {
        if (rankingMap == null) return null
        val r = NotificationListenerService.Ranking()
        if (!runCatching { rankingMap.getRanking(sbn.key, r) }.getOrDefault(false)) return null
        val o = JSONObject()
        runCatching { o.put("importance", r.importance) }
        runCatching { o.put("rank", r.rank) }
        runCatching { o.put("isAmbient", r.isAmbient) }
        runCatching { o.put("matchesInterruptionFilter", r.matchesInterruptionFilter()) }
        runCatching { o.put("suppressedVisualEffects", r.suppressedVisualEffects) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { o.put("canBubble", r.canBubble()) }
        }
        runCatching { r.channel?.let { o.put("channel", channelJson(it, env)) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { o.put("isConversation", r.isConversation) }
            runCatching { r.conversationShortcutInfo?.let { o.put("conversationShortcut", shortcutJson(it, env)) } }
        }
        return o
    }

    private fun channelJson(channel: android.app.NotificationChannel, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        runCatching { o.put("id", channel.id); env.addCandidate(channel.id) }
        runCatching { channel.name?.let { o.put("name", it.toString()) } }
        runCatching { o.put("importance", channel.importance) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { channel.conversationId?.let { o.put("conversationId", it); env.addCandidate(it) } }
            runCatching { channel.parentChannelId?.let { o.put("parentChannelId", it) } }
        }
        return o
    }

    private fun shortcutJson(si: ShortcutInfo, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        runCatching { o.put("id", si.id); env.addCandidate(si.id) }
        @Suppress("DEPRECATION")
        runCatching { o.put("package", si.`package`) }
        runCatching { si.shortLabel?.let { o.put("shortLabel", it.toString()) } }
        runCatching { si.longLabel?.let { o.put("longLabel", it.toString()) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { si.locusId?.let { o.put("locusId", it.id); env.addCandidate(it.id) } }
        }
        o.put("intentNote", "ShortcutInfo.getIntent() returns null to non-owners")
        return o
    }

    // --- §3 bubble -----------------------------------------------------------------------------

    private fun bubble(n: Notification, env: ProbeEnv): JSONObject? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val b = n.bubbleMetadata ?: return null
        val o = JSONObject()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { b.shortcutId?.let { o.put("shortcutId", it); env.addCandidate(it) } }
        }
        runCatching { o.put("desiredHeight", b.desiredHeight) }
        runCatching { o.put("autoExpandBubble", b.autoExpandBubble) }
        runCatching { b.getIntent()?.let { o.put("intent", pendingIntentProbe.probe(it, env)) } }
        runCatching { b.getIcon()?.let { o.put("icon", iconProbe.probe(it, env)) } }
        return o
    }

    // --- §4 people -----------------------------------------------------------------------------

    private fun people(n: Notification, env: ProbeEnv): JSONObject? {
        val extras = n.extras
        val o = JSONObject()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                extras.parcelableArrayCompat<Person>(Notification.EXTRA_PEOPLE_LIST)?.let { list ->
                    val arr = JSONArray()
                    for (p in list) arr.put(personJson(p, env))
                    if (arr.length() > 0) o.put("peopleList", arr)
                }
            }
        }
        @Suppress("DEPRECATION")
        runCatching {
            extras.getStringArray(Notification.EXTRA_PEOPLE)?.let { ppl ->
                val arr = JSONArray()
                for (s in ppl) { arr.put(s); env.addCandidate(s) }
                if (arr.length() > 0) o.put("people", arr)
            }
        }
        return if (o.length() == 0) null else o
    }

    private fun personJson(p: Person, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        runCatching { p.name?.let { o.put("name", it.toString()) } }
        runCatching { p.key?.let { o.put("key", it); env.addCandidate(it) } }
        runCatching { p.uri?.let { o.put("uri", it); env.addCandidate(it) } }
        runCatching { o.put("isImportant", p.isImportant) }
        runCatching { o.put("isBot", p.isBot) }
        runCatching { p.icon?.let { o.put("icon", iconProbe.probe(it, env)) } }
        return o
    }

    // --- §5 MessagingStyle ---------------------------------------------------------------------

    private fun messagingStyle(n: Notification, env: ProbeEnv): JSONObject? {
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull() ?: return null
        val o = JSONObject()
        runCatching { style.conversationTitle?.let { o.put("conversationTitle", it.toString()); env.addCandidate(it.toString()) } }
        runCatching { o.put("isGroupConversation", style.isGroupConversation) }
        runCatching { o.put("self", compatPersonJson(style.user, env)) }
        val msgs = JSONArray()
        runCatching {
            for (m in style.messages) {
                val mo = JSONObject()
                runCatching { m.person?.let { mo.put("sender", compatPersonJson(it, env)) } }
                runCatching { m.text?.let { mo.put("text", it.toString()); env.addCandidate(it.toString()) } }
                runCatching { mo.put("timestamp", m.timestamp) }
                runCatching { m.dataUri?.let { mo.put("dataUri", it.toString()); env.addCandidate(it.toString()) } }
                runCatching { m.dataMimeType?.let { mo.put("dataMimeType", it) } }
                msgs.put(mo)
            }
        }
        o.put("messages", msgs)
        return o
    }

    private fun compatPersonJson(p: androidx.core.app.Person?, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        if (p == null) return o
        runCatching { p.name?.let { o.put("name", it.toString()) } }
        runCatching { p.key?.let { o.put("key", it); env.addCandidate(it) } }
        runCatching { p.uri?.let { o.put("uri", it); env.addCandidate(it) } }
        runCatching { o.put("isImportant", p.isImportant) }
        runCatching { o.put("isBot", p.isBot) }
        return o
    }

    // --- §7 icons ------------------------------------------------------------------------------

    private fun icons(n: Notification, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        runCatching { n.smallIcon?.let { o.put("smallIcon", iconProbe.probe(it, env)) } }
        runCatching { n.getLargeIcon()?.let { o.put("largeIcon", iconProbe.probe(it, env)) } }
        for (key in EXTRA_ICON_KEYS) {
            runCatching {
                n.extras.parcelableCompat<Icon>(key)?.let { o.put(key, iconProbe.probe(it, env)) }
            }
        }
        return o
    }

    // --- §8 actions ----------------------------------------------------------------------------

    private fun actions(n: Notification, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        val arr = JSONArray()
        runCatching { n.actions?.forEach { arr.put(actionJson(it, env)) } }
        o.put("actions", arr)
        runCatching {
            val we = NotificationCompat.WearableExtender(n)
            val warr = JSONArray()
            for (a in we.actions) warr.put(compatActionJson(a, env))
            if (warr.length() > 0) o.put("wearableActions", warr)
        }
        return o
    }

    private fun actionJson(a: Notification.Action, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        runCatching { a.title?.let { o.put("title", it.toString()); env.addCandidate(it.toString()) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { o.put("semanticAction", a.semanticAction) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { o.put("isContextual", a.isContextual) }
        }
        runCatching { a.getIcon()?.let { o.put("icon", iconProbe.probe(it, env)) } }
        runCatching { a.actionIntent?.let { o.put("intent", pendingIntentProbe.probe(it, env)) } }
        runCatching {
            a.remoteInputs?.let { ris ->
                val ra = JSONArray()
                for (ri in ris) ra.put(remoteInputJson(ri.resultKey, ri.label?.toString(), ri.choices, env))
                if (ra.length() > 0) o.put("remoteInputs", ra)
            }
        }
        return o
    }

    private fun compatActionJson(a: NotificationCompat.Action, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        runCatching { a.title?.let { o.put("title", it.toString()); env.addCandidate(it.toString()) } }
        runCatching { a.actionIntent?.let { o.put("intent", pendingIntentProbe.probe(it, env)) } }
        runCatching {
            a.remoteInputs?.let { ris ->
                val ra = JSONArray()
                for (ri in ris) ra.put(remoteInputJson(ri.resultKey, ri.label?.toString(), ri.choices, env))
                if (ra.length() > 0) o.put("remoteInputs", ra)
            }
        }
        return o
    }

    private fun remoteInputJson(resultKey: String?, label: String?, choices: Array<CharSequence>?, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        runCatching { resultKey?.let { o.put("resultKey", it) } }
        runCatching { label?.let { o.put("label", it); env.addCandidate(it) } }
        runCatching {
            if (choices != null) {
                val arr = JSONArray()
                for (c in choices) { arr.put(c.toString()); env.addCandidate(c.toString()) }
                if (arr.length() > 0) o.put("choices", arr)
            }
        }
        return o
    }

    // --- §9 PendingIntents ---------------------------------------------------------------------

    private fun pendingIntents(n: Notification, env: ProbeEnv): JSONObject {
        val o = JSONObject()
        runCatching { n.contentIntent?.let { o.put("contentIntent", pendingIntentProbe.probe(it, env)) } }
        runCatching { n.deleteIntent?.let { o.put("deleteIntent", pendingIntentProbe.probe(it, env)) } }
        runCatching { n.fullScreenIntent?.let { o.put("fullScreenIntent", pendingIntentProbe.probe(it, env)) } }
        return o
    }

    // --- §10 RemoteViews -----------------------------------------------------------------------

    /**
     * Custom RemoteViews carry no public accessor for their internal action list (reading it needs
     * hidden-API reflection, which is not Play-compliant), so we record only whether each public
     * `contentView` / `bigContentView` / `headsUpContentView` is present.
     */
    private fun remoteViews(n: Notification): JSONObject {
        val o = JSONObject()
        @Suppress("DEPRECATION")
        runCatching { o.put("hasContentView", n.contentView != null) }
        @Suppress("DEPRECATION")
        runCatching { o.put("hasBigContentView", n.bigContentView != null) }
        @Suppress("DEPRECATION")
        runCatching { o.put("hasHeadsUpContentView", n.headsUpContentView != null) }
        return o
    }

    private companion object {
        val FLAG_NAMES: List<Pair<Int, String>> = listOf(
            Notification.FLAG_SHOW_LIGHTS to "SHOW_LIGHTS",
            Notification.FLAG_ONGOING_EVENT to "ONGOING_EVENT",
            Notification.FLAG_INSISTENT to "INSISTENT",
            Notification.FLAG_ONLY_ALERT_ONCE to "ONLY_ALERT_ONCE",
            Notification.FLAG_AUTO_CANCEL to "AUTO_CANCEL",
            Notification.FLAG_NO_CLEAR to "NO_CLEAR",
            Notification.FLAG_FOREGROUND_SERVICE to "FOREGROUND_SERVICE",
            Notification.FLAG_HIGH_PRIORITY to "HIGH_PRIORITY",
            Notification.FLAG_LOCAL_ONLY to "LOCAL_ONLY",
            Notification.FLAG_GROUP_SUMMARY to "GROUP_SUMMARY",
            Notification.FLAG_BUBBLE to "BUBBLE",
        )

        /** The AndroidX Wear extras bundle and its dismissal-id key — a known id hiding place. */
        const val WEARABLE_EXTENSIONS_EXTRA = "android.wearable.EXTENSIONS"
        const val DISMISSAL_ID_KEY = "dismissalId"

        val EXTRA_ICON_KEYS: List<String> = listOf(
            Notification.EXTRA_LARGE_ICON,
            Notification.EXTRA_LARGE_ICON_BIG,
            Notification.EXTRA_PICTURE_ICON,
        )
    }
}

// --- Bundle parcelable compat helpers (file-private) -------------------------------------------

@Suppress("DEPRECATION")
private inline fun <reified T> Bundle.parcelableCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        getParcelable(key) as? T
    }

@Suppress("DEPRECATION", "UNCHECKED_CAST")
private inline fun <reified T : Parcelable> Bundle.parcelableArrayCompat(key: String): Array<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArray(key, T::class.java)
    } else {
        (getParcelableArray(key) as? Array<*>)?.filterIsInstance<T>()?.toTypedArray()
    }
