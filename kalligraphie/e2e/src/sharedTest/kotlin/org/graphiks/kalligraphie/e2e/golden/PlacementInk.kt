package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.raster.A8Image
import org.graphiks.kalligraphie.raster.Rgba8Image

/** Opaque white, the background every composed canvas starts from (`0xRRGGBB`). */
internal const val WHITE_RGB: Int = 0xFFFFFF

/** Ink extent in canvas coordinates, both bounds inclusive. */
internal class CanvasInk(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
) {
    /** Number of columns the ink spans. */
    val width: Int get() = maxX - minX + 1

    /** Number of rows the ink spans. */
    val height: Int get() = maxY - minY + 1
}

/** Returns the smallest box holding every box of [boxes], or `null` when there is none. */
internal fun unionOf(boxes: List<CanvasInk>): CanvasInk? = when (boxes.isEmpty()) {
    true -> null
    false -> CanvasInk(
        minX = boxes.minOf { box -> box.minX },
        minY = boxes.minOf { box -> box.minY },
        maxX = boxes.maxOf { box -> box.maxX },
        maxY = boxes.maxOf { box -> box.maxY },
    )
}

/**
 * Ink extent of the raster of [glyph] once a coverage canvas drew it at [rowOffset] rows below its
 * own baseline.
 *
 * The canvas is in image orientation while the raster is in design orientation, so the raster's
 * first row is the canvas's last one — the same reversal `A8Canvas.drawCoverage` and
 * `RgbaCanvas.drawCoverage` apply while they draw.
 */
internal fun PlacedGlyph.inkInCanvas(rowOffset: Int = 0): CanvasInk? {
    val box = rasterInkOf(image) ?: return null
    val x0 = penX + image.left
    val y0 = baselineY - (image.top + image.height) + rowOffset
    return CanvasInk(
        minX = x0 + box.minColumn,
        maxX = x0 + box.maxColumn,
        minY = y0 + (image.height - 1 - box.maxRow),
        maxY = y0 + (image.height - 1 - box.minRow),
    )
}

/** Ink extent of every raster of [glyphs], unified. */
internal fun List<PlacedGlyph>.inkInCanvas(rowOffset: Int = 0): CanvasInk? =
    unionOf(mapNotNull { glyph -> glyph.inkInCanvas(rowOffset) })

/** Returns the ink extent of [image] in the image's own columns and rows, or `null` when blank. */
internal fun rasterInkOf(image: A8Image): RasterInk? {
    var minColumn = Int.MAX_VALUE
    var maxColumn = -1
    var minRow = Int.MAX_VALUE
    var maxRow = -1
    for (row in 0 until image.height) {
        for (column in 0 until image.width) {
            if (image[column, row] == 0) continue
            if (column < minColumn) minColumn = column
            if (column > maxColumn) maxColumn = column
            if (row < minRow) minRow = row
            if (row > maxRow) maxRow = row
        }
    }
    return if (maxColumn < 0) null else RasterInk(minColumn, maxColumn, minRow, maxRow)
}

/** Returns the ink extent of [image] in the image's own columns and rows, or `null` when blank. */
internal fun rasterInkOf(image: Rgba8Image): RasterInk? {
    var minColumn = Int.MAX_VALUE
    var maxColumn = -1
    var minRow = Int.MAX_VALUE
    var maxRow = -1
    for (row in 0 until image.height) {
        for (column in 0 until image.width) {
            if ((image[column, row] ushr 24) and 0xFF == 0) continue
            if (column < minColumn) minColumn = column
            if (column > maxColumn) maxColumn = column
            if (row < minRow) minRow = row
            if (row > maxRow) maxRow = row
        }
    }
    return if (maxColumn < 0) null else RasterInk(minColumn, maxColumn, minRow, maxRow)
}

/** Ink extent of one raster, in the raster's own columns and rows. */
internal class RasterInk(val minColumn: Int, val maxColumn: Int, val minRow: Int, val maxRow: Int)

/**
 * Ink extent of a colour raster once a canvas drew it at ([x], [y]).
 *
 * [flipped] is `true` for `RgbaCanvas.drawColor`, which draws a design-oriented raster on its
 * baseline the way the coverage canvas does, and `false` for `RgbaCanvas.drawBitmap`, which copies
 * a normalized strike top-left because the strike already arrives in image orientation.
 */
internal fun inkInCanvas(image: Rgba8Image, x: Int, y: Int, flipped: Boolean): CanvasInk? {
    val box = rasterInkOf(image) ?: return null
    return if (flipped) {
        CanvasInk(
            minX = x + image.left + box.minColumn,
            maxX = x + image.left + box.maxColumn,
            minY = y - (image.top + image.height) + (image.height - 1 - box.maxRow),
            maxY = y - (image.top + image.height) + (image.height - 1 - box.minRow),
        )
    } else {
        CanvasInk(
            minX = x + box.minColumn,
            maxX = x + box.maxColumn,
            minY = y + box.minRow,
            maxY = y + box.maxRow,
        )
    }
}
