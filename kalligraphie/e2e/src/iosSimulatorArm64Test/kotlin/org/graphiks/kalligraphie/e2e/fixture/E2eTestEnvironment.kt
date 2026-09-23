package org.graphiks.kalligraphie.e2e.fixture

import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import org.graphiks.kalligraphie.e2e.catalog.CatalogSceneRenderer
import org.graphiks.kalligraphie.e2e.catalog.PortableSceneRenderers

/**
 * The iOS simulator test environment: the embedded corpus, the portable scenes, and the capabilities
 * iOS declares.
 *
 * iOS declares `END_TO_END_LAYOUT` absent, so the paragraph-facade scenes are not registered — and
 * could not be, since the facade they call is not compiled for this target.
 */
internal object E2eTestEnvironment {
    /** The fixture corpus embedded in this test binary at build time. */
    val corpus: FixtureCorpus = EmbeddedFixtureCorpus

    /** Every scene this platform registers, keyed by catalog entry id. */
    val renderers: Map<String, CatalogSceneRenderer> = PortableSceneRenderers.byId

    /** The portable capabilities this platform declares, read from `:kalligraphie:conformance`. */
    val capabilities: Set<PortableCapability> = currentPortableCapabilityIdentity()
        .declarations
        .filter { declaration -> declaration.available }
        .map { declaration -> declaration.capability }
        .toSet()
}
