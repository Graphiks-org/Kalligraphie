package org.graphiks.kalligraphie.unicode.corpus

/** The iOS simulator test environment: the corpora are embedded in the test binary at build time. */
internal object UnicodeTestEnvironment {
    /** The conformance corpus of this platform. */
    val corpus: UnicodeCorpus = EmbeddedUnicodeCorpus
}
