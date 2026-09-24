package org.graphiks.kalligraphie.unicode.corpus

/**
 * Reads a corpus from the JVM or Android class path.
 *
 * `src/harnessResources` is wired as a resource directory of the test source sets, so
 * `/unicode/16.0.0/…` resolves exactly as it always has. Android packages the same directory into
 * the test APK, so the host test and the device test read the same entries through the same call.
 */
internal object ClasspathUnicodeCorpus : UnicodeCorpus {
    override fun text(path: String): String =
        checkNotNull(object {}.javaClass.getResourceAsStream(path)) { "corpus resource $path is missing" }
            .use { input -> input.readBytes().decodeToString() }
}
