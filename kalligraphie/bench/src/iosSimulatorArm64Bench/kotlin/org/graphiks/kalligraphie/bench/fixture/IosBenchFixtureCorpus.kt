package org.graphiks.kalligraphie.bench.fixture

import kotlin.io.encoding.Base64

/**
 * Reads the corpus embedded in the simulator binary by the `iosBenchmarkCorpus` task.
 *
 * Kotlin/Native has no resource classpath, and a `simctl`-spawned process must not depend on host
 * paths, so the four fixtures the portable scenarios read travel inside the binary as base64
 * source and decode once, lazily. [sha256Hex] hashes what was actually embedded, so a report can
 * be checked against the committed fixtures.
 */
internal object IosBenchFixtureCorpus : FixtureCorpus {
    private val decoded: Map<String, ByteArray> by lazy {
        IosFixtureCorpus.paths.associateWith { path -> Base64.decode(IosFixtureCorpus.base64(path)) }
    }

    override fun bytes(path: String): ByteArray =
        decoded[path] ?: error("The embedded benchmark corpus has no entry for $path.")

    override fun sha256Hex(path: String): String = sha256Hex(bytes(path))
}
