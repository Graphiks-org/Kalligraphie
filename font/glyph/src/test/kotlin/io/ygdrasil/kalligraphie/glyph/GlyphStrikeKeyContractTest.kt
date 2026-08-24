package io.ygdrasil.kalligraphie.glyph

import io.ygdrasil.kalligraphie.font.TypefaceID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class GlyphStrikeKeyContractTest {
    @Test
    fun canonicalPreimageIncludesClusterFingerprintAndRendererDescriptorVersion() {
        val strikeKey = GlyphStrikeKey(
            typefaceId = typefaceId("550e8400-e29b-41d4-a716-446655442001"),
            glyphId = 128,
            clusterFingerprint = GlyphClusterFingerprint(
                sourceUtf16Start = 4,
                sourceUtf16EndExclusive = 8,
                codePointCount = 3,
                graphemeClusterCount = 1,
                clusterSha256 = "0f4c5d1c2f05d2e915e2cc2c2ff9a8f69058f2a965e4af1b71c7f2b1573f1b52",
            ),
            sizePx = 24f,
            scaleX = 1.125f,
            scaleY = 0.875f,
            subpixelX = 0.25f,
            subpixelY = 0.75f,
            variationCoordinates = mapOf("wght" to 0.7f, "opsz" to 24f),
            representationRoute = "text.glyph.color.COLR",
            maskFormat = GlyphStrikeKey.NoMaskFormat,
            transformBucket = "affine.scale-translate",
            edging = "antialias",
            paletteIdentity = "cpal.palette.2",
            unicodeDataVersion = "Unicode-16.0.0",
            rendererDescriptorVersion = "colr-plan.v1",
        )

        assertEquals(
            """
            {
              "schema": "org.graphiks.kanvas.glyph.GlyphStrikeKey.v1",
              "typefaceId": "550e8400-e29b-41d4-a716-446655442001",
              "glyphId": 128,
              "clusterFingerprint": {"sourceUtf16Start": 4, "sourceUtf16EndExclusive": 8, "codePointCount": 3, "graphemeClusterCount": 1, "clusterSha256": "0f4c5d1c2f05d2e915e2cc2c2ff9a8f69058f2a965e4af1b71c7f2b1573f1b52"},
              "sizePx": 24,
              "scaleX": 1.125,
              "scaleY": 0.875,
              "transformBucket": "affine.scale-translate",
              "subpixelBucket": {"x": 0.25, "y": 0.75},
              "route": "text.glyph.color.COLR",
              "maskFormat": "none",
              "edging": "antialias",
              "variationCoordinates": [
                {"axis": "opsz", "value": 24},
                {"axis": "wght", "value": 0.7}
              ],
              "paletteIdentity": "cpal.palette.2",
              "sdf": {"spreadPx": null, "sourceResolutionPx": null},
              "unicodeDataVersion": "Unicode-16.0.0",
              "rendererDescriptorVersion": "colr-plan.v1"
            }
            """.trimIndent() + "\n",
            strikeKey.canonicalPreimage(),
        )
        assertEquals(strikeKey.preimageSha256(glyphId = 128), strikeKey.preimageSha256())

        assertTrue(
            strikeKey.preimageSha256(glyphId = 128) !=
                strikeKey.copy(clusterFingerprint = null).preimageSha256(glyphId = 128),
        )
    }

    @Test
    fun glyphStrikeKeyEvidenceDumpCoversTargetRoutesAndNegativeRefusals() {
        val dump = glyphStrikeKeyEvidenceDump()

        val json = dump.toCanonicalJson()

        assertEquals("glyph-strike-key", dump.dumpId)
        assertEquals(7, dump.routePreimages.size)
        assertEquals(5, dump.refusals.size)
        assertTrue(json.contains(""""route": "text.glyph.bitmap.PNG""""))
        assertTrue(json.contains(""""route": "text.glyph.SVG""""))
        assertTrue(json.contains(""""diagnostic": "text.glyph.LCD-future-research""""))
        assertTrue(json.contains(""""forbiddenFields": ["atlasX", "gpuTextureHandle", "uploadToken"]"""))
        assertEquals(64, dump.dumpSha256.length)
    }

    @Test
    @Suppress("DEPRECATION")
    fun rendererVersionAliasPreservesLegacyConstructionEqualityAndHashCode() {
        val legacy = GlyphStrikeKey(
            typefaceId("550e8400-e29b-41d4-a716-446655442099"),
            18f,
            1f,
            1f,
            0f,
            0f,
            emptyMap(),
            "text.glyph.bitmap.PNG",
            GlyphStrikeKey.NoMaskFormat,
            GlyphStrikeKey.DefaultTransformBucket,
            GlyphStrikeKey.DefaultEdging,
            null,
            null,
            null,
            "Unicode-16.0.0",
            "png-glyph-plan.v1",
        )
        val current = GlyphStrikeKey(
            typefaceId = typefaceId("550e8400-e29b-41d4-a716-446655442099"),
            sizePx = 18f,
            representationRoute = "text.glyph.bitmap.PNG",
            unicodeDataVersion = "Unicode-16.0.0",
            rendererDescriptorVersion = "png-glyph-plan.v1",
        )

        assertEquals("png-glyph-plan.v1", legacy.rendererVersion)
        assertEquals(legacy, current)
        assertEquals(legacy.hashCode(), current.hashCode())
        assertEquals(legacy.canonicalPreimage(glyphId = 45), current.canonicalPreimage(glyphId = 45))
    }

    @Test
    fun glyphStrikeKeyEvidenceDumpSnapshotsMutableInputs() {
        val mutableFixtureIds = mutableListOf("font-source-liberation-core")
        val mutableForbiddenFields = mutableListOf("atlasX")
        val mutableRefusals = mutableListOf(
            GlyphStrikeKeyRefusal(
                glyphId = 49,
                attemptedRoute = "text.glyph.mask.A8",
                diagnostic = "text.glyph.cache-key-nondeterministic",
                reason = "forbidden-live-handle-fields",
                message = "Refused.",
                forbiddenFields = mutableForbiddenFields,
            ),
        )
        val mutableRoutePreimages = mutableListOf(
            strikeKey(glyphId = 41, route = "text.glyph.mask.A8", maskFormat = "A8").toRoutePreimage(),
        )
        val dump = GlyphStrikeKeyEvidenceDump(
            dumpId = "glyph-strike-key",
            ownerTickets = listOf("KFONT-M9-001"),
            fixtureIds = mutableFixtureIds,
            routePreimages = mutableRoutePreimages,
            refusals = mutableRefusals,
            requiredDiagnostics = listOf("text.glyph.cache-key-nondeterministic"),
            nonClaims = listOf("no-a8-rasterization-claim"),
        )
        val initialJson = dump.toCanonicalJson()
        val initialHash = dump.dumpSha256

        mutableFixtureIds += "mutated-fixture-id"
        mutableRoutePreimages += strikeKey(glyphId = 42, route = "text.glyph.outline").toRoutePreimage()
        mutableForbiddenFields += "gpuTextureHandle"

        assertEquals(listOf("font-source-liberation-core"), dump.fixtureIds)
        assertEquals(1, dump.routePreimages.size)
        assertEquals(listOf("atlasX"), dump.refusals.single().forbiddenFields)
        assertEquals(initialJson, dump.toCanonicalJson())
        assertEquals(initialHash, dump.dumpSha256)
    }

    @Test
    fun glyphStrikeKeyGoldenFixtureRecordsRoutesDiagnosticsAndNonClaims() {
        val producedDump = glyphStrikeKeyEvidenceDump()
        val dump = readProjectFile("reports/font/fixtures/expected/glyph/glyph-strike-key.json")

        assertEquals("glyph-strike-key", jsonStringField(dump, "dumpId"))
        assertEquals(producedDump.dumpSha256, jsonStringField(dump, "dumpSha256"))
        assertEquals(listOf("KFONT-M9-001"), jsonStringArrayField(dump, "ownerTickets"))
        assertEquals(listOf("font-source-liberation-core"), jsonStringArrayField(dump, "fixtureIds"))
        assertEquals(
            listOf(
                "text.glyph.cache-key-nondeterministic",
                "text.glyph.LCD-future-research",
            ),
            jsonStringArrayField(dump, "requiredDiagnostics"),
        )
        assertEquals(
            listOf(
                "no-a8-rasterization-claim",
                "no-sdf-generation-claim",
                "no-atlas-packing-claim",
                "no-gpu-text-route-claim",
                "no-color-emoji-rendering-claim",
                "no-lcd-support-claim",
                "no-dftext-retirement",
            ),
            jsonStringArrayField(dump, "nonClaims"),
        )
        assertTrue(dump.contains(""""route": "text.glyph.color.COLR""""))
        assertTrue(dump.contains(""""route": "text.glyph.bitmap.PNG""""))
        assertTrue(dump.contains(""""route": "text.glyph.SVG""""))
        assertTrue(dump.contains(""""clusterFingerprint": {"""))
        assertEquals(7, Regex(""""compactHash": "[0-9a-f]{64}"""").findAll(dump).count())
        listOf(
            strikeKey(glyphId = 41, route = "text.glyph.mask.A8", maskFormat = "A8").toRoutePreimage(),
            strikeKey(glyphId = 42, route = "text.glyph.mask.SDF", maskFormat = "R8Unorm", sdfSpreadPx = 8f)
                .toRoutePreimage(),
            strikeKey(glyphId = 43, route = "text.glyph.outline").toRoutePreimage(),
            strikeKey(
                glyphId = 44,
                route = "text.glyph.color.COLR",
                paletteIdentity = "cpal.palette.1",
                rendererDescriptorVersion = "colr-plan.v1",
                variationCoordinates = mapOf("wght" to 0.7f),
            ).toRoutePreimage(),
            strikeKey(glyphId = 45, route = "text.glyph.bitmap.PNG", rendererDescriptorVersion = "png-glyph-plan.v1")
                .toRoutePreimage(),
            strikeKey(glyphId = 46, route = "text.glyph.SVG", rendererDescriptorVersion = "svg-glyph-plan.v1")
                .toRoutePreimage(),
            unicodeSensitiveStrikeKey(glyphId = 0, route = "text.glyph.unsupported").toRoutePreimage(),
        ).forEach { routePreimage ->
            assertTrue(dump.contains(""""route": "${routePreimage.route}""""))
            assertTrue(dump.contains(""""compactHash": "${routePreimage.compactHash}""""))
        }
        assertEquals(producedDump.toCanonicalJson(), dump)
    }

    private fun glyphStrikeKeyEvidenceDump(): GlyphStrikeKeyEvidenceDump =
        GlyphStrikeKeyEvidenceDump(
            dumpId = "glyph-strike-key",
            ownerTickets = listOf("KFONT-M9-001"),
            fixtureIds = listOf("font-source-liberation-core"),
            routePreimages = listOf(
                strikeKey(glyphId = 41, route = "text.glyph.mask.A8", maskFormat = "A8").toRoutePreimage(),
                strikeKey(glyphId = 42, route = "text.glyph.mask.SDF", maskFormat = "R8Unorm", sdfSpreadPx = 8f)
                    .toRoutePreimage(),
                strikeKey(glyphId = 43, route = "text.glyph.outline").toRoutePreimage(),
                strikeKey(
                    glyphId = 44,
                    route = "text.glyph.color.COLR",
                    paletteIdentity = "cpal.palette.1",
                    rendererDescriptorVersion = "colr-plan.v1",
                    variationCoordinates = mapOf("wght" to 0.7f),
                ).toRoutePreimage(),
                strikeKey(glyphId = 45, route = "text.glyph.bitmap.PNG", rendererDescriptorVersion = "png-glyph-plan.v1")
                    .toRoutePreimage(),
                strikeKey(glyphId = 46, route = "text.glyph.SVG", rendererDescriptorVersion = "svg-glyph-plan.v1")
                    .toRoutePreimage(),
                unicodeSensitiveStrikeKey(glyphId = 0, route = "text.glyph.unsupported").toRoutePreimage(),
            ),
            refusals = listOf(
                GlyphStrikeKeyRefusal.missingTypefaceId(glyphId = 47, attemptedRoute = "text.glyph.mask.A8"),
                GlyphStrikeKeyRefusal.nondeterministicHostSource(
                    glyphId = 48,
                    attemptedRoute = "text.glyph.outline",
                    hostSource = "system-font-scan",
                ),
                GlyphStrikeKeyRefusal.forbiddenLiveHandleFields(
                    glyphId = 49,
                    attemptedRoute = "text.glyph.mask.A8",
                    forbiddenFields = listOf("atlasX", "gpuTextureHandle", "uploadToken"),
                ),
                GlyphStrikeKeyRefusal.lcdFutureResearch(
                    glyphId = 50,
                    attemptedRoute = "text.glyph.mask.LCD",
                    fallbackRoute = "text.glyph.mask.A8",
                ),
                GlyphStrikeKeyRefusal.routeKeyGap(
                    glyphId = 51,
                    attemptedRoute = "text.glyph.bitmap.PNG",
                    missingFields = listOf("rendererDescriptorVersion"),
                ),
            ),
            requiredDiagnostics = listOf(
                "text.glyph.cache-key-nondeterministic",
                "text.glyph.LCD-future-research",
            ),
            nonClaims = listOf(
                "no-a8-rasterization-claim",
                "no-sdf-generation-claim",
                "no-atlas-packing-claim",
                "no-gpu-text-route-claim",
                "no-color-emoji-rendering-claim",
                "no-lcd-support-claim",
                "no-dftext-retirement",
            ),
        )

    private fun strikeKey(
        glyphId: Int,
        route: String,
        maskFormat: String = GlyphStrikeKey.NoMaskFormat,
        sdfSpreadPx: Float? = null,
        paletteIdentity: String? = null,
        rendererDescriptorVersion: String? = null,
        variationCoordinates: Map<String, Float> = emptyMap(),
    ): GlyphStrikeKey =
        GlyphStrikeKey(
            typefaceId = typefaceId("550e8400-e29b-41d4-a716-446655442010"),
            glyphId = glyphId,
            clusterFingerprint = GlyphClusterFingerprint(
                sourceUtf16Start = 0,
                sourceUtf16EndExclusive = 1,
                codePointCount = 1,
                graphemeClusterCount = 1,
                clusterSha256 = "559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd",
            ),
            sizePx = 18f,
            variationCoordinates = variationCoordinates,
            representationRoute = route,
            maskFormat = maskFormat,
            sdfSpreadPx = sdfSpreadPx,
            sdfSourceResolutionPx = sdfSpreadPx?.let { 18f },
            paletteIdentity = paletteIdentity,
            unicodeDataVersion = "Unicode-16.0.0",
            rendererDescriptorVersion = rendererDescriptorVersion,
        )

    private fun unicodeSensitiveStrikeKey(glyphId: Int, route: String): GlyphStrikeKey =
        GlyphStrikeKey(
            typefaceId = typefaceId("550e8400-e29b-41d4-a716-446655442010"),
            glyphId = glyphId,
            clusterFingerprint = GlyphClusterFingerprint(
                sourceUtf16Start = 0,
                sourceUtf16EndExclusive = 8,
                codePointCount = 3,
                graphemeClusterCount = 1,
                clusterSha256 = "0f4c5d1c2f05d2e915e2cc2c2ff9a8f69058f2a965e4af1b71c7f2b1573f1b52",
            ),
            sizePx = 18f,
            representationRoute = route,
            unicodeDataVersion = "Unicode-16.0.0",
        )

    private fun typefaceId(uuid: String): TypefaceID =
        TypefaceID(Uuid.parse(uuid))

    private fun readProjectFile(relativePath: String): String =
        Files.readString(
            kanvasProjectRoot().resolve(
                relativePath.replace("reports/font/fixtures", "font/fixtures"),
            ),
        )

    private fun kanvasProjectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null) {
            if (Files.isDirectory(current.resolve("font/fixtures"))) {
                return current
            }
            current = current.parent
        }
        error("Unable to locate Kanvas project root from ${Path.of("").toAbsolutePath()}")
    }

    private fun jsonStringField(json: String, field: String): String {
        val pattern = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"([^\"]+)\"")
        return pattern.find(json)?.groupValues?.get(1)
            ?: error("Missing JSON string field $field")
    }

    private fun jsonStringArrayField(json: String, field: String): List<String> {
        val pattern = Regex(
            "\"" + Regex.escape(field) + "\"\\s*:\\s*\\[(.*?)\\]",
            RegexOption.DOT_MATCHES_ALL,
        )
        val body = pattern.find(json)?.groupValues?.get(1)
            ?: error("Missing JSON array field $field")
        return Regex("\"([^\"]+)\"").findAll(body).map { match -> match.groupValues[1] }.toList()
    }
}
