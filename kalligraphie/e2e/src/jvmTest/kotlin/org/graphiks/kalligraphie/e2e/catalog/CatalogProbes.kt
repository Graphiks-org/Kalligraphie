// CatalogProbes.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.e2e.golden.fixtureBytes
import org.graphiks.kalligraphie.e2e.golden.paintRequirements
import kotlin.test.assertIs

/** The registered probes, keyed by catalog entry id. */
internal object CatalogProbes {
    private const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"
    private const val KALLIGRAPHIE_VAR_COLR = "/fonts/kalligraphie-var-colr/KalligraphieVarCOLRv1.ttf"

    /** Every registered probe. */
    val byId: Map<String, CatalogProbe> = mapOf(
        "robustness.truncated-sfnt" to CatalogProbe {
            val full = fixtureBytes(LIBERATION_SANS)
            decodeOutcome(full.copyOf(full.size / 3))
        },
        "robustness.empty-input" to CatalogProbe { decodeOutcome(ByteArray(0)) },
        "color.colr-v1-variable" to CatalogProbe {
            faceResolutionOutcome(KALLIGRAPHIE_VAR_COLR, paintRequirements())
        },
    )

    /** Translates the facade's decode result into a probe observation. */
    private fun decodeOutcome(bytes: ByteArray): ProbeObservation =
        when (val result = Kalligraphie.embedded(sourceBytes = bytes, provenance = FontSourceProvenance(declaredName = "e2e robustness probe"))) {
            is FontOperationResult.Success<*> -> ProbeObservation.Succeeded(
                stage = CatalogStage.DECODE,
                observation = "decoding succeeds; the facade accepts ${bytes.size} bytes",
            )

            is FontOperationResult.Failure -> ProbeObservation.Rejected(
                stage = CatalogStage.DECODE,
                diagnostic = result.error.code,
            )

            is FontOperationResult.Cancelled -> ProbeObservation.Succeeded(
                stage = CatalogStage.DECODE,
                observation = "cancelled; no decode verdict",
            )
        }

    /**
     * Translates the facade's face-resolution result for the [fontPath] fixture into an observation.
     *
     * The catalog itself must open: an undecodable source is a hard regression rather than the
     * refusal a face-resolution probe pins, so it fails with the facade's own message instead of
     * being reported as a face-resolution verdict.
     */
    private fun faceResolutionOutcome(fontPath: String, requirements: FontAccessRequirementsSnapshot): ProbeObservation {
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(
                sourceBytes = fixtureBytes(fontPath),
                provenance = FontSourceProvenance(declaredName = "e2e face-resolution probe"),
            ),
        ).value
        return when (val result = catalog.resolveFace(catalog.faces.single().id, requirements)) {
            is FontOperationResult.Success<*> -> ProbeObservation.Succeeded(
                stage = CatalogStage.FACE_RESOLUTION,
                observation = "face resolution succeeds",
            )

            is FontOperationResult.Failure -> ProbeObservation.Rejected(
                stage = CatalogStage.FACE_RESOLUTION,
                diagnostic = result.error.code,
            )

            is FontOperationResult.Cancelled -> ProbeObservation.Succeeded(
                stage = CatalogStage.FACE_RESOLUTION,
                observation = "cancelled; no face-resolution verdict",
            )
        }
    }
}
