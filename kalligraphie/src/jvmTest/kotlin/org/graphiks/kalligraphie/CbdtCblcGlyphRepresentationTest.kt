package org.graphiks.kalligraphie

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CbdtCblcGlyphRepresentationTest {
    @Test
    fun certifiesTheExactSixteenPixelColourStrike() {
        val catalog = success(Kalligraphie.embedded(cbdtFixtureBytes(), FontSourceProvenance("Skia CBDT colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val bitmap = bitmap(asset, GlyphId(0))

                assertEquals(GlyphId(0), bitmap.glyphId)
                assertEquals(BitmapStrike(16, 16, 32), bitmap.strike)
                assertEquals(11, bitmap.width)
                assertEquals(13, bitmap.height)
                assertEquals(1, bitmap.originX)
                assertEquals(13, bitmap.originY)
                assertEquals(12, bitmap.metrics.advanceX)
                assertEquals(0, bitmap.metrics.advanceY)
                assertEquals(BitmapPixelFormat.RGBA_8888, bitmap.pixelFormat)
                assertEquals(GlyphColorSpace.SRGB, bitmap.colorSpace)
                assertEquals(11 * 13 * 4, bitmap.decodedByteCount)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun certifiesTheExactOneTwentyEightPixelStrike() {
        val catalog = success(Kalligraphie.embedded(cbdtFixtureBytes(), FontSourceProvenance("Skia CBDT colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(128)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(128f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val bitmap = bitmap(asset, GlyphId(0))

                assertEquals(BitmapStrike(128, 128, 32), bitmap.strike)
                assertEquals(78, bitmap.width)
                assertEquals(103, bitmap.height)
                assertEquals(12, bitmap.originX)
                assertEquals(103, bitmap.originY)
                assertEquals(102, bitmap.metrics.advanceX)
                assertEquals(78 * 103 * 4, bitmap.decodedByteCount)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun decodesARealFontWithMultiIdatRecordsAndNegativeBearings() {
        val catalog = success(Kalligraphie.embedded(planetFixtureBytes(), FontSourceProvenance("Skia planet CBDT colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(
            listOf(
                colourProfile(
                    ppem = 16,
                    maxDimension = 256,
                    maxSourceTableBytes = 131_072,
                    maxTotalCompressedBytes = 131_072,
                ),
            ),
        )
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val bitmap = bitmap(asset, GlyphId(7))

                assertEquals(BitmapStrike(16, 16, 32), bitmap.strike)
                assertEquals(230, bitmap.width)
                assertEquals(245, bitmap.height)
                assertEquals(-24, bitmap.originX)
                assertEquals(120, bitmap.originY)
                assertEquals(208, bitmap.metrics.advanceX)
                assertEquals(BitmapPixelFormat.RGBA_8888, bitmap.pixelFormat)

                val encoded = glyphPng(planetFixtureBytes(), ppem = 16, glyphId = 7)
                assertTrue(encoded.isNotEmpty())
                assertTrue(idatChunkCount(encoded) > 1, "The planet fixture record spans multiple IDAT chunks.")
                val image = assertNotNull(ImageIO.read(ByteArrayInputStream(encoded)))
                assertEquals(bitmap.width, image.width)
                assertEquals(bitmap.height, image.height)
                assertContentEquals(oracleRgba(image), bitmap.copyDecodedPixels(), "ImageIO PNG oracle pixels")
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun reopensTheExactColourAssetAfterTheOriginalHandleCloses() {
        val catalog = success(Kalligraphie.embedded(cbdtFixtureBytes(), FontSourceProvenance("Skia CBDT colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val original = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            val key = original.key
            original.close()

            val reopened = success(resolver.reopen(key))
            try {
                val bitmap = bitmap(reopened, GlyphId(0))

                assertEquals(BitmapStrike(16, 16, 32), bitmap.strike)
                assertEquals(11, bitmap.width)
                assertEquals(13, bitmap.height)
            } finally {
                reopened.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun detachesTheColourAssetAndStillResolvesTheSamePixels() {
        val catalog = success(Kalligraphie.embedded(cbdtFixtureBytes(), FontSourceProvenance("Skia CBDT colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            val original = bitmap(asset, GlyphId(0))
            val originalPixels = original.copyDecodedPixels()
            val detached = success(asset.detach())
            try {
                asset.close()
                resolver.close()

                val deferred = bitmap(detached, GlyphId(0))

                assertEquals(original.glyphId, deferred.glyphId)
                assertEquals(original.strike, deferred.strike)
                assertEquals(original.width, deferred.width)
                assertEquals(original.height, deferred.height)
                assertEquals(original.originX, deferred.originX)
                assertEquals(original.originY, deferred.originY)
                assertEquals(original.metrics.advanceX, deferred.metrics.advanceX)
                assertEquals(original.metrics.advanceY, deferred.metrics.advanceY)
                assertEquals(original.pixelFormat, deferred.pixelFormat)
                assertEquals(original.colorSpace, deferred.colorSpace)
                assertContentEquals(originalPixels, deferred.copyDecodedPixels())
            } finally {
                detached.close()
            }

            assertIs<FontError.ResourceClosed>(
                assertIs<FontOperationResult.Failure>(
                    detached.resolveGlyph(FontGlyphRequest(GlyphId(0))),
                ).error,
            )
        } finally {
            resolver.close()
        }
    }

    @Test
    fun refusesAnAbsentStrikeWithoutSubstitution() {
        val catalog = success(Kalligraphie.embedded(cbdtFixtureBytes(), FontSourceProvenance("Skia CBDT colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(24)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            assertIs<FontError.UnsupportedRepresentationProfile>(failure.error)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun matchesAnIndependentPngOracle() {
        val catalog = success(Kalligraphie.embedded(cbdtFixtureBytes(), FontSourceProvenance("Skia CBDT colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val bitmap = bitmap(asset, GlyphId(0))
                val encoded = glyphPng(cbdtFixtureBytes(), ppem = 16, glyphId = 0)
                assertTrue(encoded.isNotEmpty())
                val image = assertNotNull(ImageIO.read(ByteArrayInputStream(encoded)))
                assertEquals(bitmap.width, image.width)
                assertEquals(bitmap.height, image.height)
                assertContentEquals(oracleRgba(image), bitmap.copyDecodedPixels(), "ImageIO PNG oracle pixels")
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun preservesTheDecodedPixelsUnderCachePressure() {
        val uncached = resolveColourGlyph0(FontMaterializationCachePolicy.disabled)
        val tinyBudget = resolveColourGlyph0(FontMaterializationCachePolicy(maxEvictableBytesPerFace = 1L))
        val (warm, afterPressure) = resolveColourGlyph0BeforeAndAfterPressure(
            FontMaterializationCachePolicy(maxEvictableBytesPerFace = 6_000L),
        )

        assertEquals(uncached, tinyBudget)
        assertEquals(uncached, warm)
        assertEquals(uncached, afterPressure)
        assertEquals(warm, afterPressure)
        assertContentEquals(uncached.copyDecodedPixels(), afterPressure.copyDecodedPixels())

        materializationCachePolicies().forEach { cachePolicy ->
            val (policyWarm, policyAfterPressure) = resolveColourGlyph0BeforeAndAfterPressure(cachePolicy)
            assertEquals(policyWarm, policyAfterPressure)
            assertContentEquals(policyWarm.copyDecodedPixels(), policyAfterPressure.copyDecodedPixels())
        }
    }

    @Test
    fun keepsCancellationAndClosureSemantics() {
        val catalog = success(Kalligraphie.embedded(cbdtFixtureBytes(), FontSourceProvenance("Skia CBDT colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                assertIs<FontOperationResult.Cancelled>(
                    asset.resolveGlyph(FontGlyphRequest(GlyphId(0)), CancellationToken.cancelled),
                )

                var beforeDecodeChecks = 0
                assertIs<FontOperationResult.Cancelled>(
                    asset.resolveGlyph(
                        FontGlyphRequest(GlyphId(0)),
                        CancellationToken { beforeDecodeChecks++ >= 1 },
                    ),
                )
                assertTrue(beforeDecodeChecks >= 2)

                var afterDecodeChecks = 0
                assertIs<FontOperationResult.Cancelled>(
                    asset.resolveGlyph(
                        FontGlyphRequest(GlyphId(0)),
                        CancellationToken { afterDecodeChecks++ >= 2 },
                    ),
                )
                assertTrue(afterDecodeChecks >= 3)

                assertIs<FontOperationResult.Cancelled>(
                    instance.acquireRenderAsset(
                        resolver,
                        FontRenderVariantKey.default,
                        requirements,
                        CancellationToken.cancelled,
                    ),
                )

                var acquisitionChecks = 0
                assertIs<FontOperationResult.Cancelled>(
                    instance.acquireRenderAsset(
                        resolver,
                        FontRenderVariantKey.default,
                        requirements,
                        CancellationToken { acquisitionChecks++ >= 1 },
                    ),
                )
                assertTrue(acquisitionChecks >= 2)

                assertEquals(11, bitmap(asset, GlyphId(0)).width)
            } finally {
                asset.close()
            }

            assertIs<FontError.ResourceClosed>(
                assertIs<FontOperationResult.Failure>(
                    asset.resolveGlyph(FontGlyphRequest(GlyphId(0))),
                ).error,
            )

            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
            assertIs<FontError.ResourceClosed>(
                assertIs<FontOperationResult.Failure>(
                    instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
                ).error,
            )
        } finally {
            resolver.close()
        }
    }

    @Test
    fun refusesColourStrikesWhenTheFaceHasNoCbdtRoute() {
        val monochrome = success(Kalligraphie.embedded(ebdtFixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        assertTrue(monochrome.faces.single().capabilities.bitmap)
        val colourRequirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))

        assertIs<FontError.UnsupportedRepresentationProfile>(
            assertIs<FontOperationResult.Failure>(
                monochrome.resolveFace(monochrome.faces.single().id, colourRequirements),
            ).error,
        )

        val colourOnly = success(Kalligraphie.embedded(cbdtFixtureBytes(), FontSourceProvenance("Skia CBDT colour fixture")))
        assertTrue(colourOnly.faces.single().capabilities.bitmap)
        val monochromeRequirements = FontAccessRequirementsSnapshot.renderable(listOf(monochromeBitmapProfile()))

        assertIs<FontError.UnsupportedRepresentationProfile>(
            assertIs<FontOperationResult.Failure>(
                colourOnly.resolveFace(colourOnly.faces.single().id, monochromeRequirements),
            ).error,
        )
    }

    @Test
    fun refusesABitDepthOutsideTheSupportedPair() {
        val catalog = success(Kalligraphie.embedded(cbdtFixtureBytes(), FontSourceProvenance("Skia CBDT colour fixture")))
        val supported = colourProfile(16)
        val unsupported = BitmapProfile(
            strike = BitmapStrike(16, 16, 8),
            acceptedPixelFormats = supported.acceptedPixelFormats,
            acceptedColorSpaces = supported.acceptedColorSpaces,
            limits = supported.limits,
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(unsupported))

        assertIs<FontError.UnsupportedRepresentationProfile>(
            assertIs<FontOperationResult.Failure>(
                catalog.resolveFace(catalog.faces.single().id, requirements),
            ).error,
        )
    }

    @Test
    fun doesNotAdvertiseBitmapCapabilityWhenAStrikeBitDepthIsOutsideTheColourRoute() {
        val corrupted = cbdtFixtureBytes().also { bytes ->
            bytes[tableOffset(bytes, "CBLC") + 8 + 46] = 8
        }

        val catalog = success(
            Kalligraphie.embedded(corrupted, FontSourceProvenance("Unsupported CBLC strike bit depth")),
        )

        assertFalse(catalog.faces.single().capabilities.bitmap)
    }

    private fun resolveColourGlyph0(cachePolicy: FontMaterializationCachePolicy): BitmapGlyphIR {
        val catalog = success(
            Kalligraphie.embedded(
                cbdtFixtureBytes(),
                FontSourceProvenance("Skia CBDT colour fixture"),
                cachePolicy,
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                return bitmap(asset, GlyphId(0))
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun resolveColourGlyph0BeforeAndAfterPressure(
        cachePolicy: FontMaterializationCachePolicy,
    ): Pair<BitmapGlyphIR, BitmapGlyphIR> {
        val catalog = success(
            Kalligraphie.embedded(
                cbdtFixtureBytes(),
                FontSourceProvenance("Skia CBDT colour fixture"),
                cachePolicy,
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                // One retained 11 x 13 colour entry charges roughly 5.7 KB against the
                // 6,000-byte per-face budget: the 4,096-byte cache-entry envelope plus a
                // key of about 950 bytes and a result of about 640 bytes. Admitting glyph
                // 2 (then glyph 3) therefore evicts the least-recently-used entry, so the
                // final glyph 0 read is a recompute after eviction, not a cache hit.
                val warm = bitmap(asset, GlyphId(0))
                bitmap(asset, GlyphId(2))
                bitmap(asset, GlyphId(3))
                val afterPressure = bitmap(asset, GlyphId(0))
                return warm to afterPressure
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun bitmap(asset: FontRenderAssetHandle, glyphId: GlyphId): BitmapGlyphIR =
        assertIs<GlyphRepresentation.Bitmap>(success(asset.resolveGlyph(FontGlyphRequest(glyphId)))).bitmap

    private fun oracleRgba(image: java.awt.image.BufferedImage): ByteArray {
        val pixels = ByteArray(image.width * image.height * 4)
        var offset = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                pixels[offset++] = ((argb ushr 16) and 0xFF).toByte()
                pixels[offset++] = ((argb ushr 8) and 0xFF).toByte()
                pixels[offset++] = (argb and 0xFF).toByte()
                pixels[offset++] = ((argb ushr 24) and 0xFF).toByte()
            }
        }
        return pixels
    }

    private fun colourProfile(
        ppem: Int,
        maxDimension: Int = ppem * 2,
        maxSourceTableBytes: Int = 65_536,
        maxTotalCompressedBytes: Int = 65_536,
    ): BitmapProfile {
        val pixels = maxDimension * maxDimension
        return BitmapProfile(
            strike = BitmapStrike(ppem, ppem, 32),
            acceptedPixelFormats = listOf(BitmapPixelFormat.RGBA_8888),
            acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
            limits = BitmapLimits(
                maxStrikes = 3,
                maxIndexSubtables = 4,
                maxRecordCount = 16,
                maxIndexTableBytes = 16_384,
                maxSourceTableBytes = maxSourceTableBytes,
                maxWidth = maxDimension,
                maxHeight = maxDimension,
                maxPixels = pixels,
                maxCompressedBytes = 65_536,
                maxTotalCompressedBytes = maxTotalCompressedBytes,
                maxDecodedBytes = pixels * 4,
                maxTotalDecodedBytes = pixels * 4 * 4,
            ),
        )
    }

    private fun monochromeBitmapProfile(): BitmapProfile = BitmapProfile(
        strike = BitmapStrike(16, 16, 1),
        acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = 3,
            maxIndexSubtables = 4,
            maxRecordCount = 4,
            maxIndexTableBytes = 16_384,
            maxSourceTableBytes = 16_384,
            maxWidth = 16,
            maxHeight = 16,
            maxPixels = 256,
            maxCompressedBytes = 512,
            maxTotalCompressedBytes = 2_048,
            maxDecodedBytes = 1_024,
            maxTotalDecodedBytes = 2_048,
        ),
    )

    private fun glyphPng(font: ByteArray, ppem: Int, glyphId: Int): ByteArray {
        val cblc = tableBytes(font, "CBLC")
        val cbdt = tableBytes(font, "CBDT")
        for (index in 0 until readUInt32(cblc, 4)) {
            val strike = 8 + index * 48
            if ((cblc[strike + 44].toInt() and 0xFF) != ppem) continue
            assertEquals(32, cblc[strike + 46].toInt() and 0xFF)
            val arrayOffset = readUInt32(cblc, strike)
            for (subtable in 0 until readUInt32(cblc, strike + 8)) {
                val entry = arrayOffset + subtable * 8
                val firstGlyph = readUInt16(cblc, entry)
                val lastGlyph = readUInt16(cblc, entry + 2)
                if (glyphId !in firstGlyph..lastGlyph) continue
                val subtableOffset = arrayOffset + readUInt32(cblc, entry + 4)
                assertEquals(1, readUInt16(cblc, subtableOffset))
                assertEquals(17, readUInt16(cblc, subtableOffset + 2))
                val imageDataOffset = readUInt32(cblc, subtableOffset + 4)
                val glyphOffset = subtableOffset + 8 + (glyphId - firstGlyph) * 4
                val start = imageDataOffset + readUInt32(cblc, glyphOffset)
                val end = imageDataOffset + readUInt32(cblc, glyphOffset + 4)
                val dataLength = readUInt32(cbdt, start + 5)
                assertEquals(end - start, 9 + dataLength)
                assertEquals(0x89, cbdt[start + 9].toInt() and 0xFF)
                assertEquals('P'.code, cbdt[start + 10].toInt() and 0xFF)
                return cbdt.copyOfRange(start + 9, start + 9 + dataLength)
            }
        }
        error("The fixture has no $ppem ppem strike for glyph $glyphId.")
    }

    private fun idatChunkCount(png: ByteArray): Int {
        var count = 0
        var offset = 8
        while (offset <= png.size - 8) {
            val length = readUInt32(png, offset)
            if (png.decodeToString(offset + 4, offset + 8) == "IDAT") count++
            offset += 12 + length
        }
        return count
    }

    private fun tableOffset(font: ByteArray, tag: String): Int = tableRecord(font, tag).first

    private fun tableBytes(font: ByteArray, tag: String): ByteArray {
        val (offset, length) = tableRecord(font, tag)
        return font.copyOfRange(offset, offset + length)
    }

    private fun tableRecord(font: ByteArray, tag: String): Pair<Int, Int> {
        val tableCount = readUInt16(font, 4)
        repeat(tableCount) { index ->
            val record = 12 + index * 16
            if ((0 until 4).all { tagIndex -> (font[record + tagIndex].toInt() and 0xFF).toChar() == tag[tagIndex] }) {
                return readUInt32(font, record + 8) to readUInt32(font, record + 12)
            }
        }
        error("Missing $tag table in the CBDT fixture.")
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun cbdtFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/skia-cbdt/cbdt.ttf")) {
            "Skia CBDT colour fixture is missing"
        }.use { input -> input.readBytes() }

    private fun planetFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/skia-cbdt/planetcbdt.ttf")) {
            "Skia planet CBDT colour fixture is missing"
        }.use { input -> input.readBytes() }

    private fun ebdtFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/skia-ebdt-format1/ebdt_fmt1.ttf")) {
            "Skia EBDT format 1 fixture is missing"
        }.use { input -> input.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}
