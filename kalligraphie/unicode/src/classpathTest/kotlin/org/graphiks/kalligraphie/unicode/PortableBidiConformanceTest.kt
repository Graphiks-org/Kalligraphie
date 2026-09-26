package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile
import org.graphiks.kalligraphie.unicode.corpus.UnicodeTestEnvironment

/**
 * The Unicode 16 BiDi conformance corpora, run against the portable resolver.
 *
 * `BidiTest.txt` states every case as class abbreviations, so the driver substitutes one
 * representative scalar per class — the same substitution the ICU-backed conformance test performs —
 * and `BidiCharacterTest.txt` states real scalars. Both give the expected levels per position, with
 * `x` where the position's level is unconstrained, and the expected visual order, filtered the same
 * way. The authority for these rules is the corpus, not the implementation: a disagreement is a
 * defect in the resolver.
 *
 * The resolver is checked through the same surface the analyzer will expose — levels in, runs and
 * their visual order out.
 *
 * This test lives on the class-path side of the corpus seam because the two BiDi corpora are 15 MB
 * together and are deliberately not embedded in the iOS test binary — the same decision
 * `:kalligraphie:unicode` documents for its ICU-backed tests. The resolver they exercise is integer
 * arithmetic over this module's own tables, whose behaviour on the other targets the grapheme
 * corpus and the table spot checks establish.
 */
class PortableBidiConformanceTest {
    @Test
    fun every_applicable_unicode_16_bidi_class_sequence_matches_levels_and_reordering() {
        var executed = 0
        bidiTestCases().forEach { case ->
            verify(case)
            executed += 1
        }
        assertTrue(executed > 100_000, "BidiTest looks truncated: $executed cases")
    }

    @Test
    fun every_applicable_unicode_16_bidi_character_sequence_matches_levels_and_reordering() {
        var executed = 0
        bidiCharacterTestCases().forEach { case ->
            verify(case)
            executed += 1
        }
        assertTrue(executed > 10_000, "BidiCharacterTest looks truncated: $executed cases")
    }

    private fun verify(case: BidiCase) {
        val levels = UnicodeBidiEngine.resolveLevels(
            case.scalars,
            case.paragraphLevel,
            UnicodeAnalysisProfile.unbounded,
            CancellationToken.none,
        )
        assertEquals(
            case.levels.filterNotNull(),
            levels.filterIndexed { index, _ -> case.levels[index] != null }.toList(),
            "Unicode 16.0 ${case.corpus} line ${case.lineNumber}, levels: ${case.source}",
        )
        assertEquals(
            case.visualOrder.filter { case.levels[it] != null },
            visualScalarOrder(levels).filter { case.levels[it] != null },
            "Unicode 16.0 ${case.corpus} line ${case.lineNumber}, order: ${case.source}",
        )
    }

    private fun visualScalarOrder(levels: IntArray): List<Int> {
        val runs = runsOf(levels)
        return buildList {
            runs.forEach { (start, endExclusive, level) ->
                if (level.rem(2) == 0) addAll(start until endExclusive)
                else addAll((endExclusive - 1) downTo start)
            }
        }
    }

    /** The logical runs of one level array as `(start, endExclusive, level)` triples. */
    private fun runsOf(levels: IntArray): List<Triple<Int, Int, Int>> {
        val runs = mutableListOf<Triple<Int, Int, Int>>()
        if (levels.isEmpty()) return runs
        var start = 0
        var level = levels.first()
        for (index in 1 until levels.size) {
            if (levels[index] != level) {
                runs.add(Triple(start, index, level))
                start = index
                level = levels[index]
            }
        }
        runs.add(Triple(start, levels.size, level))
        return reorder(runs)
    }

    private fun reorder(logical: List<Triple<Int, Int, Int>>): List<Triple<Int, Int, Int>> {
        var minimumOddLevel: Int? = null
        var maximumLevel = 0
        logical.forEach { (_, _, level) ->
            if (level.rem(2) == 1) minimumOddLevel = minOf(minimumOddLevel ?: level, level)
            maximumLevel = maxOf(maximumLevel, level)
        }
        val firstReorderingLevel = minimumOddLevel ?: return logical
        val visual = ArrayList(logical)
        for (level in maximumLevel downTo firstReorderingLevel) {
            var sequenceStart: Int? = null
            for (index in 0..visual.size) {
                if (index < visual.size && visual[index].third >= level) {
                    if (sequenceStart == null) sequenceStart = index
                } else if (sequenceStart != null) {
                    var left = sequenceStart
                    var right = index - 1
                    while (left < right) {
                        val run = visual[left]
                        visual[left] = visual[right]
                        visual[right] = run
                        left += 1
                        right -= 1
                    }
                    sequenceStart = null
                }
            }
        }
        return visual
    }

    private fun bidiTestCases(): Sequence<BidiCase> = sequence {
        val corpus = UnicodeTestEnvironment.corpus.text("/unicode/16.0.0/BidiTest.txt")
        var levels: List<Int?>? = null
        var visualOrder: List<Int>? = null
        corpus.lineSequence().forEachIndexed { index, rawLine ->
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
                    val expectedLevels = checkNotNull(levels) { "Missing @Levels before line ${index + 1}." }
                    val expectedOrder = checkNotNull(visualOrder) { "Missing @Reorder before line ${index + 1}." }
                    check(expectedLevels.size == scalars.size) {
                        "@Levels length differs from BidiTest line ${index + 1}."
                    }
                    if (bitset and EXPLICIT_LTR_BIT != 0) {
                        yield(BidiCase("BidiTest", index + 1, source, scalars, 0, expectedLevels, expectedOrder))
                    }
                    if (bitset and EXPLICIT_RTL_BIT != 0) {
                        yield(BidiCase("BidiTest", index + 1, source, scalars, 1, expectedLevels, expectedOrder))
                    }
                }
            }
        }
    }

    private fun bidiCharacterTestCases(): Sequence<BidiCase> = sequence {
        val corpus = UnicodeTestEnvironment.corpus.text("/unicode/16.0.0/BidiCharacterTest.txt")
        corpus.lineSequence().forEachIndexed { index, rawLine ->
            val source = rawLine.substringBefore('#').trim()
            if (source.isEmpty()) return@forEachIndexed
            val fields = source.split(';').map(String::trim)
            check(fields.size == 5) { "Malformed BidiCharacterTest line ${index + 1}: $source" }
            if (fields[1] == AUTO_DIRECTION) return@forEachIndexed
            val scalars = fields[0].split(WHITESPACE).map { it.toInt(radix = 16) }
            yield(
                BidiCase(
                    "BidiCharacterTest",
                    index + 1,
                    source,
                    scalars,
                    fields[1].toInt(),
                    parseLevels(fields[3]),
                    parseIntegers(fields[4]),
                ),
            )
        }
    }

    private fun parseLevels(source: String): List<Int?> = source.trim().split(WHITESPACE).map { token ->
        token.takeUnless { it == X9_REMOVED }?.toInt()
    }

    private fun parseIntegers(source: String): List<Int> {
        val trimmed = source.trim()
        return if (trimmed.isEmpty()) emptyList() else trimmed.split(WHITESPACE).map(String::toInt)
    }

    private data class BidiCase(
        val corpus: String,
        val lineNumber: Int,
        val source: String,
        val scalars: List<Int>,
        val paragraphLevel: Int,
        val levels: List<Int?>,
        val visualOrder: List<Int>,
    )

    private companion object {
        val WHITESPACE: Regex = Regex("\\s+")
        const val AUTO_DIRECTION: String = "2"
        const val EXPLICIT_LTR_BIT: Int = 0x2
        const val EXPLICIT_RTL_BIT: Int = 0x4
        const val X9_REMOVED: String = "x"

        /**
         * One representative scalar per class abbreviation, the same substitution the ICU-backed
         * conformance test performs: the corpus states classes, the resolver reads scalars.
         */
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
