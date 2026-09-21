package org.graphiks.kalligraphie

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
