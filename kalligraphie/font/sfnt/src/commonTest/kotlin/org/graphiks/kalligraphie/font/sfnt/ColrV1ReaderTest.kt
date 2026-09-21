@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphOutlineLimits
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import org.graphiks.kalligraphie.font.sfnt.variation.itemVariationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ColrV1ReaderTest {
    @Test
    fun appliesASolidAlphaDeltaAtANonDefaultLocation() {
        val data = success(
            ColrV1Reader.read(
                colrTable = colrV1SolidTable(deltaRow = -8192),
                cpalTable = cpalTable(),
                glyphCount = 2,
                profile = profile(),
                paletteIndex = 0,
                foregroundColor = GlyphColor(0, 0, 0),
                orderedAxes = listOf(1.0),
            ),
        )

        val paint = paintOf(data, GlyphId(1))
        val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
        val solid = assertIs<GlyphPaintNode.Solid>(paint.nodes[clip.paint])
        assertEquals(GlyphColor(255, 0, 0), solid.color)
        assertEquals(0.5, solid.opacity)
    }

    @Test
    fun leavesThePaintUnchangedAtTheDefaultInstance() {
        val data = success(
            ColrV1Reader.read(
                colrTable = colrV1SolidTable(deltaRow = -8192),
                cpalTable = cpalTable(),
                glyphCount = 2,
                profile = profile(),
                paletteIndex = 0,
                foregroundColor = GlyphColor(0, 0, 0),
                orderedAxes = emptyList(),
            ),
        )

        val paint = paintOf(data, GlyphId(1))
        val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
        val solid = assertIs<GlyphPaintNode.Solid>(paint.nodes[clip.paint])
        assertEquals(1.0, solid.opacity)
    }

    private fun paintOf(data: ColrV1Data, glyphId: GlyphId): GlyphPaintIR =
        assertIs<GlyphRepresentation.Paint>(
            success(
                data.resolveGlyph(glyphId, profile(), CancellationToken.none) { clipId ->
                    FontOperationResult.Success(outlineFor(clipId, profile().outlineProfile))
                },
            ),
        ).paint

    /** A real empty-contour outline whose limits match [outline], so the paint profile accepts it. */
    private fun outlineFor(glyphId: GlyphId, outline: OutlineProfile): GlyphOutlineIR = GlyphOutlineIR(
        glyphId = glyphId.value,
        unitsPerEm = 1000,
        bounds = DesignBounds(0, 0, 0, 0),
        contours = emptyList(),
        pointCount = 0,
        limits = GlyphOutlineLimits(
            maxBytes = outline.maxBytes,
            maxContours = outline.maxContours,
            maxPoints = outline.maxPoints,
            maxCompositeDepth = outline.maxCompositeDepth,
            maxCompositeComponents = outline.maxCompositeComponents,
        ),
    )

    private fun profile(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.GLYPH_CLIP, GlyphPaintNodeKind.SOLID),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(maxNodes = 8, maxReferences = 8, maxDepth = 4, maxClips = 1),
        outlineProfile = OutlineProfile(
            maxBytes = 1_024,
            maxContours = 8,
            maxPoints = 64,
            maxCompositeDepth = 4,
            maxCompositeComponents = 8,
        ),
        schemaVersion = 2,
    )

    /**
     * One-base-glyph COLR v1 table whose `PaintGlyph` root wraps a `PaintVarSolid` with
     * `VarIndexBase` zero, followed by the item variation store at offset 59.
     */
    private fun colrV1SolidTable(deltaRow: Int): ByteArray {
        val store = itemVariationStore(listOf(intArrayOf(deltaRow)))
        val storeOffset = 59
        val out = ByteArray(storeOffset + store.size)
        writeUInt16(out, 0, 1)
        writeUInt16(out, 2, 0)
        writeUInt32(out, 4, 0)
        writeUInt32(out, 8, 0)
        writeUInt16(out, 12, 0)
        writeUInt32(out, 14, 34)
        writeUInt32(out, 18, 0)
        writeUInt32(out, 22, 0)
        writeUInt32(out, 26, 0)
        writeUInt32(out, 30, storeOffset)
        writeUInt32(out, 34, 1)
        writeUInt16(out, 38, 1)
        writeUInt32(out, 40, 10)
        out[44] = 10
        writeUInt24(out, 45, 6)
        writeUInt16(out, 48, 0)
        out[50] = 3
        writeUInt16(out, 51, 0)
        writeUInt16(out, 53, 16384)
        writeUInt32(out, 55, 0)
        store.copyInto(out, storeOffset)
        return out
    }

    /** Two-entry CPAL v0 table whose palette holds opaque red and opaque blue records. */
    private fun cpalTable(): ByteArray = ByteArray(32).also { bytes ->
        writeUInt16(bytes, 0, 0)
        writeUInt16(bytes, 2, 2)
        writeUInt16(bytes, 4, 1)
        writeUInt16(bytes, 6, 2)
        writeUInt32(bytes, 8, 16)
        writeUInt16(bytes, 12, 0)
        bytes[16] = 0; bytes[17] = 0; bytes[18] = 255.toByte(); bytes[19] = 255.toByte()
        bytes[20] = 255.toByte(); bytes[21] = 0; bytes[22] = 0; bytes[23] = 255.toByte()
    }

    private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun writeUInt24(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 16).toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = value.toByte()
    }

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun <T> success(result: FontOperationResult<T>): T = assertIs<FontOperationResult.Success<T>>(result).value
}
