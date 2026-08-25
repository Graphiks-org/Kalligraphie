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
        )

        val first = assertIs<GlyphAtlasUploadPlan.Accepted>(GlyphAtlasUploadPlanner().plan(entries))
        val second = assertIs<GlyphAtlasUploadPlan.Accepted>(GlyphAtlasUploadPlanner().plan(entries))

        assertEquals(first, second)
        first.placements.forEach { placement ->
            val region = placement.region
            assertTrue(region.x >= 0 && region.y >= 0)
            assertTrue(region.x + region.width <= first.atlasWidth)
            assertTrue(region.y + region.height <= first.atlasHeight)
        }
        assertTrue(!overlaps(first.placements[0].region, first.placements[1].region))
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
        a.x < b.x + b.width && b.x < a.x + a.width &&
            a.y < b.y + b.height && b.y < a.y + a.height
}
