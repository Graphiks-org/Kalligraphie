package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.fixture.FixtureCorpus
import org.graphiks.kalligraphie.e2e.golden.ComposedLineScenes
import org.graphiks.kalligraphie.e2e.golden.CompositionMosaicScene
import org.graphiks.kalligraphie.e2e.golden.VariationLadderScene

/**
 * The scenes that lay text out through the paragraph facade.
 *
 * Composing *text* is the one catalogued step the portable route does not offer: a paragraph
 * request needs Unicode analysis and shaping, which the shipped targets declare absent, so these
 * renderers compile and run on the reference platform alone. They live in their own registry rather
 * than beside the portable ones because a platform that cannot serve
 * [CatalogRoute.PARAGRAPH_LAYOUT] must not be able to register them — the registry is the fact the
 * capability ratchet checks, and here it is also the only place they *can* live, since the facade
 * they call does not exist off the JVM.
 */
internal object JvmSceneRenderers {
    /** Every paragraph-facade renderer, keyed by catalog entry id. */
    val byId: Map<String, CatalogSceneRenderer> = mapOf(
        "script.latin.composed-line" to CatalogSceneRenderer(LIBERATION_SANS, CatalogRoute.PARAGRAPH_LAYOUT, sceneId = "line.latin.48") { corpus ->
            composed { ComposedLineScenes.line(corpus, "Kalligraphie", "en", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.greek.composed-line" to CatalogSceneRenderer(LIBERATION_SANS, CatalogRoute.PARAGRAPH_LAYOUT, sceneId = "line.greek.48") { corpus ->
            composed { ComposedLineScenes.line(corpus, "Καλλιγραφία", "el", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.cyrillic.composed-line" to CatalogSceneRenderer(LIBERATION_SANS, CatalogRoute.PARAGRAPH_LAYOUT, sceneId = "line.cyrillic.48") { corpus ->
            composed { ComposedLineScenes.line(corpus, "Каллиграфия", "ru", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.arabic.composed-line" to CatalogSceneRenderer(AMIRI, CatalogRoute.PARAGRAPH_LAYOUT, sceneId = "line.arabic.48") { corpus ->
            composed { ComposedLineScenes.line(corpus, "الخط العربي", "ar", BaseDirection.RIGHT_TO_LEFT, requiredFaces = 0) }
        },
        "script.devanagari.composed-line" to CatalogSceneRenderer(NOTO_DEVANAGARI, CatalogRoute.PARAGRAPH_LAYOUT, sceneId = "line.devanagari.48") { corpus ->
            composed { ComposedLineScenes.line(corpus, "देवनागरी", "hi", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.mixed.composed-line" to CatalogSceneRenderer(LIBERATION_SANS, CatalogRoute.PARAGRAPH_LAYOUT, sceneId = "line.mixed.48") { corpus ->
            composed {
                ComposedLineScenes.line(
                    corpus,
                    "Kalligraphie — Ελληνικά — Кириллица — العربية — देवनागरी",
                    "en",
                    BaseDirection.LEFT_TO_RIGHT,
                    requiredFaces = 3,
                )
            }
        },
        "variation.wght-ladder" to CatalogSceneRenderer(WORK_SANS, CatalogRoute.PARAGRAPH_LAYOUT) { corpus ->
            composed { renderWeightLadder(corpus) }
        },
        "composition.every-route-mosaic" to CatalogSceneRenderer(
            fontPath = LIBERATION_SANS,
            route = CatalogRoute.PARAGRAPH_LAYOUT,
            additionalFontPaths = listOf(
                AMIRI,
                NOTO_DEVANAGARI,
                NOTO_SANS_JP,
                CFF_LIBERATION,
                CFF2_LIBERATION,
                EMOJI_TWO_COLR_V0,
                SKIA_CBDT,
            ),
        ) { corpus ->
            composed { CompositionMosaicScene.mosaic(corpus) }
        },
    )

    /** The weights of the ladder scene, lightest first: the named instances the family publishes. */
    private val LADDER_WEIGHTS: List<Float> = listOf(100f, 300f, 500f, 700f, 900f)

    /** Renders the capitalised word at every weight of the variable fixture, on one baseline grid. */
    private fun renderWeightLadder(corpus: FixtureCorpus): GoldenImage =
        VariationLadderScene.ladder(corpus, "Kalligraphie", WORK_SANS, LADDER_WEIGHTS)
}
