package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.*
import org.graphiks.kalligraphie.layout.openLayoutHandle
import kotlin.test.*

class ColrV1LayoutHandoffTest {
    @Test
    fun retainedRendererAssetsResolveAllCertifiedFeaturesAfterEveryOriginalOwnerCloses() {
        ColrV1Fixture().use { fixture ->
            val line = assertIs<EditableLineResult.Success>(fixture.layout(colrSnapshot(0xF0100, 0xF0300, 0xF0A03, 0xF0E00))).line
            val certificates = line.positionedGlyphRuns.flatMap { it.glyphs }.map { assertNotNull(it.materializationCertificate) }
            assertEquals(listOf(8, 84, 123, 168), certificates.map { it.glyphId.value })
            certificates.forEach { assertEquals(GlyphMaterializationRoute.PAINT_GRAPH, it.route) }
            val handle = colrSuccess(line.openLayoutHandle(fixture.resolver))
            val assets = certificates.groupBy { it.assetKey }.mapValues { colrSuccess(handle.retainFontAsset(it.value.first())) }
            try {
                colrSuccess(fixture.session.close())
                colrSuccess(fixture.resolver.close())
                colrSuccess(handle.close())
                val paints = certificates.map { assertIs<GlyphRepresentation.Paint>(colrSuccess(
                    assets.getValue(it.assetKey).resolveGlyph(FontGlyphRequest(it.glyphId)),
                )).paint }
                assertEquals(DesignBounds(100, 250, 900, 950), paints[0].clipBounds)
                assertEquals(listOf(GlyphColor(255, 0, 0), GlyphColor(0, 0, 255)), paints[0].nodes.filterIsInstance<GlyphPaintNode.LinearGradient>().single().colorLine.colorStops.map { it.color })
                assertEquals(GlyphAffineTransform(0.5, 0.0, 0.0, 1.5, 250.0, -250.0), paints[1].nodes.filterIsInstance<GlyphPaintNode.Transform>().single().matrix)
                assertEquals(GlyphPaintCompositionMode.SOURCE_OVER, paints[2].nodes.filterIsInstance<GlyphPaintNode.Composite>().single().mode)
                assertLegacyCircles(paints[3])
            } finally { assets.values.forEach { colrSuccess(it.close()) }; handle.close() }
        }
    }

    @Test
    fun paletteAndForegroundChangeLiteralPaintColorsButPreserveShapingAndEditingGeometry() {
        val snapshot = colrSnapshot(0xF0100, 0xF0300, 0xF0A03, 0xF0E00, 0xF0B00)
        val variants = listOf(FontRenderVariantSnapshot.default, FontRenderVariantSnapshot(cpalPaletteIndex = 1),
            FontRenderVariantSnapshot(foregroundColor = GlyphColor(17, 34, 51, 192)))
        val geometries = variants.mapIndexed { index, variant ->
            ColrV1Fixture(variant = variant).use { fixture ->
                val line = assertIs<EditableLineResult.Success>(fixture.layout(snapshot)).line
                val glyphs = line.positionedGlyphRuns.flatMap { it.glyphs }
                glyphs.forEach { assertEquals(GlyphMaterializationRoute.PAINT_GRAPH, assertNotNull(it.materializationCertificate).route) }
                assertEquals(listOf(8, 84, 123, 168, 148), glyphs.map { assertNotNull(it.materializationCertificate).glyphId.value })
                val linear = fixture.paint(0xF0100, 8).nodes.filterIsInstance<GlyphPaintNode.LinearGradient>().single()
                assertEquals(if (index == 1) listOf(GlyphColor(42, 41, 74), GlyphColor(14, 154, 194))
                    else listOf(GlyphColor(255, 0, 0), GlyphColor(0, 0, 255)), linear.colorLine.colorStops.map { it.color })
                val foreground = fixture.paint(0xF0B00, 148).nodes.filterIsInstance<GlyphPaintNode.LinearGradient>().single()
                assertEquals(if (index == 2) GlyphColor(17, 34, 51, 192) else GlyphColor(0, 0, 0), foreground.colorLine.colorStops[1].color)
                val lineBounds = line.selectionGeometry(line.allCaretCandidates.first().position, line.allCaretCandidates.last().position)
                assertEquals(listOf(LayoutRect(LayoutUnit(0f), LayoutUnit(-1000f), LayoutUnit(5000f), LayoutUnit(250f))), lineBounds)
                listOf(line.range, line.verticalMetrics, lineBounds,
                    glyphs.map { listOf(it.advance, it.origin, it.sourceClusters.map { cluster -> cluster.sourceRange }) },
                    line.allCaretCandidates.map { it.position to it.geometry },
                    listOf(0f, 500f, 1500f, 2500f, 4500f, 5000f).map { x ->
                        line.hitTest(LayoutPoint(LayoutUnit(x), LayoutUnit(0f))).let { it.position to it.geometry }
                    })
            }
        }
        assertEquals(geometries[0], geometries[1])
        assertEquals(geometries[0], geometries[2])
    }
}
