@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.glyph

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.font.scaler.ScalerGlyphOutline

/**
 * Fixed, versioned synthetic bold and italic geometry for a scaler outline.
 *
 * The two style amounts are constants with a version marker, not axis-derived and not user-tunable:
 * bold offsets each contour outward by [BOLD_EM_FRACTION_PER_SIDE] of the em on every side, and
 * italic shears each point about the baseline with the tangent [ITALIC_SHEAR_TANGENT] (`tan 14°`).
 * Italic is applied first so that bold is computed on the sheared geometry. The transform preserves
 * every contour, point and command, and recomputes only the integer [DesignBounds] envelope; it
 * never adds geometry, so it cannot push an outline over a byte, contour, point or component limit.
 *
 * This is the fixed geometric interpretation of `FontGeometryParameters.syntheticBold` /
 * `syntheticItalic`; the default instance (both flags false) is returned by identity, so the
 * pre-existing path is unchanged. Changing either amount is a breaking change for persisted
 * instance identity and must bump [VERSION].
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object SyntheticGeometry {
    /** Version of the pinned synthetic-geometry amounts. Bump when either amount changes. */
    public const val VERSION: Int = 1

    /** Synthetic bold outward offset per side, as a fraction of the em. */
    public const val BOLD_EM_FRACTION_PER_SIDE: Double = 0.02

    /** Synthetic italic shear tangent, pinning the CSS `oblique` 14-degree default. */
    public const val ITALIC_SHEAR_TANGENT: Double = 0.2493280028431807

    /** Below this cosine the miter length is clamped to twice the offset to avoid spikes. */
    private const val MIN_MITER_COS: Double = 0.5

    /** Edges shorter than this are treated as degenerate and contribute no normal. */
    private const val EDGE_EPSILON: Double = 1e-9

    /**
     * Applies the requested synthetic styles to [outline].
     *
     * Returns [outline] unchanged (same instance) when no style is requested or the outline has no
     * contours. Every contour/point/command is preserved; only coordinates and [DesignBounds] change.
     * A transformed coordinate outside the `Int` design range fails with `font.geometry-overflow`.
     * Cancellation is observed between contours and returns [FontOperationResult.Cancelled].
     */
    public fun apply(
        outline: ScalerGlyphOutline,
        bold: Boolean,
        italic: Boolean,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<ScalerGlyphOutline> {
        if (!bold && !italic) return FontOperationResult.Success(outline)
        if (outline.contours.isEmpty()) return FontOperationResult.Success(outline)
        val delta = outline.unitsPerEm.toDouble() * BOLD_EM_FRACTION_PER_SIDE
        val transformed = ArrayList<GlyphContour>(outline.contours.size)
        val points = ArrayList<Pair<Double, Double>>()
        for (contour in outline.contours) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val anchors = contour.anchors()
            if (anchors.isEmpty()) return FontOperationResult.Success(outline)
            val shearedAnchors = if (italic) anchors.map { (x, y) -> (x + ITALIC_SHEAR_TANGENT * y) to y } else anchors
            val offsets = if (bold) anchorOffsets(shearedAnchors, delta) else List(anchors.size) { 0.0 to 0.0 }
            val commands = ArrayList<GlyphOutlineCommand>(contour.commands.size)
            var startOffset = offsets.first()
            var anchorIndex = 0
            for (command in contour.commands) {
                when (command) {
                    is GlyphOutlineCommand.MoveTo -> {
                        val point = transformPoint(command.x, command.y, offsets[0], bold, italic)
                        commands += GlyphOutlineCommand.MoveTo(point.first, point.second)
                        points += point
                        startOffset = offsets[0]
                    }

                    is GlyphOutlineCommand.LineTo -> {
                        anchorIndex += 1
                        val point = transformPoint(command.x, command.y, offsets[anchorIndex], bold, italic)
                        commands += GlyphOutlineCommand.LineTo(point.first, point.second)
                        points += point
                        startOffset = offsets[anchorIndex]
                    }

                    is GlyphOutlineCommand.QuadraticTo -> {
                        anchorIndex += 1
                        val end = transformPoint(command.endX, command.endY, offsets[anchorIndex], bold, italic)
                        val control = transformControl(
                            command.controlX,
                            command.controlY,
                            startOffset,
                            offsets[anchorIndex],
                            bold,
                            italic,
                        )
                        commands += GlyphOutlineCommand.QuadraticTo(control.first, control.second, end.first, end.second)
                        points += control
                        points += end
                        startOffset = offsets[anchorIndex]
                    }

                    is GlyphOutlineCommand.CubicTo -> {
                        anchorIndex += 1
                        val end = transformPoint(command.endX, command.endY, offsets[anchorIndex], bold, italic)
                        val first = transformControl(
                            command.control1X,
                            command.control1Y,
                            startOffset,
                            offsets[anchorIndex],
                            bold,
                            italic,
                        )
                        val second = transformControl(
                            command.control2X,
                            command.control2Y,
                            startOffset,
                            offsets[anchorIndex],
                            bold,
                            italic,
                        )
                        commands += GlyphOutlineCommand.CubicTo(
                            first.first,
                            first.second,
                            second.first,
                            second.second,
                            end.first,
                            end.second,
                        )
                        points += first
                        points += second
                        points += end
                        startOffset = offsets[anchorIndex]
                    }

                    GlyphOutlineCommand.Close -> commands += GlyphOutlineCommand.Close
                }
            }
            transformed += GlyphContour(commands)
        }
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        val bounds = boundsOf(points, outline.bounds)
            ?: return FontOperationResult.Failure(
                FontError.GeometryOverflow(
                    "Synthetic geometry exceeds the design-coordinate range.",
                    FontDiagnosticLocation.Glyph(outline.glyphId),
                ),
            )
        return FontOperationResult.Success(
            outline.copy(contours = transformed, pointCount = outline.pointCount, bounds = bounds),
        )
    }

    private fun GlyphContour.anchors(): List<Pair<Double, Double>> = commands.mapNotNull { command ->
        when (command) {
            is GlyphOutlineCommand.MoveTo -> command.x to command.y
            is GlyphOutlineCommand.LineTo -> command.x to command.y
            is GlyphOutlineCommand.QuadraticTo -> command.endX to command.endY
            is GlyphOutlineCommand.CubicTo -> command.endX to command.endY
            GlyphOutlineCommand.Close -> null
        }
    }

    /**
     * Outward miter offset per anchor. The sign uses the closed-polygon shoelace area so an outer
     * contour grows and a hole shrinks under the non-zero fill rule; the miter factor is clamped so
     * a sharp spike cannot produce an unbounded offset.
     */
    private fun anchorOffsets(anchors: List<Pair<Double, Double>>, delta: Double): List<Pair<Double, Double>> {
        val count = anchors.size
        if (count < 2 || delta == 0.0) return List(count) { 0.0 to 0.0 }
        var twiceArea = 0.0
        for (index in 0 until count) {
            val (x1, y1) = anchors[index]
            val (x2, y2) = anchors[(index + 1) % count]
            twiceArea += x1 * y2 - x2 * y1
        }
        val outwardSign = if (twiceArea < 0.0) 1.0 else -1.0
        return List(count) { index ->
            val (x, y) = anchors[index]
            val (previousX, previousY) = anchors[(index - 1 + count) % count]
            val (nextX, nextY) = anchors[(index + 1) % count]
            val incoming = outwardNormal(x - previousX, y - previousY, outwardSign)
            val outgoing = outwardNormal(nextX - x, nextY - y, outwardSign)
            val miter = when {
                incoming == null && outgoing == null -> 0.0 to 0.0
                incoming == null -> outgoing!!
                outgoing == null -> incoming
                else -> {
                    val sumX = incoming.first + outgoing.first
                    val sumY = incoming.second + outgoing.second
                    val length = hypot(sumX, sumY)
                    if (length < EDGE_EPSILON) incoming else (sumX / length) to (sumY / length)
                }
            }
            val projection = if (incoming == null) 1.0 else miter.first * incoming.first + miter.second * incoming.second
            val factor = 1.0 / projection.coerceAtLeast(MIN_MITER_COS)
            (miter.first * delta * factor) to (miter.second * delta * factor)
        }
    }

    private fun outwardNormal(dx: Double, dy: Double, outwardSign: Double): Pair<Double, Double>? {
        val length = hypot(dx, dy)
        if (length < EDGE_EPSILON) return null
        return (-dy / length) * outwardSign to (dx / length) * outwardSign
    }

    private fun transformPoint(
        x: Double,
        y: Double,
        offset: Pair<Double, Double>,
        bold: Boolean,
        italic: Boolean,
    ): Pair<Double, Double> {
        val sheared = if (italic) x + ITALIC_SHEAR_TANGENT * y else x
        return if (bold) (sheared + offset.first) to (y + offset.second) else sheared to y
    }

    private fun transformControl(
        x: Double,
        y: Double,
        startOffset: Pair<Double, Double>,
        endOffset: Pair<Double, Double>,
        bold: Boolean,
        italic: Boolean,
    ): Pair<Double, Double> {
        val sheared = if (italic) x + ITALIC_SHEAR_TANGENT * y else x
        if (!bold) return sheared to y
        val offsetX = (startOffset.first + endOffset.first) / 2.0
        val offsetY = (startOffset.second + endOffset.second) / 2.0
        return (sheared + offsetX) to (y + offsetY)
    }

    private fun boundsOf(points: List<Pair<Double, Double>>, whenEmpty: DesignBounds): DesignBounds? {
        if (points.isEmpty()) return whenEmpty
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for ((x, y) in points) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        val left = floor(minX).toDesignInt() ?: return null
        val bottom = floor(minY).toDesignInt() ?: return null
        val right = ceil(maxX).toDesignInt() ?: return null
        val top = ceil(maxY).toDesignInt() ?: return null
        return DesignBounds(left, bottom, right, top)
    }

    private fun Double.toDesignInt(): Int? =
        if (this < Int.MIN_VALUE.toDouble() || this > Int.MAX_VALUE.toDouble()) null else toInt()
}
