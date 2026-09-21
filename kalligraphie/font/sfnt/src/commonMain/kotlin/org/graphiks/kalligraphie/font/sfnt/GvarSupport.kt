@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import kotlin.math.abs

/** Bounds applied while decoding the OpenType `gvar` table. */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public data class GvarLimits(
    /** Maximum accepted `gvar` table length in bytes. Looser than `VariationLimits.maxSourceBytes`
     * because real `gvar` tables are large; revisit this default when profile-aligned variation
     * bounds (`maxVariationTableBytes`) land. */
    public val maxSourceBytes: Int = 64 * 1024 * 1024,
    /** Maximum accepted number of shared tuples. */
    public val maxSharedTuples: Int = 4_096,
    /** Maximum accepted tuple variations per glyph record. */
    public val maxTupleVariations: Int = 4_096,
    /** Maximum accepted outline or component points per variation (including the reserved phantom slots). */
    public val maxPointsPerVariation: Int = 1_000_000,
) {
    init {
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive." }
        require(maxSharedTuples >= 0) { "maxSharedTuples must not be negative." }
        require(maxTupleVariations > 0) { "maxTupleVariations must be positive." }
        require(maxPointsPerVariation > 0) { "maxPointsPerVariation must be positive." }
    }
}

/**
 * Number of phantom points `gvar` addresses after the outline or component points.
 *
 * The phantom deltas are decoded alongside the outline or component deltas and retained in
 * `GvarGlyphDeltas` for later metric derivation; a complete point set always covers
 * `outlinePoints + 4`, so the slots are reserved while decoding.
 */
internal const val GVAR_PHANTOM_POINT_COUNT: Int = 4

/** Phantom-point slot indices inside the four reserved `gvar` phantom points. */
internal const val GVAR_PHANTOM_LEFT_INDEX: Int = 0
internal const val GVAR_PHANTOM_RIGHT_INDEX: Int = 1
internal const val GVAR_PHANTOM_TOP_INDEX: Int = 2
internal const val GVAR_PHANTOM_BOTTOM_INDEX: Int = 3

internal const val GVAR_LONG_OFFSETS: Int = 0x0001
internal const val GVAR_SHARED_POINT_NUMBERS: Int = 0x8000
internal const val GVAR_TUPLE_COUNT_MASK: Int = 0x0fff
internal const val GVAR_EMBEDDED_PEAK_TUPLE: Int = 0x8000
internal const val GVAR_INTERMEDIATE_REGION: Int = 0x4000
internal const val GVAR_PRIVATE_POINT_NUMBERS: Int = 0x2000
internal const val GVAR_TUPLE_INDEX_MASK: Int = 0x0fff

/**
 * Evaluates the scalar factor of one `gvar` tuple (a.k.a. region) at normalized coordinates.
 *
 * Without an intermediate region the factor is zero at the origin, linear between the origin and
 * the peak, and zero in the opposite direction or beyond the peak magnitude. With an intermediate
 * region the factor is zero outside `[start, end]`, `1` at the peak, and linearly interpolated on
 * either side. Factors of all axes are multiplied.
 */
internal object TupleVariationScalars {
    fun scalar(
        normalizedAxes: List<Double>,
        peak: DoubleArray,
        startTuple: DoubleArray?,
        endTuple: DoubleArray?,
    ): Double {
        var scalar = 1.0
        for (axis in peak.indices) {
            val coordinate = normalizedAxes.getOrElse(axis) { 0.0 }
            val peakValue = peak[axis]
            if (peakValue == 0.0) continue
            val axisScalar = if (startTuple != null && endTuple != null) {
                val start = startTuple.getOrElse(axis) { 0.0 }
                val end = endTuple.getOrElse(axis) { 0.0 }
                when {
                    coordinate < start || coordinate > end || start > peakValue || peakValue > end -> 0.0
                    coordinate == peakValue -> 1.0
                    coordinate < peakValue -> (coordinate - start) / (peakValue - start)
                    else -> (end - coordinate) / (end - peakValue)
                }
            } else {
                when {
                    coordinate == 0.0 -> 0.0
                    !sameDirection(coordinate, peakValue) -> 0.0
                    abs(coordinate) > abs(peakValue) -> 0.0
                    else -> coordinate / peakValue
                }
            }
            scalar *= axisScalar
            if (scalar == 0.0) return 0.0
        }
        return scalar
    }

    private fun sameDirection(coordinate: Double, peakValue: Double): Boolean =
        (coordinate < 0.0 && peakValue < 0.0) || (coordinate > 0.0 && peakValue > 0.0)
}

/**
 * Resolved per-point deltas for one glyph.
 *
 * `xDeltas` and `yDeltas` are both sized `pointCount + GVAR_PHANTOM_POINT_COUNT`: the four phantom
 * slots after the outline or component points are decoded alongside them and retained for later
 * metric derivation. Simple-glyph IUP never interpolates the phantom slots; composite glyphs get no
 * interpolation at all.
 */
internal class GvarResolvedDeltas(
    val xDeltas: DoubleArray,
    val yDeltas: DoubleArray,
)

/**
 * TrueType IUP (interpolate untouched points) for simple glyphs.
 *
 * Explicit points receive their tuple delta; untouched points inside a contour that has at least
 * one explicit point and at least one untouched point are interpolated between the nearest
 * explicit neighbors (wrapping within the contour). If every point of a contour is explicit, or
 * none is, the contour is left as decoded. Phantom slots are written but not interpolated.
 */
internal object GvarIup {
    fun resolvePointDeltas(
        pointCount: Int,
        contourEndPoints: List<Int>,
        baseX: List<Double>,
        baseY: List<Double>,
        targetPoints: IntArray,
        tupleXDeltas: IntArray,
        tupleYDeltas: IntArray,
    ): GvarResolvedDeltas {
        val maxPointCount = pointCount + GVAR_PHANTOM_POINT_COUNT
        val xDeltas = DoubleArray(maxPointCount)
        val yDeltas = DoubleArray(maxPointCount)
        val explicit = BooleanArray(maxPointCount)
        for (index in targetPoints.indices) {
            val point = targetPoints[index]
            if (point in 0 until maxPointCount) {
                xDeltas[point] = tupleXDeltas.getOrElse(index) { 0 }.toDouble()
                yDeltas[point] = tupleYDeltas.getOrElse(index) { 0 }.toDouble()
                explicit[point] = true
            }
        }
        if (targetPoints.size == maxPointCount && targetPoints.indices.all { targetPoints[it] == it }) {
            return GvarResolvedDeltas(xDeltas, yDeltas)
        }
        var contourStart = 0
        for (contourEnd in contourEndPoints) {
            if (contourEnd >= pointCount) break
            val referenced = (contourStart..contourEnd).filter { explicit[it] }
            if (referenced.isEmpty() || referenced.size == contourEnd - contourStart + 1) {
                contourStart = contourEnd + 1
                continue
            }
            for (point in contourStart..contourEnd) {
                if (explicit[point]) continue
                val preceding = referenced.lastOrNull { it < point } ?: referenced.last()
                val following = referenced.firstOrNull { it > point } ?: referenced.first()
                xDeltas[point] = infer(
                    targetCoordinate = baseX.getOrElse(point) { 0.0 },
                    precedingCoordinate = baseX.getOrElse(preceding) { 0.0 },
                    precedingDelta = xDeltas[preceding],
                    followingCoordinate = baseX.getOrElse(following) { 0.0 },
                    followingDelta = xDeltas[following],
                )
                yDeltas[point] = infer(
                    targetCoordinate = baseY.getOrElse(point) { 0.0 },
                    precedingCoordinate = baseY.getOrElse(preceding) { 0.0 },
                    precedingDelta = yDeltas[preceding],
                    followingCoordinate = baseY.getOrElse(following) { 0.0 },
                    followingDelta = yDeltas[following],
                )
            }
            contourStart = contourEnd + 1
        }
        return GvarResolvedDeltas(xDeltas, yDeltas)
    }

    private fun infer(
        targetCoordinate: Double,
        precedingCoordinate: Double,
        precedingDelta: Double,
        followingCoordinate: Double,
        followingDelta: Double,
    ): Double {
        if (precedingCoordinate == followingCoordinate) {
            return if (precedingDelta == followingDelta) precedingDelta else 0.0
        }
        val minimum = minOf(precedingCoordinate, followingCoordinate)
        val maximum = maxOf(precedingCoordinate, followingCoordinate)
        return when {
            targetCoordinate <= minimum ->
                if (precedingCoordinate < followingCoordinate) precedingDelta else followingDelta
            targetCoordinate >= maximum ->
                if (precedingCoordinate > followingCoordinate) precedingDelta else followingDelta
            else -> {
                val proportion = (targetCoordinate - precedingCoordinate) / (followingCoordinate - precedingCoordinate)
                (1.0 - proportion) * precedingDelta + proportion * followingDelta
            }
        }
    }
}
