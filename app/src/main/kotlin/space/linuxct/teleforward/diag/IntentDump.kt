package space.linuxct.teleforward.diag

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full dump of an [Intent] recovered from a probe (§9): action, data, type, component, categories,
 * flags, recursive extras (via [ProbeEnv.dumpBundle]) and `toUri(URI_INTENT_SCHEME)`. Every field is
 * isolated; harvestable strings are fed into the candidate sink.
 */
@Singleton
class IntentDump @Inject constructor() {

    fun dump(intent: Intent, env: ProbeEnv, depth: Int = 0): JSONObject {
        val o = JSONObject()
        runCatching { intent.action?.let { o.put("action", it) } }
        runCatching { intent.dataString?.let { o.put("data", it); env.addCandidate(it) } }
        runCatching { intent.type?.let { o.put("type", it) } }
        runCatching { intent.component?.flattenToString()?.let { o.put("component", it) } }
        @Suppress("DEPRECATION")
        runCatching { intent.`package`?.let { o.put("package", it) } }
        runCatching {
            val cats = intent.categories
            if (!cats.isNullOrEmpty()) o.put("categories", JSONArray(cats.toList()))
        }
        runCatching { o.put("flags", "0x" + Integer.toHexString(intent.flags)) }
        runCatching {
            val uri = intent.toUri(Intent.URI_INTENT_SCHEME)
            o.put("toUri", uri); env.addCandidate(uri)
        }
        runCatching {
            val extras = intent.extras
            if (extras != null && !extras.isEmpty) o.put("extras", env.dumpBundle(extras, depth + 1))
        }
        return o
    }
}
