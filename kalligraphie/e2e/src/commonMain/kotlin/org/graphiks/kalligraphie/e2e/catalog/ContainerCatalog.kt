// ContainerCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Container expectations, including the wrappers deliberately not supported yet. */
public object ContainerCatalog {
    /** Declared container expectations. */
    public val entries: List<CatalogEntry> = listOf(
        documented(
            id = "container.woff2",
            technology = CatalogText("WOFF 2.0 container wrapping", "Encapsulation dans un conteneur WOFF 2.0"),
            unpinnedReason = UnpinnedReason.CORPUS_NOT_ACQUIRED,
        ),
        documented(
            id = "container.woff",
            technology = CatalogText("WOFF 1.0 container wrapping", "Encapsulation dans un conteneur WOFF 1.0"),
            unpinnedReason = UnpinnedReason.CORPUS_NOT_ACQUIRED,
        ),
    )

    private fun documented(id: String, technology: CatalogText, unpinnedReason: UnpinnedReason) = CatalogEntry(
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
