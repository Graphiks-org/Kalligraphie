package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.catalog.CatalogSceneMaterializer
import org.graphiks.kalligraphie.e2e.catalog.ExpectationCatalog
import org.graphiks.kalligraphie.e2e.catalog.GoldenSceneEntry
import org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment

/**
 * The platform's scene catalog, derived from [ExpectationCatalog]: every supported entry the
 * platform registers is materialized against its renderer, so a scene can no longer exist without a
 * declared expectation and a frame can no longer drift from its entry.
 *
 * The registry comes from [E2eTestEnvironment], which each platform declares for itself: on the
 * reference platform that is every catalogued scene, on a platform whose capabilities cannot serve
 * a route it is the subset that platform is expected to verify. The ratchet proves the subset is
 * exactly what the platform's own capability identity implies.
 */
internal object GoldenSceneCatalog {
    /** Every scene this platform verifies, at its declared frame. */
    fun entries(): List<GoldenSceneEntry> = CatalogSceneMaterializer.materializeAll(
        entries = ExpectationCatalog.entries,
        renderers = E2eTestEnvironment.renderers,
        corpus = E2eTestEnvironment.corpus,
    )
}
