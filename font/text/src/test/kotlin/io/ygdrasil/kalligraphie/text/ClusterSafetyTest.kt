package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ygdrasil.kalligraphie.text.shaping.ClusterSafetyFixture
import io.ygdrasil.kalligraphie.text.shaping.ClusterSafetySuite
import io.ygdrasil.kalligraphie.text.shaping.defaultClusterSafetyReportJson

class ClusterSafetyTest {
    private val clusterFixtureNames = listOf(
        "cluster-arabic-mark.txt",
        "cluster-cjk-ivs-mixed-kana.txt",
        "cluster-cjk-ivs-supplementary.txt",
        "cluster-cjk-variation-selector.txt",
        "cluster-devanagari-conjunct.txt",
        "cluster-emoji-family-zwj.txt",
        "cluster-emoji-skin-tone.txt",
        "cluster-mixed-bidi.txt",
        "cluster-negative-split.txt",
        "cluster-thai-tone.txt",
        "cluster-vs15-vs16.txt",
    )

    @Test
    fun clusterSafetyReportGoldenPinsFixtureMatrixAndLegacyGate() {
        val json = defaultClusterSafetyReportJson()

        assertEquals(
            readProjectFile("font/fixtures/expected/unicode/cluster-safety-report.json"),
            json,
        )
        assertTrue(json.contains("\"dumpId\": \"cluster-safety-report\""))
        assertTrue(json.contains("\"ownerTickets\": [\"KFONT-M5-005\"]"))
        assertTrue(json.contains("\"fixtureName\": \"cluster-emoji-family-zwj.txt\""))
        assertTrue(json.contains("\"fixtureName\": \"cluster-negative-split.txt\""))
        assertTrue(json.contains("\"fixtureName\": \"cluster-cjk-ivs-supplementary.txt\""))
        assertTrue(json.contains("\"fixtureName\": \"cluster-cjk-ivs-mixed-kana.txt\""))
        assertTrue(
            json.contains(
                "\"fixtureName\": \"cluster-cjk-ivs-supplementary.txt\", " +
                    "\"sourceText\": \"一󠄀\", " +
                    "\"inputTextHash\": \"ca64486c25ba2f967ffc2e82f9c6783121d5e2ecf57e81b09ddcb9d2d0aa234c\", " +
                    "\"gate\": null, \"invariants\": [{\"name\": \"grapheme-cluster-invariants\", " +
                    "\"clusterRange\": \"0..0\", \"segmentRanges\": [\"0..2\"]",
            ),
        )
        assertTrue(
            json.contains(
                "\"fixtureName\": \"cluster-cjk-ivs-mixed-kana.txt\", " +
                    "\"sourceText\": \"一󠄀ア\", " +
                    "\"inputTextHash\": \"47a0d67b3c920f7adef6316a46835a2546bef7f8447cf86fc4d63bb61f5c23ea\", " +
                    "\"gate\": null, \"invariants\": [{\"name\": \"grapheme-cluster-invariants\", " +
                    "\"clusterRange\": \"0..1\", \"segmentRanges\": [\"0..2\", \"3..3\"], " +
                    "\"passed\": true, \"diagnostic\": null}, {\"name\": \"bidi-run-boundaries-align\", " +
                    "\"clusterRange\": \"0..1\", \"segmentRanges\": [\"0..3\"], \"passed\": true, \"diagnostic\": null}, " +
                    "{\"name\": \"script-run-boundaries-align\", \"clusterRange\": \"0..1\", " +
                    "\"segmentRanges\": [\"0..2\", \"3..3\"]",
            ),
        )
        assertTrue(json.contains("\"gate\": \"scaledemoji\""))
        assertTrue(json.contains("\"dumpId\": \"unicode-segments\""))
        assertTrue(json.contains("\"dumpId\": \"bidi-runs\""))
        assertTrue(json.contains("\"dumpId\": \"script-runs\""))
        assertTrue(json.contains("\"code\": \"text.shaping.cluster-invariant-failed\""))
    }

    @Test
    fun clusterSafetyFixturesAreCheckedInAndNonEmpty() {
        val fixtureDir = projectRoot().resolve("font/fixtures/expected/unicode")

        clusterFixtureNames.forEach { fixtureName ->
            val path = fixtureDir.resolve(fixtureName)
            assertTrue(Files.isRegularFile(path), "missing fixture $fixtureName")
            assertTrue(Files.readString(path).isNotEmpty(), "fixture $fixtureName must not be empty")
        }
    }

    @Test
    fun clusterSafetySuitePropagatesUnicodeVersionMismatchDiagnostics() {
        val report = ClusterSafetySuite(expectedUnicodeVersion = "99.0.0").evaluate(
            fixtures = listOf(
                ClusterSafetyFixture(
                    fixtureName = "cluster-emoji-family-zwj.txt",
                    sourceText = "\uD83D\uDC66\uD83C\uDFFB\u200D\uD83D\uDC66",
                    legacyGate = "scaledemoji",
                ),
            ),
            sourceDumpRefs = emptyList(),
        ).toCanonicalJson()

        assertTrue(report.contains("\"code\": \"text.shaping.unicode-data-version-mismatch\""))
        assertTrue(report.contains("\"gate\": \"scaledemoji\""))
    }

    private fun readProjectFile(relativePath: String): String =
        Files.readString(projectRoot().resolve(relativePath))

    private fun projectRoot(): Path =
        generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
