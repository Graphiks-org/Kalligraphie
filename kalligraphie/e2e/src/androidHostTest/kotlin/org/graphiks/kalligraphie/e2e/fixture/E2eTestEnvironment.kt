package org.graphiks.kalligraphie.e2e.fixture

import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.e2e.catalog.CatalogSceneRenderer
import org.graphiks.kalligraphie.e2e.catalog.PortableSceneRenderers

/**
 * The Android **host** test environment: the compilation that runs on a JVM, not on ART.
 *
 * It reads the same corpus and registers the portable half of the harness — the scenes the device
 * compilation registers as well — but it cannot claim the whole platform's declaration, and the
 * difference is not a convenience: the Android native artifact ships shared libraries that a JVM
 * unit test cannot load, so the shaping route and everything built on it — the paragraph facade that
 * composes text — genuinely cannot run here, and those scenes stay the device compilation's. The
 * device compilation is where the Android declaration is exercised.
 *
 * What it can serve is what needs no native artifact: the portable glyph representation route, and
 * the Unicode analysis, which is this repository's own Kotlin over generated tables and needs no
 * platform engine at all. That is exactly the set declared here, so the capability ratchet still
 * means what it means everywhere else — the registry matches the declaration, in both directions —
 * and the workflow that runs this compilation verifies the portable scenes, as it says it does.
 */
internal object E2eTestEnvironment {
    /** The fixture corpus, packaged into the unit-test class path. */
    val corpus: FixtureCorpus = ClasspathFixtureCorpus

    /** Every scene this compilation registers, keyed by catalog entry id. */
    val renderers: Map<String, CatalogSceneRenderer> = PortableSceneRenderers.byId

    /** The capabilities this runtime serves: the portable ones that need no Android native library. */
    val capabilities: Set<PortableCapability> = setOf(
        PortableCapability.UNICODE_ANALYSIS,
        PortableCapability.GLYPH_REPRESENTATION_VARIANTS,
    )
}
