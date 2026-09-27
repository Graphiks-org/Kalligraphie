package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.LineBreakOpportunity
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.unicode.corpus.UnicodeTestEnvironment

/**
 * The JVM cross-check between the two line-break analyzers: the pinned ICU4J reference and the
 * portable engine over the same generated tables.
 *
 * Both analyze the same complete snapshots — the conformance corpus this module already runs
 * against its own expectations, plus real-world multilingual texts — and must publish identical
 * line-break opportunities: the same boundaries, the same kinds. Each analyzer reads the Unicode
 * analysis of its own family, which is how the identity check the contract requires stays honest;
 * the opportunity lists are what must agree. A disagreement is a defect in the portable rules,
 * the same disposition the HarfBuzz cross-checks take.
 */
class IcuLineBreakCrossCheckTest {
    @Test
    fun line_break_corpus_cases_publish_identical_opportunities() {
        var executed = 0
        lineBreakCorpusScalars().forEach { scalars ->
            crossCheck(scalars, "LineBreakTest line ${executed + 1}")
            executed += 1
        }
        assertTrue(executed > 10_000, "LineBreakTest looks truncated: $executed cases")
    }

    @Test
    fun real_world_multilingual_texts_publish_identical_opportunities() {
        val texts = listOf(
            "The quick brown fox jumps over the lazy dog.",
            "alpha beta שלום",
            "abc (\u05D0\u05D1), \u0627\u0628!",
            "\u3042\u30FC\u3044\u306E\u6587\u7AE0\u3001\u6F22\u5B57\u3068\u3072\u3089\u304C\u306A\u3002",
            "\u0AB8\u0AFB\u0ACD\u0AB8\u0AFB \u0915\u094D\u0937 \u0BA4\u0BAE\u0BBF\u0BB4\u0BCD",
            "\u2068abc\u2069 \u05D0\u2066xyz\u2069\u05D1",
            "e\u0301\u0302 = mc\u00B2 \u2014 C:\\Users\\test",
            "\u0627\u0644\u0633\u0644\u0627\u0645 \u0639\u0644\u064A\u0643\u0645 \u0648\u0631\u062D\u0645\u0629 \u0627\u0644\u0644\u0647",
            "1 2 3 \u05D0\u05D1 4 5 6 \u0627\u0628 7 8 9",
            "\u0024\u0028\u0031\u0032\u002E\u0033\u0035\u0029 2,1234 (12)\u00A2 12.54\u00A2 .50 \u20B91,00,000.00 -1/12",
            "\u00AB Excusez-moi, \u00BB dit mon p\u00E8re.",
        )
        texts.forEachIndexed { textIndex, text ->
            crossCheck(text.codePoints().toArray().toList(), "real-world text $textIndex")
        }
    }

    private fun crossCheck(scalars: List<Int>, label: String) {
        val text = buildString { scalars.forEach { scalar -> appendCodePoint(scalar) } }
        val snapshot = TextSnapshots.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(text.toCharArray())),
        ).snapshot
        val request = UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, "en")
        val icuOpportunities = ICU_LINE_BREAK.analyze(snapshot, ICU_UNICODE.analyze(snapshot, request)).opportunities
        val portableOpportunities = PORTABLE_LINE_BREAK.analyze(
            snapshot,
            PORTABLE_UNICODE.analyze(snapshot, request),
        ).opportunities
        assertEquals(
            icuOpportunities.map(LineBreakOpportunity::boundary),
            portableOpportunities.map(LineBreakOpportunity::boundary),
            "$label: line-break boundaries differ.",
        )
        assertEquals(
            icuOpportunities.map(LineBreakOpportunity::kind),
            portableOpportunities.map(LineBreakOpportunity::kind),
            "$label: line-break kinds differ.",
        )
    }

    /** Every case's scalars of the line break corpus, its ÷/× markers stripped. */
    private fun lineBreakCorpusScalars(): Sequence<List<Int>> = sequence {
        val corpus = UnicodeTestEnvironment.corpus.text("/unicode/16.0.0/LineBreakTest.txt")
        corpus.lineSequence().forEachIndexed { index, rawLine ->
            val source = rawLine.substringBefore('#').trim()
            if (source.isEmpty()) return@forEachIndexed
            val scalars = source.split(WHITESPACE)
                .filterNot { it == "÷" || it == "×" }
                .map { it.toInt(radix = 16) }
            check(scalars.isNotEmpty()) { "Malformed LineBreakTest line ${index + 1}: $source" }
            yield(scalars)
        }
    }

    private companion object {
        val ICU_UNICODE: BoundedUnicodeAnalyzer = JvmUnicodeAnalyzer.create()
        val ICU_LINE_BREAK: BoundedLineBreakAnalyzer = JvmLineBreakAnalyzer.createBounded()
        val PORTABLE_UNICODE: BoundedUnicodeAnalyzer = PortableUnicodeAnalyzer.create()
        val PORTABLE_LINE_BREAK: BoundedLineBreakAnalyzer = PortableLineBreakAnalyzer.createBounded()
        val WHITESPACE: Regex = Regex("\\s+")
    }
}
