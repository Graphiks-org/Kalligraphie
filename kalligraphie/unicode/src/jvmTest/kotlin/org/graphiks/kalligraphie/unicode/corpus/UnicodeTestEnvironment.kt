package org.graphiks.kalligraphie.unicode.corpus

/** The JVM test environment: the corpora arrive on the class path of the test runtime. */
internal object UnicodeTestEnvironment {
    /** The conformance corpus of this platform. */
    val corpus: UnicodeCorpus = ClasspathUnicodeCorpus
}
