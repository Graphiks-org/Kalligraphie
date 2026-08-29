package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FontContractsCompatibilityTest {
    @Test
    fun fontGlyphRequestKeepsTheJ11IntContract() {
        val legacy = FontGlyphRequest(glyphId = 36)
        val typed = FontGlyphRequest(GlyphId(36))

        assertEquals(36, legacy.glyphId)
        assertEquals(36, typed.glyphId)
    }

    @Test
    fun glyphOutlineKeepsTheJ11FlatCommandContract() {
        val commands = listOf<GlyphOutlineIR.Command>(
            GlyphOutlineIR.Command.MoveTo(4, 0),
            GlyphOutlineIR.Command.LineTo(10, 20),
            GlyphOutlineIR.Command.Close,
        )

        val outline = GlyphOutlineIR(
            glyphId = 36,
            unitsPerEm = 2_048,
            bounds = DesignBounds(4, 0, 10, 20),
            commands = commands,
        )
        val copiedCommands = listOf<GlyphOutlineIR.Command>(
            GlyphOutlineIR.Command.MoveTo(0, 0),
            GlyphOutlineIR.Command.Close,
        )
        val copied = outline.copy(commands = copiedCommands)
        val (_, _, _, destructuredCommands, fillRule) = outline

        assertEquals(commands, outline.commands)
        assertEquals(1, outline.contours.size)
        assertEquals(copiedCommands, copied.commands)
        assertEquals(commands, destructuredCommands)
        assertEquals(GlyphOutlineIR.FillRule.NON_ZERO, fillRule)
    }

    @Test
    fun j13RenderAssetImplementationsRetainTheirSingleArgumentResolveContract() {
        val asset: FontRenderAssetHandle = J13CompatibleRenderAsset()

        assertIs<FontOperationResult.Success<GlyphRepresentation>>(
            asset.resolveGlyph(FontGlyphRequest(0), CancellationToken.none),
        )
        assertIs<FontOperationResult.Failure>(asset.detach())
    }
}

private class J13CompatibleRenderAsset : FontRenderAssetHandle {
    override val faceId: FontFaceId = FontFaceId("legacy-face")

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        FontOperationResult.Success(GlyphRepresentation.Empty)

    override fun close(): FontOperationResult<Unit> = FontOperationResult.Success(Unit)
}
