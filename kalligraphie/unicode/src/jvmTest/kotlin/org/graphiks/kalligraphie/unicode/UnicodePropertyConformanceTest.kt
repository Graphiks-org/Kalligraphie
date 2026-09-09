package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.ScriptLanguageRun
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest

class UnicodePropertyConformanceTest {
    @Test
    fun every_unicode_16_script_value_is_consumed_by_public_script_resolution_in_bounded_chunks() {
        val scriptExtensions = scriptExtensionValues().mapTo(mutableSetOf(), ScriptExtensionValue::scalar)
        val pairedBrackets = bidiBracketValues().mapTo(mutableSetOf(), BidiBracketValue::scalar)
        var executed = 0

        fun verifyRange(range: IntRange, expectedScript: String) {
            val chunk = ArrayList<Int>(CHUNK_SIZE)
            range.forEach { scalar ->
                if (scalar !in SURROGATES && scalar !in scriptExtensions && scalar !in pairedBrackets) {
                    chunk += scalar
                    if (chunk.size == CHUNK_SIZE) {
                        verifyScripts(chunk, expectedScript)
                        executed += chunk.size
                        chunk.clear()
                    }
                }
            }
            if (chunk.isNotEmpty()) {
                verifyScripts(chunk, expectedScript)
                executed += chunk.size
            }
        }

        var nextScalar = 0
        scriptRanges().forEach { range ->
            if (nextScalar < range.scalars.first) verifyRange(nextScalar until range.scalars.first, UNKNOWN_SCRIPT)
            verifyRange(range.scalars, SCRIPT_ALIASES.getValue(range.script))
            nextScalar = range.scalars.last + 1
        }
        if (nextScalar <= MAX_UNICODE_SCALAR) verifyRange(nextScalar..MAX_UNICODE_SCALAR, UNKNOWN_SCRIPT)

        println("Unicode 16.0 Script scalars executed in chunks of at most $CHUNK_SIZE: $executed")
        println("Unicode surrogate code points excluded because TextSnapshot accepts Unicode scalar values only: ${SURROGATES.count()}")
    }

    @Test
    fun every_unicode_16_script_extensions_candidate_controls_public_resolution() {
        val ranges = scriptRanges()
        val values = scriptExtensionValues()
        val representatives = scriptRepresentatives(ranges)
        val pairedBrackets = bidiBracketValues().mapTo(mutableSetOf(), BidiBracketValue::scalar)
        val accepted = mutableMapOf<String, MutableList<Int>>()
        val rejected = mutableMapOf<String, MutableList<ExpectedScript>>()

        values.forEach { value ->
            value.candidates.forEach { candidate -> accepted.getOrPut(candidate, ::mutableListOf) += value.scalar }
            val primary = primaryScript(value.scalar, ranges)
            val fallback = when {
                "Latn" in value.candidates -> "Latn"
                primary in value.candidates -> primary
                else -> value.candidates.minOrNull()!!
            }
            representatives.keys.forEach { context ->
                if (value.scalar !in pairedBrackets && context !in value.candidates) {
                    rejected.getOrPut(context, ::mutableListOf) += ExpectedScript(value.scalar, fallback)
                }
            }
        }

        var acceptedContexts = 0
        accepted.forEach { (candidate, scalars) ->
            scalars.chunked(CHUNK_SIZE).forEach { chunk ->
                val representative = representatives.getValue(candidate)
                verifyScripts(chunk.flatMap { listOf(representative, it, representative) }, candidate)
                acceptedContexts += chunk.size
            }
        }

        var rejectedContexts = 0
        rejected.forEach { (context, expectations) ->
            expectations.chunked(CHUNK_SIZE).forEach { chunk ->
                val representative = representatives.getValue(context)
                val scalars = chunk.flatMap { listOf(representative, it.scalar, representative) }
                val actual = resolvedScripts(snapshotOf(scalars))
                chunk.forEachIndexed { index, expected ->
                    assertEquals(expected.script, actual[index * 3 + 1], "U+${expected.scalar.toString(16).uppercase()} in $context context")
                }
                rejectedContexts += chunk.size
            }
        }

        println("Unicode 16.0 Script_Extensions accepted candidate contexts executed: $acceptedContexts")
        println("Unicode 16.0 Script_Extensions non-candidate contexts executed: $rejectedContexts")
    }

    @Test
    fun every_unicode_16_bidi_bracket_mapping_and_type_controls_public_paired_punctuation() {
        var executed = 0
        bidiBracketValues().forEach { bracket ->
            val (opening, closing) = when (bracket.type) {
                "o" -> bracket.scalar to bracket.paired
                "c" -> bracket.paired to bracket.scalar
                else -> error("Unsupported Bidi_Paired_Bracket_Type '${bracket.type}'.")
            }
            val scalars = listOf(LATIN_REPRESENTATIVE, opening, GREEK_REPRESENTATIVE, closing, LATIN_REPRESENTATIVE)

            assertEquals(
                listOf("Latn", "Latn", "Grek", "Latn", "Latn"),
                resolvedScripts(snapshotOf(scalars)),
                "BidiBrackets line ${bracket.lineNumber}: ${bracket.source}",
            )
            executed += 1
        }

        println("Unicode 16.0 BidiBrackets entries executed: $executed")
    }

    private fun verifyScripts(scalars: List<Int>, expectedScript: String) {
        assertEquals(
            List(scalars.size) { expectedScript },
            resolvedScripts(snapshotOf(scalars)),
            "Script resolution for U+${scalars.first().toString(16).uppercase()}..U+${scalars.last().toString(16).uppercase()}",
        )
    }

    private fun resolvedScripts(snapshot: TextSnapshot): List<String> {
        val runs = analyzer.analyze(snapshot, REQUEST).scriptLanguageRuns
        val boundaries = (0..snapshot.scalars.size).associateBy(snapshot::textIndexAtScalarBoundary)
        return expandScripts(runs, boundaries)
    }

    private fun expandScripts(runs: List<ScriptLanguageRun>, boundaries: Map<TextIndex, Int>): List<String> = buildList {
        runs.forEach { run ->
            repeat(boundaries.getValue(run.range.endExclusive) - boundaries.getValue(run.range.start)) {
                add(run.script)
            }
        }
    }

    private fun scriptRepresentatives(ranges: List<ScriptRange>): Map<String, Int> = buildMap {
        ranges.forEach { range ->
            val alias = SCRIPT_ALIASES.getValue(range.script)
            if (alias !in setOf("Zyyy", "Zinh", UNKNOWN_SCRIPT) && alias !in this) {
                val representative = range.scalars.firstOrNull { it !in SURROGATES }
                if (representative != null) put(alias, representative)
            }
        }
    }

    private fun primaryScript(scalar: Int, ranges: List<ScriptRange>): String {
        var lower = 0
        var upper = ranges.size - 1
        while (lower <= upper) {
            val middle = (lower + upper) ushr 1
            val range = ranges[middle]
            when {
                scalar < range.scalars.first -> upper = middle - 1
                scalar > range.scalars.last -> lower = middle + 1
                else -> return SCRIPT_ALIASES.getValue(range.script)
            }
        }
        return UNKNOWN_SCRIPT
    }

    private fun scriptRanges(): List<ScriptRange> = dataLines("Scripts.txt")
        .map { line ->
            val fields = line.source.split(';').map(String::trim)
            ScriptRange(parseRange(fields[0]), fields[1])
        }
        .sortedBy { it.scalars.first }
        .toList()

    private fun scriptExtensionValues(): List<ScriptExtensionValue> = buildList {
        dataLines("ScriptExtensions.txt").forEach { line ->
            val fields = line.source.split(';').map(String::trim)
            val candidates = fields[1].split(WHITESPACE).toSet()
            parseRange(fields[0]).forEach { scalar -> add(ScriptExtensionValue(scalar, candidates)) }
        }
    }

    private fun bidiBracketValues(): List<BidiBracketValue> = dataLines("BidiBrackets.txt").map { line ->
        val fields = line.source.split(';').map(String::trim)
        BidiBracketValue(line.lineNumber, line.source, fields[0].toInt(16), fields[1].toInt(16), fields[2])
    }.toList()

    private fun dataLines(fileName: String): Sequence<DataLine> = sequence {
        val corpus = checkNotNull(javaClass.getResourceAsStream("/unicode/16.0.0/$fileName")) {
            "The checked-in Unicode 16.0 $fileName data is missing."
        }
        corpus.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                val source = rawLine.substringBefore('#').trim()
                if (source.isNotEmpty() && !source.startsWith('@')) yield(DataLine(index + 1, source))
            }
        }
    }

    private fun parseRange(source: String): IntRange {
        val limits = source.split("..", limit = 2)
        val start = limits[0].toInt(16)
        return start..(limits.getOrNull(1)?.toInt(16) ?: start)
    }

    private fun snapshotOf(scalars: List<Int>): TextSnapshot = TextSnapshots.decodeUtf16(
        TextVersion.create(),
        listOf(TextSlice.Utf16(buildString { scalars.forEach(::appendCodePoint) }.toCharArray())),
    ).snapshot

    private data class DataLine(val lineNumber: Int, val source: String)
    private data class ScriptRange(val scalars: IntRange, val script: String)
    private data class ScriptExtensionValue(val scalar: Int, val candidates: Set<String>)
    private data class ExpectedScript(val scalar: Int, val script: String)
    private data class BidiBracketValue(
        val lineNumber: Int,
        val source: String,
        val scalar: Int,
        val paired: Int,
        val type: String,
    )

    private companion object {
        val analyzer: UnicodeAnalyzer = JvmUnicodeAnalyzer.create()
        val REQUEST = UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, "en")
        val WHITESPACE = Regex("\\s+")
        val SURROGATES = 0xD800..0xDFFF
        const val CHUNK_SIZE = 2_048
        const val MAX_UNICODE_SCALAR = 0x10FFFF
        const val UNKNOWN_SCRIPT = "Zzzz"
        const val LATIN_REPRESENTATIVE = 0x0061
        const val GREEK_REPRESENTATIVE = 0x03B2

        val SCRIPT_ALIASES = mapOf(
            "Common" to "Zyyy", "Inherited" to "Zinh", "Adlam" to "Adlm", "Ahom" to "Ahom",
            "Anatolian_Hieroglyphs" to "Hluw", "Arabic" to "Arab", "Armenian" to "Armn", "Avestan" to "Avst",
            "Balinese" to "Bali", "Bamum" to "Bamu", "Bassa_Vah" to "Bass", "Batak" to "Batk",
            "Bengali" to "Beng", "Bhaiksuki" to "Bhks", "Bopomofo" to "Bopo", "Brahmi" to "Brah",
            "Braille" to "Brai", "Buginese" to "Bugi", "Buhid" to "Buhd", "Canadian_Aboriginal" to "Cans",
            "Carian" to "Cari", "Caucasian_Albanian" to "Aghb", "Chakma" to "Cakm", "Cham" to "Cham",
            "Cherokee" to "Cher", "Chorasmian" to "Chrs", "Coptic" to "Copt", "Cuneiform" to "Xsux",
            "Cypriot" to "Cprt", "Cypro_Minoan" to "Cpmn", "Cyrillic" to "Cyrl", "Deseret" to "Dsrt",
            "Devanagari" to "Deva", "Dives_Akuru" to "Diak", "Dogra" to "Dogr", "Duployan" to "Dupl",
            "Egyptian_Hieroglyphs" to "Egyp", "Elbasan" to "Elba", "Elymaic" to "Elym", "Ethiopic" to "Ethi",
            "Garay" to "Gara", "Georgian" to "Geor", "Glagolitic" to "Glag", "Gothic" to "Goth",
            "Grantha" to "Gran", "Greek" to "Grek", "Gujarati" to "Gujr", "Gunjala_Gondi" to "Gong",
            "Gurmukhi" to "Guru", "Gurung_Khema" to "Gukh", "Han" to "Hani", "Hangul" to "Hang",
            "Hanifi_Rohingya" to "Rohg", "Hanunoo" to "Hano", "Hatran" to "Hatr", "Hebrew" to "Hebr",
            "Hiragana" to "Hira", "Imperial_Aramaic" to "Armi", "Inscriptional_Pahlavi" to "Phli",
            "Inscriptional_Parthian" to "Prti", "Javanese" to "Java", "Kaithi" to "Kthi", "Kannada" to "Knda",
            "Katakana" to "Kana", "Kawi" to "Kawi", "Kayah_Li" to "Kali", "Kharoshthi" to "Khar",
            "Khitan_Small_Script" to "Kits", "Khmer" to "Khmr", "Khojki" to "Khoj", "Khudawadi" to "Sind",
            "Kirat_Rai" to "Krai", "Lao" to "Laoo", "Latin" to "Latn", "Lepcha" to "Lepc",
            "Limbu" to "Limb", "Linear_A" to "Lina", "Linear_B" to "Linb", "Lisu" to "Lisu",
            "Lycian" to "Lyci", "Lydian" to "Lydi", "Mahajani" to "Mahj", "Makasar" to "Maka",
            "Malayalam" to "Mlym", "Mandaic" to "Mand", "Manichaean" to "Mani", "Marchen" to "Marc",
            "Masaram_Gondi" to "Gonm", "Medefaidrin" to "Medf", "Meetei_Mayek" to "Mtei",
            "Mende_Kikakui" to "Mend", "Meroitic_Cursive" to "Merc", "Meroitic_Hieroglyphs" to "Mero",
            "Miao" to "Plrd", "Modi" to "Modi", "Mongolian" to "Mong", "Mro" to "Mroo",
            "Multani" to "Mult", "Myanmar" to "Mymr", "Nabataean" to "Nbat", "Nag_Mundari" to "Nagm",
            "Nandinagari" to "Nand", "New_Tai_Lue" to "Talu", "Newa" to "Newa", "Nko" to "Nkoo",
            "Nushu" to "Nshu", "Nyiakeng_Puachue_Hmong" to "Hmnp", "Ogham" to "Ogam", "Ol_Chiki" to "Olck",
            "Ol_Onal" to "Onao", "Old_Hungarian" to "Hung", "Old_Italic" to "Ital",
            "Old_North_Arabian" to "Narb", "Old_Permic" to "Perm", "Old_Persian" to "Xpeo",
            "Old_Sogdian" to "Sogo", "Old_South_Arabian" to "Sarb", "Old_Turkic" to "Orkh",
            "Old_Uyghur" to "Ougr", "Oriya" to "Orya", "Osage" to "Osge", "Osmanya" to "Osma",
            "Pahawh_Hmong" to "Hmng", "Palmyrene" to "Palm", "Pau_Cin_Hau" to "Pauc", "Phags_Pa" to "Phag",
            "Phoenician" to "Phnx", "Psalter_Pahlavi" to "Phlp", "Rejang" to "Rjng", "Runic" to "Runr",
            "Samaritan" to "Samr", "Saurashtra" to "Saur", "Sharada" to "Shrd", "Shavian" to "Shaw",
            "Siddham" to "Sidd", "SignWriting" to "Sgnw", "Sinhala" to "Sinh", "Sogdian" to "Sogd",
            "Sora_Sompeng" to "Sora", "Soyombo" to "Soyo", "Sundanese" to "Sund", "Sunuwar" to "Sunu",
            "Syloti_Nagri" to "Sylo", "Syriac" to "Syrc", "Tagalog" to "Tglg", "Tagbanwa" to "Tagb",
            "Tai_Le" to "Tale", "Tai_Tham" to "Lana", "Tai_Viet" to "Tavt", "Takri" to "Takr",
            "Tamil" to "Taml", "Tangsa" to "Tnsa", "Tangut" to "Tang", "Telugu" to "Telu",
            "Thaana" to "Thaa", "Thai" to "Thai", "Tibetan" to "Tibt", "Tifinagh" to "Tfng",
            "Tirhuta" to "Tirh", "Todhri" to "Todr", "Toto" to "Toto", "Tulu_Tigalari" to "Tutg",
            "Ugaritic" to "Ugar", "Vai" to "Vaii", "Vithkuqi" to "Vith", "Wancho" to "Wcho",
            "Warang_Citi" to "Wara", "Yezidi" to "Yezi", "Yi" to "Yiii", "Zanabazar_Square" to "Zanb",
        )
    }
}
