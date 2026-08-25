package org.graphiks.kalligraphie.text.paragraph

import java.security.MessageDigest
import org.graphiks.kalligraphie.text.shaping.BasicTextSegmenter
import org.graphiks.kalligraphie.text.shaping.PinnedUnicodeDataGenerator
import org.graphiks.kalligraphie.text.shaping.PinnedUnicodeDataSetResources
import org.graphiks.kalligraphie.text.shaping.TextSegmenter
import org.graphiks.kalligraphie.text.shaping.UnicodeDataSet

public const val LINE_BREAK_ALLOWED_DIAGNOSTIC_CODE: String = "LB18"
public const val LINE_BREAK_LOCALE_REFINEMENT_UNAVAILABLE_DIAGNOSTIC_CODE: String =
    "text.paragraph.locale-break-refinement-unavailable"
public const val LINE_BREAK_DATA_UNAVAILABLE_DIAGNOSTIC_CODE: String =
    "text.paragraph.line-break-data-unavailable"

public enum class LineBreakKind {
    MANDATORY,
    ALLOWED,
    PROHIBITED,
}

public data class LineBreakOpportunity(
    public val offset: Int,
    public val kind: LineBreakKind,
    public val reason: String,
    public val leftClusterRange: IntRange?,
    public val rightClusterRange: IntRange?,
    public val leftLineBreakClass: String?,
    public val rightLineBreakClass: String?,
)

public data class LineBreakMap(
    public val inputHash: String,
    public val unicodeVersion: String,
    public val softWrap: Boolean,
    public val opportunities: List<LineBreakOpportunity>,
    public val diagnostics: List<ParagraphLayoutDiagnostic>,
)

public interface Uax14LineBreaker : LineBreaker {
    public fun analyze(paragraph: Paragraph): LineBreakMap
}

public class DefaultUax14LineBreaker(
    private val segmenter: TextSegmenter = BasicTextSegmenter(),
    private val unicodeDataSet: UnicodeDataSet? = PinnedUnicodeDataSetResources.load(),
) : Uax14LineBreaker {
    override fun analyze(paragraph: Paragraph): LineBreakMap {
        val dataSet = unicodeDataSet
        if (dataSet == null) {
            return LineBreakMap(
                inputHash = paragraph.inputHash,
                unicodeVersion = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
                softWrap = paragraph.paragraphStyle.softWrap,
                opportunities = emptyList(),
                diagnostics = listOf(
                    ParagraphLayoutDiagnostic(
                        code = LINE_BREAK_DATA_UNAVAILABLE_DIAGNOSTIC_CODE,
                        message = "Pinned Unicode line break data is unavailable.",
                        textRange = paragraph.text.indices.takeUnless { it.isEmpty() },
                        severity = "refusal",
                    ),
                ),
            )
        }

        val clusters = segmenter.segment(paragraph.text)
        val opportunities = mutableListOf<LineBreakOpportunity>()
        opportunities += LineBreakOpportunity(
            offset = 0,
            kind = LineBreakKind.PROHIBITED,
            reason = "START",
            leftClusterRange = null,
            rightClusterRange = clusters.firstOrNull(),
            leftLineBreakClass = null,
            rightLineBreakClass = clusters.firstOrNull()?.let { range -> paragraph.firstLineBreakClass(range, dataSet) },
        )
        clusters.zipWithNext().forEach { (left, right) ->
            opportunities += opportunityFor(paragraph, left, right, dataSet)
        }
        opportunities += LineBreakOpportunity(
            offset = paragraph.text.length,
            kind = LineBreakKind.MANDATORY,
            reason = "END",
            leftClusterRange = clusters.lastOrNull(),
            rightClusterRange = null,
            leftLineBreakClass = clusters.lastOrNull()?.let { range -> paragraph.lastLineBreakClass(range, dataSet) },
            rightLineBreakClass = null,
        )

        return LineBreakMap(
            inputHash = paragraph.inputHash,
            unicodeVersion = dataSet.version.value,
            softWrap = paragraph.paragraphStyle.softWrap,
            opportunities = opportunities,
            diagnostics = paragraph.localeRefinementDiagnostics(),
        )
    }

    override fun breakLines(paragraph: Paragraph, maxWidth: Float): List<IntRange> {
        require(maxWidth.isFinite() && maxWidth >= 0f) { "maxWidth must be finite and non-negative." }
        val text = paragraph.text
        if (text.isEmpty()) return emptyList()

        val clusters = segmenter.segment(text)
        if (clusters.isEmpty()) return emptyList()
        val map = analyze(paragraph)
        if (map.diagnostics.any { diagnostic -> diagnostic.severity == "refusal" }) return emptyList()
        val opportunities = map.opportunities.associateBy { opportunity -> opportunity.offset }
        val ranges = mutableListOf<IntRange>()
        var lineStartCluster = 0
        while (lineStartCluster < clusters.size) {
            var width = 0f
            var clusterIndex = lineStartCluster
            var lastAllowedOffset: Int? = null
            var emitted = false
            while (clusterIndex < clusters.size && !emitted) {
                val cluster = clusters[clusterIndex]
                val clusterText = text.substring(cluster.first, cluster.last + 1)
                if (clusterText == "\n") {
                    if (clusterIndex > lineStartCluster) {
                        ranges += lineRange(text, clusters[lineStartCluster].first, cluster.first)
                    }
                    lineStartCluster = clusterIndex + 1
                    while (lineStartCluster < clusters.size && text.substring(clusters[lineStartCluster].first, clusters[lineStartCluster].last + 1).isLineBreakSkippablePrefix()) {
                        lineStartCluster += 1
                    }
                    emitted = true
                    continue
                }

                val nextWidth = width + paragraph.lineBreakEstimatedWidth(cluster)
                val boundaryAfter = opportunities[cluster.last + 1]
                if (boundaryAfter?.kind == LineBreakKind.ALLOWED) {
                    lastAllowedOffset = boundaryAfter.offset
                }
                if (paragraph.paragraphStyle.softWrap && nextWidth > maxWidth && clusterIndex > lineStartCluster && lastAllowedOffset != null) {
                    val breakOffset = lastAllowedOffset
                    val lineStart = clusters[lineStartCluster].first
                    val lineEndExclusive = breakOffset
                    ranges += lineRange(text, lineStart, lineEndExclusive)
                    lineStartCluster = clusters.indexOfFirst { range -> range.first >= breakOffset }.takeIf { it >= 0 } ?: clusters.size
                    while (lineStartCluster < clusters.size && text.substring(clusters[lineStartCluster].first, clusters[lineStartCluster].last + 1).isLineBreakSkippablePrefix()) {
                        lineStartCluster += 1
                    }
                    emitted = true
                    continue
                }
                if (paragraph.paragraphStyle.softWrap && nextWidth > maxWidth && clusterIndex == lineStartCluster) {
                    ranges += lineRange(text, cluster.first, cluster.last + 1)
                    lineStartCluster = clusterIndex + 1
                    while (lineStartCluster < clusters.size && text.substring(clusters[lineStartCluster].first, clusters[lineStartCluster].last + 1).isLineBreakSkippablePrefix()) {
                        lineStartCluster += 1
                    }
                    emitted = true
                    continue
                }
                if (paragraph.paragraphStyle.softWrap && nextWidth > maxWidth && clusterIndex > lineStartCluster) {
                    ranges += lineRange(text, clusters[lineStartCluster].first, cluster.first)
                    lineStartCluster = clusterIndex
                    emitted = true
                    continue
                }

                width = nextWidth
                if (boundaryAfter?.kind == LineBreakKind.MANDATORY) {
                    ranges += lineRange(text, clusters[lineStartCluster].first, cluster.last + 1)
                    lineStartCluster = clusterIndex + 1
                    while (lineStartCluster < clusters.size && text.substring(clusters[lineStartCluster].first, clusters[lineStartCluster].last + 1).isLineBreakSkippablePrefix()) {
                        lineStartCluster += 1
                    }
                    emitted = true
                }
                clusterIndex += 1
            }

            if (!emitted) {
                ranges += lineRange(text, clusters[lineStartCluster].first, text.length)
                break
            }
        }
        return ranges.filter { range -> !range.isEmpty() }
    }

    public fun dumpFixtures(fixtures: List<Pair<String, Paragraph>>): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"line-breaks\",\n")
        append("  \"ownerTickets\": [\"KFONT-M8-003\"],\n")
        append("  \"unicodeVersion\": ").append(jsonString(PinnedUnicodeDataGenerator.PinnedUnicodeVersion)).append(",\n")
        append("  \"cases\": [\n")
        fixtures.forEachIndexed { index, (caseId, paragraph) ->
            append("    ").append(caseDump(caseId, paragraph))
            if (index != fixtures.lastIndex) append(",")
            append("\n")
        }
        append("  ],\n")
        append("  \"nonClaims\": [\n")
        append("    \"no-complete-target-support-claim\",\n")
        append("    \"no-complete-uax14-claim\",\n")
        append("    \"no-complete-paragraph-layout-claim\",\n")
        append("    \"no-skia-paragraph-parity-claim\"\n")
        append("  ]\n")
        append("}\n")
    }

    private fun caseDump(caseId: String, paragraph: Paragraph): String {
        val map = analyze(paragraph)
        return buildString {
            append("{\"caseId\": ")
                .append(jsonString(caseId))
                .append(", \"text\": ")
                .append(jsonString(paragraph.text))
                .append(", \"inputHash\": ")
                .append(jsonString(map.inputHash))
                .append(", \"softWrap\": ")
                .append(map.softWrap)
                .append(", \"opportunities\": ")
                .append(map.opportunities.joinToString(prefix = "[", postfix = "]") { opportunity -> opportunity.toDumpJson() })
                .append(", \"diagnostics\": ")
                .append(map.diagnostics.joinToString(prefix = "[", postfix = "]") { diagnostic -> diagnostic.toDumpJson() })
                .append("}")
        }
    }

    private fun opportunityFor(
        paragraph: Paragraph,
        left: IntRange,
        right: IntRange,
        unicodeDataSet: UnicodeDataSet,
    ): LineBreakOpportunity {
        val leftClass = paragraph.lastLineBreakClass(left, unicodeDataSet)
        val rightClass = paragraph.firstLineBreakClass(right, unicodeDataSet)
        val leftText = paragraph.text.substring(left.first, left.last + 1)
        val kindAndReason = when {
            leftText.contains('\n') -> LineBreakKind.MANDATORY to "BK"
            !paragraph.paragraphStyle.softWrap -> LineBreakKind.PROHIBITED to "soft-wrap-disabled"
            leftText.all { char -> char == ' ' || char == '\t' } -> LineBreakKind.ALLOWED to "LB18"
            leftClass in EAST_ASIAN_BREAK_CLASSES && rightClass in EAST_ASIAN_BREAK_CLASSES -> LineBreakKind.ALLOWED to "LB31"
            paragraph.isCjkLikeCluster(left) && paragraph.isCjkLikeCluster(right) -> LineBreakKind.ALLOWED to "LB31"
            else -> LineBreakKind.PROHIBITED to "direct"
        }
        return LineBreakOpportunity(
            offset = right.first,
            kind = kindAndReason.first,
            reason = kindAndReason.second,
            leftClusterRange = left,
            rightClusterRange = right,
            leftLineBreakClass = leftClass,
            rightLineBreakClass = rightClass,
        )
    }
}

private val EAST_ASIAN_BREAK_CLASSES: Set<String> = setOf("CJ", "H2", "H3", "ID", "JL", "JT", "JV")
private val DICTIONARY_REFINEMENT_LOCALES: Set<String> = setOf("km", "lo", "my", "th")
private val CJK_LIKE_CODE_POINT_RANGES: List<IntRange> = listOf(
    0x3040..0x30FF,
    0x3400..0x4DBF,
    0x4E00..0x9FFF,
    0xF900..0xFAFF,
)

private fun Paragraph.localeRefinementDiagnostics(): List<ParagraphLayoutDiagnostic> {
    val locales = buildList {
        paragraphStyle.defaultLocale?.let(::add)
        textStyles.values.mapNotNullTo(this) { style -> style.locale }
    }.map { locale -> locale.lowercase() }
    if (locales.none { locale -> DICTIONARY_REFINEMENT_LOCALES.any(locale::startsWith) }) return emptyList()
    return listOf(
        ParagraphLayoutDiagnostic(
            code = LINE_BREAK_LOCALE_REFINEMENT_UNAVAILABLE_DIAGNOSTIC_CODE,
            message = "Locale-specific line break refinement is unavailable for locale '${locales.first { locale -> DICTIONARY_REFINEMENT_LOCALES.any(locale::startsWith) }}'; base UAX #14 classes remain in effect.",
            textRange = text.indices.takeUnless { it.isEmpty() },
            severity = "diagnostic",
        ),
    )
}

private fun Paragraph.isCjkLikeCluster(range: IntRange): Boolean {
    var index = range.first
    while (index <= range.last) {
        val codePoint = text.codePointAt(index)
        if (CJK_LIKE_CODE_POINT_RANGES.none { codePoint in it }) return false
        index += Character.charCount(codePoint)
    }
    return true
}

private fun Paragraph.firstLineBreakClass(range: IntRange, unicodeDataSet: UnicodeDataSet): String {
    var index = range.first
    while (index <= range.last) {
        val codePoint = text.codePointAt(index)
        return unicodeDataSet.lineBreak.valueAt(codePoint)
    }
    return unicodeDataSet.lineBreak.defaultValue
}

private fun Paragraph.lineBreakEstimatedWidth(range: IntRange): Float =
    placeholders.entries.firstOrNull { (placeholderRange) -> placeholderRange.first <= range.last && range.first <= placeholderRange.last }?.value?.width
        ?: textStyles.entries.firstOrNull { (styleRange) -> range.first in styleRange }?.value?.fontSize
        ?: TextStyle().fontSize

private fun Paragraph.lastLineBreakClass(range: IntRange, unicodeDataSet: UnicodeDataSet): String {
    var index = range.last
    while (index >= range.first) {
        val codePoint = Character.codePointBefore(text, index + 1)
        return unicodeDataSet.lineBreak.valueAt(codePoint)
    }
    return unicodeDataSet.lineBreak.defaultValue
}

private fun lineRange(text: String, startInclusive: Int, endExclusive: Int): IntRange {
    var trimmedEndExclusive = endExclusive
    while (trimmedEndExclusive > startInclusive && text[trimmedEndExclusive - 1] == ' ') {
        trimmedEndExclusive -= 1
    }
    return startInclusive until trimmedEndExclusive
}

private fun String.isLineBreakSkippablePrefix(): Boolean =
    this == " " || this == "\t" || this == "\n"

private fun LineBreakOpportunity.toDumpJson(): String = buildString {
    append("{\"offset\": ")
        .append(offset)
        .append(", \"kind\": ")
        .append(jsonString(kind.name.lowercase()))
        .append(", \"reason\": ")
        .append(jsonString(reason))
        .append(", \"leftClusterRange\": ")
        .append(leftClusterRange?.toDumpJson() ?: "null")
        .append(", \"rightClusterRange\": ")
        .append(rightClusterRange?.toDumpJson() ?: "null")
        .append(", \"leftLineBreakClass\": ")
        .append(leftLineBreakClass?.let(::jsonString) ?: "null")
        .append(", \"rightLineBreakClass\": ")
        .append(rightLineBreakClass?.let(::jsonString) ?: "null")
        .append("}")
}

private fun ParagraphLayoutDiagnostic.toDumpJson(): String = buildString {
    append("{\"code\": ")
        .append(jsonString(code))
        .append(", \"message\": ")
        .append(jsonString(message))
        .append(", \"textRange\": ")
        .append(textRange?.toDumpJson() ?: "null")
        .append(", \"severity\": ")
        .append(jsonString(severity))
        .append("}")
}

private fun IntRange.toDumpJson(): String = jsonString("$first..$last")

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }

private fun String.sourceHash(): String = toByteArray(Charsets.UTF_8).sha256Hex()

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char.code in 0x20..0x7E) {
                    append(char)
                } else {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                }
            }
        }
    }
    append('"')
}
