package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.font.sfnt.OpenTypeFaceData
import org.graphiks.kalligraphie.font.sfnt.HorizontalGlyphMetric
import org.graphiks.kalligraphie.font.sfnt.MetricsTables
import org.graphiks.kalligraphie.font.sfnt.SFNTTableTag
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Scales glyph outlines and metrics from parsed font data into requested design positions.
 */
interface OutlineScaler {
    /**
     * Produces an outline for one glyph at a variation position.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Variable-font axis position to apply before scaling.
     * @return Scaled glyph outline.
     */
    fun outline(glyphId: UInt, position: VariationPosition = VariationPosition()): GlyphOutline

    /**
     * Produces metrics for one glyph at a variation position.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Variable-font axis position to apply before measuring.
     * @return Scaled glyph metrics.
     */
    fun metrics(glyphId: UInt, position: VariationPosition = VariationPosition()): GlyphMetrics
}

/**
 * Typed outline drawing command shared by future glyph outline parsers.
 */
sealed interface OutlineCommand {
    /**
     * Starts a new contour at a point.
     *
     * @property x Destination x coordinate.
     * @property y Destination y coordinate.
     */
    data class MoveTo(
        val x: Double,
        val y: Double,
    ) : OutlineCommand

    /**
     * Adds a straight line segment to a point.
     *
     * @property x Destination x coordinate.
     * @property y Destination y coordinate.
     */
    data class LineTo(
        val x: Double,
        val y: Double,
    ) : OutlineCommand

    /**
     * Adds a quadratic Bezier segment.
     *
     * @property controlX Control point x coordinate.
     * @property controlY Control point y coordinate.
     * @property x Destination x coordinate.
     * @property y Destination y coordinate.
     */
    data class QuadraticTo(
        val controlX: Double,
        val controlY: Double,
        val x: Double,
        val y: Double,
    ) : OutlineCommand

    /**
     * Adds a cubic Bezier segment.
     *
     * @property controlX1 First control point x coordinate.
     * @property controlY1 First control point y coordinate.
     * @property controlX2 Second control point x coordinate.
     * @property controlY2 Second control point y coordinate.
     * @property x Destination x coordinate.
     * @property y Destination y coordinate.
     */
    data class CubicTo(
        val controlX1: Double,
        val controlY1: Double,
        val controlX2: Double,
        val controlY2: Double,
        val x: Double,
        val y: Double,
    ) : OutlineCommand

    /**
     * Closes the current contour.
     */
    data object Close : OutlineCommand
}

/**
 * Creates a typed move-to outline command.
 *
 * @param x Destination x coordinate.
 * @param y Destination y coordinate.
 * @return Outline command for starting a contour.
 */
fun moveTo(x: Double, y: Double): OutlineCommand = OutlineCommand.MoveTo(x, y)

/**
 * Creates a typed line-to outline command.
 *
 * @param x Destination x coordinate.
 * @param y Destination y coordinate.
 * @return Outline command for adding a straight line.
 */
fun lineTo(x: Double, y: Double): OutlineCommand = OutlineCommand.LineTo(x, y)

/**
 * Creates a typed quadratic curve outline command.
 *
 * @param controlX Control point x coordinate.
 * @param controlY Control point y coordinate.
 * @param x Destination x coordinate.
 * @param y Destination y coordinate.
 * @return Outline command for adding a quadratic Bezier segment.
 */
fun quadraticTo(
    controlX: Double,
    controlY: Double,
    x: Double,
    y: Double,
): OutlineCommand = OutlineCommand.QuadraticTo(controlX, controlY, x, y)

/**
 * Creates a typed cubic curve outline command.
 *
 * @param controlX1 First control point x coordinate.
 * @param controlY1 First control point y coordinate.
 * @param controlX2 Second control point x coordinate.
 * @param controlY2 Second control point y coordinate.
 * @param x Destination x coordinate.
 * @param y Destination y coordinate.
 * @return Outline command for adding a cubic Bezier segment.
 */
fun cubicTo(
    controlX1: Double,
    controlY1: Double,
    controlX2: Double,
    controlY2: Double,
    x: Double,
    y: Double,
): OutlineCommand = OutlineCommand.CubicTo(controlX1, controlY1, controlX2, controlY2, x, y)

/**
 * Creates a typed close-contour outline command.
 *
 * @return Outline command for closing the current contour.
 */
fun close(): OutlineCommand = OutlineCommand.Close

/**
 * Scaled outline geometry for one glyph.
 *
 * @property glyphId Font-specific glyph identifier.
 * @property contours Legacy opaque contour commands retained for source compatibility.
 * @property commands Typed outline commands for new pure Kotlin outline consumers.
 */
data class GlyphOutline(
    val glyphId: UInt,
    val contours: List<String> = emptyList(),
    val commands: List<OutlineCommand> = emptyList(),
)

/**
 * Scaled metrics for a glyph.
 *
 * @property advanceX Horizontal advance in user-independent font units.
 * @property advanceY Vertical advance in user-independent font units.
 * @property bounds Tight glyph bounds when available.
 */
data class GlyphMetrics(
    val advanceX: Double,
    val advanceY: Double,
    val bounds: GlyphBounds,
    val verticalMetrics: GlyphVerticalMetrics? = null,
)

/**
 * Deterministic vertical metric evidence attached to a glyph metrics dump.
 *
 * @property state Stable summary state: `present`, `fallback`, or `diagnostic`.
 * @property source Stable fact source such as `vhea-vmtx` or `horizontal-fallback-fact`.
 * @property verticalAdvance Vertical advance height in scaled font units when available.
 * @property topSideBearing Vertical top side bearing in scaled font units when available.
 * @property verticalOriginY Derived vertical origin y coordinate in scaled font units when available.
 * @property ascender Vertical ascender from `vhea` in scaled font units when available.
 * @property descender Vertical descender from `vhea` in scaled font units when available.
 * @property lineGap Vertical line gap from `vhea` in scaled font units when available.
 * @property maxAdvanceHeight Maximum vertical advance from `vhea` in scaled font units when available.
 * @property diagnostics Stable detail tokens attached to this vertical fact.
 */
data class GlyphVerticalMetrics(
    val state: String,
    val source: String,
    val verticalAdvance: Double? = null,
    val topSideBearing: Double? = null,
    val verticalOriginY: Double? = null,
    val ascender: Double? = null,
    val descender: Double? = null,
    val lineGap: Double? = null,
    val maxAdvanceHeight: Double? = null,
    val diagnostics: List<String> = emptyList(),
) {
    init {
        require(state.isStableToken()) { "vertical metric state must be a stable token." }
        require(source.isStableToken()) { "vertical metric source must be a stable token." }
        diagnostics.forEach { diagnostic ->
            require(diagnostic.isStableToken()) { "vertical metric diagnostic must be a stable token." }
        }
    }
}

/**
 * Stable scaler diagnostic code families used by evidence dumps.
 */
object FontScalerDiagnosticCodes {
    const val OUTLINE_FORMAT_UNSUPPORTED: String = "font.outline-format-unsupported"
    const val SCALER_OUTLINE_UNAVAILABLE: String = "font.scaler.outline-unavailable"
    const val CFF_OPERATOR_UNSUPPORTED: String = "font.cff-operator-unsupported"
    const val CFF_STACK_MALFORMED: String = "font.cff-stack-malformed"
    const val CFF2_BLEND_STACK_MALFORMED: String = "font.scaler.cff2.blend-stack-malformed"
    const val CFF_TABLE_MALFORMED: String = "font.cff-table-malformed"
    const val CFF_STACK_OVERFLOW: String = "font.scaler.cff.stack-overflow"
    const val CFF_TRAILING_BYTES: String = "font.scaler.cff.trailing-bytes"
    const val CFF_SUBROUTINE_OUT_OF_RANGE: String = "font.scaler.cff.subr-out-of-range"
    const val CFF_SUBROUTINE_DEPTH_LIMIT: String = "font.scaler.cff.subr-depth-limit"
    const val CFF_INSTRUCTION_LIMIT: String = "font.scaler.cff.instruction-limit"
    const val CFF_EXPANDED_BYTE_LIMIT: String = "font.scaler.cff.expanded-byte-limit"
    const val CFF_INDEX_BOUNDS: String = "font.scaler.cff.index-bounds"
    const val CFF_INDEX_OFFSIZE_UNSUPPORTED: String = "font.scaler.cff.index-offsize-unsupported"
    const val CFF_DICT_OPERAND_MALFORMED: String = "font.scaler.cff.dict-operand-malformed"
    const val CFF_REQUIRED_OPERATOR_MISSING: String = "font.scaler.cff.required-operator-missing"
    const val CFF_PATH_OUTPUT_UNAVAILABLE: String = "font.scaler.cff.path-output-unavailable"
    const val CFF_GLYPH_MALFORMED: String = "font.scaler.cff.glyph-malformed"
    const val VARIATION_DATA_MALFORMED: String = "font.variation-data-malformed"
    const val VARIATION_AXIS_UNSUPPORTED: String = "font.variation-axis-unsupported"
    const val METRICS_VARIATION_UNAVAILABLE: String = "font.metrics-variation-unavailable"
    const val VERTICAL_METRICS_UNAVAILABLE: String = "font.vertical-metrics-unavailable"
    const val REQUIRED_TABLE_MISSING: String = "font.required-table-missing"
}

/**
 * One stable scaler diagnostic attached to a glyph evidence dump.
 *
 * @property code Stable reason-code family.
 * @property detail Deterministic leaf detail inside the reason-code family.
 * @property operation Operation that produced the diagnostic, such as `outline` or `metrics`.
 * @property glyphId Glyph affected by the diagnostic.
 * @property severity Stable severity label such as `refusal` or `warning`.
 */
data class FontScalerDiagnostic(
    val code: String,
    val detail: String,
    val operation: String,
    val glyphId: UInt,
    val severity: String = "refusal",
) {
    init {
        require(code.isStableToken()) { "font scaler diagnostic code must be a stable token." }
        require(detail.isStableToken()) { "font scaler diagnostic detail must be a stable token." }
        require(operation.isStableToken()) { "font scaler diagnostic operation must be a stable token." }
        require(severity.isStableToken()) { "font scaler diagnostic severity must be a stable token." }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("code")).append(": ").append(scalerJsonString(code)).append(", ")
        append(scalerJsonString("detail")).append(": ").append(scalerJsonString(detail)).append(", ")
        append(scalerJsonString("operation")).append(": ").append(scalerJsonString(operation)).append(", ")
        append(scalerJsonString("glyphId")).append(": ").append(glyphId.toString()).append(", ")
        append(scalerJsonString("severity")).append(": ").append(scalerJsonString(severity))
        append("}")
    }
}

/**
 * Unsupported scaler operation carrying stable evidence diagnostics.
 */
class FontScalerRefusalException(
    val diagnostic: FontScalerDiagnostic,
    message: String,
) : UnsupportedOperationException(message)

/**
 * Deterministic call edge emitted while interpreting a CFF/CFF2 charstring fixture.
 *
 * @property depth Subroutine call depth from the top-level charstring.
 * @property scope `local` for `callsubr`, `global` for `callgsubr`.
 * @property encodedIndex Operand value before CFF subroutine bias is applied.
 * @property resolvedIndex Subroutine array index after applying CFF bias.
 */
data class CFFCharStringCallTrace(
    val depth: Int,
    val scope: String,
    val encodedIndex: Int,
    val resolvedIndex: Int,
    val bias: Int? = null,
    val callerByteOffset: Int? = null,
    val returnByteOffset: Int? = null,
    val instructionBudgetRemaining: Int? = null,
    val expandedByteBudgetRemaining: Int? = null,
) {
    init {
        require(depth >= 0) { "CFF call trace depth must be non-negative." }
        require(scope.isStableToken()) { "CFF call trace scope must be stable." }
        require(resolvedIndex >= 0) { "CFF call trace resolvedIndex must be non-negative." }
        require(bias == null || bias >= 0) { "CFF call trace bias must be non-negative when present." }
        require(callerByteOffset == null || callerByteOffset >= 0) {
            "CFF call trace callerByteOffset must be non-negative when present."
        }
        require(returnByteOffset == null || returnByteOffset >= 0) {
            "CFF call trace returnByteOffset must be non-negative when present."
        }
        require(instructionBudgetRemaining == null || instructionBudgetRemaining >= 0) {
            "CFF call trace instructionBudgetRemaining must be non-negative when present."
        }
        require(expandedByteBudgetRemaining == null || expandedByteBudgetRemaining >= 0) {
            "CFF call trace expandedByteBudgetRemaining must be non-negative when present."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("depth")).append(": ").append(depth).append(", ")
        append(scalerJsonString("scope")).append(": ").append(scalerJsonString(scope)).append(", ")
        append(scalerJsonString("encodedIndex")).append(": ").append(encodedIndex).append(", ")
        append(scalerJsonString("resolvedIndex")).append(": ").append(resolvedIndex)
        bias?.let { value ->
            append(", ").append(scalerJsonString("bias")).append(": ").append(value)
        }
        callerByteOffset?.let { value ->
            append(", ").append(scalerJsonString("callerByteOffset")).append(": ").append(value)
        }
        returnByteOffset?.let { value ->
            append(", ").append(scalerJsonString("returnByteOffset")).append(": ").append(value)
        }
        instructionBudgetRemaining?.let { value ->
            append(", ").append(scalerJsonString("instructionBudgetRemaining")).append(": ").append(value)
        }
        expandedByteBudgetRemaining?.let { value ->
            append(", ").append(scalerJsonString("expandedByteBudgetRemaining")).append(": ").append(value)
        }
        append("}")
    }
}

/**
 * Deterministic Type 2 execution limits. These bounds are fixed configuration, never host-derived.
 */
data class Type2ExecutionLimits(
    val maxOperandStack: Int = MAX_CFF_OPERAND_STACK_DEPTH,
    val maxCallDepth: Int = MAX_CFF_SUBROUTINE_DEPTH,
    val maxInstructionCount: Int = MAX_CFF_INSTRUCTION_COUNT,
    val maxExpandedBytes: Int = MAX_CFF_EXPANDED_BYTES,
) {
    init {
        require(maxOperandStack > 0) { "Type 2 maxOperandStack must be positive." }
        require(maxCallDepth > 0) { "Type 2 maxCallDepth must be positive." }
        require(maxInstructionCount > 0) { "Type 2 maxInstructionCount must be positive." }
        require(maxExpandedBytes > 0) { "Type 2 maxExpandedBytes must be positive." }
    }
}

/**
 * Fixture-level evidence for one interpreted CFF/CFF2 charstring.
 *
 * This evidence covers generated Type 2/CFF2 charstring fixtures only. It is intentionally not a
 * complete CFF table parser or a claim that OpenType/CFF fonts are fully supported.
 */
data class CFFBlendVectorEvidence(
    val vsIndex: Int,
    val defaults: List<Double>,
    val deltaSets: List<List<Double>>,
    val regionIndexes: List<Int> = emptyList(),
    val scalars: List<Double>,
    val blendedValues: List<Double>,
) {
    init {
        require(vsIndex >= 0) { "CFF blend vector vsIndex must be non-negative." }
        require(defaults.isNotEmpty()) { "CFF blend vector defaults must not be empty." }
        require(defaults.all(Double::isFinite)) { "CFF blend vector defaults must be finite." }
        require(deltaSets.size == scalars.size) {
            "CFF blend vector deltaSets must align with scalar count."
        }
        require(deltaSets.all { deltas -> deltas.size == defaults.size }) {
            "CFF blend vector delta rows must align with default value count."
        }
        require(regionIndexes.isEmpty() || regionIndexes.size == scalars.size) {
            "CFF blend vector regionIndexes must be empty or align with scalar count."
        }
        require(regionIndexes.all { index -> index >= 0 }) {
            "CFF blend vector region indexes must be non-negative."
        }
        require(scalars.all(Double::isFinite)) { "CFF blend vector scalars must be finite." }
        require(blendedValues.size == defaults.size) {
            "CFF blend vector blendedValues must align with default value count."
        }
        require(blendedValues.all(Double::isFinite)) { "CFF blend vector blendedValues must be finite." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  ").append(scalerJsonString("vsIndex")).append(": ").append(vsIndex).append(",\n")
        append("  ").append(scalerJsonString("defaults")).append(": ")
            .append(defaults.toScalerNumberJsonArray()).append(",\n")
        append("  ").append(scalerJsonString("deltaSets")).append(": ")
            .append(deltaSets.toScalerNumberMatrixJson()).append(",\n")
        append("  ").append(scalerJsonString("regionIndexes")).append(": ")
            .append(regionIndexes.toIntJsonArray()).append(",\n")
        append("  ").append(scalerJsonString("scalars")).append(": ")
            .append(scalars.toScalerNumberJsonArray()).append(",\n")
        append("  ").append(scalerJsonString("blendedValues")).append(": ")
            .append(blendedValues.toScalerNumberJsonArray()).append("\n")
        append("}")
    }
}

data class CFFCharStringEvidence(
    val format: String,
    val glyphId: UInt,
    val width: Double? = null,
    val stemHintCount: Int = 0,
    val hintMaskByteCount: Int = 0,
    val outlineCommands: List<String>,
    val outlineCommandDump: String = outlineCommands.joinToString("\n"),
    val outlineCommandDumpSha256: String = outlineCommandDump.scalerSha256Hex(),
    val callTrace: List<CFFCharStringCallTrace> = emptyList(),
    val variationPosition: List<VariationCoordinateEvidence> = emptyList(),
    val cff2VsIndex: Int = 0,
    val blendVectors: List<CFFBlendVectorEvidence> = emptyList(),
    val diagnostics: List<FontScalerDiagnostic> = emptyList(),
) {
    init {
        require(format.isStableToken()) { "CFF charstring evidence format must be stable." }
        require(width == null || width.isFinite()) { "CFF charstring width must be finite when present." }
        require(stemHintCount >= 0) { "CFF stem hint count must be non-negative." }
        require(hintMaskByteCount >= 0) { "CFF hint mask byte count must be non-negative." }
        require(outlineCommands.none { line -> line.any { it == '\n' || it == '\r' } }) {
            "CFF outline command evidence lines must be single-line."
        }
        require(outlineCommandDump == outlineCommands.joinToString("\n")) {
            "CFF outline command dump must match outlineCommands."
        }
        require(outlineCommandDumpSha256 == outlineCommandDump.scalerSha256Hex()) {
            "CFF outline command dump SHA-256 must match outlineCommandDump."
        }
        require(variationPosition.isSortedByTag()) {
            "CFF variation evidence coordinates must be sorted by axis tag."
        }
        require(cff2VsIndex >= 0) { "CFF2 vsindex must be non-negative." }
    }

    /**
     * Serializes this evidence with stable field order and no host-dependent facts.
     */
    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  ").append(scalerJsonString("format")).append(": ")
            .append(scalerJsonString(format)).append(",\n")
        append("  ").append(scalerJsonString("glyphId")).append(": ").append(glyphId.toString()).append(",\n")
        append("  ").append(scalerJsonString("width")).append(": ")
            .append(width?.toCanonicalScalerNumber() ?: "null").append(",\n")
        append("  ").append(scalerJsonString("stemHintCount")).append(": ")
            .append(stemHintCount).append(",\n")
        append("  ").append(scalerJsonString("hintMaskByteCount")).append(": ")
            .append(hintMaskByteCount).append(",\n")
        append("  ").append(scalerJsonString("outlineCommands")).append(": ")
            .append(outlineCommands.toJsonStringArray()).append(",\n")
        append("  ").append(scalerJsonString("outlineCommandDump")).append(": ")
            .append(scalerJsonString(outlineCommandDump)).append(",\n")
        append("  ").append(scalerJsonString("outlineCommandDumpSha256")).append(": ")
            .append(scalerJsonString(outlineCommandDumpSha256)).append(",\n")
        append("  ").append(scalerJsonString("callTrace")).append(": ")
            .append(callTrace.toCFFCallTraceJson()).append(",\n")
        append("  ").append(scalerJsonString("variationPosition")).append(": ")
            .append(variationPosition.toCoordinateJson()).append(",\n")
        append("  ").append(scalerJsonString("cff2VsIndex")).append(": ").append(cff2VsIndex).append(",\n")
        append("  ").append(scalerJsonString("blendVectors")).append(": ")
            .append(blendVectors.toCFFBlendVectorJson()).append(",\n")
        append("  ").append(scalerJsonString("diagnostics")).append(": ")
            .append(diagnostics.toDiagnosticJson()).append("\n")
        append("}")
    }
}

/**
 * Deterministic current-state evidence for one scaled CFF glyph route.
 *
 * This evidence is generated-fixture only. It proves current bounded path and metrics behavior for
 * selected CFF glyphs without promoting complete real-font CFF support, fallback selection, or GPU
 * glyph routes.
 */
data class CFFScaledGlyphEvidence(
    val sourceId: String,
    val typefaceId: String,
    val format: String,
    val requestedGlyphId: UInt,
    val resolvedGlyphId: UInt? = requestedGlyphId,
    val notdefUsed: Boolean = false,
    val scalerFamily: String = CFF_SCALER_FAMILY,
    val route: String = CFF_SCALER_ROUTE,
    val fillRule: String = CFF_FILL_RULE,
    val outlineCommands: List<String>,
    val outlineCommandDump: String = outlineCommands.joinToString("\n"),
    val outlineCommandDumpSha256: String = outlineCommandDump.scalerSha256Hex(),
    val conservativeBounds: GlyphBounds? = null,
    val metrics: GlyphMetrics? = null,
    val widthSource: String? = null,
    val linkedCharStringTraceDumpId: String? = null,
    val charStringEvidence: CFFCharStringEvidence? = null,
    val diagnostics: List<FontScalerDiagnostic> = emptyList(),
) {
    init {
        require(sourceId.isStableToken()) { "CFF scaled glyph evidence sourceId must be stable." }
        require(typefaceId.isStableToken()) { "CFF scaled glyph evidence typefaceId must be stable." }
        require(format.isStableToken()) { "CFF scaled glyph evidence format must be stable." }
        require(scalerFamily.isStableToken()) { "CFF scaled glyph evidence scalerFamily must be stable." }
        require(route.isStableToken()) { "CFF scaled glyph evidence route must be stable." }
        require(fillRule.isStableToken()) { "CFF scaled glyph evidence fillRule must be stable." }
        require(widthSource == null || widthSource.isStableToken()) {
            "CFF scaled glyph evidence widthSource must be stable when present."
        }
        require(linkedCharStringTraceDumpId == null || linkedCharStringTraceDumpId.isStableToken()) {
            "CFF scaled glyph evidence linkedCharStringTraceDumpId must be stable when present."
        }
        require(outlineCommands.none { line -> line.any { it == '\n' || it == '\r' } }) {
            "CFF scaled glyph evidence outline command lines must be single-line."
        }
        require(outlineCommandDump == outlineCommands.joinToString("\n")) {
            "CFF scaled glyph evidence outlineCommandDump must match outlineCommands."
        }
        require(outlineCommandDumpSha256 == outlineCommandDump.scalerSha256Hex()) {
            "CFF scaled glyph evidence outlineCommandDumpSha256 must match outlineCommandDump."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  ").append(scalerJsonString("sourceId")).append(": ")
            .append(scalerJsonString(sourceId)).append(",\n")
        append("  ").append(scalerJsonString("typefaceId")).append(": ")
            .append(scalerJsonString(typefaceId)).append(",\n")
        append("  ").append(scalerJsonString("format")).append(": ")
            .append(scalerJsonString(format)).append(",\n")
        append("  ").append(scalerJsonString("requestedGlyphId")).append(": ")
            .append(requestedGlyphId.toString()).append(",\n")
        append("  ").append(scalerJsonString("resolvedGlyphId")).append(": ")
            .append(resolvedGlyphId?.toString() ?: "null").append(",\n")
        append("  ").append(scalerJsonString("notdefUsed")).append(": ").append(notdefUsed).append(",\n")
        append("  ").append(scalerJsonString("scalerFamily")).append(": ")
            .append(scalerJsonString(scalerFamily)).append(",\n")
        append("  ").append(scalerJsonString("route")).append(": ")
            .append(scalerJsonString(route)).append(",\n")
        append("  ").append(scalerJsonString("fillRule")).append(": ")
            .append(scalerJsonString(fillRule)).append(",\n")
        append("  ").append(scalerJsonString("outlineCommands")).append(": ")
            .append(outlineCommands.toJsonStringArray()).append(",\n")
        append("  ").append(scalerJsonString("outlineCommandDump")).append(": ")
            .append(scalerJsonString(outlineCommandDump)).append(",\n")
        append("  ").append(scalerJsonString("outlineCommandDumpSha256")).append(": ")
            .append(scalerJsonString(outlineCommandDumpSha256)).append(",\n")
        append("  ").append(scalerJsonString("conservativeBounds")).append(": ")
            .append(conservativeBounds?.toCanonicalJson() ?: "null").append(",\n")
        append("  ").append(scalerJsonString("metrics")).append(": ")
            .append(metrics?.toCanonicalJson() ?: "null").append(",\n")
        append("  ").append(scalerJsonString("widthSource")).append(": ")
            .append(widthSource?.let(::scalerJsonString) ?: "null").append(",\n")
        append("  ").append(scalerJsonString("linkedCharStringTraceDumpId")).append(": ")
            .append(linkedCharStringTraceDumpId?.let(::scalerJsonString) ?: "null").append(",\n")
        append("  ").append(scalerJsonString("charStringEvidence")).append(": ")
            .append(charStringEvidence?.toCanonicalJson() ?: "null").append(",\n")
        append("  ").append(scalerJsonString("diagnostics")).append(": ")
            .append(diagnostics.toDiagnosticJson()).append("\n")
        append("}")
    }
}

/**
 * Byte range evidence for one bounded CFF parser slice.
 */
data class CFFByteRangeEvidence(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "CFF byte range start must be non-negative." }
        require(endExclusive >= start) { "CFF byte range endExclusive must be >= start." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("start")).append(": ").append(start).append(", ")
        append(scalerJsonString("endExclusive")).append(": ").append(endExclusive).append(", ")
        append(scalerJsonString("byteLength")).append(": ").append(endExclusive - start)
        append("}")
    }
}

/**
 * Deterministic CFF INDEX facts.
 */
data class CFFIndexEvidence(
    val name: String,
    val count: Int,
    val offSize: Int,
    val objectRanges: List<CFFByteRangeEvidence>,
) {
    init {
        require(name.isNotEmpty()) { "CFF INDEX name must not be empty." }
        require(count >= 0) { "CFF INDEX count must be non-negative." }
        require(offSize in 0..4) { "CFF INDEX offSize must be within 0..4." }
        require(objectRanges.size == count) { "CFF INDEX objectRanges must match count." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("name")).append(": ").append(scalerJsonString(name)).append(", ")
        append(scalerJsonString("count")).append(": ").append(count).append(", ")
        append(scalerJsonString("offSize")).append(": ").append(offSize).append(", ")
        append(scalerJsonString("objectRanges")).append(": ").append(objectRanges.toCFFByteRangeJsonArray())
        append("}")
    }
}

/**
 * Typed DICT operator evidence for one CFF/CFF2 dict record.
 */
data class CFFDictOperatorEvidence(
    val name: String,
    val operands: List<Int>,
    val byteRange: CFFByteRangeEvidence,
) {
    init {
        require(name.isStableToken()) { "CFF DICT operator name must be stable." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("name")).append(": ").append(scalerJsonString(name)).append(", ")
        append(scalerJsonString("operands")).append(": ").append(operands.toIntJsonArray()).append(", ")
        append(scalerJsonString("byteRange")).append(": ").append(byteRange.toCanonicalJson())
        append("}")
    }
}

/**
 * Deterministic DICT evidence for one top dict or FDArray font dict.
 */
data class CFFDictEvidence(
    val section: String,
    val objectIndex: Int?,
    val byteRange: CFFByteRangeEvidence,
    val operators: List<CFFDictOperatorEvidence>,
    val unknownOperators: List<String>,
) {
    init {
        require(section.isNotEmpty()) { "CFF dict section must not be empty." }
        require(unknownOperators == unknownOperators.sorted()) { "CFF dict unknownOperators must be sorted." }
        require(unknownOperators.all { operator -> operator.isStableToken() }) {
            "CFF dict unknownOperators must be stable."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("section")).append(": ").append(scalerJsonString(section)).append(", ")
        objectIndex?.let {
            append(scalerJsonString("objectIndex")).append(": ").append(it).append(", ")
        }
        append(scalerJsonString("byteRange")).append(": ").append(byteRange.toCanonicalJson()).append(", ")
        append(scalerJsonString("operators")).append(": ").append(operators.toCFFDictOperatorJsonArray()).append(", ")
        append(scalerJsonString("unknownOperators")).append(": ").append(unknownOperators.toJsonStringArray())
        append("}")
    }
}

/**
 * Deterministic Private DICT evidence with optional local subroutine metadata.
 */
data class CFFPrivateDictEvidence(
    val owner: String,
    val byteRange: CFFByteRangeEvidence,
    val operators: List<CFFDictOperatorEvidence>,
    val localSubroutineIndex: CFFIndexEvidence? = null,
) {
    init {
        require(owner.isNotEmpty()) { "CFF private dict owner must not be empty." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("owner")).append(": ").append(scalerJsonString(owner)).append(", ")
        append(scalerJsonString("byteRange")).append(": ").append(byteRange.toCanonicalJson()).append(", ")
        append(scalerJsonString("operators")).append(": ").append(operators.toCFFDictOperatorJsonArray()).append(", ")
        append(scalerJsonString("localSubroutineIndex")).append(": ")
            .append(localSubroutineIndex?.toCanonicalJson() ?: "null")
        append("}")
    }
}

/**
 * Deterministic FDSelect routing facts for CID-keyed CFF fixtures.
 */
data class CFFFDSelectEvidence(
    val format: Int,
    val glyphToFontDict: List<Int>,
) {
    init {
        require(format >= 0) { "CFF FDSelect format must be non-negative." }
        require(glyphToFontDict.all { index -> index >= 0 }) { "CFF FDSelect indexes must be non-negative." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("format")).append(": ").append(format).append(", ")
        append(scalerJsonString("glyphToFontDict")).append(": ").append(glyphToFontDict.toIntJsonArray())
        append("}")
    }
}

/**
 * Deterministic table-local diagnostic evidence for bounded CFF parsing.
 */
data class CFFTableDiagnosticEvidence(
    val code: String,
    val detail: String,
    val section: String,
    val byteRange: CFFByteRangeEvidence? = null,
    val objectIndex: Int? = null,
    val operatorName: String? = null,
) {
    init {
        require(code.isStableToken()) { "CFF table diagnostic code must be stable." }
        require(detail.isStableToken()) { "CFF table diagnostic detail must be stable." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("code")).append(": ").append(scalerJsonString(code)).append(", ")
        append(scalerJsonString("detail")).append(": ").append(scalerJsonString(detail)).append(", ")
        append(scalerJsonString("section")).append(": ").append(scalerJsonString(section))
        byteRange?.let { append(", ").append(scalerJsonString("byteRange")).append(": ").append(it.toCanonicalJson()) }
        objectIndex?.let { append(", ").append(scalerJsonString("objectIndex")).append(": ").append(it) }
        operatorName?.let {
            append(", ").append(scalerJsonString("operatorName")).append(": ").append(scalerJsonString(it))
        }
        append("}")
    }
}

/**
 * Deterministic provenance evidence for the bounded CFF/CFF2 table parser.
 */
data class CFFTableEvidence(
    val sourceId: String,
    val typefaceId: String,
    val format: String,
    val charStringCount: Int,
    val localSubroutineCount: Int,
    val globalSubroutineCount: Int,
    val hasPrivateDict: Boolean,
    val topDictOperators: List<String>,
    val names: List<String> = emptyList(),
    val indexes: List<CFFIndexEvidence> = emptyList(),
    val topDicts: List<CFFDictEvidence> = emptyList(),
    val privateDicts: List<CFFPrivateDictEvidence> = emptyList(),
    val fdArray: List<CFFDictEvidence> = emptyList(),
    val fdSelect: CFFFDSelectEvidence? = null,
    val diagnostics: List<CFFTableDiagnosticEvidence> = emptyList(),
    val variationAxisTags: List<String> = emptyList(),
) {
    init {
        require(sourceId.isStableToken()) { "CFF table evidence sourceId must be stable." }
        require(typefaceId.isStableToken()) { "CFF table evidence typefaceId must be stable." }
        require(format.isStableToken()) { "CFF table evidence format must be stable." }
        require(charStringCount > 0) { "CFF table evidence charStringCount must be positive." }
        require(localSubroutineCount >= 0) { "CFF table evidence localSubroutineCount must be non-negative." }
        require(globalSubroutineCount >= 0) { "CFF table evidence globalSubroutineCount must be non-negative." }
        require(topDictOperators == topDictOperators.sorted()) {
            "CFF table evidence topDictOperators must be sorted."
        }
        require(topDictOperators.all { operator -> operator.isStableToken() }) {
            "CFF table evidence topDictOperators must be stable."
        }
        require(variationAxisTags == variationAxisTags.sorted()) {
            "CFF table evidence variationAxisTags must be sorted."
        }
        variationAxisTags.forEach { tag ->
            require(tag.length == 4) { "CFF table evidence variation axis tag must contain exactly four characters." }
            require(tag.all { character -> character.code in 0x20..0x7e }) {
                "CFF table evidence variation axis tag $tag must contain printable ASCII characters."
            }
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  ").append(scalerJsonString("sourceId")).append(": ")
            .append(scalerJsonString(sourceId)).append(",\n")
        append("  ").append(scalerJsonString("typefaceId")).append(": ")
            .append(scalerJsonString(typefaceId)).append(",\n")
        append("  ").append(scalerJsonString("format")).append(": ")
            .append(scalerJsonString(format)).append(",\n")
        append("  ").append(scalerJsonString("charStringCount")).append(": ")
            .append(charStringCount).append(",\n")
        append("  ").append(scalerJsonString("localSubroutineCount")).append(": ")
            .append(localSubroutineCount).append(",\n")
        append("  ").append(scalerJsonString("globalSubroutineCount")).append(": ")
            .append(globalSubroutineCount).append(",\n")
        append("  ").append(scalerJsonString("hasPrivateDict")).append(": ")
            .append(hasPrivateDict).append(",\n")
        append("  ").append(scalerJsonString("topDictOperators")).append(": ")
            .append(topDictOperators.toJsonStringArray()).append(",\n")
        append("  ").append(scalerJsonString("names")).append(": ")
            .append(names.toJsonStringArray()).append(",\n")
        append("  ").append(scalerJsonString("indexes")).append(": ")
            .append(indexes.toCFFIndexJsonArray()).append(",\n")
        append("  ").append(scalerJsonString("topDicts")).append(": ")
            .append(topDicts.toCFFDictJsonArray()).append(",\n")
        append("  ").append(scalerJsonString("privateDicts")).append(": ")
            .append(privateDicts.toCFFPrivateDictJsonArray()).append(",\n")
        append("  ").append(scalerJsonString("fdArray")).append(": ")
            .append(fdArray.toCFFDictJsonArray()).append(",\n")
        append("  ").append(scalerJsonString("fdSelect")).append(": ")
            .append(fdSelect?.toCanonicalJson() ?: "null").append(",\n")
        append("  ").append(scalerJsonString("diagnostics")).append(": ")
            .append(diagnostics.toCFFTableDiagnosticJsonArray()).append(",\n")
        append("  ").append(scalerJsonString("variationAxisTags")).append(": ")
            .append(variationAxisTags.toJsonStringArray()).append("\n")
        append("}")
    }
}

/**
 * One variation coordinate in deterministic evidence order.
 *
 * @property tag Four-character OpenType axis tag.
 * @property value User-space or normalized coordinate value.
 */
data class VariationCoordinateEvidence(
    val tag: String,
    val value: Double,
) {
    init {
        require(tag.length == 4) { "variation evidence axis tag must contain exactly four characters." }
        require(tag.all { character -> character.code in 0x20..0x7e }) {
            "variation evidence axis tag $tag must contain printable ASCII characters."
        }
        require(value.isFinite()) { "variation evidence axis $tag value must be finite." }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("tag")).append(": ").append(scalerJsonString(tag)).append(", ")
        append(scalerJsonString("value")).append(": ").append(value.toCanonicalScalerNumber())
        append("}")
    }
}

/**
 * Bounded `loca` range facts for one TrueType glyph.
 *
 * @property start Inclusive byte offset in the `glyf` table.
 * @property endExclusive Exclusive byte offset in the `glyf` table.
 * @property byteLength Number of bytes in the glyph range.
 * @property isEmpty Whether the `loca` entry points to an empty glyph.
 */
data class TrueTypeLocaRangeEvidence(
    val start: Int,
    val endExclusive: Int,
    val byteLength: Int,
    val isEmpty: Boolean,
) {
    init {
        require(start >= 0) { "loca evidence start must be non-negative." }
        require(endExclusive >= start) { "loca evidence endExclusive must be at least start." }
        require(byteLength == endExclusive - start) { "loca evidence byteLength must match the range." }
        require(isEmpty == (byteLength == 0)) { "loca evidence isEmpty must match byteLength." }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("start")).append(": ").append(start).append(", ")
        append(scalerJsonString("endExclusive")).append(": ").append(endExclusive).append(", ")
        append(scalerJsonString("byteLength")).append(": ").append(byteLength).append(", ")
        append(scalerJsonString("isEmpty")).append(": ").append(isEmpty)
        append("}")
    }
}

/**
 * Deterministic evidence for one component edge in a TrueType composite glyph.
 *
 * @property depth Composite recursion depth where the component was encountered.
 * @property parentGlyphId Composite glyph that owns this component record.
 * @property componentIndex Zero-based component record index in the parent glyph.
 * @property componentGlyphId Referenced component glyph.
 * @property flags Raw component flags as lowercase hexadecimal.
 * @property argumentKind Decoded argument mode: `xy` or `point-matching`.
 * @property argument1 First decoded component argument.
 * @property argument2 Second decoded component argument.
 * @property xx X-to-X affine coefficient.
 * @property xy Y-to-X affine coefficient.
 * @property yx X-to-Y affine coefficient.
 * @property yy Y-to-Y affine coefficient.
 * @property dx Translation X in font units.
 * @property dy Translation Y in font units.
 */
data class TrueTypeCompositeComponentEvidence(
    val depth: Int,
    val parentGlyphId: UInt,
    val componentIndex: Int,
    val componentGlyphId: UInt,
    val flags: String,
    val argumentKind: String,
    val argument1: Int,
    val argument2: Int,
    val xx: Double,
    val xy: Double,
    val yx: Double,
    val yy: Double,
    val dx: Double,
    val dy: Double,
) {
    init {
        require(depth >= 0) { "composite component evidence depth must be non-negative." }
        require(componentIndex >= 0) { "composite component evidence componentIndex must be non-negative." }
        require(flags.matches(Regex("0x[0-9a-f]{4}"))) {
            "composite component evidence flags must be lowercase hexadecimal uint16 text."
        }
        require(argumentKind.isStableToken()) { "composite component evidence argumentKind must be stable." }
        require(listOf(xx, xy, yx, yy, dx, dy).all(Double::isFinite)) {
            "composite component evidence transform values must be finite."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{")
        append(scalerJsonString("depth")).append(": ").append(depth).append(", ")
        append(scalerJsonString("parentGlyphId")).append(": ").append(parentGlyphId).append(", ")
        append(scalerJsonString("componentIndex")).append(": ").append(componentIndex).append(", ")
        append(scalerJsonString("componentGlyphId")).append(": ").append(componentGlyphId).append(", ")
        append(scalerJsonString("flags")).append(": ").append(scalerJsonString(flags)).append(", ")
        append(scalerJsonString("argumentKind")).append(": ").append(scalerJsonString(argumentKind)).append(", ")
        append(scalerJsonString("argument1")).append(": ").append(argument1).append(", ")
        append(scalerJsonString("argument2")).append(": ").append(argument2).append(", ")
        append(scalerJsonString("xx")).append(": ").append(xx.toCanonicalScalerNumber()).append(", ")
        append(scalerJsonString("xy")).append(": ").append(xy.toCanonicalScalerNumber()).append(", ")
        append(scalerJsonString("yx")).append(": ").append(yx.toCanonicalScalerNumber()).append(", ")
        append(scalerJsonString("yy")).append(": ").append(yy.toCanonicalScalerNumber()).append(", ")
        append(scalerJsonString("dx")).append(": ").append(dx.toCanonicalScalerNumber()).append(", ")
        append(scalerJsonString("dy")).append(": ").append(dy.toCanonicalScalerNumber())
        append("}")
    }
}

/**
 * Deterministic current-state evidence for one scaled TrueType `glyf` glyph.
 *
 * The dump is intentionally narrow: it records the current pure Kotlin `glyf` scaler output and
 * stable refusals without claiming complete CFF/CFF2, IUP, phantom metrics, or full variable-font
 * support.
 */
data class ScaledTrueTypeGlyphEvidence(
    val glyphId: UInt,
    val requestedVariationPosition: List<VariationCoordinateEvidence>,
    val normalizedVariationPosition: List<VariationCoordinateEvidence>,
    val outlineCommands: List<String>,
    val outlineCommandDump: String,
    val outlineCommandDumpSha256: String,
    val conservativeBounds: GlyphBounds?,
    val metrics: GlyphMetrics?,
    val locaRange: TrueTypeLocaRangeEvidence,
    val scalerFamily: String = TRUE_TYPE_GLYF_SCALER_FAMILY,
    val route: String = TRUE_TYPE_GLYF_SCALER_ROUTE,
    val compositeComponents: List<TrueTypeCompositeComponentEvidence> = emptyList(),
    val diagnostics: List<FontScalerDiagnostic> = emptyList(),
) {
    init {
        require(requestedVariationPosition.isSortedByTag()) {
            "requested variation evidence coordinates must be sorted by axis tag."
        }
        require(normalizedVariationPosition.isSortedByTag()) {
            "normalized variation evidence coordinates must be sorted by axis tag."
        }
        require(outlineCommands.none { line -> line.any { it == '\n' || it == '\r' } }) {
            "outline command evidence lines must be single-line."
        }
        require(outlineCommandDump == outlineCommands.joinToString("\n")) {
            "outline command dump must match outlineCommands."
        }
        require(outlineCommandDumpSha256 == outlineCommandDump.scalerSha256Hex()) {
            "outline command dump SHA-256 must match outlineCommandDump."
        }
        require(scalerFamily.isStableToken()) { "scalerFamily must be a stable token." }
        require(route.isStableToken()) { "route must be a stable token." }
    }

    /**
     * Serializes this evidence with stable field order and no host-dependent facts.
     */
    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  ").append(scalerJsonString("glyphId")).append(": ").append(glyphId.toString()).append(",\n")
        append("  ").append(scalerJsonString("scalerFamily")).append(": ")
            .append(scalerJsonString(scalerFamily)).append(",\n")
        append("  ").append(scalerJsonString("route")).append(": ").append(scalerJsonString(route)).append(",\n")
        append("  ").append(scalerJsonString("locaRange")).append(": ")
            .append(locaRange.toCanonicalJson()).append(",\n")
        append("  ").append(scalerJsonString("requestedVariationPosition")).append(": ")
            .append(requestedVariationPosition.toCoordinateJson()).append(",\n")
        append("  ").append(scalerJsonString("normalizedVariationPosition")).append(": ")
            .append(normalizedVariationPosition.toCoordinateJson()).append(",\n")
        append("  ").append(scalerJsonString("outlineCommands")).append(": ")
            .append(outlineCommands.toJsonStringArray()).append(",\n")
        append("  ").append(scalerJsonString("outlineCommandDumpSha256")).append(": ")
            .append(scalerJsonString(outlineCommandDumpSha256)).append(",\n")
        append("  ").append(scalerJsonString("conservativeBounds")).append(": ")
            .append(conservativeBounds?.toCanonicalJson() ?: "null").append(",\n")
        append("  ").append(scalerJsonString("metrics")).append(": ")
            .append(metrics?.toCanonicalJson() ?: "null").append(",\n")
        append("  ").append(scalerJsonString("compositeComponents")).append(": ")
            .append(compositeComponents.toCompositeComponentJson()).append(",\n")
        append("  ").append(scalerJsonString("diagnostics")).append(": ")
            .append(diagnostics.toDiagnosticJson()).append("\n")
        append("}")
    }
}

/**
 * Horizontal TrueType glyph metric decoded from `hmtx`-equivalent data.
 *
 * @property advanceX Horizontal advance in font units before scaler conversion.
 * @property leftSideBearing Horizontal left side bearing in font units before scaler conversion.
 */
data class TrueTypeGlyphHorizontalMetrics(
    val advanceX: Double,
    val leftSideBearing: Double,
) {
    init {
        require(advanceX.isFinite()) { "advanceX must be finite." }
        require(leftSideBearing.isFinite()) { "leftSideBearing must be finite." }
    }
}

/**
 * Axis-aligned glyph bounds.
 *
 * @property left Minimum x coordinate.
 * @property top Minimum y coordinate.
 * @property right Maximum x coordinate.
 * @property bottom Maximum y coordinate.
 */
data class GlyphBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

/**
 * Computes conservative bounds for typed outline commands.
 *
 * Bounds include all command endpoints and Bezier control points. This is conservative for curves
 * and intentionally does not solve exact curve extrema.
 *
 * @return Axis-aligned bounds, or null when no command carries coordinates.
 */
fun List<OutlineCommand>.conservativeBounds(): GlyphBounds? {
    var left = Double.POSITIVE_INFINITY
    var top = Double.POSITIVE_INFINITY
    var right = Double.NEGATIVE_INFINITY
    var bottom = Double.NEGATIVE_INFINITY
    var hasPoint = false

    fun include(x: Double, y: Double) {
        left = min(left, x)
        top = min(top, y)
        right = max(right, x)
        bottom = max(bottom, y)
        hasPoint = true
    }

    for (command in this) {
        when (command) {
            is OutlineCommand.MoveTo -> include(command.x, command.y)
            is OutlineCommand.LineTo -> include(command.x, command.y)
            is OutlineCommand.QuadraticTo -> {
                include(command.controlX, command.controlY)
                include(command.x, command.y)
            }
            is OutlineCommand.CubicTo -> {
                include(command.controlX1, command.controlY1)
                include(command.controlX2, command.controlY2)
                include(command.x, command.y)
            }
            OutlineCommand.Close -> Unit
        }
    }

    return if (hasPoint) {
        GlyphBounds(left = left, top = top, right = right, bottom = bottom)
    } else {
        null
    }
}

/**
 * Computes conservative bounds for the typed commands in this outline.
 *
 * @return Axis-aligned bounds, or null when no typed command carries coordinates.
 */
fun GlyphOutline.conservativeBounds(): GlyphBounds? = commands.conservativeBounds()

/**
 * Returns this bounds scaled by independent x and y factors.
 *
 * Negative factors are accepted and bounds are normalized after scaling.
 *
 * @param scaleX Factor applied to x coordinates.
 * @param scaleY Factor applied to y coordinates.
 * @return Scaled axis-aligned bounds.
 */
fun GlyphBounds.scaled(scaleX: Double, scaleY: Double = scaleX): GlyphBounds {
    require(scaleX.isFinite()) { "scaleX must be finite." }
    require(scaleY.isFinite()) { "scaleY must be finite." }

    val scaledLeft = left * scaleX
    val scaledRight = right * scaleX
    val scaledTop = top * scaleY
    val scaledBottom = bottom * scaleY
    return GlyphBounds(
        left = min(scaledLeft, scaledRight),
        top = min(scaledTop, scaledBottom),
        right = max(scaledLeft, scaledRight),
        bottom = max(scaledTop, scaledBottom),
    )
}

/**
 * Converts this bounds from font units to pixels.
 *
 * @param unitsPerEm Number of font units in one em.
 * @param pixelSize Requested pixel size for one em.
 * @return Bounds scaled by `pixelSize / unitsPerEm`.
 */
fun GlyphBounds.fontUnitsToPixels(unitsPerEm: Double, pixelSize: Double): GlyphBounds =
    scaled(fontUnitsToPixelsScale(unitsPerEm, pixelSize))

/**
 * Returns these typed outline commands scaled by independent x and y factors.
 *
 * @param scaleX Factor applied to x coordinates.
 * @param scaleY Factor applied to y coordinates.
 * @return Scaled outline command list.
 */
fun List<OutlineCommand>.scaled(scaleX: Double, scaleY: Double = scaleX): List<OutlineCommand> {
    require(scaleX.isFinite()) { "scaleX must be finite." }
    require(scaleY.isFinite()) { "scaleY must be finite." }

    fun x(value: Double): Double = value * scaleX
    fun y(value: Double): Double = value * scaleY

    return map { command ->
        when (command) {
            is OutlineCommand.MoveTo -> moveTo(x(command.x), y(command.y))
            is OutlineCommand.LineTo -> lineTo(x(command.x), y(command.y))
            is OutlineCommand.QuadraticTo -> quadraticTo(
                controlX = x(command.controlX),
                controlY = y(command.controlY),
                x = x(command.x),
                y = y(command.y),
            )
            is OutlineCommand.CubicTo -> cubicTo(
                controlX1 = x(command.controlX1),
                controlY1 = y(command.controlY1),
                controlX2 = x(command.controlX2),
                controlY2 = y(command.controlY2),
                x = x(command.x),
                y = y(command.y),
            )
            OutlineCommand.Close -> close()
        }
    }
}

/**
 * Converts these typed outline commands from font units to pixels.
 *
 * @param unitsPerEm Number of font units in one em.
 * @param pixelSize Requested pixel size for one em.
 * @return Commands scaled by `pixelSize / unitsPerEm`.
 */
fun List<OutlineCommand>.fontUnitsToPixels(unitsPerEm: Double, pixelSize: Double): List<OutlineCommand> =
    scaled(fontUnitsToPixelsScale(unitsPerEm, pixelSize))

/**
 * Returns this outline with typed commands scaled by independent x and y factors.
 *
 * Legacy opaque contours are preserved unchanged.
 *
 * @param scaleX Factor applied to x coordinates.
 * @param scaleY Factor applied to y coordinates.
 * @return Outline with scaled typed commands.
 */
fun GlyphOutline.scaled(scaleX: Double, scaleY: Double = scaleX): GlyphOutline =
    copy(commands = commands.scaled(scaleX, scaleY))

/**
 * Converts this outline's typed commands from font units to pixels.
 *
 * Legacy opaque contours are preserved unchanged.
 *
 * @param unitsPerEm Number of font units in one em.
 * @param pixelSize Requested pixel size for one em.
 * @return Outline with typed commands scaled by `pixelSize / unitsPerEm`.
 */
fun GlyphOutline.fontUnitsToPixels(unitsPerEm: Double, pixelSize: Double): GlyphOutline =
    scaled(fontUnitsToPixelsScale(unitsPerEm, pixelSize))

/**
 * Returns this outline command transformed by a TrueType composite component matrix.
 *
 * The transform is applied to every coordinate carried by the command. Close commands do not carry
 * coordinates and are returned as close commands unchanged.
 *
 * @param transform Composite affine transform to apply.
 * @return Transformed outline command.
 */
fun OutlineCommand.transformed(transform: TrueTypeCompositeTransform): OutlineCommand =
    when (this) {
        is OutlineCommand.MoveTo -> moveTo(
            transform.transformX(x, y),
            transform.transformY(x, y),
        )
        is OutlineCommand.LineTo -> lineTo(
            transform.transformX(x, y),
            transform.transformY(x, y),
        )
        is OutlineCommand.QuadraticTo -> quadraticTo(
            controlX = transform.transformX(controlX, controlY),
            controlY = transform.transformY(controlX, controlY),
            x = transform.transformX(x, y),
            y = transform.transformY(x, y),
        )
        is OutlineCommand.CubicTo -> cubicTo(
            controlX1 = transform.transformX(controlX1, controlY1),
            controlY1 = transform.transformY(controlX1, controlY1),
            controlX2 = transform.transformX(controlX2, controlY2),
            controlY2 = transform.transformY(controlX2, controlY2),
            x = transform.transformX(x, y),
            y = transform.transformY(x, y),
        )
        OutlineCommand.Close -> close()
    }

/**
 * Returns these outline commands transformed by a TrueType composite component matrix.
 *
 * @param transform Composite affine transform to apply to every coordinate-bearing command.
 * @return Transformed outline command list preserving command order.
 */
fun List<OutlineCommand>.transformed(transform: TrueTypeCompositeTransform): List<OutlineCommand> =
    map { command -> command.transformed(transform) }

private fun TrueTypeCompositeTransform.transformX(x: Double, y: Double): Double = xx * x + xy * y + dx

private fun TrueTypeCompositeTransform.transformY(x: Double, y: Double): Double = yx * x + yy * y + dy

/**
 * Computes the scalar used to convert font units to pixels.
 *
 * @param unitsPerEm Number of font units in one em. Must be positive and finite.
 * @param pixelSize Requested pixel size for one em. Must be finite.
 * @return Conversion factor `pixelSize / unitsPerEm`.
 */
fun fontUnitsToPixelsScale(unitsPerEm: Double, pixelSize: Double): Double {
    require(unitsPerEm.isFinite() && unitsPerEm > 0.0) { "unitsPerEm must be positive and finite." }
    require(pixelSize.isFinite()) { "pixelSize must be finite." }
    return pixelSize / unitsPerEm
}

/**
 * TrueType `loca` table offset encoding.
 */
enum class TrueTypeLocaFormat {
    /**
     * Short `loca` entries store glyph offsets divided by two as unsigned 16-bit values.
     */
    Short,

    /**
     * Long `loca` entries store glyph offsets as unsigned 32-bit values.
     */
    Long,
}

/**
 * Byte range for one glyph description inside the TrueType `glyf` table.
 *
 * @property start Inclusive byte offset in the `glyf` table.
 * @property endExclusive Exclusive byte offset in the `glyf` table.
 */
data class TrueTypeGlyphDataRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "glyph data range start must be non-negative." }
        require(endExclusive >= start) { "glyph data range endExclusive must be at least start." }
    }
}

/**
 * Parsed TrueType `loca` table offsets.
 *
 * The table contains `numGlyphs + 1` offsets. The final sentinel offset is needed to bound the
 * last glyph's byte range in the `glyf` table.
 *
 * @property offsets Monotonic glyph offsets in bytes.
 */
data class TrueTypeLocaTable(
    val offsets: List<Int>,
) {
    init {
        require(offsets.isNotEmpty()) { "loca offsets must include the final sentinel offset." }
        offsets.forEachIndexed { index, offset ->
            require(offset >= 0) { "loca offset $index must be non-negative." }
            if (index > 0) {
                require(offset >= offsets[index - 1]) {
                    "loca offsets must be monotonic at index $index: ${offsets[index - 1]} > $offset."
                }
            }
        }
    }

    /**
     * Returns the bounded `glyf` table range for a glyph id.
     *
     * @param glyphId Glyph id to look up.
     * @return Byte range for the glyph description. Empty ranges represent missing glyph data.
     */
    fun rangeForGlyph(glyphId: UInt): TrueTypeGlyphDataRange {
        val index = glyphId.toLong()
        require(index <= Int.MAX_VALUE) { "glyphId $glyphId does not fit Int indexing." }
        require(index >= 0 && index < offsets.lastIndex) {
            "glyphId $glyphId outside loca table glyph count ${offsets.size - 1}."
        }
        val start = offsets[index.toInt()]
        val endExclusive = offsets[index.toInt() + 1]
        return TrueTypeGlyphDataRange(start = start, endExclusive = endExclusive)
    }
}

/**
 * Bounded parser for TrueType `loca` table data.
 */
object TrueTypeLocaTableParser {
    /**
     * Parses a complete `loca` table for an explicit glyph count.
     *
     * @param data Raw `loca` table bytes.
     * @param format Offset encoding from `head.indexToLocFormat`.
     * @param numGlyphs Explicit glyph count from `maxp.numGlyphs`.
     * @return Parsed offset table with `numGlyphs + 1` entries.
     * @throws IllegalArgumentException when the table length, offset order, or offset range is invalid.
     */
    fun parse(
        data: ByteArray,
        format: TrueTypeLocaFormat,
        numGlyphs: Int,
    ): TrueTypeLocaTable {
        require(numGlyphs >= 0) { "numGlyphs must be non-negative." }
        val entrySize = when (format) {
            TrueTypeLocaFormat.Short -> 2
            TrueTypeLocaFormat.Long -> 4
        }
        val entryCount = numGlyphs + 1
        val expectedLength = entryCount * entrySize
        require(data.size == expectedLength) {
            "loca table length ${data.size} does not match expected $expectedLength bytes for " +
                "$numGlyphs glyphs in $format format."
        }

        val offsets = List(entryCount) { index ->
            val byteOffset = index * entrySize
            when (format) {
                TrueTypeLocaFormat.Short -> readUInt16(data, byteOffset) * 2
                TrueTypeLocaFormat.Long -> {
                    val offset = readUInt32(data, byteOffset)
                    require(offset <= Int.MAX_VALUE) {
                        "loca long offset $offset at index $index does not fit Int."
                    }
                    offset.toInt()
                }
            }
        }
        return TrueTypeLocaTable(offsets)
    }
}

/**
 * Shared TrueType `glyf` header fields.
 *
 * @property numberOfContours Non-negative for simple glyphs, negative for composite glyphs.
 * @property xMin Minimum x coordinate in font units.
 * @property yMin Minimum y coordinate in font units.
 * @property xMax Maximum x coordinate in font units.
 * @property yMax Maximum y coordinate in font units.
 */
data class TrueTypeGlyphHeader(
    val numberOfContours: Int,
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
)

/**
 * One decoded point from a TrueType simple glyph contour.
 *
 * @property x Absolute x coordinate in font units.
 * @property y Absolute y coordinate in font units.
 * @property onCurve Whether the point lies on the outline curve.
 * @property flags Expanded TrueType point flags with the repeat bit removed.
 */
data class TrueTypeGlyphPoint(
    val x: Int,
    val y: Int,
    val onCurve: Boolean,
    val flags: Int,
)

/**
 * Decoded contour from a TrueType simple glyph.
 *
 * @property endPointIndex Original global endpoint index from `endPtsOfContours`.
 * @property points Points belonging to this contour.
 */
data class TrueTypeGlyphContour(
    val endPointIndex: Int,
    val points: List<TrueTypeGlyphPoint>,
)

/**
 * Decoded arguments from a TrueType composite glyph component record.
 *
 * TrueType component records encode two arguments. When `ARGS_ARE_XY_VALUES` is set, the arguments
 * are signed x/y translation values in font units. When the flag is absent, the arguments are point
 * indices used for point matching. Point matching is preserved explicitly here so outline resolution
 * can align component points, and invalid indices can refuse with a precise diagnostic instead of
 * being silently treated as translations.
 */
sealed interface TrueTypeCompositeGlyphArgument {
    /**
     * Signed x/y component translation arguments.
     *
     * @property x X translation in font units.
     * @property y Y translation in font units.
     */
    data class XyValues(
        val x: Int,
        val y: Int,
    ) : TrueTypeCompositeGlyphArgument

    /**
     * Point-matching component arguments.
     *
     * @property compoundPointIndex Point index in the already-resolved compound glyph.
     * @property componentPointIndex Point index in the component glyph before alignment.
     */
    data class PointMatching(
        val compoundPointIndex: Int,
        val componentPointIndex: Int,
    ) : TrueTypeCompositeGlyphArgument
}

/**
 * Affine transform decoded from one TrueType composite glyph component record.
 *
 * The matrix follows the TrueType component transform equation:
 * `x' = xx * x + xy * y + dx`, `y' = yx * x + yy * y + dy`. The default value is
 * the identity transform. Translation is populated for `ARGS_ARE_XY_VALUES` components. Point
 * matching components keep the decoded transform offset at zero; recursive composite resolution
 * computes the final alignment translation from resolved TrueType point coordinates.
 *
 * @property xx X contribution from the source x coordinate.
 * @property xy X contribution from the source y coordinate.
 * @property yx Y contribution from the source x coordinate.
 * @property yy Y contribution from the source y coordinate.
 * @property dx X translation in font units.
 * @property dy Y translation in font units.
 */
data class TrueTypeCompositeTransform(
    val xx: Double = 1.0,
    val xy: Double = 0.0,
    val yx: Double = 0.0,
    val yy: Double = 1.0,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
) {
    init {
        require(xx.isFinite()) { "xx must be finite." }
        require(xy.isFinite()) { "xy must be finite." }
        require(yx.isFinite()) { "yx must be finite." }
        require(yy.isFinite()) { "yy must be finite." }
        require(dx.isFinite()) { "dx must be finite." }
        require(dy.isFinite()) { "dy must be finite." }
    }
}

/**
 * One decoded TrueType composite glyph component record.
 *
 * Component records identify another glyph, supply either translation or point-matching arguments,
 * and optionally carry one of the TrueType F2Dot14 scale encodings: uniform scale, independent x/y
 * scale, or a full two-by-two matrix. Raw flags are preserved so callers can inspect policy bits
 * such as `USE_MY_METRICS`, `OVERLAP_COMPOUND`, or offset-scaling hints without reparsing bytes.
 *
 * @property glyphId Glyph id of the referenced component glyph.
 * @property flags Raw 16-bit component flags.
 * @property arguments Decoded component arguments.
 * @property transform Affine transform derived from scale flags plus x/y translation arguments.
 */
data class TrueTypeCompositeGlyphComponent(
    val glyphId: UInt,
    val flags: Int,
    val arguments: TrueTypeCompositeGlyphArgument,
    val transform: TrueTypeCompositeTransform,
) {
    init {
        require(flags in 0..0xffff) { "composite component flags must be a 16-bit unsigned value." }
    }
}

/**
 * Parsed TrueType `glyf` table entry.
 */
sealed interface TrueTypeGlyph {
    /**
     * Glyph with an empty `loca` range.
     */
    data object Empty : TrueTypeGlyph

    /**
     * Simple TrueType glyph decoded without executing hinting instructions.
     *
     * @property header Shared glyph header.
     * @property endPointsOfContours Original `endPtsOfContours` values.
     * @property instructionLength Number of skipped TrueType instruction bytes.
     * @property contours Decoded contours and points.
     */
    data class Simple(
        val header: TrueTypeGlyphHeader,
        val endPointsOfContours: List<Int>,
        val instructionLength: Int,
        val contours: List<TrueTypeGlyphContour>,
    ) : TrueTypeGlyph

    /**
     * Composite TrueType glyph decoded into component records.
     *
     * Component records are parsed without executing TrueType instructions. The raw bytes following
     * the glyph header are retained for diagnostics and future policy checks; `componentData`
     * equality follows `ByteArray` referential equality rather than content equality.
     *
     * @property header Shared glyph header.
     * @property componentData Raw bytes following the composite glyph header.
     * @property components Parsed component records in draw order.
     * @property instructionLength Number of skipped composite instruction bytes.
     * @property diagnostic Human-readable note about unsupported TrueType VM execution.
     */
    data class Composite(
        val header: TrueTypeGlyphHeader,
        val componentData: ByteArray,
        val components: List<TrueTypeCompositeGlyphComponent> = emptyList(),
        val instructionLength: Int = 0,
        val diagnostic: String = "Composite TrueType glyph instructions are not executed.",
    ) : TrueTypeGlyph
}

/**
 * Bounded parser for TrueType `glyf` table glyph descriptions.
 */
object TrueTypeGlyfTableParser {
    /**
     * Parses one glyph using a `loca`-derived range.
     *
     * @param glyfTable Complete raw `glyf` table bytes.
     * @param loca Parsed `loca` table.
     * @param glyphId Glyph id to parse.
     * @return Parsed glyph model.
     * @throws IllegalArgumentException when the glyph range or glyph data is invalid.
     */
    fun parseGlyph(
        glyfTable: ByteArray,
        loca: TrueTypeLocaTable,
        glyphId: UInt,
    ): TrueTypeGlyph = parseGlyph(
        glyfTable = glyfTable,
        range = loca.rangeForGlyph(glyphId),
        glyphId = glyphId,
    )

    /**
     * Parses one glyph using an explicit bounded range.
     *
     * @param glyfTable Complete raw `glyf` table bytes.
     * @param range Byte range for the glyph description.
     * @param glyphId Glyph id used for diagnostics.
     * @return Parsed glyph model.
     * @throws IllegalArgumentException when the range, header, flags, coordinate data, or composite
     * component records are invalid.
     * @throws FontScalerRefusalException when a composite glyph exceeds parser safety bounds.
     */
    fun parseGlyph(
        glyfTable: ByteArray,
        range: TrueTypeGlyphDataRange,
        glyphId: UInt,
    ): TrueTypeGlyph {
        require(range.endExclusive <= glyfTable.size) {
            "glyf glyph $glyphId range $range is outside glyf table length ${glyfTable.size}."
        }
        if (range.start == range.endExclusive) {
            return TrueTypeGlyph.Empty
        }

        val reader = GlyfReader(
            data = glyfTable,
            start = range.start,
            endExclusive = range.endExclusive,
            glyphId = glyphId,
        )
        val header = TrueTypeGlyphHeader(
            numberOfContours = reader.readInt16("header"),
            xMin = reader.readInt16("header"),
            yMin = reader.readInt16("header"),
            xMax = reader.readInt16("header"),
            yMax = reader.readInt16("header"),
        )

        return if (header.numberOfContours >= 0) {
            parseSimpleGlyph(reader, header)
        } else {
            val componentData = glyfTable.copyOfRange(reader.offset, range.endExclusive)
            parseCompositeGlyph(
                header = header,
                componentData = componentData,
                reader = reader,
            )
        }
    }

    private fun parseSimpleGlyph(
        reader: GlyfReader,
        header: TrueTypeGlyphHeader,
    ): TrueTypeGlyph.Simple {
        val endPointsOfContours = List(header.numberOfContours) {
            reader.readUInt16("endPtsOfContours")
        }
        endPointsOfContours.forEachIndexed { index, endPoint ->
            if (index > 0) {
                require(endPoint > endPointsOfContours[index - 1]) {
                    "glyf glyph ${reader.glyphId} endPtsOfContours must be monotonic."
                }
            }
        }

        val instructionLength = reader.readUInt16("instructionLength")
        reader.skip(instructionLength, "instructions")

        val pointCount = endPointsOfContours.lastOrNull()?.plus(1) ?: 0
        val flags = reader.readFlags(pointCount)
        val xs = reader.readCoordinates(flags, axis = CoordinateAxis.X)
        val ys = reader.readCoordinates(flags, axis = CoordinateAxis.Y)
        val points = flags.indices.map { index ->
            TrueTypeGlyphPoint(
                x = xs[index],
                y = ys[index],
                onCurve = flags[index] and FLAG_ON_CURVE != 0,
                flags = flags[index],
            )
        }

        var startIndex = 0
        val contours = endPointsOfContours.map { endPoint ->
            val contourPoints = points.subList(startIndex, endPoint + 1)
            startIndex = endPoint + 1
            TrueTypeGlyphContour(
                endPointIndex = endPoint,
                points = contourPoints,
            )
        }

        return TrueTypeGlyph.Simple(
            header = header,
            endPointsOfContours = endPointsOfContours,
            instructionLength = instructionLength,
            contours = contours,
        )
    }

    private fun parseCompositeGlyph(
        header: TrueTypeGlyphHeader,
        componentData: ByteArray,
        reader: GlyfReader,
    ): TrueTypeGlyph.Composite {
        val components = mutableListOf<TrueTypeCompositeGlyphComponent>()
        var flags: Int
        do {
            if (components.size >= MAX_COMPOSITE_GLYPH_COMPONENTS) {
                throw FontScalerRefusalException(
                    diagnostic = FontScalerDiagnostic(
                        code = FontScalerDiagnosticCodes.OUTLINE_FORMAT_UNSUPPORTED,
                        detail = "truetype.composite-component-count",
                        operation = "parse",
                        glyphId = reader.glyphId,
                    ),
                    message = "composite glyphId ${reader.glyphId} component count cap " +
                        "$MAX_COMPOSITE_GLYPH_COMPONENTS exceeded.",
                )
            }
            flags = reader.readUInt16("composite component flags")
            val componentGlyphId = reader.readUInt16("composite component glyph id").toUInt()
            val arguments = reader.readCompositeArguments(flags)
            val transform = reader.readCompositeTransform(flags, arguments)
            components += TrueTypeCompositeGlyphComponent(
                glyphId = componentGlyphId,
                flags = flags,
                arguments = arguments,
                transform = transform,
            )
        } while (flags and COMPOSITE_MORE_COMPONENTS != 0)

        val instructionLength = if (flags and COMPOSITE_WE_HAVE_INSTRUCTIONS != 0) {
            val length = reader.readUInt16("composite instructionLength")
            reader.skip(length, "composite instructions")
            length
        } else {
            0
        }

        return TrueTypeGlyph.Composite(
            header = header,
            componentData = componentData,
            components = components,
            instructionLength = instructionLength,
        )
    }

    private fun GlyfReader.readCompositeArguments(flags: Int): TrueTypeCompositeGlyphArgument {
        val argumentsAreWords = flags and COMPOSITE_ARG_1_AND_2_ARE_WORDS != 0
        val argumentsAreXyValues = flags and COMPOSITE_ARGS_ARE_XY_VALUES != 0
        return when {
            argumentsAreWords && argumentsAreXyValues -> TrueTypeCompositeGlyphArgument.XyValues(
                x = readInt16("composite xy argument"),
                y = readInt16("composite xy argument"),
            )
            argumentsAreWords -> TrueTypeCompositeGlyphArgument.PointMatching(
                compoundPointIndex = readUInt16("composite point argument"),
                componentPointIndex = readUInt16("composite point argument"),
            )
            argumentsAreXyValues -> TrueTypeCompositeGlyphArgument.XyValues(
                x = readInt8("composite xy argument"),
                y = readInt8("composite xy argument"),
            )
            else -> TrueTypeCompositeGlyphArgument.PointMatching(
                compoundPointIndex = readUInt8("composite point argument"),
                componentPointIndex = readUInt8("composite point argument"),
            )
        }
    }

    private fun GlyfReader.readCompositeTransform(
        flags: Int,
        arguments: TrueTypeCompositeGlyphArgument,
    ): TrueTypeCompositeTransform {
        val hasUniformScale = flags and COMPOSITE_WE_HAVE_A_SCALE != 0
        val hasXAndYScale = flags and COMPOSITE_WE_HAVE_AN_X_AND_Y_SCALE != 0
        val hasTwoByTwo = flags and COMPOSITE_WE_HAVE_A_TWO_BY_TWO != 0
        val scaleFlagCount = listOf(hasUniformScale, hasXAndYScale, hasTwoByTwo).count { it }
        require(scaleFlagCount <= 1) {
            "glyf glyph $glyphId composite component has mutually exclusive scale flags set."
        }

        var xx = 1.0
        var xy = 0.0
        var yx = 0.0
        var yy = 1.0
        when {
            hasUniformScale -> {
                val scale = readF2Dot14("composite uniform scale")
                xx = scale
                yy = scale
            }
            hasXAndYScale -> {
                xx = readF2Dot14("composite x scale")
                yy = readF2Dot14("composite y scale")
            }
            hasTwoByTwo -> {
                val xScale = readF2Dot14("composite two-by-two x scale")
                val scale01 = readF2Dot14("composite two-by-two scale01")
                val scale10 = readF2Dot14("composite two-by-two scale10")
                val yScale = readF2Dot14("composite two-by-two y scale")
                xx = xScale
                xy = scale10
                yx = scale01
                yy = yScale
            }
        }

        val dx: Double
        val dy: Double
        when (arguments) {
            is TrueTypeCompositeGlyphArgument.XyValues -> {
                val rawDx = arguments.x.toDouble()
                val rawDy = arguments.y.toDouble()
                val scaledOffset = flags and COMPOSITE_SCALED_COMPONENT_OFFSET != 0
                val unscaledOffset = flags and COMPOSITE_UNSCALED_COMPONENT_OFFSET != 0
                if (scaledOffset && !unscaledOffset) {
                    dx = xx * rawDx + xy * rawDy
                    dy = yx * rawDx + yy * rawDy
                } else {
                    dx = rawDx
                    dy = rawDy
                }
            }
            is TrueTypeCompositeGlyphArgument.PointMatching -> {
                dx = 0.0
                dy = 0.0
            }
        }
        return TrueTypeCompositeTransform(
            xx = xx,
            xy = xy,
            yx = yx,
            yy = yy,
            dx = dx,
            dy = dy,
        )
    }
}

/**
 * Converts a TrueType simple glyph into typed outline commands.
 *
 * Consecutive off-curve points are supported with the TrueType implicit-midpoint rule. Hinting
 * instructions are not executed, so coordinates are the raw design-space glyph coordinates.
 *
 * @param glyphId Glyph id for the returned outline.
 * @return Glyph outline containing typed commands and no legacy opaque contours.
 */
fun TrueTypeGlyph.Simple.toGlyphOutline(glyphId: UInt): GlyphOutline {
    return toGlyphOutline(glyphId = glyphId, variationDeltas = null)
}

private fun TrueTypeGlyph.Simple.toGlyphOutline(
    glyphId: UInt,
    variationDeltas: TrueTypeGlyphVariationDeltas?,
): GlyphOutline {
    val commands = buildList {
        var firstContourPointIndex = 0
        for (contour in contours) {
            addContourCommands(
                points = contour.points,
                firstPointIndex = firstContourPointIndex,
                variationDeltas = variationDeltas,
            )
            firstContourPointIndex += contour.points.size
        }
    }
    return GlyphOutline(glyphId = glyphId, commands = commands)
}

/**
 * Per-point variation deltas decoded from a bounded subset of a TrueType `gvar` glyph record.
 *
 * The arrays contain outline-point deltas plus the bounded phantom-point deltas needed for
 * horizontal advance adjustment.
 */
class TrueTypeGlyphVariationDeltas internal constructor(
    xDeltas: DoubleArray,
    yDeltas: DoubleArray,
    phantomXDeltas: DoubleArray = DoubleArray(PHANTOM_POINT_COUNT),
    phantomYDeltas: DoubleArray = DoubleArray(PHANTOM_POINT_COUNT),
    explicitPoints: BooleanArray = BooleanArray(xDeltas.size),
    inferredPoints: BooleanArray = BooleanArray(xDeltas.size),
) {
    private val xDeltas: DoubleArray = xDeltas.copyOf()
    private val yDeltas: DoubleArray = yDeltas.copyOf()
    private val phantomXDeltas: DoubleArray = phantomXDeltas.copyOf()
    private val phantomYDeltas: DoubleArray = phantomYDeltas.copyOf()
    private val explicitPoints: BooleanArray = explicitPoints.copyOf()
    private val inferredPoints: BooleanArray = inferredPoints.copyOf()

    init {
        require(xDeltas.size == yDeltas.size) { "gvar x and y delta counts must match." }
        require(phantomXDeltas.size == PHANTOM_POINT_COUNT) {
            "gvar phantom x delta count must match phantom point count."
        }
        require(phantomYDeltas.size == PHANTOM_POINT_COUNT) {
            "gvar phantom y delta count must match phantom point count."
        }
        require(xDeltas.size == explicitPoints.size) { "gvar explicit point flags must match delta count." }
        require(xDeltas.size == inferredPoints.size) { "gvar inferred point flags must match delta count." }
    }

    /**
     * Number of outline points covered by these deltas, excluding phantom points.
     */
    val pointCount: Int
        get() = xDeltas.size

    internal fun xDelta(pointIndex: Int): Double = xDeltas.getOrElse(pointIndex) { 0.0 }

    internal fun yDelta(pointIndex: Int): Double = yDeltas.getOrElse(pointIndex) { 0.0 }

    internal fun phantomXDelta(phantomPointIndex: Int): Double = phantomXDeltas.getOrElse(phantomPointIndex) { 0.0 }

    internal fun phantomYDelta(phantomPointIndex: Int): Double = phantomYDeltas.getOrElse(phantomPointIndex) { 0.0 }

    internal fun isExplicit(pointIndex: Int): Boolean = explicitPoints.getOrElse(pointIndex) { false }

    internal fun isInferred(pointIndex: Int): Boolean = inferredPoints.getOrElse(pointIndex) { false }
}

internal data class TrueTypeGvarSimpleGlyphDeltaResult(
    val deltas: TrueTypeGlyphVariationDeltas? = null,
    val requiresIupInterpolation: Boolean = false,
    val variationDataMalformed: Boolean = false,
)

private data class ResolvedTuplePointDeltas(
    val xDeltas: DoubleArray,
    val yDeltas: DoubleArray,
    val explicitPoints: BooleanArray,
    val inferredPoints: BooleanArray,
)

/**
 * Bounded model for the TrueType `gvar` table subset used by the pure Kotlin font scaler.
 *
 * Supported data is deliberately narrow: glyph variation records with embedded peak tuples,
 * shared/private point sets, packed x/y deltas, simple-glyph IUP interpolation, and optional
 * intermediate regions. Shared peak tuples are parsed and may be referenced, but composite-glyph
 * deltas, phantom point metrics, `avar` remapping, and TrueType instruction interaction are not
 * implemented here. Malformed or unsupported per-glyph records return no deltas instead of
 * escaping with an unchecked bounds failure; malformed table headers and offset directories fail
 * eagerly with clear diagnostics from [parse].
 */
class TrueTypeGvarTable private constructor(
    private val data: ByteArray,
    val axisCount: Int,
    private val sharedTuples: List<DoubleArray>,
    private val glyphOffsets: IntArray,
    private val glyphDataStart: Int,
) {
    /**
     * Number of glyph variation records advertised by this table.
     */
    val glyphCount: Int
        get() = glyphOffsets.size - 1

    /**
     * Decodes scaled deltas for one simple glyph at normalized variation coordinates.
     *
     * @param glyphId Glyph id whose `gvar` record should be read.
     * @param pointCount Number of simple glyph outline points, excluding phantom points.
     * @param normalizedCoordinates Coordinates in the same axis order used to parse this table.
     * @return Outline-point deltas, or null when the glyph has no usable record for this bounded
     * implementation.
     */
    fun simpleGlyphDeltas(
        glyphId: UInt,
        glyph: TrueTypeGlyph.Simple,
        normalizedCoordinates: List<Double>,
    ): TrueTypeGlyphVariationDeltas? =
        simpleGlyphDeltaResult(
            glyphId = glyphId,
            glyph = glyph,
            normalizedCoordinates = normalizedCoordinates,
        ).deltas

    internal fun simpleGlyphDeltaResult(
        glyphId: UInt,
        glyph: TrueTypeGlyph.Simple,
        normalizedCoordinates: List<Double>,
    ): TrueTypeGvarSimpleGlyphDeltaResult {
        fun unavailable(): TrueTypeGvarSimpleGlyphDeltaResult = TrueTypeGvarSimpleGlyphDeltaResult()
        fun malformed(): TrueTypeGvarSimpleGlyphDeltaResult = TrueTypeGvarSimpleGlyphDeltaResult(
            variationDataMalformed = true,
        )

        val glyphPoints = glyph.contours.flatMap { contour -> contour.points }
        val pointCount = glyphPoints.size
        if (pointCount <= 0) {
            return unavailable()
        }
        val glyphIndex = glyphId.toLong()
        if (glyphIndex < 0 || glyphIndex + 1 >= glyphOffsets.size || glyphIndex > Int.MAX_VALUE) {
            return unavailable()
        }
        val maxPointCount = pointCount.checkedPlusPhantomCount() ?: return unavailable()
        val start = glyphDataStart.checkedPlus(glyphOffsets[glyphIndex.toInt()]) ?: return unavailable()
        val end = glyphDataStart.checkedPlus(glyphOffsets[glyphIndex.toInt() + 1]) ?: return unavailable()
        if (start == end) {
            return unavailable()
        }
        if (!data.fitsGvar(start, 4, end)) {
            return malformed()
        }

        val tupleVariationCountField = data.readUInt16OrNull(start, end) ?: return malformed()
        val tupleVariationCount = tupleVariationCountField and GVAR_TUPLE_COUNT_MASK
        if (tupleVariationCount <= 0) {
            return malformed()
        }
        val offsetToData = data.readUInt16OrNull(start + 2, end) ?: return malformed()
        val tupleDataStart = start.checkedPlus(offsetToData) ?: return malformed()
        if (tupleDataStart < start || tupleDataStart > end) {
            return malformed()
        }

        val headers = ArrayList<TrueTypeGvarTupleHeader>(tupleVariationCount)
        var headerOffset = start + 4
        repeat(tupleVariationCount) {
            if (!data.fitsGvar(headerOffset, 4, end)) {
                return malformed()
            }
            val variationDataSize = data.readUInt16OrNull(headerOffset, end) ?: return malformed()
            val tupleIndex = data.readUInt16OrNull(headerOffset + 2, end) ?: return malformed()
            headerOffset += 4
            val peak = when {
                tupleIndex and GVAR_EMBEDDED_PEAK_TUPLE != 0 -> {
                    if (!data.fitsGvar(headerOffset, axisCount * 2, end)) {
                        return malformed()
                    }
                    DoubleArray(axisCount) { axis ->
                        data.readF2Dot14OrNull(headerOffset + axis * 2, end) ?: return malformed()
                    }.also {
                        headerOffset += axisCount * 2
                    }
                }
                else -> sharedTuples.getOrNull(tupleIndex and GVAR_TUPLE_INDEX_MASK) ?: return malformed()
            }
            val startTuple: DoubleArray?
            val endTuple: DoubleArray?
            if (tupleIndex and GVAR_INTERMEDIATE_REGION != 0) {
                if (!data.fitsGvar(headerOffset, axisCount * 4, end)) {
                    return malformed()
                }
                startTuple = DoubleArray(axisCount) { axis ->
                    data.readF2Dot14OrNull(headerOffset + axis * 2, end) ?: return malformed()
                }
                headerOffset += axisCount * 2
                endTuple = DoubleArray(axisCount) { axis ->
                    data.readF2Dot14OrNull(headerOffset + axis * 2, end) ?: return malformed()
                }
                headerOffset += axisCount * 2
            } else {
                startTuple = null
                endTuple = null
            }
            headers += TrueTypeGvarTupleHeader(
                variationDataSize = variationDataSize,
                tupleIndex = tupleIndex,
                peak = peak,
                startTuple = startTuple,
                endTuple = endTuple,
            )
        }
        if (tupleDataStart < headerOffset || tupleDataStart > end) {
            return malformed()
        }

        var dataOffset = tupleDataStart
        val sharedPoints = if (tupleVariationCountField and GVAR_SHARED_POINT_NUMBERS != 0) {
            val points = readPackedGvarPoints(
                offset = dataOffset,
                maxPointCount = maxPointCount,
                limit = end,
            ) ?: return malformed()
            dataOffset = points.nextOffset
            points.values
        } else {
            null
        }

        val xDeltas = DoubleArray(maxPointCount)
        val yDeltas = DoubleArray(maxPointCount)
        val explicitPoints = BooleanArray(maxPointCount)
        val inferredPoints = BooleanArray(maxPointCount)
        for (header in headers) {
            val tupleEnd = dataOffset.checkedPlus(header.variationDataSize) ?: return malformed()
            if (tupleEnd > end) {
                return malformed()
            }
            var tupleDataOffset = dataOffset
            val privatePoints = if (header.tupleIndex and GVAR_PRIVATE_POINT_NUMBERS != 0) {
                val points = readPackedGvarPoints(
                    offset = tupleDataOffset,
                    maxPointCount = maxPointCount,
                    limit = tupleEnd,
                ) ?: return malformed()
                tupleDataOffset = points.nextOffset
                points.values
            } else {
                null
            }
            val targetPoints = privatePoints ?: sharedPoints ?: IntArray(maxPointCount) { it }
            val tupleXDeltas = readPackedGvarDeltas(
                offset = tupleDataOffset,
                count = targetPoints.size,
                limit = tupleEnd,
            ) ?: return malformed()
            tupleDataOffset = tupleXDeltas.nextOffset
            val tupleYDeltas = readPackedGvarDeltas(
                offset = tupleDataOffset,
                count = targetPoints.size,
                limit = tupleEnd,
            ) ?: return malformed()
            if (tupleYDeltas.nextOffset > tupleEnd) {
                return malformed()
            }

            val scalar = tupleScalar(
                normalizedCoordinates = normalizedCoordinates,
                peak = header.peak,
                startTuple = header.startTuple,
                endTuple = header.endTuple,
            )
            if (scalar != 0.0) {
                val tuplePointDeltas = resolveTuplePointDeltas(
                    glyphPoints = glyphPoints,
                    contourEndPoints = glyph.endPointsOfContours,
                    pointCount = pointCount,
                    maxPointCount = maxPointCount,
                    targetPoints = targetPoints,
                    tupleXDeltas = tupleXDeltas.values,
                    tupleYDeltas = tupleYDeltas.values,
                )
                for (pointIndex in 0 until maxPointCount) {
                    xDeltas[pointIndex] += tuplePointDeltas.xDeltas[pointIndex] * scalar
                    yDeltas[pointIndex] += tuplePointDeltas.yDeltas[pointIndex] * scalar
                    explicitPoints[pointIndex] = explicitPoints[pointIndex] || tuplePointDeltas.explicitPoints[pointIndex]
                    inferredPoints[pointIndex] = inferredPoints[pointIndex] || tuplePointDeltas.inferredPoints[pointIndex]
                }
            }
            dataOffset = tupleEnd
        }

        return TrueTypeGvarSimpleGlyphDeltaResult(
            deltas = TrueTypeGlyphVariationDeltas(
                xDeltas = xDeltas.copyOfRange(0, pointCount),
                yDeltas = yDeltas.copyOfRange(0, pointCount),
                phantomXDeltas = xDeltas.copyOfRange(pointCount, maxPointCount),
                phantomYDeltas = yDeltas.copyOfRange(pointCount, maxPointCount),
                explicitPoints = explicitPoints.copyOfRange(0, pointCount),
                inferredPoints = inferredPoints.copyOfRange(0, pointCount),
            ),
        )
    }

    private fun resolveTuplePointDeltas(
        glyphPoints: List<TrueTypeGlyphPoint>,
        contourEndPoints: List<Int>,
        pointCount: Int,
        maxPointCount: Int,
        targetPoints: IntArray,
        tupleXDeltas: IntArray,
        tupleYDeltas: IntArray,
    ): ResolvedTuplePointDeltas {
        val resolvedXDeltas = DoubleArray(maxPointCount)
        val resolvedYDeltas = DoubleArray(maxPointCount)
        val explicitPoints = BooleanArray(maxPointCount)
        val inferredPoints = BooleanArray(maxPointCount)

        for (index in targetPoints.indices) {
            val point = targetPoints[index]
            if (point in 0 until maxPointCount) {
                resolvedXDeltas[point] = tupleXDeltas[index].toDouble()
                resolvedYDeltas[point] = tupleYDeltas[index].toDouble()
                explicitPoints[point] = true
            }
        }
        if (targetPoints.isCompleteGvarPointSet(maxPointCount)) {
            return ResolvedTuplePointDeltas(
                xDeltas = resolvedXDeltas,
                yDeltas = resolvedYDeltas,
                explicitPoints = explicitPoints,
                inferredPoints = inferredPoints,
            )
        }

        var contourStart = 0
        for (contourEnd in contourEndPoints) {
            val referencedPoints = (contourStart..contourEnd).filter { pointIndex -> explicitPoints[pointIndex] }
            if (referencedPoints.isEmpty() || referencedPoints.size == contourEnd - contourStart + 1) {
                contourStart = contourEnd + 1
                continue
            }
            for (pointIndex in contourStart..contourEnd) {
                if (explicitPoints[pointIndex]) {
                    continue
                }
                val precedingPointIndex = referencedPoints.lastOrNull { referencedPoint -> referencedPoint < pointIndex }
                    ?: referencedPoints.last()
                val followingPointIndex = referencedPoints.firstOrNull { referencedPoint -> referencedPoint > pointIndex }
                    ?: referencedPoints.first()
                resolvedXDeltas[pointIndex] = inferUntouchedPointDelta(
                    targetCoordinate = glyphPoints[pointIndex].x.toDouble(),
                    precedingCoordinate = glyphPoints[precedingPointIndex].x.toDouble(),
                    precedingDelta = resolvedXDeltas[precedingPointIndex],
                    followingCoordinate = glyphPoints[followingPointIndex].x.toDouble(),
                    followingDelta = resolvedXDeltas[followingPointIndex],
                )
                resolvedYDeltas[pointIndex] = inferUntouchedPointDelta(
                    targetCoordinate = glyphPoints[pointIndex].y.toDouble(),
                    precedingCoordinate = glyphPoints[precedingPointIndex].y.toDouble(),
                    precedingDelta = resolvedYDeltas[precedingPointIndex],
                    followingCoordinate = glyphPoints[followingPointIndex].y.toDouble(),
                    followingDelta = resolvedYDeltas[followingPointIndex],
                )
                inferredPoints[pointIndex] = true
            }
            contourStart = contourEnd + 1
        }

        return ResolvedTuplePointDeltas(
            xDeltas = resolvedXDeltas,
            yDeltas = resolvedYDeltas,
            explicitPoints = explicitPoints,
            inferredPoints = inferredPoints,
        )
    }

    private fun inferUntouchedPointDelta(
        targetCoordinate: Double,
        precedingCoordinate: Double,
        precedingDelta: Double,
        followingCoordinate: Double,
        followingDelta: Double,
    ): Double {
        if (precedingCoordinate == followingCoordinate) {
            return if (precedingDelta == followingDelta) precedingDelta else 0.0
        }
        val minimumCoordinate = min(precedingCoordinate, followingCoordinate)
        val maximumCoordinate = max(precedingCoordinate, followingCoordinate)
        return when {
            targetCoordinate <= minimumCoordinate -> {
                if (precedingCoordinate < followingCoordinate) precedingDelta else followingDelta
            }
            targetCoordinate >= maximumCoordinate -> {
                if (precedingCoordinate > followingCoordinate) precedingDelta else followingDelta
            }
            else -> {
                val proportion = (targetCoordinate - precedingCoordinate) / (followingCoordinate - precedingCoordinate)
                ((1.0 - proportion) * precedingDelta) + (proportion * followingDelta)
            }
        }
    }

    private fun readPackedGvarPoints(
        offset: Int,
        maxPointCount: Int,
        limit: Int,
    ): PackedGvarInts? {
        var currentOffset = offset
        val first = data.readUInt8OrNull(currentOffset, limit) ?: return null
        currentOffset += 1
        if (first == 0) {
            return PackedGvarInts(IntArray(maxPointCount) { it }, currentOffset)
        }

        val pointCount = if (first and 0x80 != 0) {
            val second = data.readUInt8OrNull(currentOffset, limit) ?: return null
            currentOffset += 1
            ((first and 0x7f) shl 8) or second
        } else {
            first
        }
        if (pointCount < 0 || pointCount > maxPointCount) {
            return null
        }

        val points = IntArray(pointCount)
        var pointIndex = 0
        var point = 0
        while (pointIndex < pointCount) {
            val control = data.readUInt8OrNull(currentOffset, limit) ?: return null
            currentOffset += 1
            val wordDeltas = control and 0x80 != 0
            val runCount = (control and 0x7f) + 1
            if (pointIndex + runCount > pointCount) {
                return null
            }
            repeat(runCount) {
                val delta = if (wordDeltas) {
                    val value = data.readUInt16OrNull(currentOffset, limit) ?: return null
                    currentOffset += 2
                    value
                } else {
                    val value = data.readUInt8OrNull(currentOffset, limit) ?: return null
                    currentOffset += 1
                    value
                }
                point += delta
                if (point >= maxPointCount) {
                    return null
                }
                points[pointIndex] = point
                pointIndex += 1
            }
        }
        return PackedGvarInts(points, currentOffset)
    }

    private fun IntArray.isCompleteGvarPointSet(maxPointCount: Int): Boolean =
        size == maxPointCount && indices.all { index -> this[index] == index }

    private fun readPackedGvarDeltas(
        offset: Int,
        count: Int,
        limit: Int,
    ): PackedGvarInts? {
        val values = IntArray(count)
        var currentOffset = offset
        var index = 0
        while (index < count) {
            val control = data.readUInt8OrNull(currentOffset, limit) ?: return null
            currentOffset += 1
            val runCount = (control and 0x3f) + 1
            if (index + runCount > count) {
                return null
            }
            when {
                control and 0x80 != 0 -> repeat(runCount) {
                    values[index] = 0
                    index += 1
                }
                control and 0x40 != 0 -> repeat(runCount) {
                    values[index] = data.readInt16OrNull(currentOffset, limit) ?: return null
                    currentOffset += 2
                    index += 1
                }
                else -> repeat(runCount) {
                    values[index] = data.readInt8OrNull(currentOffset, limit) ?: return null
                    currentOffset += 1
                    index += 1
                }
            }
        }
        return PackedGvarInts(values, currentOffset)
    }

    private fun tupleScalar(
        normalizedCoordinates: List<Double>,
        peak: DoubleArray,
        startTuple: DoubleArray?,
        endTuple: DoubleArray?,
    ): Double {
        var scalar = 1.0
        for (axis in 0 until axisCount) {
            val coordinate = normalizedCoordinates.getOrElse(axis) { 0.0 }
            val peakValue = peak[axis]
            if (peakValue == 0.0) {
                continue
            }
            val axisScalar = if (startTuple != null && endTuple != null) {
                val start = startTuple[axis]
                val end = endTuple[axis]
                when {
                    coordinate < start || coordinate > end || start > peakValue || peakValue > end -> 0.0
                    coordinate == peakValue -> 1.0
                    coordinate < peakValue -> (coordinate - start) / (peakValue - start)
                    else -> (end - coordinate) / (end - peakValue)
                }
            } else {
                when {
                    coordinate == 0.0 -> 0.0
                    !sameVariationDirection(coordinate, peakValue) -> 0.0
                    abs(coordinate) > abs(peakValue) -> 0.0
                    else -> coordinate / peakValue
                }
            }
            scalar *= axisScalar
            if (scalar == 0.0) {
                return 0.0
            }
        }
        return scalar
    }

    companion object {
        /**
         * Parses a complete `gvar` table for a known axis and glyph count.
         *
         * @param data Raw `gvar` table bytes beginning at the table header.
         * @param axisCount Number of normalized axes used by tuple coordinates.
         * @param glyphCount Number of glyphs advertised by `maxp.numGlyphs`.
         * @return Parsed `gvar` model.
         * @throws IllegalArgumentException when the table header, shared tuples, offset directory,
         * or glyph data bounds are malformed or unsupported.
         */
        fun parse(
            data: ByteArray,
            axisCount: Int,
            glyphCount: Int,
        ): TrueTypeGvarTable {
            require(axisCount > 0) { "gvar axisCount must be positive." }
            require(glyphCount >= 0) { "gvar glyphCount must be non-negative." }
            val reader = TrueTypeGvarReader(data = data)
            reader.requireAvailable(offset = 0, byteCount = 20, section = "header")

            val majorVersion = reader.readUInt16(offset = 0, section = "header")
            val minorVersion = reader.readUInt16(offset = 2, section = "header")
            require(majorVersion == 1 && minorVersion == 0) {
                "gvar version must be 1.0, found $majorVersion.$minorVersion."
            }
            val parsedAxisCount = reader.readUInt16(offset = 4, section = "header")
            require(parsedAxisCount == axisCount) {
                "gvar axisCount $parsedAxisCount does not match expected $axisCount."
            }
            val sharedTupleCount = reader.readUInt16(offset = 6, section = "header")
            val sharedTupleOffset = reader.readUInt32AsInt(offset = 8, section = "header")
            val parsedGlyphCount = reader.readUInt16(offset = 12, section = "header")
            require(parsedGlyphCount == glyphCount) {
                "gvar glyphCount $parsedGlyphCount does not match expected $glyphCount."
            }
            val flags = reader.readUInt16(offset = 14, section = "header")
            val glyphDataOffset = reader.readUInt32AsInt(offset = 16, section = "header")

            val sharedTupleByteCount = checkedTableByteCount(
                count = sharedTupleCount.toLong(),
                itemSize = axisCount.toLong() * 2L,
                section = "shared tuples",
            )
            val sharedTuples = if (sharedTupleCount == 0) {
                emptyList()
            } else {
                reader.requireAvailable(
                    offset = sharedTupleOffset,
                    byteCount = sharedTupleByteCount,
                    section = "shared tuples",
                )
                List(sharedTupleCount) { tupleIndex ->
                    DoubleArray(axisCount) { axis ->
                        reader.readF2Dot14(
                            offset = sharedTupleOffset + tupleIndex * axisCount * 2 + axis * 2,
                            section = "shared tuples",
                        )
                    }
                }
            }

            val longOffsets = flags and GVAR_LONG_OFFSETS != 0
            val offsetEntrySize = if (longOffsets) 4 else 2
            val offsetByteCount = checkedTableByteCount(
                count = glyphCount.toLong() + 1L,
                itemSize = offsetEntrySize.toLong(),
                section = "glyph variation offsets",
            )
            val offsetsStart = 20
            reader.requireAvailable(
                offset = offsetsStart,
                byteCount = offsetByteCount,
                section = "glyph variation offsets",
            )
            val offsets = IntArray(glyphCount + 1) { index ->
                val offset = offsetsStart + index * offsetEntrySize
                if (longOffsets) {
                    reader.readUInt32AsInt(offset = offset, section = "glyph variation offsets")
                } else {
                    reader.readUInt16(offset = offset, section = "glyph variation offsets") * 2
                }
            }
            for (index in 0 until offsets.lastIndex) {
                require(offsets[index] <= offsets[index + 1]) {
                    "gvar glyph variation offsets must be monotonic at index $index: " +
                        "${offsets[index]} > ${offsets[index + 1]}."
                }
            }

            reader.requireAvailable(offset = glyphDataOffset, byteCount = 0, section = "glyph variation data")
            val finalGlyphDataLength = offsets.last()
            reader.requireAvailable(
                offset = glyphDataOffset,
                byteCount = finalGlyphDataLength,
                section = "glyph variation data",
            )

            return TrueTypeGvarTable(
                data = data,
                axisCount = axisCount,
                sharedTuples = sharedTuples,
                glyphOffsets = offsets,
                glyphDataStart = glyphDataOffset,
            )
        }
    }
}

/**
 * Bridge from parsed SFNT metric data and raw TrueType outline tables to [ParsedTrueTypeGlyphScaler].
 *
 * This factory is intentionally limited to data that has already been parsed or table-sliced by the
 * SFNT layer. It does not depend on the legacy renderer and does not perform any Skia, GPU, or
 * runtime-effect work. Construction fails eagerly when required SFNT fields are missing, when
 * `head.indexToLocFormat` is not one of the two TrueType encodings, when horizontal metrics do not
 * cover every glyph advertised by `maxp.numGlyphs`, or when `loca` offsets point outside `glyf`.
 */
object TrueTypeGlyphScalerFactory {
    /**
     * Creates a parsed TrueType glyph scaler from SFNT metrics plus raw `loca` and `glyf` tables.
     *
     * The SFNT [metrics] object must provide `maxp.numGlyphs`, `head.indexToLocFormat`,
     * `head.unitsPerEm`, and one [HorizontalGlyphMetric] for each glyph id from `0` through
     * `numGlyphs - 1`. The raw [locaTable] is parsed according to `indexToLocFormat`, converted to
     * [TrueTypeLocaFormat], and validated against [glyfTable]. Horizontal SFNT metrics are converted
     * to [TrueTypeGlyphHorizontalMetrics] without synthesizing missing records.
     *
     * Scaling can be supplied either as [scale], which is applied directly, or as [pixelSize], which
     * must be positive and finite and derives `pixelSize / unitsPerEm`. Passing neither leaves font
     * units unscaled. Passing both is rejected so callers do not accidentally double-scale outlines
     * and advances. Direct [scale] accepts any finite value, including negative values for explicit
     * coordinate transformations.
     *
     * @param metrics Parsed SFNT metrics from the font tables.
     * @param locaTable Complete raw TrueType `loca` table bytes.
     * @param glyfTable Complete raw TrueType `glyf` table bytes.
     * @param pixelSize Optional positive pixel size for one em; derives the scaler factor from `unitsPerEm`.
     * @param scale Optional direct scaler factor for callers that already computed one.
     * @return A [ParsedTrueTypeGlyphScaler] backed by the supplied raw `glyf` table and parsed `loca`.
     * @throws IllegalArgumentException when required SFNT fields are missing, metrics are incomplete,
     * table encodings are invalid, scaling inputs are invalid, or `loca` and `glyf` are incoherent.
     */
    fun create(
        metrics: MetricsTables,
        locaTable: ByteArray,
        glyfTable: ByteArray,
        gvarTable: ByteArray? = null,
        normalizedAxisOrder: List<String> = emptyList(),
        pixelSize: Double? = null,
        scale: Double? = null,
    ): ParsedTrueTypeGlyphScaler {
        require(pixelSize == null || scale == null) {
            "pixelSize and scale must not both be provided."
        }
        val numGlyphs = metrics.numGlyphs
            ?: throw IllegalArgumentException("numGlyphs is required to build a TrueType glyph scaler.")
        require(numGlyphs >= 0) { "numGlyphs must be non-negative." }
        val unitsPerEm = metrics.unitsPerEm
            ?: throw IllegalArgumentException("unitsPerEm is required to build a TrueType glyph scaler.")
        require(unitsPerEm > 0) { "unitsPerEm must be positive." }

        val locaFormat = when (val indexToLocFormat = metrics.indexToLocFormat) {
            null -> throw IllegalArgumentException("indexToLocFormat is required to build a TrueType glyph scaler.")
            0 -> TrueTypeLocaFormat.Short
            1 -> TrueTypeLocaFormat.Long
            else -> throw IllegalArgumentException("indexToLocFormat $indexToLocFormat must be 0 or 1.")
        }
        val loca = TrueTypeLocaTableParser.parse(
            data = locaTable,
            format = locaFormat,
            numGlyphs = numGlyphs,
        )
        require(loca.offsets.last() <= glyfTable.size) {
            "loca final offset ${loca.offsets.last()} exceeds glyf table length ${glyfTable.size}."
        }

        val horizontalMetrics = metrics.horizontalMetrics.toTrueTypeHorizontalMetrics(numGlyphs)
        val effectiveScale = when {
            scale != null -> {
                require(scale.isFinite()) { "scale must be finite." }
                scale
            }
            pixelSize != null -> {
                require(pixelSize.isFinite() && pixelSize > 0.0) {
                    "pixelSize must be positive and finite."
                }
                fontUnitsToPixelsScale(unitsPerEm.toDouble(), pixelSize)
            }
            else -> 1.0
        }

        return ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = loca,
            horizontalMetrics = horizontalMetrics,
            scale = effectiveScale,
            gvar = gvarTable?.let { table ->
                TrueTypeGvarTable.parse(
                    data = table,
                    axisCount = normalizedAxisOrder.size,
                    glyphCount = numGlyphs,
                )
            },
            normalizedAxisOrder = normalizedAxisOrder,
        )
    }
}

private fun List<HorizontalGlyphMetric>.toTrueTypeHorizontalMetrics(
    numGlyphs: Int,
): Map<UInt, TrueTypeGlyphHorizontalMetrics> {
    val byGlyphId = LinkedHashMap<UInt, TrueTypeGlyphHorizontalMetrics>()
    for (metric in this) {
        require(metric.glyphId >= 0) { "horizontal metric glyphId ${metric.glyphId} must be non-negative." }
        require(metric.glyphId < numGlyphs) {
            "horizontal metric glyphId ${metric.glyphId} is outside numGlyphs $numGlyphs."
        }
        val glyphId = metric.glyphId.toUInt()
        require(glyphId !in byGlyphId) { "horizontal metrics contain duplicate glyphId $glyphId." }
        byGlyphId[glyphId] = TrueTypeGlyphHorizontalMetrics(
            advanceX = metric.advanceWidth.toDouble(),
            leftSideBearing = metric.leftSideBearing.toDouble(),
        )
    }
    for (glyphId in 0 until numGlyphs) {
        require(glyphId.toUInt() in byGlyphId) {
            "horizontal metrics missing for glyphId $glyphId."
        }
    }
    return byGlyphId
}

/**
 * Pure Kotlin scaler for already sliced TrueType `glyf`, `loca`, and horizontal metrics data.
 *
 * Simple glyphs are converted to typed outline commands, empty glyphs return empty outlines, and
 * composite glyphs recursively resolve their component outlines through parsed component records.
 * When [gvar] is supplied, [position] values are treated as already-normalized coordinates in
 * [normalizedAxisOrder] and are applied only to simple glyph outline points before outline command
 * conversion. Composite glyph-specific variation records, phantom point metrics, `avar` remapping,
 * and TrueType VM instructions are not implemented. Metrics use caller-provided horizontal advances
 * and bounds from the glyph header and currently ignore variation deltas.
 *
 * @param glyfTable Complete raw `glyf` table bytes.
 * @param loca Parsed `loca` table with one sentinel offset.
 * @param horizontalMetrics Horizontal metrics keyed by glyph id.
 * @param scale Uniform factor applied to outlines, advances, and bounds.
 * @param gvar Optional parsed TrueType glyph variation table.
 * @param normalizedAxisOrder Axis tags defining the order of already-normalized [VariationPosition]
 * coordinates consumed by [gvar].
 */
class ParsedTrueTypeGlyphScaler(
    private val glyfTable: ByteArray,
    private val loca: TrueTypeLocaTable,
    private val horizontalMetrics: Map<UInt, TrueTypeGlyphHorizontalMetrics>,
    private val scale: Double = 1.0,
    private val gvar: TrueTypeGvarTable? = null,
    normalizedAxisOrder: List<String> = emptyList(),
) : OutlineScaler {
    private val normalizedAxisOrder: List<String> = normalizedAxisOrder.toList()
    private val normalizedAxisTags: Set<String> = this.normalizedAxisOrder.toSet()

    init {
        require(scale.isFinite()) { "scale must be finite." }
        this.normalizedAxisOrder.forEach { tag ->
            require(tag.length == 4) { "normalized gvar axis tag must contain exactly four characters." }
            require(tag.all { character -> character.code in 0x20..0x7e }) {
                "normalized gvar axis tag $tag must contain printable ASCII characters."
            }
        }
        require(normalizedAxisTags.size == this.normalizedAxisOrder.size) {
            "normalized gvar axis order must not contain duplicate tags."
        }
        if (gvar != null) {
            require(this.normalizedAxisOrder.size == gvar.axisCount) {
                "normalized gvar axis order size ${this.normalizedAxisOrder.size} " +
                    "does not match gvar axisCount ${gvar.axisCount}."
            }
        }
    }

    /**
     * Parses and scales a TrueType glyph outline.
     *
     * Empty glyphs return empty outlines. Simple glyphs emit typed outline commands directly.
     * Composite glyphs recursively resolve component outlines, apply component transforms in font
     * units, and then apply this scaler's final uniform [scale] once to the resolved outline.
     * Composite resolution is capped to prevent cyclic glyph references from overflowing the stack.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Already-normalized variation coordinates keyed by axis tag when [gvar] is supplied.
     * @return Scaled glyph outline for simple, empty, or supported composite glyphs.
     * @throws IllegalArgumentException when the glyph id is outside the `loca` table.
     * @throws UnsupportedOperationException when composite recursion exceeds the depth cap, a
     * component glyph id is outside `loca`, or point-matching references invalid point indices.
     */
    override fun outline(glyphId: UInt, position: VariationPosition): GlyphOutline =
        resolveGlyph(
            glyphId = glyphId,
            depth = 0,
            normalizedCoordinates = normalizedCoordinates(position),
        ).outline.scaled(scale)

    /**
     * Returns scaled horizontal metrics and glyph-header bounds for one TrueType glyph.
     *
     * Empty glyphs have zero bounds. Composite glyphs keep their header bounds but do not imply
     * outline support.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Variation position. Simple-glyph metrics apply bounded `gvar` phantom-point
     * advance deltas when available.
     * @return Scaled glyph metrics.
     * @throws IllegalArgumentException when the glyph id is outside `loca` or has no horizontal metrics.
     */
    override fun metrics(glyphId: UInt, position: VariationPosition): GlyphMetrics {
        val normalizedCoordinates = normalizedCoordinates(position)
        val range = loca.rangeForGlyph(glyphId)
        val glyph = TrueTypeGlyfTableParser.parseGlyph(
            glyfTable = glyfTable,
            range = range,
            glyphId = glyphId,
        )
        val metricGlyphId = glyph.metricsSourceGlyphId(rootGlyphId = glyphId)
        val metric = horizontalMetrics[metricGlyphId]
            ?: throw IllegalArgumentException("horizontal metrics missing for glyphId $metricGlyphId.")
        val bounds = when (glyph) {
            TrueTypeGlyph.Empty -> GlyphBounds(left = 0.0, top = 0.0, right = 0.0, bottom = 0.0)
            is TrueTypeGlyph.Simple -> glyph.header.toGlyphBounds().scaled(scale)
            is TrueTypeGlyph.Composite -> glyph.header.toGlyphBounds().scaled(scale)
        }
        return GlyphMetrics(
            advanceX = resolveHorizontalAdvance(
                glyphId = metricGlyphId,
                metric = metric,
                normalizedCoordinates = normalizedCoordinates,
            ) * scale,
            advanceY = 0.0,
            bounds = bounds,
        )
    }

    /**
     * Produces deterministic current-state evidence for one scaled TrueType glyph.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Already-normalized variation position when [gvar] is present.
     * @return Canonical glyph evidence with outline, metrics, `loca`, variation, route, and
     * diagnostic facts.
     */
    fun scaledGlyphEvidence(
        glyphId: UInt,
        position: VariationPosition = VariationPosition(),
    ): ScaledTrueTypeGlyphEvidence =
        scaledGlyphEvidence(
            glyphId = glyphId,
            position = position,
            requestedPosition = position,
        )

    internal fun scaledGlyphEvidence(
        glyphId: UInt,
        position: VariationPosition,
        requestedPosition: VariationPosition,
        additionalDiagnostics: List<FontScalerDiagnostic> = emptyList(),
        includeNormalizedVariationPosition: Boolean = true,
    ): ScaledTrueTypeGlyphEvidence {
        val diagnostics = additionalDiagnostics.toMutableList()
        val range = loca.rangeForGlyph(glyphId)
        val variationPositionDiagnostic = positionValidationDiagnostic(glyphId, position)
        if (variationPositionDiagnostic != null) {
            diagnostics += variationPositionDiagnostic
        }
        val normalizedCoordinates = if (variationPositionDiagnostic == null) {
            normalizedCoordinates(position)
        } else {
            emptyList()
        }
        val normalizedVariationPosition = if (gvar != null && includeNormalizedVariationPosition) {
            normalizedAxisOrder.zip(normalizedCoordinates)
                .map { (tag, value) -> VariationCoordinateEvidence(tag = tag, value = value) }
                .sortedBy { coordinate -> coordinate.tag }
        } else {
            emptyList()
        }
        if (variationPositionDiagnostic == null) {
            diagnostics += gvarEvidenceDiagnostics(
                glyphId = glyphId,
                normalizedCoordinates = normalizedCoordinates,
            )
        }
        if (gvar == null && requestedPosition.axes.isNotEmpty()) {
            diagnostics += FontScalerDiagnostic(
                code = FontScalerDiagnosticCodes.VARIATION_AXIS_UNSUPPORTED,
                detail = "truetype.gvar-unavailable",
                operation = "variation",
                glyphId = glyphId,
                severity = "warning",
            )
        }
        val outline = runCatching {
            resolveGlyph(
                glyphId = glyphId,
                depth = 0,
                normalizedCoordinates = normalizedCoordinates,
            ).outline.scaled(scale)
        }.getOrElse { error ->
            val diagnostic = error.toFontScalerDiagnosticOrNull(
                glyphId = glyphId,
                operation = "outline",
            )
            if (diagnostic != null) {
                diagnostics += diagnostic
                null
            } else {
                throw error
            }
        }
        val metrics = runCatching { metrics(glyphId = glyphId, position = position) }
            .getOrElse { error ->
                val diagnostic = error.toFontScalerDiagnosticOrNull(
                    glyphId = glyphId,
                    operation = "metrics",
                )
                if (diagnostic != null) {
                    diagnostics += diagnostic
                    null
                } else {
                    throw error
                }
            }
        val outlineCommands = outline?.commands.orEmpty().map { command -> command.toEvidenceLine() }
        val outlineCommandDump = outlineCommands.joinToString("\n")
        val compositeComponents = runCatching { compositeComponentEvidence(glyphId) }
            .getOrDefault(emptyList())

        return ScaledTrueTypeGlyphEvidence(
            glyphId = glyphId,
            requestedVariationPosition = requestedPosition.toEvidenceCoordinates(),
            normalizedVariationPosition = normalizedVariationPosition,
            outlineCommands = outlineCommands,
            outlineCommandDump = outlineCommandDump,
            outlineCommandDumpSha256 = outlineCommandDump.scalerSha256Hex(),
            conservativeBounds = outline?.conservativeBounds(),
            metrics = metrics,
            locaRange = range.toEvidence(),
            compositeComponents = compositeComponents,
            diagnostics = diagnostics.sortedWith(fontScalerDiagnosticOrdering),
        )
    }

    private fun parseGlyph(glyphId: UInt): TrueTypeGlyph =
        TrueTypeGlyfTableParser.parseGlyph(
            glyfTable = glyfTable,
            loca = loca,
            glyphId = glyphId,
        )

    private fun TrueTypeGlyph.metricsSourceGlyphId(rootGlyphId: UInt): UInt {
        if (this !is TrueTypeGlyph.Composite) return rootGlyphId
        val component = components.firstOrNull { component ->
            component.flags and COMPOSITE_USE_MY_METRICS != 0
        } ?: return rootGlyphId
        if (component.glyphId.toLong() >= loca.offsets.lastIndex) {
            throw FontScalerRefusalException(
                diagnostic = FontScalerDiagnostic(
                    code = FontScalerDiagnosticCodes.OUTLINE_FORMAT_UNSUPPORTED,
                    detail = "truetype.composite-component-glyph-id",
                    operation = "metrics",
                    glyphId = rootGlyphId,
                ),
                message = "composite glyphId $rootGlyphId references metrics source glyphId " +
                    "${component.glyphId} outside loca table glyph count ${loca.offsets.size - 1}.",
            )
        }
        return component.glyphId
    }

    private fun compositeComponentEvidence(
        glyphId: UInt,
        depth: Int = 0,
    ): List<TrueTypeCompositeComponentEvidence> {
        if (depth > MAX_COMPOSITE_GLYPH_DEPTH) return emptyList()
        val glyph = parseGlyph(glyphId)
        if (glyph !is TrueTypeGlyph.Composite) return emptyList()

        val evidence = mutableListOf<TrueTypeCompositeComponentEvidence>()
        glyph.components.forEachIndexed { index, component ->
            evidence += component.toEvidence(
                parentGlyphId = glyphId,
                componentIndex = index,
                depth = depth,
            )
            evidence += compositeComponentEvidence(
                glyphId = component.glyphId,
                depth = depth + 1,
            )
        }
        return evidence
    }

    private fun resolveGlyph(
        glyphId: UInt,
        depth: Int,
        normalizedCoordinates: List<Double>,
    ): ResolvedTrueTypeGlyphOutline {
        if (depth > MAX_COMPOSITE_GLYPH_DEPTH) {
            throw FontScalerRefusalException(
                diagnostic = FontScalerDiagnostic(
                    code = FontScalerDiagnosticCodes.OUTLINE_FORMAT_UNSUPPORTED,
                    detail = "truetype.composite-recursion-depth",
                    operation = "outline",
                    glyphId = glyphId,
                ),
                message = "composite glyph resolution depth cap $MAX_COMPOSITE_GLYPH_DEPTH exceeded at glyphId $glyphId.",
            )
        }
        return when (val glyph = parseGlyph(glyphId)) {
            TrueTypeGlyph.Empty -> ResolvedTrueTypeGlyphOutline(outline = GlyphOutline(glyphId = glyphId))
            is TrueTypeGlyph.Simple -> {
                val variationDeltas = simpleGlyphVariationDeltas(
                    glyphId = glyphId,
                    glyph = glyph,
                    normalizedCoordinates = normalizedCoordinates,
                )
                ResolvedTrueTypeGlyphOutline(
                    outline = glyph.toGlyphOutline(
                        glyphId = glyphId,
                        variationDeltas = variationDeltas,
                    ),
                    points = glyph.resolvedPoints(variationDeltas),
                )
            }
            is TrueTypeGlyph.Composite -> resolveCompositeGlyphOutline(
                glyphId = glyphId,
                glyph = glyph,
                depth = depth,
                normalizedCoordinates = normalizedCoordinates,
            )
        }
    }

    private fun resolveCompositeGlyphOutline(
        glyphId: UInt,
        glyph: TrueTypeGlyph.Composite,
        depth: Int,
        normalizedCoordinates: List<Double>,
    ): ResolvedTrueTypeGlyphOutline {
        val commands = mutableListOf<OutlineCommand>()
        val points = mutableListOf<ResolvedTrueTypeGlyphPoint>()
        for (component in glyph.components) {
            if (component.glyphId.toLong() >= loca.offsets.lastIndex) {
                throw FontScalerRefusalException(
                    diagnostic = FontScalerDiagnostic(
                        code = FontScalerDiagnosticCodes.OUTLINE_FORMAT_UNSUPPORTED,
                        detail = "truetype.composite-component-glyph-id",
                        operation = "outline",
                        glyphId = glyphId,
                    ),
                    message = "composite glyphId $glyphId references component glyphId ${component.glyphId} " +
                        "outside loca table glyph count ${loca.offsets.size - 1}.",
                )
            }
            val child = resolveGlyph(
                glyphId = component.glyphId,
                depth = depth + 1,
                normalizedCoordinates = normalizedCoordinates,
            )
            val transform = when (val arguments = component.arguments) {
                is TrueTypeCompositeGlyphArgument.XyValues -> component.transform
                is TrueTypeCompositeGlyphArgument.PointMatching -> {
                    val compoundPoint = points.getOrNull(arguments.compoundPointIndex)
                    val componentPoint = child.points
                        .map { point -> point.transformed(component.transform) }
                        .getOrNull(arguments.componentPointIndex)
                    if (compoundPoint == null || componentPoint == null) {
                        throw FontScalerRefusalException(
                            diagnostic = FontScalerDiagnostic(
                                code = FontScalerDiagnosticCodes.OUTLINE_FORMAT_UNSUPPORTED,
                                detail = "truetype.composite-point-index",
                                operation = "outline",
                                glyphId = glyphId,
                            ),
                            message = "composite glyphId $glyphId has invalid point index for component " +
                                "glyphId ${component.glyphId}: compound point ${arguments.compoundPointIndex}, " +
                                "component point ${arguments.componentPointIndex}.",
                        )
                    }
                    component.transform.copy(
                        dx = component.transform.dx + compoundPoint.x - componentPoint.x,
                        dy = component.transform.dy + compoundPoint.y - componentPoint.y,
                    )
                }
            }
            commands += child.outline.commands.transformed(transform)
            points += child.points.map { point -> point.transformed(transform) }
        }
        return ResolvedTrueTypeGlyphOutline(
            outline = GlyphOutline(glyphId = glyphId, commands = commands),
            points = points,
        )
    }

    private fun simpleGlyphVariationDeltas(
        glyphId: UInt,
        glyph: TrueTypeGlyph.Simple,
        normalizedCoordinates: List<Double>,
    ): TrueTypeGlyphVariationDeltas? {
        val gvar = gvar ?: return null
        return gvar.simpleGlyphDeltas(
            glyphId = glyphId,
            glyph = glyph,
            normalizedCoordinates = normalizedCoordinates,
        )
    }

    private fun resolveHorizontalAdvance(
        glyphId: UInt,
        metric: TrueTypeGlyphHorizontalMetrics,
        normalizedCoordinates: List<Double>,
    ): Double {
        if (normalizedCoordinates.isEmpty() || normalizedCoordinates.all { coordinate -> coordinate == 0.0 }) {
            return metric.advanceX
        }
        val glyph = parseGlyph(glyphId)
        if (glyph !is TrueTypeGlyph.Simple) {
            return metric.advanceX
        }
        val variationDeltas = simpleGlyphVariationDeltas(
            glyphId = glyphId,
            glyph = glyph,
            normalizedCoordinates = normalizedCoordinates,
        ) ?: return metric.advanceX
        return metric.advanceX + variationDeltas.phantomXDelta(PHANTOM_RIGHT_SIDE_INDEX) -
            variationDeltas.phantomXDelta(PHANTOM_LEFT_SIDE_INDEX)
    }

    private fun gvarEvidenceDiagnostics(
        glyphId: UInt,
        normalizedCoordinates: List<Double>,
    ): List<FontScalerDiagnostic> {
        val gvar = gvar ?: return emptyList()
        val glyph = parseGlyph(glyphId)
        if (glyph !is TrueTypeGlyph.Simple) {
            return emptyList()
        }
        val result = gvar.simpleGlyphDeltaResult(
            glyphId = glyphId,
            glyph = glyph,
            normalizedCoordinates = normalizedCoordinates,
        )
        return when {
            result.variationDataMalformed -> {
                listOf(
                    FontScalerDiagnostic(
                        code = FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED,
                        detail = "truetype.gvar-malformed",
                        operation = "variation",
                        glyphId = glyphId,
                        severity = "warning",
                    ),
                )
            }
            result.requiresIupInterpolation -> {
                listOf(
                    FontScalerDiagnostic(
                        code = FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED,
                        detail = "truetype.gvar-iup-unavailable",
                        operation = "variation",
                        glyphId = glyphId,
                        severity = "warning",
                    ),
                )
            }
            else -> emptyList()
        }
    }

    private fun normalizedCoordinates(position: VariationPosition): List<Double> {
        val gvar = gvar ?: return emptyList()
        position.axes.forEach { (tag, value) ->
            require(tag in normalizedAxisTags) {
                "variation position axis $tag is not declared in normalized gvar axis order."
            }
            require(value.isFinite()) { "variation position axis $tag must be finite." }
        }
        return normalizedAxisOrder.map { tag ->
            position.axes[tag] ?: 0.0
        }.also { coordinates ->
            require(coordinates.size == gvar.axisCount) {
                "normalized coordinate count ${coordinates.size} does not match gvar axisCount ${gvar.axisCount}."
            }
        }
    }

    private fun positionValidationDiagnostic(
        glyphId: UInt,
        position: VariationPosition,
    ): FontScalerDiagnostic? {
        if (gvar == null) {
            return null
        }
        position.axes.toSortedMap().forEach { (tag, value) ->
            if (tag !in normalizedAxisTags) {
                return FontScalerDiagnostic(
                    code = FontScalerDiagnosticCodes.VARIATION_AXIS_UNSUPPORTED,
                    detail = "truetype.gvar-axis",
                    operation = "variation",
                    glyphId = glyphId,
                )
            }
            if (!value.isFinite()) {
                return FontScalerDiagnostic(
                    code = FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED,
                    detail = "truetype.variation-position-non-finite",
                    operation = "variation",
                    glyphId = glyphId,
                )
            }
        }
        return null
    }
}

private data class ResolvedTrueTypeGlyphOutline(
    val outline: GlyphOutline,
    val points: List<ResolvedTrueTypeGlyphPoint> = emptyList(),
)

private data class ResolvedTrueTypeGlyphPoint(
    val x: Double,
    val y: Double,
)

private fun TrueTypeGlyph.Simple.resolvedPoints(
    variationDeltas: TrueTypeGlyphVariationDeltas?,
): List<ResolvedTrueTypeGlyphPoint> =
    contours
        .flatMap { contour -> contour.points }
        .mapIndexed { pointIndex, point ->
            ResolvedTrueTypeGlyphPoint(
                x = point.x.toDouble() + (variationDeltas?.xDelta(pointIndex) ?: 0.0),
                y = point.y.toDouble() + (variationDeltas?.yDelta(pointIndex) ?: 0.0),
            )
        }

private fun ResolvedTrueTypeGlyphPoint.transformed(
    transform: TrueTypeCompositeTransform,
): ResolvedTrueTypeGlyphPoint = ResolvedTrueTypeGlyphPoint(
    x = transform.transformX(x, y),
    y = transform.transformY(x, y),
)

private fun TrueTypeCompositeGlyphComponent.toEvidence(
    parentGlyphId: UInt,
    componentIndex: Int,
    depth: Int,
): TrueTypeCompositeComponentEvidence {
    val (argumentKind, argument1, argument2) = when (val decoded = arguments) {
        is TrueTypeCompositeGlyphArgument.XyValues -> Triple("xy", decoded.x, decoded.y)
        is TrueTypeCompositeGlyphArgument.PointMatching -> Triple(
            "point-matching",
            decoded.compoundPointIndex,
            decoded.componentPointIndex,
        )
    }
    return TrueTypeCompositeComponentEvidence(
        depth = depth,
        parentGlyphId = parentGlyphId,
        componentIndex = componentIndex,
        componentGlyphId = glyphId,
        flags = "0x${flags.toString(radix = 16).padStart(4, '0')}",
        argumentKind = argumentKind,
        argument1 = argument1,
        argument2 = argument2,
        xx = transform.xx,
        xy = transform.xy,
        yx = transform.yx,
        yy = transform.yy,
        dx = transform.dx,
        dy = transform.dy,
    )
}

private fun TrueTypeGlyphHeader.toGlyphBounds(): GlyphBounds =
    GlyphBounds(
        left = xMin.toDouble(),
        top = yMin.toDouble(),
        right = xMax.toDouble(),
        bottom = yMax.toDouble(),
    )

private const val TRUE_TYPE_GLYF_SCALER_FAMILY = "truetype-glyf"
private const val TRUE_TYPE_GLYF_SCALER_ROUTE = "font.scaler.truetype-glyf"
private const val CFF_SCALER_FAMILY = "cff"
private const val CFF_SCALER_ROUTE = "font.scaler.cff"
private const val CFF_FILL_RULE = "non-zero"
private const val CFF_CHARSTRING_TRACE_DUMP_ID = "cff-charstring-trace"

private val fontScalerDiagnosticOrdering = compareBy<FontScalerDiagnostic>(
    { diagnostic -> diagnostic.operation },
    { diagnostic -> diagnostic.code },
    { diagnostic -> diagnostic.detail },
    { diagnostic -> diagnostic.glyphId.toString() },
    { diagnostic -> diagnostic.severity },
)

private fun VariationPosition.toEvidenceCoordinates(): List<VariationCoordinateEvidence> =
    axes.toSortedMap().mapNotNull { (tag, value) ->
        if (value.isFinite()) {
            VariationCoordinateEvidence(tag = tag, value = value)
        } else {
            null
        }
    }

private fun TrueTypeGlyphDataRange.toEvidence(): TrueTypeLocaRangeEvidence {
    val byteLength = endExclusive - start
    return TrueTypeLocaRangeEvidence(
        start = start,
        endExclusive = endExclusive,
        byteLength = byteLength,
        isEmpty = byteLength == 0,
    )
}

private fun List<VariationCoordinateEvidence>.isSortedByTag(): Boolean =
    zipWithNext().all { (left, right) -> left.tag <= right.tag }

private fun List<VariationCoordinateEvidence>.toCoordinateJson(): String =
    joinToString(prefix = "[", postfix = "]") { coordinate -> coordinate.toCanonicalJson() }

private fun List<String>.toJsonStringArray(): String =
    joinToString(prefix = "[", postfix = "]") { value -> scalerJsonString(value) }

private fun List<Int>.toIntJsonArray(): String =
    joinToString(prefix = "[", postfix = "]")

private fun List<Double>.toScalerNumberJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { value -> value.toCanonicalScalerNumber() }

private fun List<List<Double>>.toScalerNumberMatrixJson(): String =
    joinToString(prefix = "[", postfix = "]") { row -> row.toScalerNumberJsonArray() }

private fun List<CFFByteRangeEvidence>.toCFFByteRangeJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { range -> range.toCanonicalJson() }

private fun List<CFFIndexEvidence>.toCFFIndexJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { index -> index.toCanonicalJson() }

private fun List<CFFDictOperatorEvidence>.toCFFDictOperatorJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { operator -> operator.toCanonicalJson() }

private fun List<CFFDictEvidence>.toCFFDictJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { evidence -> evidence.toCanonicalJson() }

private fun List<CFFPrivateDictEvidence>.toCFFPrivateDictJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { evidence -> evidence.toCanonicalJson() }

private fun List<CFFTableDiagnosticEvidence>.toCFFTableDiagnosticJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { diagnostic -> diagnostic.toCanonicalJson() }

private fun List<TrueTypeCompositeComponentEvidence>.toCompositeComponentJson(): String =
    joinToString(prefix = "[", postfix = "]") { component -> component.toCanonicalJson() }

private fun List<CFFCharStringCallTrace>.toCFFCallTraceJson(): String =
    joinToString(prefix = "[", postfix = "]") { trace -> trace.toCanonicalJson() }

private fun List<CFFBlendVectorEvidence>.toCFFBlendVectorJson(): String =
    joinToString(prefix = "[", postfix = "]") { vector -> vector.toCanonicalJson() }

private fun List<FontScalerDiagnostic>.toDiagnosticJson(): String =
    joinToString(prefix = "[", postfix = "]") { diagnostic -> diagnostic.toCanonicalJson() }

private fun GlyphBounds.toCanonicalJson(): String = buildString {
    append("{")
    append(scalerJsonString("left")).append(": ").append(left.toCanonicalScalerNumber()).append(", ")
    append(scalerJsonString("top")).append(": ").append(top.toCanonicalScalerNumber()).append(", ")
    append(scalerJsonString("right")).append(": ").append(right.toCanonicalScalerNumber()).append(", ")
    append(scalerJsonString("bottom")).append(": ").append(bottom.toCanonicalScalerNumber())
    append("}")
}

private fun GlyphMetrics.toCanonicalJson(): String = buildString {
    append("{")
    append(scalerJsonString("advanceX")).append(": ").append(advanceX.toCanonicalScalerNumber()).append(", ")
    append(scalerJsonString("advanceY")).append(": ").append(advanceY.toCanonicalScalerNumber()).append(", ")
    append(scalerJsonString("bounds")).append(": ").append(bounds.toCanonicalJson())
    verticalMetrics?.let { vertical ->
        append(", ")
        append(scalerJsonString("verticalMetrics")).append(": ").append(vertical.toCanonicalJson())
    }
    append("}")
}

private fun GlyphVerticalMetrics.toCanonicalJson(): String = buildString {
    append("{")
    append(scalerJsonString("state")).append(": ").append(scalerJsonString(state)).append(", ")
    append(scalerJsonString("source")).append(": ").append(scalerJsonString(source)).append(", ")
    append(scalerJsonString("verticalAdvance")).append(": ")
        .append(verticalAdvance?.toCanonicalScalerNumber() ?: "null").append(", ")
    append(scalerJsonString("topSideBearing")).append(": ")
        .append(topSideBearing?.toCanonicalScalerNumber() ?: "null").append(", ")
    append(scalerJsonString("verticalOriginY")).append(": ")
        .append(verticalOriginY?.toCanonicalScalerNumber() ?: "null").append(", ")
    append(scalerJsonString("ascender")).append(": ")
        .append(ascender?.toCanonicalScalerNumber() ?: "null").append(", ")
    append(scalerJsonString("descender")).append(": ")
        .append(descender?.toCanonicalScalerNumber() ?: "null").append(", ")
    append(scalerJsonString("lineGap")).append(": ")
        .append(lineGap?.toCanonicalScalerNumber() ?: "null").append(", ")
    append(scalerJsonString("maxAdvanceHeight")).append(": ")
        .append(maxAdvanceHeight?.toCanonicalScalerNumber() ?: "null").append(", ")
    append(scalerJsonString("diagnostics")).append(": ")
        .append(diagnostics.toJsonStringArray())
    append("}")
}

private fun OutlineCommand.toEvidenceLine(): String =
    when (this) {
        is OutlineCommand.MoveTo -> "M ${x.toCanonicalScalerNumber()} ${y.toCanonicalScalerNumber()}"
        is OutlineCommand.LineTo -> "L ${x.toCanonicalScalerNumber()} ${y.toCanonicalScalerNumber()}"
        is OutlineCommand.QuadraticTo -> "Q ${controlX.toCanonicalScalerNumber()} " +
            "${controlY.toCanonicalScalerNumber()} ${x.toCanonicalScalerNumber()} ${y.toCanonicalScalerNumber()}"
        is OutlineCommand.CubicTo -> "C ${controlX1.toCanonicalScalerNumber()} " +
            "${controlY1.toCanonicalScalerNumber()} ${controlX2.toCanonicalScalerNumber()} " +
            "${controlY2.toCanonicalScalerNumber()} ${x.toCanonicalScalerNumber()} ${y.toCanonicalScalerNumber()}"
        OutlineCommand.Close -> "Z"
    }

private fun Throwable.toFontScalerDiagnosticOrNull(
    glyphId: UInt,
    operation: String,
): FontScalerDiagnostic? =
    when (this) {
        is FontScalerRefusalException -> diagnostic.copy(operation = operation, glyphId = glyphId)
        is IllegalArgumentException -> toMalformedTrueTypeDiagnosticOrNull(glyphId = glyphId, operation = operation)
        else -> null
    }

private fun IllegalArgumentException.toMalformedTrueTypeDiagnosticOrNull(
    glyphId: UInt,
    operation: String,
): FontScalerDiagnostic? {
    val message = message.orEmpty()
    val detail = when {
        "endPtsOfContours must be monotonic" in message -> "truetype.contour-endpoints-malformed"
        "flag repeat exceeds point count" in message -> "truetype.flag-repeat-overflow"
        "composite component has mutually exclusive scale flags set" in message -> "truetype.composite-transform-flags"
        "header is truncated" in message -> "truetype.glyf-header-truncated"
        "x coordinate data is truncated" in message ||
            "y coordinate data is truncated" in message -> "truetype.coordinate-run-truncated"
        "flag data is truncated" in message ||
            "flag repeat data is truncated" in message -> "truetype.flag-data-truncated"
        "instructionLength is truncated" in message ||
            "instructions is truncated" in message -> "truetype.instructions-truncated"
        "endPtsOfContours is truncated" in message -> "truetype.contour-endpoints-truncated"
        "outside glyf table" in message -> "truetype.glyf-range-invalid"
        else -> null
    } ?: return null
    return FontScalerDiagnostic(
        code = FontScalerDiagnosticCodes.SCALER_OUTLINE_UNAVAILABLE,
        detail = detail,
        operation = operation,
        glyphId = glyphId,
    )
}

private fun String.isStableToken(): Boolean =
    isNotEmpty() && all { character ->
        character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '.' ||
            character == '-' ||
            character == '_'
    }

private fun Double.toCanonicalScalerNumber(): String {
    require(isFinite()) { "scaler evidence number must be finite." }
    return toString()
}

private fun scalerJsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
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

private fun String.scalerSha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private const val FLAG_ON_CURVE = 0x01
private const val FLAG_X_SHORT_VECTOR = 0x02
private const val FLAG_Y_SHORT_VECTOR = 0x04
private const val FLAG_REPEAT = 0x08
private const val FLAG_X_SAME_OR_POSITIVE = 0x10
private const val FLAG_Y_SAME_OR_POSITIVE = 0x20

private const val COMPOSITE_ARG_1_AND_2_ARE_WORDS = 0x0001
private const val COMPOSITE_ARGS_ARE_XY_VALUES = 0x0002
private const val COMPOSITE_WE_HAVE_A_SCALE = 0x0008
private const val COMPOSITE_MORE_COMPONENTS = 0x0020
private const val COMPOSITE_WE_HAVE_AN_X_AND_Y_SCALE = 0x0040
private const val COMPOSITE_WE_HAVE_A_TWO_BY_TWO = 0x0080
private const val COMPOSITE_WE_HAVE_INSTRUCTIONS = 0x0100
private const val COMPOSITE_USE_MY_METRICS = 0x0200
private const val COMPOSITE_SCALED_COMPONENT_OFFSET = 0x0800
private const val COMPOSITE_UNSCALED_COMPONENT_OFFSET = 0x1000
private const val MAX_COMPOSITE_GLYPH_DEPTH = 32
private const val MAX_COMPOSITE_GLYPH_COMPONENTS = 64

private const val GVAR_LONG_OFFSETS = 0x0001
private const val GVAR_SHARED_POINT_NUMBERS = 0x8000
private const val GVAR_TUPLE_COUNT_MASK = 0x0fff
private const val GVAR_EMBEDDED_PEAK_TUPLE = 0x8000
private const val GVAR_INTERMEDIATE_REGION = 0x4000
private const val GVAR_PRIVATE_POINT_NUMBERS = 0x2000
private const val GVAR_TUPLE_INDEX_MASK = 0x0fff
private const val PHANTOM_POINT_COUNT = 4
private const val PHANTOM_LEFT_SIDE_INDEX = 0
private const val PHANTOM_RIGHT_SIDE_INDEX = 1

private enum class CoordinateAxis {
    X,
    Y,
}

private class GlyfReader(
    private val data: ByteArray,
    private val start: Int,
    private val endExclusive: Int,
    val glyphId: UInt,
) {
    var offset: Int = start
        private set

    fun readInt16(section: String): Int {
        requireAvailable(byteCount = 2, section = section)
        val value = readInt16(data, offset)
        offset += 2
        return value
    }

    fun readUInt16(section: String): Int {
        requireAvailable(byteCount = 2, section = section)
        val value = readUInt16(data, offset)
        offset += 2
        return value
    }

    fun readUInt8(section: String): Int {
        requireAvailable(byteCount = 1, section = section)
        return data[offset++].toInt() and 0xff
    }

    fun readInt8(section: String): Int {
        requireAvailable(byteCount = 1, section = section)
        val value = data[offset++].toInt() and 0xff
        return if (value and 0x80 != 0) value - 0x100 else value
    }

    fun skip(byteCount: Int, section: String) {
        requireAvailable(byteCount = byteCount, section = section)
        offset += byteCount
    }

    fun readFlags(pointCount: Int): List<Int> {
        val flags = mutableListOf<Int>()
        while (flags.size < pointCount) {
            val rawFlag = readUInt8("flag data")
            val effectiveFlag = rawFlag and FLAG_REPEAT.inv() and 0xff
            val repeatCount = if (rawFlag and FLAG_REPEAT != 0) {
                readUInt8("flag repeat data")
            } else {
                0
            }
            require(flags.size + repeatCount + 1 <= pointCount) {
                "glyf glyph $glyphId flag repeat exceeds point count $pointCount."
            }
            repeat(repeatCount + 1) {
                flags += effectiveFlag
            }
        }
        return flags
    }

    fun readCoordinates(flags: List<Int>, axis: CoordinateAxis): List<Int> {
        var current = 0
        return flags.map { flag ->
            val isShort: Boolean
            val sameOrPositive: Boolean
            val section: String
            when (axis) {
                CoordinateAxis.X -> {
                    isShort = flag and FLAG_X_SHORT_VECTOR != 0
                    sameOrPositive = flag and FLAG_X_SAME_OR_POSITIVE != 0
                    section = "x coordinate data"
                }
                CoordinateAxis.Y -> {
                    isShort = flag and FLAG_Y_SHORT_VECTOR != 0
                    sameOrPositive = flag and FLAG_Y_SAME_OR_POSITIVE != 0
                    section = "y coordinate data"
                }
            }

            val delta = if (isShort) {
                val magnitude = readUInt8(section)
                if (sameOrPositive) magnitude else -magnitude
            } else if (sameOrPositive) {
                0
            } else {
                readInt16(section)
            }
            current += delta
            current
        }
    }

    private fun requireAvailable(byteCount: Int, section: String) {
        require(byteCount >= 0) { "byteCount must be non-negative." }
        require(offset + byteCount <= endExclusive) {
            "glyf glyph $glyphId $section is truncated: need $byteCount bytes at " +
                "glyph-relative offset ${offset - start}, range length ${endExclusive - start}."
        }
    }
}

private fun MutableList<OutlineCommand>.addContourCommands(
    points: List<TrueTypeGlyphPoint>,
    firstPointIndex: Int,
    variationDeltas: TrueTypeGlyphVariationDeltas?,
) {
    if (points.isEmpty()) {
        return
    }

    val outlinePoints = points.mapIndexed { contourIndex, point ->
        point.toOutlinePoint(
            pointIndex = firstPointIndex + contourIndex,
            variationDeltas = variationDeltas,
        )
    }
    val first = outlinePoints.first()
    val last = outlinePoints.last()
    val startPoint = when {
        first.onCurve -> first
        last.onCurve -> last
        else -> midpoint(last, first)
    }
    add(moveTo(startPoint.x, startPoint.y))

    var currentPoint = startPoint
    var pendingOffCurve: OutlinePoint? = null
    val pointsToVisit = when {
        first.onCurve -> outlinePoints.drop(1)
        last.onCurve -> outlinePoints.dropLast(1)
        else -> outlinePoints
    }

    for (point in pointsToVisit) {
        if (point.onCurve) {
            val control = pendingOffCurve
            if (control != null) {
                add(
                    quadraticTo(
                        controlX = control.x,
                        controlY = control.y,
                        x = point.x,
                        y = point.y,
                    ),
                )
            } else if (!sameCoordinates(currentPoint, point)) {
                add(lineTo(point.x, point.y))
            }
            pendingOffCurve = null
            currentPoint = point
        } else {
            val previousOffCurve = pendingOffCurve
            if (previousOffCurve != null) {
                val implicitPoint = midpoint(previousOffCurve, point)
                add(
                    quadraticTo(
                        controlX = previousOffCurve.x,
                        controlY = previousOffCurve.y,
                        x = implicitPoint.x,
                        y = implicitPoint.y,
                    ),
                )
                currentPoint = implicitPoint
            }
            pendingOffCurve = point
        }
    }

    val control = pendingOffCurve
    if (control != null) {
        add(
            quadraticTo(
                controlX = control.x,
                controlY = control.y,
                x = startPoint.x,
                y = startPoint.y,
            ),
        )
    }
    add(close())
}

private data class OutlinePoint(
    val x: Double,
    val y: Double,
    val onCurve: Boolean,
)

private fun TrueTypeGlyphPoint.toOutlinePoint(
    pointIndex: Int,
    variationDeltas: TrueTypeGlyphVariationDeltas?,
): OutlinePoint = OutlinePoint(
    x = x.toDouble() + (variationDeltas?.xDelta(pointIndex) ?: 0.0),
    y = y.toDouble() + (variationDeltas?.yDelta(pointIndex) ?: 0.0),
    onCurve = onCurve,
)

private fun midpoint(
    a: OutlinePoint,
    b: OutlinePoint,
): OutlinePoint = OutlinePoint(
    x = (a.x + b.x) / 2.0,
    y = (a.y + b.y) / 2.0,
    onCurve = true,
)

private fun sameCoordinates(
    a: OutlinePoint,
    b: OutlinePoint,
): Boolean = a.x == b.x && a.y == b.y

private fun GlyfReader.readF2Dot14(section: String): Double = readInt16(section) / 16384.0

private fun readUInt16(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xff) shl 8) or
        (data[offset + 1].toInt() and 0xff)

private fun readInt16(data: ByteArray, offset: Int): Int {
    val value = readUInt16(data, offset)
    return if (value and 0x8000 != 0) value - 0x10000 else value
}

private fun readInt32(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xff) shl 24) or
        ((data[offset + 1].toInt() and 0xff) shl 16) or
        ((data[offset + 2].toInt() and 0xff) shl 8) or
        (data[offset + 3].toInt() and 0xff)

private fun readUInt32(data: ByteArray, offset: Int): Long =
    ((data[offset].toLong() and 0xff) shl 24) or
        ((data[offset + 1].toLong() and 0xff) shl 16) or
        ((data[offset + 2].toLong() and 0xff) shl 8) or
        (data[offset + 3].toLong() and 0xff)

private class TrueTypeGvarReader(
    private val data: ByteArray,
) {
    fun readUInt16(offset: Int, section: String): Int {
        requireAvailable(offset = offset, byteCount = 2, section = section)
        return readUInt16(data, offset)
    }

    fun readUInt32AsInt(offset: Int, section: String): Int {
        requireAvailable(offset = offset, byteCount = 4, section = section)
        val value = readUInt32(data, offset)
        require(value <= Int.MAX_VALUE) { "gvar $section UInt32 value $value does not fit Int." }
        return value.toInt()
    }

    fun readF2Dot14(offset: Int, section: String): Double {
        requireAvailable(offset = offset, byteCount = 2, section = section)
        return readInt16(data, offset) / 16384.0
    }

    fun requireAvailable(offset: Int, byteCount: Int, section: String) {
        require(offset >= 0) { "gvar $section offset must be non-negative." }
        require(byteCount >= 0) { "gvar $section byteCount must be non-negative." }
        require(offset <= data.size && byteCount <= data.size - offset) {
            "gvar $section is truncated: need $byteCount bytes at offset $offset, " +
                "table length ${data.size}."
        }
    }
}

private fun ByteArray.readUInt8OrNull(offset: Int, limit: Int): Int? {
    if (!fitsGvar(offset = offset, byteCount = 1, limit = limit)) {
        return null
    }
    return this[offset].toInt() and 0xff
}

private fun ByteArray.readInt8OrNull(offset: Int, limit: Int): Int? {
    val value = readUInt8OrNull(offset = offset, limit = limit) ?: return null
    return if (value and 0x80 != 0) value - 0x100 else value
}

private fun ByteArray.readUInt16OrNull(offset: Int, limit: Int): Int? {
    if (!fitsGvar(offset = offset, byteCount = 2, limit = limit)) {
        return null
    }
    return readUInt16(this, offset)
}

private fun ByteArray.readInt16OrNull(offset: Int, limit: Int): Int? {
    if (!fitsGvar(offset = offset, byteCount = 2, limit = limit)) {
        return null
    }
    return readInt16(this, offset)
}

private fun ByteArray.readF2Dot14OrNull(offset: Int, limit: Int): Double? {
    if (!fitsGvar(offset = offset, byteCount = 2, limit = limit)) {
        return null
    }
    return readInt16(this, offset) / 16384.0
}

private fun ByteArray.fitsGvar(offset: Int, byteCount: Int, limit: Int): Boolean =
    offset >= 0 &&
        byteCount >= 0 &&
        limit in 0..size &&
        offset <= limit &&
        byteCount <= limit - offset

private fun Int.checkedPlus(other: Int): Int? {
    val sum = toLong() + other.toLong()
    return if (sum in 0..Int.MAX_VALUE.toLong()) sum.toInt() else null
}

private fun Int.checkedPlusPhantomCount(): Int? = checkedPlus(PHANTOM_POINT_COUNT)

private fun checkedTableByteCount(count: Long, itemSize: Long, section: String): Int {
    require(count >= 0) { "gvar $section count must be non-negative." }
    require(itemSize >= 0) { "gvar $section item size must be non-negative." }
    val byteCount = count * itemSize
    require(itemSize == 0L || byteCount / itemSize == count) {
        "gvar $section byte count overflows Int."
    }
    require(byteCount <= Int.MAX_VALUE) { "gvar $section byte count $byteCount does not fit Int." }
    return byteCount.toInt()
}

private fun sameVariationDirection(coordinate: Double, peakValue: Double): Boolean =
    (coordinate < 0.0 && peakValue < 0.0) || (coordinate > 0.0 && peakValue > 0.0)

private data class TrueTypeGvarTupleHeader(
    val variationDataSize: Int,
    val tupleIndex: Int,
    val peak: DoubleArray,
    val startTuple: DoubleArray?,
    val endTuple: DoubleArray?,
)

private data class PackedGvarInts(
    val values: IntArray,
    val nextOffset: Int,
)

/**
 * TrueType `glyf` table scaler.
 *
 * @property face Parsed OpenType face data containing TrueType outline tables.
 */
class TrueTypeGlyfScaler(
    private val face: OpenTypeFaceData,
) : OutlineScaler {
    private val parsedScaler: ParsedTrueTypeGlyphScaler by lazy {
        val gvarTable = face.rawTableBytesOrNull("gvar")
        val normalizedAxisOrder = if (gvarTable != null) {
            require(face.variations.axes.isNotEmpty()) {
                "fvar variation axes are required to apply gvar for ${face.id.value}."
            }
            face.variations.axes.map { axis -> axis.tag.text }
        } else {
            emptyList()
        }

        TrueTypeGlyphScalerFactory.create(
            metrics = face.metrics,
            locaTable = face.requiredRawTableBytes("loca"),
            glyfTable = face.requiredRawTableBytes("glyf"),
            gvarTable = gvarTable,
            normalizedAxisOrder = normalizedAxisOrder,
        )
    }

    private val variationNormalizer: VariationNormalizer? by lazy {
        if (
            face.variations.axes.isNotEmpty() &&
            face.rawTables.keys.any { tag ->
                tag == SFNTTableTag("gvar") ||
                    tag == SFNTTableTag("HVAR") ||
                    tag == SFNTTableTag("VVAR") ||
                    tag == SFNTTableTag("MVAR")
            }
        ) {
            BoundedVariationNormalizer(
                face.variations.axes.map { axis ->
                    VariationAxis(
                        tag = axis.tag.text,
                        minimum = axis.minimum.value,
                        defaultValue = axis.defaultValue.value,
                        maximum = axis.maximum.value,
                    )
                },
            )
        } else {
            null
        }
    }

    /**
     * Produces a TrueType glyph outline.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Variable-font axis position.
     * @return Scaled TrueType glyph outline.
     */
    override fun outline(glyphId: UInt, position: VariationPosition): GlyphOutline =
        parsedScaler.outline(
            glyphId = glyphId,
            position = position.normalizedForGvar(),
        )

    /**
     * Produces TrueType glyph metrics.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Variable-font axis position.
     * @return Scaled TrueType glyph metrics.
     */
    override fun metrics(glyphId: UInt, position: VariationPosition): GlyphMetrics =
        scaledGlyphEvidence(glyphId = glyphId, position = position).metrics
            ?: parsedScaler.metrics(
                glyphId = glyphId,
                position = position.normalizedForGvar(),
            )

    /**
     * Produces deterministic current-state evidence for one TrueType `glyf` glyph.
     *
     * Requested variation coordinates are recorded in user-space coordinates. When `gvar` and
     * `fvar` data are available, normalized coordinates are recorded separately in stable axis order.
     */
    fun scaledGlyphEvidence(
        glyphId: UInt,
        position: VariationPosition = VariationPosition(),
    ): ScaledTrueTypeGlyphEvidence {
        val normalizationDiagnostics = position.normalizationDiagnostics(glyphId)
        val normalizedPosition = if (normalizationDiagnostics.isEmpty()) {
            position.normalizedForGvar()
        } else {
            VariationPosition()
        }
        val evidence = parsedScaler.scaledGlyphEvidence(
            glyphId = glyphId,
            position = normalizedPosition,
            requestedPosition = position,
            additionalDiagnostics = normalizationDiagnostics + avarDiagnostics(),
            includeNormalizedVariationPosition = normalizationDiagnostics.isEmpty(),
        )
        val horizontalEvidence = attachHorizontalMetricsVariationEvidence(
            glyphId = glyphId,
            normalizedPosition = normalizedPosition,
            evidence = evidence,
        )
        return attachVerticalMetricsEvidence(
            glyphId = glyphId,
            normalizedPosition = normalizedPosition,
            evidence = horizontalEvidence,
        )
    }

    private fun VariationPosition.normalizedForGvar(): VariationPosition {
        val normalizer = variationNormalizer ?: return this
        return VariationPosition(axes = normalizer.normalize(this).applyAvarSegmentMaps())
    }

    private fun Map<String, Double>.applyAvarSegmentMaps(): Map<String, Double> {
        val maps = face.variations.axisSegmentMaps
        if (maps.isEmpty()) return this

        val remapped = LinkedHashMap<String, Double>(size)
        face.variations.axes
            .map { axis -> axis.tag.text }
            .sorted()
            .forEach { tag ->
                val value = this[tag] ?: return@forEach
                val axisIndex = face.variations.axes.indexOfFirst { axis -> axis.tag.text == tag }
                val segments = maps.getOrNull(axisIndex)?.segments.orEmpty()
                remapped[tag] = remapAvarCoordinate(value, segments)
            }
        return remapped
    }

    private fun VariationPosition.normalizationDiagnostics(glyphId: UInt): List<FontScalerDiagnostic> {
        if (variationNormalizer == null) {
            return emptyList()
        }
        val supportedAxisTags = face.variations.axes.map { axis -> axis.tag.text }.toSet()
        return axes.toSortedMap().mapNotNull { (tag, value) ->
            when {
                tag !in supportedAxisTags -> FontScalerDiagnostic(
                    code = FontScalerDiagnosticCodes.VARIATION_AXIS_UNSUPPORTED,
                    detail = "truetype.gvar-axis",
                    operation = "variation",
                    glyphId = glyphId,
                )
                !value.isFinite() -> FontScalerDiagnostic(
                    code = FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED,
                    detail = "truetype.variation-position-non-finite",
                    operation = "variation",
                    glyphId = glyphId,
                )
                else -> null
            }
        }
    }

    private fun attachHorizontalMetricsVariationEvidence(
        glyphId: UInt,
        normalizedPosition: VariationPosition,
        evidence: ScaledTrueTypeGlyphEvidence,
    ): ScaledTrueTypeGlyphEvidence {
        val metrics = evidence.metrics ?: return evidence
        val horizontalEvidence = resolveHorizontalMetricsVariation(
            glyphId = glyphId,
            normalizedPosition = normalizedPosition,
        )
        return evidence.copy(
            metrics = metrics.copy(
                advanceX = horizontalEvidence.advanceX ?: metrics.advanceX,
            ),
            diagnostics = evidence.diagnostics + horizontalEvidence.diagnostics,
        )
    }

    private fun resolveHorizontalMetricsVariation(
        glyphId: UInt,
        normalizedPosition: VariationPosition,
    ): ResolvedHorizontalMetricsEvidence {
        if (normalizedPosition.axes.values.none { value -> value != 0.0 }) {
            return ResolvedHorizontalMetricsEvidence()
        }
        val hvarBytes = face.rawTableBytesOrNull("HVAR") ?: return ResolvedHorizontalMetricsEvidence()
        val baseAdvance = face.metrics.horizontalMetrics
            .firstOrNull { metric -> metric.glyphId.toUInt() == glyphId }
            ?.advanceWidth
            ?.toDouble()
            ?: return ResolvedHorizontalMetricsEvidence()
        return runCatching {
            parseHvarTable(hvarBytes).advanceWidthDelta(
                glyphId = glyphId,
                axisTags = face.variations.axes.map { axis -> axis.tag.text },
                position = normalizedPosition,
            )
        }.fold(
            onSuccess = { delta ->
                ResolvedHorizontalMetricsEvidence(advanceX = baseAdvance + delta)
            },
            onFailure = {
                ResolvedHorizontalMetricsEvidence(
                    diagnostics = listOf(
                        FontScalerDiagnostic(
                            code = FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED,
                            detail = "truetype.hvar-table-malformed",
                            operation = "metrics",
                            glyphId = glyphId,
                            severity = "warning",
                        ),
                    ),
                )
            },
        )
    }

    private fun avarDiagnostics(): List<FontScalerDiagnostic> = emptyList()

    private fun attachVerticalMetricsEvidence(
        glyphId: UInt,
        normalizedPosition: VariationPosition,
        evidence: ScaledTrueTypeGlyphEvidence,
    ): ScaledTrueTypeGlyphEvidence {
        val metrics = evidence.metrics ?: return evidence
        val verticalEvidence = resolveVerticalMetrics(
            glyphId = glyphId,
            normalizedPosition = normalizedPosition,
            metrics = metrics,
        )
        return evidence.copy(
            metrics = metrics.copy(
                advanceY = verticalEvidence.metrics?.verticalAdvance ?: metrics.advanceY,
                verticalMetrics = verticalEvidence.metrics,
            ),
            diagnostics = evidence.diagnostics + verticalEvidence.diagnostics,
        )
    }

    private fun resolveVerticalMetrics(
        glyphId: UInt,
        normalizedPosition: VariationPosition,
        metrics: GlyphMetrics,
    ): ResolvedVerticalMetricsEvidence {
        val malformedVerticalTables = face.diagnostics.any { diagnostic ->
            (diagnostic.table == SFNTTableTag("vhea") || diagnostic.table == SFNTTableTag("vmtx")) &&
                diagnostic.causeCode == "font.sfnt.optional-table-malformed"
        }
        val verticalMetric = face.metrics.verticalMetrics.firstOrNull { metric -> metric.glyphId.toUInt() == glyphId }
        if (verticalMetric == null) {
            if (malformedVerticalTables) {
                return ResolvedVerticalMetricsEvidence(
                    metrics = GlyphVerticalMetrics(
                        state = "diagnostic",
                        source = "vhea-vmtx",
                        diagnostics = listOf("truetype.vertical-metrics-malformed"),
                    ),
                    diagnostics = listOf(
                        FontScalerDiagnostic(
                            code = FontScalerDiagnosticCodes.VERTICAL_METRICS_UNAVAILABLE,
                            detail = "truetype.vertical-metrics-malformed",
                            operation = "metrics",
                            glyphId = glyphId,
                            severity = "warning",
                        ),
                    ),
                )
            }
            return ResolvedVerticalMetricsEvidence(
                metrics = GlyphVerticalMetrics(
                    state = "fallback",
                    source = "horizontal-fallback-fact",
                    diagnostics = listOf("truetype.vertical-metrics-absent"),
                ),
                diagnostics = listOf(
                    FontScalerDiagnostic(
                        code = FontScalerDiagnosticCodes.VERTICAL_METRICS_UNAVAILABLE,
                        detail = "truetype.vertical-metrics-absent",
                        operation = "metrics",
                        glyphId = glyphId,
                        severity = "warning",
                    ),
                ),
            )
        }

        val baseAdvance = verticalMetric.advanceHeight.toDouble()
        val topSideBearing = verticalMetric.topSideBearing.toDouble()
        var state = "present"
        var verticalAdvance = baseAdvance
        var ascender = face.metrics.verticalAscender?.toDouble()
        var descender = face.metrics.verticalDescender?.toDouble()
        var lineGap = face.metrics.verticalLineGap?.toDouble()
        val diagnostics = mutableListOf<FontScalerDiagnostic>()
        val detailTokens = mutableListOf<String>()
        if (normalizedPosition.axes.values.any { value -> value != 0.0 }) {
            val vvarBytes = face.rawTableBytesOrNull("VVAR")
            if (vvarBytes == null) {
                state = "diagnostic"
                detailTokens += "truetype.vvar-unavailable"
                diagnostics += FontScalerDiagnostic(
                    code = FontScalerDiagnosticCodes.METRICS_VARIATION_UNAVAILABLE,
                    detail = "truetype.vvar-unavailable",
                    operation = "metrics",
                    glyphId = glyphId,
                    severity = "warning",
                )
            } else {
                runCatching {
                    parseVvarTable(vvarBytes).advanceHeightDelta(
                        glyphId = glyphId,
                        axisTags = face.variations.axes.map { axis -> axis.tag.text },
                        position = normalizedPosition,
                    )
                }.onSuccess { delta ->
                    verticalAdvance += delta
                }.onFailure {
                    state = "diagnostic"
                    detailTokens += "truetype.vvar-table-malformed"
                    diagnostics += FontScalerDiagnostic(
                        code = FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED,
                        detail = "truetype.vvar-table-malformed",
                        operation = "metrics",
                        glyphId = glyphId,
                        severity = "warning",
                    )
                }
            }
            face.rawTableBytesOrNull("MVAR")?.let { mvarBytes ->
                runCatching {
                    parseMvarTable(mvarBytes)
                }.onSuccess { table ->
                    val axisTags = face.variations.axes.map { axis -> axis.tag.text }
                    ascender = table.adjustedMetricOrNull("vasc", axisTags, normalizedPosition, ascender)
                    descender = table.adjustedMetricOrNull("vdsc", axisTags, normalizedPosition, descender)
                    lineGap = table.adjustedMetricOrNull("vlgp", axisTags, normalizedPosition, lineGap)
                }.onFailure {
                    state = "diagnostic"
                    detailTokens += "truetype.mvar-table-malformed"
                    diagnostics += FontScalerDiagnostic(
                        code = FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED,
                        detail = "truetype.mvar-table-malformed",
                        operation = "metrics",
                        glyphId = glyphId,
                        severity = "warning",
                    )
                }
            }
        }

        return ResolvedVerticalMetricsEvidence(
            metrics = GlyphVerticalMetrics(
                state = state,
                source = "vhea-vmtx",
                verticalAdvance = verticalAdvance,
                topSideBearing = topSideBearing,
                verticalOriginY = topSideBearing + metrics.bounds.bottom,
                ascender = ascender,
                descender = descender,
                lineGap = lineGap,
                maxAdvanceHeight = face.metrics.maxAdvanceHeight?.toDouble(),
                diagnostics = detailTokens,
            ),
            diagnostics = diagnostics,
        )
    }
}

private data class ResolvedVerticalMetricsEvidence(
    val metrics: GlyphVerticalMetrics?,
    val diagnostics: List<FontScalerDiagnostic> = emptyList(),
)

private data class ResolvedHorizontalMetricsEvidence(
    val advanceX: Double? = null,
    val diagnostics: List<FontScalerDiagnostic> = emptyList(),
)

private fun remapAvarCoordinate(
    coordinate: Double,
    segments: List<org.graphiks.kalligraphie.font.sfnt.OpenTypeAvarSegment>,
): Double {
    if (segments.isEmpty()) return coordinate

    val sorted = segments.sortedBy { segment -> segment.fromCoordinate }
    if (coordinate <= sorted.first().fromCoordinate) {
        return sorted.first().toCoordinate.coerceIn(-1.0, 1.0)
    }
    if (coordinate >= sorted.last().fromCoordinate) {
        return sorted.last().toCoordinate.coerceIn(-1.0, 1.0)
    }

    val upperIndex = sorted.indexOfFirst { segment -> coordinate <= segment.fromCoordinate }
    val lower = sorted[upperIndex - 1]
    val upper = sorted[upperIndex]
    val fromRange = upper.fromCoordinate - lower.fromCoordinate
    if (fromRange == 0.0) {
        return upper.toCoordinate.coerceIn(-1.0, 1.0)
    }
    val ratio = (coordinate - lower.fromCoordinate) / fromRange
    return (lower.toCoordinate + ratio * (upper.toCoordinate - lower.toCoordinate)).coerceIn(-1.0, 1.0)
}

private fun OpenTypeFaceData.requiredRawTableBytes(tag: String): ByteArray =
    rawTableBytesOrNull(tag)
        ?: throw IllegalArgumentException(
            "TrueType glyf scaler for ${id.value} requires raw `$tag` table bytes.",
        )

private fun OpenTypeFaceData.rawTableBytesOrNull(tag: String): ByteArray? =
    rawTables[SFNTTableTag(tag)]?.toRawSfntTableBytes(tag)

private data class HvarTable(
    val axisCount: Int,
    val regions: List<VvarVariationRegion>,
    val itemVariationData: List<VvarItemVariationData>,
    val advanceWidthMapping: DeltaSetIndexMap?,
) {
    fun advanceWidthDelta(
        glyphId: UInt,
        axisTags: List<String>,
        position: VariationPosition,
    ): Double {
        require(axisTags.size == axisCount) {
            "HVAR axis tag count ${axisTags.size} does not match axisCount $axisCount."
        }
        val deltaSetIndex = advanceWidthMapping?.lookup(glyphId.toInt()) ?: DeltaSetIndex(
            outerIndex = 0,
            innerIndex = glyphId.toInt(),
        )
        val deltaSet = itemVariationData.getOrNull(deltaSetIndex.outerIndex)
            ?: throw IllegalArgumentException(
                "HVAR advanceWidth outer index ${deltaSetIndex.outerIndex} is outside itemVariationData count ${itemVariationData.size}.",
            )
        return deltaSet.delta(
            innerIndex = deltaSetIndex.innerIndex,
            regions = regions,
            axisTags = axisTags,
            position = position,
        )
    }
}

private fun parseHvarTable(data: ByteArray): HvarTable {
    require(data.size >= 20) { "HVAR table must contain at least 20 bytes." }
    val majorVersion = readUInt16(data, 0)
    val minorVersion = readUInt16(data, 2)
    require(majorVersion == 1 && minorVersion == 0) {
        "HVAR version must be 1.0, was $majorVersion.$minorVersion."
    }
    val itemVariationStoreOffset = readCFFUInt32AsInt(data, 4, "HVAR itemVariationStoreOffset")
    val advanceWidthMappingOffset = readCFFUInt32AsInt(data, 8, "HVAR advanceWidthMappingOffset")
    val itemVariationStore = parseVvarItemVariationStore(data = data, offset = itemVariationStoreOffset)
    val advanceWidthMapping = if (advanceWidthMappingOffset == 0) {
        null
    } else {
        parseDeltaSetIndexMap(data = data, offset = advanceWidthMappingOffset)
    }
    return HvarTable(
        axisCount = itemVariationStore.axisCount,
        regions = itemVariationStore.regions,
        itemVariationData = itemVariationStore.itemVariationData,
        advanceWidthMapping = advanceWidthMapping,
    )
}

private data class MvarTable(
    val axisCount: Int,
    val regions: List<VvarVariationRegion>,
    val itemVariationData: List<VvarItemVariationData>,
    val valueRecords: Map<String, DeltaSetIndex>,
) {
    fun adjustedMetricOrNull(
        valueTag: String,
        axisTags: List<String>,
        position: VariationPosition,
        baseValue: Double?,
    ): Double? {
        val metric = baseValue ?: return null
        val deltaSetIndex = valueRecords[valueTag] ?: return metric
        require(axisTags.size == axisCount) {
            "MVAR axis tag count ${axisTags.size} does not match axisCount $axisCount."
        }
        val deltaSet = itemVariationData.getOrNull(deltaSetIndex.outerIndex)
            ?: throw IllegalArgumentException(
                "MVAR value tag $valueTag outer index ${deltaSetIndex.outerIndex} is outside itemVariationData count ${itemVariationData.size}.",
            )
        return metric + deltaSet.delta(
            innerIndex = deltaSetIndex.innerIndex,
            regions = regions,
            axisTags = axisTags,
            position = position,
        )
    }
}

private fun parseMvarTable(data: ByteArray): MvarTable {
    require(data.size >= 12) { "MVAR table must contain at least 12 bytes." }
    val majorVersion = readUInt16(data, 0)
    val minorVersion = readUInt16(data, 2)
    require(majorVersion == 1 && minorVersion == 0) {
        "MVAR version must be 1.0, was $majorVersion.$minorVersion."
    }
    val valueRecordSize = readUInt16(data, 6)
    val valueRecordCount = readUInt16(data, 8)
    val itemVariationStoreOffset = readUInt16(data, 10)
    require(valueRecordSize >= 8) { "MVAR valueRecordSize must be at least 8 bytes." }
    require(valueRecordCount == 0 || itemVariationStoreOffset > 0) {
        "MVAR itemVariationStoreOffset must be non-zero when valueRecordCount is positive."
    }
    requireCFFAvailable(data, 12, valueRecordCount * valueRecordSize, "MVAR value records")
    val itemVariationStore = parseVvarItemVariationStore(data = data, offset = itemVariationStoreOffset)
    val records = LinkedHashMap<String, DeltaSetIndex>(valueRecordCount)
    var previousTag: String? = null
    repeat(valueRecordCount) { index ->
        val recordOffset = 12 + index * valueRecordSize
        val valueTag = String(data, recordOffset, 4, Charsets.US_ASCII)
        require(valueTag.length == 4 && valueTag.all { ch -> ch.code in 0x20..0x7e }) {
            "MVAR value tag at index $index must contain four printable ASCII characters."
        }
        if (previousTag != null) {
            require(previousTag!! < valueTag) {
                "MVAR value records must be strictly ordered by valueTag."
            }
        }
        previousTag = valueTag
        records[valueTag] = DeltaSetIndex(
            outerIndex = readUInt16(data, recordOffset + 4),
            innerIndex = readUInt16(data, recordOffset + 6),
        )
    }
    return MvarTable(
        axisCount = itemVariationStore.axisCount,
        regions = itemVariationStore.regions,
        itemVariationData = itemVariationStore.itemVariationData,
        valueRecords = records,
    )
}

private data class VvarTable(
    val axisCount: Int,
    val regions: List<VvarVariationRegion>,
    val itemVariationData: List<VvarItemVariationData>,
    val advanceHeightMapping: DeltaSetIndexMap?,
) {
    fun advanceHeightDelta(
        glyphId: UInt,
        axisTags: List<String>,
        position: VariationPosition,
    ): Double {
        require(axisTags.size == axisCount) {
            "VVAR axis tag count ${axisTags.size} does not match axisCount $axisCount."
        }
        val deltaSetIndex = advanceHeightMapping?.lookup(glyphId.toInt()) ?: DeltaSetIndex(outerIndex = 0, innerIndex = glyphId.toInt())
        val deltaSet = itemVariationData.getOrNull(deltaSetIndex.outerIndex)
            ?: throw IllegalArgumentException(
                "VVAR advanceHeight outer index ${deltaSetIndex.outerIndex} is outside itemVariationData count ${itemVariationData.size}.",
            )
        return deltaSet.delta(
            innerIndex = deltaSetIndex.innerIndex,
            regions = regions,
            axisTags = axisTags,
            position = position,
        )
    }
}

private data class VvarVariationRegion(
    val startCoordinates: List<Double>,
    val peakCoordinates: List<Double>,
    val endCoordinates: List<Double>,
) {
    fun scalar(
        axisTags: List<String>,
        position: VariationPosition,
    ): Double {
        require(axisTags.size == peakCoordinates.size) {
            "VVAR variation axis count must match region coordinate count."
        }
        return peakCoordinates.indices.fold(1.0) { scalar, index ->
            scalar * axisScalar(
                coordinate = position.axes[axisTags[index]] ?: 0.0,
                start = startCoordinates[index],
                peak = peakCoordinates[index],
                end = endCoordinates[index],
            )
        }
    }
}

private data class VvarItemVariationData(
    val regionIndexes: List<Int>,
    val deltaSets: List<List<Int>>,
) {
    fun delta(
        innerIndex: Int,
        regions: List<VvarVariationRegion>,
        axisTags: List<String>,
        position: VariationPosition,
    ): Double {
        val deltaSet = deltaSets.getOrNull(innerIndex)
            ?: throw IllegalArgumentException(
                "VVAR delta set inner index $innerIndex is outside itemCount ${deltaSets.size}.",
            )
        return deltaSet.indices.sumOf { index ->
            val regionIndex = regionIndexes[index]
            val region = regions.getOrNull(regionIndex)
                ?: throw IllegalArgumentException(
                    "VVAR region index $regionIndex is outside region count ${regions.size}.",
                )
            deltaSet[index].toDouble() * region.scalar(axisTags = axisTags, position = position)
        }
    }
}

private data class DeltaSetIndexMap(
    val entries: List<DeltaSetIndex>,
) {
    fun lookup(targetIndex: Int): DeltaSetIndex {
        require(targetIndex >= 0) { "VVAR target index must be non-negative." }
        require(entries.isNotEmpty()) { "VVAR DeltaSetIndexMap must contain at least one entry." }
        return entries[min(targetIndex, entries.lastIndex)]
    }
}

private data class DeltaSetIndex(
    val outerIndex: Int,
    val innerIndex: Int,
)

private fun parseVvarTable(data: ByteArray): VvarTable {
    require(data.size >= 24) { "VVAR table must contain at least 24 bytes." }
    val majorVersion = readUInt16(data, 0)
    val minorVersion = readUInt16(data, 2)
    require(majorVersion == 1 && minorVersion == 0) {
        "VVAR version must be 1.0, was $majorVersion.$minorVersion."
    }
    val itemVariationStoreOffset = readCFFUInt32AsInt(data, 4, "VVAR itemVariationStoreOffset")
    val advanceHeightMappingOffset = readCFFUInt32AsInt(data, 8, "VVAR advanceHeightMappingOffset")
    val itemVariationStore = parseVvarItemVariationStore(data = data, offset = itemVariationStoreOffset)
    val advanceHeightMapping = if (advanceHeightMappingOffset == 0) {
        null
    } else {
        parseDeltaSetIndexMap(data = data, offset = advanceHeightMappingOffset)
    }
    return VvarTable(
        axisCount = itemVariationStore.axisCount,
        regions = itemVariationStore.regions,
        itemVariationData = itemVariationStore.itemVariationData,
        advanceHeightMapping = advanceHeightMapping,
    )
}

private data class ParsedVvarItemVariationStore(
    val axisCount: Int,
    val regions: List<VvarVariationRegion>,
    val itemVariationData: List<VvarItemVariationData>,
)

private fun parseVvarItemVariationStore(
    data: ByteArray,
    offset: Int,
): ParsedVvarItemVariationStore {
    requireCFFAvailable(data, offset, 8, "VVAR ItemVariationStore")
    val format = readUInt16(data, offset)
    require(format == 1) { "VVAR ItemVariationStore format must be 1." }
    val regionListOffset = readCFFUInt32AsInt(data, offset + 2, "VVAR item variation region list offset")
    val itemVariationDataCount = readUInt16(data, offset + 6)
    require(itemVariationDataCount > 0) { "VVAR ItemVariationStore must contain ItemVariationData." }
    requireCFFAvailable(
        data,
        offset + 8,
        itemVariationDataCount * 4,
        "VVAR item variation data offsets",
    )
    val itemVariationDataOffsets = (0 until itemVariationDataCount).map { index ->
        readCFFUInt32AsInt(
            data,
            offset + 8 + index * 4,
            "VVAR item variation data offset[$index]",
        )
    }
    val (axisCount, regions) = parseVvarVariationRegionList(
        data = data,
        offset = offset + regionListOffset,
    )
    val itemVariationData = itemVariationDataOffsets.mapIndexed { index, relativeOffset ->
        parseVvarItemVariationData(
            data = data,
            offset = offset + relativeOffset,
            regionCount = regions.size,
            label = "VVAR item variation data[$index]",
        )
    }
    return ParsedVvarItemVariationStore(
        axisCount = axisCount,
        regions = regions,
        itemVariationData = itemVariationData,
    )
}

private fun parseVvarVariationRegionList(
    data: ByteArray,
    offset: Int,
): Pair<Int, List<VvarVariationRegion>> {
    requireCFFAvailable(data, offset, 4, "VVAR VariationRegionList")
    val axisCount = readUInt16(data, offset)
    val regionCount = readUInt16(data, offset + 2)
    require(axisCount > 0) { "VVAR VariationRegionList axisCount must be positive." }
    val regionRecordSize = axisCount * 6
    requireCFFAvailable(data, offset + 4, regionCount * regionRecordSize, "VVAR variation regions")
    val regions = (0 until regionCount).map { regionIndex ->
        val recordOffset = offset + 4 + regionIndex * regionRecordSize
        val start = MutableList(axisCount) { 0.0 }
        val peak = MutableList(axisCount) { 0.0 }
        val end = MutableList(axisCount) { 0.0 }
        repeat(axisCount) { axisIndex ->
            val axisOffset = recordOffset + axisIndex * 6
            start[axisIndex] = readCFFF2Dot14(data, axisOffset)
            peak[axisIndex] = readCFFF2Dot14(data, axisOffset + 2)
            end[axisIndex] = readCFFF2Dot14(data, axisOffset + 4)
        }
        VvarVariationRegion(
            startCoordinates = start,
            peakCoordinates = peak,
            endCoordinates = end,
        )
    }
    return axisCount to regions
}

private fun parseVvarItemVariationData(
    data: ByteArray,
    offset: Int,
    regionCount: Int,
    label: String,
): VvarItemVariationData {
    requireCFFAvailable(data, offset, 6, label)
    val itemCount = readUInt16(data, offset)
    val wordDeltaCount = readUInt16(data, offset + 2)
    val regionIndexCount = readUInt16(data, offset + 4)
    require(regionIndexCount > 0) { "$label regionIndexCount must be positive." }
    requireCFFAvailable(data, offset + 6, regionIndexCount * 2, "$label region indexes")
    val regionIndexes = (0 until regionIndexCount).map { index ->
        readUInt16(data, offset + 6 + index * 2).also { regionIndex ->
            require(regionIndex < regionCount) {
                "$label region index $regionIndex is outside region count $regionCount."
            }
        }
    }
    val longWords = wordDeltaCount and 0x8000 != 0
    val wordCount = wordDeltaCount and 0x7fff
    require(wordCount <= regionIndexCount) { "$label wordDeltaCount exceeds regionIndexCount." }
    val bytesPerDeltaSet = if (longWords) {
        wordCount * 4 + (regionIndexCount - wordCount) * 2
    } else {
        wordCount * 2 + (regionIndexCount - wordCount)
    }
    val deltaSetsOffset = offset + 6 + regionIndexCount * 2
    requireCFFAvailable(data, deltaSetsOffset, itemCount * bytesPerDeltaSet, "$label delta sets")
    val deltaSets = (0 until itemCount).map { itemIndex ->
        var cursor = deltaSetsOffset + itemIndex * bytesPerDeltaSet
        buildList(regionIndexCount) {
            repeat(wordCount) {
                val value = if (longWords) {
                    readInt32(data, cursor)
                } else {
                    readInt16(data, cursor)
                }
                add(value)
                cursor += if (longWords) 4 else 2
            }
            repeat(regionIndexCount - wordCount) {
                add(data[cursor].toInt())
                cursor += 1
            }
        }
    }
    return VvarItemVariationData(
        regionIndexes = regionIndexes,
        deltaSets = deltaSets,
    )
}

private fun parseDeltaSetIndexMap(
    data: ByteArray,
    offset: Int,
): DeltaSetIndexMap {
    requireCFFAvailable(data, offset, 4, "DeltaSetIndexMap header")
    val format = data[offset].toInt() and 0xff
    val entryFormat = data[offset + 1].toInt() and 0xff
    val mapCount = when (format) {
        0 -> readUInt16(data, offset + 2)
        1 -> readCFFUInt32AsInt(data, offset + 2, "DeltaSetIndexMap mapCount")
        else -> throw IllegalArgumentException("DeltaSetIndexMap format $format is unsupported.")
    }
    val entrySize = ((entryFormat and 0x30) shr 4) + 1
    val mapDataOffset = if (format == 0) offset + 4 else offset + 6
    requireCFFAvailable(data, mapDataOffset, mapCount * entrySize, "DeltaSetIndexMap data")
    val innerBitCount = (entryFormat and 0x0f) + 1
    val entries = (0 until mapCount).map { index ->
        var entry = 0
        repeat(entrySize) { byteIndex ->
            entry = (entry shl 8) or (data[mapDataOffset + index * entrySize + byteIndex].toInt() and 0xff)
        }
        val outerIndex = entry ushr innerBitCount
        val innerIndexMask = (1 shl innerBitCount) - 1
        DeltaSetIndex(
            outerIndex = outerIndex,
            innerIndex = entry and innerIndexMask,
        )
    }
    return DeltaSetIndexMap(entries = entries)
}

private fun List<Int>.toRawSfntTableBytes(tag: String): ByteArray =
    mapIndexed { index, value ->
        require(value in 0..0xff) {
            "raw `$tag` table byte at index $index must be in 0..255, found $value."
        }
        value.toByte()
    }.toByteArray()

/**
 * Compact Font Format `CFF ` scaler.
 *
 * @property face Parsed OpenType face data containing CFF outlines.
 */
class CFFScaler(
    private val face: OpenTypeFaceData,
) : OutlineScaler {
    private val parsedCFF: ParsedCFFProgram? by lazy {
        face.rawTableBytesOrNull("CFF ")?.let(::parseCFFTable)
    }

    /**
     * Emits deterministic parser provenance for the selected CFF table.
     *
     * This is generated-fixture evidence only; it is not a complete CFF support claim.
     */
    fun tableEvidence(): CFFTableEvidence =
        cffTableEvidence(
            format = "cff",
            displayFormat = "CFF",
            face = face,
            programProvider = { parsedCFF },
            variationAxisTags = emptyList(),
        )

    /**
     * Produces a CFF glyph outline.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Variation position for synthetic or blended metrics when applicable.
     * @return Scaled CFF glyph outline.
     */
    override fun outline(glyphId: UInt, position: VariationPosition): GlyphOutline =
        parsedCFF?.let { program ->
            CFFType2CharStringInterpreter(
                localSubroutines = program.localSubroutines,
                globalSubroutines = program.globalSubroutines,
            ).interpretOutline(
                charString = program.charString(glyphId),
                glyphId = glyphId,
                position = position,
            )
        } ?: unsupportedCFFGlyph("CFF", "outline", face, glyphId, position)

    /**
     * Produces CFF glyph metrics.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Variation position for synthetic or blended metrics when applicable.
     * @return Scaled CFF glyph metrics.
     */
    override fun metrics(glyphId: UInt, position: VariationPosition): GlyphMetrics =
        parsedCFF?.let { program ->
            val interpreter = CFFType2CharStringInterpreter(
                localSubroutines = program.localSubroutines,
                globalSubroutines = program.globalSubroutines,
            )
            val charString = program.charString(glyphId)
            cffGlyphMetrics(
                face = face,
                glyphId = glyphId,
                outline = interpreter.interpretOutline(
                    charString = charString,
                    glyphId = glyphId,
                    position = position,
                ),
                width = interpreter.interpretEvidence(
                    charString = charString,
                    glyphId = glyphId,
                    format = "cff",
                    position = position,
                ).width,
            )
        } ?: unsupportedCFFGlyph("CFF", "metrics", face, glyphId, position)

    /**
     * Produces deterministic current-state evidence for one CFF glyph route.
     *
     * This remains generated-fixture evidence only; it does not promote complete real-font CFF
     * support, fallback font selection, or GPU handoff readiness.
     */
    fun scaledGlyphEvidence(
        glyphId: UInt,
        position: VariationPosition = VariationPosition(),
    ): CFFScaledGlyphEvidence =
        scaledCFFGlyphEvidence(
            face = face,
            format = "cff",
            program = parsedCFF,
            glyphId = glyphId,
            position = position,
            interpreterProvider = { program ->
                CFFType2CharStringInterpreter(
                    localSubroutines = program.localSubroutines,
                    globalSubroutines = program.globalSubroutines,
                )
            },
        )
}

/**
 * Compact Font Format 2 scaler for variable CFF outlines.
 *
 * @property face Parsed OpenType face data containing CFF2 outlines.
 */
class CFF2Scaler(
    private val face: OpenTypeFaceData,
) : OutlineScaler {
    private val parsedCFF2: ParsedCFFProgram? by lazy {
        face.rawTableBytesOrNull("CFF2")?.let(::parseCFF2Table)
    }
    private val variationNormalizer: VariationNormalizer? by lazy {
        if (face.variations.axes.isEmpty()) {
            null
        } else {
            BoundedVariationNormalizer(
                face.variations.axes.map { axis ->
                    VariationAxis(
                        tag = axis.tag.text,
                        minimum = axis.minimum.value,
                        defaultValue = axis.defaultValue.value,
                        maximum = axis.maximum.value,
                    )
                },
            )
        }
    }

    /**
     * Emits deterministic parser provenance for the selected CFF2 table.
     *
     * This is generated-fixture evidence only; it is not a complete CFF2 support claim.
     */
    fun tableEvidence(): CFFTableEvidence =
        cffTableEvidence(
            format = "cff2",
            displayFormat = "CFF2",
            face = face,
            programProvider = { parsedCFF2 },
            variationAxisTags = face.variations.axes.map { axis -> axis.tag.text }.sorted(),
        )

    /**
     * Produces a CFF2 glyph outline.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Variable-font axis position.
     * @return Scaled CFF2 glyph outline.
     */
    override fun outline(glyphId: UInt, position: VariationPosition): GlyphOutline =
        parsedCFF2?.let { program ->
            val normalizedPosition = position.normalizedForCff2()
            val axisTags = face.variations.axes.map { axis -> axis.tag.text }
            CFFType2CharStringInterpreter(
                localSubroutines = program.localSubroutines,
                globalSubroutines = program.globalSubroutines,
                blendAxisTagsByVsIndex = mapOf(
                    0 to axisTags.sorted(),
                ),
                blendScalarProvider = program.variationStore?.let { store ->
                    { vsIndex, variationPosition -> store.resolution(vsIndex, axisTags, variationPosition) }
                } ?: { _, _ -> throw CFF2VariationStoreException("cff2.variation-store-missing") },
            ).interpretOutline(
                charString = program.charString(glyphId),
                glyphId = glyphId,
                position = normalizedPosition,
            )
        } ?: unsupportedCFFGlyph("CFF2", "outline", face, glyphId, position)

    /**
     * Produces CFF2 glyph metrics.
     *
     * @param glyphId Font-specific glyph identifier.
     * @param position Variable-font axis position.
     * @return Scaled CFF2 glyph metrics.
     */
    override fun metrics(glyphId: UInt, position: VariationPosition): GlyphMetrics =
        parsedCFF2?.let { program ->
            val normalizedPosition = position.normalizedForCff2()
            val axisTags = face.variations.axes.map { axis -> axis.tag.text }
            val interpreter = CFFType2CharStringInterpreter(
                localSubroutines = program.localSubroutines,
                globalSubroutines = program.globalSubroutines,
                blendAxisTagsByVsIndex = mapOf(
                    0 to axisTags.sorted(),
                ),
                blendScalarProvider = program.variationStore?.let { store ->
                    { vsIndex, variationPosition -> store.resolution(vsIndex, axisTags, variationPosition) }
                } ?: { _, _ -> throw CFF2VariationStoreException("cff2.variation-store-missing") },
            )
            val charString = program.charString(glyphId)
            cffGlyphMetrics(
                face = face,
                glyphId = glyphId,
                outline = interpreter.interpretOutline(
                    charString = charString,
                    glyphId = glyphId,
                    position = normalizedPosition,
                ),
                width = interpreter.interpretEvidence(
                    charString = charString,
                    glyphId = glyphId,
                    format = "cff2",
                    position = normalizedPosition,
                ).width,
            )
        } ?: unsupportedCFFGlyph("CFF2", "metrics", face, glyphId, position)

    /**
     * Produces deterministic current-state evidence for one CFF2 glyph route.
     *
     * This remains generated-fixture evidence only; it does not promote complete real-font CFF2
     * variation support, fallback font selection, or GPU handoff readiness.
     */
    fun scaledGlyphEvidence(
        glyphId: UInt,
        position: VariationPosition = VariationPosition(),
    ): CFFScaledGlyphEvidence {
        val normalizationDiagnostics = position.normalizationDiagnostics(glyphId)
        if (normalizationDiagnostics.isNotEmpty()) {
            return CFFScaledGlyphEvidence(
                sourceId = face.source.id.value.toString(),
                typefaceId = face.id.value.toString(),
                format = "cff2",
                requestedGlyphId = glyphId,
                resolvedGlyphId = null,
                outlineCommands = emptyList(),
                diagnostics = normalizationDiagnostics,
            )
        }
        val normalizedPosition = position.normalizedForCff2()
        return scaledCFFGlyphEvidence(
            face = face,
            format = "cff2",
            program = parsedCFF2,
            glyphId = glyphId,
            position = normalizedPosition,
            interpreterProvider = { program ->
                val axisTags = face.variations.axes.map { axis -> axis.tag.text }
                CFFType2CharStringInterpreter(
                    localSubroutines = program.localSubroutines,
                    globalSubroutines = program.globalSubroutines,
                    blendAxisTagsByVsIndex = mapOf(
                        0 to axisTags.sorted(),
                    ),
                    blendScalarProvider = program.variationStore?.let { store ->
                        { vsIndex, variationPosition -> store.resolution(vsIndex, axisTags, variationPosition) }
                    } ?: { _, _ -> throw CFF2VariationStoreException("cff2.variation-store-missing") },
                )
            },
        )
    }

    private fun VariationPosition.normalizedForCff2(): VariationPosition {
        val normalizer = variationNormalizer ?: return this
        return VariationPosition(axes = normalizer.normalize(this).applyAvarSegmentMaps())
    }

    private fun VariationPosition.normalizationDiagnostics(glyphId: UInt): List<FontScalerDiagnostic> {
        if (variationNormalizer == null) {
            return emptyList()
        }
        val supportedAxisTags = face.variations.axes.map { axis -> axis.tag.text }.toSet()
        return axes.toSortedMap().mapNotNull { (tag, value) ->
            when {
                tag !in supportedAxisTags -> FontScalerDiagnostic(
                    code = FontScalerDiagnosticCodes.VARIATION_AXIS_UNSUPPORTED,
                    detail = "cff2.variation-axis",
                    operation = "variation",
                    glyphId = glyphId,
                )
                !value.isFinite() -> FontScalerDiagnostic(
                    code = FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED,
                    detail = "cff2.variation-position-non-finite",
                    operation = "variation",
                    glyphId = glyphId,
                )
                else -> null
            }
        }
    }

    private fun Map<String, Double>.applyAvarSegmentMaps(): Map<String, Double> {
        val maps = face.variations.axisSegmentMaps
        if (maps.isEmpty()) return this

        val remapped = LinkedHashMap<String, Double>(size)
        face.variations.axes
            .map { axis -> axis.tag.text }
            .sorted()
            .forEach { tag ->
                val value = this[tag] ?: return@forEach
                val axisIndex = face.variations.axes.indexOfFirst { axis -> axis.tag.text == tag }
                val segments = maps.getOrNull(axisIndex)?.segments.orEmpty()
                remapped[tag] = remapAvarCoordinate(value, segments)
            }
        return remapped
    }
}

private fun cffGlyphMetrics(
    face: OpenTypeFaceData,
    glyphId: UInt,
    outline: GlyphOutline,
    width: Double?,
): GlyphMetrics {
    val metric = face.metrics.horizontalMetrics.firstOrNull { metric -> metric.glyphId.toUInt() == glyphId }
        ?: throw IllegalArgumentException("CFF glyph metrics missing for glyphId $glyphId.")
    return GlyphMetrics(
        advanceX = width ?: metric.advanceWidth.toDouble(),
        advanceY = 0.0,
        bounds = outline.commands.conservativeBounds()
            ?: GlyphBounds(left = 0.0, top = 0.0, right = 0.0, bottom = 0.0),
    )
}

private fun scaledCFFGlyphEvidence(
    face: OpenTypeFaceData,
    format: String,
    program: ParsedCFFProgram?,
    glyphId: UInt,
    position: VariationPosition,
    interpreterProvider: (ParsedCFFProgram) -> CFFType2CharStringInterpreter,
): CFFScaledGlyphEvidence {
    val sourceId = face.source.id.value.toString()
    val typefaceId = face.id.value.toString()
    val parsedProgram = program ?: return CFFScaledGlyphEvidence(
        sourceId = sourceId,
        typefaceId = typefaceId,
        format = format,
        requestedGlyphId = glyphId,
        resolvedGlyphId = null,
        outlineCommands = emptyList(),
        diagnostics = listOf(
            cffPathOutputUnavailableDiagnostic(
                glyphId = glyphId,
                operation = "outline",
                detail = "cff.path-output-unavailable",
            ),
        ),
    )
    if (!parsedProgram.hasGlyph(glyphId)) {
        return CFFScaledGlyphEvidence(
            sourceId = sourceId,
            typefaceId = typefaceId,
            format = format,
            requestedGlyphId = glyphId,
            resolvedGlyphId = null,
            outlineCommands = emptyList(),
            diagnostics = listOf(
                cffPathOutputUnavailableDiagnostic(
                    glyphId = glyphId,
                    operation = "outline",
                    detail = "cff.path-output-unavailable",
                ),
            ),
        )
    }

    val charString = parsedProgram.charString(glyphId)
    val interpreter = interpreterProvider(parsedProgram)
    val diagnostics = mutableListOf<FontScalerDiagnostic>()
    val charStringEvidence = runCatching {
        interpreter.interpretEvidence(
            charString = charString,
            glyphId = glyphId,
            format = format,
            position = position,
        )
    }.getOrElse { error ->
        val diagnostic = error.toFontScalerDiagnosticOrNull(
            glyphId = glyphId,
            operation = "charstring",
        )
        if (diagnostic != null) {
            diagnostics += diagnostic
            diagnostics += cffGlyphMalformedDiagnostic(glyphId = glyphId, operation = "outline")
            null
        } else {
            throw error
        }
    }

    val outline = charStringEvidence?.let {
        interpreter.interpretOutline(
            charString = charString,
            glyphId = glyphId,
            position = position,
        )
    }
    val metrics = if (outline != null && charStringEvidence != null) {
        runCatching {
            cffGlyphMetrics(
                face = face,
                glyphId = glyphId,
                outline = outline,
                width = charStringEvidence.width,
            )
        }.getOrElse { error ->
            val diagnostic = error.toFontScalerDiagnosticOrNull(
                glyphId = glyphId,
                operation = "metrics",
            )
            if (diagnostic != null) {
                diagnostics += diagnostic
                null
            } else {
                throw error
            }
        }
    } else {
        null
    }
    return CFFScaledGlyphEvidence(
        sourceId = sourceId,
        typefaceId = typefaceId,
        format = format,
        requestedGlyphId = glyphId,
        resolvedGlyphId = glyphId,
        outlineCommands = charStringEvidence?.outlineCommands.orEmpty(),
        conservativeBounds = outline?.conservativeBounds(),
        metrics = metrics,
        widthSource = when {
            metrics == null -> null
            charStringEvidence?.width != null -> "charstring"
            else -> "hmtx"
        },
        linkedCharStringTraceDumpId = if (charStringEvidence != null) CFF_CHARSTRING_TRACE_DUMP_ID else null,
        charStringEvidence = charStringEvidence,
        diagnostics = diagnostics.sortedWith(fontScalerDiagnosticOrdering),
    )
}

private data class ParsedCFFProgram(
    val charStrings: List<ByteArray>,
    val localSubroutines: List<ByteArray>,
    val globalSubroutines: List<ByteArray>,
    val hasPrivateDict: Boolean,
    val topDictOperators: List<String>,
    val tableFacts: ParsedCFFTableFacts,
    val variationStore: CFF2VariationStore? = null,
) {
    init {
        require(charStrings.isNotEmpty()) { "CFF charstring INDEX must not be empty." }
        require(topDictOperators == topDictOperators.sorted()) {
            "CFF top dict operator evidence must be sorted."
        }
    }

    fun hasGlyph(glyphId: UInt): Boolean =
        glyphId.toLong() <= Int.MAX_VALUE && glyphId.toInt() in charStrings.indices

    fun charString(glyphId: UInt): ByteArray {
        require(glyphId.toLong() <= Int.MAX_VALUE) { "CFF glyphId $glyphId does not fit Int." }
        val index = glyphId.toInt()
        require(index in charStrings.indices) {
            "CFF glyphId $glyphId is outside charstring INDEX size ${charStrings.size}."
        }
        return charStrings[index]
    }
}

private data class ParsedCFFTableFacts(
    val names: List<String>,
    val indexes: List<CFFIndexEvidence>,
    val topDicts: List<CFFDictEvidence>,
    val privateDicts: List<CFFPrivateDictEvidence>,
    val fdArray: List<CFFDictEvidence>,
    val fdSelect: CFFFDSelectEvidence?,
    val diagnostics: List<CFFTableDiagnosticEvidence> = emptyList(),
)

private fun parseCFFTable(data: ByteArray): ParsedCFFProgram {
    requireCFFAvailable(data, offset = 0, byteCount = 4, section = "header")
    require(data[0].toInt() and 0xff == 1) { "CFF header major version must be 1." }
    val headerSize = data[2].toInt() and 0xff
    require(headerSize >= 4) { "CFF header size must be at least 4." }
    requireCFFAvailable(data, offset = 0, byteCount = headerSize, section = "header")

    val nameIndex = readCFFIndex(data, headerSize, "Name INDEX")
    val topDictIndex = readCFFIndex(data, nameIndex.nextOffset, "Top DICT INDEX")
    require(topDictIndex.objects.size == 1) { "CFF Top DICT INDEX must contain exactly one dict." }
    val stringIndex = readCFFIndex(data, topDictIndex.nextOffset, "String INDEX")
    val globalSubrIndex = readCFFIndex(data, stringIndex.nextOffset, "Global Subr INDEX")
    val topDict = parseCFFDict(
        data = topDictIndex.objects.single(),
        section = "Top DICT",
        baseOffset = topDictIndex.objectRanges.single().start,
        objectIndex = 0,
    )
    val charStringsOffset = topDict.singleOperand(CFF_DICT_CHAR_STRINGS)
        ?: throw cffRequiredOperatorMissing(
            section = "Top DICT",
            operatorName = "CharStrings",
            byteRange = topDict.byteRange,
            objectIndex = topDict.objectIndex,
            message = "CFF Top DICT missing CharStrings offset.",
        )
    val indexes = mutableListOf(
        nameIndex.evidence(),
        topDictIndex.evidence(),
        stringIndex.evidence(),
        globalSubrIndex.evidence(),
    )
    val privateDicts = mutableListOf<CFFPrivateDictEvidence>()
    val localSubroutines = mutableListOf<ByteArray>()
    val privateLocation = topDict.privateLocation()
    if (privateLocation != null) {
        val (privateSize, privateOffset) = privateLocation
        requireCFFIndexAvailable(data, privateOffset, privateSize, "Private DICT")
        val privateDict = parseCFFDict(
            data = data.copyOfRange(privateOffset, privateOffset + privateSize),
            section = "Private DICT",
            baseOffset = privateOffset,
            objectIndex = null,
        )
        val localSubrIndex = privateDict.singleOperand(CFF_DICT_SUBRS)?.let { subrsOffset ->
            readCFFIndex(data, privateOffset + subrsOffset, "Local Subr INDEX")
        }
        localSubrIndex?.let { index ->
            indexes += index.evidence()
            localSubroutines += index.objects
        }
        privateDicts += privateDict.toPrivateDictEvidence(
            owner = "Top DICT",
            byteRange = CFFByteRangeEvidence(privateOffset, privateOffset + privateSize),
            localSubroutineIndex = localSubrIndex?.evidence(),
        )
    }
    val fdArray = topDict.singleOperand(CFF_DICT_FD_ARRAY)?.let { fdArrayOffset ->
        val fdArrayIndex = readCFFIndex(data, fdArrayOffset, "FDArray INDEX")
        indexes += fdArrayIndex.evidence()
        fdArrayIndex.objects.mapIndexed { index, objectBytes ->
            val fontDict = parseCFFDict(
                data = objectBytes,
                section = "FDArray Font DICT",
                baseOffset = fdArrayIndex.objectRanges[index].start,
                objectIndex = index,
            )
            fontDict.privateLocation()?.let { (privateSize, privateOffset) ->
                requireCFFIndexAvailable(data, privateOffset, privateSize, "FDArray[$index] Private DICT")
                val privateDict = parseCFFDict(
                    data = data.copyOfRange(privateOffset, privateOffset + privateSize),
                    section = "FDArray Private DICT",
                    baseOffset = privateOffset,
                    objectIndex = index,
                )
                val localSubrIndex = privateDict.singleOperand(CFF_DICT_SUBRS)?.let { subrsOffset ->
                    readCFFIndex(data, privateOffset + subrsOffset, "FDArray[$index] Local Subr INDEX")
                }
                localSubrIndex?.let { subrIndex -> indexes += subrIndex.evidence() }
                privateDicts += privateDict.toPrivateDictEvidence(
                    owner = "FDArray[$index]",
                    byteRange = CFFByteRangeEvidence(privateOffset, privateOffset + privateSize),
                    localSubroutineIndex = localSubrIndex?.evidence(),
                )
            }
            fontDict
        }
    } ?: emptyList()
    val charStringsIndex = readCFFIndex(data, charStringsOffset, "CharStrings INDEX")
    indexes += charStringsIndex.evidence()
    val fdSelect = topDict.singleOperand(CFF_DICT_FD_SELECT)?.let { fdSelectOffset ->
        parseCFFFDSelect(
            data = data,
            offset = fdSelectOffset,
            glyphCount = charStringsIndex.objects.size,
            fontDictCount = fdArray.size,
        )
    }
    return ParsedCFFProgram(
        charStrings = charStringsIndex.objects,
        localSubroutines = localSubroutines,
        globalSubroutines = globalSubrIndex.objects,
        hasPrivateDict = privateLocation != null,
        topDictOperators = topDict.operatorNames(),
        tableFacts = ParsedCFFTableFacts(
            names = nameIndex.objects.map { bytes -> bytes.toString(Charsets.US_ASCII) },
            indexes = indexes,
            topDicts = listOf(topDict.toEvidence()),
            privateDicts = privateDicts,
            fdArray = fdArray.map(CFFDict::toEvidence),
            fdSelect = fdSelect,
        ),
    )
}

private fun parseCFF2Table(data: ByteArray): ParsedCFFProgram {
    requireCFFAvailable(data, offset = 0, byteCount = 5, section = "CFF2 header")
    require(data[0].toInt() and 0xff == 2) { "CFF2 header major version must be 2." }
    val headerSize = data[2].toInt() and 0xff
    require(headerSize >= 5) { "CFF2 header size must be at least 5." }
    requireCFFAvailable(data, offset = 0, byteCount = headerSize, section = "CFF2 header")
    val topDictLength = readUInt16(data, 3)
    val topDictOffset = headerSize
    requireCFFAvailable(data, topDictOffset, topDictLength, "CFF2 Top DICT")
    val topDict = parseCFFDict(
        data = data.copyOfRange(topDictOffset, topDictOffset + topDictLength),
        section = "CFF2 Top DICT",
        baseOffset = topDictOffset,
        objectIndex = 0,
    )
    val globalSubrIndex = readCFFIndex(data, topDictOffset + topDictLength, "CFF2 Global Subr INDEX")
    val charStringsOffset = topDict.singleOperand(CFF_DICT_CHAR_STRINGS)
        ?: throw cffRequiredOperatorMissing(
            section = "CFF2 Top DICT",
            operatorName = "CharStrings",
            byteRange = topDict.byteRange,
            objectIndex = topDict.objectIndex,
            message = "CFF2 Top DICT missing CharStrings offset.",
        )
    val indexes = mutableListOf(globalSubrIndex.evidence())
    val privateDicts = mutableListOf<CFFPrivateDictEvidence>()
    val localSubroutines = mutableListOf<ByteArray>()
    val privateLocation = topDict.privateLocation()
    if (privateLocation != null) {
        val (privateSize, privateOffset) = privateLocation
        requireCFFIndexAvailable(data, privateOffset, privateSize, "CFF2 Private DICT")
        val privateDict = parseCFFDict(
            data = data.copyOfRange(privateOffset, privateOffset + privateSize),
            section = "CFF2 Private DICT",
            baseOffset = privateOffset,
            objectIndex = null,
        )
        val localSubrIndex = privateDict.singleOperand(CFF_DICT_SUBRS)?.let { subrsOffset ->
            readCFFIndex(data, privateOffset + subrsOffset, "CFF2 Local Subr INDEX")
        }
        localSubrIndex?.let { index ->
            indexes += index.evidence()
            localSubroutines += index.objects
        }
        privateDicts += privateDict.toPrivateDictEvidence(
            owner = "CFF2 Top DICT",
            byteRange = CFFByteRangeEvidence(privateOffset, privateOffset + privateSize),
            localSubroutineIndex = localSubrIndex?.evidence(),
        )
    }
    val variationStore = topDict.singleOperand(CFF_DICT_CFF2_VARIATION_STORE)
        ?.let { offset -> parseCFF2VariationStore(data, offset) }
    val charStringsIndex = readCFFIndex(data, charStringsOffset, "CFF2 CharStrings INDEX")
    indexes += charStringsIndex.evidence()
    return ParsedCFFProgram(
        charStrings = charStringsIndex.objects,
        localSubroutines = localSubroutines,
        globalSubroutines = globalSubrIndex.objects,
        hasPrivateDict = privateLocation != null,
        topDictOperators = topDict.operatorNames(),
        tableFacts = ParsedCFFTableFacts(
            names = emptyList(),
            indexes = indexes,
            topDicts = listOf(topDict.toEvidence()),
            privateDicts = privateDicts,
            fdArray = emptyList(),
            fdSelect = null,
        ),
        variationStore = variationStore,
    )
}

private data class CFF2VariationStore(
    val axisCount: Int,
    val regions: List<CFF2VariationRegion>,
    val regionIndexesByVsIndex: List<List<Int>>,
) {
    fun resolution(
        vsIndex: Int,
        axisTags: List<String>,
        position: VariationPosition,
    ): CFF2BlendResolution {
        if (axisTags.size != axisCount) {
            throw CFF2VariationStoreException("cff2.variation-axis-count")
        }
        val regionIndexes = regionIndexesByVsIndex.getOrNull(vsIndex)
            ?: throw CFF2VariationStoreException("cff2.vsindex-invalid")
        return CFF2BlendResolution(
            scalars = regionIndexes.map { regionIndex ->
                val region = regions.getOrNull(regionIndex)
                    ?: throw CFF2VariationStoreException("cff2.region-index-invalid")
                region.scalar(axisTags = axisTags, position = position)
            },
            regionIndexes = regionIndexes,
        )
    }
}

data class CFF2BlendResolution(
    val scalars: List<Double>,
    val regionIndexes: List<Int> = emptyList(),
) {
    init {
        require(regionIndexes.isEmpty() || regionIndexes.size == scalars.size) {
            "CFF2 blend resolution regionIndexes must be empty or align with scalar count."
        }
    }
}

private data class CFF2VariationRegion(
    val startCoordinates: List<Double>,
    val peakCoordinates: List<Double>,
    val endCoordinates: List<Double>,
) {
    init {
        require(startCoordinates.size == peakCoordinates.size && peakCoordinates.size == endCoordinates.size) {
            "CFF2 variation region coordinate counts must match."
        }
    }

    fun scalar(axisTags: List<String>, position: VariationPosition): Double {
        require(axisTags.size == peakCoordinates.size) {
            "CFF2 variation axis count must match region coordinate count."
        }
        return peakCoordinates.indices.fold(1.0) { scalar, index ->
            scalar * axisScalar(
                coordinate = position.axes[axisTags[index]] ?: 0.0,
                start = startCoordinates[index],
                peak = peakCoordinates[index],
                end = endCoordinates[index],
            )
        }
    }
}

private fun axisScalar(
    coordinate: Double,
    start: Double,
    peak: Double,
    end: Double,
): Double =
    when {
        start > peak || peak > end -> 1.0
        start < 0.0 && end > 0.0 && peak != 0.0 -> 1.0
        peak == 0.0 -> 1.0
        coordinate < start || coordinate > end -> 0.0
        coordinate == peak -> 1.0
        coordinate < peak -> (coordinate - start) / (peak - start)
        else -> (end - coordinate) / (end - peak)
    }

private class CFF2VariationStoreException(
    val detail: String,
) : IllegalArgumentException(detail)

private fun parseCFF2VariationStore(data: ByteArray, offset: Int): CFF2VariationStore {
    requireCFFAvailable(data, offset, 8, "CFF2 VariationStore")
    val format = readUInt16(data, offset)
    require(format == 1) { "CFF2 VariationStore format must be 1." }
    val regionListOffset = readCFFUInt32AsInt(data, offset + 2, "CFF2 VariationRegionList offset")
    val itemVariationDataCount = readUInt16(data, offset + 6)
    require(itemVariationDataCount > 0) { "CFF2 VariationStore must contain ItemVariationData." }
    requireCFFAvailable(data, offset + 8, itemVariationDataCount * 4, "CFF2 ItemVariationData offsets")
    val itemVariationDataOffsets = (0 until itemVariationDataCount).map { index ->
        readCFFUInt32AsInt(data, offset + 8 + index * 4, "CFF2 ItemVariationData offset")
    }
    val (axisCount, regions) = parseCFF2VariationRegionList(
        data = data,
        offset = offset + regionListOffset,
    )
    val regionIndexesByVsIndex = itemVariationDataOffsets.mapIndexed { index, relativeOffset ->
        require(relativeOffset > 0) { "CFF2 ItemVariationData offset $index must be non-zero." }
        parseCFF2ItemVariationData(
            data = data,
            offset = offset + relativeOffset,
            regionCount = regions.size,
        )
    }
    return CFF2VariationStore(
        axisCount = axisCount,
        regions = regions,
        regionIndexesByVsIndex = regionIndexesByVsIndex,
    )
}

private fun parseCFF2VariationRegionList(
    data: ByteArray,
    offset: Int,
): Pair<Int, List<CFF2VariationRegion>> {
    requireCFFAvailable(data, offset, 4, "CFF2 VariationRegionList")
    val axisCount = readUInt16(data, offset)
    val regionCount = readUInt16(data, offset + 2)
    require(axisCount > 0) { "CFF2 VariationRegionList axisCount must be positive." }
    require(regionCount > 0) { "CFF2 VariationRegionList regionCount must be positive." }
    var cursor = offset + 4
    val regions = (0 until regionCount).map {
        requireCFFAvailable(data, cursor, axisCount * 6, "CFF2 VariationRegion")
        val start = mutableListOf<Double>()
        val peak = mutableListOf<Double>()
        val end = mutableListOf<Double>()
        repeat(axisCount) {
            start += readCFFF2Dot14(data, cursor)
            peak += readCFFF2Dot14(data, cursor + 2)
            end += readCFFF2Dot14(data, cursor + 4)
            cursor += 6
        }
        CFF2VariationRegion(
            startCoordinates = start,
            peakCoordinates = peak,
            endCoordinates = end,
        )
    }
    return axisCount to regions
}

private fun parseCFF2ItemVariationData(
    data: ByteArray,
    offset: Int,
    regionCount: Int,
): List<Int> {
    requireCFFAvailable(data, offset, 6, "CFF2 ItemVariationData")
    val itemCount = readUInt16(data, offset)
    val wordDeltaCount = readUInt16(data, offset + 2)
    val regionIndexCount = readUInt16(data, offset + 4)
    require(regionIndexCount > 0) { "CFF2 ItemVariationData regionIndexCount must be positive." }
    requireCFFAvailable(data, offset + 6, regionIndexCount * 2, "CFF2 ItemVariationData region indexes")
    val regionIndexes = (0 until regionIndexCount).map { index ->
        readUInt16(data, offset + 6 + index * 2).also { regionIndex ->
            require(regionIndex < regionCount) {
                "CFF2 ItemVariationData region index $regionIndex is outside region count $regionCount."
            }
        }
    }
    val longWords = wordDeltaCount and 0x8000 != 0
    val wordCount = wordDeltaCount and 0x7fff
    require(wordCount <= regionIndexCount) { "CFF2 ItemVariationData wordDeltaCount exceeds regionIndexCount." }
    val bytesPerDeltaSet = if (longWords) {
        wordCount * 4 + (regionIndexCount - wordCount) * 2
    } else {
        wordCount * 2 + (regionIndexCount - wordCount)
    }
    requireCFFAvailable(data, offset + 6 + regionIndexCount * 2, itemCount * bytesPerDeltaSet, "CFF2 delta sets")
    return regionIndexes
}

private fun readCFFF2Dot14(data: ByteArray, offset: Int): Double =
    readInt16(data, offset) / 16384.0

private fun readCFFUInt32AsInt(data: ByteArray, offset: Int, section: String): Int {
    requireCFFAvailable(data, offset, 4, section)
    val value = readUInt32(data, offset)
    require(value <= Int.MAX_VALUE) { "$section UInt32 value $value does not fit Int." }
    return value.toInt()
}

private fun cffTableEvidence(
    format: String,
    displayFormat: String,
    face: OpenTypeFaceData,
    programProvider: () -> ParsedCFFProgram?,
    variationAxisTags: List<String>,
): CFFTableEvidence {
    val parsed = try {
        programProvider()
    } catch (error: CFFParseException) {
        throw FontScalerRefusalException(
            diagnostic = FontScalerDiagnostic(
                code = error.diagnosticCode,
                detail = error.diagnosticDetail,
                operation = "table",
                glyphId = 0u,
            ),
            message = cffParseFailureMessage(error),
        )
    } catch (error: IllegalArgumentException) {
        malformedCFFTable(format = format, displayFormat = displayFormat, error = error)
    } ?: unsupportedCFFGlyph(displayFormat, "table", face, 0u, VariationPosition())
    return CFFTableEvidence(
        sourceId = face.source.id.value.toString(),
        typefaceId = face.id.value.toString(),
        format = format,
        charStringCount = parsed.charStrings.size,
        localSubroutineCount = parsed.localSubroutines.size,
        globalSubroutineCount = parsed.globalSubroutines.size,
        hasPrivateDict = parsed.hasPrivateDict,
        topDictOperators = parsed.topDictOperators,
        names = parsed.tableFacts.names,
        indexes = parsed.tableFacts.indexes,
        topDicts = parsed.tableFacts.topDicts,
        privateDicts = parsed.tableFacts.privateDicts,
        fdArray = parsed.tableFacts.fdArray,
        fdSelect = parsed.tableFacts.fdSelect,
        diagnostics = parsed.tableFacts.diagnostics,
        variationAxisTags = variationAxisTags,
    )
}

private data class CFFIndexReadResult(
    val name: String,
    val count: Int,
    val offSize: Int,
    val objects: List<ByteArray>,
    val objectRanges: List<CFFByteRangeEvidence>,
    val nextOffset: Int,
) {
    fun evidence(): CFFIndexEvidence = CFFIndexEvidence(
        name = name,
        count = count,
        offSize = offSize,
        objectRanges = objectRanges,
    )
}

private class CFFParseException(
    val diagnosticCode: String,
    val diagnosticDetail: String,
    val section: String,
    val byteRange: CFFByteRangeEvidence? = null,
    val objectIndex: Int? = null,
    val operatorName: String? = null,
    message: String,
) : IllegalArgumentException(message)

private fun readCFFIndex(
    data: ByteArray,
    offset: Int,
    section: String,
): CFFIndexReadResult {
    requireCFFIndexAvailable(data, offset, 2, section)
    val count = readUInt16(data, offset)
    if (count == 0) {
        return CFFIndexReadResult(
            name = section,
            count = 0,
            offSize = 0,
            objects = emptyList(),
            objectRanges = emptyList(),
            nextOffset = offset + 2,
        )
    }
    requireCFFIndexAvailable(data, offset + 2, 1, section)
    val offSize = data[offset + 2].toInt() and 0xff
    if (offSize !in 1..4) {
        throw CFFParseException(
            diagnosticCode = FontScalerDiagnosticCodes.CFF_INDEX_OFFSIZE_UNSUPPORTED,
            diagnosticDetail = "cff.index-offsize-unsupported",
            section = section,
            byteRange = CFFByteRangeEvidence(offset + 2, offset + 3),
            message = "$section offSize must be in 1..4.",
        )
    }
    val offsetsStart = offset + 3
    val offsetCount = count + 1
    requireCFFIndexAvailable(data, offsetsStart, offsetCount * offSize, section)
    val offsets = (0 until offsetCount).map { index ->
        readCFFOffset(data, offsetsStart + index * offSize, offSize)
    }
    if (offsets.first() != 1 || offsets.any { value -> value < 1 }) {
        throw cffIndexBounds(section, CFFByteRangeEvidence(offsetsStart, offsetsStart + offsetCount * offSize)) {
            "$section first object offset must be 1."
        }
    }
    if (!offsets.zipWithNext().all { (left, right) -> right >= left }) {
        throw cffIndexBounds(section, CFFByteRangeEvidence(offsetsStart, offsetsStart + offsetCount * offSize)) {
            "$section offsets must be monotonic."
        }
    }
    val objectDataStart = offsetsStart + offsetCount * offSize
    val objectDataLength = offsets.last() - 1
    if (objectDataLength < 0) {
        throw cffIndexBounds(section, CFFByteRangeEvidence(offsetsStart, offsetsStart + offsetCount * offSize)) {
            "$section object data length must be non-negative."
        }
    }
    requireCFFIndexAvailable(data, objectDataStart, objectDataLength, section)
    val objectRanges = offsets.zipWithNext().map { (start, end) ->
        CFFByteRangeEvidence(objectDataStart + start - 1, objectDataStart + end - 1)
    }
    val objects = objectRanges.map { range -> data.copyOfRange(range.start, range.endExclusive) }
    return CFFIndexReadResult(
        name = section,
        count = count,
        offSize = offSize,
        objects = objects,
        objectRanges = objectRanges,
        nextOffset = objectDataStart + objectDataLength,
    )
}

private fun readCFFOffset(data: ByteArray, offset: Int, offSize: Int): Int {
    var result = 0
    repeat(offSize) { index ->
        result = (result shl 8) or (data[offset + index].toInt() and 0xff)
    }
    return result
}

private data class CFFDictEntry(
    val operator: Int,
    val operands: List<Int>,
    val byteRange: CFFByteRangeEvidence,
)

private data class CFFDict(
    val section: String,
    val objectIndex: Int?,
    val byteRange: CFFByteRangeEvidence,
    val entries: List<CFFDictEntry>,
) {
    private val entriesByOperator: Map<Int, List<CFFDictEntry>> = entries.groupBy(CFFDictEntry::operator)

    fun singleOperand(operator: Int): Int? =
        entriesByOperator[operator]?.lastOrNull()?.operands?.lastOrNull()

    fun operatorNames(): List<String> =
        entriesByOperator.keys.map(::cffDictOperatorName).sorted()

    fun toEvidence(): CFFDictEvidence = CFFDictEvidence(
        section = section,
        objectIndex = objectIndex,
        byteRange = byteRange,
        operators = entries.map { entry ->
            CFFDictOperatorEvidence(
                name = cffDictOperatorName(entry.operator),
                operands = entry.operands,
                byteRange = entry.byteRange,
            )
        },
        unknownOperators = entries.mapNotNull { entry ->
            cffDictOperatorName(entry.operator).takeUnless { isKnownCFFDictOperator(entry.operator) }
        }.distinct().sorted(),
    )

    fun toPrivateDictEvidence(
        owner: String,
        byteRange: CFFByteRangeEvidence,
        localSubroutineIndex: CFFIndexEvidence?,
    ): CFFPrivateDictEvidence = CFFPrivateDictEvidence(
        owner = owner,
        byteRange = byteRange,
        operators = entries.map { entry ->
            CFFDictOperatorEvidence(
                name = cffDictOperatorName(entry.operator),
                operands = entry.operands,
                byteRange = entry.byteRange,
            )
        },
        localSubroutineIndex = localSubroutineIndex,
    )

    fun privateLocation(): Pair<Int, Int>? {
        val operands = entriesByOperator[CFF_DICT_PRIVATE]?.lastOrNull()?.operands ?: return null
        if (operands.size < 2) {
            throw CFFParseException(
                diagnosticCode = FontScalerDiagnosticCodes.CFF_DICT_OPERAND_MALFORMED,
                diagnosticDetail = "cff.dict-operand-malformed",
                section = section,
                byteRange = byteRange,
                objectIndex = objectIndex,
                operatorName = cffDictOperatorName(CFF_DICT_PRIVATE),
                message = "CFF Private operator requires size and offset operands.",
            )
        }
        return operands[operands.size - 2] to operands[operands.size - 1]
    }
}

private fun parseCFFDict(
    data: ByteArray,
    section: String,
    baseOffset: Int,
    objectIndex: Int?,
): CFFDict {
    val entries = mutableListOf<CFFDictEntry>()
    val operands = mutableListOf<Int>()
    var offset = 0
    while (offset < data.size) {
        val operatorStart = offset
        val byte = data[offset++].toInt() and 0xff
        when {
            byte == 12 -> {
                if (offset >= data.size) {
                    throw CFFParseException(
                        diagnosticCode = FontScalerDiagnosticCodes.CFF_DICT_OPERAND_MALFORMED,
                        diagnosticDetail = "cff.dict-operand-malformed",
                        section = section,
                        byteRange = CFFByteRangeEvidence(baseOffset + operatorStart, baseOffset + offset),
                        objectIndex = objectIndex,
                        message = "$section contains truncated escaped DICT operator.",
                    )
                }
                val escaped = data[offset++].toInt() and 0xff
                entries += CFFDictEntry(
                    operator = 0x0c00 + escaped,
                    operands = operands.toList(),
                    byteRange = CFFByteRangeEvidence(baseOffset + operatorStart, baseOffset + offset),
                )
                operands.clear()
            }
            byte in 0..27 -> {
                entries += CFFDictEntry(
                    operator = byte,
                    operands = operands.toList(),
                    byteRange = CFFByteRangeEvidence(baseOffset + operatorStart, baseOffset + offset),
                )
                operands.clear()
            }
            else -> {
                val number = readCFFDictNumber(
                    data = data,
                    firstByte = byte,
                    offsetAfterFirstByte = offset,
                    section = section,
                    baseOffset = baseOffset,
                    objectIndex = objectIndex,
                )
                operands += number.value
                offset = number.nextOffset
            }
        }
    }
    return CFFDict(
        section = section,
        objectIndex = objectIndex,
        byteRange = CFFByteRangeEvidence(baseOffset, baseOffset + data.size),
        entries = entries,
    )
}

private data class CFFDictNumberReadResult(
    val value: Int,
    val nextOffset: Int,
)

private fun readCFFDictNumber(
    data: ByteArray,
    firstByte: Int,
    offsetAfterFirstByte: Int,
    section: String,
    baseOffset: Int,
    objectIndex: Int?,
): CFFDictNumberReadResult =
    when (firstByte) {
        28 -> {
            requireCFFDictAvailable(data, offsetAfterFirstByte, 2, section, baseOffset, objectIndex)
            CFFDictNumberReadResult(
                value = readInt16(data, offsetAfterFirstByte),
                nextOffset = offsetAfterFirstByte + 2,
            )
        }
        29 -> {
            requireCFFDictAvailable(data, offsetAfterFirstByte, 4, section, baseOffset, objectIndex)
            CFFDictNumberReadResult(
                value = readInt32(data, offsetAfterFirstByte),
                nextOffset = offsetAfterFirstByte + 4,
            )
        }
        255 -> {
            requireCFFDictAvailable(data, offsetAfterFirstByte, 4, section, baseOffset, objectIndex)
            // CFF DICT 255 is a signed 16.16 fixed-point number. The offsets and sizes
            // consumed by this parser are integral; fractional values such as FontMatrix
            // coefficients are retained only as non-routing evidence, so round them at
            // the integer boundary used by CFFDictEntry.
            CFFDictNumberReadResult(
                value = round(readInt32(data, offsetAfterFirstByte) / 65_536.0).toInt(),
                nextOffset = offsetAfterFirstByte + 4,
            )
        }
        in 32..246 -> CFFDictNumberReadResult(
            value = firstByte - 139,
            nextOffset = offsetAfterFirstByte,
        )
        in 247..250 -> {
            requireCFFDictAvailable(data, offsetAfterFirstByte, 1, section, baseOffset, objectIndex)
            CFFDictNumberReadResult(
                value = (firstByte - 247) * 256 + (data[offsetAfterFirstByte].toInt() and 0xff) + 108,
                nextOffset = offsetAfterFirstByte + 1,
            )
        }
        in 251..254 -> {
            requireCFFDictAvailable(data, offsetAfterFirstByte, 1, section, baseOffset, objectIndex)
            CFFDictNumberReadResult(
                value = -((firstByte - 251) * 256) - (data[offsetAfterFirstByte].toInt() and 0xff) - 108,
                nextOffset = offsetAfterFirstByte + 1,
            )
        }
        30 -> {
            requireCFFDictAvailable(data, offsetAfterFirstByte, 3, section, baseOffset, objectIndex)
            CFFDictNumberReadResult(
                value = ((data[offsetAfterFirstByte].toInt() and 0xff) shl 16) or
                    ((data[offsetAfterFirstByte + 1].toInt() and 0xff) shl 8) or
                    (data[offsetAfterFirstByte + 2].toInt() and 0xff),
                nextOffset = offsetAfterFirstByte + 3,
            )
        }
        31 -> {
            requireCFFDictAvailable(data, offsetAfterFirstByte, 3, section, baseOffset, objectIndex)
            CFFDictNumberReadResult(
                value = -((data[offsetAfterFirstByte].toInt() and 0xff) shl 16) -
                    ((data[offsetAfterFirstByte + 1].toInt() and 0xff) shl 8) -
                    (data[offsetAfterFirstByte + 2].toInt() and 0xff),
                nextOffset = offsetAfterFirstByte + 3,
            )
        }
        else -> throw CFFParseException(
            diagnosticCode = FontScalerDiagnosticCodes.CFF_DICT_OPERAND_MALFORMED,
            diagnosticDetail = "cff.dict-operand-malformed",
            section = section,
            byteRange = CFFByteRangeEvidence(baseOffset + offsetAfterFirstByte - 1, baseOffset + offsetAfterFirstByte),
            objectIndex = objectIndex,
            message = "$section contains unsupported DICT number byte $firstByte.",
        )
    }

private fun requireCFFAvailable(
    data: ByteArray,
    offset: Int,
    byteCount: Int,
    section: String,
) {
    require(offset >= 0) { "$section offset must be non-negative." }
    require(byteCount >= 0) { "$section byteCount must be non-negative." }
    require(offset <= data.size && byteCount <= data.size - offset) {
        "$section is truncated: need $byteCount bytes at offset $offset, table length ${data.size}."
    }
}

private fun requireCFFIndexAvailable(
    data: ByteArray,
    offset: Int,
    byteCount: Int,
    section: String,
) {
    if (offset < 0 || byteCount < 0 || offset > data.size || byteCount > data.size - offset) {
        throw cffIndexBounds(
            section = section,
            byteRange = CFFByteRangeEvidence(offset.coerceAtLeast(0), (offset + byteCount).coerceAtMost(data.size)),
        ) { "$section is truncated: need $byteCount bytes at offset $offset, table length ${data.size}." }
    }
}

private fun requireCFFDictAvailable(
    data: ByteArray,
    offset: Int,
    byteCount: Int,
    section: String,
    baseOffset: Int,
    objectIndex: Int?,
) {
    if (offset < 0 || byteCount < 0 || offset > data.size || byteCount > data.size - offset) {
        throw CFFParseException(
            diagnosticCode = FontScalerDiagnosticCodes.CFF_DICT_OPERAND_MALFORMED,
            diagnosticDetail = "cff.dict-operand-malformed",
            section = section,
            byteRange = CFFByteRangeEvidence(
                (baseOffset + offset).coerceAtLeast(baseOffset),
                (baseOffset + offset + byteCount).coerceAtMost(baseOffset + data.size),
            ),
            objectIndex = objectIndex,
            message = "$section is truncated: need $byteCount bytes at offset $offset, table length ${data.size}.",
        )
    }
}

private fun cffIndexBounds(
    section: String,
    byteRange: CFFByteRangeEvidence,
    message: () -> String,
): CFFParseException =
    CFFParseException(
        diagnosticCode = FontScalerDiagnosticCodes.CFF_INDEX_BOUNDS,
        diagnosticDetail = "cff.index-bounds",
        section = section,
        byteRange = byteRange,
        message = message(),
    )

private fun cffRequiredOperatorMissing(
    section: String,
    operatorName: String,
    byteRange: CFFByteRangeEvidence,
    objectIndex: Int?,
    message: String,
): CFFParseException =
    CFFParseException(
        diagnosticCode = FontScalerDiagnosticCodes.CFF_REQUIRED_OPERATOR_MISSING,
        diagnosticDetail = "cff.required-operator-missing",
        section = section,
        byteRange = byteRange,
        objectIndex = objectIndex,
        operatorName = operatorName,
        message = message,
    )

private fun cffParseFailureMessage(error: CFFParseException): String = buildString {
    append(error.section)
    error.objectIndex?.let { append(" objectIndex=").append(it) }
    error.operatorName?.let { append(" operator=").append(it) }
    error.byteRange?.let {
        append(" range=").append(it.start).append("..").append(it.endExclusive)
    }
    append(": ").append(error.message.orEmpty())
}

private fun parseCFFFDSelect(
    data: ByteArray,
    offset: Int,
    glyphCount: Int,
    fontDictCount: Int,
): CFFFDSelectEvidence {
    requireCFFIndexAvailable(data, offset, 1, "FDSelect")
    val format = data[offset].toInt() and 0xff
    val glyphToFontDict = when (format) {
        0 -> {
            requireCFFIndexAvailable(data, offset + 1, glyphCount, "FDSelect")
            (0 until glyphCount).map { index -> data[offset + 1 + index].toInt() and 0xff }
        }
        3 -> {
            requireCFFIndexAvailable(data, offset + 1, 2, "FDSelect")
            val rangeCount = readUInt16(data, offset + 1)
            val rangesOffset = offset + 3
            requireCFFIndexAvailable(data, rangesOffset, rangeCount * 3 + 2, "FDSelect")
            val mapping = MutableList(glyphCount) { 0 }
            var cursorGlyph = 0
            repeat(rangeCount) { rangeIndex ->
                val entryOffset = rangesOffset + rangeIndex * 3
                val firstGlyph = readUInt16(data, entryOffset)
                val fdIndex = data[entryOffset + 2].toInt() and 0xff
                val nextFirstGlyph = if (rangeIndex == rangeCount - 1) {
                    readUInt16(data, rangesOffset + rangeCount * 3)
                } else {
                    readUInt16(data, entryOffset + 3)
                }
                if (firstGlyph > nextFirstGlyph || nextFirstGlyph > glyphCount) {
                    throw cffIndexBounds("FDSelect", CFFByteRangeEvidence(entryOffset, entryOffset + 3)) {
                        "FDSelect range $rangeIndex exceeds glyph count $glyphCount."
                    }
                }
                while (cursorGlyph < nextFirstGlyph && cursorGlyph < glyphCount) {
                    mapping[cursorGlyph] = fdIndex
                    cursorGlyph += 1
                }
            }
            mapping
        }
        else -> throw cffIndexBounds("FDSelect", CFFByteRangeEvidence(offset, offset + 1)) {
            "FDSelect format $format is unsupported."
        }
    }
    if (glyphToFontDict.any { index -> index >= fontDictCount }) {
        throw cffIndexBounds("FDSelect", CFFByteRangeEvidence(offset, offset + 1 + glyphCount)) {
            "FDSelect font dict index exceeds FDArray count $fontDictCount."
        }
    }
    return CFFFDSelectEvidence(format = format, glyphToFontDict = glyphToFontDict)
}

private const val CFF_DICT_CHAR_STRINGS = 17
private const val CFF_DICT_CHARSET = 15
private const val CFF_DICT_ENCODING = 16
private const val CFF_DICT_PRIVATE = 18
private const val CFF_DICT_SUBRS = 19
private const val CFF_DICT_CFF2_VARIATION_STORE = 24
private const val CFF_DICT_DEFAULT_WIDTH_X = 20
private const val CFF_DICT_NOMINAL_WIDTH_X = 21
private const val CFF_DICT_ROS = 0x0c1e
private const val CFF_DICT_FD_ARRAY = 0x0c24
private const val CFF_DICT_FD_SELECT = 0x0c25

private fun cffDictOperatorName(operator: Int): String =
    when (operator) {
        CFF_DICT_CHARSET -> "cff.dict.charset"
        CFF_DICT_ENCODING -> "cff.dict.encoding"
        CFF_DICT_CHAR_STRINGS -> "cff.dict.charstrings"
        CFF_DICT_PRIVATE -> "cff.dict.private"
        CFF_DICT_SUBRS -> "cff.dict.subrs"
        CFF_DICT_DEFAULT_WIDTH_X -> "cff.dict.default-width-x"
        CFF_DICT_NOMINAL_WIDTH_X -> "cff.dict.nominal-width-x"
        CFF_DICT_CFF2_VARIATION_STORE -> "cff.dict.variation-store"
        CFF_DICT_ROS -> "cff.dict.ros"
        CFF_DICT_FD_ARRAY -> "cff.dict.fdarray"
        CFF_DICT_FD_SELECT -> "cff.dict.fdselect"
        in 0x0c00..0x0cff -> "cff.dict.escaped-${operator and 0xff}"
        else -> "cff.dict.operator-$operator"
    }

private fun isKnownCFFDictOperator(operator: Int): Boolean =
    operator in setOf(
        CFF_DICT_CHARSET,
        CFF_DICT_ENCODING,
        CFF_DICT_CHAR_STRINGS,
        CFF_DICT_PRIVATE,
        CFF_DICT_SUBRS,
        CFF_DICT_DEFAULT_WIDTH_X,
        CFF_DICT_NOMINAL_WIDTH_X,
        CFF_DICT_CFF2_VARIATION_STORE,
        CFF_DICT_ROS,
        CFF_DICT_FD_ARRAY,
        CFF_DICT_FD_SELECT,
    )

private fun malformedCFFTable(
    format: String,
    displayFormat: String,
    error: Throwable,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.CFF_TABLE_MALFORMED,
            detail = "$format.table-malformed",
            operation = "table",
            glyphId = 0u,
        ),
        message = "$displayFormat table malformed: ${error.message.orEmpty()}",
    )

private fun unsupportedCFFGlyph(
    format: String,
    operation: String,
    face: OpenTypeFaceData,
    glyphId: UInt,
    position: VariationPosition,
): Nothing =
    throw UnsupportedOperationException(
        "$format $operation for ${face.id.value} glyphId $glyphId requires bounded $format " +
            "table bytes and generated charstring evidence before glyph scaling can be claimed. " +
            "positionAxes=${position.axes.keys.sorted().joinToString(",")}",
    )

private fun cffPathOutputUnavailableDiagnostic(
    glyphId: UInt,
    operation: String,
    detail: String,
): FontScalerDiagnostic =
    FontScalerDiagnostic(
        code = FontScalerDiagnosticCodes.CFF_PATH_OUTPUT_UNAVAILABLE,
        detail = detail,
        operation = operation,
        glyphId = glyphId,
    )

private fun cffGlyphMalformedDiagnostic(
    glyphId: UInt,
    operation: String,
): FontScalerDiagnostic =
    FontScalerDiagnostic(
        code = FontScalerDiagnosticCodes.CFF_GLYPH_MALFORMED,
        detail = "cff.glyph-malformed",
        operation = operation,
        glyphId = glyphId,
    )

/**
 * Interpreter boundary for CFF and CFF2 charstrings.
 */
interface CFFCharStringInterpreter {
    /**
     * Interprets a raw charstring into an opaque outline command list.
     *
     * @param charString Raw Type 2 or CFF2 charstring bytes.
     * @param position Variation position used by CFF2 blend operators.
     * @return Opaque outline commands until the geometry command model is finalized.
     */
    fun interpret(charString: ByteArray, position: VariationPosition = VariationPosition()): List<String>
}

/**
 * Narrow, deterministic Type 2/CFF2 charstring interpreter used by generated font fixture tests.
 *
 * It supports the fixture operators needed to prove the current CFF/CFF2 gates: moves, lines,
 * cubic curves, flex, bounded local/global subroutine calls, `vsindex`, and `blend`. Full CFF table
 * parsing, private dictionaries, width integration, hint behavior, and complete CFF/CFF2 scaler
 * support remain outside this fixture interpreter.
 */
class CFFType2CharStringInterpreter(
    localSubroutines: List<ByteArray> = emptyList(),
    globalSubroutines: List<ByteArray> = emptyList(),
    blendAxisTagsByVsIndex: Map<Int, List<String>> = emptyMap(),
    private val blendScalarProvider: ((Int, VariationPosition) -> CFF2BlendResolution)? = null,
    private val limits: Type2ExecutionLimits = Type2ExecutionLimits(),
) : CFFCharStringInterpreter {
    private val localSubroutines: List<ByteArray> = localSubroutines.map { it.copyOf() }
    private val globalSubroutines: List<ByteArray> = globalSubroutines.map { it.copyOf() }
    private val blendAxisTagsByVsIndex: Map<Int, List<String>> =
        blendAxisTagsByVsIndex.toSortedMap().mapValues { (_, tags) ->
            tags.sorted().also { sortedTags ->
                require(sortedTags.distinct() == sortedTags) {
                    "CFF2 blend axis tags for one vsindex must be unique."
                }
                sortedTags.forEach { tag ->
                    require(tag.length == 4) { "CFF2 blend axis tag must contain exactly four characters." }
                    require(tag.all { character -> character.code in 0x20..0x7e }) {
                        "CFF2 blend axis tag $tag must contain printable ASCII characters."
                    }
                }
            }
        }

    init {
        require(blendAxisTagsByVsIndex.keys.all { index -> index >= 0 }) {
            "CFF2 blend vsindex values must be non-negative."
        }
    }

    override fun interpret(
        charString: ByteArray,
        position: VariationPosition,
    ): List<String> =
        interpretEvidence(
            charString = charString,
            glyphId = 0u,
            format = "cff",
            position = position,
        ).outlineCommands

    /**
     * Interprets one generated CFF/CFF2 charstring fixture into typed outline commands.
     */
    fun interpretOutline(
        charString: ByteArray,
        glyphId: UInt = 0u,
        position: VariationPosition = VariationPosition(),
    ): GlyphOutline =
        GlyphOutline(
            glyphId = glyphId,
            commands = interpretState(
                charString = charString,
                glyphId = glyphId,
                position = position,
            ).commands.toList(),
        )

    /**
     * Interprets one generated CFF/CFF2 charstring fixture and returns stable evidence.
     */
    fun interpretEvidence(
        charString: ByteArray,
        glyphId: UInt = 0u,
        format: String = "cff",
        position: VariationPosition = VariationPosition(),
    ): CFFCharStringEvidence {
        require(format == "cff" || format == "cff2") { "CFF charstring evidence format must be cff or cff2." }
        val state = interpretState(
            charString = charString,
            glyphId = glyphId,
            position = position,
        )
        val outlineCommands = state.commands.map { command -> command.toEvidenceLine() }
        return CFFCharStringEvidence(
            format = format,
            glyphId = glyphId,
            width = state.width,
            stemHintCount = state.stemHintCount,
            hintMaskByteCount = state.hintMaskByteCount,
            outlineCommands = outlineCommands,
            callTrace = state.callTrace.toList(),
            variationPosition = position.toEvidenceCoordinates(),
            cff2VsIndex = state.cff2VsIndex,
            blendVectors = state.blendVectors.toList(),
        )
    }

    private fun interpretState(
        charString: ByteArray,
        glyphId: UInt,
        position: VariationPosition,
    ): CFFType2ExecutionState {
        val state = CFFType2ExecutionState(limits = limits)
        execute(
            data = charString,
            state = state,
            glyphId = glyphId,
            position = position,
            depth = 0,
            allowReturn = false,
        )
        return state
    }

    private fun execute(
        data: ByteArray,
        state: CFFType2ExecutionState,
        glyphId: UInt,
        position: VariationPosition,
        depth: Int,
        allowReturn: Boolean,
    ): CFFType2SignalResult {
        var offset = 0
        while (offset < data.size) {
            val operatorOffset = offset
            val byte = data[offset++].toInt() and 0xff
            val signal = when {
                byte == 28 -> {
                    requireAvailable(data, offset, 2, glyphId, "shortint", operatorOffset)
                    state.push(readInt16(data, offset).toDouble(), glyphId, operatorOffset)
                    offset += 2
                    CFFType2Signal.CONTINUE
                }
                byte == 255 -> {
                    requireAvailable(data, offset, 4, glyphId, "fixed16dot16", operatorOffset)
                    state.push(readInt32(data, offset) / 65536.0, glyphId, operatorOffset)
                    offset += 4
                    CFFType2Signal.CONTINUE
                }
                byte in 32..246 -> {
                    state.push((byte - 139).toDouble(), glyphId, operatorOffset)
                    CFFType2Signal.CONTINUE
                }
                byte in 247..250 -> {
                    requireAvailable(data, offset, 1, glyphId, "positive-number", operatorOffset)
                    val value = (byte - 247) * 256 + (data[offset++].toInt() and 0xff) + 108
                    state.push(value.toDouble(), glyphId, operatorOffset)
                    CFFType2Signal.CONTINUE
                }
                byte in 251..254 -> {
                    requireAvailable(data, offset, 1, glyphId, "negative-number", operatorOffset)
                    val value = -((byte - 251) * 256) - (data[offset++].toInt() and 0xff) - 108
                    state.push(value.toDouble(), glyphId, operatorOffset)
                    CFFType2Signal.CONTINUE
                }
                byte == 12 -> {
                    requireAvailable(data, offset, 1, glyphId, "escaped-operator", operatorOffset)
                    val escapedOperator = data[offset++].toInt() and 0xff
                    handleEscapedOperator(
                        operator = escapedOperator,
                        operatorOffset = operatorOffset,
                        state = state,
                        glyphId = glyphId,
                    )
                }
                byte == 19 || byte == 20 -> {
                    offset = handleHintMaskOperator(
                        data = data,
                        maskOffset = offset,
                        state = state,
                        glyphId = glyphId,
                        operatorOffset = operatorOffset,
                    )
                    CFFType2Signal.CONTINUE
                }
                else -> handleOperator(
                        data = data,
                        operator = byte,
                        operatorOffset = operatorOffset,
                        state = state,
                        glyphId = glyphId,
                        position = position,
                        depth = depth,
                        allowReturn = allowReturn,
                    )
            }
            state.recordExecution(
                consumedBytes = offset - operatorOffset,
                glyphId = glyphId,
                operatorOffset = operatorOffset,
            )
            if (signal != CFFType2Signal.CONTINUE) {
                return CFFType2SignalResult(signal = signal, signalOffset = operatorOffset)
            }
        }
        if (allowReturn) {
            malformedCFFStack(glyphId, "cff.subr-missing-return", data.size)
        }
        return CFFType2SignalResult(signal = CFFType2Signal.CONTINUE, signalOffset = data.size)
    }

    private fun handleOperator(
        data: ByteArray,
        operator: Int,
        operatorOffset: Int,
        state: CFFType2ExecutionState,
        glyphId: UInt,
        position: VariationPosition,
        depth: Int,
        allowReturn: Boolean,
    ): CFFType2Signal =
        when (operator) {
            1, 3, 18, 23 -> {
                state.consumeStemHints(glyphId = glyphId, operatorOffset = operatorOffset)
                CFFType2Signal.CONTINUE
            }
            4 -> {
                val dy = state.takeMoveArguments(count = 1, glyphId = glyphId, operatorOffset = operatorOffset)[0]
                state.moveBy(dx = 0.0, dy = dy)
                CFFType2Signal.CONTINUE
            }
            5 -> {
                drawLines(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            6 -> {
                drawAlternatingLines(
                    operands = state.drainOperands(glyphId, operatorOffset),
                    state = state,
                    glyphId = glyphId,
                    operatorOffset = operatorOffset,
                    horizontalFirst = true,
                )
                CFFType2Signal.CONTINUE
            }
            7 -> {
                drawAlternatingLines(
                    operands = state.drainOperands(glyphId, operatorOffset),
                    state = state,
                    glyphId = glyphId,
                    operatorOffset = operatorOffset,
                    horizontalFirst = false,
                )
                CFFType2Signal.CONTINUE
            }
            8 -> {
                drawCurves(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            10 -> callSubroutine(
                scope = "local",
                subroutines = localSubroutines,
                state = state,
                glyphId = glyphId,
                position = position,
                depth = depth,
                operatorOffset = operatorOffset,
            )
            11 -> {
                if (!allowReturn) {
                    malformedCFFStack(glyphId, "cff.return-outside-subroutine", operatorOffset)
                }
                state.stack.clear()
                CFFType2Signal.RETURN
            }
            14 -> {
                if (state.stack.isNotEmpty()) {
                    malformedCFFStack(glyphId, "cff.stack-malformed", operatorOffset)
                }
                if (operatorOffset + 1 != data.size) {
                    trailingCFFBytes(glyphId, operatorOffset)
                }
                state.stack.clear()
                state.closeOpenPath()
                CFFType2Signal.END
            }
            15 -> {
                state.cff2VsIndex = state.popInt(glyphId, operatorOffset)
                if (state.cff2VsIndex < 0) {
                    malformedCFFStack(glyphId, "cff2.vsindex-negative", operatorOffset)
                }
                CFFType2Signal.CONTINUE
            }
            16 -> {
                applyBlend(state, glyphId, position, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            21 -> {
                val (dx, dy) = state.takeMoveArguments(count = 2, glyphId = glyphId, operatorOffset = operatorOffset)
                state.moveBy(dx = dx, dy = dy)
                CFFType2Signal.CONTINUE
            }
            22 -> {
                val dx = state.takeMoveArguments(count = 1, glyphId = glyphId, operatorOffset = operatorOffset)[0]
                state.moveBy(dx = dx, dy = 0.0)
                CFFType2Signal.CONTINUE
            }
            24 -> {
                drawCurveLine(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            25 -> {
                drawLineCurve(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            26 -> {
                drawVVCurves(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            27 -> {
                drawHHCurves(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            29 -> callSubroutine(
                scope = "global",
                subroutines = globalSubroutines,
                state = state,
                glyphId = glyphId,
                position = position,
                depth = depth,
                operatorOffset = operatorOffset,
            )
            30 -> {
                drawVHCurves(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            31 -> {
                drawHVCurves(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            else -> unsupportedCFFOperator(glyphId, "cff.operator-$operator", operatorOffset)
        }

    private fun handleEscapedOperator(
        operator: Int,
        operatorOffset: Int,
        state: CFFType2ExecutionState,
        glyphId: UInt,
    ): CFFType2Signal =
        when (operator) {
            34 -> {
                drawHFlex(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            35 -> {
                drawFlex(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            36 -> {
                drawHFlex1(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            37 -> {
                drawFlex1(state.drainOperands(glyphId, operatorOffset), state, glyphId, operatorOffset)
                CFFType2Signal.CONTINUE
            }
            else -> unsupportedCFFOperator(glyphId, "cff.escaped-operator-$operator", operatorOffset)
        }

    private fun handleHintMaskOperator(
        data: ByteArray,
        maskOffset: Int,
        state: CFFType2ExecutionState,
        glyphId: UInt,
        operatorOffset: Int,
    ): Int {
        if (state.stack.isNotEmpty()) {
            state.consumeStemHints(glyphId = glyphId, operatorOffset = operatorOffset)
        }
        val maskByteCount = (state.stemHintCount + 7) / 8
        requireAvailable(data, maskOffset, maskByteCount, glyphId, "hint-mask", operatorOffset)
        state.hintMaskByteCount += maskByteCount
        return maskOffset + maskByteCount
    }

    private fun callSubroutine(
        scope: String,
        subroutines: List<ByteArray>,
        state: CFFType2ExecutionState,
        glyphId: UInt,
        position: VariationPosition,
        depth: Int,
        operatorOffset: Int,
    ): CFFType2Signal {
        val encodedIndex = state.popInt(glyphId, operatorOffset)
        val bias = subroutineBias(subroutines.size)
        val resolvedIndex = encodedIndex + bias
        if (resolvedIndex !in subroutines.indices) {
            outOfRangeCFFSubroutine(glyphId, operatorOffset)
        }
        if (depth >= limits.maxCallDepth) {
            depthLimitedCFFSubroutine(glyphId, operatorOffset)
        }
        val signalResult = execute(
                data = subroutines[resolvedIndex],
                state = state,
                glyphId = glyphId,
                position = position,
                depth = depth + 1,
                allowReturn = true,
            )
        state.callTrace += CFFCharStringCallTrace(
            depth = depth,
            scope = scope,
            encodedIndex = encodedIndex,
            resolvedIndex = resolvedIndex,
            bias = bias,
            callerByteOffset = operatorOffset,
            returnByteOffset = when (signalResult.signal) {
                CFFType2Signal.RETURN -> signalResult.signalOffset
                else -> null
            },
            instructionBudgetRemaining = state.remainingInstructionBudget(),
            expandedByteBudgetRemaining = state.remainingExpandedByteBudget(),
        )
        return when (signalResult.signal) {
            CFFType2Signal.END -> CFFType2Signal.END
            CFFType2Signal.RETURN,
            CFFType2Signal.CONTINUE -> CFFType2Signal.CONTINUE
        }
    }

    private fun applyBlend(
        state: CFFType2ExecutionState,
        glyphId: UInt,
        position: VariationPosition,
        operatorOffset: Int,
    ) {
        val blendCount = state.popInt(glyphId, operatorOffset)
        if (blendCount <= 0) {
            malformedCFFBlendStack(glyphId, "cff2.blend-count", operatorOffset)
        }
        val resolution = try {
            blendScalarProvider?.invoke(state.cff2VsIndex, position)
        } catch (error: CFF2VariationStoreException) {
            malformedCFFVariation(glyphId, error.detail, operatorOffset)
        } ?: CFF2BlendResolution(
            scalars = blendAxisTagsByVsIndex[state.cff2VsIndex].orEmpty().map { tag ->
                position.axes[tag] ?: 0.0
            },
        )
        val scalars = resolution.scalars
        val requiredOperandCount = blendCount * (1 + scalars.size)
        if (state.stack.size < requiredOperandCount) {
            malformedCFFBlendStack(glyphId, "cff2.blend-stack-underflow", operatorOffset)
        }
        val start = state.stack.size - requiredOperandCount
        val defaults = state.stack.subList(start, start + blendCount).toList()
        val blended = defaults.toMutableList()
        var deltaOffset = start + blendCount
        val deltaSets = mutableListOf<List<Double>>()
        scalars.forEach { scalar ->
            val deltas = mutableListOf<Double>()
            repeat(blendCount) { valueIndex ->
                val delta = state.stack[deltaOffset++]
                deltas += delta
                blended[valueIndex] += delta * scalar
            }
            deltaSets += deltas
        }
        state.stack.subList(start, state.stack.size).clear()
        state.stack += blended
        state.blendVectors += CFFBlendVectorEvidence(
            vsIndex = state.cff2VsIndex,
            defaults = defaults,
            deltaSets = deltaSets,
            regionIndexes = resolution.regionIndexes,
            scalars = scalars,
            blendedValues = blended,
        )
    }
}

private class CFFType2ExecutionState(
    private val limits: Type2ExecutionLimits,
) {
    val stack: MutableList<Double> = mutableListOf()
    val commands: MutableList<OutlineCommand> = mutableListOf()
    val callTrace: MutableList<CFFCharStringCallTrace> = mutableListOf()
    val blendVectors: MutableList<CFFBlendVectorEvidence> = mutableListOf()
    var x: Double = 0.0
    var y: Double = 0.0
    var cff2VsIndex: Int = 0
    var width: Double? = null
    var stemHintCount: Int = 0
    var hintMaskByteCount: Int = 0
    private var instructionCount: Int = 0
    private var expandedByteCount: Int = 0
    private var pathOpen: Boolean = false

    fun push(value: Double, glyphId: UInt, operatorOffset: Int) {
        if (stack.size >= limits.maxOperandStack) {
            overflowedCFFStack(glyphId, operatorOffset)
        }
        stack += value
    }

    fun recordExecution(
        consumedBytes: Int,
        glyphId: UInt,
        operatorOffset: Int,
    ) {
        instructionCount += 1
        if (instructionCount > limits.maxInstructionCount) {
            instructionLimitedCFFExecution(glyphId, operatorOffset)
        }
        expandedByteCount += consumedBytes
        if (expandedByteCount > limits.maxExpandedBytes) {
            expandedByteLimitedCFFExecution(glyphId, operatorOffset)
        }
    }

    fun remainingInstructionBudget(): Int = max(0, limits.maxInstructionCount - instructionCount)

    fun remainingExpandedByteBudget(): Int = max(0, limits.maxExpandedBytes - expandedByteCount)

    fun takeMoveArguments(
        count: Int,
        glyphId: UInt,
        operatorOffset: Int,
    ): List<Double> {
        captureOptionalWidth(
            expectedRemainingOperandCount = count,
            glyphId = glyphId,
            operatorOffset = operatorOffset,
        )
        if (stack.size < count) {
            malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
        }
        if (stack.size != count) {
            malformedCFFStack(glyphId, "cff.stack-malformed", operatorOffset)
        }
        val result = stack.takeLast(count)
        stack.clear()
        return result
    }

    fun drainOperands(
        glyphId: UInt,
        operatorOffset: Int,
    ): List<Double> {
        if (stack.isEmpty()) {
            malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
        }
        return stack.toList().also { stack.clear() }
    }

    fun popInt(glyphId: UInt, operatorOffset: Int): Int {
        if (stack.isEmpty()) {
            malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
        }
        val value = stack.removeAt(stack.lastIndex)
        if (!value.isFinite() || value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            malformedCFFStack(glyphId, "cff.integer-operand", operatorOffset)
        }
        return value.toInt()
    }

    fun consumeStemHints(glyphId: UInt, operatorOffset: Int) {
        if (stack.isEmpty()) {
            return
        }
        if (stack.size % 2 == 1) {
            captureWidth(stack.removeAt(0), glyphId, operatorOffset)
        }
        if (stack.size % 2 != 0) {
            malformedCFFStack(glyphId, "cff.stem-stack-malformed", operatorOffset)
        }
        stemHintCount += stack.size / 2
        stack.clear()
    }

    fun moveBy(dx: Double, dy: Double) {
        x += dx
        y += dy
        commands += moveTo(x, y)
        pathOpen = true
    }

    fun lineBy(dx: Double, dy: Double) {
        x += dx
        y += dy
        commands += lineTo(x, y)
        pathOpen = true
    }

    fun curveBy(
        dx1: Double,
        dy1: Double,
        dx2: Double,
        dy2: Double,
        dx3: Double,
        dy3: Double,
    ) {
        val controlX1 = x + dx1
        val controlY1 = y + dy1
        val controlX2 = controlX1 + dx2
        val controlY2 = controlY1 + dy2
        x = controlX2 + dx3
        y = controlY2 + dy3
        commands += cubicTo(
            controlX1 = controlX1,
            controlY1 = controlY1,
            controlX2 = controlX2,
            controlY2 = controlY2,
            x = x,
            y = y,
        )
        pathOpen = true
    }

    fun closeOpenPath() {
        if (pathOpen && commands.lastOrNull() != OutlineCommand.Close) {
            commands += close()
        }
        pathOpen = false
    }

    private fun captureOptionalWidth(
        expectedRemainingOperandCount: Int,
        glyphId: UInt,
        operatorOffset: Int,
    ) {
        if (stack.size == expectedRemainingOperandCount + 1) {
            captureWidth(stack.removeAt(0), glyphId, operatorOffset)
        }
    }

    private fun captureWidth(value: Double, glyphId: UInt, operatorOffset: Int) {
        if (width != null) {
            malformedCFFStack(glyphId, "cff.width-duplicate", operatorOffset)
        }
        if (!value.isFinite()) {
            malformedCFFStack(glyphId, "cff.width-non-finite", operatorOffset)
        }
        width = value
    }
}

private enum class CFFType2Signal {
    CONTINUE,
    RETURN,
    END,
}

private data class CFFType2SignalResult(
    val signal: CFFType2Signal,
    val signalOffset: Int,
)

private fun drawLines(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size < 2 || operands.size % 2 != 0) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    operands.chunked(2).forEach { (dx, dy) -> state.lineBy(dx, dy) }
}

private fun drawAlternatingLines(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
    horizontalFirst: Boolean,
) {
    if (operands.isEmpty()) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    operands.forEachIndexed { index, value ->
        val horizontal = if (horizontalFirst) index % 2 == 0 else index % 2 != 0
        if (horizontal) {
            state.lineBy(dx = value, dy = 0.0)
        } else {
            state.lineBy(dx = 0.0, dy = value)
        }
    }
}

private fun drawCurves(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size < 6 || operands.size % 6 != 0) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    operands.chunked(6).forEach { chunk ->
        state.curveBy(
            dx1 = chunk[0],
            dy1 = chunk[1],
            dx2 = chunk[2],
            dy2 = chunk[3],
            dx3 = chunk[4],
            dy3 = chunk[5],
        )
    }
}

private fun drawCurveLine(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size < 8 || (operands.size - 2) % 6 != 0) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    drawCurves(operands.dropLast(2), state, glyphId, operatorOffset)
    val (dx, dy) = operands.takeLast(2)
    state.lineBy(dx, dy)
}

private fun drawLineCurve(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size < 8 || (operands.size - 6) % 2 != 0) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    drawLines(operands.dropLast(6), state, glyphId, operatorOffset)
    val curve = operands.takeLast(6)
    state.curveBy(
        dx1 = curve[0],
        dy1 = curve[1],
        dx2 = curve[2],
        dy2 = curve[3],
        dx3 = curve[4],
        dy3 = curve[5],
    )
}

private fun drawVVCurves(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size < 4) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    var index = 0
    val firstDx = if (operands.size % 4 == 1) operands[index++] else 0.0
    if ((operands.size - index) % 4 != 0) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    var firstCurve = true
    while (index < operands.size) {
        val dx1 = if (firstCurve) firstDx else 0.0
        state.curveBy(
            dx1 = dx1,
            dy1 = operands[index],
            dx2 = operands[index + 1],
            dy2 = operands[index + 2],
            dx3 = 0.0,
            dy3 = operands[index + 3],
        )
        firstCurve = false
        index += 4
    }
}

private fun drawHHCurves(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size < 4) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    var index = 0
    val firstDy = if (operands.size % 4 == 1) operands[index++] else 0.0
    if ((operands.size - index) % 4 != 0) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    var firstCurve = true
    while (index < operands.size) {
        val dy1 = if (firstCurve) firstDy else 0.0
        state.curveBy(
            dx1 = operands[index],
            dy1 = dy1,
            dx2 = operands[index + 1],
            dy2 = operands[index + 2],
            dx3 = operands[index + 3],
            dy3 = 0.0,
        )
        firstCurve = false
        index += 4
    }
}

private fun drawHVCurves(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size < 4 || operands.size % 4 !in 0..1) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    var index = 0
    var horizontalCurve = true
    while (index < operands.size) {
        val remaining = operands.size - index
        val hasFinalDelta = remaining == 5
        if (horizontalCurve) {
            state.curveBy(
                dx1 = operands[index],
                dy1 = 0.0,
                dx2 = operands[index + 1],
                dy2 = operands[index + 2],
                dx3 = if (hasFinalDelta) operands[index + 4] else 0.0,
                dy3 = operands[index + 3],
            )
        } else {
            state.curveBy(
                dx1 = 0.0,
                dy1 = operands[index],
                dx2 = operands[index + 1],
                dy2 = operands[index + 2],
                dx3 = operands[index + 3],
                dy3 = if (hasFinalDelta) operands[index + 4] else 0.0,
            )
        }
        index += if (hasFinalDelta) 5 else 4
        horizontalCurve = !horizontalCurve
    }
}

private fun drawVHCurves(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size < 4 || operands.size % 4 !in 0..1) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    var index = 0
    var verticalCurve = true
    while (index < operands.size) {
        val remaining = operands.size - index
        val hasFinalDelta = remaining == 5
        if (verticalCurve) {
            state.curveBy(
                dx1 = 0.0,
                dy1 = operands[index],
                dx2 = operands[index + 1],
                dy2 = operands[index + 2],
                dx3 = operands[index + 3],
                dy3 = if (hasFinalDelta) operands[index + 4] else 0.0,
            )
        } else {
            state.curveBy(
                dx1 = operands[index],
                dy1 = 0.0,
                dx2 = operands[index + 1],
                dy2 = operands[index + 2],
                dx3 = if (hasFinalDelta) operands[index + 4] else 0.0,
                dy3 = operands[index + 3],
            )
        }
        index += if (hasFinalDelta) 5 else 4
        verticalCurve = !verticalCurve
    }
}

private fun drawFlex(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size != 13) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    val dx1 = operands[0]
    val dy1 = operands[1]
    val dx2 = operands[2]
    val dy2 = operands[3]
    val dx3 = operands[4]
    val dy3 = operands[5]
    val dx4 = operands[6]
    val dy4 = operands[7]
    val dx5 = operands[8]
    val dy5 = operands[9]
    val dx6 = operands[10]
    val dy6 = operands[11]
    state.curveBy(dx1, dy1, dx2, dy2, dx3, dy3)
    state.curveBy(dx4, dy4, dx5, dy5, dx6, dy6)
}

private fun drawHFlex(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size != 7) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    val dx1 = operands[0]
    val dx2 = operands[1]
    val dy2 = operands[2]
    val dx3 = operands[3]
    val dx4 = operands[4]
    val dx5 = operands[5]
    val dx6 = operands[6]
    state.curveBy(dx1, 0.0, dx2, dy2, dx3, 0.0)
    state.curveBy(dx4, 0.0, dx5, -dy2, dx6, 0.0)
}

private fun drawHFlex1(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size != 9) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    val dy6 = -(operands[1] + operands[3] + operands[7])
    state.curveBy(
        dx1 = operands[0],
        dy1 = operands[1],
        dx2 = operands[2],
        dy2 = operands[3],
        dx3 = operands[4],
        dy3 = 0.0,
    )
    state.curveBy(
        dx1 = operands[5],
        dy1 = 0.0,
        dx2 = operands[6],
        dy2 = operands[7],
        dx3 = operands[8],
        dy3 = dy6,
    )
}

private fun drawFlex1(
    operands: List<Double>,
    state: CFFType2ExecutionState,
    glyphId: UInt,
    operatorOffset: Int,
) {
    if (operands.size != 11) {
        malformedCFFStack(glyphId, "cff.stack-underflow", operatorOffset)
    }
    val sumDx = operands[0] + operands[2] + operands[4] + operands[6] + operands[8]
    val sumDy = operands[1] + operands[3] + operands[5] + operands[7] + operands[9]
    val dx6: Double
    val dy6: Double
    if (abs(sumDx) > abs(sumDy)) {
        dx6 = operands[10]
        dy6 = -sumDy
    } else {
        dx6 = -sumDx
        dy6 = operands[10]
    }
    state.curveBy(
        dx1 = operands[0],
        dy1 = operands[1],
        dx2 = operands[2],
        dy2 = operands[3],
        dx3 = operands[4],
        dy3 = operands[5],
    )
    state.curveBy(
        dx1 = operands[6],
        dy1 = operands[7],
        dx2 = operands[8],
        dy2 = operands[9],
        dx3 = dx6,
        dy3 = dy6,
    )
}

private fun requireAvailable(
    data: ByteArray,
    offset: Int,
    byteCount: Int,
    glyphId: UInt,
    section: String,
    operatorOffset: Int,
) {
    if (offset < 0 || byteCount < 0 || offset > data.size || byteCount > data.size - offset) {
        malformedCFFStack(glyphId, "cff.$section-truncated", operatorOffset)
    }
}

private fun unsupportedCFFOperator(
    glyphId: UInt,
    detail: String,
    operatorOffset: Int,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.CFF_OPERATOR_UNSUPPORTED,
            detail = detail,
            operation = "charstring",
            glyphId = glyphId,
        ),
        message = "CFF Type 2 operator is unsupported at operator offset $operatorOffset for glyphId $glyphId.",
    )

private fun malformedCFFStack(
    glyphId: UInt,
    detail: String,
    operatorOffset: Int,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.CFF_STACK_MALFORMED,
            detail = detail,
            operation = "charstring",
            glyphId = glyphId,
        ),
        message = "CFF Type 2 stack is malformed at operator offset $operatorOffset for glyphId $glyphId.",
    )

private fun malformedCFFBlendStack(
    glyphId: UInt,
    detail: String,
    operatorOffset: Int,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.CFF2_BLEND_STACK_MALFORMED,
            detail = detail,
            operation = "charstring",
            glyphId = glyphId,
        ),
        message = "CFF2 blend stack is malformed at operator offset $operatorOffset for glyphId $glyphId.",
    )

private fun overflowedCFFStack(
    glyphId: UInt,
    operatorOffset: Int,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.CFF_STACK_OVERFLOW,
            detail = "cff.stack-overflow",
            operation = "charstring",
            glyphId = glyphId,
        ),
        message = "CFF Type 2 operand stack overflowed at operator offset $operatorOffset for glyphId $glyphId.",
    )

private fun trailingCFFBytes(
    glyphId: UInt,
    operatorOffset: Int,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.CFF_TRAILING_BYTES,
            detail = "cff.trailing-bytes",
            operation = "charstring",
            glyphId = glyphId,
        ),
        message = "CFF Type 2 charstring has trailing bytes after endchar at operator offset $operatorOffset for glyphId $glyphId.",
    )

private fun outOfRangeCFFSubroutine(
    glyphId: UInt,
    operatorOffset: Int,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.CFF_SUBROUTINE_OUT_OF_RANGE,
            detail = "cff.subr-out-of-range",
            operation = "charstring",
            glyphId = glyphId,
        ),
        message = "CFF Type 2 subroutine index is out of range at operator offset $operatorOffset for glyphId $glyphId.",
    )

private fun depthLimitedCFFSubroutine(
    glyphId: UInt,
    operatorOffset: Int,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.CFF_SUBROUTINE_DEPTH_LIMIT,
            detail = "cff.subr-depth-limit",
            operation = "charstring",
            glyphId = glyphId,
        ),
        message = "CFF Type 2 subroutine depth limit exceeded at operator offset $operatorOffset for glyphId $glyphId.",
    )

private fun instructionLimitedCFFExecution(
    glyphId: UInt,
    operatorOffset: Int,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.CFF_INSTRUCTION_LIMIT,
            detail = "cff.instruction-limit",
            operation = "charstring",
            glyphId = glyphId,
        ),
        message = "CFF Type 2 instruction budget exceeded at operator offset $operatorOffset for glyphId $glyphId.",
    )

private fun expandedByteLimitedCFFExecution(
    glyphId: UInt,
    operatorOffset: Int,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.CFF_EXPANDED_BYTE_LIMIT,
            detail = "cff.expanded-byte-limit",
            operation = "charstring",
            glyphId = glyphId,
        ),
        message = "CFF Type 2 expanded-byte budget exceeded at operator offset $operatorOffset for glyphId $glyphId.",
    )

private fun malformedCFFVariation(
    glyphId: UInt,
    detail: String,
    operatorOffset: Int,
): Nothing =
    throw FontScalerRefusalException(
        diagnostic = FontScalerDiagnostic(
            code = FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED,
            detail = detail,
            operation = "charstring",
            glyphId = glyphId,
        ),
        message = "CFF2 variation data is malformed at operator offset $operatorOffset for glyphId $glyphId.",
    )

private fun subroutineBias(subroutineCount: Int): Int =
    when {
        subroutineCount < 1240 -> 107
        subroutineCount < 33900 -> 1131
        else -> 32768
    }

private const val MAX_CFF_OPERAND_STACK_DEPTH = 48
private const val MAX_CFF_SUBROUTINE_DEPTH = 16
private const val MAX_CFF_INSTRUCTION_COUNT = 1024
private const val MAX_CFF_EXPANDED_BYTES = 4096

/**
 * User-space position in a variable font design space.
 *
 * @property axes Axis values keyed by four-character OpenType axis tags.
 */
data class VariationPosition(
    val axes: Map<String, Double> = emptyMap(),
)

/**
 * User-space bounds for one OpenType variable-font design axis.
 *
 * The values correspond to the design coordinates exposed by an `fvar` axis record before glyph
 * variation tuple normalization. [minimum] and [maximum] define the legal request range,
 * [defaultValue] is the coordinate used when callers omit the axis, and normalized coordinates map
 * that default to `0.0`.
 *
 * @property tag Four-character OpenType axis tag such as `wght`, `wdth`, or `opsz`.
 * @property minimum Smallest supported user-space design coordinate for this axis.
 * @property defaultValue User-space design coordinate selected when no position is requested.
 * @property maximum Largest supported user-space design coordinate for this axis.
 * @throws IllegalArgumentException when the tag is not a printable four-character OpenType tag, a
 * coordinate is not finite, or the bounds do not satisfy `minimum <= defaultValue <= maximum`.
 */
data class VariationAxis(
    val tag: String,
    val minimum: Double,
    val defaultValue: Double,
    val maximum: Double,
) {
    init {
        require(tag.length == 4) { "variation axis tag must contain exactly four characters." }
        require(tag.all { character -> character.code in 0x20..0x7e }) {
            "variation axis tag $tag must contain printable ASCII characters."
        }
        require(minimum.isFinite()) { "variation axis $tag minimum must be finite." }
        require(defaultValue.isFinite()) { "variation axis $tag defaultValue must be finite." }
        require(maximum.isFinite()) { "variation axis $tag maximum must be finite." }
        require(minimum <= defaultValue) {
            "variation axis $tag minimum $minimum must be <= defaultValue $defaultValue."
        }
        require(defaultValue <= maximum) {
            "variation axis $tag defaultValue $defaultValue must be <= maximum $maximum."
        }
    }
}

/**
 * Normalizes user-space variation positions into the scalar domain expected by glyph variation tables.
 */
interface VariationNormalizer {
    /**
     * Normalizes a variation position.
     *
     * @param position User-space variation position.
     * @return Normalized axis values keyed by axis tag.
     * @throws IllegalArgumentException when [position] cannot be normalized by this normalizer.
     */
    fun normalize(position: VariationPosition): Map<String, Double>
}

/**
 * Bounded OpenType variation normalizer for a fixed set of design axes.
 *
 * The normalizer clamps each requested user-space coordinate to its declared [VariationAxis] range
 * and converts it to the standard glyph-variation scalar interval: coordinates below the default
 * map linearly to `[-1.0, 0.0]`, coordinates above the default map linearly to `[0.0, 1.0]`, and
 * the default maps to `0.0`. Axes omitted from [VariationPosition] are kept at their declared
 * default and therefore normalize to `0.0`; unknown requested axes are rejected because they do not
 * have axis bounds to clamp against.
 *
 * Returned maps use deterministic ascending axis-tag order, independent of the order supplied by
 * callers or the order of entries inside [VariationPosition.axes].
 *
 * @param axes Axis definitions used to bound and normalize positions.
 * @throws IllegalArgumentException when two axis definitions use the same tag.
 */
class BoundedVariationNormalizer(
    axes: Iterable<VariationAxis>,
) : VariationNormalizer {
    private val axesByTag: List<VariationAxis> = axes.toList()
        .sortedBy { axis -> axis.tag }
        .also { sortedAxes ->
            sortedAxes.zipWithNext().forEach { (previous, current) ->
                require(previous.tag != current.tag) {
                    "variation axes contain duplicate tag ${current.tag}."
                }
            }
        }

    private val axisTags: Set<String> = axesByTag.map { axis -> axis.tag }.toSet()

    /**
     * Normalizes one user-space variation position against this normalizer's declared axes.
     *
     * @param position User-space variation position. Missing declared axes are treated as the axis
     * default; extra axes are rejected.
     * @return Normalized axis values keyed by tag in deterministic ascending tag order.
     * @throws IllegalArgumentException when [position] contains an unknown axis tag or a non-finite
     * coordinate.
     */
    override fun normalize(position: VariationPosition): Map<String, Double> {
        position.axes.forEach { (tag, value) ->
            require(tag in axisTags) {
                "variation position axis $tag is not declared in this normalizer."
            }
            require(value.isFinite()) { "variation position axis $tag must be finite." }
        }

        val normalized = LinkedHashMap<String, Double>(axesByTag.size)
        axesByTag.forEach { axis ->
            val requestedValue = position.axes[axis.tag] ?: axis.defaultValue
            normalized[axis.tag] = axis.normalize(requestedValue)
        }
        return normalized
    }
}

private fun VariationAxis.normalize(value: Double): Double {
    require(value.isFinite()) { "variation position axis $tag must be finite." }

    val clamped = value.coerceIn(minimum, maximum)
    return when {
        clamped < defaultValue -> {
            if (defaultValue == minimum) {
                0.0
            } else {
                ((clamped - defaultValue) / (defaultValue - minimum)).coerceIn(-1.0, 0.0)
            }
        }
        clamped > defaultValue -> {
            if (maximum == defaultValue) {
                0.0
            } else {
                ((clamped - defaultValue) / (maximum - defaultValue)).coerceIn(0.0, 1.0)
            }
        }
        else -> 0.0
    }
}
