package org.graphiks.kalligraphie

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SbixGlyphRepresentationTest {
    @Test
    fun certifiesTheExactSixteenPixelStrike() {
        val catalog = success(Kalligraphie.embedded(sbixFixtureBytes(), FontSourceProvenance("Skia sbix colour fixture")))
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
                assertEquals(0, bitmap.originX)
                assertEquals(0, bitmap.originY)
                assertEquals(13, bitmap.metrics.advanceX)
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
    fun certifiesTheColourBitmapRouteThroughALineLayout() {
        val catalog = success(Kalligraphie.embedded(sbixFixtureBytes(), FontSourceProvenance("Skia sbix colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val snapshot = Kalligraphie.decodeUtf8(
                version = TextVersion.create(),
                slices = listOf(TextSlice.Utf8("😀".encodeToByteArray())),
            ).snapshot
            val line = assertIs<EditableLineResult.Success>(
                JvmEditableLineFacade.layout(
                    JvmEditableLineFacadeRequest(
                        snapshot = snapshot,
                        font = instance,
                        baseDirection = BaseDirection.LEFT_TO_RIGHT,
                        language = "en",
                        featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                        features = emptyList(),
                        verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                        materialization = EditableLineMaterialization.Renderable(
                            resolver = resolver,
                            renderVariant = FontRenderVariantSnapshot.default,
                            requirements = requirements,
                        ),
                        cancellationToken = CancellationToken.none,
                    ),
                ),
            ).line
            val glyph = line.positionedGlyphRuns.single().glyphs.single()
            val certificate = checkNotNull(glyph.materializationCertificate)

            assertEquals(GlyphId(3), glyph.shapedGlyph.glyphId)
            assertEquals(GlyphMaterializationRoute.BITMAP, certificate.route)
            assertEquals(glyph.shapedGlyph.glyphId, certificate.glyphId)
            assertEquals(BitmapStrike(16, 16, 32), assertIs<BitmapProfile>(certificate.assetKey.representationProfile).strike)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun certifiesTheExactOneTwentyEightPixelStrike() {
        val catalog = success(Kalligraphie.embedded(sbixFixtureBytes(), FontSourceProvenance("Skia sbix colour fixture")))
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
                assertEquals(0, bitmap.originX)
                assertEquals(0, bitmap.originY)
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
    fun refusesAnAbsentStrikeWithoutSubstitution() {
        val catalog = success(Kalligraphie.embedded(sbixFixtureBytes(), FontSourceProvenance("Skia sbix colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(24)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(24f))))
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )
            val error = assertIs<FontError.UnsupportedRepresentationProfile>(failure.error)

            assertEquals(FontDiagnosticLocation.Table("sbix"), error.location)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun matchesAnIndependentPngOracle() {
        val catalog = success(Kalligraphie.embedded(sbixFixtureBytes(), FontSourceProvenance("Skia sbix colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val bitmap = bitmap(asset, GlyphId(0))
                val encoded = glyphPng(sbixFixtureBytes(), ppem = 16, glyphId = 0)
                assertTrue(encoded.isNotEmpty())
                val image = assertNotNull(ImageIO.read(ByteArrayInputStream(encoded)))
                assertEquals(bitmap.width, image.width)
                assertEquals(bitmap.height, image.height)
                assertContentEquals(oracleRgba(image), bitmap.copyDecodedPixels(), "ImageIO PNG oracle pixels")
                assertTrue(bitmap.copyDecodedPixels().distinct().size > 4)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun preservesTheDecodedPixelsUnderCachePressure() {
        val uncached = resolveSbixGlyph0(FontMaterializationCachePolicy.disabled)
        val tinyBudget = resolveSbixGlyph0(FontMaterializationCachePolicy(maxEvictableBytesPerFace = 1L))
        val (warm, afterPressure) = resolveSbixGlyph0BeforeAndAfterPressure(
            FontMaterializationCachePolicy(maxEvictableBytesPerFace = 6_000L),
        )

        assertEquals(uncached, tinyBudget)
        assertEquals(uncached, warm)
        assertEquals(uncached, afterPressure)
        assertEquals(warm, afterPressure)
        assertContentEquals(uncached.copyDecodedPixels(), afterPressure.copyDecodedPixels())

        materializationCachePolicies().forEach { cachePolicy ->
            val (policyWarm, policyAfterPressure) = resolveSbixGlyph0BeforeAndAfterPressure(cachePolicy)
            assertEquals(policyWarm, policyAfterPressure)
            assertContentEquals(policyWarm.copyDecodedPixels(), policyAfterPressure.copyDecodedPixels())
        }
    }

    @Test
    fun keepsCancellationAndClosureSemantics() {
        val catalog = success(Kalligraphie.embedded(sbixFixtureBytes(), FontSourceProvenance("Skia sbix colour fixture")))
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
    fun reopensTheExactColourAssetAfterTheOriginalHandleCloses() {
        val catalog = success(Kalligraphie.embedded(sbixFixtureBytes(), FontSourceProvenance("Skia sbix colour fixture")))
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
        val catalog = success(Kalligraphie.embedded(sbixFixtureBytes(), FontSourceProvenance("Skia sbix colour fixture")))
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
    fun doesNotAdvertiseBitmapsWhenAnUnselectedStrikeIsMalformed() {
        // Regression pin: the conservative all-strikes rule predates the one-pass scan; this
        // proves the rule end to end rather than a red-at-base behaviour change.
        val clean = success(
            Kalligraphie.embedded(sbixFixtureBytes(), FontSourceProvenance("Skia sbix colour fixture")),
        )
        assertTrue(clean.faces.single().capabilities.bitmap)

        val patched = withMalformedSbixStrikeGraphicType(sbixFixtureBytes(), ppem = 128)
        val catalog = success(
            Kalligraphie.embedded(patched, FontSourceProvenance("Skia sbix fixture with a malformed unselected strike")),
        )

        assertFalse(catalog.faces.single().capabilities.bitmap)
    }

    @Test
    fun certifiesSbixWithoutAUsableGlyfOrLocaTable() {
        // Red at the reviewed base: the advance provider used the glyf-backed metrics path, so
        // removing outline tables withdrew the sbix route. Advances now come from hmtx alone.
        val patched = withoutTables(sbixFixtureBytes(), "glyf", "loca")
        val catalog = success(
            Kalligraphie.embedded(patched, FontSourceProvenance("Skia sbix fixture without outline tables")),
        )

        assertFalse(catalog.faces.single().capabilities.outline)
        assertTrue(catalog.faces.single().capabilities.bitmap)

        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())
        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val bitmap = bitmap(asset, GlyphId(0))

                assertEquals(11, bitmap.width)
                assertEquals(13, bitmap.height)
                assertEquals(13, bitmap.metrics.advanceX)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun withdrawsTheSbixRouteWhenHmtxIsTooShortToResolveAdvances() {
        val tables = readSfntTables(sbixFixtureBytes())
        val patched = assembleSfnt(tables + ("hmtx" to tables.getValue("hmtx").copyOf(2)))
        val catalog = success(
            Kalligraphie.embedded(patched, FontSourceProvenance("Skia sbix fixture with a truncated hmtx")),
        )

        assertFalse(catalog.faces.single().capabilities.bitmap)
    }

    @Test
    fun withdrawsTheSbixRouteWhenNumberOfHMetricsIsZero() {
        val patched = withHheaNumberOfHMetrics(sbixFixtureBytes(), 0)
        val catalog = success(
            Kalligraphie.embedded(patched, FontSourceProvenance("Skia sbix fixture with zero horizontal metrics")),
        )

        assertFalse(catalog.faces.single().capabilities.bitmap)
    }

    @Test
    fun rejectsAFontWhoseRequiredHmtxTableIsMissingBeforeCapabilityDiscovery() {
        val result = Kalligraphie.embedded(
            withoutTables(sbixFixtureBytes(), "hmtx"),
            FontSourceProvenance("Skia sbix fixture without hmtx"),
        )

        assertIs<FontOperationResult.Failure>(result)
    }

    @Test
    fun prefersCbdtOverSbixWhenBothRoutesCertify() {
        val composite = compositeFixtureBytes()
        val tables = readSfntTables(composite)
        assertTrue(tables.keys.containsAll(listOf("sbix", "CBLC", "CBDT")))
        val catalog = success(
            Kalligraphie.embedded(composite, FontSourceProvenance("Skia CBDT over sbix composite")),
        )
        assertTrue(catalog.faces.single().capabilities.bitmap)
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
        val resolver = success(catalog.openAssetResolver())

        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val bitmap = bitmap(asset, GlyphId(0))

                assertEquals(BitmapStrike(16, 16, 32), bitmap.strike)
                assertEquals(11, bitmap.width)
                assertEquals(13, bitmap.height)
                assertEquals(1, bitmap.originX)
                assertEquals(13, bitmap.originY)
                assertEquals(12, bitmap.metrics.advanceX)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun doesNotFallThroughToSbixWhenTheSelectedRouteCannotCertify() {
        val patched = withFirstCblcStrikePpem(compositeFixtureBytes(), ppem = 24)
        val catalog = success(
            Kalligraphie.embedded(patched, FontSourceProvenance("Skia composite with an unavailable CBDT strike")),
        )
        assertTrue(catalog.faces.single().capabilities.bitmap)
        val resolver = success(catalog.openAssetResolver())

        try {
            val certifyingRequirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(24)))
            val certifyingFace = success(catalog.resolveFace(catalog.faces.single().id, certifyingRequirements))
            val certifyingInstance = success(certifyingFace.instantiate(FontInstanceDescriptor(LayoutUnit(24f))))
            val certifyingAsset = success(
                certifyingInstance.acquireRenderAsset(resolver, FontRenderVariantKey.default, certifyingRequirements),
            )
            try {
                val bitmap = bitmap(certifyingAsset, GlyphId(0))

                assertEquals(BitmapStrike(24, 24, 32), bitmap.strike)
                assertEquals(11, bitmap.width)
                assertEquals(13, bitmap.height)
                assertEquals(1, bitmap.originX)
                assertEquals(13, bitmap.originY)
                assertEquals(12, bitmap.metrics.advanceX)
            } finally {
                certifyingAsset.close()
            }

            val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourProfile(16)))
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )
            val error = assertIs<FontError.UnsupportedRepresentationProfile>(failure.error)

            assertEquals(FontDiagnosticLocation.Table("CBLC"), error.location)
        } finally {
            resolver.close()
        }
    }

    private fun resolveSbixGlyph0(cachePolicy: FontMaterializationCachePolicy): BitmapGlyphIR {
        val catalog = success(
            Kalligraphie.embedded(
                sbixFixtureBytes(),
                FontSourceProvenance("Skia sbix colour fixture"),
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

    private fun resolveSbixGlyph0BeforeAndAfterPressure(
        cachePolicy: FontMaterializationCachePolicy,
    ): Pair<BitmapGlyphIR, BitmapGlyphIR> {
        val catalog = success(
            Kalligraphie.embedded(
                sbixFixtureBytes(),
                FontSourceProvenance("Skia sbix colour fixture"),
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
                // One retained 11 x 13 sbix entry charges roughly 5.6 KB against the
                // 6,000-byte per-face budget: the 4,096-byte cache-entry envelope plus a key
                // of about 900 bytes and a result of about 640 bytes. Admitting glyph 2
                // (then glyph 3) therefore evicts the least-recently-used entry, so the final
                // glyph 0 read is a recompute after eviction, not a cache hit.
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

    private fun glyphPng(font: ByteArray, ppem: Int, glyphId: Int): ByteArray {
        val sbix = tableBytes(font, "sbix")
        val glyphCount = readUInt16(tableBytes(font, "maxp"), 4)
        for (index in 0 until readUInt32(sbix, 4)) {
            val strikeOffset = readUInt32(sbix, 8 + index * 4)
            if (readUInt16(sbix, strikeOffset) != ppem) continue
            assertTrue(glyphId in 0 until glyphCount)
            val start = strikeOffset + readUInt32(sbix, strikeOffset + 4 + glyphId * 4)
            val end = strikeOffset + readUInt32(sbix, strikeOffset + 8 + glyphId * 4)
            assertEquals("png ", sbix.decodeToString(start + 4, start + 8))
            return sbix.copyOfRange(start + 8, end)
        }
        error("The fixture has no $ppem ppem strike for glyph $glyphId.")
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
        error("Missing $tag table in the fixture.")
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun sbixFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/skia-sbix/sbix.ttf")) {
            "Skia sbix colour fixture is missing"
        }.use { input -> input.readBytes() }

    private fun cbdtFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/skia-cbdt/cbdt.ttf")) {
            "Skia CBDT colour fixture is missing"
        }.use { input -> input.readBytes() }

    private fun compositeFixtureBytes(): ByteArray = assembleCompositeFont(sbixFixtureBytes(), cbdtFixtureBytes())

    // The assembled copy deviates from a published SFNT in three deliberate ways: table
    // checksums are zeroed, the inherited head.checkSumAdjustment is left stale, and the
    // final table is not padded to a four-byte tail. The production reader validates only
    // tags, offsets, lengths, and required-table ranges, so none of these are inspected.
    // Both fixtures declare four glyphs in maxp, so the copied CBLC glyph range stays inside
    // the assembled face.
    private fun assembleCompositeFont(sbixFont: ByteArray, cbdtFont: ByteArray): ByteArray {
        val tables = LinkedHashMap<String, ByteArray>()
        readSfntTables(sbixFont).forEach { (tag, table) -> tables[tag] = table }
        readSfntTables(cbdtFont).forEach { (tag, table) ->
            if (tag == "CBLC" || tag == "CBDT") tables[tag] = table
        }
        return assembleSfnt(tables)
    }

    private fun assembleSfnt(tables: Map<String, ByteArray>): ByteArray {
        val tags = tables.keys.sorted()
        val headerLength = 12 + tags.size * 16
        val offsets = LinkedHashMap<String, Int>()
        var cursor = headerLength
        for (tag in tags) {
            val aligned = (cursor + 3) and -4
            offsets[tag] = aligned
            cursor = aligned + tables.getValue(tag).size
        }

        val assembled = ByteArray(cursor)
        assembled.writeUInt32(0, 0x00010000)
        assembled.writeUInt16(4, tags.size)
        val searchSpan = Integer.highestOneBit(tags.size) * 16
        assembled.writeUInt16(6, searchSpan)
        assembled.writeUInt16(8, Integer.numberOfTrailingZeros(Integer.highestOneBit(tags.size)))
        assembled.writeUInt16(10, tags.size * 16 - searchSpan)
        tags.forEachIndexed { index, tag ->
            val record = 12 + index * 16
            val table = tables.getValue(tag)
            tag.forEachIndexed { position, character -> assembled[record + position] = character.code.toByte() }
            assembled.writeUInt32(record + 4, 0)
            assembled.writeUInt32(record + 8, offsets.getValue(tag))
            assembled.writeUInt32(record + 12, table.size)
            table.copyInto(assembled, destinationOffset = offsets.getValue(tag))
        }
        return assembled
    }

    private fun readSfntTables(font: ByteArray): Map<String, ByteArray> {
        val tableCount = readUInt16(font, 4)
        val tables = LinkedHashMap<String, ByteArray>(tableCount)
        repeat(tableCount) { index ->
            val record = 12 + index * 16
            val tag = font.decodeToString(record, record + 4)
            val offset = readUInt32(font, record + 8)
            val length = readUInt32(font, record + 12)
            tables[tag] = font.copyOfRange(offset, offset + length)
        }
        return tables
    }

    private fun withoutTables(font: ByteArray, vararg tags: String): ByteArray {
        val tables = LinkedHashMap(readSfntTables(font))
        tags.forEach { tables.remove(it) }
        return assembleSfnt(tables)
    }

    private fun withHheaNumberOfHMetrics(font: ByteArray, numberOfHMetrics: Int): ByteArray {
        val patched = font.copyOf()
        val hhea = tableOffset(patched, "hhea")
        patched.writeUInt16(hhea + 34, numberOfHMetrics)
        return patched
    }

    private fun withMalformedSbixStrikeGraphicType(font: ByteArray, ppem: Int): ByteArray {
        val patched = font.copyOf()
        val sbix = tableOffset(patched, "sbix")
        val strikeCount = readUInt32(patched, sbix + 4)
        repeat(strikeCount) { index ->
            val strikeOffset = sbix + readUInt32(patched, sbix + 8 + index * 4)
            if (readUInt16(patched, strikeOffset) != ppem) return@repeat
            val recordOffset = strikeOffset + readUInt32(patched, strikeOffset + 4)
            "jpg ".forEachIndexed { position, character ->
                patched[recordOffset + 4 + position] = character.code.toByte()
            }
            return patched
        }
        error("The fixture has no $ppem ppem strike.")
    }

    private fun withFirstCblcStrikePpem(font: ByteArray, ppem: Int): ByteArray {
        val patched = font.copyOf()
        val cblc = tableOffset(patched, "CBLC")
        patched[cblc + CBLC_HEADER_BYTES + BITMAP_SIZE_TABLE_PPEM_X_OFFSET] = ppem.toByte()
        patched[cblc + CBLC_HEADER_BYTES + BITMAP_SIZE_TABLE_PPEM_Y_OFFSET] = ppem.toByte()
        return patched
    }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

// Byte offsets inside the 48-byte CBLC BitmapSize table, following the 8-byte CBLC header.
private const val CBLC_HEADER_BYTES = 8
private const val BITMAP_SIZE_TABLE_PPEM_X_OFFSET = 44
private const val BITMAP_SIZE_TABLE_PPEM_Y_OFFSET = 45
