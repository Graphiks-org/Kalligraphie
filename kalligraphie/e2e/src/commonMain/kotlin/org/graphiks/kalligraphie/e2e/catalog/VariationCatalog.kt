// VariationCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** Variable-font expectations, including the technologies deliberately not supported yet. */
public object VariationCatalog {
    /** Declared variation expectations. */
    public val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "variation.wght-ladder",
            axis = CatalogAxis.VARIATION,
            technology = "Design-coordinate `wght` selection on a real variable font: one text at " +
                "five weights, each laid out, shaped and rasterised independently, on one baseline grid",
            font = CorpusKeys.WORK_SANS,
            status = CatalogStatus.Supported(sinceCommit = "4f9b70bd"),
            tags = setOf("variation:wght", "auto-sized"),
            // The ladder resolves its code points through `cmap`, shapes each row (GDEF/GPOS/GSUB),
            // instantiates the face through `fvar` and its `avar` segment map, varies the outlines
            // through `gvar` and the advances through `HVAR`, and reads `glyf`/`loca`/`hmtx` for the
            // result. `STAT` and `gasp` are carried and read by nobody; see CatalogClaims.
            tables = setOf("avar", "cmap", "fvar", "gvar", "GDEF", "glyf", "GPOS", "GSUB", "HVAR", "hmtx", "loca"),
            family = GoldenSceneFamily.COMPOSED_LINE,
            frame = SceneFramePolicy.AutoSized(padding = 2),
        ),
        documented(
            id = "variation.avar-v2",
            technology = "avar version 2 segment maps",
            unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
        ),
        documented(
            id = "variation.cvar",
            technology = "cvar CVT variations",
            unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
        ),
        documented(
            id = "variation.varc",
            technology = "VARC variable composite glyphs",
            unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
        ),
        documented(
            id = "variation.stat",
            technology = "STAT style attributes (named instances and their axes)",
            unpinnedReason = UnpinnedReason.READER_NOT_IMPLEMENTED,
        ),
    )

    private fun documented(id: String, technology: String, unpinnedReason: UnpinnedReason) = CatalogEntry(
        id = id,
        axis = CatalogAxis.VARIATION,
        technology = technology,
        font = null,
        status = CatalogStatus.NotYet(
            trackingIssue = "spec:§5 variation",
            currentBehavior = null,
            unpinnedReason = unpinnedReason,
        ),
        tags = setOf("documented-only"),
    )
}
