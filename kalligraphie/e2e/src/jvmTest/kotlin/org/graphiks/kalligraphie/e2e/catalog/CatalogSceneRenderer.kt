package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

/**
 * One entry's renderer, at its natural frame.
 *
 * Framing is owned by [CatalogSceneMaterializer], never by the renderer: the renderer draws and
 * says nothing about the frame, so a pinned frame and an auto-sized one go through the same code.
 */
internal class CatalogSceneRenderer(
    /** Resource path of the font this renderer loads; checked against the entry's corpus key. */
    val fontPath: String,
    /**
     * Manifest id of the scene this renderer feeds, or `null` to inherit the entry's id.
     *
     * The scenes migrated from the pre-catalog registry keep the manifest keys they were committed
     * under, so their entries — named after the technology they cover — cannot supply that key.
     */
    val sceneId: String? = null,
    /** Renders at the natural frame. */
    val render: () -> GoldenRenderOutcome,
)
