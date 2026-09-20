package org.graphiks.kalligraphie.font.sfnt

import kotlin.test.Test
import kotlin.test.assertEquals

class TupleVariationScalarsTest {
    @Test
    fun zeroCoordinateYieldsZero() {
        assertEquals(0.0, TupleVariationScalars.scalar(listOf(0.0), doubleArrayOf(1.0), null, null))
    }

    @Test
    fun peakCoordinateYieldsOne() {
        assertEquals(1.0, TupleVariationScalars.scalar(listOf(1.0), doubleArrayOf(1.0), null, null))
        assertEquals(1.0, TupleVariationScalars.scalar(listOf(-1.0), doubleArrayOf(-1.0), null, null))
    }

    @Test
    fun interpolatesBetweenZeroAndPeak() {
        assertEquals(0.5, TupleVariationScalars.scalar(listOf(0.5), doubleArrayOf(1.0), null, null))
        assertEquals(0.25, TupleVariationScalars.scalar(listOf(-0.25), doubleArrayOf(-1.0), null, null))
    }

    @Test
    fun oppositeDirectionYieldsZero() {
        assertEquals(0.0, TupleVariationScalars.scalar(listOf(-0.5), doubleArrayOf(1.0), null, null))
    }

    @Test
    fun beyondPeakMagnitudeYieldsZero() {
        assertEquals(0.0, TupleVariationScalars.scalar(listOf(1.5), doubleArrayOf(1.0), null, null))
    }

    @Test
    fun zeroPeakAxisIsIgnored() {
        assertEquals(1.0, TupleVariationScalars.scalar(listOf(0.5), doubleArrayOf(0.0), null, null))
    }

    @Test
    fun intermediateRegionInterpolatesAndClamps() {
        val peak = doubleArrayOf(1.0)
        val start = doubleArrayOf(0.0)
        val end = doubleArrayOf(2.0)
        assertEquals(1.0, TupleVariationScalars.scalar(listOf(1.0), peak, start, end))
        assertEquals(0.5, TupleVariationScalars.scalar(listOf(0.5), peak, start, end))
        assertEquals(0.5, TupleVariationScalars.scalar(listOf(1.5), peak, start, end))
        assertEquals(0.0, TupleVariationScalars.scalar(listOf(-0.5), peak, start, end))
        assertEquals(0.0, TupleVariationScalars.scalar(listOf(2.5), peak, start, end))
    }

    @Test
    fun multiAxisScalarIsTheProductOfAxisFactors() {
        val peak = doubleArrayOf(1.0, 1.0)
        assertEquals(0.5, TupleVariationScalars.scalar(listOf(1.0, 0.5), peak, null, null))
        assertEquals(0.0, TupleVariationScalars.scalar(listOf(1.0, -0.5), peak, null, null))
    }

    @Test
    fun iupInterpolatesUntouchedPointFromExplicitNeighbors() {
        val resolved = GvarIup.resolvePointDeltas(
            pointCount = 3,
            contourEndPoints = listOf(2),
            baseX = listOf(0.0, 50.0, 100.0),
            baseY = listOf(0.0, 0.0, 0.0),
            targetPoints = intArrayOf(0, 2),
            tupleXDeltas = intArrayOf(0, 100),
            tupleYDeltas = intArrayOf(0, 0),
        )
        assertEquals(50.0, resolved.xDeltas[1])
        assertEquals(0.0, resolved.yDeltas[1])
    }

    @Test
    fun iupLeavesCompletePointSetUntouched() {
        val resolved = GvarIup.resolvePointDeltas(
            pointCount = 1,
            contourEndPoints = listOf(0),
            baseX = listOf(7.0),
            baseY = listOf(9.0),
            targetPoints = IntArray(5) { it },
            tupleXDeltas = intArrayOf(10, 0, 0, 0, 0),
            tupleYDeltas = intArrayOf(0, 0, 0, 0, 0),
        )
        assertEquals(10.0, resolved.xDeltas[0])
        assertEquals(0.0, resolved.xDeltas[1])
    }

    @Test
    fun iupWrapsAcrossContourSeamForTrailingUntouchedPoint() {
        val resolved = GvarIup.resolvePointDeltas(
            pointCount = 4,
            contourEndPoints = listOf(3),
            baseX = listOf(0.0, 0.0, 100.0, 50.0),
            baseY = listOf(0.0, 0.0, 0.0, 0.0),
            targetPoints = intArrayOf(0, 2),
            tupleXDeltas = intArrayOf(0, 200),
            tupleYDeltas = intArrayOf(0, 0),
        )
        // Point 3 has no following explicit point, so the seam wraps to point 0.
        assertEquals(100.0, resolved.xDeltas[3])
        assertEquals(0.0, resolved.yDeltas[3])
    }

    @Test
    fun iupReservesZeroFilledPhantomSlots() {
        val resolved = GvarIup.resolvePointDeltas(
            pointCount = 3,
            contourEndPoints = listOf(2),
            baseX = listOf(0.0, 50.0, 100.0),
            baseY = listOf(0.0, 0.0, 0.0),
            targetPoints = intArrayOf(0, 2),
            tupleXDeltas = intArrayOf(0, 100),
            tupleYDeltas = intArrayOf(0, 0),
        )
        for (phantom in 3..6) {
            assertEquals(0.0, resolved.xDeltas[phantom])
            assertEquals(0.0, resolved.yDeltas[phantom])
        }
    }

    @Test
    fun iupKeepsContoursIsolatedFromEachOther() {
        val resolved = GvarIup.resolvePointDeltas(
            pointCount = 6,
            contourEndPoints = listOf(2, 5),
            baseX = listOf(0.0, 50.0, 100.0, 0.0, 50.0, 100.0),
            baseY = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            targetPoints = intArrayOf(0, 2),
            tupleXDeltas = intArrayOf(0, 100),
            tupleYDeltas = intArrayOf(0, 0),
        )
        // First contour interpolates its untouched point.
        assertEquals(50.0, resolved.xDeltas[1])
        // Second contour has no explicit points, so the first contour's deltas must not leak in.
        assertEquals(0.0, resolved.xDeltas[3])
        assertEquals(0.0, resolved.xDeltas[4])
        assertEquals(0.0, resolved.xDeltas[5])
    }
}
