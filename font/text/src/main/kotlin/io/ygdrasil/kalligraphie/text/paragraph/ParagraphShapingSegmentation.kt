package io.ygdrasil.kalligraphie.text.paragraph

import io.ygdrasil.kalligraphie.font.FallbackRequest
import io.ygdrasil.kalligraphie.font.FontResolver
import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.font.TypefaceVariationCoordinate
import io.ygdrasil.kalligraphie.text.shaping.BasicBidiResolver
import io.ygdrasil.kalligraphie.text.shaping.BasicScriptItemizer
import io.ygdrasil.kalligraphie.text.shaping.BasicTextSegmenter
import io.ygdrasil.kalligraphie.text.shaping.BidiResolver
import io.ygdrasil.kalligraphie.text.shaping.ScriptItemizer
import io.ygdrasil.kalligraphie.text.shaping.ShapingRequest
import io.ygdrasil.kalligraphie.text.shaping.TextSegmenter

public const val PARAGRAPH_CLUSTER_BOUNDARY_VIOLATION_DIAGNOSTIC_CODE: String =
    "text.paragraph.cluster-boundary-violation"

public const val PARAGRAPH_FALLBACK_UNRESOLVED_DIAGNOSTIC_CODE: String =
    "text.paragraph.fallback-unresolved"

public data class ParagraphShapingRequest(
    public val segmentId: String,
    public val textRange: IntRange,
    public val typefaceId: TypefaceID?,
    public val style: TextStyle,
    public val script: String,
    public val bidiLevel: Int,
)

public data class ParagraphShapingPlan(
    public val requests: List<ParagraphShapingRequest>,
    public val placeholderRanges: List<IntRange>,
    public val diagnostics: List<ParagraphLayoutDiagnostic>,
) {
    public fun dump(paragraph: Paragraph): String = buildString {
        append("{\n")
        append("  \"schema\": \"kanvas.paragraph.shaping-requests.v1\",\n")
        append("  \"text\": ").append(paragraphShapingJsonString(paragraph.text)).append(",\n")
        append("  \"paragraphDirection\": ")
            .append(paragraphShapingJsonString(paragraph.paragraphStyle.textDirection.dumpToken()))
            .append(",\n")
        append("  \"requests\": ")
        appendParagraphShapingArray(
            values = requests,
            entryIndent = "    ",
            closingIndent = "  ",
        ) { request ->
            request.toDumpJson(paragraph)
        }
        append(",\n")
        append("  \"placeholderRanges\": ")
        appendParagraphShapingArray(
            values = placeholderRanges,
            entryIndent = "    ",
            closingIndent = "  ",
        ) { range ->
            paragraphShapingJsonString(range.toDumpLabel())
        }
        append(",\n")
        append("  \"diagnostics\": ")
        appendParagraphShapingArray(
            values = diagnostics,
            entryIndent = "    ",
            closingIndent = "  ",
        ) { diagnostic ->
            diagnostic.toDumpJson()
        }
        append("\n")
        append("}\n")
    }
}

public interface ParagraphShapingSegmenter {
    public fun segment(paragraph: Paragraph): ParagraphShapingPlan
}

public class DefaultParagraphShapingSegmenter(
    private val segmenter: TextSegmenter = BasicTextSegmenter(),
    private val scriptItemizer: ScriptItemizer = BasicScriptItemizer(),
    private val bidiResolver: BidiResolver = BasicBidiResolver(),
    @Suppress("UNUSED_PARAMETER")
    private val fontResolver: FontResolver? = null,
) : ParagraphShapingSegmenter {
    override fun segment(paragraph: Paragraph): ParagraphShapingPlan {
        if (paragraph.text.isEmpty()) {
            return ParagraphShapingPlan(
                requests = emptyList(),
                placeholderRanges = emptyList(),
                diagnostics = emptyList(),
            )
        }

        val request = ShapingRequest(
            text = paragraph.text,
            textRange = paragraph.text.indices,
            fontSize = paragraph.textStyles.values.firstOrNull()?.fontSize ?: 12f,
            paragraphDirection = paragraph.paragraphStyle.textDirection.legacyValue,
        )
        val scriptRuns = scriptItemizer.itemize(request)
        val bidiRuns = bidiResolver.resolve(request)

        val diagnostics = mutableListOf<ParagraphLayoutDiagnostic>()
        val placeholderRanges = mutableListOf<IntRange>()
        val pending = mutableListOf<PendingParagraphRequest>()

        segmenter.segment(paragraph.text).forEach { clusterRange ->
            if (paragraph.placeholders.keys.any { placeholderRange -> placeholderRange.overlaps(clusterRange) }) {
                placeholderRanges += clusterRange
                return@forEach
            }
            val leadingStyle = paragraph.styleAtIndex(clusterRange.first)
            if (clusterUsesMultipleStyles(paragraph, clusterRange, leadingStyle)) {
                diagnostics += ParagraphLayoutDiagnostic(
                    code = PARAGRAPH_CLUSTER_BOUNDARY_VIOLATION_DIAGNOSTIC_CODE,
                    message = "Style boundaries must align to grapheme cluster coverage; widening to the leading style range.",
                    textRange = clusterRange,
                    severity = "diagnostic",
                )
            }
            pending += PendingParagraphRequest(
                textRange = clusterRange,
                style = leadingStyle,
                script = scriptRuns.firstOrNull { run -> clusterRange.first in run.textRange }?.script ?: "Zyyy",
                bidiLevel = bidiRuns.firstOrNull { run -> clusterRange.first in run.textRange }?.level ?: 0,
            )
        }

        val requests = pending.coalesce()
            .flatMap { requestCandidate -> resolveTypefaceRuns(paragraph, requestCandidate, diagnostics) }
            .mapIndexed { index, requestCandidate ->
                ParagraphShapingRequest(
                    segmentId = "seg-${index.toString().padStart(3, '0')}",
                    textRange = requestCandidate.textRange,
                    typefaceId = requestCandidate.typefaceId,
                    style = requestCandidate.style,
                    script = requestCandidate.script,
                    bidiLevel = requestCandidate.bidiLevel,
                )
            }

        return ParagraphShapingPlan(
            requests = requests,
            placeholderRanges = placeholderRanges,
            diagnostics = diagnostics.sortedWith(compareBy<ParagraphLayoutDiagnostic> { it.textRange?.first ?: Int.MAX_VALUE }.thenBy { it.code }),
        )
    }

    private fun resolveTypefaceRuns(
        paragraph: Paragraph,
        requestCandidate: PendingParagraphRequest,
        diagnostics: MutableList<ParagraphLayoutDiagnostic>,
    ): List<PendingParagraphRequest> {
        val resolver = fontResolver ?: return listOf(requestCandidate.copy(typefaceId = requestCandidate.style.typefaceId))
        val requestText = paragraph.text.substring(requestCandidate.textRange.first, requestCandidate.textRange.last + 1)
        val resolvedRuns = resolver.resolve(
            FallbackRequest(
                text = requestText,
                locale = requestCandidate.style.locale ?: paragraph.paragraphStyle.defaultLocale,
                preferredFamilies = requestCandidate.style.fontFamilies,
                style = requestCandidate.style.fontStyle,
                variationCoordinates = requestCandidate.style.variationCoordinates.toTypefaceVariationCoordinates(),
            ),
        )
        if (resolvedRuns.isEmpty()) {
            diagnostics += ParagraphLayoutDiagnostic(
                code = PARAGRAPH_FALLBACK_UNRESOLVED_DIAGNOSTIC_CODE,
                message = "No fallback typeface resolved for paragraph segment ${requestCandidate.textRange.first}..${requestCandidate.textRange.last}.",
                textRange = requestCandidate.textRange,
                severity = "refusal",
            )
            return emptyList()
        }
        return resolvedRuns.map { run ->
            requestCandidate.copy(
                textRange = (requestCandidate.textRange.first + run.start)..(requestCandidate.textRange.first + run.end - 1),
                typefaceId = run.face.typeface.id,
            )
        }
    }
}

private data class PendingParagraphRequest(
    val textRange: IntRange,
    val typefaceId: TypefaceID? = null,
    val style: TextStyle,
    val script: String,
    val bidiLevel: Int,
)

private fun List<PendingParagraphRequest>.coalesce(): List<PendingParagraphRequest> {
    if (isEmpty()) return emptyList()
    val coalesced = mutableListOf(first())
    drop(1).forEach { current ->
        val previous = coalesced.last()
        if (
            previous.textRange.last + 1 == current.textRange.first &&
            previous.script == current.script &&
            previous.bidiLevel == current.bidiLevel &&
            previous.style.sameShapingFactsAs(current.style)
        ) {
            coalesced[coalesced.lastIndex] = previous.copy(textRange = previous.textRange.first..current.textRange.last)
        } else {
            coalesced += current
        }
    }
    return coalesced
}

private fun clusterUsesMultipleStyles(
    paragraph: Paragraph,
    clusterRange: IntRange,
    leadingStyle: TextStyle,
): Boolean =
    (clusterRange.first..clusterRange.last).any { index -> paragraph.styleAtIndex(index) != leadingStyle }

private fun Paragraph.styleAtIndex(index: Int): TextStyle =
    textStyles.entries.firstOrNull { (range) -> index in range }?.value ?: TextStyle()

private fun IntRange.overlaps(other: IntRange): Boolean =
    first <= other.last && other.first <= last

private fun TextStyle.sameShapingFactsAs(other: TextStyle): Boolean =
    fontFamilies == other.fontFamilies &&
        fallbackPreference == other.fallbackPreference &&
        typefaceId == other.typefaceId &&
        fontSize == other.fontSize &&
        fontStyle == other.fontStyle &&
        syntheticStylePolicy == other.syntheticStylePolicy &&
        locale == other.locale &&
        scriptHint == other.scriptHint &&
        features == other.features &&
        variationCoordinates == other.variationCoordinates &&
        palette == other.palette &&
        letterSpacing == other.letterSpacing &&
        wordSpacing == other.wordSpacing

private fun Map<String, Float>.toTypefaceVariationCoordinates(): List<TypefaceVariationCoordinate> =
    entries.sortedBy { (axisTag) -> axisTag }.map { (axisTag, value) ->
        TypefaceVariationCoordinate(axisTag = axisTag, value = value.toDouble())
    }

private fun ParagraphShapingRequest.toDumpJson(paragraph: Paragraph): String = buildString {
    append("{\"segmentId\": ")
        .append(paragraphShapingJsonString(segmentId))
        .append(", \"textRange\": ")
        .append(paragraphShapingJsonString(textRange.toDumpLabel()))
        .append(", \"text\": ")
        .append(paragraphShapingJsonString(paragraph.text.substring(textRange.first, textRange.last + 1)))
        .append(", \"fontFamilies\": ")
        .append(style.fontFamilies.toDumpJsonStringArray())
        .append(", \"fallbackPreference\": ")
        .append(paragraphShapingJsonString(style.fallbackPreference.dumpToken()))
        .append(", \"typefaceId\": ")
        .append(typefaceId?.value?.toString()?.let(::paragraphShapingJsonString) ?: "null")
        .append(", \"fontSize\": ")
        .append(paragraphShapingJsonFloat(style.fontSize))
        .append(", \"locale\": ")
        .append(style.locale?.let(::paragraphShapingJsonString) ?: "null")
        .append(", \"features\": ")
        .append(style.features.toFeatureDumpJson())
        .append(", \"variationCoordinates\": ")
        .append(style.variationCoordinates.toVariationDumpJson())
        .append(", \"script\": ")
        .append(paragraphShapingJsonString(script))
        .append(", \"bidiLevel\": ")
        .append(bidiLevel)
        .append("}")
}

private fun ParagraphLayoutDiagnostic.toDumpJson(): String = buildString {
    append("{\"code\": ")
        .append(paragraphShapingJsonString(code))
        .append(", \"message\": ")
        .append(paragraphShapingJsonString(message))
        .append(", \"textRange\": ")
        .append(textRange?.toDumpLabel()?.let(::paragraphShapingJsonString) ?: "null")
        .append(", \"severity\": ")
        .append(paragraphShapingJsonString(severity))
        .append("}")
}

private fun Map<String, Int>.toFeatureDumpJson(): String =
    entries.sortedBy { (tag) -> tag }.joinToString(prefix = "[", postfix = "]") { (tag, value) ->
        "{\"tag\": ${paragraphShapingJsonString(tag)}, \"value\": $value}"
    }

private fun Map<String, Float>.toVariationDumpJson(): String =
    entries.sortedBy { (axisTag) -> axisTag }.joinToString(prefix = "[", postfix = "]") { (axisTag, value) ->
        "{\"axisTag\": ${paragraphShapingJsonString(axisTag)}, \"value\": ${paragraphShapingJsonFloat(value)}}"
    }

private fun List<String>.toDumpJsonStringArray(): String =
    joinToString(prefix = "[", postfix = "]") { value -> paragraphShapingJsonString(value) }

private fun FallbackPreference.dumpToken(): String =
    when (this) {
        FallbackPreference.SYSTEM_DEFAULT -> "system-default"
        FallbackPreference.PREFER_DECLARED_FAMILIES -> "prefer-declared-families"
        FallbackPreference.PREFER_EXACT_TYPEFACE -> "prefer-exact-typeface"
    }

private fun TextDirection.dumpToken(): String =
    when (this) {
        TextDirection.AUTO -> "auto"
        TextDirection.LEFT_TO_RIGHT -> "ltr"
        TextDirection.RIGHT_TO_LEFT -> "rtl"
    }

private fun IntRange.toDumpLabel(): String = "$first..$last"

private fun <T> StringBuilder.appendParagraphShapingArray(
    values: List<T>,
    entryIndent: String,
    closingIndent: String,
    encode: (T) -> String,
) {
    if (values.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    values.forEachIndexed { index, value ->
        append(entryIndent).append(encode(value))
        if (index != values.lastIndex) append(",")
        append("\n")
    }
    append(closingIndent).append("]")
}

private fun paragraphShapingJsonFloat(value: Float): String =
    if (value.isFinite()) value.toString() else paragraphShapingJsonString(value.toString())

private fun paragraphShapingJsonString(value: String): String = buildString {
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
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                }
            }
        }
    }
    append('"')
}
