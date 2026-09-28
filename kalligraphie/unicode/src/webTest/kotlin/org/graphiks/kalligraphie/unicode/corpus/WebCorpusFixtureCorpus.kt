package org.graphiks.kalligraphie.unicode.corpus

import kotlin.io.encoding.Base64

/**
 * Reads the corpora embedded in the web (Kotlin/JS and Kotlin/Wasm) test binaries by the
 * `webCorpus` task.
 *
 * Neither web target has a resource class path, so the committed corpus cannot be read from disk
 * the way the JVM and Android `ClasspathUnicodeCorpus` reads it. It is embedded as base64 Kotlin
 * source instead and decoded once, lazily, exactly as `EmbeddedUnicodeCorpus` does on iOS. This is
 * the web implementation of the shared [UnicodeCorpus] seam (the plan calls the seam
 * `FixtureCorpus`; in this module it is [UnicodeCorpus]).
 */
internal object WebCorpusFixtureCorpus : UnicodeCorpus {
    private val decoded: Map<String, String> by lazy {
        EmbeddedWebCorpusData.paths.associateWith { path ->
            Base64.decode(EmbeddedWebCorpusData.base64(path)).decodeToString()
        }
    }

    override fun text(path: String): String =
        decoded[path] ?: error("The embedded web Unicode corpus has no entry for $path.")
}

/** The web test environment: the corpora are embedded in the test binaries at build time. */
internal object UnicodeTestEnvironment {
    /** The conformance corpus of this platform. */
    val corpus: UnicodeCorpus = WebCorpusFixtureCorpus
}
