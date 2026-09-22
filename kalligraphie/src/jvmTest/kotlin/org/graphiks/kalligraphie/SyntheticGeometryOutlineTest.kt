package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontGeometryParameters
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile

class SyntheticGeometryOutlineTest {
    @Test
    fun emboldensTheHyphenOutlineByThePinnedEmFraction() {
        val renderable = openRenderable(bold = true, italic = false)
        try {
            val outline = resolveOutline(renderable, 0x2D)

            assertEquals(DesignBounds(50, 423, 632, 665), outline.bounds)
            val move = assertIs<GlyphOutlineCommand.MoveTo>(outline.contours.single().commands.first())
            assertEquals(50.04, move.x, 1e-9)
            assertEquals(423.04, move.y, 1e-9)
        } finally {
            close(renderable)
        }
    }

    @Test
    fun obliquesTheHyphenOutlineByThePinnedShearTangent() {
        val renderable = openRenderable(bold = false, italic = true)
        try {
            val outline = resolveOutline(renderable, 0x2D)

            assertEquals(DesignBounds(206, 464, 747, 624), outline.bounds)
            val commands = outline.contours.single().commands
            val move = assertIs<GlyphOutlineCommand.MoveTo>(commands.first())
            assertEquals(206.68819331923584, move.x, 1e-9)
            assertEquals(464.0, move.y, 1e-9)
            val third = assertIs<GlyphOutlineCommand.LineTo>(commands[2])
            assertEquals(746.5806737741448, third.x, 1e-9)
            assertEquals(624.0, third.y, 1e-9)
        } finally {
            close(renderable)
        }
    }

    @Test
    fun leavesMetricsAndFontMetricsUnchangedBySyntheticGeometry() {
        val plain = openRenderable(bold = false, italic = false)
        val styled = openRenderable(bold = true, italic = true)
        try {
            val plainGlyph = resolveGlyphId(plain, 0x2D)
            val styledGlyph = resolveGlyphId(styled, 0x2D)
            assertEquals(plainGlyph, styledGlyph)

            val plainMetrics = assertIs<FontOperationResult.Success<GlyphMetrics>>(plain.instance.metrics(plainGlyph)).value
            val styledMetrics = assertIs<FontOperationResult.Success<GlyphMetrics>>(styled.instance.metrics(styledGlyph)).value
            assertEquals(plainMetrics, styledMetrics)

            val plainFontMetrics = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.FontMetrics>>(plain.instance.fontMetrics()).value
            val styledFontMetrics = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.FontMetrics>>(styled.instance.fontMetrics()).value
            assertEquals(plainFontMetrics, styledFontMetrics)
        } finally {
            close(plain)
            close(styled)
        }
    }

    private fun resolveGlyphId(renderable: Renderable, codePoint: Int): GlyphId =
        assertIs<FontOperationResult.Success<GlyphResolution>>(renderable.instance.resolveGlyph(codePoint)).value.glyphId

    private fun resolveOutline(renderable: Renderable, codePoint: Int): org.graphiks.kalligraphie.api.GlyphOutlineIR {
        val glyphId = resolveGlyphId(renderable, codePoint)
        return assertIs<GlyphRepresentation.Outline>(
            assertIs<FontOperationResult.Success<GlyphRepresentation>>(
                renderable.asset.resolveGlyph(FontGlyphRequest(glyphId)),
            ).value,
        ).outline
    }

    private fun close(renderable: Renderable) {
        renderable.asset.close()
        renderable.resolver.close()
    }

    private fun openRenderable(bold: Boolean, italic: Boolean): Renderable {
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Liberation Sans Regular")),
        ).value
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(catalog.openAssetResolver()).value
        val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile())
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(catalog.faces.single().id, requirements),
        ).value
        val instance = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(
                FontInstanceDescriptor(
                    layoutSize = LayoutUnit(2_048f),
                    geometry = FontGeometryParameters(syntheticBold = bold, syntheticItalic = italic),
                ),
            ),
        ).value
        val asset = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
            instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
        ).value
        return Renderable(instance, asset, resolver)
    }

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 256,
        maxPoints = 16_384,
        maxCompositeDepth = 8,
        maxCompositeComponents = 256,
    )

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "Liberation Sans fixture is missing"
        }.use { it.readBytes() }

    private data class Renderable(
        val instance: FontInstance,
        val asset: FontRenderAssetHandle,
        val resolver: FontAssetResolverHandle,
    )
}
