package org.graphiks.kalligraphie.e2e.golden

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenFingerprint
import org.graphiks.kalligraphie.e2e.GoldenManifest
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

class GoldenUpdateRunnerTest {
    @Test
    fun writesTheManifestOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_E2E_UPDATE") != "true") {
            return
        }
        val fingerprints = ArrayList<GoldenFingerprint>()
        for (entry in JvmGoldenSceneCatalog.entries()) {
            when (val outcome = entry.render()) {
                is GoldenRenderOutcome.Rendered -> fingerprints.add(GoldenFingerprint.of(entry.scene, outcome.image))
                is GoldenRenderOutcome.Refused ->
                    error("${outcome.code.code} (${entry.scene.id}): ${outcome.detail}")
            }
        }
        val target = repositoryRoot().resolve("kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv")
        Files.createDirectories(target.parent)
        Files.writeString(target, GoldenManifest.serialize(GoldenManifest.of(fingerprints)))
    }
}

/** Locates the repository root by walking up from the test working directory. */
internal fun repositoryRoot(): Path {
    var candidate: Path? = Path.of("").toAbsolutePath().normalize()
    while (candidate != null) {
        if (Files.exists(candidate.resolve(".git"))) return candidate
        candidate = candidate.parent
    }
    error("Could not locate the repository root from the test working directory.")
}
