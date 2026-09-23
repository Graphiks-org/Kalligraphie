// ContainerCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Container expectations, including the wrappers deliberately not supported yet. */
public object ContainerCatalog {
    /** Declared container expectations. */
    public val entries: List<CatalogEntry> = listOf(
        documented(
            id = "container.woff2",
            technology = "WOFF 2.0 container wrapping",
            unpinnedReason = UnpinnedReason.CORPUS_NOT_ACQUIRED,
        ),
        documented(
            id = "container.woff",
            technology = "WOFF 1.0 container wrapping",
            unpinnedReason = UnpinnedReason.CORPUS_NOT_ACQUIRED,
        ),
    )

    private fun documented(id: String, technology: String, unpinnedReason: UnpinnedReason) = CatalogEntry(
        id = id,
        axis = CatalogAxis.CONTAINER,
        technology = technology,
        font = null,
        status = CatalogStatus.NotYet(
            trackingIssue = "spec:§5 containers",
            currentBehavior = null,
            unpinnedReason = unpinnedReason,
        ),
        tags = setOf("documented-only"),
    )
}
