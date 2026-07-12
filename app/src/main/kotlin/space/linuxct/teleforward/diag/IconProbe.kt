package space.linuxct.teleforward.diag

import android.graphics.drawable.Icon
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §7 Icon probe (public API only). Records the icon type and, for URI-backed icons, the public
 * content URI (a thumbnail URI can leak an id → harvested). No pixels, hidden fields or resource
 * internals are read — only the public [Icon.getType] / [Icon.getUri] getters. Every access is
 * isolated.
 */
@Singleton
class IconProbe @Inject constructor() {

    fun probe(icon: Icon?, env: ProbeEnv): JSONObject? {
        if (icon == null) return null
        val o = JSONObject()
        val type = runCatching { icon.type }.getOrNull()
        o.put("type", typeName(type))
        if (type == TYPE_URI || type == TYPE_URI_ADAPTIVE_BITMAP) {
            runCatching {
                icon.uri?.toString()?.let { o.put("uri", it); env.addCandidate(it) }
            }
        }
        return o
    }

    private fun typeName(type: Int?): String = when (type) {
        TYPE_BITMAP -> "BITMAP"
        TYPE_RESOURCE -> "RESOURCE"
        TYPE_DATA -> "DATA"
        TYPE_URI -> "URI"
        TYPE_ADAPTIVE_BITMAP -> "ADAPTIVE_BITMAP"
        TYPE_URI_ADAPTIVE_BITMAP -> "URI_ADAPTIVE_BITMAP"
        null -> "UNKNOWN"
        else -> "TYPE_$type"
    }

    private companion object {
        // Mirrors android.graphics.drawable.Icon.TYPE_* (public constants).
        const val TYPE_BITMAP = 1
        const val TYPE_RESOURCE = 2
        const val TYPE_DATA = 3
        const val TYPE_URI = 4
        const val TYPE_ADAPTIVE_BITMAP = 5
        const val TYPE_URI_ADAPTIVE_BITMAP = 6
    }
}
