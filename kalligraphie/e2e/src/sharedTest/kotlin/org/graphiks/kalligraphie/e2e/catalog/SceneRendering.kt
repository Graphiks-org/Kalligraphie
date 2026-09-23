package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

/**
 * Renders a composed scene, turning any failure into a typed refusal.
 *
 * A composed scene throws on a broken premise — a resolved code point with no ink, a face that
 * cannot be opened, a frame that no longer holds — and the harness reports every scene failure the
 * same way, so a broken scene is a refused fingerprint rather than a crashed suite.
 */
internal inline fun composed(render: () -> GoldenImage): GoldenRenderOutcome =
    try {
        GoldenRenderOutcome.Rendered(render())
    } catch (error: Throwable) {
        GoldenRenderOutcome.Refused(
            code = GoldenDiagnosticCode.RENDER_FAILED,
            detail = "composed scene failed: ${error.message}",
        )
    }

/** A refusal of [what] caused by the diagnostic [field] the rasterizer named. */
internal fun refused(what: String, field: String): GoldenRenderOutcome = GoldenRenderOutcome.Refused(
    code = GoldenDiagnosticCode.RENDER_FAILED,
    detail = "$what refused: $field",
)
