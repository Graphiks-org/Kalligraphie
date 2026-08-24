package io.ygdrasil.kalligraphie.font.atlas

import io.ygdrasil.kalligraphie.font.glyph.A8Bitmap
import io.ygdrasil.kalligraphie.font.glyph.GlyphStrikeKey

data class AtlasRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

private class AtlasCursor(
    private val width: Int,
    private val height: Int,
) {
    private var x = 0
    private var y = 0
    private var rowHeight = 0

    fun place(itemWidth: Int, itemHeight: Int): AtlasRegion? {
        if (itemWidth > width || itemHeight > height) return null
        if (x + itemWidth > width) {
            x = 0
            y += rowHeight
            rowHeight = 0
        }
        if (y + itemHeight > height) return null
        val region = AtlasRegion(x, y, itemWidth, itemHeight)
        x += itemWidth
        rowHeight = maxOf(rowHeight, itemHeight)
        return region
    }
}

data class GlyphAtlasPlacement(
    val strikeKey: GlyphStrikeKey,
    val region: AtlasRegion,
)

sealed interface GlyphAtlasUploadPlan {
    data class Accepted(
        val atlasWidth: Int,
        val atlasHeight: Int,
        val atlasBytes: ByteArray,
        val placements: List<GlyphAtlasPlacement>,
    ) : GlyphAtlasUploadPlan {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Accepted) return false
            return atlasWidth == other.atlasWidth &&
                atlasHeight == other.atlasHeight &&
                atlasBytes.contentEquals(other.atlasBytes) &&
                placements == other.placements
        }

        override fun hashCode(): Int {
            var result = atlasWidth
            result = 31 * result + atlasHeight
            result = 31 * result + atlasBytes.contentHashCode()
            result = 31 * result + placements.hashCode()
            return result
        }
    }

    data class Refused(val reason: String) : GlyphAtlasUploadPlan
}

class GlyphAtlasPacker(
    private val atlasWidth: Int,
    private val atlasHeight: Int,
) {
    private val cursor = AtlasCursor(atlasWidth, atlasHeight)

    fun place(key: GlyphStrikeKey, bitmap: A8Bitmap): GlyphAtlasPlacement? {
        val region = cursor.place(bitmap.width, bitmap.height) ?: return null
        return GlyphAtlasPlacement(
            strikeKey = key,
            region = region,
        )
    }
}

class GlyphAtlasUploadPlanner {
    fun plan(entries: List<Pair<GlyphStrikeKey, A8Bitmap>>): GlyphAtlasUploadPlan {
        if (entries.isEmpty()) {
            return GlyphAtlasUploadPlan.Accepted(0, 0, ByteArray(0), emptyList())
        }

        val maxWidth = entries.maxOf { (_, bmp) -> bmp.width }
        val maxHeight = entries.maxOf { (_, bmp) -> bmp.height }
        val totalArea = entries.sumOf { (_, bmp) -> bmp.width.toLong() * bmp.height.toLong() }

        var atlasDim = 128
        while (atlasDim < maxWidth || atlasDim < maxHeight || atlasDim.toLong() * atlasDim.toLong() < totalArea) {
            atlasDim *= 2
            if (atlasDim > 4096) {
                return GlyphAtlasUploadPlan.Refused("required atlas size exceeds maximum")
            }
        }

        val packer = GlyphAtlasPacker(atlasDim, atlasDim)
        val placements = mutableListOf<GlyphAtlasPlacement>()

        for ((key, bmp) in entries) {
            val placement = packer.place(key, bmp) ?: return GlyphAtlasUploadPlan.Refused(
                "failed to place glyph ${key.glyphId} (${bmp.width}x${bmp.height}) in atlas ${atlasDim}x${atlasDim}"
            )
            placements.add(placement)
        }

        val usedWidth = placements.maxOf { it.region.x + it.region.width }
        val usedHeight = placements.maxOf { it.region.y + it.region.height }
        val atlasBytes = ByteArray(usedWidth * usedHeight)

        for ((index, placement) in placements.withIndex()) {
            val (_, bmp) = entries[index]
            val rx = placement.region.x
            val ry = placement.region.y
            for (row in 0 until bmp.height) {
                for (col in 0 until bmp.width) {
                    atlasBytes[(ry + row) * usedWidth + (rx + col)] = bmp.pixels[row * bmp.width + col]
                }
            }
        }

        return GlyphAtlasUploadPlan.Accepted(usedWidth, usedHeight, atlasBytes, placements)
    }
}
