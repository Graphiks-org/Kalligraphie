package org.graphiks.kalligraphie.e2e.fixture

/**
 * Reads the harness fixture corpus as the platform running the tests can reach it.
 *
 * The harness reads its fonts and its committed resources — the golden manifest, the auto-sizing
 * exemptions, the claims export — through this seam instead of a JVM class path, because Kotlin
 * Native has no classpath resources at all. Every test environment supplies one implementation:
 * a class-path reader where the platform has one, an embedded corpus where it does not.
 *
 * A corpus is opened once per test environment and shared; implementations must therefore be safe
 * to use from several test threads, and are expected to cache the bytes they decode.
 */
internal interface FixtureCorpus {
    /** Bytes of the fixture at [path], a `/`-rooted resource path such as `/fonts/liberation/…`. */
    fun bytes(path: String): ByteArray

    /** The UTF-8 text of the fixture at [path]. */
    fun text(path: String): String = bytes(path).decodeToString()
}
