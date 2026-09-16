package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One glyph outline placed at a pen position, in the font's own design units.
 *
 * [outline] is already translated to [x]/[y]; those fields record the position
 * for reference only. A consumer applies at most one further uniform shift to
 * the whole word and must never re-apply the pen position.
 */
internal class PlacedGlyph(
    val glyphId: Int,
    val x: Double,
    val y: Double,
    val outline: GlyphOutlineIR,
)

/**
 * Returns the same outline shifted by [dx] and [dy] design units.
 *
 * The CPU rasterizer refuses transform nodes, so placement happens on the
 * geometry itself. The conservative integer envelope is recomputed with the
 * same floor/ceil rule the outline contract uses.
 * The result is rebuilt from the legacy flattened command view, so `components`
 * is empty, `limits` are the compatibility limits, and `pointCount` is
 * recomputed.
 */
internal fun GlyphOutlineIR.translated(dx: Double, dy: Double): GlyphOutlineIR =
    GlyphOutlineIR(
        glyphId = glyphId,
        unitsPerEm = unitsPerEm,
        bounds = DesignBounds(
            minX = floor(bounds.minX + dx).toInt(),
            minY = floor(bounds.minY + dy).toInt(),
            maxX = ceil(bounds.maxX + dx).toInt(),
            maxY = ceil(bounds.maxY + dy).toInt(),
        ),
        commands = commands.map { command ->
            when (command) {
                is GlyphOutlineIR.Command.MoveTo ->
                    GlyphOutlineIR.Command.MoveTo(command.x + dx, command.y + dy)

                is GlyphOutlineIR.Command.LineTo ->
                    GlyphOutlineIR.Command.LineTo(command.x + dx, command.y + dy)

                is GlyphOutlineIR.Command.QuadraticTo ->
                    GlyphOutlineIR.Command.QuadraticTo(
                        command.controlX + dx,
                        command.controlY + dy,
                        command.endX + dx,
                        command.endY + dy,
                    )

                GlyphOutlineIR.Command.Close -> GlyphOutlineIR.Command.Close
            }
        },
        fillRule = fillRule,
    )

/** Returns the union of the placed outlines' conservative envelopes. */
internal fun inkBoundsOf(glyphs: List<PlacedGlyph>): DesignBounds {
    require(glyphs.isNotEmpty()) { "ink bounds require at least one placed glyph." }
    return DesignBounds(
        minX = glyphs.minOf { glyph -> glyph.outline.bounds.minX },
        minY = glyphs.minOf { glyph -> glyph.outline.bounds.minY },
        maxX = glyphs.maxOf { glyph -> glyph.outline.bounds.maxX },
        maxY = glyphs.maxOf { glyph -> glyph.outline.bounds.maxY },
    )
}
