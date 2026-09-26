package org.graphiks.kalligraphie.unicode.corpus

/**
 * Reads one committed Unicode conformance corpus as text.
 *
 * The conformance tests read their corpora through this seam instead of a JVM class path, because
 * Kotlin/Native has no class-path resources at all. Every test environment supplies one
 * implementation: a class-path reader where the platform has one, an embedded corpus where it does
 * not. A corpus is opened once per test environment and shared, so an implementation is expected to
 * cache what it decodes.
 */
internal interface UnicodeCorpus {
    /** The text of the corpus at [path], a `/`-rooted resource path such as `/unicode/16.0.0/…`. */
    fun text(path: String): String
}
