package io.ygdrasil.kalligraphie.font

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FontIdentityDumpTest {
    @Test
    fun `font source and typeface golden dumps match writer output`() {
        val root = projectRoot()
        val expectedSource = Files.readString(root.resolve("font/fixtures/expected/pure-kotlin-text/font-source.json"))
            .replace("reports/font/fixtures", "font/fixtures")
        val expectedTypeface = Files.readString(root.resolve("font/fixtures/expected/pure-kotlin-text/typeface-id.json"))
            .replace("reports/font/fixtures", "font/fixtures")
        val bundle = FontIdentityDumpWriter.writeBundle()

        assertEquals(expectedSource, FontIdentityDumpWriter.writeFontSourceJson().value)
        assertEquals(expectedTypeface, FontIdentityDumpWriter.writeTypefaceIdJson().value)
        assertEquals(expectedSource, bundle.fontSourceJson.value)
        assertEquals(expectedTypeface, bundle.typefaceIdJson.value)
        assertFalse(bundle.claimPromotionAllowed)
    }

    @Test
    fun `identity dump determinism compares repeated runs byte for byte`() {
        val result = FontIdentityDumpWriter.assertDeterministicDump {
            FontIdentityDumpWriter.writeBundle()
        }

        assertTrue(result.matches)
        assertEquals(result.firstSha256, result.secondSha256)
        assertEquals(emptyList(), result.differingFiles)

        val first = FontIdentityDumpWriter.writeBundle()
        val changed = first.copy(
            typefaceIdJson = CanonicalFontIdentityJson("""{"schema":"changed-typeface-id"}"""),
        )
        val mismatch = FontIdentityDumpWriter.verifyDeterministicRuns(first, changed)

        assertFalse(mismatch.matches)
        assertNotEquals(mismatch.firstSha256, mismatch.secondSha256)
        assertEquals(listOf("typeface-id.json"), mismatch.differingFiles)
        assertContains(mismatch.toCanonicalJson().value, """"differingFiles":["typeface-id.json"]""")
    }

    @Test
    fun `identity dump schema and determinism result snapshot mutable lists`() {
        val outputFiles = mutableListOf("font-source.json", "typeface-id.json", "identity-dump-schema.json")
        val schema = FontIdentityDumpSchema(outputFiles = outputFiles)
        outputFiles[0] = "mutated.json"

        assertContains(schema.toCanonicalJson().value, """"outputFiles":["font-source.json","typeface-id.json","identity-dump-schema.json"]""")
        assertFalse(schema.toCanonicalJson().value.contains("mutated.json"))

        val differingFiles = mutableListOf("typeface-id.json")
        val result = FontIdentityDumpDeterminismResult(
            matches = false,
            firstSha256 = "0".repeat(64),
            secondSha256 = "1".repeat(64),
            differingFiles = differingFiles,
        )
        differingFiles += "mutated.json"

        assertEquals(listOf("typeface-id.json"), result.differingFiles)
        assertContains(result.toCanonicalJson().value, """"differingFiles":["typeface-id.json"]""")
        assertFalse(result.toCanonicalJson().value.contains("mutated.json"))
    }

    @Test
    fun `canonical identity json rejects trailing content`() {
        assertFailsWith<IllegalArgumentException> {
            CanonicalFontIdentityJson("""{"schema":"one"}{"schema":"two"}""")
        }
    }

    @Test
    fun `identity dump schema describes fields and ordering rules`() {
        val schema = FontIdentityDumpWriter.writeBundle().schemaDescriptionJson.value

        assertContains(schema, """"schemaVersion":1""")
        assertContains(schema, "font-source.json")
        assertContains(schema, "typeface-id.json")
        assertContains(schema, "sorted table tags")
        assertContains(schema, "sorted variation coordinates")
        assertContains(schema, "sorted palette overrides")
        assertContains(schema, "selected cmap")
        assertContains(schema, "host-dependent marker")
        assertContains(schema, "diagnostics")
        assertContains(schema, """"claimPromotionAllowed":false""")
    }

    @Test
    fun `host dependent source diagnostic remains visible in dump bundle`() {
        val bundleJson = FontIdentityDumpWriter.writeBundle().toCanonicalJson().value

        assertContains(bundleJson, "system-scanned-host-dependent")
        assertContains(bundleJson, "hostDependent")
        assertContains(bundleJson, "font.source.host-dependent")
        assertFalse(bundleJson.contains("/var/folders/"), bundleJson)
        assertFalse(bundleJson.contains("/tmp/"), bundleJson)
        assertFalse(bundleJson.contains("/private/tmp/"), bundleJson)
        assertFalse(bundleJson.contains("C:\\Users\\"), bundleJson)
    }

    @Test
    fun `identity dump bundle does not contain hidden support claims or native engine tokens`() {
        val generatedJson = listOf(
            FontIdentityDumpWriter.writeFontSourceJson().value,
            FontIdentityDumpWriter.writeTypefaceIdJson().value,
            FontIdentityDumpWriter.writeBundle().schemaDescriptionJson.value,
            FontIdentityDumpWriter.writeBundle().toCanonicalJson().value,
        ).joinToString(separator = "\n")

        listOf(
            "glyph rendering support",
            "rendering support",
            "shaping support",
            "fallback complete",
            "glyph scaling",
            "glyph cache",
            "GPU support",
            "WebGPU",
            "WGSL",
            "SkTypeface",
            "HarfBuzz",
            "FreeType",
            "native engine",
        ).forEach { token ->
            assertFalse(generatedJson.contains(token), "Identity dump leaked forbidden token $token: $generatedJson")
        }
    }

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null && !Files.isDirectory(current.resolve("font/fixtures"))) {
            current = current.parent
        }
        return current
    }
}
