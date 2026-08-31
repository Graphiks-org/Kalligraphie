package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.LineBreakKind
import org.graphiks.kalligraphie.api.LineBreakOpportunity
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest

class IcuLineBreakAnalyzerTest {
    @Test
    fun normal_space_exposes_the_audited_allowed_boundary() {
        // Unicode 16.0 LineBreakTest.txt line 26:
        // × 23E9 × 0020 ÷ 23E9 ÷ (LB18 after SP; terminal boundary is implicit here).
        val snapshot = snapshotOf("\u23E9 \u23E9")

        assertEquals(
            listOf(opportunity(snapshot, 2, LineBreakKind.ALLOWED)),
            analyze(snapshot),
        )
    }

    @Test
    fun cr_lf_pair_exposes_one_mandatory_boundary_after_the_pair() {
        // Unicode 16.0 LineBreakTest.txt line 4697: × 000D × 000A ÷.
        val snapshot = snapshotOf("\r\n\u23E9")

        assertEquals(
            listOf(opportunity(snapshot, 2, LineBreakKind.MANDATORY)),
            analyze(snapshot),
        )
    }

    @Test
    fun combining_marks_and_variation_selectors_never_receive_interior_breaks() {
        // LineBreakTest.txt line 27 audits AL × CM; FE0F is the variation-selector CM case.
        val combining = snapshotOf("\u23E9\u0308 \u23E9")
        val variation = snapshotOf("\u2764\uFE0F \u23E9")

        assertEquals(
            listOf(opportunity(combining, 3, LineBreakKind.ALLOWED)),
            analyze(combining),
        )
        assertEquals(
            listOf(opportunity(variation, 3, LineBreakKind.ALLOWED)),
            analyze(variation),
        )
    }

    @Test
    fun emoji_zwj_sequence_never_receives_an_interior_break() {
        // LineBreakTest.txt lines 245 and 14105 audit AL × ZWJ and ZWJ × AL (LB8a).
        val snapshot = snapshotOf("\uD83D\uDC69\u200D\uD83D\uDE80 \u23E9")

        assertEquals(
            listOf(opportunity(snapshot, 4, LineBreakKind.ALLOWED)),
            analyze(snapshot),
        )
    }

    @Test
    fun range_end_is_published_only_for_source_required_termination() {
        val ordinary = snapshotOf("a")
        val terminated = snapshotOf("a\n")

        assertEquals(emptyList(), analyze(ordinary))
        assertEquals(
            listOf(opportunity(terminated, 2, LineBreakKind.MANDATORY)),
            analyze(terminated),
        )
    }

    @Test
    fun opportunities_are_independent_of_utf16_slice_boundaries() {
        val vectors = listOf(
            "\u23E9 \u23E9",
            "\r\n\u23E9",
            "\u23E9\u0308 \u23E9",
            "\u2764\uFE0F \u23E9",
            "\uD83D\uDC69\u200D\uD83D\uDE80 \u23E9",
        )

        vectors.forEach { text ->
            val version = TextVersion.create()
            val unsplit = snapshotOf(version, listOf(text.toCharArray()))
            val splitAtEveryCodeUnit = snapshotOf(
                version,
                text.toCharArray().map { codeUnit -> charArrayOf(codeUnit) },
            )

            assertEquals(analyze(unsplit), analyze(splitAtEveryCodeUnit), text)
        }
    }

    private fun analyze(snapshot: TextSnapshot): List<LineBreakOpportunity> {
        val unicodeAnalysis = unicodeAnalyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )
        return lineBreakAnalyzer.analyze(snapshot, unicodeAnalysis).opportunities
    }

    private fun opportunity(
        snapshot: TextSnapshot,
        boundary: Int,
        kind: LineBreakKind,
    ): LineBreakOpportunity = LineBreakOpportunity(
        boundary = snapshot.textIndexAtScalarBoundary(boundary),
        kind = kind,
    )

    private fun snapshotOf(text: String): TextSnapshot =
        snapshotOf(TextVersion.create(), listOf(text.toCharArray()))

    private fun snapshotOf(version: TextVersion, slices: List<CharArray>): TextSnapshot =
        TextSnapshots.decodeUtf16(
            version,
            slices.map(TextSlice::Utf16),
        ).snapshot

    private companion object {
        val unicodeAnalyzer: UnicodeAnalyzer = JvmUnicodeAnalyzer.create()
        val lineBreakAnalyzer: LineBreakAnalyzer = JvmLineBreakAnalyzer.create()
    }
}
