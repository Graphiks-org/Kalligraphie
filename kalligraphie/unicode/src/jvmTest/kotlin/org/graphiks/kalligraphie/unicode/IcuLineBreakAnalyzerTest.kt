package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditorOperationLimitKind
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.LineBreakAnalysisOutcome
import org.graphiks.kalligraphie.api.LineBreakKind
import org.graphiks.kalligraphie.api.LineBreakOpportunity
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest

class IcuLineBreakAnalyzerTest {
    @Test
    fun bounded_analysis_rejects_real_work_atomically_and_retry_keeps_exact_partitions() {
        val snapshot = snapshotOf("alpha beta שלום")
        val unicodeAnalysis = unicodeAnalyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )
        val analyzer = JvmLineBreakAnalyzer.createBounded()

        val limited = analyzer.analyze(
            snapshot,
            unicodeAnalysis,
            EditorOperationProfile(maxLineBreakWork = 4),
            CancellationToken.none,
        )
        val failure = assertIs<LineBreakAnalysisOutcome.LimitExceeded>(limited).limit
        assertEquals(EditorOperationLimitKind.LINE_BREAK_WORK, failure.kind)
        assertEquals(4L, failure.maximum)
        assertEquals(15L, failure.observed)

        val retry = assertIs<LineBreakAnalysisOutcome.Success>(
            analyzer.analyze(
                snapshot,
                unicodeAnalysis,
                EditorOperationProfile.unbounded,
                CancellationToken.none,
            ),
        ).value
        assertEquals(
            listOf(
                opportunity(snapshot, 6, LineBreakKind.ALLOWED),
                opportunity(snapshot, 11, LineBreakKind.ALLOWED),
            ),
            retry.opportunities,
        )
        assertEquals((0 until 15).map { range(snapshot, it, it + 1) }, retry.graphemeClusters)
    }

    @Test
    fun cancelled_bounded_analysis_publishes_nothing_and_exact_retry_succeeds() {
        val repetitions = 512
        val snapshot = snapshotOf("👩‍🚀 مرحبا שלום ".repeat(repetitions))
        val unicodeAnalysis = unicodeAnalyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.RIGHT_TO_LEFT, language = "ar"),
        )
        val analyzer = JvmLineBreakAnalyzer.createBounded()

        assertEquals(
            LineBreakAnalysisOutcome.Cancelled,
            analyzer.analyze(
                snapshot,
                unicodeAnalysis,
                EditorOperationProfile(cancellationCheckInterval = 1),
                CancellationToken.cancelled,
            ),
        )

        val retry = assertIs<LineBreakAnalysisOutcome.Success>(
            analyzer.analyze(
                snapshot,
                unicodeAnalysis,
                EditorOperationProfile.unbounded,
                CancellationToken.none,
            ),
        ).value
        val expectedOpportunities = buildList {
            repeat(repetitions) { unit ->
                val start = unit * 15
                add(opportunity(snapshot, start + 4, LineBreakKind.ALLOWED))
                add(opportunity(snapshot, start + 10, LineBreakKind.ALLOWED))
                if (unit + 1 < repetitions) add(opportunity(snapshot, start + 15, LineBreakKind.ALLOWED))
            }
        }
        val expectedClusters = buildList {
            repeat(repetitions) { unit ->
                val start = unit * 15
                add(range(snapshot, start, start + 3))
                for (scalar in start + 3 until start + 15) add(range(snapshot, scalar, scalar + 1))
            }
        }
        assertEquals(expectedOpportunities, retry.opportunities)
        assertEquals(expectedClusters, retry.graphemeClusters)
    }

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
    fun opportunities_are_independent_of_valid_utf16_slice_boundaries() {
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
            val splitAtEveryCompleteUnit = snapshotOf(
                version,
                splitAtCompleteUtf16Units(text.toCharArray()),
            )

            assertEquals(analyze(unsplit), analyze(splitAtEveryCompleteUnit), text)
        }
    }

    private fun splitAtCompleteUtf16Units(codeUnits: CharArray): List<CharArray> = buildList {
        var start = 0
        while (start < codeUnits.size) {
            val length = if (
                codeUnits[start].isHighSurrogate() &&
                start + 1 < codeUnits.size &&
                codeUnits[start + 1].isLowSurrogate()
            ) {
                2
            } else {
                1
            }
            add(codeUnits.copyOfRange(start, start + length))
            start += length
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

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int) =
        org.graphiks.kalligraphie.api.TextRange(
            snapshot.textIndexAtScalarBoundary(start),
            snapshot.textIndexAtScalarBoundary(endExclusive),
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
