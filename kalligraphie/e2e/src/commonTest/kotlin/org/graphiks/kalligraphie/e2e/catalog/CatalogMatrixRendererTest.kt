// CatalogMatrixRendererTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

class CatalogMatrixRendererTest {
    @Test
    fun theMatrixCountsMatchTheCatalog() {
        val rendered = CatalogMatrixRenderer.render(listOf(entry), CatalogMatrixLanguage.EN)
        assertTrue(rendered.contains("| Supported | 1 |"), rendered)
    }

    @Test
    fun theTwoLanguagesDifferOnlyInTheirProse() {
        val en = CatalogMatrixRenderer.render(listOf(entry), CatalogMatrixLanguage.EN)
        val fr = CatalogMatrixRenderer.render(listOf(entry), CatalogMatrixLanguage.FR)
        assertTrue(en.contains("Supported"), en)
        assertTrue(fr.contains("Supporté"), fr)
        assertEquals(en.lines().count { line -> line.startsWith("| ") }, fr.lines().count { line -> line.startsWith("| ") })
    }

    @Test
    fun theMatrixNamesTheFontAndTheStatusOfEachEntry() {
        val rendered = CatalogMatrixRenderer.render(listOf(entry), CatalogMatrixLanguage.EN)
        assertTrue(rendered.contains("outline.glyf"), rendered)
        assertTrue(rendered.contains("liberation"), rendered)
        assertTrue(rendered.contains("abc1234"), rendered)
    }

    @Test
    fun theCommittedFileNamesAreStable() {
        assertEquals("e2e-catalog-matrix.md", CatalogMatrixRenderer.fileName(CatalogMatrixLanguage.EN))
        assertEquals("e2e-catalog-matrix.fr.md", CatalogMatrixRenderer.fileName(CatalogMatrixLanguage.FR))
    }

    private val entry = CatalogEntry(
        id = "outline.glyf",
        axis = CatalogAxis.OUTLINE,
        technology = CatalogText("glyf outlines", "contours glyf"),
        font = CorpusKey("liberation"),
        status = CatalogStatus.Supported("abc1234"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        frame = SceneFramePolicy.AutoSized(),
    )
}
