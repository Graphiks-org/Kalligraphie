// GoldenWriterMain.kt
package org.graphiks.kalligraphie.e2e.golden

import java.nio.file.Files
import java.nio.file.Path
import org.graphiks.kalligraphie.e2e.GoldenFingerprint
import org.graphiks.kalligraphie.e2e.GoldenManifest
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.catalog.CatalogClaims
import org.graphiks.kalligraphie.e2e.catalog.CatalogMatrixLanguage
import org.graphiks.kalligraphie.e2e.catalog.CatalogMatrixRenderer
import org.graphiks.kalligraphie.e2e.catalog.ExpectationCatalog

/**
 * Regenerates the committed golden artefacts, one command per artefact.
 *
 * This is tooling, not a test: it writes into the source tree and says nothing about whether the
 * project is correct. It used to be a suite of `@Test` methods gated by environment variables, which
 * is why four classes had to be excluded from `check` and why the build carried two extra `Test`
 * tasks to re-enable them — a suite that switches itself off is not a suite. The Gradle tasks call
 * this entry point instead, and every test that remains is an assertion.
 *
 * The freshness of what this writes is asserted by the test suite, so a stale artefact is still a
 * failure rather than a surprise.
 */
public fun main(args: Array<String>) {
    require(args.isNotEmpty()) {
        "expected at least one command: manifest, matrix, claims, or dumps=<absolute directory>"
    }
    val repository = repositoryRoot()
    for (command in args) {
        when {
            command == "manifest" -> writeManifest(repository)
            command == "matrix" -> writeMatrix(repository)
            command == "claims" -> writeClaims(repository)
            command.startsWith("dumps=") -> writeDumps(Path.of(command.removePrefix("dumps=")), repository)
            else -> error("unknown command: $command")
        }
        println("wrote $command")
    }
}

/** Writes the golden fingerprint manifest of every scene this platform verifies. */
private fun writeManifest(repository: Path) {
    val fingerprints = ArrayList<GoldenFingerprint>()
    for (entry in GoldenSceneCatalog.entries()) {
        when (val outcome = entry.render()) {
            is GoldenRenderOutcome.Rendered -> fingerprints.add(GoldenFingerprint.of(entry.scene, outcome.image))
            is GoldenRenderOutcome.Refused -> error("${outcome.code.code} (${entry.scene.id}): ${outcome.detail}")
        }
    }
    writeText(repository.resolve(MANIFEST_PATH), GoldenManifest.serialize(GoldenManifest.of(fingerprints)))
}

/** Writes the bilingual catalog matrix, one page per language. */
private fun writeMatrix(repository: Path) {
    for (language in CatalogMatrixLanguage.entries) {
        writeText(
            repository.resolve("docs/docs/generated/${CatalogMatrixRenderer.fileName(language)}"),
            CatalogMatrixRenderer.render(ExpectationCatalog.entries, language),
        )
    }
}

/** Writes the table claims export the exhaustiveness lint consumes. */
private fun writeClaims(repository: Path) {
    writeText(repository.resolve(CLAIMS_PATH), CatalogClaims.render(ExpectationCatalog.entries))
}

/**
 * Writes the inspection dumps of every scene, as binary PGM or PPM.
 *
 * The output directory is a caller argument and must sit outside the repository: dumps are for
 * looking at, not for committing, and a path inside the checkout is a mistake worth refusing.
 */
private fun writeDumps(output: Path, repository: Path) {
    require(output.isAbsolute) { "the dump output must be an absolute path: $output" }
    require(!output.normalize().startsWith(repository)) { "the dump output must be outside the repository: $output" }
    Files.createDirectories(output)
    for (entry in GoldenSceneCatalog.entries()) {
        require(entry.scene.id.none { character -> character == '/' || character == '\\' }) {
            "A scene id must not contain a path separator: ${entry.scene.id}"
        }
        when (val outcome = entry.render()) {
            is GoldenRenderOutcome.Rendered -> Files.write(
                output.resolve("${entry.scene.id}.${GoldenDumpWriter.extensionFor(outcome.image.format)}"),
                GoldenDumpWriter.encode(outcome.image),
            )

            is GoldenRenderOutcome.Refused ->
                error("${outcome.code.code} (${entry.scene.id}): ${outcome.detail}")
        }
    }
}

private fun writeText(target: Path, text: String) {
    Files.createDirectories(target.parent)
    Files.writeString(target, text)
}

private const val MANIFEST_PATH = "kalligraphie/e2e/src/harnessResources/golden/manifest.tsv"
private const val CLAIMS_PATH = "kalligraphie/e2e/src/harnessResources/catalog/claimed-tables.json"
