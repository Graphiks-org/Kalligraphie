package io.ygdrasil.kalligraphie.font

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FontFixtureManifestTest {
    @Test
    fun `manifest includes minimum M1 M2 fixture families without host normative entries`() {
        val manifest = defaultFontFixtureManifest()
        val entriesById = manifest.entries.associateBy { entry -> entry.fixtureId }

        listOf(
            "single-ttf-liberation-sans",
            "ttc-face-index-planned-generated",
            "otf-cff-source-serif",
            "variable-ttf-roboto-flex",
            "malformed-directory-planned-generated",
            "missing-required-table-planned-generated",
            "system-scanned-host-dependent",
        ).forEach { fixtureId ->
            assertTrue(fixtureId in entriesById, "Missing fixture manifest entry $fixtureId")
        }

        assertEquals("fixture-gated", manifest.dashboardClassification)
        assertFalse(manifest.claimPromotionAllowed)
        assertEquals(emptyList(), manifest.normativeEvidenceDiagnostics())
        assertEquals(
            FontFixtureNormativeStatus.NON_NORMATIVE,
            entriesById.getValue("system-scanned-host-dependent").normativeStatus,
        )
        assertContains(
            entriesById.getValue("single-ttf-liberation-sans").fontSourceReportLabels,
            "bundled-fixture",
        )
        assertContains(
            entriesById.getValue("single-ttf-liberation-sans").typefaceReportLabels,
            "single-face-ttf",
        )
        assertFalse(
            manifest.entries.any { entry ->
                entry.sourceKind == FontSourceKind.SYSTEM_SCANNED &&
                    entry.normativeStatus == FontFixtureNormativeStatus.NORMATIVE
            },
        )
    }

    @Test
    fun `normative bundled fixture hashes match checked in bytes`() {
        val root = projectRoot()
        val manifest = defaultFontFixtureManifest()
        val entriesById = manifest.entries.associateBy { entry -> entry.fixtureId }

        mapOf(
            "single-ttf-liberation-sans" to "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            "otf-cff-source-serif" to "font/fixtures/fonts/scaler/SourceSerif4-Regular.otf",
            "variable-ttf-roboto-flex" to "font/fixtures/fonts/scaler/RobotoFlex-Variable.ttf",
        ).forEach { (fixtureId, relativePath) ->
            val entry = entriesById.getValue(fixtureId)
            val bytes = Files.readAllBytes(root.resolve(relativePath))

            assertEquals(FontFixtureNormativeStatus.NORMATIVE, entry.normativeStatus)
            assertEquals(relativePath, entry.relativePath)
            assertEquals(bytes.sha256HexForTest(), entry.contentSha256)
            assertEquals(bytes.size.toLong(), entry.byteLength)
            assertEquals(1, entry.faceCount)
            assertNotNull(entry.licenseId)
            assertNotNull(entry.licensePath)
            assertNotNull(entry.provenance)
            assertFalse(entry.claimPromotionAllowed)
        }
    }

    @Test
    fun `generated and planned fixtures record generator id provenance and remain non normative until bytes exist`() {
        val manifest = defaultFontFixtureManifest()
        val entriesById = manifest.entries.associateBy { entry -> entry.fixtureId }
        val plannedIds = listOf(
            "ttc-face-index-planned-generated",
            "malformed-directory-planned-generated",
            "missing-required-table-planned-generated",
        )

        plannedIds.forEach { fixtureId ->
            val entry = entriesById.getValue(fixtureId)

            assertEquals(FontSourceKind.GENERATED_FIXTURE, entry.sourceKind)
            assertEquals(FontFixtureNormativeStatus.PLANNED_GENERATED, entry.normativeStatus)
            assertNotNull(entry.generatorId)
            assertTrue(entry.generatorParameters.isNotEmpty())
            assertEquals(null, entry.relativePath)
            assertEquals(null, entry.contentSha256)
            assertEquals(null, entry.byteLength)
            assertContains(entry.diagnostics.map { diagnostic -> diagnostic.code }, "font.fixture.generated-bytes-missing")
            assertNotNull(entry.remainingGate)
            assertFalse(entry.claimPromotionAllowed)
        }
    }

    @Test
    fun `fixture without provenance hash is refused as normative evidence`() {
        val entry = BundledFontFixtureManifestEntry(
            fixtureId = "bad-normative-fixture",
            sourceKind = FontSourceKind.BUNDLED_FIXTURE,
            relativePath = "font/fixtures/fonts/Bad-Regular.ttf",
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = null,
            licensePath = null,
            provenance = null,
            contentSha256 = null,
            byteLength = null,
            faceCount = 1,
            coverageTags = listOf("single-ttf"),
            normativeStatus = FontFixtureNormativeStatus.NORMATIVE,
            remainingGate = null,
        )

        val diagnostics = entry.normativeEvidenceDiagnostics()
        assertContains(diagnostics.map { diagnostic -> diagnostic.code }, "font.fixture.provenance-missing")
        assertContains(
            FontFixtureManifest(entries = listOf(entry)).normativeEvidenceDiagnostics().map { diagnostic -> diagnostic.code },
            "font.fixture.provenance-missing",
        )
    }

    @Test
    fun `generated fixture manifest entries require generator provenance`() {
        assertFailsWith<IllegalArgumentException> {
            BundledFontFixtureManifestEntry(
                fixtureId = "generated-without-generator",
                sourceKind = FontSourceKind.GENERATED_FIXTURE,
                relativePath = null,
                generatorId = null,
                generatorParameters = listOf("faceCount=1"),
                licenseId = "Kanvas-generated-fixture",
                licensePath = null,
                provenance = "Generated fixture without a generator ID.",
                contentSha256 = null,
                byteLength = null,
                faceCount = 1,
                coverageTags = listOf("generated"),
                normativeStatus = FontFixtureNormativeStatus.NON_NORMATIVE,
                remainingGate = "Generator ID is required.",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            BundledFontFixtureManifestEntry(
                fixtureId = "planned-without-parameters",
                sourceKind = FontSourceKind.GENERATED_FIXTURE,
                relativePath = null,
                generatorId = "kfont.fixture.planned.v1",
                generatorParameters = emptyList(),
                licenseId = "Kanvas-generated-fixture",
                licensePath = null,
                provenance = "Generated fixture without source parameters.",
                contentSha256 = null,
                byteLength = null,
                faceCount = 1,
                coverageTags = listOf("generated"),
                normativeStatus = FontFixtureNormativeStatus.PLANNED_GENERATED,
                remainingGate = "Source parameters are required.",
            )
        }
    }

    @Test
    fun `planned generated entries cannot carry captured bytes or bundled source kind`() {
        assertFailsWith<IllegalArgumentException> {
            BundledFontFixtureManifestEntry(
                fixtureId = "planned-bundled-kind",
                sourceKind = FontSourceKind.BUNDLED_FIXTURE,
                relativePath = null,
                generatorId = "kfont.fixture.planned.v1",
                generatorParameters = listOf("faceCount=1"),
                licenseId = "Kanvas-generated-fixture",
                licensePath = null,
                provenance = "Planned generated fixture with the wrong source kind.",
                contentSha256 = null,
                byteLength = null,
                faceCount = 1,
                coverageTags = listOf("generated"),
                normativeStatus = FontFixtureNormativeStatus.PLANNED_GENERATED,
                remainingGate = "Generated source kind is required.",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            BundledFontFixtureManifestEntry(
                fixtureId = "planned-with-bytes",
                sourceKind = FontSourceKind.GENERATED_FIXTURE,
                relativePath = null,
                generatorId = "kfont.fixture.planned.v1",
                generatorParameters = listOf("faceCount=1"),
                licenseId = "Kanvas-generated-fixture",
                licensePath = null,
                provenance = "Planned generated fixture with captured bytes.",
                contentSha256 = "0".repeat(64),
                byteLength = 1,
                faceCount = 1,
                coverageTags = listOf("generated"),
                normativeStatus = FontFixtureNormativeStatus.PLANNED_GENERATED,
                remainingGate = "Captured bytes require a promoted generated fixture row.",
            )
        }
    }

    @Test
    fun `checked in font fixtures manifest matches generated manifest`() {
        val expected = Files.readString(projectRoot().resolve("font/fixtures/expected/pure-kotlin-text/font-fixtures-manifest.json"))
            .replace("reports/font/fixtures", "font/fixtures")

        assertEquals(expected, FontFixtureManifestWriter.writeManifestJson().value)
    }

    @Test
    fun `fixture manifest claim audit keeps support tokens out of generated evidence`() {
        val json = FontFixtureManifestWriter.writeManifestJson().value

        assertContains(json, """"classification":"fixture-gated"""")
        assertContains(json, """"claimPromotionAllowed":false""")
        assertContains(json, "no-parser-support-claim")
        assertContains(json, "no-scaler-support-claim")
        assertContains(json, "no-rendering-support-claim")
        assertContains(json, "no-shaping-support-claim")
        assertContains(json, "no-glyph-support-claim")
        assertContains(json, "no-gpu-support-claim")
        assertContains(json, "no-fallback-support-claim")
        assertContains(json, """"fontSourceReportLabels":["bundled-fixture"]""")
        assertContains(json, """"typefaceReportLabels":["single-face-ttf"]""")

        listOf(
            "parser support",
            "scaler support",
            "rendering support",
            "shaping support",
            "glyph support",
            "GPU support",
            "fallback support",
            "native engine",
        ).forEach { token ->
            assertFalse(json.contains(token), "Fixture manifest leaked support token $token: $json")
        }
    }

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null && !Files.isDirectory(current.resolve("font/fixtures"))) {
            current = current.parent
        }
        return current
    }

    private fun ByteArray.sha256HexForTest(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
