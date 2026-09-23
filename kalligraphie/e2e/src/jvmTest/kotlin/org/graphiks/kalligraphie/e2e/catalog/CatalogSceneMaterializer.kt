package org.graphiks.kalligraphie.e2e.catalog

import kotlin.math.max
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenScene

/** Turns a catalog entry plus its renderer into the golden scene the verifier consumes. */
internal object CatalogSceneMaterializer {
    /** Materializes every supported entry, failing on the first entry that has no renderer. */
    fun materializeAll(
        entries: List<CatalogEntry>,
        renderers: Map<String, CatalogSceneRenderer>,
    ): List<JvmGoldenEntry> = entries.mapNotNull { entry ->
        when (entry.status) {
            is CatalogStatus.Supported -> materialize(entry, renderers.getValue(entry.id))
            else -> null
        }
    }

    /** Materializes [entry] against [renderer]. */
    fun materialize(entry: CatalogEntry, renderer: CatalogSceneRenderer): JvmGoldenEntry {
        val family = requireNotNull(entry.family) { "${entry.id} is supported but declares no family" }
        val frame = requireNotNull(entry.frame) { "${entry.id} is supported but declares no frame" }
        val sceneId = renderer.sceneId ?: entry.id
        val natural = lazy { renderer.render() }
        return when (frame) {
            is SceneFramePolicy.Pinned -> {
                val scene = GoldenScene(sceneId, family, frame.width, frame.height, entry.tags)
                JvmGoldenEntry(scene) {
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
                    JvmGoldenEntry(scene) { outcome }
                } else {
                    val image = (outcome as GoldenRenderOutcome.Rendered).image
                    val ink = GoldenInkBox.of(image)
                    if (ink == null) {
                        val scene = GoldenScene(sceneId, family, max(1, image.width), max(1, image.height), entry.tags)
                        JvmGoldenEntry(scene) { refused(scene, GoldenDiagnosticCode.BLANK_SCENE, "measured no ink") }
                    } else {
                        val width = ink.width + 2 * frame.padding
                        val height = ink.height + 2 * frame.padding
                        val scene = GoldenScene(sceneId, family, width, height, entry.tags)
                        JvmGoldenEntry(scene) {
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
}
