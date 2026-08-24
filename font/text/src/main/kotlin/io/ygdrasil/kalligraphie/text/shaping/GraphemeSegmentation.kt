package io.ygdrasil.kalligraphie.text.shaping

import java.security.MessageDigest

public const val TEXT_UNICODE_INVALID_SCALAR_DIAGNOSTIC_CODE: String = "text.unicode.invalid-scalar"
public const val TEXT_UNICODE_GRAPHEME_RULE_UNSUPPORTED_DIAGNOSTIC_CODE: String =
    "text.unicode.grapheme-rule-unsupported"
public const val TEXT_UNICODE_CLUSTER_BOUNDARY_INVALID_DIAGNOSTIC_CODE: String =
    "text.unicode.cluster-boundary-invalid"

public data class GraphemeCluster(
    public val clusterIndex: Int,
    public val utf16Range: IntRange,
    public val codePointRange: IntRange,
    public val clusterLevel: Int,
    public val sourceTextHash: String,
    public val unicodeVersion: String,
    public val breakBeforeRuleId: String,
)

public data class GraphemeBoundaryDecision(
    public val boundaryIndex: Int,
    public val codePointIndex: Int,
    public val ruleId: String,
    public val breakAllowed: Boolean,
    public val leftUtf16Range: IntRange?,
    public val rightUtf16Range: IntRange?,
    public val leftCodePoint: Int?,
    public val rightCodePoint: Int?,
)

public data class GraphemeSegmentationResult(
    public val unicodeVersion: String,
    public val sourceTextHash: String,
    public val clusters: List<GraphemeCluster>,
    public val boundaries: List<GraphemeBoundaryDecision>,
    public val diagnostics: List<ShapingDiagnostic>,
)

public data class GraphemeFixtureDumpInput(
    public val fixtureName: String,
    public val sourceText: String,
    public val result: GraphemeSegmentationResult,
)

public data class GraphemeSegmentsDump(
    public val unicodeVersion: String,
    public val inputs: List<GraphemeFixtureDumpInput>,
) {
    public fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"unicode-segments\",\n")
        append("  \"ownerTickets\": [\"KFONT-M5-002\"],\n")
        appendJsonField("unicodeVersion", unicodeVersion, comma = true)
        appendJsonField("sourceTextHashAlgorithm", SOURCE_TEXT_HASH_ALGORITHM, comma = true)
        append("  \"inputs\": [\n")
        append(inputs.joinToString(",\n") { input -> input.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"nonClaims\": [\n")
        append(GraphemeNonClaims.joinToString(",\n") { nonClaim -> "    ${jsonString(nonClaim)}" })
        append("\n  ]\n")
        append("}\n")
    }
}

public class GraphemeClusterer(
    private val unicodeDataSet: UnicodeDataSet,
    private val expectedUnicodeVersion: String = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
) {
    public fun segment(text: String): GraphemeSegmentationResult {
        val sourceTextHash = text.sourceTextHash()
        if (unicodeDataSet.version.value != expectedUnicodeVersion) {
            return GraphemeSegmentationResult(
                unicodeVersion = unicodeDataSet.version.value,
                sourceTextHash = sourceTextHash,
                clusters = emptyList(),
                boundaries = emptyList(),
                diagnostics = listOf(
                    UnicodeDataVersionMismatchDiagnostic(
                        expectedUnicodeVersion = expectedUnicodeVersion,
                        actualUnicodeVersion = unicodeDataSet.version.value,
                        subject = "grapheme-segmentation",
                        textRange = text.indices.takeUnless { it.isEmpty() },
                    ).toShapingDiagnostic(),
                ),
            )
        }

        val unsupportedDataDiagnostics = unsupportedDataDiagnostics(text)
        if (unsupportedDataDiagnostics.isNotEmpty()) {
            return GraphemeSegmentationResult(
                unicodeVersion = unicodeDataSet.version.value,
                sourceTextHash = sourceTextHash,
                clusters = emptyList(),
                boundaries = emptyList(),
                diagnostics = unsupportedDataDiagnostics,
            )
        }

        val diagnostics = mutableListOf<ShapingDiagnostic>()
        val scalars = decodeScalars(text, diagnostics)
        if (scalars.isEmpty()) {
            return GraphemeSegmentationResult(
                unicodeVersion = unicodeDataSet.version.value,
                sourceTextHash = sourceTextHash,
                clusters = emptyList(),
                boundaries = emptyList(),
                diagnostics = diagnostics,
            )
        }

        val boundaries = mutableListOf<GraphemeBoundaryDecision>()
        boundaries += GraphemeBoundaryDecision(
            boundaryIndex = scalars.first().utf16Range.first,
            codePointIndex = scalars.first().codePointIndex,
            ruleId = "GB1",
            breakAllowed = true,
            leftUtf16Range = null,
            rightUtf16Range = scalars.first().utf16Range,
            leftCodePoint = null,
            rightCodePoint = scalars.first().codePoint,
        )

        val clusters = mutableListOf<GraphemeCluster>()
        var clusterStart = 0
        var clusterBreakRule = "GB1"
        for (index in 1 until scalars.size) {
            val decision = boundaryDecision(scalars, index)
            boundaries += decision
            if (decision.breakAllowed) {
                clusters += clusterFor(
                    clusterIndex = clusters.size,
                    scalars = scalars,
                    startIndex = clusterStart,
                    endIndex = index - 1,
                    sourceTextHash = sourceTextHash,
                    breakRule = clusterBreakRule,
                )
                clusterStart = index
                clusterBreakRule = decision.ruleId
            }
        }
        clusters += clusterFor(
            clusterIndex = clusters.size,
            scalars = scalars,
            startIndex = clusterStart,
            endIndex = scalars.lastIndex,
            sourceTextHash = sourceTextHash,
            breakRule = clusterBreakRule,
        )
        boundaries += GraphemeBoundaryDecision(
            boundaryIndex = scalars.last().utf16Range.last + 1,
            codePointIndex = scalars.size,
            ruleId = "GB2",
            breakAllowed = true,
            leftUtf16Range = scalars.last().utf16Range,
            rightUtf16Range = null,
            leftCodePoint = scalars.last().codePoint,
            rightCodePoint = null,
        )

        diagnostics += validateGraphemeClusterInvariants(text, clusters)
        return GraphemeSegmentationResult(
            unicodeVersion = unicodeDataSet.version.value,
            sourceTextHash = sourceTextHash,
            clusters = clusters,
            boundaries = boundaries,
            diagnostics = diagnostics,
        )
    }

    public fun dumpFixtures(fixtures: List<Pair<String, String>>): GraphemeSegmentsDump =
        GraphemeSegmentsDump(
            unicodeVersion = unicodeDataSet.version.value,
            inputs = fixtures.map { (fixtureName, sourceText) ->
                GraphemeFixtureDumpInput(
                    fixtureName = fixtureName,
                    sourceText = sourceText,
                    result = segment(sourceText),
                )
            },
        )

    private fun decodeScalars(text: String, diagnostics: MutableList<ShapingDiagnostic>): List<GraphemeScalar> {
        val scalars = mutableListOf<GraphemeScalar>()
        var index = 0
        while (index < text.length) {
            val first = text[index]
            when {
                first.isHighSurrogate() -> {
                    if (index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                        val codePoint = Character.toCodePoint(first, text[index + 1])
                        scalars += scalarFor(codePoint, index..index + 1, scalars.size)
                        index += 2
                    } else {
                        diagnostics += invalidScalarDiagnostic(index..index, "isolated high surrogate")
                        diagnostics += clusterBoundaryInvalidDiagnostic(index..index, "malformed UTF-16 input")
                        index += 1
                    }
                }
                first.isLowSurrogate() -> {
                    diagnostics += invalidScalarDiagnostic(index..index, "isolated low surrogate")
                    diagnostics += clusterBoundaryInvalidDiagnostic(index..index, "malformed UTF-16 input")
                    index += 1
                }
                else -> {
                    scalars += scalarFor(first.code, index..index, scalars.size)
                    index += 1
                }
            }
        }
        return scalars
    }

    private fun scalarFor(codePoint: Int, utf16Range: IntRange, codePointIndex: Int): GraphemeScalar =
        GraphemeScalar(
            codePoint = codePoint,
            utf16Range = utf16Range,
            codePointIndex = codePointIndex,
            graphemeBreak = if (unicodeDataSet.variationSelector.valueAt(codePoint)) {
                GcbExtend
            } else {
                unicodeDataSet.graphemeBreak.valueAt(codePoint)
            },
            indicConjunctBreak = unicodeDataSet.indicConjunctBreak.valueAt(codePoint),
            extendedPictographic = unicodeDataSet.emojiProperties.extendedPictographic.valueAt(codePoint),
            variationSelector = unicodeDataSet.variationSelector.valueAt(codePoint),
        )

    private fun boundaryDecision(scalars: List<GraphemeScalar>, rightIndex: Int): GraphemeBoundaryDecision {
        val left = scalars[rightIndex - 1]
        val right = scalars[rightIndex]
        val rule = when {
            left.graphemeBreak == GcbCr && right.graphemeBreak == GcbLf -> GraphemeRule("GB3", breakAllowed = false)
            left.graphemeBreak.isControlLikeGcb() -> GraphemeRule("GB4", breakAllowed = true)
            right.graphemeBreak.isControlLikeGcb() -> GraphemeRule("GB5", breakAllowed = true)
            left.graphemeBreak == GcbL && right.graphemeBreak in setOf(GcbL, GcbV, GcbLv, GcbLvt) ->
                GraphemeRule("GB6", breakAllowed = false)
            left.graphemeBreak in setOf(GcbLv, GcbV) && right.graphemeBreak in setOf(GcbV, GcbT) ->
                GraphemeRule("GB7", breakAllowed = false)
            left.graphemeBreak in setOf(GcbLvt, GcbT) && right.graphemeBreak == GcbT ->
                GraphemeRule("GB8", breakAllowed = false)
            right.graphemeBreak in setOf(GcbExtend, GcbZwj) || right.variationSelector ->
                GraphemeRule("GB9", breakAllowed = false)
            right.graphemeBreak == GcbSpacingMark -> GraphemeRule("GB9a", breakAllowed = false)
            left.graphemeBreak == GcbPrepend -> GraphemeRule("GB9b", breakAllowed = false)
            hasIndicConjunctLinkerBefore(scalars, rightIndex) -> GraphemeRule("GB9c", breakAllowed = false)
            hasExtendedPictographicZwjBefore(scalars, rightIndex) -> GraphemeRule("GB11", breakAllowed = false)
            isRegionalIndicatorPairBoundary(scalars, rightIndex) -> GraphemeRule("GB12/13", breakAllowed = false)
            else -> GraphemeRule("GB999", breakAllowed = true)
        }
        return GraphemeBoundaryDecision(
            boundaryIndex = right.utf16Range.first,
            codePointIndex = right.codePointIndex,
            ruleId = rule.ruleId,
            breakAllowed = rule.breakAllowed,
            leftUtf16Range = left.utf16Range,
            rightUtf16Range = right.utf16Range,
            leftCodePoint = left.codePoint,
            rightCodePoint = right.codePoint,
        )
    }

    private fun hasIndicConjunctLinkerBefore(scalars: List<GraphemeScalar>, rightIndex: Int): Boolean {
        if (scalars[rightIndex].indicConjunctBreak != InCbConsonant) return false
        var index = rightIndex - 1
        var sawLinker = false
        while (index >= 0 && scalars[index].indicConjunctBreak in setOf(InCbExtend, InCbLinker)) {
            if (scalars[index].indicConjunctBreak == InCbLinker) {
                sawLinker = true
            }
            index -= 1
        }
        return sawLinker && index >= 0 && scalars[index].indicConjunctBreak == InCbConsonant
    }

    private fun hasExtendedPictographicZwjBefore(scalars: List<GraphemeScalar>, rightIndex: Int): Boolean {
        if (!scalars[rightIndex].extendedPictographic) return false
        if (scalars[rightIndex - 1].graphemeBreak != GcbZwj) return false
        var index = rightIndex - 2
        while (index >= 0 && scalars[index].graphemeBreak == GcbExtend) {
            index -= 1
        }
        return index >= 0 && scalars[index].extendedPictographic
    }

    private fun isRegionalIndicatorPairBoundary(scalars: List<GraphemeScalar>, rightIndex: Int): Boolean {
        if (scalars[rightIndex - 1].graphemeBreak != GcbRegionalIndicator ||
            scalars[rightIndex].graphemeBreak != GcbRegionalIndicator
        ) {
            return false
        }
        var count = 0
        var index = rightIndex - 1
        while (index >= 0 && scalars[index].graphemeBreak == GcbRegionalIndicator) {
            count += 1
            index -= 1
        }
        return count % 2 == 1
    }

    private fun clusterFor(
        clusterIndex: Int,
        scalars: List<GraphemeScalar>,
        startIndex: Int,
        endIndex: Int,
        sourceTextHash: String,
        breakRule: String,
    ): GraphemeCluster {
        val first = scalars[startIndex]
        val last = scalars[endIndex]
        return GraphemeCluster(
            clusterIndex = clusterIndex,
            utf16Range = first.utf16Range.first..last.utf16Range.last,
            codePointRange = first.codePointIndex..last.codePointIndex,
            clusterLevel = 0,
            sourceTextHash = sourceTextHash,
            unicodeVersion = unicodeDataSet.version.value,
            breakBeforeRuleId = breakRule,
        )
    }

    private fun invalidScalarDiagnostic(textRange: IntRange, reason: String): ShapingDiagnostic =
        ShapingDiagnostic(
            code = TEXT_UNICODE_INVALID_SCALAR_DIAGNOSTIC_CODE,
            message = "Invalid Unicode scalar at UTF-16 range ${textRange.toRangeLabel()}: $reason.",
            textRange = textRange,
        )

    private fun unsupportedDataDiagnostics(text: String): List<ShapingDiagnostic> {
        val missing = mutableListOf<String>()
        val graphemeClasses = unicodeDataSet.graphemeBreak.ranges.mapTo(sortedSetOf()) { range -> range.value }
        val indicClasses = unicodeDataSet.indicConjunctBreak.ranges.mapTo(sortedSetOf()) { range -> range.value }
        for (required in RequiredGraphemeBreakClasses) {
            if (required !in graphemeClasses) {
                missing += "Grapheme_Cluster_Break=$required"
            }
        }
        for (required in RequiredIndicConjunctBreakClasses) {
            if (required !in indicClasses) {
                missing += "Indic_Conjunct_Break=$required"
            }
        }
        if (unicodeDataSet.emojiProperties.extendedPictographic.ranges.isEmpty()) {
            missing += "Extended_Pictographic"
        }
        if (missing.isEmpty()) return emptyList()
        return listOf(
            ShapingDiagnostic(
                code = TEXT_UNICODE_GRAPHEME_RULE_UNSUPPORTED_DIAGNOSTIC_CODE,
                message = "Grapheme segmentation cannot run with incomplete pinned Unicode data: " +
                    missing.joinToString(", ") + ".",
                textRange = text.indices.takeUnless { it.isEmpty() },
            ),
        )
    }
}

public object DefaultGraphemeTextSegmenter : TextSegmenter {
    private val delegate: GraphemeTextSegmenter by lazy {
        GraphemeTextSegmenter(GraphemeClusterer(PinnedUnicodeDataSetResources.load()))
    }

    override fun segment(text: String): List<IntRange> =
        delegate.segment(text)
}

public class GraphemeTextSegmenter(
    private val clusterer: GraphemeClusterer,
) : TextSegmenter {
    override fun segment(text: String): List<IntRange> =
        clusterer.segment(text).clusters.map { cluster -> cluster.utf16Range }
}

public object PinnedUnicodeDataSetResources {
    public fun load(): UnicodeDataSet =
        PinnedUnicodeDataSet
}

public fun validateGraphemeClusterInvariants(
    text: String,
    clusters: List<GraphemeCluster>,
): List<ShapingDiagnostic> {
    val diagnostics = mutableListOf<ShapingDiagnostic>()
    clusters.forEachIndexed { index, cluster ->
        if (cluster.clusterIndex != index) {
            diagnostics += clusterInvariantDiagnostic(cluster.utf16Range, "cluster index ${cluster.clusterIndex} is not $index")
        }
        if (cluster.utf16Range.first < 0 || cluster.utf16Range.last >= text.length || cluster.utf16Range.first > cluster.utf16Range.last) {
            diagnostics += clusterInvariantDiagnostic(cluster.utf16Range, "UTF-16 range is outside source text")
        }
        if (cluster.utf16Range.first > 0 &&
            cluster.utf16Range.first < text.length &&
            text[cluster.utf16Range.first].isLowSurrogate() &&
            text[cluster.utf16Range.first - 1].isHighSurrogate()
        ) {
            diagnostics += clusterInvariantDiagnostic(cluster.utf16Range, "UTF-16 range starts inside a surrogate pair")
        }
        if (cluster.utf16Range.last >= 0 &&
            cluster.utf16Range.last + 1 < text.length &&
            text[cluster.utf16Range.last].isHighSurrogate() &&
            text[cluster.utf16Range.last + 1].isLowSurrogate()
        ) {
            diagnostics += clusterInvariantDiagnostic(cluster.utf16Range, "UTF-16 range ends inside a surrogate pair")
        }
        val previous = clusters.getOrNull(index - 1)
        if (previous != null && previous.utf16Range.last >= cluster.utf16Range.first) {
            diagnostics += clusterInvariantDiagnostic(
                cluster.utf16Range,
                "cluster overlaps previous range ${previous.utf16Range.toRangeLabel()}",
            )
        }
    }
    return diagnostics.distinctBy { diagnostic -> diagnostic.code to diagnostic.textRange }
}

private data class GraphemeScalar(
    val codePoint: Int,
    val utf16Range: IntRange,
    val codePointIndex: Int,
    val graphemeBreak: String,
    val indicConjunctBreak: String,
    val extendedPictographic: Boolean,
    val variationSelector: Boolean,
)

private data class GraphemeRule(
    val ruleId: String,
    val breakAllowed: Boolean,
)

private const val SOURCE_TEXT_HASH_ALGORITHM = "sha256-utf16-code-units"
private const val GcbCr = "CR"
private const val GcbLf = "LF"
private const val GcbControl = "Control"
private const val GcbExtend = "Extend"
private const val GcbPrepend = "Prepend"
private const val GcbSpacingMark = "SpacingMark"
private const val GcbZwj = "ZWJ"
private const val GcbRegionalIndicator = "Regional_Indicator"
private const val GcbL = "L"
private const val GcbV = "V"
private const val GcbT = "T"
private const val GcbLv = "LV"
private const val GcbLvt = "LVT"
private const val InCbConsonant = "Consonant"
private const val InCbExtend = "Extend"
private const val InCbLinker = "Linker"
private const val UnicodeResourceRoot = "io/ygdrasil/kalligraphie/text/unicode/16.0.0"

private val RequiredGraphemeBreakClasses = setOf(
    GcbCr,
    GcbLf,
    GcbControl,
    GcbExtend,
    GcbPrepend,
    GcbSpacingMark,
    GcbZwj,
    GcbRegionalIndicator,
    GcbL,
    GcbV,
    GcbT,
    GcbLv,
    GcbLvt,
)
private val RequiredIndicConjunctBreakClasses = setOf(InCbConsonant, InCbExtend, InCbLinker)
private val DefaultUnicodeInputFileNames = listOf(
    "DerivedCoreProperties.txt",
    "GraphemeBreakProperty.txt",
    "LineBreak.txt",
    "PropList.txt",
    "ScriptExtensions.txt",
    "Scripts.txt",
    "UnicodeData.txt",
    "emoji/emoji-data.txt",
)
private val PinnedUnicodeDataSet: UnicodeDataSet by lazy {
    PinnedUnicodeDataGenerator.generate(
        DefaultUnicodeInputFileNames.map { fileName ->
            UcdInputFile(
                fileName = fileName,
                unicodeVersion = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
                content = readUnicodeResource(fileName),
            )
        },
    )
}

private val GraphemeNonClaims = listOf(
    "bounded-kfont-m5-002-fixture-evidence-only",
    "no-complete-ucd-claim",
    "no-word-sentence-or-line-breaking-claim",
    "no-shaping-support-promotion",
    "no-paragraph-support-claim",
    "no-gpu-text-route-claim",
)

private fun String.isControlLikeGcb(): Boolean =
    this == GcbCr || this == GcbLf || this == GcbControl

private fun readUnicodeResource(fileName: String): String {
    val resourcePath = "$UnicodeResourceRoot/$fileName"
    val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)
        ?: GraphemeClusterer::class.java.classLoader.getResourceAsStream(resourcePath)
        ?: error("Missing pinned Unicode resource: $resourcePath")
    return stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
}

private fun GraphemeFixtureDumpInput.toCanonicalJson(): String = buildString {
    append("{\n")
    appendJsonField("fixtureName", fixtureName, comma = true, indent = "  ")
    appendJsonField("inputTextHash", result.sourceTextHash, comma = true, indent = "  ")
    append("  \"clusters\": [\n")
    append(result.clusters.joinToString(",\n") { cluster -> cluster.toCanonicalJson().prependIndent("    ") })
    append("\n  ],\n")
    append("  \"boundaries\": [\n")
    append(result.boundaries.joinToString(",\n") { boundary -> boundary.toCanonicalJson().prependIndent("    ") })
    append("\n  ],\n")
    append("  \"diagnostics\": [")
    if (result.diagnostics.isNotEmpty()) {
        append("\n")
        append(result.diagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
        append("\n  ")
    }
    append("]\n")
    append("}")
}

private fun GraphemeCluster.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonString("clusterIndex")).append(": ").append(clusterIndex).append(", ")
    append(jsonString("utf16Range")).append(": ").append(jsonString(utf16Range.toRangeLabel())).append(", ")
    append(jsonString("codePointRange")).append(": ").append(jsonString(codePointRange.toRangeLabel())).append(", ")
    append(jsonString("clusterLevel")).append(": ").append(clusterLevel).append(", ")
    append(jsonString("sourceTextHash")).append(": ").append(jsonString(sourceTextHash)).append(", ")
    append(jsonString("unicodeVersion")).append(": ").append(jsonString(unicodeVersion)).append(", ")
    append(jsonString("breakBeforeRuleId")).append(": ").append(jsonString(breakBeforeRuleId))
    append("}")
}

private fun GraphemeBoundaryDecision.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonString("boundaryIndex")).append(": ").append(boundaryIndex).append(", ")
    append(jsonString("codePointIndex")).append(": ").append(codePointIndex).append(", ")
    append(jsonString("ruleId")).append(": ").append(jsonString(ruleId)).append(", ")
    append(jsonString("breakAllowed")).append(": ").append(breakAllowed).append(", ")
    append(jsonString("leftUtf16Range")).append(": ").append(jsonString(leftUtf16Range?.toRangeLabel())).append(", ")
    append(jsonString("rightUtf16Range")).append(": ").append(jsonString(rightUtf16Range?.toRangeLabel())).append(", ")
    append(jsonString("leftCodePoint")).append(": ").append(jsonString(leftCodePoint?.toCodePointLabel())).append(", ")
    append(jsonString("rightCodePoint")).append(": ").append(jsonString(rightCodePoint?.toCodePointLabel()))
    append("}")
}

private fun ShapingDiagnostic.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonString("code")).append(": ").append(jsonString(code)).append(", ")
    append(jsonString("severity")).append(": ").append(jsonString("refusal")).append(", ")
    append(jsonString("textRange")).append(": ").append(jsonString(textRange?.toRangeLabel())).append(", ")
    append(jsonString("message")).append(": ").append(jsonString(message))
    append("}")
}

private fun clusterInvariantDiagnostic(textRange: IntRange, reason: String): ShapingDiagnostic =
    ShapingDiagnostic(
        code = TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE,
        message = "Grapheme cluster invariant failed for UTF-16 range ${textRange.toRangeLabel()}: $reason.",
        textRange = textRange,
    )

private fun clusterBoundaryInvalidDiagnostic(textRange: IntRange, reason: String): ShapingDiagnostic =
    ShapingDiagnostic(
        code = TEXT_UNICODE_CLUSTER_BOUNDARY_INVALID_DIAGNOSTIC_CODE,
        message = "Grapheme cluster boundary invalid for UTF-16 range ${textRange.toRangeLabel()}: $reason.",
        textRange = textRange,
    )

private fun String.sourceTextHash(): String {
    val bytes = ByteArray(length * 2)
    forEachIndexed { index, char ->
        val value = char.code
        bytes[index * 2] = (value ushr 8).toByte()
        bytes[index * 2 + 1] = value.toByte()
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
}

private fun StringBuilder.appendJsonField(name: String, value: String?, comma: Boolean, indent: String = "  ") {
    append(indent).append(jsonString(name)).append(": ").append(jsonString(value))
    if (comma) append(",")
    append("\n")
}

private fun jsonString(value: String?): String =
    value?.let {
        buildString {
            append('"')
            for (char in it) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.code < 0x20 || char.code in 0xD800..0xDFFF) {
                            append("\\u")
                            append(char.code.toString(16).uppercase().padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
            append('"')
        }
    } ?: "null"

private fun Int.toCodePointLabel(): String {
    val hex = toString(16).uppercase()
    return "U+${hex.padStart(maxOf(4, hex.length), '0')}"
}

private fun IntRange.toRangeLabel(): String =
    "$first..$last"
