// ScriptCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** Writing-system expectations: one entry per script the end-to-end catalog renders. */
public object ScriptCatalog {
    /**
     * Frame pinned per scene, copied verbatim from the committed golden manifest. Declared before
     * [entries] so the entry builders can read it while this object is being initialised.
     */
    private val PINNED_FRAMES: Map<String, Pair<Int, Int>> = mapOf(
        "line.latin.48" to (250 to 49),
        "line.greek.48" to (267 to 51),
        "line.cyrillic.48" to (299 to 49),
        "line.arabic.48" to (182 to 64),
        "line.devanagari.48" to (156 to 52),
        "line.mixed.48" to (1235 to 62),
        "sheet.outline.liberation-latin.32" to (576 to 140),
        "sheet.outline.liberation-greek.32" to (464 to 140),
        "sheet.outline.liberation-cyrillic.32" to (560 to 160),
        "sheet.outline.amiri-arabic.32" to (736 to 162),
        "sheet.outline.noto-devanagari.32" to (624 to 156),
    )

    /** Declared script expectations. */
    public val entries: List<CatalogEntry> = listOf(
        line(
            id = "script.latin.composed-line",
            technology = "Latin composed line through the paragraph facade",
            font = CorpusKeys.LIBERATION,
            scene = "line.latin.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:latin"),
        ),
        line(
            id = "script.greek.composed-line",
            technology = "Greek composed line through the paragraph facade",
            font = CorpusKeys.LIBERATION,
            scene = "line.greek.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:greek"),
        ),
        line(
            id = "script.cyrillic.composed-line",
            technology = "Cyrillic composed line through the paragraph facade",
            font = CorpusKeys.LIBERATION,
            scene = "line.cyrillic.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:cyrillic"),
        ),
        line(
            id = "script.arabic.composed-line",
            technology = "Arabic composed line through the paragraph facade",
            font = CorpusKeys.AMIRI,
            scene = "line.arabic.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:arabic"),
        ),
        line(
            id = "script.devanagari.composed-line",
            technology = "Devanagari composed line through the paragraph facade",
            font = CorpusKeys.NOTO_DEVANAGARI,
            scene = "line.devanagari.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:devanagari"),
        ),
        line(
            id = "script.mixed.composed-line",
            technology = "Mixed-script composed line over the three required faces (liberation, amiri, noto-devanagari)",
            font = CorpusKeys.LIBERATION,
            scene = "line.mixed.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:mixed", "multi-face"),
        ),
        sheet(
            id = "script.latin.outline-sheet",
            technology = "Latin outline alphabet sheet",
            font = CorpusKeys.LIBERATION,
            scene = "sheet.outline.liberation-latin.32",
            sinceCommit = "fa405247",
            tags = setOf("scripts:latin"),
        ),
        sheet(
            id = "script.greek.outline-sheet",
            technology = "Greek outline alphabet sheet",
            font = CorpusKeys.LIBERATION,
            scene = "sheet.outline.liberation-greek.32",
            sinceCommit = "fa405247",
            tags = setOf("scripts:greek"),
        ),
        sheet(
            id = "script.cyrillic.outline-sheet",
            technology = "Cyrillic outline alphabet sheet",
            font = CorpusKeys.LIBERATION,
            scene = "sheet.outline.liberation-cyrillic.32",
            sinceCommit = "fa405247",
            tags = setOf("scripts:cyrillic"),
        ),
        sheet(
            id = "script.arabic.outline-sheet",
            technology = "Arabic outline alphabet sheet",
            font = CorpusKeys.AMIRI,
            scene = "sheet.outline.amiri-arabic.32",
            sinceCommit = "fa405247",
            tags = setOf("scripts:arabic"),
        ),
        sheet(
            id = "script.devanagari.outline-sheet",
            technology = "Devanagari outline alphabet sheet",
            font = CorpusKeys.NOTO_DEVANAGARI,
            scene = "sheet.outline.noto-devanagari.32",
            sinceCommit = "fa405247",
            tags = setOf("scripts:devanagari"),
        ),
    )

    private fun line(id: String, technology: String, font: CorpusKey, scene: String, sinceCommit: String, tags: Set<String>) =
        CatalogEntry(
            id = id,
            axis = CatalogAxis.SCRIPT,
            technology = technology,
            font = font,
            status = CatalogStatus.Supported(sinceCommit),
            tags = tags,
            tables = setOf("cmap", "GSUB", "GPOS", "GDEF"),
            family = GoldenSceneFamily.COMPOSED_LINE,
            frame = SceneFramePolicy.Pinned(width = PINNED_FRAMES.getValue(scene).first, height = PINNED_FRAMES.getValue(scene).second),
        )

    private fun sheet(id: String, technology: String, font: CorpusKey, scene: String, sinceCommit: String, tags: Set<String>) =
        CatalogEntry(
            id = id,
            axis = CatalogAxis.SCRIPT,
            technology = technology,
            font = font,
            status = CatalogStatus.Supported(sinceCommit),
            tags = tags,
            tables = setOf("cmap", "glyf", "loca", "hmtx"),
            family = GoldenSceneFamily.ALPHABET_SHEET,
            frame = SceneFramePolicy.Pinned(width = PINNED_FRAMES.getValue(scene).first, height = PINNED_FRAMES.getValue(scene).second),
        )
}
