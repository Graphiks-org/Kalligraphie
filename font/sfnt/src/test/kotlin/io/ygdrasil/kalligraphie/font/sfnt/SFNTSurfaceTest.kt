package io.ygdrasil.kalligraphie.font.sfnt

import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSlant
import io.ygdrasil.kalligraphie.font.TypefaceID
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SFNTSurfaceTest {
    @Test
    fun defaultReaderReadsDirectoryAndBoundedTableBytes() {
        val payload = byteArrayOf(0x21, 0x22, 0x23, 0x24)
        val source = memoryFontSource(
            byteArrayOf(
                0x00, 0x01, 0x00, 0x00,
                0x00, 0x01,
                0x00, 0x10,
                0x00, 0x00,
                0x00, 0x00,
                'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'e'.code.toByte(),
                0x12, 0x34, 0x56, 0x78,
                0x00, 0x00, 0x00, 0x1c,
                0x00, 0x00, 0x00, 0x04,
                *payload,
            ),
        )

        val reader = DefaultSFNTReader()
        val directory = reader.readDirectory(source)
        val record = directory.tables.single()

        assertEquals(0x00010000u, directory.scalerType)
        assertEquals(SFNTTableTag("name"), record.tag)
        assertEquals(0x12345678u, record.checksum)
        assertEquals(28u, record.offset)
        assertEquals(4u, record.length)
        assertContentEquals(payload, reader.readTable(source, record))
    }

    @Test
    fun defaultReaderRejectsTableRangesOutsideSourceBounds() {
        val reader = DefaultSFNTReader()
        val source = memoryFontSource(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val record = SFNTTableRecord(
            tag = SFNTTableTag("name"),
            checksum = 0u,
            offset = 2u,
            length = 4u,
        )

        assertFailsWith<IllegalArgumentException> {
            reader.readTable(source, record)
        }
    }

    @Test
    fun defaultReaderRejectsTableOffsetsThatCannotAddressSourceBytes() {
        val reader = DefaultSFNTReader()
        val source = memoryFontSource(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val record = SFNTTableRecord(
            tag = SFNTTableTag("name"),
            checksum = 0u,
            offset = UInt.MAX_VALUE,
            length = 1u,
        )

        assertFailsWith<IllegalArgumentException> {
            reader.readTable(source, record)
        }
    }

    @Test
    fun tableDirectoryValidatorReportsBoundedDiagnosticsDeterministically() {
        val directory = SFNTTableDirectory(
            scalerType = 0x00010000u,
            tables = listOf(
                SFNTTableRecord(tag = SFNTTableTag("name"), checksum = 0u, offset = 24u, length = 16u),
                SFNTTableRecord(tag = SFNTTableTag("name"), checksum = 1u, offset = 40u, length = 8u),
                SFNTTableRecord(tag = SFNTTableTag("cmap"), checksum = 2u, offset = 44u, length = 12u),
                SFNTTableRecord(tag = SFNTTableTag("glyf"), checksum = 3u, offset = 80u, length = 0u),
                SFNTTableRecord(tag = SFNTTableTag("post"), checksum = 4u, offset = 120u, length = 16u),
            ),
        )

        val diagnostics = SFNTTableDirectoryValidator.validate(
            directory = directory,
            sourceLength = 128,
            requiredTables = setOf(SFNTTableTag("cmap"), SFNTTableTag("glyf"), SFNTTableTag("head")),
        )

        assertEquals(
            listOf(
                "font.sfnt.required-table-missing tag=\"glyf\" offset=80 length=0 sourceLength=128 message=\"Required table is present with zero length.\"",
                "font.sfnt.required-table-missing tag=\"head\" offset=none length=none sourceLength=128 message=\"Required table is not present.\"",
                "font.sfnt.table-duplicate tag=\"name\" offset=40 length=8 sourceLength=128 message=\"Duplicate SFNT table tag.\"",
                "font.sfnt.table-out-of-bounds tag=\"post\" offset=120 length=16 sourceLength=128 message=\"Table range exceeds source length.\"",
                "font.sfnt.table-overlap tag=\"cmap\" offset=44 length=12 sourceLength=128 message=\"Table range overlaps previous table range ending at 48.\"",
            ),
            diagnostics.map { it.dump() },
        )
    }

    @Test
    fun exposesSfntDirectoryAndParsedTableContainers() {
        val tag = SFNTTableTag("name")
        val record = SFNTTableRecord(
            tag = tag,
            checksum = 1u,
            offset = 12u,
            length = 24u,
        )
        val directory = SFNTTableDirectory(
            scalerType = 0x00010000u,
            tables = listOf(record),
        )
        val diagnostic = OpenTypeParseDiagnostic(
            sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440100")),
            message = "table deferred",
        )
        val faceData = OpenTypeFaceData(
            id = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440101")),
            source = FontSource(
                id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440100")),
                kind = FontSourceKind.MEMORY,
                displayName = "Memory Font",
                bytes = ByteArray(0),
            ),
            directory = directory,
            cmap = CMapTable(),
            names = NameTable(),
            metrics = MetricsTables(),
            variations = VariationTables(),
            layout = OpenTypeLayoutTables(),
            color = ColorFontTables(),
            rawTables = mapOf(tag to listOf(0x01, 0x02)),
            diagnostics = listOf(diagnostic),
        )

        assertEquals("name", tag.value)
        assertEquals(record, directory.tables.single())
        assertEquals(directory, faceData.directory)
        assertEquals(listOf(0x01, 0x02), faceData.rawTables[tag])
        assertContentEquals(byteArrayOf(0x01, 0x02), faceData.rawTableBytes(tag))
        assertContentEquals(byteArrayOf(0x01, 0x02), faceData.rawTableBytes("name"))
        assertEquals(diagnostic, faceData.diagnostics.single())
    }

    @Test
    fun defaultOpenTypeFaceParserParsesSimpleSfntFaceAndKeepsRawTables() {
        val family = "Kanvas Sans"
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(
                    testNameRecord(
                        platformId = 3,
                        encodingId = 1,
                        languageId = 0x0409,
                        nameId = 1,
                        bytes = family.toByteArray(Charsets.UTF_16BE),
                    ),
                ),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
                    indexToLocFormat = 1,
                ),
                "hhea" to hheaTable(
                    ascender = 820,
                    descender = -180,
                    lineGap = 40,
                    numberOfHMetrics = 2,
                ),
                "maxp" to maxpTable(numGlyphs = 2),
                "hmtx" to hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = -20),
                    metric(advanceWidth = 450, leftSideBearing = 7),
                ),
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val reparsed = DefaultOpenTypeFaceParser().parse(source)

        assertEquals(parsed.id, reparsed.id)
        assertEquals(6, parsed.directory.tables.size)
        assertEquals(family, parsed.names.lookupName(nameId = 1))
        assertEquals(7, parsed.cmap.lookupGlyphId(0x0041))
        assertEquals(1000, parsed.metrics.unitsPerEm)
        assertEquals(2, parsed.metrics.numGlyphs)
        assertEquals(820, parsed.metrics.ascender)
        assertEquals(450, parsed.metrics.horizontalMetrics[1].advanceWidth)
        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(6, parsed.rawTables.size)
        assertEquals(54, parsed.rawTables.getValue(SFNTTableTag("head")).size)
        assertEquals(0, parsed.rawTables.getValue(SFNTTableTag("head"))[0])
    }

    @Test
    fun liberationSansFixtureAdvertisesRequiredTablesAndKeepsFormat14GateExplicit() {
        val fontPath = fixturePath("font/fixtures/fonts/liberation/LiberationSans-Regular.ttf")
        val expectedDump = fixturePath("font/fixtures/expected/sfnt/sfnt-cmap-format14-readiness.json")
        val source = FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655442000")),
            kind = FontSourceKind.FILE,
            displayName = fontPath.fileName.toString(),
            bytes = Files.readAllBytes(fontPath),
        )
        val directory = DefaultSFNTReader().readDirectory(source)
        val tableTags = directory.tables.map { it.tag.value }.toSet()

        assertEquals(
            setOf("cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post"),
            tableTags.intersect(setOf("cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post")),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val hasFormat14 = parsed.cmap.encodingRecords.any { it.format == 14 }
        val diagnostic = "font.cmap.format14-fixture-missing"

        assertFalse(hasFormat14)
        assertTrue(Files.readString(expectedDump).contains(diagnostic))
    }

    @Test
    fun m6SimpleLayoutFixturesAreCheckedInWithSyntheticProvenance() {
        val fixturePaths = listOf(
            "font/fixtures/fonts/shaping/gsub-single-substitution.otf",
            "font/fixtures/fonts/shaping/gsub-multiple-substitution.otf",
            "font/fixtures/fonts/shaping/gsub-ligature-fi.otf",
            "font/fixtures/fonts/shaping/gsub-coverage-malformed.otf",
            "font/fixtures/fonts/shaping/gsub-ligature-bad-component.otf",
            "font/fixtures/fonts/shaping/gpos-single-adjustment.otf",
            "font/fixtures/fonts/shaping/gpos-pair-format1-kerning.otf",
            "font/fixtures/fonts/shaping/gpos-pair-format2-class.otf",
            "font/fixtures/fonts/shaping/gpos-valueformat-malformed.otf",
            "font/fixtures/fonts/shaping/gpos-pair-out-of-range.otf",
        )
        val provenanceIndex = Files.readString(fixturePath("font/fixtures/provenance/index.json"))

        fixturePaths.forEach { relativePath ->
            assertTrue(
                actual = Files.isRegularFile(fixturePath(relativePath)),
                message = "Expected checked-in M6 shaping fixture $relativePath",
            )
            assertTrue(
                actual = provenanceIndex.contains(relativePath),
                message = "Fixture provenance index should reference $relativePath",
            )
        }

        listOf(
            "gsub-single-substitution",
            "gsub-multiple-substitution",
            "gsub-ligature-fi",
            "gsub-coverage-malformed",
            "gsub-ligature-bad-component",
            "gpos-single-adjustment",
            "gpos-pair-format1-kerning",
            "gpos-pair-format2-class",
            "gpos-valueformat-malformed",
            "gpos-pair-out-of-range",
            "\"kind\": \"synthetic-kanvas\"",
            "\"ownerTickets\": [\n        \"KFONT-M6-002\"",
            "\"ownerTickets\": [\n        \"KFONT-M6-004\"",
        ).forEach { requiredSnippet ->
            assertTrue(
                actual = provenanceIndex.contains(requiredSnippet),
                message = "Fixture provenance index is missing $requiredSnippet",
            )
        }
    }

    @Test
    fun m6ExtensionLayoutFixturesAreCheckedInWithSyntheticProvenance() {
        val fixturePaths = listOf(
            "font/fixtures/fonts/shaping/gsub-extension-substitution.otf",
            "font/fixtures/fonts/shaping/layout-extension-cycle.otf",
        )
        val provenanceIndex = Files.readString(fixturePath("font/fixtures/provenance/index.json"))

        fixturePaths.forEach { relativePath ->
            assertTrue(
                actual = Files.isRegularFile(fixturePath(relativePath)),
                message = "Expected checked-in M6 extension fixture $relativePath",
            )
            assertTrue(
                actual = provenanceIndex.contains(relativePath),
                message = "Fixture provenance index should reference $relativePath",
            )
        }

        listOf(
            "gsub-extension-substitution",
            "layout-extension-cycle",
            "\"ownerTickets\": [\n        \"KFONT-M6-010\"",
            "\"kind\": \"synthetic-kanvas\"",
        ).forEach { requiredSnippet ->
            assertTrue(
                actual = provenanceIndex.contains(requiredSnippet),
                message = "Fixture provenance index is missing $requiredSnippet",
            )
        }
    }

    @Test
    fun m6FallbackPlusSourceAssetIsCheckedInWithApacheProvenance() {
        val relativePath = "font/fixtures/fonts/shaping/FallbackPlus-Small.otf"
        val provenanceIndex = Files.readString(fixturePath("font/fixtures/provenance/index.json"))

        assertTrue(
            actual = Files.isRegularFile(fixturePath(relativePath)),
            message = "Expected checked-in shaping source asset $relativePath",
        )
        listOf(
            "fallbackplus-small-source",
            relativePath,
            "\"ownerTickets\": [\n        \"KFONT-M6-010\"",
            "\"project\": \"simoncozens/test-fonts FallbackPlus-Small\"",
            "\"url\": \"https://raw.githubusercontent.com/simoncozens/test-fonts/master/FallbackPlus-Small.otf\"",
            "\"path\": \"reports/font/fixtures/licenses/test-fonts-Apache-2.0.txt\"",
        ).forEach { requiredSnippet ->
            assertTrue(
                actual = provenanceIndex.contains(requiredSnippet),
                message = "FallbackPlus source provenance index is missing $requiredSnippet",
            )
        }
    }

    @Test
    fun m6RemainingFixtureResourceWaveIsCheckedInWithReviewedProvenance() {
        val expectedPaths = listOf(
            "font/fixtures/fonts/shaping/arabic-joining-forms.otf",
            "font/fixtures/fonts/shaping/arabic-lam-alef.otf",
            "font/fixtures/fonts/shaping/arabic-marks-cursive.otf",
            "font/fixtures/fonts/shaping/arabic-missing-cursive.otf",
            "font/fixtures/fonts/shaping/arabic-missing-mark.otf",
            "font/fixtures/fonts/shaping/devanagari-consonant-cluster.otf",
            "font/fixtures/fonts/shaping/devanagari-reph.otf",
            "font/fixtures/fonts/shaping/devanagari-prebase-matra.otf",
            "font/fixtures/fonts/shaping/devanagari-below-base.otf",
            "font/fixtures/fonts/shaping/devanagari-mark-placement.otf",
            "font/fixtures/fonts/shaping/devanagari-unsupported-syllable.otf",
            "font/fixtures/fonts/shaping/thai-base-marks.otf",
            "font/fixtures/fonts/shaping/thai-tone-marks.otf",
            "font/fixtures/fonts/shaping/cjk-han-variation-selector.otf",
            "font/fixtures/fonts/shaping/cjk-kana-vertical.otf",
            "font/fixtures/fonts/shaping/cjk-hangul-direct.otf",
            "font/fixtures/fonts/shaping/cjk-missing-vertical-alt.otf",
            "font/fixtures/fonts/shaping/gsub-chaining-context.otf",
            "font/fixtures/fonts/shaping/gsub-reverse-chaining.otf",
            "font/fixtures/fonts/shaping/gpos-contextual-positioning.otf",
            "font/fixtures/fonts/shaping/gpos-chaining-positioning.otf",
            "font/fixtures/fonts/shaping/gpos-extension-positioning.otf",
            "font/fixtures/fonts/shaping/gpos-variation-device.otf",
            "font/fixtures/expected/shaping/thai-latin-mixed.txt",
            "font/fixtures/expected/shaping/variation-adjustment-trace.json",
        )
        val provenanceIndex = Files.readString(fixturePath("font/fixtures/provenance/index.json"))

        expectedPaths.forEach { relativePath ->
            assertTrue(
                actual = Files.isRegularFile(fixturePath(relativePath)),
                message = "Expected checked-in M6 fixture resource $relativePath",
            )
            if (relativePath.endsWith(".otf") || relativePath.endsWith(".ttf")) {
                assertTrue(
                    actual = provenanceIndex.contains(relativePath),
                    message = "Fixture provenance index should reference $relativePath",
                )
            }
        }

        listOf(
            "arabic-ticket-local-fixture-wave",
            "devanagari-ticket-local-fixture-wave",
            "thai-cjk-ticket-local-fixture-wave",
            "advanced-lookup-ticket-local-fixture-wave",
            "\"ownerTickets\": [\n        \"KFONT-M6-007\"",
            "\"ownerTickets\": [\n        \"KFONT-M6-008\"",
            "\"ownerTickets\": [\n        \"KFONT-M6-009\"",
            "\"ownerTickets\": [\n        \"KFONT-M6-010\"",
        ).forEach { requiredSnippet ->
            assertTrue(
                actual = provenanceIndex.contains(requiredSnippet),
                message = "Fixture provenance index is missing $requiredSnippet",
            )
        }
    }

    @Test
    fun defaultOpenTypeFaceParserParsesSvgTableAndLooksUpDocumentsByGlyphId() {
        val svgBytes = "<svg><path id=\"glyph-eight\"/></svg>".toByteArray(Charsets.UTF_8)
        val svg = svgTable(
            startGlyphId = 7,
            endGlyphId = 9,
            bytes = svgBytes,
        )
        val source = memoryFontSource(
            sfntFaceWithColorTables(
                numGlyphs = 10,
                "SVG " to svg,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val svgTable = parsed.color.svg ?: error("Expected parsed SVG table.")
        val document = svgTable.documentForGlyph(8) ?: error("Expected SVG document for glyph 8.")

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(svg.toUnsignedByteList(), parsed.color.tables.getValue(SFNTTableTag("SVG ")))
        assertEquals(0, svgTable.version)
        assertEquals(10, svgTable.documentListOffset)
        assertEquals(OpenTypeSvgDocumentRecord(startGlyphId = 7, endGlyphId = 9, offset = 24, length = svgBytes.size), svgTable.records.single())
        assertEquals(7, document.startGlyphId)
        assertEquals(9, document.endGlyphId)
        assertEquals(svgBytes.toUnsignedByteList(), document.bytes)
        assertEquals("<svg><path id=\"glyph-eight\"/></svg>", document.text)
        assertEquals(null, svgTable.documentForGlyph(6))
    }

    @Test
    fun defaultOpenTypeFaceParserParsesSbixPngGlyphMetadataAndPayload() {
        val png = pngPayload(0x10, 0x20, 0x30)
        val sbix = sbixTable(
            numGlyphs = 5,
            glyphId = 3,
            ppem = 19,
            originOffsetX = -2,
            originOffsetY = 5,
            png = png,
        )
        val source = memoryFontSource(
            sfntFaceWithColorTables(
                numGlyphs = 5,
                "sbix" to sbix,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val bitmap = parsed.color.bitmap ?: error("Expected parsed bitmap font.")
        val glyph = bitmap.glyph(3) ?: error("Expected sbix glyph 3.")

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(sbix.toUnsignedByteList(), parsed.color.tables.getValue(SFNTTableTag("sbix")))
        assertEquals(OpenTypeBitmapGlyphSource.SBIX, glyph.source)
        assertEquals(3, glyph.glyphId)
        assertEquals(19, glyph.ppemX)
        assertEquals(19, glyph.ppemY)
        assertEquals(32, glyph.bitDepth)
        assertEquals(-2, glyph.originOffsetX)
        assertEquals(5, glyph.originOffsetY)
        assertEquals("png ", glyph.imageFormat)
        assertEquals(png.toUnsignedByteList(), glyph.bytes)
        assertEquals(null, bitmap.glyph(2))
    }

    @Test
    fun defaultOpenTypeFaceParserParsesCbdtCblcIndexFormat1PngGlyph() {
        val png = pngPayload(0x44, 0x55)
        val cbdtPayload = cbdtFormat17Payload(png)
        val cbdt = cbdtTable(cbdtPayload)
        val cblc = cblcTableIndexFormat1(
            glyphId = 4,
            payloadLength = cbdtPayload.size,
            ppemX = 18,
            ppemY = 20,
            bitDepth = 8,
        )
        val source = memoryFontSource(
            sfntFaceWithColorTables(
                numGlyphs = 6,
                "CBDT" to cbdt,
                "CBLC" to cblc,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val bitmap = parsed.color.bitmap ?: error("Expected parsed bitmap font.")
        val glyph = bitmap.glyph(4) ?: error("Expected CBDT/CBLC glyph 4.")

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(cbdt.toUnsignedByteList(), parsed.color.tables.getValue(SFNTTableTag("CBDT")))
        assertEquals(cblc.toUnsignedByteList(), parsed.color.tables.getValue(SFNTTableTag("CBLC")))
        assertEquals(OpenTypeBitmapGlyphSource.CBDT_CBLC, glyph.source)
        assertEquals(4, glyph.glyphId)
        assertEquals(18, glyph.ppemX)
        assertEquals(20, glyph.ppemY)
        assertEquals(8, glyph.bitDepth)
        assertEquals(0, glyph.originOffsetX)
        assertEquals(0, glyph.originOffsetY)
        assertEquals("png ", glyph.imageFormat)
        assertEquals(png.toUnsignedByteList(), glyph.bytes)
        assertEquals(null, bitmap.glyph(3))
    }

    @Test
    fun defaultOpenTypeFaceParserParsesFvarVariationAxes() {
        val fvar = fvarTable(
            testFvarAxis(
                tag = "wght",
                minimum = 0.5,
                defaultValue = 1.0,
                maximum = 2.0,
                flags = 1,
                nameId = 256,
            ),
            testFvarAxis(
                tag = "slnt",
                minimum = -12.5,
                defaultValue = 0.0,
                maximum = 10.0,
                flags = 0,
                nameId = 257,
            ),
        )
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 1),
                "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = 0)),
                "fvar" to fvar,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val weight = parsed.variations.axes[0]
        val slant = parsed.variations.axes[1]

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(2, parsed.variations.axes.size)
        assertEquals(OpenTypeVariationAxisTag(text = "wght", rawValue = 0x77676874), weight.tag)
        assertEquals(0x00008000, weight.minimum.rawValue)
        assertEquals(0.5, weight.minimum.value)
        assertEquals(0x00010000, weight.defaultValue.rawValue)
        assertEquals(1.0, weight.defaultValue.value)
        assertEquals(0x00020000, weight.maximum.rawValue)
        assertEquals(2.0, weight.maximum.value)
        assertEquals(1, weight.flags)
        assertEquals(256, weight.nameId)
        assertEquals(OpenTypeVariationAxisTag(text = "slnt", rawValue = 0x736c6e74), slant.tag)
        assertEquals(fixed16Dot16Raw(-12.5), slant.minimum.rawValue)
        assertEquals(-12.5, slant.minimum.value)
        assertEquals(fixed16Dot16Raw(0.0), slant.defaultValue.rawValue)
        assertEquals(0.0, slant.defaultValue.value)
        assertEquals(fixed16Dot16Raw(10.0), slant.maximum.rawValue)
        assertEquals(10.0, slant.maximum.value)
        assertEquals(0, slant.flags)
        assertEquals(257, slant.nameId)
        assertEquals(fvar.toUnsignedByteList(), parsed.rawTables.getValue(SFNTTableTag("fvar")))
    }

    @Test
    fun defaultOpenTypeFaceParserParsesAvarSegmentMapsInFvarAxisOrder() {
        val fvar = fvarTable(
            testFvarAxis(
                tag = "wght",
                minimum = 0.5,
                defaultValue = 1.0,
                maximum = 2.0,
                flags = 0,
                nameId = 256,
            ),
        )
        val avar = avarTable(
            listOf(
                -1.0 to -1.0,
                0.0 to 0.0,
                0.5 to 0.25,
                1.0 to 1.0,
            ),
        )
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 1),
                "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = 0)),
                "fvar" to fvar,
                "avar" to avar,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(
            listOf(
                OpenTypeAvarSegment(fromCoordinate = -1.0, toCoordinate = -1.0),
                OpenTypeAvarSegment(fromCoordinate = 0.0, toCoordinate = 0.0),
                OpenTypeAvarSegment(fromCoordinate = 0.5, toCoordinate = 0.25),
                OpenTypeAvarSegment(fromCoordinate = 1.0, toCoordinate = 1.0),
            ),
            parsed.variations.axisSegmentMaps.single().segments,
        )
        assertEquals(avar.toUnsignedByteList(), parsed.rawTables.getValue(SFNTTableTag("avar")))
    }

    @Test
    fun defaultOpenTypeFaceParserReportsMalformedFvarTablesAsDiagnostics() {
        val malformedFvar = ByteArray(12)
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 1),
                "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = 0)),
                "fvar" to malformedFvar,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val diagnostic = parsed.diagnostics.single()

        assertEquals(SFNTTableTag("fvar"), diagnostic.table)
        assertEquals("font.sfnt.optional-table-malformed", diagnostic.causeCode)
        assertTrue(
            diagnostic.causeMessage.orEmpty().contains("OpenType fvar table must contain at least 16 bytes"),
            "Unexpected diagnostic: $diagnostic",
        )
        assertEquals(emptyList(), parsed.variations.axes)
        assertEquals(malformedFvar.toUnsignedByteList(), parsed.rawTables.getValue(SFNTTableTag("fvar")))
    }

    @Test
    fun defaultOpenTypeFaceParserReportsFvarAxisArrayOffsetsInsideHeaderAsDiagnostics() {
        val malformedFvar = fvarTable(
            testFvarAxis(
                tag = "wght",
                minimum = 0.5,
                defaultValue = 1.0,
                maximum = 2.0,
                flags = 0,
                nameId = 256,
            ),
        )
        malformedFvar.writeUInt16(4, 0)
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 1),
                "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = 0)),
                "fvar" to malformedFvar,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val diagnostic = parsed.diagnostics.single()

        assertEquals(SFNTTableTag("fvar"), diagnostic.table)
        assertEquals("font.sfnt.optional-table-malformed", diagnostic.causeCode)
        assertTrue(
            diagnostic.causeMessage.orEmpty().contains("axesArrayOffset"),
            "Unexpected diagnostic: $diagnostic",
        )
        assertEquals(emptyList(), parsed.variations.axes)
        assertEquals(malformedFvar.toUnsignedByteList(), parsed.rawTables.getValue(SFNTTableTag("fvar")))
    }

    @Test
    fun defaultOpenTypeFaceParserKeepsMalformedNameAsInvalidTableDiagnostic() {
        val source = memoryFontSource(
            sfntFont(
                "name" to ByteArray(5),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 1),
                "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = 0)),
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)

        val diagnostic = parsed.diagnostics.single()
        assertEquals(SFNTTableTag("name"), diagnostic.table)
        assertEquals("INVALID_TABLE", diagnostic.causeCode)
        assertTrue(
            diagnostic.causeMessage.orEmpty().contains("OpenType name table must contain at least 6 bytes"),
            "Unexpected diagnostic: $diagnostic",
        )
        assertEquals(emptyList(), parsed.names.nameRecords)
        assertEquals(7, parsed.cmap.lookupGlyphId(0x0041))
    }

    @Test
    fun defaultOpenTypeFaceParserSelectsIndexedFacesFromTtcCollection() {
        val firstFamily = "Kanvas TTC One"
        val secondFamily = "Kanvas TTC Two"
        val firstHead = headTable(
            unitsPerEm = 1000,
            bounds = OpenTypeFontBounds(xMin = 0, yMin = -200, xMax = 1000, yMax = 820),
            indexToLocFormat = 0,
        )
        val secondHead = headTable(
            unitsPerEm = 1200,
            bounds = OpenTypeFontBounds(xMin = -20, yMin = -240, xMax = 1080, yMax = 900),
            indexToLocFormat = 1,
        )
        val firstFace = sfntFont(
            "name" to nameTable(
                testNameRecord(
                    platformId = 3,
                    encodingId = 1,
                    languageId = 0x0409,
                    nameId = 1,
                    bytes = firstFamily.toByteArray(Charsets.UTF_16BE),
                ),
            ),
            "cmap" to cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(
                            startCode = 0x0041,
                            endCode = 0x0041,
                            startGlyphId = 7,
                        ),
                    ),
                ),
            ),
            "head" to firstHead,
            "hhea" to hheaTable(
                ascender = 820,
                descender = -180,
                lineGap = 40,
                numberOfHMetrics = 1,
            ),
            "maxp" to maxpTable(numGlyphs = 1),
            "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = -20)),
        )
        val secondFace = sfntFont(
            "name" to nameTable(
                testNameRecord(
                    platformId = 3,
                    encodingId = 1,
                    languageId = 0x0409,
                    nameId = 1,
                    bytes = secondFamily.toByteArray(Charsets.UTF_16BE),
                ),
            ),
            "cmap" to cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(
                            startCode = 0x0042,
                            endCode = 0x0042,
                            startGlyphId = 11,
                        ),
                    ),
                ),
            ),
            "head" to secondHead,
            "hhea" to hheaTable(
                ascender = 900,
                descender = -220,
                lineGap = 20,
                numberOfHMetrics = 1,
            ),
            "maxp" to maxpTable(numGlyphs = 1),
            "hmtx" to hmtxTable(metric(advanceWidth = 610, leftSideBearing = 14)),
        )
        val source = memoryFontSource(ttcFont(firstFace, secondFace))
        val parser = DefaultOpenTypeFaceParser()

        val firstParsed = parser.parse(source, faceIndex = 0)
        val secondParsed = parser.parse(source, faceIndex = 1)
        val secondReparsed = parser.parse(source, faceIndex = 1)

        assertEquals(firstFamily, firstParsed.names.lookupName(nameId = 1))
        assertEquals(secondFamily, secondParsed.names.lookupName(nameId = 1))
        assertEquals(7, firstParsed.cmap.lookupGlyphId(0x0041))
        assertEquals(0, firstParsed.cmap.lookupGlyphId(0x0042))
        assertEquals(0, secondParsed.cmap.lookupGlyphId(0x0041))
        assertEquals(11, secondParsed.cmap.lookupGlyphId(0x0042))
        assertEquals(1000, firstParsed.metrics.unitsPerEm)
        assertEquals(1200, secondParsed.metrics.unitsPerEm)
        assertEquals(500, firstParsed.metrics.horizontalMetrics.single().advanceWidth)
        assertEquals(610, secondParsed.metrics.horizontalMetrics.single().advanceWidth)
        assertEquals(firstHead.toUnsignedByteList(), firstParsed.rawTables.getValue(SFNTTableTag("head")))
        assertEquals(secondHead.toUnsignedByteList(), secondParsed.rawTables.getValue(SFNTTableTag("head")))
        assertTrue(firstParsed.id != secondParsed.id, "TTC face IDs must include faceIndex.")
        assertEquals(secondParsed.id, secondReparsed.id)
        assertEquals(emptyList(), firstParsed.diagnostics)
        assertEquals(emptyList(), secondParsed.diagnostics)
    }

    @Test
    fun openTypeFaceDataProducesDeterministicFaceEvidenceJson() {
        val malformedName = ByteArray(5)
        val cmap = cmapTable(
            testCMapRecord(
                platformId = 3,
                encodingId = 10,
                subtable = format12Subtable(
                    testFormat12Group(startCharCode = 0x1f600, endCharCode = 0x1f600, startGlyphId = 300),
                ),
            ),
        )
        val head = headTable(
            unitsPerEm = 1000,
            bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
            indexToLocFormat = 1,
        )
        val hhea = hheaTable(
            ascender = 820,
            descender = -180,
            lineGap = 40,
            numberOfHMetrics = 2,
        )
        val maxp = maxpTable(numGlyphs = 2)
        val hmtx = hmtxTable(
            metric(advanceWidth = 500, leftSideBearing = -20),
            metric(advanceWidth = 450, leftSideBearing = 7),
        )
        val source = memoryFontSource(
            ttcFont(
                sfntFont(),
                sfntFont(
                    "hhea" to hhea,
                    "name" to malformedName,
                    "hmtx" to hmtx,
                    "head" to head,
                    "cmap" to cmap,
                    "maxp" to maxp,
                ),
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source, faceIndex = 1)
        val evidence = parsed.faceEvidence()

        assertEquals(1, evidence.faceIndex)
        assertEquals(source.id, evidence.sourceId)
        assertEquals(parsed.id, evidence.typefaceId)
        assertEquals("0x00010000", evidence.scalerType)
        assertEquals("TrueType", evidence.scalerTypeLabel)
        assertEquals(listOf("cmap", "head", "hhea", "hmtx", "maxp", "name"), evidence.tableRecords.map { it.tag })
        assertEquals(cmap.size, evidence.tableRecords.single { it.tag == "cmap" }.rawByteLength)
        assertEquals(cmap.sha256Hex(), evidence.tableRecords.single { it.tag == "cmap" }.rawSha256)
        assertEquals(
            OpenTypeCMapEvidence(
                platformId = 3,
                encodingId = 10,
                format = 12,
                offset = 12,
                length = 28,
                mappingKind = "format12-segmented-coverage",
                mappingEntryCount = 1,
                encodingRecordCount = 1,
                parsedSubtableCount = 1,
            ),
            evidence.preferredCMap,
        )
        assertEquals(1000, evidence.metrics.unitsPerEm)
        assertEquals(2, evidence.metrics.numGlyphs)
        assertEquals(2, evidence.metrics.horizontalMetricCount)
        assertEquals(SFNTTableTag("name"), evidence.diagnostics.single().table)
        assertEquals("INVALID_TABLE", evidence.diagnostics.single().causeCode)
        assertEquals(evidence.toCanonicalJson(), evidence.toCanonicalJson())
        assertEquals(
            """
            {
              "faceIndex": 1,
              "sourceId": "${source.id.value}",
              "typefaceId": "${parsed.id.value}",
              "sourceKind": "MEMORY",
              "scalerType": "0x00010000",
              "scalerTypeLabel": "TrueType",
              "tables": [
                {
                  "tag": "cmap",
                  "checksum": "0x00000000",
                  "offset": 243,
                  "length": 40,
                  "rawByteLength": 40,
                  "rawSha256": "${cmap.sha256Hex()}"
                },
                {
                  "tag": "head",
                  "checksum": "0x00000000",
                  "offset": 189,
                  "length": 54,
                  "rawByteLength": 54,
                  "rawSha256": "${head.sha256Hex()}"
                },
                {
                  "tag": "hhea",
                  "checksum": "0x00000000",
                  "offset": 140,
                  "length": 36,
                  "rawByteLength": 36,
                  "rawSha256": "${hhea.sha256Hex()}"
                },
                {
                  "tag": "hmtx",
                  "checksum": "0x00000000",
                  "offset": 181,
                  "length": 8,
                  "rawByteLength": 8,
                  "rawSha256": "${hmtx.sha256Hex()}"
                },
                {
                  "tag": "maxp",
                  "checksum": "0x00000000",
                  "offset": 283,
                  "length": 6,
                  "rawByteLength": 6,
                  "rawSha256": "${maxp.sha256Hex()}"
                },
                {
                  "tag": "name",
                  "checksum": "0x00000000",
                  "offset": 176,
                  "length": 5,
                  "rawByteLength": 5,
                  "rawSha256": "${malformedName.sha256Hex()}"
                }
              ],
              "directoryDiagnostics": [],
              "preferredCMap": {
                "platformId": 3,
                "encodingId": 10,
                "format": 12,
                "offset": 12,
                "length": 28,
                "mappingKind": "format12-segmented-coverage",
                "mappingEntryCount": 1,
                "encodingRecordCount": 1,
                "parsedSubtableCount": 1
              },
              "metrics": {
                "unitsPerEm": 1000,
                "ascender": 820,
                "descender": -180,
                "lineGap": 40,
                "numGlyphs": 2,
                "numberOfHMetrics": 2,
                "horizontalMetricCount": 2,
                "indexToLocFormat": 1,
                "bounds": {
                  "xMin": -40,
                  "yMin": -200,
                  "xMax": 980,
                  "yMax": 840
                }
              },
              "diagnostics": [
                {
                  "table": "name",
                  "causeCode": "INVALID_TABLE",
                  "message": "Unable to parse OpenType table name.",
                  "causeMessage": "OpenType name table must contain at least 6 bytes for the header."
                }
              ]
            }
            """.trimIndent() + "\n",
            evidence.toCanonicalJson(),
        )
    }

    @Test
    fun openTypeFaceDataKeepsExistingPositionalConstructorOrder() {
        val source = memoryFontSource(byteArrayOf())
        val directory = SFNTTableDirectory(scalerType = 0x00010000u)
        val cmap = CMapTable(subtables = mapOf("compat" to listOf(1, 2, 3)))
        val names = NameTable(records = mapOf("3:1:1033:1" to "Compat Sans"))

        val data = OpenTypeFaceData(
            TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440190")),
            source,
            directory,
            cmap,
            names,
        )

        assertEquals(cmap, data.cmap)
        assertEquals(names, data.names)
        assertEquals(0, data.faceIndex)
    }

    @Test
    fun openTypeFaceEvidenceIncludesBoundedDirectoryDiagnostics() {
        val source = memoryFontSource(ByteArray(96))
        val data = OpenTypeFaceData(
            id = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440193")),
            source = source,
            directory = SFNTTableDirectory(
                scalerType = 0x00010000u,
                tables = listOf(
                    SFNTTableRecord(tag = SFNTTableTag("name"), checksum = 0u, offset = 32u, length = 16u),
                    SFNTTableRecord(tag = SFNTTableTag("name"), checksum = 1u, offset = 48u, length = 8u),
                    SFNTTableRecord(tag = SFNTTableTag("glyf"), checksum = 2u, offset = 64u, length = 0u),
                    SFNTTableRecord(tag = SFNTTableTag("post"), checksum = 3u, offset = 88u, length = 16u),
                ),
            ),
        )

        val evidence = data.faceEvidence(
            requiredTables = setOf(SFNTTableTag("cmap"), SFNTTableTag("glyf")),
        )

        assertEquals(
            listOf(
                "font.sfnt.required-table-missing tag=\"cmap\" offset=none length=none sourceLength=96 message=\"Required table is not present.\"",
                "font.sfnt.required-table-missing tag=\"glyf\" offset=64 length=0 sourceLength=96 message=\"Required table is present with zero length.\"",
                "font.sfnt.table-duplicate tag=\"name\" offset=48 length=8 sourceLength=96 message=\"Duplicate SFNT table tag.\"",
                "font.sfnt.table-out-of-bounds tag=\"post\" offset=88 length=16 sourceLength=96 message=\"Table range exceeds source length.\"",
            ),
            evidence.directoryDiagnostics.map { it.dump() },
        )
        assertTrue(evidence.toCanonicalJson().contains("\"directoryDiagnostics\""))
    }

    @Test
    fun openTypeFaceEvidenceRejectsUnstableConstructorInputs() {
        val sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440191"))
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440192"))
        val metrics = OpenTypeMetricsEvidence(
            unitsPerEm = null,
            ascender = null,
            descender = null,
            lineGap = null,
            numGlyphs = null,
            numberOfHMetrics = null,
            horizontalMetricCount = 0,
            indexToLocFormat = null,
            bounds = null,
        )
        val firstDiagnostic = OpenTypeParseDiagnosticEvidence(
            table = SFNTTableTag("name"),
            causeCode = "INVALID_TABLE",
            message = "name table malformed",
            causeMessage = null,
        )
        val secondDiagnostic = OpenTypeParseDiagnosticEvidence(
            table = SFNTTableTag("cmap"),
            causeCode = "INVALID_TABLE",
            message = "cmap table malformed",
            causeMessage = null,
        )

        val unsortedDiagnostics = assertFailsWith<IllegalArgumentException> {
            OpenTypeFaceEvidence(
                faceIndex = 0,
                sourceId = sourceId,
                typefaceId = typefaceId,
                sourceKind = FontSourceKind.MEMORY,
                scalerType = "0x00010000",
                scalerTypeLabel = "TrueType",
                tableRecords = emptyList(),
                preferredCMap = null,
                metrics = metrics,
                diagnostics = listOf(firstDiagnostic, secondDiagnostic),
            )
        }
        assertTrue(unsortedDiagnostics.message.orEmpty().contains("diagnostics must be sorted"))

        val malformedCode = assertFailsWith<IllegalArgumentException> {
            OpenTypeParseDiagnosticEvidence(
                table = SFNTTableTag("name"),
                causeCode = "INVALID\nTABLE",
                message = "bad code",
                causeMessage = null,
            )
        }
        assertTrue(malformedCode.message.orEmpty().contains("stable one-line"))

        val malformedTag = assertFailsWith<IllegalArgumentException> {
            OpenTypeParseDiagnosticEvidence(
                table = SFNTTableTag("cm\np"),
                causeCode = "INVALID_TABLE",
                message = "bad tag",
                causeMessage = null,
            )
        }
        assertTrue(malformedTag.message.orEmpty().contains("printable ASCII SFNT tag"))
    }

    @Test
    fun defaultOpenTypeFaceParserRejectsRawSfntNonZeroFaceIndex() {
        val error = assertFailsWith<IllegalArgumentException> {
            DefaultOpenTypeFaceParser().parse(memoryFontSource(sfntFont()), faceIndex = 1)
        }

        assertEquals("Single-face SFNT sources support only faceIndex 0; received 1.", error.message)
    }

    @Test
    fun defaultOpenTypeFaceParserRejectsTtcFaceIndexOutsideCollectionRange() {
        val source = memoryFontSource(ttcFont(sfntFont(), sfntFont()))

        val error = assertFailsWith<IllegalArgumentException> {
            DefaultOpenTypeFaceParser().parse(source, faceIndex = 2)
        }

        assertTrue(
            error.message.orEmpty().contains("TTC/ttcf faceIndex 2 is outside collection range 0..1"),
            "Unexpected error message: ${error.message}",
        )
    }

    @Test
    fun defaultOpenTypeFaceParserRejectsMalformedTtcOffsetTable() {
        val malformed = byteArrayOf(
            't'.code.toByte(), 't'.code.toByte(), 'c'.code.toByte(), 'f'.code.toByte(),
            0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x10,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            DefaultOpenTypeFaceParser().parse(memoryFontSource(malformed), faceIndex = 0)
        }

        assertTrue(
            error.message.orEmpty().contains("TTC/ttcf offset table range [12, 20) exceeds source length 16"),
            "Unexpected error message: ${error.message}",
        )
    }

    @Test
    fun defaultOpenTypeFaceParserPreservesMetricDiagnosticTableForInvalidHead() {
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 0,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 1),
                "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = 0)),
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)

        val diagnostic = parsed.diagnostics.single()
        assertEquals(SFNTTableTag("head"), diagnostic.table)
        assertEquals("INVALID_METRICS", diagnostic.causeCode)
        assertTrue(
            diagnostic.causeMessage.orEmpty().contains("OpenType head unitsPerEm 0"),
            "Unexpected diagnostic: $diagnostic",
        )
    }

    @Test
    fun defaultReaderRejectsUnknownSingleFaceScalerTypes() {
        val source = sfntFont().also { font ->
            font.writeUInt32(0, 0x12345678)
        }

        val error = assertFailsWith<IllegalArgumentException> {
            DefaultSFNTReader().readDirectory(memoryFontSource(source))
        }

        assertTrue(
            error.message.orEmpty().contains("Unsupported SFNT scaler type 0x12345678"),
            "Unexpected error message: ${error.message}",
        )
    }

    @Test
    fun nameTableParserDecodesTypedRecordsAndKeepsLegacyRecordMap() {
        val family = "Kanvas Sans"
        val subfamily = "Regular"

        val names = OpenTypeNameTableParser.parse(
            nameTable(
                testNameRecord(
                    platformId = 3,
                    encodingId = 1,
                    languageId = 0x0409,
                    nameId = 1,
                    bytes = family.toByteArray(Charsets.UTF_16BE),
                ),
                testNameRecord(
                    platformId = 3,
                    encodingId = 1,
                    languageId = 0x0409,
                    nameId = 2,
                    bytes = subfamily.toByteArray(Charsets.UTF_16BE),
                ),
                testNameRecord(
                    platformId = 1,
                    encodingId = 0,
                    languageId = 0,
                    nameId = 0,
                    bytes = byteArrayOf('C'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xe9.toByte()),
                ),
            ),
        )

        assertEquals(0, names.format)
        assertEquals(42, names.stringOffset)
        assertEquals(3, names.nameRecords.size)

        val familyRecord = names.nameRecords[0]
        assertEquals(3, familyRecord.platformId)
        assertEquals(1, familyRecord.encodingId)
        assertEquals(0x0409, familyRecord.languageId)
        assertEquals(1, familyRecord.nameId)
        assertEquals(family, familyRecord.value)

        assertEquals("Caf\u00e9", names.nameRecords[2].value)
        assertEquals(family, names.lookupName(nameId = 1))
        assertEquals(subfamily, names.lookupName(nameId = 2, languageId = 0x0409))
        assertEquals(family, names.records["3:1:1033:1"])
    }

    @Test
    fun nameTableParserRejectsShortTablesWithIllegalArgumentException() {
        val error = assertFailsWith<IllegalArgumentException> {
            OpenTypeNameTableParser.parse(ByteArray(5))
        }

        assertEquals("OpenType name table must contain at least 6 bytes for the header.", error.message)
    }

    @Test
    fun nameTableParserRejectsInvalidStringOffsetsWithIllegalArgumentException() {
        val table = ByteArray(18)
        table.writeUInt16(0, 0)
        table.writeUInt16(2, 1)
        table.writeUInt16(4, 18)
        table.writeUInt16(6, 3)
        table.writeUInt16(8, 1)
        table.writeUInt16(10, 0x0409)
        table.writeUInt16(12, 1)
        table.writeUInt16(14, 2)
        table.writeUInt16(16, 0)

        val error = assertFailsWith<IllegalArgumentException> {
            OpenTypeNameTableParser.parse(table)
        }

        assertTrue(
            error.message.orEmpty().contains("OpenType name record 0 string range"),
            "Unexpected error message: ${error.message}",
        )
    }

    @Test
    fun nameTableParserRejectsOddWindowsUtf16LengthsWithIllegalArgumentException() {
        val error = assertFailsWith<IllegalArgumentException> {
            OpenTypeNameTableParser.parse(
                nameTable(
                    testNameRecord(
                        platformId = 3,
                        encodingId = 1,
                        languageId = 0x0409,
                        nameId = 1,
                        bytes = byteArrayOf(0x00),
                    ),
                ),
            )
        }

        assertTrue(
            error.message.orEmpty().contains("UTF-16BE string length must be even"),
            "Unexpected error message: ${error.message}",
        )
    }

    @Test
    fun nameTableParserDecodesUnicodePlatformAsUtf16Be() {
        val family = "Kanvas Sans"

        val names = OpenTypeNameTableParser.parse(
            nameTable(
                testNameRecord(
                    platformId = 0,
                    encodingId = 3,
                    languageId = 0,
                    nameId = 1,
                    bytes = family.toByteArray(Charsets.UTF_16BE),
                ),
            ),
        )

        assertEquals(family, names.nameRecords.single().value)
        assertEquals(family, names.lookupName(nameId = 1))
    }

    @Test
    fun nameTableExposesPreferredAndLocalizedNamesWithLanguageTags() {
        val names = OpenTypeNameTableParser.parse(
            nameTable(
                testNameRecord(
                    platformId = 1,
                    encodingId = 0,
                    languageId = 1,
                    nameId = 1,
                    bytes = "Famille Mac".toByteArray(Charsets.ISO_8859_1),
                ),
                testNameRecord(
                    platformId = 3,
                    encodingId = 1,
                    languageId = 0x0409,
                    nameId = 1,
                    bytes = "Kanvas Sans".toByteArray(Charsets.UTF_16BE),
                ),
                testNameRecord(
                    platformId = 3,
                    encodingId = 1,
                    languageId = 0x040C,
                    nameId = 4,
                    bytes = "Kanvas Sans Regular".toByteArray(Charsets.UTF_16BE),
                ),
                testNameRecord(
                    platformId = 3,
                    encodingId = 1,
                    languageId = 0x0409,
                    nameId = 2,
                    bytes = "Bold Italic".toByteArray(Charsets.UTF_16BE),
                ),
                testNameRecord(
                    platformId = 3,
                    encodingId = 1,
                    languageId = 0x0409,
                    nameId = 6,
                    bytes = "KanvasSans-BoldItalic".toByteArray(Charsets.UTF_16BE),
                ),
            ),
        )

        assertEquals("Kanvas Sans", names.preferredFamilyName())
        assertEquals("Bold Italic", names.preferredStyleName())
        assertEquals("KanvasSans-BoldItalic", names.preferredPostScriptName())
        assertEquals(
            listOf(
                OpenTypeLocalizedName("Famille Mac", "fr", 1),
                OpenTypeLocalizedName("Kanvas Sans", "en-US", 1),
                OpenTypeLocalizedName("Kanvas Sans Regular", "fr-FR", 4),
            ),
            names.localizedFamilyNames(),
        )
    }

    @Test
    fun nameTableParserRejectsOddUnicodePlatformUtf16LengthsWithIllegalArgumentException() {
        val error = assertFailsWith<IllegalArgumentException> {
            OpenTypeNameTableParser.parse(
                nameTable(
                    testNameRecord(
                        platformId = 0,
                        encodingId = 3,
                        languageId = 0,
                        nameId = 1,
                        bytes = byteArrayOf(0x00),
                    ),
                ),
            )
        }

        assertTrue(
            error.message.orEmpty().contains("UTF-16BE string length must be even"),
            "Unexpected error message: ${error.message}",
        )
    }

    @Test
    fun cmapTableParserParsesFormat4BmpMappingAndKeepsLegacySubtableBytes() {
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(
                            startCode = 0x0041,
                            endCode = 0x0041,
                            startGlyphId = 7,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(0, table.version)
        assertEquals(1, table.encodingRecords.size)
        assertEquals(CMapEncodingRecord(platformId = 3, encodingId = 1, offset = 12, format = 4), table.encodingRecords.single())
        assertEquals(4, table.preferredSubtable?.format)
        assertEquals(7, table.lookupGlyphId(0x0041))
        assertEquals(0, table.lookupGlyphId(0x0042))
        assertEquals(32, table.subtables["3:1:4"]?.size)
    }

    @Test
    fun cmapTableParserTreatsFormat4DeltaResolvedGlyphZeroAsMissing() {
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(
                            startCode = 0x0041,
                            endCode = 0x0041,
                            startGlyphId = 0,
                        ),
                        testFormat4Segment(
                            startCode = 0x0042,
                            endCode = 0x0042,
                            startGlyphId = 7,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(0, table.lookupGlyphId(0x0041))
        assertEquals(7, table.lookupGlyphId(0x0042))
    }

    @Test
    fun cmapTableParserTreatsFormat4RangeOffsetRawGlyphZeroAsMissing() {
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4RangeOffsetSubtable(
                        startCode = 0x0041,
                        endCode = 0x0042,
                        rawGlyphIds = listOf(0, 11),
                    ),
                ),
            ),
        )

        assertEquals(0, table.lookupGlyphId(0x0041))
        assertEquals(11, table.lookupGlyphId(0x0042))
    }

    @Test
    fun cmapTableParserPrefersWindowsFormat12ForUnicodeLookup() {
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(
                            startCode = 0x0041,
                            endCode = 0x0041,
                            startGlyphId = 7,
                        ),
                    ),
                ),
                testCMapRecord(
                    platformId = 3,
                    encodingId = 10,
                    subtable = format12Subtable(
                        testFormat12Group(startCharCode = 0x0041, endCharCode = 0x0041, startGlyphId = 9),
                        testFormat12Group(startCharCode = 0x1f600, endCharCode = 0x1f600, startGlyphId = 300),
                    ),
                ),
            ),
        )

        assertEquals(12, table.preferredSubtable?.format)
        assertEquals(3, table.preferredSubtable?.platformId)
        assertEquals(10, table.preferredSubtable?.encodingId)
        assertEquals(9, table.lookupGlyphId(0x0041))
        assertEquals(300, table.lookupGlyphId(0x1f600))
    }

    @Test
    fun cmapTableParserTreatsFormat12ResolvedGlyphZeroAsMissing() {
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 10,
                    subtable = format12Subtable(
                        testFormat12Group(startCharCode = 0x0041, endCharCode = 0x0041, startGlyphId = 0),
                        testFormat12Group(startCharCode = 0x0042, endCharCode = 0x0042, startGlyphId = 7),
                    ),
                ),
            ),
        )

        assertEquals(0, table.lookupGlyphId(0x0041))
        assertEquals(7, table.lookupGlyphId(0x0042))
    }

    @Test
    fun cmapTableParserReturnsGlyphZeroForMissingCodepoints() {
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 10,
                    subtable = format12Subtable(
                        testFormat12Group(startCharCode = 0x0041, endCharCode = 0x0041, startGlyphId = 7),
                    ),
                ),
            ),
        )

        assertEquals(7, table.lookupGlyphId(0x0041))
        assertEquals(0, table.lookupGlyphId(0x0042))
        assertEquals(0, table.lookupGlyphId(0x0042, variationSelector = 0xfe0f))
    }

    @Test
    fun cmapTableParserAppliesFormat14NonDefaultVariationSelectors() {
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 10,
                    subtable = format12Subtable(
                        testFormat12Group(startCharCode = 0x2764, endCharCode = 0x2764, startGlyphId = 20),
                        testFormat12Group(startCharCode = 0x1f600, endCharCode = 0x1f600, startGlyphId = 40),
                    ),
                ),
                testCMapRecord(
                    platformId = 0,
                    encodingId = 5,
                    subtable = format14Subtable(
                        variationSelector = 0xfe0f,
                        defaultRanges = listOf(0x2764 to 0),
                        nonDefaultMappings = listOf(0x1f600 to 99),
                    ),
                ),
            ),
        )

        assertEquals(12, table.preferredSubtable?.format)
        assertEquals(14, table.variationSubtable?.format)
        assertEquals(20, table.lookupGlyphId(0x2764))
        assertEquals(20, table.lookupGlyphId(0x2764, variationSelector = 0xfe0f))
        assertEquals(40, table.lookupGlyphId(0x1f600))
        assertEquals(99, table.lookupGlyphId(0x1f600, variationSelector = 0xfe0f))
        assertEquals(0, table.lookupGlyphId(0x0041, variationSelector = 0xfe0f))
    }

    @Test
    fun cmapTableParserReportsUnsupportedAndUnusableCMapDiagnostics() {
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 10,
                    subtable = format13Subtable(),
                ),
            ),
        )

        assertEquals(null, table.preferredSubtable)
        assertEquals(
            listOf(
                "font.sfnt.cmap-format-unsupported format=13 platformId=3 encodingId=10 offset=12 message=\"Unsupported cmap format 13 is not selected for Unicode lookup.\"",
                "font.sfnt.cmap-unusable format=none platformId=none encodingId=none offset=none message=\"No usable Unicode cmap subtable was parsed.\"",
            ),
            table.diagnostics.map { it.dump() },
        )
    }

    @Test
    fun cmapTableParserPrefersFormat4OverLegacyFallbackSubtables() {
        val fallbackGlyphIds = MutableList(256) { 0 }
        fallbackGlyphIds[0x41] = 3
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 0,
                    encodingId = 0,
                    subtable = format6Subtable(firstCode = 0x41, glyphIds = listOf(2)),
                ),
                testCMapRecord(
                    platformId = 1,
                    encodingId = 0,
                    subtable = format0Subtable(fallbackGlyphIds),
                ),
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(
                            startCode = 0x0041,
                            endCode = 0x0041,
                            startGlyphId = 7,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(4, table.preferredSubtable?.format)
        assertEquals(7, table.lookupGlyphId(0x0041))
    }

    @Test
    fun cmapMapReportCoversKfontM2CMapEvidence() {
        val actual = cmapMapReport()
        val expectedPath = fixturePath("font/fixtures/expected/pure-kotlin-text/cmap-map.json")

        if (!Files.exists(expectedPath)) {
            error("Missing cmap-map.json golden. Actual report:\n$actual")
        }

        assertEquals(Files.readString(expectedPath), actual)
        assertEquals(actual, cmapMapReport())
        assertTrue(actual.contains("\"KFONT-M2-003\""))
        assertTrue(actual.contains("\"format\": 12"))
        assertTrue(actual.contains("\"format\": 14"))
        assertTrue(actual.contains("\"format\": 6"))
        assertTrue(actual.contains("\"format\": 0"))
        assertTrue(actual.contains("\"code\": \"font.sfnt.cmap-format-unsupported\""))
        assertTrue(actual.contains("\"code\": \"font.sfnt.cmap-unusable\""))
        assertTrue(actual.contains("\"entryId\": \"malformed-generated-format13-refused\""))
        assertTrue(actual.contains("\"glyphId\": 0"))
        assertTrue(actual.contains("\"claimPromotionAllowed\": false"))
        listOf("GPU", "Skia", "HarfBuzz", "FreeType", "Fontations", "CoreText", "DirectWrite").forEach { token ->
            assertFalse(actual.contains(token), "cmap-map.json must not contain hidden engine token $token")
        }
    }

    @Test
    fun cmapTableParserParsesFormat0ByteEncodingMapping() {
        val glyphIds = MutableList(256) { 0 }
        glyphIds[0x41] = 7
        glyphIds[0x42] = 0
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 1,
                    encodingId = 0,
                    subtable = format0Subtable(glyphIds),
                ),
            ),
        )

        assertEquals(CMapEncodingRecord(platformId = 1, encodingId = 0, offset = 12, format = 0), table.encodingRecords.single())
        assertEquals(0, table.preferredSubtable?.format)
        assertEquals(7, table.lookupGlyphId(0x41))
        assertEquals(0, table.lookupGlyphId(0x42))
        assertEquals(0, table.lookupGlyphId(0x100))
        assertEquals(262, table.subtables["1:0:0"]?.size)
    }

    @Test
    fun cmapTableParserParsesFormat0MacRomanMappingAsUnicodeLookup() {
        val glyphIds = MutableList(256) { 0 }
        glyphIds[0x41] = 7
        glyphIds[0x80] = 11
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 1,
                    encodingId = 0,
                    subtable = format0Subtable(glyphIds),
                ),
            ),
        )

        assertEquals(7, table.lookupGlyphId(0x0041))
        assertEquals(11, table.lookupGlyphId(0x00C4))
        assertEquals(0, table.lookupGlyphId(0x0080))
    }

    @Test
    fun cmapTableParserParsesFormat6TrimmedMapping() {
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 0,
                    encodingId = 0,
                    subtable = format6Subtable(firstCode = 0x41, glyphIds = listOf(7, 0, 9)),
                ),
            ),
        )

        assertEquals(6, table.preferredSubtable?.format)
        assertEquals(7, table.lookupGlyphId(0x41))
        assertEquals(0, table.lookupGlyphId(0x42))
        assertEquals(9, table.lookupGlyphId(0x43))
        assertEquals(0, table.lookupGlyphId(0x44))
        assertEquals(16, table.subtables["0:0:6"]?.size)
    }

    @Test
    fun cmapTableParserParsesFormat6MacRomanMappingAsUnicodeLookup() {
        val table = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 1,
                    encodingId = 0,
                    subtable = format6Subtable(firstCode = 0x80, glyphIds = listOf(11, 12)),
                ),
            ),
        )

        assertEquals(11, table.lookupGlyphId(0x00C4))
        assertEquals(12, table.lookupGlyphId(0x00C5))
        assertEquals(0, table.lookupGlyphId(0x0080))
    }

    @Test
    fun cmapTableParserRejectsEncodingRecordOffsetsWithIllegalArgumentException() {
        val table = ByteArray(12)
        table.writeUInt16(0, 0)
        table.writeUInt16(2, 1)
        table.writeUInt16(4, 3)
        table.writeUInt16(6, 1)
        table.writeUInt32(8, 40)

        val error = assertFailsWith<IllegalArgumentException> {
            OpenTypeCMapTableParser.parse(table)
        }

        assertTrue(
            error.message.orEmpty().contains("OpenType cmap encoding record 0 subtable offset 40"),
            "Unexpected error message: ${error.message}",
        )
    }

    @Test
    fun cmapTableParserRejectsFormat12GroupsOutsideDeclaredLengthWithIllegalArgumentException() {
        val subtable = format12Subtable(
            testFormat12Group(startCharCode = 0x1f600, endCharCode = 0x1f600, startGlyphId = 300),
        )
        subtable.writeUInt32(4, 16)

        val error = assertFailsWith<IllegalArgumentException> {
            OpenTypeCMapTableParser.parse(
                cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 10,
                        subtable = subtable,
                    ),
                ),
            )
        }

        assertTrue(
            error.message.orEmpty().contains("OpenType cmap format 12 group array"),
            "Unexpected error message: ${error.message}",
        )
    }

    @Test
    fun metricsTableParserParsesHeadHheaMaxpAndHmtx() {
        val metrics = OpenTypeMetricsTableParser.parse(
            head = headTable(
                unitsPerEm = 1000,
                bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
                indexToLocFormat = 1,
            ),
            hhea = hheaTable(
                ascender = 820,
                descender = -180,
                lineGap = 40,
                numberOfHMetrics = 2,
            ),
            maxp = maxpTable(numGlyphs = 4),
            hmtx = hmtxTable(
                metric(advanceWidth = 500, leftSideBearing = -20),
                metric(advanceWidth = 450, leftSideBearing = 7),
                extraLeftSideBearing(-9),
                extraLeftSideBearing(11),
            ),
        )

        assertEquals(1000, metrics.unitsPerEm)
        assertEquals(1, metrics.indexToLocFormat)
        assertEquals(OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840), metrics.bounds)
        assertEquals(4, metrics.numGlyphs)
        assertEquals(820, metrics.ascender)
        assertEquals(-180, metrics.descender)
        assertEquals(40, metrics.lineGap)
        assertEquals(2, metrics.numberOfHMetrics)
        assertEquals(
            listOf(
                HorizontalGlyphMetric(glyphId = 0, advanceWidth = 500, leftSideBearing = -20),
                HorizontalGlyphMetric(glyphId = 1, advanceWidth = 450, leftSideBearing = 7),
                HorizontalGlyphMetric(glyphId = 2, advanceWidth = 450, leftSideBearing = -9),
                HorizontalGlyphMetric(glyphId = 3, advanceWidth = 450, leftSideBearing = 11),
            ),
            metrics.horizontalMetrics,
        )
    }

    @Test
    fun metricsTableParserParsesOptionalTypographicMetricsFromOs2AndPost() {
        val metrics = OpenTypeMetricsTableParser.parse(
            head = headTable(
                unitsPerEm = 1000,
                bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
                indexToLocFormat = 1,
            ),
            hhea = hheaTable(
                ascender = 820,
                descender = -180,
                lineGap = 40,
                maxCharWidth = 500,
                numberOfHMetrics = 1,
            ),
            maxp = maxpTable(numGlyphs = 1),
            hmtx = hmtxTable(metric(advanceWidth = 500, leftSideBearing = -20)),
            os2 = os2Table(
                averageCharWidth = 477,
                xHeight = 512,
                capHeight = 702,
                strikeoutThickness = 51,
                strikeoutPosition = 262,
            ),
            post = postTable(
                underlinePosition = -96,
                underlineThickness = 44,
            ),
        )

        assertEquals(512, metrics.xHeight)
        assertEquals(702, metrics.capHeight)
        assertEquals(477, metrics.averageCharWidth)
        assertEquals(500, metrics.maxCharWidth)
        assertEquals(44, metrics.underlineThickness)
        assertEquals(-96, metrics.underlinePosition)
        assertEquals(51, metrics.strikeoutThickness)
        assertEquals(262, metrics.strikeoutPosition)
    }

    @Test
    fun metricsTableParserParsesOptionalVerticalMetricsFromVheaAndVmtx() {
        val metrics = OpenTypeMetricsTableParser.parse(
            head = headTable(
                unitsPerEm = 1000,
                bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
                indexToLocFormat = 1,
            ),
            hhea = hheaTable(
                ascender = 820,
                descender = -180,
                lineGap = 40,
                numberOfHMetrics = 1,
            ),
            maxp = maxpTable(numGlyphs = 3),
            hmtx = hmtxTable(
                metric(advanceWidth = 500, leftSideBearing = -20),
                extraLeftSideBearing(7),
                extraLeftSideBearing(11),
            ),
            vhea = vheaTable(
                ascender = 910,
                descender = -320,
                lineGap = 70,
                maxAdvanceHeight = 760,
                numberOfVMetrics = 2,
            ),
            vmtx = vmtxTable(
                verticalMetric(advanceHeight = 700, topSideBearing = 40),
                verticalMetric(advanceHeight = 680, topSideBearing = -15),
                extraTopSideBearing(22),
            ),
        )

        assertEquals(910, metrics.verticalAscender)
        assertEquals(-320, metrics.verticalDescender)
        assertEquals(70, metrics.verticalLineGap)
        assertEquals(760, metrics.maxAdvanceHeight)
        assertEquals(2, metrics.numberOfVMetrics)
        assertEquals(
            listOf(
                VerticalGlyphMetric(glyphId = 0, advanceHeight = 700, topSideBearing = 40),
                VerticalGlyphMetric(glyphId = 1, advanceHeight = 680, topSideBearing = -15),
                VerticalGlyphMetric(glyphId = 2, advanceHeight = 680, topSideBearing = 22),
            ),
            metrics.verticalMetrics,
        )
    }

    @Test
    fun defaultOpenTypeFaceParserExposesOptionalTypographicMetricsWhenTablesExist() {
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
                    indexToLocFormat = 1,
                ),
                "hhea" to hheaTable(
                    ascender = 820,
                    descender = -180,
                    lineGap = 40,
                    maxCharWidth = 500,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 1),
                "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = -20)),
                "OS/2" to os2Table(
                    averageCharWidth = 477,
                    xHeight = 512,
                    capHeight = 702,
                    strikeoutThickness = 51,
                    strikeoutPosition = 262,
                ),
                "post" to postTable(
                    underlinePosition = -96,
                    underlineThickness = 44,
                ),
            ),
        )

        val metrics = DefaultOpenTypeFaceParser().parse(source).metrics

        assertEquals(512, metrics.xHeight)
        assertEquals(702, metrics.capHeight)
        assertEquals(477, metrics.averageCharWidth)
        assertEquals(500, metrics.maxCharWidth)
        assertEquals(44, metrics.underlineThickness)
        assertEquals(-96, metrics.underlinePosition)
        assertEquals(51, metrics.strikeoutThickness)
        assertEquals(262, metrics.strikeoutPosition)
    }

    @Test
    fun defaultOpenTypeFaceParserKeepsHorizontalMetricsWhenOptionalVerticalMetricsAreMalformed() {
        val malformedVhea = ByteArray(35)
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
                    indexToLocFormat = 1,
                ),
                "hhea" to hheaTable(
                    ascender = 820,
                    descender = -180,
                    lineGap = 40,
                    numberOfHMetrics = 2,
                ),
                "maxp" to maxpTable(numGlyphs = 3),
                "hmtx" to hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = -20),
                    metric(advanceWidth = 450, leftSideBearing = 7),
                    extraLeftSideBearing(11),
                ),
                "vhea" to malformedVhea,
                "vmtx" to vmtxTable(
                    verticalMetric(advanceHeight = 700, topSideBearing = 40),
                    verticalMetric(advanceHeight = 680, topSideBearing = -15),
                    extraTopSideBearing(22),
                ),
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val diagnostic = parsed.diagnostics.single()

        assertEquals(
            listOf(
                HorizontalGlyphMetric(glyphId = 0, advanceWidth = 500, leftSideBearing = -20),
                HorizontalGlyphMetric(glyphId = 1, advanceWidth = 450, leftSideBearing = 7),
                HorizontalGlyphMetric(glyphId = 2, advanceWidth = 450, leftSideBearing = 11),
            ),
            parsed.metrics.horizontalMetrics,
        )
        assertEquals(SFNTTableTag("vhea"), diagnostic.table)
        assertEquals("font.sfnt.optional-table-malformed", diagnostic.causeCode)
        assertTrue(
            diagnostic.causeMessage.orEmpty().contains("OpenType vhea table must contain at least 36 bytes"),
            "Unexpected diagnostic: $diagnostic",
        )
        assertEquals(null, parsed.metrics.verticalAscender)
        assertEquals(null, parsed.metrics.verticalDescender)
        assertEquals(null, parsed.metrics.verticalLineGap)
        assertEquals(null, parsed.metrics.maxAdvanceHeight)
        assertEquals(null, parsed.metrics.numberOfVMetrics)
        assertEquals(emptyList(), parsed.metrics.verticalMetrics)
        assertEquals(malformedVhea.toUnsignedByteList(), parsed.rawTables.getValue(SFNTTableTag("vhea")))
    }

    @Test
    fun metricsTableParserUsesOs2TypographicLineMetricsWhenSelectionBitIsSet() {
        val metrics = OpenTypeMetricsTableParser.parse(
            head = headTable(
                unitsPerEm = 1000,
                bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
                indexToLocFormat = 1,
            ),
            hhea = hheaTable(
                ascender = 820,
                descender = -180,
                lineGap = 40,
                numberOfHMetrics = 1,
            ),
            maxp = maxpTable(numGlyphs = 1),
            hmtx = hmtxTable(metric(advanceWidth = 500, leftSideBearing = -20)),
            os2 = os2Table(
                averageCharWidth = 477,
                xHeight = 512,
                capHeight = 702,
                strikeoutThickness = 51,
                strikeoutPosition = 262,
                fsSelection = 0x0080,
                typoAscender = 760,
                typoDescender = -240,
                typoLineGap = 55,
            ),
        )

        assertEquals(760, metrics.ascender)
        assertEquals(-240, metrics.descender)
        assertEquals(55, metrics.lineGap)
    }

    @Test
    fun defaultOpenTypeFaceParserExposesOpenTypeStyleMetadata() {
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(
                    testNameRecord(
                        platformId = 3,
                        encodingId = 1,
                        languageId = 0x0409,
                        nameId = 2,
                        bytes = "Oblique".toByteArray(Charsets.UTF_16BE),
                    ),
                ),
                "cmap" to cmapTable(),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
                    indexToLocFormat = 1,
                    macStyle = 0x0001,
                ),
                "hhea" to hheaTable(
                    ascender = 820,
                    descender = -180,
                    lineGap = 40,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 1),
                "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = -20)),
                "OS/2" to os2Table(
                    averageCharWidth = 477,
                    xHeight = 512,
                    capHeight = 702,
                    strikeoutThickness = 51,
                    strikeoutPosition = 262,
                    weightClass = 350,
                    widthClass = 3,
                    fsSelection = 0x0200,
                ),
                "post" to postTable(
                    underlinePosition = -96,
                    underlineThickness = 44,
                    italicAngleHigh = -12,
                ),
            ),
        )

        val style = DefaultOpenTypeFaceParser().parse(source).style

        assertEquals(700, style.weight)
        assertEquals(3, style.width)
        assertEquals(OpenTypeStyleSlant.OBLIQUE, style.slant)
        assertEquals(true, style.hasMetadata)
    }

    @Test
    fun openTypeFaceDataConvertsParsedStyleToCoreTypefaceData() {
        val diagnostic = OpenTypeParseDiagnostic(
            sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440100")),
            message = "Unable to parse OpenType table.",
            table = SFNTTableTag("GPOS"),
            causeCode = "INVALID_TABLE",
            causeMessage = "broken",
        )
        val face = OpenTypeFaceData(
            id = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440101")),
            source = FontSource(
                id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440100")),
                kind = FontSourceKind.MEMORY,
                displayName = "Fallback Name",
                bytes = ByteArray(0),
            ),
            directory = SFNTTableDirectory(scalerType = 0x00010000u),
            names = NameTable(
                listOf(
                    OpenTypeNameRecord(3, 1, 0x0409, 1, 8, 0, "Legacy Family"),
                    OpenTypeNameRecord(3, 1, 0x0409, 2, 6, 0, "Regular"),
                    OpenTypeNameRecord(3, 1, 0x0409, 16, 8, 0, "Typographic Family"),
                    OpenTypeNameRecord(3, 1, 0x0409, 17, 7, 0, "Display"),
                ),
            ),
            style = OpenTypeStyle(
                weight = 725,
                width = 4,
                slant = OpenTypeStyleSlant.ITALIC,
                hasMetadata = true,
            ),
            diagnostics = listOf(diagnostic),
        )

        val typeface = face.toTypefaceData()

        assertEquals("Typographic Family", typeface.familyName)
        assertEquals("Display", typeface.styleName)
        assertEquals(725, typeface.style.weight)
        assertEquals(4, typeface.style.width)
        assertEquals(FontSlant.ITALIC, typeface.style.slant)
        assertEquals("INVALID_TABLE", typeface.diagnostics.single().causeCode)
        assertTrue(typeface.diagnostics.single().message.contains("GPOS"))
    }

    @Test
    fun openTypeTypefaceDataFactoryParsesMemoryBytesToCoreTypefaceData() {
        val sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440100"))
        val typeface = OpenTypeTypefaceDataFactory.fromBytes(
            sourceId = sourceId,
            displayName = "Factory Font",
            bytes = sfntFont(
                "name" to nameTable(
                    testNameRecord(
                        platformId = 3,
                        encodingId = 1,
                        languageId = 0x0409,
                        nameId = 1,
                        bytes = "Factory Sans".toByteArray(Charsets.UTF_16BE),
                    ),
                    testNameRecord(
                        platformId = 3,
                        encodingId = 1,
                        languageId = 0x0409,
                        nameId = 2,
                        bytes = "Bold".toByteArray(Charsets.UTF_16BE),
                    ),
                ),
                "cmap" to cmapTable(),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
                    indexToLocFormat = 1,
                ),
                "hhea" to hheaTable(
                    ascender = 820,
                    descender = -180,
                    lineGap = 40,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 1),
                "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = -20)),
                "OS/2" to os2Table(
                    averageCharWidth = 477,
                    xHeight = 512,
                    capHeight = 702,
                    strikeoutThickness = 51,
                    strikeoutPosition = 262,
                    weightClass = 500,
                    fsSelection = 0x0020,
                ),
            ),
        )

        assertEquals(sourceId, typeface.source.id)
        assertEquals("Factory Sans", typeface.familyName)
        assertEquals("Bold", typeface.styleName)
        assertEquals(700, typeface.style.weight)
    }

    @Test
    fun metricsTableParserLeavesOptionalTypographicMetricsNullWhenTablesAreAbsent() {
        val metrics = OpenTypeMetricsTableParser.parse(
            head = headTable(
                unitsPerEm = 1000,
                bounds = OpenTypeFontBounds(xMin = -40, yMin = -200, xMax = 980, yMax = 840),
                indexToLocFormat = 1,
            ),
            hhea = hheaTable(
                ascender = 820,
                descender = -180,
                lineGap = 40,
                maxCharWidth = 500,
                numberOfHMetrics = 1,
            ),
            maxp = maxpTable(numGlyphs = 1),
            hmtx = hmtxTable(metric(advanceWidth = 500, leftSideBearing = -20)),
        )

        assertEquals(null, metrics.xHeight)
        assertEquals(null, metrics.capHeight)
        assertEquals(null, metrics.averageCharWidth)
        assertEquals(500, metrics.maxCharWidth)
        assertEquals(null, metrics.underlineThickness)
        assertEquals(null, metrics.underlinePosition)
        assertEquals(null, metrics.strikeoutThickness)
        assertEquals(null, metrics.strikeoutPosition)
    }

    @Test
    fun metricsTableParserRejectsInvalidHeadUnitsPerEmWithIllegalArgumentException() {
        val invalidUnitsPerEm = listOf(0, 15, 16385)

        invalidUnitsPerEm.forEach { unitsPerEm ->
            val error = assertFailsWith<IllegalArgumentException> {
                OpenTypeMetricsTableParser.parse(
                    head = headTable(
                        unitsPerEm = unitsPerEm,
                        bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                        indexToLocFormat = 0,
                    ),
                    hhea = hheaTable(
                        ascender = 800,
                        descender = -200,
                        lineGap = 0,
                        numberOfHMetrics = 1,
                    ),
                    maxp = maxpTable(numGlyphs = 1),
                    hmtx = hmtxTable(metric(advanceWidth = 500, leftSideBearing = 0)),
                )
            }

            assertTrue(
                error.message.orEmpty().contains("OpenType head unitsPerEm $unitsPerEm must be between 16 and 16384"),
                "Unexpected error message: ${error.message}",
            )
        }
    }

    @Test
    fun metricsTableParserRejectsInvalidHeadIndexToLocFormatWithIllegalArgumentException() {
        val error = assertFailsWith<IllegalArgumentException> {
            OpenTypeMetricsTableParser.parse(
                head = headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 2,
                ),
                hhea = hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                maxp = maxpTable(numGlyphs = 1),
                hmtx = hmtxTable(metric(advanceWidth = 500, leftSideBearing = 0)),
            )
        }

        assertTrue(
            error.message.orEmpty().contains("OpenType head indexToLocFormat 2 must be 0 or 1"),
            "Unexpected error message: ${error.message}",
        )
    }

    @Test
    fun metricsTableParserRejectsShortHmtxTablesWithIllegalArgumentException() {
        val error = assertFailsWith<IllegalArgumentException> {
            OpenTypeMetricsTableParser.parse(
                head = headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                hhea = hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 2,
                ),
                maxp = maxpTable(numGlyphs = 3),
                hmtx = hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = 0),
                    metric(advanceWidth = 400, leftSideBearing = 0),
                ),
            )
        }

        assertTrue(
            error.message.orEmpty().contains("OpenType hmtx table length 8 is shorter than required 10 bytes"),
            "Unexpected error message: ${error.message}",
        )
    }

    @Test
    fun metricsTableParserRejectsShortRequiredTablesWithIllegalArgumentException() {
        val validHead = headTable(
            unitsPerEm = 1000,
            bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
            indexToLocFormat = 0,
        )
        val validHhea = hheaTable(
            ascender = 800,
            descender = -200,
            lineGap = 0,
            numberOfHMetrics = 1,
        )
        val validMaxp = maxpTable(numGlyphs = 1)
        val validHmtx = hmtxTable(metric(advanceWidth = 500, leftSideBearing = 0))

        val cases = listOf(
            "OpenType head table must contain at least 54 bytes" to {
                OpenTypeMetricsTableParser.parse(
                    head = ByteArray(53),
                    hhea = validHhea,
                    maxp = validMaxp,
                    hmtx = validHmtx,
                )
            },
            "OpenType hhea table must contain at least 36 bytes" to {
                OpenTypeMetricsTableParser.parse(
                    head = validHead,
                    hhea = ByteArray(35),
                    maxp = validMaxp,
                    hmtx = validHmtx,
                )
            },
            "OpenType maxp table must contain at least 6 bytes" to {
                OpenTypeMetricsTableParser.parse(
                    head = validHead,
                    hhea = validHhea,
                    maxp = ByteArray(5),
                    hmtx = validHmtx,
                )
            },
        )

        cases.forEach { (expectedMessage, parse) ->
            val error = assertFailsWith<IllegalArgumentException> {
                parse()
            }

            assertTrue(
                error.message.orEmpty().contains(expectedMessage),
                "Unexpected error message: ${error.message}",
            )
        }
    }

    @Test
    fun kernTableParserParsesVersion0HorizontalFormat0Pairs() {
        val table = OpenTypeKernTableParser.parse(
            kernTable(
                kernFormat0Subtable(
                    testKernPair(leftGlyphId = 7, rightGlyphId = 11, value = -80),
                    testKernPair(leftGlyphId = 11, rightGlyphId = 19, value = 24),
                ),
            ),
        )

        val subtable = table.subtables.single()

        assertEquals(0, table.version)
        assertEquals(0, subtable.version)
        assertEquals(0, subtable.coverage.format)
        assertEquals(true, subtable.coverage.horizontal)
        assertEquals(false, subtable.coverage.minimum)
        assertEquals(false, subtable.coverage.crossStream)
        assertEquals(2, subtable.pairs.size)
        assertEquals(OpenTypeKernPair(leftGlyphId = 7, rightGlyphId = 11, value = -80), subtable.pairs[0])
        assertEquals(OpenTypeKernPair(leftGlyphId = 11, rightGlyphId = 19, value = 24), subtable.pairs[1])
        assertEquals(-80, table.lookupKerningAdjustment(leftGlyphId = 7, rightGlyphId = 11))
        assertEquals(24, table.lookupKerningAdjustment(leftGlyphId = 11, rightGlyphId = 19))
        assertEquals(0, table.lookupKerningAdjustment(leftGlyphId = 7, rightGlyphId = 19))
    }

    @Test
    fun defaultOpenTypeFaceParserExposesParsedKernTableInLayout() {
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 2,
                ),
                "maxp" to maxpTable(numGlyphs = 2),
                "hmtx" to hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = 0),
                    metric(advanceWidth = 450, leftSideBearing = 0),
                ),
                "kern" to kernTable(
                    kernFormat0Subtable(
                        testKernPair(leftGlyphId = 7, rightGlyphId = 11, value = -40),
                    ),
                ),
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(7, parsed.directory.tables.size)
        assertEquals(24, parsed.layout.tables.getValue(SFNTTableTag("kern")).size)
        assertEquals(-40, parsed.layout.kern?.lookupKerningAdjustment(leftGlyphId = 7, rightGlyphId = 11))
        assertEquals(0, parsed.layout.kern?.lookupKerningAdjustment(leftGlyphId = 11, rightGlyphId = 7))
    }

    @Test
    fun defaultOpenTypeFaceParserExposesParsedGposPairKerningInLayout() {
        val gpos = gposPairAdjustmentFormat1Table(
            leftGlyphId = 7,
            rightGlyphId = 11,
            xAdvance = -55,
        )
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 2,
                ),
                "maxp" to maxpTable(numGlyphs = 12),
                "hmtx" to hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = 0),
                    metric(advanceWidth = 450, leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                ),
                "GPOS" to gpos,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(gpos.size, parsed.layout.tables.getValue(SFNTTableTag("GPOS")).size)
        assertEquals(-55, parsed.layout.gposPairs?.lookupXAdvanceAdjustment(leftGlyphId = 7, rightGlyphId = 11))
        assertEquals(0, parsed.layout.gposPairs?.lookupXAdvanceAdjustment(leftGlyphId = 11, rightGlyphId = 7))
    }

    @Test
    fun defaultOpenTypeFaceParserExposesParsedGposSingleAdjustmentsInLayout() {
        val gpos = gposSingleAdjustmentFormat1Table(
            glyphId = 7,
            xPlacement = 40,
            yPlacement = -20,
            xAdvance = -30,
        )
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 2,
                ),
                "maxp" to maxpTable(numGlyphs = 12),
                "hmtx" to hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = 0),
                    metric(advanceWidth = 450, leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                ),
                "GPOS" to gpos,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(gpos.size, parsed.layout.tables.getValue(SFNTTableTag("GPOS")).size)
        assertEquals(
            OpenTypeGposValueRecord(
                xPlacement = 40,
                yPlacement = -20,
                xAdvance = -30,
            ),
            parsed.layout.gposSingles?.lookupAdjustment(7),
        )
        assertEquals(null, parsed.layout.gposSingles?.lookupAdjustment(11))
    }

    @Test
    fun defaultOpenTypeFaceParserExposesParsedGsubSingleMultipleAndLigatureLookupsInLayout() {
        val gsub = gsubSimpleLookupsTable()
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0061,
                                endCode = 0x0069,
                                startGlyphId = 5,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 2,
                ),
                "maxp" to maxpTable(numGlyphs = 64),
                "hmtx" to hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = 0),
                    metric(advanceWidth = 450, leftSideBearing = 0),
                    *Array(62) { extraLeftSideBearing(leftSideBearing = 0) },
                ),
                "GSUB" to gsub,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(gsub.toUnsignedByteList(), parsed.layout.tables.getValue(SFNTTableTag("GSUB")))
        assertEquals(
            OpenTypeGsubTable(
                lookups = listOf(
                    OpenTypeGsubSingleSubstitutionLookup(
                        featureTag = "ccmp",
                        lookupIndex = 0,
                        substitutions = listOf(
                            OpenTypeGsubSingleSubstitution(inputGlyphId = 5, replacementGlyphId = 15),
                        ),
                    ),
                    OpenTypeGsubMultipleSubstitutionLookup(
                        featureTag = "ccmp",
                        lookupIndex = 1,
                        substitutions = listOf(
                            OpenTypeGsubMultipleSubstitution(inputGlyphId = 6, replacementGlyphIds = listOf(16, 17)),
                        ),
                    ),
                    OpenTypeGsubLigatureSubstitutionLookup(
                        featureTag = "liga",
                        lookupIndex = 2,
                        substitutions = listOf(
                            OpenTypeGsubLigatureSubstitution(inputGlyphIds = listOf(7, 8), replacementGlyphId = 42),
                        ),
                    ),
                ),
            ),
            parsed.layout.gsub,
        )
    }

    @Test
    fun defaultOpenTypeFaceParserPreservesAllTopLevelFeatureTagsForSharedGsubLookup() {
        val gsub = gsubSharedLookupAcrossFeaturesTable()
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0061,
                                endCode = 0x0061,
                                startGlyphId = 5,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 32),
                "hmtx" to hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = 0),
                    *Array(31) { extraLeftSideBearing(leftSideBearing = 0) },
                ),
                "GSUB" to gsub,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(
            OpenTypeGsubTable(
                lookups = listOf(
                    OpenTypeGsubSingleSubstitutionLookup(
                        featureTag = "ccmp",
                        extraFeatureTags = setOf("liga"),
                        lookupIndex = 0,
                        substitutions = listOf(
                            OpenTypeGsubSingleSubstitution(inputGlyphId = 5, replacementGlyphId = 15),
                        ),
                    ),
                ),
            ),
            parsed.layout.gsub,
        )
    }

    @Test
    fun defaultOpenTypeFaceParserLoadsReviewedGsubFixtureFontsFromRepo() {
        val parser = DefaultOpenTypeFaceParser()
        val cases = listOf(
            ReviewedGsubFixtureCase(
                relativePath = "font/fixtures/fonts/shaping/gsub-single-substitution.otf",
                expected = OpenTypeGsubTable(
                    lookups = listOf(
                        OpenTypeGsubSingleSubstitutionLookup(
                            featureTag = "ccmp",
                            substitutions = listOf(
                                OpenTypeGsubSingleSubstitution(inputGlyphId = 5, replacementGlyphId = 15),
                            ),
                        ),
                    ),
                ),
            ),
            ReviewedGsubFixtureCase(
                relativePath = "font/fixtures/fonts/shaping/gsub-multiple-substitution.otf",
                expected = OpenTypeGsubTable(
                    lookups = listOf(
                        OpenTypeGsubMultipleSubstitutionLookup(
                            featureTag = "ccmp",
                            substitutions = listOf(
                                OpenTypeGsubMultipleSubstitution(inputGlyphId = 6, replacementGlyphIds = listOf(16, 17)),
                            ),
                        ),
                    ),
                ),
            ),
            ReviewedGsubFixtureCase(
                relativePath = "font/fixtures/fonts/shaping/gsub-ligature-fi.otf",
                expected = OpenTypeGsubTable(
                    lookups = listOf(
                        OpenTypeGsubLigatureSubstitutionLookup(
                            featureTag = "liga",
                            substitutions = listOf(
                                OpenTypeGsubLigatureSubstitution(inputGlyphIds = listOf(7, 10), replacementGlyphId = 42),
                            ),
                        ),
                    ),
                ),
            ),
        )

        cases.forEach { case ->
            val parsed = parser.parse(fixtureFontSource(case.relativePath))

            assertEquals(emptyList(), parsed.diagnostics, case.relativePath)
            assertEquals(case.expected, parsed.layout.gsub, case.relativePath)
            assertTrue(parsed.rawTables.containsKey(SFNTTableTag("GSUB")), case.relativePath)
        }
    }

    @Test
    fun defaultOpenTypeFaceParserReportsReviewedMalformedGsubFixtureFontsAsDiagnostics() {
        val parser = DefaultOpenTypeFaceParser()

        val malformedCoverage = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gsub-coverage-malformed.otf"),
        )
        val malformedLigature = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gsub-ligature-bad-component.otf"),
        )

        assertEquals("font.sfnt.optional-table-malformed", malformedCoverage.diagnostics.single().causeCode)
        assertTrue(
            malformedCoverage.diagnostics.single().causeMessage.orEmpty().contains("OpenType GSUB coverage format"),
            malformedCoverage.diagnostics.single().toString(),
        )
        assertEquals(null, malformedCoverage.layout.gsub)
        assertEquals("font.sfnt.optional-table-malformed", malformedLigature.diagnostics.single().causeCode)
        assertTrue(
            malformedLigature.diagnostics.single().causeMessage.orEmpty().contains("compCount"),
            malformedLigature.diagnostics.single().toString(),
        )
        assertEquals(null, malformedLigature.layout.gsub)
    }

    @Test
    fun defaultOpenTypeFaceParserLoadsReviewedGsubContextFixtureFontsFromRepo() {
        val parser = DefaultOpenTypeFaceParser()

        val format1 = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gsub-context-format1.otf"),
        )
        assertEquals(emptyList(), format1.diagnostics)
        assertEquals(
            OpenTypeGsubTable(
                lookups = listOf(
                    OpenTypeGsubSingleSubstitutionLookup(
                        featureTag = "ccmp",
                        lookupIndex = 0,
                        substitutions = listOf(
                            OpenTypeGsubSingleSubstitution(inputGlyphId = 552, replacementGlyphId = 555),
                        ),
                    ),
                    OpenTypeGsubContextGlyphLookup(
                        featureTag = "calt",
                        lookupIndex = 1,
                        rules = listOf(
                            OpenTypeGsubContextGlyphRule(
                                inputGlyphIds = listOf(552, 553),
                                nestedLookups = listOf(
                                    OpenTypeGsubNestedLookupRecord(sequenceIndex = 0, lookupIndex = 0),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            format1.layout.gsub,
        )

        val format2 = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gsub-context-format2-class.otf"),
        )
        assertEquals(emptyList(), format2.diagnostics)
        assertEquals(
            OpenTypeGsubTable(
                lookups = listOf(
                    OpenTypeGsubSingleSubstitutionLookup(
                        featureTag = "ccmp",
                        lookupIndex = 0,
                        substitutions = listOf(
                            OpenTypeGsubSingleSubstitution(inputGlyphId = 552, replacementGlyphId = 556),
                        ),
                    ),
                    OpenTypeGsubContextClassLookup(
                        featureTag = "calt",
                        lookupIndex = 1,
                        firstGlyphCoverage = setOf(552),
                        classDefinitions = mapOf(
                            552 to 1,
                            553 to 2,
                            554 to 3,
                        ),
                        rules = listOf(
                            OpenTypeGsubContextClassRule(
                                inputClasses = listOf(1, 2, 3),
                                nestedLookups = listOf(
                                    OpenTypeGsubNestedLookupRecord(sequenceIndex = 0, lookupIndex = 0),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            format2.layout.gsub,
        )

        val format3 = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gsub-context-format3-coverage.otf"),
        )
        assertEquals(emptyList(), format3.diagnostics)
        assertEquals(
            OpenTypeGsubTable(
                lookups = listOf(
                    OpenTypeGsubSingleSubstitutionLookup(
                        featureTag = "ccmp",
                        lookupIndex = 0,
                        substitutions = listOf(
                            OpenTypeGsubSingleSubstitution(inputGlyphId = 552, replacementGlyphId = 557),
                        ),
                    ),
                    OpenTypeGsubContextCoverageLookup(
                        featureTag = "calt",
                        lookupIndex = 1,
                        rules = listOf(
                            OpenTypeGsubContextCoverageRule(
                                inputCoverages = listOf(
                                    setOf(552),
                                    setOf(553, 554),
                                    setOf(555),
                                ),
                                nestedLookups = listOf(
                                    OpenTypeGsubNestedLookupRecord(sequenceIndex = 0, lookupIndex = 0),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            format3.layout.gsub,
        )
    }

    @Test
    fun defaultOpenTypeFaceParserKeepsNestedOnlyGsubLookupsReachableFromContextRules() {
        val parser = DefaultOpenTypeFaceParser()
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0061,
                                endCode = 0x0063,
                                startGlyphId = 5,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 2,
                ),
                "maxp" to maxpTable(numGlyphs = 32),
                "hmtx" to hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = 0),
                    metric(advanceWidth = 450, leftSideBearing = 0),
                    *Array(30) { extraLeftSideBearing(leftSideBearing = 0) },
                ),
                "GSUB" to gsubContextLookupWithNestedOnlySingleLookupTable(),
            ),
        )

        val parsed = parser.parse(source)

        assertEquals(emptyList(), parsed.diagnostics)
        assertEquals(
            OpenTypeGsubTable(
                lookups = listOf(
                    OpenTypeGsubSingleSubstitutionLookup(
                        featureTag = "",
                        lookupIndex = 0,
                        substitutions = listOf(
                            OpenTypeGsubSingleSubstitution(inputGlyphId = 5, replacementGlyphId = 15),
                        ),
                    ),
                    OpenTypeGsubContextGlyphLookup(
                        featureTag = "calt",
                        lookupIndex = 1,
                        rules = listOf(
                            OpenTypeGsubContextGlyphRule(
                                inputGlyphIds = listOf(5, 6),
                                nestedLookups = listOf(
                                    OpenTypeGsubNestedLookupRecord(sequenceIndex = 0, lookupIndex = 0),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            parsed.layout.gsub,
        )
    }

    @Test
    fun defaultOpenTypeFaceParserReportsReviewedMalformedGsubContextFixturesAsDiagnostics() {
        val parser = DefaultOpenTypeFaceParser()

        val malformedClassDef = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gsub-context-malformed-classdef.otf"),
        )
        val nestedCycle = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gsub-context-nested-cycle.otf"),
        )

        assertEquals("font.sfnt.optional-table-malformed", malformedClassDef.diagnostics.single().causeCode)
        assertTrue(
            malformedClassDef.diagnostics.single().causeMessage.orEmpty().contains("ClassDef"),
            malformedClassDef.diagnostics.single().toString(),
        )
        assertEquals(null, malformedClassDef.layout.gsub)
        assertEquals(emptyList(), nestedCycle.diagnostics)
        assertTrue(nestedCycle.layout.gsub != null)
    }

    @Test
    fun defaultOpenTypeFaceParserReportsGposFormat2ExcessiveFinalExpansionAsDiagnostic() {
        val gpos = gposPairAdjustmentFormat2Class0Table(
            coverageGlyphCount = 257,
            xAdvance = -1,
        )
        val source = memoryFontSource(
            sfntFaceWithColorTables(
                numGlyphs = 257,
                "GPOS" to gpos,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val diagnostic = parsed.diagnostics.single()

        assertEquals(SFNTTableTag("GPOS"), diagnostic.table)
        assertEquals("font.sfnt.optional-table-malformed", diagnostic.causeCode)
        assertTrue(
            diagnostic.causeMessage.orEmpty().contains("format 2 expanded glyph pair count"),
            "Unexpected diagnostic: $diagnostic",
        )
        assertEquals(gpos.toUnsignedByteList(), parsed.layout.tables.getValue(SFNTTableTag("GPOS")))
        assertEquals(null, parsed.layout.gposPairs)
    }

    @Test
    fun defaultOpenTypeFaceParserLoadsReviewedGposFixtureFontsFromRepo() {
        val parser = DefaultOpenTypeFaceParser()

        val single = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-single-adjustment.otf"),
        )
        assertEquals(emptyList(), single.diagnostics)
        assertEquals(
            OpenTypeGposSingleTable(
                adjustments = listOf(
                    OpenTypeGposSingleAdjustment(
                        glyphId = 7,
                        valueRecord = OpenTypeGposValueRecord(xPlacement = 40, yPlacement = -20, xAdvance = -30),
                    ),
                ),
            ),
            single.layout.gposSingles,
        )

        val pairFormat1 = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-pair-format1-kerning.otf"),
        )
        assertEquals(emptyList(), pairFormat1.diagnostics)
        assertEquals(
            OpenTypeGposPairTable(
                pairs = listOf(
                    OpenTypeGposPairAdjustment(
                        leftGlyphId = 7,
                        rightGlyphId = 11,
                        firstValueRecord = OpenTypeGposValueRecord(xAdvance = -55),
                    ),
                ),
            ),
            pairFormat1.layout.gposPairs,
        )

        val pairFormat2 = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-pair-format2-class.otf"),
        )
        assertEquals(emptyList(), pairFormat2.diagnostics)
        val pairFormat2Pairs = requireNotNull(pairFormat2.layout.gposPairs).pairs
        assertEquals(520, pairFormat2Pairs.size)
        assertEquals(
            OpenTypeGposPairAdjustment(
                leftGlyphId = 0,
                rightGlyphId = 0,
                firstValueRecord = OpenTypeGposValueRecord(xAdvance = -40),
            ),
            pairFormat2Pairs.first(),
        )
        assertEquals(
            OpenTypeGposPairAdjustment(
                leftGlyphId = 1,
                rightGlyphId = 259,
                firstValueRecord = OpenTypeGposValueRecord(xAdvance = -40),
            ),
            pairFormat2Pairs.last(),
        )
    }

    @Test
    fun defaultOpenTypeFaceParserLoadsReviewedMarkAndCursiveGposFixtureFontsFromRepo() {
        val parser = DefaultOpenTypeFaceParser()

        val markToBase = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-mark-to-base.otf"),
        )
        assertEquals(emptyList(), markToBase.diagnostics)
        val markToBaseGdef = assertNotNull(markToBase.layout.gdef)
        val markToBaseGpos = assertNotNull(markToBase.layout.gpos)
        val alefGlyphId = assertNotNull(markToBase.cmap.lookupGlyphId(0x0627))
        val fathaGlyphId = assertNotNull(markToBase.cmap.lookupGlyphId(0x064E))
        assertEquals(1, markToBaseGdef.glyphClasses[alefGlyphId])
        assertEquals(3, markToBaseGdef.glyphClasses[fathaGlyphId])
        val markToBaseLookup = markToBaseGpos.lookups.filterIsInstance<OpenTypeGposMarkToBaseLookup>().first {
            it.featureTag == "mark" && it.attachments.any { attachment ->
                attachment.baseGlyphId == alefGlyphId && attachment.markGlyphId == fathaGlyphId
            }
        }
        val markToBaseAttachment = markToBaseLookup.attachments.first { attachment ->
            attachment.baseGlyphId == alefGlyphId && attachment.markGlyphId == fathaGlyphId
        }
        assertEquals(0, markToBaseAttachment.markClass)
        assertEquals(OpenTypeAnchor(format = 1, x = 99, y = 748), markToBaseAttachment.markAnchor)
        assertEquals(OpenTypeAnchor(format = 1, x = 120, y = 711), markToBaseAttachment.baseAnchor)

        val markToLigature = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-mark-to-ligature.otf"),
        )
        assertEquals(emptyList(), markToLigature.diagnostics)
        val markToLigatureGpos = assertNotNull(markToLigature.layout.gpos)
        val ligatureGlyphId = assertNotNull(markToLigature.cmap.lookupGlyphId(0xE000))
        val ligatureMarkGlyphId = assertNotNull(markToLigature.cmap.lookupGlyphId(0x064E))
        val markToLigatureLookup = assertNotNull(
            markToLigatureGpos.lookups.filterIsInstance<OpenTypeGposMarkToLigatureLookup>().firstOrNull {
                it.featureTag == "mark" && it.attachments.any { attachment ->
                    attachment.ligatureGlyphId == ligatureGlyphId && attachment.markGlyphId == ligatureMarkGlyphId
                }
            },
            markToLigatureGpos.lookups.toString(),
        )
        val ligatureAttachment = markToLigatureLookup.attachments.first { attachment ->
            attachment.ligatureGlyphId == ligatureGlyphId && attachment.markGlyphId == ligatureMarkGlyphId
        }
        assertEquals(0, ligatureAttachment.markClass)
        assertEquals(0, ligatureAttachment.componentIndex)
        assertEquals(OpenTypeAnchor(format = 1, x = 99, y = 748), ligatureAttachment.markAnchor)
        assertEquals(OpenTypeAnchor(format = 1, x = 1050, y = 720), ligatureAttachment.ligatureAnchor)

        val markToMark = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-mark-to-mark.otf"),
        )
        assertEquals(emptyList(), markToMark.diagnostics)
        val markToMarkGpos = assertNotNull(markToMark.layout.gpos)
        val dotAboveGlyphId = assertNotNull(markToMark.cmap.lookupGlyphId(0xE003))
        val markToMarkGlyphId = assertNotNull(markToMark.cmap.lookupGlyphId(0x064E))
        val markToMarkLookup = assertNotNull(
            markToMarkGpos.lookups.filterIsInstance<OpenTypeGposMarkToMarkLookup>().firstOrNull {
                it.featureTag == "mkmk" && it.attachments.any { attachment ->
                    attachment.mark1GlyphId == markToMarkGlyphId && attachment.mark2GlyphId == dotAboveGlyphId
                }
            },
            markToMarkGpos.lookups.toString(),
        )
        val markToMarkAttachment = markToMarkLookup.attachments.first { attachment ->
            attachment.mark1GlyphId == markToMarkGlyphId && attachment.mark2GlyphId == dotAboveGlyphId
        }
        assertEquals(0, markToMarkAttachment.markClass)
        assertEquals(OpenTypeAnchor(format = 1, x = 99, y = 748), markToMarkAttachment.mark1Anchor)
        assertEquals(OpenTypeAnchor(format = 1, x = 66, y = 929), markToMarkAttachment.mark2Anchor)

        val cursive = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-cursive-attachment.otf"),
        )
        assertEquals(
            listOf(SFNTTableTag("GSUB"), SFNTTableTag("GPOS")),
            cursive.diagnostics.map(OpenTypeParseDiagnostic::table),
        )
        assertEquals(
            listOf("font.sfnt.optional-table-malformed", "font.sfnt.optional-table-malformed"),
            cursive.diagnostics.map(OpenTypeParseDiagnostic::causeCode),
        )
        assertTrue(
            cursive.diagnostics[0].causeMessage.orEmpty().contains("OpenType GSUB ContextSubst format 3 glyphCount 1 must be at least 2."),
            cursive.diagnostics[0].toString(),
        )
        assertTrue(
            cursive.diagnostics[1].causeMessage.orEmpty().contains("expanded glyph pair count 102762 exceeds supported limit 65536"),
            cursive.diagnostics[1].toString(),
        )
        val cursiveGdef = assertNotNull(cursive.layout.gdef)
        val cursiveGpos = assertNotNull(cursive.layout.gpos)
        val leftCursiveGlyphId = assertNotNull(cursive.cmap.lookupGlyphId(0xE001))
        val rightCursiveGlyphId = assertNotNull(cursive.cmap.lookupGlyphId(0xE002))
        assertEquals(1, cursiveGdef.glyphClasses[leftCursiveGlyphId])
        assertEquals(1, cursiveGdef.glyphClasses[rightCursiveGlyphId])
        val cursiveLookup = assertNotNull(
            cursiveGpos.lookups.filterIsInstance<OpenTypeGposCursiveLookup>().firstOrNull {
                it.featureTag == "curs" && it.attachments.any { attachment ->
                    attachment.glyphId == leftCursiveGlyphId && attachment.exitAnchor != null
                } && it.attachments.any { attachment ->
                    attachment.glyphId == rightCursiveGlyphId && attachment.entryAnchor != null
                }
            },
            cursiveGpos.lookups.toString(),
        )
        val leftAttachment = cursiveLookup.attachments.first { it.glyphId == leftCursiveGlyphId }
        val rightAttachment = cursiveLookup.attachments.first { it.glyphId == rightCursiveGlyphId }
        assertEquals(OpenTypeAnchor(format = 1, x = 0, y = 106), leftAttachment.exitAnchor)
        assertEquals(OpenTypeAnchor(format = 1, x = 226, y = 106), rightAttachment.entryAnchor)
    }

    @Test
    fun defaultOpenTypeFaceParserPreservesMissingGdefAndMalformedAnchorFixtureFacts() {
        val parser = DefaultOpenTypeFaceParser()

        val missingGdef = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-missing-gdef.otf"),
        )
        assertEquals(emptyList(), missingGdef.diagnostics)
        assertNull(missingGdef.layout.gdef)
        assertNotNull(missingGdef.layout.gpos)

        val malformedAnchor = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-anchor-malformed.otf"),
        )
        assertEquals(emptyList(), malformedAnchor.diagnostics)
        val malformedLookup = assertNotNull(malformedAnchor.layout.gpos)
            .lookups
            .filterIsInstance<OpenTypeGposMalformedLookup>()
            .first()
        assertEquals("mark", malformedLookup.featureTag)
        assertEquals(4, malformedLookup.lookupType)
        assertTrue(
            malformedLookup.message.contains("anchor format 9"),
            malformedLookup.message,
        )
    }

    @Test
    fun defaultOpenTypeFaceParserStillReportsMalformedKernSubsetWhenGeneralGposSucceeds() {
        val gpos = gposCursiveAndMalformedKernTable()
        assertEquals(
            listOf(
                OpenTypeGposMalformedLookup(
                    featureTag = "curs",
                    lookupIndex = 1,
                    lookupType = 3,
                    message = "OpenType GPOS cursive posFormat 9 is not supported.",
                ),
            ),
            OpenTypeGposTableParser.parse(gpos).lookups,
        )
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                            testFormat4Segment(
                                startCode = 0x0042,
                                endCode = 0x0042,
                                startGlyphId = 11,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 16),
                "hmtx" to hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = 0),
                    *Array(15) { extraLeftSideBearing(leftSideBearing = 0) },
                ),
                "GPOS" to gpos,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)

        val diagnostic = parsed.diagnostics.single()
        assertEquals(SFNTTableTag("GPOS"), diagnostic.table)
        assertEquals("font.sfnt.optional-table-malformed", diagnostic.causeCode)
        assertTrue(
            diagnostic.causeMessage.orEmpty().contains("pairSetCount"),
            "Unexpected diagnostic: $diagnostic",
        )
        assertEquals(null, parsed.layout.gposPairs)
        val malformedGeneralLookup = assertNotNull(
            parsed.layout.gpos
                ?.lookups
                ?.filterIsInstance<OpenTypeGposMalformedLookup>()
                ?.firstOrNull { it.featureTag == "curs" && it.lookupType == 3 },
        )
        assertTrue(
            malformedGeneralLookup.message.contains("cursive posFormat 9"),
            malformedGeneralLookup.message,
        )
    }

    @Test
    fun defaultOpenTypeFaceParserReportsMalformedGposTablesAsDiagnostics() {
        val malformedGpos = gposPairAdjustmentFormat1Table(
            leftGlyphId = 7,
            rightGlyphId = 11,
            xAdvance = -55,
            declaredPairSetCount = 2,
        )
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 12),
                "hmtx" to hmtxTable(
                    metric(advanceWidth = 500, leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                    extraLeftSideBearing(leftSideBearing = 0),
                ),
                "GPOS" to malformedGpos,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val diagnostic = parsed.diagnostics.single()

        assertEquals(SFNTTableTag("GPOS"), diagnostic.table)
        assertEquals("font.sfnt.optional-table-malformed", diagnostic.causeCode)
        assertTrue(
            diagnostic.causeMessage.orEmpty().contains("OpenType GPOS pair adjustment format 1 pairSetCount"),
            "Unexpected diagnostic: $diagnostic",
        )
        assertEquals(malformedGpos.size, parsed.layout.tables.getValue(SFNTTableTag("GPOS")).size)
        assertEquals(malformedGpos.toUnsignedByteList(), parsed.rawTables.getValue(SFNTTableTag("GPOS")))
        assertEquals(null, parsed.layout.gposPairs)
    }

    @Test
    fun defaultOpenTypeFaceParserReportsReviewedMalformedGposFixtureFontsAsDiagnostics() {
        val parser = DefaultOpenTypeFaceParser()

        val malformedValueFormat = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-valueformat-malformed.otf"),
        )
        val malformedPairRecords = parser.parse(
            fixtureFontSource("font/fixtures/fonts/shaping/gpos-pair-out-of-range.otf"),
        )

        assertEquals("font.sfnt.optional-table-malformed", malformedValueFormat.diagnostics.single().causeCode)
        assertTrue(
            malformedValueFormat.diagnostics.single().causeMessage.orEmpty().contains("PairSet 0 records"),
            malformedValueFormat.diagnostics.single().toString(),
        )
        assertEquals(null, malformedValueFormat.layout.gposSingles)
        assertEquals("font.sfnt.optional-table-malformed", malformedPairRecords.diagnostics.single().causeCode)
        assertTrue(
            malformedPairRecords.diagnostics.single().causeMessage.orEmpty().contains("pairSetCount"),
            malformedPairRecords.diagnostics.single().toString(),
        )
        assertEquals(null, malformedPairRecords.layout.gposPairs)
    }

    @Test
    fun defaultOpenTypeFaceParserReportsMalformedKernTablesAsDiagnostics() {
        val malformedKern = kernTable(
            kernFormat0Subtable(
                testKernPair(leftGlyphId = 7, rightGlyphId = 11, value = -40),
            ).also { subtable ->
                subtable.writeUInt16(2, 14)
            },
        )
        val source = memoryFontSource(
            sfntFont(
                "name" to nameTable(),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(
                                startCode = 0x0041,
                                endCode = 0x0041,
                                startGlyphId = 7,
                            ),
                        ),
                    ),
                ),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                    indexToLocFormat = 0,
                ),
                "hhea" to hheaTable(
                    ascender = 800,
                    descender = -200,
                    lineGap = 0,
                    numberOfHMetrics = 1,
                ),
                "maxp" to maxpTable(numGlyphs = 1),
                "hmtx" to hmtxTable(metric(advanceWidth = 500, leftSideBearing = 0)),
                "kern" to malformedKern,
            ),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)
        val diagnostic = parsed.diagnostics.single()

        assertEquals(SFNTTableTag("kern"), diagnostic.table)
        assertEquals("font.sfnt.optional-table-malformed", diagnostic.causeCode)
        assertTrue(
            diagnostic.causeMessage.orEmpty().contains("OpenType kern format 0 pair array for subtable 0"),
            "Unexpected diagnostic: $diagnostic",
        )
        assertEquals(malformedKern.size, parsed.layout.tables.getValue(SFNTTableTag("kern")).size)
        assertEquals(null, parsed.layout.kern)
    }

    @Test
    fun kernTableParserRejectsFormat0PairsOutsideSubtableLength() {
        val malformedKern = kernTable(
            kernFormat0Subtable(
                testKernPair(leftGlyphId = 7, rightGlyphId = 11, value = -40),
            ).also { subtable ->
                subtable.writeUInt16(2, 14)
            },
        )

        val error = assertFailsWith<IllegalArgumentException> {
            OpenTypeKernTableParser.parse(malformedKern)
        }

        assertTrue(
            error.message.orEmpty().contains("OpenType kern format 0 pair array for subtable 0"),
            "Unexpected error message: ${error.message}",
        )
    }

    private fun memoryFontSource(bytes: ByteArray): FontSource =
        FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440100")),
            kind = FontSourceKind.MEMORY,
            displayName = "Memory Font",
            bytes = bytes,
        )

    private fun fixtureFontSource(relativePath: String): FontSource {
        val path = fixturePath(relativePath)
        return FontSource(
            id = FontSourceID(Uuid.random()),
            kind = FontSourceKind.FILE,
            displayName = path.fileName.toString(),
            bytes = Files.readAllBytes(path),
        )
    }

    private fun fixturePath(relativePath: String): Path =
        projectRoot().resolve(relativePath).normalize()

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null && !Files.isDirectory(current.resolve("font/fixtures"))) {
            current = current.parent
        }
        return current
    }

    private data class TestNameRecord(
        val platformId: Int,
        val encodingId: Int,
        val languageId: Int,
        val nameId: Int,
        val bytes: ByteArray,
    )

    private data class ReviewedGsubFixtureCase(
        val relativePath: String,
        val expected: OpenTypeGsubTable,
    )

    private fun testNameRecord(
        platformId: Int,
        encodingId: Int,
        languageId: Int,
        nameId: Int,
        bytes: ByteArray,
    ): TestNameRecord = TestNameRecord(
        platformId = platformId,
        encodingId = encodingId,
        languageId = languageId,
        nameId = nameId,
        bytes = bytes,
    )

    private data class TestCMapRecord(
        val platformId: Int,
        val encodingId: Int,
        val subtable: ByteArray,
    )

    private data class TestFormat4Segment(
        val startCode: Int,
        val endCode: Int,
        val idDelta: Int,
    )

    private data class TestFormat12Group(
        val startCharCode: Int,
        val endCharCode: Int,
        val startGlyphId: Int,
    )

    private data class TestHorizontalMetric(
        val advanceWidth: Int?,
        val leftSideBearing: Int,
    )

    private data class TestVerticalMetric(
        val advanceHeight: Int?,
        val topSideBearing: Int,
    )

    private data class TestKernPair(
        val leftGlyphId: Int,
        val rightGlyphId: Int,
        val value: Int,
    )

    private data class TestFvarAxis(
        val tag: String,
        val minimum: Double,
        val defaultValue: Double,
        val maximum: Double,
        val flags: Int,
        val nameId: Int,
    )

    private fun testFvarAxis(
        tag: String,
        minimum: Double,
        defaultValue: Double,
        maximum: Double,
        flags: Int,
        nameId: Int,
    ): TestFvarAxis = TestFvarAxis(
        tag = tag,
        minimum = minimum,
        defaultValue = defaultValue,
        maximum = maximum,
        flags = flags,
        nameId = nameId,
    )

    private fun nameTable(vararg records: TestNameRecord): ByteArray {
        val stringOffset = 6 + records.size * 12
        val table = ByteArray(stringOffset + records.sumOf { it.bytes.size })
        table.writeUInt16(0, 0)
        table.writeUInt16(2, records.size)
        table.writeUInt16(4, stringOffset)

        var stringCursor = 0
        records.forEachIndexed { index, record ->
            val recordOffset = 6 + index * 12
            table.writeUInt16(recordOffset, record.platformId)
            table.writeUInt16(recordOffset + 2, record.encodingId)
            table.writeUInt16(recordOffset + 4, record.languageId)
            table.writeUInt16(recordOffset + 6, record.nameId)
            table.writeUInt16(recordOffset + 8, record.bytes.size)
            table.writeUInt16(recordOffset + 10, stringCursor)
            record.bytes.copyInto(table, stringOffset + stringCursor)
            stringCursor += record.bytes.size
        }

        return table
    }

    private fun testCMapRecord(
        platformId: Int,
        encodingId: Int,
        subtable: ByteArray,
    ): TestCMapRecord = TestCMapRecord(
        platformId = platformId,
        encodingId = encodingId,
        subtable = subtable,
    )

    private fun testFormat4Segment(
        startCode: Int,
        endCode: Int,
        startGlyphId: Int,
    ): TestFormat4Segment = TestFormat4Segment(
        startCode = startCode,
        endCode = endCode,
        idDelta = (startGlyphId - startCode) and 0xffff,
    )

    private fun testFormat12Group(
        startCharCode: Int,
        endCharCode: Int,
        startGlyphId: Int,
    ): TestFormat12Group = TestFormat12Group(
        startCharCode = startCharCode,
        endCharCode = endCharCode,
        startGlyphId = startGlyphId,
    )

    private fun cmapTable(vararg records: TestCMapRecord): ByteArray {
        val recordsEnd = 4 + records.size * 8
        val table = ByteArray(recordsEnd + records.sumOf { it.subtable.size })
        table.writeUInt16(0, 0)
        table.writeUInt16(2, records.size)

        var subtableOffset = recordsEnd
        records.forEachIndexed { index, record ->
            val recordOffset = 4 + index * 8
            table.writeUInt16(recordOffset, record.platformId)
            table.writeUInt16(recordOffset + 2, record.encodingId)
            table.writeUInt32(recordOffset + 4, subtableOffset)
            record.subtable.copyInto(table, subtableOffset)
            subtableOffset += record.subtable.size
        }

        return table
    }

    private fun format4Subtable(vararg segments: TestFormat4Segment): ByteArray {
        val allSegments = segments.toList() + TestFormat4Segment(
            startCode = 0xffff,
            endCode = 0xffff,
            idDelta = 1,
        )
        val segCount = allSegments.size
        val length = 16 + segCount * 8
        val table = ByteArray(length)
        table.writeUInt16(0, 4)
        table.writeUInt16(2, length)
        table.writeUInt16(4, 0)
        table.writeUInt16(6, segCount * 2)
        table.writeUInt16(8, 4)
        table.writeUInt16(10, 1)
        table.writeUInt16(12, 0)

        val endCodeOffset = 14
        val reservedPadOffset = endCodeOffset + segCount * 2
        val startCodeOffset = reservedPadOffset + 2
        val idDeltaOffset = startCodeOffset + segCount * 2
        val idRangeOffsetOffset = idDeltaOffset + segCount * 2

        allSegments.forEachIndexed { index, segment ->
            table.writeUInt16(endCodeOffset + index * 2, segment.endCode)
            table.writeUInt16(startCodeOffset + index * 2, segment.startCode)
            table.writeUInt16(idDeltaOffset + index * 2, segment.idDelta)
            table.writeUInt16(idRangeOffsetOffset + index * 2, 0)
        }

        return table
    }

    private fun format4RangeOffsetSubtable(
        startCode: Int,
        endCode: Int,
        rawGlyphIds: List<Int>,
    ): ByteArray {
        require(rawGlyphIds.size == endCode - startCode + 1)

        val segCount = 2
        val glyphIdArrayOffset = 16 + segCount * 8
        val length = glyphIdArrayOffset + rawGlyphIds.size * 2
        val table = ByteArray(length)
        table.writeUInt16(0, 4)
        table.writeUInt16(2, length)
        table.writeUInt16(4, 0)
        table.writeUInt16(6, segCount * 2)
        table.writeUInt16(8, 4)
        table.writeUInt16(10, 1)
        table.writeUInt16(12, 0)

        val endCodeOffset = 14
        val reservedPadOffset = endCodeOffset + segCount * 2
        val startCodeOffset = reservedPadOffset + 2
        val idDeltaOffset = startCodeOffset + segCount * 2
        val idRangeOffsetOffset = idDeltaOffset + segCount * 2

        table.writeUInt16(endCodeOffset, endCode)
        table.writeUInt16(endCodeOffset + 2, 0xffff)
        table.writeUInt16(startCodeOffset, startCode)
        table.writeUInt16(startCodeOffset + 2, 0xffff)
        table.writeUInt16(idDeltaOffset, 0)
        table.writeUInt16(idDeltaOffset + 2, 1)
        table.writeUInt16(idRangeOffsetOffset, glyphIdArrayOffset - idRangeOffsetOffset)
        table.writeUInt16(idRangeOffsetOffset + 2, 0)
        rawGlyphIds.forEachIndexed { index, glyphId ->
            table.writeUInt16(glyphIdArrayOffset + index * 2, glyphId)
        }

        return table
    }

    private fun format12Subtable(vararg groups: TestFormat12Group): ByteArray {
        val length = 16 + groups.size * 12
        val table = ByteArray(length)
        table.writeUInt16(0, 12)
        table.writeUInt16(2, 0)
        table.writeUInt32(4, length)
        table.writeUInt32(8, 0)
        table.writeUInt32(12, groups.size)

        groups.forEachIndexed { index, group ->
            val groupOffset = 16 + index * 12
            table.writeUInt32(groupOffset, group.startCharCode)
            table.writeUInt32(groupOffset + 4, group.endCharCode)
            table.writeUInt32(groupOffset + 8, group.startGlyphId)
        }

        return table
    }

    private fun cmapMapReport(): String {
        val priorityGlyphIds = MutableList(256) { 0 }
        priorityGlyphIds[0x41] = 3
        val priority = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 0,
                    encodingId = 0,
                    subtable = format6Subtable(firstCode = 0x41, glyphIds = listOf(2, 0)),
                ),
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(startCode = 0x0041, endCode = 0x0041, startGlyphId = 7),
                    ),
                ),
                testCMapRecord(
                    platformId = 3,
                    encodingId = 10,
                    subtable = format12Subtable(
                        testFormat12Group(startCharCode = 0x0041, endCharCode = 0x0041, startGlyphId = 9),
                        testFormat12Group(startCharCode = 0x2764, endCharCode = 0x2764, startGlyphId = 20),
                        testFormat12Group(startCharCode = 0x1f600, endCharCode = 0x1f600, startGlyphId = 300),
                    ),
                ),
                testCMapRecord(
                    platformId = 0,
                    encodingId = 5,
                    subtable = format14Subtable(
                        variationSelector = 0xfe0f,
                        defaultRanges = listOf(0x2764 to 0),
                        nonDefaultMappings = listOf(0x1f600 to 99),
                    ),
                ),
                testCMapRecord(
                    platformId = 1,
                    encodingId = 0,
                    subtable = format0Subtable(priorityGlyphIds),
                ),
            ),
        )
        val format4 = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(startCode = 0x0041, endCode = 0x0042, startGlyphId = 11),
                    ),
                ),
            ),
        )
        val format6 = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 0,
                    encodingId = 0,
                    subtable = format6Subtable(firstCode = 0x41, glyphIds = listOf(17, 0, 19)),
                ),
            ),
        )
        val format0GlyphIds = MutableList(256) { 0 }
        format0GlyphIds[0x41] = 23
        val format0 = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 1,
                    encodingId = 0,
                    subtable = format0Subtable(format0GlyphIds),
                ),
            ),
        )
        val unsupported = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 10,
                    subtable = format13Subtable(),
                ),
            ),
        )
        val malformedUnsupported = OpenTypeCMapTableParser.parse(
            cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 10,
                    subtable = malformedFormat13Subtable(),
                ),
            ),
        )

        val unsupportedDiagnostics = unsupported.diagnostics.joinToString(",\n") { diagnostic ->
            """{"code": "${diagnostic.code}", "format": ${diagnostic.format?.toString() ?: "null"}, "platformId": ${diagnostic.platformId?.toString() ?: "null"}, "encodingId": ${diagnostic.encodingId?.toString() ?: "null"}, "offset": ${diagnostic.offset?.toString() ?: "null"}, "message": "${diagnostic.message.jsonEscapedForTest()}"}"""
        }.replace("\n", "\n                    ")
        val malformedUnsupportedDiagnostics = malformedUnsupported.diagnostics.joinToString(",\n") { diagnostic ->
            """{"code": "${diagnostic.code}", "format": ${diagnostic.format?.toString() ?: "null"}, "platformId": ${diagnostic.platformId?.toString() ?: "null"}, "encodingId": ${diagnostic.encodingId?.toString() ?: "null"}, "offset": ${diagnostic.offset?.toString() ?: "null"}, "message": "${diagnostic.message.jsonEscapedForTest()}"}"""
        }.replace("\n", "\n                    ")

        return """
            {
              "schema": "org.graphiks.kanvas.font.sfnt.CMapMapReport.v1",
              "schemaVersion": 1,
              "ticketIds": ["KFONT-M2-003"],
              "dashboardClassification": "tracked-gap",
              "claimPromotionAllowed": false,
              "entries": [
                {
                  "entryId": "generated-format12-priority-with-format14",
                  "fixtureId": "cmap-formats-12-4-14-6-0-generated",
                  "fixtureKind": "GeneratedFixtureFontSource",
                  "sourceFaceId": "generated-cmap-priority-face-0",
                  "selectedSubtable": {"platformId": ${priority.preferredSubtable?.platformId}, "encodingId": ${priority.preferredSubtable?.encodingId}, "format": ${priority.preferredSubtable?.format}},
                  "encodingRecords": [
                    {"platformId": 0, "encodingId": 0, "format": 6},
                    {"platformId": 3, "encodingId": 1, "format": 4},
                    {"platformId": 3, "encodingId": 10, "format": 12},
                    {"platformId": 0, "encodingId": 5, "format": 14},
                    {"platformId": 1, "encodingId": 0, "format": 0}
                  ],
                  "mappedRanges": [
                    {"format": 12, "startCodePoint": "U+0041", "endCodePoint": "U+0041", "startGlyphId": 9},
                    {"format": 12, "startCodePoint": "U+2764", "endCodePoint": "U+2764", "startGlyphId": 20},
                    {"format": 12, "startCodePoint": "U+1F600", "endCodePoint": "U+1F600", "startGlyphId": 300}
                  ],
                  "lookupFacts": [
                    {"codePoint": "U+0041", "variationSelector": null, "glyphId": ${priority.lookupGlyphId(0x0041)}},
                    {"codePoint": "U+0042", "variationSelector": null, "glyphId": ${priority.lookupGlyphId(0x0042)}},
                    {"codePoint": "U+2764", "variationSelector": "U+FE0F", "glyphId": ${priority.lookupGlyphId(0x2764, variationSelector = 0xfe0f)}},
                    {"codePoint": "U+1F600", "variationSelector": "U+FE0F", "glyphId": ${priority.lookupGlyphId(0x1f600, variationSelector = 0xfe0f)}}
                  ],
                  "variationSelectorFacts": [
                    {
                      "format": ${priority.variationSubtable?.format},
                      "variationSelector": "U+FE0F",
                      "defaultRanges": [{"startCodePoint": "U+2764", "endCodePoint": "U+2764", "baseGlyphId": 20}],
                      "nonDefaultMappings": [{"codePoint": "U+1F600", "glyphId": 99}]
                    }
                  ],
                  "diagnostics": []
                },
                {
                  "entryId": "generated-format4-bmp-selected",
                  "fixtureId": "cmap-format4-generated",
                  "fixtureKind": "GeneratedFixtureFontSource",
                  "sourceFaceId": "generated-cmap-format4-face-0",
                  "selectedSubtable": {"platformId": ${format4.preferredSubtable?.platformId}, "encodingId": ${format4.preferredSubtable?.encodingId}, "format": ${format4.preferredSubtable?.format}},
                  "mappedRanges": [{"format": 4, "startCodePoint": "U+0041", "endCodePoint": "U+0042", "startGlyphId": 11}],
                  "lookupFacts": [
                    {"codePoint": "U+0041", "variationSelector": null, "glyphId": ${format4.lookupGlyphId(0x0041)}},
                    {"codePoint": "U+0043", "variationSelector": null, "glyphId": ${format4.lookupGlyphId(0x0043)}}
                  ],
                  "diagnostics": []
                },
                {
                  "entryId": "generated-format6-legacy-fallback-selected",
                  "fixtureId": "cmap-format6-generated",
                  "fixtureKind": "GeneratedFixtureFontSource",
                  "sourceFaceId": "generated-cmap-format6-face-0",
                  "selectedSubtable": {"platformId": ${format6.preferredSubtable?.platformId}, "encodingId": ${format6.preferredSubtable?.encodingId}, "format": ${format6.preferredSubtable?.format}},
                  "mappedRanges": [{"format": 6, "startCodePoint": "U+0041", "endCodePoint": "U+0043", "glyphIds": [17, 0, 19]}],
                  "lookupFacts": [
                    {"codePoint": "U+0041", "variationSelector": null, "glyphId": ${format6.lookupGlyphId(0x0041)}},
                    {"codePoint": "U+0042", "variationSelector": null, "glyphId": ${format6.lookupGlyphId(0x0042)}},
                    {"codePoint": "U+0044", "variationSelector": null, "glyphId": ${format6.lookupGlyphId(0x0044)}}
                  ],
                  "diagnostics": []
                },
                {
                  "entryId": "generated-format0-legacy-fallback-selected",
                  "fixtureId": "cmap-format0-generated",
                  "fixtureKind": "GeneratedFixtureFontSource",
                  "sourceFaceId": "generated-cmap-format0-face-0",
                  "selectedSubtable": {"platformId": ${format0.preferredSubtable?.platformId}, "encodingId": ${format0.preferredSubtable?.encodingId}, "format": ${format0.preferredSubtable?.format}},
                  "mappedRanges": [{"format": 0, "startCodePoint": "U+0000", "endCodePoint": "U+00FF", "glyphArrayLength": 256}],
                  "lookupFacts": [
                    {"codePoint": "U+0041", "variationSelector": null, "glyphId": ${format0.lookupGlyphId(0x0041)}},
                    {"codePoint": "U+0042", "variationSelector": null, "glyphId": ${format0.lookupGlyphId(0x0042)}}
                  ],
                  "diagnostics": []
                },
                {
                  "entryId": "generated-format13-refused",
                  "fixtureId": "cmap-format13-generated-refusal",
                  "fixtureKind": "GeneratedFixtureFontSource",
                  "sourceFaceId": "generated-cmap-format13-face-0",
                  "selectedSubtable": null,
                  "mappedRanges": [],
                  "lookupFacts": [{"codePoint": "U+0041", "variationSelector": null, "glyphId": ${unsupported.lookupGlyphId(0x0041)}}],
                  "diagnostics": [
                    $unsupportedDiagnostics
                  ]
                },
                {
                  "entryId": "malformed-generated-format13-refused",
                  "fixtureId": "malformed-sfnt-unsupported-cmap-format13-generated",
                  "fixtureKind": "GeneratedFixtureFontSource",
                  "sourceFaceId": "generated-unsupported-cmap-format13",
                  "selectedSubtable": null,
                  "mappedRanges": [],
                  "lookupFacts": [{"codePoint": "U+0041", "variationSelector": null, "glyphId": ${malformedUnsupported.lookupGlyphId(0x0041)}}],
                  "diagnostics": [
                    $malformedUnsupportedDiagnostics
                  ]
                }
              ]
            }
        """.trimIndent() + "\n"
    }

    private fun format13Subtable(): ByteArray {
        val table = ByteArray(16)
        table.writeUInt16(0, 13)
        table.writeUInt16(2, 0)
        table.writeUInt32(4, table.size)
        table.writeUInt32(8, 0)
        table.writeUInt32(12, 0)
        return table
    }

    private fun malformedFormat13Subtable(): ByteArray {
        val table = ByteArray(28)
        table.writeUInt16(0, 13)
        table.writeUInt32(4, table.size)
        table.writeUInt32(12, 1)
        table.writeUInt32(16, 0x0041)
        table.writeUInt32(20, 0x0041)
        table.writeUInt32(24, 5)
        return table
    }

    private fun format14Subtable(
        variationSelector: Int,
        defaultRanges: List<Pair<Int, Int>>,
        nonDefaultMappings: List<Pair<Int, Int>>,
    ): ByteArray {
        val defaultOffset = 10 + 11
        val nonDefaultOffset = defaultOffset + 4 + defaultRanges.size * 4
        val length = nonDefaultOffset + 4 + nonDefaultMappings.size * 5
        val table = ByteArray(length)
        table.writeUInt16(0, 14)
        table.writeUInt32(2, length)
        table.writeUInt32(6, 1)
        table.writeUInt24(10, variationSelector)
        table.writeUInt32(13, defaultOffset)
        table.writeUInt32(17, nonDefaultOffset)
        table.writeUInt32(defaultOffset, defaultRanges.size)
        defaultRanges.forEachIndexed { index, (startUnicodeValue, additionalCount) ->
            val rangeOffset = defaultOffset + 4 + index * 4
            table.writeUInt24(rangeOffset, startUnicodeValue)
            table[rangeOffset + 3] = additionalCount.toByte()
        }
        table.writeUInt32(nonDefaultOffset, nonDefaultMappings.size)
        nonDefaultMappings.forEachIndexed { index, (unicodeValue, glyphId) ->
            val mappingOffset = nonDefaultOffset + 4 + index * 5
            table.writeUInt24(mappingOffset, unicodeValue)
            table.writeUInt16(mappingOffset + 3, glyphId)
        }
        return table
    }

    private fun format0Subtable(glyphIds: List<Int>): ByteArray {
        require(glyphIds.size == 256)
        val table = ByteArray(262)
        table.writeUInt16(0, 0)
        table.writeUInt16(2, table.size)
        table.writeUInt16(4, 0)
        glyphIds.forEachIndexed { index, glyphId ->
            table[6 + index] = glyphId.toByte()
        }
        return table
    }

    private fun format6Subtable(firstCode: Int, glyphIds: List<Int>): ByteArray {
        val table = ByteArray(10 + glyphIds.size * 2)
        table.writeUInt16(0, 6)
        table.writeUInt16(2, table.size)
        table.writeUInt16(4, 0)
        table.writeUInt16(6, firstCode)
        table.writeUInt16(8, glyphIds.size)
        glyphIds.forEachIndexed { index, glyphId ->
            table.writeUInt16(10 + index * 2, glyphId)
        }
        return table
    }

    private fun headTable(
        unitsPerEm: Int,
        bounds: OpenTypeFontBounds,
        indexToLocFormat: Int,
        macStyle: Int = 0,
    ): ByteArray {
        val table = ByteArray(54)
        table.writeUInt16(18, unitsPerEm)
        table.writeInt16(36, bounds.xMin)
        table.writeInt16(38, bounds.yMin)
        table.writeInt16(40, bounds.xMax)
        table.writeInt16(42, bounds.yMax)
        table.writeUInt16(44, macStyle)
        table.writeInt16(50, indexToLocFormat)
        return table
    }

    private fun hheaTable(
        ascender: Int,
        descender: Int,
        lineGap: Int,
        maxCharWidth: Int = 0,
        numberOfHMetrics: Int,
    ): ByteArray {
        val table = ByteArray(36)
        table.writeInt16(4, ascender)
        table.writeInt16(6, descender)
        table.writeInt16(8, lineGap)
        table.writeUInt16(10, maxCharWidth)
        table.writeUInt16(34, numberOfHMetrics)
        return table
    }

    private fun maxpTable(numGlyphs: Int): ByteArray {
        val table = ByteArray(6)
        table.writeUInt16(4, numGlyphs)
        return table
    }

    private fun hmtxTable(vararg metrics: TestHorizontalMetric): ByteArray {
        val length = metrics.sumOf { if (it.advanceWidth == null) 2 else 4 }
        val table = ByteArray(length)
        var offset = 0
        metrics.forEach { metric ->
            if (metric.advanceWidth != null) {
                table.writeUInt16(offset, metric.advanceWidth)
                offset += 2
            }
            table.writeInt16(offset, metric.leftSideBearing)
            offset += 2
        }
        return table
    }

    private fun vheaTable(
        ascender: Int,
        descender: Int,
        lineGap: Int,
        maxAdvanceHeight: Int = 0,
        numberOfVMetrics: Int,
    ): ByteArray {
        val table = ByteArray(36)
        table.writeInt16(4, ascender)
        table.writeInt16(6, descender)
        table.writeInt16(8, lineGap)
        table.writeUInt16(10, maxAdvanceHeight)
        table.writeUInt16(34, numberOfVMetrics)
        return table
    }

    private fun vmtxTable(vararg metrics: TestVerticalMetric): ByteArray {
        val length = metrics.sumOf { if (it.advanceHeight == null) 2 else 4 }
        val table = ByteArray(length)
        var offset = 0
        metrics.forEach { metric ->
            if (metric.advanceHeight != null) {
                table.writeUInt16(offset, metric.advanceHeight)
                offset += 2
            }
            table.writeInt16(offset, metric.topSideBearing)
            offset += 2
        }
        return table
    }

    private fun os2Table(
        averageCharWidth: Int,
        xHeight: Int,
        capHeight: Int,
        strikeoutThickness: Int,
        strikeoutPosition: Int,
        weightClass: Int = 400,
        widthClass: Int = 5,
        fsSelection: Int = 0,
        typoAscender: Int = 0,
        typoDescender: Int = 0,
        typoLineGap: Int = 0,
    ): ByteArray {
        val table = ByteArray(96)
        table.writeUInt16(0, 2)
        table.writeInt16(2, averageCharWidth)
        table.writeUInt16(4, weightClass)
        table.writeUInt16(6, widthClass)
        table.writeInt16(26, strikeoutThickness)
        table.writeInt16(28, strikeoutPosition)
        table.writeUInt16(62, fsSelection)
        table.writeInt16(68, typoAscender)
        table.writeInt16(70, typoDescender)
        table.writeInt16(72, typoLineGap)
        table.writeInt16(86, xHeight)
        table.writeInt16(88, capHeight)
        return table
    }

    private fun postTable(
        underlinePosition: Int,
        underlineThickness: Int,
        italicAngleHigh: Int = 0,
    ): ByteArray {
        val table = ByteArray(12)
        table.writeInt16(4, italicAngleHigh)
        table.writeInt16(8, underlinePosition)
        table.writeInt16(10, underlineThickness)
        return table
    }

    private fun kernTable(vararg subtables: ByteArray): ByteArray {
        val table = ByteArray(4 + subtables.sumOf { it.size })
        table.writeUInt16(0, 0)
        table.writeUInt16(2, subtables.size)

        var offset = 4
        subtables.forEach { subtable ->
            subtable.copyInto(table, offset)
            offset += subtable.size
        }

        return table
    }

    private fun kernFormat0Subtable(
        vararg pairs: TestKernPair,
        coverage: Int = 0x0001,
    ): ByteArray {
        val length = 14 + pairs.size * 6
        val table = ByteArray(length)
        table.writeUInt16(0, 0)
        table.writeUInt16(2, length)
        table.writeUInt16(4, coverage)
        table.writeUInt16(6, pairs.size)
        table.writeUInt16(8, 0)
        table.writeUInt16(10, 0)
        table.writeUInt16(12, 0)

        pairs.forEachIndexed { index, pair ->
            val pairOffset = 14 + index * 6
            table.writeUInt16(pairOffset, pair.leftGlyphId)
            table.writeUInt16(pairOffset + 2, pair.rightGlyphId)
            table.writeInt16(pairOffset + 4, pair.value)
        }

        return table
    }

    private fun gposPairAdjustmentFormat1Table(
        leftGlyphId: Int,
        rightGlyphId: Int,
        xAdvance: Int,
        scriptTag: String = "latn",
        declaredPairSetCount: Int = 1,
    ): ByteArray {
        require(scriptTag.length == 4)

        val table = ByteArray(80)
        val scriptListOffset = 10
        val featureListOffset = 30
        val lookupListOffset = 44
        val scriptStart = scriptListOffset + 8
        val langSysStart = scriptStart + 4
        val featureStart = featureListOffset + 8
        val lookupStart = lookupListOffset + 4
        val subtableStart = lookupStart + 8
        val coverageOffset = 12
        val pairSetOffset = 18

        table.writeUInt16(0, 1)
        table.writeUInt16(2, 0)
        table.writeUInt16(4, scriptListOffset)
        table.writeUInt16(6, featureListOffset)
        table.writeUInt16(8, lookupListOffset)

        table.writeUInt16(scriptListOffset, 1)
        scriptTag.toByteArray(Charsets.ISO_8859_1).copyInto(table, scriptListOffset + 2)
        table.writeUInt16(scriptListOffset + 6, 8)
        table.writeUInt16(scriptStart, 4)
        table.writeUInt16(scriptStart + 2, 0)
        table.writeUInt16(langSysStart, 0)
        table.writeUInt16(langSysStart + 2, 0xffff)
        table.writeUInt16(langSysStart + 4, 1)
        table.writeUInt16(langSysStart + 6, 0)

        table.writeUInt16(featureListOffset, 1)
        "kern".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 2)
        table.writeUInt16(featureListOffset + 6, 8)
        table.writeUInt16(featureStart, 0)
        table.writeUInt16(featureStart + 2, 1)
        table.writeUInt16(featureStart + 4, 0)

        table.writeUInt16(lookupListOffset, 1)
        table.writeUInt16(lookupListOffset + 2, 4)
        table.writeUInt16(lookupStart, 2)
        table.writeUInt16(lookupStart + 2, 0)
        table.writeUInt16(lookupStart + 4, 1)
        table.writeUInt16(lookupStart + 6, 8)

        table.writeUInt16(subtableStart, 1)
        table.writeUInt16(subtableStart + 2, coverageOffset)
        table.writeUInt16(subtableStart + 4, 0x0004)
        table.writeUInt16(subtableStart + 6, 0)
        table.writeUInt16(subtableStart + 8, declaredPairSetCount)
        table.writeUInt16(subtableStart + 10, pairSetOffset)
        table.writeUInt16(subtableStart + coverageOffset, 1)
        table.writeUInt16(subtableStart + coverageOffset + 2, 1)
        table.writeUInt16(subtableStart + coverageOffset + 4, leftGlyphId)
        table.writeUInt16(subtableStart + pairSetOffset, 1)
        table.writeUInt16(subtableStart + pairSetOffset + 2, rightGlyphId)
        table.writeInt16(subtableStart + pairSetOffset + 4, xAdvance)

        return table
    }

    private fun gposSingleAdjustmentFormat1Table(
        glyphId: Int,
        xPlacement: Int = 0,
        yPlacement: Int = 0,
        xAdvance: Int = 0,
        scriptTag: String = "latn",
    ): ByteArray {
        require(scriptTag.length == 4)

        val table = ByteArray(78)
        val scriptListOffset = 10
        val featureListOffset = 30
        val lookupListOffset = 44
        val scriptStart = scriptListOffset + 8
        val langSysStart = scriptStart + 4
        val featureStart = featureListOffset + 8
        val lookupStart = lookupListOffset + 4
        val subtableStart = lookupStart + 8
        val coverageOffset = 12
        val valueRecordStart = subtableStart + 6

        table.writeUInt16(0, 1)
        table.writeUInt16(2, 0)
        table.writeUInt16(4, scriptListOffset)
        table.writeUInt16(6, featureListOffset)
        table.writeUInt16(8, lookupListOffset)

        table.writeUInt16(scriptListOffset, 1)
        scriptTag.toByteArray(Charsets.ISO_8859_1).copyInto(table, scriptListOffset + 2)
        table.writeUInt16(scriptListOffset + 6, 8)
        table.writeUInt16(scriptStart, 4)
        table.writeUInt16(scriptStart + 2, 0)
        table.writeUInt16(langSysStart, 0)
        table.writeUInt16(langSysStart + 2, 0xffff)
        table.writeUInt16(langSysStart + 4, 1)
        table.writeUInt16(langSysStart + 6, 0)

        table.writeUInt16(featureListOffset, 1)
        "kern".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 2)
        table.writeUInt16(featureListOffset + 6, 8)
        table.writeUInt16(featureStart, 0)
        table.writeUInt16(featureStart + 2, 1)
        table.writeUInt16(featureStart + 4, 0)

        table.writeUInt16(lookupListOffset, 1)
        table.writeUInt16(lookupListOffset + 2, 4)
        table.writeUInt16(lookupStart, 1)
        table.writeUInt16(lookupStart + 2, 0)
        table.writeUInt16(lookupStart + 4, 1)
        table.writeUInt16(lookupStart + 6, 8)

        table.writeUInt16(subtableStart, 1)
        table.writeUInt16(subtableStart + 2, coverageOffset)
        table.writeUInt16(subtableStart + 4, 0x0007)
        table.writeInt16(valueRecordStart, xPlacement)
        table.writeInt16(valueRecordStart + 2, yPlacement)
        table.writeInt16(valueRecordStart + 4, xAdvance)
        table.writeUInt16(subtableStart + coverageOffset, 1)
        table.writeUInt16(subtableStart + coverageOffset + 2, 1)
        table.writeUInt16(subtableStart + coverageOffset + 4, glyphId)

        return table
    }

    private fun gposCursiveAndMalformedKernTable(): ByteArray {
        val table = ByteArray(140)
        val scriptListOffset = 10
        val featureListOffset = 32
        val lookupListOffset = 60
        val scriptStart = scriptListOffset + 8
        val langSysStart = scriptStart + 4
        val firstFeatureStart = featureListOffset + 14
        val secondFeatureStart = firstFeatureStart + 6
        val firstLookupStart = lookupListOffset + 6
        val firstSubtableStart = firstLookupStart + 8
        val secondLookupStart = firstLookupStart + 32
        val secondSubtableStart = secondLookupStart + 8

        table.writeUInt16(0, 1)
        table.writeUInt16(2, 0)
        table.writeUInt16(4, scriptListOffset)
        table.writeUInt16(6, featureListOffset)
        table.writeUInt16(8, lookupListOffset)

        table.writeUInt16(scriptListOffset, 1)
        "latn".toByteArray(Charsets.ISO_8859_1).copyInto(table, scriptListOffset + 2)
        table.writeUInt16(scriptListOffset + 6, 8)
        table.writeUInt16(scriptStart, 4)
        table.writeUInt16(scriptStart + 2, 0)
        table.writeUInt16(langSysStart, 0)
        table.writeUInt16(langSysStart + 2, 0xffff)
        table.writeUInt16(langSysStart + 4, 2)
        table.writeUInt16(langSysStart + 6, 0)
        table.writeUInt16(langSysStart + 8, 1)

        table.writeUInt16(featureListOffset, 2)
        "kern".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 2)
        table.writeUInt16(featureListOffset + 6, 14)
        "curs".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 8)
        table.writeUInt16(featureListOffset + 12, 20)
        table.writeUInt16(firstFeatureStart, 0)
        table.writeUInt16(firstFeatureStart + 2, 1)
        table.writeUInt16(firstFeatureStart + 4, 0)
        table.writeUInt16(secondFeatureStart, 0)
        table.writeUInt16(secondFeatureStart + 2, 1)
        table.writeUInt16(secondFeatureStart + 4, 1)

        table.writeUInt16(lookupListOffset, 2)
        table.writeUInt16(lookupListOffset + 2, 6)
        table.writeUInt16(lookupListOffset + 4, 38)

        table.writeUInt16(firstLookupStart, 2)
        table.writeUInt16(firstLookupStart + 2, 0)
        table.writeUInt16(firstLookupStart + 4, 1)
        table.writeUInt16(firstLookupStart + 6, 8)
        table.writeUInt16(firstSubtableStart, 1)
        table.writeUInt16(firstSubtableStart + 2, 12)
        table.writeUInt16(firstSubtableStart + 4, 0x0004)
        table.writeUInt16(firstSubtableStart + 6, 0)
        table.writeUInt16(firstSubtableStart + 8, 2)
        table.writeUInt16(firstSubtableStart + 10, 18)
        table.writeUInt16(firstSubtableStart + 12, 1)
        table.writeUInt16(firstSubtableStart + 14, 1)
        table.writeUInt16(firstSubtableStart + 16, 7)
        table.writeUInt16(firstSubtableStart + 18, 1)
        table.writeUInt16(firstSubtableStart + 20, 11)
        table.writeInt16(firstSubtableStart + 22, -55)

        table.writeUInt16(secondLookupStart, 3)
        table.writeUInt16(secondLookupStart + 2, 0)
        table.writeUInt16(secondLookupStart + 4, 1)
        table.writeUInt16(secondLookupStart + 6, 8)
        table.writeUInt16(secondSubtableStart, 9)
        table.writeUInt16(secondSubtableStart + 2, 0)
        table.writeUInt16(secondSubtableStart + 4, 0)

        return table
    }

    private fun gsubSimpleLookupsTable(): ByteArray {
        val table = ByteArray(150)
        val scriptListOffset = 10
        val featureListOffset = 32
        val lookupListOffset = 60
        val scriptStart = scriptListOffset + 8
        val langSysStart = scriptStart + 4
        val feature1Start = featureListOffset + 14
        val feature2Start = feature1Start + 8
        val lookupStart = lookupListOffset + 8
        val singleLookupStart = lookupStart
        val multipleLookupStart = singleLookupStart + 22
        val ligatureLookupStart = multipleLookupStart + 28

        table.writeUInt16(0, 1)
        table.writeUInt16(2, 0)
        table.writeUInt16(4, scriptListOffset)
        table.writeUInt16(6, featureListOffset)
        table.writeUInt16(8, lookupListOffset)

        table.writeUInt16(scriptListOffset, 1)
        "latn".toByteArray(Charsets.ISO_8859_1).copyInto(table, scriptListOffset + 2)
        table.writeUInt16(scriptListOffset + 6, 8)
        table.writeUInt16(scriptStart, 4)
        table.writeUInt16(scriptStart + 2, 0)
        table.writeUInt16(langSysStart, 0)
        table.writeUInt16(langSysStart + 2, 0xffff)
        table.writeUInt16(langSysStart + 4, 2)
        table.writeUInt16(langSysStart + 6, 0)
        table.writeUInt16(langSysStart + 8, 1)

        table.writeUInt16(featureListOffset, 2)
        "ccmp".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 2)
        table.writeUInt16(featureListOffset + 6, 14)
        "liga".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 8)
        table.writeUInt16(featureListOffset + 12, 22)
        table.writeUInt16(feature1Start, 0)
        table.writeUInt16(feature1Start + 2, 2)
        table.writeUInt16(feature1Start + 4, 0)
        table.writeUInt16(feature1Start + 6, 1)
        table.writeUInt16(feature2Start, 0)
        table.writeUInt16(feature2Start + 2, 1)
        table.writeUInt16(feature2Start + 4, 2)

        table.writeUInt16(lookupListOffset, 3)
        table.writeUInt16(lookupListOffset + 2, 8)
        table.writeUInt16(lookupListOffset + 4, 30)
        table.writeUInt16(lookupListOffset + 6, 58)

        table.writeUInt16(singleLookupStart, 1)
        table.writeUInt16(singleLookupStart + 2, 0)
        table.writeUInt16(singleLookupStart + 4, 1)
        table.writeUInt16(singleLookupStart + 6, 8)
        table.writeUInt16(singleLookupStart + 8, 2)
        table.writeUInt16(singleLookupStart + 10, 8)
        table.writeUInt16(singleLookupStart + 12, 1)
        table.writeUInt16(singleLookupStart + 14, 15)
        table.writeUInt16(singleLookupStart + 16, 1)
        table.writeUInt16(singleLookupStart + 18, 1)
        table.writeUInt16(singleLookupStart + 20, 5)

        table.writeUInt16(multipleLookupStart, 2)
        table.writeUInt16(multipleLookupStart + 2, 0)
        table.writeUInt16(multipleLookupStart + 4, 1)
        table.writeUInt16(multipleLookupStart + 6, 8)
        table.writeUInt16(multipleLookupStart + 8, 1)
        table.writeUInt16(multipleLookupStart + 10, 14)
        table.writeUInt16(multipleLookupStart + 12, 1)
        table.writeUInt16(multipleLookupStart + 14, 8)
        table.writeUInt16(multipleLookupStart + 16, 2)
        table.writeUInt16(multipleLookupStart + 18, 16)
        table.writeUInt16(multipleLookupStart + 20, 17)
        table.writeUInt16(multipleLookupStart + 22, 1)
        table.writeUInt16(multipleLookupStart + 24, 1)
        table.writeUInt16(multipleLookupStart + 26, 6)

        table.writeUInt16(ligatureLookupStart, 4)
        table.writeUInt16(ligatureLookupStart + 2, 0)
        table.writeUInt16(ligatureLookupStart + 4, 1)
        table.writeUInt16(ligatureLookupStart + 6, 8)
        table.writeUInt16(ligatureLookupStart + 8, 1)
        table.writeUInt16(ligatureLookupStart + 10, 18)
        table.writeUInt16(ligatureLookupStart + 12, 1)
        table.writeUInt16(ligatureLookupStart + 14, 8)
        table.writeUInt16(ligatureLookupStart + 16, 1)
        table.writeUInt16(ligatureLookupStart + 18, 4)
        table.writeUInt16(ligatureLookupStart + 20, 42)
        table.writeUInt16(ligatureLookupStart + 22, 2)
        table.writeUInt16(ligatureLookupStart + 24, 8)
        table.writeUInt16(ligatureLookupStart + 26, 1)
        table.writeUInt16(ligatureLookupStart + 28, 1)
        table.writeUInt16(ligatureLookupStart + 30, 7)

        return table
    }

    private fun gsubSharedLookupAcrossFeaturesTable(): ByteArray {
        val table = ByteArray(96)
        val scriptListOffset = 10
        val featureListOffset = 32
        val lookupListOffset = 60
        val scriptStart = scriptListOffset + 8
        val langSysStart = scriptStart + 4
        val firstFeatureStart = featureListOffset + 14
        val secondFeatureStart = firstFeatureStart + 6
        val lookupStart = lookupListOffset + 4

        table.writeUInt16(0, 1)
        table.writeUInt16(2, 0)
        table.writeUInt16(4, scriptListOffset)
        table.writeUInt16(6, featureListOffset)
        table.writeUInt16(8, lookupListOffset)

        table.writeUInt16(scriptListOffset, 1)
        "latn".toByteArray(Charsets.ISO_8859_1).copyInto(table, scriptListOffset + 2)
        table.writeUInt16(scriptListOffset + 6, 8)
        table.writeUInt16(scriptStart, 4)
        table.writeUInt16(scriptStart + 2, 0)
        table.writeUInt16(langSysStart, 0)
        table.writeUInt16(langSysStart + 2, 0xffff)
        table.writeUInt16(langSysStart + 4, 2)
        table.writeUInt16(langSysStart + 6, 0)
        table.writeUInt16(langSysStart + 8, 1)

        table.writeUInt16(featureListOffset, 2)
        "ccmp".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 2)
        table.writeUInt16(featureListOffset + 6, 14)
        "liga".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 8)
        table.writeUInt16(featureListOffset + 12, 20)

        table.writeUInt16(firstFeatureStart, 0)
        table.writeUInt16(firstFeatureStart + 2, 1)
        table.writeUInt16(firstFeatureStart + 4, 0)
        table.writeUInt16(secondFeatureStart, 0)
        table.writeUInt16(secondFeatureStart + 2, 1)
        table.writeUInt16(secondFeatureStart + 4, 0)

        table.writeUInt16(lookupListOffset, 1)
        table.writeUInt16(lookupListOffset + 2, 4)

        table.writeUInt16(lookupStart, 1)
        table.writeUInt16(lookupStart + 2, 0)
        table.writeUInt16(lookupStart + 4, 1)
        table.writeUInt16(lookupStart + 6, 8)
        table.writeUInt16(lookupStart + 8, 2)
        table.writeUInt16(lookupStart + 10, 8)
        table.writeUInt16(lookupStart + 12, 1)
        table.writeUInt16(lookupStart + 14, 15)
        table.writeUInt16(lookupStart + 16, 1)
        table.writeUInt16(lookupStart + 18, 1)
        table.writeUInt16(lookupStart + 20, 5)

        return table
    }

    private fun gsubContextLookupWithNestedOnlySingleLookupTable(): ByteArray {
        val table = ByteArray(110)
        val scriptListOffset = 10
        val featureListOffset = 32
        val lookupListOffset = 46
        val scriptStart = scriptListOffset + 8
        val langSysStart = scriptStart + 4
        val featureStart = featureListOffset + 8
        val lookupStart = lookupListOffset + 6
        val singleLookupStart = lookupStart
        val contextLookupStart = singleLookupStart + 22
        val contextSubtableStart = contextLookupStart + 8
        val coverageStart = contextSubtableStart + 8
        val subRuleSetStart = coverageStart + 6
        val subRuleStart = subRuleSetStart + 4

        table.writeUInt16(0, 1)
        table.writeUInt16(2, 0)
        table.writeUInt16(4, scriptListOffset)
        table.writeUInt16(6, featureListOffset)
        table.writeUInt16(8, lookupListOffset)

        table.writeUInt16(scriptListOffset, 1)
        "latn".toByteArray(Charsets.ISO_8859_1).copyInto(table, scriptListOffset + 2)
        table.writeUInt16(scriptListOffset + 6, 8)
        table.writeUInt16(scriptStart, 4)
        table.writeUInt16(scriptStart + 2, 0)
        table.writeUInt16(langSysStart, 0)
        table.writeUInt16(langSysStart + 2, 0xffff)
        table.writeUInt16(langSysStart + 4, 1)
        table.writeUInt16(langSysStart + 6, 0)

        table.writeUInt16(featureListOffset, 1)
        "calt".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 2)
        table.writeUInt16(featureListOffset + 6, 8)
        table.writeUInt16(featureStart, 0)
        table.writeUInt16(featureStart + 2, 1)
        table.writeUInt16(featureStart + 4, 1)

        table.writeUInt16(lookupListOffset, 2)
        table.writeUInt16(lookupListOffset + 2, 6)
        table.writeUInt16(lookupListOffset + 4, 28)

        table.writeUInt16(singleLookupStart, 1)
        table.writeUInt16(singleLookupStart + 2, 0)
        table.writeUInt16(singleLookupStart + 4, 1)
        table.writeUInt16(singleLookupStart + 6, 8)
        table.writeUInt16(singleLookupStart + 8, 2)
        table.writeUInt16(singleLookupStart + 10, 8)
        table.writeUInt16(singleLookupStart + 12, 1)
        table.writeUInt16(singleLookupStart + 14, 15)
        table.writeUInt16(singleLookupStart + 16, 1)
        table.writeUInt16(singleLookupStart + 18, 1)
        table.writeUInt16(singleLookupStart + 20, 5)

        table.writeUInt16(contextLookupStart, 5)
        table.writeUInt16(contextLookupStart + 2, 0)
        table.writeUInt16(contextLookupStart + 4, 1)
        table.writeUInt16(contextLookupStart + 6, 8)

        table.writeUInt16(contextSubtableStart, 1)
        table.writeUInt16(contextSubtableStart + 2, 8)
        table.writeUInt16(contextSubtableStart + 4, 1)
        table.writeUInt16(contextSubtableStart + 6, 14)

        table.writeUInt16(coverageStart, 1)
        table.writeUInt16(coverageStart + 2, 1)
        table.writeUInt16(coverageStart + 4, 5)

        table.writeUInt16(subRuleSetStart, 1)
        table.writeUInt16(subRuleSetStart + 2, 4)

        table.writeUInt16(subRuleStart, 2)
        table.writeUInt16(subRuleStart + 2, 1)
        table.writeUInt16(subRuleStart + 4, 6)
        table.writeUInt16(subRuleStart + 6, 0)
        table.writeUInt16(subRuleStart + 8, 0)

        return table
    }

    private fun gposPairAdjustmentFormat2Class0Table(
        coverageGlyphCount: Int,
        xAdvance: Int,
    ): ByteArray {
        val table = ByteArray(92)
        val scriptListOffset = 10
        val featureListOffset = 30
        val lookupListOffset = 44
        val scriptStart = scriptListOffset + 8
        val langSysStart = scriptStart + 4
        val featureStart = featureListOffset + 8
        val lookupStart = lookupListOffset + 4
        val subtableStart = lookupStart + 8
        val coverageOffset = 18
        val classDef1Offset = 28
        val classDef2Offset = 32

        table.writeUInt16(0, 1)
        table.writeUInt16(2, 0)
        table.writeUInt16(4, scriptListOffset)
        table.writeUInt16(6, featureListOffset)
        table.writeUInt16(8, lookupListOffset)

        table.writeUInt16(scriptListOffset, 1)
        "latn".toByteArray(Charsets.ISO_8859_1).copyInto(table, scriptListOffset + 2)
        table.writeUInt16(scriptListOffset + 6, 8)
        table.writeUInt16(scriptStart, 4)
        table.writeUInt16(scriptStart + 2, 0)
        table.writeUInt16(langSysStart, 0)
        table.writeUInt16(langSysStart + 2, 0xffff)
        table.writeUInt16(langSysStart + 4, 1)
        table.writeUInt16(langSysStart + 6, 0)

        table.writeUInt16(featureListOffset, 1)
        "kern".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 2)
        table.writeUInt16(featureListOffset + 6, 8)
        table.writeUInt16(featureStart, 0)
        table.writeUInt16(featureStart + 2, 1)
        table.writeUInt16(featureStart + 4, 0)

        table.writeUInt16(lookupListOffset, 1)
        table.writeUInt16(lookupListOffset + 2, 4)
        table.writeUInt16(lookupStart, 2)
        table.writeUInt16(lookupStart + 2, 0)
        table.writeUInt16(lookupStart + 4, 1)
        table.writeUInt16(lookupStart + 6, 8)

        table.writeUInt16(subtableStart, 2)
        table.writeUInt16(subtableStart + 2, coverageOffset)
        table.writeUInt16(subtableStart + 4, 0x0004)
        table.writeUInt16(subtableStart + 6, 0)
        table.writeUInt16(subtableStart + 8, classDef1Offset)
        table.writeUInt16(subtableStart + 10, classDef2Offset)
        table.writeUInt16(subtableStart + 12, 1)
        table.writeUInt16(subtableStart + 14, 1)
        table.writeInt16(subtableStart + 16, xAdvance)

        table.writeUInt16(subtableStart + coverageOffset, 2)
        table.writeUInt16(subtableStart + coverageOffset + 2, 1)
        table.writeUInt16(subtableStart + coverageOffset + 4, 0)
        table.writeUInt16(subtableStart + coverageOffset + 6, coverageGlyphCount - 1)
        table.writeUInt16(subtableStart + coverageOffset + 8, 0)

        table.writeUInt16(subtableStart + classDef1Offset, 2)
        table.writeUInt16(subtableStart + classDef1Offset + 2, 0)
        table.writeUInt16(subtableStart + classDef2Offset, 2)
        table.writeUInt16(subtableStart + classDef2Offset + 2, 0)
        return table
    }

    private fun fvarTable(vararg axes: TestFvarAxis): ByteArray {
        val table = ByteArray(16 + axes.size * 20)
        table.writeUInt16(0, 1)
        table.writeUInt16(2, 0)
        table.writeUInt16(4, 16)
        table.writeUInt16(6, 2)
        table.writeUInt16(8, axes.size)
        table.writeUInt16(10, 20)
        table.writeUInt16(12, 0)
        table.writeUInt16(14, 0)

        axes.forEachIndexed { index, axis ->
            val axisOffset = 16 + index * 20
            require(axis.tag.length == 4)
            axis.tag.toByteArray(Charsets.ISO_8859_1).copyInto(table, axisOffset)
            table.writeInt32(axisOffset + 4, fixed16Dot16Raw(axis.minimum))
            table.writeInt32(axisOffset + 8, fixed16Dot16Raw(axis.defaultValue))
            table.writeInt32(axisOffset + 12, fixed16Dot16Raw(axis.maximum))
            table.writeUInt16(axisOffset + 16, axis.flags)
            table.writeUInt16(axisOffset + 18, axis.nameId)
        }

        return table
    }

    private fun avarTable(vararg axisSegmentMaps: List<Pair<Double, Double>>): ByteArray {
        val table = ByteArray(8 + axisSegmentMaps.sumOf { 2 + it.size * 4 })
        table.writeUInt16(0, 1)
        table.writeUInt16(2, 0)
        table.writeUInt16(4, 0)
        table.writeUInt16(6, axisSegmentMaps.size)

        var offset = 8
        for (segments in axisSegmentMaps) {
            table.writeUInt16(offset, segments.size)
            offset += 2
            for ((from, to) in segments) {
                table.writeInt16(offset, f2Dot14Raw(from))
                table.writeInt16(offset + 2, f2Dot14Raw(to))
                offset += 4
            }
        }
        return table
    }

    private fun f2Dot14Raw(value: Double): Int =
        (value * 16384.0).toInt()

    private fun sfntFaceWithColorTables(
        numGlyphs: Int,
        vararg colorTables: Pair<String, ByteArray>,
    ): ByteArray {
        val hmtxMetrics = arrayOf(metric(advanceWidth = 500, leftSideBearing = 0)) +
            Array(numGlyphs - 1) { extraLeftSideBearing(leftSideBearing = 0) }
        val requiredTables = arrayOf(
            "name" to nameTable(),
            "cmap" to cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(
                            startCode = 0x0041,
                            endCode = 0x0041,
                            startGlyphId = 1,
                        ),
                    ),
                ),
            ),
            "head" to headTable(
                unitsPerEm = 1000,
                bounds = OpenTypeFontBounds(xMin = 0, yMin = 0, xMax = 1000, yMax = 1000),
                indexToLocFormat = 0,
            ),
            "hhea" to hheaTable(
                ascender = 800,
                descender = -200,
                lineGap = 0,
                numberOfHMetrics = 1,
            ),
            "maxp" to maxpTable(numGlyphs = numGlyphs),
            "hmtx" to hmtxTable(*hmtxMetrics),
        )
        return sfntFont(*(requiredTables + colorTables))
    }

    private fun svgTable(
        startGlyphId: Int,
        endGlyphId: Int,
        bytes: ByteArray,
    ): ByteArray {
        val documentListOffset = 10
        val documentRecordOffset = documentListOffset + 2
        val svgDocumentOffset = 14
        val table = ByteArray(documentListOffset + svgDocumentOffset + bytes.size)
        table.writeUInt16(0, 0)
        table.writeUInt32(2, documentListOffset)
        table.writeUInt32(6, 0)
        table.writeUInt16(documentListOffset, 1)
        table.writeUInt16(documentRecordOffset, startGlyphId)
        table.writeUInt16(documentRecordOffset + 2, endGlyphId)
        table.writeUInt32(documentRecordOffset + 4, svgDocumentOffset)
        table.writeUInt32(documentRecordOffset + 8, bytes.size)
        bytes.copyInto(table, documentListOffset + svgDocumentOffset)
        return table
    }

    private fun sbixTable(
        numGlyphs: Int,
        glyphId: Int,
        ppem: Int,
        originOffsetX: Int,
        originOffsetY: Int,
        png: ByteArray,
    ): ByteArray {
        val strikeOffset = 12
        val offsetsStart = strikeOffset + 4
        val glyphDataOffset = 4 + (numGlyphs + 1) * 4
        val glyphPayload = ByteArray(8 + png.size)
        glyphPayload.writeInt16(0, originOffsetX)
        glyphPayload.writeInt16(2, originOffsetY)
        "png ".toByteArray(Charsets.ISO_8859_1).copyInto(glyphPayload, 4)
        png.copyInto(glyphPayload, 8)

        val table = ByteArray(strikeOffset + glyphDataOffset + glyphPayload.size)
        table.writeUInt16(0, 1)
        table.writeUInt16(2, 0)
        table.writeUInt32(4, 1)
        table.writeUInt32(8, strikeOffset)
        table.writeUInt16(strikeOffset, ppem)
        table.writeUInt16(strikeOffset + 2, 72)
        repeat(numGlyphs + 1) { index ->
            val offset = when {
                index <= glyphId -> glyphDataOffset
                else -> glyphDataOffset + glyphPayload.size
            }
            table.writeUInt32(offsetsStart + index * 4, offset)
        }
        glyphPayload.copyInto(table, strikeOffset + glyphDataOffset)
        return table
    }

    private fun cbdtTable(payload: ByteArray): ByteArray {
        val table = ByteArray(4 + payload.size)
        table.writeUInt16(0, 3)
        table.writeUInt16(2, 0)
        payload.copyInto(table, 4)
        return table
    }

    private fun cbdtFormat17Payload(png: ByteArray): ByteArray {
        val payload = ByteArray(5 + png.size)
        payload[0] = 1
        payload[1] = 1
        payload[2] = 0
        payload[3] = 0
        payload[4] = 1
        png.copyInto(payload, 5)
        return payload
    }

    private fun cblcTableIndexFormat1(
        glyphId: Int,
        payloadLength: Int,
        ppemX: Int,
        ppemY: Int,
        bitDepth: Int,
    ): ByteArray {
        val sizeTableOffset = 8
        val indexArrayOffset = 56
        val subtableOffsetFromArray = 8
        val subtableOffset = indexArrayOffset + subtableOffsetFromArray
        val table = ByteArray(subtableOffset + 16)
        table.writeUInt16(0, 3)
        table.writeUInt16(2, 0)
        table.writeUInt32(4, 1)

        table.writeUInt32(sizeTableOffset, indexArrayOffset)
        table.writeUInt32(sizeTableOffset + 4, 24)
        table.writeUInt32(sizeTableOffset + 8, 1)
        table.writeUInt32(sizeTableOffset + 12, 0)
        table.writeUInt16(sizeTableOffset + 40, glyphId)
        table.writeUInt16(sizeTableOffset + 42, glyphId)
        table[sizeTableOffset + 44] = ppemX.toByte()
        table[sizeTableOffset + 45] = ppemY.toByte()
        table[sizeTableOffset + 46] = bitDepth.toByte()
        table[sizeTableOffset + 47] = 1

        table.writeUInt16(indexArrayOffset, glyphId)
        table.writeUInt16(indexArrayOffset + 2, glyphId)
        table.writeUInt32(indexArrayOffset + 4, subtableOffsetFromArray)

        table.writeUInt16(subtableOffset, 1)
        table.writeUInt16(subtableOffset + 2, 17)
        table.writeUInt32(subtableOffset + 4, 4)
        table.writeUInt32(subtableOffset + 8, 0)
        table.writeUInt32(subtableOffset + 12, payloadLength)
        return table
    }

    private fun pngPayload(vararg trailingBytes: Int): ByteArray =
        byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
            *trailingBytes.map { it.toByte() }.toByteArray(),
        )

    private fun sfntFont(vararg tables: Pair<String, ByteArray>): ByteArray {
        val directoryLength = 12 + tables.size * 16
        val totalLength = directoryLength + tables.sumOf { (_, payload) -> payload.size }
        val font = ByteArray(totalLength)
        font.writeUInt32(0, 0x00010000)
        font.writeUInt16(4, tables.size)

        var payloadOffset = directoryLength
        tables.forEachIndexed { index, (tag, payload) ->
            require(tag.length == 4)
            val recordOffset = 12 + index * 16
            tag.toByteArray(Charsets.ISO_8859_1).copyInto(font, recordOffset)
            font.writeUInt32(recordOffset + 4, 0)
            font.writeUInt32(recordOffset + 8, payloadOffset)
            font.writeUInt32(recordOffset + 12, payload.size)
            payload.copyInto(font, payloadOffset)
            payloadOffset += payload.size
        }

        return font
    }

    private fun ttcFont(vararg faces: ByteArray): ByteArray {
        val headerLength = 12 + faces.size * 4
        val totalLength = headerLength + faces.sumOf(ByteArray::size)
        val collection = ByteArray(totalLength)
        collection.writeUInt32(0, 0x74746366)
        collection.writeUInt32(4, 0x00010000)
        collection.writeUInt32(8, faces.size)

        var cursor = headerLength
        faces.forEachIndexed { index, face ->
            collection.writeUInt32(12 + index * 4, cursor)
            val routedFace = face.copyOf()
            val tableCount = routedFace.readUInt16(4)
            repeat(tableCount) { tableIndex ->
                val recordOffset = 12 + tableIndex * 16
                routedFace.writeUInt32(recordOffset + 8, cursor + routedFace.readUInt32(recordOffset + 8))
            }
            routedFace.copyInto(collection, cursor)
            cursor += routedFace.size
        }

        return collection
    }

    private fun metric(
        advanceWidth: Int,
        leftSideBearing: Int,
    ): TestHorizontalMetric = TestHorizontalMetric(
        advanceWidth = advanceWidth,
        leftSideBearing = leftSideBearing,
    )

    private fun verticalMetric(
        advanceHeight: Int,
        topSideBearing: Int,
    ): TestVerticalMetric = TestVerticalMetric(
        advanceHeight = advanceHeight,
        topSideBearing = topSideBearing,
    )

    private fun extraLeftSideBearing(leftSideBearing: Int): TestHorizontalMetric =
        TestHorizontalMetric(
            advanceWidth = null,
            leftSideBearing = leftSideBearing,
        )

    private fun extraTopSideBearing(topSideBearing: Int): TestVerticalMetric =
        TestVerticalMetric(
            advanceHeight = null,
            topSideBearing = topSideBearing,
        )

    private fun testKernPair(
        leftGlyphId: Int,
        rightGlyphId: Int,
        value: Int,
    ): TestKernPair = TestKernPair(
        leftGlyphId = leftGlyphId,
        rightGlyphId = rightGlyphId,
        value = value,
    )

    private fun ByteArray.writeUInt16(offset: Int, value: Int) {
        this[offset] = ((value ushr 8) and 0xff).toByte()
        this[offset + 1] = (value and 0xff).toByte()
    }

    private fun ByteArray.writeUInt24(offset: Int, value: Int) {
        this[offset] = ((value ushr 16) and 0xff).toByte()
        this[offset + 1] = ((value ushr 8) and 0xff).toByte()
        this[offset + 2] = (value and 0xff).toByte()
    }

    private fun ByteArray.readUInt16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or
            (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.writeInt16(offset: Int, value: Int) {
        writeUInt16(offset, value and 0xffff)
    }

    private fun ByteArray.writeUInt32(offset: Int, value: Int) {
        this[offset] = ((value ushr 24) and 0xff).toByte()
        this[offset + 1] = ((value ushr 16) and 0xff).toByte()
        this[offset + 2] = ((value ushr 8) and 0xff).toByte()
        this[offset + 3] = (value and 0xff).toByte()
    }

    private fun ByteArray.writeInt32(offset: Int, value: Int) {
        writeUInt32(offset, value)
    }

    private fun ByteArray.readUInt32(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun fixed16Dot16Raw(value: Double): Int =
        (value * 65536.0).toInt()

    private fun ByteArray.toUnsignedByteList(): List<Int> =
        map { it.toInt() and 0xff }

    private fun String.jsonEscapedForTest(): String =
        flatMap { character ->
            when (character) {
                '\\' -> listOf('\\', '\\')
                '"' -> listOf('\\', '"')
                '\n' -> listOf('\\', 'n')
                '\r' -> listOf('\\', 'r')
                '\t' -> listOf('\\', 't')
                else -> listOf(character)
            }
        }.joinToString(separator = "")

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
