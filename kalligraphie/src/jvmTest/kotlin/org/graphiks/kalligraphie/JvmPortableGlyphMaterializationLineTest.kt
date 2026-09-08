package org.graphiks.kalligraphie

import java.util.concurrent.atomic.AtomicInteger
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceMetadata
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OpenTypeFontData
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.VerticalGlyphMetrics
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val SFNT_HEADER_BYTES: Int = 12
private const val SFNT_TABLE_RECORD_BYTES: Int = 16
private const val HEAD_CHECKSUM_ADJUSTMENT_OFFSET: Int = 8
private val OPEN_TYPE_CHECKSUM_MAGIC: UInt = 0xB1B0AFBAu

class JvmPortableGlyphMaterializationLineTest {
    @Test
    fun reusesTheFallbackRouteProofInsteadOfResolvingAnUnchangedGlyphTwice() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val fixture = openFixture(
            bytes = resourceBytes("/fonts/bungee-color/BungeeColor-Regular.ttf"),
            provenance = "Bungee Color Regular",
            requirements = requirements,
            layoutSize = 1_000f,
        )
        val countingCatalog = CountingFontCatalog(fixture.catalog)
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf('A'.code.toByte()))),
        ).snapshot

        try {
            val line = paragraphLine(
                snapshot = snapshot,
                fixture = fixture,
                renderVariant = FontRenderVariantSnapshot.default,
                requirements = requirements,
                fontCatalog = countingCatalog,
            )

            assertEquals(GlyphId(43), line.positionedGlyphRuns.single().glyphs.single().shapedGlyph.glyphId)
            assertEquals(1, countingCatalog.resolvedGlyphCount.get())
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun certifiesTwoCpalPaletteSelectionsWithoutChangingShapingOrLineGeometry() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val fixture = openFixture(
            bytes = resourceBytes("/fonts/bungee-color/BungeeColor-Regular.ttf"),
            provenance = "Bungee Color Regular",
            requirements = requirements,
            layoutSize = 1_000f,
        )
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf('A'.code.toByte()))),
        ).snapshot

        try {
            val paletteZero = paragraphLine(snapshot, fixture, FontRenderVariantSnapshot(cpalPaletteIndex = 0), requirements)
            val paletteOne = paragraphLine(snapshot, fixture, FontRenderVariantSnapshot(cpalPaletteIndex = 1), requirements)
            val zeroGlyph = paletteZero.positionedGlyphRuns.single().glyphs.single()
            val oneGlyph = paletteOne.positionedGlyphRuns.single().glyphs.single()
            val zeroCertificate = checkNotNull(zeroGlyph.materializationCertificate)
            val oneCertificate = checkNotNull(oneGlyph.materializationCertificate)

            assertEquals(GlyphId(43), zeroGlyph.shapedGlyph.glyphId)
            assertEquals(zeroGlyph.shapedGlyph.glyphId, oneGlyph.shapedGlyph.glyphId)
            assertEquals(zeroGlyph.origin, oneGlyph.origin)
            assertEquals(zeroGlyph.advance, oneGlyph.advance)
            assertEquals(
                paletteZero.allCaretCandidates.map { caret -> caret.position to caret.geometry },
                paletteOne.allCaretCandidates.map { caret -> caret.position to caret.geometry },
            )
            assertEquals(GlyphMaterializationRoute.PAINT_GRAPH, zeroCertificate.route)
            assertEquals(GlyphMaterializationRoute.PAINT_GRAPH, oneCertificate.route)
            assertNotEquals(zeroCertificate.assetKey, oneCertificate.assetKey)

            assertEquals(
                listOf(GlyphColor(201, 9, 0), GlyphColor(255, 149, 128)),
                paintColors(fixture.resolver, zeroCertificate),
            )
            assertEquals(
                listOf(GlyphColor(255, 255, 255), GlyphColor(232, 232, 231)),
                paintColors(fixture.resolver, oneCertificate),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun certifiesAnEmbeddedBitmapGlyphWhoseReopenedAssetReturnsTheDecodedPixels() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))
        val fixture = openFixture(
            bytes = resourceBytes("/fonts/skia-ebdt-format1/ebdt_fmt1.ttf"),
            provenance = "Skia EBDT format 1",
            requirements = requirements,
            layoutSize = 16f,
        )
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8("😀".encodeToByteArray())),
        ).snapshot

        try {
            val line = layout(snapshot, fixture, FontRenderVariantSnapshot.default, requirements)
            val glyph = line.positionedGlyphRuns.single().glyphs.single()
            val certificate = checkNotNull(glyph.materializationCertificate)

            assertEquals(GlyphId(3), glyph.shapedGlyph.glyphId)
            assertEquals(GlyphMaterializationRoute.BITMAP, certificate.route)
            val reopened = success(fixture.resolver.reopen(certificate.assetKey))
            try {
                val bitmap = assertIs<GlyphRepresentation.Bitmap>(
                    success(reopened.resolveGlyph(org.graphiks.kalligraphie.api.FontGlyphRequest(certificate.glyphId))),
                ).bitmap
                assertDecodedBitmap(bitmap)
            } finally {
                reopened.close()
            }
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun certifiesAnOutlineWhenThePreferredBitmapStrikeDoesNotContainTheFinalGlyph() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile(), outlineProfile()))
        val fixture = openFixture(
            bytes = liberationSansWithSkiaBitmapTables(),
            provenance = "Liberation Sans outlines with Skia EBDT format 1 tables",
            requirements = requirements,
            layoutSize = 16f,
        )
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf('!'.code.toByte()))),
        ).snapshot

        try {
            val line = layout(snapshot, fixture, FontRenderVariantSnapshot.default, requirements)
            val glyph = line.positionedGlyphRuns.single().glyphs.single()
            val certificate = checkNotNull(glyph.materializationCertificate)

            assertEquals(GlyphId(4), glyph.shapedGlyph.glyphId)
            assertEquals(GlyphMaterializationRoute.OUTLINE, certificate.route)
            assertEquals(outlineProfile(), certificate.assetKey.representationProfile)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun paragraphCertificationFallsBackToAnOutlineWhenThePreferredBitmapStrikeDoesNotContainTheFinalGlyph() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile(), outlineProfile()))
        val fixture = openFixture(
            bytes = liberationSansWithSkiaBitmapTables(),
            provenance = "Liberation Sans outlines with Skia EBDT format 1 tables",
            requirements = requirements,
            layoutSize = 16f,
        )
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf('!'.code.toByte()))),
        ).snapshot

        try {
            val line = paragraphLine(snapshot, fixture, FontRenderVariantSnapshot.default, requirements)
            val glyph = line.positionedGlyphRuns.single().glyphs.single()
            val certificate = checkNotNull(glyph.materializationCertificate)

            assertEquals(GlyphId(4), glyph.shapedGlyph.glyphId)
            assertEquals(GlyphMaterializationRoute.OUTLINE, certificate.route)
            assertEquals(outlineProfile(), certificate.assetKey.representationProfile)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun doesNotMaskABitmapAssetCloseFailureWithAnOutlineFallback() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile(), outlineProfile()))
        val fixture = openFixture(
            bytes = liberationSansWithSkiaBitmapTables(),
            provenance = "Liberation Sans outlines with Skia EBDT format 1 tables",
            requirements = requirements,
            layoutSize = 16f,
        )
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf(' '.code.toByte()))),
        ).snapshot

        try {
            val failure = assertIs<EditableLineResult.Failure>(
                layoutResult(
                    snapshot,
                    fixture,
                    FontRenderVariantSnapshot.default,
                    requirements,
                    CloseFailingBitmapFontInstance(fixture.font),
                ),
            )

            assertEquals(
                "font.test-asset-close-failure",
                assertIs<EditableLineError.FontMaterializationFailure>(failure.error).fontError.code,
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun paragraphDoesNotMaskABitmapAssetCloseFailureWithAnOutlineFallback() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile(), outlineProfile()))
        val fixture = openFixture(
            bytes = liberationSansWithSkiaBitmapTables(),
            provenance = "Liberation Sans outlines with Skia EBDT format 1 tables",
            requirements = requirements,
            layoutSize = 16f,
        )
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf(' '.code.toByte()))),
        ).snapshot

        try {
            val failure = assertIs<ParagraphLayoutResult.Failure>(
                paragraphResult(
                    snapshot,
                    fixture,
                    FontRenderVariantSnapshot.default,
                    requirements,
                    CloseFailingBitmapFontCatalog(fixture.catalog),
                ),
            )

            assertTrue(failure.diagnostics.any { diagnostic -> diagnostic.code == "font.test-asset-close-failure" })
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun keepsCancellationWhenClosingTheCancelledBitmapAssetFails() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile(), outlineProfile()))
        val fixture = openFixture(
            bytes = liberationSansWithSkiaBitmapTables(),
            provenance = "Liberation Sans outlines with Skia EBDT format 1 tables",
            requirements = requirements,
            layoutSize = 16f,
        )
        val cancellation = SwitchableCancellationToken()
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf(' '.code.toByte()))),
        ).snapshot

        try {
            val cancelled = assertIs<EditableLineResult.Cancelled>(
                layoutResult(
                    snapshot,
                    fixture,
                    FontRenderVariantSnapshot.default,
                    requirements,
                    CloseFailingBitmapFontInstance(fixture.font, cancellation),
                    cancellation,
                ),
            )

            assertTrue(cancelled.diagnostics.any { diagnostic -> diagnostic.code == "font.test-asset-close-failure" })
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun paragraphKeepsCancellationWhenClosingTheCancelledBitmapAssetFails() {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile(), outlineProfile()))
        val fixture = openFixture(
            bytes = liberationSansWithSkiaBitmapTables(),
            provenance = "Liberation Sans outlines with Skia EBDT format 1 tables",
            requirements = requirements,
            layoutSize = 16f,
        )
        val cancellation = SwitchableCancellationToken()
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf(' '.code.toByte()))),
        ).snapshot

        try {
            val cancelled = assertIs<ParagraphLayoutResult.Cancelled>(
                paragraphResult(
                    snapshot,
                    fixture,
                    FontRenderVariantSnapshot.default,
                    requirements,
                    CloseFailingBitmapFontCatalog(fixture.catalog, cancellation),
                    cancellation,
                ),
            )

            assertTrue(cancelled.diagnostics.any { diagnostic -> diagnostic.code == "font.test-asset-close-failure" })
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    private fun layout(
        snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
        fixture: Fixture,
        renderVariant: FontRenderVariantSnapshot,
        requirements: FontAccessRequirementsSnapshot,
    ) = assertIs<EditableLineResult.Success>(
        layoutResult(snapshot, fixture, renderVariant, requirements),
    ).line

    private fun layoutResult(
        snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
        fixture: Fixture,
        renderVariant: FontRenderVariantSnapshot,
        requirements: FontAccessRequirementsSnapshot,
        font: FontInstance = fixture.font,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): EditableLineResult =
        JvmEditableLineFacade.layout(
            JvmEditableLineFacadeRequest(
                snapshot = snapshot,
                font = font,
                baseDirection = BaseDirection.LEFT_TO_RIGHT,
                language = "en",
                featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                features = emptyList(),
                verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                materialization = EditableLineMaterialization.Renderable(
                    resolver = fixture.resolver,
                    renderVariant = renderVariant,
                    requirements = requirements,
                ),
                cancellationToken = cancellationToken,
            ),
        )

    private fun paragraphLine(
        snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
        fixture: Fixture,
        renderVariant: FontRenderVariantSnapshot,
        requirements: FontAccessRequirementsSnapshot,
        fontCatalog: FontCatalogSnapshot = fixture.catalog,
    ): LineLayout {
        return assertIs<ParagraphLayoutResult.Success>(
            paragraphResult(snapshot, fixture, renderVariant, requirements, fontCatalog),
        ).layout.lines.single()
    }

    private fun paragraphResult(
        snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
        fixture: Fixture,
        renderVariant: FontRenderVariantSnapshot,
        requirements: FontAccessRequirementsSnapshot,
        fontCatalog: FontCatalogSnapshot = fixture.catalog,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): ParagraphLayoutResult {
        val face = fontCatalog.faces.single().id
        return JvmEditableParagraphFacade.layout(
                JvmEditableParagraphFacadeRequest(
                    snapshot = snapshot,
                    constraints = HorizontalParagraphConstraints(
                        region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(2_000f), LayoutUnit(1_000f)),
                        lineMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    ),
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    fontCatalog = fontCatalog,
                    resolutionPolicy = FontResolutionPolicySnapshot(
                        generation = fontCatalog.generation,
                        policyId = "portable-glyph-materialization-fixture",
                        version = "1",
                        candidates = listOf(FontResolutionCandidate(face)),
                        lastResortFace = face,
                    ),
                    fontInstanceDescriptor = fixture.descriptor,
                    materialization = EditableLineMaterialization.Renderable(
                        resolver = fixture.resolver,
                        renderVariant = renderVariant,
                        requirements = requirements,
                    ),
                    cancellationToken = cancellationToken,
                ),
            )
    }

    private fun paintColors(
        resolver: FontAssetResolverHandle,
        certificate: org.graphiks.kalligraphie.api.GlyphMaterializationCertificate,
    ): List<GlyphColor> {
        val reopened = success(resolver.reopen(certificate.assetKey))
        try {
            val paint = assertIs<GlyphRepresentation.Paint>(
                success(reopened.resolveGlyph(org.graphiks.kalligraphie.api.FontGlyphRequest(certificate.glyphId))),
            ).paint
            return paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().map(GlyphPaintNode.SolidOutline::color)
        } finally {
            reopened.close()
        }
    }

    private fun assertDecodedBitmap(bitmap: BitmapGlyphIR) {
        assertEquals(BitmapStrike(16, 16), bitmap.strike)
        assertEquals(13, bitmap.width)
        assertEquals(13, bitmap.height)
        assertEquals(0, bitmap.originX)
        assertEquals(13, bitmap.originY)
        assertEquals(BitmapPixelFormat.ALPHA_8, bitmap.pixelFormat)
        assertEquals(GlyphColorSpace.SRGB, bitmap.colorSpace)
        assertContentEquals(
            byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, -1, -1, -1, -1, -1, 0, 0, 0, 0,
            ),
            bitmap.copyDecodedPixels().copyOfRange(0, 26),
        )
    }

    private fun openFixture(
        bytes: ByteArray,
        provenance: String,
        requirements: FontAccessRequirementsSnapshot,
        layoutSize: Float,
    ): Fixture {
        val catalog = success(Kalligraphie.embedded(bytes, FontSourceProvenance(provenance)))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val descriptor = FontInstanceDescriptor(LayoutUnit(layoutSize))
        val font = success(face.instantiate(descriptor))
        return Fixture(catalog, font, resolver, descriptor)
    }

    private fun paintProfile(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 3,
            maxReferences = 2,
            maxDepth = 2,
            maxSourceBytes = 16_384,
            maxPaths = 2,
            maxPalettes = 9,
            maxPaletteEntries = 2,
            maxColorRecords = 16,
            maxDecodedPaletteBytes = 72,
            maxBaseGlyphRecords = 288,
            maxLayerRecords = 576,
        ),
        outlineProfile = outlineProfile(),
    )

    private fun bitmapProfile(): BitmapProfile = BitmapProfile(
        strike = BitmapStrike(16, 16),
        acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = 3,
            maxIndexSubtables = 16,
            maxRecordCount = 16,
            maxIndexTableBytes = 16_384,
            maxBitmapTableBytes = 16_384,
            maxWidth = 16,
            maxHeight = 16,
            maxPixels = 256,
            maxCompressedBytes = 64,
            maxTotalCompressedBytes = 1_024,
            maxDecodedBytes = 256,
            maxTotalDecodedBytes = 1_024,
        ),
    )

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 1_024,
        maxPoints = 65_536,
        maxCompositeDepth = 16,
        maxCompositeComponents = 256,
    )

    /**
     * Joins audited Liberation Sans outlines with audited Skia bitmap tables for one real-font
     * fallback scenario: Liberation glyph 4 (`!`) has a TrueType outline but no Skia bitmap
     * record. Both source fixtures carry their own immutable provenance and license records.
     */
    private fun liberationSansWithSkiaBitmapTables(): ByteArray {
        val bitmapSource = resourceBytes("/fonts/skia-ebdt-format1/ebdt_fmt1.ttf")
        val bitmapTables = listOf("EBLC", "EBDT").map { tag -> table(bitmapSource, tag) }
        return sfntWithAdditionalTables(
            source = resourceBytes("/fonts/liberation/LiberationSans-Regular.ttf"),
            additionalTables = bitmapTables,
        )
    }

    private fun sfntWithAdditionalTables(source: ByteArray, additionalTables: List<SfntTable>): ByteArray {
        val sourceTables = readTables(source)
        require(additionalTables.none { additional -> sourceTables.any { sourceTable -> sourceTable.tag == additional.tag } }) {
            "Test source already declares a bitmap table."
        }
        val tables = (sourceTables + additionalTables).sortedBy(SfntTable::tag)
        val headerSize = SFNT_HEADER_BYTES + tables.size * SFNT_TABLE_RECORD_BYTES
        val offsets = ArrayList<Int>(tables.size)
        var totalSize = headerSize
        tables.forEach { table ->
            totalSize = alignToWord(totalSize)
            offsets += totalSize
            totalSize += table.bytes.size
        }
        totalSize = alignToWord(totalSize)

        return ByteArray(totalSize).also { result ->
            source.copyInto(result, destinationOffset = 0, startIndex = 0, endIndex = 4)
            writeUInt16(result, 4, tables.size)
            val largestPowerOfTwo = Integer.highestOneBit(tables.size)
            writeUInt16(result, 6, largestPowerOfTwo * SFNT_TABLE_RECORD_BYTES)
            writeUInt16(result, 8, Integer.numberOfTrailingZeros(largestPowerOfTwo))
            writeUInt16(result, 10, tables.size * SFNT_TABLE_RECORD_BYTES - largestPowerOfTwo * SFNT_TABLE_RECORD_BYTES)

            tables.forEachIndexed { index, table ->
                val recordOffset = SFNT_HEADER_BYTES + index * SFNT_TABLE_RECORD_BYTES
                writeTag(result, recordOffset, table.tag)
                writeUInt32(result, recordOffset + 4, table.checksum)
                writeUInt32(result, recordOffset + 8, offsets[index].toUInt())
                writeUInt32(result, recordOffset + 12, table.bytes.size.toUInt())
                table.bytes.copyInto(result, destinationOffset = offsets[index])
            }

            val headOffset = offsets[tables.indexOfFirst { table -> table.tag == "head" }]
            writeUInt32(result, headOffset + HEAD_CHECKSUM_ADJUSTMENT_OFFSET, 0u)
            writeUInt32(result, headOffset + HEAD_CHECKSUM_ADJUSTMENT_OFFSET, OPEN_TYPE_CHECKSUM_MAGIC - openTypeChecksum(result))
        }
    }

    private fun table(source: ByteArray, tag: String): SfntTable =
        readTables(source).single { table -> table.tag == tag }

    private fun readTables(source: ByteArray): List<SfntTable> {
        val tableCount = readUInt16(source, 4)
        return List(tableCount) { index ->
            val recordOffset = SFNT_HEADER_BYTES + index * SFNT_TABLE_RECORD_BYTES
            val offset = readUInt32(source, recordOffset + 8)
            val length = readUInt32(source, recordOffset + 12)
            require(offset >= 0 && length >= 0 && offset <= source.size - length) { "Invalid SFNT table range in fixture." }
            SfntTable(
                tag = readTag(source, recordOffset),
                bytes = source.copyOfRange(offset, offset + length),
                checksum = readUInt32(source, recordOffset + 4).toUInt(),
            )
        }
    }

    private fun readTag(bytes: ByteArray, offset: Int): String =
        CharArray(4) { index -> bytes[offset + index].toInt().toChar() }.concatToString()

    private fun writeTag(bytes: ByteArray, offset: Int, tag: String) {
        require(tag.length == 4) { "An SFNT tag must have exactly four characters." }
        tag.forEachIndexed { index, character -> bytes[offset + index] = character.code.toByte() }
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: UInt) {
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun openTypeChecksum(bytes: ByteArray): UInt {
        var checksum = 0u
        bytes.indices.step(4).forEach { offset ->
            val word = (bytes[offset].toUInt() and 0xffu) shl 24 or
                ((bytes.getOrElse(offset + 1) { 0 }.toUInt() and 0xffu) shl 16) or
                ((bytes.getOrElse(offset + 2) { 0 }.toUInt() and 0xffu) shl 8) or
                (bytes.getOrElse(offset + 3) { 0 }.toUInt() and 0xffu)
            checksum += word
        }
        return checksum
    }

    private fun alignToWord(value: Int): Int = (value + 3) and 3.inv()

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Fixture font resource is missing: $path" }.use { it.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value

    private data class Fixture(
        val catalog: FontCatalogSnapshot,
        val font: FontInstance,
        val resolver: FontAssetResolverHandle,
        val descriptor: FontInstanceDescriptor,
    )

    private data class SfntTable(
        val tag: String,
        val bytes: ByteArray,
        val checksum: UInt,
    )

    private class CountingFontCatalog(
        private val delegate: FontCatalogSnapshot,
    ) : FontCatalogSnapshot {
        val resolvedGlyphCount: AtomicInteger = AtomicInteger(0)

        override val generation: FontCatalogGeneration
            get() = delegate.generation

        override val faces: List<org.graphiks.kalligraphie.api.FontFaceRecord>
            get() = delegate.faces

        override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle> = delegate.openAssetResolver()

        override fun resolveFace(
            faceId: FontFaceId,
            requirements: FontAccessRequirementsSnapshot,
        ): FontOperationResult<FontFace> = when (val resolved = delegate.resolveFace(faceId, requirements)) {
            is FontOperationResult.Success -> FontOperationResult.Success(CountingFontFace(resolved.value, resolvedGlyphCount))
            is FontOperationResult.Failure -> resolved
            is FontOperationResult.Cancelled -> resolved
        }
    }

    private class CountingFontFace(
        private val delegate: FontFace,
        private val resolvedGlyphCount: AtomicInteger,
    ) : FontFace {
        override val id: FontFaceId
            get() = delegate.id

        override val metadata: FontFaceMetadata
            get() = delegate.metadata

        override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> =
            when (val instantiated = delegate.instantiate(descriptor)) {
                is FontOperationResult.Success -> FontOperationResult.Success(CountingFontInstance(instantiated.value, resolvedGlyphCount))
                is FontOperationResult.Failure -> instantiated
                is FontOperationResult.Cancelled -> instantiated
            }
    }

    private class CountingFontInstance(
        private val delegate: FontInstance,
        private val resolvedGlyphCount: AtomicInteger,
    ) : FontInstance {
        override val key: org.graphiks.kalligraphie.api.FontInstanceKey
            get() = delegate.key

        override fun resolveGlyph(codePoint: Int) = delegate.resolveGlyph(codePoint)

        override fun resolveGlyph(codePoint: Int, variationSelector: Int) =
            delegate.resolveGlyph(codePoint, variationSelector)

        override fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics> = delegate.metrics(glyphId)

        override fun verticalMetrics(glyphId: GlyphId): FontOperationResult<VerticalGlyphMetrics> =
            delegate.verticalMetrics(glyphId)

        override fun copyOpenTypeData(): FontOperationResult<OpenTypeFontData> = delegate.copyOpenTypeData()

        override fun acquireRenderAsset(
            resolver: FontAssetResolverHandle,
            variant: FontRenderVariantKey,
            requirements: FontAccessRequirementsSnapshot,
        ): FontOperationResult<FontRenderAssetHandle> =
            counted(delegate.acquireRenderAsset(resolver, variant, requirements))

        override fun acquireRenderAsset(
            resolver: FontAssetResolverHandle,
            renderVariant: FontRenderVariantSnapshot,
            requirements: FontAccessRequirementsSnapshot,
        ): FontOperationResult<FontRenderAssetHandle> =
            counted(delegate.acquireRenderAsset(resolver, renderVariant, requirements))

        private fun counted(result: FontOperationResult<FontRenderAssetHandle>): FontOperationResult<FontRenderAssetHandle> =
            when (result) {
                is FontOperationResult.Success -> FontOperationResult.Success(CountingRenderAsset(result.value, resolvedGlyphCount))
                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
            }
    }

    private class CountingRenderAsset(
        private val delegate: FontRenderAssetHandle,
        private val resolvedGlyphCount: AtomicInteger,
    ) : FontRenderAssetHandle {
        override val key: FontRenderAssetKey
            get() = delegate.key

        override val faceId: FontFaceId
            get() = delegate.faceId

        override fun detach(): FontOperationResult<FontRenderAssetHandle> =
            when (val detached = delegate.detach()) {
                is FontOperationResult.Success -> FontOperationResult.Success(CountingRenderAsset(detached.value, resolvedGlyphCount))
                is FontOperationResult.Failure -> detached
                is FontOperationResult.Cancelled -> detached
            }

        override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> {
            resolvedGlyphCount.incrementAndGet()
            return delegate.resolveGlyph(request)
        }

        override fun resolveGlyph(
            request: FontGlyphRequest,
            cancellationToken: org.graphiks.kalligraphie.api.CancellationToken,
        ): FontOperationResult<GlyphRepresentation> {
            resolvedGlyphCount.incrementAndGet()
            return delegate.resolveGlyph(request, cancellationToken)
        }

        override fun close(): FontOperationResult<Unit> = delegate.close()
    }

    private class CloseFailingBitmapFontCatalog(
        private val delegate: FontCatalogSnapshot,
        private val cancellationToken: SwitchableCancellationToken? = null,
    ) : FontCatalogSnapshot {
        override val generation: FontCatalogGeneration
            get() = delegate.generation

        override val faces: List<org.graphiks.kalligraphie.api.FontFaceRecord>
            get() = delegate.faces

        override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle> = delegate.openAssetResolver()

        override fun resolveFace(
            faceId: FontFaceId,
            requirements: FontAccessRequirementsSnapshot,
        ): FontOperationResult<FontFace> = when (val resolved = delegate.resolveFace(faceId, requirements)) {
            is FontOperationResult.Success -> FontOperationResult.Success(CloseFailingBitmapFontFace(resolved.value, cancellationToken))
            is FontOperationResult.Failure -> resolved
            is FontOperationResult.Cancelled -> resolved
        }
    }

    private class CloseFailingBitmapFontFace(
        private val delegate: FontFace,
        private val cancellationToken: SwitchableCancellationToken?,
    ) : FontFace {
        override val id: FontFaceId
            get() = delegate.id

        override val metadata: FontFaceMetadata
            get() = delegate.metadata

        override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> =
            when (val instantiated = delegate.instantiate(descriptor)) {
                is FontOperationResult.Success -> FontOperationResult.Success(CloseFailingBitmapFontInstance(instantiated.value, cancellationToken))
                is FontOperationResult.Failure -> instantiated
                is FontOperationResult.Cancelled -> instantiated
            }
    }

    private class CloseFailingBitmapFontInstance(
        private val delegate: FontInstance,
        private val cancellationToken: SwitchableCancellationToken? = null,
    ) : FontInstance {
        override val key: org.graphiks.kalligraphie.api.FontInstanceKey
            get() = delegate.key

        override fun resolveGlyph(codePoint: Int) = delegate.resolveGlyph(codePoint)

        override fun resolveGlyph(codePoint: Int, variationSelector: Int) =
            delegate.resolveGlyph(codePoint, variationSelector)

        override fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics> = delegate.metrics(glyphId)

        override fun verticalMetrics(glyphId: GlyphId): FontOperationResult<VerticalGlyphMetrics> =
            delegate.verticalMetrics(glyphId)

        override fun copyOpenTypeData(): FontOperationResult<OpenTypeFontData> = delegate.copyOpenTypeData()

        override fun acquireRenderAsset(
            resolver: FontAssetResolverHandle,
            variant: FontRenderVariantKey,
            requirements: FontAccessRequirementsSnapshot,
        ): FontOperationResult<FontRenderAssetHandle> =
            closeFailingBitmapAsset(delegate.acquireRenderAsset(resolver, variant, requirements), requirements)

        override fun acquireRenderAsset(
            resolver: FontAssetResolverHandle,
            renderVariant: FontRenderVariantSnapshot,
            requirements: FontAccessRequirementsSnapshot,
        ): FontOperationResult<FontRenderAssetHandle> =
            closeFailingBitmapAsset(delegate.acquireRenderAsset(resolver, renderVariant, requirements), requirements)

        private fun closeFailingBitmapAsset(
            result: FontOperationResult<FontRenderAssetHandle>,
            requirements: FontAccessRequirementsSnapshot,
        ): FontOperationResult<FontRenderAssetHandle> = when (result) {
            is FontOperationResult.Success -> if (requirements.acceptedProfiles.singleOrNull() is BitmapProfile) {
                FontOperationResult.Success(CloseFailingRenderAsset(result.value, cancellationToken))
            } else {
                result
            }
            is FontOperationResult.Failure -> result
            is FontOperationResult.Cancelled -> result
        }
    }

    private class CloseFailingRenderAsset(
        private val delegate: FontRenderAssetHandle,
        private val cancellationToken: SwitchableCancellationToken? = null,
    ) : FontRenderAssetHandle {
        override val key: FontRenderAssetKey
            get() = delegate.key

        override val faceId: FontFaceId
            get() = delegate.faceId

        override fun detach(): FontOperationResult<FontRenderAssetHandle> =
            when (val detached = delegate.detach()) {
                is FontOperationResult.Success -> FontOperationResult.Success(CloseFailingRenderAsset(detached.value, cancellationToken))
                is FontOperationResult.Failure -> detached
                is FontOperationResult.Cancelled -> detached
            }

        override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
            delegate.resolveGlyph(request)

        override fun resolveGlyph(
            request: FontGlyphRequest,
            cancellationToken: org.graphiks.kalligraphie.api.CancellationToken,
        ): FontOperationResult<GlyphRepresentation> {
            this.cancellationToken?.cancel()
            return delegate.resolveGlyph(request, cancellationToken)
        }

        override fun close(): FontOperationResult<Unit> = when (val closed = delegate.close()) {
            is FontOperationResult.Success -> FontOperationResult.Failure(
                FontError.FontDataFailure(
                    code = "font.test-asset-close-failure",
                    message = "The test render asset could not close.",
                    location = FontDiagnosticLocation.Source,
                ),
            )
            is FontOperationResult.Failure -> closed
            is FontOperationResult.Cancelled -> closed
        }
    }

    private class SwitchableCancellationToken : CancellationToken {
        private var cancelled: Boolean = false

        fun cancel() {
            cancelled = true
        }

        override fun isCancellationRequested(): Boolean = cancelled
    }
}
