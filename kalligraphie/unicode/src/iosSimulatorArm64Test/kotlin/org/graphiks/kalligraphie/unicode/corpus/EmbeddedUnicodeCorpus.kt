package org.graphiks.kalligraphie.unicode.corpus

import kotlin.io.encoding.Base64

/**
 * Reads the corpora embedded in the iOS simulator test binary by the `iosCorpus` task.
 *
 * Kotlin/Native has no resource classpath, so the committed corpus cannot be read from disk here.
 * It is embedded as base64 Kotlin source instead and decoded once, lazily, so the suite pays for a
 * corpus at most once. Only the corpora the portable tests run on this target are embedded.
 */
internal object EmbeddedUnicodeCorpus : UnicodeCorpus {
    private val decoded: Map<String, String> by lazy {
        EmbeddedUnicodeCorpusData.paths.associateWith { path ->
            Base64.decode(EmbeddedUnicodeCorpusData.base64(path)).decodeToString()
        }
    }

    override fun text(path: String): String =
        decoded[path] ?: error("The embedded Unicode corpus has no entry for $path.")
}
