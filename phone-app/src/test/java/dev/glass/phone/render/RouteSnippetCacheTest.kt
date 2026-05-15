package dev.glass.phone.render

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class RouteSnippetCacheTest {

    @Test fun `put then get returns the same bytes`() {
        val cache = RouteSnippetCache(capacity = 4)
        val bytes = byteArrayOf(1, 2, 3)
        cache.put(7L, 0, bytes)
        assertThat(cache.get(7L, 0)).isEqualTo(bytes)
    }

    @Test fun `evicts least recently used when over capacity`() {
        val cache = RouteSnippetCache(capacity = 2)
        cache.put(1L, 0, byteArrayOf(0))
        cache.put(1L, 1, byteArrayOf(1))
        // touch key 0 to make it recently used
        cache.get(1L, 0)
        cache.put(1L, 2, byteArrayOf(2))
        assertThat(cache.size()).isEqualTo(2)
        assertThat(cache.get(1L, 0)).isNotNull
        assertThat(cache.get(1L, 1)).isNull()
        assertThat(cache.get(1L, 2)).isNotNull
    }

    @Test fun `keys with same turnIndex but different routeIds do not collide`() {
        val cache = RouteSnippetCache()
        cache.put(1L, 5, byteArrayOf(0xA1.toByte()))
        cache.put(2L, 5, byteArrayOf(0xB2.toByte()))
        assertThat(cache.get(1L, 5)?.first()).isEqualTo(0xA1.toByte())
        assertThat(cache.get(2L, 5)?.first()).isEqualTo(0xB2.toByte())
    }
}
