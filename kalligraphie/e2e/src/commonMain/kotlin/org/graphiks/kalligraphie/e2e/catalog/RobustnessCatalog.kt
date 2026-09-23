// RobustnessCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Hostile and malformed input: behaviours that must never soften. */
public object RobustnessCatalog {
    /** Declared robustness expectations. */
    public val entries: List<CatalogEntry> = listOf(
        expectedRejection(
            id = "robustness.truncated-sfnt",
            technology = CatalogText("Truncated TrueType container", "Conteneur TrueType tronqué"),
            font = CorpusKeys.LIBERATION,
            code = "font.out-of-bounds",
            stage = CatalogStage.DECODE,
        ),
        expectedRejection(
            id = "robustness.empty-input",
            technology = CatalogText("Zero-byte font source", "Source de police de zéro octet"),
            font = CorpusKeys.LIBERATION,
            code = "font.invalid-font-data",
            stage = CatalogStage.DECODE,
        ),
    )

    private fun expectedRejection(id: String, technology: CatalogText, font: CorpusKey, code: String, stage: CatalogStage) =
        CatalogEntry(
            id = id,
            axis = CatalogAxis.ROBUSTNESS,
            technology = technology,
            font = font,
            status = CatalogStatus.ExpectedRejection(stage = stage, code = code),
            tags = setOf("hostile-input"),
        )
}
