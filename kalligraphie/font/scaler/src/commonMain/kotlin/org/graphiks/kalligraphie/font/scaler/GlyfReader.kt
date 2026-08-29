package org.graphiks.kalligraphie.font.scaler

import kotlin.math.roundToInt
import org.graphiks.kalligraphie.api.DesignBounds
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
import org.graphiks.kalligraphie.font.sfnt.readInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.slice

public object GlyfReader {
    public fun readGlyphOutline(
        sourceBytes: ByteArray,
        parsedFont: ParsedTrueTypeFont,
        glyphId: GlyphId,
        profile: OutlineProfile,
    ): FontOperationResult<ScalerGlyphOutline> {
        if (glyphId.value !in 0 until parsedFont.metadata.glyphCount) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }
        val glyfRecord = parsedFont.tableRecords["glyf"] ?: return failure(FontError.MissingRequiredTable("glyf"))
        val glyf = slice(sourceBytes, glyfRecord)
            ?: return failure(fontFailure("font.glyf.truncated", "Table glyf exceeds source length.", tableLocation("glyf")))
        val loca = when (val result = LocaReader.readLoca(sourceBytes, parsedFont, glyf.size)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val maxp = readMaxpLimits(sourceBytes, parsedFont)
        return GlyphResolver(glyf, loca, parsedFont.metadata.glyphCount, parsedFont.metadata.unitsPerEm, profile, maxp)
            .resolveRoot(glyphId)
    }
}

public data class ScalerGlyphOutline(
    public val glyphId: Int,
    public val unitsPerEm: Int,
    public val bounds: DesignBounds,
    public val contours: List<GlyphContour>,
    public val pointCount: Int,
    public val components: List<GlyphComponentReference>,
)

private class GlyphResolver(
    private val glyf: ByteArray,
    private val loca: LocaTable,
    private val glyphCount: Int,
    private val unitsPerEm: Int,
    private val profile: OutlineProfile,
    private val maxp: MaxpLimits,
) {
    private var componentCount = 0

    fun resolveRoot(glyphId: GlyphId): FontOperationResult<ScalerGlyphOutline> =
        resolve(glyphId, path = emptySet(), depth = 0, publishDirectComponents = true)

    private fun resolve(
        glyphId: GlyphId,
        path: Set<Int>,
        depth: Int,
        publishDirectComponents: Boolean,
    ): FontOperationResult<ScalerGlyphOutline> {
        if (glyphId.value in path) {
            return failure(fontFailure("font.glyf.composite-cycle", "Composite glyph re-enters the active path.", glyphLocation(glyphId.value)))
        }
        if (depth > profile.maxCompositeDepth || depth > maxp.maxComponentDepth) {
            return failure(FontError.ResourceLimitExceeded("Composite glyph depth limit exceeded.", glyphLocation(glyphId.value)))
        }
        val range = when (val rangeResult = loca.rangeForGlyph(glyphId)) {
            is FontOperationResult.Success -> rangeResult.value
            is FontOperationResult.Failure -> return rangeResult
            is FontOperationResult.Cancelled -> return rangeResult
        }
        if (range.start == range.endExclusive) {
            return FontOperationResult.Success(emptyOutline(glyphId.value))
        }
        if (range.endExclusive > glyf.size) {
            return failure(fontFailure("font.loca.out-of-range", "Glyph loca range exceeds glyf length.", tableLocation("loca")))
        }
        if (range.endExclusive - range.start < 10) {
            return failure(fontFailure("font.glyf.truncated", "Glyph header is truncated.", glyphLocation(glyphId.value)))
        }

        val reader = GlyphByteReader(glyf, range.start, range.endExclusive)
        val contourCount = reader.readInt16() ?: return truncated(glyphId.value)
        val bounds = DesignBounds(
            minX = reader.readInt16() ?: return truncated(glyphId.value),
            minY = reader.readInt16() ?: return truncated(glyphId.value),
            maxX = reader.readInt16() ?: return truncated(glyphId.value),
            maxY = reader.readInt16() ?: return truncated(glyphId.value),
        )
        return if (contourCount >= 0) {
            readSimple(glyphId.value, contourCount, bounds, reader)
        } else {
            readComposite(glyphId, bounds, reader, path + glyphId.value, depth, publishDirectComponents)
        }
    }

    private fun readSimple(
        glyphId: Int,
        contourCount: Int,
        bounds: DesignBounds,
        reader: GlyphByteReader,
    ): FontOperationResult<ScalerGlyphOutline> {
        if (contourCount > profile.maxContours || contourCount > maxp.maxContours) {
            return failure(FontError.ResourceLimitExceeded("Simple glyph contour limit exceeded.", glyphLocation(glyphId)))
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
        if (pointCount > profile.maxPoints || pointCount > maxp.maxPoints) {
            return failure(FontError.ResourceLimitExceeded("Simple glyph point limit exceeded.", glyphLocation(glyphId)))
        }
        val flags = when (val result = readFlags(reader, pointCount, glyphId)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val xs = when (val result = readCoordinates(reader, flags, CoordinateAxis.X, glyphId)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val ys = when (val result = readCoordinates(reader, flags, CoordinateAxis.Y, glyphId)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val points = flags.indices.map { index ->
            GlyphPoint(xs[index], ys[index], flags[index] and FLAG_ON_CURVE != 0)
        }
        val contours = ArrayList<GlyphContour>(contourCount)
        var start = 0
        for (end in endPoints) {
            contours += GlyphContour(commandsForContour(points.subList(start, end + 1)))
            start = end + 1
        }
        return FontOperationResult.Success(
            ScalerGlyphOutline(
                glyphId = glyphId,
                unitsPerEm = unitsPerEm,
                bounds = boundsForPoints(points) ?: bounds,
                contours = contours,
                pointCount = pointCount,
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
    ): FontOperationResult<ScalerGlyphOutline> {
        val contours = mutableListOf<GlyphContour>()
        val directComponents = mutableListOf<GlyphComponentReference>()
        var pointCount = 0
        var componentElementCount = 0
        var flags: Int
        do {
            componentCount += 1
            if (componentCount > profile.maxCompositeComponents) {
                return failure(FontError.ResourceLimitExceeded("Composite glyph component limit exceeded.", glyphLocation(glyphId.value)))
            }
            componentElementCount += 1
            if (componentElementCount > maxp.maxComponentElements) {
                return failure(FontError.ResourceLimitExceeded("Composite glyph component element limit exceeded.", glyphLocation(glyphId.value)))
            }
            flags = reader.readUInt16() ?: return truncated(glyphId.value)
            if (flags and SUPPORTED_COMPOSITE_FLAGS.inv() != 0) {
                return failure(fontFailure("font.glyf.unsupported-component", "Composite glyph has unsupported flags.", glyphLocation(glyphId.value)))
            }
            val componentGlyphId = reader.readUInt16() ?: return truncated(glyphId.value)
            if (componentGlyphId !in 0 until glyphCount) {
                return failure(fontFailure("font.glyf.component-out-of-range", "Composite component glyph ID is out of range.", glyphLocation(glyphId.value)))
            }
            if (flags and COMPOSITE_ARGS_ARE_XY_VALUES == 0) {
                return failure(fontFailure("font.glyf.unsupported-component", "Composite point matching is not supported in J1.3.", glyphLocation(glyphId.value)))
            }
            val (translationX, translationY) = readComponentTranslation(reader, flags) ?: return truncated(glyphId.value)
            val transform = readComponentTransform(reader, flags, translationX, translationY)
                ?: return failure(fontFailure("font.glyf.unsupported-component", "Composite scale flags are invalid.", glyphLocation(glyphId.value)))
            val componentReference = GlyphComponentReference(componentGlyphId, transform)
            if (publishDirectComponents) {
                directComponents += componentReference
            }
            if (componentGlyphId in path) {
                return failure(fontFailure("font.glyf.composite-cycle", "Composite glyph re-enters the active path.", glyphLocation(glyphId.value)))
            }
            val child = when (
                val result = resolve(GlyphId(componentGlyphId), path = path, depth = depth + 1, publishDirectComponents = false)
            ) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            contours += child.contours.map { contour -> transformContour(contour, transform) }
            pointCount += child.pointCount
            if (pointCount > profile.maxPoints || pointCount > maxp.maxCompositePoints) {
                return failure(FontError.ResourceLimitExceeded("Composite glyph point limit exceeded.", glyphLocation(glyphId.value)))
            }
        } while (flags and COMPOSITE_MORE_COMPONENTS != 0)

        if (flags and COMPOSITE_WE_HAVE_INSTRUCTIONS != 0) {
            val length = reader.readUInt16() ?: return truncated(glyphId.value)
            if (!reader.skip(length)) return truncated(glyphId.value)
        }
        if (contours.size > profile.maxContours || contours.size > maxp.maxCompositeContours) {
            return failure(FontError.ResourceLimitExceeded("Composite glyph contour limit exceeded.", glyphLocation(glyphId.value)))
        }
        return FontOperationResult.Success(
            ScalerGlyphOutline(
                glyphId = glyphId.value,
                unitsPerEm = unitsPerEm,
                bounds = boundsForContours(contours) ?: bounds,
                contours = contours,
                pointCount = pointCount,
                components = directComponents,
            ),
        )
    }

    private fun emptyOutline(glyphId: Int): ScalerGlyphOutline =
        ScalerGlyphOutline(
            glyphId = glyphId,
            unitsPerEm = unitsPerEm,
            bounds = DesignBounds.empty,
            contours = emptyList(),
            pointCount = 0,
            components = emptyList(),
        )
}

private fun readFlags(
    reader: GlyphByteReader,
    pointCount: Int,
    glyphId: Int,
): FontOperationResult<List<Int>> {
    val flags = ArrayList<Int>(pointCount)
    while (flags.size < pointCount) {
        val raw = reader.readUInt8() ?: return truncated(glyphId)
        val flag = raw and FLAG_REPEAT.inv()
        val repeat = if (raw and FLAG_REPEAT != 0) reader.readUInt8() ?: return truncated(glyphId) else 0
        if (flags.size + repeat + 1 > pointCount) {
            return failure(fontFailure("font.glyf.invalid-flags", "Glyph flag repeat exceeds point count.", glyphLocation(glyphId)))
        }
        repeat(repeat + 1) {
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
): FontOperationResult<List<Int>> {
    var current = 0L
    val values = ArrayList<Int>(flags.size)
    for (flag in flags) {
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
        values += current.toInt()
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

private fun readComponentTranslation(reader: GlyphByteReader, flags: Int): Pair<Int, Int>? =
    if (flags and COMPOSITE_ARG_1_AND_2_ARE_WORDS != 0) {
        Pair(reader.readInt16() ?: return null, reader.readInt16() ?: return null)
    } else {
        Pair(reader.readInt8() ?: return null, reader.readInt8() ?: return null)
    }

private fun readComponentTransform(reader: GlyphByteReader, flags: Int, dx: Int, dy: Int): GlyphComponentTransform? {
    val hasUniform = flags and COMPOSITE_WE_HAVE_A_SCALE != 0
    val hasXY = flags and COMPOSITE_WE_HAVE_AN_X_AND_Y_SCALE != 0
    val hasMatrix = flags and COMPOSITE_WE_HAVE_A_TWO_BY_TWO != 0
    if (listOf(hasUniform, hasXY, hasMatrix).count { it } > 1) return null
    val transform = when {
        hasUniform -> {
            val scale = reader.readInt16() ?: return null
            GlyphComponentTransform(dx, dy, scale, 0, 0, scale)
        }
        hasXY -> {
            val xScale = reader.readInt16() ?: return null
            val yScale = reader.readInt16() ?: return null
            GlyphComponentTransform(dx, dy, xScale, 0, 0, yScale)
        }
        hasMatrix -> {
            val xx = reader.readInt16() ?: return null
            val yx = reader.readInt16() ?: return null
            val xy = reader.readInt16() ?: return null
            val yy = reader.readInt16() ?: return null
            GlyphComponentTransform(dx, dy, xx, yx, xy, yy)
        }
        else -> GlyphComponentTransform(dx, dy)
    }
    return if (flags and COMPOSITE_SCALED_COMPONENT_OFFSET != 0 && flags and COMPOSITE_UNSCALED_COMPONENT_OFFSET == 0) {
        transform.copy(
            translationX = ((transform.xxF2Dot14.toDouble() * dx.toDouble() + transform.xyF2Dot14.toDouble() * dy.toDouble()) / 16_384.0).roundToInt(),
            translationY = ((transform.yxF2Dot14.toDouble() * dx.toDouble() + transform.yyF2Dot14.toDouble() * dy.toDouble()) / 16_384.0).roundToInt(),
        )
    } else {
        transform
    }
}

private fun transformContour(contour: GlyphContour, transform: GlyphComponentTransform): GlyphContour =
    GlyphContour(
        contour.commands.map { command ->
            when (command) {
                is GlyphOutlineCommand.MoveTo -> {
                    val point = transformPoint(command.x, command.y, transform)
                    GlyphOutlineCommand.MoveTo(point.first, point.second)
                }
                is GlyphOutlineCommand.LineTo -> {
                    val point = transformPoint(command.x, command.y, transform)
                    GlyphOutlineCommand.LineTo(point.first, point.second)
                }
                is GlyphOutlineCommand.QuadraticTo -> {
                    val control = transformPoint(command.controlX, command.controlY, transform)
                    val end = transformPoint(command.endX, command.endY, transform)
                    GlyphOutlineCommand.QuadraticTo(control.first, control.second, end.first, end.second)
                }
                GlyphOutlineCommand.Close -> GlyphOutlineCommand.Close
            }
        },
    )

private fun transformPoint(x: Int, y: Int, transform: GlyphComponentTransform): Pair<Int, Int> {
    val tx = (transform.xxF2Dot14.toDouble() * x.toDouble() + transform.xyF2Dot14.toDouble() * y.toDouble()) / 16_384.0 +
        transform.translationX.toDouble()
    val ty = (transform.yxF2Dot14.toDouble() * x.toDouble() + transform.yyF2Dot14.toDouble() * y.toDouble()) / 16_384.0 +
        transform.translationY.toDouble()
    return Pair(tx.roundToInt(), ty.roundToInt())
}

private fun boundsForContours(contours: List<GlyphContour>): DesignBounds? {
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    var hasPoint = false

    fun include(x: Int, y: Int) {
        minX = minOf(minX, x)
        minY = minOf(minY, y)
        maxX = maxOf(maxX, x)
        maxY = maxOf(maxY, y)
        hasPoint = true
    }

    for (contour in contours) {
        for (command in contour.commands) {
            when (command) {
                is GlyphOutlineCommand.MoveTo -> include(command.x, command.y)
                is GlyphOutlineCommand.LineTo -> include(command.x, command.y)
                is GlyphOutlineCommand.QuadraticTo -> {
                    include(command.controlX, command.controlY)
                    include(command.endX, command.endY)
                }
                GlyphOutlineCommand.Close -> Unit
            }
        }
    }
    return if (hasPoint) DesignBounds(minX, minY, maxX, maxY) else null
}

private fun boundsForPoints(points: List<GlyphPoint>): DesignBounds? {
    if (points.isEmpty()) return null
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    for (point in points) {
        minX = minOf(minX, point.x)
        minY = minOf(minY, point.y)
        maxX = maxOf(maxX, point.x)
        maxY = maxOf(maxY, point.y)
    }
    return DesignBounds(minX, minY, maxX, maxY)
}

private fun midpoint(a: GlyphPoint, b: GlyphPoint): GlyphPoint =
    GlyphPoint((a.x + b.x) / 2, (a.y + b.y) / 2, onCurve = true)

private fun readMaxpLimits(sourceBytes: ByteArray, parsedFont: ParsedTrueTypeFont): MaxpLimits {
    val maxp = parsedFont.tableRecords["maxp"]?.let { slice(sourceBytes, it) } ?: return MaxpLimits()
    return MaxpLimits(
        maxPoints = readUInt16(maxp, 6)?.toInt()?.takeIf { it > 0 } ?: Int.MAX_VALUE,
        maxContours = readUInt16(maxp, 8)?.toInt()?.takeIf { it > 0 } ?: Int.MAX_VALUE,
        maxCompositePoints = readUInt16(maxp, 10)?.toInt()?.takeIf { it > 0 } ?: Int.MAX_VALUE,
        maxCompositeContours = readUInt16(maxp, 12)?.toInt()?.takeIf { it > 0 } ?: Int.MAX_VALUE,
        maxComponentElements = readUInt16(maxp, 28)?.toInt()?.takeIf { it > 0 } ?: Int.MAX_VALUE,
        maxComponentDepth = readUInt16(maxp, 30)?.toInt()?.takeIf { it > 0 } ?: Int.MAX_VALUE,
    )
}

private data class MaxpLimits(
    val maxPoints: Int = Int.MAX_VALUE,
    val maxContours: Int = Int.MAX_VALUE,
    val maxCompositePoints: Int = Int.MAX_VALUE,
    val maxCompositeContours: Int = Int.MAX_VALUE,
    val maxComponentElements: Int = Int.MAX_VALUE,
    val maxComponentDepth: Int = Int.MAX_VALUE,
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
        if (byteCount < 0 || offset + byteCount > endExclusive) return false
        offset += byteCount
        return true
    }

    private fun read(byteCount: Int, block: (Int) -> Int?): Int? {
        if (offset + byteCount > endExclusive) return null
        val value = block(offset)
        offset += byteCount
        return value
    }
}

private data class GlyphPoint(
    val x: Int,
    val y: Int,
    val onCurve: Boolean,
)

private fun truncated(glyphId: Int): FontOperationResult.Failure =
    failure(fontFailure("font.glyf.truncated", "Glyph data is truncated.", glyphLocation(glyphId)))

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
