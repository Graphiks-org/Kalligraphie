// VariationCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Variable-font expectations, including the technologies deliberately not supported yet. */
public object VariationCatalog {
    /** Declared variation expectations. */
    public val entries: List<CatalogEntry> = listOf(
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
            technology = "STAT style attributes",
            unpinnedReason = UnpinnedReason.CORPUS_NOT_ACQUIRED,
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
