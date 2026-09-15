package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode

/**
 * Composites a portable paint graph into one non-premultiplied RGBA image.
 *
 * Solid outline nodes use their own `unitsPerEm`; portable path nodes use the
 * request-level `unitsPerEm`. Children of a group are painted in index order
 * with `SOURCE_OVER` integer arithmetic, so identical graphs produce identical
 * pixels on every platform.
 *
 * Each visited node materializes one layer before its group composites, and the
 * layers stay live until the group finishes, so peak memory scales with the live
 * stack: roughly `maxPaintNodes × layer bytes`. There is no aggregate byte
 * budget, so callers processing untrusted graphs must choose
 * [RasterLimits.maxPaintNodes] and [RasterLimits.maxPixelsPerImage] together.
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
        val context = Context(paint, pixelsPerEm, unitsPerEm, originX, originY, limits)
        val root = context.build(paint.rootNode, depth = 0)
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

        fun build(nodeIndex: Int, depth: Int): Layer? {
            visitedNodes += 1
            if (visitedNodes > limits.maxPaintNodes) {
                throw RasterLimitReached("maxPaintNodes", visitedNodes.toLong(), limits.maxPaintNodes.toLong())
            }
            if (depth > limits.maxPaintDepth) {
                throw RasterLimitReached("maxPaintDepth", depth.toLong(), limits.maxPaintDepth.toLong())
            }
            return when (val node = paint.nodes[nodeIndex]) {
                is GlyphPaintNode.SolidOutline -> tinted(coverageOfOutline(node), node.color)

                is GlyphPaintNode.Path -> tinted(coverageOfPath(node), node.color)

                is GlyphPaintNode.Group -> {
                    require(node.compositionMode == GlyphPaintCompositionMode.SOURCE_OVER) {
                        "Unsupported paint composition mode."
                    }
                    composite(node.children.mapNotNull { child -> build(child, depth + 1) })
                }
            }
        }

        private fun coverageOfOutline(node: GlyphPaintNode.SolidOutline): A8Image? {
            val contours = ContourFlattener.flattenOutline(
                contours = node.outline.contours,
                scale = pixelsPerEm / node.outline.unitsPerEm,
                originX = originX.toDouble(),
                originY = originY.toDouble(),
                limits = limits,
            )
            val bounds = boundsOf(contours, limits) ?: return null
            checkCanvas(bounds.width, bounds.height)
            return CoverageRaster.rasterize(contours, bounds.left, bounds.top, bounds.width, bounds.height)
        }

        private fun coverageOfPath(node: GlyphPaintNode.Path): A8Image? {
            val contours = ContourFlattener.flattenPath(
                commands = node.path.commands,
                scale = pixelsPerEm / unitsPerEm,
                originX = originX.toDouble(),
                originY = originY.toDouble(),
                limits = limits,
            )
            val bounds = boundsOf(contours, limits) ?: return null
            checkCanvas(bounds.width, bounds.height)
            return CoverageRaster.rasterize(contours, bounds.left, bounds.top, bounds.width, bounds.height)
        }

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
}
