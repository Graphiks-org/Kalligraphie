package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand

/**
 * Internal signal that a declared raster bound was reached.
 *
 * The facade converts this signal into a typed [RasterDiagnostic.LimitExceeded];
 * it never crosses the public API.
 */
internal class RasterLimitReached(
    val field: String,
    val observed: Long,
    val limit: Long,
) : RuntimeException("$field limit reached: observed $observed, limit $limit")

/**
 * Internal signal that a request produced geometry outside the representable
 * integer domain.
 *
 * The facade converts this signal into a typed [RasterDiagnostic.InvalidRequest];
 * it never crosses the public API.
 */
internal class RasterRequestRejected(
    val field: String,
    val detail: String,
) : RuntimeException("$field rejected: $detail")

/**
 * One point of a flattened pixel-space contour.
 *
 * Points are compared by value, so consecutive duplicates are never emitted.
 */
internal data class FlatPoint(val x: Double, val y: Double)

/**
 * One closed flattened contour in pixel space.
 *
 * The first point is not repeated at Close: the last point implicitly connects
 * back to the first. Contours with fewer than two points are discarded.
 */
internal class FlatContour(val points: List<FlatPoint>)

/**
 * Converts portable outline and paint commands into closed pixel-space polylines.
 *
 * Curves are subdivided with a fixed De Casteljau recursion until every control
 * point lies within [TolerancePx] of its chord, measured with squared distances.
 * The result depends only on the input values: no locale, rounding mode, or
 * platform function participates, so identical inputs produce identical points
 * on every platform.
 */
internal object ContourFlattener {
    /** Maximum distance in pixels between a curve and its flattened polyline. */
    const val TolerancePx: Double = 0.25

    /** Maximum subdivision depth applied to one curve segment. */
    const val MaxSubdivisionDepth: Int = 16

    /** Squared form of [TolerancePx]; used for squared-distance flatness tests. */
    private const val ToleranceSquared: Double = TolerancePx * TolerancePx

    /**
     * Flattens validated outline contours into closed pixel-space polylines.
     *
     * Contours are implicitly closed: the first point is not repeated at Close
     * and the last point connects back to the first. Contours with fewer than
     * two points are discarded, and consecutive duplicate points are skipped,
     * including curve endpoints that coincide with the current point. Both
     * [RasterLimits.maxContours] and [RasterLimits.maxTotalPoints] are enforced;
     * a refusal throws [RasterLimitReached] and every emitted point consumes
     * exactly one budget unit.
     */
    fun flattenOutline(
        contours: List<GlyphContour>,
        scale: Double,
        originX: Double,
        originY: Double,
        limits: RasterLimits,
        transform: GlyphAffineTransform = GlyphAffineTransform.IDENTITY,
    ): List<FlatContour> {
        if (contours.size > limits.maxContours) {
            throw RasterLimitReached("maxContours", contours.size.toLong(), limits.maxContours.toLong())
        }
        val budget = PointBudget(limits)
        val result = ArrayList<FlatContour>(contours.size)
        for (contour in contours) {
            val edges = contour.commands.map { it.toEdge(scale, originX, originY, transform) }
            result += flattenEdges(edges, budget)
        }
        return result
    }

    /**
     * Flattens a portable paint path into closed pixel-space polylines.
     *
     * Commands must come from a validated `GlyphPaintPath`; this function does
     * not re-validate path structure. Closure, discarded degenerate contours,
     * duplicate-point skipping, budget accounting, and the refusal shape of
     * [RasterLimitReached] are identical to [flattenOutline].
     */
    fun flattenPath(
        commands: List<GlyphPaintPathCommand>,
        scale: Double,
        originX: Double,
        originY: Double,
        limits: RasterLimits,
        transform: GlyphAffineTransform = GlyphAffineTransform.IDENTITY,
    ): List<FlatContour> {
        val contourCount = commands.count { command -> command is GlyphPaintPathCommand.MoveTo }
        if (contourCount > limits.maxContours) {
            throw RasterLimitReached("maxContours", contourCount.toLong(), limits.maxContours.toLong())
        }
        val budget = PointBudget(limits)
        return flattenEdges(commands.map { it.toEdge(scale, originX, originY, transform) }, budget)
    }

    /** Maps a local design point through [transform], then the request scale and origin. */
    private fun deviceX(x: Double, y: Double, transform: GlyphAffineTransform, scale: Double, originX: Double): Double =
        requireFinite((transform.xx * x + transform.xy * y + transform.dx) * scale + originX)

    /** Maps a local design point through [transform], then the request scale and origin. */
    private fun deviceY(x: Double, y: Double, transform: GlyphAffineTransform, scale: Double, originY: Double): Double =
        requireFinite((transform.yx * x + transform.yy * y + transform.dy) * scale + originY)

    /** Rejects a flattened coordinate that left the finite domain, before bounds ever see it. */
    private fun requireFinite(value: Double): Double {
        if (!value.isFinite()) {
            throw RasterRequestRejected("transform", "a flattened coordinate is not finite.")
        }
        return value
    }

    private sealed interface Edge {
        class Move(val x: Double, val y: Double) : Edge
        class Line(val x: Double, val y: Double) : Edge
        class Quad(val controlX: Double, val controlY: Double, val x: Double, val y: Double) : Edge
        class Cubic(
            val control1X: Double,
            val control1Y: Double,
            val control2X: Double,
            val control2Y: Double,
            val x: Double,
            val y: Double,
        ) : Edge

        data object Close : Edge
    }

    private fun GlyphOutlineCommand.toEdge(
        scale: Double,
        originX: Double,
        originY: Double,
        transform: GlyphAffineTransform,
    ): Edge =
        when (this) {
            is GlyphOutlineCommand.MoveTo -> Edge.Move(
                deviceX(x, y, transform, scale, originX),
                deviceY(x, y, transform, scale, originY),
            )
            is GlyphOutlineCommand.LineTo -> Edge.Line(
                deviceX(x, y, transform, scale, originX),
                deviceY(x, y, transform, scale, originY),
            )
            is GlyphOutlineCommand.QuadraticTo -> Edge.Quad(
                deviceX(controlX, controlY, transform, scale, originX),
                deviceY(controlX, controlY, transform, scale, originY),
                deviceX(endX, endY, transform, scale, originX),
                deviceY(endX, endY, transform, scale, originY),
            )
            is GlyphOutlineCommand.CubicTo -> Edge.Cubic(
                deviceX(control1X, control1Y, transform, scale, originX),
                deviceY(control1X, control1Y, transform, scale, originY),
                deviceX(control2X, control2Y, transform, scale, originX),
                deviceY(control2X, control2Y, transform, scale, originY),
                deviceX(endX, endY, transform, scale, originX),
                deviceY(endX, endY, transform, scale, originY),
            )
            is GlyphOutlineCommand.Close -> Edge.Close
        }

    private fun GlyphPaintPathCommand.toEdge(
        scale: Double,
        originX: Double,
        originY: Double,
        transform: GlyphAffineTransform,
    ): Edge =
        when (this) {
            is GlyphPaintPathCommand.MoveTo -> Edge.Move(
                deviceX(x, y, transform, scale, originX),
                deviceY(x, y, transform, scale, originY),
            )
            is GlyphPaintPathCommand.LineTo -> Edge.Line(
                deviceX(x, y, transform, scale, originX),
                deviceY(x, y, transform, scale, originY),
            )
            is GlyphPaintPathCommand.CubicTo -> Edge.Cubic(
                deviceX(control1X, control1Y, transform, scale, originX),
                deviceY(control1X, control1Y, transform, scale, originY),
                deviceX(control2X, control2Y, transform, scale, originX),
                deviceY(control2X, control2Y, transform, scale, originY),
                deviceX(endX, endY, transform, scale, originX),
                deviceY(endX, endY, transform, scale, originY),
            )
            GlyphPaintPathCommand.Close -> Edge.Close
        }

    private fun flattenEdges(edges: List<Edge>, budget: PointBudget): List<FlatContour> {
        val result = ArrayList<FlatContour>()
        var current: ArrayList<FlatPoint>? = null
        var currentPoint: FlatPoint? = null

        fun finish() {
            val points = current ?: return
            if (points.size >= 2) result += FlatContour(points.toList())
            current = null
            currentPoint = null
        }

        for (edge in edges) {
            when (edge) {
                is Edge.Move -> {
                    finish()
                    val start = FlatPoint(edge.x, edge.y)
                    current = arrayListOf(start)
                    currentPoint = start
                    budget.consume()
                }

                is Edge.Line -> {
                    val list = current ?: continue
                    val target = FlatPoint(edge.x, edge.y)
                    if (target != currentPoint) {
                        list += target
                        currentPoint = target
                        budget.consume()
                    }
                }

                is Edge.Quad -> {
                    val list = current ?: continue
                    val from = currentPoint ?: continue
                    flattenQuadratic(from, FlatPoint(edge.controlX, edge.controlY), FlatPoint(edge.x, edge.y), list, budget, 0)
                    currentPoint = FlatPoint(edge.x, edge.y)
                }

                is Edge.Cubic -> {
                    val list = current ?: continue
                    val from = currentPoint ?: continue
                    flattenCubic(
                        from,
                        FlatPoint(edge.control1X, edge.control1Y),
                        FlatPoint(edge.control2X, edge.control2Y),
                        FlatPoint(edge.x, edge.y),
                        list,
                        budget,
                        0,
                    )
                    currentPoint = FlatPoint(edge.x, edge.y)
                }

                Edge.Close -> finish()
            }
        }
        finish()
        return result
    }

    private fun flattenQuadratic(
        from: FlatPoint,
        control: FlatPoint,
        to: FlatPoint,
        out: MutableList<FlatPoint>,
        budget: PointBudget,
        depth: Int,
    ) {
        if (depth >= MaxSubdivisionDepth || distanceSquaredToSegment(control, from, to) <= ToleranceSquared) {
            if (to != out.last()) {
                budget.consume()
                out += to
            }
            return
        }
        val first = midpoint(from, control)
        val second = midpoint(control, to)
        val middle = midpoint(first, second)
        flattenQuadratic(from, first, middle, out, budget, depth + 1)
        flattenQuadratic(middle, second, to, out, budget, depth + 1)
    }

    private fun flattenCubic(
        from: FlatPoint,
        control1: FlatPoint,
        control2: FlatPoint,
        to: FlatPoint,
        out: MutableList<FlatPoint>,
        budget: PointBudget,
        depth: Int,
    ) {
        if (depth >= MaxSubdivisionDepth ||
            (distanceSquaredToSegment(control1, from, to) <= ToleranceSquared &&
                distanceSquaredToSegment(control2, from, to) <= ToleranceSquared)
        ) {
            if (to != out.last()) {
                budget.consume()
                out += to
            }
            return
        }
        val first = midpoint(from, control1)
        val second = midpoint(control1, control2)
        val third = midpoint(control2, to)
        val fourth = midpoint(first, second)
        val fifth = midpoint(second, third)
        val middle = midpoint(fourth, fifth)
        flattenCubic(from, first, fourth, middle, out, budget, depth + 1)
        flattenCubic(middle, fifth, third, to, out, budget, depth + 1)
    }

    private fun midpoint(a: FlatPoint, b: FlatPoint): FlatPoint =
        FlatPoint((a.x + b.x) * 0.5, (a.y + b.y) * 0.5)

    private fun distanceSquaredToSegment(point: FlatPoint, a: FlatPoint, b: FlatPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) {
            val px = point.x - a.x
            val py = point.y - a.y
            return px * px + py * py
        }
        val raw = ((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared
        val t = if (raw < 0.0) 0.0 else if (raw > 1.0) 1.0 else raw
        val closestX = a.x + t * dx - point.x
        val closestY = a.y + t * dy - point.y
        return closestX * closestX + closestY * closestY
    }

    private class PointBudget(private val limits: RasterLimits) {
        private var consumed = 0L

        fun consume() {
            consumed += 1
            if (consumed > limits.maxTotalPoints) {
                throw RasterLimitReached("maxTotalPoints", consumed, limits.maxTotalPoints.toLong())
            }
        }
    }
}
