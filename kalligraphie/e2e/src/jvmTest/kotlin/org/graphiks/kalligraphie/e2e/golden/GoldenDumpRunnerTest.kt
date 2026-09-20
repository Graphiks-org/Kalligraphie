package org.graphiks.kalligraphie.e2e.golden

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

class GoldenDumpRunnerTest {
    @Test
    fun writesDumpsOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_E2E_DUMPS") != "true") {
            return
        }
        val output = checkNotNull(System.getenv("KALLIGRAPHIE_E2E_DUMPS_OUTPUT")) {
            "KALLIGRAPHIE_E2E_DUMPS_OUTPUT must point to an absolute directory outside the repository."
        }
        val directory = Path.of(output)
        assertTrue(directory.isAbsolute, "the dump output must be an absolute path")
        assertTrue(!directory.normalize().startsWith(repositoryRoot()), "the dump output must be outside the repository")
        Files.createDirectories(directory)

        for (entry in JvmGoldenSceneCatalog.entries()) {
            when (val outcome = entry.render()) {
                is GoldenRenderOutcome.Rendered -> {
                    val extension = if (outcome.image.format.name == "ALPHA_8") "pgm" else "ppm"
                    Files.write(directory.resolve("${entry.scene.id}.$extension"), GoldenDumpWriter.encode(outcome.image))
                }

                is GoldenRenderOutcome.Refused ->
                    error("${outcome.code.code} (${entry.scene.id}): ${outcome.detail}")
            }
        }
    }
}
