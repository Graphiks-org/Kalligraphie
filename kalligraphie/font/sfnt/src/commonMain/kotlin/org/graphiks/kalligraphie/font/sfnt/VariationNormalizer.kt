@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.immutableListSnapshot

/**
 * Maps design-coordinate variation selections to normalized `[-1..1]` coordinates.
 *
 * The design value is clamped to the axis bounds before normalization; a clamped axis produces one
 * informational `font.variation.axis-clamped` diagnostic. When an `avar` segment map is present it
 * is applied after normalization and the result is clamped to `[-1..1]`. An unknown axis tag fails
 * with `font.variation.unknown-axis`. The returned coordinates are tag-sorted, which satisfies
 * `FontGeometryParameters`, and contain only the axes present in the input selection: an axis
 * omitted from the selection is not emitted, and an empty selection yields an empty list.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object VariationNormalizer {
    /** Normalizes [design] against [fvar] and optional [avar]. */
    public fun normalize(
        design: FontVariationCoordinates,
        fvar: FvarData,
        avar: AvarData?,
    ): FontOperationResult<List<FontAxisCoordinate>> {
        val coordinates = ArrayList<FontAxisCoordinate>(design.coordinates.size)
        val diagnostics = ArrayList<FontDiagnostic>()
        for (coordinate in design.coordinates) {
            val axisIndex = fvar.axisIndex(coordinate.tag)
                ?: return variationFailure(
                    "font.variation.unknown-axis",
                    "Axis ${coordinate.tag} is not declared by the font.",
                    "fvar",
                )
            val axis = fvar.axes[axisIndex]
            val clamped = coordinate.value.coerceIn(axis.minValue, axis.maxValue)
            if (clamped != coordinate.value) {
                diagnostics += FontDiagnostic(
                    code = "font.variation.axis-clamped",
                    severity = FontDiagnosticSeverity.INFO,
                    location = FontDiagnosticLocation.Table("fvar"),
                    message = "Axis ${coordinate.tag} value ${coordinate.value} was clamped to $clamped.",
                )
            }
            val normalized = normalizeAxis(clamped, axis)
            val mapped = applyAvar(normalized, avar, axisIndex).coerceIn(-1f, 1f)
            coordinates += FontAxisCoordinate(axis.tag, mapped)
        }
        return FontOperationResult.Success(coordinates.sortedBy { it.tag }.immutableListSnapshot(), diagnostics)
    }

    private fun normalizeAxis(value: Float, axis: FvarAxis): Float = when {
        value < axis.defaultValue && axis.defaultValue > axis.minValue ->
            -(axis.defaultValue - value) / (axis.defaultValue - axis.minValue)
        value > axis.defaultValue && axis.maxValue > axis.defaultValue ->
            (value - axis.defaultValue) / (axis.maxValue - axis.defaultValue)
        else -> 0f
    }

    private fun applyAvar(normalized: Float, avar: AvarData?, axisIndex: Int): Float {
        val segments = avar?.axisSegmentMaps?.getOrNull(axisIndex) ?: return normalized
        if (segments.size < 2) return normalized
        if (normalized <= segments.first().from) return segments.first().to
        if (normalized >= segments.last().from) return segments.last().to
        for (index in 0 until segments.size - 1) {
            val left = segments[index]
            val right = segments[index + 1]
            if (normalized in left.from..right.from) {
                val span = right.from - left.from
                if (span == 0f) return left.to
                return left.to + (normalized - left.from) / span * (right.to - left.to)
            }
        }
        return normalized
    }
}
