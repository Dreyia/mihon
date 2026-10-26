package mihon.feature.translation.core.cache

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PageTranslationCacheTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `key depends on image bytes and settings`() {
        val cache = PageTranslationCache(dir)
        val a = cache.key(byteArrayOf(1, 2, 3), "cfg")
        assertEquals(a, cache.key(byteArrayOf(1, 2, 3), "cfg"))
        assertNotEquals(a, cache.key(byteArrayOf(1, 2, 4), "cfg"))
        assertNotEquals(a, cache.key(byteArrayOf(1, 2, 3), "cfg2"))
    }

    @Test
    fun `stores translated pages and no-text markers`() {
        val cache = PageTranslationCache(dir)
        assertNull(cache.get("k1"))
        cache.put("k1", byteArrayOf(9, 9))
        cache.put("k2", null)
        assertArrayEquals(byteArrayOf(9, 9), (cache.get("k1") as PageTranslationCache.Entry.Translated).bytes)
        assertEquals(PageTranslationCache.Entry.NoText, cache.get("k2"))
    }

    @Test
    fun `trims oldest entries when over the size limit`() {
        val cache = PageTranslationCache(dir, maxBytes = 250)
        for (i in 0 until 5) {
            cache.put("k$i", ByteArray(100))
            File(dir, "k$i.img").setLastModified(1_000_000L + i * 10_000)
        }
        cache.put("k5", ByteArray(100))
        assertTrue(cache.size() <= 250)
        assertNull(cache.get("k0"))
        assertTrue(cache.get("k5") is PageTranslationCache.Entry.Translated)
    }
}
