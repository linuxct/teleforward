package space.linuxct.teleforward.util

/**
 * A tiny LRU map with a **hard** entry cap.
 *
 * Every in-process cache in this app is keyed by package name, which is bounded in practice but not
 * by construction — a compromised or pathological key source would otherwise grow it without limit.
 * The cap makes the worst case a fixed, small amount of memory instead of an OOM.
 *
 * Eviction must never change behaviour, only cost: callers treat a miss as "unknown" and do the real
 * work to find the answer, rather than assuming the cached-away value.
 */
class BoundedCache<K : Any, V : Any>(private val maxEntries: Int) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = object : LinkedHashMap<K, V>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        entries[key] = value
    }

    @Synchronized
    fun remove(key: K): V? = entries.remove(key)

    /** Existing value, or the result of [compute] stored under [key]. */
    @Synchronized
    fun getOrPut(key: K, compute: () -> V): V = entries.getOrPut(key, compute)

    @Synchronized
    fun size(): Int = entries.size

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
