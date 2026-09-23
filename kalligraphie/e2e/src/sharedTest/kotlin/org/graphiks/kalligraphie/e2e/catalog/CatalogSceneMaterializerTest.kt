package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily
import org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment
import org.graphiks.kalligraphie.e2e.fixture.FixtureCorpus

class CatalogSceneMaterializerTest {
    @Test
    fun anAutoSizedSceneFramesItsInkBoxWithPadding() {
        val entry = autoSizedEntry(padding = 2)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf", CatalogRoute.PORTABLE_GLYPH) { _ ->
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(4, 3, inkAt(x = 1, y = 1, width = 4, height = 3)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer, E2eTestEnvironment.corpus)

        assertEquals(6, materialized.scene.width, "ink box is 2x2, padding 2 on each side")
        assertEquals(6, materialized.scene.height)
        val framed = assertIs<GoldenRenderOutcome.Rendered>(materialized.render()).image
        assertEquals(6, framed.width)
        assertEquals(6, framed.height)
        // The ink sits at (1,1) and (2,2) of the natural 4x3 image, so the tight box starts one pixel
        // from the origin and the reframe moves it to (padding, padding) == (2,2) of the 6x6 frame:
        // the two lit pixels land at (2,2) and (3,3), at byte index y * 6 + x.
        assertContentEquals(
            byteArrayOf(
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
                0, 0, 0xFF.toByte(), 0, 0, 0,
                0, 0, 0, 0xFF.toByte(), 0, 0,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
            ),
            framed.copyCanonicalBytes(),
        )
    }

    @Test
    fun aPinnedSceneRefusesAFrameThatNoLongerMatches() {
        val entry = pinnedEntry(width = 10, height = 10)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf", CatalogRoute.PORTABLE_GLYPH) { _ ->
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(9, 10, ByteArray(90)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer, E2eTestEnvironment.corpus)

        val refused = assertIs<GoldenRenderOutcome.Refused>(materialized.render())
        assertEquals(GoldenDiagnosticCode.SCENE_BOUNDS_INVALID, refused.code)
    }

    @Test
    fun aPinnedSceneKeepsItsDeclaredFrameWhenTheRenderAgrees() {
        val entry = pinnedEntry(width = 10, height = 10)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf", CatalogRoute.PORTABLE_GLYPH) { _ ->
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(10, 10, ByteArray(100)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer, E2eTestEnvironment.corpus)

        assertEquals(10, materialized.scene.width)
        assertEquals(GoldenSceneFamily.GLYPH_OUTLINE, materialized.scene.family)
        val rendered = assertIs<GoldenRenderOutcome.Rendered>(materialized.render())
        assertEquals(10, rendered.image.width, "an agreeing render keeps the pinned frame")
        assertEquals(10, rendered.image.height)
    }

    @Test
    fun anAutoSizedSceneWithNoInkIsRefused() {
        val entry = autoSizedEntry(padding = 1)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf", CatalogRoute.PORTABLE_GLYPH) { _ ->
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(8, 8, ByteArray(64)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer, E2eTestEnvironment.corpus)

        val refused = assertIs<GoldenRenderOutcome.Refused>(materialized.render())
        assertEquals(GoldenDiagnosticCode.BLANK_SCENE, refused.code)
    }

    @Test
    fun theRendererFontPathMustBelongToTheEntryCorpusKey() {
        val entry = autoSizedEntry(padding = 1)
        val renderer = CatalogSceneRenderer("/fonts/amiri/Amiri-Regular.ttf", CatalogRoute.PORTABLE_GLYPH) { _ ->
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(2, 2, byteArrayOf(0, 0, 0, 1)))
        }

        val failure = CatalogSceneMaterializer.fontPathMismatch(entry, renderer)
        assertEquals(
            "entry outline.glyf declares corpus families [liberation] but renders " +
                "[/fonts/amiri/Amiri-Regular.ttf]",
            failure,
        )
    }

    @Test
    fun aComposedRendererMustLoadEveryFamilyItsEntryDeclares() {
        val entry = composedEntry(composedOf = listOf(CorpusKeys.AMIRI, CorpusKeys.NOTO_SANS_JP))
        val renderer = CatalogSceneRenderer(
            "/fonts/liberation/LiberationSans-Regular.ttf",
            CatalogRoute.PORTABLE_GLYPH,
            additionalFontPaths = listOf("/fonts/amiri/Amiri-Regular.ttf"),
        ) { _ ->
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(2, 2, byteArrayOf(0, 0, 0, 1)))
        }

        assertEquals(
            "entry outline.glyf declares corpus families [noto-sans-jp] but renders " +
                "[/fonts/liberation/LiberationSans-Regular.ttf, /fonts/amiri/Amiri-Regular.ttf]",
            CatalogSceneMaterializer.fontPathMismatch(entry, renderer),
        )
    }

    @Test
    fun aRendererMustNotLoadAFamilyItsEntryNeverDeclares() {
        val entry = composedEntry(composedOf = listOf(CorpusKeys.AMIRI))
        val renderer = CatalogSceneRenderer(
            "/fonts/liberation/LiberationSans-Regular.ttf",
            CatalogRoute.PORTABLE_GLYPH,
            additionalFontPaths = listOf(
                "/fonts/amiri/Amiri-Regular.ttf",
                "/fonts/skia-cbdt/cbdt.ttf",
            ),
        ) { _ ->
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(2, 2, byteArrayOf(0, 0, 0, 1)))
        }

        assertEquals(
            "entry outline.glyf renders [/fonts/skia-cbdt/cbdt.ttf] without declaring their " +
                "corpus families",
            CatalogSceneMaterializer.fontPathMismatch(entry, renderer),
        )
    }

    @Test
    fun theRendererRouteMustMatchTheEntryRoute() {
        val entry = autoSizedEntry(padding = 1)
        val rendering: (FixtureCorpus) -> GoldenRenderOutcome = { _ ->
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(2, 2, byteArrayOf(0, 0, 0, 1)))
        }

        assertEquals(
            null,
            CatalogSceneMaterializer.routeMismatch(
                entry,
                CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf", CatalogRoute.PORTABLE_GLYPH, render = rendering),
            ),
        )
        assertEquals(
            "entry outline.glyf declares route PORTABLE_GLYPH but its renderer declares PARAGRAPH_LAYOUT",
            CatalogSceneMaterializer.routeMismatch(
                entry,
                CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf", CatalogRoute.PARAGRAPH_LAYOUT, render = rendering),
            ),
        )
    }

    private fun composedEntry(composedOf: List<CorpusKey>) = CatalogEntry(
        id = "outline.glyf",
        axis = CatalogAxis.OUTLINE,
        technology = CatalogText("glyf outlines over several families", "contours glyf sur plusieurs familles"),
        font = CorpusKey("liberation"),
        status = CatalogStatus.Supported("abc1234"),
        tables = setOf("glyf"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        frame = SceneFramePolicy.AutoSized(padding = 1),
        route = CatalogRoute.PORTABLE_GLYPH,
        composedOf = composedOf,
        composedTables = composedOf.associateWith { key -> setOf("cmap") },
    )

    @Test
    fun theEntryManifestKeyOverridesTheEntryIdAndDefaultsToIt() {
        val entry = autoSizedEntry(padding = 1)
        val keyed = autoSizedEntry(padding = 1, sceneId = "glyph.outline.liberation-sans.A.64")
        val render: (FixtureCorpus) -> GoldenRenderOutcome = { _ ->
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(2, 2, byteArrayOf(0, 0, 0, 1)))
        }
        val inheriting = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf", CatalogRoute.PORTABLE_GLYPH, render = render)
        val overriding = CatalogSceneRenderer(
            "/fonts/liberation/LiberationSans-Regular.ttf",
            CatalogRoute.PORTABLE_GLYPH,
            sceneId = "glyph.outline.liberation-sans.A.64",
            render = render,
        )

        val corpus = E2eTestEnvironment.corpus
        assertEquals("outline.glyf", CatalogSceneMaterializer.materialize(entry, inheriting, corpus).scene.id)
        assertEquals(
            "glyph.outline.liberation-sans.A.64",
            CatalogSceneMaterializer.materialize(keyed, overriding, corpus).scene.id,
        )
    }

    @Test
    fun theRendererManifestKeyMustMatchTheEntryKey() {
        val entry = autoSizedEntry(padding = 1, sceneId = "glyph.outline.liberation-sans.A.64")
        val render: (FixtureCorpus) -> GoldenRenderOutcome = { _ ->
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(2, 2, byteArrayOf(0, 0, 0, 1)))
        }
        val agreeing = CatalogSceneRenderer(
            "/fonts/liberation/LiberationSans-Regular.ttf",
            CatalogRoute.PORTABLE_GLYPH,
            sceneId = "glyph.outline.liberation-sans.A.64",
            render = render,
        )
        val disagreeing = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf", CatalogRoute.PORTABLE_GLYPH, render = render)

        assertEquals(null, CatalogSceneMaterializer.sceneIdMismatch(entry, agreeing))
        assertEquals(
            "entry outline.glyf certifies the scene glyph.outline.liberation-sans.A.64 but its renderer writes outline.glyf",
            CatalogSceneMaterializer.sceneIdMismatch(entry, disagreeing),
        )
    }

    private fun autoSizedEntry(padding: Int, sceneId: String? = null) = CatalogEntry(
        id = "outline.glyf",
        axis = CatalogAxis.OUTLINE,
        technology = CatalogText("glyf outlines", "contours glyf"),
        font = CorpusKey("liberation"),
        status = CatalogStatus.Supported("abc1234"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        sceneId = sceneId,
        frame = SceneFramePolicy.AutoSized(padding = padding),
        route = CatalogRoute.PORTABLE_GLYPH,
    )

    private fun pinnedEntry(width: Int, height: Int) = CatalogEntry(
        id = "outline.glyf",
        axis = CatalogAxis.OUTLINE,
        technology = CatalogText("glyf outlines", "contours glyf"),
        font = CorpusKey("liberation"),
        status = CatalogStatus.Supported("abc1234"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        frame = SceneFramePolicy.Pinned(width = width, height = height),
        route = CatalogRoute.PORTABLE_GLYPH,
    )

    private fun inkAt(x: Int, y: Int, width: Int, height: Int): ByteArray {
        val pixels = ByteArray(width * height)
        pixels[y * width + x] = 0xFF.toByte()
        pixels[(y + 1) * width + x + 1] = 0xFF.toByte()
        return pixels
    }
}
