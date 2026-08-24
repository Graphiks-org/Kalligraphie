package io.ygdrasil.kalligraphie.font.atlas

import io.ygdrasil.kalligraphie.font.glyph.A8Bitmap
import io.ygdrasil.kalligraphie.font.glyph.GlyphStrikeKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GlyphAtlasUploadPlanTest {

    private fun glyphBitmap(w: Int, h: Int, fill: Byte = (-1).toByte()): A8Bitmap {
        val pixels = ByteArray(w * h)
        pixels.fill(fill)
        return A8Bitmap(w, h, pixels)
    }

    @Test
    fun `packer places 10 glyphs without overlap`() {
        val packer = GlyphAtlasPacker(atlasWidth = 512, atlasHeight = 512)

        val placements = mutableListOf<GlyphAtlasPlacement>()
        for (i in 0 until 10) {
            val key = GlyphStrikeKey(glyphId = i, size = 32.0f, subpixelX = 0, subpixelY = 0)
            val bmp = glyphBitmap(40 + i * 3, 40 + i * 2)
            val placement = packer.place(key, bmp)
            assertNotNull(placement, "glyph $i should be placed")
            placements.add(placement!!)
        }

        assertEquals(10, placements.size)

        for (i in placements.indices) {
            for (j in i + 1 until placements.size) {
                val a = placements[i].region
                val b = placements[j].region
                val overlap = a.x < b.x + b.width &&
                    a.x + a.width > b.x &&
                    a.y < b.y + b.height &&
                    a.y + a.height > b.y
                assertFalse(overlap, "placements $i and $j overlap")
            }
        }
    }

    @Test
    fun `packer refuses glyph too large for atlas`() {
        val packer = GlyphAtlasPacker(atlasWidth = 64, atlasHeight = 64)

        val key = GlyphStrikeKey(glyphId = 42, size = 16.0f, subpixelX = 0, subpixelY = 0)
        val bmp = glyphBitmap(128, 1)

        val placement = packer.place(key, bmp)
        assertNull(placement, "glyph wider than atlas should be refused")
    }

    @Test
    fun `packer refuses glyph too tall for remaining space`() {
        val packer = GlyphAtlasPacker(atlasWidth = 64, atlasHeight = 64)

        // Fill the first shelf completely both horizontally and vertically
        val tall = GlyphStrikeKey(glyphId = 1, size = 16.0f, subpixelX = 0, subpixelY = 0)
        packer.place(tall, glyphBitmap(64, 64))

        // Second glyph has no room — shelf exhausted both axes
        val small = GlyphStrikeKey(glyphId = 2, size = 16.0f, subpixelX = 0, subpixelY = 0)
        val placement = packer.place(small, glyphBitmap(10, 1))
        assertNull(placement, "glyph should be refused when atlas is full")
    }

    @Test
    fun `packer starts a new shelf and refuses a glyph beyond the atlas height`() {
        val sizes = listOf(3 to 2, 4 to 5, 6 to 1, 2 to 3)
        val packer = GlyphAtlasPacker(atlasWidth = 8, atlasHeight = 8)
        val regions = sizes.mapIndexed { index, (width, height) ->
            val key = GlyphStrikeKey(glyphId = index, size = 16f, subpixelX = 0, subpixelY = 0)
            val placement = packer.place(key, glyphBitmap(width, height))
            assertNotNull(placement)
            placement!!.region
        }

        assertEquals(
            listOf(
                AtlasRegion(0, 0, 3, 2),
                AtlasRegion(3, 0, 4, 5),
                AtlasRegion(0, 5, 6, 1),
                AtlasRegion(6, 5, 2, 3),
            ),
            regions,
        )

        assertNull(
            packer.place(
                GlyphStrikeKey(glyphId = 4, size = 16f, subpixelX = 0, subpixelY = 0),
                glyphBitmap(1, 1),
            ),
        )
    }

    @Test
    fun `legacy incremental packer preserves all degenerate rectangle placements`() {
        val packer = GlyphAtlasPacker(atlasWidth = 8, atlasHeight = 8)
        val dimensions = listOf(0 to 0, 0 to 3, 4 to 0)

        val placements = dimensions.mapIndexed { index, (width, height) ->
            val placement = packer.place(
                GlyphStrikeKey(index, 16f, 0, 0),
                glyphBitmap(width, height),
            )
            assertNotNull(placement)
            placement!!
        }

        assertEquals(dimensions.map { it.first }, placements.map { it.region.width })
        assertEquals(dimensions.map { it.second }, placements.map { it.region.height })
    }

    @Test
    fun `upload plan produces valid atlas bytes with correct pixel placement`() {
        val planner = GlyphAtlasUploadPlanner()

        val entries = listOf(
            GlyphStrikeKey(glyphId = 10, size = 16.0f, subpixelX = 0, subpixelY = 0) to
                A8Bitmap(2, 2, byteArrayOf(1, 2, 3, 4)),
            GlyphStrikeKey(glyphId = 20, size = 24.0f, subpixelX = 0, subpixelY = 0) to
                A8Bitmap(2, 1, byteArrayOf(5, 6)),
        )

        val plan = planner.plan(entries)
        assertTrue(plan is GlyphAtlasUploadPlan.Accepted, "plan should be Accepted, got: $plan")

        plan as GlyphAtlasUploadPlan.Accepted
        assertTrue(plan.atlasWidth > 0)
        assertTrue(plan.atlasHeight > 0)
        assertTrue(plan.atlasBytes.isNotEmpty())
        assertEquals(2, plan.placements.size)

        for (entry in plan.placements) {
            val original = entries.first { it.first == entry.strikeKey }.second
            assertEquals(original.width, entry.region.width)
            assertEquals(original.height, entry.region.height)
        }

        // Verify pixels are copied correctly at the right positions
        val atlas = plan.atlasBytes
        val stride = plan.atlasWidth
        for (entry in plan.placements) {
            val original = entries.first { it.first == entry.strikeKey }.second
            val rx = entry.region.x
            val ry = entry.region.y
            for (row in 0 until original.height) {
                for (col in 0 until original.width) {
                    val atlasIdx = (ry + row) * stride + (rx + col)
                    val srcIdx = row * original.width + col
                    assertEquals(
                        original.pixels[srcIdx],
                        atlas[atlasIdx],
                        "pixel mismatch at ($rx+$col, $ry+$row) for glyph ${entry.strikeKey.glyphId}"
                    )
                }
            }
        }
    }

    @Test
    fun `upload plan refuses when packing fails`() {
        val planner = GlyphAtlasUploadPlanner()

        val entries = listOf(
            GlyphStrikeKey(glyphId = 1, size = 16.0f, subpixelX = 0, subpixelY = 0) to
                A8Bitmap(5000, 1, ByteArray(5000)),
        )

        val plan = planner.plan(entries)
        assertTrue(plan is GlyphAtlasUploadPlan.Refused, "plan should be Refused for too-large glyph")
    }

    @Test
    fun `AtlasRegion data class has correct fields`() {
        val region = AtlasRegion(x = 10, y = 20, width = 30, height = 40)
        assertEquals(10, region.x)
        assertEquals(20, region.y)
        assertEquals(30, region.width)
        assertEquals(40, region.height)
    }

    @Test
    fun `GlyphAtlasPlacement data class has correct fields`() {
        val key = GlyphStrikeKey(glyphId = 42, size = 32.0f, subpixelX = 0, subpixelY = 0)
        val region = AtlasRegion(x = 0, y = 0, width = 64, height = 64)
        val placement = GlyphAtlasPlacement(strikeKey = key, region = region)
        assertEquals(key, placement.strikeKey)
        assertEquals(region, placement.region)
    }

    @Test
    fun `empty entries produce minimal accepted plan`() {
        val planner = GlyphAtlasUploadPlanner()
        val plan = planner.plan(emptyList())
        assertTrue(plan is GlyphAtlasUploadPlan.Accepted, "empty entries should be accepted")
        plan as GlyphAtlasUploadPlan.Accepted
        assertTrue(plan.placements.isEmpty())
        assertEquals(0, plan.atlasBytes.size)
    }
}
