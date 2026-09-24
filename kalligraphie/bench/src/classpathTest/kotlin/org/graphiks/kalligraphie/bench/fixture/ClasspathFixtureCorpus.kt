package org.graphiks.kalligraphie.bench.fixture

/**
 * The JVM and Android reader: resources come from the class path, fed by
 * `resources.srcDir(rootProject.file("test-fixtures"))`.
 */
public class ClasspathFixtureCorpus : FixtureCorpus {
    private val hashes = mutableMapOf<String, String>()

    override fun bytes(path: String): ByteArray =
        ClasspathFixtureCorpus::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("The measurement corpus has no resource at $path.")

    override fun sha256Hex(path: String): String = hashes.getOrPut(path) { sha256Hex(bytes(path)) }
}
