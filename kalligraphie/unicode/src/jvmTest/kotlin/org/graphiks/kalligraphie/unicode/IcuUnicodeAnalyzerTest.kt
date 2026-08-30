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
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest

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
        assertEquals("76.1", analysis.unicodeData.implementationVersion)
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

    private companion object {
        val analyzer: UnicodeAnalyzer = JvmUnicodeAnalyzer.create()
    }
}
