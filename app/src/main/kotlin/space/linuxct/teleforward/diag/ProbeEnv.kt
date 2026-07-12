package space.linuxct.teleforward.diag

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.text.Spanned
import android.text.style.URLSpan
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-capture probe environment: the shared candidate-string sink (fed into [UriHarvest]) plus the
 * recursive Bundle/value/CharSequence dumper used by every probe. Created fresh by
 * [NotificationForensics] for each notification with the already-built probe singletons, so there is
 * no DI cycle (probes receive this as a method parameter; they do not hold it).
 *
 * Every step is best-effort; unreadable values are recorded as a small marker rather than thrown.
 */
class ProbeEnv(
    val context: Context,
    private val iconProbe: IconProbe,
    private val pendingIntentProbe: PendingIntentProbe,
    private val intentDump: IntentDump,
) {

    /** De-duplicated set of every captured string, harvested for URIs in §11. */
    val candidates = LinkedHashSet<String>()

    fun addCandidate(s: String?) {
        if (s.isNullOrBlank()) return
        val v = s.trim()
        if (v.length in 1..MAX_STRING) candidates += v
    }

    fun probeIcon(icon: Icon?): JSONObject? = runCatching { iconProbe.probe(icon, this) }.getOrNull()

    fun probePendingIntent(pi: PendingIntent?): JSONObject? =
        runCatching { pendingIntentProbe.probe(pi, this) }.getOrNull()

    /** Recursively dump every key of [bundle] into a JSONObject (depth/size guarded). */
    fun dumpBundle(bundle: Bundle?, depth: Int): JSONObject {
        val o = JSONObject()
        if (bundle == null) return o
        if (depth > MAX_DEPTH) return o.put("<truncated>", "max-depth")
        val keys = runCatching { bundle.keySet() }.getOrNull() ?: return o
        for (key in keys) {
            val value = try {
                @Suppress("DEPRECATION")
                dumpValue(bundle.get(key), depth)
            } catch (t: Throwable) {
                "EXCEPTION(${t.javaClass.simpleName})"
            }
            runCatching { o.put(key, value) }
        }
        return o
    }

    /** Dispatch a single value to the right probe/serializer; harvests any stringy content. */
    fun dumpValue(value: Any?, depth: Int): Any {
        if (value == null) return JSONObject.NULL
        if (depth > MAX_DEPTH) return "<max-depth>"
        return when (value) {
            is CharSequence -> dumpCharSequence(value)
            is Uri -> { addCandidate(value.toString()); typed("Uri", value.toString()) }
            is Intent -> typedObj("Intent", intentDump.dump(value, this, depth + 1))
            is PendingIntent -> typedObj("PendingIntent", pendingIntentProbe.probe(value, this) ?: JSONObject())
            is Icon -> typedObj("Icon", iconProbe.probe(value, this) ?: JSONObject())
            is Bundle -> typedObj("Bundle", dumpBundle(value, depth + 1))
            is Boolean, is Int, is Long, is Double, is Float, is Short, is Byte -> value
            is Char -> value.toString()
            is IntArray -> JSONArray(value.toList())
            is LongArray -> JSONArray(value.toList())
            is DoubleArray -> JSONArray(value.toList())
            is FloatArray -> JSONArray(value.map { it.toDouble() })
            is BooleanArray -> JSONArray(value.toList())
            is ByteArray -> "byte[${value.size}]"
            is CharArray -> String(value)
            is Array<*> -> dumpArray(value, depth)
            is List<*> -> dumpArray(value.toTypedArray(), depth)
            else -> {
                val s = value.toString()
                addCandidate(s)
                typed(value.javaClass.simpleName, s)
            }
        }
    }

    private fun dumpArray(arr: Array<*>, depth: Int): JSONArray {
        val out = JSONArray()
        for ((i, e) in arr.withIndex()) {
            if (i >= MAX_ARRAY) { out.put("<truncated:${arr.size}>"); break }
            out.put(dumpValue(e, depth + 1))
        }
        return out
    }

    /** Text + any URLSpan URLs (a URLSpan is a direct, high-value deep link). */
    fun dumpCharSequence(cs: CharSequence): Any {
        val text = cs.toString()
        addCandidate(text)
        if (cs is Spanned) {
            val spans = runCatching { cs.getSpans(0, cs.length, URLSpan::class.java) }.getOrNull()
            if (!spans.isNullOrEmpty()) {
                val urls = JSONArray()
                for (sp in spans) sp.url?.let { addCandidate(it); urls.put(it) }
                if (urls.length() > 0) return JSONObject().put("text", text).put("urls", urls)
            }
        }
        return text
    }

    private fun typed(type: String, value: String) = JSONObject().put("type", type).put("value", value)
    private fun typedObj(type: String, value: JSONObject) = JSONObject().put("type", type).put("value", value)

    private companion object {
        const val MAX_DEPTH = 6
        const val MAX_ARRAY = 64
        const val MAX_STRING = 8192
    }
}
