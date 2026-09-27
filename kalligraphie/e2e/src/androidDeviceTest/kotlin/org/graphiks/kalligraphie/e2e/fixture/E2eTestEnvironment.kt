package org.graphiks.kalligraphie.e2e.fixture

import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import org.graphiks.kalligraphie.e2e.catalog.CatalogSceneRenderer
import org.graphiks.kalligraphie.e2e.catalog.ParagraphSceneRenderers
import org.graphiks.kalligraphie.e2e.catalog.PortableSceneRenderers

/**
 * The Android **device** test environment: the compilation that runs on ART.
 *
 * It registers both halves of the harness — the portable scenes and the paragraph-facade ones —
 * because Android declares the complete portable capability surface, end-to-end layout included, and
 * the capability ratchet requires the registry to match that declaration exactly. The host
 * compilation, which runs on a JVM, carries its own reduced declaration and the portable half alone.
 */
internal object E2eTestEnvironment {
    /** The fixture corpus, packaged into the unit-test class path and the device-test APK. */
    val corpus: FixtureCorpus = ClasspathFixtureCorpus

    /** Every scene this platform registers, keyed by catalog entry id. */
    val renderers: Map<String, CatalogSceneRenderer> = PortableSceneRenderers.byId + ParagraphSceneRenderers.byId

    /** The portable capabilities this platform declares, read from `:kalligraphie:conformance`. */
    val capabilities: Set<PortableCapability> = currentPortableCapabilityIdentity()
        .declarations
        .filter { declaration -> declaration.available }
        .map { declaration -> declaration.capability }
        .toSet()
}
