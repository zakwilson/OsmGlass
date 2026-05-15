package dev.glass.phone.render

/**
 * Bounded LRU cache mapping turn index → encoded PNG bytes. Used to keep already-rendered snippets
 * around so we don't re-render when the rider revisits a turn (or when a new ROUTE_START arrives
 * with overlapping turns).
 */
class RouteSnippetCache(private val capacity: Int = 64) {
    private val map = object : LinkedHashMap<Long, ByteArray>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean {
            return size > capacity
        }
    }

    @Synchronized
    fun get(routeId: Long, turnIndex: Int): ByteArray? = map[key(routeId, turnIndex)]

    @Synchronized
    fun put(routeId: Long, turnIndex: Int, png: ByteArray) {
        map[key(routeId, turnIndex)] = png
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size

    private fun key(routeId: Long, turnIndex: Int): Long = (routeId shl 32) or (turnIndex.toLong() and 0xffffffffL)
}
