package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontDiagnosticData
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphComponentReference
import org.graphiks.kalligraphie.api.GlyphComponentTransform
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.checkedRangeEnd
import org.graphiks.kalligraphie.font.sfnt.readInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.slice
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Decodes TrueType `glyf` data into bounded scaler outlines.
 *
 * The reader validates offsets, recursion, composite placement, geometry, and
 * profile limits before publishing an outline. It does not mutate the source
 * bytes; malformed data and limit violations are returned as typed failures.
 */
internal object GlyfReader {
    /**
     * Reads [glyphId] while enforcing [profile] and cooperative cancellation.
     *
     * The returned outline is owned by the caller and contains immutable
     * contour and component snapshots. A cancellation observed before
     * completion returns [FontOperationResult.Cancelled] without partial data;
     * malformed glyph data and resource exhaustion return typed failures.
     */
    internal fun readGlyphOutline(
        sourceBytes: ByteArray,
        parsedFont: ParsedTrueTypeFont,
        glyphId: GlyphId,
        profile: OutlineProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<ScalerGlyphOutline> {
        if (cancellationToken.isCancellationRequested()) return cancelled()
        if (glyphId.value !in 0 until parsedFont.metadata.glyphCount) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }
        val prepared = when (val result = prepare(sourceBytes, parsedFont, cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return readGlyphOutline(prepared, glyphId, profile, cancellationToken)
    }

    /** Prepares immutable `glyf`, `loca`, and `maxp` data for repeated outline reads. */
    internal fun prepare(
        sourceBytes: ByteArray,
        parsedFont: ParsedTrueTypeFont,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<PreparedGlyphData> {
        if (cancellationToken.isCancellationRequested()) return cancelled()
        val glyfRecord = parsedFont.tableRecords["glyf"] ?: return failure(FontError.MissingRequiredTable("glyf"))
        val glyf = slice(sourceBytes, glyfRecord)
            ?: return failure(fontFailure("font.glyf.truncated", "Table glyf exceeds source length.", tableLocation("glyf")))
        if (cancellationToken.isCancellationRequested()) return cancelled()
        val loca = when (val result = LocaReader.readLoca(sourceBytes, parsedFont, glyf.size, cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (cancellationToken.isCancellationRequested()) return cancelled()
        val maxp = when (val result = readMaxpLimits(sourceBytes, parsedFont)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (cancellationToken.isCancellationRequested()) return cancelled()
        return FontOperationResult.Success(
            PreparedGlyphData(
                glyf = glyf,
                loca = loca,
                glyphCount = parsedFont.metadata.glyphCount,
                unitsPerEm = parsedFont.metadata.unitsPerEm,
                maxp = maxp,
            ),
        )
    }

    /**
     * Returns the glyph whose horizontal metrics apply to [glyphId].
     *
     * A composite component carrying `USE_MY_METRICS` overrides the parent
     * composite's `hmtx` entry. The lookup is intentionally limited to the
     * component records and does not decode contours a second time.
     */
    internal fun horizontalMetricsGlyphId(
        prepared: PreparedGlyphData,
        glyphId: GlyphId,
    ): FontOperationResult<GlyphId> =
        readHorizontalMetricsGlyphId(prepared, glyphId, emptySet(), 0)

    /** Reads one outline from prepared immutable glyph tables. */
    internal fun readGlyphOutline(
        prepared: PreparedGlyphData,
        glyphId: GlyphId,
        profile: OutlineProfile,
        cancellationToken: CancellationToken,
    ): FontOperationResult<ScalerGlyphOutline> {
        if (cancellationToken.isCancellationRequested()) return cancelled()
        if (glyphId.value !in 0 until prepared.glyphCount) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }
        return GlyphResolver(
            prepared.glyf,
            prepared.loca,
            prepared.glyphCount,
            prepared.unitsPerEm,
            profile,
            prepared.maxp,
            cancellationToken,
        )
            .resolveRoot(glyphId)
    }

    private fun readHorizontalMetricsGlyphId(
        prepared: PreparedGlyphData,
        glyphId: GlyphId,
        path: Set<Int>,
        depth: Int,
    ): FontOperationResult<GlyphId> {
        if (glyphId.value !in 0 until prepared.glyphCount) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }
        if (glyphId.value in path) {
            return failure(fontFailure("font.glyf.composite-cycle", "Composite glyph re-enters the active path.", glyphLocation(glyphId.value)))
        }
        if (depth > prepared.maxp.maxComponentDepth) {
            return limitFailure(
                "Composite glyph depth limit exceeded.",
                glyphLocation(glyphId.value),
                depth.toLong(),
                prepared.maxp.maxComponentDepth.toLong(),
            )
        }
        val range = when (val result = prepared.loca.rangeForGlyph(glyphId)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (range.start == range.endExclusive) return FontOperationResult.Success(glyphId)
        if (range.endExclusive > prepared.glyf.size || range.endExclusive - range.start < 10) {
            return failure(fontFailure("font.glyf.truncated", "Glyph header is truncated.", glyphLocation(glyphId.value)))
        }

        val reader = GlyphByteReader(prepared.glyf, range.start, range.endExclusive)
        val contourCount = reader.readInt16() ?: return truncated(glyphId.value)
        repeat(4) {
            reader.readInt16() ?: return truncated(glyphId.value)
        }
        if (contourCount >= 0) return FontOperationResult.Success(glyphId)

        var metricsGlyphId = glyphId
        var flags: Int
        var componentCount = 0
        val activePath = path + glyphId.value
        do {
            if (componentCount >= prepared.maxp.maxComponentElements) {
                return limitFailure(
                    "Composite glyph component element limit exceeded.",
                    glyphLocation(glyphId.value),
                    (componentCount + 1).toLong(),
                    prepared.maxp.maxComponentElements.toLong(),
                )
            }
            flags = reader.readUInt16() ?: return truncated(glyphId.value)
            if (flags and SUPPORTED_COMPOSITE_FLAGS.inv() != 0) {
                return failure(fontFailure("font.glyf.unsupported-component", "Composite glyph has unsupported flags.", glyphLocation(glyphId.value)))
            }
            val componentGlyphId = reader.readUInt16() ?: return truncated(glyphId.value)
            if (componentGlyphId !in 0 until prepared.glyphCount) {
                return failure(fontFailure("font.glyf.component-out-of-range", "Composite component glyph ID is out of range.", glyphLocation(glyphId.value)))
            }
            val placement = readComponentPlacement(reader, flags) ?: return truncated(glyphId.value)
            val initialTranslation = if (placement is ComponentPlacement.Offset) {
                Pair(placement.x, placement.y)
            } else {
                Pair(0.0, 0.0)
            }
            when (
                val result = readComponentTransform(
                    reader,
                    flags,
                    initialTranslation.first,
                    initialTranslation.second,
                    glyphId.value,
                )
            ) {
                is FontOperationResult.Success -> Unit
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            componentCount += 1
            if (flags and COMPOSITE_USE_MY_METRICS != 0) {
                metricsGlyphId = when (
                    val result = readHorizontalMetricsGlyphId(
                        prepared = prepared,
                        glyphId = GlyphId(componentGlyphId),
                        path = activePath,
                        depth = depth + 1,
                    )
                ) {
                    is FontOperationResult.Success -> result.value
                    is FontOperationResult.Failure -> return result
                    is FontOperationResult.Cancelled -> return result
                }
            }
        } while (flags and COMPOSITE_MORE_COMPONENTS != 0)
        if (flags and COMPOSITE_WE_HAVE_INSTRUCTIONS != 0) {
            val instructionLength = reader.readUInt16() ?: return truncated(glyphId.value)
            if (!reader.skip(instructionLength)) return truncated(glyphId.value)
        }
        return FontOperationResult.Success(metricsGlyphId)
    }
}

/**
 * Immutable outline produced directly by the TrueType scaler.
 *
 * Contour commands retain fractional design-space coordinates generated by
 * implicit points and composite transforms. [bounds] remains an integer
 * envelope for compatibility with the public design-bounds contract.
 */
public class ScalerGlyphOutline(
    /** Numeric glyph identifier. */
    public val glyphId: Int,
    /** Design units in one em. */
    public val unitsPerEm: Int,
    /** Bounds declared by the decoded glyph. */
    public val bounds: DesignBounds,
    /** Decoded simple-glyph contours. */
    contours: List<GlyphContour>,
    /** Number of decoded points. */
    public val pointCount: Int,
    /** Direct composite component references. */
    components: List<GlyphComponentReference>,
) {
    /** Immutable contour snapshot. */
    public val contours: List<GlyphContour> = contours.immutableListSnapshot()
    /** Immutable component snapshot. */
    public val components: List<GlyphComponentReference> = components.immutableListSnapshot()

    /** Returns the glyph identifier. */
    public operator fun component1(): Int = glyphId

    /** Returns the units-per-em value. */
    public operator fun component2(): Int = unitsPerEm

    /** Returns the glyph bounds. */
    public operator fun component3(): DesignBounds = bounds

    /** Returns the contours. */
    public operator fun component4(): List<GlyphContour> = contours

    /** Returns the point count. */
    public operator fun component5(): Int = pointCount

    /** Returns the component references. */
    public operator fun component6(): List<GlyphComponentReference> = components

    /** Copies this scaler outline with selected fields changed. */
    public fun copy(
        glyphId: Int = this.glyphId,
        unitsPerEm: Int = this.unitsPerEm,
        bounds: DesignBounds = this.bounds,
        contours: List<GlyphContour> = this.contours,
        pointCount: Int = this.pointCount,
        components: List<GlyphComponentReference> = this.components,
    ): ScalerGlyphOutline = ScalerGlyphOutline(
        glyphId,
        unitsPerEm,
        bounds,
        contours,
        pointCount,
        components,
    )

    /** Compares all immutable outline fields and their ordered contents. */
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ScalerGlyphOutline &&
            glyphId == other.glyphId &&
            unitsPerEm == other.unitsPerEm &&
            bounds == other.bounds &&
            contours == other.contours &&
            pointCount == other.pointCount &&
            components == other.components

    /** Returns a hash derived from all immutable outline fields. */
    override fun hashCode(): Int {
        var result = glyphId
        result = 31 * result + unitsPerEm
        result = 31 * result + bounds.hashCode()
        result = 31 * result + contours.hashCode()
        result = 31 * result + pointCount
        result = 31 * result + components.hashCode()
        return result
    }

    /** Returns a diagnostic representation containing all outline fields. */
    override fun toString(): String =
        "ScalerGlyphOutline(glyphId=$glyphId, unitsPerEm=$unitsPerEm, bounds=$bounds, contours=$contours, pointCount=$pointCount, components=$components)"
}

internal data class PreparedGlyphData(
    val glyf: ByteArray,
    val loca: LocaTable,
    val glyphCount: Int,
    val unitsPerEm: Int,
    val maxp: MaxpLimits,
)

private class GlyphResolver(
    private val glyf: ByteArray,
    private val loca: LocaTable,
    private val glyphCount: Int,
    private val unitsPerEm: Int,
    private val profile: OutlineProfile,
    private val maxp: MaxpLimits,
    private val cancellationToken: CancellationToken,
) {
    private var componentCount = 0L
    private var consumedGlyphBytes = 0L

    fun resolveRoot(glyphId: GlyphId): FontOperationResult<ScalerGlyphOutline> =
        when (val result = resolve(
            glyphId,
            path = emptySet(),
            depth = 0,
            publishDirectComponents = true,
            remainingPointBudget = profile.maxPoints.toLong(),
            remainingContourBudget = profile.maxContours.toLong(),
        )) {
            is FontOperationResult.Success -> FontOperationResult.Success(result.value.toOutline(unitsPerEm), result.diagnostics)
            is FontOperationResult.Failure -> result
            is FontOperationResult.Cancelled -> result
        }

    private fun resolve(
        glyphId: GlyphId,
        path: Set<Int>,
        depth: Int,
        publishDirectComponents: Boolean,
        remainingPointBudget: Long,
        remainingContourBudget: Long,
    ): FontOperationResult<ResolvedGlyph> {
        if (cancellationToken.isCancellationRequested()) return cancelled()
        if (glyphId.value in path) {
            return failure(fontFailure("font.glyf.composite-cycle", "Composite glyph re-enters the active path.", glyphLocation(glyphId.value)))
        }
        val depthLimit = minOf(profile.maxCompositeDepth.toLong(), maxp.maxComponentDepth.toLong())
        if (depth.toLong() > depthLimit) {
            return limitFailure(
                "Composite glyph depth limit exceeded.",
                glyphLocation(glyphId.value),
                depth.toLong(),
                depthLimit,
            )
        }
        val range = when (val rangeResult = loca.rangeForGlyph(glyphId)) {
            is FontOperationResult.Success -> rangeResult.value
            is FontOperationResult.Failure -> return rangeResult
            is FontOperationResult.Cancelled -> return rangeResult
        }
        if (cancellationToken.isCancellationRequested()) return cancelled()
        if (range.start == range.endExclusive) {
            return FontOperationResult.Success(emptyOutline(glyphId.value))
        }
        if (range.endExclusive > glyf.size) {
            return failure(fontFailure("font.loca.out-of-range", "Glyph loca range exceeds glyf length.", tableLocation("loca")))
        }
        if (range.endExclusive - range.start < 10) {
            return failure(fontFailure("font.glyf.truncated", "Glyph header is truncated.", glyphLocation(glyphId.value)))
        }
        val glyphByteCount = range.endExclusive.toLong() - range.start.toLong()
        val nextConsumedBytes = checkedAdd(consumedGlyphBytes, glyphByteCount)
            ?: return limitFailure(
                "Expanded glyph byte budget overflowed.",
                glyphLocation(glyphId.value),
                Long.MAX_VALUE,
                profile.maxBytes.toLong(),
            )
        if (nextConsumedBytes > profile.maxBytes.toLong()) {
            return limitFailure(
                "Expanded glyph byte limit exceeded.",
                glyphLocation(glyphId.value),
                nextConsumedBytes,
                profile.maxBytes.toLong(),
            )
        }
        consumedGlyphBytes = nextConsumedBytes

        val reader = GlyphByteReader(glyf, range.start, range.endExclusive)
        val contourCount = reader.readInt16() ?: return truncated(glyphId.value)
        val bounds = DesignBounds(
            minX = reader.readInt16() ?: return truncated(glyphId.value),
            minY = reader.readInt16() ?: return truncated(glyphId.value),
            maxX = reader.readInt16() ?: return truncated(glyphId.value),
            maxY = reader.readInt16() ?: return truncated(glyphId.value),
        )
        if (cancellationToken.isCancellationRequested()) return cancelled()
        return if (contourCount >= 0) {
            readSimple(
                glyphId.value,
                contourCount,
                bounds,
                reader,
                remainingPointBudget,
                remainingContourBudget,
            )
        } else {
            readComposite(
                glyphId,
                bounds,
                reader,
                path + glyphId.value,
                depth,
                publishDirectComponents,
                remainingPointBudget,
                remainingContourBudget,
            )
        }
    }

    private fun readSimple(
        glyphId: Int,
        contourCount: Int,
        bounds: DesignBounds,
        reader: GlyphByteReader,
        remainingPointBudget: Long,
        remainingContourBudget: Long,
    ): FontOperationResult<ResolvedGlyph> {
        if (cancellationToken.isCancellationRequested()) return cancelled()
        val contourLimit = minOf(
            profile.maxContours.toLong(),
            maxp.maxContours.toLong(),
            remainingContourBudget,
        )
        if (contourCount.toLong() > contourLimit) {
            return limitFailure(
                "Simple glyph contour limit exceeded.",
                glyphLocation(glyphId),
                contourCount.toLong(),
                contourLimit,
            )
        }
        val endPoints = ArrayList<Int>(contourCount)
        repeat(contourCount) {
            val endPoint = reader.readUInt16() ?: return truncated(glyphId)
            if (endPoints.isNotEmpty() && endPoint <= endPoints.last()) {
                return failure(fontFailure("font.glyf.invalid-contours", "Contour endpoints must be strictly monotonic.", glyphLocation(glyphId)))
            }
            endPoints += endPoint
        }
        val instructionLength = reader.readUInt16() ?: return truncated(glyphId)
        if (!reader.skip(instructionLength)) return truncated(glyphId)
        val pointCount = endPoints.lastOrNull()?.plus(1) ?: 0
        val pointLimit = minOf(
            profile.maxPoints.toLong(),
            maxp.maxPoints.toLong(),
            remainingPointBudget,
        )
        if (pointCount.toLong() > pointLimit) {
            return limitFailure(
                "Simple glyph point limit exceeded.",
                glyphLocation(glyphId),
                pointCount.toLong(),
                pointLimit,
            )
        }
        val flags = when (val result = readFlags(reader, pointCount, glyphId, cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (cancellationToken.isCancellationRequested()) return cancelled()
        val xs = when (val result = readCoordinates(reader, flags, CoordinateAxis.X, glyphId, cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (cancellationToken.isCancellationRequested()) return cancelled()
        val ys = when (val result = readCoordinates(reader, flags, CoordinateAxis.Y, glyphId, cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (cancellationToken.isCancellationRequested()) return cancelled()
        val points = flags.indices.map { index ->
            GlyphPoint(xs[index], ys[index], flags[index] and FLAG_ON_CURVE != 0)
        }
        if (cancellationToken.isCancellationRequested()) return cancelled()
        return FontOperationResult.Success(
            ResolvedGlyph(
                glyphId = glyphId,
                bounds = boundsForPoints(points) ?: bounds,
                points = points,
                contourEndPoints = endPoints,
                components = emptyList(),
            ),
        )
    }

    private fun readComposite(
        glyphId: GlyphId,
        bounds: DesignBounds,
        reader: GlyphByteReader,
        path: Set<Int>,
        depth: Int,
        publishDirectComponents: Boolean,
        remainingPointBudget: Long,
        remainingContourBudget: Long,
    ): FontOperationResult<ResolvedGlyph> {
        val points = mutableListOf<GlyphPoint>()
        val contourEndPoints = mutableListOf<Int>()
        val directComponents = mutableListOf<GlyphComponentReference>()
        var componentElementCount = 0L
        val pointLimit = minOf(
            profile.maxPoints.toLong(),
            maxp.maxCompositePoints.toLong(),
            remainingPointBudget,
        )
        val contourLimit = minOf(
            profile.maxContours.toLong(),
            maxp.maxCompositeContours.toLong(),
            remainingContourBudget,
        )
        var flags: Int
        do {
            if (cancellationToken.isCancellationRequested()) return cancelled()
            val nextComponentCount = checkedAdd(componentCount, 1L)
                ?: return limitFailure(
                    "Composite glyph component budget overflowed.",
                    glyphLocation(glyphId.value),
                    Long.MAX_VALUE,
                    profile.maxCompositeComponents.toLong(),
                )
            if (nextComponentCount > profile.maxCompositeComponents.toLong()) {
                return limitFailure(
                    "Composite glyph component limit exceeded.",
                    glyphLocation(glyphId.value),
                    nextComponentCount,
                    profile.maxCompositeComponents.toLong(),
                )
            }
            componentCount = nextComponentCount
            val nextElementCount = checkedAdd(componentElementCount, 1L)
                ?: return limitFailure(
                    "Composite glyph component element budget overflowed.",
                    glyphLocation(glyphId.value),
                    Long.MAX_VALUE,
                    maxp.maxComponentElements.toLong(),
                )
            if (nextElementCount > maxp.maxComponentElements.toLong()) {
                return limitFailure(
                    "Composite glyph component element limit exceeded.",
                    glyphLocation(glyphId.value),
                    nextElementCount,
                    maxp.maxComponentElements.toLong(),
                )
            }
            componentElementCount = nextElementCount
            flags = reader.readUInt16() ?: return truncated(glyphId.value)
            if (flags and SUPPORTED_COMPOSITE_FLAGS.inv() != 0) {
                return failure(fontFailure("font.glyf.unsupported-component", "Composite glyph has unsupported flags.", glyphLocation(glyphId.value)))
            }
            if (
                flags and COMPOSITE_SCALED_COMPONENT_OFFSET != 0 &&
                flags and COMPOSITE_UNSCALED_COMPONENT_OFFSET != 0
            ) {
                return failure(
                    fontFailure(
                        "font.glyf.invalid-component-flags",
                        "Composite glyph declares both scaled and unscaled component offsets.",
                        glyphLocation(glyphId.value),
                    ),
                )
            }
            val componentGlyphId = reader.readUInt16() ?: return truncated(glyphId.value)
            if (componentGlyphId !in 0 until glyphCount) {
                return failure(fontFailure("font.glyf.component-out-of-range", "Composite component glyph ID is out of range.", glyphLocation(glyphId.value)))
            }
            val placement = readComponentPlacement(reader, flags) ?: return truncated(glyphId.value)
            if (componentElementCount == 1L && placement is ComponentPlacement.PointMatch) {
                return failure(
                    fontFailure(
                        "font.glyf.invalid-component-placement",
                        "The first composite component must use XY offsets.",
                        glyphLocation(glyphId.value),
                    ),
                )
            }
            if (placement is ComponentPlacement.PointMatch && placement.parentPointIndex !in points.indices) {
                return failure(
                    fontFailure(
                        "font.glyf.component-point-out-of-range",
                        "Composite parent point index is out of range.",
                        glyphLocation(glyphId.value),
                    ),
                )
            }
            val initialTranslation = if (placement is ComponentPlacement.Offset) {
                Pair(placement.x, placement.y)
            } else {
                Pair(0.0, 0.0)
            }
            val baseTransform = when (
                val result = readComponentTransform(
                    reader,
                    flags,
                    initialTranslation.first,
                    initialTranslation.second,
                    glyphId.value,
                )
            ) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            if (componentGlyphId in path) {
                return failure(fontFailure("font.glyf.composite-cycle", "Composite glyph re-enters the active path.", glyphLocation(glyphId.value)))
            }
            val child = when (
                val result = resolve(
                    GlyphId(componentGlyphId),
                    path = path,
                    depth = depth + 1,
                    publishDirectComponents = false,
                    remainingPointBudget = pointLimit - points.size.toLong(),
                    remainingContourBudget = contourLimit - contourEndPoints.size.toLong(),
                )
            ) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            if (cancellationToken.isCancellationRequested()) return cancelled()
            val nextPointCount = checkedAdd(points.size.toLong(), child.points.size.toLong())
                ?: return limitFailure(
                    "Composite glyph point budget overflowed.",
                    glyphLocation(glyphId.value),
                    Long.MAX_VALUE,
                    pointLimit,
                )
            if (nextPointCount > pointLimit) {
                return limitFailure(
                    "Composite glyph point limit exceeded.",
                    glyphLocation(glyphId.value),
                    nextPointCount,
                    pointLimit,
                )
            }
            val nextContourCount = checkedAdd(contourEndPoints.size.toLong(), child.contourEndPoints.size.toLong())
                ?: return limitFailure(
                    "Composite glyph contour budget overflowed.",
                    glyphLocation(glyphId.value),
                    Long.MAX_VALUE,
                    contourLimit,
                )
            if (nextContourCount > contourLimit) {
                return limitFailure(
                    "Composite glyph contour limit exceeded.",
                    glyphLocation(glyphId.value),
                    nextContourCount,
                    contourLimit,
                )
            }
            val transformedChildPoints = ArrayList<GlyphPoint>(child.points.size)
            for (point in child.points) {
                val transformed = transformPoint(point.x, point.y, baseTransform)
                    ?: return geometryOverflow(glyphId.value, "Composite point exceeds Int geometry range.")
                transformedChildPoints += GlyphPoint(transformed.first, transformed.second, point.onCurve)
            }
            val transform = when (placement) {
                is ComponentPlacement.Offset -> baseTransform
                is ComponentPlacement.PointMatch -> {
                    if (placement.childPointIndex !in transformedChildPoints.indices) {
                        return failure(
                            fontFailure(
                                "font.glyf.component-point-out-of-range",
                                "Composite child point index is out of range.",
                                glyphLocation(glyphId.value),
                            ),
                        )
                    }
                    val parentPoint = points[placement.parentPointIndex]
                    val childPoint = transformedChildPoints[placement.childPointIndex]
                    val translationX = parentPoint.x - childPoint.x
                    val translationY = parentPoint.y - childPoint.y
                    if (!isDesignCoordinate(translationX) || !isDesignCoordinate(translationY)) {
                        return geometryOverflow(glyphId.value, "Composite point alignment exceeds Int geometry range.")
                    }
                    for (index in transformedChildPoints.indices) {
                        val point = transformedChildPoints[index]
                        val translatedX = point.x + translationX
                        val translatedY = point.y + translationY
                        if (!isDesignCoordinate(translatedX) || !isDesignCoordinate(translatedY)) {
                            return geometryOverflow(glyphId.value, "Composite point alignment exceeds Int geometry range.")
                        }
                        transformedChildPoints[index] = GlyphPoint(
                            translatedX,
                            translatedY,
                            point.onCurve,
                        )
                    }
                    baseTransform.copy(
                        translationX = translationX,
                        translationY = translationY,
                    )
                }
            }
            if (publishDirectComponents) {
                directComponents += GlyphComponentReference(componentGlyphId, transform)
            }
            val pointOffset = points.size
            points += transformedChildPoints
            child.contourEndPoints.forEach { contourEndPoints += pointOffset + it }
        } while (flags and COMPOSITE_MORE_COMPONENTS != 0)

        if (flags and COMPOSITE_WE_HAVE_INSTRUCTIONS != 0) {
            val length = reader.readUInt16() ?: return truncated(glyphId.value)
            if (!reader.skip(length)) return truncated(glyphId.value)
        }
        if (cancellationToken.isCancellationRequested()) return cancelled()
        return FontOperationResult.Success(
            ResolvedGlyph(
                glyphId = glyphId.value,
                bounds = boundsForPoints(points) ?: bounds,
                points = points,
                contourEndPoints = contourEndPoints,
                components = directComponents,
            ),
        )
    }

    private fun emptyOutline(glyphId: Int): ResolvedGlyph =
        ResolvedGlyph(
            glyphId = glyphId,
            bounds = DesignBounds.empty,
            points = emptyList(),
            contourEndPoints = emptyList(),
            components = emptyList(),
        )
}

private fun readFlags(
    reader: GlyphByteReader,
    pointCount: Int,
    glyphId: Int,
    cancellationToken: CancellationToken,
): FontOperationResult<List<Int>> {
    val flags = ArrayList<Int>(pointCount)
    while (flags.size < pointCount) {
        if (cancellationToken.isCancellationRequested()) return cancelled()
        val raw = reader.readUInt8() ?: return truncated(glyphId)
        val flag = raw and FLAG_REPEAT.inv()
        val repeat = if (raw and FLAG_REPEAT != 0) reader.readUInt8() ?: return truncated(glyphId) else 0
        if (flags.size + repeat + 1 > pointCount) {
            return failure(fontFailure("font.glyf.invalid-flags", "Glyph flag repeat exceeds point count.", glyphLocation(glyphId)))
        }
        repeat(repeat + 1) {
            if (cancellationToken.isCancellationRequested()) return cancelled()
            flags += flag
        }
    }
    return FontOperationResult.Success(flags)
}

private fun readCoordinates(
    reader: GlyphByteReader,
    flags: List<Int>,
    axis: CoordinateAxis,
    glyphId: Int,
    cancellationToken: CancellationToken,
): FontOperationResult<List<Double>> {
    var current = 0L
    val values = ArrayList<Double>(flags.size)
    for (flag in flags) {
        if (cancellationToken.isCancellationRequested()) return cancelled()
        val shortFlag = if (axis == CoordinateAxis.X) FLAG_X_SHORT_VECTOR else FLAG_Y_SHORT_VECTOR
        val sameFlag = if (axis == CoordinateAxis.X) FLAG_X_SAME_OR_POSITIVE else FLAG_Y_SAME_OR_POSITIVE
        val delta = if (flag and shortFlag != 0) {
            val magnitude = reader.readUInt8() ?: return truncated(glyphId)
            if (flag and sameFlag != 0) magnitude else -magnitude
        } else if (flag and sameFlag != 0) {
            0
        } else {
            reader.readInt16() ?: return truncated(glyphId)
        }
        current += delta.toLong()
        if (current !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            return failure(fontFailure("font.glyf.coordinate-overflow", "Glyph coordinate deltas overflow Int.", glyphLocation(glyphId)))
        }
        values += current.toDouble()
    }
    return FontOperationResult.Success(values)
}

private fun commandsForContour(points: List<GlyphPoint>): List<GlyphOutlineCommand> {
    if (points.isEmpty()) return emptyList()
    val first = points.first()
    val last = points.last()
    val startPoint = when {
        first.onCurve -> first
        last.onCurve -> last
        else -> midpoint(last, first)
    }
    val commands = mutableListOf<GlyphOutlineCommand>(GlyphOutlineCommand.MoveTo(startPoint.x, startPoint.y))
    var currentPoint = startPoint
    var pendingOffCurve: GlyphPoint? = null
    val pointsToVisit = when {
        first.onCurve -> points.drop(1)
        last.onCurve -> points.dropLast(1)
        else -> points
    }

    for (point in pointsToVisit) {
        if (point.onCurve) {
            val control = pendingOffCurve
            if (control != null) {
                commands += GlyphOutlineCommand.QuadraticTo(control.x, control.y, point.x, point.y)
            } else if (currentPoint.x != point.x || currentPoint.y != point.y) {
                commands += GlyphOutlineCommand.LineTo(point.x, point.y)
            }
            pendingOffCurve = null
            currentPoint = point
        } else {
            val previous = pendingOffCurve
            if (previous != null) {
                val implicit = midpoint(previous, point)
                commands += GlyphOutlineCommand.QuadraticTo(previous.x, previous.y, implicit.x, implicit.y)
                currentPoint = implicit
            }
            pendingOffCurve = point
        }
    }
    val control = pendingOffCurve
    if (control != null) {
        commands += GlyphOutlineCommand.QuadraticTo(control.x, control.y, startPoint.x, startPoint.y)
    }
    commands += GlyphOutlineCommand.Close
    return commands
}

private fun readComponentPlacement(reader: GlyphByteReader, flags: Int): ComponentPlacement? =
    if (flags and COMPOSITE_ARGS_ARE_XY_VALUES != 0) {
        if (flags and COMPOSITE_ARG_1_AND_2_ARE_WORDS != 0) {
            ComponentPlacement.Offset(
                (reader.readInt16() ?: return null).toDouble(),
                (reader.readInt16() ?: return null).toDouble(),
            )
        } else {
            ComponentPlacement.Offset(
                (reader.readInt8() ?: return null).toDouble(),
                (reader.readInt8() ?: return null).toDouble(),
            )
        }
    } else {
        if (flags and COMPOSITE_ARG_1_AND_2_ARE_WORDS != 0) {
            ComponentPlacement.PointMatch(reader.readUInt16() ?: return null, reader.readUInt16() ?: return null)
        } else {
            ComponentPlacement.PointMatch(reader.readUInt8() ?: return null, reader.readUInt8() ?: return null)
        }
    }

private fun readComponentTransform(
    reader: GlyphByteReader,
    flags: Int,
    dx: Double,
    dy: Double,
    glyphId: Int,
): FontOperationResult<GlyphComponentTransform> {
    val hasUniform = flags and COMPOSITE_WE_HAVE_A_SCALE != 0
    val hasXY = flags and COMPOSITE_WE_HAVE_AN_X_AND_Y_SCALE != 0
    val hasMatrix = flags and COMPOSITE_WE_HAVE_A_TWO_BY_TWO != 0
    if (listOf(hasUniform, hasXY, hasMatrix).count { it } > 1) {
        return failure(
            fontFailure(
                "font.glyf.invalid-component-flags",
                "Composite glyph declares mutually exclusive transform flags.",
                glyphLocation(glyphId),
            ),
        )
    }
    val transform = when {
        hasUniform -> {
            val scale = reader.readInt16() ?: return truncated(glyphId)
            GlyphComponentTransform(dx, dy, scale, 0, 0, scale)
        }
        hasXY -> {
            val xScale = reader.readInt16() ?: return truncated(glyphId)
            val yScale = reader.readInt16() ?: return truncated(glyphId)
            GlyphComponentTransform(dx, dy, xScale, 0, 0, yScale)
        }
        hasMatrix -> {
            val xx = reader.readInt16() ?: return truncated(glyphId)
            val yx = reader.readInt16() ?: return truncated(glyphId)
            val xy = reader.readInt16() ?: return truncated(glyphId)
            val yy = reader.readInt16() ?: return truncated(glyphId)
            GlyphComponentTransform(dx, dy, xx, yx, xy, yy)
        }
        else -> GlyphComponentTransform(dx, dy)
    }
    val resolvedTransform = if (flags and COMPOSITE_SCALED_COMPONENT_OFFSET != 0) {
        val scaledOffset = transformVector(dx, dy, transform)
            ?: return geometryOverflow(glyphId, "Scaled composite component offset exceeds Int geometry range.")
        transform.copy(translationX = scaledOffset.first, translationY = scaledOffset.second)
    } else {
        transform
    }
    return FontOperationResult.Success(resolvedTransform)
}

private fun transformPoint(x: Double, y: Double, transform: GlyphComponentTransform): Pair<Double, Double>? {
    val vector = transformVector(x, y, transform) ?: return null
    val translatedX = vector.first + transform.translationX
    val translatedY = vector.second + transform.translationY
    if (!isDesignCoordinate(translatedX) || !isDesignCoordinate(translatedY)) return null
    return Pair(translatedX, translatedY)
}

private fun transformVector(x: Double, y: Double, transform: GlyphComponentTransform): Pair<Double, Double>? {
    val transformedX = (
        transform.xxF2Dot14.toDouble() * x +
            transform.xyF2Dot14.toDouble() * y
        ) / F2DOT14_ONE
    val transformedY = (
        transform.yxF2Dot14.toDouble() * x +
            transform.yyF2Dot14.toDouble() * y
        ) / F2DOT14_ONE
    if (!isDesignCoordinate(transformedX) || !isDesignCoordinate(transformedY)) return null
    return Pair(transformedX, transformedY)
}

private fun boundsForPoints(points: List<GlyphPoint>): DesignBounds? {
    if (points.isEmpty()) return null
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    for (point in points) {
        minX = minOf(minX, floor(point.x).toInt())
        minY = minOf(minY, floor(point.y).toInt())
        maxX = maxOf(maxX, ceil(point.x).toInt())
        maxY = maxOf(maxY, ceil(point.y).toInt())
    }
    return DesignBounds(minX, minY, maxX, maxY)
}

private fun midpoint(a: GlyphPoint, b: GlyphPoint): GlyphPoint =
    GlyphPoint(
        (a.x + b.x) / 2.0,
        (a.y + b.y) / 2.0,
        onCurve = true,
    )

private fun readMaxpLimits(
    sourceBytes: ByteArray,
    parsedFont: ParsedTrueTypeFont,
): FontOperationResult<MaxpLimits> {
    val record = parsedFont.tableRecords["maxp"] ?: return failure(FontError.MissingRequiredTable("maxp"))
    val maxp = slice(sourceBytes, record)
        ?: return failure(
            fontFailure("font.maxp.out-of-range", "Table maxp exceeds source length.", tableLocation("maxp")),
            FontDiagnosticData(offset = record.offset, length = record.length),
        )
    if (maxp.size < MAXP_VERSION_1_LENGTH) {
        return failure(
            fontFailure("font.maxp.truncated", "Version 1.0 maxp limits are truncated.", tableLocation("maxp")),
            FontDiagnosticData(observedValue = maxp.size.toLong(), limit = MAXP_VERSION_1_LENGTH.toLong()),
        )
    }
    fun requiredUInt16(offset: Int): Int? = readUInt16(maxp, offset)?.toInt()
    return FontOperationResult.Success(
        MaxpLimits(
            maxPoints = requiredUInt16(6) ?: return failure(fontFailure("font.maxp.truncated", "maxp.maxPoints is truncated.", tableLocation("maxp"))),
            maxContours = requiredUInt16(8) ?: return failure(fontFailure("font.maxp.truncated", "maxp.maxContours is truncated.", tableLocation("maxp"))),
            maxCompositePoints = requiredUInt16(10) ?: return failure(fontFailure("font.maxp.truncated", "maxp.maxCompositePoints is truncated.", tableLocation("maxp"))),
            maxCompositeContours = requiredUInt16(12) ?: return failure(fontFailure("font.maxp.truncated", "maxp.maxCompositeContours is truncated.", tableLocation("maxp"))),
            maxComponentElements = requiredUInt16(28) ?: return failure(fontFailure("font.maxp.truncated", "maxp.maxComponentElements is truncated.", tableLocation("maxp"))),
            maxComponentDepth = requiredUInt16(30) ?: return failure(fontFailure("font.maxp.truncated", "maxp.maxComponentDepth is truncated.", tableLocation("maxp"))),
        ),
    )
}

internal data class MaxpLimits(
    val maxPoints: Int,
    val maxContours: Int,
    val maxCompositePoints: Int,
    val maxCompositeContours: Int,
    val maxComponentElements: Int,
    val maxComponentDepth: Int,
)

private class GlyphByteReader(
    private val data: ByteArray,
    private val start: Int,
    private val endExclusive: Int,
) {
    private var offset = start

    fun readInt16(): Int? = read(2) { readInt16(data, it) }
    fun readUInt16(): Int? = read(2) { readUInt16(data, it)?.toInt() }
    fun readUInt8(): Int? = read(1) { data[it].toInt() and 0xFF }
    fun readInt8(): Int? = readUInt8()?.let { if (it and 0x80 != 0) it - 0x100 else it }

    fun skip(byteCount: Int): Boolean {
        val nextOffset = checkedRangeEnd(offset.toLong(), byteCount.toLong(), endExclusive) ?: return false
        offset = nextOffset
        return true
    }

    private fun read(byteCount: Int, block: (Int) -> Int?): Int? {
        val nextOffset = checkedRangeEnd(offset.toLong(), byteCount.toLong(), endExclusive) ?: return null
        val value = block(offset)
        offset = nextOffset
        return value
    }
}

private data class GlyphPoint(
    val x: Double,
    val y: Double,
    val onCurve: Boolean,
)

private data class ResolvedGlyph(
    val glyphId: Int,
    val bounds: DesignBounds,
    val points: List<GlyphPoint>,
    val contourEndPoints: List<Int>,
    val components: List<GlyphComponentReference>,
) {
    fun toOutline(unitsPerEm: Int): ScalerGlyphOutline {
        val contours = ArrayList<GlyphContour>(contourEndPoints.size)
        var start = 0
        for (end in contourEndPoints) {
            contours += GlyphContour(commandsForContour(points.subList(start, end + 1)))
            start = end + 1
        }
        return ScalerGlyphOutline(
            glyphId = glyphId,
            unitsPerEm = unitsPerEm,
            bounds = bounds,
            contours = contours,
            pointCount = points.size,
            components = components,
        )
    }
}

private sealed interface ComponentPlacement {
    data class Offset(val x: Double, val y: Double) : ComponentPlacement

    data class PointMatch(
        val parentPointIndex: Int,
        val childPointIndex: Int,
    ) : ComponentPlacement
}

private fun truncated(glyphId: Int): FontOperationResult.Failure =
    failure(fontFailure("font.glyf.truncated", "Glyph data is truncated.", glyphLocation(glyphId)))

private fun limitFailure(
    message: String,
    location: FontDiagnosticLocation,
    observedValue: Long,
    limit: Long,
): FontOperationResult.Failure =
    failure(
        FontError.ResourceLimitExceeded(message, location),
        FontDiagnosticData(observedValue = observedValue, limit = limit),
    )

private fun geometryOverflow(glyphId: Int, message: String): FontOperationResult.Failure =
    failure(FontError.GeometryOverflow(message, glyphLocation(glyphId)))

private fun checkedAdd(left: Long, right: Long): Long? {
    if (right > 0L && left > Long.MAX_VALUE - right) return null
    if (right < 0L && left < Long.MIN_VALUE - right) return null
    return left + right
}

private fun cancelled(): FontOperationResult.Cancelled = FontOperationResult.Cancelled()

private fun glyphLocation(glyphId: Int): FontDiagnosticLocation = FontDiagnosticLocation.Glyph(glyphId)

private enum class CoordinateAxis {
    X,
    Y,
}

private const val FLAG_ON_CURVE = 0x01
private const val FLAG_X_SHORT_VECTOR = 0x02
private const val FLAG_Y_SHORT_VECTOR = 0x04
private const val FLAG_REPEAT = 0x08
private const val FLAG_X_SAME_OR_POSITIVE = 0x10
private const val FLAG_Y_SAME_OR_POSITIVE = 0x20

private const val MAXP_VERSION_1_LENGTH = 32
private const val F2DOT14_ONE = 16_384.0
private const val DESIGN_MIN = Int.MIN_VALUE.toDouble()
private const val DESIGN_MAX = Int.MAX_VALUE.toDouble()

private const val COMPOSITE_ARG_1_AND_2_ARE_WORDS = 0x0001
private const val COMPOSITE_ARGS_ARE_XY_VALUES = 0x0002
private const val COMPOSITE_ROUND_XY_TO_GRID = 0x0004
private const val COMPOSITE_WE_HAVE_A_SCALE = 0x0008
private const val COMPOSITE_MORE_COMPONENTS = 0x0020
private const val COMPOSITE_WE_HAVE_AN_X_AND_Y_SCALE = 0x0040
private const val COMPOSITE_WE_HAVE_A_TWO_BY_TWO = 0x0080
private const val COMPOSITE_WE_HAVE_INSTRUCTIONS = 0x0100
private const val COMPOSITE_USE_MY_METRICS = 0x0200
private const val COMPOSITE_OVERLAP_COMPOUND = 0x0400
private const val COMPOSITE_SCALED_COMPONENT_OFFSET = 0x0800
private const val COMPOSITE_UNSCALED_COMPONENT_OFFSET = 0x1000
private const val SUPPORTED_COMPOSITE_FLAGS = COMPOSITE_ARG_1_AND_2_ARE_WORDS or
    COMPOSITE_ARGS_ARE_XY_VALUES or
    COMPOSITE_ROUND_XY_TO_GRID or
    COMPOSITE_WE_HAVE_A_SCALE or
    COMPOSITE_MORE_COMPONENTS or
    COMPOSITE_WE_HAVE_AN_X_AND_Y_SCALE or
    COMPOSITE_WE_HAVE_A_TWO_BY_TWO or
    COMPOSITE_WE_HAVE_INSTRUCTIONS or
    COMPOSITE_USE_MY_METRICS or
    COMPOSITE_OVERLAP_COMPOUND or
    COMPOSITE_SCALED_COMPONENT_OFFSET or
    COMPOSITE_UNSCALED_COMPONENT_OFFSET

private fun isDesignCoordinate(value: Double): Boolean = value.isFinite() && value in DESIGN_MIN..DESIGN_MAX
