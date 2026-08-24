package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.font.sfnt.CMapTable
import io.ygdrasil.kalligraphie.font.sfnt.DefaultOpenTypeFaceParser
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeFontBounds
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubChainingContextGlyphLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubLigatureSubstitution
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubLigatureSubstitutionLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubSingleSubstitution
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubSingleSubstitutionLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubTable
import io.ygdrasil.kalligraphie.text.shaping.BasicOpenTypeShapingEngine
import io.ygdrasil.kalligraphie.text.shaping.CMapGlyphMapper
import io.ygdrasil.kalligraphie.text.shaping.ShapingRequest

class ExtensionLookupFixtureTest {

    @Test
    fun defaultOpenTypeFaceParserResolvesCheckedInGsubExtensionFixtureFromRepo() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440714",
            relativePath = "font/fixtures/fonts/shaping/gsub-extension-substitution.otf",
        )

        val gsub = requireNotNull(face.gsub)
        assertEquals(listOf("ccmp", "liga"), gsub.lookups.map { it.featureTag })
        assertEquals(2, gsub.lookups.size)
    }

    @Test
    fun basicOpenTypeShapingEngineAppliesCheckedInGsubExtensionFixtureFromRepo() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440715",
            relativePath = "font/fixtures/fonts/shaping/gsub-extension-substitution.otf",
        )
        val engine = BasicOpenTypeShapingEngine(
            glyphMapper = CMapGlyphMapper(cmapsByTypefaceId = mapOf(face.typefaceId to face.cmap)),
            gsubTablesByTypefaceId = mapOf(face.typefaceId to requireNotNull(face.gsub)),
            kernUnitsPerEmByTypefaceId = mapOf(face.typefaceId to face.unitsPerEm),
        )

        val singleResult = engine.shape(
            ShapingRequest(
                text = "A",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        val ligatureResult = engine.shape(
            ShapingRequest(
                text = "fi",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )

        assertEquals(emptyList(), singleResult.diagnostics)
        assertEquals(listOf(15), singleResult.glyphRuns.single().glyphIds)
        assertEquals(emptyList(), ligatureResult.diagnostics)
        assertEquals(listOf(42), ligatureResult.glyphRuns.single().glyphIds)
    }

    @Test
    fun defaultOpenTypeFaceParserReportsCheckedInMalformedExtensionFixtureDiagnostic() {
        val path = projectRoot().resolve("font/fixtures/fonts/shaping/layout-extension-cycle.otf")
        val source = FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440716")),
            kind = FontSourceKind.FILE,
            displayName = path.fileName.toString(),
            bytes = Files.readAllBytes(path),
        )

        val parsed = DefaultOpenTypeFaceParser().parse(source)

        assertEquals(listOf("font.sfnt.optional-table-malformed"), parsed.diagnostics.map { it.causeCode })
        assertTrue(parsed.diagnostics.single().message.contains("Unable to parse OpenType table GSUB."))
        assertTrue(
            parsed.diagnostics.single().causeMessage?.contains(
                "OpenType GSUB ExtensionSubst target lookup type 7 is not supported.",
            ) == true,
        )
        assertEquals(null, parsed.layout.gsub)
    }

    @Test
    fun defaultOpenTypeFaceParserResolvesGsubExtensionSingleLookupFromMemoryFont() {
        val face = parsedFace(
            uuid = "550e8400-e29b-41d4-a716-446655440710",
            bytes = extensionSingleLookupFont(),
        )

        assertEquals(
            OpenTypeGsubTable(
                lookups = listOf(
                    OpenTypeGsubSingleSubstitutionLookup(
                        featureTag = "ccmp",
                        lookupIndex = 0,
                        substitutions = listOf(
                            OpenTypeGsubSingleSubstitution(
                                inputGlyphId = 5,
                                replacementGlyphId = 15,
                            ),
                        ),
                    ),
                ),
            ),
            face.gsub,
        )
    }

    @Test
    fun basicOpenTypeShapingEngineAppliesParsedGsubExtensionSingleLookupFromMemoryFont() {
        val face = parsedFace(
            uuid = "550e8400-e29b-41d4-a716-446655440711",
            bytes = extensionSingleLookupFont(),
        )
        val typefaceId = face.typefaceId
        val engine = BasicOpenTypeShapingEngine(
            glyphMapper = CMapGlyphMapper(cmapsByTypefaceId = mapOf(typefaceId to face.cmap)),
            gsubTablesByTypefaceId = mapOf(typefaceId to requireNotNull(face.gsub)),
            kernUnitsPerEmByTypefaceId = mapOf(typefaceId to face.unitsPerEm),
        )

        val result = engine.shape(
            ShapingRequest(
                text = "A",
                typefaceId = typefaceId,
                fontSize = 20f,
            ),
        )

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(listOf(15), result.glyphRuns.single().glyphIds)
    }

    @Test
    fun defaultOpenTypeFaceParserResolvesGsubExtensionLigatureLookupFromMemoryFont() {
        val face = parsedFace(
            uuid = "550e8400-e29b-41d4-a716-446655440712",
            bytes = extensionLigatureLookupFont(),
        )

        assertEquals(
            OpenTypeGsubTable(
                lookups = listOf(
                    OpenTypeGsubLigatureSubstitutionLookup(
                        featureTag = "liga",
                        lookupIndex = 0,
                        substitutions = listOf(
                            OpenTypeGsubLigatureSubstitution(
                                inputGlyphIds = listOf(7, 8),
                                replacementGlyphId = 42,
                            ),
                        ),
                    ),
                ),
            ),
            face.gsub,
        )
    }

    @Test
    fun basicOpenTypeShapingEngineAppliesParsedGsubExtensionLigatureLookupFromMemoryFont() {
        val face = parsedFace(
            uuid = "550e8400-e29b-41d4-a716-446655440713",
            bytes = extensionLigatureLookupFont(),
        )
        val typefaceId = face.typefaceId
        val engine = BasicOpenTypeShapingEngine(
            glyphMapper = CMapGlyphMapper(cmapsByTypefaceId = mapOf(typefaceId to face.cmap)),
            gsubTablesByTypefaceId = mapOf(typefaceId to requireNotNull(face.gsub)),
            kernUnitsPerEmByTypefaceId = mapOf(typefaceId to face.unitsPerEm),
        )

        val result = engine.shape(
            ShapingRequest(
                text = "fi",
                typefaceId = typefaceId,
                fontSize = 20f,
            ),
        )

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(listOf(42), result.glyphRuns.single().glyphIds)
    }

    @Test
    fun extensionLookupReportGoldenTracksBoundedWave() {
        val report = Files.readString(
            projectRoot().resolve("font/fixtures/expected/shaping/extension-lookup-report.json"),
        )

        assertTrue(report.contains(""""dumpId": "extension-lookup-report""""))
        assertTrue(report.contains(""""ownerTickets": ["KFONT-M6-010"]"""))
        assertTrue(report.contains(""""fixtureKind": "checked-in-synthetic-font""""))
        assertTrue(report.contains(""""extension-single-substitution""""))
        assertTrue(report.contains(""""extension-ligature-substitution""""))
        assertTrue(report.contains(""""extension-unsupported-target-diagnostic""""))
        assertTrue(report.contains(""""no-complete-advanced-lookup-support-claim""""))
        assertTrue(report.contains(""""no-gpos-contextual-or-variation-claim""""))
    }

    @Test
    fun defaultOpenTypeFaceParserResolvesCheckedInChainingContextFixtureFromRepo() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440750",
            relativePath = "font/fixtures/fonts/shaping/gsub-chaining-context.otf",
        )
        assertNotNull(face.gsub)
        assertTrue(face.gsub!!.lookups.isNotEmpty())
    }

    private fun parsedFixtureFace(
        uuid: String,
        relativePath: String,
    ): ParsedFace {
        val path = projectRoot().resolve(relativePath)
        return parsedFace(
            uuid = uuid,
            bytes = Files.readAllBytes(path),
        )
    }

    private fun parsedFace(uuid: String, bytes: ByteArray): ParsedFace {
        val typefaceId = TypefaceID(Uuid.parse(uuid))
        val sourceId = FontSourceID(Uuid.parse(uuid.replaceRange(uuid.length - 1, uuid.length, "0")))
        val parsed = DefaultOpenTypeFaceParser().parse(
            FontSource(
                id = sourceId,
                kind = FontSourceKind.MEMORY,
                displayName = "extension-single.otf",
                bytes = bytes,
            ),
        )
        assertEquals(emptyList(), parsed.diagnostics)
        return ParsedFace(
            typefaceId = typefaceId,
            cmap = parsed.cmap,
            unitsPerEm = requireNotNull(parsed.metrics.unitsPerEm),
            gsub = parsed.layout.gsub,
        )
    }

    private fun extensionSingleLookupFont(): ByteArray =
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
            "maxp" to maxpTable(numGlyphs = 16),
            "hmtx" to hmtxTable(
                *Array(16) {
                    metric(advanceWidth = 500, leftSideBearing = 0)
                },
            ),
            "GSUB" to gsubExtensionSingleLookupTable(),
        )

    private fun extensionLigatureLookupFont(): ByteArray =
        sfntFont(
            "name" to nameTable(),
            "cmap" to cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(
                        testFormat4Segment(
                            startCode = 0x0066,
                            endCode = 0x0066,
                            startGlyphId = 7,
                        ),
                        testFormat4Segment(
                            startCode = 0x0069,
                            endCode = 0x0069,
                            startGlyphId = 8,
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
            "maxp" to maxpTable(numGlyphs = 43),
            "hmtx" to hmtxTable(
                *Array(43) {
                    metric(advanceWidth = 500, leftSideBearing = 0)
                },
            ),
            "GSUB" to gsubExtensionLigatureLookupTable(),
        )

    private fun gsubExtensionSingleLookupTable(): ByteArray {
        val table = ByteArray(78)
        val scriptListOffset = 10
        val featureListOffset = 30
        val lookupListOffset = 44
        val scriptStart = scriptListOffset + 8
        val langSysStart = scriptStart + 4
        val featureStart = featureListOffset + 8
        val lookupStart = lookupListOffset + 4
        val extensionSubtableStart = lookupStart + 8
        val singleSubtableStart = extensionSubtableStart + 8
        val coverageStart = singleSubtableStart + 8

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
        "ccmp".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 2)
        table.writeUInt16(featureListOffset + 6, 8)
        table.writeUInt16(featureStart, 0)
        table.writeUInt16(featureStart + 2, 1)
        table.writeUInt16(featureStart + 4, 0)

        table.writeUInt16(lookupListOffset, 1)
        table.writeUInt16(lookupListOffset + 2, 4)

        table.writeUInt16(lookupStart, 7)
        table.writeUInt16(lookupStart + 2, 0)
        table.writeUInt16(lookupStart + 4, 1)
        table.writeUInt16(lookupStart + 6, 8)

        table.writeUInt16(extensionSubtableStart, 1)
        table.writeUInt16(extensionSubtableStart + 2, 1)
        table.writeUInt32(extensionSubtableStart + 4, 8)

        table.writeUInt16(singleSubtableStart, 2)
        table.writeUInt16(singleSubtableStart + 2, 8)
        table.writeUInt16(singleSubtableStart + 4, 1)
        table.writeUInt16(singleSubtableStart + 6, 15)

        table.writeUInt16(coverageStart, 1)
        table.writeUInt16(coverageStart + 2, 1)
        table.writeUInt16(coverageStart + 4, 5)

        return table
    }

    private fun gsubExtensionLigatureLookupTable(): ByteArray {
        val table = ByteArray(88)
        val scriptListOffset = 10
        val featureListOffset = 30
        val lookupListOffset = 44
        val scriptStart = scriptListOffset + 8
        val langSysStart = scriptStart + 4
        val featureStart = featureListOffset + 8
        val lookupStart = lookupListOffset + 4
        val extensionSubtableStart = lookupStart + 8
        val ligatureSubtableStart = extensionSubtableStart + 8
        val coverageStart = ligatureSubtableStart + 8
        val ligatureSetStart = coverageStart + 6
        val ligatureStart = ligatureSetStart + 4

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
        "liga".toByteArray(Charsets.ISO_8859_1).copyInto(table, featureListOffset + 2)
        table.writeUInt16(featureListOffset + 6, 8)
        table.writeUInt16(featureStart, 0)
        table.writeUInt16(featureStart + 2, 1)
        table.writeUInt16(featureStart + 4, 0)

        table.writeUInt16(lookupListOffset, 1)
        table.writeUInt16(lookupListOffset + 2, 4)

        table.writeUInt16(lookupStart, 7)
        table.writeUInt16(lookupStart + 2, 0)
        table.writeUInt16(lookupStart + 4, 1)
        table.writeUInt16(lookupStart + 6, 8)

        table.writeUInt16(extensionSubtableStart, 1)
        table.writeUInt16(extensionSubtableStart + 2, 4)
        table.writeUInt32(extensionSubtableStart + 4, 8)

        table.writeUInt16(ligatureSubtableStart, 1)
        table.writeUInt16(ligatureSubtableStart + 2, 8)
        table.writeUInt16(ligatureSubtableStart + 4, 1)
        table.writeUInt16(ligatureSubtableStart + 6, 14)

        table.writeUInt16(coverageStart, 1)
        table.writeUInt16(coverageStart + 2, 1)
        table.writeUInt16(coverageStart + 4, 7)

        table.writeUInt16(ligatureSetStart, 1)
        table.writeUInt16(ligatureSetStart + 2, 4)

        table.writeUInt16(ligatureStart, 42)
        table.writeUInt16(ligatureStart + 2, 2)
        table.writeUInt16(ligatureStart + 4, 8)

        return table
    }

    private data class ParsedFace(
        val typefaceId: TypefaceID,
        val cmap: CMapTable,
        val unitsPerEm: Int,
        val gsub: OpenTypeGsubTable?,
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

    private data class TestHorizontalMetric(
        val advanceWidth: Int?,
        val leftSideBearing: Int,
    )

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

    private fun nameTable(): ByteArray {
        val table = ByteArray(6)
        table.writeUInt16(0, 0)
        table.writeUInt16(2, 0)
        table.writeUInt16(4, 6)
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

    private fun metric(
        advanceWidth: Int,
        leftSideBearing: Int,
    ): TestHorizontalMetric = TestHorizontalMetric(
        advanceWidth = advanceWidth,
        leftSideBearing = leftSideBearing,
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

    private fun projectRoot(): Path =
        generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
