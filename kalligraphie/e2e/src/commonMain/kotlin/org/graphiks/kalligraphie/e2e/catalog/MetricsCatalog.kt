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
            technology = CatalogText("Variable face carrying VVAR: the scene loads the face and rasterises its outline, and observes no vertical advance delta", "Police variable portant VVAR : la scène charge la police et rastérise son contour, sans observer de delta d'avance verticale"),
            font = CorpusKeys.KALLIGRAPHIE_VAR_VVAR,
            status = CatalogStatus.Supported(sinceCommit = "4b156eac"),
            tags = setOf("metrics:vvar", "auto-sized"),
            // The scene is the outline route of the fixture's capital A: it resolves the code point
            // through `cmap`, reads the `glyf`/`loca` outlines and rasterises them. The fixture's
            // vertical and variation tables stay carried but unread; see CatalogClaims.
            tables = setOf("glyf", "loca", "cmap"),
            family = GoldenSceneFamily.GLYPH_OUTLINE,
            frame = SceneFramePolicy.AutoSized(padding = 1),
        ),
        documented(
            id = "metrics.vvar-real-font",
            technology = CatalogText("vvar vertical metrics from a real font", "Métriques verticales vvar d'une vraie police"),
            unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
        ),
        documented(
            id = "metrics.mvar-real-font",
            technology = CatalogText("mvar metric variations from a real font", "Variations de métriques mvar d'une vraie police"),
            unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
        ),
    )

    private fun documented(id: String, technology: CatalogText, unpinnedReason: UnpinnedReason) = CatalogEntry(
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
