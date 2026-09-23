// MetricsCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** Vertical-metrics expectations: the auto-sized VVAR scene, then the gaps no real font pins yet. */
public object MetricsCatalog {
    /** Declared metrics expectations. */
    public val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "metrics.vvar-advance-height",
            axis = CatalogAxis.METRICS,
            technology = "VVAR vertical advance deltas",
            font = CorpusKeys.KALLIGRAPHIE_VAR_VVAR,
            status = CatalogStatus.Supported(sinceCommit = "4b156eac"),
            tags = setOf("metrics:vvar", "auto-sized"),
            tables = setOf("VVAR", "fvar", "vhea", "vmtx"),
            family = GoldenSceneFamily.GLYPH_OUTLINE,
            frame = SceneFramePolicy.AutoSized(padding = 1),
        ),
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
