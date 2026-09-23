package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.fixture.FixtureCorpus

/**
 * One entry's renderer, at its natural frame.
 *
 * Framing is owned by [CatalogSceneMaterializer], never by the renderer: the renderer draws and
 * says nothing about the frame, so a pinned frame and an auto-sized one go through the same code.
 */
internal class CatalogSceneRenderer(
    /** Resource path of the primary font this renderer loads; checked against the entry's key. */
    val fontPath: String,
    /**
     * Platform route this renderer's scene needs; checked against the entry's declaration.
     *
     * The renderer's half of the two-way check: a renderer registered on a platform whose
     * capabilities cannot serve this route is a harness error, and the harness refuses it rather
     * than letting the scene fail at render time. A route is therefore never inferred from the
     * scene's code — the scene declares it, and the registry is checked against it.
     */
    val route: CatalogRoute,
    /**
     * Manifest id of the scene this renderer feeds, or `null` to inherit the entry's id.
     *
     * The scenes migrated from the pre-catalog registry keep the manifest keys they were committed
     * under, so their entries — named after the technology they cover — cannot supply that key.
     */
    val sceneId: String? = null,
    /**
     * Resource paths of the further families this renderer loads, in declaration order.
     *
     * Set exactly by a composition renderer, whose scene draws on several corpus families: the
     * materializer checks that the set of paths matches the entry's declared families in both
     * directions, so neither side can drift from the other.
     */
    val additionalFontPaths: List<String> = emptyList(),
    /** Renders at the natural frame, reading its fonts from the supplied corpus. */
    val render: (FixtureCorpus) -> GoldenRenderOutcome,
) {
    /** Every resource path this renderer loads, the primary one first. */
    val fontPaths: List<String> get() = listOf(fontPath) + additionalFontPaths
}
