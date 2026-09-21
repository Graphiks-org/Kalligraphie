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
import org.graphiks.kalligraphie.api.GlyphPaintExtendMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import org.graphiks.kalligraphie.font.sfnt.variation.itemVariationStore
import org.graphiks.kalligraphie.font.sfnt.variation.success
import org.graphiks.kalligraphie.font.sfnt.variation.writeUInt16
import org.graphiks.kalligraphie.font.sfnt.variation.writeUInt24
import org.graphiks.kalligraphie.font.sfnt.variation.writeUInt32
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

    @Test
    fun treatsAnAllZeroLocationAsTheDefaultInstance() {
        val data = success(
            ColrV1Reader.read(
                colrTable = colrV1SolidTable(deltaRow = -8192),
                cpalTable = cpalTable(),
                glyphCount = 2,
                profile = profile(),
                paletteIndex = 0,
                foregroundColor = GlyphColor(0, 0, 0),
                orderedAxes = listOf(0.0),
            ),
        )

        val paint = paintOf(data, GlyphId(1))
        val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode])
        val solid = assertIs<GlyphPaintNode.Solid>(paint.nodes[clip.paint])
        assertEquals(1.0, solid.opacity)
    }

    @Test
    fun rejectsAVariableRadialGradientWithANegativeRadius() {
        val data = success(
            ColrV1Reader.read(
                colrTable = colrV1RadialTable(deltaRow = -200),
                cpalTable = cpalTable(),
                glyphCount = 2,
                profile = radialProfile(),
                paletteIndex = 0,
                foregroundColor = GlyphColor(0, 0, 0),
                orderedAxes = listOf(1.0),
            ),
        )

        val failure = assertIs<FontOperationResult.Failure>(
            data.resolveGlyph(GlyphId(1), radialProfile(), CancellationToken.none) { clipId ->
                FontOperationResult.Success(outlineFor(clipId, radialProfile().outlineProfile))
            },
        )

        assertEquals("font.invalid-font-data", failure.error.code)
    }

    @Test
    fun variesTheClipBoxAtANonDefaultLocation() {
        val data = success(
            ColrV1Reader.read(
                colrTable = colrV1ClipTable(deltaRows = listOf(10, 0, 0, 0)),
                cpalTable = cpalTable(),
                glyphCount = 2,
                profile = profile(),
                paletteIndex = 0,
                foregroundColor = GlyphColor(0, 0, 0),
                orderedAxes = listOf(1.0),
            ),
        )

        assertEquals(DesignBounds(110, 250, 900, 950), paintOf(data, GlyphId(1)).clipBounds)
    }

    /**
     * At scalar 0.2 with deltas `[3, 3, 2, 2]`: `xMin`/`yMin` = 100.6/250.6 round down (floor) to
     * 100/250, and `xMax`/`yMax` = 900.4/950.4 round up (ceil) to 901/951. A nearest-integer round
     * would instead give 101/251/900/950, so this pins the `ClipBoxFormat2` rule.
     */
    @Test
    fun roundsTheVariedClipBoxOutwardAtAFractionalLocation() {
        val data = success(
            ColrV1Reader.read(
                colrTable = colrV1ClipTable(deltaRows = listOf(3, 3, 2, 2)),
                cpalTable = cpalTable(),
                glyphCount = 2,
                profile = profile(),
                paletteIndex = 0,
                foregroundColor = GlyphColor(0, 0, 0),
                orderedAxes = listOf(0.2),
            ),
        )

        assertEquals(DesignBounds(100, 250, 901, 951), paintOf(data, GlyphId(1)).clipBounds)
    }

    @Test
    fun forwardsTheCancellationTokenToTheVariationStore() {
        val failure = assertIs<FontOperationResult.Cancelled>(
            ColrV1Reader.read(
                colrTable = colrV1SolidTable(deltaRow = -8192),
                cpalTable = cpalTable(),
                glyphCount = 2,
                profile = profile(),
                paletteIndex = 0,
                foregroundColor = GlyphColor(0, 0, 0),
                orderedAxes = listOf(1.0),
                cancellationToken = CancellationToken.cancelled,
            ),
        )
    }

    @Test
    fun aNonVariableColourTableIsStaticAtANonDefaultLocation() {
        val data = success(
            ColrV1Reader.read(
                colrTable = colrV1SolidTable(deltaRow = -8192, withStore = false),
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
        assertEquals(1.0, solid.opacity)
    }

    @Test
    fun rejectsAnUnknownClipBoxFormat() {
        val failure = assertIs<FontOperationResult.Failure>(
            ColrV1Reader.read(
                colrTable = colrV1ClipTable(deltaRows = listOf(10, 0, 0, 0), clipBoxFormat = 3),
                cpalTable = cpalTable(),
                glyphCount = 2,
                profile = profile(),
                paletteIndex = 0,
                foregroundColor = GlyphColor(0, 0, 0),
                orderedAxes = listOf(1.0),
            ),
        )

        assertEquals("font.invalid-font-data", failure.error.code)
    }

    @Test
    fun rejectsAReversedVariedClipBox() {
        val data = success(
            ColrV1Reader.read(
                colrTable = colrV1ClipTable(deltaRows = listOf(800, 0, -800, 0)),
                cpalTable = cpalTable(),
                glyphCount = 2,
                profile = profile(),
                paletteIndex = 0,
                foregroundColor = GlyphColor(0, 0, 0),
                orderedAxes = listOf(1.0),
            ),
        )

        val failure = assertIs<FontOperationResult.Failure>(resolve(data, GlyphId(1)))
        assertEquals("font.invalid-font-data", failure.error.code)
    }

    /**
     * At scalar 0.25 with deltas `[3203, 0, 1, 0]` the raw coordinates are reversed
     * (`xMin` = 900.75 > `xMax` = 900.25). Outward rounding floors `xMin` to 900 and ceils `xMax`
     * to 901, producing the non-reversed box `(900, 250, 901, 950)`. The reversal check runs on the
     * rounded integers — a float reversal narrower than one font unit is widened, not rejected —
     * so this pins that the check is not applied to the intermediate doubles.
     */
    @Test
    fun acceptsAFloatReversedClipBoxThatRoundsForward() {
        val data = success(
            ColrV1Reader.read(
                colrTable = colrV1ClipTable(deltaRows = listOf(3203, 0, 1, 0)),
                cpalTable = cpalTable(),
                glyphCount = 2,
                profile = profile(),
                paletteIndex = 0,
                foregroundColor = GlyphColor(0, 0, 0),
                orderedAxes = listOf(0.25),
            ),
        )

        assertEquals(DesignBounds(900, 250, 901, 950), paintOf(data, GlyphId(1)).clipBounds)
    }

    /**
     * One-base-glyph COLR v1 table whose root is a `PaintVarSolid` at 44 and whose glyph-level
     * `ClipList` (format 1) sits at 56. The `ClipBox` (format 2 by default, `(100,250,900,950)`) is
     * at 68 with `VarIndexBase` zero, and the item variation store follows the ClipBox at 81.
     */
    private fun colrV1ClipTable(deltaRows: List<Int>, clipBoxFormat: Int = 2): ByteArray {
        val store = itemVariationStore(deltaRows.map { intArrayOf(it) })
        val clipListOffset = 56
        val clipBoxOffset = clipListOffset + 12
        val clipBoxExtent = when (clipBoxFormat) {
            1 -> 9
            else -> 13
        }
        val storeOffset = clipBoxOffset + 13
        require(clipListOffset >= 53) { "The ClipList at $clipListOffset overlaps the nine-byte paint at 44..52." }
        require(storeOffset >= clipBoxOffset + clipBoxExtent) { "The variation store at $storeOffset overlaps the ClipBox at $clipBoxOffset..${clipBoxOffset + clipBoxExtent - 1}." }
        val out = ByteArray(storeOffset + store.size)
        writeUInt16(out, 0, 1)
        writeUInt16(out, 2, 0)
        writeUInt32(out, 4, 0)
        writeUInt32(out, 8, 0)
        writeUInt16(out, 12, 0)
        writeUInt32(out, 14, 34)
        writeUInt32(out, 18, 0)
        writeUInt32(out, 22, clipListOffset)
        writeUInt32(out, 26, 0)
        writeUInt32(out, 30, storeOffset)
        writeUInt32(out, 34, 1)
        writeUInt16(out, 38, 1)
        writeUInt32(out, 40, 10)
        out[44] = 3
        writeUInt16(out, 45, 0)
        writeUInt16(out, 47, 16384)
        writeUInt32(out, 49, 0)
        out[clipListOffset] = 1
        writeUInt32(out, clipListOffset + 1, 1)
        writeUInt16(out, clipListOffset + 5, 1)
        writeUInt16(out, clipListOffset + 7, 1)
        writeUInt24(out, clipListOffset + 9, 12)
        out[clipBoxOffset] = clipBoxFormat.toByte()
        writeUInt16(out, clipBoxOffset + 1, 100)
        writeUInt16(out, clipBoxOffset + 3, 250)
        writeUInt16(out, clipBoxOffset + 5, 900)
        writeUInt16(out, clipBoxOffset + 7, 950)
        writeUInt32(out, clipBoxOffset + 9, 0)
        store.copyInto(out, storeOffset)
        return out
    }

    private fun resolve(
        data: ColrV1Data,
        glyphId: GlyphId,
        profile: PaintGraphProfile = profile(),
    ): FontOperationResult<GlyphRepresentation> =
        data.resolveGlyph(glyphId, profile, CancellationToken.none) { clipId ->
            FontOperationResult.Success(outlineFor(clipId, profile.outlineProfile))
        }

    private fun paintOf(data: ColrV1Data, glyphId: GlyphId, profile: PaintGraphProfile = profile()): GlyphPaintIR =
        assertIs<GlyphRepresentation.Paint>(success(resolve(data, glyphId, profile))).paint

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

    private fun radialProfile(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.GLYPH_CLIP, GlyphPaintNodeKind.RADIAL_GRADIENT),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        acceptedGradientExtendModes = listOf(GlyphPaintExtendMode.PAD),
        limits = PaintGraphLimits(maxNodes = 8, maxReferences = 8, maxDepth = 4, maxGradients = 1, maxColorStops = 4, maxClips = 1),
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
     * One-base-glyph COLR v1 table whose `PaintGlyph` root at 44 wraps a `PaintVarSolid` at 50 with
     * `VarIndexBase` zero. With [withStore] the one-axis item variation store follows the paint at
     * 59; without it the store offset field is zero and the table is a static COLR v1 header and
     * paint region of 59 bytes.
     */
    private fun colrV1SolidTable(deltaRow: Int, withStore: Boolean = true): ByteArray {
        val store = if (withStore) itemVariationStore(listOf(intArrayOf(deltaRow))) else ByteArray(0)
        val storeOffset = if (withStore) 59 else 0
        val total = if (withStore) storeOffset + store.size else 59
        require(!withStore || storeOffset >= 59) { "The variation store at $storeOffset overlaps the paint at 44..58." }
        val out = ByteArray(total)
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

    /**
     * One-base-glyph COLR v1 table whose `PaintGlyph` root wraps a `PaintVarRadialGradient` with
     * `VarIndexBase` zero, followed by the item variation store at offset 73. Ordinal 2 drives
     * `radius0`, so [deltaRow] occupies row 2 of a three-row store.
     */
    private fun colrV1RadialTable(deltaRow: Int): ByteArray {
        val store = itemVariationStore(listOf(intArrayOf(0), intArrayOf(0), intArrayOf(deltaRow)))
        val storeOffset = 73
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
        out[50] = 7
        writeUInt24(out, 51, 20)
        writeUInt16(out, 54, 0)
        writeUInt16(out, 56, 0)
        writeUInt16(out, 58, 100)
        writeUInt16(out, 60, 100)
        writeUInt16(out, 62, 100)
        writeUInt16(out, 64, 200)
        writeUInt32(out, 66, 0)
        out[70] = 1
        writeUInt16(out, 71, 0)
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
}
