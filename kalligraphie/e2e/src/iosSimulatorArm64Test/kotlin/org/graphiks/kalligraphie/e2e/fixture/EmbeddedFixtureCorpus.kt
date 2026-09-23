package org.graphiks.kalligraphie.e2e.fixture

import kotlin.io.encoding.Base64

/**
 * Reads the corpus embedded in the iOS simulator test binary by the `iosFixtureCorpus` task.
 *
 * Kotlin/Native has no resource classpath and the `simctl`-spawned test process does not inherit the
 * Gradle `environment(...)` variables, so the committed fonts and harness resources cannot be read
 * from disk here. They are embedded as base64 Kotlin source instead and decoded once, lazily, so the
 * suite pays for each fixture at most once.
 */
internal object EmbeddedFixtureCorpus : FixtureCorpus {
    private val decoded: Map<String, ByteArray> by lazy {
        E2eFixtureCorpus.paths.associateWith { path -> Base64.decode(E2eFixtureCorpus.base64(path)) }
    }

    override fun bytes(path: String): ByteArray =
        decoded[path] ?: error("The embedded harness corpus has no entry for $path.")
}
