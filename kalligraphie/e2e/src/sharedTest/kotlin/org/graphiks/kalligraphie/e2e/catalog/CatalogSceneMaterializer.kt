package org.graphiks.kalligraphie.e2e.catalog

import kotlin.math.max
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenScene
import org.graphiks.kalligraphie.e2e.fixture.FixtureCorpus

/** Turns a catalog entry plus its renderer into the golden scene the verifier consumes. */
internal object CatalogSceneMaterializer {
    /**
     * Materializes every supported entry [renderers] is responsible for.
     *
     * The registry decides what a platform verifies, so an entry it does not register is left out
     * rather than failed: an entry a platform cannot serve is exactly what the capability ratchet
     * is there to check, and the catalog is the list of scenes, not the list of excuses. A registry
     * that forgets an entry it *should* serve is caught by that same ratchet, not here.
     */
    fun materializeAll(
        entries: List<CatalogEntry>,
        renderers: Map<String, CatalogSceneRenderer>,
        corpus: FixtureCorpus,
    ): List<GoldenSceneEntry> = entries.mapNotNull { entry ->
        val renderer = renderers[entry.id] ?: return@mapNotNull null
        when (entry.status) {
            is CatalogStatus.Supported -> materialize(entry, renderer, corpus)
            else -> null
        }
    }

    /** Materializes [entry] against [renderer], reading its fonts from [corpus]. */
    fun materialize(entry: CatalogEntry, renderer: CatalogSceneRenderer, corpus: FixtureCorpus): GoldenSceneEntry {
        val family = requireNotNull(entry.family) { "${entry.id} is supported but declares no family" }
        val frame = requireNotNull(entry.frame) { "${entry.id} is supported but declares no frame" }
        val sceneId = entry.sceneId ?: entry.id
        val natural = lazy { renderer.render(corpus) }
        return when (frame) {
            is SceneFramePolicy.Pinned -> {
                val scene = GoldenScene(sceneId, family, frame.width, frame.height, entry.tags)
                GoldenSceneEntry(scene) {
                    when (val outcome = natural.value) {
                        is GoldenRenderOutcome.Refused -> outcome
                        is GoldenRenderOutcome.Rendered -> {
                            val image = outcome.image
                            if (image.width != frame.width || image.height != frame.height) {
                                refused(scene, GoldenDiagnosticCode.SCENE_BOUNDS_INVALID, "rendered ${image.width}x${image.height}")
                            } else {
                                outcome
                            }
                        }
                    }
                }
            }

            is SceneFramePolicy.AutoSized -> {
                val outcome = natural.value
                if (outcome is GoldenRenderOutcome.Refused) {
                    val scene = GoldenScene(sceneId, family, 1, 1, entry.tags)
                    GoldenSceneEntry(scene) { outcome }
                } else {
                    val image = (outcome as GoldenRenderOutcome.Rendered).image
                    val ink = GoldenInkBox.of(image)
                    if (ink == null) {
                        val scene = GoldenScene(sceneId, family, max(1, image.width), max(1, image.height), entry.tags)
                        GoldenSceneEntry(scene) { refused(scene, GoldenDiagnosticCode.BLANK_SCENE, "measured no ink") }
                    } else {
                        val width = ink.width + 2 * frame.padding
                        val height = ink.height + 2 * frame.padding
                        val scene = GoldenScene(sceneId, family, width, height, entry.tags)
                        GoldenSceneEntry(scene) {
                            GoldenRenderOutcome.Rendered(
                                GoldenImageReframer.reframe(
                                    image = image,
                                    width = width,
                                    height = height,
                                    offsetX = frame.padding - ink.minX,
                                    offsetY = frame.padding - ink.minY,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the description of a corpus-family mismatch for [entry] and [renderer], or `null`
     * when they agree.
     *
     * An entry declares the primary family it rests on in `font`, plus every further family its
     * scene composes in `composedOf`; a renderer declares the resource path of each. Both
     * directions are checked: a family the entry declares must be loaded, and a font the renderer
     * loads must be declared, so a composed scene can neither hide a family nor claim one it never
     * draws.
     */
    fun fontPathMismatch(entry: CatalogEntry, renderer: CatalogSceneRenderer): String? {
        val declared = (listOfNotNull(entry.font) + entry.composedOf).map { key -> key.value }
        val loaded = renderer.fontPaths
        val missing = declared.filterNot { key -> loaded.any { path -> path.contains("/$key/") } }
        if (missing.isNotEmpty()) {
            return "entry ${entry.id} declares corpus families $missing but renders $loaded"
        }
        val undeclared = loaded.filterNot { path -> declared.any { key -> path.contains("/$key/") } }
        if (undeclared.isNotEmpty()) {
            return "entry ${entry.id} renders $undeclared without declaring their corpus families"
        }
        return null
    }

    private fun refused(scene: GoldenScene, code: GoldenDiagnosticCode, what: String) = GoldenRenderOutcome.Refused(
        code = code,
        detail = "${scene.id} $what",
    )

    /**
     * Returns the description of a manifest-key mismatch for [entry] and [renderer], or `null` when
     * they agree.
     *
     * The key the scene is certified under is declared by the entry, where a platform that defers
     * the scene can still read it, and by the renderer, where it describes the scene the code draws.
     * The harness refuses a disagreement: a renderer cannot write a fingerprint under a key the
     * catalog does not certify, and a catalog cannot certify a key no renderer produces.
     */
    fun sceneIdMismatch(entry: CatalogEntry, renderer: CatalogSceneRenderer): String? {
        val declared = entry.sceneId ?: entry.id
        val rendered = renderer.sceneId ?: entry.id
        return if (declared == rendered) {
            null
        } else {
            "entry ${entry.id} certifies the scene $declared but its renderer writes $rendered"
        }
    }

    /**
     * Returns the description of a platform-route mismatch for [entry] and [renderer], or `null`
     * when they agree.
     *
     * The route a scene needs decides which platforms may verify it, so it is declared twice — by
     * the entry, where it drives the documentation, and by the renderer, where it describes the
     * code. The harness refuses a disagreement instead of trusting either side: a scene cannot be
     * documented as portable while only the paragraph facade can render it, and it cannot be
     * excused from a platform on which its renderer would in fact run.
     */
    fun routeMismatch(entry: CatalogEntry, renderer: CatalogSceneRenderer): String? {
        val declared = entry.route
            ?: return "entry ${entry.id} has a renderer but declares no platform route"
        return if (declared == renderer.route) {
            null
        } else {
            "entry ${entry.id} declares route $declared but its renderer declares ${renderer.route}"
        }
    }
}
