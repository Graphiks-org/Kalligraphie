package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode

/**
 * Composites a portable paint graph into one non-premultiplied RGBA image.
 *
 * Solid outline and portable path nodes rasterize their own geometry; a `GlyphClip` restricts
 * the child paint to its outline, and a `Transform` composes its matrix into the child's
 * coordinates. Clipping is an exact per-sample intersection at the rasterizer's sixteen fixed
 * sub-pixel positions, so a shared edge is never squared and nested clips intersect correctly.
 * Children of a group are painted in index order with `SOURCE_OVER` integer arithmetic, so
 * identical graphs produce identical pixels on every platform.
 *
 * Each visited node materializes one layer before its group composites, and the layers stay live
 * until the group finishes, so peak memory scales with the live stack: roughly
 * `maxPaintNodes × layer bytes`. There is no aggregate byte budget, so callers processing
 * untrusted graphs must choose [RasterLimits.maxPaintNodes] and [RasterLimits.maxPixelsPerImage]
 * together.
 */
internal object PaintCompositor {
    fun rasterize(
        paint: GlyphPaintIR,
        pixelsPerEm: Double,
        unitsPerEm: Int,
        originX: Int,
        originY: Int,
        limits: RasterLimits,
    ): Rgba8Image {
        val rootClips = paint.clipBounds?.let { bounds ->
            val scale = pixelsPerEm / unitsPerEm
            listOf(
                listOf(
                    FlatContour(
                        listOf(
                            FlatPoint(bounds.minX * scale + originX, bounds.minY * scale + originY),
                            FlatPoint(bounds.maxX * scale + originX, bounds.minY * scale + originY),
                            FlatPoint(bounds.maxX * scale + originX, bounds.maxY * scale + originY),
                            FlatPoint(bounds.minX * scale + originX, bounds.maxY * scale + originY),
                        ),
                    ),
                ),
            )
        } ?: emptyList()
        val context = Context(paint, pixelsPerEm, unitsPerEm, originX, originY, limits)
        val root = context.build(paint.rootNode, 0, rootClips, GlyphAffineTransform.IDENTITY, unitsPerEm)
        return if (root == null) {
            Rgba8Image(0, 0, 0, 0, ByteArray(0))
        } else {
            Rgba8Image(root.width, root.height, root.left, root.top, root.pixels)
        }
    }

    private class Layer(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val pixels: ByteArray,
    )

    private class Context(
        private val paint: GlyphPaintIR,
        private val pixelsPerEm: Double,
        private val unitsPerEm: Int,
        private val originX: Int,
        private val originY: Int,
        private val limits: RasterLimits,
    ) {
        private var visitedNodes = 0

        fun build(
            nodeIndex: Int,
            depth: Int,
            clips: List<List<FlatContour>>,
            transform: GlyphAffineTransform,
            unboundedUnitsPerEm: Int,
        ): Layer? {
            visitedNodes += 1
            if (visitedNodes > limits.maxPaintNodes) {
                throw RasterLimitReached("maxPaintNodes", visitedNodes.toLong(), limits.maxPaintNodes.toLong())
            }
            if (depth > limits.maxPaintDepth) {
                throw RasterLimitReached("maxPaintDepth", depth.toLong(), limits.maxPaintDepth.toLong())
            }
            return when (val node = paint.nodes[nodeIndex]) {
                is GlyphPaintNode.SolidOutline -> buildOutline(node, clips, transform)

                is GlyphPaintNode.Path -> buildPath(node, clips, transform)

                is GlyphPaintNode.GlyphClip -> {
                    val flattened = flattenOutline(node.outline.contours, node.outline.unitsPerEm, transform)
                    if (flattened.isEmpty()) {
                        null
                    } else {
                        build(node.paint, depth + 1, clips + listOf(flattened), transform, node.outline.unitsPerEm)
                    }
                }

                is GlyphPaintNode.Transform -> {
                    val composed = compose(transform, node.matrix)
                    requireFinite(composed)
                    build(node.paint, depth + 1, clips, composed, unboundedUnitsPerEm)
                }

                is GlyphPaintNode.Group -> {
                    if (clips.isNotEmpty()) {
                        throw RasterRequestRejected("nodeKind", "a clip around a composite is not supported.")
                    }
                    if (node.compositionMode != GlyphPaintCompositionMode.SOURCE_OVER) {
                        throw RasterRequestRejected("compositionMode", "unsupported paint composition mode.")
                    }
                    composite(node.children.mapNotNull { child -> build(child, depth + 1, clips, transform, unboundedUnitsPerEm) })
                }

                is GlyphPaintNode.Solid -> throw RasterRequestRejected(
                    "nodeKind",
                    "unsupported paint node kind Solid.",
                )

                is GlyphPaintNode.LinearGradient -> throw RasterRequestRejected(
                    "nodeKind",
                    "unsupported paint node kind LinearGradient.",
                )

                else -> throw RasterRequestRejected(
                    "nodeKind",
                    "unsupported paint node kind ${node::class.simpleName}.",
                )
            }
        }

        private fun buildOutline(
            node: GlyphPaintNode.SolidOutline,
            clips: List<List<FlatContour>>,
            transform: GlyphAffineTransform,
        ): Layer? {
            val contours = flattenOutline(node.outline.contours, node.outline.unitsPerEm, transform)
            val bounds = boundsOf(contours, limits) ?: return null
            checkCanvas(bounds.width, bounds.height)
            val coverage = CoverageRaster.rasterizeLeaf(contours, clips, bounds.left, bounds.top, bounds.width, bounds.height)
            return tinted(coverage, node.color)
        }

        private fun buildPath(
            node: GlyphPaintNode.Path,
            clips: List<List<FlatContour>>,
            transform: GlyphAffineTransform,
        ): Layer? {
            val contours = flattenPath(node.path.commands, unitsPerEm, transform)
            val bounds = boundsOf(contours, limits) ?: return null
            checkCanvas(bounds.width, bounds.height)
            val coverage = CoverageRaster.rasterizeLeaf(contours, clips, bounds.left, bounds.top, bounds.width, bounds.height)
            return tinted(coverage, node.color)
        }

        private fun flattenOutline(
            contours: List<org.graphiks.kalligraphie.api.GlyphContour>,
            outlineUnitsPerEm: Int,
            transform: GlyphAffineTransform,
        ): List<FlatContour> = ContourFlattener.flattenOutline(
            contours = contours,
            scale = pixelsPerEm / outlineUnitsPerEm,
            originX = originX.toDouble(),
            originY = originY.toDouble(),
            limits = limits,
            transform = transform,
        )

        private fun flattenPath(
            commands: List<org.graphiks.kalligraphie.api.GlyphPaintPathCommand>,
            pathUnitsPerEm: Int,
            transform: GlyphAffineTransform,
        ): List<FlatContour> = ContourFlattener.flattenPath(
            commands = commands,
            scale = pixelsPerEm / pathUnitsPerEm,
            originX = originX.toDouble(),
            originY = originY.toDouble(),
            limits = limits,
            transform = transform,
        )

        private fun tinted(mask: A8Image?, color: GlyphColor): Layer? {
            if (mask == null || mask.width == 0 || mask.height == 0) return null
            val coverage = mask.copyPixels()
            val pixels = ByteArray(coverage.size * 4)
            for (index in coverage.indices) {
                val alpha = (((coverage[index].toInt() and 0xFF) * color.alpha) + 127) / 255
                val base = index * 4
                pixels[base] = color.red.toByte()
                pixels[base + 1] = color.green.toByte()
                pixels[base + 2] = color.blue.toByte()
                pixels[base + 3] = alpha.toByte()
            }
            return Layer(mask.left, mask.top, mask.width, mask.height, pixels)
        }

        private fun composite(layers: List<Layer>): Layer? {
            if (layers.isEmpty()) return null
            val left = layers.minOf { layer -> layer.left }
            val top = layers.minOf { layer -> layer.top }
            val right = layers.maxOf { layer -> layer.left.toLong() + layer.width.toLong() }
            val bottom = layers.maxOf { layer -> layer.top.toLong() + layer.height.toLong() }
            val width = right - left
            val height = bottom - top
            checkCanvasSize(width, height)
            val canvas = ByteArray(width.toInt() * height.toInt() * 4)
            for (layer in layers) {
                blendInto(canvas, width.toInt(), left, top, layer)
            }
            return Layer(left, top, width.toInt(), height.toInt(), canvas)
        }

        private fun blendInto(canvas: ByteArray, canvasWidth: Int, canvasLeft: Int, canvasTop: Int, layer: Layer) {
            for (y in 0 until layer.height) {
                for (x in 0 until layer.width) {
                    val sourceIndex = (y * layer.width + x) * 4
                    val sourceAlpha = layer.pixels[sourceIndex + 3].toInt() and 0xFF
                    if (sourceAlpha == 0) continue
                    val destinationX = layer.left + x - canvasLeft
                    val destinationY = layer.top + y - canvasTop
                    val destinationIndex = (destinationY * canvasWidth + destinationX) * 4
                    val destinationAlpha = canvas[destinationIndex + 3].toInt() and 0xFF
                    val destinationWeight = (destinationAlpha * (255 - sourceAlpha) + 127) / 255
                    val outputAlpha = sourceAlpha + destinationWeight
                    if (outputAlpha == 0) continue
                    canvas[destinationIndex] = blendChannel(
                        layer.pixels[sourceIndex].toInt() and 0xFF,
                        sourceAlpha,
                        canvas[destinationIndex].toInt() and 0xFF,
                        destinationWeight,
                        outputAlpha,
                    )
                    canvas[destinationIndex + 1] = blendChannel(
                        layer.pixels[sourceIndex + 1].toInt() and 0xFF,
                        sourceAlpha,
                        canvas[destinationIndex + 1].toInt() and 0xFF,
                        destinationWeight,
                        outputAlpha,
                    )
                    canvas[destinationIndex + 2] = blendChannel(
                        layer.pixels[sourceIndex + 2].toInt() and 0xFF,
                        sourceAlpha,
                        canvas[destinationIndex + 2].toInt() and 0xFF,
                        destinationWeight,
                        outputAlpha,
                    )
                    canvas[destinationIndex + 3] = outputAlpha.toByte()
                }
            }
        }

        private fun blendChannel(
            source: Int,
            sourceAlpha: Int,
            destination: Int,
            destinationWeight: Int,
            outputAlpha: Int,
        ): Byte = ((source * sourceAlpha + destination * destinationWeight + outputAlpha / 2) / outputAlpha).toByte()

        private fun checkCanvas(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            checkCanvasSize(width.toLong(), height.toLong())
        }

        private fun checkCanvasSize(width: Long, height: Long) {
            if (width > limits.maxWidthPx.toLong()) {
                throw RasterLimitReached("maxWidthPx", width, limits.maxWidthPx.toLong())
            }
            if (height > limits.maxHeightPx.toLong()) {
                throw RasterLimitReached("maxHeightPx", height, limits.maxHeightPx.toLong())
            }
            val pixels = width * height
            if (pixels > limits.maxPixelsPerImage.toLong()) {
                throw RasterLimitReached("maxPixelsPerImage", pixels, limits.maxPixelsPerImage.toLong())
            }
            if (pixels > Int.MAX_VALUE.toLong() / 4L) {
                throw RasterLimitReached("maxPixelsPerImage", pixels, Int.MAX_VALUE.toLong() / 4L)
            }
        }
    }

    /** Composes [inner] first, then [outer]. */
    private fun compose(outer: GlyphAffineTransform, inner: GlyphAffineTransform): GlyphAffineTransform =
        GlyphAffineTransform(
            xx = outer.xx * inner.xx + outer.xy * inner.yx,
            yx = outer.yx * inner.xx + outer.yy * inner.yx,
            xy = outer.xx * inner.xy + outer.xy * inner.yy,
            yy = outer.yx * inner.xy + outer.yy * inner.yy,
            dx = outer.xx * inner.dx + outer.xy * inner.dy + outer.dx,
            dy = outer.yx * inner.dx + outer.yy * inner.dy + outer.dy,
        )

    /** Rejects a transform whose accumulation left the finite domain. */
    private fun requireFinite(transform: GlyphAffineTransform) {
        val finite = transform.xx.isFinite() && transform.yx.isFinite() && transform.xy.isFinite() &&
            transform.yy.isFinite() && transform.dx.isFinite() && transform.dy.isFinite()
        if (!finite) throw RasterRequestRejected("transform", "the accumulated transform is not finite.")
    }
}
