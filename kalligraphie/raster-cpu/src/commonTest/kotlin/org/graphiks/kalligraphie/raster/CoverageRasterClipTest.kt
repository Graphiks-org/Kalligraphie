package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertEquals

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
        val clip = square(0.0, 0.0, 2.0, 2.0)
        val once = CoverageRaster.rasterizeLeaf(null, listOf(clip), 0, 0, 2, 2)
        val twice = CoverageRaster.rasterizeLeaf(null, listOf(clip, clip), 0, 0, 2, 2)
        assertEquals(once[0, 0], twice[0, 0])
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
