package io.ygdrasil.kalligraphie.glyph

import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Identifies the strike-specific inputs that affect glyph rasterization and cache reuse.
 *
 * @property typefaceId Stable identifier for the selected typeface.
 * @property glyphId font-specific glyph identifier when the key is bound to one glyph.
 * @property clusterFingerprint source cluster facts when glyph output depends on text cluster
 * shaping, variation selectors, emoji sequence, or combining marks.
 * @property sizePx requested strike size in pixels.
 * @property scaleX horizontal scale applied before glyph generation.
 * @property scaleY vertical scale applied before glyph generation.
 * @property subpixelX fractional x placement bucket.
 * @property subpixelY fractional y placement bucket.
 * @property variationCoordinates normalized variation axis coordinates keyed by axis tag.
 * @property representationRoute stable glyph representation route label when known.
 * @property maskFormat stable mask format label when the route produces a mask.
 * @property transformBucket deterministic transform class used for cache separation.
 * @property edging antialiasing or edging policy that affects generated coverage.
 * @property sdfSpreadPx SDF spread in source pixels when an SDF route is selected.
 * @property sdfSourceResolutionPx source SDF resolution selected for this strike.
 * @property paletteIdentity palette identity for color glyph routes when applicable.
 * @property unicodeDataVersion Unicode data version when cluster or emoji facts affect output.
 * @property rendererDescriptorVersion route-specific renderer descriptor version for SVG, COLR,
 * bitmap, or masks.
 * @property rendererVersion legacy source-compatible alias for [rendererDescriptorVersion].
 */
@Suppress("DEPRECATION")
data class GlyphStrikeKey(
    val typefaceId: TypefaceID,
    val sizePx: Float,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val subpixelX: Float = 0f,
    val subpixelY: Float = 0f,
    val variationCoordinates: Map<String, Float> = emptyMap(),
    val representationRoute: String = UnspecifiedRepresentationRoute,
    val maskFormat: String = NoMaskFormat,
    val transformBucket: String = DefaultTransformBucket,
    val edging: String = DefaultEdging,
    val sdfSpreadPx: Float? = null,
    val sdfSourceResolutionPx: Float? = null,
    val paletteIdentity: String? = null,
    val unicodeDataVersion: String? = null,
    @Deprecated("Use rendererDescriptorVersion.", ReplaceWith("rendererDescriptorVersion"))
    val rendererVersion: String? = null,
    val rendererDescriptorVersion: String? = null,
    val glyphId: Int? = null,
    val clusterFingerprint: GlyphClusterFingerprint? = null,
) {
    init {
        require(
            rendererVersion == null ||
                rendererDescriptorVersion == null ||
                rendererVersion == rendererDescriptorVersion,
        ) {
                "rendererVersion and rendererDescriptorVersion must match when both are present."
        }
        require(glyphId == null || glyphId >= 0) { "Glyph strike glyphId must be non-negative when present." }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlyphStrikeKey) return false

        return typefaceId == other.typefaceId &&
            sizePx.glyphBitsEqual(other.sizePx) &&
            scaleX.glyphBitsEqual(other.scaleX) &&
            scaleY.glyphBitsEqual(other.scaleY) &&
            subpixelX.glyphBitsEqual(other.subpixelX) &&
            subpixelY.glyphBitsEqual(other.subpixelY) &&
            variationCoordinates == other.variationCoordinates &&
            representationRoute == other.representationRoute &&
            maskFormat == other.maskFormat &&
            transformBucket == other.transformBucket &&
            edging == other.edging &&
            sdfSpreadPx.glyphBitsEqual(other.sdfSpreadPx) &&
            sdfSourceResolutionPx.glyphBitsEqual(other.sdfSourceResolutionPx) &&
            paletteIdentity == other.paletteIdentity &&
            unicodeDataVersion == other.unicodeDataVersion &&
            effectiveRendererDescriptorVersion() == other.effectiveRendererDescriptorVersion() &&
            glyphId == other.glyphId &&
            clusterFingerprint == other.clusterFingerprint
    }

    override fun hashCode(): Int {
        var result = typefaceId.hashCode()
        result = 31 * result + sizePx.toBits()
        result = 31 * result + scaleX.toBits()
        result = 31 * result + scaleY.toBits()
        result = 31 * result + subpixelX.toBits()
        result = 31 * result + subpixelY.toBits()
        result = 31 * result + variationCoordinates.hashCode()
        result = 31 * result + representationRoute.hashCode()
        result = 31 * result + maskFormat.hashCode()
        result = 31 * result + transformBucket.hashCode()
        result = 31 * result + edging.hashCode()
        result = 31 * result + (sdfSpreadPx?.toBits() ?: 0)
        result = 31 * result + (sdfSourceResolutionPx?.toBits() ?: 0)
        result = 31 * result + (paletteIdentity?.hashCode() ?: 0)
        result = 31 * result + (unicodeDataVersion?.hashCode() ?: 0)
        result = 31 * result + (effectiveRendererDescriptorVersion()?.hashCode() ?: 0)
        result = 31 * result + (glyphId ?: 0)
        result = 31 * result + (clusterFingerprint?.hashCode() ?: 0)
        return result
    }

    /**
     * Serializes every strike and glyph fact that can affect rendered output into canonical JSON.
     *
     * The output is intentionally independent of map iteration order and object identity so it can
     * be used directly in route diagnostics, golden fixtures, PM evidence, and cache-key audits.
     *
     * @param glyphId font-specific glyph identifier represented by this strike entry.
     * @return stable JSON preimage ending with a newline.
     */
    fun canonicalPreimage(glyphId: Int): String {
        requireFiniteStrikeFacts()
        require(this.glyphId == null || this.glyphId == glyphId) {
            "Glyph strike key glyphId ${this.glyphId} does not match requested glyphId $glyphId."
        }

        return buildString {
            append("{\n")
            appendGlyphJsonField("schema", PreimageSchema, comma = true)
            appendGlyphJsonField("typefaceId", typefaceId.value.toString(), comma = true)
            appendGlyphJsonField("glyphId", glyphId, comma = true)
            append("  \"clusterFingerprint\": ")
            append(clusterFingerprint?.toCanonicalJson() ?: "null")
            append(",\n")
            appendGlyphJsonField("sizePx", sizePx, comma = true)
            appendGlyphJsonField("scaleX", scaleX, comma = true)
            appendGlyphJsonField("scaleY", scaleY, comma = true)
            appendGlyphJsonField("transformBucket", transformBucket, comma = true)
            append("  \"subpixelBucket\": {\"x\": ")
            append(glyphFloatToken(subpixelX))
            append(", \"y\": ")
            append(glyphFloatToken(subpixelY))
            append("},\n")
            appendGlyphJsonField("route", representationRoute, comma = true)
            appendGlyphJsonField("maskFormat", maskFormat, comma = true)
            appendGlyphJsonField("edging", edging, comma = true)
            append("  \"variationCoordinates\": ")
            appendVariationCoordinatesJson(variationCoordinates)
            append(",\n")
            append("  \"paletteIdentity\": ")
            append(glyphJsonNullableString(paletteIdentity))
            append(",\n")
            append("  \"sdf\": {\"spreadPx\": ")
            append(glyphNullableFloatToken(sdfSpreadPx))
            append(", \"sourceResolutionPx\": ")
            append(glyphNullableFloatToken(sdfSourceResolutionPx))
            append("},\n")
            append("  \"unicodeDataVersion\": ")
            append(glyphJsonNullableString(unicodeDataVersion))
            append(",\n")
            append("  \"rendererDescriptorVersion\": ")
            append(glyphJsonNullableString(effectiveRendererDescriptorVersion()))
            append("\n")
            append("}\n")
        }
    }

    /**
     * Computes a SHA-256 hash of [canonicalPreimage] for compact diagnostics and cache evidence.
     *
     * @param glyphId font-specific glyph identifier represented by this strike entry.
     * @return lowercase hexadecimal SHA-256 digest of the canonical preimage.
     */
    fun preimageSha256(glyphId: Int): String =
        glyphSha256(canonicalPreimage(glyphId).toByteArray(Charsets.UTF_8))

    /**
     * Serializes this key using its embedded glyph ID.
     */
    fun canonicalPreimage(): String =
        canonicalPreimage(requireGlyphId())

    /**
     * Computes a compact hash using this key's embedded glyph ID.
     */
    fun preimageSha256(): String =
        preimageSha256(requireGlyphId())

    /**
     * Returns the canonical renderer descriptor value, accepting the legacy constructor alias.
     */
    internal fun effectiveRendererDescriptorVersion(): String? =
        rendererDescriptorVersion ?: rendererVersion

    private fun requireGlyphId(): Int =
        glyphId ?: error("GlyphStrikeKey requires glyphId for this operation.")

    /**
     * Shared canonical labels for strike-key dumps.
     */
    companion object {
        /**
         * Stable schema label included in every canonical strike-key preimage.
         */
        const val PreimageSchema: String = "org.graphiks.kanvas.glyph.GlyphStrikeKey.v1"

        /**
         * Default route label for existing callers that have not selected a representation route.
         */
        const val UnspecifiedRepresentationRoute: String = "text.glyph.route-unspecified"

        /**
         * Default mask format for outline, deferred, or route-unspecified strikes.
         */
        const val NoMaskFormat: String = "none"

        /**
         * Default transform bucket for existing callers that only provide scale fields.
         */
        const val DefaultTransformBucket: String = "identity"

        /**
         * Default coverage policy for current grayscale mask generation.
         */
        const val DefaultEdging: String = "antialias"
    }
}

/**
 * Stable, content-only facts for the source text cluster that produced one glyph.
 *
 * The fingerprint deliberately avoids storing source text in the key. The optional hash lets
 * Unicode-sensitive routes distinguish cluster content without leaking mutable string identity or
 * platform handles into cache evidence.
 *
 * @property sourceUtf16Start start offset in the source UTF-16 input.
 * @property sourceUtf16EndExclusive exclusive end offset in the source UTF-16 input.
 * @property codePointCount number of Unicode scalar values in the cluster.
 * @property graphemeClusterCount number of grapheme clusters represented by the key.
 * @property clusterSha256 optional lowercase SHA-256 over the normalized cluster payload.
 */
data class GlyphClusterFingerprint(
    val sourceUtf16Start: Int,
    val sourceUtf16EndExclusive: Int,
    val codePointCount: Int,
    val graphemeClusterCount: Int,
    val clusterSha256: String? = null,
) {
    init {
        require(sourceUtf16Start >= 0) { "Cluster sourceUtf16Start must be non-negative." }
        require(sourceUtf16EndExclusive >= sourceUtf16Start) {
            "Cluster sourceUtf16EndExclusive must be greater than or equal to sourceUtf16Start."
        }
        require(codePointCount >= 0) { "Cluster codePointCount must be non-negative." }
        require(graphemeClusterCount >= 0) { "Cluster graphemeClusterCount must be non-negative." }
        require(clusterSha256 == null || clusterSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Cluster SHA-256 must be lowercase hexadecimal when present."
        }
    }

    /**
     * Serializes cluster facts as an inline stable JSON object.
     */
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(glyphJsonString("sourceUtf16Start")).append(": ").append(sourceUtf16Start).append(", ")
        append(glyphJsonString("sourceUtf16EndExclusive")).append(": ").append(sourceUtf16EndExclusive).append(", ")
        append(glyphJsonString("codePointCount")).append(": ").append(codePointCount).append(", ")
        append(glyphJsonString("graphemeClusterCount")).append(": ").append(graphemeClusterCount).append(", ")
        append(glyphJsonString("clusterSha256")).append(": ").append(glyphJsonNullableString(clusterSha256))
        append("}")
    }
}

/**
 * One dumpable route-specific strike-key preimage and compact hash.
 */
data class GlyphStrikeKeyRoutePreimage(
    val glyphId: Int,
    val route: String,
    val compactHash: String,
    val preimage: String,
) {
    /**
     * Serializes this route preimage as stable JSON.
     */
    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonField("glyphId", glyphId, comma = true)
        appendGlyphJsonField("route", route, comma = true)
        appendGlyphJsonField("compactHash", compactHash, comma = true)
        append("  \"preimage\": ")
        append(preimage.trimEnd().indentJsonContinuation("  "))
        append("\n}")
    }
}

/**
 * Builds a dumpable strike-key route preimage for fixture evidence.
 */
fun GlyphStrikeKey.toRoutePreimage(glyphId: Int): GlyphStrikeKeyRoutePreimage =
    GlyphStrikeKeyRoutePreimage(
        glyphId = glyphId,
        route = representationRoute,
        compactHash = preimageSha256(glyphId),
        preimage = canonicalPreimage(glyphId),
    )

/**
 * Builds a dumpable strike-key route preimage from a key with an embedded glyph ID.
 */
fun GlyphStrikeKey.toRoutePreimage(): GlyphStrikeKeyRoutePreimage {
    val resolvedGlyphId = glyphId ?: error("GlyphStrikeKey requires glyphId for route preimage evidence.")
    return toRoutePreimage(resolvedGlyphId)
}

/**
 * Stable negative record for requests that cannot produce a deterministic glyph strike key.
 *
 * @property glyphId glyph identifier when known.
 * @property attemptedRoute route requested by the caller.
 * @property diagnostic stable diagnostic code emitted for the refusal.
 * @property reason machine-readable refusal reason.
 * @property message human-readable refusal message.
 * @property forbiddenFields live handles, upload tokens, atlas coordinates, or other fields rejected
 * from the key preimage.
 * @property fallbackRoute explicit fallback route, when the refusal policy names one.
 * @property severity diagnostic severity.
 */
class GlyphStrikeKeyRefusal(
    val glyphId: Int?,
    val attemptedRoute: String,
    val diagnostic: String,
    val reason: String,
    val message: String,
    forbiddenFields: List<String> = emptyList(),
    val fallbackRoute: String? = null,
    val severity: String = "warning",
) {
    val forbiddenFields: List<String> = forbiddenFields.toList()

    /**
     * Serializes this refusal as stable JSON.
     */
    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonNullableField("glyphId", glyphId, comma = true)
        appendGlyphJsonField("attemptedRoute", attemptedRoute, comma = true)
        appendGlyphJsonField("diagnostic", diagnostic, comma = true)
        appendGlyphJsonField("reason", reason, comma = true)
        appendGlyphJsonField("severity", severity, comma = true)
        appendGlyphJsonField("message", message, comma = true)
        append("  \"forbiddenFields\": ")
        appendGlyphStringArrayInlineJson(forbiddenFields)
        append(",\n")
        append("  \"fallbackRoute\": ")
        append(glyphJsonNullableString(fallbackRoute))
        append("\n}")
    }

    /**
     * SHA-256 digest of [toCanonicalJson] for compact negative evidence.
     */
    val dumpSha256: String
        get() = glyphSha256(toCanonicalJson().toByteArray(Charsets.UTF_8))

    companion object {
        fun missingTypefaceId(glyphId: Int?, attemptedRoute: String): GlyphStrikeKeyRefusal =
            GlyphStrikeKeyRefusal(
                glyphId = glyphId,
                attemptedRoute = attemptedRoute,
                diagnostic = GlyphCacheKeyNondeterministicDiagnostic,
                reason = "missing-typeface-id",
                message = "Glyph strike key refused because TypefaceID is missing.",
            )

        fun nondeterministicHostSource(
            glyphId: Int?,
            attemptedRoute: String,
            hostSource: String,
        ): GlyphStrikeKeyRefusal =
            GlyphStrikeKeyRefusal(
                glyphId = glyphId,
                attemptedRoute = attemptedRoute,
                diagnostic = GlyphCacheKeyNondeterministicDiagnostic,
                reason = "host-source-nondeterministic",
                message = "Glyph strike key refused because host source '$hostSource' is not captured as deterministic font bytes.",
            )

        fun forbiddenLiveHandleFields(
            glyphId: Int?,
            attemptedRoute: String,
            forbiddenFields: List<String>,
        ): GlyphStrikeKeyRefusal =
            GlyphStrikeKeyRefusal(
                glyphId = glyphId,
                attemptedRoute = attemptedRoute,
                diagnostic = GlyphCacheKeyNondeterministicDiagnostic,
                reason = "forbidden-live-handle-fields",
                message = "Glyph strike key refused because live handles, atlas coordinates, GPU resources, or upload tokens are not key facts.",
                forbiddenFields = forbiddenFields.sorted(),
            )

        fun lcdFutureResearch(
            glyphId: Int?,
            attemptedRoute: String,
            fallbackRoute: String?,
        ): GlyphStrikeKeyRefusal =
            GlyphStrikeKeyRefusal(
                glyphId = glyphId,
                attemptedRoute = attemptedRoute,
                diagnostic = GlyphLCDFutureResearchDiagnostic,
                reason = "lcd-future-research",
                message = "LCD subpixel text remains future research and is recorded only as a refused key request.",
                fallbackRoute = fallbackRoute,
            )

        fun routeKeyGap(
            glyphId: Int?,
            attemptedRoute: String,
            missingFields: List<String>,
        ): GlyphStrikeKeyRefusal =
            GlyphStrikeKeyRefusal(
                glyphId = glyphId,
                attemptedRoute = attemptedRoute,
                diagnostic = GlyphCacheKeyNondeterministicDiagnostic,
                reason = "route-key-gap",
                message = "Glyph strike key refused because route-specific key fields are missing.",
                forbiddenFields = missingFields.sorted(),
            )
    }
}

/**
 * Aggregate deterministic evidence dump for `glyph-strike-key.json`.
 */
class GlyphStrikeKeyEvidenceDump(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    routePreimages: List<GlyphStrikeKeyRoutePreimage>,
    refusals: List<GlyphStrikeKeyRefusal>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val routePreimages: List<GlyphStrikeKeyRoutePreimage> = routePreimages.toList()
    val refusals: List<GlyphStrikeKeyRefusal> = refusals.toList()
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    /**
     * SHA-256 digest of [toCanonicalJson] content with `dumpSha256` omitted.
     */
    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    /**
     * Serializes the evidence dump as stable JSON.
     */
    fun toCanonicalJson(): String =
        canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("routePreimageCount", routePreimages.size, comma = true)
        append("  \"routePreimages\": ")
        appendGlyphRoutePreimagesJson(routePreimages)
        append(",\n")
        appendGlyphJsonField("refusalCount", refusals.size, comma = true)
        append("  \"refusals\": ")
        appendGlyphStrikeKeyRefusalsJson(refusals)
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Represents the selected intermediate representation for one glyph before final handoff to
 * GPU-facing artifact types.
 *
 * Implementations can carry outlines, alpha masks, signed-distance-field masks, or deferred color
 * glyph instructions while preserving a single cache and diagnostics surface.
 */
interface GlyphRepresentation {
    /**
     * Glyph identifier represented by this planning result.
     */
    val glyphId: Int
}

/**
 * Typed placeholder reference for a later route-specific glyph plan.
 *
 * The pure Kotlin glyph planner can select these refs without parsing or materializing the
 * underlying color/bitmap/SVG plan. They keep the route taxonomy explicit while M10 owns the plan
 * internals and M11 owns GPU artifact registration.
 */
interface GlyphArtifactPlanRef : GlyphRepresentation {
    val artifactName: String
    val planId: String
    val boundsPlaceholder: String
}

data class ColorGlyphPlanRef(
    override val glyphId: Int,
    override val planId: String,
    override val boundsPlaceholder: String = "deferred-to-color-glyph-plan",
    override val artifactName: String = "ColorGlyphPlan",
) : GlyphArtifactPlanRef {
    init {
        require(planId.isNotBlank()) { "ColorGlyphPlanRef planId must not be blank." }
        require(boundsPlaceholder.isNotBlank()) { "ColorGlyphPlanRef boundsPlaceholder must not be blank." }
        require(artifactName == "ColorGlyphPlan") {
            "ColorGlyphPlanRef artifactName must stay aligned with the M11 registry."
        }
    }
}

data class BitmapGlyphPlanRef(
    override val glyphId: Int,
    override val planId: String,
    override val boundsPlaceholder: String = "deferred-to-bitmap-glyph-plan",
    override val artifactName: String = "BitmapGlyphPlan",
) : GlyphArtifactPlanRef {
    init {
        require(planId.isNotBlank()) { "BitmapGlyphPlanRef planId must not be blank." }
        require(boundsPlaceholder.isNotBlank()) { "BitmapGlyphPlanRef boundsPlaceholder must not be blank." }
        require(artifactName == "BitmapGlyphPlan") {
            "BitmapGlyphPlanRef artifactName must stay aligned with the M11 registry."
        }
    }
}

data class SVGGlyphPlanRef(
    override val glyphId: Int,
    override val planId: String,
    override val boundsPlaceholder: String = "deferred-to-svg-glyph-plan",
    override val artifactName: String = "SVGGlyphPlan",
) : GlyphArtifactPlanRef {
    init {
        require(planId.isNotBlank()) { "SVGGlyphPlanRef planId must not be blank." }
        require(boundsPlaceholder.isNotBlank()) { "SVGGlyphPlanRef boundsPlaceholder must not be blank." }
        require(artifactName == "SVGGlyphPlan") {
            "SVGGlyphPlanRef artifactName must stay aligned with the M11 registry."
        }
    }
}

/**
 * Names the renderer-neutral representation routes supported by the module-local glyph planner.
 *
 * The route is deliberately narrower than a GPU artifact type. It only says which pure Kotlin
 * representation should be handed to the next pipeline stage, leaving atlas packing, upload
 * planning, and renderer policy outside this module.
 *
 * @property diagnosticName stable lowercase route label used in diagnostics and PM evidence.
 */
enum class GlyphArtifactRoute(
    val diagnosticName: String,
) {
    /**
     * Keeps the glyph as vector outline commands for downstream coverage or GPU path handling.
     */
    OUTLINE("outline"),

    /**
     * Uses a single-channel 8-bit alpha mask representation.
     */
    A8("a8"),

    /**
     * Uses a signed-distance-field mask representation for scale-tolerant text rendering.
     */
    SDF("sdf"),

    /**
     * Requests LCD text but keeps the route outside target support until dedicated evidence lands.
     */
    LCD("lcd"),

    /**
     * Defers to a typed COLR color glyph plan owned by the color-font stack.
     */
    COLOR("colr"),

    /**
     * Defers to a typed PNG bitmap glyph plan owned by the color-font stack.
     */
    BITMAP("bitmap"),

    /**
     * Defers to a typed SVG glyph plan owned by the color-font stack.
     */
    SVG("svg"),
}

/**
 * Stable policy facts that explain why a glyph plan chose one route over another.
 *
 * The run descriptor and strike key are carried directly by [GlyphArtifactPlan]; this value object
 * only records the additional planner policy inputs requested by the KFONT evidence contract.
 */
data class GlyphArtifactRoutePolicyInputs(
    val textStylePreference: String,
    val transformClass: String,
    val atlasBudgetClass: String,
    val sdfEligibility: String,
    val colorGlyphAvailability: String,
    val emojiSequenceFacts: String,
    val rendererCapabilitySummary: String,
)

/**
 * Describes the route preference and already-available representations for one planning pass.
 *
 * The planner is intentionally limited to choosing from supplied pure Kotlin representations. It
 * does not parse font tables, invoke a scaler, rasterize outlines, allocate atlas space, or create
 * GPU artifacts. Callers that can produce multiple representations list all of them by glyph ID,
 * and the planner selects the first representation whose route appears in [preferredRoutes].
 *
 * @property preferredRoutes ordered route preference. Earlier routes are selected before later
 * routes when multiple representations are available for the same glyph.
 * @property availableRepresentations candidate representations keyed by glyph identifier. Entries
 * for glyphs not present in the current run are ignored.
 */
data class GlyphArtifactRouteRequest(
    val preferredRoutes: List<GlyphArtifactRoute>,
    val policyInputs: GlyphArtifactRoutePolicyInputs? = null,
    val routeDiagnostics: Map<Int, List<GlyphRouteDiagnostic>> = emptyMap(),
    val availableRepresentations: Map<Int, List<GlyphRepresentation>> = emptyMap(),
)

/**
 * Plans the artifact route for a glyph run and strike.
 */
interface GlyphArtifactPlanner {
    /**
     * Builds a glyph artifact plan for a run at a specific strike.
     *
     * @param run shaped glyph run to route.
     * @param strikeKey strike inputs that affect rasterization and caching.
     * @return planned pure Kotlin glyph artifacts.
     */
    fun plan(run: ShapedGlyphRun, strikeKey: GlyphStrikeKey): GlyphArtifactPlan
}

/**
 * Records the pure Kotlin planning output for a glyph run.
 *
 * @property run source run used for planning.
 * @property strikeKey strike key used for generation and cache lookup.
 * @property representations per-glyph representations selected by the planner.
 * @property diagnostics non-fatal routing decisions and unsupported-route notes.
 * @property decisions one selected or explicitly unsupported route decision per glyph position.
 */
data class GlyphArtifactPlan(
    val run: ShapedGlyphRun,
    val strikeKey: GlyphStrikeKey,
    val policyInputs: GlyphArtifactRoutePolicyInputs? = null,
    val representations: List<GlyphRepresentation>,
    val diagnostics: List<GlyphRouteDiagnostic> = emptyList(),
    val decisions: List<GlyphArtifactPlanDecision> = emptyList(),
) {
    /**
     * SHA-256 digest of [toCanonicalGlyphArtifactPlanJson] content with `dumpSha256` omitted.
     */
    val dumpSha256: String by lazy {
        glyphSha256(canonicalArtifactPlanJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))
    }

    /**
     * Serializes selected routes, rejected alternatives, fallback policy, and diagnostics.
     *
     * @return stable `glyph-artifact-plan.json` content ending with a newline.
     */
    fun toCanonicalGlyphArtifactPlanJson(): String =
        canonicalArtifactPlanJson(includeDumpSha256 = true)

    /**
     * Serializes this plan with optional dump hash inclusion.
     */
    private fun canonicalArtifactPlanJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schema", GlyphArtifactPlanSchema, comma = true)
        appendGlyphJsonField("runId", run.typefaceId?.value?.toString() ?: "unidentified", comma = true)
        appendGlyphJsonField("glyphCount", run.glyphIds.size, comma = true)
        appendGlyphJsonField("representationCount", representations.size, comma = true)
        appendGlyphJsonField("diagnosticCount", diagnostics.size, comma = true)
        append("  \"policyInputs\": ")
        append(policyInputs?.toCanonicalJson()?.indentJsonContinuation("  ") ?: "null")
        append(",\n")
        append("  \"decisions\": [")
        if (decisions.isNotEmpty()) {
            append("\n")
            append(decisions.joinToString(",\n") { decision -> decision.toCanonicalJson().prependIndent("    ") })
            append("\n")
        }
        append("  ],\n")
        append("  \"diagnostics\": [")
        if (diagnostics.isNotEmpty()) {
            append("\n")
            append(diagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
            append("\n")
        }
        append("  ]")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Records the route selected or explicitly refused for one glyph position.
 *
 * @property index zero-based glyph position inside the source run.
 * @property glyphId font-specific glyph identifier.
 * @property selectedRoute canonical `text.glyph.*` route selected for this position.
 * @property representation short representation label, or null for unsupported routes.
 * @property source selected representation source, such as `request` or `cache`.
 * @property keySha256 route-specific strike-key hash for deterministic cache evidence.
 * @property sourceRepresentationSha256 optional SHA-256 over the selected source representation.
 * @property fallbackPolicy stable fallback/refusal policy label for this decision.
 * @property rejectedAlternatives routes considered and rejected before the selected route.
 * @property diagnostic optional unsupported-route diagnostic attached to this decision.
 */
data class GlyphArtifactPlanDecision(
    val index: Int,
    val glyphId: Int,
    val selectedRoute: String,
    val representation: String?,
    val source: String?,
    val planRef: GlyphArtifactPlanRef? = null,
    val artifactIntent: String? = null,
    val keySha256: String,
    val sourceRepresentationSha256: String? = null,
    val fallbackPolicy: String,
    val rejectedAlternatives: List<GlyphArtifactRouteRejection> = emptyList(),
    val diagnostic: GlyphRouteDiagnostic? = null,
)

/**
 * Aggregate deterministic evidence dump for `glyph-artifact-plan.json`.
 */
class GlyphArtifactPlanEvidenceDump(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    plans: List<GlyphArtifactPlan>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val plans: List<GlyphArtifactPlan> = plans.toList()
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String =
        canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("planCount", plans.size, comma = true)
        append("  \"plans\": ")
        append(appendGlyphArtifactPlansJson(plans))
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Describes one rejected route alternative in a glyph artifact decision trace.
 *
 * @property route canonical `text.glyph.*` route that was considered.
 * @property reason stable reason why the route was not selected.
 */
data class GlyphArtifactRouteRejection(
    val route: String,
    val reason: String,
)

/**
 * Describes an outline glyph route before any mask or atlas artifact is generated.
 *
 * @property glyphId glyph identifier for the outline.
 * @property pathCommands compact, renderer-neutral path command stream.
 * @property windingRule fill winding rule name used by downstream coverage generation.
 */
data class OutlineGlyphRepresentation(
    override val glyphId: Int,
    val pathCommands: List<String> = emptyList(),
    val windingRule: String = "nonZero",
) : GlyphRepresentation

/**
 * Generates alpha masks from outline glyph plans.
 */
interface GlyphMaskGenerator {
    /**
     * Rasterizes one outline plan into an A8 alpha mask.
     *
     * @param outline outline route to rasterize.
     * @param strikeKey strike inputs used for mask generation.
     * @return generated A8 glyph mask.
     */
    fun generate(outline: OutlineGlyphRepresentation, strikeKey: GlyphStrikeKey): A8GlyphMask =
        try {
            rasterizeOutlineToA8(outline, strikeKey)
        } catch (error: IllegalArgumentException) {
            outline.failedA8Mask(strikeKey, error)
        } catch (error: IllegalStateException) {
            outline.failedA8Mask(strikeKey, error)
        }
}

/**
 * Stores an 8-bit alpha glyph mask in pure Kotlin memory.
 *
 * @property glyphId glyph identifier represented by this mask.
 * @property width mask width in pixels.
 * @property height mask height in pixels.
 * @property left horizontal bearing of the mask origin.
 * @property top vertical bearing of the mask origin.
 * @property rowBytes number of bytes per mask row.
 * @property pixels Immutable alpha samples in row-major order, encoded as
 * integer byte values in the `0..255` range.
 * @property diagnostics stable glyph-local diagnostics recorded while producing
 * this mask.
 * @property sourceOutlineSha256 optional SHA-256 over the source outline facts
 * used to generate this mask.
 */
class A8GlyphMask(
    override val glyphId: Int,
    val width: Int,
    val height: Int,
    val left: Int = 0,
    val top: Int = 0,
    val rowBytes: Int = width,
    pixels: List<Int> = List(rowBytes * height) { 0 },
    diagnostics: List<GlyphRouteDiagnostic> = emptyList(),
    val sourceOutlineSha256: String? = null,
) : GlyphRepresentation {
    val pixels: List<Int> = Collections.unmodifiableList(ArrayList(pixels))

    val diagnostics: List<GlyphRouteDiagnostic> =
        Collections.unmodifiableList(ArrayList(diagnostics))

    fun copy(
        glyphId: Int = this.glyphId,
        width: Int = this.width,
        height: Int = this.height,
        left: Int = this.left,
        top: Int = this.top,
        rowBytes: Int = this.rowBytes,
        pixels: List<Int> = this.pixels,
        diagnostics: List<GlyphRouteDiagnostic> = this.diagnostics,
        sourceOutlineSha256: String? = this.sourceOutlineSha256,
    ): A8GlyphMask =
        A8GlyphMask(
            glyphId = glyphId,
            width = width,
            height = height,
            left = left,
            top = top,
            rowBytes = rowBytes,
            pixels = pixels,
            diagnostics = diagnostics,
            sourceOutlineSha256 = sourceOutlineSha256,
        )

    operator fun component1(): Int = glyphId

    operator fun component2(): Int = width

    operator fun component3(): Int = height

    operator fun component4(): Int = left

    operator fun component5(): Int = top

    operator fun component6(): Int = rowBytes

    operator fun component7(): List<Int> = pixels

    operator fun component8(): List<GlyphRouteDiagnostic> = diagnostics

    operator fun component9(): String? = sourceOutlineSha256

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is A8GlyphMask) return false

        return glyphId == other.glyphId &&
            width == other.width &&
            height == other.height &&
            left == other.left &&
            top == other.top &&
            rowBytes == other.rowBytes &&
            pixels == other.pixels &&
            diagnostics == other.diagnostics &&
            sourceOutlineSha256 == other.sourceOutlineSha256
    }

    override fun hashCode(): Int {
        var result = glyphId
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + left
        result = 31 * result + top
        result = 31 * result + rowBytes
        result = 31 * result + pixels.hashCode()
        result = 31 * result + diagnostics.hashCode()
        result = 31 * result + (sourceOutlineSha256?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "A8GlyphMask(" +
            "glyphId=$glyphId, " +
            "width=$width, " +
            "height=$height, " +
            "left=$left, " +
            "top=$top, " +
            "rowBytes=$rowBytes, " +
            "pixels=$pixels, " +
            "diagnostics=$diagnostics, " +
            "sourceOutlineSha256=$sourceOutlineSha256" +
            ")"
}

/**
 * Deterministic evidence for one CPU-prepared A8 glyph mask.
 *
 * @property glyphId glyph identifier represented by this mask.
 * @property strikeKeySha256 route-specific strike-key hash for this glyph and A8 route.
 * @property left horizontal mask origin relative to glyph origin.
 * @property top vertical mask origin relative to glyph origin.
 * @property width addressable mask width in pixels.
 * @property height addressable mask height in pixels.
 * @property rowBytes source row stride in samples.
 * @property addressablePixelCount number of samples included in [coverageSha256].
 * @property nonZeroPixels count of non-zero addressable samples.
 * @property coverageSha256 SHA-256 digest of addressable samples in row-major order.
 * @property sourceOutlineSha256 optional SHA-256 over the source outline facts.
 * @property diagnostics stable diagnostics attached to this evidence.
 */
data class A8GlyphMaskArtifactEvidence(
    val glyphId: Int,
    val strikeKeySha256: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val rowBytes: Int,
    val addressablePixelCount: Int,
    val nonZeroPixels: Int,
    val coverageSha256: String,
    val sourceOutlineSha256: String? = null,
    val diagnostics: List<GlyphRouteDiagnostic> = emptyList(),
) {
    /**
     * SHA-256 digest of [toCanonicalJson] content with `dumpSha256` omitted.
     */
    val dumpSha256: String by lazy {
        glyphSha256(canonicalA8GlyphMaskJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))
    }

    /**
     * Serializes this A8 mask evidence into stable JSON.
     *
     * @return canonical JSON ending with a newline.
     */
    fun toCanonicalJson(): String =
        canonicalA8GlyphMaskJson(includeDumpSha256 = true)

    /**
     * Shared constructors for A8 mask evidence.
     */
    companion object {
        /**
         * Builds deterministic evidence from an in-memory A8 mask and strike key.
         */
        fun from(mask: A8GlyphMask, strikeKey: GlyphStrikeKey): A8GlyphMaskArtifactEvidence {
            mask.requireValidA8Pixels()
            val summary = GlyphMaskSummary.fromA8Mask(mask)
            val addressablePixelCount = mask.width.nonNegativeProduct(
                other = mask.height,
                glyphId = mask.glyphId,
                label = "A8 mask",
            )
            require(addressablePixelCount <= Int.MAX_VALUE.toLong()) {
                "A8 mask addressable pixel count $addressablePixelCount exceeds Int.MAX_VALUE for glyph ${mask.glyphId}."
            }

            return A8GlyphMaskArtifactEvidence(
                glyphId = mask.glyphId,
                strikeKeySha256 = strikeKey.artifactPlanKeySha256(mask.glyphId, "text.glyph.mask.A8"),
                left = mask.left,
                top = mask.top,
                width = mask.width,
                height = mask.height,
                rowBytes = mask.rowBytes,
                addressablePixelCount = addressablePixelCount.toInt(),
                nonZeroPixels = summary.nonZeroPixels,
                coverageSha256 = summary.sha256,
                sourceOutlineSha256 = mask.sourceOutlineSha256,
                diagnostics = mask.diagnostics,
            )
        }
    }

    /**
     * Serializes this evidence with optional dump hash inclusion.
     */
    private fun canonicalA8GlyphMaskJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schema", A8GlyphMaskArtifactEvidenceSchema, comma = true)
        appendGlyphJsonField("glyphId", glyphId, comma = true)
        appendGlyphJsonField("strikeKeySha256", strikeKeySha256, comma = true)
        append("  \"bounds\": {")
        append(glyphJsonString("left")).append(": ").append(left).append(", ")
        append(glyphJsonString("top")).append(": ").append(top).append(", ")
        append(glyphJsonString("width")).append(": ").append(width).append(", ")
        append(glyphJsonString("height")).append(": ").append(height)
        append("},\n")
        appendGlyphJsonField("rowBytes", rowBytes, comma = true)
        appendGlyphJsonField("addressablePixelCount", addressablePixelCount, comma = true)
        appendGlyphJsonField("nonZeroPixels", nonZeroPixels, comma = true)
        appendGlyphJsonField("coverageSha256", coverageSha256, comma = true)
        appendGlyphJsonNullableField("sourceOutlineSha256", sourceOutlineSha256, comma = true)
        append("  \"diagnostics\": ")
        append(appendGlyphRouteDiagnosticsInlineJson(diagnostics))
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Aggregate deterministic evidence dump for `a8-glyph-mask.json`.
 */
class A8GlyphMaskEvidenceDump(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    masks: List<A8GlyphMaskArtifactEvidence>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val masks: List<A8GlyphMaskArtifactEvidence> = masks.toList()
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    /**
     * SHA-256 digest of [toCanonicalJson] content with `dumpSha256` omitted.
     */
    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    /**
     * Serializes the dump as stable JSON.
     */
    fun toCanonicalJson(): String =
        canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("maskCount", masks.size, comma = true)
        append("  \"masks\": ")
        append(appendA8GlyphMaskEvidenceJson(masks))
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Generates signed-distance-field glyph masks for scalable text rendering.
 */
interface SDFGlyphGenerator {
    /**
     * Converts an outline plan into a signed-distance-field mask.
     *
     * @param outline outline route to convert.
     * @param strikeKey strike inputs used for SDF generation.
     * @return generated SDF glyph mask.
     */
    fun generate(outline: OutlineGlyphRepresentation, strikeKey: GlyphStrikeKey): SDFGlyphMask =
        generateLinearOutlineSDF(outline, strikeKey)
}

/**
 * Stores a signed-distance-field glyph mask in pure Kotlin memory.
 *
 * @property glyphId glyph identifier represented by this mask.
 * @property width mask width in pixels.
 * @property height mask height in pixels.
 * @property left horizontal bearing of the mask origin.
 * @property top vertical bearing of the mask origin.
 * @property distanceRange distance range encoded by one byte of mask data.
 * @property pixels Immutable signed-distance samples in row-major order,
 * encoded as integer byte values in the `0..255` range.
 * @property sourceOutlineSha256 optional SHA-256 over the source outline facts
 * used to generate this mask.
 */
data class SDFGlyphMask(
    override val glyphId: Int,
    val width: Int,
    val height: Int,
    val left: Int = 0,
    val top: Int = 0,
    val distanceRange: Float,
    val pixels: List<Int> = List(width * height) { 0 },
    val sourceOutlineSha256: String? = null,
) : GlyphRepresentation

/**
 * Deterministic evidence for one CPU-prepared SDF glyph artifact.
 *
 * @property glyphId glyph identifier represented by this artifact.
 * @property strikeKeySha256 route-specific strike-key hash for this glyph and SDF route.
 * @property left horizontal mask origin relative to glyph origin.
 * @property top vertical mask origin relative to glyph origin.
 * @property width addressable mask width in pixels.
 * @property height addressable mask height in pixels.
 * @property spreadPx signed-distance spread in source pixels.
 * @property sourceResolutionPx source SDF resolution used for generation.
 * @property atlasPaddingPx minimum atlas padding required by the spread contract.
 * @property format CPU artifact storage format.
 * @property normalizationFormulaVersion stable normalization formula contract identifier.
 * @property addressablePixelCount number of samples included in [distanceFieldSha256].
 * @property distanceFieldSha256 SHA-256 digest of addressable samples in row-major order.
 * @property sourceOutlineSha256 optional SHA-256 over the source outline facts.
 */
data class SDFGlyphArtifactEvidence(
    val glyphId: Int,
    val strikeKeySha256: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val spreadPx: Float,
    val sourceResolutionPx: Float,
    val atlasPaddingPx: Int,
    val format: String,
    val normalizationFormulaVersion: String,
    val addressablePixelCount: Int,
    val distanceFieldSha256: String,
    val sourceOutlineSha256: String? = null,
) {
    val dumpSha256: String by lazy {
        glyphSha256(canonicalSdfGlyphArtifactJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))
    }

    fun toCanonicalJson(): String =
        canonicalSdfGlyphArtifactJson(includeDumpSha256 = true)

    companion object {
        fun from(mask: SDFGlyphMask, strikeKey: GlyphStrikeKey): SDFGlyphArtifactEvidence {
            mask.requireValidSDFPixels()
            val spreadPx = strikeKey.sdfSpreadPx ?: mask.distanceRange
            require(spreadPx.isFinite() && spreadPx > 0f) {
                "SDF spreadPx must be finite and positive for glyph ${mask.glyphId}."
            }
            val sourceResolutionPx = strikeKey.sdfSourceResolutionPx ?: strikeKey.sizePx
            require(sourceResolutionPx.isFinite() && sourceResolutionPx > 0f) {
                "SDF sourceResolutionPx must be finite and positive for glyph ${mask.glyphId}."
            }
            val addressablePixelCount = mask.width.nonNegativeProduct(
                other = mask.height,
                glyphId = mask.glyphId,
                label = "SDF mask",
            )
            require(addressablePixelCount <= Int.MAX_VALUE.toLong()) {
                "SDF mask addressable pixel count $addressablePixelCount exceeds Int.MAX_VALUE for glyph ${mask.glyphId}."
            }
            return SDFGlyphArtifactEvidence(
                glyphId = mask.glyphId,
                strikeKeySha256 = strikeKey.artifactPlanKeySha256(mask.glyphId, "text.glyph.mask.SDF"),
                left = mask.left,
                top = mask.top,
                width = mask.width,
                height = mask.height,
                spreadPx = spreadPx,
                sourceResolutionPx = sourceResolutionPx,
                atlasPaddingPx = ceil(spreadPx.toDouble()).toInt() + 1,
                format = strikeKey.maskFormat.ifBlank { "R8Unorm" },
                normalizationFormulaVersion = SDFNormalizationFormulaVersion,
                addressablePixelCount = addressablePixelCount.toInt(),
                distanceFieldSha256 = mask.pixels.pixelSha256(addressablePixelCount.toInt()),
                sourceOutlineSha256 = mask.sourceOutlineSha256,
            )
        }
    }

    private fun canonicalSdfGlyphArtifactJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schema", SDFGlyphArtifactEvidenceSchema, comma = true)
        appendGlyphJsonField("glyphId", glyphId, comma = true)
        appendGlyphJsonField("strikeKeySha256", strikeKeySha256, comma = true)
        append("  \"bounds\": {")
        append(glyphJsonString("left")).append(": ").append(left).append(", ")
        append(glyphJsonString("top")).append(": ").append(top).append(", ")
        append(glyphJsonString("width")).append(": ").append(width).append(", ")
        append(glyphJsonString("height")).append(": ").append(height)
        append("},\n")
        appendGlyphJsonField("spreadPx", spreadPx, comma = true)
        appendGlyphJsonField("sourceResolutionPx", sourceResolutionPx, comma = true)
        appendGlyphJsonField("atlasPaddingPx", atlasPaddingPx, comma = true)
        appendGlyphJsonField("format", format, comma = true)
        appendGlyphJsonField("normalizationFormulaVersion", normalizationFormulaVersion, comma = true)
        appendGlyphJsonField("addressablePixelCount", addressablePixelCount, comma = true)
        appendGlyphJsonField("distanceFieldSha256", distanceFieldSha256, comma = true)
        appendGlyphJsonNullableField("sourceOutlineSha256", sourceOutlineSha256, comma = includeDumpSha256)
        if (includeDumpSha256) {
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("\n}\n")
    }
}

/**
 * Aggregate deterministic evidence dump for `sdf-glyph-artifact.json`.
 */
class SDFGlyphArtifactEvidenceDump(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    artifacts: List<SDFGlyphArtifactEvidence>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val artifacts: List<SDFGlyphArtifactEvidence> = artifacts.toList()
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String =
        canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("artifactCount", artifacts.size, comma = true)
        append("  \"artifacts\": ")
        append(appendSdfGlyphArtifactEvidenceJson(artifacts))
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Deterministic atlas entry evidence for one packed glyph mask.
 *
 * @property glyphId glyph identifier placed in the atlas.
 * @property strikeKeySha256 strike-specific hash that selected the source mask.
 * @property x left atlas coordinate in pixels.
 * @property y top atlas coordinate in pixels.
 * @property width placed atlas width in pixels.
 * @property height placed atlas height in pixels.
 * @property sourceLeft source-mask origin relative to the glyph origin.
 * @property sourceTop source-mask origin relative to the glyph origin.
 * @property sourceWidth source-mask addressable width.
 * @property sourceHeight source-mask addressable height.
 * @property paddingPx padding reserved around this entry inside the atlas plan.
 * @property sourceMaskSha256 SHA-256 over the source mask addressable samples.
 */
data class GlyphAtlasArtifactEvidenceEntry(
    val glyphId: Int,
    val strikeKeySha256: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val sourceLeft: Int,
    val sourceTop: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val paddingPx: Int,
    val sourceMaskSha256: String,
) {
    init {
        require(strikeKeySha256.matches(Regex("[0-9a-f]{64}"))) {
            "Atlas entry strikeKeySha256 must be lowercase hexadecimal for glyph $glyphId."
        }
        require(sourceMaskSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Atlas entry sourceMaskSha256 must be lowercase hexadecimal for glyph $glyphId."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonField("glyphId", glyphId, comma = true)
        appendGlyphJsonField("strikeKeySha256", strikeKeySha256, comma = true)
        append("  \"rect\": {")
        append(glyphJsonString("x")).append(": ").append(x).append(", ")
        append(glyphJsonString("y")).append(": ").append(y).append(", ")
        append(glyphJsonString("width")).append(": ").append(width).append(", ")
        append(glyphJsonString("height")).append(": ").append(height)
        append("},\n")
        append("  \"sourceBounds\": {")
        append(glyphJsonString("left")).append(": ").append(sourceLeft).append(", ")
        append(glyphJsonString("top")).append(": ").append(sourceTop).append(", ")
        append(glyphJsonString("width")).append(": ").append(sourceWidth).append(", ")
        append(glyphJsonString("height")).append(": ").append(sourceHeight)
        append("},\n")
        appendGlyphJsonField("paddingPx", paddingPx, comma = true)
        appendGlyphJsonField("sourceMaskSha256", sourceMaskSha256, comma = false)
        append("\n}")
    }
}

/**
 * Deterministic CPU-only atlas artifact evidence for A8 and SDF atlas build results.
 *
 * @property artifactType `GlyphAtlasArtifact` or `SDFGlyphAtlasArtifact`.
 * @property artifactKeySha256 compact atlas key hash derived from strike, mask, and placement facts.
 * @property generation monotonic atlas generation carried by the artifact dump.
 * @property textureFormat CPU upload texel format expected by downstream consumers.
 * @property width atlas width in pixels.
 * @property height atlas height in pixels.
 * @property rowStride atlas row stride in samples.
 * @property memoryBudgetClass stable budget classification.
 * @property lifetimeClass stable lifetime classification.
 * @property invalidationToken stable invalidation token for stale-generation checks.
 * @property entries atlas entry facts in placement order.
 * @property sourceMaskHashes source mask hashes in placement order.
 * @property uploadByteSha256 SHA-256 over atlas upload bytes in row-major order.
 * @property diagnostics stable diagnostics attached to this artifact dump.
 * @property distanceRange optional shared distance range for SDF atlas artifacts.
 */
data class GlyphAtlasArtifactEvidence(
    val artifactType: String,
    val artifactKeySha256: String,
    val generation: Long,
    val textureFormat: String,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val memoryBudgetClass: String,
    val lifetimeClass: String,
    val invalidationToken: String,
    val entries: List<GlyphAtlasArtifactEvidenceEntry>,
    val sourceMaskHashes: List<String>,
    val uploadByteSha256: String,
    val diagnostics: List<GlyphRouteDiagnostic> = emptyList(),
    val distanceRange: Float? = null,
) {
    val entryCount: Int
        get() = entries.size

    val dumpSha256: String by lazy {
        glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))
    }

    init {
        require(artifactType == "GlyphAtlasArtifact" || artifactType == "SDFGlyphAtlasArtifact") {
            "Unsupported glyph atlas artifact type '$artifactType'."
        }
        require(artifactKeySha256.matches(Regex("[0-9a-f]{64}"))) {
            "Atlas artifactKeySha256 must be lowercase hexadecimal."
        }
        require(uploadByteSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Atlas uploadByteSha256 must be lowercase hexadecimal."
        }
        require(generation >= 0L) { "Atlas generation must be non-negative." }
        require(width >= 0 && height >= 0 && rowStride >= 0) { "Atlas dimensions must be non-negative." }
        require(sourceMaskHashes.all { it.matches(Regex("[0-9a-f]{64}")) }) {
            "Atlas sourceMaskHashes must be lowercase hexadecimal."
        }
        require(memoryBudgetClass.isNotBlank()) { "memoryBudgetClass must not be blank." }
        require(lifetimeClass.isNotBlank()) { "lifetimeClass must not be blank." }
        require(invalidationToken.isNotBlank()) { "invalidationToken must not be blank." }
    }

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    companion object {
        fun fromA8(
            buildResult: GlyphAtlasBuildResult,
            masks: List<A8GlyphMask>,
            strikeKeysByGlyphId: Map<Int, GlyphStrikeKey>,
            generation: Long,
            invalidationToken: String,
            memoryBudgetClass: String,
            lifetimeClass: String,
            paddingPx: Int = 0,
            diagnostics: List<GlyphRouteDiagnostic> = emptyList(),
        ): GlyphAtlasArtifactEvidence {
            val masksByGlyphId = masks.associateUniqueA8Masks()
            val entries = buildResult.placements.map { placement ->
                val mask = requireNotNull(masksByGlyphId[placement.glyphId]) {
                    "Missing A8 source mask for glyph ${placement.glyphId}."
                }
                val strikeKey = requireNotNull(strikeKeysByGlyphId[placement.glyphId]) {
                    "Missing strikeKey for atlas glyph ${placement.glyphId}."
                }
                GlyphAtlasArtifactEvidenceEntry(
                    glyphId = placement.glyphId,
                    strikeKeySha256 = strikeKey.artifactPlanKeySha256(mask.glyphId, "text.glyph.mask.A8"),
                    x = placement.x,
                    y = placement.y,
                    width = placement.width,
                    height = placement.height,
                    sourceLeft = mask.left,
                    sourceTop = mask.top,
                    sourceWidth = mask.width,
                    sourceHeight = mask.height,
                    paddingPx = paddingPx,
                    sourceMaskSha256 = GlyphMaskSummary.fromA8Mask(mask).sha256,
                )
            }
            return fromCommon(
                artifactType = "GlyphAtlasArtifact",
                textureFormat = "A8",
                width = buildResult.width,
                height = buildResult.height,
                rowStride = buildResult.width,
                generation = generation,
                invalidationToken = invalidationToken,
                memoryBudgetClass = memoryBudgetClass,
                lifetimeClass = lifetimeClass,
                entries = entries,
                sourceMaskHashes = entries.map { it.sourceMaskSha256 },
                uploadByteSha256 = buildResult.pixels.pixelSha256(
                    checkedAtlasPixelCount(buildResult.width, buildResult.height, "A8"),
                ),
                diagnostics = diagnostics,
                distanceRange = null,
            )
        }

        fun fromSdf(
            buildResult: SDFGlyphAtlasBuildResult,
            masks: List<SDFGlyphMask>,
            strikeKeysByGlyphId: Map<Int, GlyphStrikeKey>,
            generation: Long,
            invalidationToken: String,
            memoryBudgetClass: String,
            lifetimeClass: String,
            paddingPx: Int = 0,
            diagnostics: List<GlyphRouteDiagnostic> = emptyList(),
        ): GlyphAtlasArtifactEvidence {
            val masksByGlyphId = masks.associateUniqueSDFMasks()
            val entries = buildResult.placements.map { placement ->
                val mask = requireNotNull(masksByGlyphId[placement.glyphId]) {
                    "Missing SDF source mask for glyph ${placement.glyphId}."
                }
                val strikeKey = requireNotNull(strikeKeysByGlyphId[placement.glyphId]) {
                    "Missing strikeKey for atlas glyph ${placement.glyphId}."
                }
                GlyphAtlasArtifactEvidenceEntry(
                    glyphId = placement.glyphId,
                    strikeKeySha256 = strikeKey.artifactPlanKeySha256(mask.glyphId, "text.glyph.mask.SDF"),
                    x = placement.x,
                    y = placement.y,
                    width = placement.width,
                    height = placement.height,
                    sourceLeft = mask.left,
                    sourceTop = mask.top,
                    sourceWidth = mask.width,
                    sourceHeight = mask.height,
                    paddingPx = paddingPx,
                    sourceMaskSha256 = mask.pixels.pixelSha256(mask.width * mask.height),
                )
            }
            return fromCommon(
                artifactType = "SDFGlyphAtlasArtifact",
                textureFormat = "R8Unorm",
                width = buildResult.width,
                height = buildResult.height,
                rowStride = buildResult.width,
                generation = generation,
                invalidationToken = invalidationToken,
                memoryBudgetClass = memoryBudgetClass,
                lifetimeClass = lifetimeClass,
                entries = entries,
                sourceMaskHashes = entries.map { it.sourceMaskSha256 },
                uploadByteSha256 = buildResult.pixels.pixelSha256(
                    checkedAtlasPixelCount(buildResult.width, buildResult.height, "SDF"),
                ),
                diagnostics = diagnostics,
                distanceRange = buildResult.distanceRange,
            )
        }

        private fun fromCommon(
            artifactType: String,
            textureFormat: String,
            width: Int,
            height: Int,
            rowStride: Int,
            generation: Long,
            invalidationToken: String,
            memoryBudgetClass: String,
            lifetimeClass: String,
            entries: List<GlyphAtlasArtifactEvidenceEntry>,
            sourceMaskHashes: List<String>,
            uploadByteSha256: String,
            diagnostics: List<GlyphRouteDiagnostic>,
            distanceRange: Float?,
        ): GlyphAtlasArtifactEvidence {
            val artifactKeySha256 = glyphSha256(
                canonicalArtifactKeyJson(
                    artifactType = artifactType,
                    textureFormat = textureFormat,
                    width = width,
                    height = height,
                    rowStride = rowStride,
                    generation = generation,
                    invalidationToken = invalidationToken,
                    entries = entries,
                    sourceMaskHashes = sourceMaskHashes,
                    distanceRange = distanceRange,
                ).toByteArray(Charsets.UTF_8),
            )
            return GlyphAtlasArtifactEvidence(
                artifactType = artifactType,
                artifactKeySha256 = artifactKeySha256,
                generation = generation,
                textureFormat = textureFormat,
                width = width,
                height = height,
                rowStride = rowStride,
                memoryBudgetClass = memoryBudgetClass,
                lifetimeClass = lifetimeClass,
                invalidationToken = invalidationToken,
                entries = entries,
                sourceMaskHashes = sourceMaskHashes,
                uploadByteSha256 = uploadByteSha256,
                diagnostics = diagnostics,
                distanceRange = distanceRange,
            )
        }

        private fun canonicalArtifactKeyJson(
            artifactType: String,
            textureFormat: String,
            width: Int,
            height: Int,
            rowStride: Int,
            generation: Long,
            invalidationToken: String,
            entries: List<GlyphAtlasArtifactEvidenceEntry>,
            sourceMaskHashes: List<String>,
            distanceRange: Float?,
        ): String = buildString {
            append("{\n")
            appendGlyphJsonField("artifactType", artifactType, comma = true)
            appendGlyphJsonField("textureFormat", textureFormat, comma = true)
            appendGlyphJsonField("width", width, comma = true)
            appendGlyphJsonField("height", height, comma = true)
            appendGlyphJsonField("rowStride", rowStride, comma = true)
            appendGlyphJsonField("generation", generation.toInt(), comma = true)
            appendGlyphJsonField("invalidationToken", invalidationToken, comma = true)
            append("  \"entries\": ")
            append(appendGlyphAtlasArtifactEvidenceEntriesJson(entries))
            append(",\n")
            append("  \"sourceMaskHashes\": ")
            appendGlyphStringArrayInlineJson(sourceMaskHashes)
            if (distanceRange != null) {
                append(",\n")
                appendGlyphJsonField("distanceRange", distanceRange, comma = false)
            } else {
                append("\n")
            }
            append("}\n")
        }
    }

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schema", GlyphAtlasArtifactEvidenceSchema, comma = true)
        appendGlyphJsonField("artifactType", artifactType, comma = true)
        appendGlyphJsonField("artifactKeySha256", artifactKeySha256, comma = true)
        appendGlyphJsonField("generation", generation.toInt(), comma = true)
        appendGlyphJsonField("textureFormat", textureFormat, comma = true)
        appendGlyphJsonField("width", width, comma = true)
        appendGlyphJsonField("height", height, comma = true)
        appendGlyphJsonField("rowStride", rowStride, comma = true)
        appendGlyphJsonField("memoryBudgetClass", memoryBudgetClass, comma = true)
        appendGlyphJsonField("lifetimeClass", lifetimeClass, comma = true)
        appendGlyphJsonField("invalidationToken", invalidationToken, comma = true)
        appendGlyphJsonField("entryCount", entryCount, comma = true)
        if (distanceRange != null) {
            appendGlyphJsonField("distanceRange", distanceRange, comma = true)
        }
        append("  \"entries\": ")
        append(appendGlyphAtlasArtifactEvidenceEntriesJson(entries))
        append(",\n")
        append("  \"sourceMaskHashes\": ")
        appendGlyphStringArrayInlineJson(sourceMaskHashes)
        append(",\n")
        appendGlyphJsonField("uploadByteSha256", uploadByteSha256, comma = true)
        append("  \"diagnostics\": ")
        append(appendGlyphRouteDiagnosticsInlineJson(diagnostics))
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Aggregate deterministic evidence dump for `glyph-atlas.json`.
 */
class GlyphAtlasArtifactEvidenceDump(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    artifacts: List<GlyphAtlasArtifactEvidence>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val artifacts: List<GlyphAtlasArtifactEvidence> = artifacts.toList()
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("artifactCount", artifacts.size, comma = true)
        append("  \"artifacts\": ")
        append(appendGlyphAtlasArtifactEvidenceJson(artifacts))
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * One deterministic eviction event captured while rotating atlas generations.
 *
 * @property strikeKeySha256 strike-key hash whose resident atlas entries were evicted.
 * @property glyphIds glyph identifiers evicted in order for this strike.
 * @property bytesFreed approximate resident bytes released by this event.
 */
data class GlyphAtlasEvictionTraceEntry(
    val strikeKeySha256: String,
    val glyphIds: List<Int>,
    val bytesFreed: Long,
) {
    init {
        require(strikeKeySha256.matches(Regex("[0-9a-f]{64}"))) {
            "GlyphAtlasEvictionTraceEntry strikeKeySha256 must be lowercase hexadecimal."
        }
        require(bytesFreed >= 0L) { "GlyphAtlasEvictionTraceEntry bytesFreed must be non-negative." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonField("strikeKeySha256", strikeKeySha256, comma = true)
        append("  \"glyphIds\": ")
        append(appendGlyphIntArrayInlineJson(glyphIds))
        append(",\n")
        appendGlyphJsonField("bytesFreed", bytesFreed.toInt(), comma = false)
        append("\n}")
    }
}

/**
 * Deterministic eviction and invalidation trace for `glyph-atlas-eviction-trace.json`.
 */
class GlyphAtlasEvictionTrace(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    val artifactType: String,
    val previousGeneration: Long,
    val nextGeneration: Long,
    val invalidationTokenBefore: String,
    val invalidationTokenAfter: String,
    val budgetBytes: Long,
    val residentBytesBefore: Long,
    val residentBytesAfter: Long,
    evictions: List<GlyphAtlasEvictionTraceEntry>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val evictions: List<GlyphAtlasEvictionTraceEntry> = evictions.toList()
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("artifactType", artifactType, comma = true)
        appendGlyphJsonField("previousGeneration", previousGeneration.toInt(), comma = true)
        appendGlyphJsonField("nextGeneration", nextGeneration.toInt(), comma = true)
        appendGlyphJsonField("invalidationTokenBefore", invalidationTokenBefore, comma = true)
        appendGlyphJsonField("invalidationTokenAfter", invalidationTokenAfter, comma = true)
        appendGlyphJsonField("budgetBytes", budgetBytes.toInt(), comma = true)
        appendGlyphJsonField("residentBytesBefore", residentBytesBefore.toInt(), comma = true)
        appendGlyphJsonField("residentBytesAfter", residentBytesAfter.toInt(), comma = true)
        appendGlyphJsonField("evictionCount", evictions.size, comma = true)
        append("  \"evictions\": ")
        append(appendGlyphAtlasEvictionTraceEntriesJson(evictions))
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Selects pure Kotlin glyph representations for a run from the caller's available route request.
 *
 * The planner is deterministic: glyphs are considered in run order, routes are considered in the
 * order supplied by [GlyphArtifactRouteRequest.preferredRoutes], and candidates are considered in
 * their original list order. Selected representations can be mirrored into an optional
 * module-local [cache], but route selection itself stays renderer-independent and never allocates
 * GPU resources.
 *
 * @property request route preferences and available representations for this planning pass.
 * @property cache optional cache that receives selected representations and can satisfy a glyph
 * when the current request does not include an in-memory candidate for an accepted route.
 */
class GlyphArtifactRoutePlanner(
    private val request: GlyphArtifactRouteRequest,
    private val cache: GlyphCache? = null,
) : GlyphArtifactPlanner {
    /**
     * Builds a route plan by selecting one representation per glyph when a requested route exists.
     *
     * @param run shaped glyph run whose glyph IDs define planning order.
     * @param strikeKey strike inputs used for optional cache lookup and storage.
     * @return route plan containing selected representations and non-fatal diagnostics for glyphs
     * that could not satisfy the requested route set.
     */
    override fun plan(run: ShapedGlyphRun, strikeKey: GlyphStrikeKey): GlyphArtifactPlan {
        val representations = mutableListOf<GlyphRepresentation>()
        val diagnostics = mutableListOf<GlyphRouteDiagnostic>()
        val decisions = mutableListOf<GlyphArtifactPlanDecision>()

        run.glyphIds.forEachIndexed { index, glyphId ->
            val selection = request.selectRoute(glyphId, cache?.get(strikeKey, glyphId))

            if (selection.representation == null) {
                val diagnostic = selection.terminalDiagnostic ?: request.unsupportedDiagnostic(
                    glyphId = glyphId,
                    availableRepresentations = request.availableRepresentations[glyphId].orEmpty(),
                )
                diagnostics += diagnostic
                decisions += GlyphArtifactPlanDecision(
                    index = index,
                    glyphId = glyphId,
                    selectedRoute = UnsupportedGlyphArtifactRoute,
                    representation = null,
                    source = null,
                    keySha256 = strikeKey.artifactPlanKeySha256(glyphId, UnsupportedGlyphArtifactRoute),
                    fallbackPolicy = FallbackPolicyRefuseNoRequestedRepresentation,
                    rejectedAlternatives = selection.rejectedAlternatives,
                    diagnostic = diagnostic,
                )
            } else {
                val selectedRoute = selection.representation.artifactPlanRouteName()
                representations += selection.representation
                cache?.put(strikeKey, selection.representation)
                decisions += GlyphArtifactPlanDecision(
                    index = index,
                    glyphId = glyphId,
                    selectedRoute = selectedRoute,
                    representation = selection.representation.diagnosticRouteName(),
                    source = selection.source,
                    planRef = selection.representation as? GlyphArtifactPlanRef,
                    artifactIntent = selection.representation.artifactIntent(),
                    keySha256 = strikeKey.artifactPlanKeySha256(glyphId, selectedRoute),
                    sourceRepresentationSha256 = selection.representation.sourceRepresentationSha256(),
                    fallbackPolicy = if (selection.rejectedAlternatives.isEmpty()) {
                        FallbackPolicySelectedFirstRequestedRoute
                    } else {
                        FallbackPolicyFallbackSelectedAfterRejections
                    },
                    rejectedAlternatives = selection.rejectedAlternatives,
                    diagnostic = null,
                )
            }
        }

        return GlyphArtifactPlan(
            run = run,
            strikeKey = strikeKey,
            policyInputs = request.policyInputs,
            representations = representations.toList(),
            diagnostics = diagnostics.toList(),
            decisions = decisions.toList(),
        )
    }
}

/**
 * Packs generated glyph masks into atlas coordinates before GPU artifact construction.
 */
interface GlyphAtlasPacker {
    /**
     * Packs A8 masks into atlas placements.
     *
     * @param masks masks to place in an atlas.
     * @return placement records for each packed mask.
     */
    fun pack(masks: List<A8GlyphMask>): List<GlyphAtlasPlacement>
}

/**
 * Deterministic row-based atlas packer for pure Kotlin glyph masks.
 *
 * Masks are placed in input order. Each row starts at the configured padding, and masks wrap to the
 * next row when the next placement would exceed the configured atlas width.
 *
 * @property atlasWidth maximum row width in pixels.
 * @property padding empty pixels reserved before the first row/column and between placements.
 */
class RowGlyphAtlasPacker(
    private val atlasWidth: Int = 2048,
    private val padding: Int = 1,
) : GlyphAtlasPacker {
    init {
        require(atlasWidth > 0) { "atlasWidth must be positive." }
        require(padding >= 0) { "padding must be non-negative." }
    }

    /**
     * Packs A8 masks into stable row-major atlas placements.
     *
     * @param masks masks to place in input order.
     * @return deterministic atlas placements for the supplied masks.
     */
    override fun pack(masks: List<A8GlyphMask>): List<GlyphAtlasPlacement> =
        packAtlasItems(
            masks.map { mask ->
                mask.requireValidA8Pixels()
                AtlasItem(mask.glyphId, mask.width, mask.height)
            },
            atlasWidth = atlasWidth,
            padding = padding,
        )

    /**
     * Packs A8 masks while returning stable diagnostics for plan-level atlas refusals.
     *
     * When any mask cannot fit in the configured atlas row width, the whole pack is refused and no
     * partial placements are returned. This keeps callers from silently dropping glyphs.
     *
     * @param masks masks to place in input order.
     * @return placements or capacity diagnostics for the complete pack request.
     */
    fun packWithDiagnostics(masks: List<A8GlyphMask>): GlyphAtlasPackingResult {
        val diagnostics = masks.mapNotNull { mask ->
            mask.requireValidA8Pixels()
            val paddedWidth = mask.width.toLong() + padding.toLong() * 2L
            if (paddedWidth > atlasWidth.toLong()) {
                GlyphRouteDiagnostic(
                    glyphId = mask.glyphId,
                    route = GlyphAtlasCapacityExceededDiagnosticRoute,
                    severity = "warning",
                    message = "Glyph ${mask.glyphId} width plus padding ($paddedWidth) exceeds atlas width $atlasWidth; " +
                        "refusing atlas pack without partial placements.",
                )
            } else {
                null
            }
        }

        return if (diagnostics.isNotEmpty()) {
            GlyphAtlasPackingResult(
                placements = emptyList(),
                diagnostics = diagnostics,
            )
        } else {
            GlyphAtlasPackingResult(
                placements = pack(masks),
                diagnostics = emptyList(),
            )
        }
    }
}

/**
 * Describes the atlas rectangle assigned to one glyph mask.
 *
 * @property glyphId glyph identifier assigned to this placement.
 * @property x left atlas coordinate in pixels.
 * @property y top atlas coordinate in pixels.
 * @property width placement width in pixels.
 * @property height placement height in pixels.
 */
data class GlyphAtlasPlacement(
    val glyphId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Records a diagnostic-aware atlas packing attempt without allocating GPU resources.
 *
 * @property placements assigned atlas rectangles when the complete pack request succeeds.
 * @property diagnostics stable refusal diagnostics when packing cannot preserve every glyph.
 */
data class GlyphAtlasPackingResult(
    val placements: List<GlyphAtlasPlacement>,
    val diagnostics: List<GlyphRouteDiagnostic> = emptyList(),
) {
    /**
     * SHA-256 digest of [toCanonicalGlyphAtlasPackingJson] content with `dumpSha256` omitted.
     */
    val dumpSha256: String by lazy {
        glyphSha256(canonicalAtlasPackingJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))
    }

    /**
     * Serializes placements and diagnostics into a stable atlas packing evidence dump.
     *
     * @return canonical JSON ending with a newline.
     */
    fun toCanonicalGlyphAtlasPackingJson(): String =
        canonicalAtlasPackingJson(includeDumpSha256 = true)

    /**
     * Serializes this result with optional dump hash inclusion.
     */
    private fun canonicalAtlasPackingJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schema", GlyphAtlasPackingResultSchema, comma = true)
        appendGlyphJsonField("placementCount", placements.size, comma = true)
        appendGlyphJsonField("diagnosticCount", diagnostics.size, comma = true)
        append("  \"placements\": ")
        append(appendGlyphAtlasPlacementsJson(placements))
        append(",\n")
        append("  \"diagnostics\": [")
        if (diagnostics.isNotEmpty()) {
            append("\n")
            append(diagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
            append("\n")
        }
        append("  ]")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Internal builder for A8 atlas upload inputs before the public GPU handoff artifact is produced
 * by the GPU API module.
 */
interface GlyphAtlasArtifactBuilder {
    /**
     * Builds a module-local atlas build result from masks and placements.
     *
     * @param masks source masks included in the atlas.
     * @param placements atlas placements assigned to source masks.
     * @return module-local build result for downstream GPU API adaptation.
     */
    fun build(masks: List<A8GlyphMask>, placements: List<GlyphAtlasPlacement>): GlyphAtlasBuildResult
}

/**
 * Builds pure Kotlin A8 atlas upload inputs from packed glyph masks.
 */
class KotlinGlyphAtlasArtifactBuilder : GlyphAtlasArtifactBuilder {
    /**
     * Composes source mask pixels into a zero-filled A8 atlas using the supplied placements.
     *
     * @param masks source A8 masks keyed by glyph identifier.
     * @param placements atlas placements that select and position masks.
     * @return composed atlas pixels with the original placements preserved.
     */
    override fun build(masks: List<A8GlyphMask>, placements: List<GlyphAtlasPlacement>): GlyphAtlasBuildResult {
        val masksByGlyphId = masks.associateUniqueA8Masks()
        val width = placements.atlasWidth()
        val height = placements.atlasHeight()
        val pixels = MutableList(checkedAtlasPixelCount(width, height, "A8")) { 0 }

        placements.forEach { placement ->
            placement.requireValidPlacement()
            val mask = requireNotNull(masksByGlyphId[placement.glyphId]) {
                "Missing A8 mask for glyph ${placement.glyphId}."
            }
            mask.requireValidA8Pixels()
            require(placement.width == mask.width && placement.height == mask.height) {
                "Placement dimensions must match A8 mask dimensions for glyph ${placement.glyphId}."
            }
            copyA8Mask(mask, placement, width, pixels)
        }

        return GlyphAtlasBuildResult(
            width = width,
            height = height,
            placements = placements.toList(),
            pixels = pixels.toList(),
        )
    }
}

/**
 * Internal builder for SDF atlas upload inputs before the public GPU handoff artifact is produced
 * by the GPU API module.
 */
interface SDFGlyphAtlasArtifactBuilder {
    /**
     * Builds a module-local SDF atlas build result from masks.
     *
     * @param masks source SDF masks included in the atlas.
     * @return module-local build result for downstream GPU API adaptation.
     */
    fun build(masks: List<SDFGlyphMask>): SDFGlyphAtlasBuildResult
}

/**
 * Builds pure Kotlin SDF atlas upload inputs with deterministic row packing.
 *
 * @property atlasWidth maximum row width in pixels.
 * @property padding empty pixels reserved before the first row/column and between placements.
 */
class KotlinSDFGlyphAtlasArtifactBuilder(
    private val atlasWidth: Int = 2048,
    private val padding: Int = 1,
) : SDFGlyphAtlasArtifactBuilder {
    init {
        require(atlasWidth > 0) { "atlasWidth must be positive." }
        require(padding >= 0) { "padding must be non-negative." }
    }

    /**
     * Packs and composes SDF masks into a zero-filled atlas.
     *
     * @param masks source SDF masks. All masks must share one distance range.
     * @return composed SDF atlas pixels and the shared distance range.
     */
    override fun build(masks: List<SDFGlyphMask>): SDFGlyphAtlasBuildResult {
        if (masks.isEmpty()) {
            return SDFGlyphAtlasBuildResult(
                width = 0,
                height = 0,
                distanceRange = 0f,
                pixels = emptyList(),
                placements = emptyList(),
            )
        }

        val distanceRange = masks.first().distanceRange
        require(masks.all { it.distanceRange == distanceRange }) {
            "All SDF masks in one atlas must share the same distanceRange."
        }

        val placements = packAtlasItems(
            masks.map { mask ->
                mask.requireValidSDFPixels()
                AtlasItem(mask.glyphId, mask.width, mask.height)
            },
            atlasWidth = atlasWidth,
            padding = padding,
        )
        val masksByGlyphId = masks.associateUniqueSDFMasks()
        val width = placements.atlasWidth()
        val height = placements.atlasHeight()
        val pixels = MutableList(checkedAtlasPixelCount(width, height, "SDF")) { 0 }

        placements.forEach { placement ->
            val mask = requireNotNull(masksByGlyphId[placement.glyphId]) {
                "Missing SDF mask for glyph ${placement.glyphId}."
            }
            copySDFMask(mask, placement, width, pixels)
        }

        return SDFGlyphAtlasBuildResult(
            width = width,
            height = height,
            distanceRange = distanceRange,
            pixels = pixels.toList(),
            placements = placements.toList(),
        )
    }
}

/**
 * Module-local A8 atlas build result that avoids duplicating public GPU API artifact classes.
 *
 * @property width atlas width in pixels.
 * @property height atlas height in pixels.
 * @property placements packed glyph placements.
 * @property pixels Immutable atlas alpha pixels in row-major order, encoded as
 * integer byte values in the `0..255` range.
 */
data class GlyphAtlasBuildResult(
    val width: Int,
    val height: Int,
    val placements: List<GlyphAtlasPlacement>,
    val pixels: List<Int>,
)

/**
 * Module-local SDF atlas build result that avoids duplicating public GPU API artifact classes.
 *
 * @property width atlas width in pixels.
 * @property height atlas height in pixels.
 * @property distanceRange distance range encoded by atlas samples.
 * @property pixels Immutable atlas signed-distance samples in row-major order,
 * encoded as integer byte values in the `0..255` range.
 * @property placements packed glyph placements used to compose the SDF atlas.
 */
data class SDFGlyphAtlasBuildResult(
    val width: Int,
    val height: Int,
    val distanceRange: Float,
    val pixels: List<Int>,
    val placements: List<GlyphAtlasPlacement> = emptyList(),
)

/**
 * Caches glyph plans and generated representations under a strike-aware budget.
 */
interface GlyphCache {
    /**
     * Looks up a cached representation.
     *
     * @param strikeKey strike key for the cached entry.
     * @param glyphId glyph identifier for the cached entry.
     * @return cached representation or null when absent.
     */
    fun get(strikeKey: GlyphStrikeKey, glyphId: Int): GlyphRepresentation?

    /**
     * Stores a representation in the cache.
     *
     * @param strikeKey strike key for the cached entry.
     * @param representation representation to store.
     */
    fun put(strikeKey: GlyphStrikeKey, representation: GlyphRepresentation)
}

/**
 * Defines memory and entry-count limits for glyph cache implementations.
 *
 * @property maxBytes maximum approximate cache memory budget.
 * @property maxEntries maximum number of glyph entries.
 */
data class GlyphCacheBudget(
    val maxBytes: Long,
    val maxEntries: Int,
) {
    init {
        require(maxBytes >= 0L) { "maxBytes must be non-negative." }
        require(maxEntries >= 0) { "maxEntries must be non-negative." }
    }
}

/**
 * Describes one shaped glyph observed while building a renderer-neutral cache inventory.
 *
 * This input type carries only deterministic, pure Kotlin facts that a shaping or cache build
 * step already knows. It deliberately excludes font objects, path objects, canvas state, GPU
 * handles, native pointers, and rasterizer callbacks so the resulting inventory can be used as
 * stable debug evidence across renderers.
 *
 * @property index zero-based position of the glyph in the shaped run.
 * @property codePoint Unicode scalar value associated with this glyph position.
 * @property glyphId font-specific glyph identifier selected for [codePoint].
 * @property key stable cache key that identifies the strike-specific glyph record.
 * @property advance horizontal advance in pixels or caller-defined run units.
 * @property x horizontal glyph origin in pixels or caller-defined run units.
 * @property maskSummary renderer-neutral summary of the cached mask artifact, or
 * [GlyphMaskSummary.Empty] when no mask pixels are present.
 * @property diagnostic optional diagnostic code attached to this glyph position.
 */
data class GlyphRunCacheBuildGlyph(
    val index: Int,
    val codePoint: Int,
    val glyphId: Int,
    val key: String,
    val advance: Float,
    val x: Float,
    val maskSummary: GlyphMaskSummary = GlyphMaskSummary.Empty,
    val diagnostic: String? = null,
)

/**
 * Records one glyph position in a stable renderer-neutral cache inventory.
 *
 * Items preserve shaped-run order and are intentionally lighter than cache records: each item
 * points at a cache [key] and keeps the per-position facts needed to audit glyph selection,
 * advance accumulation, placement, and missing-glyph diagnostics.
 *
 * @property index zero-based position of the glyph in the shaped run.
 * @property codePoint Unicode scalar value associated with this glyph position.
 * @property glyphId font-specific glyph identifier selected for [codePoint].
 * @property key stable cache key for the deduplicated glyph record used by this item.
 * @property advance horizontal advance in pixels or caller-defined run units.
 * @property x horizontal glyph origin in pixels or caller-defined run units.
 * @property diagnostic optional diagnostic code attached to this glyph position.
 */
data class GlyphRunCacheInventoryItem(
    val index: Int,
    val codePoint: Int,
    val glyphId: Int,
    val key: String,
    val advance: Float,
    val x: Float,
    val diagnostic: String?,
)

/**
 * Summarizes a cached glyph mask without retaining renderer or rasterizer-specific objects.
 *
 * The summary is suitable for deterministic debug dumps: dimensions and origin describe the mask
 * rectangle, [rowBytes] preserves source row stride, [nonZeroPixels] captures coverage density,
 * and [sha256] identifies the addressable samples without embedding the full pixel payload.
 *
 * @property left horizontal mask origin relative to glyph origin.
 * @property top vertical mask origin relative to glyph origin.
 * @property width addressable mask width in pixels.
 * @property height addressable mask height in pixels.
 * @property rowBytes source row stride in samples.
 * @property nonZeroPixels count of non-zero addressable samples.
 * @property sha256 SHA-256 digest of addressable samples in row-major order.
 */
data class GlyphMaskSummary(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val rowBytes: Int,
    val nonZeroPixels: Int,
    val sha256: String,
) {
    /**
     * Shared constructors and constants for renderer-neutral glyph mask summaries.
     */
    companion object {
        /**
         * SHA-256 digest of an empty byte array.
         */
        const val EmptyHash: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        /**
         * Empty mask summary used for whitespace, missing outlines, and deferred mask generation.
         */
        val Empty: GlyphMaskSummary = GlyphMaskSummary(
            left = 0,
            top = 0,
            width = 0,
            height = 0,
            rowBytes = 0,
            nonZeroPixels = 0,
            sha256 = EmptyHash,
        )

        /**
         * Builds a deterministic summary from an in-memory A8 mask.
         *
         * Only addressable samples inside [A8GlyphMask.width] by [A8GlyphMask.height] are hashed;
         * row padding beyond [A8GlyphMask.width] is ignored so callers can compare masks with the
         * same visible pixels but different backing stride.
         *
         * @param mask source A8 glyph mask to summarize.
         * @return renderer-neutral mask summary for debug evidence and cache records.
         */
        fun fromA8Mask(mask: A8GlyphMask): GlyphMaskSummary {
            mask.requireValidA8Pixels()
            if (mask.width == 0 || mask.height == 0) {
                return Empty
            }

            val samples = ByteArray(mask.width * mask.height)
            var targetIndex = 0
            var nonZeroPixels = 0
            for (row in 0 until mask.height) {
                for (column in 0 until mask.width) {
                    val sample = mask.pixels[row * mask.rowBytes + column]
                    if (sample != 0) {
                        nonZeroPixels += 1
                    }
                    samples[targetIndex] = sample.toByte()
                    targetIndex += 1
                }
            }

            return GlyphMaskSummary(
                left = mask.left,
                top = mask.top,
                width = mask.width,
                height = mask.height,
                rowBytes = mask.rowBytes,
                nonZeroPixels = nonZeroPixels,
                sha256 = glyphSha256(samples),
            )
        }
    }
}

/**
 * Describes one deduplicated glyph cache record in a renderer-neutral run inventory.
 *
 * Records are grouped by stable [key] in first-seen run order. They aggregate the Unicode code
 * points that selected the same cached glyph artifact while preserving the first record's glyph
 * identity, advance, mask summary, and diagnostic.
 *
 * @property key stable cache key shared by all run items represented by this record.
 * @property glyphId font-specific glyph identifier represented by the cache record.
 * @property codePoints distinct Unicode scalar values that mapped to this record, in first-seen
 * order.
 * @property advance horizontal advance in pixels or caller-defined run units.
 * @property maskSummary renderer-neutral summary of the cached mask artifact.
 * @property diagnostic optional diagnostic code attached to the record.
 */
data class GlyphCacheRecord(
    val key: String,
    val glyphId: Int,
    val codePoints: List<Int>,
    val advance: Float,
    val maskSummary: GlyphMaskSummary,
    val diagnostic: String?,
)

/**
 * Stable debug artifact for auditing one shaped glyph run and its deduplicated cache records.
 *
 * This inventory is renderer-neutral: it does not retain font engines, path objects, canvases,
 * GPU resources, native handles, or rasterizer state. Callers provide already-known glyph facts,
 * and [build] converts them into deterministic per-position [items], deduplicated [records],
 * ordered [diagnostics], canonical JSON, and a SHA-256 dump hash.
 *
 * @property runId stable caller-defined identifier for the shaped run or evidence scope.
 * @property representation stable representation label, such as [AlphaMaskRepresentation].
 * @property items glyph positions in shaped-run order.
 * @property records deduplicated cache records in first-seen key order.
 * @property diagnostics distinct diagnostic codes in first-seen order.
 */
data class GlyphRunCacheInventory(
    val runId: String,
    val representation: String,
    val items: List<GlyphRunCacheInventoryItem>,
    val records: List<GlyphCacheRecord>,
    val diagnostics: List<String>,
) {
    /**
     * SHA-256 digest of [toCanonicalJson] content with the `dumpSha256` field omitted.
     */
    val dumpSha256: String by lazy {
        glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))
    }

    /**
     * Serializes this inventory into stable human-readable JSON.
     *
     * The serializer is intentionally local and deterministic: field order, array order, float
     * formatting, Unicode escaping, and trailing newline are fixed so PM evidence and debug
     * artifacts can be compared by text diff or [dumpSha256].
     *
     * @return canonical JSON dump ending with a newline.
     */
    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    /**
     * Shared constructors and constants for run cache inventories.
     */
    companion object {
        /**
         * Stable representation label for 8-bit alpha mask glyph cache records.
         */
        const val AlphaMaskRepresentation: String = "font.glyph.alpha-mask"

        /**
         * Builds a deterministic inventory from shaped glyph cache observations.
         *
         * Items are preserved in input order. Records are deduplicated by [GlyphRunCacheBuildGlyph.key]
         * in first-seen order, and record code points are distinct in first-seen order. Reusing a
         * key with a different glyph ID, advance, or mask summary is rejected because that would
         * make the cache evidence ambiguous.
         *
         * @param runId stable caller-defined identifier for the shaped run or evidence scope.
         * @param representation stable representation label for the cache artifact type.
         * @param glyphs shaped glyph observations to inventory.
         * @return deterministic renderer-neutral cache inventory.
         */
        fun build(
            runId: String,
            representation: String,
            glyphs: List<GlyphRunCacheBuildGlyph>,
        ): GlyphRunCacheInventory {
            val items = ArrayList<GlyphRunCacheInventoryItem>(glyphs.size)
            val recordsByKey = linkedMapOf<String, MutableGlyphCacheRecord>()
            val diagnostics = linkedSetOf<String>()

            glyphs.forEach { glyph ->
                glyph.diagnostic?.let { diagnostic -> diagnostics += diagnostic }
                items += GlyphRunCacheInventoryItem(
                    index = glyph.index,
                    codePoint = glyph.codePoint,
                    glyphId = glyph.glyphId,
                    key = glyph.key,
                    advance = glyph.advance,
                    x = glyph.x,
                    diagnostic = glyph.diagnostic,
                )

                val record = recordsByKey.getOrPut(glyph.key) {
                    MutableGlyphCacheRecord(
                        key = glyph.key,
                        glyphId = glyph.glyphId,
                        codePoints = linkedSetOf(),
                        advance = glyph.advance,
                        maskSummary = glyph.maskSummary,
                        diagnostic = glyph.diagnostic,
                    )
                }
                record.requireCompatible(glyph)
                record.codePoints += glyph.codePoint
                if (record.diagnostic == null && glyph.diagnostic != null) {
                    record.diagnostic = glyph.diagnostic
                }
            }

            return GlyphRunCacheInventory(
                runId = runId,
                representation = representation,
                items = items.toList(),
                records = recordsByKey.values.map { record -> record.freeze() },
                diagnostics = diagnostics.toList(),
            )
        }
    }

    /**
     * Serializes this inventory with optional dump hash inclusion.
     */
    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("runId", runId, comma = true)
        appendGlyphJsonField("representation", representation, comma = true)
        appendGlyphJsonField("itemCount", items.size, comma = true)
        appendGlyphJsonField("recordCount", records.size, comma = true)
        append("  \"diagnostics\": [")
        append(diagnostics.joinToString(", ") { diagnostic -> glyphJsonString(diagnostic) })
        append("],\n")
        append("  \"items\": [\n")
        append(items.joinToString(",\n") { item -> item.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"records\": [\n")
        append(records.joinToString(",\n") { record -> record.toCanonicalJson().prependIndent("    ") })
        append("\n  ]")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * One resident or evicted glyph cache artifact captured for deterministic cache evidence.
 *
 * The entry preserves the stable strike preimage used for cache addressing, the route-specific
 * strike hash, memory accounting, and optional lifecycle tokens without retaining JVM or GPU
 * handles.
 */
data class GlyphCacheInventoryEntry(
    val artifactType: String,
    val glyphId: Int,
    val keyPreimage: String,
    val keyPreimageSha256: String,
    val strikeKeySha256: String,
    val residentBytes: Long,
    val lifecycleState: String,
    val generation: Long? = null,
    val invalidationToken: String? = null,
) {
    init {
        require(artifactType.isNotBlank()) { "artifactType must not be blank." }
        require(glyphId >= 0) { "glyphId must be non-negative." }
        require(keyPreimage.isNotBlank()) { "keyPreimage must not be blank." }
        require(keyPreimageSha256.matches(Regex("[0-9a-f]{64}"))) {
            "keyPreimageSha256 must be lowercase hexadecimal."
        }
        require(strikeKeySha256.matches(Regex("[0-9a-f]{64}"))) {
            "strikeKeySha256 must be lowercase hexadecimal."
        }
        require(residentBytes >= 0L) { "residentBytes must be non-negative." }
        require(lifecycleState == ResidentState || lifecycleState == EvictedState) {
            "lifecycleState must be resident or evicted."
        }
        generation?.let { require(it >= 0L) { "generation must be non-negative." } }
        invalidationToken?.let { require(it.isNotBlank()) { "invalidationToken must not be blank." } }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonField("artifactType", artifactType, comma = true)
        appendGlyphJsonField("glyphId", glyphId, comma = true)
        appendGlyphJsonField("keyPreimage", keyPreimage, comma = true)
        appendGlyphJsonField("keyPreimageSha256", keyPreimageSha256, comma = true)
        appendGlyphJsonField("strikeKeySha256", strikeKeySha256, comma = true)
        appendGlyphJsonField("residentBytes", residentBytes, comma = true)
        appendGlyphJsonField("lifecycleState", lifecycleState, comma = true)
        appendGlyphJsonNullableField("generation", generation, comma = true)
        appendGlyphJsonNullableField("invalidationToken", invalidationToken, comma = false)
        append("}")
    }

    companion object {
        const val ResidentState: String = "resident"
        const val EvictedState: String = "evicted"

        fun fromRepresentation(
            strikeKey: GlyphStrikeKey,
            representation: GlyphRepresentation,
            lifecycleState: String,
            generation: Long? = null,
            invalidationToken: String? = null,
        ): GlyphCacheInventoryEntry {
            val keyPreimage = strikeKey.canonicalPreimage(representation.glyphId).trimEnd()
            val route = strikeKey.representationRoute.trim().ifEmpty {
                representation.inventoryArtifactType()
            }
            return GlyphCacheInventoryEntry(
                artifactType = representation.inventoryArtifactType(),
                glyphId = representation.glyphId,
                keyPreimage = keyPreimage,
                keyPreimageSha256 = glyphSha256(keyPreimage.toByteArray(Charsets.UTF_8)),
                strikeKeySha256 = strikeKey.artifactPlanKeySha256(representation.glyphId, route),
                residentBytes = representation.approximateCacheBytes(),
                lifecycleState = lifecycleState,
                generation = generation,
                invalidationToken = invalidationToken,
            )
        }
    }
}

/**
 * Deterministic cache inventory dump for `glyph-cache-inventory.json`.
 */
class GlyphCacheInventoryDump(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    val budgetBytes: Long,
    val maxEntries: Int,
    residentEntries: List<GlyphCacheInventoryEntry>,
    evictedEntries: List<GlyphCacheInventoryEntry>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val residentEntries: List<GlyphCacheInventoryEntry> = residentEntries.toList()
    val evictedEntries: List<GlyphCacheInventoryEntry> = evictedEntries.toList()
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    init {
        require(dumpId.isNotBlank()) { "dumpId must not be blank." }
        require(ownerTickets.isNotEmpty()) { "ownerTickets must be non-empty." }
        require(fixtureIds.isNotEmpty()) { "fixtureIds must be non-empty." }
        require(budgetBytes >= 0L) { "budgetBytes must be non-negative." }
        require(maxEntries >= 0) { "maxEntries must be non-negative." }
        require(this.residentEntries.all { entry -> entry.lifecycleState == GlyphCacheInventoryEntry.ResidentState }) {
            "residentEntries must use lifecycleState resident."
        }
        require(this.evictedEntries.all { entry -> entry.lifecycleState == GlyphCacheInventoryEntry.EvictedState }) {
            "evictedEntries must use lifecycleState evicted."
        }
    }

    val entryCount: Int
        get() = residentEntries.size + evictedEntries.size

    val residentBytes: Long
        get() = residentEntries.sumOf { entry -> entry.residentBytes }

    val evictedBytes: Long
        get() = evictedEntries.sumOf { entry -> entry.residentBytes }

    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("budgetBytes", budgetBytes, comma = true)
        appendGlyphJsonField("maxEntries", maxEntries, comma = true)
        appendGlyphJsonField("entryCount", entryCount, comma = true)
        appendGlyphJsonField("residentBytes", residentBytes, comma = true)
        appendGlyphJsonField("evictedBytes", evictedBytes, comma = true)
        append("  \"residentEntries\": ")
        appendGlyphCacheInventoryEntriesJson(residentEntries)
        append(",\n")
        append("  \"evictedEntries\": ")
        appendGlyphCacheInventoryEntriesJson(evictedEntries)
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Sample metadata for advisory glyph cache telemetry.
 */
data class GlyphCacheTelemetryMetadata(
    val environmentLabel: String,
    val fontSourceSet: String,
    val unicodeVersion: String,
    val cacheState: String,
    val sampleLabel: String,
    val sampleCount: Int,
    val timingMode: String = "fixture-counter",
    val releaseGatePromoted: Boolean = false,
) {
    init {
        require(environmentLabel.isNotBlank()) { "environmentLabel must not be blank." }
        require(fontSourceSet.isNotBlank()) { "fontSourceSet must not be blank." }
        require(unicodeVersion.isNotBlank()) { "unicodeVersion must not be blank." }
        require(cacheState.isNotBlank()) { "cacheState must not be blank." }
        require(sampleLabel.isNotBlank()) { "sampleLabel must not be blank." }
        require(sampleCount >= 0) { "sampleCount must be non-negative." }
        require(timingMode.isNotBlank()) { "timingMode must not be blank." }
        require(!releaseGatePromoted) {
            "Glyph cache telemetry is advisory-only and must not promote a release gate."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonField("environmentLabel", environmentLabel, comma = true)
        appendGlyphJsonField("fontSourceSet", fontSourceSet, comma = true)
        appendGlyphJsonField("unicodeVersion", unicodeVersion, comma = true)
        appendGlyphJsonField("cacheState", cacheState, comma = true)
        appendGlyphJsonField("sampleLabel", sampleLabel, comma = true)
        appendGlyphJsonField("sampleCount", sampleCount, comma = true)
        appendGlyphJsonField("timingMode", timingMode, comma = true)
        appendGlyphJsonField("releaseGatePromoted", releaseGatePromoted, comma = false)
        append("\n}")
    }
}

/**
 * One route-count observation in a glyph cache telemetry sample.
 */
data class GlyphCacheRouteCount(
    val route: String,
    val count: Int,
) {
    init {
        require(route.isNotBlank()) { "route must not be blank." }
        require(count >= 0) { "count must be non-negative." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonField("route", route, comma = true)
        appendGlyphJsonField("count", count, comma = false)
        append("\n}")
    }
}

/**
 * Deterministic timing summary in advisory fixture ticks.
 */
data class GlyphCacheTimingStats(
    val metricName: String,
    val sampleCount: Int,
    val median: Long,
    val p90: Long,
    val max: Long,
    val unit: String = "fixture-ticks",
) {
    init {
        require(metricName.isNotBlank()) { "metricName must not be blank." }
        require(sampleCount >= 0) { "sampleCount must be non-negative." }
        require(median >= 0L) { "median must be non-negative." }
        require(p90 >= 0L) { "p90 must be non-negative." }
        require(max >= 0L) { "max must be non-negative." }
        require(median <= p90 && p90 <= max) { "median <= p90 <= max must hold." }
        require(unit.isNotBlank()) { "unit must not be blank." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonField("metricName", metricName, comma = true)
        appendGlyphJsonField("sampleCount", sampleCount, comma = true)
        appendGlyphJsonField("median", median, comma = true)
        appendGlyphJsonField("p90", p90, comma = true)
        appendGlyphJsonField("max", max, comma = true)
        appendGlyphJsonField("unit", unit, comma = false)
        append("\n}")
    }
}

/**
 * Advisory budget-refusal record tied to one strike-key preimage.
 */
data class GlyphCacheBudgetRefusal(
    val diagnosticCode: String,
    val cacheDomain: String,
    val keyPreimageSha256: String,
    val observedBytes: Long,
    val advisoryLimitBytes: Long,
) {
    init {
        require(diagnosticCode.isNotBlank()) { "diagnosticCode must not be blank." }
        require(cacheDomain.isNotBlank()) { "cacheDomain must not be blank." }
        require(keyPreimageSha256.matches(Regex("[0-9a-f]{64}"))) {
            "keyPreimageSha256 must be lowercase hexadecimal."
        }
        require(observedBytes >= 0L) { "observedBytes must be non-negative." }
        require(advisoryLimitBytes >= 0L) { "advisoryLimitBytes must be non-negative." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonField("diagnosticCode", diagnosticCode, comma = true)
        appendGlyphJsonField("cacheDomain", cacheDomain, comma = true)
        appendGlyphJsonField("keyPreimageSha256", keyPreimageSha256, comma = true)
        appendGlyphJsonField("observedBytes", observedBytes, comma = true)
        appendGlyphJsonField("advisoryLimitBytes", advisoryLimitBytes, comma = false)
        append("\n}")
    }

    companion object {
        fun fromStrikeKey(
            strikeKey: GlyphStrikeKey,
            cacheDomain: String,
            observedBytes: Long,
            advisoryLimitBytes: Long,
        ): GlyphCacheBudgetRefusal {
            require(strikeKey.glyphId != null) { "Glyph strike key must include glyphId for budget refusals." }
            return GlyphCacheBudgetRefusal(
                diagnosticCode = GlyphArtifactBudgetExceededDiagnosticRoute,
                cacheDomain = cacheDomain,
                keyPreimageSha256 = strikeKey.preimageSha256(),
                observedBytes = observedBytes,
                advisoryLimitBytes = advisoryLimitBytes,
            )
        }
    }
}

/**
 * One advisory telemetry sample for cold or warm glyph cache evidence.
 */
class GlyphCacheTelemetrySample(
    val metadata: GlyphCacheTelemetryMetadata,
    routeCounts: List<GlyphCacheRouteCount>,
    timings: List<GlyphCacheTimingStats>,
    val cacheHitCount: Int,
    val cacheMissCount: Int,
    val evictionCount: Int,
    val invalidationCount: Int,
    val residentBytes: Long,
    val uploadPreparationBytes: Long,
    val artifactBudgetRefusalCount: Int,
    budgetRefusals: List<GlyphCacheBudgetRefusal>,
) {
    val routeCounts: List<GlyphCacheRouteCount> =
        routeCounts.sortedWith(compareBy({ it.route }, { it.count }))
    val timings: List<GlyphCacheTimingStats> =
        timings.sortedWith(compareBy({ it.metricName }, { it.sampleCount }, { it.median }, { it.p90 }, { it.max }, { it.unit }))
    val budgetRefusals: List<GlyphCacheBudgetRefusal> =
        budgetRefusals.sortedWith(compareBy({ it.cacheDomain }, { it.keyPreimageSha256 }, { it.observedBytes }, { it.advisoryLimitBytes }))

    init {
        require(cacheHitCount >= 0) { "cacheHitCount must be non-negative." }
        require(cacheMissCount >= 0) { "cacheMissCount must be non-negative." }
        require(evictionCount >= 0) { "evictionCount must be non-negative." }
        require(invalidationCount >= 0) { "invalidationCount must be non-negative." }
        require(residentBytes >= 0L) { "residentBytes must be non-negative." }
        require(uploadPreparationBytes >= 0L) { "uploadPreparationBytes must be non-negative." }
        require(artifactBudgetRefusalCount >= 0) { "artifactBudgetRefusalCount must be non-negative." }
        require(artifactBudgetRefusalCount == this.budgetRefusals.size) {
            "artifactBudgetRefusalCount must match budgetRefusals.size."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"metadata\": ")
        append(metadata.toCanonicalJson().prependGlyphJsonIndent("  ").trimStart())
        append(",\n")
        append("  \"routeCounts\": ")
        appendGlyphCacheRouteCountsJson(routeCounts)
        append(",\n")
        append("  \"timings\": ")
        appendGlyphCacheTimingStatsJson(timings)
        append(",\n")
        appendGlyphJsonField("cacheHitCount", cacheHitCount, comma = true)
        appendGlyphJsonField("cacheMissCount", cacheMissCount, comma = true)
        appendGlyphJsonField("evictionCount", evictionCount, comma = true)
        appendGlyphJsonField("invalidationCount", invalidationCount, comma = true)
        appendGlyphJsonField("residentBytes", residentBytes, comma = true)
        appendGlyphJsonField("uploadPreparationBytes", uploadPreparationBytes, comma = true)
        appendGlyphJsonField("artifactBudgetRefusalCount", artifactBudgetRefusalCount, comma = true)
        append("  \"budgetRefusals\": ")
        appendGlyphCacheBudgetRefusalsJson(budgetRefusals)
        append("\n}")
    }
}

/**
 * Deterministic advisory cache telemetry dump for `glyph-cache-telemetry.json`.
 */
class GlyphCacheTelemetryDump(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    samples: List<GlyphCacheTelemetrySample>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val samples: List<GlyphCacheTelemetrySample> = samples.toList()
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    init {
        require(dumpId.isNotBlank()) { "dumpId must not be blank." }
        require(ownerTickets.isNotEmpty()) { "ownerTickets must be non-empty." }
        require(fixtureIds.isNotEmpty()) { "fixtureIds must be non-empty." }
        require(this.samples.isNotEmpty()) { "samples must be non-empty." }
    }

    val sampleCount: Int
        get() = samples.size

    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("sampleCount", sampleCount, comma = true)
        append("  \"samples\": ")
        appendGlyphCacheTelemetrySamplesJson(samples)
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Advisory glyph artifact metrics sample for deterministic M12 fixture evidence.
 */
class GlyphArtifactMetricsSample(
    val metadata: GlyphCacheTelemetryMetadata,
    routeCounts: List<GlyphCacheRouteCount>,
    timings: List<GlyphCacheTimingStats>,
    val sourceMaskHashCount: Int,
    refusalCounts: List<GlyphCacheRouteCount>,
    routePreimages: List<GlyphStrikeKeyRoutePreimage>,
) {
    val routeCounts: List<GlyphCacheRouteCount> =
        routeCounts.sortedWith(compareBy({ it.route }, { it.count }))
    val timings: List<GlyphCacheTimingStats> =
        timings.sortedWith(compareBy({ it.metricName }, { it.sampleCount }, { it.median }, { it.p90 }, { it.max }, { it.unit }))
    val refusalCounts: List<GlyphCacheRouteCount> =
        refusalCounts.sortedWith(compareBy({ it.route }, { it.count }))
    val routePreimages: List<GlyphStrikeKeyRoutePreimage> =
        routePreimages.sortedWith(compareBy({ it.route }, { it.glyphId }, { it.compactHash }))

    init {
        require(sourceMaskHashCount >= 0) { "sourceMaskHashCount must be non-negative." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"metadata\": ")
        append(metadata.toCanonicalJson().prependGlyphJsonIndent("  ").trimStart())
        append(",\n")
        append("  \"routeCounts\": ")
        appendGlyphCacheRouteCountsJson(routeCounts)
        append(",\n")
        append("  \"timings\": ")
        appendGlyphCacheTimingStatsJson(timings)
        append(",\n")
        appendGlyphJsonField("sourceMaskHashCount", sourceMaskHashCount, comma = true)
        append("  \"refusalCounts\": ")
        appendGlyphCacheRouteCountsJson(refusalCounts)
        append(",\n")
        append("  \"routePreimages\": ")
        appendGlyphRoutePreimagesJson(routePreimages)
        append("\n}")
    }
}

/**
 * Deterministic advisory dump for `glyph-artifact-metrics.json`.
 */
class GlyphArtifactMetricsDump(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    samples: List<GlyphArtifactMetricsSample>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val samples: List<GlyphArtifactMetricsSample> = samples.toList()
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    init {
        require(dumpId.isNotBlank()) { "dumpId must not be blank." }
        require(this.ownerTickets.isNotEmpty()) { "ownerTickets must be non-empty." }
        require(this.fixtureIds.isNotEmpty()) { "fixtureIds must be non-empty." }
        require(this.samples.isNotEmpty()) { "samples must be non-empty." }
    }

    val sampleCount: Int
        get() = samples.size

    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("sampleCount", sampleCount, comma = true)
        append("  \"samples\": ")
        appendGlyphArtifactMetricsSamplesJson(samples)
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Stable atlas occupancy row derived from a deterministic atlas artifact dump.
 */
data class GlyphAtlasOccupancyEntry(
    val artifactType: String,
    val artifactId: String,
    val generation: Long,
    val invalidationToken: String,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val entryCount: Int,
    val occupiedPixels: Int,
    val totalPixels: Int,
    val occupancyRatio: Float,
    val keyPreimageSha256s: List<String>,
    val distanceRange: Float? = null,
) {
    init {
        require(artifactType.isNotBlank()) { "artifactType must not be blank." }
        require(artifactId.matches(Regex("[0-9a-f]{64}"))) { "artifactId must be lowercase hexadecimal." }
        require(generation >= 0L) { "generation must be non-negative." }
        require(invalidationToken.isNotBlank()) { "invalidationToken must not be blank." }
        require(width >= 0 && height >= 0 && rowStride >= 0) { "atlas dimensions must be non-negative." }
        require(entryCount >= 0) { "entryCount must be non-negative." }
        require(occupiedPixels >= 0) { "occupiedPixels must be non-negative." }
        require(totalPixels >= 0) { "totalPixels must be non-negative." }
        require(totalPixels == checkedAtlasPixelCount(width, height, "glyph-atlas-occupancy")) {
            "totalPixels must match width * height."
        }
        require(occupiedPixels <= totalPixels) {
            "occupiedPixels must not exceed totalPixels."
        }
        require(occupancyRatio.isFinite()) { "occupancyRatio must be finite." }
        require(occupancyRatio >= 0f) { "occupancyRatio must be non-negative." }
        if (totalPixels == 0) {
            require(occupiedPixels == 0) { "occupiedPixels must be zero when totalPixels is zero." }
            require(occupancyRatio == 0f) { "occupancyRatio must be zero when totalPixels is zero." }
        } else {
            require(occupancyRatio <= 1f) { "occupancyRatio must not exceed 1.0." }
        }
        require(keyPreimageSha256s.all { it.matches(Regex("[0-9a-f]{64}")) }) {
            "keyPreimageSha256s must be lowercase hexadecimal."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonField("artifactType", artifactType, comma = true)
        appendGlyphJsonField("artifactId", artifactId, comma = true)
        appendGlyphJsonField("generation", generation, comma = true)
        appendGlyphJsonField("invalidationToken", invalidationToken, comma = true)
        appendGlyphJsonField("width", width, comma = true)
        appendGlyphJsonField("height", height, comma = true)
        appendGlyphJsonField("rowStride", rowStride, comma = true)
        appendGlyphJsonField("entryCount", entryCount, comma = true)
        appendGlyphJsonField("occupiedPixels", occupiedPixels, comma = true)
        appendGlyphJsonField("totalPixels", totalPixels, comma = true)
        appendGlyphJsonField("occupancyRatio", occupancyRatio, comma = true)
        append("  \"keyPreimageSha256s\": ")
        appendGlyphStringArrayInlineJson(keyPreimageSha256s)
        if (distanceRange != null) {
            append(",\n")
            appendGlyphJsonField("distanceRange", distanceRange, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }

    companion object {
        fun fromArtifact(
            artifact: GlyphAtlasArtifactEvidence,
            keyPreimageSha256s: List<String>,
        ): GlyphAtlasOccupancyEntry {
            val occupiedPixels = artifact.entries.sumOf { entry ->
                entry.width.nonNegativeProduct(other = entry.height, glyphId = entry.glyphId, label = "Glyph atlas occupancy entry")
            }.toInt()
            val totalPixels = checkedAtlasPixelCount(artifact.width, artifact.height, "atlas-occupancy")
            val occupancyRatio = if (totalPixels == 0) 0f else occupiedPixels.toFloat() / totalPixels.toFloat()
            return GlyphAtlasOccupancyEntry(
                artifactType = artifact.artifactType,
                artifactId = artifact.artifactKeySha256,
                generation = artifact.generation,
                invalidationToken = artifact.invalidationToken,
                width = artifact.width,
                height = artifact.height,
                rowStride = artifact.rowStride,
                entryCount = artifact.entryCount,
                occupiedPixels = occupiedPixels,
                totalPixels = totalPixels,
                occupancyRatio = occupancyRatio,
                keyPreimageSha256s = keyPreimageSha256s.sorted(),
                distanceRange = artifact.distanceRange,
            )
        }
    }
}

/**
 * Deterministic occupancy dump for `glyph-atlas-occupancy.json`.
 */
class GlyphAtlasOccupancyDump(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    atlases: List<GlyphAtlasOccupancyEntry>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val atlases: List<GlyphAtlasOccupancyEntry> =
        atlases.sortedWith(compareBy({ it.artifactType }, { it.artifactId }))
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    init {
        require(dumpId.isNotBlank()) { "dumpId must not be blank." }
        require(this.ownerTickets.isNotEmpty()) { "ownerTickets must be non-empty." }
        require(this.fixtureIds.isNotEmpty()) { "fixtureIds must be non-empty." }
        require(this.atlases.isNotEmpty()) { "atlases must be non-empty." }
    }

    val atlasCount: Int
        get() = atlases.size

    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("atlasCount", atlasCount, comma = true)
        append("  \"atlases\": ")
        appendGlyphAtlasOccupancyEntriesJson(atlases)
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Advisory glyph cache metrics sample with stable atlas/key facts for M12 evidence.
 */
class GlyphCacheMetricsSample(
    val metadata: GlyphCacheTelemetryMetadata,
    val atlasArtifactId: String,
    val strikeKeyCount: Int,
    keyPreimageSha256s: List<String>,
    val occupancyRatio: Float,
    timings: List<GlyphCacheTimingStats>,
    val cacheHitCount: Int,
    val cacheMissCount: Int,
    val evictionCount: Int,
    val atlasCapacityPressureCount: Int,
    val staleGenerationRefusalCount: Int,
    val invalidationTokenChangeCount: Int,
    val cacheMemoryBytes: Long,
    val uploadByteExpectation: Long,
    val artifactBudgetRefusalCount: Int,
    budgetRefusals: List<GlyphCacheBudgetRefusal>,
) {
    val keyPreimageSha256s: List<String> = keyPreimageSha256s.sorted()
    val timings: List<GlyphCacheTimingStats> =
        timings.sortedWith(compareBy({ it.metricName }, { it.sampleCount }, { it.median }, { it.p90 }, { it.max }, { it.unit }))
    val budgetRefusals: List<GlyphCacheBudgetRefusal> =
        budgetRefusals.sortedWith(compareBy({ it.cacheDomain }, { it.keyPreimageSha256 }, { it.observedBytes }, { it.advisoryLimitBytes }))

    init {
        require(atlasArtifactId.matches(Regex("[0-9a-f]{64}"))) {
            "atlasArtifactId must be lowercase hexadecimal."
        }
        require(strikeKeyCount >= 0) { "strikeKeyCount must be non-negative." }
        require(this.keyPreimageSha256s.all { it.matches(Regex("[0-9a-f]{64}")) }) {
            "keyPreimageSha256s must be lowercase hexadecimal."
        }
        require(strikeKeyCount == this.keyPreimageSha256s.size) {
            "strikeKeyCount must match keyPreimageSha256s.size."
        }
        require(occupancyRatio.isFinite()) { "occupancyRatio must be finite." }
        require(occupancyRatio >= 0f) { "occupancyRatio must be non-negative." }
        require(occupancyRatio <= 1f) { "occupancyRatio must not exceed 1.0." }
        require(cacheHitCount >= 0) { "cacheHitCount must be non-negative." }
        require(cacheMissCount >= 0) { "cacheMissCount must be non-negative." }
        require(evictionCount >= 0) { "evictionCount must be non-negative." }
        require(atlasCapacityPressureCount >= 0) { "atlasCapacityPressureCount must be non-negative." }
        require(staleGenerationRefusalCount >= 0) { "staleGenerationRefusalCount must be non-negative." }
        require(invalidationTokenChangeCount >= 0) { "invalidationTokenChangeCount must be non-negative." }
        require(cacheMemoryBytes >= 0L) { "cacheMemoryBytes must be non-negative." }
        require(uploadByteExpectation >= 0L) { "uploadByteExpectation must be non-negative." }
        require(artifactBudgetRefusalCount >= 0) { "artifactBudgetRefusalCount must be non-negative." }
        require(artifactBudgetRefusalCount == this.budgetRefusals.size) {
            "artifactBudgetRefusalCount must match budgetRefusals.size."
        }
        require(this.budgetRefusals.all { it.keyPreimageSha256 in this.keyPreimageSha256s }) {
            "budgetRefusals keyPreimageSha256 values must belong to keyPreimageSha256s."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"metadata\": ")
        append(metadata.toCanonicalJson().prependGlyphJsonIndent("  ").trimStart())
        append(",\n")
        appendGlyphJsonField("atlasArtifactId", atlasArtifactId, comma = true)
        appendGlyphJsonField("strikeKeyCount", strikeKeyCount, comma = true)
        append("  \"keyPreimageSha256s\": ")
        appendGlyphStringArrayInlineJson(keyPreimageSha256s)
        append(",\n")
        appendGlyphJsonField("occupancyRatio", occupancyRatio, comma = true)
        append("  \"timings\": ")
        appendGlyphCacheTimingStatsJson(timings)
        append(",\n")
        appendGlyphJsonField("cacheHitCount", cacheHitCount, comma = true)
        appendGlyphJsonField("cacheMissCount", cacheMissCount, comma = true)
        appendGlyphJsonField("evictionCount", evictionCount, comma = true)
        appendGlyphJsonField("atlasCapacityPressureCount", atlasCapacityPressureCount, comma = true)
        appendGlyphJsonField("staleGenerationRefusalCount", staleGenerationRefusalCount, comma = true)
        appendGlyphJsonField("invalidationTokenChangeCount", invalidationTokenChangeCount, comma = true)
        appendGlyphJsonField("cacheMemoryBytes", cacheMemoryBytes, comma = true)
        appendGlyphJsonField("uploadByteExpectation", uploadByteExpectation, comma = true)
        appendGlyphJsonField("artifactBudgetRefusalCount", artifactBudgetRefusalCount, comma = true)
        append("  \"budgetRefusals\": ")
        appendGlyphCacheBudgetRefusalsJson(budgetRefusals)
        append("\n}")
    }
}

/**
 * Deterministic advisory dump for `glyph-cache-metrics.json`.
 */
class GlyphCacheMetricsDump(
    val dumpId: String,
    ownerTickets: List<String>,
    fixtureIds: List<String>,
    samples: List<GlyphCacheMetricsSample>,
    requiredDiagnostics: List<String>,
    nonClaims: List<String>,
) {
    val ownerTickets: List<String> = ownerTickets.toList()
    val fixtureIds: List<String> = fixtureIds.toList()
    val samples: List<GlyphCacheMetricsSample> = samples.toList()
    val requiredDiagnostics: List<String> = requiredDiagnostics.toList()
    val nonClaims: List<String> = nonClaims.toList()

    init {
        require(dumpId.isNotBlank()) { "dumpId must not be blank." }
        require(this.ownerTickets.isNotEmpty()) { "ownerTickets must be non-empty." }
        require(this.fixtureIds.isNotEmpty()) { "fixtureIds must be non-empty." }
        require(this.samples.isNotEmpty()) { "samples must be non-empty." }
    }

    val sampleCount: Int
        get() = samples.size

    val dumpSha256: String
        get() = glyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendGlyphJsonField("schemaVersion", 1, comma = true)
        appendGlyphJsonField("dumpId", dumpId, comma = true)
        append("  \"ownerTickets\": ")
        appendGlyphStringArrayMultilineJson(ownerTickets, indent = "  ")
        append(",\n")
        append("  \"fixtureIds\": ")
        appendGlyphStringArrayMultilineJson(fixtureIds, indent = "  ")
        append(",\n")
        appendGlyphJsonField("sampleCount", sampleCount, comma = true)
        append("  \"samples\": ")
        appendGlyphCacheMetricsSamplesJson(samples)
        append(",\n")
        append("  \"requiredDiagnostics\": ")
        appendGlyphStringArrayMultilineJson(requiredDiagnostics, indent = "  ")
        append(",\n")
        append("  \"nonClaims\": ")
        appendGlyphStringArrayMultilineJson(nonClaims, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Derives one advisory glyph artifact metrics sample from a runtime glyph plan.
 *
 * The producer stays renderer-neutral: callers provide the already-dumpable route preimages and
 * any refusal diagnostics that should remain visible in M12 evidence, while the plan contributes
 * the source representation hashes used for deterministic artifact accounting.
 */
fun GlyphArtifactPlan.toMetricsSample(
    metadata: GlyphCacheTelemetryMetadata,
    timings: List<GlyphCacheTimingStats>,
    routePreimages: List<GlyphStrikeKeyRoutePreimage>,
    additionalRefusalDiagnostics: List<GlyphRouteDiagnostic> = emptyList(),
): GlyphArtifactMetricsSample {
    require(routePreimages.all { routePreimage ->
        routePreimage.compactHash == glyphSha256(routePreimage.preimage.toByteArray(Charsets.UTF_8))
    }) {
        "routePreimages compactHash values must match their preimage payloads."
    }
    val expectedGlyphIds = run.glyphIds.groupingBy { it }.eachCount()
    val actualGlyphIds = routePreimages.groupingBy { it.glyphId }.eachCount()
    require(actualGlyphIds == expectedGlyphIds) { "routePreimages glyph IDs must match the source glyph run." }
    if (decisions.isNotEmpty()) {
        val expectedDecisionTriples = decisions.groupingBy { decision ->
            Triple(decision.glyphId, decision.selectedRoute, decision.keySha256)
        }.eachCount()
        val actualDecisionTriples = routePreimages.groupingBy { routePreimage ->
            Triple(routePreimage.glyphId, routePreimage.route, routePreimage.compactHash)
        }.eachCount()
        require(actualDecisionTriples == expectedDecisionTriples) {
            "routePreimages must match GlyphArtifactPlan decisions for glyphId, route, and compactHash."
        }
    }
    val refusalRoutes = buildList {
        decisions.forEach { decision ->
            decision.rejectedAlternatives.forEach { rejection ->
                if (rejection.reason != RouteRejectionUnavailable) {
                    add(rejection.reason)
                }
            }
            decision.diagnostic?.route?.let(::add)
        }
        val decisionDiagnosticKeys = decisions.mapNotNull { decision ->
            decision.diagnostic?.let { diagnostic -> Triple(diagnostic.glyphId, diagnostic.route, diagnostic.message) }
        }.toSet()
        diagnostics.forEach { diagnostic ->
            val key = Triple(diagnostic.glyphId, diagnostic.route, diagnostic.message)
            if (key !in decisionDiagnosticKeys) {
                add(diagnostic.route)
            }
        }
        additionalRefusalDiagnostics.forEach { diagnostic -> add(diagnostic.route) }
    }
    return GlyphArtifactMetricsSample(
        metadata = metadata,
        routeCounts = if (decisions.isNotEmpty()) {
            decisions.toMetricRouteCounts { decision -> decision.selectedRoute }
        } else {
            routePreimages.toMetricRouteCounts { record -> record.route }
        },
        timings = timings,
        sourceMaskHashCount = representations.mapNotNull { representation -> representation.sourceRepresentationSha256() }.distinct().size,
        refusalCounts = refusalRoutes.toMetricRouteCounts { route -> route },
        routePreimages = routePreimages,
    )
}

/**
 * Projects one glyph cache telemetry sample plus atlas occupancy facts into the M12 cache sample
 * shape. Atlas pack timing remains the only timing carried forward because the cache sample is
 * intended to expose atlas/cache pressure separately from per-route generation timings.
 */
fun GlyphCacheTelemetrySample.toMetricsSample(
    atlasOccupancy: GlyphAtlasOccupancyEntry,
    glyphIds: Set<Int>,
    atlasDiagnostics: List<GlyphRouteDiagnostic> = emptyList(),
): GlyphCacheMetricsSample {
    require(glyphIds.isNotEmpty()) { "glyphIds must be non-empty when scoping atlas diagnostics." }
    require(atlasDiagnostics.all { diagnostic -> diagnostic.glyphId in glyphIds }) {
        "atlasDiagnostics must already be scoped to the sample glyph IDs."
    }
    return GlyphCacheMetricsSample(
        metadata = metadata,
        atlasArtifactId = atlasOccupancy.artifactId,
        strikeKeyCount = atlasOccupancy.keyPreimageSha256s.size,
        keyPreimageSha256s = atlasOccupancy.keyPreimageSha256s,
        occupancyRatio = atlasOccupancy.occupancyRatio,
        timings = timings.filter { timing -> timing.metricName == "atlas-pack" },
        cacheHitCount = cacheHitCount,
        cacheMissCount = cacheMissCount,
        evictionCount = evictionCount,
        atlasCapacityPressureCount = atlasDiagnostics.count { diagnostic ->
            diagnostic.route == GlyphAtlasCapacityExceededDiagnosticRoute
        },
        staleGenerationRefusalCount = atlasDiagnostics.count { diagnostic ->
            diagnostic.route == GlyphAtlasGenerationStaleDiagnosticRoute
        },
        invalidationTokenChangeCount = invalidationCount,
        cacheMemoryBytes = residentBytes,
        uploadByteExpectation = uploadPreparationBytes,
        artifactBudgetRefusalCount = artifactBudgetRefusalCount,
        budgetRefusals = budgetRefusals,
    )
}

/**
 * Deterministic in-memory implementation of [GlyphCache] for module-local glyph planning.
 *
 * The cache keys entries by every strike input that can affect representation reuse: typeface,
 * glyph ID, pixel size, scale, subpixel buckets, and variation coordinates. Variation coordinates
 * are normalized by axis tag so maps with the same values but different iteration order address
 * the same entry. Eviction is deterministic FIFO by insertion order; replacing an existing entry
 * moves it to the newest position.
 *
 * The byte budget is approximate and intentionally renderer-independent. It counts addressable
 * mask samples and path command text with a small fixed overhead so tests and PM evidence can
 * exercise budget behavior without relying on platform object-size introspection.
 *
 * @property budget maximum approximate byte count and entry count retained by this cache.
 */
class InMemoryGlyphCache(
    private val budget: GlyphCacheBudget,
) : GlyphCache {
    private val entries = linkedMapOf<GlyphCacheKey, GlyphCacheEntry>()
    private var currentBytes = 0L

    /**
     * Looks up a representation by normalized strike and glyph identity.
     *
     * @param strikeKey strike key whose variation coordinates are normalized for lookup.
     * @param glyphId glyph identifier for the cached entry.
     * @return cached representation, or null when the entry was never stored or has been evicted.
     */
    override fun get(strikeKey: GlyphStrikeKey, glyphId: Int): GlyphRepresentation? =
        entries[GlyphCacheKey.from(strikeKey, glyphId)]?.representation

    /**
     * Stores a representation unless it cannot fit within the configured budget.
     *
     * @param strikeKey strike key whose rasterization-affecting fields become part of the cache
     * key.
     * @param representation representation to store under its glyph identifier.
     */
    override fun put(strikeKey: GlyphStrikeKey, representation: GlyphRepresentation) {
        val key = GlyphCacheKey.from(strikeKey, representation.glyphId)
        val byteSize = representation.approximateCacheBytes()
        val previousEntry = entries.remove(key)
        if (previousEntry != null) {
            currentBytes -= previousEntry.byteSize
        }

        if (budget.maxEntries == 0 || budget.maxBytes == 0L || byteSize > budget.maxBytes) {
            return
        }

        entries[key] = GlyphCacheEntry(
            representation = representation,
            byteSize = byteSize,
        )
        currentBytes += byteSize
        evictUntilWithinBudget()
    }

    /**
     * Removes oldest entries until both byte and entry budgets are satisfied.
     */
    private fun evictUntilWithinBudget() {
        val iterator = entries.iterator()
        while ((entries.size > budget.maxEntries || currentBytes > budget.maxBytes) && iterator.hasNext()) {
            val entry = iterator.next()
            currentBytes -= entry.value.byteSize
            iterator.remove()
        }
    }
}

private fun <T> List<T>.toMetricRouteCounts(routeSelector: (T) -> String): List<GlyphCacheRouteCount> =
    groupingBy(routeSelector)
        .eachCount()
        .map { (route, count) -> GlyphCacheRouteCount(route, count) }

/**
 * Describes a routing decision, alternate route, or unsupported glyph condition observed while planning.
 *
 * @property glyphId glyph identifier associated with the diagnostic when available.
 * @property route selected route, such as outline, A8, SDF, bitmap, or SVG.
 * @property message human-readable diagnostic message.
 * @property severity severity label for logs and PM evidence.
 */
data class GlyphRouteDiagnostic(
    val glyphId: Int?,
    val route: String,
    val message: String,
    val severity: String = "info",
) {
    /**
     * Shared constructors for stable glyph route diagnostics.
     */
    companion object {
        /**
         * Builds a stale atlas generation refusal diagnostic.
         *
         * @param glyphId glyph identifier whose atlas artifact was stale, or null when the refusal
         * applies to the whole atlas.
         * @param artifactGeneration generation recorded by the artifact being consumed.
         * @param currentGeneration generation currently required by the cache/invalidation source.
         * @param invalidationToken stable invalidation token that made the artifact stale.
         * @return warning diagnostic with the stable `text.glyph.atlas-generation-stale` route.
         */
        fun atlasGenerationStale(
            glyphId: Int?,
            artifactGeneration: Long,
            currentGeneration: Long,
            invalidationToken: String,
        ): GlyphRouteDiagnostic {
            require(artifactGeneration >= 0L && currentGeneration >= 0L) {
                "Atlas generation values must be non-negative."
            }
            val normalizedToken = invalidationToken.trim().ifEmpty { "unknown" }
            val glyphLabel = glyphId?.toString() ?: "atlas"
            return GlyphRouteDiagnostic(
                glyphId = glyphId,
                route = GlyphAtlasGenerationStaleDiagnosticRoute,
                message = "Glyph atlas generation is stale for glyph $glyphLabel: " +
                    "artifactGeneration=$artifactGeneration, currentGeneration=$currentGeneration, " +
                    "invalidationToken=$normalizedToken.",
                severity = "warning",
            )
        }

        /**
         * Builds an advisory telemetry-unavailable diagnostic for cache evidence generation.
         */
        fun telemetryUnavailable(
            cacheDomain: String,
            detail: String,
        ): GlyphRouteDiagnostic {
            val normalizedDomain = cacheDomain.trim().ifEmpty { "glyph-cache" }
            val normalizedDetail = detail.trim().ifEmpty { "unspecified" }
            return GlyphRouteDiagnostic(
                glyphId = null,
                route = GlyphTelemetryUnavailableDiagnosticRoute,
                message = "Glyph cache telemetry is unavailable for cacheDomain=$normalizedDomain: " +
                    "detail=$normalizedDetail.",
                severity = "warning",
            )
        }

        /**
         * Builds an SDF transform refusal diagnostic.
         *
         * @param glyphId glyph identifier whose SDF route was refused.
         * @param transformBucket stable transform classification from [GlyphStrikeKey].
         * @param fallbackRoute stable fallback route label, or `none` when no fallback exists.
         * @return warning diagnostic with the stable `text.glyph.SDF-transform-unsupported` route.
         */
        fun sdfTransformUnsupported(
            glyphId: Int,
            transformBucket: String,
            fallbackRoute: String,
        ): GlyphRouteDiagnostic {
            val normalizedTransform = transformBucket.trim().ifEmpty { "unknown" }
            val normalizedFallback = fallbackRoute.trim().ifEmpty { "none" }
            return GlyphRouteDiagnostic(
                glyphId = glyphId,
                route = GlyphSDFTransformUnsupportedDiagnosticRoute,
                message = "SDF transform is unsupported for glyph $glyphId: " +
                    "transformBucket=$normalizedTransform, fallbackRoute=$normalizedFallback.",
                severity = "warning",
            )
        }
        /**
         * Builds the stable refusal for unsupported LCD glyph requests.
         *
         * @param glyphId glyph identifier whose LCD route remains outside target support.
         * @return warning diagnostic with the stable `text.glyph.LCD-future-research` route.
         */
        fun lcdFutureResearch(glyphId: Int): GlyphRouteDiagnostic =
            GlyphRouteDiagnostic(
                glyphId = glyphId,
                route = GlyphLCDFutureResearchDiagnostic,
                message = "LCD glyph rendering remains future research for glyph $glyphId; " +
                    "refusing text.glyph.mask.LCD without fallback support claims.",
                severity = "warning",
            )

        /**
         * Builds the stable refusal for glyphs whose outline representation is unavailable.
         *
         * @param glyphId glyph identifier whose outline route is unavailable.
         * @return warning diagnostic with the stable `text.glyph.outline-unavailable` route.
         */
        fun outlineUnavailable(glyphId: Int): GlyphRouteDiagnostic =
            GlyphRouteDiagnostic(
                glyphId = glyphId,
                route = GlyphOutlineUnavailableDiagnosticRoute,
                message = "Outline glyph representation is unavailable for glyph $glyphId.",
                severity = "warning",
            )

        /**
         * Builds a stable A8 rasterization refusal diagnostic with reason and strike-key hash.
         *
         * @param glyphId glyph identifier whose A8 route failed or was refused.
         * @param strikeKeySha256 route-specific strike-key hash for deterministic evidence.
         * @param reason stable machine-readable reason suffix.
         * @param detail human-readable detail retained in the diagnostic snapshot.
         * @param severity severity label for logs and evidence.
         * @return diagnostic with the stable `text.glyph.A8-generation-failed` route.
         */
        fun a8GenerationFailed(
            glyphId: Int,
            strikeKeySha256: String,
            reason: String,
            detail: String,
            severity: String = "warning",
        ): GlyphRouteDiagnostic {
            val normalizedReason = reason.trim().ifEmpty { "unspecified" }
            val normalizedDetail = detail.trim().ifEmpty { "No additional detail." }
            return GlyphRouteDiagnostic(
                glyphId = glyphId,
                route = GlyphA8GenerationFailedDiagnosticRoute,
                message = "A8 generation failed for glyph $glyphId: " +
                    "reason=$normalizedReason, strikeKeySha256=$strikeKeySha256, detail=$normalizedDetail",
                severity = severity,
            )
        }
    }

    /**
     * SHA-256 digest of [toCanonicalJson] for compact route evidence.
     */
    val dumpSha256: String by lazy {
        glyphSha256(toCanonicalJson().toByteArray(Charsets.UTF_8))
    }

    /**
     * Serializes this diagnostic with stable field order and JSON escaping.
     *
     * @return canonical JSON object without a trailing newline.
     */
    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendGlyphJsonNullableField("glyphId", glyphId, comma = true)
        appendGlyphJsonField("route", route, comma = true)
        appendGlyphJsonField("severity", severity, comma = true)
        appendGlyphJsonField("message", message, comma = false)
        append("}")
    }
}

/**
 * Serializes route diagnostics into a deterministic JSON array.
 *
 * The input order is preserved because planner diagnostics are route evidence; callers that need a
 * different order should sort explicitly before calling this helper.
 *
 * @return canonical JSON array ending with a newline.
 */
fun List<GlyphRouteDiagnostic>.toCanonicalGlyphRouteDiagnosticsJson(): String = buildString {
    append("[")
    if (isNotEmpty()) {
        append("\n")
        append(joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("  ") })
        append("\n")
    }
    append("]\n")
}

/**
 * Serializes diagnostics as an inline field value for embedding in larger evidence objects.
 */
private fun appendGlyphRouteDiagnosticsInlineJson(diagnostics: List<GlyphRouteDiagnostic>): String = buildString {
    append("[")
    if (diagnostics.isNotEmpty()) {
        append("\n")
        append(diagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
        append("\n  ")
    }
    append("]")
}

/**
 * Computes a compact hash for [toCanonicalGlyphRouteDiagnosticsJson].
 *
 * @return lowercase hexadecimal SHA-256 digest of the canonical diagnostics dump.
 */
fun List<GlyphRouteDiagnostic>.glyphRouteDiagnosticsSha256(): String =
    glyphSha256(toCanonicalGlyphRouteDiagnosticsJson().toByteArray(Charsets.UTF_8))

private val A8SampleOffsets = doubleArrayOf(0.125, 0.375, 0.625, 0.875)

private const val MaxGlyphPathCommands = 4_096
private const val MaxGeneratedA8MaskPixels = 16_777_216L
private const val MaxGeneratedSDFMaskPixels = 16_777_216L
private const val MaxBezierSubdivisionDepth = 10
private const val A8CurveFlatteningTolerancePx = 0.125
private const val DefaultSDFDistanceRange = 8f
private const val OutlineGlyphSourceSchema = "org.graphiks.kanvas.glyph.OutlineGlyphRepresentation.source.v1"
private const val A8GlyphMaskArtifactEvidenceSchema = "org.graphiks.kanvas.glyph.A8GlyphMaskArtifactEvidence.v1"
private const val SDFGlyphArtifactEvidenceSchema = "org.graphiks.kanvas.glyph.SDFGlyphArtifactEvidence.v1"
private const val GlyphAtlasArtifactEvidenceSchema = "org.graphiks.kanvas.glyph.GlyphAtlasArtifactEvidence.v1"
private const val GlyphArtifactPlanSchema = "org.graphiks.kanvas.glyph.GlyphArtifactPlan.v1"
private const val GlyphAtlasPackingResultSchema = "org.graphiks.kanvas.glyph.GlyphAtlasPackingResult.v1"
private const val SDFNormalizationFormulaVersion = "sdf-normalization-r8unorm-v1"
private const val UnsupportedGlyphArtifactRoute = "text.glyph.unsupported"
private const val GlyphCacheKeyNondeterministicDiagnostic = "text.glyph.cache-key-nondeterministic"
private const val GlyphOutlineUnavailableDiagnosticRoute = "text.glyph.outline-unavailable"
private const val GlyphLCDFutureResearchDiagnostic = "text.glyph.LCD-future-research"
private const val GlyphAtlasCapacityExceededDiagnosticRoute = "text.glyph.atlas-capacity-exceeded"
private const val GlyphAtlasGenerationStaleDiagnosticRoute = "text.glyph.atlas-generation-stale"
private const val GlyphSDFTransformUnsupportedDiagnosticRoute = "text.glyph.SDF-transform-unsupported"
private const val GlyphA8GenerationFailedDiagnosticRoute = "text.glyph.A8-generation-failed"
private const val GlyphSDFGenerationFailedDiagnosticRoute = "text.glyph.SDF-generation-failed"
private const val GlyphArtifactBudgetExceededDiagnosticRoute = "text.glyph.artifact-budget-exceeded"
private const val GlyphTelemetryUnavailableDiagnosticRoute = "text.glyph.telemetry-unavailable"
private const val FallbackPolicySelectedFirstRequestedRoute = "selected-first-requested-route"
private const val FallbackPolicyFallbackSelectedAfterRejections = "fallback-selected-after-rejections"
private const val FallbackPolicyRefuseNoRequestedRepresentation = "refuse-no-requested-representation"
private const val RouteRejectionUnavailable = "route-unavailable"
private const val GlyphPlanSourceRequest = "request"
private const val GlyphPlanSourceCache = "cache"

/**
 * Mutable accumulator for one cache record while building a run inventory.
 *
 * @property key stable cache key for the record.
 * @property glyphId glyph identifier associated with [key].
 * @property codePoints distinct code points that map to [key].
 * @property advance advance associated with [key].
 * @property maskSummary renderer-neutral mask summary associated with [key].
 * @property diagnostic optional diagnostic associated with [key].
 */
private data class MutableGlyphCacheRecord(
    val key: String,
    val glyphId: Int,
    val codePoints: LinkedHashSet<Int>,
    val advance: Float,
    val maskSummary: GlyphMaskSummary,
    var diagnostic: String?,
) {
    /**
     * Rejects ambiguous reuse of the same cache key for different glyph facts.
     */
    fun requireCompatible(glyph: GlyphRunCacheBuildGlyph) {
        require(glyphId == glyph.glyphId) {
            "Glyph cache key '${glyph.key}' maps to glyph $glyphId and glyph ${glyph.glyphId}."
        }
        require(advance == glyph.advance) {
            "Glyph cache key '${glyph.key}' maps to multiple advances."
        }
        require(maskSummary == glyph.maskSummary) {
            "Glyph cache key '${glyph.key}' maps to multiple mask summaries."
        }
    }

    /**
     * Freezes this accumulator into an immutable public record.
     */
    fun freeze(): GlyphCacheRecord = GlyphCacheRecord(
        key = key,
        glyphId = glyphId,
        codePoints = codePoints.toList(),
        advance = advance,
        maskSummary = maskSummary,
        diagnostic = diagnostic,
    )
}

/**
 * Rectangle-like input used by shared atlas packing logic.
 *
 * @property glyphId glyph identifier assigned to the packed item.
 * @property width item width in pixels.
 * @property height item height in pixels.
 */
private data class AtlasItem(
    val glyphId: Int,
    val width: Int,
    val height: Int,
)

/**
 * Normalized cache key for one strike-specific glyph representation.
 *
 * @property typefaceId parsed or virtual typeface identity.
 * @property glyphId font-specific glyph identifier.
 * @property clusterFingerprint source cluster facts that affect Unicode-sensitive glyph output.
 * @property sizePx strike pixel size.
 * @property scaleX horizontal scale applied before glyph generation.
 * @property scaleY vertical scale applied before glyph generation.
 * @property subpixelX fractional x placement bucket.
 * @property subpixelY fractional y placement bucket.
 * @property variationCoordinates variation coordinates sorted by axis tag.
 * @property representationRoute stable glyph representation route label.
 * @property maskFormat stable mask format label.
 * @property transformBucket deterministic transform class used for cache separation.
 * @property edging antialiasing or edging policy that affects generated coverage.
 * @property sdfSpreadPx SDF spread in source pixels when applicable.
 * @property sdfSourceResolutionPx source SDF resolution selected for this strike.
 * @property paletteIdentity palette identity for color glyph routes when applicable.
 * @property unicodeDataVersion Unicode data version when cluster or emoji facts affect output.
 * @property rendererDescriptorVersion route-specific renderer descriptor version for SVG, COLR,
 * bitmap, or masks.
 */
private data class GlyphCacheKey(
    val typefaceId: TypefaceID,
    val glyphId: Int,
    val clusterFingerprint: GlyphClusterFingerprint?,
    val sizePx: Float,
    val scaleX: Float,
    val scaleY: Float,
    val subpixelX: Float,
    val subpixelY: Float,
    val variationCoordinates: List<GlyphVariationCoordinate>,
    val representationRoute: String,
    val maskFormat: String,
    val transformBucket: String,
    val edging: String,
    val sdfSpreadPx: Float?,
    val sdfSourceResolutionPx: Float?,
    val paletteIdentity: String?,
    val unicodeDataVersion: String?,
    val rendererDescriptorVersion: String?,
) {
    companion object {
        /**
         * Builds a normalized cache key from public strike inputs and glyph identity.
         *
         * @param strikeKey public strike key supplied by the caller.
         * @param glyphId glyph identifier associated with the cached representation.
         * @return normalized cache key suitable for deterministic map lookup.
         */
        fun from(strikeKey: GlyphStrikeKey, glyphId: Int): GlyphCacheKey {
            require(strikeKey.glyphId == null || strikeKey.glyphId == glyphId) {
                "Glyph strike key glyphId ${strikeKey.glyphId} does not match cached glyphId $glyphId."
            }
            return GlyphCacheKey(
                typefaceId = strikeKey.typefaceId,
                glyphId = glyphId,
                clusterFingerprint = strikeKey.clusterFingerprint,
                sizePx = strikeKey.sizePx,
                scaleX = strikeKey.scaleX,
                scaleY = strikeKey.scaleY,
                subpixelX = strikeKey.subpixelX,
                subpixelY = strikeKey.subpixelY,
                variationCoordinates = strikeKey.variationCoordinates.toSortedMap().map { (axisTag, value) ->
                    GlyphVariationCoordinate(axisTag = axisTag, value = value)
                },
                representationRoute = strikeKey.representationRoute,
                maskFormat = strikeKey.maskFormat,
                transformBucket = strikeKey.transformBucket,
                edging = strikeKey.edging,
                sdfSpreadPx = strikeKey.sdfSpreadPx,
                sdfSourceResolutionPx = strikeKey.sdfSourceResolutionPx,
                paletteIdentity = strikeKey.paletteIdentity,
                unicodeDataVersion = strikeKey.unicodeDataVersion,
                rendererDescriptorVersion = strikeKey.effectiveRendererDescriptorVersion(),
            )
        }
    }
}

/**
 * One normalized variation coordinate stored in [GlyphCacheKey].
 *
 * @property axisTag OpenType variation axis tag.
 * @property value normalized coordinate value for that axis.
 */
private data class GlyphVariationCoordinate(
    val axisTag: String,
    val value: Float,
)

/**
 * Cache value plus approximate memory cost used for deterministic budget enforcement.
 *
 * @property representation cached glyph representation.
 * @property byteSize approximate bytes charged to the cache budget.
 */
private data class GlyphCacheEntry(
    val representation: GlyphRepresentation,
    val byteSize: Long,
)

/**
 * Internal route selection result before public decision serialization.
 *
 * @property representation selected representation, or null when every requested route failed.
 * @property source selected representation source when present.
 * @property rejectedAlternatives requested routes rejected before the selected/refused route.
 */
private data class GlyphArtifactRouteSelection(
    val representation: GlyphRepresentation?,
    val source: String?,
    val rejectedAlternatives: List<GlyphArtifactRouteRejection>,
    val terminalDiagnostic: GlyphRouteDiagnostic? = null,
)

/**
 * Point in transformed pixel-space outline coordinates.
 */
private data class GlyphPathPoint(
    val x: Double,
    val y: Double,
)

/**
 * Parses and rasterizes a small deterministic line-only outline command subset to A8.
 */
private fun rasterizeOutlineToA8(
    outline: OutlineGlyphRepresentation,
    strikeKey: GlyphStrikeKey,
): A8GlyphMask {
    val sourceOutlineSha256 = outline.sourceOutlineSha256()
    val contours = outline.parseA8Contours(strikeKey)
    if (contours.isEmpty()) {
        return outline.emptyA8Mask(
            sourceOutlineSha256 = sourceOutlineSha256,
            diagnostics = listOf(
                GlyphRouteDiagnostic.a8GenerationFailed(
                    glyphId = outline.glyphId,
                    strikeKeySha256 = strikeKey.artifactPlanKeySha256(outline.glyphId, "text.glyph.mask.A8"),
                    reason = "empty-outline",
                    detail = "No closed contours were available for rasterization.",
                    severity = "info",
                ),
            ),
        )
    }

    require(outline.windingRule == "nonZero") {
        "Unsupported glyph winding rule '${outline.windingRule}' for glyph ${outline.glyphId}; only nonZero is supported."
    }

    val points = contours.flatten()
    val left = floor(points.minOf { point -> point.x }).toInt()
    val top = floor(points.minOf { point -> point.y }).toInt()
    val right = ceil(points.maxOf { point -> point.x }).toInt()
    val bottom = ceil(points.maxOf { point -> point.y }).toInt()
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) {
        return A8GlyphMask(
            glyphId = outline.glyphId,
            width = 0,
            height = 0,
            left = left,
            top = top,
            pixels = emptyList(),
        )
    }

    val pixelCount = width.toLong() * height.toLong()
    require(pixelCount <= MaxGeneratedA8MaskPixels) {
        "Generated A8 mask pixel count $pixelCount exceeds limit $MaxGeneratedA8MaskPixels for glyph ${outline.glyphId}."
    }

    val pixels = MutableList(pixelCount.toInt()) { 0 }
    for (row in 0 until height) {
        for (column in 0 until width) {
            var insideCount = 0
            for (offsetY in A8SampleOffsets) {
                val sampleY = top + row + offsetY
                for (offsetX in A8SampleOffsets) {
                    val sampleX = left + column + offsetX
                    if (contours.nonZeroContains(sampleX, sampleY)) {
                        insideCount++
                    }
                }
            }
            if (insideCount > 0) {
                pixels[row * width + column] = ((insideCount * 255) + 8) / 16
            }
        }
    }

    return A8GlyphMask(
        glyphId = outline.glyphId,
        width = width,
        height = height,
        left = left,
        top = top,
        rowBytes = width,
        pixels = pixels.toList(),
        sourceOutlineSha256 = sourceOutlineSha256,
    )
}

/**
 * Builds a stable empty/refusal A8 mask from outline-local diagnostics.
 */
private fun OutlineGlyphRepresentation.emptyA8Mask(
    sourceOutlineSha256: String? = sourceOutlineSha256(),
    diagnostics: List<GlyphRouteDiagnostic> = emptyList(),
): A8GlyphMask =
    A8GlyphMask(
        glyphId = glyphId,
        width = 0,
        height = 0,
        pixels = emptyList(),
        diagnostics = diagnostics,
        sourceOutlineSha256 = sourceOutlineSha256,
    )

/**
 * Converts a rasterization exception into stable A8 refusal evidence.
 */
private fun OutlineGlyphRepresentation.failedA8Mask(
    strikeKey: GlyphStrikeKey,
    error: Throwable,
): A8GlyphMask =
    emptyA8Mask(
        diagnostics = listOf(
            GlyphRouteDiagnostic.a8GenerationFailed(
                glyphId = glyphId,
                strikeKeySha256 = strikeKey.artifactPlanKeySha256(glyphId, "text.glyph.mask.A8"),
                reason = error.a8FailureReason(),
                detail = error.message ?: error::class.simpleName ?: "Unknown A8 generation failure.",
            ),
        ),
    )

/**
 * Computes a stable outline source hash for A8 evidence.
 */
private fun OutlineGlyphRepresentation.sourceOutlineSha256(): String =
    glyphSha256(canonicalOutlineSourceJson().toByteArray(Charsets.UTF_8))

/**
 * Serializes the source outline facts that can affect deterministic A8 output.
 */
private fun OutlineGlyphRepresentation.canonicalOutlineSourceJson(): String = buildString {
    append("{\n")
    appendGlyphJsonField("schema", OutlineGlyphSourceSchema, comma = true)
    appendGlyphJsonField("glyphId", glyphId, comma = true)
    appendGlyphJsonField("windingRule", windingRule, comma = true)
    append("  \"pathCommands\": ")
    appendGlyphStringArrayMultilineJson(pathCommands, indent = "  ")
    append("\n}\n")
}

/**
 * Maps runtime rasterization failures onto stable A8 refusal reasons.
 */
private fun Throwable.a8FailureReason(): String {
    val detail = message.orEmpty()
    return when {
        "winding rule" in detail -> "unsupported-winding-rule"
        "pixel count" in detail && "exceeds limit" in detail -> "coverage-overflow"
        else -> "malformed-outline"
    }
}

/**
 * Parses supported line/quadratic/cubic path commands into closed contours in strike pixel space.
 */
private fun OutlineGlyphRepresentation.parseA8Contours(strikeKey: GlyphStrikeKey): List<List<GlyphPathPoint>> {
    require(pathCommands.size <= MaxGlyphPathCommands) {
        "Glyph $glyphId path command count ${pathCommands.size} exceeds limit $MaxGlyphPathCommands."
    }
    require(strikeKey.scaleX.isFinite() && strikeKey.scaleY.isFinite()) {
        "Glyph $glyphId strike scale must be finite."
    }
    require(strikeKey.subpixelX.isFinite() && strikeKey.subpixelY.isFinite()) {
        "Glyph $glyphId strike subpixel buckets must be finite."
    }

    val contours = mutableListOf<List<GlyphPathPoint>>()
    var current = mutableListOf<GlyphPathPoint>()
    var start: GlyphPathPoint? = null

    pathCommands.forEach { commandText ->
        val tokens = commandText.pathCommandTokens()
        if (tokens.isEmpty()) {
            return@forEach
        }

        when (val command = tokens.first()) {
            "M" -> {
                require(tokens.size == 3) { "Glyph $glyphId M command must have exactly two coordinates: '$commandText'." }
                current.finishContourInto(contours)
                val point = parseGlyphPathPoint(tokens, 1, 2, commandText, strikeKey)
                current = mutableListOf(point)
                start = point
            }
            "L" -> {
                require(tokens.size == 3) { "Glyph $glyphId L command must have exactly two coordinates: '$commandText'." }
                require(current.isNotEmpty()) { "Glyph $glyphId L command appears before M: '$commandText'." }
                current += parseGlyphPathPoint(tokens, 1, 2, commandText, strikeKey)
            }
            "Q" -> {
                require(tokens.size == 5) { "Glyph $glyphId Q command must have one control point and one endpoint: '$commandText'." }
                require(current.isNotEmpty()) { "Glyph $glyphId Q command appears before M: '$commandText'." }
                val startPoint = current.last()
                val control = parseGlyphPathPoint(tokens, 1, 2, commandText, strikeKey)
                val end = parseGlyphPathPoint(tokens, 3, 4, commandText, strikeKey)
                current += flattenQuadraticSegment(startPoint, control, end)
            }
            "C" -> {
                require(tokens.size == 7) { "Glyph $glyphId C command must have two control points and one endpoint: '$commandText'." }
                require(current.isNotEmpty()) { "Glyph $glyphId C command appears before M: '$commandText'." }
                val startPoint = current.last()
                val control1 = parseGlyphPathPoint(tokens, 1, 2, commandText, strikeKey)
                val control2 = parseGlyphPathPoint(tokens, 3, 4, commandText, strikeKey)
                val end = parseGlyphPathPoint(tokens, 5, 6, commandText, strikeKey)
                current += flattenCubicSegment(startPoint, control1, control2, end)
            }
            "Z" -> {
                require(tokens.size == 1) { "Glyph $glyphId Z command must not have coordinates: '$commandText'." }
                val contourStart = start
                if (contourStart != null && current.lastOrNull() != contourStart) {
                    current += contourStart
                }
                current.finishContourInto(contours)
                current = mutableListOf()
                start = null
            }
            else -> throw IllegalArgumentException("Unsupported glyph $glyphId path command '$command' in '$commandText'.")
        }
    }

    current.finishContourInto(contours)
    return contours.toList()
}

/**
 * Generates a deterministic SDF for a small line-only outline command subset.
 */
private fun generateLinearOutlineSDF(
    outline: OutlineGlyphRepresentation,
    strikeKey: GlyphStrikeKey,
): SDFGlyphMask {
    val distanceRange = strikeKey.sdfSpreadPx ?: DefaultSDFDistanceRange
    val sourceOutlineSha256 = outline.sourceOutlineSha256()
    val contours = outline.parseLinearContours(strikeKey)
    if (contours.isEmpty()) {
        return SDFGlyphMask(
            glyphId = outline.glyphId,
            width = 0,
            height = 0,
            left = 0,
            top = 0,
            distanceRange = distanceRange,
            pixels = emptyList(),
            sourceOutlineSha256 = sourceOutlineSha256,
        )
    }

    require(outline.windingRule == "nonZero") {
        "Unsupported glyph winding rule '${outline.windingRule}' for glyph ${outline.glyphId}; only nonZero is supported."
    }

    val points = contours.flatten()
    val left = floor(points.minOf { point -> point.x }).toInt() - 1
    val top = floor(points.minOf { point -> point.y }).toInt() - 1
    val right = ceil(points.maxOf { point -> point.x }).toInt() + 1
    val bottom = ceil(points.maxOf { point -> point.y }).toInt() + 1
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) {
        return SDFGlyphMask(
            glyphId = outline.glyphId,
            width = 0,
            height = 0,
            left = left,
            top = top,
            distanceRange = distanceRange,
            pixels = emptyList(),
            sourceOutlineSha256 = sourceOutlineSha256,
        )
    }

    val pixelCount = width.toLong() * height.toLong()
    require(pixelCount <= MaxGeneratedSDFMaskPixels) {
        "Generated SDF mask pixel count $pixelCount exceeds limit $MaxGeneratedSDFMaskPixels for glyph ${outline.glyphId}."
    }

    val pixels = MutableList(pixelCount.toInt()) { 0 }
    for (row in 0 until height) {
        val sampleY = top + row.toDouble()
        for (column in 0 until width) {
            val sampleX = left + column.toDouble()
            val distance = contours.distanceToNearestSegment(sampleX, sampleY)
            val signedDistance = if (contours.nonZeroContains(sampleX, sampleY)) distance else -distance
            pixels[row * width + column] = signedDistance.encodeSDFSample(distanceRange)
        }
    }

    return SDFGlyphMask(
        glyphId = outline.glyphId,
        width = width,
        height = height,
        left = left,
        top = top,
        distanceRange = distanceRange,
        pixels = pixels.toList(),
        sourceOutlineSha256 = sourceOutlineSha256,
    )
}

/**
 * Parses supported path commands into closed linear contours in strike pixel space.
 */
private fun OutlineGlyphRepresentation.parseLinearContours(strikeKey: GlyphStrikeKey): List<List<GlyphPathPoint>> {
    require(pathCommands.size <= MaxGlyphPathCommands) {
        "Glyph $glyphId path command count ${pathCommands.size} exceeds limit $MaxGlyphPathCommands."
    }
    require(strikeKey.scaleX.isFinite() && strikeKey.scaleY.isFinite()) {
        "Glyph $glyphId strike scale must be finite."
    }
    require(strikeKey.subpixelX.isFinite() && strikeKey.subpixelY.isFinite()) {
        "Glyph $glyphId strike subpixel buckets must be finite."
    }

    val contours = mutableListOf<List<GlyphPathPoint>>()
    var current = mutableListOf<GlyphPathPoint>()
    var start: GlyphPathPoint? = null

    pathCommands.forEach { commandText ->
        val tokens = commandText.pathCommandTokens()
        if (tokens.isEmpty()) {
            return@forEach
        }

        when (val command = tokens.first()) {
            "M" -> {
                require(tokens.size == 3) { "Glyph $glyphId M command must have exactly two coordinates: '$commandText'." }
                require(current.isEmpty()) {
                    "Glyph $glyphId SDF generation requires closed contours before starting a new contour."
                }
                val point = parseGlyphPathPoint(tokens, 1, 2, commandText, strikeKey)
                current = mutableListOf(point)
                start = point
            }
            "L" -> {
                require(tokens.size == 3) { "Glyph $glyphId L command must have exactly two coordinates: '$commandText'." }
                require(current.isNotEmpty()) { "Glyph $glyphId L command appears before M: '$commandText'." }
                current += parseGlyphPathPoint(tokens, 1, 2, commandText, strikeKey)
            }
            "Z" -> {
                require(tokens.size == 1) { "Glyph $glyphId Z command must not have coordinates: '$commandText'." }
                val contourStart = start
                if (contourStart != null && current.lastOrNull() != contourStart) {
                    current += contourStart
                }
                current.finishContourInto(contours)
                current = mutableListOf()
                start = null
            }
            "Q", "C" -> throw UnsupportedOperationException(
                "Glyph $glyphId command '$command' is not supported by the minimal line-only glyph generator.",
            )
            else -> throw IllegalArgumentException("Unsupported glyph $glyphId path command '$command' in '$commandText'.")
        }
    }

    require(current.isEmpty()) {
        "Glyph $glyphId SDF generation requires closed contours terminated by Z."
    }
    return contours.toList()
}

/**
 * Splits a compact glyph path command on whitespace and commas.
 */
private fun String.pathCommandTokens(): List<String> =
    trim()
        .split(Regex("[\\s,]+"))
        .filter { token -> token.isNotEmpty() }

/**
 * Parses one command endpoint and applies strike placement transforms.
 */
private fun OutlineGlyphRepresentation.parseGlyphPathPoint(
    tokens: List<String>,
    xIndex: Int,
    yIndex: Int,
    commandText: String,
    strikeKey: GlyphStrikeKey,
): GlyphPathPoint {
    val x = tokens.getOrNull(xIndex)?.toDoubleOrNull()
    val y = tokens.getOrNull(yIndex)?.toDoubleOrNull()
    require(x != null && y != null && x.isFinite() && y.isFinite()) {
        "Glyph $glyphId path command has non-finite coordinates: '$commandText'."
    }

    return GlyphPathPoint(
        x = x * strikeKey.scaleX + strikeKey.subpixelX,
        y = y * strikeKey.scaleY + strikeKey.subpixelY,
    )
}

private fun flattenQuadraticSegment(
    start: GlyphPathPoint,
    control: GlyphPathPoint,
    end: GlyphPathPoint,
): List<GlyphPathPoint> {
    val points = mutableListOf<GlyphPathPoint>()
    flattenQuadraticInto(
        start = start,
        control = control,
        end = end,
        toleranceSquared = A8CurveFlatteningTolerancePx * A8CurveFlatteningTolerancePx,
        depth = 0,
        points = points,
    )
    return points
}

private fun flattenQuadraticInto(
    start: GlyphPathPoint,
    control: GlyphPathPoint,
    end: GlyphPathPoint,
    toleranceSquared: Double,
    depth: Int,
    points: MutableList<GlyphPathPoint>,
) {
    if (depth >= MaxBezierSubdivisionDepth ||
        squaredDistanceToSegment(control.x, control.y, start, end) <= toleranceSquared
    ) {
        points += end
        return
    }

    val startControl = start.midpoint(control)
    val controlEnd = control.midpoint(end)
    val mid = startControl.midpoint(controlEnd)
    flattenQuadraticInto(start, startControl, mid, toleranceSquared, depth + 1, points)
    flattenQuadraticInto(mid, controlEnd, end, toleranceSquared, depth + 1, points)
}

private fun flattenCubicSegment(
    start: GlyphPathPoint,
    control1: GlyphPathPoint,
    control2: GlyphPathPoint,
    end: GlyphPathPoint,
): List<GlyphPathPoint> {
    val points = mutableListOf<GlyphPathPoint>()
    flattenCubicInto(
        start = start,
        control1 = control1,
        control2 = control2,
        end = end,
        toleranceSquared = A8CurveFlatteningTolerancePx * A8CurveFlatteningTolerancePx,
        depth = 0,
        points = points,
    )
    return points
}

private fun flattenCubicInto(
    start: GlyphPathPoint,
    control1: GlyphPathPoint,
    control2: GlyphPathPoint,
    end: GlyphPathPoint,
    toleranceSquared: Double,
    depth: Int,
    points: MutableList<GlyphPathPoint>,
) {
    val control1DistanceSquared = squaredDistanceToSegment(control1.x, control1.y, start, end)
    val control2DistanceSquared = squaredDistanceToSegment(control2.x, control2.y, start, end)
    if (depth >= MaxBezierSubdivisionDepth ||
        maxOf(control1DistanceSquared, control2DistanceSquared) <= toleranceSquared
    ) {
        points += end
        return
    }

    val startControl1 = start.midpoint(control1)
    val control1Control2 = control1.midpoint(control2)
    val control2End = control2.midpoint(end)
    val leftMid = startControl1.midpoint(control1Control2)
    val rightMid = control1Control2.midpoint(control2End)
    val mid = leftMid.midpoint(rightMid)
    flattenCubicInto(start, startControl1, leftMid, mid, toleranceSquared, depth + 1, points)
    flattenCubicInto(mid, rightMid, control2End, end, toleranceSquared, depth + 1, points)
}

private fun GlyphPathPoint.midpoint(other: GlyphPathPoint): GlyphPathPoint =
    GlyphPathPoint(
        x = (x + other.x) / 2.0,
        y = (y + other.y) / 2.0,
    )

/**
 * Adds a contour when it has enough distinct points to enclose area.
 */
private fun MutableList<GlyphPathPoint>.finishContourInto(contours: MutableList<List<GlyphPathPoint>>) {
    if (distinct().size >= 3) {
        contours += toList()
    }
}

/**
 * Evaluates non-zero winding containment for line-only contours.
 */
private fun List<List<GlyphPathPoint>>.nonZeroContains(x: Double, y: Double): Boolean {
    var winding = 0
    forEach { contour ->
        for (index in 0 until contour.lastIndex) {
            val start = contour[index]
            val end = contour[index + 1]
            if ((start.y <= y && end.y > y) || (start.y > y && end.y <= y)) {
                val intersectionX = start.x + (y - start.y) * (end.x - start.x) / (end.y - start.y)
                if (intersectionX > x) {
                    winding += if (end.y > start.y) 1 else -1
                }
            }
        }
    }
    return winding != 0
}

/**
 * Computes the shortest Euclidean distance from a sample to any contour segment.
 */
private fun List<List<GlyphPathPoint>>.distanceToNearestSegment(x: Double, y: Double): Double {
    var minDistanceSquared = Double.POSITIVE_INFINITY
    forEach { contour ->
        for (index in 0 until contour.lastIndex) {
            minDistanceSquared = minOf(
                minDistanceSquared,
                squaredDistanceToSegment(x, y, contour[index], contour[index + 1]),
            )
        }
    }
    return sqrt(minDistanceSquared)
}

/**
 * Computes squared distance from a sample point to one finite line segment.
 */
private fun squaredDistanceToSegment(
    x: Double,
    y: Double,
    start: GlyphPathPoint,
    end: GlyphPathPoint,
): Double {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0.0) {
        val pointDx = x - start.x
        val pointDy = y - start.y
        return pointDx * pointDx + pointDy * pointDy
    }

    val t = (((x - start.x) * dx + (y - start.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
    val projectionX = start.x + t * dx
    val projectionY = start.y + t * dy
    val projectionDx = x - projectionX
    val projectionDy = y - projectionY
    return projectionDx * projectionDx + projectionDy * projectionDy
}

/**
 * Encodes signed distance into an 8-bit SDF sample with 128 at the edge.
 */
private fun Double.encodeSDFSample(distanceRange: Float): Int {
    val normalized = (this / distanceRange.toDouble()).coerceIn(-1.0, 1.0)
    return (128.0 + normalized * 127.0).roundToInt().coerceIn(0, 255)
}

/**
 * Packs rectangle items into rows with deterministic padding.
 *
 * @param items rectangles to place in input order.
 * @param atlasWidth maximum row width in pixels.
 * @param padding empty pixels reserved around and between placements.
 * @return atlas placements in the same order as the input items.
 */
private fun packAtlasItems(
    items: List<AtlasItem>,
    atlasWidth: Int,
    padding: Int,
): List<GlyphAtlasPlacement> {
    items.forEach { item ->
        require(item.width >= 0) { "Glyph ${item.glyphId} width must be non-negative." }
        require(item.height >= 0) { "Glyph ${item.glyphId} height must be non-negative." }
        require(item.width.toLong() + padding.toLong() * 2L <= atlasWidth.toLong()) {
            "Glyph ${item.glyphId} width plus padding exceeds atlas width."
        }
    }
    if (items.isEmpty()) return emptyList()
    var cursorX = padding
    var cursorY = padding
    var rowHeight = 0
    val placements = ArrayList<GlyphAtlasPlacement>(items.size)
    for (item in items) {
        if (
            cursorX.toLong() + item.width.toLong() + padding.toLong() > atlasWidth.toLong() &&
                cursorX > padding
        ) {
            cursorX = padding
            val nextCursorY = cursorY.toLong() + rowHeight.toLong() + padding.toLong()
            require(nextCursorY <= Int.MAX_VALUE.toLong()) {
                "Glyph ${item.glyphId} vertical cursor exceeds Int.MAX_VALUE."
            }
            cursorY = nextCursorY.toInt()
            rowHeight = 0
        }
        require(cursorY.toLong() + item.height.toLong() <= Int.MAX_VALUE.toLong()) {
            "Glyph ${item.glyphId} vertical placement exceeds Int.MAX_VALUE."
        }
        placements += GlyphAtlasPlacement(
            glyphId = item.glyphId,
            x = cursorX,
            y = cursorY,
            width = item.width,
            height = item.height,
        )
        cursorX += item.width + padding
        rowHeight = maxOf(rowHeight, item.height)
    }
    return placements
}

/**
 * Computes the minimal atlas width that contains all placements.
 *
 * @return maximum placement right edge, or zero for an empty placement list.
 */
private fun List<GlyphAtlasPlacement>.atlasWidth(): Int =
    maxOfOrNull { placement ->
        placement.requireValidPlacement()
        placement.checkedRightEdge()
    } ?: 0

/**
 * Computes the minimal atlas height that contains all placements.
 *
 * @return maximum placement bottom edge, or zero for an empty placement list.
 */
private fun List<GlyphAtlasPlacement>.atlasHeight(): Int =
    maxOfOrNull { placement ->
        placement.requireValidPlacement()
        placement.checkedBottomEdge()
    } ?: 0

/**
 * Computes the atlas pixel count with overflow and allocation bounds checked.
 */
private fun checkedAtlasPixelCount(width: Int, height: Int, label: String): Int {
    val pixelCount = width.toLong() * height.toLong()
    require(pixelCount <= Int.MAX_VALUE.toLong()) {
        "$label atlas pixel count $pixelCount exceeds Int.MAX_VALUE."
    }
    return pixelCount.toInt()
}

/**
 * Validates that a placement describes a non-negative atlas rectangle.
 */
private fun GlyphAtlasPlacement.requireValidPlacement() {
    require(x >= 0) { "Placement x must be non-negative for glyph $glyphId." }
    require(y >= 0) { "Placement y must be non-negative for glyph $glyphId." }
    require(width >= 0) { "Placement width must be non-negative for glyph $glyphId." }
    require(height >= 0) { "Placement height must be non-negative for glyph $glyphId." }
}

private fun GlyphAtlasPlacement.checkedRightEdge(): Int {
    val right = x.toLong() + width.toLong()
    require(right <= Int.MAX_VALUE.toLong()) {
        "Placement right edge $right exceeds Int.MAX_VALUE for glyph $glyphId."
    }
    return right.toInt()
}

private fun GlyphAtlasPlacement.checkedBottomEdge(): Int {
    val bottom = y.toLong() + height.toLong()
    require(bottom <= Int.MAX_VALUE.toLong()) {
        "Placement bottom edge $bottom exceeds Int.MAX_VALUE for glyph $glyphId."
    }
    return bottom.toInt()
}

/**
 * Builds a glyph-id map for A8 masks while rejecting ambiguous duplicates.
 *
 * @return masks keyed by glyph identifier.
 */
private fun List<A8GlyphMask>.associateUniqueA8Masks(): Map<Int, A8GlyphMask> {
    val masksByGlyphId = mutableMapOf<Int, A8GlyphMask>()
    forEach { mask ->
        require(masksByGlyphId.put(mask.glyphId, mask) == null) {
            "Duplicate A8 mask for glyph ${mask.glyphId}."
        }
    }
    return masksByGlyphId
}

/**
 * Builds a glyph-id map for SDF masks while rejecting ambiguous duplicates.
 *
 * @return masks keyed by glyph identifier.
 */
private fun List<SDFGlyphMask>.associateUniqueSDFMasks(): Map<Int, SDFGlyphMask> {
    val masksByGlyphId = mutableMapOf<Int, SDFGlyphMask>()
    forEach { mask ->
        require(masksByGlyphId.put(mask.glyphId, mask) == null) {
            "Duplicate SDF mask for glyph ${mask.glyphId}."
        }
    }
    return masksByGlyphId
}

/**
 * Validates dimensions, backing pixel count, and addressable sample values for an A8 mask.
 */
private fun A8GlyphMask.requireValidA8Pixels() {
    require(width >= 0) { "A8 mask width must be non-negative for glyph $glyphId." }
    require(height >= 0) { "A8 mask height must be non-negative for glyph $glyphId." }
    require(rowBytes >= width) { "A8 mask rowBytes must be at least width for glyph $glyphId." }
    require(sourceOutlineSha256 == null || sourceOutlineSha256.matches(Regex("[0-9a-f]{64}"))) {
        "A8 mask sourceOutlineSha256 must be lowercase hexadecimal when present for glyph $glyphId."
    }
    val expectedPixelCount = rowBytes.toLong() * height.toLong()
    require(pixels.size.toLong() >= expectedPixelCount) {
        "A8 mask pixel count is smaller than rowBytes * height for glyph $glyphId."
    }

    for (row in 0 until height) {
        for (column in 0 until width) {
            val value = pixels[row * rowBytes + column]
            require(value in 0..255) {
                "A8 mask pixel value $value is outside 0..255 for glyph $glyphId."
            }
        }
    }
}

/**
 * Validates dimensions, backing pixel count, and sample values for an SDF mask.
 */
private fun SDFGlyphMask.requireValidSDFPixels() {
    require(width >= 0) { "SDF mask width must be non-negative for glyph $glyphId." }
    require(height >= 0) { "SDF mask height must be non-negative for glyph $glyphId." }
    require(sourceOutlineSha256 == null || sourceOutlineSha256.matches(Regex("[0-9a-f]{64}"))) {
        "SDF mask sourceOutlineSha256 must be lowercase hexadecimal when present for glyph $glyphId."
    }
    val expectedPixelCount = width.toLong() * height.toLong()
    require(pixels.size.toLong() >= expectedPixelCount) {
        "SDF mask pixel count is smaller than width * height for glyph $glyphId."
    }

    for (index in 0 until expectedPixelCount.toInt()) {
        val value = pixels[index]
        require(value in 0..255) {
            "SDF mask pixel value $value is outside 0..255 for glyph $glyphId."
        }
    }
}

/**
 * Copies one A8 mask into its atlas position.
 *
 * @param mask source mask.
 * @param placement destination atlas rectangle.
 * @param atlasWidth atlas row width in pixels.
 * @param atlasPixels mutable atlas pixels to receive source samples.
 */
private fun copyA8Mask(
    mask: A8GlyphMask,
    placement: GlyphAtlasPlacement,
    atlasWidth: Int,
    atlasPixels: MutableList<Int>,
) {
    for (row in 0 until mask.height) {
        for (column in 0 until mask.width) {
            val sourceIndex = row * mask.rowBytes + column
            val targetIndex = (placement.y + row) * atlasWidth + placement.x + column
            atlasPixels[targetIndex] = mask.pixels[sourceIndex]
        }
    }
}

/**
 * Copies one SDF mask into its atlas position.
 *
 * @param mask source mask.
 * @param placement destination atlas rectangle.
 * @param atlasWidth atlas row width in pixels.
 * @param atlasPixels mutable atlas pixels to receive source samples.
 */
private fun copySDFMask(
    mask: SDFGlyphMask,
    placement: GlyphAtlasPlacement,
    atlasWidth: Int,
    atlasPixels: MutableList<Int>,
) {
    for (row in 0 until mask.height) {
        for (column in 0 until mask.width) {
            val sourceIndex = row * mask.width + column
            val targetIndex = (placement.y + row) * atlasWidth + placement.x + column
            atlasPixels[targetIndex] = mask.pixels[sourceIndex]
        }
    }
}

/**
 * Selects the first available representation that satisfies the request's route order.
 *
 * @param glyphId glyph identifier being planned.
 * @return selected representation plus rejected route alternatives.
 */
private fun GlyphArtifactRouteRequest.selectRoute(
    glyphId: Int,
    cached: GlyphRepresentation?,
): GlyphArtifactRouteSelection {
    val candidates = availableRepresentations[glyphId].orEmpty()
    val explicitDiagnostics = routeDiagnostics[glyphId].orEmpty().toMutableList()
    val rejectedAlternatives = mutableListOf<GlyphArtifactRouteRejection>()
    var terminalDiagnostic: GlyphRouteDiagnostic? = null

    preferredRoutes.forEach { route ->
        candidates.firstOrNull { representation -> route.matches(representation) }?.let { representation ->
            return GlyphArtifactRouteSelection(
                representation = representation,
                source = GlyphPlanSourceRequest,
                rejectedAlternatives = rejectedAlternatives.toList(),
            )
        }
        if (cached != null && route.matches(cached)) {
            return GlyphArtifactRouteSelection(
                representation = cached,
                source = GlyphPlanSourceCache,
                rejectedAlternatives = rejectedAlternatives.toList(),
            )
        }
        val explicitDiagnostic = explicitDiagnostics.firstOrNull { diagnostic ->
            diagnostic.matchesRejectedRoute(route)
        }
        val rejectionDiagnostic = explicitDiagnostic ?: route.defaultRejectionDiagnostic(glyphId)
        if (explicitDiagnostic != null) {
            explicitDiagnostics.remove(explicitDiagnostic)
        }
        if (rejectionDiagnostic != null) {
            terminalDiagnostic = rejectionDiagnostic
        }
        rejectedAlternatives += GlyphArtifactRouteRejection(
            route = route.artifactPlanRouteName(),
            reason = rejectionDiagnostic?.route ?: RouteRejectionUnavailable,
        )
    }

    return GlyphArtifactRouteSelection(
        representation = null,
        source = null,
        rejectedAlternatives = rejectedAlternatives.toList(),
        terminalDiagnostic = terminalDiagnostic,
    )
}

/**
 * Builds a stable diagnostic for a glyph whose requested routes cannot be satisfied.
 *
 * @param glyphId glyph identifier that could not be planned.
 * @param availableRepresentations supplied candidate representations for that glyph.
 * @return warning diagnostic describing requested and available routes.
 */
private fun GlyphArtifactRouteRequest.unsupportedDiagnostic(
    glyphId: Int,
    availableRepresentations: List<GlyphRepresentation>,
): GlyphRouteDiagnostic {
    val requestedRoutes = preferredRoutes.diagnosticLabel()
    val availableRoutes = availableRepresentations
        .map { representation -> representation.diagnosticRouteName() }
        .distinct()
        .joinToString(separator = "|")
        .ifEmpty { "none" }

    return GlyphRouteDiagnostic(
        glyphId = glyphId,
        route = requestedRoutes,
        message = "No requested glyph representation is available for glyph $glyphId; " +
            "requested $requestedRoutes, available $availableRoutes.",
        severity = "warning",
    )
}

/**
 * Formats route preferences for deterministic diagnostics.
 *
 * @return pipe-delimited route label, or "none" when no route was requested.
 */
private fun List<GlyphArtifactRoute>.diagnosticLabel(): String =
    joinToString(separator = "|") { route -> route.diagnosticName }.ifEmpty { "none" }

/**
 * Determines whether this route can carry the supplied representation.
 *
 * @param representation candidate pure Kotlin glyph representation.
 * @return true when the representation has this route's artifact shape.
 */
private fun GlyphArtifactRoute.matches(representation: GlyphRepresentation): Boolean =
    when (this) {
        GlyphArtifactRoute.OUTLINE -> representation is OutlineGlyphRepresentation
        GlyphArtifactRoute.A8 -> representation is A8GlyphMask
        GlyphArtifactRoute.SDF -> representation is SDFGlyphMask
        GlyphArtifactRoute.LCD -> false
        GlyphArtifactRoute.COLOR -> representation is ColorGlyphPlanRef
        GlyphArtifactRoute.BITMAP -> representation is BitmapGlyphPlanRef
        GlyphArtifactRoute.SVG -> representation is SVGGlyphPlanRef
    }

/**
 * Returns the canonical route label used by glyph artifact plan dumps.
 */
private fun GlyphArtifactRoute.artifactPlanRouteName(): String =
    when (this) {
        GlyphArtifactRoute.OUTLINE -> "text.glyph.outline"
        GlyphArtifactRoute.A8 -> "text.glyph.mask.A8"
        GlyphArtifactRoute.SDF -> "text.glyph.mask.SDF"
        GlyphArtifactRoute.LCD -> "text.glyph.mask.LCD"
        GlyphArtifactRoute.COLOR -> "text.glyph.color.COLR"
        GlyphArtifactRoute.BITMAP -> "text.glyph.bitmap.PNG"
        GlyphArtifactRoute.SVG -> "text.glyph.SVG"
    }

/**
 * Returns the diagnostic route represented by a concrete glyph representation.
 *
 * @return stable lowercase route label for known glyph representations.
 */
private fun GlyphRepresentation.diagnosticRouteName(): String =
    when (this) {
        is OutlineGlyphRepresentation -> GlyphArtifactRoute.OUTLINE.diagnosticName
        is A8GlyphMask -> GlyphArtifactRoute.A8.diagnosticName
        is SDFGlyphMask -> GlyphArtifactRoute.SDF.diagnosticName
        is ColorGlyphPlanRef -> GlyphArtifactRoute.COLOR.diagnosticName
        is BitmapGlyphPlanRef -> GlyphArtifactRoute.BITMAP.diagnosticName
        is SVGGlyphPlanRef -> GlyphArtifactRoute.SVG.diagnosticName
        else -> this::class.simpleName
            ?.removeSuffix("GlyphRepresentation")
            ?.lowercase()
            ?.ifEmpty { "unknown" } ?: "unknown"
    }

/**
 * Returns the artifact intent for routes that stop at CPU-prepared glyph data.
 */
private fun GlyphRepresentation.artifactIntent(): String? =
    when (this) {
        is A8GlyphMask, is SDFGlyphMask -> "CPUPreparedGPU"
        else -> null
    }

/**
 * Returns a stable source hash for representations whose content should appear in evidence dumps.
 */
private fun GlyphRepresentation.sourceRepresentationSha256(): String? =
    when (this) {
        is OutlineGlyphRepresentation -> sourceOutlineSha256()
        is A8GlyphMask -> GlyphMaskSummary.fromA8Mask(this).sha256
        is SDFGlyphMask -> pixels.pixelSha256(width * height)
        else -> null
    }

/**
 * Maps stable refusal diagnostics onto the planning route they reject.
 */
private fun GlyphRouteDiagnostic.matchesRejectedRoute(route: GlyphArtifactRoute): Boolean =
    when (route) {
        GlyphArtifactRoute.OUTLINE -> this.route == GlyphOutlineUnavailableDiagnosticRoute
        GlyphArtifactRoute.A8 -> this.route in setOf(
            GlyphA8GenerationFailedDiagnosticRoute,
            GlyphAtlasCapacityExceededDiagnosticRoute,
            GlyphAtlasGenerationStaleDiagnosticRoute,
            GlyphArtifactBudgetExceededDiagnosticRoute,
        )
        GlyphArtifactRoute.SDF -> this.route in setOf(
            GlyphSDFGenerationFailedDiagnosticRoute,
            GlyphSDFTransformUnsupportedDiagnosticRoute,
        )
        GlyphArtifactRoute.LCD -> this.route == GlyphLCDFutureResearchDiagnostic
        GlyphArtifactRoute.COLOR,
        GlyphArtifactRoute.BITMAP,
        GlyphArtifactRoute.SVG,
        -> false
    }

/**
 * Returns the built-in refusal for routes that are always explicit non-claims in M9.
 */
private fun GlyphArtifactRoute.defaultRejectionDiagnostic(glyphId: Int): GlyphRouteDiagnostic? =
    when (this) {
        GlyphArtifactRoute.LCD -> GlyphRouteDiagnostic.lcdFutureResearch(glyphId)
        else -> null
    }

/**
 * Returns the canonical glyph artifact route for a concrete representation.
 */
private fun GlyphRepresentation.artifactPlanRouteName(): String =
    when (this) {
        is OutlineGlyphRepresentation -> GlyphArtifactRoute.OUTLINE.artifactPlanRouteName()
        is A8GlyphMask -> GlyphArtifactRoute.A8.artifactPlanRouteName()
        is SDFGlyphMask -> GlyphArtifactRoute.SDF.artifactPlanRouteName()
        is ColorGlyphPlanRef -> GlyphArtifactRoute.COLOR.artifactPlanRouteName()
        is BitmapGlyphPlanRef -> GlyphArtifactRoute.BITMAP.artifactPlanRouteName()
        is SVGGlyphPlanRef -> GlyphArtifactRoute.SVG.artifactPlanRouteName()
        else -> UnsupportedGlyphArtifactRoute
    }

/**
 * Builds a route-specific strike-key hash for glyph artifact plan evidence.
 */
private fun GlyphStrikeKey.artifactPlanKeySha256(glyphId: Int, route: String): String =
    copy(
        representationRoute = route,
        maskFormat = when (route) {
            "text.glyph.mask.A8" -> "A8"
            "text.glyph.mask.SDF" -> "R8Unorm"
            else -> GlyphStrikeKey.NoMaskFormat
        },
    ).preimageSha256(glyphId)

/**
 * Returns a stable artifact-type label for cache inventory evidence.
 */
private fun GlyphRepresentation.inventoryArtifactType(): String =
    when (this) {
        is OutlineGlyphRepresentation -> "OutlineGlyphRepresentation"
        is A8GlyphMask -> "A8GlyphMask"
        is SDFGlyphMask -> "SDFGlyphMask"
        is GlyphArtifactPlanRef -> "GlyphArtifactPlanRef"
        is ColorGlyphPlanRef -> "ColorGlyphPlanRef"
        is BitmapGlyphPlanRef -> "BitmapGlyphPlanRef"
        is SVGGlyphPlanRef -> "SVGGlyphPlanRef"
        else -> this::class.simpleName ?: "GlyphRepresentation"
    }

/**
 * Estimates cache memory for a representation without depending on JVM object layout.
 *
 * @return approximate byte cost charged to [GlyphCacheBudget.maxBytes].
 */
private fun GlyphRepresentation.approximateCacheBytes(): Long {
    val fixedOverheadBytes = 64L
    return fixedOverheadBytes + when (this) {
        is OutlineGlyphRepresentation -> pathCommands.sumOf { command -> command.length.toLong() * 2L } +
            windingRule.length.toLong() * 2L
        is A8GlyphMask -> rowBytes.nonNegativeProduct(height, glyphId = glyphId, label = "A8 mask")
        is SDFGlyphMask -> width.nonNegativeProduct(height, glyphId = glyphId, label = "SDF mask")
        is GlyphArtifactPlanRef -> artifactName.length.toLong() * 2L +
            planId.length.toLong() * 2L +
            boundsPlaceholder.length.toLong() * 2L
        else -> 0L
    }
}

/**
 * Hashes the first [addressablePixelCount] samples of a mask-like representation.
 */
private fun List<Int>.pixelSha256(addressablePixelCount: Int): String {
    require(addressablePixelCount >= 0) { "Addressable pixel count must be non-negative." }
    require(size >= addressablePixelCount) {
        "Pixel list size $size is smaller than addressable pixel count $addressablePixelCount."
    }
    val bytes = ByteArray(addressablePixelCount)
    for (index in 0 until addressablePixelCount) {
        val value = this[index]
        require(value in 0..255) { "Pixel value $value is outside 0..255 at index $index." }
        bytes[index] = value.toByte()
    }
    return glyphSha256(bytes)
}

/**
 * Computes a non-negative Int product as Long for cache-size estimates.
 *
 * @param other second multiplicand.
 * @param glyphId glyph identifier used in validation errors.
 * @param label representation label used in validation errors.
 * @return non-negative product as a Long.
 */
private fun Int.nonNegativeProduct(other: Int, glyphId: Int, label: String): Long {
    require(this >= 0) { "$label dimension must be non-negative for glyph $glyphId." }
    require(other >= 0) { "$label dimension must be non-negative for glyph $glyphId." }
    return toLong() * other.toLong()
}

/**
 * Serializes one glyph artifact route decision into stable JSON.
 */
private fun GlyphArtifactPlanDecision.toCanonicalJson(): String = buildString {
    append("{\n")
    appendGlyphJsonField("index", index, comma = true)
    appendGlyphJsonField("glyphId", glyphId, comma = true)
    appendGlyphJsonField("selectedRoute", selectedRoute, comma = true)
    append("  \"representation\": ")
    append(glyphJsonNullableString(representation))
    append(",\n")
    append("  \"source\": ")
    append(glyphJsonNullableString(source))
    append(",\n")
    append("  \"planRef\": ")
    append(planRef?.toCanonicalJson()?.indentJsonContinuation("  ") ?: "null")
    append(",\n")
    append("  \"artifactIntent\": ")
    append(glyphJsonNullableString(artifactIntent))
    append(",\n")
    appendGlyphJsonField("keySha256", keySha256, comma = true)
    appendGlyphJsonNullableField("sourceRepresentationSha256", sourceRepresentationSha256, comma = true)
    appendGlyphJsonField("fallbackPolicy", fallbackPolicy, comma = true)
    append("  \"rejectedAlternatives\": [")
    if (rejectedAlternatives.isEmpty()) {
        append("],\n")
    } else {
        append("\n")
        append(rejectedAlternatives.joinToString(",\n") { rejection -> rejection.toCanonicalJson().prependIndent("    ") })
        append("\n")
        append("  ],\n")
    }
    append("  \"diagnostic\": ")
    append(diagnostic?.toCanonicalJson()?.indentJsonContinuation("  ") ?: "null")
    append("\n}")
}

/**
 * Serializes one rejected route alternative as a compact stable JSON object.
 */
private fun GlyphArtifactRouteRejection.toCanonicalJson(): String = buildString {
    append("{")
    append(glyphJsonString("route")).append(": ").append(glyphJsonString(route)).append(", ")
    append(glyphJsonString("reason")).append(": ").append(glyphJsonString(reason))
    append("}")
}

private fun GlyphArtifactPlanRef.toCanonicalJson(): String = buildString {
    append("{\n")
    appendGlyphJsonField("artifactName", artifactName, comma = true)
    appendGlyphJsonField("planId", planId, comma = true)
    appendGlyphJsonField("boundsPlaceholder", boundsPlaceholder, comma = false)
    append("\n}")
}

private fun GlyphArtifactRoutePolicyInputs.toCanonicalJson(): String = buildString {
    append("{\n")
    appendGlyphJsonField("textStylePreference", textStylePreference, comma = true)
    appendGlyphJsonField("transformClass", transformClass, comma = true)
    appendGlyphJsonField("atlasBudgetClass", atlasBudgetClass, comma = true)
    appendGlyphJsonField("sdfEligibility", sdfEligibility, comma = true)
    appendGlyphJsonField("colorGlyphAvailability", colorGlyphAvailability, comma = true)
    appendGlyphJsonField("emojiSequenceFacts", emojiSequenceFacts, comma = true)
    appendGlyphJsonField("rendererCapabilitySummary", rendererCapabilitySummary, comma = false)
    append("\n}")
}

private fun appendGlyphArtifactPlansJson(plans: List<GlyphArtifactPlan>): String = buildString {
    append("[")
    if (plans.isNotEmpty()) {
        append("\n")
        append(plans.joinToString(",\n") { plan ->
            plan.toCanonicalGlyphArtifactPlanJson().trimEnd().prependIndent("    ")
        })
        append("\n  ")
    }
    append("]")
}

private fun appendA8GlyphMaskEvidenceJson(masks: List<A8GlyphMaskArtifactEvidence>): String = buildString {
    append("[")
    if (masks.isNotEmpty()) {
        append("\n")
        append(masks.joinToString(",\n") { mask ->
            mask.toCanonicalJson().trimEnd().prependIndent("    ")
        })
        append("\n  ")
    }
    append("]")
}

private fun appendSdfGlyphArtifactEvidenceJson(artifacts: List<SDFGlyphArtifactEvidence>): String = buildString {
    append("[")
    if (artifacts.isNotEmpty()) {
        append("\n")
        append(artifacts.joinToString(",\n") { artifact ->
            artifact.toCanonicalJson().trimEnd().prependIndent("    ")
        })
        append("\n  ")
    }
    append("]")
}

private fun appendGlyphAtlasArtifactEvidenceJson(artifacts: List<GlyphAtlasArtifactEvidence>): String = buildString {
    append("[")
    if (artifacts.isNotEmpty()) {
        append("\n")
        append(artifacts.joinToString(",\n") { artifact ->
            artifact.toCanonicalJson().trimEnd().prependIndent("    ")
        })
        append("\n  ")
    }
    append("]")
}

private fun appendGlyphAtlasArtifactEvidenceEntriesJson(entries: List<GlyphAtlasArtifactEvidenceEntry>): String = buildString {
    append("[")
    if (entries.isNotEmpty()) {
        append("\n")
        append(entries.joinToString(",\n") { entry ->
            entry.toCanonicalJson().trimEnd().prependIndent("    ")
        })
        append("\n  ")
    }
    append("]")
}

private fun appendGlyphAtlasEvictionTraceEntriesJson(entries: List<GlyphAtlasEvictionTraceEntry>): String = buildString {
    append("[")
    if (entries.isNotEmpty()) {
        append("\n")
        append(entries.joinToString(",\n") { entry ->
            entry.toCanonicalJson().trimEnd().prependIndent("    ")
        })
        append("\n  ")
    }
    append("]")
}

private fun appendGlyphIntArrayInlineJson(values: List<Int>): String = buildString {
    append("[")
    append(values.joinToString(", "))
    append("]")
}

/**
 * Serializes atlas placements into a stable JSON array.
 */
private fun appendGlyphAtlasPlacementsJson(placements: List<GlyphAtlasPlacement>): String = buildString {
    append("[")
    if (placements.isNotEmpty()) {
        append("\n")
        append(placements.joinToString(",\n") { placement -> placement.toCanonicalJson().prependIndent("    ") })
        append("\n  ")
    }
    append("]")
}

/**
 * Serializes one atlas placement into stable JSON.
 */
private fun GlyphAtlasPlacement.toCanonicalJson(): String = buildString {
    append("{\n")
    appendGlyphJsonField("glyphId", glyphId, comma = true)
    appendGlyphJsonField("x", x, comma = true)
    appendGlyphJsonField("y", y, comma = true)
    appendGlyphJsonField("width", width, comma = true)
    appendGlyphJsonField("height", height, comma = false)
    append("}")
}

/**
 * Serializes an inventory item into stable JSON.
 */
private fun GlyphRunCacheInventoryItem.toCanonicalJson(): String = buildString {
    append("{\n")
    appendGlyphJsonField("index", index, comma = true)
    appendGlyphJsonField("codePoint", glyphCodePointLabel(codePoint), comma = true)
    appendGlyphJsonField("codePointValue", codePoint, comma = true)
    appendGlyphJsonField("glyphId", glyphId, comma = true)
    appendGlyphJsonField("key", key, comma = true)
    appendGlyphJsonField("advance", advance, comma = true)
    appendGlyphJsonField("x", x, comma = true)
    appendGlyphJsonNullableField("diagnostic", diagnostic, comma = false)
    append("}")
}

/**
 * Serializes a cache record into stable JSON.
 */
private fun GlyphCacheRecord.toCanonicalJson(): String = buildString {
    append("{\n")
    appendGlyphJsonField("key", key, comma = true)
    appendGlyphJsonField("glyphId", glyphId, comma = true)
    append("  \"codePoints\": [")
    append(codePoints.joinToString(", ") { codePoint -> glyphJsonString(glyphCodePointLabel(codePoint)) })
    append("],\n")
    appendGlyphJsonField("advance", advance, comma = true)
    append("  \"maskSummary\": ")
    append(maskSummary.toCanonicalJson())
    append(",\n")
    appendGlyphJsonNullableField("diagnostic", diagnostic, comma = false)
    append("}")
}

/**
 * Serializes a mask summary as a single-line stable JSON object.
 */
private fun GlyphMaskSummary.toCanonicalJson(): String = buildString {
    append("{")
    append(glyphJsonString("left")).append(": ").append(left).append(", ")
    append(glyphJsonString("top")).append(": ").append(top).append(", ")
    append(glyphJsonString("width")).append(": ").append(width).append(", ")
    append(glyphJsonString("height")).append(": ").append(height).append(", ")
    append(glyphJsonString("rowBytes")).append(": ").append(rowBytes).append(", ")
    append(glyphJsonString("nonZeroPixels")).append(": ").append(nonZeroPixels).append(", ")
    append(glyphJsonString("sha256")).append(": ").append(glyphJsonString(sha256))
    append("}")
}

/**
 * Rejects non-finite strike facts before canonical preimage serialization.
 */
private fun GlyphStrikeKey.requireFiniteStrikeFacts() {
    require(sizePx.isFinite()) { "Glyph strike sizePx must be finite." }
    require(scaleX.isFinite() && scaleY.isFinite()) { "Glyph strike scales must be finite." }
    require(subpixelX.isFinite() && subpixelY.isFinite()) {
        "Glyph strike subpixel buckets must be finite."
    }
    variationCoordinates.forEach { (axisTag, value) ->
        require(value.isFinite()) {
            "Glyph strike variation coordinate '$axisTag' must be finite."
        }
    }
    require(sdfSpreadPx == null || sdfSpreadPx.isFinite()) {
        "Glyph strike SDF spread must be finite when present."
    }
    require(sdfSourceResolutionPx == null || sdfSourceResolutionPx.isFinite()) {
        "Glyph strike SDF source resolution must be finite when present."
    }
}

/**
 * Serializes variation coordinates in axis-tag order for stable strike-key preimages.
 */
private fun StringBuilder.appendVariationCoordinatesJson(variationCoordinates: Map<String, Float>) {
    val sortedCoordinates = variationCoordinates.toSortedMap()
    if (sortedCoordinates.isEmpty()) {
        append("[]")
        return
    }

    append("[\n")
    append(
        sortedCoordinates.entries.joinToString(",\n") { (axisTag, value) ->
            "    {\"axis\": ${glyphJsonString(axisTag)}, \"value\": ${glyphFloatToken(value)}}"
        },
    )
    append("\n  ]")
}

/**
 * Serializes a string list as one inline JSON array.
 */
private fun StringBuilder.appendGlyphStringArrayInlineJson(values: List<String>) {
    append("[")
    append(values.joinToString(", ") { value -> glyphJsonString(value) })
    append("]")
}

/**
 * Serializes a string list as a stable multiline JSON array.
 */
private fun StringBuilder.appendGlyphStringArrayMultilineJson(values: List<String>, indent: String) {
    if (values.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(values.joinToString(",\n") { value -> "$indent  ${glyphJsonString(value)}" })
    append("\n")
    append(indent)
    append("]")
}

/**
 * Serializes glyph cache inventory entries.
 */
private fun StringBuilder.appendGlyphCacheInventoryEntriesJson(entries: List<GlyphCacheInventoryEntry>) {
    if (entries.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(entries.joinToString(",\n") { entry -> entry.toCanonicalJson().prependGlyphJsonIndent("    ") })
    append("\n  ]")
}

/**
 * Serializes glyph cache route counts.
 */
private fun StringBuilder.appendGlyphCacheRouteCountsJson(counts: List<GlyphCacheRouteCount>) {
    if (counts.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(counts.joinToString(",\n") { count -> count.toCanonicalJson().prependGlyphJsonIndent("    ") })
    append("\n  ]")
}

/**
 * Serializes glyph cache timing summaries.
 */
private fun StringBuilder.appendGlyphCacheTimingStatsJson(stats: List<GlyphCacheTimingStats>) {
    if (stats.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(stats.joinToString(",\n") { item -> item.toCanonicalJson().prependGlyphJsonIndent("    ") })
    append("\n  ]")
}

/**
 * Serializes advisory budget-refusal rows.
 */
private fun StringBuilder.appendGlyphCacheBudgetRefusalsJson(refusals: List<GlyphCacheBudgetRefusal>) {
    if (refusals.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(refusals.joinToString(",\n") { refusal -> refusal.toCanonicalJson().prependGlyphJsonIndent("    ") })
    append("\n  ]")
}

/**
 * Serializes glyph cache telemetry samples.
 */
private fun StringBuilder.appendGlyphCacheTelemetrySamplesJson(samples: List<GlyphCacheTelemetrySample>) {
    if (samples.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(samples.joinToString(",\n") { sample -> sample.toCanonicalJson().prependGlyphJsonIndent("    ") })
    append("\n  ]")
}

/**
 * Serializes glyph artifact metric samples.
 */
private fun StringBuilder.appendGlyphArtifactMetricsSamplesJson(samples: List<GlyphArtifactMetricsSample>) {
    if (samples.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(samples.joinToString(",\n") { sample -> sample.toCanonicalJson().prependGlyphJsonIndent("    ") })
    append("\n  ]")
}

/**
 * Serializes glyph atlas occupancy rows.
 */
private fun StringBuilder.appendGlyphAtlasOccupancyEntriesJson(entries: List<GlyphAtlasOccupancyEntry>) {
    if (entries.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(entries.joinToString(",\n") { entry -> entry.toCanonicalJson().prependIndent("    ").trimEnd() })
    append("\n  ]")
}

/**
 * Serializes glyph cache metric samples.
 */
private fun StringBuilder.appendGlyphCacheMetricsSamplesJson(samples: List<GlyphCacheMetricsSample>) {
    if (samples.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(samples.joinToString(",\n") { sample -> sample.toCanonicalJson().prependGlyphJsonIndent("    ") })
    append("\n  ]")
}

/**
 * Serializes strike-key route preimage records.
 */
private fun StringBuilder.appendGlyphRoutePreimagesJson(records: List<GlyphStrikeKeyRoutePreimage>) {
    if (records.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(records.joinToString(",\n") { record -> record.toCanonicalJson().prependIndent("    ") })
    append("\n  ]")
}

/**
 * Serializes strike-key refusal records.
 */
private fun StringBuilder.appendGlyphStrikeKeyRefusalsJson(refusals: List<GlyphStrikeKeyRefusal>) {
    if (refusals.isEmpty()) {
        append("[]")
        return
    }
    append("[\n")
    append(refusals.joinToString(",\n") { refusal -> refusal.toCanonicalJson().prependIndent("    ") })
    append("\n  ]")
}

/**
 * Appends a stable string JSON field.
 */
private fun StringBuilder.appendGlyphJsonField(name: String, value: String, comma: Boolean) {
    append("  ").append(glyphJsonString(name)).append(": ").append(glyphJsonString(value))
    if (comma) append(",")
    append("\n")
}

/**
 * Appends a stable nullable string JSON field.
 */
private fun StringBuilder.appendGlyphJsonNullableField(name: String, value: String?, comma: Boolean) {
    append("  ").append(glyphJsonString(name)).append(": ")
    append(if (value == null) "null" else glyphJsonString(value))
    if (comma) append(",")
    append("\n")
}

/**
 * Appends a stable nullable integer JSON field.
 */
private fun StringBuilder.appendGlyphJsonNullableField(name: String, value: Int?, comma: Boolean) {
    append("  ").append(glyphJsonString(name)).append(": ")
    append(value?.toString() ?: "null")
    if (comma) append(",")
    append("\n")
}

/**
 * Appends a stable nullable long JSON field.
 */
private fun StringBuilder.appendGlyphJsonNullableField(name: String, value: Long?, comma: Boolean) {
    append("  ").append(glyphJsonString(name)).append(": ")
    append(value?.toString() ?: "null")
    if (comma) append(",")
    append("\n")
}

/**
 * Appends a stable integer JSON field.
 */
private fun StringBuilder.appendGlyphJsonField(name: String, value: Int, comma: Boolean) {
    append("  ").append(glyphJsonString(name)).append(": ").append(value)
    if (comma) append(",")
    append("\n")
}

/**
 * Appends a stable long JSON field.
 */
private fun StringBuilder.appendGlyphJsonField(name: String, value: Long, comma: Boolean) {
    append("  ").append(glyphJsonString(name)).append(": ").append(value)
    if (comma) append(",")
    append("\n")
}

/**
 * Appends a stable boolean JSON field.
 */
private fun StringBuilder.appendGlyphJsonField(name: String, value: Boolean, comma: Boolean) {
    append("  ").append(glyphJsonString(name)).append(": ").append(value)
    if (comma) append(",")
    append("\n")
}

/**
 * Appends a stable float JSON field.
 */
private fun StringBuilder.appendGlyphJsonField(name: String, value: Float, comma: Boolean) {
    append("  ").append(glyphJsonString(name)).append(": ").append(glyphFloatToken(value))
    if (comma) append(",")
    append("\n")
}

/**
 * Escapes a string as deterministic ASCII JSON.
 */
private fun glyphJsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (ch.code < 0x20 || ch.code > 0x7E) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
    }
    append('"')
}

/**
 * Formats a Unicode scalar value as a stable `U+XXXX` label.
 */
private fun glyphCodePointLabel(codePoint: Int): String =
    "U+${codePoint.toString(16).uppercase().padStart(4, '0')}"

/**
 * Formats a finite float using the JVM/Kotlin round-trip decimal syntax.
 */
private fun glyphFloatToken(value: Float): String {
    require(value.isFinite()) { "Glyph inventory float values must be finite." }
    val token = value.toString()
    return if (token.endsWith(".0") && 'E' !in token && 'e' !in token) {
        token.dropLast(2)
    } else {
        token
    }
}

/**
 * Formats a nullable finite float for compact JSON object fields.
 */
private fun glyphNullableFloatToken(value: Float?): String =
    value?.let { glyphFloatToken(it) } ?: "null"

private fun Float.glyphBitsEqual(other: Float): Boolean =
    toBits() == other.toBits()

private fun Float?.glyphBitsEqual(other: Float?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> this.glyphBitsEqual(other)
    }

/**
 * Escapes a nullable string for compact JSON object fields.
 */
private fun glyphJsonNullableString(value: String?): String =
    value?.let { glyphJsonString(it) } ?: "null"

/**
 * Indents all lines after the first one when embedding a JSON object after a field prefix.
 */
private fun String.indentJsonContinuation(indent: String): String =
    split("\n").mapIndexed { index, line ->
        if (index == 0) line else indent + line
    }.joinToString("\n")

/**
 * Indents embedded JSON blocks without adding trailing whitespace to blank lines.
 */
private fun String.prependGlyphJsonIndent(indent: String): String =
    lines().joinToString("\n") { line ->
        if (line.isBlank()) line else indent + line
    }

/**
 * Computes a lowercase SHA-256 digest for deterministic evidence identifiers.
 */
private fun glyphSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
