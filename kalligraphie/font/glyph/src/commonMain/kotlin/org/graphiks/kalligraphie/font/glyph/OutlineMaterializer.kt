package org.graphiks.kalligraphie.font.glyph

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticData
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.api.toGlyphOutlineLimits
import org.graphiks.kalligraphie.font.scaler.ScalerGlyphOutline

/**
 * Converts scaler output into the public bounded outline representation.
 *
 * Materialization is read-only, publishes only complete immutable values, and
 * returns typed failures when a profile limit is exceeded.
 */
public object OutlineMaterializer {
    /**
     * Materializes [outline] while enforcing [profile] and cooperative
     * cancellation. A cancellation observed before completion returns
     * [FontOperationResult.Cancelled] without exposing partial output. The
     * result contains a complete immutable [GlyphRepresentation.Outline], an
     * empty representation for a glyph without contours, or a typed resource
     * limit failure. No renderer-specific object is created or retained.
     *
     * @param outline scaler output in design units.
     * @param profile limits and schema accepted by the consumer.
     * @param cancellationToken cooperative cancellation signal.
     * @return a complete representation, a typed limit failure, or cancellation.
     */
    public fun materialize(
        outline: ScalerGlyphOutline,
        profile: OutlineProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<GlyphRepresentation> {
        if (cancellationToken.isCancellationRequested()) {
            return FontOperationResult.Cancelled()
        }
        if (outline.contours.isEmpty() || outline.pointCount == 0) {
            return FontOperationResult.Success(GlyphRepresentation.Empty)
        }
        if (outline.contours.size > profile.maxContours) {
            return limitFailure(
                "Outline contour limit exceeded.",
                outline.glyphId,
                outline.contours.size.toLong(),
                profile.maxContours.toLong(),
            )
        }
        if (outline.pointCount > profile.maxPoints) {
            return limitFailure(
                "Outline point limit exceeded.",
                outline.glyphId,
                outline.pointCount.toLong(),
                profile.maxPoints.toLong(),
            )
        }
        if (outline.components.size > profile.maxCompositeComponents) {
            return limitFailure(
                "Outline component limit exceeded.",
                outline.glyphId,
                outline.components.size.toLong(),
                profile.maxCompositeComponents.toLong(),
            )
        }
        var commandBytes = 0L
        for (contour in outline.contours) {
            for (command in contour.commands) {
                val encodedBytes = when (command) {
                    is org.graphiks.kalligraphie.api.GlyphOutlineCommand.QuadraticTo -> BYTES_PER_QUADRATIC_COMMAND
                    else -> BYTES_PER_COMMAND
                }
                commandBytes = checkedAdd(commandBytes, encodedBytes)
                    ?: return limitFailure(
                        "Outline command byte budget overflowed.",
                        outline.glyphId,
                        Long.MAX_VALUE,
                        profile.maxBytes.toLong(),
                    )
            }
        }
        val componentBytes = checkedMultiply(outline.components.size.toLong(), BYTES_PER_COMPONENT)
            ?: return limitFailure(
                "Outline component byte budget overflowed.",
                outline.glyphId,
                Long.MAX_VALUE,
                profile.maxBytes.toLong(),
            )
        val contentBytes = checkedAdd(commandBytes, componentBytes)
        val byteBudget = contentBytes?.let { checkedAdd(it, OUTLINE_OVERHEAD_BYTES) }
            ?: return limitFailure(
                "Outline byte budget overflowed.",
                outline.glyphId,
                Long.MAX_VALUE,
                profile.maxBytes.toLong(),
            )
        if (byteBudget > profile.maxBytes.toLong()) {
            return limitFailure(
                "Outline byte limit exceeded.",
                outline.glyphId,
                byteBudget,
                profile.maxBytes.toLong(),
            )
        }
        if (cancellationToken.isCancellationRequested()) {
            return FontOperationResult.Cancelled()
        }
        return FontOperationResult.Success(
            GlyphRepresentation.Outline(
                GlyphOutlineIR(
                    glyphId = outline.glyphId,
                    unitsPerEm = outline.unitsPerEm,
                    bounds = outline.bounds,
                    contours = outline.contours.map { contour -> contour.copy(commands = contour.commands.toList()) },
                    pointCount = outline.pointCount,
                    components = outline.components.toList(),
                    limits = profile.toGlyphOutlineLimits(),
                ),
            ),
        )
    }

    private fun limitFailure(
        message: String,
        glyphId: Int,
        observedValue: Long,
        limit: Long,
    ): FontOperationResult.Failure {
        val error = FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Glyph(glyphId))
        return failure(error, listOf(error.toDiagnostic(FontDiagnosticData(observedValue = observedValue, limit = limit))))
    }

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
}

private fun checkedAdd(left: Long, right: Long): Long? {
    if (right > 0L && left > Long.MAX_VALUE - right) return null
    if (right < 0L && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun checkedMultiply(left: Long, right: Long): Long? {
    if (left < 0L || right < 0L) return null
    if (left != 0L && right > Long.MAX_VALUE / left) return null
    return left * right
}

private const val BYTES_PER_COMMAND = 16L
private const val BYTES_PER_QUADRATIC_COMMAND = 32L
private const val BYTES_PER_COMPONENT = 16L
private const val OUTLINE_OVERHEAD_BYTES = 32L
