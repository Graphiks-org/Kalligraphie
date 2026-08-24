package io.ygdrasil.kalligraphie.font.sfnt

import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MalformedSFNTFixtureSuiteTest {
    @Test
    fun malformedSfntFixtureSuiteMatchesGoldenAndCoversKfontM2005Evidence() {
        val report = malformedSfntFixtureSuiteReport()
        val actual = MalformedSFNTFixtureSuiteReportWriter.write(report)
        val expectedPath = projectRoot().resolve("font/fixtures/expected/pure-kotlin-text/malformed-sfnt-fixtures.json")

        if (!Files.exists(expectedPath)) {
            error("Missing malformed-sfnt-fixtures.json golden. Actual report:\n$actual")
        }

        assertEquals(Files.readString(expectedPath), actual)
        assertEquals(actual, MalformedSFNTFixtureSuiteReportWriter.write(malformedSfntFixtureSuiteReport()))
        assertEquals(MALFORMED_CASE_IDS, report.entries.map { it.caseId })
        assertTrue(actual.contains("\"ticketIds\": [\"KFONT-M2-005\"]"))
        assertTrue(actual.contains("\"dashboardClassification\": \"fixture-gated\""))
        assertTrue(actual.contains("\"claimPromotionAllowed\": false"))
        MALFORMED_CASE_IDS.forEach { caseId ->
            assertTrue(actual.contains("\"caseId\": \"$caseId\""), "missing case $caseId")
        }
        listOf(
            "font.outline-format-unsupported",
            "font.sfnt.wrapper-unsupported",
            "font.collection-index-invalid",
            "font.sfnt.table-out-of-bounds",
            "font.sfnt.table-overlap",
            "font.sfnt.table-duplicate",
            "font.sfnt.required-table-missing",
            "font.sfnt.optional-table-malformed",
            "font.sfnt.cmap-format-unsupported",
        ).forEach { diagnostic ->
            assertTrue(actual.contains("\"expectedDiagnostic\": \"$diagnostic\""), "missing diagnostic $diagnostic")
        }
        listOf(
            "reports/pure-kotlin-text/sfnt-directory.json",
            "reports/pure-kotlin-text/sfnt-tables.json",
            "reports/pure-kotlin-text/cmap-map.json",
        ).forEach { path ->
            assertTrue(actual.contains("\"linkedEvidencePath\": \"$path\""), "missing evidence path $path")
        }
        listOf("HarfBuzz", "FreeType", "Fontations", "CoreText", "DirectWrite", "GPU route").forEach { token ->
            assertFalse(actual.contains(token), "malformed-sfnt-fixtures.json must not contain hidden support token $token")
        }
        assertLinkedEvidenceRowsResolveToMatchingFixtureFacts(report)
    }

    @Test
    fun malformedFixtureRowsCarryStableGeneratedHashesAndPrimaryDiagnostics() {
        val entries = malformedSfntFixtureSuiteReport().entries

        assertEquals(MALFORMED_CASE_IDS, entries.map { it.caseId })
        entries.forEach { entry ->
            assertEquals("GeneratedFixtureFontSource", entry.fixtureKind)
            assertTrue(entry.generatorId.startsWith("kfont.fixture.malformed-sfnt."))
            assertTrue(entry.generatorParameters.isNotEmpty())
            assertTrue(entry.byteLength > 0)
            assertTrue(entry.contentSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(entry.diagnostics.map { it.code }.contains(entry.expectedDiagnostic))
            assertFalse(entry.claimPromotionAllowed)
        }
    }

    private fun malformedSfntFixtureSuiteReport(): MalformedSFNTFixtureSuiteReport {
        val badVersion = badSfntVersionFixture()
        val truncatedHeader = truncatedHeaderFixture()
        val invalidTtc = ttcFont(minimalFace("TTC Fixture", glyphId = 5))
        val outOfBounds = tableOutOfBoundsFixture()
        val overlap = overlappingTablesFixture()
        val duplicate = duplicateTagFixture()
        val missingRequired = minimalFace("Missing Required", glyphId = 7)
        val optionalMalformed = optionalMalformedFaceFixture()
        val unsupportedCMap = unsupportedCMapFaceFixture()

        return MalformedSFNTFixtureSuiteReport(
            entries = listOf(
                parserEntry(
                    caseId = "bad-sfnt-version",
                    fixtureId = "malformed-sfnt-bad-version-generated",
                    generatorParameters = listOf("scalerType=0x00020000", "tableCount=0"),
                    bytes = badVersion,
                    result = parseDirectory("generated-bad-sfnt-version", badVersion),
                    expectedDiagnostic = "font.outline-format-unsupported",
                    expectedOutcome = "parser-refused",
                    linkedEvidenceEntryId = "generated-bad-sfnt-version",
                ),
                parserEntry(
                    caseId = "truncated-header",
                    fixtureId = "malformed-sfnt-truncated-header-generated",
                    generatorParameters = listOf("byteLength=10", "scalerType=0x00010000"),
                    bytes = truncatedHeader,
                    result = parseDirectory("generated-truncated-sfnt-header", truncatedHeader),
                    expectedDiagnostic = "font.sfnt.wrapper-unsupported",
                    expectedOutcome = "parser-refused",
                    linkedEvidenceEntryId = "generated-truncated-sfnt-header",
                ),
                parserEntry(
                    caseId = "invalid-ttc-index",
                    fixtureId = "malformed-sfnt-invalid-ttc-index-generated",
                    generatorParameters = listOf("container=ttc", "faceCount=1", "requestedIndex=3"),
                    bytes = invalidTtc,
                    result = parseDirectory("generated-malformed-ttc-invalid-index", invalidTtc, collectionIndex = 3),
                    expectedDiagnostic = "font.collection-index-invalid",
                    expectedOutcome = "parser-refused",
                    linkedEvidenceEntryId = "generated-malformed-ttc-invalid-index",
                ),
                parserEntry(
                    caseId = "out-of-bounds-table-record",
                    fixtureId = "malformed-sfnt-table-out-of-bounds-generated",
                    generatorParameters = listOf("record=post", "tableLength=16", "tableOffset=72"),
                    bytes = outOfBounds,
                    result = parseDirectory("generated-table-out-of-bounds", outOfBounds),
                    expectedDiagnostic = "font.sfnt.table-out-of-bounds",
                    expectedOutcome = "directory-diagnostic",
                    linkedEvidenceEntryId = "generated-table-out-of-bounds",
                ),
                parserEntry(
                    caseId = "overlapping-tables",
                    fixtureId = "malformed-sfnt-overlapping-tables-generated",
                    generatorParameters = listOf("first=name:48:16", "second=cmap:56:12"),
                    bytes = overlap,
                    result = parseDirectory("generated-overlapping-tables", overlap),
                    expectedDiagnostic = "font.sfnt.table-overlap",
                    expectedOutcome = "directory-diagnostic",
                    linkedEvidenceEntryId = "generated-overlapping-tables",
                ),
                parserEntry(
                    caseId = "duplicate-tag",
                    fixtureId = "malformed-sfnt-duplicate-tag-generated",
                    generatorParameters = listOf("duplicateTag=name", "tableCount=2"),
                    bytes = duplicate,
                    result = parseDirectory("generated-duplicate-tag", duplicate),
                    expectedDiagnostic = "font.sfnt.table-duplicate",
                    expectedOutcome = "directory-diagnostic",
                    linkedEvidenceEntryId = "generated-duplicate-tag",
                ),
                parserEntry(
                    caseId = "missing-required-table",
                    fixtureId = "malformed-sfnt-missing-required-table-generated",
                    generatorParameters = listOf("missing=glyf", "missing=loca", "outline=TrueType"),
                    bytes = missingRequired,
                    result = parseDirectory(
                        displayName = "generated-missing-required-table",
                        bytes = missingRequired,
                        requiredTables = setOf(SFNTTableTag("glyf"), SFNTTableTag("loca")),
                    ),
                    expectedDiagnostic = "font.sfnt.required-table-missing",
                    expectedOutcome = "directory-diagnostic",
                    linkedEvidenceEntryId = "generated-missing-required-table",
                ),
                faceEntry(
                    caseId = "malformed-optional-table",
                    fixtureId = "font-source-sfnt-malformed-optional-table-diagnostic",
                    generatorParameters = listOf("optionalTable=fvar", "payloadLength=8"),
                    bytes = optionalMalformed,
                    face = parseFace("generated-optional-fvar-malformed", optionalMalformed),
                    expectedDiagnostic = "font.sfnt.optional-table-malformed",
                    expectedOutcome = "optional-table-fallback-diagnostic",
                    linkedEvidencePath = "reports/pure-kotlin-text/sfnt-tables.json",
                    linkedEvidenceEntryId = "generated-optional-fvar-malformed",
                ),
                cmapEntry(
                    caseId = "unsupported-cmap-format",
                    fixtureId = "malformed-sfnt-unsupported-cmap-format13-generated",
                    generatorParameters = listOf("cmapFormat=13", "encodingId=10", "platformId=3"),
                    bytes = unsupportedCMap,
                    face = parseFace("generated-unsupported-cmap-format13", unsupportedCMap),
                    expectedDiagnostic = "font.sfnt.cmap-format-unsupported",
                    expectedOutcome = "cmap-refusal-diagnostic",
                    linkedEvidenceEntryId = "malformed-generated-format13-refused",
                ),
            ),
        )
    }

    private fun parserEntry(
        caseId: String,
        fixtureId: String,
        generatorParameters: List<String>,
        bytes: ByteArray,
        result: SFNTParseResult,
        expectedDiagnostic: String,
        expectedOutcome: String,
        linkedEvidenceEntryId: String,
    ): MalformedSFNTFixtureSuiteEntry =
        MalformedSFNTFixtureSuiteEntry(
            caseId = caseId,
            fixtureId = fixtureId,
            fixtureKind = "GeneratedFixtureFontSource",
            generatorId = "kfont.fixture.malformed-sfnt.$caseId.v1",
            generatorParameters = generatorParameters,
            contentSha256 = bytes.sha256Hex(),
            byteLength = bytes.size,
            expectedDiagnostic = expectedDiagnostic,
            expectedOutcome = expectedOutcome,
            linkedEvidencePath = "reports/pure-kotlin-text/sfnt-directory.json",
            linkedEvidenceEntryId = linkedEvidenceEntryId,
            diagnostics = directoryDiagnostics(result),
        )

    private fun faceEntry(
        caseId: String,
        fixtureId: String,
        generatorParameters: List<String>,
        bytes: ByteArray,
        face: OpenTypeFaceData,
        expectedDiagnostic: String,
        expectedOutcome: String,
        linkedEvidencePath: String,
        linkedEvidenceEntryId: String,
    ): MalformedSFNTFixtureSuiteEntry =
        MalformedSFNTFixtureSuiteEntry(
            caseId = caseId,
            fixtureId = fixtureId,
            fixtureKind = "GeneratedFixtureFontSource",
            generatorId = "kfont.fixture.malformed-sfnt.$caseId.v1",
            generatorParameters = generatorParameters,
            contentSha256 = bytes.sha256Hex(),
            byteLength = bytes.size,
            expectedDiagnostic = expectedDiagnostic,
            expectedOutcome = expectedOutcome,
            linkedEvidencePath = linkedEvidencePath,
            linkedEvidenceEntryId = linkedEvidenceEntryId,
            diagnostics = face.diagnostics.map {
                MalformedSFNTFixtureDiagnosticSnapshot(
                    source = "face-parser",
                    code = it.causeCode ?: "font.sfnt.table-parse-diagnostic",
                    table = it.table?.value,
                    message = it.message,
                )
            },
        )

    private fun cmapEntry(
        caseId: String,
        fixtureId: String,
        generatorParameters: List<String>,
        bytes: ByteArray,
        face: OpenTypeFaceData,
        expectedDiagnostic: String,
        expectedOutcome: String,
        linkedEvidenceEntryId: String,
    ): MalformedSFNTFixtureSuiteEntry =
        MalformedSFNTFixtureSuiteEntry(
            caseId = caseId,
            fixtureId = fixtureId,
            fixtureKind = "GeneratedFixtureFontSource",
            generatorId = "kfont.fixture.malformed-sfnt.$caseId.v1",
            generatorParameters = generatorParameters,
            contentSha256 = bytes.sha256Hex(),
            byteLength = bytes.size,
            expectedDiagnostic = expectedDiagnostic,
            expectedOutcome = expectedOutcome,
            linkedEvidencePath = "reports/pure-kotlin-text/cmap-map.json",
            linkedEvidenceEntryId = linkedEvidenceEntryId,
            diagnostics = face.cmap.diagnostics.map {
                MalformedSFNTFixtureDiagnosticSnapshot(
                    source = "cmap-parser",
                    code = it.code,
                    table = "cmap",
                    message = it.message,
                )
            },
        )

    private fun directoryDiagnostics(result: SFNTParseResult): List<MalformedSFNTFixtureDiagnosticSnapshot> =
        result.diagnostics.map {
            MalformedSFNTFixtureDiagnosticSnapshot(
                source = "sfnt-parser",
                code = it.code,
                table = null,
                message = it.message,
            )
        } + result.directoryFacts?.directoryDiagnostics.orEmpty().map {
            MalformedSFNTFixtureDiagnosticSnapshot(
                source = "sfnt-directory",
                code = it.code,
                table = it.tag?.value,
                message = it.message,
            )
        }

    private fun assertLinkedEvidenceRowsResolveToMatchingFixtureFacts(report: MalformedSFNTFixtureSuiteReport) {
        val root = projectRoot()
        val evidenceByPath = MALFORMED_CASE_IDS
            .mapNotNull { caseId -> report.entries.singleOrNull { it.caseId == caseId } }
            .groupBy { it.linkedEvidencePath }
            .mapValues { (path, entries) ->
                val dump = Files.readString(root.resolve(path.replace("reports/pure-kotlin-text", "font/fixtures/expected/pure-kotlin-text")))
                entries.associateWith { entry -> dump.jsonObjectContaining("entryId", entry.linkedEvidenceEntryId) }
            }

        report.entries.forEach { entry ->
            val linked = evidenceByPath
                .getValue(entry.linkedEvidencePath)
                .getValue(entry)
            assertEquals(entry.fixtureId, linked.jsonStringField("fixtureId"), "fixtureId mismatch for ${entry.caseId}")
            assertTrue(linked.contains("\"code\": \"${entry.expectedDiagnostic}\""), "missing linked diagnostic for ${entry.caseId}")

            when (entry.linkedEvidencePath) {
                "reports/pure-kotlin-text/sfnt-directory.json" -> {
                    assertEquals(entry.byteLength, linked.jsonIntField("sourceByteLength"), "byte length mismatch for ${entry.caseId}")
                    assertEquals(entry.contentSha256, linked.jsonStringField("sourceSha256"), "source hash mismatch for ${entry.caseId}")
                }

                "reports/pure-kotlin-text/sfnt-tables.json" -> {
                    assertEquals(entry.byteLength, linked.jsonIntField("sourceByteLength"), "byte length mismatch for ${entry.caseId}")
                    assertEquals(entry.contentSha256, linked.jsonStringField("sourceSha256"), "source hash mismatch for ${entry.caseId}")
                }

                "reports/pure-kotlin-text/cmap-map.json" -> {
                    assertEquals(
                        "generated-unsupported-cmap-format13",
                        linked.jsonStringField("sourceFaceId"),
                        "source face mismatch for ${entry.caseId}",
                    )
                }
            }
        }
    }

    private fun String.jsonObjectContaining(field: String, value: String): String {
        val marker = "\"$field\": \"$value\""
        val markerIndex = indexOf(marker)
        require(markerIndex >= 0) { "Missing JSON object with $marker" }
        var start = markerIndex
        while (start >= 0 && this[start] != '{') start--
        require(start >= 0) { "Missing JSON object start for $marker" }
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until length) {
            val char = this[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\' && inString) {
                escaped = true
            } else if (char == '"') {
                inString = !inString
            } else if (!inString && char == '{') {
                depth++
            } else if (!inString && char == '}') {
                depth--
                if (depth == 0) {
                    return substring(start, index + 1)
                }
            }
        }
        error("Missing JSON object end for $marker")
    }

    private fun String.jsonStringField(field: String): String? {
        val match = Regex(""""$field": (null|"([^"]*)")""").find(this)
            ?: error("Missing JSON field $field in:\n$this")
        return match.groupValues.getOrNull(2)?.ifEmpty { null }
    }

    private fun String.jsonIntField(field: String): Int {
        val match = Regex(""""$field": ([0-9]+)""").find(this)
            ?: error("Missing JSON integer field $field in:\n$this")
        return match.groupValues[1].toInt()
    }

    private fun parseDirectory(
        displayName: String,
        bytes: ByteArray,
        collectionIndex: Int = 0,
        requiredTables: Set<SFNTTableTag> = emptySet(),
    ): SFNTParseResult =
        DefaultSFNTParser().parse(
            SFNTParseRequest(
                sourceId = FontSourceID(Uuid.parse(SOURCE_IDS.getValue(displayName))),
                sourceKind = FontSourceKind.MEMORY,
                displayName = displayName,
                bytes = BoundedFontBytes(rawBytes = bytes),
                collectionIndex = collectionIndex,
                parserGeneration = 1,
                requiredTables = requiredTables,
            ),
        )

    private fun parseFace(displayName: String, bytes: ByteArray): OpenTypeFaceData =
        DefaultOpenTypeFaceParser().parse(
            FontSource(
                id = FontSourceID(Uuid.parse(SOURCE_IDS.getValue(displayName))),
                kind = FontSourceKind.MEMORY,
                displayName = displayName,
                bytes = bytes,
            ),
        )

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
        directoryOnlyFont(sourceLength = 80, "post" to TableRecord(offset = 72, length = 16))

    private fun overlappingTablesFixture(): ByteArray =
        directoryOnlyFont(
            sourceLength = 96,
            "name" to TableRecord(offset = 48, length = 16),
            "cmap" to TableRecord(offset = 56, length = 12),
        )

    private fun duplicateTagFixture(): ByteArray =
        directoryOnlyFont(
            sourceLength = 96,
            "name" to TableRecord(offset = 48, length = 4),
            "name" to TableRecord(offset = 56, length = 4),
        )

    private fun directoryOnlyFont(
        sourceLength: Int,
        vararg records: Pair<String, TableRecord>,
    ): ByteArray {
        val font = ByteArray(sourceLength)
        font.writeUInt32(0, 0x00010000)
        font.writeUInt16(4, records.size)
        records.forEachIndexed { index, (tag, record) ->
            val recordOffset = 12 + index * 16
            tag.toByteArray(Charsets.ISO_8859_1).copyInto(font, recordOffset)
            font.writeUInt32(recordOffset + 4, index)
            font.writeUInt32(recordOffset + 8, record.offset)
            font.writeUInt32(recordOffset + 12, record.length)
        }
        return font
    }

    private fun minimalFace(family: String, glyphId: Int): ByteArray =
        sfntFont(
            "name" to nameTable(nameRecord(family)),
            "cmap" to cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(testFormat4Segment(startCode = 0x0041, endCode = 0x0041, startGlyphId = glyphId)),
                ),
            ),
            "head" to headTable(unitsPerEm = 1000, indexToLocFormat = 0),
            "hhea" to hheaTable(numberOfHMetrics = 1),
            "maxp" to maxpTable(numGlyphs = 1),
            "hmtx" to hmtxTable(advanceWidth = 500, leftSideBearing = 0),
        )

    private fun optionalMalformedFaceFixture(): ByteArray =
        sfntFont(
            "name" to nameTable(nameRecord("Table Facts")),
            "cmap" to cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 1,
                    subtable = format4Subtable(testFormat4Segment(startCode = 0x0041, endCode = 0x0041, startGlyphId = 17)),
                ),
            ),
            "head" to headTable(unitsPerEm = 1000, indexToLocFormat = 0),
            "hhea" to hheaTable(numberOfHMetrics = 1),
            "maxp" to maxpTable(numGlyphs = 1),
            "hmtx" to hmtxTable(advanceWidth = 500, leftSideBearing = 0),
            "OS/2" to os2Table(),
            "post" to postTable(),
            "loca" to byteArrayOf(0, 0, 0, 2),
            "glyf" to byteArrayOf(0, 1, 2, 3),
            "fvar" to ByteArray(8),
        )

    private fun unsupportedCMapFaceFixture(): ByteArray =
        sfntFont(
            "name" to nameTable(nameRecord("Unsupported CMap")),
            "cmap" to cmapTable(
                testCMapRecord(
                    platformId = 3,
                    encodingId = 10,
                    subtable = format13Subtable(),
                ),
            ),
            "head" to headTable(unitsPerEm = 1000, indexToLocFormat = 0),
            "hhea" to hheaTable(numberOfHMetrics = 1),
            "maxp" to maxpTable(numGlyphs = 1),
            "hmtx" to hmtxTable(advanceWidth = 500, leftSideBearing = 0),
        )

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

    private fun nameRecord(value: String): TestNameRecord =
        TestNameRecord(
            platformId = 3,
            encodingId = 1,
            languageId = 0x0409,
            nameId = 1,
            bytes = value.toByteArray(Charsets.UTF_16BE),
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

    private fun format13Subtable(): ByteArray {
        val table = ByteArray(28)
        table.writeUInt16(0, 13)
        table.writeUInt32(4, table.size)
        table.writeUInt32(12, 1)
        table.writeUInt32(16, 0x0041)
        table.writeUInt32(20, 0x0041)
        table.writeUInt32(24, 5)
        return table
    }

    private fun headTable(unitsPerEm: Int, indexToLocFormat: Int): ByteArray =
        ByteArray(54).also {
            it.writeUInt16(18, unitsPerEm)
            it.writeInt16(36, 0)
            it.writeInt16(38, -200)
            it.writeInt16(40, 1000)
            it.writeInt16(42, 820)
            it.writeInt16(50, indexToLocFormat)
        }

    private fun hheaTable(numberOfHMetrics: Int): ByteArray =
        ByteArray(36).also {
            it.writeInt16(4, 820)
            it.writeInt16(6, -180)
            it.writeInt16(8, 40)
            it.writeUInt16(34, numberOfHMetrics)
        }

    private fun maxpTable(numGlyphs: Int): ByteArray =
        ByteArray(6).also { it.writeUInt16(4, numGlyphs) }

    private fun hmtxTable(advanceWidth: Int, leftSideBearing: Int): ByteArray =
        ByteArray(4).also {
            it.writeUInt16(0, advanceWidth)
            it.writeInt16(2, leftSideBearing)
        }

    private fun os2Table(): ByteArray =
        ByteArray(96).also {
            it.writeUInt16(0, 2)
            it.writeUInt16(4, 400)
            it.writeUInt16(6, 5)
            it.writeInt16(26, 50)
            it.writeInt16(28, 250)
            it.writeInt16(86, 500)
            it.writeInt16(88, 700)
        }

    private fun postTable(): ByteArray =
        ByteArray(12).also {
            it.writeInt16(8, -75)
            it.writeInt16(10, 50)
        }

    private fun testCMapRecord(platformId: Int, encodingId: Int, subtable: ByteArray): TestCMapRecord =
        TestCMapRecord(platformId = platformId, encodingId = encodingId, subtable = subtable)

    private fun testFormat4Segment(startCode: Int, endCode: Int, startGlyphId: Int): TestFormat4Segment =
        TestFormat4Segment(startCode = startCode, endCode = endCode, idDelta = (startGlyphId - startCode) and 0xffff)

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

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }

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

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null && !Files.isDirectory(current.resolve("font/fixtures"))) {
            current = current.parent
        }
        return current
    }

    private data class TableRecord(val offset: Int, val length: Int)
    private data class TestNameRecord(
        val platformId: Int,
        val encodingId: Int,
        val languageId: Int,
        val nameId: Int,
        val bytes: ByteArray,
    )
    private data class TestCMapRecord(val platformId: Int, val encodingId: Int, val subtable: ByteArray)
    private data class TestFormat4Segment(val startCode: Int, val endCode: Int, val idDelta: Int)

    private companion object {
        private val MALFORMED_CASE_IDS = listOf(
            "bad-sfnt-version",
            "truncated-header",
            "invalid-ttc-index",
            "out-of-bounds-table-record",
            "overlapping-tables",
            "duplicate-tag",
            "missing-required-table",
            "malformed-optional-table",
            "unsupported-cmap-format",
        )
        private val SOURCE_IDS = mapOf(
            "generated-bad-sfnt-version" to "550e8400-e29b-41d4-a716-44665544a001",
            "generated-truncated-sfnt-header" to "550e8400-e29b-41d4-a716-44665544a002",
            "generated-malformed-ttc-invalid-index" to "550e8400-e29b-41d4-a716-44665544a003",
            "generated-table-out-of-bounds" to "550e8400-e29b-41d4-a716-44665544a004",
            "generated-overlapping-tables" to "550e8400-e29b-41d4-a716-44665544a005",
            "generated-duplicate-tag" to "550e8400-e29b-41d4-a716-44665544a006",
            "generated-missing-required-table" to "550e8400-e29b-41d4-a716-44665544a007",
            "generated-optional-fvar-malformed" to "550e8400-e29b-41d4-a716-44665544a008",
            "generated-unsupported-cmap-format13" to "550e8400-e29b-41d4-a716-44665544a009",
        )
    }
}
