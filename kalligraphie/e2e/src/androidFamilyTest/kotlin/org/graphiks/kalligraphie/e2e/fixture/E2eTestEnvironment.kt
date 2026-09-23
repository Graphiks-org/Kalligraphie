package org.graphiks.kalligraphie.e2e.fixture

import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import org.graphiks.kalligraphie.e2e.catalog.CatalogSceneRenderer
import org.graphiks.kalligraphie.e2e.catalog.PortableSceneRenderers

/**
 * The Android test environment, shared by the host and the device compilation so the two cannot
 * drift: both run the same runtime family, read the corpus from the same packaged resources, and
 * register the portable scenes alone.
 *
 * Android declares `END_TO_END_LAYOUT` absent, so the paragraph-facade scenes are not registered:
 * their renderers could not even compile here, and the capability ratchet requires the registry to
 * match that declaration exactly.
 */
internal object E2eTestEnvironment {
    /** The fixture corpus, packaged into the unit-test class path and the device-test APK. */
    val corpus: FixtureCorpus = ClasspathFixtureCorpus

    /** Every scene this platform registers, keyed by catalog entry id. */
    val renderers: Map<String, CatalogSceneRenderer> = PortableSceneRenderers.byId

    /** The portable capabilities this platform declares, read from `:kalligraphie:conformance`. */
    val capabilities: Set<PortableCapability> = currentPortableCapabilityIdentity()
        .declarations
        .filter { declaration -> declaration.available }
        .map { declaration -> declaration.capability }
        .toSet()
}
