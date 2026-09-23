package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.catalog.CatalogSceneMaterializer
import org.graphiks.kalligraphie.e2e.catalog.ExpectationCatalog
import org.graphiks.kalligraphie.e2e.catalog.JvmGoldenEntry
import org.graphiks.kalligraphie.e2e.catalog.SceneRenderers

/**
 * The JVM scene catalog, now derived from [ExpectationCatalog]: every supported entry is
 * materialized against its renderer, so a scene can no longer exist without a declared expectation
 * and a frame can no longer drift from its entry.
 */
internal object JvmGoldenSceneCatalog {
    fun entries(): List<JvmGoldenEntry> =
        CatalogSceneMaterializer.materializeAll(ExpectationCatalog.entries, SceneRenderers.byId)
}
