package io.ygdrasil.kalligraphie.font.glyph

import java.util.LinkedHashMap

class GlyphCache(
    private val maxEntries: Int = 512,
    private val maxBytes: Long = 4L * 1024L * 1024L,
) {
    private val entries = LinkedHashMap<GlyphStrikeKey, A8Bitmap>(maxEntries, 0.75f, true)
    private var totalBytes: Long = 0L

    fun getOrRasterize(
        key: GlyphStrikeKey,
        rasterizer: () -> A8Bitmap?,
    ): A8Bitmap? {
        entries[key]?.let { return it }
        val bitmap = rasterizer() ?: return null
        put(key, bitmap)
        return bitmap
    }

    fun put(key: GlyphStrikeKey, bitmap: A8Bitmap) {
        val size = bitmap.width.toLong() * bitmap.height.toLong()
        entries.remove(key)?.let { totalBytes -= it.occupancySize() }

        entries[key] = bitmap
        totalBytes += size

        while ((entries.size > maxEntries || totalBytes > maxBytes) && entries.isNotEmpty()) {
            val eldest = entries.entries.first()
            totalBytes -= eldest.value.occupancySize()
            entries.remove(eldest.key)
        }
    }

    fun invalidate() {
        entries.clear()
        totalBytes = 0L
    }

    fun occupancy(): CacheOccupancy = CacheOccupancy(
        entryCount = entries.size,
        byteCount = totalBytes,
    )
}

data class CacheOccupancy(
    val entryCount: Int,
    val byteCount: Long,
)
