package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.*
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ColrV1GradientRepresentationTest {
    @Test
    fun resolvesTheAuditedRepeatingLinearGradientAndBothClips() {
        withPaint(0xF0100, 8) { paint ->
            assertEquals(DesignBounds(100, 250, 900, 950), paint.clipBounds)
            val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
            assertEquals(8, clip.outline.glyphId)
            val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[clip.paint])
            assertLinearGeometry(gradient)
            assertEquals(GlyphPaintExtendMode.REPEAT, gradient.colorLine.extendMode)
            assertEquals(listOf(0.0, 1.0), gradient.colorLine.colorStops.map { it.offset })
            assertEquals(listOf(GlyphColor(255, 0, 0, 255), GlyphColor(0, 0, 255, 255)), gradient.colorLine.colorStops.map { it.color })
            assertEquals(listOf(1.0, 1.0), gradient.colorLine.colorStops.map { it.opacity })
        }
    }

    @Test
    fun resolvesTheAuditedSweepAnglesAndPreciselyEncodedStops() {
        withPaint(0xF0200, 12) { paint ->
            val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
            assertEquals(176, clip.outline.glyphId)
            val gradient = assertIs<GlyphPaintNode.SweepGradient>(paint.nodes[clip.paint])
            assertEquals(GlyphPaintPoint(500.0, 600.0), gradient.center)
            assertEquals(0.0, gradient.startAngleDegrees)
            assertEquals(360.0, gradient.endAngleDegrees)
            assertEquals(GlyphPaintExtendMode.PAD, gradient.colorLine.extendMode)
            assertEquals(listOf(0.25, 0.41668701171875, 0.58331298828125, 0.75), gradient.colorLine.colorStops.map { it.offset })
            assertEquals(listOf(GlyphColor(250, 240, 230), GlyphColor(0, 0, 255), GlyphColor(255, 0, 0), GlyphColor(47, 79, 79)), gradient.colorLine.colorStops.map { it.color })
        }
    }

    @Test
    fun preservesTheAuditedClockwiseSweepAndItsColorProgression() {
        withPaint(0xF0203, 15) { paint ->
            val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
            assertEquals(176, clip.outline.glyphId)
            val gradient = assertIs<GlyphPaintNode.SweepGradient>(paint.nodes[clip.paint])
            assertEquals(GlyphPaintPoint(500.0, 600.0), gradient.center)
            assertEquals(90.0, gradient.startAngleDegrees)
            assertEquals(0.0, gradient.endAngleDegrees)
            assertEquals(GlyphPaintExtendMode.PAD, gradient.colorLine.extendMode)
            assertEquals(listOf(0.25, 0.41668701171875, 0.58331298828125, 0.75), gradient.colorLine.colorStops.map { it.offset })
            assertEquals(listOf(GlyphColor(250, 240, 230), GlyphColor(0, 0, 255), GlyphColor(255, 0, 0), GlyphColor(47, 79, 79)), gradient.colorLine.colorStops.map { it.color })
            assertEquals(listOf(1.0, 1.0, 1.0, 1.0), gradient.colorLine.colorStops.map { it.opacity })
        }
    }

    @Test
    fun resolvesTheAuditedRadialCircles() {
        withPaint(0xF0503, 93) { paint ->
            val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
            assertEquals(2, clip.outline.glyphId)
            val gradient = assertIs<GlyphPaintNode.RadialGradient>(paint.nodes[clip.paint])
            assertEquals(GlyphPaintPoint(166.0, 768.0), gradient.c0)
            assertEquals(0.0, gradient.radius0)
            assertEquals(GlyphPaintPoint(166.0, 768.0), gradient.c1)
            assertEquals(256.0, gradient.radius1)
            assertEquals(GlyphPaintExtendMode.PAD, gradient.colorLine.extendMode)
            assertEquals(listOf(0.0, 0.5, 1.0), gradient.colorLine.colorStops.map { it.offset })
            assertEquals(listOf(GlyphColor(0, 128, 0), GlyphColor(255, 255, 255), GlyphColor(255, 0, 0)), gradient.colorLine.colorStops.map { it.color })
        }
    }

    @Test
    fun selectsTheSecondPaletteWithoutChangingLinearGeometry() {
        withPaint(0xF0100, 8, paletteIndex = 1) { paint ->
            assertEquals(DesignBounds(100, 250, 900, 950), paint.clipBounds)
            val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
            assertEquals(8, clip.outline.glyphId)
            val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[clip.paint])
            assertLinearGeometry(gradient)
            assertEquals(GlyphPaintExtendMode.REPEAT, gradient.colorLine.extendMode)
            assertEquals(listOf(0.0, 1.0), gradient.colorLine.colorStops.map { it.offset })
            assertEquals(listOf(GlyphColor(42, 41, 74, 255), GlyphColor(14, 154, 194, 255)), gradient.colorLine.colorStops.map { it.color })
        }
    }

    private fun assertLinearGeometry(gradient: GlyphPaintNode.LinearGradient) {
        assertEquals(GlyphPaintPoint(100.0, 250.0), gradient.p0)
        assertEquals(GlyphPaintPoint(900.0, 250.0), gradient.p1)
        assertEquals(GlyphPaintPoint(100.0, 300.0), gradient.p2)
    }

    private fun withPaint(codePoint: Int, expectedGlyph: Int, paletteIndex: Int = 0, assertions: (GlyphPaintIR) -> Unit) {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/fonts/skia-colr-v1/test_glyphs-glyf_colr_1.ttf.b64"))
            .use { Base64.getMimeDecoder().decode(it.readBytes()) }
        assertEquals(21_568, bytes.size)
        assertEquals("72cb79b606c79bc49861094e25f442db0b24881504b29533bbe8ea75f3902e67", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val catalog = success(Kalligraphie.embedded(bytes, FontSourceProvenance("Skia COLR v1 test glyphs")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.GROUP, GlyphPaintNodeKind.SOLID, GlyphPaintNodeKind.LINEAR_GRADIENT, GlyphPaintNodeKind.RADIAL_GRADIENT, GlyphPaintNodeKind.SWEEP_GRADIENT, GlyphPaintNodeKind.GLYPH_CLIP),
            acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
            acceptedGradientExtendModes = listOf(GlyphPaintExtendMode.PAD, GlyphPaintExtendMode.REPEAT, GlyphPaintExtendMode.REFLECT),
            limits = PaintGraphLimits(maxNodes = 128, maxReferences = 256, maxDepth = 32, maxGradients = 32, maxColorStops = 128, maxClips = 32, maxPaintVisits = 512),
            outlineProfile = OutlineProfile(maxBytes = 1_000_000, maxContours = 1_024, maxPoints = 65_536, maxCompositeDepth = 16, maxCompositeComponents = 256),
            schemaVersion = 2,
        )))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(1_000f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val glyph = success(instance.resolveGlyph(codePoint)).glyphId
            assertEquals(GlyphId(expectedGlyph), glyph)
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot(cpalPaletteIndex = paletteIndex), requirements))
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(glyph)))).paint
                assertEquals(2, paint.schemaVersion)
                assertions(paint)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun <T> success(result: FontOperationResult<T>): T = assertIs<FontOperationResult.Success<T>>(result).value
}
