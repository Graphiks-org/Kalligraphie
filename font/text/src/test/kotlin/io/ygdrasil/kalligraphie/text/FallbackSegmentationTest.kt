package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ygdrasil.kalligraphie.text.shaping.defaultFallbackSegmentationReport
import io.ygdrasil.kalligraphie.text.shaping.defaultFallbackSegmentationReportJson

class FallbackSegmentationTest {
    @Test
    fun fallbackSegmentationReportGoldenPinsClusterSafeFallbackEvidence() {
        val json = defaultFallbackSegmentationReportJson()

        assertEquals(
            readProjectFile("font/fixtures/expected/fallback/fallback-segmentation-report.json"),
            json,
        )
        assertTrue(json.contains("\"dumpId\": \"fallback-segmentation-report\""))
        assertTrue(json.contains("\"ownerTickets\": [\"KFONT-M7-004\"]"))
        assertTrue(json.contains("\"fixtureName\": \"fallback-cluster-emoji-zwj.txt\""))
        assertTrue(json.contains("\"fixtureName\": \"fallback-cluster-negative-split.txt\""))
        assertTrue(json.contains("\"gate\": \"scaledemoji\""))
        assertTrue(json.contains("\"code\": \"text.fallback.cluster-split-forbidden\""))
    }

    @Test
    fun fallbackSegmentationFixturesAreCheckedInAndNonEmpty() {
        val fixtureNames =
            listOf(
                "fallback-cluster-arabic-mark.txt",
                "fallback-cluster-cjk-vs.txt",
                "fallback-cluster-devanagari.txt",
                "fallback-cluster-emoji-zwj.txt",
                "fallback-cluster-latin-mark.txt",
                "fallback-cluster-negative-split.txt",
                "fallback-cluster-skin-tone.txt",
                "fallback-cluster-thai.txt",
                "fallback-cluster-vs15-vs16.txt",
            )

        fixtureNames.forEach { fixtureName ->
            val content = readProjectFile("font/fixtures/expected/fallback/$fixtureName")
            assertTrue(content.isNotBlank(), "$fixtureName should be checked in and non-empty.")
        }
    }

    @Test
    fun fallbackSegmentationFixtureAssetsAreCheckedInAndNonEmpty() {
        val fixtureNames =
            listOf(
                "fallback-cluster-arabic-mark.json",
                "fallback-cluster-cjk-vs.json",
                "fallback-cluster-devanagari.json",
                "fallback-cluster-emoji-zwj.json",
                "fallback-cluster-latin-mark.json",
                "fallback-cluster-negative-split.json",
                "fallback-cluster-skin-tone.json",
                "fallback-cluster-thai.json",
                "fallback-cluster-vs15-vs16.json",
            )

        fixtureNames.forEach { fixtureName ->
            val content = readProjectFile("font/fixtures/expected/fallback/$fixtureName")
            assertTrue(content.isNotBlank(), "$fixtureName should be checked in and non-empty.")
        }
    }

    @Test
    fun negativeEmojiFallbackCaseRefusesWholeClusterInsteadOfSplittingRuns() {
        val report = defaultFallbackSegmentationReport()
        val negativeCase = report.cases.single { it.fixtureName == "fallback-cluster-negative-split.txt" }
        val diagnosticCodes = negativeCase.diagnostics.map { diagnostic -> diagnostic.code }

        assertTrue(negativeCase.invariant.passed)
        assertEquals(emptyList(), negativeCase.invariant.fallbackRunRanges)
        assertTrue(diagnosticCodes.contains("text.fallback.cluster-split-forbidden"))
        assertTrue(diagnosticCodes.contains("text.shaping.emoji-sequence-unsupported"))
        assertFalse(diagnosticCodes.contains("text.shaping.cluster-invariant-failed"))
    }

    @Test
    fun fallbackSegmentationReportLinksDedicatedPerFixtureFallbackAssets() {
        val report = defaultFallbackSegmentationReport()
        val negativeCase = report.cases.single { it.fixtureName == "fallback-cluster-negative-split.txt" }

        assertEquals("fallback-fixture", negativeCase.decisionTraceRef.dumpId)
        assertEquals("fallback-cluster-negative-split", negativeCase.decisionTraceRef.fixtureId)
        assertEquals("decisions", negativeCase.decisionTraceRef.section)
        assertEquals("fallback-fixture", negativeCase.resolvedRunsRef.dumpId)
        assertEquals("fallback-cluster-negative-split", negativeCase.resolvedRunsRef.fixtureId)
        assertEquals("runs", negativeCase.resolvedRunsRef.section)
        assertEquals("fallback-fixture", negativeCase.fixtureAssetRef.dumpId)
        assertEquals("fallback-cluster-negative-split", negativeCase.fixtureAssetRef.fixtureId)
        assertEquals(null, negativeCase.fixtureAssetRef.section)
        assertFalse(negativeCase.hostDependent)
    }

    @Test
    fun fallbackSegmentationReportCarriesNonNormativeHostDependentMarker() {
        val report = defaultFallbackSegmentationReport()
        val marker = report.hostDependentMarkers.single()

        assertEquals("host-dependent-system-fallback", marker.name)
        assertEquals("non-normative", marker.normativeStatus)
        assertTrue(marker.hostDependent)
        assertEquals("fallback-decision-trace", marker.hostDependentSourceRef.dumpId)
        assertEquals("host-dependent-system-fallback", marker.hostDependentSourceRef.fixtureId)
    }

    private fun readProjectFile(relativePath: String): String =
        Files.readString(projectRoot().resolve(relativePath))

    private fun projectRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
