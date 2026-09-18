@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import kotlin.io.encoding.Base64
import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapResourceLimit
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SbixReaderTest {
    @Test
    fun decodesStraightRgbaPixelsFromAPngRecord() {
        val table = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph(originX = 64, originY = 128))))

        val glyph = decoded(value(SbixReader.read(table, 1, 1_000, { 192 }, profile())).decode(GlyphId(0)))

        assertContentEquals(rgba2x2Pixels(), glyph.copyDecodedPixels())
        assertEquals(2, glyph.width)
        assertEquals(2, glyph.height)
        assertEquals(1, glyph.originX)
        assertEquals(2, glyph.originY)
        assertEquals(3, glyph.metrics.advanceX)
        assertEquals(0, glyph.metrics.advanceY)
        assertEquals(BitmapPixelFormat.RGBA_8888, glyph.pixelFormat)
        assertEquals(BitmapStrike(16, 16, 32), glyph.strike)
    }

    @Test
    fun convertsDesignUnitOriginsWithRoundHalfAwayFromZero() {
        val positive = sbixTable(
            1,
            SbixStrikeSpec(16, listOf(pngGlyph(originX = 24))),
            SbixStrikeSpec(64, listOf(pngGlyph(originX = 24))),
            SbixStrikeSpec(128, listOf(pngGlyph(originX = 24))),
        )
        val negative = sbixTable(1, SbixStrikeSpec(128, listOf(pngGlyph(originX = -24))))
        val ties = sbixTable(2, SbixStrikeSpec(100, listOf(pngGlyph(originX = 5), pngGlyph(originX = -5))))

        assertEquals(0, decoded(value(SbixReader.read(positive, 1, 1_000, { 0 }, profile(ppem = 16))).decode(GlyphId(0))).originX)
        assertEquals(2, decoded(value(SbixReader.read(positive, 1, 1_000, { 0 }, profile(ppem = 64))).decode(GlyphId(0))).originX)
        assertEquals(3, decoded(value(SbixReader.read(positive, 1, 1_000, { 0 }, profile(ppem = 128))).decode(GlyphId(0))).originX)
        assertEquals(-3, decoded(value(SbixReader.read(negative, 1, 1_000, { 0 }, profile(ppem = 128))).decode(GlyphId(0))).originX)

        val tieData = value(SbixReader.read(ties, 2, 1_000, { 0 }, profile(ppem = 100)))
        assertEquals(1, decoded(tieData.decode(GlyphId(0))).originX)
        assertEquals(-1, decoded(tieData.decode(GlyphId(1))).originX)
    }

    @Test
    fun convertsHmtxAdvancesToStrikePixels() {
        val table = sbixTable(1, SbixStrikeSpec(128, listOf(pngGlyph())))

        val glyph = decoded(value(SbixReader.read(table, 1, 1_000, { 8 }, profile(ppem = 128))).decode(GlyphId(0)))

        assertEquals(1, glyph.metrics.advanceX)
        assertEquals(0, glyph.metrics.advanceY)
    }

    @Test
    fun resolvesDupeRecordsToTheReferencedImageWithTheCurrentOrigin() {
        val table = sbixTable(
            2,
            SbixStrikeSpec(
                16,
                listOf(
                    pngGlyph(originX = 64, originY = 128),
                    dupeGlyph(target = 0, originX = 128, originY = 64),
                ),
            ),
        )
        val data = value(SbixReader.read(table, 2, 1_000, { 192 }, profile()))

        val source = decoded(data.decode(GlyphId(0)))
        val dupe = decoded(data.decode(GlyphId(1)))

        assertContentEquals(source.copyDecodedPixels(), dupe.copyDecodedPixels())
        assertEquals(2, dupe.width)
        assertEquals(2, dupe.height)
        assertEquals(2, dupe.originX)
        assertEquals(1, dupe.originY)
        assertEquals(3, dupe.metrics.advanceX)
    }

    @Test
    fun resolvesDupeChains() {
        val table = sbixTable(
            3,
            SbixStrikeSpec(
                16,
                listOf(
                    pngGlyph(originX = 64, originY = 128),
                    dupeGlyph(target = 0, originX = 128, originY = 64),
                    dupeGlyph(target = 1, originX = 192),
                ),
            ),
        )
        val data = value(SbixReader.read(table, 3, 1_000, { 192 }, profile()))

        val source = decoded(data.decode(GlyphId(0)))
        val chained = decoded(data.decode(GlyphId(2)))

        assertContentEquals(source.copyDecodedPixels(), chained.copyDecodedPixels())
        assertEquals(3, chained.originX)
        assertEquals(0, chained.originY)
    }

    @Test
    fun reportsADupeCycleAsInvalidData() {
        val table = sbixTable(
            3,
            SbixStrikeSpec(16, listOf(pngGlyph(), dupeGlyph(target = 2), dupeGlyph(target = 1))),
        )

        assertEquals(
            "font.sbix.duplicate-cycle",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 3, 1_000, { 192 }, profile())),
            ).code,
        )
    }

    @Test
    fun rejectsADupeWhosePayloadIsNotTwoBytes() {
        val table = sbixTable(1, SbixStrikeSpec(16, listOf(SbixGlyphSpec(0, 0, "dupe", byteArrayOf(0)))))

        assertEquals(
            "font.sbix.invalid-dupe",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 1, 1_000, { 192 }, profile())),
            ).code,
        )
    }

    @Test
    fun reportsNonPngGraphicTypesAsUnsupported() {
        val jpeg = sbixTable(1, SbixStrikeSpec(16, listOf(SbixGlyphSpec(0, 0, "jpg ", ByteArray(4)))))
        val tiff = sbixTable(1, SbixStrikeSpec(16, listOf(SbixGlyphSpec(0, 0, "tiff", ByteArray(4)))))

        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(SbixReader.read(jpeg, 1, 1_000, { 192 }, profile())),
        )
        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(SbixReader.read(tiff, 1, 1_000, { 192 }, profile())),
        )
    }

    @Test
    fun selectsOnlyTheExactPpem() {
        val table = sbixTable(
            1,
            SbixStrikeSpec(16, listOf(pngGlyph())),
            SbixStrikeSpec(32, listOf(pngGlyph())),
        )

        val sixteen = decoded(value(SbixReader.read(table, 1, 1_000, { 192 }, profile(ppem = 16))).decode(GlyphId(0)))
        val thirtyTwo = decoded(value(SbixReader.read(table, 1, 1_000, { 192 }, profile(ppem = 32))).decode(GlyphId(0)))

        assertEquals(BitmapStrike(16, 16, 32), sixteen.strike)
        assertEquals(BitmapStrike(32, 32, 32), thirtyTwo.strike)
        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(SbixReader.read(table, 1, 1_000, { 192 }, profile(ppem = 24))),
        )

        val duplicates = sbixTable(
            1,
            SbixStrikeSpec(16, listOf(pngGlyph())),
            SbixStrikeSpec(16, listOf(pngGlyph())),
        )
        assertEquals(
            "font.sbix.duplicate-strike",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(duplicates, 1, 1_000, { 192 }, profile(ppem = 16))),
            ).code,
        )
    }

    @Test
    fun rejectsUnsupportedVersionsAndReservedFlags() {
        val strike = SbixStrikeSpec(16, listOf(pngGlyph()))

        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(SbixReader.read(sbixTable(1, strike, version = 2), 1, 1_000, { 192 }, profile())),
        )
        assertEquals(
            "font.sbix.invalid-flags",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(sbixTable(1, strike, flags = 0x04), 1, 1_000, { 192 }, profile())),
            ).code,
        )
        assertEquals(
            "font.sbix.invalid-flags",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(sbixTable(1, strike, flags = 0x02), 1, 1_000, { 192 }, profile())),
            ).code,
        )
        assertIs<FontOperationResult.Success<SbixData>>(
            SbixReader.read(sbixTable(1, strike, flags = 0x03), 1, 1_000, { 192 }, profile()),
        )
    }

    @Test
    fun reportsTypedBoundsForSourceAndCumulativeBytes() {
        val one = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph())))
        val recordBytes = RGBA_2X2_BYTES.size

        val sourceError = assertIs<FontError.BitmapResourceLimitExceeded>(
            error(
                SbixReader.read(one, 1, 1_000, { 192 }, profile(maxSourceTableBytes = one.size - 1)),
            ),
        )
        assertEquals(BitmapResourceLimit.SOURCE_TABLE_BYTES, sourceError.limit)
        assertEquals(one.size.toLong(), sourceError.observed)
        assertEquals((one.size - 1).toLong(), sourceError.maximum)

        val compressedError = assertIs<FontError.BitmapResourceLimitExceeded>(
            error(
                SbixReader.read(one, 1, 1_000, { 192 }, profile(maxCompressedBytes = recordBytes - 1)),
            ),
        )
        assertEquals(BitmapResourceLimit.COMPRESSED_BYTES, compressedError.limit)
        assertEquals(recordBytes.toLong(), compressedError.observed)
        assertEquals((recordBytes - 1).toLong(), compressedError.maximum)

        val two = sbixTable(2, SbixStrikeSpec(16, listOf(pngGlyph(), pngGlyph())))
        val totalError = assertIs<FontError.BitmapResourceLimitExceeded>(
            error(
                SbixReader.read(two, 2, 1_000, { 192 }, profile(maxTotalCompressedBytes = recordBytes * 2 - 1)),
            ),
        )
        assertEquals(BitmapResourceLimit.TOTAL_COMPRESSED_BYTES, totalError.limit)
        assertEquals(recordBytes.toLong() * 2, totalError.observed)
        assertEquals((recordBytes * 2 - 1).toLong(), totalError.maximum)

        val recordError = assertIs<FontError.BitmapResourceLimitExceeded>(
            error(SbixReader.read(two, 2, 1_000, { 192 }, profile(maxRecordCount = 1))),
        )
        assertEquals(BitmapResourceLimit.RECORD_COUNT, recordError.limit)
        assertEquals(2, recordError.observed)
        assertEquals(1, recordError.maximum)
    }

    @Test
    fun reportsAMissingAdvanceAsInvalidData() {
        val table = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph())))

        assertEquals(
            "font.sbix.missing-advance",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 1, 1_000, { null }, profile())),
            ).code,
        )
    }

    @Test
    fun hasSupportedVersionOneHeaderAndStructuralPredicateAgreeWithRead() {
        val table = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph(originX = 64, originY = 128))))

        assertTrue(SbixReader.hasSupportedVersionOneHeader(table))
        assertTrue(SbixReader.hasStructurallyValidTable(table, 1, 1_000, { 192 }))
        assertIs<FontOperationResult.Success<SbixData>>(SbixReader.read(table, 1, 1_000, { 192 }, profile()))

        val versionTwo = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph())), version = 2)
        assertFalse(SbixReader.hasSupportedVersionOneHeader(versionTwo))
        assertFalse(SbixReader.hasStructurallyValidTable(versionTwo, 1, 1_000, { 192 }))
        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(SbixReader.read(versionTwo, 1, 1_000, { 192 }, profile())),
        )

        assertFalse(SbixReader.hasSupportedVersionOneHeader(ByteArray(7)))
        assertFalse(SbixReader.hasStructurallyValidTable(table, 1, 1_000, { null }))
        assertFalse(SbixReader.hasStructurallyValidTable(table, 0, 1_000, { 192 }))
        assertFalse(SbixReader.hasStructurallyValidTable(table, 1, 0, { 192 }))

        assertEquals(
            "font.sbix.invalid-glyph-count",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 0, 1_000, { 192 }, profile())),
            ).code,
        )
        assertEquals(
            "font.sbix.invalid-units-per-em",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 1, 0, { 192 }, profile())),
            ).code,
        )
    }

    @Test
    fun rejectsUnsupportedProfilesWithDistinctSchemaAndCapabilityFailures() {
        val table = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph())))

        val schema = assertIs<FontError.UnsupportedRepresentationProfile>(
            error(SbixReader.read(table, 1, 1_000, { 192 }, profile(schemaVersion = 1))),
        )
        assertTrue(schema.message.contains("schema version 2"), schema.message)

        val bitDepth = assertIs<FontError.UnsupportedRepresentationProfile>(
            error(SbixReader.read(table, 1, 1_000, { 192 }, profile(bitDepth = 1))),
        )
        assertTrue(bitDepth.message.contains("32-bit RGBA"), bitDepth.message)
    }

    @Test
    fun reportsAnAbsentRecordAndAnOutOfRangeGlyph() {
        val table = sbixTable(2, SbixStrikeSpec(16, listOf(pngGlyph(), null)))
        val data = value(SbixReader.read(table, 2, 1_000, { 192 }, profile()))

        val outOfRange = assertIs<FontError.GlyphOutOfRange>(
            decodeError(data.decode(GlyphId(2))),
        )
        assertEquals(2, outOfRange.glyphId)

        val absent = assertIs<FontError.GlyphRepresentationUnavailable>(
            decodeError(data.decode(GlyphId(1))),
        )
        assertTrue(absent.message.contains("16"), absent.message)
    }

    @Test
    fun reportsNonMonotonicGlyphOffsetsAsInvalidData() {
        val table = sbixTable(2, SbixStrikeSpec(16, listOf(pngGlyph(), pngGlyph())))
        val strikeOffset = readUInt32(table, SBIT_HEADER_BASE_LENGTH)!!.toInt()
        table.writeUInt32(strikeOffset + STRIKE_HEADER_BASE_LENGTH + GLYPH_OFFSET_LENGTH, 0u)

        assertEquals(
            "font.sbix.invalid-offset-order",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 2, 1_000, { 192 }, profile())),
            ).code,
        )
    }

    @Test
    fun reportsTruncatedGlyphDataAsTruncated() {
        val table = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph())))
        val strikeOffset = readUInt32(table, SBIT_HEADER_BASE_LENGTH)!!.toInt()
        table.writeUInt32(strikeOffset + STRIKE_HEADER_BASE_LENGTH + GLYPH_OFFSET_LENGTH, (table.size + 8).toUInt())

        assertEquals(
            "font.sbix.truncated",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 1, 1_000, { 192 }, profile())),
            ).code,
        )
    }

    @Test
    fun reportsADupeTargetWithoutABitmapAsInvalidData() {
        val table = sbixTable(
            3,
            SbixStrikeSpec(16, listOf(pngGlyph(), null, dupeGlyph(target = 1))),
        )

        assertEquals(
            "font.sbix.invalid-dupe",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 3, 1_000, { 192 }, profile())),
            ).code,
        )
    }

    @Test
    fun reportsADupeTargetOutsideTheFaceAsInvalidData() {
        val table = sbixTable(1, SbixStrikeSpec(16, listOf(dupeGlyph(target = 1))))

        assertEquals(
            "font.sbix.invalid-dupe",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 1, 1_000, { 192 }, profile())),
            ).code,
        )
    }

    @Test
    fun reportsTheStrikeAndDecodedByteBoundsAsTypedFailures() {
        val twoStrikes = sbixTable(
            1,
            SbixStrikeSpec(16, listOf(pngGlyph())),
            SbixStrikeSpec(32, listOf(pngGlyph())),
        )
        val strikeError = assertIs<FontError.BitmapResourceLimitExceeded>(
            error(SbixReader.read(twoStrikes, 1, 1_000, { 192 }, profile(ppem = 16, maxStrikes = 1))),
        )
        assertEquals(BitmapResourceLimit.STRIKES, strikeError.limit)
        assertEquals(2, strikeError.observed)
        assertEquals(1, strikeError.maximum)

        val image = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph())))
        val decodedError = assertIs<FontError.BitmapResourceLimitExceeded>(
            error(SbixReader.read(image, 1, 1_000, { 192 }, profile(maxTotalDecodedBytes = 15))),
        )
        assertEquals(BitmapResourceLimit.TOTAL_DECODED_BYTES, decodedError.limit)
        assertEquals(16, decodedError.observed)
        assertEquals(15, decodedError.maximum)
    }

    @Test
    fun clampsNegativeAdvancesToZero() {
        val table = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph())))

        val glyph = decoded(value(SbixReader.read(table, 1, 1_000, { -5 }, profile())).decode(GlyphId(0)))

        assertEquals(0, glyph.metrics.advanceX)
    }

    @Test
    fun countsASharedDupeImageOnlyOnce() {
        val table = sbixTable(2, SbixStrikeSpec(16, listOf(pngGlyph(), dupeGlyph(target = 0))))
        val payload = RGBA_2X2_BYTES.size
        val decodedBytes = 2 * 2 * 4

        assertIs<FontOperationResult.Success<SbixData>>(
            SbixReader.read(
                table,
                2,
                1_000,
                { 192 },
                profile(maxTotalCompressedBytes = payload + 2, maxTotalDecodedBytes = decodedBytes),
            ),
        )

        val compressed = assertIs<FontError.BitmapResourceLimitExceeded>(
            error(SbixReader.read(table, 2, 1_000, { 192 }, profile(maxTotalCompressedBytes = payload + 1))),
        )
        assertEquals(BitmapResourceLimit.TOTAL_COMPRESSED_BYTES, compressed.limit)
        assertEquals((payload + 2).toLong(), compressed.observed)
        assertEquals((payload + 1).toLong(), compressed.maximum)

        val totalDecoded = assertIs<FontError.BitmapResourceLimitExceeded>(
            error(SbixReader.read(table, 2, 1_000, { 192 }, profile(maxTotalDecodedBytes = decodedBytes - 1))),
        )
        assertEquals(BitmapResourceLimit.TOTAL_DECODED_BYTES, totalDecoded.limit)
        assertEquals(decodedBytes.toLong(), totalDecoded.observed)
        assertEquals((decodedBytes - 1).toLong(), totalDecoded.maximum)
    }

    @Test
    fun rejectsUnitsPerEmOutsideTheOpenTypeDomain() {
        val table = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph())))

        assertEquals(
            "font.sbix.invalid-units-per-em",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 1, 15, { 192 }, profile())),
            ).code,
        )
        assertEquals(
            "font.sbix.invalid-units-per-em",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 1, 16_385, { 192 }, profile())),
            ).code,
        )
    }

    @Test
    fun rejectsAnAdvanceOutsideTheHmtxDomain() {
        val table = sbixTable(1, SbixStrikeSpec(16, listOf(pngGlyph())))

        assertEquals(
            "font.sbix.invalid-advance",
            assertIs<FontError.FontDataFailure>(
                error(SbixReader.read(table, 1, 1_000, { 65_536 }, profile())),
            ).code,
        )
    }

    private fun profile(
        ppem: Int = 16,
        schemaVersion: Int = 2,
        bitDepth: Int = 32,
        maxStrikes: Int = 8,
        maxRecordCount: Int = 8,
        maxSourceTableBytes: Int = 4_096,
        maxCompressedBytes: Int = 4_096,
        maxTotalCompressedBytes: Int = 4_096,
        maxTotalDecodedBytes: Int = 1_024,
    ): BitmapProfile = BitmapProfile(
        strike = BitmapStrike(ppem, ppem, bitDepth),
        acceptedPixelFormats = listOf(BitmapPixelFormat.RGBA_8888),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = maxStrikes,
            maxIndexSubtables = 8,
            maxRecordCount = maxRecordCount,
            maxIndexTableBytes = 4_096,
            maxSourceTableBytes = maxSourceTableBytes,
            maxWidth = 16,
            maxHeight = 16,
            maxPixels = 256,
            maxCompressedBytes = maxCompressedBytes,
            maxTotalCompressedBytes = maxTotalCompressedBytes,
            maxDecodedBytes = 1_024,
            maxTotalDecodedBytes = maxTotalDecodedBytes,
        ),
        schemaVersion = schemaVersion,
    )

    private fun sbixTable(
        glyphCount: Int,
        vararg strikes: SbixStrikeSpec,
        version: Int = 1,
        flags: Int = 0x01,
    ): ByteArray {
        val headerLength = SBIT_HEADER_BASE_LENGTH + strikes.size * 4
        val strikeLengths = strikes.map { strike ->
            val strikeHeaderLength = STRIKE_HEADER_BASE_LENGTH + (glyphCount + 1) * 4
            strikeHeaderLength + strike.glyphs.take(glyphCount).sumOf { glyph ->
                glyph?.let { GLYPH_RECORD_HEADER_LENGTH + it.payload.size } ?: 0
            }
        }
        val table = ByteArray(headerLength + strikeLengths.sum())
        table.writeUInt16(0, version)
        table.writeUInt16(2, flags)
        table.writeUInt32(4, strikes.size.toUInt())
        var strikeOffset = headerLength
        strikes.forEachIndexed { index, strike ->
            table.writeUInt32(SBIT_HEADER_BASE_LENGTH + index * 4, strikeOffset.toUInt())
            table.writeUInt16(strikeOffset, strike.ppem)
            table.writeUInt16(strikeOffset + 2, RESOLUTION_72)
            var dataOffset = STRIKE_HEADER_BASE_LENGTH + (glyphCount + 1) * 4
            repeat(glyphCount) { glyph ->
                table.writeUInt32(strikeOffset + STRIKE_HEADER_BASE_LENGTH + glyph * 4, dataOffset.toUInt())
                val spec = strike.glyphs.getOrNull(glyph)
                if (spec != null) {
                    val recordOffset = strikeOffset + dataOffset
                    table.writeInt16(recordOffset, spec.originX)
                    table.writeInt16(recordOffset + 2, spec.originY)
                    spec.graphicType.encodeToByteArray().copyInto(table, recordOffset + 4)
                    spec.payload.copyInto(table, recordOffset + 8)
                    dataOffset += GLYPH_RECORD_HEADER_LENGTH + spec.payload.size
                }
            }
            table.writeUInt32(strikeOffset + STRIKE_HEADER_BASE_LENGTH + glyphCount * 4, dataOffset.toUInt())
            strikeOffset += strikeLengths[index]
        }
        return table
    }

    private fun value(result: FontOperationResult<SbixData>): SbixData =
        assertIs<FontOperationResult.Success<SbixData>>(result).value

    private fun decoded(result: FontOperationResult<BitmapGlyphIR>): BitmapGlyphIR =
        assertIs<FontOperationResult.Success<BitmapGlyphIR>>(result).value

    private fun error(result: FontOperationResult<SbixData>): FontError =
        assertIs<FontOperationResult.Failure>(result).error

    private fun decodeError(result: FontOperationResult<BitmapGlyphIR>): FontError =
        assertIs<FontOperationResult.Failure>(result).error
}

private class SbixStrikeSpec(
    val ppem: Int,
    val glyphs: List<SbixGlyphSpec?>,
)

private class SbixGlyphSpec(
    val originX: Int,
    val originY: Int,
    val graphicType: String,
    val payload: ByteArray,
)

private fun pngGlyph(originX: Int = 0, originY: Int = 0): SbixGlyphSpec =
    SbixGlyphSpec(originX, originY, "png ", RGBA_2X2_BYTES)

private fun dupeGlyph(target: Int, originX: Int = 0, originY: Int = 0): SbixGlyphSpec =
    SbixGlyphSpec(originX, originY, "dupe", byteArrayOf((target ushr 8).toByte(), target.toByte()))

private fun rgba2x2Pixels(): ByteArray = byteArrayOf(
    255.toByte(), 0, 0, 255.toByte(),
    0, 255.toByte(), 0, 128.toByte(),
    0, 0, 255.toByte(), 64,
    255.toByte(), 255.toByte(), 255.toByte(), 0,
)

private const val RGBA_2X2 =
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAF0lEQVR42mP4z8DwHwgbGIC0w////xkAQBgHul5CkSMAAAAASUVORK5CYII="

private val RGBA_2X2_BYTES: ByteArray = Base64.decode(RGBA_2X2)

private const val SBIT_HEADER_BASE_LENGTH = 8
private const val STRIKE_HEADER_BASE_LENGTH = 4
private const val GLYPH_OFFSET_LENGTH = 4
private const val GLYPH_RECORD_HEADER_LENGTH = 8
private const val RESOLUTION_72 = 72

private fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.writeInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.writeUInt32(offset: Int, value: UInt) {
    this[offset] = (value shr 24).toByte()
    this[offset + 1] = (value shr 16).toByte()
    this[offset + 2] = (value shr 8).toByte()
    this[offset + 3] = value.toByte()
}
