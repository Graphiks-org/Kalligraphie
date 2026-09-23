// CompositionCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/**
 * Expectations about composing heterogeneous material into one artefact.
 *
 * The other axes each own one technology, one representation or one writing system. This axis owns
 * the question a consumer actually asks — can several of them share one image — which no single-axis
 * scene can answer, because a scene that draws one route never has to mix anything.
 */
public object CompositionCatalog {
    /** Declared composition expectations. */
    public val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "composition.every-route-mosaic",
            axis = CatalogAxis.COMPOSITION,
            technology = CatalogText(
                "Seven rendering routes in one image: one word in a TrueType outline, the capital A " +
                    "in a CFF 1 and in a CFF 2 outline, a line whose three scripts resolve through " +
                    "three-face fallback, one glyph at four design weights, a colour paint and a " +
                    "bitmap strike, composed on one colour canvas with a shared left margin",
                "Sept routes de rendu dans une seule image : un mot en contour TrueType, le A " +
                    "majuscule en contour CFF 1 puis CFF 2, une ligne dont les trois scripts sont " +
                    "résolus par repli entre trois polices, un glyphe à quatre graisses, une " +
                    "peinture couleur et un strike bitmap, composés sur un même canvas couleur à " +
                    "marge gauche partagée",
            ),
            font = CorpusKeys.LIBERATION,
            status = CatalogStatus.Supported(sinceCommit = "58ba2267"),
            tags = setOf("composition:mosaic", "auto-sized"),
            // The mosaic draws the Latin bands through `cmap` and `glyf`/`loca`, shapes the
            // multi-script line (GDEF/GPOS/GSUB) and reads `hmtx` for its advances. Every other
            // family it composes is claimed by that family's own entry, because the tables each one
            // exercises are the same here as there; see CatalogClaims for what the fixture families
            // carry unread.
            tables = setOf("cmap", "glyf", "loca", "hmtx", "GDEF", "GPOS", "GSUB"),
            family = GoldenSceneFamily.MOSAIC,
            frame = SceneFramePolicy.AutoSized(padding = 2),
            composedOf = listOf(
                CorpusKeys.AMIRI,
                CorpusKeys.NOTO_DEVANAGARI,
                CorpusKeys.NOTO_SANS_JP,
                CorpusKeys.CFF_LIBERATION,
                CorpusKeys.CFF2_LIBERATION,
                CorpusKeys.EMOJI_TWO_COLR_V0,
                CorpusKeys.SKIA_CBDT,
            ),
            composedTables = mapOf(
                // The multi-script band shapes Arabic and Devanagari, so those families contribute
                // outlines and layout tables exactly as their own line entries claim.
                CorpusKeys.AMIRI to setOf("GDEF", "GPOS", "GSUB", "cmap", "glyf", "hmtx", "loca"),
                CorpusKeys.NOTO_DEVANAGARI to setOf("GDEF", "GPOS", "GSUB", "cmap", "glyf", "hmtx", "loca"),
                // The style band instantiates the fixture at four design weights, so it reads the
                // axis tables and the deltas there; `BASE`, `HVAR`, `STAT`, `gasp`, `vhea` and `vmtx`
                // stay carried and unread, with their reasons in CatalogClaims.
                CorpusKeys.NOTO_SANS_JP to setOf(
                    "GDEF", "GPOS", "GSUB", "avar", "cmap", "fvar", "gvar", "glyf", "hmtx", "loca",
                ),
                CorpusKeys.CFF_LIBERATION to setOf("CFF ", "cmap"),
                CorpusKeys.CFF2_LIBERATION to setOf("CFF2", "cmap"),
                CorpusKeys.EMOJI_TWO_COLR_V0 to setOf("COLR", "CPAL"),
                CorpusKeys.SKIA_CBDT to setOf("CBDT", "CBLC"),
            ),
        ),
        CatalogEntry(
            id = "composition.per-span-style",
            axis = CatalogAxis.COMPOSITION,
            technology = CatalogText(
                "Per-span styling inside one paragraph: a run that keeps its own face or its own " +
                    "variation instance while the rest of the paragraph keeps another",
                "Style par plage à l'intérieur d'un paragraphe : un segment qui garde sa propre " +
                    "police ou sa propre instance de variation alors que le reste du paragraphe en " +
                    "garde une autre",
            ),
            font = null,
            status = CatalogStatus.NotYet(
                trackingIssue = "spec:§3 composition",
                currentBehavior = null,
                unpinnedReason = UnpinnedReason.NO_API_SURFACE,
            ),
            tags = setOf("documented-only"),
        ),
    )
}
