package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ygdrasil.kalligraphie.text.shaping.PinnedUnicodeDataSetResources
import io.ygdrasil.kalligraphie.text.shaping.ScriptExtensionsItemizer

class ScriptItemizationTest {
    private val scriptFixtureNames = listOf(
        "script-ambiguous-extension",
        "script-arabic-extension-ambiguous",
        "script-arabic-marks",
        "script-cjk-vs",
        "script-conflicting-context",
        "script-devanagari-matra",
        "script-emoji-zwj",
        "script-greek-polytonic",
        "script-hebrew-niqqud",
        "script-latin-combining",
        "script-thai-tone",
        "script-unsupported",
    )

    @Test
    fun scriptExtensionsItemizerUsesPinnedDataClustersExtensionsAndStableDiagnostics() {
        val itemizer = ScriptExtensionsItemizer(PinnedUnicodeDataSetResources.load())

        val latin = itemizer.itemize("A\u0301")
        assertEquals(1, latin.runs.size)
        assertEquals(0..0, latin.runs.single().clusterRange)
        assertEquals(0..1, latin.runs.single().utf16Range)
        assertEquals("Latn", latin.runs.single().selectedScript)
        assertEquals(listOf("latn"), latin.runs.single().openTypeScriptTags)
        assertEquals(listOf("Latn"), latin.runs.single().extensionCandidates)
        assertEquals("strong-script", latin.runs.single().reason)
        assertEquals(emptyList(), latin.diagnostics)

        val cjk = itemizer.itemize("\u4E00\u3003\uFE0F")
        assertEquals(1, cjk.runs.size)
        assertEquals(0..1, cjk.runs.single().clusterRange)
        assertEquals(0..2, cjk.runs.single().codePointRange)
        assertEquals("Hani", cjk.runs.single().selectedScript)
        assertEquals(listOf("hani"), cjk.runs.single().openTypeScriptTags)
        assertTrue(cjk.runs.single().extensionCandidates.contains("Hani"))
        assertTrue(cjk.runs.single().extensionCandidates.contains("Kana"))
        assertEquals("script-extension", cjk.runs.single().reason)
        assertEquals(emptyList(), cjk.diagnostics)

        val mixedCjkSupplementaryVariationSelector = itemizer.itemize("\u4E00\uDB40\uDD00\u30A2")
        assertEquals(listOf("Hani", "Kana"), mixedCjkSupplementaryVariationSelector.runs.map { it.selectedScript })
        assertEquals(listOf(0..2, 3..3), mixedCjkSupplementaryVariationSelector.runs.map { it.utf16Range })
        assertEquals(emptyList(), mixedCjkSupplementaryVariationSelector.diagnostics)

        val ambiguous = itemizer.itemize("\u3003")
        assertEquals("Zyyy", ambiguous.runs.single().selectedScript)
        assertEquals(listOf("Bopo", "Hang", "Hani", "Hira", "Kana"), ambiguous.runs.single().extensionCandidates)
        assertEquals("script-extension-ambiguous", ambiguous.runs.single().reason)
        assertEquals("text.shaping.script-run-ambiguous", ambiguous.diagnostics.single().code)
        assertEquals(0..0, ambiguous.diagnostics.single().textRange)

        val isolatedTatweel = itemizer.itemize("\u0640")
        assertEquals("Zyyy", isolatedTatweel.runs.single().selectedScript)
        assertEquals(listOf("Arab", "Mand", "Syrc"), isolatedTatweel.runs.single().extensionCandidates)
        assertEquals("script-extension-ambiguous", isolatedTatweel.runs.single().reason)
        assertEquals("text.shaping.script-run-ambiguous", isolatedTatweel.diagnostics.single().code)
        assertEquals(0..0, isolatedTatweel.diagnostics.single().textRange)

        val conflictingContext = itemizer.itemize("A.\u03B1")
        assertEquals(listOf("Latn", "Zyyy", "Grek"), conflictingContext.runs.map { it.selectedScript })
        assertEquals("context-ambiguous", conflictingContext.runs[1].reason)
        assertEquals("text.shaping.script-run-ambiguous", conflictingContext.diagnostics.single().code)
        assertEquals(1..1, conflictingContext.diagnostics.single().textRange)

        val deterministicContext = itemizer.itemize("A.A")
        assertEquals(1, deterministicContext.runs.size)
        assertEquals("Latn", deterministicContext.runs.single().selectedScript)
        assertEquals("context-script", deterministicContext.runs.single().reason)
        assertEquals(emptyList(), deterministicContext.diagnostics)

        val unsupported = itemizer.itemize("\u10A0")
        assertEquals("Geor", unsupported.runs.single().selectedScript)
        assertEquals(emptyList(), unsupported.runs.single().openTypeScriptTags)
        assertEquals("unsupported-script", unsupported.runs.single().reason)
        assertEquals("text.shaping.script-unsupported", unsupported.diagnostics.single().code)
        assertEquals(0..0, unsupported.diagnostics.single().textRange)
    }

    @Test
    fun scriptRunsGoldenPinsRequiredMatrixFixturesAndNonClaims() {
        val dump = readProjectFile("font/fixtures/expected/unicode/script-runs.json")
        val generated = ScriptExtensionsItemizer(PinnedUnicodeDataSetResources.load())
            .dumpFixtures(scriptFixtureInputs())
            .toCanonicalJson()

        assertEquals(dump, generated)

        assertTrue(dump.contains("\"schemaVersion\": 1"))
        assertTrue(dump.contains("\"dumpId\": \"script-runs\""))
        assertTrue(dump.contains("\"ownerTickets\": [\"KFONT-M5-004\"]"))
        assertTrue(dump.contains("\"unicodeVersion\": \"16.0.0\""))
        assertTrue(
            dump.containsInOrder(
                listOf(
                    "\"fixtureName\": \"script-ambiguous-extension\"",
                    "\"fixtureName\": \"script-arabic-extension-ambiguous\"",
                    "\"fixtureName\": \"script-arabic-marks\"",
                    "\"fixtureName\": \"script-cjk-vs\"",
                    "\"fixtureName\": \"script-conflicting-context\"",
                    "\"fixtureName\": \"script-devanagari-matra\"",
                    "\"fixtureName\": \"script-emoji-zwj\"",
                    "\"fixtureName\": \"script-greek-polytonic\"",
                    "\"fixtureName\": \"script-hebrew-niqqud\"",
                    "\"fixtureName\": \"script-latin-combining\"",
                    "\"fixtureName\": \"script-thai-tone\"",
                    "\"fixtureName\": \"script-unsupported\"",
                ),
            ),
        )

        assertTrue(
            dump.containsInOrder(
                listOf(
                    "\"fixtureName\": \"script-latin-combining\"",
                    "\"selectedScript\": \"Latn\"",
                    "\"openTypeScriptTags\": [\"latn\"]",
                    "\"extensionCandidates\": [\"Latn\"]",
                    "\"reason\": \"strong-script\"",
                ),
            ),
        )
        assertTrue(
            dump.containsInOrder(
                listOf(
                    "\"fixtureName\": \"script-cjk-vs\"",
                    "\"selectedScript\": \"Hani\"",
                    "\"openTypeScriptTags\": [\"hani\"]",
                    "\"reason\": \"script-extension\"",
                ),
            ),
        )
        assertTrue(
            dump.containsInOrder(
                listOf(
                    "\"fixtureName\": \"script-conflicting-context\"",
                    "\"selectedScript\": \"Zyyy\"",
                    "\"reason\": \"context-ambiguous\"",
                ),
            ),
        )
        assertTrue(dump.contains("\"code\": \"text.shaping.script-unsupported\""))
        assertTrue(dump.contains("\"code\": \"text.shaping.script-run-ambiguous\""))

        assertTrue(
            dump.containsInOrder(
                listOf(
                    "\"nonClaims\": [",
                    "\"no-complete-target-support-claim\"",
                    "\"no-complete-ucd-claim\"",
                    "\"no-complete-gsub-gpos-claim\"",
                    "\"no-shaping-support-promotion\"",
                    "\"no-paragraph-support-claim\"",
                    "\"no-gpu-text-route-claim\"",
                ),
            ),
        )
        assertFalse(dump.contains("HarfBuzz", ignoreCase = true))
    }

    @Test
    fun scriptFixtureTextsAreCheckedInAndNamedByTicket() {
        val fixtureDir = projectRoot().resolve("font/fixtures/expected/unicode")

        scriptFixtureNames.forEach { fixtureName ->
            val name = "$fixtureName.txt"
            val path = fixtureDir.resolve(name)
            assertTrue(Files.isRegularFile(path), "missing fixture $name")
            assertTrue(Files.readString(path).isNotEmpty(), "fixture $name must not be empty")
        }
    }

    @Test
    fun scriptRunsDumpEscapesControlCharactersForStableJson() {
        val dump = ScriptExtensionsItemizer(PinnedUnicodeDataSetResources.load())
            .dumpFixtures(listOf("script-control" to "\u0001"))
            .toCanonicalJson()

        assertTrue(dump.contains("\"sourceText\": \"\\u0001\""))
    }

    @Test
    fun scriptDumpIndexAndFixtureManifestReferenceScriptItemizationEvidence() {
        val dumpIndex = readProjectFile("font/fixtures/expected/pure-kotlin-text/dump-evidence-index.json")
        assertTrue(
            dumpIndex.containsInOrder(
                listOf(
                    "\"dumpId\": \"script-runs\"",
                    "\"ownerTicket\": \"KFONT-M5-004\"",
                    "\"classification\": \"golden-gated\"",
                    "\"font/fixtures/expected/unicode/script-runs.json\"",
                    "\"producer-only\"",
                ),
            ),
        )

        val manifest = readProjectFile("font/fixtures/expected/pure-kotlin-text/fixture-evidence-manifest.json")
        assertTrue(
            manifest.containsInOrder(
                listOf(
                    "\"familyId\": \"shaping-scripts\"",
                    "\"font/fixtures/expected/unicode/script-runs.json\"",
                    "\"no-complete-gsub-gpos-claim\"",
                ),
            ),
        )
    }

    private fun String.containsInOrder(snippets: List<String>): Boolean {
        var cursor = 0
        for (snippet in snippets) {
            val index = indexOf(snippet, startIndex = cursor)
            if (index < 0) return false
            cursor = index + snippet.length
        }
        return true
    }

    private fun readProjectFile(relativePath: String): String =
        Files.readString(projectRoot().resolve(relativePath))

    private fun scriptFixtureInputs(): List<Pair<String, String>> =
        scriptFixtureNames.map { fixtureName ->
            fixtureName to readProjectFile("font/fixtures/expected/unicode/$fixtureName.txt").trimEnd('\n', '\r')
        }

    private fun projectRoot(): Path =
        generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
