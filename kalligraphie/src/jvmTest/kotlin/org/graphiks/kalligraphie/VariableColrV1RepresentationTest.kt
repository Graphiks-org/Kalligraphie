package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintExtendMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphPaintPoint
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VariableColrV1RepresentationTest {
    private val bytes: ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/fonts/kalligraphie-var-colr/KalligraphieVarCOLRv1.ttf"),
    ).readBytes()

    @Test
    fun solidAlphaVariesWithTheInstanceLocation() {
        assertSolid(wght = null, expectedOpacity = 1.0)
        assertSolid(wght = 900f, expectedOpacity = 0.5)
    }

    @Test
    fun translateVariesWithTheInstanceLocation() {
        assertTransform(wght = null, expectedDx = 10.0, expectedDy = 20.0, expectedChildOpacity = 1.0)
        assertTransform(wght = 900f, expectedDx = 60.0, expectedDy = -40.0, expectedChildOpacity = 0.5)
    }

    @Test
    fun linearGradientStopsAndClipVaryWithTheInstanceLocation() {
        assertLinear(
            wght = null,
            expectedClip = DesignBounds(100, 250, 900, 950),
            expectedP0 = GlyphPaintPoint(100.0, 250.0),
            expectedP2 = GlyphPaintPoint(100.0, 300.0),
            expectedStop0Offset = 0.0,
            expectedStop0Opacity = 1.0,
            expectedStop1Offset = 1.0,
        )
        assertLinear(
            wght = 650f,
            expectedClip = DesignBounds(105, 260, 885, 930),
            expectedP0 = GlyphPaintPoint(150.0, 225.0),
            expectedP2 = GlyphPaintPoint(87.5, 337.5),
            expectedStop0Offset = 0.125,
            expectedStop0Opacity = 0.75,
            expectedStop1Offset = 1.0625,
        )
        assertLinear(
            wght = 900f,
            expectedClip = DesignBounds(110, 270, 870, 910),
            expectedP0 = GlyphPaintPoint(200.0, 200.0),
            expectedP2 = GlyphPaintPoint(75.0, 375.0),
            expectedStop0Offset = 0.25,
            expectedStop0Opacity = 0.5,
            expectedStop1Offset = 1.125,
        )
    }

    private fun assertLinear(
        wght: Float?,
        expectedClip: DesignBounds,
        expectedP0: GlyphPaintPoint,
        expectedP2: GlyphPaintPoint,
        expectedStop0Offset: Double,
        expectedStop0Opacity: Double,
        expectedStop1Offset: Double,
    ) {
        withPaint(0x41, wght) { paint ->
            assertEquals(expectedClip, paint.clipBounds)
            val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
            val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[clip.paint])
            assertEquals(expectedP0, gradient.p0)
            assertEquals(expectedP2, gradient.p2)
            assertEquals(GlyphPaintExtendMode.REPEAT, gradient.colorLine.extendMode)
            assertEquals(listOf(GlyphColor(255, 0, 0, 255), GlyphColor(0, 0, 255, 255)), gradient.colorLine.colorStops.map { it.color })
            assertEquals(expectedStop0Offset, gradient.colorLine.colorStops[0].offset)
            assertEquals(expectedStop0Opacity, gradient.colorLine.colorStops[0].opacity)
            assertEquals(expectedStop1Offset, gradient.colorLine.colorStops[1].offset)
        }
    }

    private fun assertSolid(wght: Float?, expectedOpacity: Double) {
        withPaint(0x42, wght) { paint ->
            val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
            val solid = assertIs<GlyphPaintNode.Solid>(paint.nodes[clip.paint])
            assertEquals(GlyphColor(255, 0, 0), solid.color)
            assertEquals(expectedOpacity, solid.opacity)
        }
    }

    private fun assertTransform(wght: Float?, expectedDx: Double, expectedDy: Double, expectedChildOpacity: Double) {
        withPaint(0x43, wght) { paint ->
            val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
            val transform = assertIs<GlyphPaintNode.Transform>(paint.nodes[clip.paint])
            assertEquals(expectedDx, transform.matrix.dx)
            assertEquals(expectedDy, transform.matrix.dy)
            val solid = assertIs<GlyphPaintNode.Solid>(paint.nodes[transform.paint])
            assertEquals(expectedChildOpacity, solid.opacity)
        }
    }

    private fun withPaint(
        codePoint: Int,
        wght: Float?,
        fontBytes: ByteArray = bytes,
        provenance: String = "Kalligraphie variable COLR v1 fixture",
        assertions: (GlyphPaintIR) -> Unit,
    ) {
        val catalog = success(
            Kalligraphie.embedded(fontBytes, FontSourceProvenance(provenance)),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(
            listOf(
                PaintGraphProfile(
                    acceptedNodeKinds = listOf(
                        GlyphPaintNodeKind.GLYPH_CLIP,
                        GlyphPaintNodeKind.SOLID,
                        GlyphPaintNodeKind.LINEAR_GRADIENT,
                        GlyphPaintNodeKind.TRANSFORM,
                    ),
                    acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
                    acceptedGradientExtendModes = listOf(
                        GlyphPaintExtendMode.PAD,
                        GlyphPaintExtendMode.REPEAT,
                        GlyphPaintExtendMode.REFLECT,
                    ),
                    limits = PaintGraphLimits(
                        maxNodes = 32,
                        maxReferences = 32,
                        maxDepth = 8,
                        maxGradients = 4,
                        maxColorStops = 16,
                        maxClips = 4,
                        maxTransforms = 4,
                        maxPaintVisits = 64,
                    ),
                    outlineProfile = OutlineProfile(
                        maxBytes = 1_000_000,
                        maxContours = 1_024,
                        maxPoints = 65_536,
                        maxCompositeDepth = 16,
                        maxCompositeComponents = 256,
                    ),
                    schemaVersion = 2,
                ),
            ),
        )
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val descriptor = FontInstanceDescriptor(
            variation = wght?.let { FontVariationCoordinates(listOf(FontVariationCoordinate("wght", it))) },
        )
        val instance = success(face.instantiate(descriptor))
        val resolver = success(catalog.openAssetResolver())
        try {
            val glyph = success(instance.resolveGlyph(codePoint)).glyphId
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements))
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(
                    success(asset.resolveGlyph(FontGlyphRequest(glyph.value))),
                ).paint
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
