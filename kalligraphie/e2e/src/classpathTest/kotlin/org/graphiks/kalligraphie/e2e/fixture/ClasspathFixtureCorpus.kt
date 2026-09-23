package org.graphiks.kalligraphie.e2e.fixture

/**
 * Reads the corpus from the JVM or Android class path.
 *
 * `test-fixtures/` is wired as a resource directory of the test source sets, so `/fonts/…` and the
 * committed harness resources resolve exactly as they always have. Android packages the same
 * directory into the test APK, so the host test and the device test read the same entries through
 * the same call.
 */
internal object ClasspathFixtureCorpus : FixtureCorpus {
    override fun bytes(path: String): ByteArray =
        checkNotNull(object {}.javaClass.getResourceAsStream(path)) { "fixture resource $path is missing" }
            .use { input -> input.readBytes() }
}
