package org.graphiks.kalligraphie.bench.fixture

import kotlin.io.encoding.Base64

/**
 * Reads the corpus embedded in the web benchmark by the `webBenchmarkCorpus` task.
 *
 * The web runtimes have no resource classpath, so the fixtures the portable scenarios read travel
 * inside the binary as base64 source and decode once, lazily. [sha256Hex] hashes what was actually
 * embedded, so a report can be checked against the committed fixtures.
 */
internal object WebBenchFixtureCorpus : FixtureCorpus {
    private val decoded: Map<String, ByteArray> by lazy {
        WebFixtureCorpusData.paths.associateWith { path -> Base64.decode(WebFixtureCorpusData.base64(path)) }
    }

    override fun bytes(path: String): ByteArray =
        decoded[path] ?: error("The embedded web benchmark corpus has no entry for $path.")

    override fun sha256Hex(path: String): String = sha256Hex(bytes(path))
}
