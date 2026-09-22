@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

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
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
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
     * @param syntheticBold applies the pinned synthetic bold geometry before the limits are enforced.
     * @param syntheticItalic applies the pinned synthetic italic geometry before the limits are enforced.
     * @return a complete representation, a typed limit failure, or cancellation.
     *
     * When [syntheticBold] or [syntheticItalic] is set, [SyntheticGeometry] transforms [outline]
     * before the profile limits are enforced: the transform preserves every contour, point and
     * command, so it cannot introduce a limit breach, and only the bounds envelope is recomputed.
     * The default (`false`, `false`) takes the unchanged path and returns [outline] by identity.
     * A transform failure (only `font.geometry-overflow`) or cancellation is returned unchanged.
     */
    public fun materialize(
        outline: ScalerGlyphOutline,
        profile: OutlineProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
        syntheticBold: Boolean = false,
        syntheticItalic: Boolean = false,
    ): FontOperationResult<GlyphRepresentation> {
        if (cancellationToken.isCancellationRequested()) {
            return FontOperationResult.Cancelled()
        }
        val source = if (syntheticBold || syntheticItalic) {
            when (val styled = SyntheticGeometry.apply(outline, syntheticBold, syntheticItalic, cancellationToken)) {
                is FontOperationResult.Success -> styled.value
                is FontOperationResult.Failure -> return styled
                is FontOperationResult.Cancelled -> return styled
            }
        } else {
            outline
        }
        if (source.contours.isEmpty() || source.pointCount == 0) {
            return FontOperationResult.Success(GlyphRepresentation.Empty)
        }
        if (source.contours.size > profile.maxContours) {
            return limitFailure(
                "Outline contour limit exceeded.",
                source.glyphId,
                source.contours.size.toLong(),
                profile.maxContours.toLong(),
            )
        }
        if (source.pointCount > profile.maxPoints) {
            return limitFailure(
                "Outline point limit exceeded.",
                source.glyphId,
                source.pointCount.toLong(),
                profile.maxPoints.toLong(),
            )
        }
        if (source.components.size > profile.maxCompositeComponents) {
            return limitFailure(
                "Outline component limit exceeded.",
                source.glyphId,
                source.components.size.toLong(),
                profile.maxCompositeComponents.toLong(),
            )
        }
        var commandBytes = 0L
        for (contour in source.contours) {
            for (command in contour.commands) {
                val encodedBytes = when (command) {
                    is org.graphiks.kalligraphie.api.GlyphOutlineCommand.QuadraticTo -> BYTES_PER_QUADRATIC_COMMAND
                    is org.graphiks.kalligraphie.api.GlyphOutlineCommand.CubicTo -> BYTES_PER_CUBIC_COMMAND
                    else -> BYTES_PER_COMMAND
                }
                commandBytes = checkedAdd(commandBytes, encodedBytes)
                    ?: return limitFailure(
                        "Outline command byte budget overflowed.",
                        source.glyphId,
                        Long.MAX_VALUE,
                        profile.maxBytes.toLong(),
                    )
            }
        }
        val componentBytes = checkedMultiply(source.components.size.toLong(), BYTES_PER_COMPONENT)
            ?: return limitFailure(
                "Outline component byte budget overflowed.",
                source.glyphId,
                Long.MAX_VALUE,
                profile.maxBytes.toLong(),
            )
        val contentBytes = checkedAdd(commandBytes, componentBytes)
        val byteBudget = contentBytes?.let { checkedAdd(it, OUTLINE_OVERHEAD_BYTES) }
            ?: return limitFailure(
                "Outline byte budget overflowed.",
                source.glyphId,
                Long.MAX_VALUE,
                profile.maxBytes.toLong(),
            )
        if (byteBudget > profile.maxBytes.toLong()) {
            return limitFailure(
                "Outline byte limit exceeded.",
                source.glyphId,
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
                    glyphId = source.glyphId,
                    unitsPerEm = source.unitsPerEm,
                    bounds = source.bounds,
                    contours = source.contours.map { contour -> contour.copy(commands = contour.commands.toList()) },
                    pointCount = source.pointCount,
                    components = source.components.toList(),
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
private const val BYTES_PER_CUBIC_COMMAND = 48L
private const val BYTES_PER_COMPONENT = 16L
private const val OUTLINE_OVERHEAD_BYTES = 32L
