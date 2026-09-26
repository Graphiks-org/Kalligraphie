package org.graphiks.kalligraphie.bench.fixture

/**
 * Reads the measurement corpus.
 *
 * The corpus is read one way per platform, and **no implementation falls back to a path relative to
 * the working directory**: a silent fallback can measure a different font, or fail only on a
 * device. [bytes] fails loudly when the resource is absent, and [sha256Hex] hashes what was
 * actually read, so a report can be checked against the committed fixtures.
 */
public interface FixtureCorpus {
    /** Reads the resource at [path], e.g. `/fonts/liberation/LiberationSans-Regular.ttf`. */
    public fun bytes(path: String): ByteArray

    /** Lowercase hexadecimal SHA-256 of the bytes actually read for [path]. */
    public fun sha256Hex(path: String): String
}
