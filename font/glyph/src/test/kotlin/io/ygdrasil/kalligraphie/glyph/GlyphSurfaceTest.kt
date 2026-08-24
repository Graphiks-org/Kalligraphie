package io.ygdrasil.kalligraphie.glyph

import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Verifies that the pure Kotlin glyph package exposes the planned public surface.
 */
class GlyphSurfaceTest {
    /**
     * References every top-level glyph pipeline type so the module fails to compile until the
     * surface exists.
     */
    @Test
    fun exposesGlyphPipelineSurface() {
        val names = listOf(
            ShapedGlyphRun::class.simpleName,
            GlyphStrikeKey::class.simpleName,
            GlyphRepresentation::class.simpleName,
            GlyphArtifactPlanner::class.simpleName,
            GlyphArtifactPlan::class.simpleName,
            GlyphArtifactPlanEvidenceDump::class.simpleName,
            GlyphArtifactPlanRef::class.simpleName,
            ColorGlyphPlanRef::class.simpleName,
            BitmapGlyphPlanRef::class.simpleName,
            SVGGlyphPlanRef::class.simpleName,
            GlyphArtifactRoutePolicyInputs::class.simpleName,
            OutlineGlyphRepresentation::class.simpleName,
            GlyphMaskGenerator::class.simpleName,
            A8GlyphMask::class.simpleName,
            SDFGlyphGenerator::class.simpleName,
            SDFGlyphMask::class.simpleName,
            GlyphAtlasPacker::class.simpleName,
            GlyphAtlasArtifactBuilder::class.simpleName,
            SDFGlyphAtlasArtifactBuilder::class.simpleName,
            GlyphCache::class.simpleName,
            GlyphCacheBudget::class.simpleName,
            GlyphRunCacheBuildGlyph::class.simpleName,
            GlyphRunCacheInventory::class.simpleName,
            GlyphRunCacheInventoryItem::class.simpleName,
            GlyphCacheRecord::class.simpleName,
            GlyphCacheInventoryEntry::class.simpleName,
            GlyphCacheInventoryDump::class.simpleName,
            GlyphCacheTelemetryMetadata::class.simpleName,
            GlyphCacheRouteCount::class.simpleName,
            GlyphCacheTimingStats::class.simpleName,
            GlyphCacheBudgetRefusal::class.simpleName,
            GlyphCacheTelemetrySample::class.simpleName,
            GlyphCacheTelemetryDump::class.simpleName,
            GlyphArtifactMetricsSample::class.simpleName,
            GlyphArtifactMetricsDump::class.simpleName,
            GlyphCacheMetricsSample::class.simpleName,
            GlyphCacheMetricsDump::class.simpleName,
            GlyphAtlasOccupancyEntry::class.simpleName,
            GlyphAtlasOccupancyDump::class.simpleName,
            GlyphMaskSummary::class.simpleName,
            GlyphRouteDiagnostic::class.simpleName,
            GlyphArtifactPlanDecision::class.simpleName,
            GlyphArtifactRouteRejection::class.simpleName,
            GlyphAtlasPackingResult::class.simpleName,
            A8GlyphMaskArtifactEvidence::class.simpleName,
            A8GlyphMaskEvidenceDump::class.simpleName,
            GlyphAtlasArtifactEvidence::class.simpleName,
            GlyphAtlasArtifactEvidenceDump::class.simpleName,
            GlyphAtlasEvictionTrace::class.simpleName,
        )

        assertEquals(
            listOf(
                "ShapedGlyphRun",
                "GlyphStrikeKey",
                "GlyphRepresentation",
                "GlyphArtifactPlanner",
                "GlyphArtifactPlan",
                "GlyphArtifactPlanEvidenceDump",
                "GlyphArtifactPlanRef",
                "ColorGlyphPlanRef",
                "BitmapGlyphPlanRef",
                "SVGGlyphPlanRef",
                "GlyphArtifactRoutePolicyInputs",
                "OutlineGlyphRepresentation",
                "GlyphMaskGenerator",
                "A8GlyphMask",
                "SDFGlyphGenerator",
                "SDFGlyphMask",
                "GlyphAtlasPacker",
                "GlyphAtlasArtifactBuilder",
                "SDFGlyphAtlasArtifactBuilder",
                "GlyphCache",
                "GlyphCacheBudget",
                "GlyphRunCacheBuildGlyph",
                "GlyphRunCacheInventory",
                "GlyphRunCacheInventoryItem",
                "GlyphCacheRecord",
                "GlyphCacheInventoryEntry",
                "GlyphCacheInventoryDump",
                "GlyphCacheTelemetryMetadata",
                "GlyphCacheRouteCount",
                "GlyphCacheTimingStats",
                "GlyphCacheBudgetRefusal",
                "GlyphCacheTelemetrySample",
                "GlyphCacheTelemetryDump",
                "GlyphArtifactMetricsSample",
                "GlyphArtifactMetricsDump",
                "GlyphCacheMetricsSample",
                "GlyphCacheMetricsDump",
                "GlyphAtlasOccupancyEntry",
                "GlyphAtlasOccupancyDump",
                "GlyphMaskSummary",
                "GlyphRouteDiagnostic",
                "GlyphArtifactPlanDecision",
                "GlyphArtifactRouteRejection",
                "GlyphAtlasPackingResult",
                "A8GlyphMaskArtifactEvidence",
                "A8GlyphMaskEvidenceDump",
                "GlyphAtlasArtifactEvidence",
                "GlyphAtlasArtifactEvidenceDump",
                "GlyphAtlasEvictionTrace",
            ),
            names,
        )
    }

    @Test
    fun glyphMaskGeneratorRasterizesClosedRectangleToDeterministicA8Mask() {
        val generator = object : GlyphMaskGenerator {}
        val outline = OutlineGlyphRepresentation(
            glyphId = 50,
            pathCommands = listOf(
                "M 1 2",
                "L 4 2",
                "L 4 5",
                "L 1 5",
                "Z",
            ),
        )

        val mask = generator.generate(
            outline = outline,
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441041"),
        )

        assertEquals(50, mask.glyphId)
        assertEquals(3, mask.width)
        assertEquals(3, mask.height)
        assertEquals(1, mask.left)
        assertEquals(2, mask.top)
        assertEquals(3, mask.rowBytes)
        assertEquals(
            listOf(
                255, 255, 255,
                255, 255, 255,
                255, 255, 255,
            ),
            mask.pixels,
        )
    }

    @Test
    fun glyphMaskGeneratorReturnsEmptyA8MaskForEmptyOutline() {
        val generator = object : GlyphMaskGenerator {}

        val mask = generator.generate(
            outline = OutlineGlyphRepresentation(glyphId = 51),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441042"),
        )

        assertEquals(
            A8GlyphMask(glyphId = 51, width = 0, height = 0, pixels = emptyList()),
            mask.copy(diagnostics = emptyList(), sourceOutlineSha256 = null),
        )
        assertEquals(64, mask.sourceOutlineSha256?.length)
        assertEquals(1, mask.diagnostics.size)
        assertEquals("text.glyph.A8-generation-failed", mask.diagnostics.single().route)
        assertEquals("info", mask.diagnostics.single().severity)
        assertTrue(mask.diagnostics.single().message.contains("reason=empty-outline"))
    }

    @Test
    fun glyphMaskGeneratorRasterizesClosedQuadraticContourToDeterministicA8Mask() {
        val generator = object : GlyphMaskGenerator {}
        val outline = OutlineGlyphRepresentation(
            glyphId = 54,
            pathCommands = listOf(
                "M 1 2",
                "Q 2.5 2 4 2",
                "Q 4 3.5 4 5",
                "Q 2.5 5 1 5",
                "Q 1 3.5 1 2",
                "Z",
            ),
        )

        val mask = generator.generate(
            outline = outline,
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441080"),
        )

        assertEquals(54, mask.glyphId)
        assertEquals(3, mask.width)
        assertEquals(3, mask.height)
        assertEquals(1, mask.left)
        assertEquals(2, mask.top)
        assertEquals(3, mask.rowBytes)
        assertEquals(64, mask.sourceOutlineSha256?.length)
        assertEquals(emptyList(), mask.diagnostics)
        assertEquals(
            listOf(
                255, 255, 255,
                255, 255, 255,
                255, 255, 255,
            ),
            mask.pixels,
        )
    }

    @Test
    fun glyphMaskGeneratorRasterizesClosedCubicContourToDeterministicA8Mask() {
        val generator = object : GlyphMaskGenerator {}
        val outline = OutlineGlyphRepresentation(
            glyphId = 55,
            pathCommands = listOf(
                "M 1 2",
                "C 2 2 3 2 4 2",
                "C 4 3 4 4 4 5",
                "C 3 5 2 5 1 5",
                "C 1 4 1 3 1 2",
                "Z",
            ),
        )

        val mask = generator.generate(
            outline = outline,
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441081"),
        )

        assertEquals(55, mask.glyphId)
        assertEquals(3, mask.width)
        assertEquals(3, mask.height)
        assertEquals(1, mask.left)
        assertEquals(2, mask.top)
        assertEquals(3, mask.rowBytes)
        assertEquals(64, mask.sourceOutlineSha256?.length)
        assertEquals(emptyList(), mask.diagnostics)
        assertEquals(
            listOf(
                255, 255, 255,
                255, 255, 255,
                255, 255, 255,
            ),
            mask.pixels,
        )
    }

    @Test
    fun glyphMaskGeneratorReturnsStableDiagnosticForUnsupportedWindingRule() {
        val generator = object : GlyphMaskGenerator {}
        val strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441082")
        val outline = OutlineGlyphRepresentation(
            glyphId = 56,
            pathCommands = listOf(
                "M 1 2",
                "L 4 2",
                "L 4 5",
                "L 1 5",
                "Z",
            ),
            windingRule = "evenOdd",
        )

        val mask = generator.generate(outline = outline, strikeKey = strikeKey)

        assertEquals(A8GlyphMask(glyphId = 56, width = 0, height = 0, pixels = emptyList()), mask.copy(
            diagnostics = emptyList(),
            sourceOutlineSha256 = null,
        ))
        assertEquals(64, mask.sourceOutlineSha256?.length)
        assertEquals(1, mask.diagnostics.size)
        assertEquals(56, mask.diagnostics.single().glyphId)
        assertEquals("text.glyph.A8-generation-failed", mask.diagnostics.single().route)
        assertEquals("warning", mask.diagnostics.single().severity)
        assertTrue(mask.diagnostics.single().message.contains("reason=unsupported-winding-rule"))
        assertTrue(mask.diagnostics.single().message.contains(
            strikeKey.copy(
                representationRoute = "text.glyph.mask.A8",
                maskFormat = "A8",
            ).preimageSha256(glyphId = 56),
        ))
    }

    @Test
    fun glyphMaskGeneratorReturnsStableDiagnosticForMalformedContour() {
        val generator = object : GlyphMaskGenerator {}
        val strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441083")
        val outline = OutlineGlyphRepresentation(
            glyphId = 57,
            pathCommands = listOf("L 1 2"),
        )

        val mask = generator.generate(outline = outline, strikeKey = strikeKey)

        assertEquals(A8GlyphMask(glyphId = 57, width = 0, height = 0, pixels = emptyList()), mask.copy(
            diagnostics = emptyList(),
            sourceOutlineSha256 = null,
        ))
        assertEquals(64, mask.sourceOutlineSha256?.length)
        assertEquals(1, mask.diagnostics.size)
        assertEquals(57, mask.diagnostics.single().glyphId)
        assertEquals("text.glyph.A8-generation-failed", mask.diagnostics.single().route)
        assertEquals("warning", mask.diagnostics.single().severity)
        assertTrue(mask.diagnostics.single().message.contains("reason=malformed-outline"))
        assertTrue(mask.diagnostics.single().message.contains(
            strikeKey.copy(
                representationRoute = "text.glyph.mask.A8",
                maskFormat = "A8",
            ).preimageSha256(glyphId = 57),
        ))
    }

    @Test
    fun glyphMaskGeneratorReturnsStableDiagnosticForCoverageOverflow() {
        val generator = object : GlyphMaskGenerator {}
        val strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441090")
        val outline = OutlineGlyphRepresentation(
            glyphId = 58,
            pathCommands = listOf(
                "M 0 0",
                "L 5000 0",
                "L 5000 5000",
                "L 0 5000",
                "Z",
            ),
        )

        val mask = generator.generate(outline = outline, strikeKey = strikeKey)

        assertEquals(
            A8GlyphMask(glyphId = 58, width = 0, height = 0, pixels = emptyList()),
            mask.copy(diagnostics = emptyList(), sourceOutlineSha256 = null),
        )
        assertEquals(64, mask.sourceOutlineSha256?.length)
        assertEquals(1, mask.diagnostics.size)
        assertEquals(58, mask.diagnostics.single().glyphId)
        assertEquals("text.glyph.A8-generation-failed", mask.diagnostics.single().route)
        assertEquals("warning", mask.diagnostics.single().severity)
        assertTrue(mask.diagnostics.single().message.contains("reason=coverage-overflow"))
        assertTrue(mask.diagnostics.single().message.contains(
            strikeKey.copy(
                representationRoute = "text.glyph.mask.A8",
                maskFormat = "A8",
            ).preimageSha256(glyphId = 58),
        ))
    }

    @Test
    fun a8GlyphMaskArtifactEvidenceRecordsBoundsAndCoverageHash() {
        val mask = A8GlyphMask(
            glyphId = 70,
            width = 2,
            height = 2,
            left = -1,
            top = 3,
            rowBytes = 3,
            sourceOutlineSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            pixels = listOf(
                0, 7, 99,
                255, 0, 99,
            ),
            diagnostics = listOf(
                GlyphRouteDiagnostic(
                    glyphId = 70,
                    route = "text.glyph.A8-generation-failed",
                    message = "Synthetic diagnostic snapshot.",
                    severity = "warning",
                ),
            ),
        )

        val evidence = A8GlyphMaskArtifactEvidence.from(
            mask = mask,
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441072"),
        )

        assertEquals(70, evidence.glyphId)
        assertEquals(4, evidence.addressablePixelCount)
        assertEquals(2, evidence.nonZeroPixels)
        assertEquals("79ef38e8384dc02cd1de6202a09cab298e2bac8148fff30a3c437f87e63956eb", evidence.coverageSha256)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", evidence.sourceOutlineSha256)
        assertEquals(mask.diagnostics, evidence.diagnostics)
        assertEquals(64, evidence.strikeKeySha256.length)
        assertEquals(64, evidence.dumpSha256.length)
        assertEquals(
            """
            {
              "schema": "org.graphiks.kanvas.glyph.A8GlyphMaskArtifactEvidence.v1",
              "glyphId": 70,
              "strikeKeySha256": "${evidence.strikeKeySha256}",
              "bounds": {"left": -1, "top": 3, "width": 2, "height": 2},
              "rowBytes": 3,
              "addressablePixelCount": 4,
              "nonZeroPixels": 2,
              "coverageSha256": "79ef38e8384dc02cd1de6202a09cab298e2bac8148fff30a3c437f87e63956eb",
              "sourceOutlineSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "diagnostics": [
                {
                  "glyphId": 70,
                  "route": "text.glyph.A8-generation-failed",
                  "severity": "warning",
                  "message": "Synthetic diagnostic snapshot."
                }
              ],
              "dumpSha256": "${evidence.dumpSha256}"
            }
            """.trimIndent() + "\n",
            evidence.toCanonicalJson(),
        )
    }

    @Test
    fun sdfGlyphArtifactEvidenceRecordsSpreadPaddingBoundsAndCoverageHash() {
        val mask = SDFGlyphMask(
            glyphId = 78,
            width = 3,
            height = 2,
            left = -1,
            top = 2,
            distanceRange = 16f,
            pixels = listOf(
                96, 128, 160,
                80, 128, 176,
            ),
            sourceOutlineSha256 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
        )

        val evidence = SDFGlyphArtifactEvidence.from(
            mask = mask,
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441093").copy(
                representationRoute = "text.glyph.mask.SDF",
                maskFormat = "R8Unorm",
                sdfSpreadPx = 16f,
                sdfSourceResolutionPx = 24f,
            ),
        )

        assertEquals(78, evidence.glyphId)
        assertEquals(-1, evidence.left)
        assertEquals(2, evidence.top)
        assertEquals(3, evidence.width)
        assertEquals(2, evidence.height)
        assertEquals(16f, evidence.spreadPx)
        assertEquals(24f, evidence.sourceResolutionPx)
        assertEquals(17, evidence.atlasPaddingPx)
        assertEquals("R8Unorm", evidence.format)
        assertEquals("sdf-normalization-r8unorm-v1", evidence.normalizationFormulaVersion)
        assertEquals(6, evidence.addressablePixelCount)
        assertEquals("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", evidence.sourceOutlineSha256)
        assertEquals(64, evidence.strikeKeySha256.length)
        assertEquals(64, evidence.distanceFieldSha256.length)

        val dump = evidence.toCanonicalJson()
        assertTrue(dump.contains(""""spreadPx": 16"""))
        assertTrue(dump.contains(""""atlasPaddingPx": 17"""))
        assertTrue(dump.contains(""""normalizationFormulaVersion": "sdf-normalization-r8unorm-v1""""))
        assertTrue(dump.contains(""""sourceOutlineSha256": "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210""""))
    }

    @Test
    fun sdfGlyphArtifactEvidenceDumpMatchesRepoFixture() {
        val generator = object : SDFGlyphGenerator {}
        val defaultOutline = OutlineGlyphRepresentation(
            glyphId = 79,
            pathCommands = listOf(
                "M 1 1",
                "L 5 1",
                "L 5 5",
                "L 1 5",
                "Z",
            ),
        )
        val widenedOutline = defaultOutline.copy(glyphId = 80)

        val defaultStrikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441094").copy(
            representationRoute = "text.glyph.mask.SDF",
            maskFormat = "R8Unorm",
        )
        val widenedStrikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441095").copy(
            representationRoute = "text.glyph.mask.SDF",
            maskFormat = "R8Unorm",
            sdfSpreadPx = 16f,
            sdfSourceResolutionPx = 24f,
        )

        val dump = SDFGlyphArtifactEvidenceDump(
            dumpId = "sdf-glyph-artifact",
            ownerTickets = listOf("KFONT-M9-004"),
            fixtureIds = listOf(
                "sdf-default-spread",
                "sdf-wide-spread",
            ),
            artifacts = listOf(
                SDFGlyphArtifactEvidence.from(
                    mask = generator.generate(defaultOutline, defaultStrikeKey),
                    strikeKey = defaultStrikeKey,
                ),
                SDFGlyphArtifactEvidence.from(
                    mask = generator.generate(widenedOutline, widenedStrikeKey),
                    strikeKey = widenedStrikeKey,
                ),
            ),
            requiredDiagnostics = listOf(
                "text.glyph.SDF-generation-failed",
                "text.glyph.SDF-transform-unsupported",
            ),
            nonClaims = listOf(
                "producer-only",
                "no-gpu-text-route-claim",
                "no-dftext-retirement",
            ),
        )

        assertEquals(
            readProjectFile("reports/font/fixtures/expected/glyph/sdf-glyph-artifact.json"),
            dump.toCanonicalJson(),
        )
    }

    @Test
    fun sdfGlyphGeneratorBuildsDeterministicSignedDistanceMaskForClosedRectangle() {
        val generator = object : SDFGlyphGenerator {}
        val outline = OutlineGlyphRepresentation(
            glyphId = 52,
            pathCommands = listOf(
                "M 1 1",
                "L 5 1",
                "L 5 5",
                "L 1 5",
                "Z",
            ),
        )

        val mask = generator.generate(
            outline = outline,
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441043"),
        )

        assertEquals(52, mask.glyphId)
        assertEquals(6, mask.width)
        assertEquals(6, mask.height)
        assertEquals(8f, mask.distanceRange)
        assertEquals(36, mask.pixels.size)
        assertTrue(mask.pixels.any { sample -> sample != 0 })

        val outside = mask.pixels[0]
        val edge = mask.pixels[1 * mask.width + 1]
        val center = mask.pixels[3 * mask.width + 3]

        assertTrue(outside < edge, "Outside samples should encode a lower signed distance than edge samples.")
        assertTrue(edge in 120..136, "Edge samples should stay near the 128 SDF midpoint.")
        assertTrue(center > edge, "Interior samples should encode a higher signed distance than edge samples.")
    }

    @Test
    fun sdfGlyphGeneratorReturnsEmptyMaskForEmptyOutline() {
        val generator = object : SDFGlyphGenerator {}

        val mask = generator.generate(
            outline = OutlineGlyphRepresentation(glyphId = 53),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441044"),
        )

        assertEquals(
            SDFGlyphMask(glyphId = 53, width = 0, height = 0, distanceRange = 8f, pixels = emptyList()),
            mask.copy(sourceOutlineSha256 = null),
        )
        assertEquals(64, mask.sourceOutlineSha256?.length)
    }

    @Test
    fun sdfGlyphGeneratorRespectsNonDefaultSpreadFromStrikeKey() {
        val generator = object : SDFGlyphGenerator {}
        val outline = OutlineGlyphRepresentation(
            glyphId = 58,
            pathCommands = listOf(
                "M 1 1",
                "L 5 1",
                "L 5 5",
                "L 1 5",
                "Z",
            ),
        )

        val defaultMask = generator.generate(
            outline = outline,
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441090"),
        )
        val widenedSpreadMask = generator.generate(
            outline = outline,
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441091").copy(
                sdfSpreadPx = 16f,
                sdfSourceResolutionPx = 16f,
                maskFormat = "R8Unorm",
                representationRoute = "text.glyph.mask.SDF",
            ),
        )

        assertEquals(8f, defaultMask.distanceRange)
        assertEquals(16f, widenedSpreadMask.distanceRange)
        assertTrue(defaultMask.pixels != widenedSpreadMask.pixels)
        assertTrue(
            widenedSpreadMask.pixels[3 * widenedSpreadMask.width + 3] < defaultMask.pixels[3 * defaultMask.width + 3],
            "Larger spread should flatten normalized interior distances in the SDF mask.",
        )
    }

    @Test
    fun sdfGlyphGeneratorRejectsNonClosedOutlinePath() {
        val generator = object : SDFGlyphGenerator {}

        val failure = assertFailsWith<IllegalArgumentException> {
            generator.generate(
                outline = OutlineGlyphRepresentation(
                    glyphId = 59,
                    pathCommands = listOf(
                        "M 1 1",
                        "L 5 1",
                        "L 5 5",
                        "L 1 5",
                    ),
                ),
                strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441092"),
            )
        }

        assertTrue(failure.message.orEmpty().contains("closed"))
    }

    @Test
    fun rowPackerPlacesMasksDeterministicallyWithPaddingAndRowWraps() {
        val packer = RowGlyphAtlasPacker(atlasWidth = 8, padding = 1)

        val placements = packer.pack(
            listOf(
                a8Mask(glyphId = 10, width = 3, height = 2),
                a8Mask(glyphId = 11, width = 2, height = 4),
                a8Mask(glyphId = 12, width = 1, height = 1),
            ),
        )

        assertEquals(
            listOf(
                GlyphAtlasPlacement(glyphId = 10, x = 1, y = 1, width = 3, height = 2),
                GlyphAtlasPlacement(glyphId = 11, x = 5, y = 1, width = 2, height = 4),
                GlyphAtlasPlacement(glyphId = 12, x = 1, y = 6, width = 1, height = 1),
            ),
            placements,
        )
    }

    @Test
    fun rowPackerStartsSecondRowAfterTallestFirstRowMask() {
        val padding = 1
        val firstRowHeight = 4

        val placements = RowGlyphAtlasPacker(atlasWidth = 8, padding = padding).pack(
            listOf(
                a8Mask(glyphId = 10, width = 3, height = 2),
                a8Mask(glyphId = 11, width = 2, height = firstRowHeight),
                a8Mask(glyphId = 12, width = 1, height = 1),
            ),
        )

        val firstGlyphOnSecondRow = placements[2]
        assertEquals(padding, firstGlyphOnSecondRow.x)
        assertEquals(padding + firstRowHeight + padding, firstGlyphOnSecondRow.y)
    }

    @Test
    fun rowPackerWrapsAtIntMaximumWidthWithoutPlacingOutsideAtlas() {
        val atlasWidth = Int.MAX_VALUE
        val placements = RowGlyphAtlasPacker(atlasWidth = atlasWidth, padding = 0).pack(
            listOf(
                a8Mask(glyphId = 70, width = Int.MAX_VALUE, height = 0),
                a8Mask(glyphId = 71, width = 1, height = 0),
            ),
        )

        assertEquals(0, placements[1].x)
        assertTrue(
            placements.all { placement ->
                placement.x.toLong() + placement.width.toLong() <= atlasWidth.toLong()
            },
        )
    }

    @Test
    fun rowPackerRejectsDegenerateRowsThatOverflowTheVerticalCursor() {
        val failure = assertFailsWith<IllegalArgumentException> {
            RowGlyphAtlasPacker(atlasWidth = 1, padding = 0).pack(
                listOf(
                    a8Mask(glyphId = 72, width = 0, height = Int.MAX_VALUE),
                    a8Mask(glyphId = 73, width = 1, height = 0),
                    a8Mask(glyphId = 74, width = 0, height = Int.MAX_VALUE),
                    a8Mask(glyphId = 75, width = 1, height = 0),
                    a8Mask(glyphId = 76, width = 0, height = Int.MAX_VALUE),
                    a8Mask(glyphId = 77, width = 1, height = 0),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("vertical"))
    }

    @Test
    fun rowPackerPreservesDegenerateMaskPlacements() {
        val placements = RowGlyphAtlasPacker(atlasWidth = 8, padding = 1).pack(
            listOf(
                a8Mask(glyphId = 20, width = 0, height = 0),
                a8Mask(glyphId = 21, width = 0, height = 2),
                a8Mask(glyphId = 22, width = 3, height = 0),
            ),
        )

        assertEquals(listOf(0, 0, 3), placements.map { it.width })
        assertEquals(listOf(0, 2, 0), placements.map { it.height })
    }

    @Test
    fun rowPackerReportsCapacityDiagnosticWithoutPartialPlacements() {
        val packer = RowGlyphAtlasPacker(atlasWidth = 4, padding = 1)

        val result = packer.packWithDiagnostics(
            listOf(
                a8Mask(glyphId = 60, width = 2, height = 1),
                a8Mask(glyphId = 61, width = 3, height = 1),
            ),
        )

        assertEquals(emptyList(), result.placements)
        assertEquals(1, result.diagnostics.size)
        assertEquals(61, result.diagnostics.single().glyphId)
        assertEquals("text.glyph.atlas-capacity-exceeded", result.diagnostics.single().route)
        assertTrue(result.diagnostics.single().message.contains("atlas width 4"))
        assertEquals(64, result.dumpSha256.length)
        assertEquals(
            """
            {
              "schema": "org.graphiks.kanvas.glyph.GlyphAtlasPackingResult.v1",
              "placementCount": 0,
              "diagnosticCount": 1,
              "placements": [],
              "diagnostics": [
                {
                  "glyphId": 61,
                  "route": "text.glyph.atlas-capacity-exceeded",
                  "severity": "warning",
                  "message": "Glyph 61 width plus padding (5) exceeds atlas width 4; refusing atlas pack without partial placements."
                }
              ],
              "dumpSha256": "${result.dumpSha256}"
            }
            """.trimIndent() + "\n",
            result.toCanonicalGlyphAtlasPackingJson(),
        )
    }

    @Test
    fun a8AtlasBuilderComposesMaskPixelsUsingRowBytes() {
        val builder = KotlinGlyphAtlasArtifactBuilder()
        val masks = listOf(
            A8GlyphMask(
                glyphId = 20,
                width = 2,
                height = 2,
                rowBytes = 3,
                pixels = listOf(
                    1, 2, 99,
                    3, 4, 99,
                ),
            ),
            A8GlyphMask(
                glyphId = 21,
                width = 1,
                height = 3,
                pixels = listOf(5, 6, 7),
            ),
        )
        val placements = listOf(
            GlyphAtlasPlacement(glyphId = 20, x = 1, y = 0, width = 2, height = 2),
            GlyphAtlasPlacement(glyphId = 21, x = 0, y = 1, width = 1, height = 3),
        )

        val result = builder.build(masks, placements)

        assertEquals(3, result.width)
        assertEquals(4, result.height)
        assertEquals(placements, result.placements)
        assertEquals(
            listOf(
                0, 1, 2,
                5, 3, 4,
                6, 0, 0,
                7, 0, 0,
            ),
            result.pixels,
        )
    }

    @Test
    fun a8AtlasBuilderRejectsNegativeAddressableSample() {
        val builder = KotlinGlyphAtlasArtifactBuilder()

        val failure = assertFailsWith<IllegalArgumentException> {
            builder.build(
                masks = listOf(
                    A8GlyphMask(
                        glyphId = 22,
                        width = 2,
                        height = 1,
                        rowBytes = 3,
                        pixels = listOf(0, -1, 255),
                    ),
                ),
                placements = listOf(
                    GlyphAtlasPlacement(glyphId = 22, x = 0, y = 0, width = 2, height = 1),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("glyph 22"))
        assertTrue(failure.message.orEmpty().contains("-1"))
    }

    @Test
    fun a8AtlasBuilderRejectsPixelCountWhenRowBytesTimesHeightOverflowsInt() {
        val builder = KotlinGlyphAtlasArtifactBuilder()

        val failure = assertFailsWith<IllegalArgumentException> {
            builder.build(
                masks = listOf(
                    A8GlyphMask(
                        glyphId = 23,
                        width = 1,
                        height = 65_536,
                        rowBytes = 65_536,
                        pixels = listOf(0),
                    ),
                ),
                placements = listOf(
                    GlyphAtlasPlacement(glyphId = 23, x = 0, y = 0, width = 1, height = 65_536),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("pixel count"))
    }

    @Test
    fun a8AtlasBuilderRejectsSparsePlacementAtlasAreaOverflowBeforeCopying() {
        val builder = KotlinGlyphAtlasArtifactBuilder()

        val failure = assertFailsWith<IllegalArgumentException> {
            builder.build(
                masks = listOf(A8GlyphMask(glyphId = 24, width = 1, height = 1, pixels = listOf(255))),
                placements = listOf(
                    GlyphAtlasPlacement(
                        glyphId = 24,
                        x = 65_535,
                        y = 65_535,
                        width = 1,
                        height = 1,
                    ),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("atlas pixel count"))
    }

    @Test
    fun sdfAtlasBuilderComposesMasksAndPreservesDistanceRange() {
        val builder = KotlinSDFGlyphAtlasArtifactBuilder(atlasWidth = 5, padding = 1)

        val result = builder.build(
            listOf(
                SDFGlyphMask(
                    glyphId = 30,
                    width = 2,
                    height = 1,
                    distanceRange = 4f,
                    pixels = listOf(8, 9),
                ),
                SDFGlyphMask(
                    glyphId = 31,
                    width = 1,
                    height = 2,
                    distanceRange = 4f,
                    pixels = listOf(6, 7),
                ),
            ),
        )

        assertEquals(3, result.width)
        assertEquals(5, result.height)
        assertEquals(4f, result.distanceRange)
        assertEquals(
            listOf(
                GlyphAtlasPlacement(glyphId = 30, x = 1, y = 1, width = 2, height = 1),
                GlyphAtlasPlacement(glyphId = 31, x = 1, y = 3, width = 1, height = 2),
            ),
            result.placements,
        )
        assertEquals(
            listOf(
                0, 0, 0,
                0, 8, 9,
                0, 0, 0,
                0, 6, 0,
                0, 7, 0,
            ),
            result.pixels,
        )
    }

    @Test
    fun sdfAtlasBuilderRejectsSampleAboveByteRange() {
        val builder = KotlinSDFGlyphAtlasArtifactBuilder()

        val failure = assertFailsWith<IllegalArgumentException> {
            builder.build(
                listOf(
                    SDFGlyphMask(
                        glyphId = 32,
                        width = 1,
                        height = 1,
                        distanceRange = 4f,
                        pixels = listOf(256),
                    ),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("glyph 32"))
        assertTrue(failure.message.orEmpty().contains("256"))
    }

    @Test
    fun sdfAtlasBuilderRejectsPixelCountWhenWidthTimesHeightOverflowsInt() {
        val builder = KotlinSDFGlyphAtlasArtifactBuilder(atlasWidth = Int.MAX_VALUE)

        val failure = assertFailsWith<IllegalArgumentException> {
            builder.build(
                listOf(
                    SDFGlyphMask(
                        glyphId = 33,
                        width = 65_536,
                        height = 65_536,
                        distanceRange = 4f,
                        pixels = listOf(0),
                    ),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("pixel count"))
    }

    @Test
    fun sdfAtlasBuilderRejectsAtlasAreaOverflowBeforeAllocating() {
        val builder = KotlinSDFGlyphAtlasArtifactBuilder(atlasWidth = Int.MAX_VALUE, padding = 65_535)

        val failure = assertFailsWith<IllegalArgumentException> {
            builder.build(
                listOf(
                    SDFGlyphMask(
                        glyphId = 34,
                        width = 1,
                        height = 1,
                        distanceRange = 4f,
                        pixels = listOf(128),
                    ),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("atlas pixel count"))
    }

    @Test
    fun sdfAtlasBuilderRejectsMixedDistanceRanges() {
        val builder = KotlinSDFGlyphAtlasArtifactBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.build(
                listOf(
                    SDFGlyphMask(glyphId = 40, width = 1, height = 1, distanceRange = 4f),
                    SDFGlyphMask(glyphId = 41, width = 1, height = 1, distanceRange = 8f),
                ),
            )
        }
    }

    @Test
    fun glyphAtlasArtifactEvidenceRecordsEntryHashesAndBudgetFacts() {
        val a8Builder = KotlinGlyphAtlasArtifactBuilder()
        val strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441120").copy(
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
            variationCoordinates = mapOf("wght" to 0.4f),
            paletteIdentity = "cpal.palette.1",
            rendererDescriptorVersion = "renderer.v2",
        )
        val mask = A8GlyphMask(
            glyphId = 120,
            width = 2,
            height = 2,
            left = -1,
            top = 3,
            rowBytes = 3,
            pixels = listOf(
                5, 6, 0,
                7, 8, 0,
            ),
            sourceOutlineSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
        val build = a8Builder.build(
            masks = listOf(mask),
            placements = listOf(
                GlyphAtlasPlacement(glyphId = 120, x = 1, y = 2, width = 2, height = 2),
            ),
        )

        val evidence = GlyphAtlasArtifactEvidence.fromA8(
            buildResult = build,
            masks = listOf(mask),
            strikeKeysByGlyphId = mapOf(120 to strikeKey),
            generation = 4,
            invalidationToken = "font-source-v4",
            memoryBudgetClass = "atlas-budget.medium",
            lifetimeClass = "glyph-run-frame",
            paddingPx = 1,
        )

        assertEquals("GlyphAtlasArtifact", evidence.artifactType)
        assertEquals(4L, evidence.generation)
        assertEquals("A8", evidence.textureFormat)
        assertEquals(3, evidence.width)
        assertEquals(4, evidence.height)
        assertEquals(3, evidence.rowStride)
        assertEquals("atlas-budget.medium", evidence.memoryBudgetClass)
        assertEquals("glyph-run-frame", evidence.lifetimeClass)
        assertEquals("font-source-v4", evidence.invalidationToken)
        assertEquals(1, evidence.entryCount)
        assertEquals(1, evidence.entries.size)
        assertEquals(120, evidence.entries.single().glyphId)
        assertEquals(1, evidence.entries.single().paddingPx)
        assertEquals(-1, evidence.entries.single().sourceLeft)
        assertEquals(3, evidence.entries.single().sourceTop)
        assertEquals(64, evidence.artifactKeySha256.length)
        assertEquals(64, evidence.entries.single().strikeKeySha256.length)
        assertEquals(64, evidence.entries.single().sourceMaskSha256.length)
        assertEquals(64, evidence.uploadByteSha256.length)
        assertTrue(evidence.toCanonicalJson().contains(""""invalidationToken": "font-source-v4""""))
    }

    @Test
    fun glyphAtlasArtifactEvidenceKeyChangesWhenStrikeOrMaskFactsChange() {
        val a8Builder = KotlinGlyphAtlasArtifactBuilder()
        val baseA8StrikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441121").copy(
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
            variationCoordinates = mapOf("wght" to 0.4f),
            paletteIdentity = "cpal.palette.1",
            rendererDescriptorVersion = "renderer.v1",
        )
        val baseA8Mask = A8GlyphMask(
            glyphId = 121,
            width = 1,
            height = 1,
            pixels = listOf(64),
            sourceOutlineSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )
        val a8Build = a8Builder.build(
            masks = listOf(baseA8Mask),
            placements = listOf(GlyphAtlasPlacement(glyphId = 121, x = 0, y = 0, width = 1, height = 1)),
        )

        fun a8Artifact(strikeKey: GlyphStrikeKey, mask: A8GlyphMask): GlyphAtlasArtifactEvidence =
            GlyphAtlasArtifactEvidence.fromA8(
                buildResult = a8Build,
                masks = listOf(mask),
                strikeKeysByGlyphId = mapOf(121 to strikeKey),
                generation = 1,
                invalidationToken = "font-source-v1",
                memoryBudgetClass = "atlas-budget.small",
                lifetimeClass = "glyph-run-frame",
            )

        val baseA8 = a8Artifact(baseA8StrikeKey, baseA8Mask)
        val variation = a8Artifact(baseA8StrikeKey.copy(variationCoordinates = mapOf("wght" to 0.5f)), baseA8Mask)
        val palette = a8Artifact(baseA8StrikeKey.copy(paletteIdentity = "cpal.palette.2"), baseA8Mask)
        val renderer = a8Artifact(baseA8StrikeKey.copy(rendererDescriptorVersion = "renderer.v2"), baseA8Mask)
        val sourceMask = a8Artifact(baseA8StrikeKey, baseA8Mask.copy(pixels = listOf(65)))

        val sdfBuilder = KotlinSDFGlyphAtlasArtifactBuilder(atlasWidth = 5, padding = 1)
        val baseSdfStrikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441126").copy(
            representationRoute = "text.glyph.mask.SDF",
            maskFormat = "R8Unorm",
            sdfSpreadPx = 8f,
            sdfSourceResolutionPx = 16f,
        )
        val sdfMask = SDFGlyphMask(glyphId = 126, width = 1, height = 1, distanceRange = 8f, pixels = listOf(128))
        val sdfBuild = sdfBuilder.build(listOf(sdfMask))
        fun sdfArtifact(strikeKey: GlyphStrikeKey): GlyphAtlasArtifactEvidence =
            GlyphAtlasArtifactEvidence.fromSdf(
                buildResult = sdfBuild,
                masks = listOf(sdfMask),
                strikeKeysByGlyphId = mapOf(126 to strikeKey),
                generation = 1,
                invalidationToken = "font-source-v1",
                memoryBudgetClass = "atlas-budget.small",
                lifetimeClass = "glyph-run-frame",
                paddingPx = 1,
            )
        val baseSdf = sdfArtifact(baseSdfStrikeKey)
        val widenedSdf = sdfArtifact(baseSdfStrikeKey.copy(sdfSpreadPx = 16f, sdfSourceResolutionPx = 24f))

        assertTrue(baseA8.artifactKeySha256 != variation.artifactKeySha256)
        assertTrue(baseA8.artifactKeySha256 != palette.artifactKeySha256)
        assertTrue(baseA8.artifactKeySha256 != renderer.artifactKeySha256)
        assertTrue(baseA8.artifactKeySha256 != sourceMask.artifactKeySha256)
        assertTrue(baseSdf.artifactKeySha256 != widenedSdf.artifactKeySha256)
    }

    @Test
    fun glyphAtlasArtifactEvidenceDumpMatchesRepoFixture() {
        val a8Builder = KotlinGlyphAtlasArtifactBuilder()
        val sdfBuilder = KotlinSDFGlyphAtlasArtifactBuilder(atlasWidth = 6, padding = 1)

        val a8StrikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441122").copy(
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
            variationCoordinates = mapOf("wght" to 0.45f),
        )
        val sdfStrikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441123").copy(
            representationRoute = "text.glyph.mask.SDF",
            maskFormat = "R8Unorm",
            sdfSpreadPx = 16f,
            sdfSourceResolutionPx = 24f,
            rendererDescriptorVersion = "renderer.v2",
        )

        val a8Mask = A8GlyphMask(
            glyphId = 122,
            width = 2,
            height = 2,
            left = 1,
            top = 2,
            rowBytes = 2,
            pixels = listOf(10, 20, 30, 40),
            sourceOutlineSha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )
        val sdfMask = SDFGlyphMask(
            glyphId = 123,
            width = 2,
            height = 2,
            left = -1,
            top = 0,
            distanceRange = 16f,
            pixels = listOf(96, 128, 160, 192),
            sourceOutlineSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        )

        val dump = GlyphAtlasArtifactEvidenceDump(
            dumpId = "glyph-atlas",
            ownerTickets = listOf("KFONT-M9-005"),
            fixtureIds = listOf(
                "a8-atlas",
                "sdf-atlas",
            ),
            artifacts = listOf(
                GlyphAtlasArtifactEvidence.fromA8(
                    buildResult = a8Builder.build(
                        masks = listOf(a8Mask),
                        placements = listOf(
                            GlyphAtlasPlacement(glyphId = 122, x = 1, y = 1, width = 2, height = 2),
                        ),
                    ),
                    masks = listOf(a8Mask),
                    strikeKeysByGlyphId = mapOf(122 to a8StrikeKey),
                    generation = 7,
                    invalidationToken = "font-source-v7",
                    memoryBudgetClass = "atlas-budget.medium",
                    lifetimeClass = "glyph-run-frame",
                    paddingPx = 1,
                ),
                GlyphAtlasArtifactEvidence.fromSdf(
                    buildResult = sdfBuilder.build(listOf(sdfMask)),
                    masks = listOf(sdfMask),
                    strikeKeysByGlyphId = mapOf(123 to sdfStrikeKey),
                    generation = 8,
                    invalidationToken = "font-source-v8",
                    memoryBudgetClass = "atlas-budget.medium",
                    lifetimeClass = "glyph-run-frame",
                    paddingPx = 1,
                ),
            ),
            requiredDiagnostics = listOf(
                "text.glyph.atlas-capacity-exceeded",
                "text.glyph.atlas-generation-stale",
            ),
            nonClaims = listOf(
                "producer-only",
                "no-gpu-text-route-claim",
                "no-dftext-retirement",
            ),
        )

        assertEquals(
            readProjectFile("reports/font/fixtures/expected/glyph/glyph-atlas.json"),
            dump.toCanonicalJson(),
        )
    }

    @Test
    fun glyphAtlasEvictionTraceMatchesRepoFixture() {
        val firstStrikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441124").copy(
            representationRoute = "text.glyph.mask.A8",
            variationCoordinates = mapOf("wght" to 0.25f),
        )
        val secondStrikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441125").copy(
            representationRoute = "text.glyph.mask.SDF",
            sdfSpreadPx = 16f,
            sdfSourceResolutionPx = 24f,
        )

        val trace = GlyphAtlasEvictionTrace(
            dumpId = "glyph-atlas-eviction-trace",
            ownerTickets = listOf("KFONT-M9-005"),
            fixtureIds = listOf("eviction-order", "stale-generation"),
            artifactType = "GlyphAtlasArtifact",
            previousGeneration = 7,
            nextGeneration = 8,
            invalidationTokenBefore = "font-source-v7",
            invalidationTokenAfter = "font-source-v8",
            budgetBytes = 256,
            residentBytesBefore = 224,
            residentBytesAfter = 96,
            evictions = listOf(
                GlyphAtlasEvictionTraceEntry(
                    strikeKeySha256 = firstStrikeKey.preimageSha256(glyphId = 124),
                    glyphIds = listOf(124, 125),
                    bytesFreed = 64,
                ),
                GlyphAtlasEvictionTraceEntry(
                    strikeKeySha256 = secondStrikeKey.preimageSha256(glyphId = 126),
                    glyphIds = listOf(126),
                    bytesFreed = 64,
                ),
            ),
            requiredDiagnostics = listOf(
                "text.glyph.atlas-capacity-exceeded",
                "text.glyph.atlas-generation-stale",
            ),
            nonClaims = listOf(
                "producer-only",
                "no-gpu-text-route-claim",
                "no-dftext-retirement",
            ),
        )

        assertEquals(
            readProjectFile("reports/font/fixtures/expected/glyph/glyph-atlas-eviction-trace.json"),
            trace.toCanonicalJson(),
        )
    }

    @Test
    fun inMemoryGlyphCacheUsesStableTypefaceGlyphSizeAndVariationKeys() {
        val cache = InMemoryGlyphCache(GlyphCacheBudget(maxBytes = 16_384, maxEntries = 8))
        val typeface = typefaceId("550e8400-e29b-41d4-a716-446655441001")
        val alternateTypeface = typefaceId("550e8400-e29b-41d4-a716-446655441002")
        val strikeKey = GlyphStrikeKey(
            typefaceId = typeface,
            sizePx = 18f,
            variationCoordinates = mapOf("wght" to 0.6f, "wdth" to 0.25f),
        )
        val sameVariationDifferentOrder = strikeKey.copy(
            variationCoordinates = mapOf("wdth" to 0.25f, "wght" to 0.6f),
        )
        val representation = OutlineGlyphRepresentation(
            glyphId = 42,
            pathCommands = listOf("M 0 0", "L 4 0", "Z"),
        )

        cache.put(strikeKey, representation)

        assertEquals(representation, cache.get(sameVariationDifferentOrder, glyphId = 42))
        assertEquals(null, cache.get(strikeKey, glyphId = 43))
        assertEquals(null, cache.get(strikeKey.copy(sizePx = 19f), glyphId = 42))
        assertEquals(null, cache.get(strikeKey.copy(typefaceId = alternateTypeface), glyphId = 42))
        assertEquals(
            null,
            cache.get(
                strikeKey.copy(
                    variationCoordinates = mapOf("wght" to 0.7f, "wdth" to 0.25f),
                ),
                glyphId = 42,
            ),
        )
    }

    @Test
    fun glyphStrikeKeyCanonicalPreimageIncludesRenderingFactsInStableOrder() {
        val strikeKey = GlyphStrikeKey(
            typefaceId = typefaceId("550e8400-e29b-41d4-a716-446655441061"),
            sizePx = 18f,
            scaleX = 1.25f,
            scaleY = 0.75f,
            subpixelX = 0.5f,
            subpixelY = 0.25f,
            variationCoordinates = mapOf("wght" to 0.6f, "wdth" to 0.25f),
            representationRoute = "text.glyph.mask.SDF",
            maskFormat = "R8Unorm",
            transformBucket = "affine.scale-translate",
            edging = "antialias",
            sdfSpreadPx = 8f,
            sdfSourceResolutionPx = 18f,
            paletteIdentity = "cpal.palette.3",
            unicodeDataVersion = "Unicode-15.1.0",
            rendererDescriptorVersion = "sdf-cpu.v1",
        )

        assertEquals(
            """
            {
              "schema": "org.graphiks.kanvas.glyph.GlyphStrikeKey.v1",
              "typefaceId": "550e8400-e29b-41d4-a716-446655441061",
              "glyphId": 77,
              "clusterFingerprint": null,
              "sizePx": 18,
              "scaleX": 1.25,
              "scaleY": 0.75,
              "transformBucket": "affine.scale-translate",
              "subpixelBucket": {"x": 0.5, "y": 0.25},
              "route": "text.glyph.mask.SDF",
              "maskFormat": "R8Unorm",
              "edging": "antialias",
              "variationCoordinates": [
                {"axis": "wdth", "value": 0.25},
                {"axis": "wght", "value": 0.6}
              ],
              "paletteIdentity": "cpal.palette.3",
              "sdf": {"spreadPx": 8, "sourceResolutionPx": 18},
              "unicodeDataVersion": "Unicode-15.1.0",
              "rendererDescriptorVersion": "sdf-cpu.v1"
            }
            """.trimIndent() + "\n",
            strikeKey.canonicalPreimage(glyphId = 77),
        )
        assertEquals(64, strikeKey.preimageSha256(glyphId = 77).length)
    }

    @Test
    fun glyphStrikeKeyPreimageHashNormalizesVariationOrderAndTracksRouteFacts() {
        val strikeKey = GlyphStrikeKey(
            typefaceId = typefaceId("550e8400-e29b-41d4-a716-446655441062"),
            sizePx = 16f,
            variationCoordinates = mapOf("wght" to 0.55f, "opsz" to 14f),
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
            paletteIdentity = "cpal.palette.1",
            unicodeDataVersion = "Unicode-15.1.0",
        )
        val sameFactsDifferentVariationOrder = strikeKey.copy(
            variationCoordinates = mapOf("opsz" to 14f, "wght" to 0.55f),
        )

        assertEquals(strikeKey.canonicalPreimage(glyphId = 12), sameFactsDifferentVariationOrder.canonicalPreimage(glyphId = 12))
        assertEquals(strikeKey.preimageSha256(glyphId = 12), sameFactsDifferentVariationOrder.preimageSha256(glyphId = 12))
        assertTrue(
            strikeKey.preimageSha256(glyphId = 12) != strikeKey.copy(paletteIdentity = "cpal.palette.2").preimageSha256(glyphId = 12),
        )
        assertTrue(
            strikeKey.preimageSha256(glyphId = 12) != strikeKey.copy(unicodeDataVersion = "Unicode-16.0.0").preimageSha256(glyphId = 12),
        )
        assertTrue(
            strikeKey.preimageSha256(glyphId = 12) != strikeKey.copy(representationRoute = "text.glyph.mask.SDF").preimageSha256(glyphId = 12),
        )
    }

    @Test
    fun glyphStrikeKeyPreimagePreservesDistinctFloatFacts() {
        val strikeKey = GlyphStrikeKey(
            typefaceId = typefaceId("550e8400-e29b-41d4-a716-446655441066"),
            sizePx = 16f,
            subpixelX = 0f,
            variationCoordinates = mapOf("wght" to 0f),
        )
        val distinctSubpixel = strikeKey.copy(subpixelX = 0.0000001f)
        val distinctVariation = strikeKey.copy(variationCoordinates = mapOf("wght" to 0.0000001f))

        assertTrue(strikeKey.canonicalPreimage(glyphId = 9) != distinctSubpixel.canonicalPreimage(glyphId = 9))
        assertTrue(strikeKey.preimageSha256(glyphId = 9) != distinctSubpixel.preimageSha256(glyphId = 9))
        assertTrue(strikeKey.canonicalPreimage(glyphId = 9) != distinctVariation.canonicalPreimage(glyphId = 9))
        assertTrue(strikeKey.preimageSha256(glyphId = 9) != distinctVariation.preimageSha256(glyphId = 9))
    }

    @Test
    fun inMemoryGlyphCacheSeparatesRenderingRoutePaletteAndUnicodeFacts() {
        val cache = InMemoryGlyphCache(GlyphCacheBudget(maxBytes = 16_384, maxEntries = 8))
        val strikeKey = GlyphStrikeKey(
            typefaceId = typefaceId("550e8400-e29b-41d4-a716-446655441063"),
            sizePx = 16f,
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
            paletteIdentity = "cpal.palette.1",
            unicodeDataVersion = "Unicode-15.1.0",
        )
        val representation = A8GlyphMask(glyphId = 88, width = 1, height = 1, pixels = listOf(255))

        cache.put(strikeKey, representation)

        assertEquals(representation, cache.get(strikeKey, glyphId = 88))
        assertEquals(null, cache.get(strikeKey.copy(representationRoute = "text.glyph.mask.SDF"), glyphId = 88))
        assertEquals(null, cache.get(strikeKey.copy(maskFormat = "R8Unorm"), glyphId = 88))
        assertEquals(null, cache.get(strikeKey.copy(paletteIdentity = "cpal.palette.2"), glyphId = 88))
        assertEquals(null, cache.get(strikeKey.copy(unicodeDataVersion = "Unicode-16.0.0"), glyphId = 88))
    }

    @Test
    fun inMemoryGlyphCacheEvictsOldestEntriesWhenEntryBudgetIsExceeded() {
        val cache = InMemoryGlyphCache(GlyphCacheBudget(maxBytes = 16_384, maxEntries = 2))
        val strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441011")
        val first = OutlineGlyphRepresentation(glyphId = 1, pathCommands = listOf("M 0 0"))
        val second = A8GlyphMask(glyphId = 2, width = 1, height = 1, pixels = listOf(2))
        val third = SDFGlyphMask(glyphId = 3, width = 1, height = 1, distanceRange = 4f, pixels = listOf(3))

        cache.put(strikeKey, first)
        cache.put(strikeKey, second)
        cache.put(strikeKey, third)

        assertEquals(null, cache.get(strikeKey, glyphId = 1))
        assertEquals(second, cache.get(strikeKey, glyphId = 2))
        assertEquals(third, cache.get(strikeKey, glyphId = 3))
    }

    @Test
    fun routeDiagnosticRecordsStaleAtlasGenerationRefusal() {
        val diagnostic = GlyphRouteDiagnostic.atlasGenerationStale(
            glyphId = 91,
            artifactGeneration = 3,
            currentGeneration = 4,
            invalidationToken = "font-source-v2",
        )

        assertEquals(91, diagnostic.glyphId)
        assertEquals("text.glyph.atlas-generation-stale", diagnostic.route)
        assertEquals("warning", diagnostic.severity)
        assertTrue(diagnostic.message.contains("artifactGeneration=3"))
        assertTrue(diagnostic.message.contains("currentGeneration=4"))
        assertTrue(diagnostic.message.contains("invalidationToken=font-source-v2"))
        assertEquals(64, diagnostic.dumpSha256.length)
        assertEquals(
            """
            {
              "glyphId": 91,
              "route": "text.glyph.atlas-generation-stale",
              "severity": "warning",
              "message": "Glyph atlas generation is stale for glyph 91: artifactGeneration=3, currentGeneration=4, invalidationToken=font-source-v2."
            }
            """.trimIndent(),
            diagnostic.toCanonicalJson(),
        )
    }

    @Test
    fun routeDiagnosticRecordsSDFTransformUnsupportedRefusal() {
        val diagnostic = GlyphRouteDiagnostic.sdfTransformUnsupported(
            glyphId = 92,
            transformBucket = "perspective",
            fallbackRoute = "text.glyph.mask.A8",
        )

        assertEquals(92, diagnostic.glyphId)
        assertEquals("text.glyph.SDF-transform-unsupported", diagnostic.route)
        assertEquals("warning", diagnostic.severity)
        assertTrue(diagnostic.message.contains("transformBucket=perspective"))
        assertTrue(diagnostic.message.contains("fallbackRoute=text.glyph.mask.A8"))
        assertEquals(
            """
            {
              "glyphId": 92,
              "route": "text.glyph.SDF-transform-unsupported",
              "severity": "warning",
              "message": "SDF transform is unsupported for glyph 92: transformBucket=perspective, fallbackRoute=text.glyph.mask.A8."
            }
            """.trimIndent(),
            diagnostic.toCanonicalJson(),
        )
    }

    @Test
    fun routePlannerSelectsPreferredAvailableRepresentationForEachGlyph() {
        val strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441021")
        val outline = OutlineGlyphRepresentation(glyphId = 10, pathCommands = listOf("M 0 0"))
        val fallbackOutline = OutlineGlyphRepresentation(glyphId = 11, pathCommands = listOf("M 1 1"))
        val a8 = A8GlyphMask(glyphId = 11, width = 1, height = 1, pixels = listOf(64))
        val fallbackA8 = A8GlyphMask(glyphId = 12, width = 1, height = 1, pixels = listOf(128))
        val sdf = SDFGlyphMask(glyphId = 12, width = 1, height = 1, distanceRange = 8f, pixels = listOf(192))
        val cache = InMemoryGlyphCache(GlyphCacheBudget(maxBytes = 16_384, maxEntries = 8))
        val planner = GlyphArtifactRoutePlanner(
            request = GlyphArtifactRouteRequest(
                preferredRoutes = listOf(GlyphArtifactRoute.SDF, GlyphArtifactRoute.A8, GlyphArtifactRoute.OUTLINE),
                availableRepresentations = mapOf(
                    10 to listOf(outline),
                    11 to listOf(fallbackOutline, a8),
                    12 to listOf(fallbackA8, sdf),
                ),
            ),
            cache = cache,
        )

        val plan = planner.plan(
            run = glyphRun(glyphIds = listOf(10, 11, 12)),
            strikeKey = strikeKey,
        )

        assertEquals(listOf(outline, a8, sdf), plan.representations)
        assertEquals(emptyList(), plan.diagnostics)
        assertEquals(outline, cache.get(strikeKey, glyphId = 10))
        assertEquals(a8, cache.get(strikeKey, glyphId = 11))
        assertEquals(sdf, cache.get(strikeKey, glyphId = 12))
    }

    @Test
    fun routePlannerPrefersCachedRepresentationWhenItMatchesEarlierRoute() {
        val strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441022")
        val outline = OutlineGlyphRepresentation(glyphId = 13, pathCommands = listOf("M 0 0"))
        val sdf = SDFGlyphMask(glyphId = 13, width = 1, height = 1, distanceRange = 8f, pixels = listOf(192))
        val cache = InMemoryGlyphCache(GlyphCacheBudget(maxBytes = 16_384, maxEntries = 8))
        cache.put(strikeKey, sdf)
        val planner = GlyphArtifactRoutePlanner(
            request = GlyphArtifactRouteRequest(
                preferredRoutes = listOf(GlyphArtifactRoute.SDF, GlyphArtifactRoute.OUTLINE),
                availableRepresentations = mapOf(13 to listOf(outline)),
            ),
            cache = cache,
        )

        val plan = planner.plan(
            run = glyphRun(glyphIds = listOf(13)),
            strikeKey = strikeKey,
        )

        assertEquals(listOf(sdf), plan.representations)
        assertEquals(emptyList(), plan.diagnostics)
    }

    @Test
    fun routePlannerRecordsDiagnosticsWhenRequestedRouteIsImpossible() {
        val outline = OutlineGlyphRepresentation(glyphId = 20, pathCommands = listOf("M 0 0"))
        val planner = GlyphArtifactRoutePlanner(
            request = GlyphArtifactRouteRequest(
                preferredRoutes = listOf(GlyphArtifactRoute.SDF),
                availableRepresentations = mapOf(20 to listOf(outline)),
            ),
        )

        val plan = planner.plan(
            run = glyphRun(glyphIds = listOf(20, 21)),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441031"),
        )

        assertEquals(emptyList(), plan.representations)
        assertEquals(listOf(20, 21), plan.diagnostics.map { it.glyphId })
        assertEquals(listOf("sdf", "sdf"), plan.diagnostics.map { it.route })
        assertTrue(plan.diagnostics.all { diagnostic -> diagnostic.severity == "warning" })
        assertTrue(plan.diagnostics.all { diagnostic -> diagnostic.message.contains("requested") })
    }

    @Test
    fun glyphArtifactPlanRecordsDecisionTraceAndCanonicalDump() {
        val outline = OutlineGlyphRepresentation(glyphId = 30, pathCommands = listOf("M 0 0"))
        val a8 = A8GlyphMask(glyphId = 31, width = 1, height = 1, pixels = listOf(64))
        val sdf = SDFGlyphMask(glyphId = 32, width = 1, height = 1, distanceRange = 8f, pixels = listOf(192))
        val planner = GlyphArtifactRoutePlanner(
            request = GlyphArtifactRouteRequest(
                preferredRoutes = listOf(GlyphArtifactRoute.SDF, GlyphArtifactRoute.A8, GlyphArtifactRoute.OUTLINE),
                availableRepresentations = mapOf(
                    30 to listOf(outline),
                    31 to listOf(outline.copy(glyphId = 31), a8),
                    32 to listOf(a8.copy(glyphId = 32), sdf),
                ),
            ),
        )

        val plan = planner.plan(
            run = glyphRun(glyphIds = listOf(30, 31, 32, 33)),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441071"),
        )

        assertEquals(listOf(outline, a8, sdf), plan.representations)
        assertEquals(listOf(0, 1, 2, 3), plan.decisions.map { decision -> decision.index })
        assertEquals(listOf(30, 31, 32, 33), plan.decisions.map { decision -> decision.glyphId })
        assertEquals(
            listOf(
                "text.glyph.outline",
                "text.glyph.mask.A8",
                "text.glyph.mask.SDF",
                "text.glyph.unsupported",
            ),
            plan.decisions.map { decision -> decision.selectedRoute },
        )
        assertEquals(
            listOf(
                "fallback-selected-after-rejections",
                "fallback-selected-after-rejections",
                "selected-first-requested-route",
                "refuse-no-requested-representation",
            ),
            plan.decisions.map { decision -> decision.fallbackPolicy },
        )
        assertEquals(1, plan.diagnostics.size)
        assertTrue(plan.decisions.all { decision -> decision.keySha256.length == 64 })
        assertEquals(64, plan.decisions[0].sourceRepresentationSha256?.length)
        assertEquals(64, plan.decisions[1].sourceRepresentationSha256?.length)
        assertEquals(64, plan.decisions[2].sourceRepresentationSha256?.length)
        assertEquals(null, plan.decisions[3].sourceRepresentationSha256)
        assertEquals(plan.diagnostics.single(), plan.decisions.last().diagnostic)
        assertEquals(
            listOf("text.glyph.mask.SDF", "text.glyph.mask.A8"),
            plan.decisions.first().rejectedAlternatives.map { rejection -> rejection.route },
        )
        assertEquals(64, plan.dumpSha256.length)
        assertEquals(
            """
            {
              "schema": "org.graphiks.kanvas.glyph.GlyphArtifactPlan.v1",
              "runId": "550e8400-e29b-41d4-a716-446655441101",
              "glyphCount": 4,
              "representationCount": 3,
              "diagnosticCount": 1,
              "policyInputs": null,
              "decisions": [
                {
                  "index": 0,
                  "glyphId": 30,
                  "selectedRoute": "text.glyph.outline",
                  "representation": "outline",
                  "source": "request",
                  "planRef": null,
                  "artifactIntent": null,
                  "keySha256": "${plan.decisions[0].keySha256}",
                  "sourceRepresentationSha256": "${plan.decisions[0].sourceRepresentationSha256}",
                  "fallbackPolicy": "fallback-selected-after-rejections",
                  "rejectedAlternatives": [
                    {"route": "text.glyph.mask.SDF", "reason": "route-unavailable"},
                    {"route": "text.glyph.mask.A8", "reason": "route-unavailable"}
                  ],
                  "diagnostic": null
                },
                {
                  "index": 1,
                  "glyphId": 31,
                  "selectedRoute": "text.glyph.mask.A8",
                  "representation": "a8",
                  "source": "request",
                  "planRef": null,
                  "artifactIntent": "CPUPreparedGPU",
                  "keySha256": "${plan.decisions[1].keySha256}",
                  "sourceRepresentationSha256": "${plan.decisions[1].sourceRepresentationSha256}",
                  "fallbackPolicy": "fallback-selected-after-rejections",
                  "rejectedAlternatives": [
                    {"route": "text.glyph.mask.SDF", "reason": "route-unavailable"}
                  ],
                  "diagnostic": null
                },
                {
                  "index": 2,
                  "glyphId": 32,
                  "selectedRoute": "text.glyph.mask.SDF",
                  "representation": "sdf",
                  "source": "request",
                  "planRef": null,
                  "artifactIntent": "CPUPreparedGPU",
                  "keySha256": "${plan.decisions[2].keySha256}",
                  "sourceRepresentationSha256": "${plan.decisions[2].sourceRepresentationSha256}",
                  "fallbackPolicy": "selected-first-requested-route",
                  "rejectedAlternatives": [],
                  "diagnostic": null
                },
                {
                  "index": 3,
                  "glyphId": 33,
                  "selectedRoute": "text.glyph.unsupported",
                  "representation": null,
                  "source": null,
                  "planRef": null,
                  "artifactIntent": null,
                  "keySha256": "${plan.decisions[3].keySha256}",
                  "sourceRepresentationSha256": null,
                  "fallbackPolicy": "refuse-no-requested-representation",
                  "rejectedAlternatives": [
                    {"route": "text.glyph.mask.SDF", "reason": "route-unavailable"},
                    {"route": "text.glyph.mask.A8", "reason": "route-unavailable"},
                    {"route": "text.glyph.outline", "reason": "route-unavailable"}
                  ],
                  "diagnostic": {
                    "glyphId": 33,
                    "route": "sdf|a8|outline",
                    "severity": "warning",
                    "message": "No requested glyph representation is available for glyph 33; requested sdf|a8|outline, available none."
                  }
                }
              ],
              "diagnostics": [
                {
                  "glyphId": 33,
                  "route": "sdf|a8|outline",
                  "severity": "warning",
                  "message": "No requested glyph representation is available for glyph 33; requested sdf|a8|outline, available none."
                }
              ],
              "dumpSha256": "${plan.dumpSha256}"
            }
            """.trimIndent() + "\n",
            plan.toCanonicalGlyphArtifactPlanJson(),
        )
    }

    @Test
    fun glyphArtifactPlanSupportsColorBitmapAndSvgPlaceholderRoutes() {
        val outlineFallback = OutlineGlyphRepresentation(glyphId = 40, pathCommands = listOf("M 0 0"))
        val colorRef = ColorGlyphPlanRef(glyphId = 41, planId = "color-plan-41")
        val bitmapRef = BitmapGlyphPlanRef(glyphId = 42, planId = "bitmap-plan-42")
        val svgRef = SVGGlyphPlanRef(glyphId = 43, planId = "svg-plan-43")

        val planner = GlyphArtifactRoutePlanner(
            request = GlyphArtifactRouteRequest(
                preferredRoutes = listOf(
                    GlyphArtifactRoute.COLOR,
                    GlyphArtifactRoute.BITMAP,
                    GlyphArtifactRoute.SVG,
                    GlyphArtifactRoute.OUTLINE,
                ),
                availableRepresentations = mapOf(
                    40 to listOf(outlineFallback),
                    41 to listOf(colorRef),
                    42 to listOf(bitmapRef),
                    43 to listOf(svgRef),
                ),
            ),
        )

        val plan = planner.plan(
            run = glyphRun(glyphIds = listOf(40, 41, 42, 43, 44)),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441072"),
        )

        assertEquals(
            listOf(
                "text.glyph.outline",
                "text.glyph.color.COLR",
                "text.glyph.bitmap.PNG",
                "text.glyph.SVG",
                "text.glyph.unsupported",
            ),
            plan.decisions.map { decision -> decision.selectedRoute },
        )
        assertEquals(
            listOf("ColorGlyphPlan", "BitmapGlyphPlan", "SVGGlyphPlan"),
            plan.decisions.mapNotNull { decision -> decision.planRef?.artifactName },
        )
        assertEquals(
            listOf("color-plan-41", "bitmap-plan-42", "svg-plan-43"),
            plan.decisions.mapNotNull { decision -> decision.planRef?.planId },
        )
        assertEquals(
            listOf(
                "fallback-selected-after-rejections",
                "selected-first-requested-route",
                "fallback-selected-after-rejections",
                "fallback-selected-after-rejections",
                "refuse-no-requested-representation",
            ),
            plan.decisions.map { decision -> decision.fallbackPolicy },
        )

        val dump = plan.toCanonicalGlyphArtifactPlanJson()
        assertTrue(dump.contains(""""artifactName": "ColorGlyphPlan""""))
        assertTrue(dump.contains(""""artifactName": "BitmapGlyphPlan""""))
        assertTrue(dump.contains(""""artifactName": "SVGGlyphPlan""""))
        assertTrue(dump.contains(""""selectedRoute": "text.glyph.color.COLR""""))
        assertTrue(dump.contains(""""selectedRoute": "text.glyph.bitmap.PNG""""))
        assertTrue(dump.contains(""""selectedRoute": "text.glyph.SVG""""))
        assertEquals(plan.diagnostics.single(), plan.decisions.last().diagnostic)
    }

    @Test
    fun glyphArtifactPlanRecordsPolicyInputsIntentAndExplicitRouteDiagnostics() {
        val outlineFallback = OutlineGlyphRepresentation(glyphId = 60, pathCommands = listOf("M 0 0"))
        val a8 = a8Mask(glyphId = 61, width = 1, height = 1)
        val sdf = SDFGlyphMask(glyphId = 63, width = 1, height = 1, distanceRange = 8f, pixels = listOf(192))

        val request = GlyphArtifactRouteRequest(
            preferredRoutes = listOf(
                GlyphArtifactRoute.SDF,
                GlyphArtifactRoute.A8,
                GlyphArtifactRoute.OUTLINE,
            ),
            policyInputs = GlyphArtifactRoutePolicyInputs(
                textStylePreference = "body",
                transformClass = "perspective",
                atlasBudgetClass = "tight",
                sdfEligibility = "mixed",
                colorGlyphAvailability = "placeholders-only",
                emojiSequenceFacts = "none",
                rendererCapabilitySummary = "cpu-only",
            ),
            routeDiagnostics = mapOf(
                60 to listOf(
                    GlyphRouteDiagnostic.sdfTransformUnsupported(
                        glyphId = 60,
                        transformBucket = "perspective",
                        fallbackRoute = "text.glyph.outline",
                    ),
                ),
                62 to listOf(
                    GlyphRouteDiagnostic(
                        glyphId = 62,
                        route = "text.glyph.atlas-capacity-exceeded",
                        message = "Atlas budget exceeded for glyph 62.",
                        severity = "warning",
                    ),
                ),
            ),
            availableRepresentations = mapOf(
                60 to listOf(outlineFallback),
                61 to listOf(a8),
                62 to listOf(outlineFallback.copy(glyphId = 62)),
                63 to listOf(sdf),
            ),
        )

        val plan = GlyphArtifactRoutePlanner(request).plan(
            run = glyphRun(glyphIds = listOf(60, 61, 62, 63)),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441073"),
        )

        assertEquals(request.policyInputs, plan.policyInputs)
        assertEquals("text.glyph.SDF-transform-unsupported", plan.decisions[0].rejectedAlternatives.first().reason)
        assertEquals("CPUPreparedGPU", plan.decisions[1].artifactIntent)
        assertEquals(64, plan.decisions[1].sourceRepresentationSha256?.length)
        assertEquals("text.glyph.atlas-capacity-exceeded", plan.decisions[2].rejectedAlternatives[1].reason)
        assertEquals("CPUPreparedGPU", plan.decisions[3].artifactIntent)
        assertEquals(64, plan.decisions[3].sourceRepresentationSha256?.length)

        val dump = plan.toCanonicalGlyphArtifactPlanJson()
        assertTrue(dump.contains(""""artifactIntent": "CPUPreparedGPU""""))
        assertTrue(dump.contains(""""sourceRepresentationSha256": """"))
        assertTrue(dump.contains(""""transformClass": "perspective""""))
        assertTrue(dump.contains(""""rendererCapabilitySummary": "cpu-only""""))
    }

    @Test
    fun glyphArtifactPlanSupportsExplicitLcdAndOutlineRefusalDiagnostics() {
        val lcdPlan = GlyphArtifactRoutePlanner(
            GlyphArtifactRouteRequest(
                preferredRoutes = listOf(GlyphArtifactRoute.LCD),
            ),
        ).plan(
            run = glyphRun(glyphIds = listOf(70)),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441074"),
        )
        assertEquals("text.glyph.LCD-future-research", lcdPlan.diagnostics.single().route)
        assertEquals(lcdPlan.diagnostics.single(), lcdPlan.decisions.single().diagnostic)

        val outlinePlan = GlyphArtifactRoutePlanner(
            GlyphArtifactRouteRequest(
                preferredRoutes = listOf(GlyphArtifactRoute.OUTLINE),
                routeDiagnostics = mapOf(
                    71 to listOf(GlyphRouteDiagnostic.outlineUnavailable(glyphId = 71)),
                ),
            ),
        ).plan(
            run = glyphRun(glyphIds = listOf(71)),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441075"),
        )
        assertEquals("text.glyph.outline-unavailable", outlinePlan.diagnostics.single().route)
        assertEquals(outlinePlan.diagnostics.single(), outlinePlan.decisions.single().diagnostic)
    }

    @Test
    fun glyphArtifactPlanEvidenceDumpMatchesRepoFixture() {
        val placeholderPlan = GlyphArtifactRoutePlanner(
            request = GlyphArtifactRouteRequest(
                preferredRoutes = listOf(
                    GlyphArtifactRoute.COLOR,
                    GlyphArtifactRoute.BITMAP,
                    GlyphArtifactRoute.SVG,
                    GlyphArtifactRoute.OUTLINE,
                ),
                availableRepresentations = mapOf(
                    40 to listOf(OutlineGlyphRepresentation(glyphId = 40, pathCommands = listOf("M 0 0"))),
                    41 to listOf(ColorGlyphPlanRef(glyphId = 41, planId = "color-plan-41")),
                    42 to listOf(BitmapGlyphPlanRef(glyphId = 42, planId = "bitmap-plan-42")),
                    43 to listOf(SVGGlyphPlanRef(glyphId = 43, planId = "svg-plan-43")),
                ),
            ),
        ).plan(
            run = glyphRun(glyphIds = listOf(40, 41, 42, 43, 44)),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441076"),
        )

        val policyPlan = GlyphArtifactRoutePlanner(
            request = GlyphArtifactRouteRequest(
                preferredRoutes = listOf(
                    GlyphArtifactRoute.SDF,
                    GlyphArtifactRoute.A8,
                    GlyphArtifactRoute.OUTLINE,
                ),
                policyInputs = GlyphArtifactRoutePolicyInputs(
                    textStylePreference = "body",
                    transformClass = "perspective",
                    atlasBudgetClass = "tight",
                    sdfEligibility = "mixed",
                    colorGlyphAvailability = "placeholders-only",
                    emojiSequenceFacts = "none",
                    rendererCapabilitySummary = "cpu-only",
                ),
                routeDiagnostics = mapOf(
                    60 to listOf(GlyphRouteDiagnostic.sdfTransformUnsupported(60, "perspective", "text.glyph.outline")),
                    62 to listOf(
                        GlyphRouteDiagnostic(
                            glyphId = 62,
                            route = "text.glyph.atlas-capacity-exceeded",
                            message = "Atlas budget exceeded for glyph 62.",
                            severity = "warning",
                        ),
                    ),
                ),
                availableRepresentations = mapOf(
                    60 to listOf(OutlineGlyphRepresentation(glyphId = 60, pathCommands = listOf("M 0 0"))),
                    61 to listOf(a8Mask(glyphId = 61, width = 1, height = 1)),
                    62 to listOf(OutlineGlyphRepresentation(glyphId = 62, pathCommands = listOf("M 0 0"))),
                    63 to listOf(SDFGlyphMask(glyphId = 63, width = 1, height = 1, distanceRange = 8f, pixels = listOf(192))),
                ),
            ),
        ).plan(
            run = glyphRun(glyphIds = listOf(60, 61, 62, 63)),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441077"),
        )

        val lcdPlan = GlyphArtifactRoutePlanner(
            GlyphArtifactRouteRequest(
                preferredRoutes = listOf(GlyphArtifactRoute.LCD),
            ),
        ).plan(
            run = glyphRun(glyphIds = listOf(70)),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441078"),
        )

        val outlinePlan = GlyphArtifactRoutePlanner(
            GlyphArtifactRouteRequest(
                preferredRoutes = listOf(GlyphArtifactRoute.OUTLINE),
                routeDiagnostics = mapOf(
                    71 to listOf(GlyphRouteDiagnostic.outlineUnavailable(glyphId = 71)),
                ),
            ),
        ).plan(
            run = glyphRun(glyphIds = listOf(71)),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441079"),
        )

        val dump = GlyphArtifactPlanEvidenceDump(
            dumpId = "glyph-artifact-plan",
            ownerTickets = listOf("KFONT-M9-002"),
            fixtureIds = listOf(
                "glyph-artifact-plan-placeholders",
                "glyph-artifact-plan-policy-refusals",
                "glyph-artifact-plan-lcd-refusal",
                "glyph-artifact-plan-outline-refusal",
            ),
            plans = listOf(placeholderPlan, policyPlan, lcdPlan, outlinePlan),
            requiredDiagnostics = listOf(
                "text.glyph.LCD-future-research",
                "text.glyph.SDF-transform-unsupported",
                "text.glyph.atlas-capacity-exceeded",
                "text.glyph.outline-unavailable",
            ),
            nonClaims = listOf(
                "producer-only",
                "no-complete-color-bitmap-svg-plan-claim",
                "no-gpu-text-route-claim",
            ),
        )

        assertEquals(
            readProjectFile("reports/font/fixtures/expected/glyph/glyph-artifact-plan.json"),
            dump.toCanonicalJson(),
        )
    }

    @Test
    fun a8GlyphMaskEvidenceDumpMatchesRepoFixture() {
        val generator = object : GlyphMaskGenerator {}

        val quadratic = generator.generate(
            outline = OutlineGlyphRepresentation(
                glyphId = 54,
                pathCommands = listOf(
                    "M 1 2",
                    "Q 2.5 2 4 2",
                    "Q 4 3.5 4 5",
                    "Q 2.5 5 1 5",
                    "Q 1 3.5 1 2",
                    "Z",
                ),
            ),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441084"),
        )
        val cubic = generator.generate(
            outline = OutlineGlyphRepresentation(
                glyphId = 55,
                pathCommands = listOf(
                    "M 1 2",
                    "C 2 2 3 2 4 2",
                    "C 4 3 4 4 4 5",
                    "C 3 5 2 5 1 5",
                    "C 1 4 1 3 1 2",
                    "Z",
                ),
            ),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441085"),
        )
        val composite = generator.generate(
            outline = OutlineGlyphRepresentation(
                glyphId = 0,
                pathCommands = listOf(
                    "M 50 50",
                    "L 150 50",
                    "L 150 150",
                    "L 50 150",
                    "Z",
                    "M 150 50",
                    "L 250 50",
                    "L 250 150",
                    "L 150 150",
                    "Z",
                ),
            ),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441086"),
        )
        val notdefEmpty = generator.generate(
            outline = OutlineGlyphRepresentation(glyphId = 0),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441087"),
        )
        val malformed = generator.generate(
            outline = OutlineGlyphRepresentation(glyphId = 57, pathCommands = listOf("L 1 2")),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441088"),
        )
        val unsupportedFill = generator.generate(
            outline = OutlineGlyphRepresentation(
                glyphId = 56,
                pathCommands = listOf(
                    "M 1 2",
                    "L 4 2",
                    "L 4 5",
                    "L 1 5",
                    "Z",
                ),
                windingRule = "evenOdd",
            ),
            strikeKey = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655441089"),
        )

        val dump = A8GlyphMaskEvidenceDump(
            dumpId = "a8-glyph-mask",
            ownerTickets = listOf("KFONT-M9-003"),
            fixtureIds = listOf(
                "a8-quadratic-simple",
                "a8-cubic-simple",
                "a8-composite-outline",
                "a8-empty-notdef",
                "a8-malformed-contour",
                "a8-unsupported-fill-rule",
            ),
            masks = listOf(
                A8GlyphMaskArtifactEvidence.from(quadratic, strikeKey("550e8400-e29b-41d4-a716-446655441084")),
                A8GlyphMaskArtifactEvidence.from(cubic, strikeKey("550e8400-e29b-41d4-a716-446655441085")),
                A8GlyphMaskArtifactEvidence.from(composite, strikeKey("550e8400-e29b-41d4-a716-446655441086")),
                A8GlyphMaskArtifactEvidence.from(notdefEmpty, strikeKey("550e8400-e29b-41d4-a716-446655441087")),
                A8GlyphMaskArtifactEvidence.from(malformed, strikeKey("550e8400-e29b-41d4-a716-446655441088")),
                A8GlyphMaskArtifactEvidence.from(unsupportedFill, strikeKey("550e8400-e29b-41d4-a716-446655441089")),
            ),
            requiredDiagnostics = listOf("text.glyph.A8-generation-failed"),
            nonClaims = listOf(
                "producer-only",
                "no-gpu-text-route-claim",
                "no-lcd-support-claim",
                "no-sdf-support-claim",
                "no-external-rasterizer-parity-claim",
            ),
        )

        assertEquals(
            readProjectFile("reports/font/fixtures/expected/glyph/a8-glyph-mask.json"),
            dump.toCanonicalJson(),
        )
    }

    @Test
    fun routeDiagnosticsHaveCanonicalDumpAndStableHash() {
        val diagnostics = listOf(
            GlyphRouteDiagnostic(
                glyphId = 20,
                route = "text.glyph.mask.SDF",
                severity = "warning",
                message = "No \"SDF\" route\navailable.",
            ),
            GlyphRouteDiagnostic(
                glyphId = null,
                route = "text.glyph.unsupported",
                severity = "info",
                message = "LCD is future research.",
            ),
        )

        assertEquals(
            """
            [
              {
                "glyphId": 20,
                "route": "text.glyph.mask.SDF",
                "severity": "warning",
                "message": "No \"SDF\" route\navailable."
              },
              {
                "glyphId": null,
                "route": "text.glyph.unsupported",
                "severity": "info",
                "message": "LCD is future research."
              }
            ]
            """.trimIndent() + "\n",
            diagnostics.toCanonicalGlyphRouteDiagnosticsJson(),
        )
        assertEquals(64, diagnostics.glyphRouteDiagnosticsSha256().length)
        assertEquals(
            diagnostics.glyphRouteDiagnosticsSha256(),
            diagnostics.toList().glyphRouteDiagnosticsSha256(),
        )
    }

    @Test
    fun glyphRunCacheInventoryBuildsStableRecordsJsonAndHash() {
        val missingDiagnostic = "font.missing-glyph.notdef-used"
        val alphaMask = A8GlyphMask(
            glyphId = 42,
            width = 2,
            height = 2,
            left = -1,
            top = 3,
            rowBytes = 3,
            pixels = listOf(
                0, 7, 99,
                255, 0, 99,
            ),
        )
        val emptyMask = GlyphMaskSummary.Empty

        val inventory = GlyphRunCacheInventory.build(
            runId = "run.simple-latin.v1",
            representation = GlyphRunCacheInventory.AlphaMaskRepresentation,
            glyphs = listOf(
                GlyphRunCacheBuildGlyph(
                    index = 0,
                    codePoint = 'A'.code,
                    glyphId = 42,
                    key = "scope|font|size=16|glyph=42",
                    advance = 9.5f,
                    x = 0f,
                    maskSummary = GlyphMaskSummary.fromA8Mask(alphaMask),
                ),
                GlyphRunCacheBuildGlyph(
                    index = 1,
                    codePoint = 'A'.code,
                    glyphId = 42,
                    key = "scope|font|size=16|glyph=42",
                    advance = 9.5f,
                    x = 9.5f,
                    maskSummary = GlyphMaskSummary.fromA8Mask(alphaMask),
                ),
                GlyphRunCacheBuildGlyph(
                    index = 2,
                    codePoint = 0xE000,
                    glyphId = 0,
                    key = "scope|font|size=16|glyph=0",
                    advance = 8f,
                    x = 19f,
                    maskSummary = emptyMask,
                    diagnostic = missingDiagnostic,
                ),
            ),
        )

        assertEquals(3, inventory.items.size)
        assertEquals(2, inventory.records.size)
        assertEquals(listOf(missingDiagnostic), inventory.diagnostics)
        assertEquals(listOf('A'.code), inventory.records[0].codePoints)
        assertEquals(listOf(0xE000), inventory.records[1].codePoints)
        assertEquals(2, inventory.records[0].maskSummary.nonZeroPixels)
        assertEquals("79ef38e8384dc02cd1de6202a09cab298e2bac8148fff30a3c437f87e63956eb", inventory.records[0].maskSummary.sha256)
        assertEquals(64, inventory.dumpSha256.length)
        assertEquals(inventory.dumpSha256, GlyphRunCacheInventory.build(
            runId = "run.simple-latin.v1",
            representation = GlyphRunCacheInventory.AlphaMaskRepresentation,
            glyphs = listOf(
                GlyphRunCacheBuildGlyph(
                    index = 0,
                    codePoint = 'A'.code,
                    glyphId = 42,
                    key = "scope|font|size=16|glyph=42",
                    advance = 9.5f,
                    x = 0f,
                    maskSummary = GlyphMaskSummary.fromA8Mask(alphaMask),
                ),
                GlyphRunCacheBuildGlyph(
                    index = 1,
                    codePoint = 'A'.code,
                    glyphId = 42,
                    key = "scope|font|size=16|glyph=42",
                    advance = 9.5f,
                    x = 9.5f,
                    maskSummary = GlyphMaskSummary.fromA8Mask(alphaMask),
                ),
                GlyphRunCacheBuildGlyph(
                    index = 2,
                    codePoint = 0xE000,
                    glyphId = 0,
                    key = "scope|font|size=16|glyph=0",
                    advance = 8f,
                    x = 19f,
                    maskSummary = emptyMask,
                    diagnostic = missingDiagnostic,
                ),
            ),
        ).dumpSha256)
        assertEquals(
            """
            {
              "runId": "run.simple-latin.v1",
              "representation": "font.glyph.alpha-mask",
              "itemCount": 3,
              "recordCount": 2,
              "diagnostics": ["font.missing-glyph.notdef-used"],
              "items": [
                {
                  "index": 0,
                  "codePoint": "U+0041",
                  "codePointValue": 65,
                  "glyphId": 42,
                  "key": "scope|font|size=16|glyph=42",
                  "advance": 9.5,
                  "x": 0,
                  "diagnostic": null
                },
                {
                  "index": 1,
                  "codePoint": "U+0041",
                  "codePointValue": 65,
                  "glyphId": 42,
                  "key": "scope|font|size=16|glyph=42",
                  "advance": 9.5,
                  "x": 9.5,
                  "diagnostic": null
                },
                {
                  "index": 2,
                  "codePoint": "U+E000",
                  "codePointValue": 57344,
                  "glyphId": 0,
                  "key": "scope|font|size=16|glyph=0",
                  "advance": 8,
                  "x": 19,
                  "diagnostic": "font.missing-glyph.notdef-used"
                }
              ],
              "records": [
                {
                  "key": "scope|font|size=16|glyph=42",
                  "glyphId": 42,
                  "codePoints": ["U+0041"],
                  "advance": 9.5,
                  "maskSummary": {"left": -1, "top": 3, "width": 2, "height": 2, "rowBytes": 3, "nonZeroPixels": 2, "sha256": "79ef38e8384dc02cd1de6202a09cab298e2bac8148fff30a3c437f87e63956eb"},
                  "diagnostic": null
                },
                {
                  "key": "scope|font|size=16|glyph=0",
                  "glyphId": 0,
                  "codePoints": ["U+E000"],
                  "advance": 8,
                  "maskSummary": {"left": 0, "top": 0, "width": 0, "height": 0, "rowBytes": 0, "nonZeroPixels": 0, "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
                  "diagnostic": "font.missing-glyph.notdef-used"
                }
              ],
              "dumpSha256": "${inventory.dumpSha256}"
            }
            """.trimIndent() + "\n",
            inventory.toCanonicalJson(),
        )
    }

    @Test
    fun glyphCacheInventoryDumpMatchesRepoFixture() {
        val residentA8 = GlyphCacheInventoryEntry.fromRepresentation(
            strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441091").copy(
                glyphId = 65,
                representationRoute = "text.glyph.mask.A8",
                maskFormat = "A8",
            ),
            representation = A8GlyphMask(
                glyphId = 65,
                width = 4,
                height = 3,
                left = -1,
                top = 4,
                rowBytes = 4,
                pixels = listOf(
                    0, 32, 96, 255,
                    16, 64, 128, 240,
                    0, 8, 72, 200,
                ),
            ),
            lifecycleState = GlyphCacheInventoryEntry.ResidentState,
            generation = 8L,
            invalidationToken = "font-source-v8",
        )
        val residentSdf = GlyphCacheInventoryEntry.fromRepresentation(
            strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441092").copy(
                glyphId = 66,
                representationRoute = "text.glyph.mask.SDF",
                maskFormat = "SDF",
                sdfSpreadPx = 8f,
                sdfSourceResolutionPx = 16f,
            ),
            representation = SDFGlyphMask(
                glyphId = 66,
                width = 5,
                height = 5,
                left = -2,
                top = 5,
                distanceRange = 8f,
                pixels = List(25) { index -> (index * 9) % 256 },
                sourceOutlineSha256 = "2b7d4f4c03a0e3b1f34d1bc7b53d8bc967bf5744aca7c13d8f4dd2ba0d9271a8",
            ),
            lifecycleState = GlyphCacheInventoryEntry.ResidentState,
            generation = 8L,
            invalidationToken = "font-source-v8",
        )
        val evictedA8 = GlyphCacheInventoryEntry.fromRepresentation(
            strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441093").copy(
                glyphId = 90,
                representationRoute = "text.glyph.mask.A8",
                maskFormat = "A8",
                paletteIdentity = "palette:legacy-ui",
            ),
            representation = A8GlyphMask(
                glyphId = 90,
                width = 2,
                height = 2,
                left = 0,
                top = 2,
                rowBytes = 2,
                pixels = listOf(
                    255, 180,
                    12, 0,
                ),
            ),
            lifecycleState = GlyphCacheInventoryEntry.EvictedState,
            generation = 7L,
            invalidationToken = "font-source-v7",
        )

        val dump = GlyphCacheInventoryDump(
            dumpId = "glyph-cache-inventory",
            ownerTickets = listOf("KFONT-M9-006"),
            fixtureIds = listOf("a8-cache-resident", "sdf-cache-resident", "evicted-a8-entry"),
            budgetBytes = 512,
            maxEntries = 4,
            residentEntries = listOf(residentA8, residentSdf),
            evictedEntries = listOf(evictedA8),
            requiredDiagnostics = listOf(
                "text.glyph.artifact-budget-exceeded",
                "text.glyph.atlas-generation-stale",
            ),
            nonClaims = listOf(
                "no-complete-target-support-claim",
                "no-gpu-text-route-claim",
                "no-hidden-performance-gate",
                "no-dftext-retirement",
            ),
        )

        assertEquals(3, dump.entryCount)
        assertEquals(residentA8.residentBytes + residentSdf.residentBytes, dump.residentBytes)
        assertEquals(evictedA8.residentBytes, dump.evictedBytes)
        assertEquals(
            readProjectFile("reports/font/fixtures/expected/glyph/glyph-cache-inventory.json"),
            dump.toCanonicalJson(),
        )
    }

    @Test
    fun glyphCacheTelemetryDumpMatchesRepoFixture() {
        val coldRefusal = GlyphCacheBudgetRefusal.fromStrikeKey(
            strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441094").copy(
                glyphId = 91,
                representationRoute = "text.glyph.mask.A8",
                maskFormat = "A8",
            ),
            cacheDomain = "glyph-cache-memory",
            observedBytes = 544,
            advisoryLimitBytes = 512,
        )
        val warmRefusal = GlyphCacheBudgetRefusal.fromStrikeKey(
            strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441095").copy(
                glyphId = 66,
                representationRoute = "text.glyph.mask.SDF",
                maskFormat = "SDF",
                sdfSpreadPx = 8f,
                sdfSourceResolutionPx = 16f,
            ),
            cacheDomain = "atlas-upload-preparation",
            observedBytes = 1536,
            advisoryLimitBytes = 1024,
        )

        val dump = GlyphCacheTelemetryDump(
            dumpId = "glyph-cache-telemetry",
            ownerTickets = listOf("KFONT-M9-006"),
            fixtureIds = listOf("cold-cache-sample", "warm-cache-sample"),
            samples = listOf(
                GlyphCacheTelemetrySample(
                    metadata = GlyphCacheTelemetryMetadata(
                        environmentLabel = "ci-macos-arm64",
                        fontSourceSet = "font-source-liberation-core",
                        unicodeVersion = "16.0.0",
                        cacheState = "cold",
                        sampleLabel = "cold-start",
                        sampleCount = 5,
                    ),
                    routeCounts = listOf(
                        GlyphCacheRouteCount("text.glyph.mask.A8", 2),
                        GlyphCacheRouteCount("text.glyph.mask.SDF", 1),
                    ),
                    timings = listOf(
                        GlyphCacheTimingStats("a8-generation", sampleCount = 5, median = 9, p90 = 11, max = 12),
                        GlyphCacheTimingStats("atlas-pack", sampleCount = 5, median = 6, p90 = 8, max = 9),
                        GlyphCacheTimingStats("sdf-generation", sampleCount = 5, median = 13, p90 = 16, max = 18),
                    ),
                    cacheHitCount = 0,
                    cacheMissCount = 3,
                    evictionCount = 1,
                    invalidationCount = 1,
                    residentBytes = 352,
                    uploadPreparationBytes = 768,
                    artifactBudgetRefusalCount = 1,
                    budgetRefusals = listOf(coldRefusal),
                ),
                GlyphCacheTelemetrySample(
                    metadata = GlyphCacheTelemetryMetadata(
                        environmentLabel = "ci-macos-arm64",
                        fontSourceSet = "font-source-liberation-core",
                        unicodeVersion = "16.0.0",
                        cacheState = "warm",
                        sampleLabel = "warm-repeat",
                        sampleCount = 5,
                    ),
                    routeCounts = listOf(
                        GlyphCacheRouteCount("text.glyph.mask.A8", 2),
                        GlyphCacheRouteCount("text.glyph.mask.SDF", 1),
                    ),
                    timings = listOf(
                        GlyphCacheTimingStats("a8-generation", sampleCount = 5, median = 2, p90 = 3, max = 4),
                        GlyphCacheTimingStats("atlas-pack", sampleCount = 5, median = 1, p90 = 2, max = 2),
                        GlyphCacheTimingStats("sdf-generation", sampleCount = 5, median = 4, p90 = 5, max = 6),
                    ),
                    cacheHitCount = 3,
                    cacheMissCount = 0,
                    evictionCount = 0,
                    invalidationCount = 0,
                    residentBytes = 352,
                    uploadPreparationBytes = 256,
                    artifactBudgetRefusalCount = 1,
                    budgetRefusals = listOf(warmRefusal),
                ),
            ),
            requiredDiagnostics = listOf(
                "text.glyph.artifact-budget-exceeded",
                "text.glyph.telemetry-unavailable",
            ),
            nonClaims = listOf(
                "no-complete-target-support-claim",
                "no-gpu-upload-execution-claim",
                "no-hidden-performance-gate",
            ),
        )

        assertEquals(2, dump.sampleCount)
        assertEquals(
            readProjectFile("reports/font/fixtures/expected/glyph/glyph-cache-telemetry.json"),
            dump.toCanonicalJson(),
        )
    }

    @Test
    fun glyphCacheTelemetryRemainsAdvisoryAndPublishesStableDiagnostics() {
        val refusal = GlyphCacheBudgetRefusal.fromStrikeKey(
            strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441096").copy(
                glyphId = 77,
                representationRoute = "text.glyph.mask.A8",
                maskFormat = "A8",
            ),
            cacheDomain = "atlas-upload-preparation",
            observedBytes = 4096,
            advisoryLimitBytes = 2048,
        )

        assertEquals("text.glyph.artifact-budget-exceeded", refusal.diagnosticCode)
        assertEquals(64, refusal.keyPreimageSha256.length)
        assertTrue(refusal.toCanonicalJson().contains(""""cacheDomain": "atlas-upload-preparation""""))

        val unavailable = GlyphRouteDiagnostic.telemetryUnavailable(
            cacheDomain = "glyph-cache-memory",
            detail = "fixture-mode-disabled",
        )
        assertEquals("text.glyph.telemetry-unavailable", unavailable.route)
        assertTrue(unavailable.message.contains("glyph-cache-memory"))

        assertFailsWith<IllegalArgumentException> {
            GlyphCacheTelemetryMetadata(
                environmentLabel = "ci-macos-arm64",
                fontSourceSet = "font-source-liberation-core",
                unicodeVersion = "16.0.0",
                cacheState = "cold",
                sampleLabel = "should-fail",
                sampleCount = 1,
                releaseGatePromoted = true,
            )
        }
    }

    @Test
    fun glyphArtifactAndCacheMetricDumpsMatchRepoFixtures() {
        val outlineStrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441181").copy(
            glyphId = 41,
            representationRoute = "text.glyph.outline",
        )
        val a8StrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441182").copy(
            glyphId = 42,
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
        )
        val sdfStrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441183").copy(
            glyphId = 43,
            representationRoute = "text.glyph.mask.SDF",
            maskFormat = "SDF",
            sdfSpreadPx = 8f,
            sdfSourceResolutionPx = 16f,
        )
        val bitmapStrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441184").copy(
            glyphId = 44,
            representationRoute = "text.glyph.bitmap.png",
            paletteIdentity = "emoji-palette-v1",
        )
        val colrStrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441185").copy(
            glyphId = 45,
            representationRoute = "text.glyph.color.colr",
            paletteIdentity = "emoji-palette-v1",
        )
        val svgStrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441186").copy(
            glyphId = 46,
            representationRoute = "text.glyph.vector.svg",
            rendererDescriptorVersion = "svg-descriptor-v1",
        )
        val unsupportedStrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441187").copy(
            glyphId = 47,
            representationRoute = "text.glyph.unsupported",
        )
        val outlineRoutePreimage = outlineStrikeKey.toRoutePreimage()
        val a8RoutePreimage = a8StrikeKey.toRoutePreimage()
        val sdfRoutePreimage = sdfStrikeKey.toRoutePreimage()
        val bitmapRoutePreimage = bitmapStrikeKey.toRoutePreimage()
        val colrRoutePreimage = colrStrikeKey.toRoutePreimage()
        val unsupportedRoutePreimage = unsupportedStrikeKey.toRoutePreimage()
        val svgRoutePreimage = svgStrikeKey.toRoutePreimage()
        val artifactRoutePreimages = listOf(
            outlineRoutePreimage,
            a8RoutePreimage,
            sdfRoutePreimage,
            bitmapRoutePreimage,
            colrRoutePreimage,
            unsupportedRoutePreimage,
            svgRoutePreimage,
        )
        val atlasCapacityDiagnostic = GlyphRouteDiagnostic(
            glyphId = 47,
            route = "text.glyph.atlas-capacity-exceeded",
            message = "Glyph atlas capacity exceeded for glyph 47 during bounded M12 metrics production.",
            severity = "warning",
        )
        val artifactPlan = GlyphArtifactPlan(
            run = glyphRun(listOf(41, 42, 43, 44, 45, 46, 47)),
            strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441180"),
            representations = listOf(
                OutlineGlyphRepresentation(
                    glyphId = 41,
                    pathCommands = listOf("M 0 0", "L 6 0", "L 3 8", "Z"),
                    windingRule = "nonZero",
                ),
                A8GlyphMask(
                    glyphId = 42,
                    width = 2,
                    height = 2,
                    left = 0,
                    top = 3,
                    rowBytes = 2,
                    pixels = listOf(0, 64, 192, 255),
                    sourceOutlineSha256 = "1111111111111111111111111111111111111111111111111111111111111111",
                ),
                SDFGlyphMask(
                    glyphId = 43,
                    width = 2,
                    height = 2,
                    left = -1,
                    top = 2,
                    distanceRange = 8f,
                    pixels = listOf(16, 64, 128, 240),
                    sourceOutlineSha256 = "2222222222222222222222222222222222222222222222222222222222222222",
                ),
                BitmapGlyphPlanRef(glyphId = 44, planId = "bitmap-plan-fixture"),
                ColorGlyphPlanRef(glyphId = 45, planId = "color-plan-fixture"),
                SVGGlyphPlanRef(glyphId = 46, planId = "svg-plan-fixture"),
            ),
            diagnostics = listOf(atlasCapacityDiagnostic),
            decisions = listOf(
                GlyphArtifactPlanDecision(
                    index = 0,
                    glyphId = 41,
                    selectedRoute = "text.glyph.outline",
                    representation = "outline",
                    source = "request",
                    keySha256 = outlineRoutePreimage.compactHash,
                    fallbackPolicy = "selected-first-requested-route",
                ),
                GlyphArtifactPlanDecision(
                    index = 1,
                    glyphId = 42,
                    selectedRoute = "text.glyph.mask.A8",
                    representation = "a8",
                    source = "request",
                    keySha256 = a8RoutePreimage.compactHash,
                    fallbackPolicy = "fallback-selected-after-rejections",
                    rejectedAlternatives = listOf(
                        GlyphArtifactRouteRejection(
                            route = "text.glyph.mask.SDF",
                            reason = "text.glyph.SDF-transform-unsupported",
                        ),
                    ),
                ),
                GlyphArtifactPlanDecision(
                    index = 2,
                    glyphId = 43,
                    selectedRoute = "text.glyph.mask.SDF",
                    representation = "sdf",
                    source = "request",
                    keySha256 = sdfRoutePreimage.compactHash,
                    fallbackPolicy = "selected-first-requested-route",
                ),
                GlyphArtifactPlanDecision(
                    index = 3,
                    glyphId = 44,
                    selectedRoute = "text.glyph.bitmap.png",
                    representation = "bitmap",
                    source = "request",
                    keySha256 = bitmapRoutePreimage.compactHash,
                    fallbackPolicy = "selected-first-requested-route",
                ),
                GlyphArtifactPlanDecision(
                    index = 4,
                    glyphId = 45,
                    selectedRoute = "text.glyph.color.colr",
                    representation = "colr",
                    source = "request",
                    keySha256 = colrRoutePreimage.compactHash,
                    fallbackPolicy = "selected-first-requested-route",
                ),
                GlyphArtifactPlanDecision(
                    index = 5,
                    glyphId = 46,
                    selectedRoute = "text.glyph.vector.svg",
                    representation = "svg",
                    source = "request",
                    keySha256 = svgRoutePreimage.compactHash,
                    fallbackPolicy = "selected-first-requested-route",
                ),
                GlyphArtifactPlanDecision(
                    index = 6,
                    glyphId = 47,
                    selectedRoute = "text.glyph.unsupported",
                    representation = null,
                    source = null,
                    keySha256 = unsupportedRoutePreimage.compactHash,
                    fallbackPolicy = "refuse-no-requested-representation",
                    rejectedAlternatives = listOf(
                        GlyphArtifactRouteRejection(
                            route = "text.glyph.vector.svg",
                            reason = "route-unavailable",
                        ),
                    ),
                    diagnostic = atlasCapacityDiagnostic,
                ),
            ),
        )

        val artifactDump = GlyphArtifactMetricsDump(
            dumpId = "glyph-artifact-metrics",
            ownerTickets = listOf("KFONT-M12-004"),
            fixtureIds = listOf("outline-a8-sdf-routes", "color-bitmap-svg-gates"),
            samples = listOf(
                artifactPlan.toMetricsSample(
                    metadata = GlyphCacheTelemetryMetadata(
                        environmentLabel = "ci-macos-arm64",
                        fontSourceSet = "font-source-liberation-core",
                        unicodeVersion = "16.0.0",
                        cacheState = "cold",
                        sampleLabel = "artifact-cold",
                        sampleCount = 5,
                    ),
                    timings = listOf(
                        GlyphCacheTimingStats("a8-generation", sampleCount = 5, median = 8, p90 = 10, max = 11),
                        GlyphCacheTimingStats("bitmap-decode", sampleCount = 5, median = 21, p90 = 24, max = 27),
                        GlyphCacheTimingStats("colr-plan", sampleCount = 5, median = 15, p90 = 18, max = 20),
                        GlyphCacheTimingStats("sdf-generation", sampleCount = 5, median = 17, p90 = 20, max = 23),
                        GlyphCacheTimingStats("svg-plan", sampleCount = 5, median = 19, p90 = 22, max = 26),
                    ),
                    routePreimages = artifactRoutePreimages,
                ),
                artifactPlan.toMetricsSample(
                    metadata = GlyphCacheTelemetryMetadata(
                        environmentLabel = "ci-macos-arm64",
                        fontSourceSet = "font-source-liberation-core",
                        unicodeVersion = "16.0.0",
                        cacheState = "warm",
                        sampleLabel = "artifact-warm",
                        sampleCount = 5,
                    ),
                    timings = listOf(
                        GlyphCacheTimingStats("a8-generation", sampleCount = 5, median = 2, p90 = 3, max = 4),
                        GlyphCacheTimingStats("bitmap-decode", sampleCount = 5, median = 7, p90 = 9, max = 10),
                        GlyphCacheTimingStats("colr-plan", sampleCount = 5, median = 5, p90 = 6, max = 8),
                        GlyphCacheTimingStats("sdf-generation", sampleCount = 5, median = 4, p90 = 5, max = 7),
                        GlyphCacheTimingStats("svg-plan", sampleCount = 5, median = 6, p90 = 8, max = 9),
                    ),
                    routePreimages = artifactRoutePreimages,
                ),
            ),
            requiredDiagnostics = listOf(
                "text.glyph.SDF-transform-unsupported",
                "text.glyph.atlas-capacity-exceeded",
            ),
            nonClaims = listOf(
                "no-complete-target-support-claim",
                "no-gpu-text-route-claim",
                "no-hidden-performance-gate",
                "no-dftext-retirement",
            ),
        )

        val a8Mask = A8GlyphMask(
            glyphId = 142,
            width = 2,
            height = 2,
            left = 1,
            top = 2,
            rowBytes = 2,
            pixels = listOf(10, 20, 30, 40),
            sourceOutlineSha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )
        val sdfMask = SDFGlyphMask(
            glyphId = 143,
            width = 2,
            height = 2,
            left = -1,
            top = 0,
            distanceRange = 16f,
            pixels = listOf(96, 128, 160, 192),
            sourceOutlineSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        )
        val a8AtlasArtifact = GlyphAtlasArtifactEvidence.fromA8(
            buildResult = KotlinGlyphAtlasArtifactBuilder().build(
                masks = listOf(a8Mask),
                placements = listOf(GlyphAtlasPlacement(glyphId = 142, x = 1, y = 1, width = 2, height = 2)),
            ),
            masks = listOf(a8Mask),
            strikeKeysByGlyphId = mapOf(142 to a8StrikeKey.copy(glyphId = 142)),
            generation = 9,
            invalidationToken = "font-source-v9",
            memoryBudgetClass = "atlas-budget.medium",
            lifetimeClass = "glyph-run-frame",
            paddingPx = 1,
        )
        val sdfAtlasArtifact = GlyphAtlasArtifactEvidence.fromSdf(
            buildResult = KotlinSDFGlyphAtlasArtifactBuilder(atlasWidth = 5, padding = 1).build(listOf(sdfMask)),
            masks = listOf(sdfMask),
            strikeKeysByGlyphId = mapOf(143 to sdfStrikeKey.copy(glyphId = 143)),
            generation = 10,
            invalidationToken = "font-source-v10",
            memoryBudgetClass = "atlas-budget.medium",
            lifetimeClass = "glyph-run-frame",
            paddingPx = 1,
            diagnostics = listOf(
                GlyphRouteDiagnostic.atlasGenerationStale(
                    glyphId = 143,
                    artifactGeneration = 9,
                    currentGeneration = 10,
                    invalidationToken = "font-source-v10",
                ),
            ),
        )
        val a8AtlasStrikeKey = a8StrikeKey.copy(glyphId = 142)
        val sdfAtlasStrikeKey = sdfStrikeKey.copy(glyphId = 143)
        val a8Occupancy = GlyphAtlasOccupancyEntry.fromArtifact(
            artifact = a8AtlasArtifact,
            keyPreimageSha256s = listOf(a8AtlasStrikeKey.preimageSha256()),
        )
        val sdfOccupancy = GlyphAtlasOccupancyEntry.fromArtifact(
            artifact = sdfAtlasArtifact,
            keyPreimageSha256s = listOf(sdfAtlasStrikeKey.preimageSha256()),
        )
        val occupancyDump = GlyphAtlasOccupancyDump(
            dumpId = "glyph-atlas-occupancy",
            ownerTickets = listOf("KFONT-M12-004"),
            fixtureIds = listOf("a8-atlas-occupancy", "sdf-atlas-occupancy"),
            atlases = listOf(a8Occupancy, sdfOccupancy),
            requiredDiagnostics = listOf(
                "text.glyph.atlas-capacity-exceeded",
                "text.glyph.atlas-generation-stale",
            ),
            nonClaims = listOf(
                "no-complete-target-support-claim",
                "no-gpu-text-route-claim",
                "no-dftext-retirement",
            ),
        )

        val coldRefusal = GlyphCacheBudgetRefusal.fromStrikeKey(
            strikeKey = a8AtlasStrikeKey,
            cacheDomain = "glyph-cache-memory",
            observedBytes = 544,
            advisoryLimitBytes = 512,
        )
        val warmRefusal = GlyphCacheBudgetRefusal.fromStrikeKey(
            strikeKey = sdfAtlasStrikeKey,
            cacheDomain = "atlas-upload-preparation",
            observedBytes = 1536,
            advisoryLimitBytes = 1024,
        )
        val coldTelemetry = GlyphCacheTelemetrySample(
            metadata = GlyphCacheTelemetryMetadata(
                environmentLabel = "ci-macos-arm64",
                fontSourceSet = "font-source-liberation-core",
                unicodeVersion = "16.0.0",
                cacheState = "cold",
                sampleLabel = "cache-cold",
                sampleCount = 5,
            ),
            routeCounts = listOf(
                GlyphCacheRouteCount("text.glyph.mask.A8", 2),
                GlyphCacheRouteCount("text.glyph.mask.SDF", 1),
            ),
            timings = listOf(
                GlyphCacheTimingStats("a8-generation", sampleCount = 5, median = 9, p90 = 11, max = 12),
                GlyphCacheTimingStats("atlas-pack", sampleCount = 5, median = 6, p90 = 8, max = 9),
                GlyphCacheTimingStats("sdf-generation", sampleCount = 5, median = 13, p90 = 16, max = 18),
            ),
            cacheHitCount = 0,
            cacheMissCount = 3,
            evictionCount = 1,
            invalidationCount = 1,
            residentBytes = 352,
            uploadPreparationBytes = 768,
            artifactBudgetRefusalCount = 1,
            budgetRefusals = listOf(coldRefusal),
        )
        val warmTelemetry = GlyphCacheTelemetrySample(
            metadata = GlyphCacheTelemetryMetadata(
                environmentLabel = "ci-macos-arm64",
                fontSourceSet = "font-source-liberation-core",
                unicodeVersion = "16.0.0",
                cacheState = "warm",
                sampleLabel = "cache-warm",
                sampleCount = 5,
            ),
            routeCounts = listOf(
                GlyphCacheRouteCount("text.glyph.mask.A8", 2),
                GlyphCacheRouteCount("text.glyph.mask.SDF", 1),
            ),
            timings = listOf(
                GlyphCacheTimingStats("a8-generation", sampleCount = 5, median = 2, p90 = 3, max = 4),
                GlyphCacheTimingStats("atlas-pack", sampleCount = 5, median = 1, p90 = 2, max = 2),
                GlyphCacheTimingStats("sdf-generation", sampleCount = 5, median = 4, p90 = 5, max = 6),
            ),
            cacheHitCount = 3,
            cacheMissCount = 0,
            evictionCount = 0,
            invalidationCount = 1,
            residentBytes = 352,
            uploadPreparationBytes = 256,
            artifactBudgetRefusalCount = 1,
            budgetRefusals = listOf(warmRefusal),
        )
        val coldAtlasDiagnostics = listOf(
            GlyphRouteDiagnostic(
                glyphId = 142,
                route = "text.glyph.atlas-capacity-exceeded",
                message = "Atlas capacity pressure remained visible for bounded cache metrics evidence.",
                severity = "warning",
            ),
        )
        val warmAtlasDiagnostics = listOf(
            GlyphRouteDiagnostic.atlasGenerationStale(
                glyphId = 143,
                artifactGeneration = 9,
                currentGeneration = 10,
                invalidationToken = "font-source-v10",
            ),
        )
        val cacheDump = GlyphCacheMetricsDump(
            dumpId = "glyph-cache-metrics",
            ownerTickets = listOf("KFONT-M12-004"),
            fixtureIds = listOf("cold-cache-metrics", "warm-cache-metrics"),
            samples = listOf(
                coldTelemetry.toMetricsSample(
                    atlasOccupancy = a8Occupancy,
                    glyphIds = setOf(142),
                    atlasDiagnostics = coldAtlasDiagnostics,
                ),
                warmTelemetry.toMetricsSample(
                    atlasOccupancy = sdfOccupancy,
                    glyphIds = setOf(143),
                    atlasDiagnostics = warmAtlasDiagnostics,
                ),
            ),
            requiredDiagnostics = listOf(
                "text.glyph.artifact-budget-exceeded",
                "text.glyph.atlas-capacity-exceeded",
                "text.glyph.atlas-generation-stale",
            ),
            nonClaims = listOf(
                "no-complete-target-support-claim",
                "no-gpu-text-route-claim",
                "no-hidden-performance-gate",
                "no-dftext-retirement",
            ),
        )

        assertEquals(2, artifactDump.sampleCount)
        assertEquals(2, occupancyDump.atlasCount)
        assertEquals(2, cacheDump.sampleCount)
        assertEquals(
            readProjectFile("reports/font/fixtures/expected/glyph/glyph-artifact-metrics.json"),
            artifactDump.toCanonicalJson(),
        )
        assertEquals(
            readProjectFile("reports/font/fixtures/expected/glyph/glyph-atlas-occupancy.json"),
            occupancyDump.toCanonicalJson(),
        )
        assertEquals(
            readProjectFile("reports/font/fixtures/expected/glyph/glyph-cache-metrics.json"),
            cacheDump.toCanonicalJson(),
        )
    }

    @Test
    fun glyphArtifactMetricsProducerRejectsRoutePreimagesThatDoNotMatchDecisionTrace() {
        val outlineStrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441189").copy(
            glyphId = 51,
            representationRoute = "text.glyph.outline",
        )
        val routePreimage = outlineStrikeKey.toRoutePreimage()
        val artifactPlan = GlyphArtifactPlan(
            run = glyphRun(listOf(51)),
            strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441190"),
            representations = listOf(
                OutlineGlyphRepresentation(
                    glyphId = 51,
                    pathCommands = listOf("M 0 0", "L 4 0", "L 2 5", "Z"),
                ),
            ),
            decisions = listOf(
                GlyphArtifactPlanDecision(
                    index = 0,
                    glyphId = 51,
                    selectedRoute = "text.glyph.mask.A8",
                    representation = "a8",
                    source = "request",
                    keySha256 = routePreimage.compactHash,
                    fallbackPolicy = "selected-first-requested-route",
                ),
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            artifactPlan.toMetricsSample(
                metadata = GlyphCacheTelemetryMetadata(
                    environmentLabel = "ci-macos-arm64",
                    fontSourceSet = "font-source-liberation-core",
                    unicodeVersion = "16.0.0",
                    cacheState = "cold",
                    sampleLabel = "decision-mismatch",
                    sampleCount = 1,
                ),
                timings = emptyList(),
                routePreimages = listOf(routePreimage),
            )
        }

        assertTrue(failure.message!!.contains("glyphId, route, and compactHash"))
    }

    @Test
    fun glyphArtifactMetricsProducerRejectsRoutePreimagesThatDoNotMatchDecisionHash() {
        val outlineStrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441192").copy(
            glyphId = 52,
            representationRoute = "text.glyph.outline",
        )
        val routePreimage = outlineStrikeKey.toRoutePreimage()
        val artifactPlan = GlyphArtifactPlan(
            run = glyphRun(listOf(52)),
            strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441193"),
            representations = listOf(
                OutlineGlyphRepresentation(
                    glyphId = 52,
                    pathCommands = listOf("M 0 0", "L 5 0", "L 2 6", "Z"),
                ),
            ),
            decisions = listOf(
                GlyphArtifactPlanDecision(
                    index = 0,
                    glyphId = 52,
                    selectedRoute = "text.glyph.outline",
                    representation = "outline",
                    source = "request",
                    keySha256 = "f".repeat(64),
                    fallbackPolicy = "selected-first-requested-route",
                ),
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            artifactPlan.toMetricsSample(
                metadata = GlyphCacheTelemetryMetadata(
                    environmentLabel = "ci-macos-arm64",
                    fontSourceSet = "font-source-liberation-core",
                    unicodeVersion = "16.0.0",
                    cacheState = "cold",
                    sampleLabel = "decision-hash-mismatch",
                    sampleCount = 1,
                ),
                timings = emptyList(),
                routePreimages = listOf(routePreimage),
            )
        }

        assertTrue(failure.message!!.contains("glyphId, route, and compactHash"))
    }

    @Test
    fun glyphCacheMetricsProducerRejectsAtlasDiagnosticsOutsideSampleGlyphScope() {
        val strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441191").copy(
            glyphId = 142,
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
        )
        val telemetry = GlyphCacheTelemetrySample(
            metadata = GlyphCacheTelemetryMetadata(
                environmentLabel = "ci-macos-arm64",
                fontSourceSet = "font-source-liberation-core",
                unicodeVersion = "16.0.0",
                cacheState = "cold",
                sampleLabel = "scope-check",
                sampleCount = 1,
            ),
            routeCounts = listOf(GlyphCacheRouteCount("text.glyph.mask.A8", 1)),
            timings = listOf(GlyphCacheTimingStats("atlas-pack", sampleCount = 1, median = 1, p90 = 1, max = 1)),
            cacheHitCount = 0,
            cacheMissCount = 1,
            evictionCount = 0,
            invalidationCount = 0,
            residentBytes = 64,
            uploadPreparationBytes = 128,
            artifactBudgetRefusalCount = 1,
            budgetRefusals = listOf(
                GlyphCacheBudgetRefusal.fromStrikeKey(
                    strikeKey = strikeKey,
                    cacheDomain = "glyph-cache-memory",
                    observedBytes = 96,
                    advisoryLimitBytes = 64,
                ),
            ),
        )
        val occupancy = GlyphAtlasOccupancyEntry(
            artifactType = "GlyphAtlasArtifact",
            artifactId = "a".repeat(64),
            generation = 0,
            invalidationToken = "font-source-v9",
            width = 2,
            height = 2,
            rowStride = 2,
            entryCount = 1,
            occupiedPixels = 4,
            totalPixels = 4,
            occupancyRatio = 1f,
            keyPreimageSha256s = listOf(strikeKey.preimageSha256()),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            telemetry.toMetricsSample(
                atlasOccupancy = occupancy,
                glyphIds = setOf(142),
                atlasDiagnostics = listOf(
                    GlyphRouteDiagnostic.atlasGenerationStale(
                        glyphId = 143,
                        artifactGeneration = 0,
                        currentGeneration = 1,
                        invalidationToken = "font-source-v10",
                    ),
                ),
            )
        }

        assertTrue(failure.message!!.contains("already be scoped"))
    }

    @Test
    fun glyphCacheMetricsProducerRejectsAtlasDiagnosticsWithoutSampleGlyphId() {
        val strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441194").copy(
            glyphId = 142,
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
        )
        val telemetry = GlyphCacheTelemetrySample(
            metadata = GlyphCacheTelemetryMetadata(
                environmentLabel = "ci-macos-arm64",
                fontSourceSet = "font-source-liberation-core",
                unicodeVersion = "16.0.0",
                cacheState = "cold",
                sampleLabel = "null-scope-check",
                sampleCount = 1,
            ),
            routeCounts = listOf(GlyphCacheRouteCount("text.glyph.mask.A8", 1)),
            timings = listOf(GlyphCacheTimingStats("atlas-pack", sampleCount = 1, median = 1, p90 = 1, max = 1)),
            cacheHitCount = 0,
            cacheMissCount = 1,
            evictionCount = 0,
            invalidationCount = 0,
            residentBytes = 64,
            uploadPreparationBytes = 128,
            artifactBudgetRefusalCount = 1,
            budgetRefusals = listOf(
                GlyphCacheBudgetRefusal.fromStrikeKey(
                    strikeKey = strikeKey,
                    cacheDomain = "glyph-cache-memory",
                    observedBytes = 96,
                    advisoryLimitBytes = 64,
                ),
            ),
        )
        val occupancy = GlyphAtlasOccupancyEntry(
            artifactType = "GlyphAtlasArtifact",
            artifactId = "b".repeat(64),
            generation = 0,
            invalidationToken = "font-source-v9",
            width = 2,
            height = 2,
            rowStride = 2,
            entryCount = 1,
            occupiedPixels = 4,
            totalPixels = 4,
            occupancyRatio = 1f,
            keyPreimageSha256s = listOf(strikeKey.preimageSha256()),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            telemetry.toMetricsSample(
                atlasOccupancy = occupancy,
                glyphIds = setOf(142),
                atlasDiagnostics = listOf(
                    GlyphRouteDiagnostic(
                        glyphId = null,
                        route = "text.glyph.atlas-capacity-exceeded",
                        message = "Atlas-wide diagnostic must be expanded to sample glyph IDs before cache metrics emission.",
                        severity = "warning",
                    ),
                ),
            )
        }

        assertTrue(failure.message!!.contains("already be scoped"))
    }

    @Test
    fun glyphAtlasOccupancyRejectsEntriesThatExceedAtlasCapacity() {
        val strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441188").copy(
            glyphId = 48,
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
        )
        val artifact = GlyphAtlasArtifactEvidence(
            artifactType = "GlyphAtlasArtifact",
            artifactKeySha256 = "a".repeat(64),
            generation = 0,
            textureFormat = "r8unorm",
            width = 4,
            height = 4,
            rowStride = 4,
            memoryBudgetClass = "glyph-cache-memory",
            lifetimeClass = "resident",
            invalidationToken = "atlas-generation-0",
            entries = listOf(
                GlyphAtlasArtifactEvidenceEntry(
                    glyphId = checkNotNull(strikeKey.glyphId),
                    strikeKeySha256 = strikeKey.toRoutePreimage().compactHash,
                    x = 0,
                    y = 0,
                    width = 5,
                    height = 5,
                    sourceLeft = 0,
                    sourceTop = 0,
                    sourceWidth = 5,
                    sourceHeight = 5,
                    paddingPx = 0,
                    sourceMaskSha256 = "b".repeat(64),
                ),
            ),
            sourceMaskHashes = listOf("b".repeat(64)),
            uploadByteSha256 = "c".repeat(64),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            GlyphAtlasOccupancyEntry.fromArtifact(
                artifact = artifact,
                keyPreimageSha256s = listOf(strikeKey.preimageSha256()),
            )
        }

        assertTrue(failure.message.orEmpty().contains("occupiedPixels"))
    }

    @Test
    fun glyphCacheMetricsRejectOccupancyRatioAboveOne() {
        val strikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441189").copy(
            glyphId = 49,
            representationRoute = "text.glyph.mask.SDF",
            maskFormat = "SDF",
            sdfSpreadPx = 8f,
            sdfSourceResolutionPx = 16f,
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            GlyphCacheMetricsSample(
                metadata = GlyphCacheTelemetryMetadata(
                    environmentLabel = "ci-macos-arm64",
                    fontSourceSet = "font-source-liberation-core",
                    unicodeVersion = "16.0.0",
                    cacheState = "cold",
                    sampleLabel = "invalid-occupancy",
                    sampleCount = 1,
                ),
                atlasArtifactId = "d".repeat(64),
                strikeKeyCount = 1,
                keyPreimageSha256s = listOf(strikeKey.preimageSha256()),
                occupancyRatio = 1.1f,
                timings = listOf(
                    GlyphCacheTimingStats("atlas-pack", sampleCount = 1, median = 1, p90 = 1, max = 1),
                ),
                cacheHitCount = 0,
                cacheMissCount = 1,
                evictionCount = 0,
                atlasCapacityPressureCount = 0,
                staleGenerationRefusalCount = 0,
                invalidationTokenChangeCount = 0,
                cacheMemoryBytes = 128,
                uploadByteExpectation = 64,
                artifactBudgetRefusalCount = 0,
                budgetRefusals = emptyList(),
            )
        }

        assertTrue(failure.message.orEmpty().contains("occupancyRatio"))
    }

    @Test
    fun glyphCacheMetricsRejectBudgetRefusalsOutsideSampleKeySet() {
        val sampleStrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441190").copy(
            glyphId = 50,
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
        )
        val refusalStrikeKey = strikeKey("550e8400-e29b-41d4-a716-446655441191").copy(
            glyphId = 51,
            representationRoute = "text.glyph.mask.A8",
            maskFormat = "A8",
        )
        val refusal = GlyphCacheBudgetRefusal.fromStrikeKey(
            strikeKey = refusalStrikeKey,
            cacheDomain = "glyph-cache-memory",
            observedBytes = 544,
            advisoryLimitBytes = 512,
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            GlyphCacheMetricsSample(
                metadata = GlyphCacheTelemetryMetadata(
                    environmentLabel = "ci-macos-arm64",
                    fontSourceSet = "font-source-liberation-core",
                    unicodeVersion = "16.0.0",
                    cacheState = "cold",
                    sampleLabel = "invalid-budget-refusal-key",
                    sampleCount = 1,
                ),
                atlasArtifactId = "e".repeat(64),
                strikeKeyCount = 1,
                keyPreimageSha256s = listOf(sampleStrikeKey.preimageSha256()),
                occupancyRatio = 0.5f,
                timings = listOf(
                    GlyphCacheTimingStats("atlas-pack", sampleCount = 1, median = 1, p90 = 1, max = 1),
                ),
                cacheHitCount = 0,
                cacheMissCount = 1,
                evictionCount = 0,
                atlasCapacityPressureCount = 0,
                staleGenerationRefusalCount = 0,
                invalidationTokenChangeCount = 0,
                cacheMemoryBytes = 128,
                uploadByteExpectation = 64,
                artifactBudgetRefusalCount = 1,
                budgetRefusals = listOf(refusal),
            )
        }

        assertTrue(failure.message.orEmpty().contains("budgetRefusals"))
    }

    @Test
    fun a8SdfAtlasLifecycleGoldenRecordsFixtureDiagnosticsAndNonClaims() {
        val dump = readProjectFile("reports/font/fixtures/expected/glyph/a8-sdf-atlas-lifecycle.json")

        assertEquals("a8-sdf-atlas-lifecycle", jsonStringField(dump, "dumpId"))
        assertEquals(listOf("PKT-10D"), jsonStringArrayField(dump, "ownerTickets"))
        assertEquals(listOf("font-source-liberation-core"), jsonStringArrayField(dump, "fixtureIds"))
        assertEquals(
            listOf(
                "text.glyph.SDF-transform-unsupported",
                "text.glyph.SDF-generation-failed",
                "text.glyph.atlas-capacity-exceeded",
                "text.glyph.atlas-generation-stale",
                "text.glyph.cache-key-nondeterministic",
                "text.glyph.artifact-budget-exceeded",
            ),
            jsonStringArrayField(dump, "requiredDiagnostics"),
        )
        assertEquals(
            listOf(
                "no-complete-target-support-claim",
                "no-complete-a8-atlas-claim",
                "no-complete-sdf-production-claim",
                "no-complete-atlas-lifecycle-claim",
                "no-gpu-text-route-claim",
                "no-gpu-upload-execution-claim",
                "no-renderer-resource-ownership-claim",
            ),
            jsonStringArrayField(dump, "nonClaims"),
        )
    }

    @Test
    fun `A8 rasterizer emits deterministic intermediate coverage for diagonal edge`() {
        val generator = object : GlyphMaskGenerator {}
        val outline = OutlineGlyphRepresentation(
            glyphId = 7,
            pathCommands = listOf(
                "M 0 0",
                "L 4 0",
                "L 0 4",
                "Z",
            ),
        )
        val key = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655442001")
        val first = generator.generate(outline, key)
        val second = generator.generate(outline, key)

        assertEquals(first, second, "Repeated rasterization must produce identical masks")
        assertEquals(7, first.glyphId)
        assertEquals(4, first.width)
        assertEquals(4, first.height)
        assertEquals(
            listOf(
                255, 255, 255, 96,
                255, 255, 96, 0,
                255, 96, 0, 0,
                96, 0, 0, 0,
            ),
            first.pixels,
        )
    }

    @Test
    fun `A8 rasterizer returns identical masks for same outline with same strike`() {
        val generator = object : GlyphMaskGenerator {}
        val outline = OutlineGlyphRepresentation(
            glyphId = 8,
            pathCommands = listOf(
                "M 1 1",
                "L 5 1",
                "L 5 5",
                "L 1 5",
                "Z",
            ),
        )
        val key = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655442002")
        val mask1 = generator.generate(outline, key)
        val mask2 = generator.generate(outline, key)
        assertEquals(mask1, mask2)
    }

    @Test
    fun `A8 rasterizer coverage sums are preserved under strike identity`() {
        val generator = object : GlyphMaskGenerator {}
        val outline = OutlineGlyphRepresentation(
            glyphId = 9,
            pathCommands = listOf(
                "M 1 1",
                "L 5 1",
                "L 5 5",
                "L 1 5",
                "Z",
            ),
        )
        val keyA = strikeKey(typefaceUuid = "550e8400-e29b-41d4-a716-446655442003")
            .copy(representationRoute = "text.glyph.mask.A8", maskFormat = "A8")
        val keyB = keyA.copy(glyphId = 9)
        val maskA = generator.generate(outline, keyA)
        val maskB = generator.generate(outline, keyB)
        assertEquals(maskA.pixels, maskB.pixels)
        assertEquals(maskA.left, maskB.left)
        assertEquals(maskA.top, maskB.top)
    }

    @Test
    fun `A8 glyph mask snapshots mutable pixel source and preserves summary hash`() {
        val sourcePixels = mutableListOf(
            0, 64,
            128, 255,
        )
        val mask = A8GlyphMask(
            glyphId = 10,
            width = 2,
            height = 2,
            pixels = sourcePixels,
        )
        val originalSummary = GlyphMaskSummary.fromA8Mask(mask)

        sourcePixels.fill(7)

        assertEquals(listOf(0, 64, 128, 255), mask.pixels)
        assertEquals(originalSummary, GlyphMaskSummary.fromA8Mask(mask))
    }

    @Test
    fun `A8 glyph mask snapshots mutable diagnostic source`() {
        val diagnostic = GlyphRouteDiagnostic(
            glyphId = 10,
            route = "text.glyph.mask.A8",
            message = "fixture diagnostic",
            severity = "warning",
        )
        val sourceDiagnostics = mutableListOf(diagnostic)
        val mask = A8GlyphMask(
            glyphId = 10,
            width = 1,
            height = 1,
            pixels = listOf(255),
            diagnostics = sourceDiagnostics,
        )

        sourceDiagnostics.clear()

        assertEquals(listOf(diagnostic), mask.diagnostics)
    }

    private fun a8Mask(glyphId: Int, width: Int, height: Int): A8GlyphMask =
        A8GlyphMask(
            glyphId = glyphId,
            width = width,
            height = height,
            pixels = List(width * height) { glyphId },
        )

    private fun strikeKey(typefaceUuid: String): GlyphStrikeKey =
        GlyphStrikeKey(
            typefaceId = typefaceId(typefaceUuid),
            sizePx = 16f,
        )

    private fun glyphRun(glyphIds: List<Int>): ShapedGlyphRun =
        ShapedGlyphRun(
            glyphIds = glyphIds,
            typefaceId = typefaceId("550e8400-e29b-41d4-a716-446655441101"),
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
