package io.ygdrasil.kalligraphie.font.sfnt

import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import io.ygdrasil.kalligraphie.font.defaultTypefaceIdentityReport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SFNTTableFactDumpTest {
    @Test
    fun openTypeTableFactDumpMatchesGoldenAndCoversKfontM2004Evidence() {
        val report = sfntTableFactReport()
        val actual = OpenTypeTableFactReportWriter.write(report)
        val expectedPath = projectRoot().resolve("font/fixtures/expected/pure-kotlin-text/sfnt-tables.json")

        if (!Files.exists(expectedPath)) {
            error("Missing sfnt-tables.json golden. Actual report:\n$actual")
        }

        assertEquals(Files.readString(expectedPath), actual)
        assertEquals(actual, OpenTypeTableFactReportWriter.write(sfntTableFactReport()))
        assertTrue(actual.contains("\"ticketIds\": [\"KFONT-M2-004\"]"))
        assertTrue(actual.contains("\"fontSourceReportLabel\": \"bundled-fixture\""))
        assertTrue(actual.contains("\"typefaceReportLabel\": \"single-face-ttf\""))
        assertTrue(actual.contains("\"sourceId\": \"432a2cf5-370b-52f0-9dbe-74899c8c606d\""))
        assertTrue(actual.contains("\"typefaceId\": \"c98295d3-4a18-5109-a9e9-135806c2311a\""))
        assertTrue(actual.contains("\"entryId\": \"single-ttf-liberation-sans\""))
        assertTrue(actual.contains("\"tag\": \"cmap\""))
        assertTrue(actual.contains("\"tag\": \"head\""))
        assertTrue(actual.contains("\"tag\": \"hhea\""))
        assertTrue(actual.contains("\"tag\": \"hmtx\""))
        assertTrue(actual.contains("\"tag\": \"maxp\""))
        assertTrue(actual.contains("\"tag\": \"name\""))
        assertTrue(actual.contains("\"tag\": \"OS/2\""))
        assertTrue(actual.contains("\"tag\": \"post\""))
        assertTrue(actual.contains("\"tag\": \"loca\""))
        assertTrue(actual.contains("\"tag\": \"glyf\""))
        assertTrue(actual.contains("\"tag\": \"CFF \""))
        assertTrue(actual.contains("\"tag\": \"CFF2\""))
        assertTrue(actual.contains("\"tag\": \"vhea\""))
        assertTrue(actual.contains("\"tag\": \"vmtx\""))
        assertTrue(actual.contains("\"tag\": \"GDEF\""))
        assertTrue(actual.contains("\"tag\": \"GSUB\""))
        assertTrue(actual.contains("\"tag\": \"GPOS\""))
        assertTrue(actual.contains("\"tag\": \"BASE\""))
        assertTrue(actual.contains("\"tag\": \"kern\""))
        assertTrue(actual.contains("\"tag\": \"fvar\""))
        assertTrue(actual.contains("\"tag\": \"avar\""))
        assertTrue(actual.contains("\"tag\": \"gvar\""))
        assertTrue(actual.contains("\"tag\": \"HVAR\""))
        assertTrue(actual.contains("\"tag\": \"VVAR\""))
        assertTrue(actual.contains("\"tag\": \"MVAR\""))
        assertTrue(actual.contains("\"tag\": \"COLR\""))
        assertTrue(actual.contains("\"tag\": \"CPAL\""))
        assertTrue(actual.contains("\"tag\": \"CBDT\""))
        assertTrue(actual.contains("\"tag\": \"CBLC\""))
        assertTrue(actual.contains("\"tag\": \"sbix\""))
        assertTrue(actual.contains("\"tag\": \"SVG \""))
        assertTrue(actual.contains("\"entryId\": \"generated-optional-fvar-malformed\""))
        assertTrue(actual.contains("\"parserStatus\": \"malformed-optional\""))
        assertTrue(actual.contains("\"code\": \"font.sfnt.optional-table-malformed\""))
        assertTrue(actual.contains("\"entryId\": \"generated-missing-required-tables\""))
        assertTrue(actual.contains("\"parserStatus\": \"missing-required\""))
        assertTrue(actual.contains("\"code\": \"font.sfnt.required-table-missing\""))
        assertTrue(actual.contains("\"reportPath\": \"reports/pure-kotlin-text/cmap-map.json\""))
        assertTrue(actual.contains("\"linkedTicketIds\": [\"KFONT-M2-003\"]"))
        assertTrue(actual.contains("\"linkage\": \"metadata-only-cmap-facts\""))
        assertTrue(actual.contains("\"claimPromotionAllowed\": false"))
        listOf("HarfBuzz", "FreeType", "Fontations", "CoreText", "DirectWrite", "GPU route").forEach { token ->
            assertFalse(actual.contains(token), "sfnt-tables.json must not contain hidden support token $token")
        }
    }

    @Test
    fun tableFactsUseCanonicalOrderingIndependentOfDirectoryOrder() {
        val source = FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655449201")),
            kind = FontSourceKind.MEMORY,
            displayName = "generated-shuffled-table-order",
            bytes = sfntFont(
                "glyf" to byteArrayOf(0, 1, 2, 3),
                "loca" to byteArrayOf(0, 0, 0, 2),
                "post" to postTable(underlinePosition = -75, underlineThickness = 50),
                "OS/2" to os2Table(),
                "hmtx" to hmtxTable(advanceWidth = 500, leftSideBearing = 0),
                "maxp" to maxpTable(numGlyphs = 1),
                "hhea" to hheaTable(ascender = 800, descender = -200, lineGap = 0, numberOfHMetrics = 1),
                "head" to headTable(
                    unitsPerEm = 1000,
                    bounds = OpenTypeFontBounds(xMin = 0, yMin = -200, xMax = 1000, yMax = 800),
                    indexToLocFormat = 0,
                ),
                "cmap" to cmapTable(
                    testCMapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = format4Subtable(
                            testFormat4Segment(startCode = 0x0041, endCode = 0x0041, startGlyphId = 1),
                        ),
                    ),
                ),
                "name" to nameTable(),
            ),
        )
        val face = DefaultOpenTypeFaceParser().parse(source)
        val entry = OpenTypeTableFactReportEntry.fromFaceData(
            entryId = "generated-shuffled-table-order",
            fixtureId = "sfnt-table-facts-shuffled-generated",
            fixtureKind = "GeneratedFixtureFontSource",
            face = face,
            requiredTables = OpenTypeTableFactReport.trueTypeRequiredTableTags,
        )

        assertEquals(OpenTypeTableFactReport.canonicalTableTags, entry.tableFacts.map { it.tag })
        assertEquals(
            listOf("cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "loca", "glyf"),
            entry.tableFacts.filter { it.present && it.role == "required" }.map { it.tag },
        )
    }

    private fun sfntTableFactReport(): OpenTypeTableFactReport {
        val liberationPath = projectRoot().resolve("font/fixtures/fonts/liberation/LiberationSans-Regular.ttf")
        val liberationTypefaceId = defaultTypefaceIdentityReport()
            .entries
            .single { it.label == "single-face-ttf" }
            .typefaceId()
            ?: error("M1 single-face-ttf row must have a TypefaceID.")
        val liberationFace = DefaultOpenTypeFaceParser().parse(
            FontSource(
                id = LIBERATION_SOURCE_ID,
                kind = FontSourceKind.FILE,
                displayName = "LiberationSans-Regular.ttf",
                bytes = Files.readAllBytes(liberationPath),
            ),
        )
        val optionalMalformedFace = DefaultOpenTypeFaceParser().parse(
            FontSource(
                id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655449202")),
                kind = FontSourceKind.MEMORY,
                displayName = "generated-optional-fvar-malformed",
                bytes = optionalMalformedFaceFixture(),
            ),
        )
        val missingRequiredFace = DefaultOpenTypeFaceParser().parse(
            FontSource(
                id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655449203")),
                kind = FontSourceKind.MEMORY,
                displayName = "generated-missing-required-tables",
                bytes = minimalFaceWithoutOutlineTables(),
            ),
        )

        return OpenTypeTableFactReport(
            entries = listOf(
                OpenTypeTableFactReportEntry.fromFaceData(
                    entryId = "single-ttf-liberation-sans",
                    fixtureId = "single-ttf-liberation-sans",
                    fixtureKind = "BundledFontSource",
                    face = liberationFace,
                    requiredTables = OpenTypeTableFactReport.trueTypeRequiredTableTags,
                    fontSourceReportLabel = "bundled-fixture",
                    typefaceReportLabel = "single-face-ttf",
                    typefaceId = liberationTypefaceId,
                ),
                OpenTypeTableFactReportEntry.fromFaceData(
                    entryId = "generated-optional-fvar-malformed",
                    fixtureId = "font-source-sfnt-malformed-optional-table-diagnostic",
                    fixtureKind = "GeneratedFixtureFontSource",
                    face = optionalMalformedFace,
                    requiredTables = OpenTypeTableFactReport.trueTypeRequiredTableTags,
                ),
                OpenTypeTableFactReportEntry.fromFaceData(
                    entryId = "generated-missing-required-tables",
                    fixtureId = "sfnt-table-facts-missing-required-generated",
                    fixtureKind = "GeneratedFixtureFontSource",
                    face = missingRequiredFace,
                    requiredTables = OpenTypeTableFactReport.trueTypeRequiredTableTags,
                ),
            ),
            cmapMapLink = OpenTypeTableFactCMapLink(
                reportPath = "reports/pure-kotlin-text/cmap-map.json",
                linkedTicketIds = listOf("KFONT-M2-003"),
                linkedEntryIds = listOf(
                    "generated-format0-legacy-fallback-selected",
                    "generated-format12-priority-with-format14",
                    "generated-format13-refused",
                    "generated-format4-bmp-selected",
                    "generated-format6-legacy-fallback-selected",
                ),
                linkage = "metadata-only-cmap-facts",
            ),
        )
    }

    private fun minimalFaceWithoutOutlineTables(): ByteArray =
        sfntFont(
            "name" to nameTable(),
            "cmap" to cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(startCode = 0x0041, endCode = 0x0041, startGlyphId = 17),
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
            "OS/2" to os2Table(),
            "post" to postTable(underlinePosition = -75, underlineThickness = 50),
        )

    private fun optionalMalformedFaceFixture(): ByteArray =
        sfntFont(
            "name" to nameTable(),
            "cmap" to cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(startCode = 0x0041, endCode = 0x0041, startGlyphId = 17),
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
            "OS/2" to os2Table(),
            "post" to postTable(underlinePosition = -75, underlineThickness = 50),
            "loca" to byteArrayOf(0, 0, 0, 2),
            "glyf" to byteArrayOf(0, 1, 2, 3),
            "fvar" to ByteArray(8),
        )

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

    private fun nameTable(): ByteArray =
        nameTable(
            testNameRecord(
                platformId = 3,
                encodingId = 1,
                languageId = 0x0409,
                nameId = 1,
                bytes = "Table Facts".toByteArray(Charsets.UTF_16BE),
            ),
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

    private fun os2Table(): ByteArray {
        val table = ByteArray(96)
        table.writeUInt16(0, 2)
        table.writeUInt16(4, 400)
        table.writeUInt16(6, 5)
        table.writeInt16(26, 50)
        table.writeInt16(28, 250)
        table.writeInt16(86, 500)
        table.writeInt16(88, 700)
        return table
    }

    private fun postTable(
        underlinePosition: Int,
        underlineThickness: Int,
    ): ByteArray {
        val table = ByteArray(12)
        table.writeInt16(8, underlinePosition)
        table.writeInt16(10, underlineThickness)
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

    private companion object {
        private val LIBERATION_SOURCE_ID = FontSourceID(Uuid.parse("432a2cf5-370b-52f0-9dbe-74899c8c606d"))
    }
}
