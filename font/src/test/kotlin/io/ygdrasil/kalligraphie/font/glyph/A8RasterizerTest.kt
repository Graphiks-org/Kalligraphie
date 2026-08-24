package io.ygdrasil.kalligraphie.font.glyph

import io.ygdrasil.kalligraphie.font.scaler.GlyphScaler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class A8RasterizerTest {

    @Test
    fun `A8 bitmap rejects a negative width`() {
        assertThrows(IllegalArgumentException::class.java) {
            A8Bitmap(width = -1, height = 0, pixels = ByteArray(0))
        }
    }

    @Test
    fun `A8 bitmap rejects an empty buffer for a one pixel bitmap`() {
        assertThrows(IllegalArgumentException::class.java) {
            A8Bitmap(width = 1, height = 1, pixels = ByteArray(0))
        }
    }

    @Test
    fun `A8 bitmap rejects dimensions whose pixel buffer is not representable`() {
        assertThrows(IllegalArgumentException::class.java) {
            A8Bitmap(width = Int.MAX_VALUE, height = Int.MAX_VALUE, pixels = ByteArray(1))
        }
    }

    @Test
    fun `A8 rasterizer produces non-empty bitmap for Liberation Sans 'A' at 32px`() {
        val fontBytes = javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")!!
            .readBytes()
        val scaler = GlyphScaler.fromBytes(fontBytes)
        val glyph = scaler.scaleGlyph(
            glyphId = scaler.glyphIdForCodepoint('A'.code)!!,
            size = 32.0f,
            sourceCodepoint = 'A'.code,
        )

        val rasterizer = A8Rasterizer()
        val bitmap = rasterizer.rasterize(glyph)

        assertNotNull(bitmap)
        bitmap!!
        assertTrue(bitmap.width > 0)
        assertTrue(bitmap.height > 0)
        assertTrue(bitmap.pixels.any { it != 0.toByte() }, "glyph should have non-empty coverage")
        assertEquals(bitmap.width * bitmap.height, bitmap.pixels.size)
    }

    @Test
    fun `strike key is deterministic for same glyph+size+transform`() {
        val key1 = GlyphStrikeKey(glyphId = 36, size = 32.0f, subpixelX = 0, subpixelY = 0)
        val key2 = GlyphStrikeKey(glyphId = 36, size = 32.0f, subpixelX = 0, subpixelY = 0)
        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())
        assertEquals(key1.cacheHash(), key2.cacheHash())
    }

    @Test
    fun `strike keys differ for different glyph ids`() {
        val keyA = GlyphStrikeKey(glyphId = 36, size = 32.0f, subpixelX = 0, subpixelY = 0)
        val keyB = GlyphStrikeKey(glyphId = 37, size = 32.0f, subpixelX = 0, subpixelY = 0)
        assertNotEquals(keyA, keyB)
    }

    @Test
    fun `cache getOrRasterize returns cached entry on second call`() {
        val cache = GlyphCache()
        val key = GlyphStrikeKey(glyphId = 1, size = 16.0f, subpixelX = 0, subpixelY = 0)
        val bitmap = A8Bitmap(2, 2, byteArrayOf(1, 2, 3, 4))

        var callCount = 0
        val supplier = {
            callCount++
            bitmap
        }

        val result1 = cache.getOrRasterize(key, supplier)
        val result2 = cache.getOrRasterize(key, supplier)

        assertNotNull(result1)
        assertNotNull(result2)
        assertEquals(1, callCount, "supplier should be called only once; cache should satisfy second call")
        assertTrue(result1!!.pixels.contentEquals(result2!!.pixels))
    }

    @Test
    fun `cache invalidate clears all entries`() {
        val cache = GlyphCache()
        val key = GlyphStrikeKey(glyphId = 1, size = 16.0f, subpixelX = 0, subpixelY = 0)
        val bitmap = A8Bitmap(1, 1, byteArrayOf(42))

        var callCount = 0
        val supplier = {
            callCount++
            bitmap
        }

        cache.getOrRasterize(key, supplier)
        assertEquals(1, cache.occupancy().entryCount)

        cache.invalidate()
        assertEquals(0, cache.occupancy().entryCount)
        assertEquals(0L, cache.occupancy().byteCount)

        cache.getOrRasterize(key, supplier)
        assertEquals(2, callCount, "supplier should be called again after invalidation")
    }

    @Test
    fun `cache evicts eldest entry when capacity exceeded`() {
        val cache = GlyphCache(maxEntries = 2, maxBytes = Long.MAX_VALUE)
        val key1 = GlyphStrikeKey(glyphId = 1, size = 16.0f, subpixelX = 0, subpixelY = 0)
        val key2 = GlyphStrikeKey(glyphId = 2, size = 16.0f, subpixelX = 0, subpixelY = 0)
        val key3 = GlyphStrikeKey(glyphId = 3, size = 16.0f, subpixelX = 0, subpixelY = 0)

        cache.put(key1, A8Bitmap(1, 1, byteArrayOf(1)))
        cache.put(key2, A8Bitmap(1, 1, byteArrayOf(2)))
        cache.put(key3, A8Bitmap(1, 1, byteArrayOf(3)))

        assertEquals(2, cache.occupancy().entryCount)
        assertNull(cache.getOrRasterize(key1) { null })
    }

    @Test
    fun `cache uses LRU eviction - recently accessed entry survives`() {
        val cache = GlyphCache(maxEntries = 2, maxBytes = Long.MAX_VALUE)
        val key1 = GlyphStrikeKey(glyphId = 1, size = 16.0f, subpixelX = 0, subpixelY = 0)
        val key2 = GlyphStrikeKey(glyphId = 2, size = 16.0f, subpixelX = 0, subpixelY = 0)
        val key3 = GlyphStrikeKey(glyphId = 3, size = 16.0f, subpixelX = 0, subpixelY = 0)

        cache.put(key1, A8Bitmap(1, 1, byteArrayOf(1)))
        cache.put(key2, A8Bitmap(1, 1, byteArrayOf(2)))

        // Access key1 to mark it recently used; key2 becomes least-recently-used
        assertNotNull(cache.getOrRasterize(key1) { null })

        cache.put(key3, A8Bitmap(1, 1, byteArrayOf(3)))

        assertEquals(2, cache.occupancy().entryCount)
        // key1 was accessed recently, so it should survive; key2 should be evicted
        assertNotNull(cache.getOrRasterize(key1) { null })
        assertNull(cache.getOrRasterize(key2) { null })
    }

    @Test
    fun `rasterizer returns null for empty glyph`() {
        val fontBytes = javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")!!
            .readBytes()
        val scaler = GlyphScaler.fromBytes(fontBytes)
        val space = scaler.scaleGlyph(
            glyphId = scaler.glyphIdForCodepoint(' '.code)!!,
            size = 32.0f,
            sourceCodepoint = ' '.code,
        )

        val rasterizer = A8Rasterizer()
        val bitmap = rasterizer.rasterize(space)

        assertNull(bitmap, "space character should produce null bitmap (no contours)")
    }

    @Test
    fun `rasterizer produces deterministic output for same input`() {
        val fontBytes = javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")!!
            .readBytes()
        val scaler = GlyphScaler.fromBytes(fontBytes)
        val glyph = scaler.scaleGlyph(
            glyphId = scaler.glyphIdForCodepoint('A'.code)!!,
            size = 32.0f,
            sourceCodepoint = 'A'.code,
        )

        val rasterizer = A8Rasterizer()
        val bitmap1 = rasterizer.rasterize(glyph)
        val bitmap2 = rasterizer.rasterize(glyph)

        assertNotNull(bitmap1)
        assertNotNull(bitmap2)
        bitmap1!!
        bitmap2!!
        assertEquals(bitmap1.width, bitmap2.width)
        assertEquals(bitmap1.height, bitmap2.height)
        assertTrue(bitmap1.pixels.contentEquals(bitmap2.pixels), "same input should produce identical output")
    }
}
