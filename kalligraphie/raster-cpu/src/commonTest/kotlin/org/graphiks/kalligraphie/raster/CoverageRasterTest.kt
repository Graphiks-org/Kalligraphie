package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageRasterTest {
    @Test
    fun fillsAWholePixelAlignedSquare() {
        val square = contour(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0)
        val image = CoverageRaster.rasterize(listOf(square), left = 0, top = 0, width = 4, height = 4)
        assertEquals(4, image.width)
        assertEquals(4, image.height)
        for (y in 0 until 4) for (x in 0 until 4) assertEquals(255, image[x, y], "pixel ($x, $y)")
    }

    @Test
    fun combinesLeftAndTopBearingsWithSamples() {
        val square = contour(3.0, 2.0, 4.0, 2.0, 4.0, 3.0, 3.0, 3.0)
        val image = CoverageRaster.rasterize(listOf(square), left = 3, top = 2, width = 1, height = 1)
        assertEquals(255, image[0, 0])
        assertEquals(3, image.left)
        assertEquals(2, image.top)
    }

    @Test
    fun coverageOfAHalfPixelSquareIsSixtyFour() {
        val square = contour(0.0, 0.0, 0.5, 0.0, 0.5, 0.5, 0.0, 0.5)
        val image = CoverageRaster.rasterize(listOf(square), left = 0, top = 0, width = 1, height = 1)
        assertEquals(64, image[0, 0])
    }

    @Test
    fun oppositeWindingCreatesAHole() {
        val outer = contour(0.0, 0.0, 8.0, 0.0, 8.0, 8.0, 0.0, 8.0)
        val inner = contour(2.0, 2.0, 2.0, 6.0, 6.0, 6.0, 6.0, 2.0)
        val image = CoverageRaster.rasterize(listOf(outer, inner), left = 0, top = 0, width = 8, height = 8)
        assertEquals(0, image[4, 4])
        assertEquals(255, image[1, 1])
        assertEquals(255, image[6, 6])
    }

    @Test
    fun containsReportsHoleInteriorAndExterior() {
        val outer = contour(0.0, 0.0, 8.0, 0.0, 8.0, 8.0, 0.0, 8.0)
        val inner = contour(2.0, 2.0, 2.0, 6.0, 6.0, 6.0, 6.0, 2.0)
        assertTrue(CoverageRaster.contains(listOf(outer, inner), 1.5, 1.5))
        assertFalse(CoverageRaster.contains(listOf(outer, inner), 4.0, 4.0))
        assertFalse(CoverageRaster.contains(listOf(outer, inner), 9.0, 9.0))
    }

    @Test
    fun selfIntersectingBowtieFillsBothLobes() {
        val bowtie = contour(0.0, 0.0, 8.0, 8.0, 8.0, 0.0, 0.0, 8.0)
        val image = CoverageRaster.rasterize(listOf(bowtie), left = 0, top = 0, width = 8, height = 8)
        // Left lobe: 6 samples strictly above the diagonal are inside and the 4 samples
        // exactly on it cancel to zero winding → (6 * 255 + 8) / 16 = 96.
        // Right lobe: the 4 on-diagonal samples resolve to winding -1 (the anti-diagonal
        // does not contribute there), so 10 samples are inside → (10 * 255 + 8) / 16 = 159.
        assertEquals(96, image[1, 1])
        assertEquals(159, image[6, 6])
        assertTrue(image[1, 1] > 0 && image[6, 6] > 0)
    }

    @Test
    fun outsidePixelsStayEmpty() {
        val square = contour(0.0, 0.0, 2.0, 0.0, 2.0, 2.0, 0.0, 2.0)
        val image = CoverageRaster.rasterize(listOf(square), left = 0, top = 0, width = 4, height = 4)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                if (x >= 2 || y >= 2) assertEquals(0, image[x, y], "pixel ($x, $y)")
            }
        }
    }

    @Test
    fun emptyContourListProducesAnEmptyImage() {
        val image = CoverageRaster.rasterize(emptyList(), left = 0, top = 0, width = 2, height = 2)
        for (y in 0 until 2) for (x in 0 until 2) assertEquals(0, image[x, y], "pixel ($x, $y)")
    }

    private fun contour(vararg coordinates: Double): FlatContour {
        val points = coordinates.toList().chunked(2) { (x, y) -> FlatPoint(x, y) }
        return FlatContour(points)
    }
}
