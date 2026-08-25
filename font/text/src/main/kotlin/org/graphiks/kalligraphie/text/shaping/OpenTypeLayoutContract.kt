package org.graphiks.kalligraphie.text.shaping

import java.security.MessageDigest
import org.graphiks.kalligraphie.font.TypefaceID

public const val TEXT_SHAPING_ENGINE_CONTRACT_MISSING_DIAGNOSTIC_CODE: String =
    "text.shaping.engine-contract-missing"
public const val TEXT_SHAPING_LOOKUP_TYPE_UNSUPPORTED_DIAGNOSTIC_CODE: String =
    "text.shaping.lookup-type-unsupported"
public const val TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE: String =
    "text.shaping.lookup-malformed"

public fun interface OpenTypeLayoutGlyphMapper {
    public fun glyphIdFor(typefaceId: TypefaceID?, codePoint: Int): Int?
}

public interface OpenTypeLayoutEngine {
    public fun shape(input: OpenTypeRunInput): OpenTypeLayoutResult
}

public data class ShapingFeatureRequest(
    public val tag: String,
    public val value: Int,
)

public data class ResolvedFeatureSet(
    public val requested: List<ShapingFeatureRequest> = emptyList(),
    public val enabled: List<ShapingFeatureRequest> = emptyList(),
    public val disabled: List<ShapingFeatureRequest> = emptyList(),
    public val defaulted: List<ShapingFeatureRequest> = emptyList(),
    public val unsupported: List<ShapingFeatureRequest> = emptyList(),
    public val languageSystem: String? = null,
)

public const val DEFAULT_OPEN_TYPE_LANGUAGE_SYSTEM: String = "dflt"

public data class ScriptFeaturePolicy(
    public val scriptFamily: String,
    public val selectedScripts: List<String>,
    public val openTypeScriptTags: List<String>,
    public val requiredDefaults: List<String>,
    public val optionalFeatures: List<String> = emptyList(),
    public val refusalWhenMissing: List<String> = emptyList(),
)

public object RequiredScriptFeaturePolicies {
    public val rows: List<ScriptFeaturePolicy> = listOf(
        ScriptFeaturePolicy(
            scriptFamily = "Latin",
            selectedScripts = listOf("Latn"),
            openTypeScriptTags = listOf("latn"),
            requiredDefaults = listOf("ccmp", "locl", "liga", "rlig", "clig", "calt", "kern", "mark", "mkmk"),
        ),
        ScriptFeaturePolicy(
            scriptFamily = "Greek",
            selectedScripts = listOf("Grek"),
            openTypeScriptTags = listOf("grek"),
            requiredDefaults = listOf("ccmp", "locl", "liga", "rlig", "clig", "calt", "kern", "mark", "mkmk"),
        ),
        ScriptFeaturePolicy(
            scriptFamily = "Cyrillic",
            selectedScripts = listOf("Cyrl"),
            openTypeScriptTags = listOf("cyrl"),
            requiredDefaults = listOf("ccmp", "locl", "liga", "rlig", "clig", "calt", "kern", "mark", "mkmk"),
        ),
        ScriptFeaturePolicy(
            scriptFamily = "Hebrew",
            selectedScripts = listOf("Hebr"),
            openTypeScriptTags = listOf("hebr"),
            requiredDefaults = listOf("ccmp", "locl", "mark", "mkmk", "kern"),
        ),
        ScriptFeaturePolicy(
            scriptFamily = "Arabic",
            selectedScripts = listOf("Arab"),
            openTypeScriptTags = listOf("arab"),
            requiredDefaults = listOf("init", "medi", "fina", "isol", "rlig", "liga", "calt", "mark", "mkmk", "curs"),
            refusalWhenMissing = listOf("mark", "mkmk", "cursive-attachment"),
        ),
        ScriptFeaturePolicy(
            scriptFamily = "Devanagari",
            selectedScripts = listOf("Deva"),
            openTypeScriptTags = listOf("deva", "dev2"),
            requiredDefaults = listOf(
                "nukt", "akhn", "rphf", "blwf", "half", "pstf", "vatu",
                "pres", "abvs", "blws", "psts", "haln", "dist", "abvm", "blwm",
            ),
        ),
        ScriptFeaturePolicy(
            scriptFamily = "Thai",
            selectedScripts = listOf("Thai"),
            openTypeScriptTags = listOf("thai"),
            requiredDefaults = listOf("ccmp", "locl", "mark", "mkmk", "kern"),
        ),
        ScriptFeaturePolicy(
            scriptFamily = "CJK",
            selectedScripts = listOf("Hani", "Hira", "Kana", "Hang"),
            openTypeScriptTags = listOf("hani", "hira", "kana", "hang"),
            requiredDefaults = listOf("locl"),
            optionalFeatures = listOf("vert", "vrt2"),
        ),
        ScriptFeaturePolicy(
            scriptFamily = "Emoji",
            selectedScripts = listOf("Zsye", "Zsym"),
            openTypeScriptTags = listOf("Zsye", "Zsym"),
            requiredDefaults = emptyList(),
            refusalWhenMissing = listOf("variation-selectors", "emoji-modifiers", "zwj-sequences"),
        ),
    )

    public fun resolve(
        scriptRun: ScriptItemizationRun,
        requested: List<ShapingFeatureRequest>,
    ): ResolvedFeatureSet {
        val normalizedRequested = requested.distinctBy { it.tag }
        val policy = rows.firstOrNull { row ->
            scriptRun.selectedScript in row.selectedScripts ||
                scriptRun.openTypeScriptTags.any { tag -> tag in row.openTypeScriptTags }
        }
        if (policy == null) {
            return ResolvedFeatureSet(
                requested = normalizedRequested,
                languageSystem = DEFAULT_OPEN_TYPE_LANGUAGE_SYSTEM,
            )
        }

        val requestedByTag = normalizedRequested.associateBy { it.tag }
        val requestedEnabledTags = normalizedRequested.filter { it.value > 0 }.map { it.tag }.toSet()
        val supportedTags = (policy.requiredDefaults + policy.optionalFeatures).toSet()
        val explicitDisabled = normalizedRequested.filter { it.value <= 0 }
        val unsupported = normalizedRequested.filter { it.value > 0 && it.tag !in supportedTags }
        val defaulted = policy.requiredDefaults
            .filter { tag -> tag !in requestedByTag }
            .map { tag -> ShapingFeatureRequest(tag, 1) }
        val enabled = buildList {
            policy.requiredDefaults.forEach { tag ->
                if (requestedByTag[tag]?.value != 0) {
                    add(ShapingFeatureRequest(tag, requestedByTag[tag]?.value ?: 1))
                }
            }
            normalizedRequested
                .filter { feature ->
                    feature.value > 0 &&
                        feature.tag in supportedTags &&
                        feature.tag !in policy.requiredDefaults &&
                        feature.tag in requestedEnabledTags
                }
                .forEach(::add)
        }
        val disabled = buildList {
            addAll(explicitDisabled)
            unsupported.forEach { feature ->
                if (none { it.tag == feature.tag }) {
                    add(feature)
                }
            }
        }

        return ResolvedFeatureSet(
            requested = normalizedRequested,
            enabled = enabled,
            disabled = disabled,
            defaulted = defaulted,
            unsupported = unsupported,
            languageSystem = DEFAULT_OPEN_TYPE_LANGUAGE_SYSTEM,
        )
    }

    public fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"feature-policy-matrix\",\n")
        append("  \"ownerTickets\": [\"KFONT-M6-006\"],\n")
        append("  \"rows\": [\n")
        append(rows.joinToString(",\n") { row -> row.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append(
            "  \"nonClaims\": " +
                jsonStringList(
                    listOf(
                        "no-complete-target-support-claim",
                        "no-complete-feature-policy-claim",
                        "no-native-shaper-oracle-claim",
                        "no-gpu-text-route-claim",
                    ),
                ) +
                "\n",
        )
        append("}\n")
    }
}

public data class OpenTypeTableAvailability(
    public val gdef: Boolean = true,
    public val gsub: Boolean = true,
    public val gpos: Boolean = true,
)

public data class OpenTypeLookupTraceRequest(
    public val stage: String,
    public val lookupId: String,
    public val lookupType: Int?,
    public val status: String,
)

public data class OpenTypeDirectGlyphInput(
    public val glyphIds: List<Int>,
    public val sourceUtf16Ranges: List<IntRange>,
)

public data class OpenTypeRunInput(
    public val text: String,
    public val typefaceId: TypefaceID?,
    public val clusters: List<GraphemeCluster>,
    public val bidiLevel: Int,
    public val direction: String,
    public val scriptRun: ScriptItemizationRun,
    public val features: ResolvedFeatureSet,
    public val tableAvailability: OpenTypeTableAvailability = OpenTypeTableAvailability(),
    public val fallbackRun: String? = null,
    public val lookupTraceRequests: List<OpenTypeLookupTraceRequest> = emptyList(),
    public val directGlyphInput: OpenTypeDirectGlyphInput? = null,
)

public data class OpenTypeShapingPlan(
    public val unicodeVersion: String,
    public val sourceTextHash: String,
    public val textRange: IntRange,
    public val typefaceId: TypefaceID?,
    public val scriptRun: ScriptItemizationRun,
    public val bidiLevel: Int,
    public val direction: String,
    public val languageSystem: String?,
    public val features: ResolvedFeatureSet,
    public val gsubTraceRef: String,
    public val gposTraceRef: String,
    public val fallbackRun: String?,
    public val directGlyphInput: Boolean,
)

public data class OpenTypeLookupTrace(
    public val dumpId: String,
    public val stage: String,
    public val unicodeVersion: String,
    public val sourceTextHash: String,
    public val typefaceId: TypefaceID?,
    public val scriptRun: ScriptItemizationRun,
    public val features: ResolvedFeatureSet,
    public val events: List<OpenTypeLookupTraceEvent>,
    public val diagnostics: List<ShapingDiagnostic>,
)

public data class OpenTypeLookupTraceEvent(
    public val stage: String,
    public val lookupId: String,
    public val lookupType: Int?,
    public val decision: String,
    public val featureTags: List<String>,
    public val diagnosticCode: String?,
)

public data class OpenTypeShapedRun(
    public val unicodeVersion: String,
    public val sourceTextHash: String,
    public val typefaceId: TypefaceID?,
    public val script: String,
    public val direction: String,
    public val glyphs: List<OpenTypeShapedGlyph>,
    public val clusters: List<OpenTypeClusterMapping>,
    public val diagnostics: List<ShapingDiagnostic>,
)

public data class OpenTypeShapedGlyph(
    public val glyphIndex: Int,
    public val glyphId: Int,
    public val clusterIndex: Int,
    public val sourceUtf16Range: IntRange,
    public val source: String,
    public val xAdvance: Float,
    public val xOffset: Float = 0f,
    public val yOffset: Float = 0f,
)

public data class OpenTypeClusterMapping(
    public val clusterIndex: Int,
    public val sourceUtf16Range: IntRange,
    public val glyphRange: IntRange,
    public val synthetic: Boolean,
)

public data class OpenTypeLayoutResult(
    public val shapingPlan: OpenTypeShapingPlan,
    public val gsubTrace: OpenTypeLookupTrace,
    public val gposTrace: OpenTypeLookupTrace,
    public val shapedRun: OpenTypeShapedRun,
    public val diagnostics: List<ShapingDiagnostic>,
) {
    public fun toEvidenceBundle(): OpenTypeLayoutEvidenceBundle =
        OpenTypeLayoutEvidenceBundle(
            shapingPlanJson = shapingPlan.toCanonicalJson(diagnostics),
            gsubTraceJson = gsubTrace.toCanonicalJson(),
            gposTraceJson = gposTrace.toCanonicalJson(),
            shapedGlyphRunJson = shapedRun.toCanonicalJson(),
        )
}

public data class OpenTypeLayoutEvidenceBundle(
    public val shapingPlanJson: String,
    public val gsubTraceJson: String,
    public val gposTraceJson: String,
    public val shapedGlyphRunJson: String,
)

public data class OpenTypeShapingPlanCase(
    public val caseId: String,
    public val result: OpenTypeLayoutResult,
)

public fun openTypeShapingPlanCasesToCanonicalJson(cases: List<OpenTypeShapingPlanCase>): String {
    require(cases.isNotEmpty()) { "OpenType shaping plan dump requires at least one case." }
    val unicodeVersion = cases.first().result.shapingPlan.unicodeVersion
    require(cases.all { it.result.shapingPlan.unicodeVersion == unicodeVersion }) {
        "OpenType shaping plan cases must use a single pinned Unicode version."
    }

    return buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"shaping-plan\",\n")
        append("  \"ownerTickets\": [\"KFONT-M6-001\"],\n")
        appendJsonField("unicodeVersion", unicodeVersion, comma = true)
        appendJsonField("sourceTextHashAlgorithm", "SHA-256-UTF-8", comma = true)
        append("  \"cases\": [\n")
        append(cases.joinToString(",\n") { shapingCase ->
            shapingCase.toCanonicalJson().trimEnd().prependIndent("    ")
        })
        append("\n  ],\n")
        append("  \"nonClaims\": ${jsonStringList(OpenTypeLayoutNonClaims)}\n")
        append("}\n")
    }
}

public class OpenTypeLayoutEngineContract(
    private val unicodeVersion: String = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
    private val glyphMapper: OpenTypeLayoutGlyphMapper,
) : OpenTypeLayoutEngine {
    public constructor(glyphMapper: OpenTypeLayoutGlyphMapper) : this(
        unicodeVersion = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
        glyphMapper = glyphMapper,
    )

    override fun shape(input: OpenTypeRunInput): OpenTypeLayoutResult {
        val sourceTextHash = input.text.sourceTextHashForOpenTypeLayout()
        val diagnostics = mutableListOf<ShapingDiagnostic>()
        diagnostics += validateInput(input)
        diagnostics += featureDiagnostics(input)
        diagnostics += tableDiagnostics(input)
        diagnostics += lookupDiagnostics(input)

        val shapedGlyphs = when {
            input.directGlyphInput != null -> directGlyphs(input)
            diagnostics.any { it.code == TEXT_SHAPING_SCRIPT_UNSUPPORTED_DIAGNOSTIC_CODE } -> emptyList()
            else -> mappedGlyphs(input, diagnostics)
        }
        val clusterMappings = clusterMappingsFor(input, shapedGlyphs)

        val shapingPlan = OpenTypeShapingPlan(
            unicodeVersion = unicodeVersion,
            sourceTextHash = sourceTextHash,
            textRange = textRangeFor(input),
            typefaceId = input.typefaceId,
            scriptRun = input.scriptRun,
            bidiLevel = input.bidiLevel,
            direction = input.direction,
            languageSystem = input.features.languageSystem ?: input.scriptRun.languageHint ?: DEFAULT_OPEN_TYPE_LANGUAGE_SYSTEM,
            features = input.features,
            gsubTraceRef = "opentype-layout-contract-gsub-trace",
            gposTraceRef = "opentype-layout-contract-gpos-trace",
            fallbackRun = input.fallbackRun,
            directGlyphInput = input.directGlyphInput != null,
        )
        val gsubTrace = traceFor("opentype-layout-contract-gsub-trace", "GSUB", input, sourceTextHash, diagnostics)
        val gposTrace = traceFor("opentype-layout-contract-gpos-trace", "GPOS", input, sourceTextHash, diagnostics)
        val shapedRun = OpenTypeShapedRun(
            unicodeVersion = unicodeVersion,
            sourceTextHash = sourceTextHash,
            typefaceId = input.typefaceId,
            script = input.scriptRun.selectedScript,
            direction = input.direction,
            glyphs = shapedGlyphs,
            clusters = clusterMappings,
            diagnostics = diagnostics.distinctForOpenTypeEvidence(),
        )
        return OpenTypeLayoutResult(
            shapingPlan = shapingPlan,
            gsubTrace = gsubTrace,
            gposTrace = gposTrace,
            shapedRun = shapedRun,
            diagnostics = diagnostics.distinctForOpenTypeEvidence(),
        )
    }

    private fun validateInput(input: OpenTypeRunInput): List<ShapingDiagnostic> {
        val diagnostics = mutableListOf<ShapingDiagnostic>()
        if (input.typefaceId == null) {
            diagnostics += ShapingDiagnostic(
                code = TEXT_SHAPING_FALLBACK_MISSING_DIAGNOSTIC_CODE,
                message = "OpenType layout input has no deterministic typeface.",
                textRange = textRangeFor(input),
            )
        }
        if (input.scriptRun.openTypeScriptTags.isEmpty() && input.scriptRun.selectedScript !in setOf("Zyyy", "Zinh")) {
            diagnostics += ShapingDiagnostic(
                code = TEXT_SHAPING_SCRIPT_UNSUPPORTED_DIAGNOSTIC_CODE,
                message = "Script ${input.scriptRun.selectedScript} has no supported OpenType script tag in the contract.",
                textRange = input.scriptRun.utf16Range,
            )
        }
        input.clusters.forEach { cluster ->
            if (cluster.utf16Range.first < 0 || cluster.utf16Range.last >= input.text.length || cluster.utf16Range.isEmpty()) {
                diagnostics += ShapingDiagnostic(
                    code = TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE,
                    message = "Cluster ${cluster.clusterIndex} range ${cluster.utf16Range.toRangeLabel()} is outside input text.",
                    textRange = cluster.utf16Range,
                )
            }
        }
        input.directGlyphInput?.let { direct ->
            if (direct.glyphIds.size != direct.sourceUtf16Ranges.size) {
                diagnostics += ShapingDiagnostic(
                    code = TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE,
                    message = "Direct glyph input has ${direct.glyphIds.size} glyph IDs but ${direct.sourceUtf16Ranges.size} source ranges.",
                    textRange = textRangeFor(input),
                )
            }
            direct.sourceUtf16Ranges.forEachIndexed { index, range ->
                if (range.first < 0 || range.last >= input.text.length || range.isEmpty()) {
                    diagnostics += ShapingDiagnostic(
                        code = TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE,
                        message = "Direct glyph input range $index ${range.toRangeLabel()} is outside input text.",
                        textRange = range,
                    )
                }
            }
        }
        return diagnostics
    }

    private fun featureDiagnostics(input: OpenTypeRunInput): List<ShapingDiagnostic> =
        input.features.disabled.map { feature ->
            ShapingDiagnostic(
                code = TEXT_SHAPING_FEATURE_UNSUPPORTED_DIAGNOSTIC_CODE,
                message = "Feature ${feature.tag} is requested but disabled by the no-op OpenType layout contract.",
                textRange = textRangeFor(input),
            )
        }

    private fun tableDiagnostics(input: OpenTypeRunInput): List<ShapingDiagnostic> {
        if (input.directGlyphInput != null) return emptyList()
        val diagnostics = mutableListOf<ShapingDiagnostic>()
        val enabledTags = input.features.enabled.map { it.tag }.toSet()
        if (!input.tableAvailability.gsub && enabledTags.any { it in gsubFeatureTags }) {
            diagnostics += ShapingDiagnostic(
                code = TEXT_SHAPING_ENGINE_CONTRACT_MISSING_DIAGNOSTIC_CODE,
                message = "GSUB table is missing for requested feature set ${enabledTags.sorted().joinToString(",")}.",
                textRange = textRangeFor(input),
            )
        }
        if (!input.tableAvailability.gpos && enabledTags.any { it in gposFeatureTags }) {
            diagnostics += ShapingDiagnostic(
                code = TEXT_SHAPING_ENGINE_CONTRACT_MISSING_DIAGNOSTIC_CODE,
                message = "GPOS table is missing for requested feature set ${enabledTags.sorted().joinToString(",")}.",
                textRange = textRangeFor(input),
            )
        }
        if (!input.tableAvailability.gdef && enabledTags.any { it in gdefFeatureTags }) {
            diagnostics += ShapingDiagnostic(
                code = TEXT_SHAPING_ENGINE_CONTRACT_MISSING_DIAGNOSTIC_CODE,
                message = "GDEF table is missing for mark or ligature feature data.",
                textRange = textRangeFor(input),
            )
        }
        return diagnostics
    }

    private fun lookupDiagnostics(input: OpenTypeRunInput): List<ShapingDiagnostic> =
        input.lookupTraceRequests.mapNotNull { request ->
            when (request.status) {
                "unsupported" -> ShapingDiagnostic(
                    code = TEXT_SHAPING_LOOKUP_TYPE_UNSUPPORTED_DIAGNOSTIC_CODE,
                    message = "${request.stage} ${request.lookupId} type ${request.lookupType ?: "unknown"} is unsupported by this contract slice.",
                    textRange = textRangeFor(input),
                )
                "malformed" -> ShapingDiagnostic(
                    code = TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE,
                    message = "${request.stage} ${request.lookupId} is malformed and refused by this contract slice.",
                    textRange = textRangeFor(input),
                )
                else -> null
            }
        }

    private fun mappedGlyphs(
        input: OpenTypeRunInput,
        diagnostics: MutableList<ShapingDiagnostic>,
    ): List<OpenTypeShapedGlyph> {
        val glyphs = mutableListOf<OpenTypeShapedGlyph>()
        input.clusters.forEachIndexed { clusterIndex, cluster ->
            if (cluster.utf16Range.first < 0 || cluster.utf16Range.last >= input.text.length || cluster.utf16Range.isEmpty()) {
                return@forEachIndexed
            }
            val codePoints = codePointRangesForOpenType(input.text, cluster.utf16Range)
            codePoints.forEach { codePointRange ->
                val glyphId = glyphMapper.glyphIdFor(input.typefaceId, codePointRange.codePoint)
                if (glyphId == null) {
                    diagnostics += ShapingDiagnostic(
                        code = TEXT_SHAPING_FALLBACK_MISSING_DIAGNOSTIC_CODE,
                        message = "No glyph mapping for U+${codePointRange.codePoint.toString(16).uppercase().padStart(4, '0')}.",
                        textRange = codePointRange.textRange,
                    )
                }
                glyphs += OpenTypeShapedGlyph(
                    glyphIndex = glyphs.size,
                    glyphId = glyphId ?: 0,
                    clusterIndex = clusterIndex,
                    sourceUtf16Range = codePointRange.textRange,
                    source = "cmap-no-op",
                    xAdvance = 1f,
                )
            }
        }
        return glyphs
    }

    private fun directGlyphs(input: OpenTypeRunInput): List<OpenTypeShapedGlyph> {
        val direct = input.directGlyphInput ?: return emptyList()
        return direct.glyphIds.zip(direct.sourceUtf16Ranges).mapIndexed { index, (glyphId, sourceRange) ->
            OpenTypeShapedGlyph(
                glyphIndex = index,
                glyphId = glyphId,
                clusterIndex = index,
                sourceUtf16Range = sourceRange,
                source = "direct-glyph-input",
                xAdvance = 1f,
            )
        }
    }

    private fun clusterMappingsFor(
        input: OpenTypeRunInput,
        glyphs: List<OpenTypeShapedGlyph>,
    ): List<OpenTypeClusterMapping> {
        if (input.directGlyphInput != null) {
            return input.directGlyphInput.glyphIds.zip(input.directGlyphInput.sourceUtf16Ranges).mapIndexed { index, (_, range) ->
                OpenTypeClusterMapping(
                    clusterIndex = index,
                    sourceUtf16Range = range,
                    glyphRange = index..index,
                    synthetic = true,
                )
            }
        }
        return input.clusters.mapIndexedNotNull { clusterIndex, cluster ->
            val clusterGlyphs = glyphs.filter { glyph -> glyph.clusterIndex == clusterIndex }
            if (clusterGlyphs.isEmpty()) return@mapIndexedNotNull null
            OpenTypeClusterMapping(
                clusterIndex = clusterIndex,
                sourceUtf16Range = cluster.utf16Range,
                glyphRange = clusterGlyphs.first().glyphIndex..clusterGlyphs.last().glyphIndex,
                synthetic = false,
            )
        }
    }

    private fun traceFor(
        dumpId: String,
        stage: String,
        input: OpenTypeRunInput,
        sourceTextHash: String,
        diagnostics: List<ShapingDiagnostic>,
    ): OpenTypeLookupTrace {
        val requests = input.lookupTraceRequests.filter { it.stage == stage }
        val events = when {
            requests.isNotEmpty() -> requests.map { request ->
                val diagnosticCode = when (request.status) {
                    "unsupported" -> TEXT_SHAPING_LOOKUP_TYPE_UNSUPPORTED_DIAGNOSTIC_CODE
                    "malformed" -> TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE
                    else -> null
                }
                OpenTypeLookupTraceEvent(
                    stage = stage,
                    lookupId = request.lookupId,
                    lookupType = request.lookupType,
                    decision = request.status,
                    featureTags = input.features.enabled.map { it.tag },
                    diagnosticCode = diagnosticCode,
                )
            }
            input.directGlyphInput != null -> listOf(
                OpenTypeLookupTraceEvent(
                    stage = stage,
                    lookupId = "direct-glyph-input",
                    lookupType = null,
                    decision = "direct-glyph-input-bypass",
                    featureTags = input.features.enabled.map { it.tag },
                    diagnosticCode = null,
                ),
            )
            else -> listOf(
                OpenTypeLookupTraceEvent(
                    stage = stage,
                    lookupId = "${stage.lowercase()}-noop",
                    lookupType = null,
                    decision = "no-op-contract",
                    featureTags = input.features.enabled.map { it.tag },
                    diagnosticCode = null,
                ),
            )
        }
        val traceDiagnostics = diagnostics.filter { diagnostic ->
            diagnostic.appliesToOpenTypeTraceStage(stage)
        }
        return OpenTypeLookupTrace(
            dumpId = dumpId,
            stage = stage,
            unicodeVersion = unicodeVersion,
            sourceTextHash = sourceTextHash,
            typefaceId = input.typefaceId,
            scriptRun = input.scriptRun,
            features = input.features,
            events = events,
            diagnostics = traceDiagnostics.distinctForOpenTypeEvidence(),
        )
    }
}

private val gsubFeatureTags = setOf("ccmp", "locl", "liga", "rlig", "clig", "calt")
private val gposFeatureTags = setOf("kern", "mark", "mkmk", "dist", "abvm", "blwm")
private val gdefFeatureTags = setOf("mark", "mkmk", "rlig", "liga")

private val OpenTypeLayoutNonClaims = listOf(
    "no-complete-target-support-claim",
    "no-complex-shaping-support-claim",
    "no-gsub-gpos-lookup-implementation-claim",
    "no-native-shaper-oracle-claim",
    "no-gpu-text-route-claim",
)

private fun List<ShapingDiagnostic>.distinctForOpenTypeEvidence(): List<ShapingDiagnostic> =
    distinctBy { Triple(it.code, it.message, it.textRange) }

private fun ShapingDiagnostic.appliesToOpenTypeTraceStage(stage: String): Boolean =
    when (code) {
        TEXT_SHAPING_ENGINE_CONTRACT_MISSING_DIAGNOSTIC_CODE ->
            message.startsWith("$stage table ")
        TEXT_SHAPING_LOOKUP_TYPE_UNSUPPORTED_DIAGNOSTIC_CODE,
        TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE ->
            message.startsWith("$stage ")
        else -> false
    }

private fun textRangeFor(input: OpenTypeRunInput): IntRange =
    if (input.text.isEmpty()) 0..-1 else 0..input.text.lastIndex

private data class OpenTypeCodePointRange(
    val codePoint: Int,
    val textRange: IntRange,
)

private fun codePointRangesForOpenType(text: String, range: IntRange): List<OpenTypeCodePointRange> {
    val ranges = mutableListOf<OpenTypeCodePointRange>()
    var index = range.first
    while (index <= range.last && index in text.indices) {
        val codePoint = Character.codePointAt(text, index)
        val charCount = Character.charCount(codePoint)
        ranges += OpenTypeCodePointRange(codePoint, index untilExclusive index + charCount)
        index += charCount
    }
    return ranges
}

private infix fun Int.untilExclusive(endExclusive: Int): IntRange =
    this..(endExclusive - 1)

private fun OpenTypeShapingPlan.toCanonicalJson(diagnostics: List<ShapingDiagnostic>): String = buildString {
    append("{\n")
    append("  \"schemaVersion\": 1,\n")
    append("  \"dumpId\": \"shaping-plan\",\n")
    append("  \"ownerTickets\": [\"KFONT-M6-001\"],\n")
    appendJsonField("unicodeVersion", unicodeVersion, comma = true)
    appendJsonField("sourceTextHashAlgorithm", "SHA-256-UTF-8", comma = true)
    appendJsonField("sourceTextHash", sourceTextHash, comma = true)
    appendJsonField("textRange", textRange.toRangeLabel(), comma = true)
    appendJsonField("typefaceId", typefaceId?.value?.toString(), comma = true)
    append("  \"scriptRun\": ${scriptRun.toPlanJson()},\n")
    append("  \"bidi\": {\"level\": $bidiLevel, \"direction\": ${jsonString(direction)}},\n")
    appendJsonField("languageSystem", languageSystem, comma = true)
    append("  \"features\": ${features.toCanonicalJson()},\n")
    append("  \"traceRefs\": {\"gsub\": ${jsonString(gsubTraceRef)}, \"gpos\": ${jsonString(gposTraceRef)}},\n")
    appendJsonField("fallbackRun", fallbackRun, comma = true)
    append("  \"directGlyphInput\": $directGlyphInput,\n")
    append("  \"diagnostics\": ${diagnostics.toDiagnosticJsonArray()},\n")
    append("  \"nonClaims\": ${jsonStringList(OpenTypeLayoutNonClaims)}\n")
    append("}\n")
}

private fun OpenTypeShapingPlanCase.toCanonicalJson(): String = buildString {
    val plan = result.shapingPlan
    append("{\n")
    appendJsonField("caseId", caseId, comma = true)
    appendJsonField("sourceTextHash", plan.sourceTextHash, comma = true)
    appendJsonField("textRange", plan.textRange.toRangeLabel(), comma = true)
    appendJsonField("typefaceId", plan.typefaceId?.value?.toString(), comma = true)
    append("  \"scriptRun\": ${plan.scriptRun.toPlanJson()},\n")
    append("  \"bidi\": {\"level\": ${plan.bidiLevel}, \"direction\": ${jsonString(plan.direction)}},\n")
    appendJsonField("languageSystem", plan.languageSystem, comma = true)
    append("  \"features\": ${plan.features.toCanonicalJson()},\n")
    append("  \"traceRefs\": {\"gsub\": ${jsonString(plan.gsubTraceRef)}, \"gpos\": ${jsonString(plan.gposTraceRef)}},\n")
    appendJsonField("fallbackRun", plan.fallbackRun, comma = true)
    append("  \"directGlyphInput\": ${plan.directGlyphInput},\n")
    append("  \"diagnostics\": ${result.diagnostics.toDiagnosticJsonArray()}\n")
    append("}\n")
}

private fun OpenTypeLookupTrace.toCanonicalJson(): String = buildString {
    append("{\n")
    append("  \"schemaVersion\": 1,\n")
    append("  \"dumpId\": ${jsonString(dumpId)},\n")
    append("  \"ownerTickets\": [\"KFONT-M6-001\"],\n")
    appendJsonField("unicodeVersion", unicodeVersion, comma = true)
    appendJsonField("sourceTextHashAlgorithm", "SHA-256-UTF-8", comma = true)
    appendJsonField("sourceTextHash", sourceTextHash, comma = true)
    appendJsonField("stage", stage, comma = true)
    appendJsonField("typefaceId", typefaceId?.value?.toString(), comma = true)
    append("  \"scriptRun\": ${scriptRun.toPlanJson()},\n")
    append("  \"features\": ${features.toCanonicalJson()},\n")
    append("  \"events\": ${events.joinToString(prefix = "[", postfix = "]") { it.toCanonicalJson() }},\n")
    append("  \"diagnostics\": ${diagnostics.toDiagnosticJsonArray()},\n")
    append("  \"nonClaims\": ${jsonStringList(OpenTypeLayoutNonClaims)}\n")
    append("}\n")
}

private fun OpenTypeShapedRun.toCanonicalJson(): String = buildString {
    append("{\n")
    append("  \"schemaVersion\": 1,\n")
    append("  \"dumpId\": \"opentype-layout-contract-shaped-glyph-run\",\n")
    append("  \"ownerTickets\": [\"KFONT-M6-001\"],\n")
    appendJsonField("unicodeVersion", unicodeVersion, comma = true)
    appendJsonField("sourceTextHashAlgorithm", "SHA-256-UTF-8", comma = true)
    appendJsonField("sourceTextHash", sourceTextHash, comma = true)
    appendJsonField("typefaceId", typefaceId?.value?.toString(), comma = true)
    appendJsonField("script", script, comma = true)
    appendJsonField("direction", direction, comma = true)
    append("  \"glyphs\": ${glyphs.joinToString(prefix = "[", postfix = "]") { it.toCanonicalJson() }},\n")
    append("  \"clusters\": ${clusters.joinToString(prefix = "[", postfix = "]") { it.toCanonicalJson() }},\n")
    append("  \"diagnostics\": ${diagnostics.toDiagnosticJsonArray()},\n")
    append("  \"nonClaims\": ${jsonStringList(OpenTypeLayoutNonClaims)}\n")
    append("}\n")
}

private fun ScriptItemizationRun.toPlanJson(): String = buildString {
    append("{")
    append(jsonPair("clusterRange", clusterRange.toRangeLabel())).append(", ")
    append(jsonPair("utf16Range", utf16Range.toRangeLabel())).append(", ")
    append(jsonPair("codePointRange", codePointRange.toRangeLabel())).append(", ")
    append(jsonPair("selectedScript", selectedScript)).append(", ")
    append(jsonString("openTypeScriptTags")).append(": ").append(jsonStringList(openTypeScriptTags)).append(", ")
    append(jsonString("extensionCandidates")).append(": ").append(jsonStringList(extensionCandidates)).append(", ")
    append(jsonPair("languageHint", languageHint)).append(", ")
    append(jsonPair("reason", reason))
    append("}")
}

private fun ScriptFeaturePolicy.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("scriptFamily", scriptFamily)).append(", ")
    append(jsonString("selectedScripts")).append(": ").append(jsonStringList(selectedScripts)).append(", ")
    append(jsonString("openTypeScriptTags")).append(": ").append(jsonStringList(openTypeScriptTags)).append(", ")
    append(jsonString("requiredDefaults")).append(": ").append(jsonStringList(requiredDefaults)).append(", ")
    append(jsonString("optionalFeatures")).append(": ").append(jsonStringList(optionalFeatures)).append(", ")
    append(jsonString("refusalWhenMissing")).append(": ").append(jsonStringList(refusalWhenMissing))
    append("}")
}

private fun ResolvedFeatureSet.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonString("requested")).append(": ").append(requested.toFeatureArrayJson()).append(", ")
    append(jsonString("enabled")).append(": ").append(enabled.toFeatureArrayJson()).append(", ")
    append(jsonString("disabled")).append(": ").append(disabled.toFeatureArrayJson()).append(", ")
    append(jsonString("defaulted")).append(": ").append(defaulted.toFeatureArrayJson()).append(", ")
    append(jsonString("unsupported")).append(": ").append(unsupported.toFeatureArrayJson())
    append("}")
}

private fun List<ShapingFeatureRequest>.toFeatureArrayJson(): String =
    joinToString(prefix = "[", postfix = "]") { feature ->
        "{\"tag\": ${jsonString(feature.tag)}, \"value\": ${feature.value}}"
    }

private fun OpenTypeLookupTraceEvent.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("stage", stage)).append(", ")
    append(jsonPair("lookupId", lookupId)).append(", ")
    append(jsonString("lookupType")).append(": ").append(lookupType ?: "null").append(", ")
    append(jsonPair("decision", decision)).append(", ")
    append(jsonString("featureTags")).append(": ").append(jsonStringList(featureTags)).append(", ")
    append(jsonPair("diagnosticCode", diagnosticCode))
    append("}")
}

private fun OpenTypeShapedGlyph.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonString("glyphIndex")).append(": ").append(glyphIndex).append(", ")
    append(jsonString("glyphId")).append(": ").append(glyphId).append(", ")
    append(jsonString("clusterIndex")).append(": ").append(clusterIndex).append(", ")
    append(jsonPair("sourceUtf16Range", sourceUtf16Range.toRangeLabel())).append(", ")
    append(jsonPair("source", source)).append(", ")
    append(jsonString("xAdvance")).append(": ").append(xAdvance).append(", ")
    append(jsonString("xOffset")).append(": ").append(xOffset).append(", ")
    append(jsonString("yOffset")).append(": ").append(yOffset)
    append("}")
}

private fun OpenTypeClusterMapping.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonString("clusterIndex")).append(": ").append(clusterIndex).append(", ")
    append(jsonPair("sourceUtf16Range", sourceUtf16Range.toRangeLabel())).append(", ")
    append(jsonPair("glyphRange", glyphRange.toRangeLabel())).append(", ")
    append(jsonString("synthetic")).append(": ").append(synthetic)
    append("}")
}

private fun List<ShapingDiagnostic>.toDiagnosticJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { diagnostic -> diagnostic.toCanonicalJson() }

private fun ShapingDiagnostic.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("code", code)).append(", ")
    append(jsonPair("severity", "refusal")).append(", ")
    append(jsonPair("textRange", textRange?.toRangeLabel())).append(", ")
    append(jsonPair("message", message))
    append("}")
}

private fun StringBuilder.appendJsonField(name: String, value: String?, comma: Boolean) {
    append("  ").append(jsonPair(name, value))
    if (comma) append(",")
    append("\n")
}

private fun String.sourceTextHashForOpenTypeLayout(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

private fun IntRange.toRangeLabel(): String =
    if (isEmpty()) "" else "$first..$last"

private fun jsonPair(name: String, value: String?): String =
    jsonString(name) + ": " + if (value == null) "null" else jsonString(value)

private fun jsonStringList(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { value -> jsonString(value) }

private fun jsonString(value: String): String = buildString {
    append('"')
    for (char in value) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char.code < 0x20 || char.isSurrogate()) {
                    append("\\u").append(char.code.toString(16).uppercase().padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
    append('"')
}
