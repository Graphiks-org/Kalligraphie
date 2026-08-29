package org.graphiks.kalligraphie.font.glyph

import org.graphiks.kalligraphie.api.FontDiagnostic
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

public object OutlineMaterializer {
    public fun materialize(
        outline: ScalerGlyphOutline,
        profile: OutlineProfile,
    ): FontOperationResult<GlyphRepresentation> {
        if (outline.contours.isEmpty() || outline.pointCount == 0) {
            return FontOperationResult.Success(GlyphRepresentation.Empty)
        }
        val commandCount = outline.contours.sumOf { it.commands.size }
        val byteBudget = commandCount * 16 + outline.components.size * 16 + 32
        if (outline.contours.size > profile.maxContours) {
            return limitFailure("Outline contour limit exceeded.", outline.glyphId)
        }
        if (outline.pointCount > profile.maxPoints) {
            return limitFailure("Outline point limit exceeded.", outline.glyphId)
        }
        if (outline.components.size > profile.maxCompositeComponents) {
            return limitFailure("Outline component limit exceeded.", outline.glyphId)
        }
        if (byteBudget > profile.maxBytes) {
            return limitFailure("Outline byte limit exceeded.", outline.glyphId)
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

    private fun limitFailure(message: String, glyphId: Int): FontOperationResult.Failure =
        failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Glyph(glyphId)))

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
}
