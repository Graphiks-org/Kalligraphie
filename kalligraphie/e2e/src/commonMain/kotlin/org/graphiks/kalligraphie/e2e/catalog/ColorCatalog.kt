// ColorCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** Colour-glyph expectations: the migrated COLR v0 scenes, then the documented and pinned gaps. */
public object ColorCatalog {
    /** Declared colour expectations. */
    public val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "color.colr-v0-single-glyph",
            axis = CatalogAxis.COLOR,
            technology = CatalogText("COLR v0 + CPAL v0 single-glyph paint graph", "Graphe de peinture COLR v0 + CPAL v0 d'un glyphe isolé"),
            font = CorpusKeys.EMOJI_TWO_COLR_V0,
            status = CatalogStatus.Supported(sinceCommit = "fa405247"),
            tags = setOf("scripts:emoji"),
            tables = setOf("COLR", "CPAL", "glyf", "loca"),
            family = GoldenSceneFamily.GLYPH_PAINT,
            frame = SceneFramePolicy.Pinned(width = 71, height = 72),
            route = CatalogRoute.PORTABLE_GLYPH,
        ),
        CatalogEntry(
            id = "color.colr-v0-alphabet-sheet",
            axis = CatalogAxis.COLOR,
            technology = CatalogText("COLR v0 + CPAL v0 Latin alphabet sheet", "Planche d'alphabet latin COLR v0 + CPAL v0"),
            font = CorpusKeys.BUNGEE_COLOR,
            status = CatalogStatus.Supported(sinceCommit = "fa405247"),
            tags = setOf("scripts:latin"),
            tables = setOf("COLR", "CPAL", "glyf", "loca"),
            family = GoldenSceneFamily.ALPHABET_SHEET,
            frame = SceneFramePolicy.Pinned(width = 656, height = 90),
            route = CatalogRoute.PORTABLE_GLYPH,
        ),
        CatalogEntry(
            id = "color.colr-v0-emoji-sheet",
            axis = CatalogAxis.COLOR,
            technology = CatalogText("COLR v0 + CPAL v0 emoji alphabet sheet", "Planche d'alphabet emoji COLR v0 + CPAL v0"),
            font = CorpusKeys.EMOJI_TWO_COLR_V0,
            status = CatalogStatus.Supported(sinceCommit = "fa405247"),
            tags = setOf("scripts:emoji"),
            tables = setOf("COLR", "CPAL", "glyf", "loca"),
            family = GoldenSceneFamily.ALPHABET_SHEET,
            frame = SceneFramePolicy.Pinned(width = 1200, height = 76),
            route = CatalogRoute.PORTABLE_GLYPH,
        ),
        CatalogEntry(
            id = "color.colr-cff",
            axis = CatalogAxis.COLOR,
            technology = CatalogText("CFF-backed COLR glyphs", "Glyphes COLR adossés à des charstrings CFF"),
            font = null,
            status = CatalogStatus.OutOfScope(
                CatalogText(
                    "CFF-in-COLR is not part of the supported paint surface; the rationale is recorded in font-management.md.",
                    "Le CFF-dans-COLR ne fait pas partie de la surface de peinture supportée ; le motif est consigné dans font-management.md.",
                ),
            ),
            tags = setOf("documented-only"),
        ),
        CatalogEntry(
            id = "color.colr-v1-variable",
            axis = CatalogAxis.COLOR,
            technology = CatalogText("Variable COLR v1 paint graphs; blocked today by the CPU compositor, which does not composite GlyphClip nodes", "Graphes de peinture COLR v1 variables ; bloqués aujourd'hui par le compositeur CPU, qui ne compose pas les nœuds GlyphClip"),
            font = CorpusKeys.KALLIGRAPHIE_VAR_COLR,
            status = CatalogStatus.NotYet(
                trackingIssue = "spec:§5 color",
                currentBehavior = PinnedBehavior.RejectedAt(
                    stage = CatalogStage.FACE_RESOLUTION,
                    diagnostic = "font.unsupported-representation-profile",
                ),
                unpinnedReason = null,
            ),
            tags = setOf("color:colr-v1", "blocked-by-compositor"),
            tables = setOf("COLR", "CPAL", "fvar"),
        ),
        documented(
            id = "color.cpal-variable",
            technology = CatalogText("variable CPAL palettes", "Palettes CPAL variables"),
            unpinnedReason = UnpinnedReason.CORPUS_NOT_ACQUIRED,
        ),
    )

    private fun documented(id: String, technology: CatalogText, unpinnedReason: UnpinnedReason) = CatalogEntry(
        id = id,
        axis = CatalogAxis.COLOR,
        technology = technology,
        font = null,
        status = CatalogStatus.NotYet(
            trackingIssue = "spec:§5 color",
            currentBehavior = null,
            unpinnedReason = unpinnedReason,
        ),
        tags = setOf("documented-only"),
    )
}
