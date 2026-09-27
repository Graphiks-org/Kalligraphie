package org.graphiks.kalligraphie.e2e.fixture

import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import org.graphiks.kalligraphie.e2e.catalog.CatalogSceneRenderer
import org.graphiks.kalligraphie.e2e.catalog.ParagraphSceneRenderers
import org.graphiks.kalligraphie.e2e.catalog.PortableSceneRenderers

/**
 * The iOS simulator test environment: the embedded corpus, both halves of the harness, and the
 * capabilities iOS declares.
 *
 * iOS declares the complete portable capability surface, end-to-end layout included, so the
 * paragraph-facade scenes are registered alongside the portable ones: the capability ratchet
 * requires the registry to match that declaration exactly, and no scene is deferred here.
 */
internal object E2eTestEnvironment {
    /** The fixture corpus embedded in this test binary at build time. */
    val corpus: FixtureCorpus = EmbeddedFixtureCorpus

    /** Every scene this platform registers, keyed by catalog entry id. */
    val renderers: Map<String, CatalogSceneRenderer> = PortableSceneRenderers.byId + ParagraphSceneRenderers.byId

    /** The portable capabilities this platform declares, read from `:kalligraphie:conformance`. */
    val capabilities: Set<PortableCapability> = currentPortableCapabilityIdentity()
        .declarations
        .filter { declaration -> declaration.available }
        .map { declaration -> declaration.capability }
        .toSet()
}
