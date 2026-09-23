// ExpectationCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** The aggregate of every declared expectation, grouped by axis. */
public object ExpectationCatalog {
    /** Every entry, in axis declaration order. */
    public val entries: List<CatalogEntry> =
        ContainerCatalog.entries + OutlineCatalog.entries + MetricsCatalog.entries +
            VariationCatalog.entries + ColorCatalog.entries + BitmapCatalog.entries +
            ScriptCatalog.entries + CompositionCatalog.entries + RobustnessCatalog.entries

    /** Entries grouped by axis, each list in declaration order. */
    public val byAxis: Map<CatalogAxis, List<CatalogEntry>> = entries.groupBy { entry -> entry.axis }
}
