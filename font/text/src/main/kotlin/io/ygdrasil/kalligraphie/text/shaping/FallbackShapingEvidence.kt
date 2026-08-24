package io.ygdrasil.kalligraphie.text.shaping

import io.ygdrasil.kalligraphie.font.FallbackEvidenceCase
import io.ygdrasil.kalligraphie.font.FallbackRequest
import io.ygdrasil.kalligraphie.font.FontFace
import io.ygdrasil.kalligraphie.font.FontResolver
import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import io.ygdrasil.kalligraphie.font.ResolvedFontRun
import io.ygdrasil.kalligraphie.font.ResolvedFontRunEvidence
import io.ygdrasil.kalligraphie.font.TypefaceData
import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.font.defaultFallbackEvidenceCases

private val FallbackShapingEvidenceNonClaims = listOf(
    "no-cluster-safe-fallback-claim",
    "no-complete-target-support-claim",
    "no-platform-font-fallback-claim",
    "no-shaping-engine-claim",
)

private data class FallbackDumpRef(
    val dumpId: String,
    val fixtureId: String,
)

private data class FallbackShapedGlyphRunCase(
    val fixtureId: String,
    val request: FallbackRequest,
    val selectedTypefaceIds: List<TypefaceID>,
    val rejectedTypefaceIds: List<TypefaceID>,
    val glyphRuns: List<ShapedGlyphRun>,
    val diagnostics: List<ShapingDiagnostic>,
)

public fun defaultFallbackShapedGlyphRunEvidenceJson(): String {
    val cases = defaultFallbackEvidenceCases()
        .sortedBy { evidenceCase -> evidenceCase.fixtureId }
        .map(::buildFallbackShapedGlyphRunCase)
    return buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"fallback-shaped-glyph-run\",\n")
        append("  \"ownerTickets\": [\"KFONT-M7-002\", \"KFONT-M7-003\"],\n")
        append("  \"cases\": [\n")
        append(cases.joinToString(",\n") { case -> case.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"nonClaims\": ${jsonStringList(FallbackShapingEvidenceNonClaims)}\n")
        append("}\n")
    }
}

private fun buildFallbackShapedGlyphRunCase(case: FallbackEvidenceCase): FallbackShapedGlyphRunCase {
    val facesById = linkedMapOf<TypefaceID, FontFace>()
    val resolvedRuns = case.runs.map { run ->
        val face = facesById.getOrPut(run.typefaceId) {
            syntheticFace(
                typefaceId = run.typefaceId,
                familyName = run.familyName,
                hostDependent = run.hostDependent,
            )
        }
        ResolvedFontRun(start = run.start, end = run.end, face = face)
    }
    val glyphMapper = fallbackEvidenceGlyphMapper(case)
    val result = FallbackOpenTypeShapingEngine(
        fontResolver = FixedFontResolver(resolvedRuns),
        glyphMapper = glyphMapper,
    ).shape(
        ShapingRequest(
            text = case.request.text,
            locale = case.request.locale,
            preferredFamilies = case.request.preferredFamilies,
            fontSize = 12f,
        ),
    )
    return FallbackShapedGlyphRunCase(
        fixtureId = case.fixtureId,
        request = case.request,
        selectedTypefaceIds = case.decisions.mapNotNull { decision -> decision.selectedTypefaceId }.distinctBy { id -> id.value },
        rejectedTypefaceIds = case.decisions.flatMap { decision ->
            decision.candidates.filter { candidate -> candidate.rejectionReason != null }.map { candidate -> candidate.typefaceId }
        }.distinctBy { id -> id.value },
        glyphRuns = result.glyphRuns,
        diagnostics = result.diagnostics.distinctBy { diagnostic -> Triple(diagnostic.code, diagnostic.message, diagnostic.textRange) },
    )
}

private fun fallbackEvidenceGlyphMapper(case: FallbackEvidenceCase): GlyphMapper {
    val glyphIdsByTypeface = linkedMapOf<TypefaceID, MutableMap<Int, Int>>()
    case.decisions.forEachIndexed { index, decision ->
        if (decision.covered) {
            val typefaceId = decision.selectedTypefaceId ?: return@forEachIndexed
            glyphIdsByTypeface.getOrPut(typefaceId) { linkedMapOf() }.putIfAbsent(decision.codePoint, 100 + index)
        }
    }
    return object : GlyphMapper {
        override fun glyphIdFor(typefaceId: TypefaceID?, codePoint: Int): Int? =
            typefaceId?.let { id -> glyphIdsByTypeface[id]?.get(codePoint) }
    }
}

private fun syntheticFace(
    typefaceId: TypefaceID,
    familyName: String,
    hostDependent: Boolean,
): FontFace =
    FontFace(
        typeface = TypefaceData(
            id = typefaceId,
            source = FontSource(
                id = FontSourceID(typefaceId.value),
                kind = if (hostDependent) FontSourceKind.SYSTEM_SCANNED else FontSourceKind.MEMORY,
                displayName = familyName,
                bytes = ByteArray(0),
            ),
            familyName = familyName,
            styleName = "Regular",
        ),
    )

private class FixedFontResolver(
    private val runs: List<ResolvedFontRun>,
) : FontResolver {
    override fun resolve(request: FallbackRequest): List<ResolvedFontRun> = runs
}

private fun FallbackShapedGlyphRunCase.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("fixtureId", fixtureId)).append(",")
    append(jsonString("request")).append(":").append(request.toCanonicalJson()).append(",")
    append(jsonString("decisionTraceRef")).append(":").append(FallbackDumpRef("fallback-decision-trace", fixtureId).toCanonicalJson()).append(",")
    append(jsonString("resolvedRunsRef")).append(":").append(FallbackDumpRef("resolved-font-runs", fixtureId).toCanonicalJson()).append(",")
    append(jsonString("fixtureAssetRef")).append(":").append(FallbackDumpRef("fallback-fixture", fixtureId).toCanonicalJson()).append(",")
    append(jsonString("selectedTypefaceIds")).append(":").append(selectedTypefaceIds.toTypefaceIdArrayJson()).append(",")
    append(jsonString("rejectedTypefaceIds")).append(":").append(rejectedTypefaceIds.toTypefaceIdArrayJson()).append(",")
    append(jsonString("glyphRuns")).append(":").append(glyphRuns.toShapedRunArrayJson()).append(",")
    append(jsonString("diagnostics")).append(":").append(diagnostics.toDiagnosticJsonArray())
    append("}")
}

private fun FallbackDumpRef.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("dumpId", dumpId)).append(",")
    append(jsonPair("fixtureId", fixtureId))
    append("}")
}

private fun FallbackRequest.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("text", text)).append(",")
    append(jsonPair("locale", locale)).append(",")
    append(jsonString("preferredFamilies")).append(":").append(jsonStringList(preferredFamilies)).append(",")
    append(jsonString("style")).append(":{")
    append(jsonString("weight")).append(":").append(style.weight).append(",")
    append(jsonString("width")).append(":").append(style.width).append(",")
    append(jsonPair("slant", style.slant.serializedName))
    append("}")
    if (variationCoordinates.isNotEmpty()) {
        append(",")
        append(jsonString("variationCoordinates")).append(":").append(
            variationCoordinates.joinToString(prefix = "[", postfix = "]", separator = ",") { coordinate ->
                """{"axisTag":${jsonString(coordinate.axisTag)},"value":${coordinate.value}}"""
            },
        )
    }
    if (namedInstance != null) {
        append(",")
        append(jsonPair("namedInstance", namedInstance))
    }
    append("}")
}

private fun List<TypefaceID>.toTypefaceIdArrayJson(): String =
    joinToString(prefix = "[", postfix = "]") { id -> jsonString(id.value.toString()) }

private fun List<ShapedGlyphRun>.toShapedRunArrayJson(): String =
    joinToString(prefix = "[", postfix = "]") { run -> run.toCanonicalJson() }

private fun ShapedGlyphRun.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("typefaceId", typefaceId?.value?.toString())).append(",")
    append(jsonPair("script", script)).append(",")
    append(jsonString("bidiLevel")).append(":").append(bidiLevel).append(",")
    append(jsonString("fontSize")).append(":").append(fontSize).append(",")
    append(jsonString("advanceX")).append(":").append(advanceX).append(",")
    append(jsonString("advanceY")).append(":").append(advanceY).append(",")
    append(jsonString("glyphIds")).append(":").append(glyphIds.joinToString(prefix = "[", postfix = "]")).append(",")
    append(jsonString("clusters")).append(":").append(clusters.toClusterArrayJson())
    append("}")
}

private fun List<GlyphCluster>.toClusterArrayJson(): String =
    joinToString(prefix = "[", postfix = "]") { cluster -> cluster.toCanonicalJson() }

private fun GlyphCluster.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("textRange", textRange.toRangeLabel())).append(",")
    append(jsonPair("glyphRange", glyphRange.toRangeLabel())).append(",")
    append(jsonString("advanceX")).append(":").append(advanceX).append(",")
    append(jsonString("offsetX")).append(":").append(offsetX).append(",")
    append(jsonString("offsetY")).append(":").append(offsetY)
    append("}")
}

private fun List<ShapingDiagnostic>.toDiagnosticJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { diagnostic -> diagnostic.toCanonicalJson() }

private fun ShapingDiagnostic.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("code", code)).append(",")
    append(jsonPair("severity", "refusal")).append(",")
    append(jsonPair("textRange", textRange?.toRangeLabel())).append(",")
    append(jsonPair("message", message))
    append("}")
}

private fun IntRange.toRangeLabel(): String =
    if (isEmpty()) "" else "$first..$last"

private fun jsonPair(name: String, value: String?): String =
    jsonString(name) + ":" + if (value == null) "null" else jsonString(value)

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

private fun jsonStringList(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { value -> jsonString(value) }

private val io.ygdrasil.kalligraphie.font.FontSlant.serializedName: String
    get() = when (this) {
        io.ygdrasil.kalligraphie.font.FontSlant.UPRIGHT -> "upright"
        io.ygdrasil.kalligraphie.font.FontSlant.ITALIC -> "italic"
        io.ygdrasil.kalligraphie.font.FontSlant.OBLIQUE -> "oblique"
    }
