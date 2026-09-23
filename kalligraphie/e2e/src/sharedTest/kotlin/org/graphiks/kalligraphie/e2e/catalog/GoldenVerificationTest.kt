package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.Test
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenComparison
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenManifest
import org.graphiks.kalligraphie.e2e.GoldenManifestParseResult
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment
import org.graphiks.kalligraphie.e2e.GoldenVerifier

class GoldenVerificationTest {
    @Test
    fun everyCataloguedSceneMatchesTheCommittedManifest() {
        val text = E2eTestEnvironment.corpus.text("/golden/manifest.tsv")
        val manifest = when (val parsed = GoldenManifest.parse(text)) {
            is GoldenManifestParseResult.Parsed -> parsed.manifest
            is GoldenManifestParseResult.Rejected -> error("${parsed.code.code}: ${parsed.detail}")
        }

        val entries = GoldenSceneCatalog.entries()
        assertTrue(entries.isNotEmpty(), "the golden scene catalog must not be empty")
        val rendered = LinkedHashMap<String, GoldenImage>()
        for (entry in entries) {
            when (val outcome = entry.render()) {
                is GoldenRenderOutcome.Rendered -> rendered[entry.scene.id] = outcome.image
                is GoldenRenderOutcome.Refused -> error("${outcome.code.code} (${entry.scene.id}): ${outcome.detail}")
            }
        }

        val results = GoldenVerifier.verify(entries.map { entry -> entry.scene }, rendered, manifest)
        val failures = results.filterNot { result -> result is GoldenComparison.Matched }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("golden verification failed:")
                for (failure in failures) {
                    when (failure) {
                        is GoldenComparison.Mismatch ->
                            appendLine(
                                "  ${GoldenDiagnosticCode.MISMATCH.code}: ${failure.sceneId} " +
                                    "expected=${failure.expectedSha256} actual=${failure.actualSha256} " +
                                    "expectedSize=${failure.expectedWidth}x${failure.expectedHeight} " +
                                    "actualSize=${failure.actualWidth}x${failure.actualHeight} " +
                                    "(run ./gradlew :kalligraphie:e2e:e2eGoldenDumps to inspect)",
                            )

                        is GoldenComparison.MissingInManifest ->
                            appendLine("  ${GoldenDiagnosticCode.MANIFEST_MISSING_ENTRY.code}: ${failure.sceneId} (run updateE2eGolden)")

                        is GoldenComparison.StaleManifestEntry ->
                            appendLine("  ${GoldenDiagnosticCode.MANIFEST_STALE_ENTRY.code}: ${failure.sceneId} (run updateE2eGolden)")

                        is GoldenComparison.Matched -> Unit
                    }
                }
            },
        )
    }
}
