package io.ygdrasil.kalligraphie.font.sfnt

import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import io.ygdrasil.kalligraphie.font.FontSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SFNTParserEntryPointTest {
    @Test
    fun sfntParserUsesOneBoundedRequestForSingleSfntAndTtcDirectoryReports() {
        val report = sfntDirectoryReport()
        val actual = SFNTDirectoryReportWriter.write(report)
        val expectedPath = projectRoot().resolve("font/fixtures/expected/pure-kotlin-text/sfnt-directory.json")

        if (!Files.exists(expectedPath)) {
            error("Missing sfnt-directory.json golden. Actual report:\n$actual")
        }

        assertEquals(Files.readString(expectedPath), actual)
        assertEquals(actual, SFNTDirectoryReportWriter.write(sfntDirectoryReport()))
        assertTrue(actual.contains("\"ticketIds\": ["))
        assertTrue(actual.contains("\"KFONT-M2-001\""))
        assertTrue(actual.contains("\"KFONT-M2-002\""))
        assertTrue(actual.contains("\"KFONT-M2-005\""))
        assertTrue(actual.contains("\"containerKind\": \"SINGLE_FACE\""))
        assertTrue(actual.contains("\"containerKind\": \"TTC_COLLECTION\""))
        assertTrue(actual.contains("\"code\": \"font.collection-index-invalid\""))
        assertTrue(actual.contains("\"entryId\": \"generated-directory-diagnostics\""))
        assertTrue(actual.contains("\"code\": \"font.sfnt.required-table-missing\""))
        assertTrue(actual.contains("\"code\": \"font.sfnt.table-out-of-bounds\""))
        assertTrue(actual.contains("\"code\": \"font.sfnt.table-duplicate\""))
        assertTrue(actual.contains("\"code\": \"font.sfnt.table-overlap\""))
        assertTrue(actual.contains("\"entryId\": \"generated-optional-table-malformed\""))
        assertTrue(actual.contains("\"sourceSha256\": \"${optionalMalformedFaceFixture().sha256HexForTest()}\""))
        assertTrue(actual.contains("\"faceDiagnostics\": ["))
        assertTrue(actual.contains("\"table\": \"fvar\""))
        assertTrue(actual.contains("\"causeCode\": \"font.sfnt.optional-table-malformed\""))
        assertTrue(actual.contains("\"dashboardClassification\": \"tracked-gap\""))
        assertTrue(actual.contains("\"claimPromotionAllowed\": false"))
        listOf("GPU", "Skia", "HarfBuzz", "FreeType", "Fontations", "CoreText", "DirectWrite").forEach { token ->
            assertFalse(actual.contains(token), "sfnt-directory.json must not contain hidden engine token $token")
        }
    }

    @Test
    fun invalidCollectionIndexReturnsStableDiagnosticWithoutParsingAnotherFace() {
        val ttc = generatedTtcFixture()
        val parser = DefaultSFNTParser()

        val result = parser.parse(
            SFNTParseRequest(
                sourceId = GENERATED_TTC_SOURCE_ID,
                sourceKind = FontSourceKind.MEMORY,
                displayName = "generated-ttc-face-index",
                bytes = BoundedFontBytes(rawBytes = ttc),
                collectionIndex = 3,
                parserGeneration = 1,
            ),
        )

        assertEquals(SFNTContainerKind.TTC_COLLECTION, result.containerKind)
        assertEquals(3, result.requestedCollectionIndex)
        assertEquals(2, result.faceCount)
        assertNull(result.selectedFaceIndex)
        assertNull(result.faceFacts)
        assertEquals(emptyList(), result.tableSlices)
        assertEquals(listOf("font.collection-index-invalid"), result.diagnostics.map { it.code })
    }

    @Test
    fun singleFaceNonZeroIndexAndUnknownWrappersReturnStableDiagnostics() {
        val parser = DefaultSFNTParser()

        val singleIndex = parser.parse(
            SFNTParseRequest(
                sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655449101")),
                sourceKind = FontSourceKind.MEMORY,
                displayName = "single-non-zero-index",
                bytes = BoundedFontBytes(rawBytes = sfntFont()),
                collectionIndex = 1,
                parserGeneration = 1,
            ),
        )
        assertEquals(SFNTContainerKind.SINGLE_FACE, singleIndex.containerKind)
        assertNull(singleIndex.selectedFaceIndex)
        assertEquals(listOf("font.collection-index-invalid"), singleIndex.diagnostics.map { it.code })

        val woff = byteArrayOf('w'.code.toByte(), 'O'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0)
        val wrapper = parser.parse(
            SFNTParseRequest(
                sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655449102")),
                sourceKind = FontSourceKind.MEMORY,
                displayName = "unsupported-wrapper",
                bytes = BoundedFontBytes(rawBytes = woff),
                collectionIndex = 0,
                parserGeneration = 1,
            ),
        )
        assertEquals(SFNTContainerKind.UNKNOWN_WRAPPER, wrapper.containerKind)
        assertNull(wrapper.selectedFaceIndex)
        assertEquals(listOf("font.sfnt.wrapper-unsupported"), wrapper.diagnostics.map { it.code })
    }

    @Test
    fun boundedFontBytesRejectOutOfRangeSlicesAndParsesOnlyRequestedWindow() {
        assertFailsWith<IllegalArgumentException> {
            BoundedFontBytes(rawBytes = ByteArray(4), byteOffset = 2, byteLength = 3)
        }

        val prefix = byteArrayOf(0x7f, 0x7e, 0x7d)
        val font = minimalFace("Bounded Slice", glyphId = 5)
        val combined = prefix + font + byteArrayOf(0x55, 0x66)
        val result = DefaultSFNTParser().parse(
            SFNTParseRequest(
                sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655449103")),
                sourceKind = FontSourceKind.MEMORY,
                displayName = "bounded-window",
                bytes = BoundedFontBytes(rawBytes = combined, byteOffset = prefix.size, byteLength = font.size),
                collectionIndex = null,
                parserGeneration = 1,
            ),
        )

        assertEquals(SFNTContainerKind.SINGLE_FACE, result.containerKind)
        assertEquals(0, result.selectedFaceIndex)
        assertEquals(font.size, result.sourceByteLength)
        assertNull(result.faceFacts)
        assertEquals(listOf("cmap", "head", "hhea", "hmtx", "maxp", "name"), result.tableSlices.map { it.tag })
    }

    @Test
    fun requiredTablesFlowIntoSfntParserDirectoryDiagnostics() {
        val result = DefaultSFNTParser().parse(
            SFNTParseRequest(
                sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655449104")),
                sourceKind = FontSourceKind.MEMORY,
                displayName = "missing-outline-required-tables",
                bytes = BoundedFontBytes(rawBytes = minimalFace("Missing Outline Tables", glyphId = 13)),
                collectionIndex = 0,
                parserGeneration = 1,
                requiredTables = setOf(SFNTTableTag("cmap"), SFNTTableTag("glyf"), SFNTTableTag("loca")),
            ),
        )

        assertEquals(
            listOf(
                "font.sfnt.required-table-missing tag=\"glyf\" offset=none length=none sourceLength=314 message=\"Required table is not present.\"",
                "font.sfnt.required-table-missing tag=\"loca\" offset=none length=none sourceLength=314 message=\"Required table is not present.\"",
            ),
            result.directoryFacts?.directoryDiagnostics?.map { it.dump() },
        )
    }

    private fun sfntDirectoryReport(): SFNTDirectoryReport {
        val parser = DefaultSFNTParser()
        val liberationPath = projectRoot().resolve("font/fixtures/fonts/liberation/LiberationSans-Regular.ttf")
        val ttc = generatedTtcFixture()
        val single = parser.parse(
            SFNTParseRequest(
                sourceId = LIBERATION_SOURCE_ID,
                sourceKind = FontSourceKind.FILE,
                displayName = "LiberationSans-Regular.ttf",
                bytes = BoundedFontBytes(rawBytes = Files.readAllBytes(liberationPath)),
                collectionIndex = 0,
                parserGeneration = 1,
            ),
        )
        val ttcFace = parser.parse(
            SFNTParseRequest(
                sourceId = GENERATED_TTC_SOURCE_ID,
                sourceKind = FontSourceKind.MEMORY,
                displayName = "generated-ttc-face-index",
                bytes = BoundedFontBytes(rawBytes = ttc),
                collectionIndex = 1,
                parserGeneration = 1,
            ),
        )
        val invalidIndex = parser.parse(
            SFNTParseRequest(
                sourceId = GENERATED_TTC_SOURCE_ID,
                sourceKind = FontSourceKind.MEMORY,
                displayName = "generated-ttc-face-index-invalid",
                bytes = BoundedFontBytes(rawBytes = ttc),
                collectionIndex = 3,
                parserGeneration = 1,
            ),
        )
        val directoryDiagnostics = parser.parse(
            SFNTParseRequest(
                sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655449105")),
                sourceKind = FontSourceKind.MEMORY,
                displayName = "generated-directory-diagnostics",
                bytes = BoundedFontBytes(rawBytes = directoryDiagnosticsFixture()),
                collectionIndex = 0,
                parserGeneration = 1,
                requiredTables = setOf(SFNTTableTag("cmap"), SFNTTableTag("glyf"), SFNTTableTag("head")),
            ),
        )
        val optionalMalformedBytes = optionalMalformedFaceFixture()
        val optionalMalformed = DefaultOpenTypeFaceParser().parse(
            FontSource(
                id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655449106")),
                kind = FontSourceKind.MEMORY,
                displayName = "generated-optional-table-malformed",
                bytes = optionalMalformedBytes,
            ),
        )
        val badVersionBytes = badSfntVersionFixture()
        val truncatedHeaderBytes = truncatedHeaderFixture()
        val invalidTtcBytes = ttcFont(malformedMinimalFace("TTC Fixture", glyphId = 5))
        val outOfBoundsBytes = tableOutOfBoundsFixture()
        val overlapBytes = overlappingTablesFixture()
        val duplicateBytes = duplicateTagFixture()
        val missingRequiredBytes = malformedMinimalFace("Missing Required", glyphId = 7)

        return SFNTDirectoryReport(
            entries = listOf(
                malformedDirectoryEntry(
                    entryId = "generated-bad-sfnt-version",
                    fixtureId = "malformed-sfnt-bad-version-generated",
                    sourceId = "550e8400-e29b-41d4-a716-44665544a001",
                    displayName = "generated-bad-sfnt-version",
                    bytes = badVersionBytes,
                ),
                malformedDirectoryEntry(
                    entryId = "generated-truncated-sfnt-header",
                    fixtureId = "malformed-sfnt-truncated-header-generated",
                    sourceId = "550e8400-e29b-41d4-a716-44665544a002",
                    displayName = "generated-truncated-sfnt-header",
                    bytes = truncatedHeaderBytes,
                ),
                malformedDirectoryEntry(
                    entryId = "generated-malformed-ttc-invalid-index",
                    fixtureId = "malformed-sfnt-invalid-ttc-index-generated",
                    sourceId = "550e8400-e29b-41d4-a716-44665544a003",
                    displayName = "generated-malformed-ttc-invalid-index",
                    bytes = invalidTtcBytes,
                    collectionIndex = 3,
                ),
                malformedDirectoryEntry(
                    entryId = "generated-table-out-of-bounds",
                    fixtureId = "malformed-sfnt-table-out-of-bounds-generated",
                    sourceId = "550e8400-e29b-41d4-a716-44665544a004",
                    displayName = "generated-table-out-of-bounds",
                    bytes = outOfBoundsBytes,
                ),
                malformedDirectoryEntry(
                    entryId = "generated-overlapping-tables",
                    fixtureId = "malformed-sfnt-overlapping-tables-generated",
                    sourceId = "550e8400-e29b-41d4-a716-44665544a005",
                    displayName = "generated-overlapping-tables",
                    bytes = overlapBytes,
                ),
                malformedDirectoryEntry(
                    entryId = "generated-duplicate-tag",
                    fixtureId = "malformed-sfnt-duplicate-tag-generated",
                    sourceId = "550e8400-e29b-41d4-a716-44665544a006",
                    displayName = "generated-duplicate-tag",
                    bytes = duplicateBytes,
                ),
                malformedDirectoryEntry(
                    entryId = "generated-missing-required-table",
                    fixtureId = "malformed-sfnt-missing-required-table-generated",
                    sourceId = "550e8400-e29b-41d4-a716-44665544a007",
                    displayName = "generated-missing-required-table",
                    bytes = missingRequiredBytes,
                    requiredTables = setOf(SFNTTableTag("glyf"), SFNTTableTag("loca")),
                ),
                SFNTDirectoryReportEntry.fromResult(
                    entryId = "single-ttf-liberation-sans",
                    fixtureId = "single-ttf-liberation-sans",
                    fixtureKind = "BundledFontSource",
                    result = single,
                ),
                SFNTDirectoryReportEntry.fromResult(
                    entryId = "generated-ttc-face-index-1",
                    fixtureId = "ttc-face-index-planned-generated",
                    fixtureKind = "GeneratedFixtureFontSource",
                    result = ttcFace,
                ),
                SFNTDirectoryReportEntry.fromResult(
                    entryId = "generated-ttc-invalid-index",
                    fixtureId = "ttc-face-index-planned-generated",
                    fixtureKind = "GeneratedFixtureFontSource",
                    result = invalidIndex,
                ),
                SFNTDirectoryReportEntry.fromResult(
                    entryId = "generated-directory-diagnostics",
                    fixtureId = "sfnt-directory-diagnostics-generated",
                    fixtureKind = "GeneratedFixtureFontSource",
                    result = directoryDiagnostics,
                ),
                SFNTDirectoryReportEntry.fromFaceData(
                    entryId = "generated-optional-table-malformed",
                    fixtureId = "font-source-sfnt-malformed-optional-table-diagnostic",
                    fixtureKind = "GeneratedFixtureFontSource",
                    face = optionalMalformed,
                ),
            ),
        )
    }

    private fun malformedDirectoryEntry(
        entryId: String,
        fixtureId: String,
        sourceId: String,
        displayName: String,
        bytes: ByteArray,
        collectionIndex: Int = 0,
        requiredTables: Set<SFNTTableTag> = emptySet(),
    ): SFNTDirectoryReportEntry =
        SFNTDirectoryReportEntry.fromResult(
            entryId = entryId,
            fixtureId = fixtureId,
            fixtureKind = "GeneratedFixtureFontSource",
            result = DefaultSFNTParser().parse(
                SFNTParseRequest(
                    sourceId = FontSourceID(Uuid.parse(sourceId)),
                    sourceKind = FontSourceKind.MEMORY,
                    displayName = displayName,
                    bytes = BoundedFontBytes(rawBytes = bytes),
                    collectionIndex = collectionIndex,
                    parserGeneration = 1,
                    requiredTables = requiredTables,
                ),
            ),
            sourceSha256 = bytes.sha256HexForTest(),
        )

    private fun generatedTtcFixture(): ByteArray =
        ttcFont(
            minimalFace("Generated TTC One", glyphId = 7, unitsPerEm = 1000, advanceWidth = 500),
            minimalFace("Generated TTC Two", glyphId = 11, unitsPerEm = 1200, advanceWidth = 610),
        )

    private fun directoryDiagnosticsFixture(): ByteArray {
        val font = ByteArray(96)
        font.writeUInt32(0, 0x00010000)
        font.writeUInt16(4, 4)
        writeDirectoryRecord(font, index = 0, tag = "name", checksum = 0, offset = 80, length = 8)
        writeDirectoryRecord(font, index = 1, tag = "name", checksum = 1, offset = 88, length = 4)
        writeDirectoryRecord(font, index = 2, tag = "cmap", checksum = 2, offset = 90, length = 6)
        writeDirectoryRecord(font, index = 3, tag = "post", checksum = 3, offset = 92, length = 12)
        return font
    }

    private fun badSfntVersionFixture(): ByteArray =
        ByteArray(12).also { bytes ->
            bytes.writeUInt32(0, 0x00020000)
        }

    private fun truncatedHeaderFixture(): ByteArray =
        ByteArray(10).also { bytes ->
            bytes.writeUInt32(0, 0x00010000)
            bytes.writeUInt16(4, 1)
        }

    private fun tableOutOfBoundsFixture(): ByteArray =
        directoryOnlyFont(sourceLength = 80, "post" to TestTableRecord(offset = 72, length = 16))

    private fun overlappingTablesFixture(): ByteArray =
        directoryOnlyFont(
            sourceLength = 96,
            "name" to TestTableRecord(offset = 48, length = 16),
            "cmap" to TestTableRecord(offset = 56, length = 12),
        )

    private fun duplicateTagFixture(): ByteArray =
        directoryOnlyFont(
            sourceLength = 96,
            "name" to TestTableRecord(offset = 48, length = 4),
            "name" to TestTableRecord(offset = 56, length = 4),
        )

    private fun directoryOnlyFont(
        sourceLength: Int,
        vararg records: Pair<String, TestTableRecord>,
    ): ByteArray {
        val font = ByteArray(sourceLength)
        font.writeUInt32(0, 0x00010000)
        font.writeUInt16(4, records.size)
        records.forEachIndexed { index, (tag, record) ->
            writeDirectoryRecord(font, index = index, tag = tag, checksum = index, offset = record.offset, length = record.length)
        }
        return font
    }

    private fun writeDirectoryRecord(
        font: ByteArray,
        index: Int,
        tag: String,
        checksum: Int,
        offset: Int,
        length: Int,
    ) {
        val recordOffset = 12 + index * 16
        tag.toByteArray(Charsets.ISO_8859_1).copyInto(font, recordOffset)
        font.writeUInt32(recordOffset + 4, checksum)
        font.writeUInt32(recordOffset + 8, offset)
        font.writeUInt32(recordOffset + 12, length)
    }

    private fun minimalFace(
        family: String,
        glyphId: Int,
        unitsPerEm: Int = 1000,
        advanceWidth: Int = 500,
    ): ByteArray =
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
                    subtable = format4Subtable(testFormat4Segment(startCode = 0x0041, endCode = 0x0041, startGlyphId = glyphId)),
                ),
            ),
            "head" to headTable(
                unitsPerEm = unitsPerEm,
                bounds = OpenTypeFontBounds(xMin = 0, yMin = -200, xMax = 1000, yMax = 820),
                indexToLocFormat = 0,
            ),
            "hhea" to hheaTable(ascender = 820, descender = -180, lineGap = 40, numberOfHMetrics = 1),
            "maxp" to maxpTable(numGlyphs = 1),
            "hmtx" to hmtxTable(advanceWidth = advanceWidth, leftSideBearing = 0),
        )

    private fun malformedMinimalFace(family: String, glyphId: Int): ByteArray =
        sfntFontWithChecksums(
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
                    subtable = format4Subtable(testFormat4Segment(startCode = 0x0041, endCode = 0x0041, startGlyphId = glyphId)),
                ),
            ),
            "head" to headTable(
                unitsPerEm = 1000,
                bounds = OpenTypeFontBounds(xMin = 0, yMin = -200, xMax = 1000, yMax = 820),
                indexToLocFormat = 0,
            ),
            "hhea" to hheaTable(ascender = 820, descender = -180, lineGap = 40, numberOfHMetrics = 1),
            "maxp" to maxpTable(numGlyphs = 1),
            "hmtx" to hmtxTable(advanceWidth = 500, leftSideBearing = 0),
        )

    private fun optionalMalformedFaceFixture(): ByteArray =
        sfntFont(
            "name" to nameTable(
                testNameRecord(
                    platformId = 3,
                    encodingId = 1,
                    languageId = 0x0409,
                    nameId = 1,
                    bytes = "Optional Malformed".toByteArray(Charsets.UTF_16BE),
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
                            startGlyphId = 17,
                        ),
                    ),
                ),
            ),
            "head" to headTable(
                unitsPerEm = 1000,
                bounds = OpenTypeFontBounds(xMin = 0, yMin = -200, xMax = 1000, yMax = 820),
                indexToLocFormat = 0,
            ),
            "hhea" to hheaTable(ascender = 820, descender = -180, lineGap = 40, numberOfHMetrics = 1),
            "maxp" to maxpTable(numGlyphs = 1),
            "hmtx" to hmtxTable(advanceWidth = 500, leftSideBearing = 0),
            "fvar" to ByteArray(8),
        )

    private fun ByteArray.sha256HexForTest(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

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
    private data class TestTableRecord(val offset: Int, val length: Int)

    private fun testNameRecord(
        platformId: Int,
        encodingId: Int,
        languageId: Int,
        nameId: Int,
        bytes: ByteArray,
    ): TestNameRecord = TestNameRecord(platformId, encodingId, languageId, nameId, bytes)

    private fun testCMapRecord(
        platformId: Int,
        encodingId: Int,
        subtable: ByteArray,
    ): TestCMapRecord = TestCMapRecord(platformId, encodingId, subtable)

    private fun testFormat4Segment(
        startCode: Int,
        endCode: Int,
        startGlyphId: Int,
    ): TestFormat4Segment = TestFormat4Segment(
        startCode = startCode,
        endCode = endCode,
        idDelta = (startGlyphId - startCode) and 0xffff,
    )

    private fun nameTable(vararg records: TestNameRecord): ByteArray {
        val stringOffset = 6 + records.size * 12
        val table = ByteArray(stringOffset + records.sumOf { it.bytes.size })
        table.writeUInt16(0, 0)
        table.writeUInt16(2, records.size)
        table.writeUInt16(4, stringOffset)

        var cursor = 0
        records.forEachIndexed { index, record ->
            val recordOffset = 6 + index * 12
            table.writeUInt16(recordOffset, record.platformId)
            table.writeUInt16(recordOffset + 2, record.encodingId)
            table.writeUInt16(recordOffset + 4, record.languageId)
            table.writeUInt16(recordOffset + 6, record.nameId)
            table.writeUInt16(recordOffset + 8, record.bytes.size)
            table.writeUInt16(recordOffset + 10, cursor)
            record.bytes.copyInto(table, stringOffset + cursor)
            cursor += record.bytes.size
        }
        return table
    }

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
        val allSegments = segments.toList() + TestFormat4Segment(startCode = 0xffff, endCode = 0xffff, idDelta = 1)
        val segCount = allSegments.size
        val length = 16 + segCount * 8
        val table = ByteArray(length)
        table.writeUInt16(0, 4)
        table.writeUInt16(2, length)
        table.writeUInt16(6, segCount * 2)
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

    private fun headTable(
        unitsPerEm: Int,
        bounds: OpenTypeFontBounds,
        indexToLocFormat: Int,
    ): ByteArray {
        val table = ByteArray(54)
        table.writeUInt16(18, unitsPerEm)
        table.writeInt16(36, bounds.xMin)
        table.writeInt16(38, bounds.yMin)
        table.writeInt16(40, bounds.xMax)
        table.writeInt16(42, bounds.yMax)
        table.writeInt16(50, indexToLocFormat)
        return table
    }

    private fun hheaTable(
        ascender: Int,
        descender: Int,
        lineGap: Int,
        numberOfHMetrics: Int,
    ): ByteArray {
        val table = ByteArray(36)
        table.writeInt16(4, ascender)
        table.writeInt16(6, descender)
        table.writeInt16(8, lineGap)
        table.writeUInt16(34, numberOfHMetrics)
        return table
    }

    private fun maxpTable(numGlyphs: Int): ByteArray {
        val table = ByteArray(6)
        table.writeUInt16(4, numGlyphs)
        return table
    }

    private fun hmtxTable(
        advanceWidth: Int,
        leftSideBearing: Int,
    ): ByteArray {
        val table = ByteArray(4)
        table.writeUInt16(0, advanceWidth)
        table.writeInt16(2, leftSideBearing)
        return table
    }

    private fun sfntFont(vararg tables: Pair<String, ByteArray>): ByteArray {
        val directoryLength = 12 + tables.size * 16
        val font = ByteArray(directoryLength + tables.sumOf { (_, payload) -> payload.size })
        font.writeUInt32(0, 0x00010000)
        font.writeUInt16(4, tables.size)

        var payloadOffset = directoryLength
        tables.forEachIndexed { index, (tag, payload) ->
            val recordOffset = 12 + index * 16
            tag.toByteArray(Charsets.ISO_8859_1).copyInto(font, recordOffset)
            font.writeUInt32(recordOffset + 8, payloadOffset)
            font.writeUInt32(recordOffset + 12, payload.size)
            payload.copyInto(font, payloadOffset)
            payloadOffset += payload.size
        }
        return font
    }

    private fun sfntFontWithChecksums(vararg tables: Pair<String, ByteArray>): ByteArray {
        val directoryLength = 12 + tables.size * 16
        val font = ByteArray(directoryLength + tables.sumOf { (_, payload) -> payload.size })
        font.writeUInt32(0, 0x00010000)
        font.writeUInt16(4, tables.size)

        var payloadOffset = directoryLength
        tables.forEachIndexed { index, (tag, payload) ->
            val recordOffset = 12 + index * 16
            tag.toByteArray(Charsets.ISO_8859_1).copyInto(font, recordOffset)
            font.writeUInt32(recordOffset + 4, payload.checksumForTest())
            font.writeUInt32(recordOffset + 8, payloadOffset)
            font.writeUInt32(recordOffset + 12, payload.size)
            payload.copyInto(font, payloadOffset)
            payloadOffset += payload.size
        }
        return font
    }

    private fun ByteArray.checksumForTest(): Int {
        var sum = 0L
        var offset = 0
        while (offset < size) {
            var word = 0
            repeat(4) { index ->
                word = word shl 8
                if (offset + index < size) {
                    word = word or (this[offset + index].toInt() and 0xff)
                }
            }
            sum = (sum + (word.toLong() and 0xffffffffL)) and 0xffffffffL
            offset += 4
        }
        return sum.toInt()
    }

    private fun ttcFont(vararg faces: ByteArray): ByteArray {
        val headerLength = 12 + faces.size * 4
        val collection = ByteArray(headerLength + faces.sumOf(ByteArray::size))
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

    private fun ByteArray.writeUInt16(offset: Int, value: Int) {
        this[offset] = ((value ushr 8) and 0xff).toByte()
        this[offset + 1] = (value and 0xff).toByte()
    }

    private fun ByteArray.writeInt16(offset: Int, value: Int) {
        writeUInt16(offset, value and 0xffff)
    }

    private fun ByteArray.writeUInt32(offset: Int, value: Int) {
        this[offset] = ((value ushr 24) and 0xff).toByte()
        this[offset + 1] = ((value ushr 16) and 0xff).toByte()
        this[offset + 2] = ((value ushr 8) and 0xff).toByte()
        this[offset + 3] = (value and 0xff).toByte()
    }

    private fun ByteArray.readUInt16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.readUInt32(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private companion object {
        private val LIBERATION_SOURCE_ID = FontSourceID(Uuid.parse("831c03a5-238d-5b9c-93a5-90c6414ab1cd"))
        private val GENERATED_TTC_SOURCE_ID = FontSourceID(Uuid.parse("e7fcb0f9-4a4d-5f0c-b7e8-5704535b80c8"))
    }
}
