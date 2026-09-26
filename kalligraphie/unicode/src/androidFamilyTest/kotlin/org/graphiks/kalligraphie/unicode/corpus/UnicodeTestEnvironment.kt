package org.graphiks.kalligraphie.unicode.corpus

/**
 * The Android test environment, shared by the host and device compilations so the two cannot drift:
 * AGP puts the harness resources on the unit-test class path and inside the device-test APK, so both
 * read the committed corpora through the class loader.
 */
internal object UnicodeTestEnvironment {
    /** The conformance corpus of this platform. */
    val corpus: UnicodeCorpus = ClasspathUnicodeCorpus
}
