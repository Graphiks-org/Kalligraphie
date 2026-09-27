package org.graphiks.kalligraphie.e2e.fixture

import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.conformance.currentPortableCapabilityIdentity
import org.graphiks.kalligraphie.e2e.catalog.CatalogSceneRenderer
import org.graphiks.kalligraphie.e2e.catalog.ParagraphSceneRenderers
import org.graphiks.kalligraphie.e2e.catalog.PortableSceneRenderers

/**
 * What the harness needs from the platform running the tests.
 *
 * Each test compilation declares exactly one environment, so a platform cannot inherit another's
 * fixtures nor another's scene registry: the shared harness reads this declaration and would not
 * compile without it.
 *
 * Every platform registers both halves — the portable scenes and the paragraph-facade ones — since
 * each declares the complete portable capability surface. One compilation does not: the Android
 * host, which runs on a JVM and cannot load the shared library the shaping route needs, registers
 * the portable half alone. The ratchet refuses a registry that does not match its own declaration
 * in either case.
 */
internal object E2eTestEnvironment {
    /** The fixture corpus of this platform. */
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
