// RobustnessCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Hostile-input expectations, documented until the probes pin the observed refusals. */
public object RobustnessCatalog {
    /** Declared robustness expectations. */
    public val entries: List<CatalogEntry> = listOf(
        documented(
            id = "robustness.truncated-sfnt",
            technology = "SFNT input truncated mid-table",
            unpinnedReason = UnpinnedReason.CORPUS_NOT_ACQUIRED,
        ),
        documented(
            id = "robustness.empty-input",
            technology = "empty font input",
            unpinnedReason = UnpinnedReason.CORPUS_NOT_ACQUIRED,
        ),
    )

    private fun documented(id: String, technology: String, unpinnedReason: UnpinnedReason) = CatalogEntry(
        id = id,
        axis = CatalogAxis.ROBUSTNESS,
        technology = technology,
        font = null,
        status = CatalogStatus.NotYet(
            trackingIssue = "spec:§5 robustness",
            currentBehavior = null,
            unpinnedReason = unpinnedReason,
        ),
        tags = setOf("documented-only"),
    )
}
