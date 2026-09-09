package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BidiRun
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest

class UnicodeBidiConformanceTest {
    @Test
    fun unknown_bidi_test_directive_fails_explicitly() {
        val failure = assertFailsWith<IllegalStateException> {
            bidiTestCases(
                sequenceOf(
                    "@Levels: 0",
                    "@Reorder: 0",
                    "@Unexpected: value",
                    "L; 2",
                ),
            ).toList()
        }

        assertEquals("Unknown BidiTest directive at line 3: @Unexpected: value", failure.message)
    }

    @Test
    fun every_applicable_unicode_16_bidi_class_sequence_matches_levels_and_reordering() {
        var executed = 0
        var excludedAutoDirection = 0
        unicode16BidiTestCases(onAutoDirection = { excludedAutoDirection += 1 }).forEach { case ->
            verify(case)
            executed += 1
        }

        println("Unicode 16.0 BidiTest explicit-direction cases executed: $executed")
        println("Unicode 16.0 BidiTest auto-direction cases excluded: $excludedAutoDirection")
    }

    @Test
    fun every_applicable_unicode_16_bidi_character_sequence_matches_levels_and_reordering() {
        var executed = 0
        var excludedAutoDirection = 0
        unicode16BidiCharacterTestCases(
            onAutoDirection = { excludedAutoDirection += 1 },
        ).forEach { case ->
            verify(case)
            executed += 1
        }

        println("Unicode 16.0 BidiCharacterTest explicit-direction cases executed: $executed")
        println("Unicode 16.0 BidiCharacterTest auto-direction cases excluded: $excludedAutoDirection")
    }

    private fun verify(case: BidiCase) {
        val snapshot = snapshotOf(case.scalars)
        val analysis = analyzer.analyze(
            snapshot,
            UnicodeAnalysisRequest(case.baseDirection, language = "en"),
        )
        val boundaries = (0..snapshot.scalars.size).associateBy(snapshot::textIndexAtScalarBoundary)
        val actualLevels = expandLevels(analysis.logicalBidiRuns, boundaries)

        case.levels.forEachIndexed { index, expected ->
            if (expected != null) {
                assertEquals(
                    expected,
                    actualLevels[index],
                    "Unicode 16.0 ${case.corpus} line ${case.lineNumber}, scalar $index: ${case.source}",
                )
            }
        }
        assertEquals(
            case.visualOrder,
            visualScalarOrder(analysis.visualBidiRuns, boundaries).filter { case.levels[it] != null },
            "Unicode 16.0 ${case.corpus} line ${case.lineNumber}: ${case.source}",
        )
    }

    private fun unicode16BidiTestCases(onAutoDirection: () -> Unit): Sequence<BidiCase> = sequence {
        val corpus = resource("BidiTest.txt")
        corpus.bufferedReader().useLines { lines ->
            yieldAll(bidiTestCases(lines, onAutoDirection))
        }
    }

    private fun bidiTestCases(
        lines: Sequence<String>,
        onAutoDirection: () -> Unit = {},
    ): Sequence<BidiCase> = sequence {
        var levels: List<Int?>? = null
        var visualOrder: List<Int>? = null
        lines.forEachIndexed { index, rawLine ->
            val source = rawLine.substringBefore('#').trim()
            when {
                source.isEmpty() -> Unit
                source.startsWith("@Levels:") -> levels = parseLevels(source.substringAfter(':'))
                source.startsWith("@Reorder:") -> visualOrder = parseIntegers(source.substringAfter(':'))
                source.startsWith('@') -> error("Unknown BidiTest directive at line ${index + 1}: $source")
                else -> {
                    val fields = source.split(';').map(String::trim)
                    check(fields.size == 2) { "Malformed BidiTest line ${index + 1}: $source" }
                    val scalars = fields[0].split(WHITESPACE).map(BIDI_CLASS_SCALARS::getValue)
                    val bitset = fields[1].toInt(radix = 16)
                    if (bitset and AUTO_LTR_BIT != 0) onAutoDirection()
                    val expectedLevels = checkNotNull(levels) { "Missing @Levels before BidiTest line ${index + 1}." }
                    val expectedOrder = checkNotNull(visualOrder) { "Missing @Reorder before BidiTest line ${index + 1}." }
                    check(expectedLevels.size == scalars.size) {
                        "@Levels length differs from BidiTest line ${index + 1}."
                    }
                    if (bitset and EXPLICIT_LTR_BIT != 0) {
                        yield(BidiCase("BidiTest", index + 1, source, scalars, BaseDirection.LEFT_TO_RIGHT, expectedLevels, expectedOrder))
                    }
                    if (bitset and EXPLICIT_RTL_BIT != 0) {
                        yield(BidiCase("BidiTest", index + 1, source, scalars, BaseDirection.RIGHT_TO_LEFT, expectedLevels, expectedOrder))
                    }
                }
            }
        }
    }

    private fun unicode16BidiCharacterTestCases(onAutoDirection: () -> Unit): Sequence<BidiCase> = sequence {
        val corpus = resource("BidiCharacterTest.txt")
        corpus.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                val source = rawLine.substringBefore('#').trim()
                if (source.isEmpty()) return@forEachIndexed
                val fields = source.split(';').map(String::trim)
                check(fields.size == 5) { "Malformed BidiCharacterTest line ${index + 1}: $source" }
                if (fields[1] == AUTO_DIRECTION) {
                    onAutoDirection()
                    return@forEachIndexed
                }
                val scalars = fields[0].split(WHITESPACE).map { it.toInt(radix = 16) }
                yield(
                    BidiCase(
                        "BidiCharacterTest",
                        index + 1,
                        source,
                        scalars,
                        when (fields[1]) {
                            "0" -> BaseDirection.LEFT_TO_RIGHT
                            "1" -> BaseDirection.RIGHT_TO_LEFT
                            else -> error("Unknown paragraph direction at BidiCharacterTest line ${index + 1}.")
                        },
                        parseLevels(fields[3]),
                        parseIntegers(fields[4]),
                    ),
                )
            }
        }
    }

    private fun resource(fileName: String) = checkNotNull(
        javaClass.getResourceAsStream("/unicode/16.0.0/$fileName"),
    ) { "The checked-in Unicode 16.0 $fileName corpus is missing." }

    private fun parseLevels(source: String): List<Int?> = source.trim().split(WHITESPACE).map { token ->
        token.takeUnless { it == X9_REMOVED }?.toInt()
    }

    private fun parseIntegers(source: String): List<Int> {
        val trimmed = source.trim()
        return if (trimmed.isEmpty()) emptyList() else trimmed.split(WHITESPACE).map(String::toInt)
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
            if (run.level.rem(2) == 0) addAll(start until endExclusive) else addAll((endExclusive - 1) downTo start)
        }
    }

    private fun snapshotOf(scalars: List<Int>): TextSnapshot = TextSnapshots.decodeUtf16(
        TextVersion.create(),
        listOf(TextSlice.Utf16(buildString { scalars.forEach(::appendCodePoint) }.toCharArray())),
    ).snapshot

    private data class BidiCase(
        val corpus: String,
        val lineNumber: Int,
        val source: String,
        val scalars: List<Int>,
        val baseDirection: BaseDirection,
        val levels: List<Int?>,
        val visualOrder: List<Int>,
    )

    private companion object {
        val analyzer: UnicodeAnalyzer = JvmUnicodeAnalyzer.create()
        val WHITESPACE: Regex = Regex("\\s+")
        const val AUTO_DIRECTION: String = "2"
        const val AUTO_LTR_BIT: Int = 0x1
        const val EXPLICIT_LTR_BIT: Int = 0x2
        const val EXPLICIT_RTL_BIT: Int = 0x4
        const val X9_REMOVED: String = "x"

        val BIDI_CLASS_SCALARS: Map<String, Int> = mapOf(
            "L" to 0x0061, "R" to 0x05D0, "EN" to 0x0030, "ES" to 0x002B,
            "ET" to 0x0024, "AN" to 0x0660, "CS" to 0x002C, "B" to 0x2029,
            "S" to 0x0009, "WS" to 0x0020, "ON" to 0x0022, "LRE" to 0x202A,
            "LRO" to 0x202D, "AL" to 0x0627, "RLE" to 0x202B, "RLO" to 0x202E,
            "PDF" to 0x202C, "NSM" to 0x0300, "BN" to 0x00AD, "FSI" to 0x2068,
            "LRI" to 0x2066, "RLI" to 0x2067, "PDI" to 0x2069,
        )
    }
}
