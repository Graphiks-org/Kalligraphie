package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapResourceLimit
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphRepresentationProfile
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.MultiFontEditableLineRequest
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.layout.ExactEditableLineLayouter
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class EbdtFormatOneGlyphRepresentationTest {
    @Test
    fun doesNotAdvertiseBitmapCapabilityWhenTheEbdtHeaderUsesAnUnsupportedVersion() {
        val corrupted = fixtureBytes().also { bytes ->
            bytes[tableOffset(bytes, "EBDT") + 3] = 1
        }

        val catalog = success(Kalligraphie.embedded(corrupted, FontSourceProvenance("Unsupported EBDT header")))

        assertFalse(catalog.faces.single().capabilities.bitmap)
    }

    @Test
    fun doesNotAdvertiseBitmapCapabilityWhenAnEblcSubtableUsesAnUnsupportedFormat() {
        val corrupted = fixtureBytes().also { bytes ->
            val indexFormatOffset = firstEblcIndexSubtableOffset(bytes)
            bytes[indexFormatOffset] = 0
            bytes[indexFormatOffset + 1] = 2
        }

        val catalog = success(Kalligraphie.embedded(corrupted, FontSourceProvenance("Unsupported EBLC index format")))

        assertFalse(catalog.faces.single().capabilities.bitmap)
    }

    @Test
    fun decodesSkiaEbdtFormatOneGrinningFaceAtTheExplicitSixteenPixelStrike() {
        materializationCachePolicies().forEach { cachePolicy ->
            val catalog = success(
                Kalligraphie.embedded(
                    sources = listOf(
                        org.graphiks.kalligraphie.api.FontSource(
                            fixtureBytes(), FontSourceProvenance("Skia EBDT format 1"),
                        ),
                        org.graphiks.kalligraphie.api.FontSource(
                            checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf"))
                                .use { it.readBytes() },
                            FontSourceProvenance("Liberation Sans Regular"),
                        ),
                    ),
                    cachePolicy = cachePolicy,
                ),
            )
            val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
            val resolver = success(catalog.openAssetResolver())
            val face = success(catalog.resolveFace(catalog.faces[0].id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

            try {
                val glyph = success(instance.resolveGlyph(0x1F600)).glyphId
                assertEquals(GlyphId(3), glyph)
                val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
                try {
                    val bitmap = assertIs<GlyphRepresentation.Bitmap>(success(asset.resolveGlyph(FontGlyphRequest(glyph)))).bitmap
                    val warmBitmap = assertIs<GlyphRepresentation.Bitmap>(success(asset.resolveGlyph(FontGlyphRequest(glyph)))).bitmap
                    repeat(5) { index ->
                        val pressureRequirements = FontAccessRequirementsSnapshot.renderable(
                            listOf(bitmapProfile(maxSourceTableBytes = 16_384 + index + 1)),
                        )
                        val pressureAsset = success(
                            instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, pressureRequirements),
                        )
                        try {
                            assertIs<GlyphRepresentation.Bitmap>(success(pressureAsset.resolveGlyph(FontGlyphRequest(glyph))))
                        } finally {
                            pressureAsset.close()
                        }
                    }
                    val outlineRequirements = FontAccessRequirementsSnapshot.renderable(
                        org.graphiks.kalligraphie.api.OutlineProfile(
                            maxBytes = 1_000_000, maxContours = 256, maxPoints = 16_384,
                            maxCompositeDepth = 8, maxCompositeComponents = 256,
                        ),
                    )
                    val outlineFace = success(catalog.resolveFace(catalog.faces[1].id, outlineRequirements))
                    val outlineInstance = success(outlineFace.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
                    val outlineAsset = success(
                        outlineInstance.acquireRenderAsset(resolver, FontRenderVariantKey.default, outlineRequirements),
                    )
                    try {
                        for (codePoint in listOf('A'.code, '$'.code, 'Ä'.code, 'A'.code)) {
                            val glyphId = success(outlineInstance.resolveGlyph(codePoint)).glyphId
                            val outline = assertIs<GlyphRepresentation.Outline>(
                                success(outlineAsset.resolveGlyph(FontGlyphRequest(glyphId))),
                            ).outline
                            assertEquals(2048, outline.unitsPerEm)
                            if (codePoint == 'A'.code) {
                                assertEquals(36, outline.glyphId)
                                assertEquals(4, outline.bounds.minX)
                                assertEquals(1362, outline.bounds.maxX)
                                assertEquals(1409, outline.bounds.maxY)
                            }
                        }
                    } finally {
                        outlineAsset.close()
                    }
                    val afterPressureBitmap = assertIs<GlyphRepresentation.Bitmap>(success(asset.resolveGlyph(FontGlyphRequest(glyph)))).bitmap

                    for (resolved in listOf(bitmap, warmBitmap, afterPressureBitmap)) assertBitmap(resolved)
                    assertEquals(bitmap, warmBitmap)
                    assertEquals(bitmap, afterPressureBitmap)
                } finally {
                    asset.close()
                }
            } finally {
                resolver.close()
            }
        }
    }

    @Test
    fun reportsAnUnavailableBitmapInsteadOfPublishingAnEmptyGlyphForAnOmittedStrikeRecord() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val failure = assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(GlyphId(1))))
                assertIs<FontError.GlyphRepresentationUnavailable>(failure.error)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun publishesEmptyForAValidatedBitmapRecordWhoseAlphaPixelsAreAllZero() {
        val catalog = success(Kalligraphie.embedded(transparentGrinningFaceFixtureBytes(), FontSourceProvenance("Transparent Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                assertEquals(GlyphRepresentation.Empty, success(asset.resolveGlyph(FontGlyphRequest(GlyphId(3)))))
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsTheWholeSelectedStrikeBeforePublishingAnAssetWhenItsPixelsExceedTheProfileLimit() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(
            listOf(bitmapProfile(maxWidth = 12)),
        )
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)
            assertEquals(BitmapResourceLimit.WIDTH, error.limit)
            assertEquals(13, error.observed)
            assertEquals(12, error.maximum)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun reopensTheExactBitmapAssetAfterTheOriginalHandleCloses() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val original = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            val key = original.key
            original.close()

            val reopened = success(resolver.reopen(key))
            try {
                val bitmap = assertIs<GlyphRepresentation.Bitmap>(
                    success(reopened.resolveGlyph(FontGlyphRequest(GlyphId(3)))),
                ).bitmap
                assertBitmap(bitmap)
            } finally {
                reopened.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsTheWholeBitmapTableBeforePublishingAnAssetWhenItsSourceBytesExceedTheProfileLimit() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile(maxSourceTableBytes = 1)))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val result = instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements)
            val error = assertIs<FontError.BitmapResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
            assertEquals(BitmapResourceLimit.SOURCE_TABLE_BYTES, error.limit)
            assertEquals(4_410, error.observed)
            assertEquals(1, error.maximum)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsTheSelectedStrikeBeforePublishingAnAssetWhenAggregateRecordsExceedTheProfileLimit() {
        val catalog = success(Kalligraphie.embedded(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile(maxRecordCount = 0)))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))

        try {
            val result = instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements)
            val error = assertIs<FontError.BitmapResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
            assertEquals(BitmapResourceLimit.RECORD_COUNT, error.limit)
            assertEquals(4, error.observed)
            assertEquals(0, error.maximum)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun aBitmapSourceBoundBreachDoesNotFallBackToAnotherFaceOrProfile() {
        val bitmapSource = FontSource(fixtureBytes(), FontSourceProvenance("Skia EBDT format 1"))
        val fallbackSource = FontSource(
            checkNotNull(javaClass.getResourceAsStream("/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf"))
                .use { it.readBytes() },
            FontSourceProvenance("Emoji Two COLR v0"),
        )
        val catalog = success(Kalligraphie.embedded(listOf(bitmapSource, fallbackSource)))
        val bitmapFace = FontFaceId(bitmapSource.id, 0)
        val fallbackFace = FontFaceId(fallbackSource.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = catalog.generation,
            policyId = "bitmap-breach-no-fallback",
            version = "1",
            candidates = listOf(FontResolutionCandidate(bitmapFace), FontResolutionCandidate(fallbackFace)),
            lastResortFace = fallbackFace,
        )
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("\uD83D\uDE00".toCharArray())),
        ).snapshot
        val analysis = JvmUnicodeAnalyzer.create().analyze(
            snapshot,
            UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, "en"),
        )
        val resolver = success(catalog.openAssetResolver())
        val backend = success(JvmHarfBuzzShapingBackend.open())
        try {
            val fallbackCapable = layout(
                snapshot, analysis, catalog, policy, backend, resolver, listOf(outlineProfile()),
            )
            val fallbackLine = assertIs<EditableLineResult.Success>(fallbackCapable).line
            assertEquals(listOf(fallbackFace), fallbackLine.positionedGlyphRuns.map { it.fontInstanceKey.face })

            val breached = layout(
                snapshot, analysis, catalog, policy, backend, resolver,
                listOf(bitmapProfile(maxSourceTableBytes = 1), outlineProfile()),
            )
            val failure = assertIs<EditableLineResult.Failure>(breached)
            val fontError = assertIs<EditableLineError.FontResolutionFailure>(failure.error).fontError
            val error = assertIs<FontError.BitmapResourceLimitExceeded>(fontError)
            assertEquals(BitmapResourceLimit.SOURCE_TABLE_BYTES, error.limit)
            assertEquals(4_410, error.observed)
            assertEquals(1, error.maximum)
        } finally {
            backend.close()
            resolver.close()
        }
    }

    private fun layout(
        snapshot: TextSnapshot,
        analysis: UnicodeAnalysis,
        catalog: FontCatalogSnapshot,
        policy: FontResolutionPolicySnapshot,
        backend: ShapingBackend,
        resolver: FontAssetResolverHandle,
        profiles: List<GlyphRepresentationProfile>,
    ): EditableLineResult = ExactEditableLineLayouter.layout(
        MultiFontEditableLineRequest(
            snapshot = snapshot,
            unicodeAnalysis = analysis,
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(16f)),
            shapingBackend = backend,
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
            materialization = EditableLineMaterialization.Renderable(
                resolver = resolver,
                renderVariant = FontRenderVariantSnapshot.default,
                requirements = FontAccessRequirementsSnapshot.renderable(profiles),
            ),
        ),
    )

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 1_024,
        maxPoints = 65_536,
        maxCompositeDepth = 16,
        maxCompositeComponents = 256,
    )

    private fun assertBitmap(bitmap: BitmapGlyphIR) {
        assertEquals(BitmapStrike(16, 16, 1), bitmap.strike)
        assertEquals(13, bitmap.width)
        assertEquals(13, bitmap.height)
        assertEquals(0, bitmap.originX)
        assertEquals(13, bitmap.originY)
        assertEquals(12, bitmap.metrics.advanceX)
        assertEquals(0, bitmap.metrics.advanceY)
        assertEquals(BitmapPixelFormat.ALPHA_8, bitmap.pixelFormat)
        assertEquals(GlyphColorSpace.SRGB, bitmap.colorSpace)
        assertContentEquals(expectedPixels(), bitmap.copyDecodedPixels())
    }

    private fun bitmapProfile(
        maxWidth: Int = 16,
        maxSourceTableBytes: Int = 16_384,
        maxRecordCount: Int = 16,
    ): BitmapProfile = BitmapProfile(
        strike = BitmapStrike(16, 16, 1),
        acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = 3,
            maxIndexSubtables = 16,
            maxRecordCount = maxRecordCount,
            maxIndexTableBytes = 16_384,
            maxSourceTableBytes = maxSourceTableBytes,
            maxWidth = maxWidth,
            maxHeight = 16,
            maxPixels = 256,
            maxCompressedBytes = 64,
            maxTotalCompressedBytes = 1_024,
            maxDecodedBytes = 256,
            maxTotalDecodedBytes = 1_024,
        ),
    )

    private fun expectedPixels(): ByteArray =
        listOf(
            ".............",
            "....#####....",
            "..#########..",
            ".##########..",
            ".###########.",
            ".###########.",
            "############.",
            ".###########.",
            ".###########.",
            ".###########.",
            "..#########..",
            "...#######...",
            ".....##......",
        ).flatMap { row -> row.map { pixel -> if (pixel == '#') 255.toByte() else 0 } }.toByteArray()

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/skia-ebdt-format1/ebdt_fmt1.ttf")) {
            "Skia EBDT format 1 fixture is missing"
        }.use { input -> input.readBytes() }

    private fun transparentGrinningFaceFixtureBytes(): ByteArray = fixtureBytes().also { font ->
        val eblcOffset = tableOffset(font, "EBLC")
        val ebdtOffset = tableOffset(font, "EBDT")
        val indexSubtableArrayOffset = readUInt32(font, eblcOffset + 8)
        val additionalOffset = readUInt32(font, eblcOffset + indexSubtableArrayOffset + 4)
        val subtableOffset = eblcOffset + indexSubtableArrayOffset + additionalOffset
        val firstGlyph = readUInt16(font, subtableOffset + 8)
        val glyphOffset = 3 - firstGlyph
        val imageDataOffset = readUInt32(font, subtableOffset + 4)
        val imageOffsetsOffset = subtableOffset + 8
        val imageOffset = readUInt32(font, imageOffsetsOffset + glyphOffset * 4)
        val nextImageOffset = readUInt32(font, imageOffsetsOffset + (glyphOffset + 1) * 4)
        val packedPixelsStart = ebdtOffset + imageDataOffset + imageOffset + 5
        val packedPixelsEnd = ebdtOffset + imageDataOffset + nextImageOffset
        for (index in packedPixelsStart until packedPixelsEnd) font[index] = 0
    }

    private fun tableOffset(font: ByteArray, tag: String): Int {
        val tableCount = readUInt16(font, 4)
        repeat(tableCount) { index ->
            val recordOffset = 12 + index * 16
            if ((0 until 4).all { tagIndex -> font[recordOffset + tagIndex].toInt().toChar() == tag[tagIndex] }) {
                return readUInt32(font, recordOffset + 8)
            }
        }
        error("Missing $tag table in EBDT fixture.")
    }

    private fun firstEblcIndexSubtableOffset(font: ByteArray): Int {
        val eblcOffset = tableOffset(font, "EBLC")
        val indexSubtableArrayOffset = readUInt32(font, eblcOffset + 8)
        val additionalOffset = readUInt32(font, eblcOffset + indexSubtableArrayOffset + 4)
        return eblcOffset + indexSubtableArrayOffset + additionalOffset
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}
