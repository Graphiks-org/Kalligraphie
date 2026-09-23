// MetricsCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Vertical-metrics expectations no real font pins yet. */
public object MetricsCatalog {
    /** Declared metrics expectations. */
    public val entries: List<CatalogEntry> = listOf(
        documented(
            id = "metrics.vvar-real-font",
            technology = "vvar vertical metrics from a real font",
            unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
        ),
        documented(
            id = "metrics.mvar-real-font",
            technology = "mvar metric variations from a real font",
            unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
        ),
    )

    private fun documented(id: String, technology: String, unpinnedReason: UnpinnedReason) = CatalogEntry(
        id = id,
        axis = CatalogAxis.METRICS,
        technology = technology,
        font = null,
        status = CatalogStatus.NotYet(
            trackingIssue = "spec:§5 metrics",
            currentBehavior = null,
            unpinnedReason = unpinnedReason,
        ),
        tags = setOf("documented-only"),
    )
}
