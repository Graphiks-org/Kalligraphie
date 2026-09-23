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
            technology = CatalogText("Latin composed line through the paragraph facade", "Ligne latine composée via la façade de paragraphe"),
            font = CorpusKeys.LIBERATION,
            scene = "line.latin.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:latin"),
        ),
        line(
            id = "script.greek.composed-line",
            technology = CatalogText("Greek composed line through the paragraph facade", "Ligne grecque composée via la façade de paragraphe"),
            font = CorpusKeys.LIBERATION,
            scene = "line.greek.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:greek"),
        ),
        line(
            id = "script.cyrillic.composed-line",
            technology = CatalogText("Cyrillic composed line through the paragraph facade", "Ligne cyrillique composée via la façade de paragraphe"),
            font = CorpusKeys.LIBERATION,
            scene = "line.cyrillic.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:cyrillic"),
        ),
        line(
            id = "script.arabic.composed-line",
            technology = CatalogText("Arabic composed line through the paragraph facade", "Ligne arabe composée via la façade de paragraphe"),
            font = CorpusKeys.AMIRI,
            scene = "line.arabic.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:arabic"),
        ),
        line(
            id = "script.devanagari.composed-line",
            technology = CatalogText("Devanagari composed line through the paragraph facade", "Ligne devanagari composée via la façade de paragraphe"),
            font = CorpusKeys.NOTO_DEVANAGARI,
            scene = "line.devanagari.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:devanagari"),
        ),
        line(
            id = "script.mixed.composed-line",
            technology = CatalogText("Mixed-script composed line over the three required faces (liberation, amiri, noto-devanagari)", "Ligne composée multi-scripts sur les trois polices requises (liberation, amiri, noto-devanagari)"),
            font = CorpusKeys.LIBERATION,
            scene = "line.mixed.48",
            sinceCommit = "fa405247",
            tags = setOf("scripts:mixed", "multi-face"),
        ),
        sheet(
            id = "script.latin.outline-sheet",
            technology = CatalogText("Latin outline alphabet sheet", "Planche d'alphabet latin en contour"),
            font = CorpusKeys.LIBERATION,
            scene = "sheet.outline.liberation-latin.32",
            sinceCommit = "fa405247",
            tags = setOf("scripts:latin"),
        ),
        sheet(
            id = "script.greek.outline-sheet",
            technology = CatalogText("Greek outline alphabet sheet", "Planche d'alphabet grec en contour"),
            font = CorpusKeys.LIBERATION,
            scene = "sheet.outline.liberation-greek.32",
            sinceCommit = "fa405247",
            tags = setOf("scripts:greek"),
        ),
        sheet(
            id = "script.cyrillic.outline-sheet",
            technology = CatalogText("Cyrillic outline alphabet sheet", "Planche d'alphabet cyrillique en contour"),
            font = CorpusKeys.LIBERATION,
            scene = "sheet.outline.liberation-cyrillic.32",
            sinceCommit = "fa405247",
            tags = setOf("scripts:cyrillic"),
        ),
        sheet(
            id = "script.arabic.outline-sheet",
            technology = CatalogText("Arabic outline alphabet sheet", "Planche d'alphabet arabe en contour"),
            font = CorpusKeys.AMIRI,
            scene = "sheet.outline.amiri-arabic.32",
            sinceCommit = "fa405247",
            tags = setOf("scripts:arabic"),
        ),
        sheet(
            id = "script.devanagari.outline-sheet",
            technology = CatalogText("Devanagari outline alphabet sheet", "Planche d'alphabet devanagari en contour"),
            font = CorpusKeys.NOTO_DEVANAGARI,
            scene = "sheet.outline.noto-devanagari.32",
            sinceCommit = "fa405247",
            tags = setOf("scripts:devanagari"),
        ),
    )

    private fun line(id: String, technology: CatalogText, font: CorpusKey, scene: String, sinceCommit: String, tags: Set<String>) =
        CatalogEntry(
            id = id,
            axis = CatalogAxis.SCRIPT,
            technology = technology,
            font = font,
            status = CatalogStatus.Supported(sinceCommit),
            tags = tags,
            tables = setOf("cmap", "GSUB", "GPOS", "GDEF"),
            family = GoldenSceneFamily.COMPOSED_LINE,
            sceneId = scene,
            frame = SceneFramePolicy.Pinned(width = PINNED_FRAMES.getValue(scene).first, height = PINNED_FRAMES.getValue(scene).second),
            route = CatalogRoute.PARAGRAPH_LAYOUT,
        )

    private fun sheet(id: String, technology: CatalogText, font: CorpusKey, scene: String, sinceCommit: String, tags: Set<String>) =
        CatalogEntry(
            id = id,
            axis = CatalogAxis.SCRIPT,
            technology = technology,
            font = font,
            status = CatalogStatus.Supported(sinceCommit),
            tags = tags,
            tables = setOf("cmap", "glyf", "loca", "hmtx"),
            family = GoldenSceneFamily.ALPHABET_SHEET,
            sceneId = scene,
            frame = SceneFramePolicy.Pinned(width = PINNED_FRAMES.getValue(scene).first, height = PINNED_FRAMES.getValue(scene).second),
            route = CatalogRoute.PORTABLE_GLYPH,
        )
}
