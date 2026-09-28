package org.graphiks.kalligraphie.e2e.fixture

import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import org.graphiks.kalligraphie.e2e.catalog.CatalogSceneRenderer
import org.graphiks.kalligraphie.e2e.catalog.ParagraphSceneRenderers
import org.graphiks.kalligraphie.e2e.catalog.PortableSceneRenderers

/**
 * The web (js/wasmJs) test environment: the embedded corpus, both halves of the harness, and the
 * capabilities the web target declares.
 *
 * Web declares the complete portable capability surface, end-to-end layout included, so the
 * paragraph-facade scenes are registered alongside the portable ones: the capability ratchet
 * requires the registry to match that declaration exactly, and no scene is deferred here.
 *
 * The paragraph route opens the WebAssembly HarfBuzz backend, which the web runtime instantiates
 * asynchronously — the shared suite therefore awaits `initialize()` before it renders, which is what
 * `initializedSceneTest` exists for.
 */
internal object E2eTestEnvironment {
    /** The fixture corpus embedded in this test bundle at build time. */
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
