package org.graphiks.kalligraphie.shaping

import kotlin.io.encoding.Base64

/**
 * Loads the shared fixture corpus inside the iOS simulator test binary.
 *
 * Kotlin/Native has no resource classpath (there is no `getResourceAsStream`) and the
 * `simctl`-spawned test process does not inherit the Gradle `environment(...)` variables, so the
 * corpus cannot be read from `test-fixtures/` at runtime. The `iosFixtureCorpus` Gradle task
 * embeds the real files as base64 Kotlin source instead; this loader decodes each fixture once and
 * caches it. [fixtureBytes] accepts the same `/fonts/...` resource-path shape the JVM and Android
 * suites use.
 */
internal object IosFixtureLoader {
    private val decoded: Map<String, ByteArray> by lazy {
        IosFixtureCorpus.paths.associateWith { resourcePath ->
            Base64.decode(IosFixtureCorpus.base64(resourcePath))
        }
    }

    fun fixtureBytes(relativePath: String): ByteArray =
        decoded[relativePath]
            ?: error("The embedded iOS fixture corpus has no entry for $relativePath.")
}
