@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.glyph

import kotlin.math.abs
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
 * Bold offsets every contour relative to the **filled region**, not the individual contour: a single
 * outward handedness is derived for the whole glyph from the signed area of the contour with the
 * largest absolute area (the outer boundary) and applied to every contour. An outer contour grows, an
 * opposite-wound hole shrinks, and a same-wound nested contour grows. See [anchorOffsets] for why a
 * per-contour sign would be wrong. Curve control points are offset by the mean of their segment-end
 * offsets, which either pushes or flips a sharp curve; see [transformControl].
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

    /**
     * Miter-limit floor expressed as the cosine of the half-angle between the two edge normals.
     *
     * The miter extension is `1 / cos(half-angle)`; clamping the divisor to `0.5` (a 60-degree
     * half-angle) caps the extension at `2 * delta`, so a near-cusp vertex cannot shoot an
     * arbitrarily long spike. This is the standard miter-limit behaviour with a limit ratio of 2.
     */
    private const val MIN_MITER_COS: Double = 0.5

    /**
     * Numerical guard below which an edge or a summed miter direction is treated as degenerate.
     *
     * `hypot` lengths and normalised sums at or below this magnitude are discarded: a zero-length
     * edge contributes no normal, and two opposed normals (a straight vertex) fall back to the
     * incoming normal instead of a near-zero, numerically unstable bisector.
     */
    private const val EDGE_EPSILON: Double = 1e-9

    /** Failure message for a contour that contains an interior `MoveTo` (multiple subpaths). */
    private const val MULTI_SUBPATH_MESSAGE = "Synthetic geometry requires exactly one MoveTo per contour."

    /** Failure message for a transformed coordinate that is not finite. */
    private const val NON_FINITE_MESSAGE = "Synthetic geometry produced a non-finite coordinate."

    /** Failure message for a transformed coordinate envelope outside the `Int` design range. */
    private const val OVERFLOW_MESSAGE = "Synthetic geometry exceeds the design-coordinate range."

    /**
     * Applies the requested synthetic styles to [outline].
     *
     * Returns [outline] unchanged (same instance) when no style is requested or the outline has no
     * contours. Every contour/point/command is preserved; only coordinates and [DesignBounds] change,
     * so the transform cannot introduce a contour, point, byte or component limit breach. Bold derives
     * one outward handedness for the whole glyph from the contour with the largest absolute area and
     * applies it to every contour, so an outer contour grows and an opposite-wound hole shrinks (see
     * [anchorOffsets]); the returned bounds are the `floor`/`ceil` envelope of all transformed
     * coordinates, including control points.
     *
     * This method returns a typed failure instead of throwing: a contour with more than one `MoveTo`
     * (a multi-subpath contour that this single-subpath offset does not support) and any transformed
     * coordinate that is non-finite or outside the `Int` design range all return
     * `font.geometry-overflow`. Cancellation is observed between contours and returns
     * [FontOperationResult.Cancelled]. Rejecting a multi-`MoveTo` contour is a deliberate behaviour
     * change: an earlier revision silently offset such a contour at its first subpath only.
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
        val outwardSign = outwardSignOf(outline.contours)
        val transformed = ArrayList<GlyphContour>(outline.contours.size)
        val points = ArrayList<Pair<Double, Double>>()
        for (contour in outline.contours) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            if (contour.moveToCount() > 1) return geometryOverflow(outline, MULTI_SUBPATH_MESSAGE)
            val anchors = contour.anchors()
            if (anchors.isEmpty()) return FontOperationResult.Success(outline)
            val shearedAnchors = if (italic) anchors.map { (x, y) -> (x + ITALIC_SHEAR_TANGENT * y) to y } else anchors
            val offsets = if (bold) {
                anchorOffsets(shearedAnchors, delta, outwardSign)
            } else {
                List(anchors.size) { 0.0 to 0.0 }
            }
            val commands = ArrayList<GlyphOutlineCommand>(contour.commands.size)
            var startOffset = offsets.first()
            var anchorIndex = 0
            for (command in contour.commands) {
                when (command) {
                    is GlyphOutlineCommand.MoveTo -> {
                        val point = transformPoint(command.x, command.y, offsets[0], bold, italic)
                        if (!point.isFiniteCoordinate()) return geometryOverflow(outline, NON_FINITE_MESSAGE)
                        commands += GlyphOutlineCommand.MoveTo(point.first, point.second)
                        points += point
                        startOffset = offsets[0]
                    }

                    is GlyphOutlineCommand.LineTo -> {
                        anchorIndex += 1
                        val point = transformPoint(command.x, command.y, offsets[anchorIndex], bold, italic)
                        if (!point.isFiniteCoordinate()) return geometryOverflow(outline, NON_FINITE_MESSAGE)
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
                        if (!control.isFiniteCoordinate() || !end.isFiniteCoordinate()) {
                            return geometryOverflow(outline, NON_FINITE_MESSAGE)
                        }
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
                        if (!first.isFiniteCoordinate() || !second.isFiniteCoordinate() || !end.isFiniteCoordinate()) {
                            return geometryOverflow(outline, NON_FINITE_MESSAGE)
                        }
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
            ?: return geometryOverflow(outline, OVERFLOW_MESSAGE)
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

    private fun GlyphContour.moveToCount(): Int = commands.count { it is GlyphOutlineCommand.MoveTo }

    /**
     * Outward handedness for the whole glyph, shared by every contour.
     *
     * The contour with the largest absolute shoelace area is the outer boundary; its signed area
     * fixes the glyph's orientation. A negative signed area means a clockwise outer boundary, so the
     * outward normal is rotated one way ([outwardSign] `1.0`), and a non-negative area means a
     * counter-clockwise outer boundary ([outwardSign] `-1.0`). Returning a single sign is what makes
     * [anchorOffsets] treat every contour relative to the filled region. When every contour is
     * degenerate (all areas zero) the default `-1.0` is harmless because no meaningful normal exists.
     *
     * This rule assumes the largest absolute area belongs to the true outer boundary. For a malformed
     * glyph — self-intersecting or otherwise invalid input whose largest absolute area belongs to a
     * contour that is not the outer boundary — the single derived sign is inverted, so every contour
     * shrinks instead of growing. The transform neither validates winding nor rejects such input: one
     * sign is fixed for the whole glyph, so a wrong outer boundary cannot be recovered from.
     */
    private fun outwardSignOf(contours: List<GlyphContour>): Double {
        var largestAbsoluteArea = -1.0
        var sign = -1.0
        for (contour in contours) {
            val anchors = contour.anchors()
            if (anchors.size < 3) continue
            val twiceArea = twiceSignedArea(anchors)
            val absoluteArea = abs(twiceArea)
            if (absoluteArea > largestAbsoluteArea) {
                largestAbsoluteArea = absoluteArea
                sign = if (twiceArea < 0.0) 1.0 else -1.0
            }
        }
        return sign
    }

    private fun twiceSignedArea(anchors: List<Pair<Double, Double>>): Double {
        var twiceArea = 0.0
        for (index in anchors.indices) {
            val (x1, y1) = anchors[index]
            val (x2, y2) = anchors[(index + 1) % anchors.size]
            twiceArea += x1 * y2 - x2 * y1
        }
        return twiceArea
    }

    /**
     * Outward miter offset per anchor, using the glyph-wide [outwardSign].
     *
     * [outwardSign] is derived once per glyph by [outwardSignOf] from the signed area of the contour
     * with the largest absolute area — the outer boundary — and applied to every contour. This makes
     * the offset relative to the **filled** region rather than to each contour's own interior: with
     * one sign an outer contour moves away from its interior and grows, an opposite-wound hole moves
     * toward its own interior (away from the filled region) and shrinks, and a same-wound nested
     * contour grows. A per-contour sign instead cancels orientation: reversing a hole flips both its
     * normals and its area sign, so the hole would grow. The rule holds for both conventions because
     * non-zero filling fixes only the relative winding; the largest contour supplies the sense of
     * "outward" without assuming TrueType's clockwise or CFF's counter-clockwise outer direction.
     *
     * The miter factor `1 / max(dot, [MIN_MITER_COS])` clamps a sharp vertex so its offset cannot
     * grow without bound.
     */
    private fun anchorOffsets(
        anchors: List<Pair<Double, Double>>,
        delta: Double,
        outwardSign: Double,
    ): List<Pair<Double, Double>> {
        val count = anchors.size
        if (count < 2 || delta == 0.0) return List(count) { 0.0 to 0.0 }
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

    private fun Pair<Double, Double>.isFiniteCoordinate(): Boolean = first.isFinite() && second.isFinite()

    private fun geometryOverflow(outline: ScalerGlyphOutline, message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(
            FontError.GeometryOverflow(message, FontDiagnosticLocation.Glyph(outline.glyphId)),
        )

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

    /**
     * Offsets a Bézier control point by the mean of its segment-end offsets.
     *
     * Offsetting a control point by the average of the two endpoint offset vectors is an approximation
     * of a true curve offset; it keeps the curve close to the offset outline for gentle turns but can
     * push or invert the curve where consecutive segments meet at a sharp angle or cusp. The exact
     * offset of a quadratic or cubic is not attempted here.
     */
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
