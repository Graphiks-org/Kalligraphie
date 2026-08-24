package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.ygdrasil.kalligraphie.text.shaping.PinnedUnicodeDataGenerator
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_UNICODE_DATA_VERSION_MISMATCH_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.UcdInputFile
import io.ygdrasil.kalligraphie.text.shaping.UnicodeDataVersionMismatchDiagnostic
import io.ygdrasil.kalligraphie.text.shaping.UnicodeRange
import io.ygdrasil.kalligraphie.text.shaping.UnicodeRangeTable

class UnicodeDataGenerationTest {
    @Test
    fun pinnedUnicodeDataGeneratorProducesDeterministicManifestAndTableFixtures() {
        val inputs = loadSeedInputs()

        val first = PinnedUnicodeDataGenerator.generate(inputs)
        val second = PinnedUnicodeDataGenerator.generate(inputs)

        assertEquals(first.toManifestJson(), second.toManifestJson())
        assertEquals(first.toTablesJson(), second.toTablesJson())
        assertEquals(
            readProjectFile("font/fixtures/expected/unicode/unicode-data-manifest.json"),
            first.toManifestJson(),
        )
        assertEquals(
            readProjectFile("font/fixtures/expected/unicode/unicode-data-tables.json"),
            first.toTablesJson(),
        )
        assertTrue(first.toTablesJson().contains("\"defaultValue\": {"))
        assertTrue(first.tableJson("graphemeClusterBreak").contains("\"defaultValue\": \"Other\""))
        assertTrue(first.tableJson("bidiClass").contains("\"defaultValue\": \"L\""))
        assertTrue(first.tableJson("scriptExtensions").contains("\"defaultValue\": []"))
        assertTrue(first.tableJson("defaultIgnorable").contains("\"defaultValue\": false"))
        assertTrue(
            first.tableJson("emojiProperties")
                .contains(
                    "\"defaultValue\": {\"emoji\": false, \"emojiModifier\": false, " +
                        "\"emojiModifierBase\": false, \"emojiPresentation\": false, " +
                        "\"extendedPictographic\": false}",
                ),
        )
        listOf(
            first.toManifestJson(),
            first.toTablesJson(),
            UnicodeDataVersionMismatchDiagnostic(
                expectedUnicodeVersion = "15.1.0",
                actualUnicodeVersion = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
                subject = "fixture:unicode-data-version-mismatch",
                textRange = 0..1,
            ).toCanonicalJson(),
        ).forEach { generatedJson ->
            assertTrue(generatedJson.contains("\"no-bidi-or-script-itemizer-replacement-claim\""))
            assertTrue(generatedJson.contains("\"no-complete-uax29-claim\""))
            assertTrue(generatedJson.contains("\"no-paragraph-support-claim\""))
        }
        assertEquals("16.0.0", first.version.value)
        assertEquals(seedInputFileNames, first.sourceManifest.inputs.map { it.fileName })

        val inputHashes = first.sourceManifest.inputs.associate { it.fileName to it.sha256 }
        inputs.forEach { input ->
            assertEquals(input.content.toByteArray(Charsets.UTF_8).sha256HexForTest(), inputHashes[input.fileName])
        }

        val tableNames = listOf(
            "bidiClass",
            "defaultIgnorable",
            "emojiProperties",
            "generalCategory",
            "graphemeClusterBreak",
            "indicConjunctBreak",
            "lineBreak",
            "script",
            "scriptExtensions",
            "variationSelector",
        )
        assertEquals(tableNames, first.sourceManifest.generatedTableHashes.keys.toList())
        tableNames.forEach { tableName ->
            assertEquals(
                first.tableJson(tableName).toByteArray(Charsets.UTF_8).sha256HexForTest(),
                first.sourceManifest.generatedTableHashes[tableName],
                "Generated hash mismatch for $tableName",
            )
        }

        val facts = first.sampleFacts.associateBy { it.codePoint }
        assertEquals("Latn", facts.getValue(0x0041).script)
        assertEquals("L", facts.getValue(0x0041).bidiClass)
        assertEquals("Lu", facts.getValue(0x0041).generalCategory)
        assertEquals("Ll", facts.getValue(0x0061).generalCategory)
        assertEquals("Extend", facts.getValue(0x0301).graphemeClusterBreak)
        assertEquals("R", facts.getValue(0x05D0).bidiClass)
        assertEquals(listOf("Arab", "Mand", "Syrc"), facts.getValue(0x0640).scriptExtensions)
        assertEquals("Consonant", facts.getValue(0x0915).indicConjunctBreak)
        assertEquals("Linker", facts.getValue(0x094D).indicConjunctBreak)
        assertTrue(facts.getValue(0x1F3FB).emojiModifier)
        assertTrue(facts.getValue(0x1F466).emojiModifierBase)
        assertTrue(facts.getValue(0x1F600).emoji)
        assertTrue(facts.getValue(0x1F600).extendedPictographic)
        assertTrue(facts.getValue(0xFE0F).variationSelector)
        assertEquals("Extend", first.graphemeBreak.valueAt(0xE0100))
        assertEquals("Zinh", first.script.valueAt(0xE0100))
        assertTrue(first.defaultIgnorable.valueAt(0xE0100))
        assertTrue(first.variationSelector.valueAt(0xE0100))
    }

    @Test
    fun pinnedUnicodeDataGeneratorRefusesMissingAndUnpinnedInputs() {
        val inputs = loadSeedInputs()

        val missingLineBreak = assertFailsWith<IllegalArgumentException> {
            PinnedUnicodeDataGenerator.generate(inputs.filterNot { it.fileName == "LineBreak.txt" })
        }
        assertTrue(missingLineBreak.message.orEmpty().contains("missing required Unicode input: LineBreak.txt"))

        val unpinnedScripts = assertFailsWith<IllegalArgumentException> {
            PinnedUnicodeDataGenerator.generate(
                inputs.map { input ->
                    if (input.fileName == "Scripts.txt") {
                        input.copy(unicodeVersion = "15.1.0")
                    } else {
                        input
                    }
                },
            )
        }
        assertTrue(unpinnedScripts.message.orEmpty().contains("expected Unicode 16.0.0"))
    }

    @Test
    fun unicodeRangeTablesRejectUtf16SurrogateCodePoints() {
        val surrogateRange = assertFailsWith<IllegalArgumentException> {
            UnicodeRange(0xD800, 0xD800, "surrogate")
        }
        assertTrue(surrogateRange.message.orEmpty().contains("scalar value"))

        val lookup = UnicodeRangeTable(
            propertyName = "test",
            defaultValue = "Other",
            ranges = emptyList(),
        )
        val surrogateLookup = assertFailsWith<IllegalArgumentException> {
            lookup.valueAt(0xDFFF)
        }
        assertTrue(surrogateLookup.message.orEmpty().contains("scalar value"))
    }

    @Test
    fun unicodeRangeTablesRejectDuplicateAndOverlappingRanges() {
        val duplicate = assertFailsWith<IllegalArgumentException> {
            UnicodeRangeTable(
                propertyName = "test",
                defaultValue = "Other",
                ranges = listOf(
                    UnicodeRange(0x0041, 0x0041, "A"),
                    UnicodeRange(0x0041, 0x0041, "B"),
                ),
            )
        }
        assertTrue(duplicate.message.orEmpty().contains("overlap"))

        val overlap = assertFailsWith<IllegalArgumentException> {
            UnicodeRangeTable(
                propertyName = "test",
                defaultValue = "Other",
                ranges = listOf(
                    UnicodeRange(0x0041, 0x0043, "A"),
                    UnicodeRange(0x0043, 0x0044, "B"),
                ),
            )
        }
        assertTrue(overlap.message.orEmpty().contains("overlap"))
    }

    @Test
    fun unicodeDataVersionMismatchDiagnosticIsStableAndFixtureBacked() {
        val mismatch = UnicodeDataVersionMismatchDiagnostic(
            expectedUnicodeVersion = "15.1.0",
            actualUnicodeVersion = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
            subject = "fixture:unicode-data-version-mismatch",
            textRange = 0..1,
        )

        val diagnostic = mismatch.toShapingDiagnostic()

        assertEquals(TEXT_SHAPING_UNICODE_DATA_VERSION_MISMATCH_DIAGNOSTIC_CODE, diagnostic.code)
        assertEquals(0..1, diagnostic.textRange)
        assertTrue(diagnostic.message.contains("fixture:unicode-data-version-mismatch"))
        assertTrue(diagnostic.message.contains("expected 15.1.0"))
        assertTrue(diagnostic.message.contains("actual 16.0.0"))
        assertEquals(
            readProjectFile("font/fixtures/expected/unicode/unicode-data-version-mismatch-diagnostic.json"),
            mismatch.toCanonicalJson(),
        )
    }

    private fun loadSeedInputs(): List<UcdInputFile> =
        seedInputFileNames.map { fileName ->
            UcdInputFile(
                fileName = fileName,
                unicodeVersion = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
                content = readProjectFile("font/fixtures/expected/unicode/source-extracts/16.0.0/$fileName"),
            )
        }

    private fun readProjectFile(relativePath: String): String =
        Files.readString(projectRoot().resolve(relativePath))

    private fun projectRoot(): Path =
        generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private fun ByteArray.sha256HexForTest(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }

    private companion object {
        val seedInputFileNames = listOf(
            "DerivedCoreProperties.txt",
            "GraphemeBreakProperty.txt",
            "LineBreak.txt",
            "PropList.txt",
            "ScriptExtensions.txt",
            "Scripts.txt",
            "UnicodeData.txt",
            "emoji/emoji-data.txt",
        )
    }
}
