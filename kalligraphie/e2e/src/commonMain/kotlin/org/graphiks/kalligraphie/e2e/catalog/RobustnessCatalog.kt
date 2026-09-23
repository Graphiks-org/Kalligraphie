// RobustnessCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Hostile and malformed input: behaviours that must never soften. */
public object RobustnessCatalog {
    /** Declared robustness expectations. */
    public val entries: List<CatalogEntry> = listOf(
        expectedRejection(
            id = "robustness.truncated-sfnt",
            technology = "Truncated TrueType container",
            font = CorpusKeys.LIBERATION,
            code = "font.out-of-bounds",
            stage = CatalogStage.DECODE,
        ),
        expectedRejection(
            id = "robustness.empty-input",
            technology = "Zero-byte font source",
            font = CorpusKeys.LIBERATION,
            code = "font.invalid-font-data",
            stage = CatalogStage.DECODE,
        ),
    )

    private fun expectedRejection(id: String, technology: String, font: CorpusKey, code: String, stage: CatalogStage) =
        CatalogEntry(
            id = id,
            axis = CatalogAxis.ROBUSTNESS,
            technology = technology,
            font = font,
            status = CatalogStatus.ExpectedRejection(stage = stage, code = code),
            tags = setOf("hostile-input"),
        )
}
