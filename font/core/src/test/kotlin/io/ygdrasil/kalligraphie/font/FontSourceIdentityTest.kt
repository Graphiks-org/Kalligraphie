package io.ygdrasil.kalligraphie.font

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FontSourceIdentityTest {
    @Test
    fun `font source identity preimage derives stable ids from provenance and captured bytes`() {
        val first = fontSourceIdentityPreimage(
            kind = FontSourceKind.BUNDLED_FIXTURE,
            declaredName = "Fixture Sans",
            licenseId = "OFL-1.1",
            contentBytes = byteArrayOf(1, 2, 3),
            faceCount = 1,
            tableTags = listOf("name", "cmap", "head", "cmap"),
            parserGeneration = 7,
        )
        val repeated = fontSourceIdentityPreimage(
            kind = FontSourceKind.BUNDLED_FIXTURE,
            declaredName = "Fixture Sans",
            licenseId = "OFL-1.1",
            contentBytes = byteArrayOf(1, 2, 3),
            faceCount = 1,
            tableTags = listOf("head", "name", "cmap"),
            parserGeneration = 7,
        )
        val differentBytes = fontSourceIdentityPreimage(
            kind = FontSourceKind.BUNDLED_FIXTURE,
            declaredName = "Fixture Sans",
            licenseId = "OFL-1.1",
            contentBytes = byteArrayOf(1, 2, 4),
            faceCount = 1,
            tableTags = listOf("cmap", "head", "name"),
            parserGeneration = 7,
        )

        assertEquals(first.toCanonicalJson(), repeated.toCanonicalJson())
        assertEquals(first, repeated)
        assertEquals(first.hashCode(), repeated.hashCode())
        assertEquals(first.sourceId(), repeated.sourceId())
        assertNotEquals(first.sourceId(), differentBytes.sourceId())
        assertEquals(listOf("cmap", "head", "name"), first.tableTags)
        assertEquals(3L, first.byteLength)
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", first.contentSha256)
        assertFalse(first.hostDependent)
        assertContains(first.toCanonicalJson(), """"schema": "org.graphiks.kanvas.font.FontSourceIdentityPreimage.v1"""")
        assertContains(first.toCanonicalJson(), """"kind": "BundledFontSource"""")
        assertContains(first.toCanonicalJson(), """"tableTags": ["cmap", "head", "name"]""")
    }

    @Test
    fun `font source preimages distinguish target source kinds and host dependence`() {
        val userData = fontSourceIdentityPreimage(
            kind = FontSourceKind.USER_DATA,
            declaredName = "Upload Sans",
            contentBytes = byteArrayOf(9, 9),
            faceCount = 1,
            tableTags = listOf("cmap"),
            parserGeneration = 1,
        )
        val userStream = fontSourceIdentityPreimage(
            kind = FontSourceKind.USER_STREAM,
            declaredName = "Upload Sans",
            contentBytes = byteArrayOf(9, 9),
            faceCount = 1,
            tableTags = listOf("cmap"),
            parserGeneration = 1,
        )
        val userFile = fontSourceIdentityPreimage(
            kind = FontSourceKind.USER_FILE,
            declaredName = "Upload Sans",
            originPath = "fonts/UploadSans.ttf",
            contentBytes = byteArrayOf(9, 9),
            faceCount = 1,
            tableTags = listOf("cmap"),
            parserGeneration = 1,
        )
        val generated = fontSourceIdentityPreimage(
            kind = FontSourceKind.GENERATED_FIXTURE,
            declaredName = "Tiny Generated",
            contentBytes = byteArrayOf(4),
            faceCount = 1,
            tableTags = emptyList(),
            parserGeneration = 1,
        )
        val systemScanned = fontSourceIdentityPreimage(
            kind = FontSourceKind.SYSTEM_SCANNED,
            declaredName = "Host Sans",
            originPath = "system-fonts/HostSans.ttf",
            contentBytes = null,
            faceCount = 0,
            tableTags = emptyList(),
            parserGeneration = 1,
            diagnostics = listOf(
                fontSourceIdentityDiagnostic(
                    code = "font.source.host-dependent",
                    message = "System scan did not capture bytes.",
                ),
            ),
        )

        assertNotEquals(userData.sourceId(), userStream.sourceId())
        assertNotEquals(userData.sourceId(), userFile.sourceId())
        assertEquals("GeneratedFixtureFontSource", generated.kind.serializedName)
        assertTrue(systemScanned.hostDependent)
        assertEquals(null, systemScanned.contentSha256)
        assertEquals(null, systemScanned.byteLength)
        assertContains(systemScanned.toCanonicalJson(), """"hostDependent": true""")
        assertContains(systemScanned.toCanonicalJson(), """"code": "font.source.host-dependent"""")
        assertFalse(systemScanned.toCanonicalJson().contains("/var/folders/"))
    }

    @Test
    fun `font source report emits fixture-equivalent deterministic font-source json`() {
        val report = defaultFontSourceIdentityReport()
        val json = report.toCanonicalJson()

        assertEquals(json, defaultFontSourceIdentityReport().toCanonicalJson())
        assertEquals("font-source.json", report.fixtureName)
        assertEquals(
            listOf(
                "bundled-fixture",
                "generated-fixture",
                "user-data",
                "system-scanned-host-dependent",
            ),
            report.entries.map { entry -> entry.label },
        )
        assertTrue(report.entries.all { entry -> entry.claimPromotionAllowed == false })
        assertContains(json, """"schema":"org.graphiks.kanvas.font.FontSourceIdentityReport.v1"""")
        assertContains(json, """"fixtureName":"font-source.json"""")
        assertContains(json, """"diagnostics": [
    {
      "code": "font.source.host-dependent"""")
        assertContains(json, """"claimPromotionAllowed":false""")
        listOf("SkFont", "SkTypeface", "HarfBuzz", "FreeType", "GPUHandle", "@").forEach { token ->
            assertFalse(json.contains(token), "Font source report leaked forbidden token $token: $json")
        }
    }

    @Test
    fun `canonical source preimage json escapes control characters`() {
        val preimage = fontSourceIdentityPreimage(
            kind = FontSourceKind.USER_DATA,
            declaredName = "Name\b\u0000\u001F",
            licenseId = "License\u000C",
            originPath = "fonts\tFixture.ttf",
            contentBytes = byteArrayOf(1),
            faceCount = 1,
            parserGeneration = 1,
            diagnostics = listOf(
                fontSourceIdentityDiagnostic(
                    code = "font.source.unreadable",
                    message = "line\nzero\u0000",
                ),
            ),
        )
        val json = preimage.toCanonicalJson()

        assertContains(json, "Name\\b\\u0000\\u001f")
        assertContains(json, "License\\f")
        assertContains(json, "fonts\\tFixture.ttf")
        assertContains(json, "line\\nzero\\u0000")
        assertFalse(json.any { character -> character < ' ' && character != '\n' })
    }

    @Test
    fun `checked in font source json matches generated report`() {
        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/pure-kotlin-text/font-source.json"))
            .replace("reports/font/fixtures", "font/fixtures")

        assertEquals(expected.trim(), defaultFontSourceIdentityReport().toCanonicalJson())
    }

    @Test
    fun `legacy font source kinds keep enum order and conservative serialized names`() {
        assertEquals(
            listOf(
                FontSourceKind.MEMORY,
                FontSourceKind.SYSTEM,
                FontSourceKind.FILE,
                FontSourceKind.RESOURCE,
            ),
            FontSourceKind.entries.take(4),
        )
        assertEquals("UserDataFontSource", FontSourceKind.MEMORY.serializedName)
        assertEquals("SystemScannedFontSource", FontSourceKind.SYSTEM.serializedName)
        assertEquals("UserFileFontSource", FontSourceKind.FILE.serializedName)
        assertEquals("UserDataFontSource", FontSourceKind.RESOURCE.serializedName)
    }

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null && !Files.isDirectory(current.resolve("font/fixtures"))) {
            current = current.parent
        }
        return current
    }
}
