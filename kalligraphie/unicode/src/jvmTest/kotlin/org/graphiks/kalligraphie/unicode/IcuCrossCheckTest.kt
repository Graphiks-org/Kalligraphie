package org.graphiks.kalligraphie.unicode

import com.ibm.icu.util.IllformedLocaleException
import com.ibm.icu.util.ULocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.unicode.corpus.UnicodeTestEnvironment

/**
 * The JVM cross-check between the two analyzers: the pinned ICU4J reference and the portable
 * analyzer over the same generated tables.
 *
 * Both analyze the same complete snapshots through the same public surface — the conformance
 * corpora this module already runs against their own expectations, plus real-world multilingual
 * texts — and every part of the analysis must agree: grapheme clusters, script-language runs,
 * logical BiDi runs, and their visual order. A disagreement is a defect in the portable rules, the
 * same disposition the HarfBuzz cross-checks take.
 *
 * The language grammar is cross-checked the same way: ICU's locale parser is the reference the
 * JVM has always used, and a tag the two canonicalize differently or disagree on rejecting is a
 * defect until reconciled.
 */
class IcuCrossCheckTest {
    @Test
    fun grapheme_corpus_cases_analyze_identically() {
        var executed = 0
        graphemeCorpusScalars().forEach { scalars ->
            crossCheck(scalars, BaseDirection.LEFT_TO_RIGHT, "en", "GraphemeBreakTest line ${executed + 1}")
            executed += 1
        }
        assertTrue(executed > 1_000, "The grapheme corpus looks truncated: $executed cases")
    }

    @Test
    fun bidi_character_corpus_cases_analyze_identically() {
        var executed = 0
        bidiCorpusCases().forEach { case ->
            crossCheck(case.scalars, BaseDirection.LEFT_TO_RIGHT, "en", "${case.label} [LTR]")
            crossCheck(case.scalars, BaseDirection.RIGHT_TO_LEFT, "en", "${case.label} [RTL]")
            executed += 1
        }
        assertTrue(executed > 500, "The BiDi corpus looks truncated: $executed cases")
    }

    @Test
    fun real_world_multilingual_texts_analyze_identically() {
        val texts = listOf(
            "The quick brown fox jumps over the lazy dog.",
            "abc\u05D0\u05D1\u05D2def",
            "abc (\u05D0\u05D1), \u0627\u0628!",
            "a([\u03B2)c",
            "a\u3008\u03B2\u232Ac",
            "\u05D0\u05D1\u05D2 \u0627\u0628\u062C \u0985\u09CD\u09AF",
            "A\uD83D\uDC69\u200D\uD83D\uDE80B",
            "\u3042\u30FC\u3044\u306E\u6587\u7AE0\u3001\u6F22\u5B57\u3068\u3072\u3089\u304C\u306A\u3002",
            "\u0AB8\u0AFB\u0ACD\u0AB8\u0AFB \u0915\u094D\u0937 \u0BA4\u0BAE\u0BBF\u0BB4\u0BCD",
            "\u2068abc\u2069 \u05D0\u2066xyz\u2069\u05D1",
            "\u202B\u05D0\u202C a\u0301 () \u05D1\u0301",
            "e\u0301\u0302 = mc\u00B2 \u2014 C:\\Users\\test",
            "\u05D0\u05D1 \u202A\u05D2\u202C \u05D3, \u05D4\u05D5? \u05D6!",
            "\u0627\u0644\u0633\u0644\u0627\u0645 \u0639\u0644\u064A\u0643\u0645 \u0648\u0631\u062D\u0645\u0629 \u0627\u0644\u0644\u0647",
            "\u0995\u09CD\u09B0\u09AE\u09AA\u09CD\u09B0\u09AC\u09C3\u09A6\u09CD\u09A7\u09BF\u09B8\u09CD\u0995\u09BE\u09B0",
            "1 2 3 \u05D0\u05D1 4 5 6 \u0627\u0628 7 8 9",
            "()[]{}<> \u3008\u3009\u2329\u232A",
            "\u0001\u0002\u001F \u0007 \u0008\u000B\u000C",
        )
        texts.forEachIndexed { textIndex, text ->
            val scalars = text.codePoints().toArray().toList()
            BaseDirection.entries.forEach { direction ->
                crossCheck(scalars, direction, "en", "real-world text $textIndex")
            }
        }
    }

    @Test
    fun language_tags_canonicalize_identically() {
        val acceptedTags = listOf(
            "en", "EN-us", "fr-FR", "zh-Hant", "zh-TW", "ja", "he", "ar", "hi", "th",
            "yue", "nan", "sr-RS", "de-CH-1901", "sl-rozaj-biske", "en-a-aaa", "en-x-US",
            "fr-Latn-FR", "es-419", "zh-min-nan", "art-lojban", "no-bok", "en-GB-oed",
            "x-private-one", "i-klingon", "sgn-BE-FR", "hy-Latn-IT-arevela",
            "en-variant-x-extended", "ab-cd", "de-DE-u-co-phonebk-x-foo", "sl-rozaj-biske",
            "de-a-ccc-bbb", "el-monoton", "hy-Latn", "en-boont-Scouse", "i-default",
            "i-enochian", "i-mingo",
        )
        acceptedTags.forEach { tag ->
            assertEquals(
                icuCanonicalTag(tag),
                portableCanonicalTag(tag),
                "Canonicalization disagrees for '$tag'.",
            )
        }

        val rejectedTags = listOf(
            "", "e", "en-", "-en", "en_US", "x", "en-x", "abcdefghi",
            "en--US", "en-Latn-Latn", "123", "en-US-", "ab-cd-ef-gh-ij", "en-aaa-bbb-ccc-ddd",
        )
        rejectedTags.forEach { tag ->
            assertEquals(
                icuAccepts(tag),
                portableAccepts(tag),
                "Acceptance disagrees for '$tag'.",
            )
        }
    }

    private fun crossCheck(scalars: List<Int>, direction: BaseDirection, language: String, label: String) {
        val snapshot = snapshotOf(scalars)
        val request = UnicodeAnalysisRequest(direction, language)
        val icu = ICU_ANALYZER.analyze(snapshot, request)
        val portable = PORTABLE_ANALYZER.analyze(snapshot, request)
        assertEquals(icu.graphemeClusters, portable.graphemeClusters, "$label: grapheme clusters differ.")
        assertEquals(icu.scriptLanguageRuns, portable.scriptLanguageRuns, "$label: script runs differ.")
        assertEquals(icu.logicalBidiRuns, portable.logicalBidiRuns, "$label: logical BiDi runs differ.")
        assertEquals(icu.visualBidiRuns, portable.visualBidiRuns, "$label: visual BiDi runs differ.")
    }

    private fun snapshotOf(scalars: List<Int>): TextSnapshot = snapshotOf(
        buildString { scalars.forEach { scalar -> appendCodePoint(scalar) } },
    )

    private fun snapshotOf(text: String): TextSnapshot =
        TextSnapshots.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(text.toCharArray())),
        ).snapshot

    private fun icuCanonicalTag(tag: String): String? = try {
        ULocale.Builder().setLanguageTag(tag).build().toLanguageTag()
    } catch (_: IllformedLocaleException) {
        null
    }

    private fun portableCanonicalTag(tag: String): String? = try {
        parseLanguageTag(tag).canonicalTag
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun icuAccepts(tag: String): Boolean = icuCanonicalTag(tag) != null
    private fun portableAccepts(tag: String): Boolean = portableCanonicalTag(tag) != null

    /** Every case's scalars of the extended grapheme break corpus, its ÷/× markers stripped. */
    private fun graphemeCorpusScalars(): Sequence<List<Int>> = sequence {
        val corpus = UnicodeTestEnvironment.corpus.text("/unicode/16.0.0/GraphemeBreakTest.txt")
        corpus.lineSequence().forEachIndexed { index, rawLine ->
            val source = rawLine.substringBefore('#').trim()
            if (source.isEmpty()) return@forEachIndexed
            val scalars = source.split(WHITESPACE)
                .filterNot { it == "÷" || it == "×" }
                .map { it.toInt(radix = 16) }
            check(scalars.isNotEmpty()) { "Malformed GraphemeBreakTest line ${index + 1}: $source" }
            yield(scalars)
        }
    }

    /** Every real-scalar case of the BiDi character corpus with an explicit paragraph direction. */
    private fun bidiCorpusCases(): Sequence<BidiCase> = sequence {
        val corpus = UnicodeTestEnvironment.corpus.text("/unicode/16.0.0/BidiCharacterTest.txt")
        corpus.lineSequence().forEachIndexed { index, rawLine ->
            val source = rawLine.substringBefore('#').trim()
            if (source.isEmpty()) return@forEachIndexed
            val fields = source.split(';').map(String::trim)
            check(fields.size == 5) { "Malformed BidiCharacterTest line ${index + 1}: $source" }
            if (fields[1] == AUTO_DIRECTION) return@forEachIndexed
            yield(
                BidiCase(
                    label = "BidiCharacterTest line ${index + 1}",
                    scalars = fields[0].split(WHITESPACE).map { it.toInt(radix = 16) },
                ),
            )
        }
    }

    private data class BidiCase(
        val label: String,
        val scalars: List<Int>,
    )

    private companion object {
        val ICU_ANALYZER: BoundedUnicodeAnalyzer = JvmUnicodeAnalyzer.create()
        val PORTABLE_ANALYZER: BoundedUnicodeAnalyzer = PortableUnicodeAnalyzer.create()
        val WHITESPACE: Regex = Regex("\\s+")
        const val AUTO_DIRECTION: String = "2"
    }
}
