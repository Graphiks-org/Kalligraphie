package org.graphiks.kalligraphie.font

/**
 * Font-domain refusal codes shared by core diagnostics and GPU consumers.
 *
 * Renderer-only refusal codes belong to the GPU API layer.
 */
object FontTextRefusalCodes {
    const val ARTIFACT_UNREGISTERED: String = "unsupported.text.artifact_unregistered"
}
