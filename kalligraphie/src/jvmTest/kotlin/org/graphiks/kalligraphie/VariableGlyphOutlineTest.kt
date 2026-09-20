package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile

class VariableGlyphOutlineTest {
    // Shared OFL variable TrueType fixture (wght 100/100/900, gvar present). Glyph 1 is 'A'.
    private val bytes: ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"),
    ).readBytes()

    @Test
    fun defaultInstanceOutlineIsUnchanged() {
        assertEquals(11.0, firstMoveToX(wght = null))
        assertEquals(11.0, firstMoveToX(wght = 100f))
    }

    @Test
    fun gvarVariationAtWght900MovesTheFirstContourPoint() {
        assertEquals(-8.0, firstMoveToX(wght = 900f))
    }

    private fun firstMoveToX(wght: Float?): Double {
        val catalog = success(Kalligraphie.embedded(bytes, FontSourceProvenance("NotoSansJP-VerticalFixture")))
        val resolver = success(catalog.openAssetResolver())
        val face = success(
            catalog.resolveFace(
                catalog.faces.single().id,
                FontAccessRequirementsSnapshot.renderable(outlineProfile()),
            ),
        )
        val instance = success(
            face.instantiate(
                FontInstanceDescriptor(
                    layoutSize = LayoutUnit(2048f),
                    variation = wght?.let {
                        FontVariationCoordinates(listOf(FontVariationCoordinate("wght", it)))
                    },
                ),
            ),
        )
        val asset = success(
            instance.acquireRenderAsset(
                resolver,
                FontRenderVariantKey.default,
                FontAccessRequirementsSnapshot.renderable(outlineProfile()),
            ),
        )
        val representation = success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1))))
        val outline = assertIs<GlyphRepresentation.Outline>(representation).outline
        val move = assertIs<GlyphOutlineCommand.MoveTo>(outline.contours.first().commands.first())
        return move.x
    }

    private fun outlineProfile(): OutlineProfile =
        OutlineProfile(
            maxBytes = 4_000_000,
            maxContours = 256,
            maxPoints = 16_384,
            maxCompositeDepth = 8,
            maxCompositeComponents = 256,
        )

    private fun <T> success(result: FontOperationResult<T>): T = when (result) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> error("Unexpected failure: ${result.error}")
        is FontOperationResult.Cancelled -> error("Unexpected cancellation")
    }
}
