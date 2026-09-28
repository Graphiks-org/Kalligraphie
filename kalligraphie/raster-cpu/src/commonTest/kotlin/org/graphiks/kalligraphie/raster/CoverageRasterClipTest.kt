package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageRasterClipTest {
    private fun square(left: Double, top: Double, right: Double, bottom: Double): List<FlatContour> =
        listOf(
            FlatContour(
                listOf(
                    FlatPoint(left, top),
                    FlatPoint(right, top),
                    FlatPoint(right, bottom),
                    FlatPoint(left, bottom),
                ),
            ),
        )

    @Test
    fun anUnboundedFillIsFullyCoveredWhereInsideEveryClip() {
        val clip = square(0.0, 0.0, 4.0, 4.0)
        val image = CoverageRaster.rasterizeLeaf(null, listOf(clip), 0, 0, 4, 4)
        assertEquals(255, image[2, 2])
    }

    @Test
    fun anUnboundedFillIsEmptyOutsideTheClip() {
        val clip = square(0.0, 0.0, 2.0, 2.0)
        val image = CoverageRaster.rasterizeLeaf(null, listOf(clip), 0, 0, 4, 4)
        assertEquals(0, image[3, 3])
    }

    @Test
    fun nestedIdenticalClipsDoNotSquareTheEdge() {
        // The clip's right edge at 1.5 falls inside pixel column 1, so that pixel is only partly
        // covered; multiplying two masks would square its coverage.
        val clip = square(0.0, 0.0, 1.5, 2.0)
        val once = CoverageRaster.rasterizeLeaf(null, listOf(clip), 0, 0, 2, 2)
        val twice = CoverageRaster.rasterizeLeaf(null, listOf(clip, clip), 0, 0, 2, 2)
        assertTrue(once[1, 0] in 1..254, "the edge pixel must be partly covered, was ${once[1, 0]}")
        assertEquals(once[1, 0], twice[1, 0])
    }

    @Test
    fun anEmptyBoundedGeometryPaintsNothing() {
        val clip = square(0.0, 0.0, 4.0, 4.0)
        val image = CoverageRaster.rasterizeLeaf(emptyList(), listOf(clip), 0, 0, 4, 4)
        assertEquals(0, image[2, 2])
    }

    @Test
    fun aBoundedShapeIsIntersectedWithTheClip() {
        val shape = square(0.0, 0.0, 4.0, 4.0)
        val clip = square(0.0, 0.0, 2.0, 2.0)
        val image = CoverageRaster.rasterizeLeaf(shape, listOf(clip), 0, 0, 4, 4)
        assertEquals(255, image[1, 1])
        assertEquals(0, image[3, 3])
    }
}
