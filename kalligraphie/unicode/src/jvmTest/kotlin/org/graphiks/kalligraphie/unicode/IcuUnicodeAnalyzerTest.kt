package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BidiRun
import org.graphiks.kalligraphie.api.ScriptLanguageRun
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.UnicodeDataIdentity

class IcuUnicodeAnalyzerTest {
    @Test
    fun extended_grapheme_cluster_keeps_emoji_zwj_sequence_together() {
        val snapshot = snapshotOf("A\uD83D\uDC69\u200D\uD83D\uDE80B")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )

        assertEquals(
            listOf(range(snapshot, 0, 1), range(snapshot, 1, 4), range(snapshot, 4, 5)),
            analysis.graphemeClusters,
        )
        assertEquals("16.0", analysis.unicodeData.unicodeVersion)
        assertEquals("ICU4J", analysis.unicodeData.implementation)
        assertEquals("77.1", analysis.unicodeData.implementationVersion)
    }

    @Test
    fun unicode_16_indic_conjunct_with_gujarati_shadda_is_one_extended_grapheme_cluster() {
        val snapshot = snapshotOf("\u0AB8\u0AFB\u0ACD\u0AB8\u0AFB")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "hi"),
        )

        assertEquals(listOf(range(snapshot, 0, 5)), analysis.graphemeClusters)
    }

    @Test
    fun inherited_mark_follows_the_neighbouring_latin_script_run() {
        val snapshot = snapshotOf("a\u0301\u0628")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )

        assertEquals(
            listOf(
                ScriptLanguageRun(range(snapshot, 0, 2), script = "Latn", language = "en"),
                ScriptLanguageRun(range(snapshot, 2, 3), script = "Arab", language = "en"),
            ),
            analysis.scriptLanguageRuns,
        )
    }

    @Test
    fun leading_inherited_mark_follows_the_next_latin_script_run() {
        val snapshot = snapshotOf("\u0301a")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )

        assertEquals(
            listOf(ScriptLanguageRun(range(snapshot, 0, 2), script = "Latn", language = "en")),
            analysis.scriptLanguageRuns,
        )
    }

    @Test
    fun trailing_inherited_mark_follows_the_previous_latin_script_run() {
        val snapshot = snapshotOf("a\u0301")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )

        assertEquals(
            listOf(ScriptLanguageRun(range(snapshot, 0, 2), script = "Latn", language = "en")),
            analysis.scriptLanguageRuns,
        )
    }

    @Test
    fun paired_punctuation_uses_the_determinable_outer_script() {
        val snapshot = snapshotOf("a(\u03B2)c")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )

        assertEquals(
            listOf(
                ScriptLanguageRun(range(snapshot, 0, 2), script = "Latn", language = "en"),
                ScriptLanguageRun(range(snapshot, 2, 3), script = "Grek", language = "en"),
                ScriptLanguageRun(range(snapshot, 3, 5), script = "Latn", language = "en"),
            ),
            analysis.scriptLanguageRuns,
        )
    }

    @Test
    fun multi_value_script_extensions_use_matching_context() {
        val snapshot = snapshotOf("\u3042\u30FC\u3044")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "ja"),
        )

        assertEquals(
            listOf(ScriptLanguageRun(range(snapshot, 0, 3), script = "Hira", language = "ja")),
            analysis.scriptLanguageRuns,
        )
    }

    @Test
    fun all_common_text_remains_common_without_script_context() {
        val snapshot = snapshotOf("()")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )

        assertEquals(
            listOf(ScriptLanguageRun(range(snapshot, 0, 2), script = "Zyyy", language = "en")),
            analysis.scriptLanguageRuns,
        )
    }

    @Test
    fun private_use_and_unassigned_scalars_remain_unknown() {
        val snapshot = snapshotOf("\uE000\u0378")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )

        assertEquals(
            listOf(ScriptLanguageRun(range(snapshot, 0, 2), script = "Zzzz", language = "en")),
            analysis.scriptLanguageRuns,
        )
    }

    @Test
    fun pure_rtl_line_reports_odd_logical_level() {
        val snapshot = snapshotOf("\u05D0\u05D1\u05D2")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.RIGHT_TO_LEFT, language = "he"),
        )

        val expected = BidiRun(range(snapshot, 0, 3), level = 1)
        assertEquals(listOf(expected), analysis.logicalBidiRuns)
        assertEquals(listOf(expected), analysis.visualBidiRuns)
    }

    @Test
    fun mixed_rtl_line_reports_logical_levels_and_visual_order() {
        val snapshot = snapshotOf("abc\u05D0\u05D1\u05D2def")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.RIGHT_TO_LEFT, language = "he"),
        )

        val leadingLatin = BidiRun(range(snapshot, 0, 3), level = 2)
        val hebrew = BidiRun(range(snapshot, 3, 6), level = 1)
        val trailingLatin = BidiRun(range(snapshot, 6, 9), level = 2)
        assertEquals(listOf(leadingLatin, hebrew, trailingLatin), analysis.logicalBidiRuns)
        assertEquals(listOf(trailingLatin, hebrew, leadingLatin), analysis.visualBidiRuns)
    }

    @Test
    fun deep_embeddings_accept_resolved_level_126() {
        val snapshot = snapshotOf("\u202B".repeat(63) + "a" + "\u202C".repeat(63))

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )

        val embeddedText = BidiRun(range(snapshot, 0, 64), level = 126)
        val closingControls = BidiRun(range(snapshot, 64, 127), level = 0)
        assertEquals(listOf(embeddedText, closingControls), analysis.logicalBidiRuns)
        assertEquals(listOf(embeddedText, closingControls), analysis.visualBidiRuns)
    }

    @Test
    fun uax9_embedding_and_isolate_vector_preserves_logical_levels_and_visual_permutation() {
        val snapshot = snapshotOf(
            "\u202E" + "a" + "\u202A" + "b" + "\u202C" + "\u2066" + "c" + "\u2069" +
                "\u202A" + "d" + "\u202C" + "e" + "\u202C",
        )

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.RIGHT_TO_LEFT, language = "en"),
        )

        val first = BidiRun(range(snapshot, 0, 2), level = 3)
        val second = BidiRun(range(snapshot, 2, 10), level = 4)
        val third = BidiRun(range(snapshot, 10, 12), level = 3)
        val fourth = BidiRun(range(snapshot, 12, 13), level = 1)
        assertEquals(listOf(first, second, third, fourth), analysis.logicalBidiRuns)
        assertEquals(listOf(fourth, third, second, first), analysis.visualBidiRuns)
    }

    @Test
    fun malformed_language_tag_is_rejected_deterministically() {
        val snapshot = snapshotOf("abc")

        val failure = assertFailsWith<IllegalArgumentException> {
            analyzer.analyze(
                snapshot,
                UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en_US"),
            )
        }

        assertEquals("Language must be a well-formed BCP 47 tag.", failure.message)
    }

    @Test
    fun well_formed_language_tag_is_canonicalized_in_script_runs() {
        val snapshot = snapshotOf("abc")

        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "EN-us"),
        )

        assertEquals("en-US", analysis.scriptLanguageRuns.single().language)
    }

    @Test
    fun unicode_analysis_rejects_empty_ranges_inside_non_empty_partitions() {
        val snapshot = snapshotOf("a")

        assertFailsWith<IllegalArgumentException> {
            UnicodeAnalysis(
                range = range(snapshot, 0, 1),
                unicodeData = unicodeData(),
                graphemeClusters = listOf(range(snapshot, 0, 0), range(snapshot, 0, 1)),
                scriptLanguageRuns = listOf(ScriptLanguageRun(range(snapshot, 0, 1), "Latn", "en")),
                logicalBidiRuns = listOf(BidiRun(range(snapshot, 0, 1), level = 0)),
                visualBidiRuns = listOf(BidiRun(range(snapshot, 0, 1), level = 0)),
            )
        }
    }

    @Test
    fun unicode_analysis_rejects_visual_runs_from_a_different_snapshot() {
        val snapshot = snapshotOf("a")
        val foreignSnapshot = snapshotOf("a")

        assertFailsWith<IllegalArgumentException> {
            UnicodeAnalysis(
                range = range(snapshot, 0, 1),
                unicodeData = unicodeData(),
                graphemeClusters = listOf(range(snapshot, 0, 1)),
                scriptLanguageRuns = listOf(ScriptLanguageRun(range(snapshot, 0, 1), "Latn", "en")),
                logicalBidiRuns = listOf(BidiRun(range(snapshot, 0, 1), level = 0)),
                visualBidiRuns = listOf(BidiRun(range(foreignSnapshot, 0, 1), level = 0)),
            )
        }
    }

    private fun snapshotOf(text: String): TextSnapshot =
        TextSnapshots.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(text.toCharArray())),
        ).snapshot

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(
            snapshot.textIndexAtScalarBoundary(start),
            snapshot.textIndexAtScalarBoundary(endExclusive),
        )

    private fun unicodeData(): UnicodeDataIdentity =
        UnicodeDataIdentity(unicodeVersion = "16.0", implementation = "ICU4J", implementationVersion = "77.1")

    private companion object {
        val analyzer: UnicodeAnalyzer = JvmUnicodeAnalyzer.create()
    }
}
