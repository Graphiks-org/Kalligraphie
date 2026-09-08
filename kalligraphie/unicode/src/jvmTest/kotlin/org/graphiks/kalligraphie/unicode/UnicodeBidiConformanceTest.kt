package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BidiRun
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest

class UnicodeBidiConformanceTest {
    @Test
    fun nonspacing_marks_at_isolate_starts_keep_their_isolate_levels() {
        val cases = listOf(
            "\u2066\u0331\u05D0\u2069" to listOf(0, 2, 3, 0),
            "\u2067\u0331a\u2069" to listOf(0, 1, 2, 0),
            "\u2068\u0331\u05D0\u2069" to listOf(0, 1, 1, 0),
        )

        cases.forEach { (text, expectedLevels) ->
            val snapshot = snapshotOf(text)
            val analysis = analyzer.analyze(
                snapshot,
                UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
            )
            val boundaries = (0..snapshot.scalars.size).associateBy(snapshot::textIndexAtScalarBoundary)

            assertEquals(expectedLevels, expandLevels(analysis.logicalBidiRuns, boundaries))
        }
    }

    @Test
    fun astral_arabic_letters_keep_scalar_boundaries_and_visual_order() {
        val snapshot = snapshotOf(
            buildString {
                append('a')
                appendCodePoint(0x1EE00)
                appendCodePoint(0x1EE01)
                append('b')
            },
        )
        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language = "en"),
        )
        val boundaries = (0..snapshot.scalars.size).associateBy(snapshot::textIndexAtScalarBoundary)

        assertEquals(listOf(0, 1, 1, 0), expandLevels(analysis.logicalBidiRuns, boundaries))
        assertEquals(listOf(0, 2, 1, 3), visualScalarOrder(analysis.visualBidiRuns, boundaries))
    }

    @Test
    fun unicode_16_bidi_vectors_match_the_public_analysis_contract() {
        val cases = unicode16BidiCases().toList()
        assertEquals(512, cases.size, "The vendored Unicode 16 BiDi vector set must remain complete.")
        assertEquals(256, cases.count { it.baseDirection == BaseDirection.LEFT_TO_RIGHT })
        assertEquals(256, cases.count { it.baseDirection == BaseDirection.RIGHT_TO_LEFT })
        assertTrue(cases.any { case -> case.scalars.any { it in 0x0030..0x0039 } })
        assertTrue(cases.any { case -> case.scalars.any { it in 0x0660..0x0669 } })
        assertTrue(cases.any { case -> case.scalars.contains(0x0627) })
        assertTrue(cases.any { case -> case.scalars.any { it in 0x0300..0x036F } })
        assertTrue(cases.any { case -> case.scalars.any { it in 0x2066..0x2069 } })

        cases.forEach { case ->
            val snapshot = snapshotOf(case.text)
            val analysis = analyzer.analyze(
                snapshot,
                UnicodeAnalysisRequest(case.baseDirection, language = "en"),
            )
            val boundaries = (0..snapshot.scalars.size).associateBy(snapshot::textIndexAtScalarBoundary)

            assertEquals(
                case.levels,
                expandLevels(analysis.logicalBidiRuns, boundaries),
                "Unicode 16 BidiCharacterTest line ${case.lineNumber}: ${case.source}",
            )
            assertEquals(
                case.visualOrder,
                visualScalarOrder(analysis.visualBidiRuns, boundaries),
                "Unicode 16 BidiCharacterTest line ${case.lineNumber}: ${case.source}",
            )
        }
    }

    private fun unicode16BidiCases(): Sequence<BidiCase> = sequence {
        val corpus = checkNotNull(javaClass.getResourceAsStream("/BidiCharacterTest-16.0.0-stratified.txt")) {
            "The checked-in Unicode 16 BidiCharacterTest vector set is missing."
        }
        corpus.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                if (rawLine.isNotBlank()) yield(parseCase(rawLine))
            }
        }
    }

    private fun parseCase(rawLine: String): BidiCase {
        val (lineNumber, source) = rawLine.split('|', limit = 2)
        val fields = source.split(';').map(String::trim)
        check(fields.size == 5) { "Malformed BidiCharacterTest vector: $rawLine" }
        val scalars = fields[0].split(WHITESPACE).map { it.toInt(radix = 16) }
        return BidiCase(
            lineNumber = lineNumber.toInt(),
            source = source,
            scalars = scalars,
            text = buildString { scalars.forEach { appendCodePoint(it) } },
            baseDirection = when (fields[1]) {
                "0" -> BaseDirection.LEFT_TO_RIGHT
                "1" -> BaseDirection.RIGHT_TO_LEFT
                else -> error("The checked-in BiDi vector must use an explicit base direction: $rawLine")
            },
            levels = fields[3].split(WHITESPACE).map(String::toInt),
            visualOrder = fields[4].split(WHITESPACE).map(String::toInt),
        )
    }

    private fun expandLevels(runs: List<BidiRun>, boundaries: Map<TextIndex, Int>): List<Int> = buildList {
        runs.forEach { run ->
            val start = boundaries.getValue(run.range.start)
            val endExclusive = boundaries.getValue(run.range.endExclusive)
            repeat(endExclusive - start) { add(run.level) }
        }
    }

    private fun visualScalarOrder(runs: List<BidiRun>, boundaries: Map<TextIndex, Int>): List<Int> = buildList {
        runs.forEach { run ->
            val start = boundaries.getValue(run.range.start)
            val endExclusive = boundaries.getValue(run.range.endExclusive)
            if (run.level.rem(2) == 0) {
                addAll(start until endExclusive)
            } else {
                addAll((endExclusive - 1) downTo start)
            }
        }
    }

    private fun snapshotOf(text: String): TextSnapshot =
        TextSnapshots.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(text.toCharArray())),
        ).snapshot

    private data class BidiCase(
        val lineNumber: Int,
        val source: String,
        val scalars: List<Int>,
        val text: String,
        val baseDirection: BaseDirection,
        val levels: List<Int>,
        val visualOrder: List<Int>,
    )

    private companion object {
        val analyzer: UnicodeAnalyzer = JvmUnicodeAnalyzer.create()
        val WHITESPACE: Regex = Regex("\\s+")
    }
}
