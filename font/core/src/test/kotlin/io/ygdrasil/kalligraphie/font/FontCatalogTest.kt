package io.ygdrasil.kalligraphie.font

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FontCatalogTest {
    @Test
    fun `bundled font catalog stays byte identical across repeated loads and input order`() {
        val alpha = testCatalogInput(
            fixtureId = "alpha-sans",
            declaredName = "Alpha Sans Regular",
            familyName = "Alpha Sans",
            styleName = "Regular",
            relativePath = "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            contentByte = 0x11,
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Latin", "Greek", "Cyrillic"),
        )
        val beta = testCatalogInput(
            fixtureId = "beta-serif",
            declaredName = "Beta Serif Regular",
            familyName = "Beta Serif",
            styleName = "Regular",
            relativePath = "font/fixtures/fonts/liberation/LiberationSerif-Regular.ttf",
            contentByte = 0x22,
            genericFamilies = listOf("serif"),
            scriptCoverage = listOf("Latin"),
        )

        val first = BundledFontCatalogBuilder.build(
            generation = 7,
            inputs = listOf(beta, alpha),
        ).toCanonicalJson().value
        val second = BundledFontCatalogBuilder.build(
            generation = 7,
            inputs = listOf(alpha, beta),
        ).toCanonicalJson().value

        assertEquals(first, second)
        assertContains(first, """"generation":7""")
        assertTrue(first.indexOf("Alpha Sans") < first.indexOf("Beta Serif"))
    }

    @Test
    fun `bundled font catalog emits duplicate provenance and refusal diagnostics deterministically`() {
        val primary = testCatalogInput(
            fixtureId = "gamma-primary",
            declaredName = "Gamma Sans Regular",
            familyName = "Gamma Sans",
            styleName = "Regular",
            relativePath = "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            contentByte = 0x33,
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Latin"),
        )
        val duplicate = testCatalogInput(
            fixtureId = "gamma-duplicate",
            declaredName = "Gamma Sans Regular Alt",
            familyName = "Gamma Sans",
            styleName = "Regular",
            relativePath = "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            contentByte = 0x44,
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Latin"),
        )
        val missingProvenance = testCatalogInput(
            fixtureId = "missing-provenance",
            declaredName = "Missing Provenance Sans",
            familyName = "Missing Provenance Sans",
            styleName = "Regular",
            relativePath = "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            contentByte = 0x55,
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Latin"),
            licenseId = null,
            licensePath = null,
            provenance = null,
        )
        val missingRequiredTable = testCatalogInput(
            fixtureId = "missing-required-table",
            declaredName = "Missing Head Sans",
            familyName = "Missing Head Sans",
            styleName = "Regular",
            relativePath = "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            contentByte = 0x66,
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Latin"),
            typefaceDiagnostics = listOf(
                typefaceIdentityDiagnostic(
                    code = "font.required-table-missing",
                    message = "Required table head is not present.",
                ),
            ),
        )
        val unsupportedOutline = testCatalogInput(
            fixtureId = "bitmap-only-face",
            declaredName = "Bitmap Only Sans",
            familyName = "Bitmap Only Sans",
            styleName = "Regular",
            relativePath = "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            contentByte = 0x77,
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Latin"),
            outlineFormat = TypefaceOutlineFormat.BITMAP_ONLY,
            typefaceDiagnostics = listOf(
                typefaceIdentityDiagnostic(
                    code = "font.outline-format-unsupported",
                    message = "Bitmap-only outlines are not supported by the bundled catalog.",
                ),
            ),
        )
        val hostDependent = testCatalogInput(
            fixtureId = "host-dependent-face",
            declaredName = "Host Dependent Sans",
            familyName = "Host Dependent Sans",
            styleName = "Regular",
            relativePath = null,
            contentByte = 0x00,
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Latin"),
            sourceKind = FontSourceKind.SYSTEM_SCANNED,
        )

        val catalog = BundledFontCatalogBuilder.build(
            generation = 9,
            inputs = listOf(unsupportedOutline, duplicate, missingRequiredTable, hostDependent, primary, missingProvenance),
        )

        assertEquals(
            listOf(
                "font.catalog.duplicate-face",
                "font.catalog.provenance-missing",
                "font.outline-format-unsupported",
                "font.required-table-missing",
                "font.source.host-dependent",
            ),
            catalog.diagnostics.map { it.code }.distinct(),
        )
        assertEquals(listOf("Gamma Sans"), catalog.entries.filter { it.familyName == "Gamma Sans" }.map { it.familyName })
        assertFalse(catalog.entries.any { it.sourceKind == FontSourceKind.SYSTEM_SCANNED.serializedName })
    }

    @Test
    fun `checked in font catalog json matches generated default catalog`() {
        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/pure-kotlin-text/font-catalog.json"))
            .replace("reports/font/fixtures", "font/fixtures")
        val actual = defaultBundledFontCatalog().toCanonicalJson().value

        assertEquals(expected.trim(), actual)
        assertContains(actual, """"catalogId":"font-catalog"""")
        assertContains(actual, """"claimPromotionAllowed":false""")
        assertFalse(actual.contains("system-scanned-host-dependent"))
    }

    @Test
    fun `default bundled font catalog includes deterministic multilingual fallback breadth`() {
        val catalog = defaultBundledFontCatalog()

        assertEquals(
            setOf(
                "Liberation Sans",
                "Noto Color Emoji",
                "Noto Naskh Arabic",
                "Noto Sans Devanagari",
                "Noto Sans Hebrew",
                "Noto Sans SC",
                "Noto Sans Thai",
                "Roboto Flex",
                "Source Serif 4",
            ),
            catalog.entries.map { it.familyName }.toSet(),
        )
        assertTrue(catalog.entries.any { it.familyName == "Noto Sans Hebrew" && it.scriptCoverage == listOf("Hebrew") })
        assertTrue(catalog.entries.any { it.familyName == "Noto Naskh Arabic" && it.scriptCoverage == listOf("Arabic") })
        assertTrue(catalog.entries.any { it.familyName == "Noto Sans Devanagari" && it.scriptCoverage == listOf("Devanagari") })
        assertTrue(catalog.entries.any { it.familyName == "Noto Sans Thai" && it.scriptCoverage == listOf("Thai") })
        assertTrue(catalog.entries.any { it.familyName == "Noto Sans SC" && it.scriptCoverage == listOf("Han") })
        assertTrue(
            catalog.entries.any { entry ->
                entry.familyName == "Noto Color Emoji" &&
                    entry.emojiCapable &&
                    entry.colorCapable &&
                    entry.colorFormats == listOf("COLRv1")
            },
        )
    }

    @Test
    fun `checked in duplicate face catalog golden matches deterministic builder output`() {
        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/pure-kotlin-text/font-catalog-duplicate-face.json"))
            .replace("reports/font/fixtures", "font/fixtures")
        val actual = duplicateFaceCatalogGoldenJson()

        assertEquals(expected.trim(), actual)
        assertContains(actual, """"code":"font.catalog.duplicate-face"""")
    }

    private fun testCatalogInput(
        fixtureId: String,
        declaredName: String,
        familyName: String,
        styleName: String,
        relativePath: String?,
        contentByte: Int,
        genericFamilies: List<String>,
        scriptCoverage: List<String>,
        sourceKind: FontSourceKind = FontSourceKind.BUNDLED_FIXTURE,
        outlineFormat: TypefaceOutlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
        licenseId: String? = "SIL-OFL-1.1",
        licensePath: String? = "font/fixtures/licenses/liberation-OFL-1.1.txt",
        provenance: String? = "Deterministic test fixture.",
        typefaceDiagnostics: List<TypefaceIdentityDiagnostic> = emptyList(),
    ): BundledFontCatalogInput {
        val contentBytes = if (sourceKind == FontSourceKind.SYSTEM_SCANNED) null else byteArrayOf(contentByte.toByte())
        val sourcePreimage = fontSourceIdentityPreimage(
            kind = sourceKind,
            declaredName = declaredName,
            licenseId = licenseId,
            originPath = relativePath,
            contentBytes = contentBytes,
            faceCount = 1,
            tableTags = listOf("cmap", "glyf", "head", "name"),
            parserGeneration = 1,
            diagnostics = if (sourceKind == FontSourceKind.SYSTEM_SCANNED) {
                listOf(
                    fontSourceIdentityDiagnostic(
                        code = "font.source.host-dependent",
                        message = "Host-scanned fonts remain non-normative until bytes are captured.",
                    ),
                )
            } else {
                emptyList()
            },
        )
        val manifestEntry = BundledFontFixtureManifestEntry(
            fixtureId = fixtureId,
            sourceKind = sourceKind,
            relativePath = relativePath,
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = licenseId,
            licensePath = licensePath,
            provenance = provenance,
            contentSha256 = contentBytes?.sha256Hex(),
            byteLength = contentBytes?.size?.toLong(),
            faceCount = 1,
            coverageTags = listOf("sfnt-source", "table:cmap", "table:glyf", "table:head", "table:name"),
            normativeStatus = if (sourceKind == FontSourceKind.SYSTEM_SCANNED) {
                FontFixtureNormativeStatus.NON_NORMATIVE
            } else {
                FontFixtureNormativeStatus.NORMATIVE
            },
            remainingGate = "Test fixture.",
            diagnostics = if (sourceKind == FontSourceKind.SYSTEM_SCANNED) {
                listOf(
                    FontFixtureManifestDiagnostic(
                        code = "font.source.host-dependent",
                        fixtureId = fixtureId,
                        detail = "Host-scanned fonts are non-normative until bytes are captured into the fixture manifest.",
                    ),
                )
            } else {
                emptyList()
            },
        )
        val typefacePreimage = typefaceIdentityPreimage(
            sourceId = sourcePreimage.sourceId(),
            collectionIndex = 0,
            postScriptName = familyName.replace(" ", "") + "-" + styleName,
            familyName = familyName,
            styleName = styleName,
            outlineFormat = outlineFormat,
            selectedCMap = TypefaceCMapSelection(
                platformId = 3,
                encodingId = 10,
                format = 12,
                language = 0,
                unicode = true,
            ),
            scalerMode = TypefaceScalerMode.OUTLINE,
            tableTags = listOf("cmap", "glyf", "head", "name"),
            diagnostics = typefaceDiagnostics,
        )
        return BundledFontCatalogInput(
            manifestEntry = manifestEntry,
            typefacePreimage = typefacePreimage,
            genericFamilies = genericFamilies,
            scriptCoverage = scriptCoverage,
            localeHints = emptyList(),
            emojiCapable = false,
            colorCapable = false,
        )
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun duplicateFaceCatalogGoldenJson(): String {
        val primary = testCatalogInput(
            fixtureId = "duplicate-face-primary",
            declaredName = "Duplicate Face Sans Regular",
            familyName = "Duplicate Face Sans",
            styleName = "Regular",
            relativePath = "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            contentByte = 0x31,
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Latin"),
        )
        val duplicate = testCatalogInput(
            fixtureId = "duplicate-face-secondary",
            declaredName = "Duplicate Face Sans Regular Alt",
            familyName = "Duplicate Face Sans",
            styleName = "Regular",
            relativePath = "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            contentByte = 0x32,
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Latin"),
        )
        val catalog = BundledFontCatalogBuilder.build(
            generation = 17,
            inputs = listOf(primary, duplicate),
        )
        return catalog.toCanonicalJson().value
    }

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null && !Files.isDirectory(current.resolve("font/fixtures"))) {
            current = current.parent
        }
        return current
    }
}
