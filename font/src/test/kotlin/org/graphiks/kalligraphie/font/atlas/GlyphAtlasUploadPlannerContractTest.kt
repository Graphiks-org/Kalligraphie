package org.graphiks.kalligraphie.font.atlas

import org.graphiks.kalligraphie.font.glyph.A8Bitmap
import org.graphiks.kalligraphie.font.glyph.GlyphStrikeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GlyphAtlasUploadPlannerContractTest {
    @Test
    fun producesDeterministicInBoundsNonOverlappingRegions() {
        val entries = listOf(
            key(3) to A8Bitmap(200, 1, ByteArray(200) { 0x11 }),
            key(7) to A8Bitmap(100, 2, ByteArray(200) { 0x22 }),
            key(11) to A8Bitmap(50, 3, ByteArray(150) { 0x33 }),
        )

        val first = assertIs<GlyphAtlasUploadPlan.Accepted>(GlyphAtlasUploadPlanner().plan(entries))
        val second = assertIs<GlyphAtlasUploadPlan.Accepted>(GlyphAtlasUploadPlanner().plan(entries))

        assertEquals(first, second)
        assertEquals(entries.size, first.placements.size)
        first.placements.forEach { placement ->
            val region = placement.region
            assertTrue(region.x >= 0 && region.y >= 0 && region.width >= 0 && region.height >= 0)
            assertTrue(region.x.toLong() + region.width.toLong() <= first.atlasWidth.toLong())
            assertTrue(region.y.toLong() + region.height.toLong() <= first.atlasHeight.toLong())
        }
        first.placements.forEachIndexed { index, placement ->
            first.placements.drop(index + 1).forEach { other ->
                assertTrue(!overlaps(placement.region, other.region))
            }
        }
    }

    @Test
    fun refusesAPlanWhoseRequiredAtlasExceedsThePublicLimit() {
        val result = GlyphAtlasUploadPlanner().plan(
            listOf(key(42) to A8Bitmap(4097, 1, ByteArray(4097))),
        )

        assertIs<GlyphAtlasUploadPlan.Refused>(result)
    }

    private fun key(glyphId: Int) = GlyphStrikeKey(glyphId, 16f, 0, 0)

    private fun overlaps(a: AtlasRegion, b: AtlasRegion): Boolean =
        a.x.toLong() < b.x.toLong() + b.width.toLong() &&
            b.x.toLong() < a.x.toLong() + a.width.toLong() &&
            a.y.toLong() < b.y.toLong() + b.height.toLong() &&
            b.y.toLong() < a.y.toLong() + a.height.toLong()
}
