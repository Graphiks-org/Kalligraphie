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

    /** Returns a description of the corpus-key mismatch for [entry] and [renderer], or `null` when they agree. */
    fun fontPathMismatch(entry: CatalogEntry, renderer: CatalogSceneRenderer): String? {
        val key = entry.font ?: return null
        return if (renderer.fontPath.contains("/${key.value}/")) {
            null
        } else {
            "entry ${entry.id} declares corpus key ${key.value} but renders ${renderer.fontPath}"
        }
    }

    private fun refused(scene: GoldenScene, code: GoldenDiagnosticCode, what: String) = GoldenRenderOutcome.Refused(
        code = code,
        detail = "${scene.id} $what",
    )
}
