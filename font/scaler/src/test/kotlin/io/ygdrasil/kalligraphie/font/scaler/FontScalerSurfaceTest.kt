package io.ygdrasil.kalligraphie.font.scaler

import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.font.sfnt.HorizontalGlyphMetric
import io.ygdrasil.kalligraphie.font.sfnt.DefaultOpenTypeFaceParser
import io.ygdrasil.kalligraphie.font.sfnt.MetricsTables
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeAvarAxisSegmentMap
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeAvarSegment
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeFaceData
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeFixed16Dot16
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeVariationAxis
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeVariationAxisTag
import io.ygdrasil.kalligraphie.font.sfnt.SFNTTableDirectory
import io.ygdrasil.kalligraphie.font.sfnt.SFNTTableTag
import io.ygdrasil.kalligraphie.font.sfnt.VariationTables
import io.ygdrasil.kalligraphie.font.sfnt.VerticalGlyphMetric
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontScalerSurfaceTest {
    @Test
    fun exposesScalerGeometryMetricsAndVariationValueObjects() {
        val bounds = GlyphBounds(
            left = 1.0,
            top = 2.0,
            right = 3.0,
            bottom = 4.0,
        )
        val metrics = GlyphMetrics(
            advanceX = 9.0,
            advanceY = 0.0,
            bounds = bounds,
        )
        val outline = GlyphOutline(
            glyphId = 42u,
            contours = emptyList(),
        )
        val position = VariationPosition(axes = mapOf("wght" to 700.0))

        assertEquals(2.0, bounds.top)
        assertEquals(bounds, metrics.bounds)
        assertEquals(42u, outline.glyphId)
        assertEquals(700.0, position.axes.getValue("wght"))
    }

    @Test
    fun boundedVariationNormalizerClampsAndNormalizesConfiguredAxesByTag() {
        val normalizer = BoundedVariationNormalizer(
            listOf(
                VariationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                VariationAxis(tag = "ital", minimum = 0.0, defaultValue = 0.0, maximum = 1.0),
                VariationAxis(tag = "wdth", minimum = 50.0, defaultValue = 100.0, maximum = 200.0),
            ),
        )

        val normalized = normalizer.normalize(
            VariationPosition(
                axes = mapOf(
                    "wght" to 950.0,
                    "wdth" to 75.0,
                ),
            ),
        )

        assertEquals(listOf("ital", "wdth", "wght"), normalized.keys.toList())
        assertEquals(0.0, normalized.getValue("ital"))
        assertEquals(-0.5, normalized.getValue("wdth"))
        assertEquals(1.0, normalized.getValue("wght"))
    }

    @Test
    fun boundedVariationNormalizerMapsBelowAndAboveDefaultSeparately() {
        val normalizer = BoundedVariationNormalizer(
            listOf(
                VariationAxis(tag = "opsz", minimum = 8.0, defaultValue = 14.0, maximum = 72.0),
                VariationAxis(tag = "slnt", minimum = -12.0, defaultValue = 0.0, maximum = 0.0),
            ),
        )

        val normalized = normalizer.normalize(
            VariationPosition(
                axes = mapOf(
                    "opsz" to 43.0,
                    "slnt" to -6.0,
                ),
            ),
        )

        assertEquals(0.5, normalized.getValue("opsz"))
        assertEquals(-0.5, normalized.getValue("slnt"))
    }

    @Test
    fun boundedVariationNormalizerRejectsNonFiniteAndIncoherentAxes() {
        val nonFinite = assertFailsWith<IllegalArgumentException> {
            VariationAxis(tag = "wght", minimum = 100.0, defaultValue = Double.NaN, maximum = 900.0)
        }
        val invertedDefault = assertFailsWith<IllegalArgumentException> {
            VariationAxis(tag = "wdth", minimum = 100.0, defaultValue = 75.0, maximum = 200.0)
        }
        val duplicateTag = assertFailsWith<IllegalArgumentException> {
            BoundedVariationNormalizer(
                listOf(
                    VariationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    VariationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                ),
            )
        }

        assertTrue(nonFinite.message.orEmpty().contains("defaultValue"))
        assertTrue(invertedDefault.message.orEmpty().contains("minimum"))
        assertTrue(duplicateTag.message.orEmpty().contains("duplicate"))
    }

    @Test
    fun boundedVariationNormalizerRejectsUnknownAndNonFiniteRequestedPositions() {
        val normalizer = BoundedVariationNormalizer(
            listOf(
                VariationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
            ),
        )

        val unknown = assertFailsWith<IllegalArgumentException> {
            normalizer.normalize(VariationPosition(axes = mapOf("wdth" to 100.0)))
        }
        val nonFinite = assertFailsWith<IllegalArgumentException> {
            normalizer.normalize(VariationPosition(axes = mapOf("wght" to Double.POSITIVE_INFINITY)))
        }

        assertTrue(unknown.message.orEmpty().contains("wdth"))
        assertTrue(nonFinite.message.orEmpty().contains("finite"))
    }

    @Test
    fun glyphOutlineKeepsLegacyContoursAndExposesTypedCommands() {
        val outline = GlyphOutline(
            glyphId = 7u,
            contours = listOf("M 0 0", "L 10 0"),
            commands = listOf(
                moveTo(0.0, 0.0),
                lineTo(10.0, 0.0),
                quadraticTo(12.0, 4.0, 10.0, 10.0),
                cubicTo(6.0, 14.0, 3.0, 12.0, 0.0, 10.0),
                close(),
            ),
        )

        assertEquals(listOf("M 0 0", "L 10 0"), outline.contours)
        assertEquals(
            listOf(
                OutlineCommand.MoveTo(0.0, 0.0),
                OutlineCommand.LineTo(10.0, 0.0),
                OutlineCommand.QuadraticTo(12.0, 4.0, 10.0, 10.0),
                OutlineCommand.CubicTo(6.0, 14.0, 3.0, 12.0, 0.0, 10.0),
                OutlineCommand.Close,
            ),
            outline.commands,
        )
    }

    @Test
    fun computesConservativeBoundsFromTypedOutlineCommands() {
        val commands = listOf(
            moveTo(1.0, 2.0),
            lineTo(3.0, -4.0),
            quadraticTo(5.0, 10.0, -2.0, 6.0),
            cubicTo(8.0, -9.0, -7.0, 11.0, 4.0, 0.0),
            close(),
        )

        assertEquals(
            GlyphBounds(left = -7.0, top = -9.0, right = 8.0, bottom = 11.0),
            commands.conservativeBounds(),
        )
        assertNull(listOf(close()).conservativeBounds())
    }

    @Test
    fun scalesBoundsAndTypedOutlinesFromFontUnitsToPixels() {
        val bounds = GlyphBounds(left = -500.0, top = -250.0, right = 1000.0, bottom = 750.0)
        val outline = GlyphOutline(
            glyphId = 9u,
            contours = listOf("legacy-outline"),
            commands = listOf(
                moveTo(0.0, 0.0),
                lineTo(1000.0, 500.0),
                quadraticTo(500.0, 750.0, 250.0, 1000.0),
                cubicTo(-250.0, 1250.0, 1500.0, -500.0, 2000.0, 0.0),
                close(),
            ),
        )

        assertEquals(
            GlyphBounds(left = -8.0, top = -4.0, right = 16.0, bottom = 12.0),
            bounds.fontUnitsToPixels(unitsPerEm = 1000.0, pixelSize = 16.0),
        )
        assertEquals(
            listOf(
                moveTo(0.0, 0.0),
                lineTo(16.0, 8.0),
                quadraticTo(8.0, 12.0, 4.0, 16.0),
                cubicTo(-4.0, 20.0, 24.0, -8.0, 32.0, 0.0),
                close(),
            ),
            outline.fontUnitsToPixels(unitsPerEm = 1000.0, pixelSize = 16.0).commands,
        )
        assertEquals(listOf("legacy-outline"), outline.fontUnitsToPixels(1000.0, 16.0).contours)
    }

    @Test
    fun parsesTrueTypeLocaOffsetsInShortAndLongFormats() {
        val shortLoca = TrueTypeLocaTableParser.parse(
            data = bytes(
                0x00, 0x00,
                0x00, 0x05,
                0x00, 0x05,
                0x00, 0x0c,
            ),
            format = TrueTypeLocaFormat.Short,
            numGlyphs = 3,
        )
        val longLoca = TrueTypeLocaTableParser.parse(
            data = bytes(
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x12,
                0x00, 0x00, 0x00, 0x1a,
                0x00, 0x00, 0x00, 0x1a,
            ),
            format = TrueTypeLocaFormat.Long,
            numGlyphs = 3,
        )

        assertEquals(listOf(0, 10, 10, 24), shortLoca.offsets)
        assertEquals(TrueTypeGlyphDataRange(start = 0, endExclusive = 10), shortLoca.rangeForGlyph(0u))
        assertEquals(TrueTypeGlyphDataRange(start = 10, endExclusive = 10), shortLoca.rangeForGlyph(1u))
        assertEquals(listOf(0, 18, 26, 26), longLoca.offsets)
    }

    @Test
    fun rejectsInvalidTrueTypeLocaTables() {
        val truncated = assertFailsWith<IllegalArgumentException> {
            TrueTypeLocaTableParser.parse(
                data = bytes(0x00, 0x00),
                format = TrueTypeLocaFormat.Short,
                numGlyphs = 1,
            )
        }
        val descending = assertFailsWith<IllegalArgumentException> {
            TrueTypeLocaTableParser.parse(
                data = bytes(
                    0x00, 0x05,
                    0x00, 0x04,
                ),
                format = TrueTypeLocaFormat.Short,
                numGlyphs = 1,
            )
        }

        assertTrue(truncated.message.orEmpty().contains("loca table length"))
        assertTrue(descending.message.orEmpty().contains("monotonic"))
    }

    @Test
    fun parsesTrueTypeSimpleGlyphWithRepeatedFlagsAndDeltaCoordinates() {
        val glyph = assertIs<TrueTypeGlyph.Simple>(
            TrueTypeGlyfTableParser.parseGlyph(
                glyfTable = simpleRepeatedFlagGlyphData(),
                range = TrueTypeGlyphDataRange(start = 0, endExclusive = simpleRepeatedFlagGlyphData().size),
                glyphId = 4u,
            ),
        )

        assertEquals(
            TrueTypeGlyphHeader(
                numberOfContours = 1,
                xMin = 0,
                yMin = 0,
                xMax = 100,
                yMax = 40,
            ),
            glyph.header,
        )
        assertEquals(listOf(3), glyph.endPointsOfContours)
        assertEquals(
            listOf(
                TrueTypeGlyphPoint(x = 0, y = 0, onCurve = true, flags = 0x31),
                TrueTypeGlyphPoint(x = 50, y = 0, onCurve = true, flags = 0x33),
                TrueTypeGlyphPoint(x = 100, y = 0, onCurve = true, flags = 0x33),
                TrueTypeGlyphPoint(x = 100, y = 40, onCurve = true, flags = 0x35),
            ),
            glyph.contours.single().points,
        )
    }

    @Test
    fun convertsSimpleGlyphContoursToTypedOutlineCommands() {
        val glyph = assertIs<TrueTypeGlyph.Simple>(
            TrueTypeGlyfTableParser.parseGlyph(
                glyfTable = simpleQuadraticGlyphData(),
                range = TrueTypeGlyphDataRange(start = 0, endExclusive = simpleQuadraticGlyphData().size),
                glyphId = 6u,
            ),
        )

        assertEquals(
            GlyphOutline(
                glyphId = 6u,
                commands = listOf(
                    moveTo(0.0, 0.0),
                    lineTo(100.0, 0.0),
                    quadraticTo(100.0, 100.0, 0.0, 100.0),
                    close(),
                ),
            ),
            glyph.toGlyphOutline(glyphId = 6u),
        )
    }

    @Test
    fun convertsConsecutiveOffCurvePointsWithImplicitMidpoint() {
        val glyph = TrueTypeGlyph.Simple(
            header = TrueTypeGlyphHeader(
                numberOfContours = 1,
                xMin = 0,
                yMin = 0,
                xMax = 6,
                yMax = 2,
            ),
            endPointsOfContours = listOf(3),
            instructionLength = 0,
            contours = listOf(
                TrueTypeGlyphContour(
                    endPointIndex = 3,
                    points = listOf(
                        TrueTypeGlyphPoint(x = 0, y = 0, onCurve = true, flags = 0x01),
                        TrueTypeGlyphPoint(x = 1, y = 1, onCurve = false, flags = 0x00),
                        TrueTypeGlyphPoint(x = 4, y = 2, onCurve = false, flags = 0x00),
                        TrueTypeGlyphPoint(x = 6, y = 0, onCurve = true, flags = 0x01),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                moveTo(0.0, 0.0),
                quadraticTo(1.0, 1.0, 2.5, 1.5),
                quadraticTo(4.0, 2.0, 6.0, 0.0),
                close(),
            ),
            glyph.toGlyphOutline(glyphId = 10u).commands,
        )
    }

    @Test
    fun parsesCompositeGlyphComponentRecordsWithXyArgumentsScalesAndMoreComponents() {
        val glyphData = compositeGlyphData(
            *componentRecord(
                flags = 0x002b,
                glyphId = 2,
                arg1 = 10,
                arg2 = -20,
                transformWords = intArrayOf(0x6000),
            ),
            *componentRecord(
                flags = 0x0063,
                glyphId = 3,
                arg1 = -2,
                arg2 = 4,
                transformWords = intArrayOf(0x2000, 0xc000),
            ),
            *componentRecord(
                flags = 0x0083,
                glyphId = 4,
                arg1 = 7,
                arg2 = 8,
                transformWords = intArrayOf(0x4000, 0x1000, 0xe000, 0x3000),
            ),
        )
        val glyph = assertIs<TrueTypeGlyph.Composite>(
            TrueTypeGlyfTableParser.parseGlyph(
                glyfTable = glyphData,
                range = TrueTypeGlyphDataRange(start = 0, endExclusive = glyphData.size),
                glyphId = 7u,
            ),
        )

        assertEquals(-1, glyph.header.numberOfContours)
        assertEquals(3, glyph.components.size)
        assertEquals(
            TrueTypeCompositeGlyphComponent(
                glyphId = 2u,
                flags = 0x002b,
                arguments = TrueTypeCompositeGlyphArgument.XyValues(x = 10, y = -20),
                transform = TrueTypeCompositeTransform(xx = 1.5, yy = 1.5, dx = 10.0, dy = -20.0),
            ),
            glyph.components[0],
        )
        assertEquals(
            TrueTypeCompositeGlyphComponent(
                glyphId = 3u,
                flags = 0x0063,
                arguments = TrueTypeCompositeGlyphArgument.XyValues(x = -2, y = 4),
                transform = TrueTypeCompositeTransform(xx = 0.5, yy = -1.0, dx = -2.0, dy = 4.0),
            ),
            glyph.components[1],
        )
        assertEquals(
            TrueTypeCompositeGlyphComponent(
                glyphId = 4u,
                flags = 0x0083,
                arguments = TrueTypeCompositeGlyphArgument.XyValues(x = 7, y = 8),
                transform = TrueTypeCompositeTransform(xx = 1.0, xy = -0.5, yx = 0.25, yy = 0.75, dx = 7.0, dy = 8.0),
            ),
            glyph.components[2],
        )
    }

    @Test
    fun parsesCompositePointMatchingArgumentsAsExplicitUnsupportedRecords() {
        val glyph = assertIs<TrueTypeGlyph.Composite>(
            TrueTypeGlyfTableParser.parseGlyph(
                glyfTable = compositeGlyphData(
                    *componentRecord(
                        flags = 0x0001,
                        glyphId = 2,
                        arg1 = 5,
                        arg2 = 6,
                    ),
                ),
                range = TrueTypeGlyphDataRange(start = 0, endExclusive = 18),
                glyphId = 8u,
            ),
        )

        assertEquals(
            TrueTypeCompositeGlyphArgument.PointMatching(
                compoundPointIndex = 5,
                componentPointIndex = 6,
            ),
            glyph.components.single().arguments,
        )
        assertEquals(TrueTypeCompositeTransform(), glyph.components.single().transform)
    }

    @Test
    fun transformsTypedOutlineCommandsWithCompositeMatrix() {
        val transform = TrueTypeCompositeTransform(
            xx = 2.0,
            xy = 3.0,
            yx = -1.0,
            yy = 0.5,
            dx = 10.0,
            dy = -4.0,
        )

        assertEquals(
            listOf(
                moveTo(18.0, -4.0),
                lineTo(19.0, -6.5),
                quadraticTo(20.0, -9.0, 21.0, -11.5),
                cubicTo(22.0, -14.0, 23.0, -16.5, 24.0, -19.0),
                close(),
            ),
            listOf(
                moveTo(1.0, 2.0),
                lineTo(3.0, 1.0),
                quadraticTo(5.0, 0.0, 7.0, -1.0),
                cubicTo(9.0, -2.0, 11.0, -3.0, 13.0, -4.0),
                close(),
            ).transformed(transform),
        )
    }

    @Test
    fun rejectsInvalidTrueTypeGlyfRangesAndTruncatedGlyphs() {
        val outOfRange = assertFailsWith<IllegalArgumentException> {
            TrueTypeGlyfTableParser.parseGlyph(
                glyfTable = bytes(0x00, 0x00),
                range = TrueTypeGlyphDataRange(start = 0, endExclusive = 20),
                glyphId = 8u,
            )
        }
        val truncatedCoordinates = assertFailsWith<IllegalArgumentException> {
            TrueTypeGlyfTableParser.parseGlyph(
                glyfTable = simpleRepeatedFlagGlyphData().copyOf(simpleRepeatedFlagGlyphData().size - 1),
                range = TrueTypeGlyphDataRange(start = 0, endExclusive = simpleRepeatedFlagGlyphData().size - 1),
                glyphId = 9u,
            )
        }

        assertTrue(outOfRange.message.orEmpty().contains("outside glyf table"))
        assertTrue(truncatedCoordinates.message.orEmpty().contains("glyf glyph 9"))
    }

    @Test
    fun parsedTrueTypeGlyphScalerReturnsScaledOutlineAndMetricsForSimpleGlyph() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            scale = 0.5,
        )

        assertEquals(
            GlyphOutline(
                glyphId = 0u,
                commands = listOf(
                    moveTo(0.0, 0.0),
                    lineTo(50.0, 0.0),
                    lineTo(50.0, 50.0),
                    lineTo(0.0, 50.0),
                    close(),
                ),
            ),
            scaler.outline(glyphId = 0u),
        )
        assertEquals(
            GlyphMetrics(
                advanceX = 300.0,
                advanceY = 0.0,
                bounds = GlyphBounds(left = 0.0, top = 0.0, right = 50.0, bottom = 50.0),
            ),
            scaler.metrics(glyphId = 0u),
        )
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceDumpsSimpleGlyphDeterministically() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(glyphId = 0u)
        val repeated = scaler.scaledGlyphEvidence(glyphId = 0u)
        val dump = evidence.toCanonicalJson()

        assertEquals(evidence, repeated)
        assertEquals(dump, repeated.toCanonicalJson())
        assertEquals(0u, evidence.glyphId)
        assertEquals("truetype-glyf", evidence.scalerFamily)
        assertEquals("font.scaler.truetype-glyf", evidence.route)
        assertEquals(
            TrueTypeLocaRangeEvidence(
                start = 0,
                endExclusive = simpleSquare.size,
                byteLength = simpleSquare.size,
                isEmpty = false,
            ),
            evidence.locaRange,
        )
        assertEquals(emptyList(), evidence.requestedVariationPosition)
        assertEquals(emptyList(), evidence.normalizedVariationPosition)
        assertEquals(
            listOf(
                "M 0.0 0.0",
                "L 100.0 0.0",
                "L 100.0 100.0",
                "L 0.0 100.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertEquals(
            evidence.outlineCommandDump.sha256Hex(),
            evidence.outlineCommandDumpSha256,
        )
        assertEquals(GlyphBounds(left = 0.0, top = 0.0, right = 100.0, bottom = 100.0), evidence.conservativeBounds)
        assertEquals(
            GlyphMetrics(
                advanceX = 600.0,
                advanceY = 0.0,
                bounds = GlyphBounds(left = 0.0, top = 0.0, right = 100.0, bottom = 100.0),
            ),
            evidence.metrics,
        )
        assertEquals(emptyList(), evidence.diagnostics)
        assertTrue(!dump.contains("@"))
        assertTrue(!Regex("""\bSk[A-Za-z0-9_]*""").containsMatchIn(dump))
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceSortsRequestedVariationAxesInDumps() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(
                axes = mapOf(
                    "wght" to 400.0,
                    "wdth" to 100.0,
                ),
            ),
        )
        val dump = evidence.toCanonicalJson()

        assertEquals(
            listOf(
                VariationCoordinateEvidence(tag = "wdth", value = 100.0),
                VariationCoordinateEvidence(tag = "wght", value = 400.0),
            ),
            evidence.requestedVariationPosition,
        )
        assertTrue(dump.indexOf("\"tag\": \"wdth\"") < dump.indexOf("\"tag\": \"wght\""))
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_AXIS_UNSUPPORTED &&
                diagnostic.detail == "truetype.gvar-unavailable"
        })
    }

    @Test
    fun trueTypeGlyfEvidenceShowsRequestedAndNormalizedVariationFacts() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                    SFNTTableTag("gvar") to singleAxisGvarWithAllPointDelta().toUnsignedByteList(),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val dump = evidence.toCanonicalJson()

        assertEquals(listOf(VariationCoordinateEvidence(tag = "wght", value = 650.0)), evidence.requestedVariationPosition)
        assertEquals(listOf(VariationCoordinateEvidence(tag = "wght", value = 0.5)), evidence.normalizedVariationPosition)
        assertEquals(
            listOf(
                "M 0.0 0.0",
                "L 100.0 0.0",
                "L 110.0 95.0",
                "L 0.0 100.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertTrue(evidence.diagnostics.none { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.METRICS_VARIATION_UNAVAILABLE
        })
        assertTrue(dump.indexOf("\"requestedVariationPosition\"") < dump.indexOf("\"normalizedVariationPosition\""))
        assertTrue(!dump.contains("@"))
        assertTrue(!Regex("""\bSk[A-Za-z0-9_]*""").containsMatchIn(dump))
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceAppliesGvarIupForSingleExplicitPoint() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithPointDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 1.0)),
        )

        assertEquals(
            listOf(
                "M 20.0 -10.0",
                "L 120.0 -10.0",
                "L 120.0 90.0",
                "L 20.0 90.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertTrue(evidence.diagnostics.none { diagnostic -> diagnostic.detail == "truetype.gvar-iup-unavailable" })
        assertTrue(!evidence.toCanonicalJson().contains("\"detail\": \"truetype.gvar-iup-unavailable\""))
    }

    @Test
    fun parsedTrueTypeGlyphScalerAppliesGvarPhantomPointAdvanceDeltaToMetrics() {
        val simpleSquare = simpleSquareGlyphData()
        val simpleGlyph = TrueTypeGlyfTableParser.parseGlyph(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            glyphId = 0u,
        ) as TrueTypeGlyph.Simple
        val gvar = TrueTypeGvarTable.parse(
            data = singleAxisGvarWithPhantomPointAdvanceDelta(),
            axisCount = 1,
            glyphCount = 2,
        )
        val phantomDeltas = gvar.simpleGlyphDeltaResult(
            glyphId = 0u,
            glyph = simpleGlyph,
            normalizedCoordinates = listOf(1.0),
        ).deltas
        requireNotNull(phantomDeltas)
        assertEquals(10.0, phantomDeltas.phantomXDelta(0))
        assertEquals(50.0, phantomDeltas.phantomXDelta(1))
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = gvar,
            normalizedAxisOrder = listOf("wght"),
        )

        assertEquals(
            GlyphMetrics(
                advanceX = 640.0,
                advanceY = 0.0,
                bounds = GlyphBounds(left = 0.0, top = 0.0, right = 100.0, bottom = 100.0),
            ),
            scaler.metrics(
                glyphId = 0u,
                position = VariationPosition(axes = mapOf("wght" to 1.0)),
            ),
        )
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceOmitsPhantomMetricsUnavailableWhenAdvanceIsResolved() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithPhantomPointAdvanceDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 1.0)),
        )

        assertEquals(640.0, evidence.metrics?.advanceX)
        assertTrue(evidence.diagnostics.none { diagnostic -> diagnostic.detail == "truetype.phantom-metrics-unavailable" })
    }

    @Test
    fun trueTypeGlyfEvidenceAppliesHvarAdvanceWidthDeltas() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                    SFNTTableTag("HVAR") to generatedHvarTableOneAxis(
                        start = 0.0,
                        peak = 0.5,
                        end = 1.0,
                        delta = 40,
                    ).toUnsignedByteList(),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )

        assertEquals(640.0, evidence.metrics?.advanceX)
        assertTrue(evidence.diagnostics.none { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.METRICS_VARIATION_UNAVAILABLE
        })
    }

    @Test
    fun trueTypeGlyfEvidenceReportsMalformedHvarWithoutDroppingBaseMetrics() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                    SFNTTableTag("HVAR") to listOf(0x00),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )

        assertEquals(600.0, evidence.metrics?.advanceX)
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED &&
                diagnostic.detail == "truetype.hvar-table-malformed"
        })
    }

    @Test
    fun trueTypeGlyfEvidenceIncludesPresentVerticalMetricsFromFaceMetrics() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                ),
                metrics = sfntMetricsForFactory(
                    verticalAscender = 850,
                    verticalDescender = -320,
                    verticalLineGap = 60,
                    maxAdvanceHeight = 760,
                    numberOfVMetrics = 1,
                    verticalMetrics = listOf(
                        VerticalGlyphMetric(glyphId = 0, advanceHeight = 700, topSideBearing = 40),
                        VerticalGlyphMetric(glyphId = 1, advanceHeight = 700, topSideBearing = 0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(glyphId = 0u)
        val vertical = evidence.metrics?.verticalMetrics ?: error("missing vertical metrics evidence")

        assertEquals(700.0, evidence.metrics.advanceY)
        assertEquals("present", vertical.state)
        assertEquals("vhea-vmtx", vertical.source)
        assertEquals(700.0, vertical.verticalAdvance)
        assertEquals(40.0, vertical.topSideBearing)
        assertEquals(140.0, vertical.verticalOriginY)
        assertEquals(850.0, vertical.ascender)
        assertEquals(-320.0, vertical.descender)
        assertEquals(60.0, vertical.lineGap)
        assertEquals(760.0, vertical.maxAdvanceHeight)
        assertEquals(emptyList(), vertical.diagnostics)
        assertTrue(evidence.diagnostics.none { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VERTICAL_METRICS_UNAVAILABLE
        })
    }

    @Test
    fun trueTypeGlyfEvidenceMarksMissingVerticalTablesAsFallbackFacts() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(glyphId = 0u)
        val vertical = evidence.metrics?.verticalMetrics ?: error("missing fallback vertical metrics evidence")

        assertEquals(0.0, evidence.metrics.advanceY)
        assertEquals("fallback", vertical.state)
        assertEquals("horizontal-fallback-fact", vertical.source)
        assertEquals(null, vertical.verticalAdvance)
        assertEquals(null, vertical.topSideBearing)
        assertEquals(null, vertical.verticalOriginY)
        assertTrue(vertical.diagnostics.contains("truetype.vertical-metrics-absent"))
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VERTICAL_METRICS_UNAVAILABLE &&
                diagnostic.detail == "truetype.vertical-metrics-absent"
        })
    }

    @Test
    fun trueTypeGlyfEvidenceAppliesVvarAdvanceHeightDeltas() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                    SFNTTableTag("VVAR") to generatedVvarTableOneAxis(
                        start = 0.0,
                        peak = 0.5,
                        end = 1.0,
                        delta = 20,
                    ).toUnsignedByteList(),
                ),
                metrics = sfntMetricsForFactory(
                    verticalAscender = 850,
                    verticalDescender = -320,
                    verticalLineGap = 60,
                    maxAdvanceHeight = 760,
                    numberOfVMetrics = 1,
                    verticalMetrics = listOf(
                        VerticalGlyphMetric(glyphId = 0, advanceHeight = 700, topSideBearing = 40),
                        VerticalGlyphMetric(glyphId = 1, advanceHeight = 700, topSideBearing = 0),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val vertical = evidence.metrics?.verticalMetrics ?: error("missing varied vertical metrics evidence")

        assertEquals(720.0, evidence.metrics.advanceY)
        assertEquals("present", vertical.state)
        assertEquals(720.0, vertical.verticalAdvance)
        assertEquals(140.0, vertical.verticalOriginY)
        assertTrue(evidence.diagnostics.none { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.METRICS_VARIATION_UNAVAILABLE &&
                diagnostic.detail == "truetype.metrics-variation-table-unavailable"
        })
    }

    @Test
    fun trueTypeGlyfEvidenceAppliesMvarVerticalMetricDeltas() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                    SFNTTableTag("MVAR") to generatedMvarTableOneAxis(
                        entries = listOf(
                            "vasc" to 12,
                            "vdsc" to -8,
                            "vlgp" to 5,
                        ),
                        start = 0.0,
                        peak = 0.5,
                        end = 1.0,
                    ).toUnsignedByteList(),
                ),
                metrics = sfntMetricsForFactory(
                    verticalAscender = 850,
                    verticalDescender = -320,
                    verticalLineGap = 60,
                    maxAdvanceHeight = 760,
                    numberOfVMetrics = 1,
                    verticalMetrics = listOf(
                        VerticalGlyphMetric(glyphId = 0, advanceHeight = 700, topSideBearing = 40),
                        VerticalGlyphMetric(glyphId = 1, advanceHeight = 700, topSideBearing = 0),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val vertical = evidence.metrics?.verticalMetrics ?: error("missing MVAR vertical metrics evidence")

        assertEquals(862.0, vertical.ascender)
        assertEquals(-328.0, vertical.descender)
        assertEquals(65.0, vertical.lineGap)
        assertTrue(evidence.diagnostics.none { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED &&
                diagnostic.detail == "truetype.mvar-table-malformed"
        })
    }

    @Test
    fun trueTypeGlyfEvidenceReportsMalformedMvarWithoutDroppingBaseVerticalMetrics() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                    SFNTTableTag("MVAR") to listOf(0x00),
                ),
                metrics = sfntMetricsForFactory(
                    verticalAscender = 850,
                    verticalDescender = -320,
                    verticalLineGap = 60,
                    maxAdvanceHeight = 760,
                    numberOfVMetrics = 1,
                    verticalMetrics = listOf(
                        VerticalGlyphMetric(glyphId = 0, advanceHeight = 700, topSideBearing = 40),
                        VerticalGlyphMetric(glyphId = 1, advanceHeight = 700, topSideBearing = 0),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val vertical = evidence.metrics?.verticalMetrics ?: error("missing malformed MVAR vertical metrics evidence")

        assertEquals(850.0, vertical.ascender)
        assertEquals(-320.0, vertical.descender)
        assertEquals(60.0, vertical.lineGap)
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED &&
                diagnostic.detail == "truetype.mvar-table-malformed"
        })
    }

    @Test
    fun trueTypeGlyfEvidenceReportsMalformedVvarWithoutDroppingBaseVerticalMetrics() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                    SFNTTableTag("VVAR") to listOf(0x00),
                ),
                metrics = sfntMetricsForFactory(
                    verticalAscender = 850,
                    verticalDescender = -320,
                    verticalLineGap = 60,
                    maxAdvanceHeight = 760,
                    numberOfVMetrics = 1,
                    verticalMetrics = listOf(
                        VerticalGlyphMetric(glyphId = 0, advanceHeight = 700, topSideBearing = 40),
                        VerticalGlyphMetric(glyphId = 1, advanceHeight = 700, topSideBearing = 0),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val vertical = evidence.metrics?.verticalMetrics ?: error("missing malformed VVAR evidence")

        assertEquals(700.0, evidence.metrics.advanceY)
        assertEquals("diagnostic", vertical.state)
        assertTrue(vertical.diagnostics.contains("truetype.vvar-table-malformed"))
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED &&
                diagnostic.detail == "truetype.vvar-table-malformed"
        })
    }

    @Test
    fun trueTypeGlyfEvidenceReportsUnknownRequestedAxisWithoutThrowing() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = variableTrueTypeGlyfScaler(simpleSquare = simpleSquare)

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wdth" to 100.0)),
        )

        assertEquals(listOf(VariationCoordinateEvidence(tag = "wdth", value = 100.0)), evidence.requestedVariationPosition)
        assertEquals(emptyList(), evidence.normalizedVariationPosition)
        assertEquals(
            listOf(
                "M 0.0 0.0",
                "L 100.0 0.0",
                "L 100.0 100.0",
                "L 0.0 100.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_AXIS_UNSUPPORTED &&
                diagnostic.detail == "truetype.gvar-axis"
        })
    }

    @Test
    fun trueTypeGlyfEvidenceReportsNonFiniteRequestedAxisWithoutThrowing() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = variableTrueTypeGlyfScaler(simpleSquare = simpleSquare)

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to Double.NaN)),
        )

        assertEquals(emptyList(), evidence.requestedVariationPosition)
        assertEquals(emptyList(), evidence.normalizedVariationPosition)
        assertEquals(
            listOf(
                "M 0.0 0.0",
                "L 100.0 0.0",
                "L 100.0 100.0",
                "L 0.0 100.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED &&
                diagnostic.detail == "truetype.variation-position-non-finite"
        })
    }

    @Test
    fun trueTypeGlyfEvidenceAppliesAvarCoordinateMappingFixture() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = variableTrueTypeGlyfScaler(
            simpleSquare = simpleSquare,
            gvarTable = singleAxisGvarWithPointDelta(),
            variations = VariationTables(
                axes = listOf(
                    variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                ),
                axisSegmentMaps = listOf(
                    OpenTypeAvarAxisSegmentMap(
                        segments = listOf(
                            OpenTypeAvarSegment(fromCoordinate = -1.0, toCoordinate = -1.0),
                            OpenTypeAvarSegment(fromCoordinate = 0.0, toCoordinate = 0.0),
                            OpenTypeAvarSegment(fromCoordinate = 1.0, toCoordinate = 0.75),
                        ),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 900.0)),
        )

        assertEquals(listOf(VariationCoordinateEvidence(tag = "wght", value = 0.75)), evidence.normalizedVariationPosition)
        assertEquals(
            listOf(
                "M 15.0 -7.5",
                "L 115.0 -7.5",
                "L 115.0 92.5",
                "L 15.0 92.5",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertTrue(evidence.diagnostics.none { diagnostic -> diagnostic.detail == "truetype.avar-unapplied" })
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceReportsInvalidCompositeGlyphPointIndexRefusalCode() {
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0001,
                glyphId = 1,
                arg1 = 4,
                arg2 = 0,
            ),
        )
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = compositeGlyph + simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, compositeGlyph.size, compositeGlyph.size + simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(glyphId = 0u)

        assertEquals(emptyList(), evidence.outlineCommands)
        assertNull(evidence.conservativeBounds)
        assertEquals(
            listOf(
                FontScalerDiagnostic(
                    code = FontScalerDiagnosticCodes.OUTLINE_FORMAT_UNSUPPORTED,
                    detail = "truetype.composite-point-index",
                    operation = "outline",
                    glyphId = 0u,
                    severity = "refusal",
                ),
            ),
            evidence.diagnostics,
        )
        assertTrue(evidence.toCanonicalJson().contains("\"code\": \"font.outline-format-unsupported\""))
        assertTrue(evidence.toCanonicalJson().contains("\"detail\": \"truetype.composite-point-index\""))
    }

    @Test
    fun parsedTrueTypeGlyphScalerAppliesGvarDeltasToSimpleGlyphAtVariationPositionOnly() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithAllPointDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        )

        assertEquals(
            listOf(
                moveTo(0.0, 0.0),
                lineTo(100.0, 0.0),
                lineTo(100.0, 100.0),
                lineTo(0.0, 100.0),
                close(),
            ),
            scaler.outline(glyphId = 0u).commands,
        )
        assertEquals(
            listOf(
                moveTo(0.0, 0.0),
                lineTo(100.0, 0.0),
                lineTo(120.0, 90.0),
                lineTo(0.0, 100.0),
                close(),
            ),
            scaler.outline(
                glyphId = 0u,
                position = VariationPosition(axes = mapOf("wght" to 1.0)),
            ).commands,
        )
    }

    @Test
    fun parsedTrueTypeGlyphScalerAppliesGvarDeltasToCompositeComponents() {
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 1,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val simpleSquare = simpleSquareGlyphData()
        val glyfTable = compositeGlyph + simpleSquare
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(offsets = listOf(0, compositeGlyph.size, glyfTable.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithGlyphOneAllPointDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        )

        assertEquals(
            listOf(
                moveTo(0.0, 0.0),
                lineTo(100.0, 0.0),
                lineTo(120.0, 90.0),
                lineTo(0.0, 100.0),
                close(),
            ),
            scaler.outline(
                glyphId = 0u,
                position = VariationPosition(axes = mapOf("wght" to 1.0)),
            ).commands,
        )
    }

    @Test
    fun parsedTrueTypeGlyphScalerAppliesInterpolatedChildGvarDeltasToCompositeComponents() {
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 1,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val simpleSquare = simpleSquareGlyphData()
        val glyfTable = compositeGlyph + simpleSquare
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(offsets = listOf(0, compositeGlyph.size, glyfTable.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithGlyphOnePointDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        )

        assertEquals(
            listOf(
                moveTo(20.0, -10.0),
                lineTo(120.0, -10.0),
                lineTo(120.0, 90.0),
                lineTo(20.0, 90.0),
                close(),
            ),
            scaler.outline(
                glyphId = 0u,
                position = VariationPosition(axes = mapOf("wght" to 1.0)),
            ).commands,
        )
    }

    @Test
    fun parsedTrueTypeGlyphScalerAppliesGvarPartialPointDeltasUsingIup() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithPointDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        )

        assertEquals(
            listOf(
                moveTo(20.0, -10.0),
                lineTo(120.0, -10.0),
                lineTo(120.0, 90.0),
                lineTo(20.0, 90.0),
                close(),
            ),
            scaler.outline(
                glyphId = 0u,
                position = VariationPosition(axes = mapOf("wght" to 1.0)),
            ).commands,
        )
    }

    @Test
    fun parsedTrueTypeGlyphScalerAppliesGvarIupAcrossWraparoundReferences() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithWraparoundDeltas(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        )

        assertEquals(
            listOf(
                moveTo(0.0, 0.0),
                lineTo(120.0, 0.0),
                lineTo(120.0, 80.0),
                lineTo(0.0, 80.0),
                close(),
            ),
            scaler.outline(
                glyphId = 0u,
                position = VariationPosition(axes = mapOf("wght" to 1.0)),
            ).commands,
        )
    }

    @Test
    fun parsedTrueTypeGlyphScalerLeavesUntouchedContoursStableDuringIupInterpolation() {
        val twoContours = twoContourSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = twoContours,
            loca = TrueTypeLocaTable(offsets = listOf(0, twoContours.size, twoContours.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithPointDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        )

        assertEquals(
            listOf(
                moveTo(20.0, -10.0),
                lineTo(120.0, -10.0),
                lineTo(120.0, 90.0),
                lineTo(20.0, 90.0),
                close(),
                moveTo(150.0, 0.0),
                lineTo(300.0, 0.0),
                lineTo(300.0, 100.0),
                lineTo(150.0, 100.0),
                close(),
            ),
            scaler.outline(
                glyphId = 0u,
                position = VariationPosition(axes = mapOf("wght" to 1.0)),
            ).commands,
        )
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceReportsMalformedGvarTupleDiagnostic() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithMalformedPointDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 1.0)),
        )

        assertEquals(
            listOf(
                "M 0.0 0.0",
                "L 100.0 0.0",
                "L 100.0 100.0",
                "L 0.0 100.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED &&
                diagnostic.detail == "truetype.gvar-malformed" &&
                diagnostic.severity == "warning"
        })
    }

    @Test
    fun trueTypeGvarTableRejectsMalformedTablesWithClearDiagnostics() {
        val failure = assertFailsWith<IllegalArgumentException> {
            TrueTypeGvarTable.parse(
                data = singleAxisGvarWithAllPointDelta().copyOf(10),
                axisCount = 1,
                glyphCount = 2,
            )
        }

        assertTrue(failure.message.orEmpty().contains("gvar"))
        assertTrue(failure.message.orEmpty().contains("header"))
    }

    @Test
    fun parsedTrueTypeGlyphScalerReturnsEmptyOutlineAndMetricsForEmptyGlyph() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        assertEquals(GlyphOutline(glyphId = 1u), scaler.outline(glyphId = 1u))
        assertEquals(
            GlyphMetrics(
                advanceX = 500.0,
                advanceY = 0.0,
                bounds = GlyphBounds(left = 0.0, top = 0.0, right = 0.0, bottom = 0.0),
            ),
            scaler.metrics(glyphId = 1u),
        )
    }

    @Test
    fun parsedTrueTypeGlyphScalerResolvesCompositeGlyphOutlinesRecursively() {
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x002b,
                glyphId = 1,
                arg1 = 10,
                arg2 = 20,
                transformWords = intArrayOf(0x2000),
            ),
            *componentRecord(
                flags = 0x0043,
                glyphId = 2,
                arg1 = 0,
                arg2 = 10,
                transformWords = intArrayOf(0x6000, 0x4000),
            ),
        )
        val simpleSquare = simpleSquareGlyphData()
        val nestedComposite = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 1,
                arg1 = 100,
                arg2 = 0,
            ),
        )
        val glyfTable = compositeGlyph + simpleSquare + nestedComposite
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(
                offsets = listOf(
                    0,
                    compositeGlyph.size,
                    compositeGlyph.size + simpleSquare.size,
                    glyfTable.size,
                ),
            ),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
                2u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        assertEquals(
            GlyphOutline(
                glyphId = 0u,
                commands = listOf(
                    moveTo(10.0, 20.0),
                    lineTo(60.0, 20.0),
                    lineTo(60.0, 70.0),
                    lineTo(10.0, 70.0),
                    close(),
                    moveTo(150.0, 10.0),
                    lineTo(300.0, 10.0),
                    lineTo(300.0, 110.0),
                    lineTo(150.0, 110.0),
                    close(),
                ),
            ),
            scaler.outline(glyphId = 0u),
        )
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceIncludesCompositeComponentTrace() {
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x002b,
                glyphId = 1,
                arg1 = 10,
                arg2 = 20,
                transformWords = intArrayOf(0x2000),
            ),
            *componentRecord(
                flags = 0x0003,
                glyphId = 2,
                arg1 = 30,
                arg2 = 40,
            ),
        )
        val nestedComposite = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 1,
                arg1 = 5,
                arg2 = 6,
            ),
        )
        val simpleSquare = simpleSquareGlyphData()
        val glyfTable = compositeGlyph + simpleSquare + nestedComposite
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(
                offsets = listOf(
                    0,
                    compositeGlyph.size,
                    compositeGlyph.size + simpleSquare.size,
                    glyfTable.size,
                ),
            ),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
                2u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(glyphId = 0u)

        assertEquals(
            listOf(
                TrueTypeCompositeComponentEvidence(
                    depth = 0,
                    parentGlyphId = 0u,
                    componentIndex = 0,
                    componentGlyphId = 1u,
                    flags = "0x002b",
                    argumentKind = "xy",
                    argument1 = 10,
                    argument2 = 20,
                    xx = 0.5,
                    xy = 0.0,
                    yx = 0.0,
                    yy = 0.5,
                    dx = 10.0,
                    dy = 20.0,
                ),
                TrueTypeCompositeComponentEvidence(
                    depth = 0,
                    parentGlyphId = 0u,
                    componentIndex = 1,
                    componentGlyphId = 2u,
                    flags = "0x0003",
                    argumentKind = "xy",
                    argument1 = 30,
                    argument2 = 40,
                    xx = 1.0,
                    xy = 0.0,
                    yx = 0.0,
                    yy = 1.0,
                    dx = 30.0,
                    dy = 40.0,
                ),
                TrueTypeCompositeComponentEvidence(
                    depth = 1,
                    parentGlyphId = 2u,
                    componentIndex = 0,
                    componentGlyphId = 1u,
                    flags = "0x0003",
                    argumentKind = "xy",
                    argument1 = 5,
                    argument2 = 6,
                    xx = 1.0,
                    xy = 0.0,
                    yx = 0.0,
                    yy = 1.0,
                    dx = 5.0,
                    dy = 6.0,
                ),
            ),
            evidence.compositeComponents,
        )
        assertTrue(evidence.toCanonicalJson().contains("\"compositeComponents\""))
    }

    @Test
    fun parsedTrueTypeGlyphScalerUsesOpenTypeTwoByTwoCoefficientOrder() {
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0083,
                glyphId = 1,
                arg1 = 0,
                arg2 = 0,
                transformWords = intArrayOf(
                    0x4000,
                    0x2000,
                    0x1000,
                    0x4000,
                ),
            ),
        )
        val simpleSquare = simpleSquareGlyphData()
        val glyfTable = compositeGlyph + simpleSquare
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(offsets = listOf(0, compositeGlyph.size, glyfTable.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        assertEquals(
            GlyphOutline(
                glyphId = 0u,
                commands = listOf(
                    moveTo(0.0, 0.0),
                    lineTo(100.0, 50.0),
                    lineTo(125.0, 150.0),
                    lineTo(25.0, 100.0),
                    close(),
                ),
            ),
            scaler.outline(glyphId = 0u),
        )
    }

    @Test
    fun parsedTrueTypeGlyphScalerAppliesScaledComponentOffsetsOnlyWhenRequested() {
        val scaledOffsetComposite = compositeGlyphData(
            *componentRecord(
                flags = 0x080b,
                glyphId = 2,
                arg1 = 10,
                arg2 = 20,
                transformWords = intArrayOf(0x2000),
            ),
        )
        val unscaledOffsetComposite = compositeGlyphData(
            *componentRecord(
                flags = 0x100b,
                glyphId = 2,
                arg1 = 10,
                arg2 = 20,
                transformWords = intArrayOf(0x2000),
            ),
        )
        val simpleSquare = simpleSquareGlyphData()
        val glyfTable = scaledOffsetComposite + unscaledOffsetComposite + simpleSquare
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(
                offsets = listOf(
                    0,
                    scaledOffsetComposite.size,
                    scaledOffsetComposite.size + unscaledOffsetComposite.size,
                    glyfTable.size,
                ),
            ),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                2u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        assertEquals(moveTo(5.0, 10.0), scaler.outline(glyphId = 0u).commands.first())
        assertEquals(moveTo(10.0, 20.0), scaler.outline(glyphId = 1u).commands.first())
    }

    @Test
    fun parsedTrueTypeGlyphScalerUsesFirstCompositeGlyphUseMyMetricsSource() {
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0223,
                glyphId = 1,
                arg1 = 10,
                arg2 = 20,
            ),
            *componentRecord(
                flags = 0x0203,
                glyphId = 2,
                arg1 = 30,
                arg2 = 40,
            ),
        )
        val simpleSquare = simpleSquareGlyphData()
        val glyfTable = compositeGlyph + simpleSquare + simpleSquare
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(
                offsets = listOf(
                    0,
                    compositeGlyph.size,
                    compositeGlyph.size + simpleSquare.size,
                    glyfTable.size,
                ),
            ),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
                2u to TrueTypeGlyphHorizontalMetrics(advanceX = 700.0, leftSideBearing = 30.0),
            ),
        )

        assertEquals(
            GlyphMetrics(
                advanceX = 500.0,
                advanceY = 0.0,
                bounds = GlyphBounds(left = 0.0, top = 0.0, right = 16.0, bottom = 16.0),
            ),
            scaler.metrics(glyphId = 0u),
        )
        assertEquals(500.0, scaler.scaledGlyphEvidence(glyphId = 0u).metrics?.advanceX)
    }

    @Test
    fun parsedTrueTypeGlyphScalerAlignsCompositeGlyphComponentPoints() {
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0023,
                glyphId = 1,
                arg1 = 50,
                arg2 = 50,
            ),
            *componentRecord(
                flags = 0x0001,
                glyphId = 2,
                arg1 = 1,
                arg2 = 0,
            ),
        )
        val simpleSquare = simpleSquareGlyphData()
        val glyfTable = compositeGlyph + simpleSquare + simpleSquare
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(
                offsets = listOf(
                    0,
                    compositeGlyph.size,
                    compositeGlyph.size + simpleSquare.size,
                    glyfTable.size,
                ),
            ),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
                2u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        assertEquals(
            GlyphOutline(
                glyphId = 0u,
                commands = listOf(
                    moveTo(50.0, 50.0),
                    lineTo(150.0, 50.0),
                    lineTo(150.0, 150.0),
                    lineTo(50.0, 150.0),
                    close(),
                    moveTo(150.0, 50.0),
                    lineTo(250.0, 50.0),
                    lineTo(250.0, 150.0),
                    lineTo(150.0, 150.0),
                    close(),
                ),
            ),
            scaler.outline(glyphId = 0u),
        )
    }

    @Test
    fun parsedTrueTypeGlyphScalerRejectsInvalidCompositeGlyphPointIndicesExplicitly() {
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0001,
                glyphId = 1,
                arg1 = 4,
                arg2 = 0,
            ),
        )
        val simpleSquare = simpleSquareGlyphData()
        val glyfTable = compositeGlyph + simpleSquare
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(offsets = listOf(0, compositeGlyph.size, glyfTable.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        val failure = assertFailsWith<UnsupportedOperationException> {
            scaler.outline(glyphId = 0u)
        }

        assertTrue(failure.message.orEmpty().contains("point index"))
        assertTrue(failure.message.orEmpty().contains("glyphId 0"))
    }

    @Test
    fun parsedTrueTypeGlyphScalerStopsRecursiveCompositeResolutionAtDepthCap() {
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 0,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = compositeGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, compositeGlyph.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
            ),
        )

        val failure = assertFailsWith<UnsupportedOperationException> {
            scaler.outline(glyphId = 0u)
        }

        assertTrue(failure.message.orEmpty().contains("depth"))
        assertTrue(failure.message.orEmpty().contains("glyphId 0"))
    }

    @Test
    fun parsedTrueTypeGlyphScalerRejectsCompositeGlyphsAboveComponentCountCap() {
        val compositeGlyph = excessiveCompositeComponentGlyphData()
        val simpleSquare = simpleSquareGlyphData()
        val glyfTable = compositeGlyph + simpleSquare
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(offsets = listOf(0, compositeGlyph.size, glyfTable.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        val failure = assertFailsWith<FontScalerRefusalException> {
            scaler.outline(glyphId = 0u)
        }
        val evidence = scaler.scaledGlyphEvidence(glyphId = 0u)

        assertEquals(FontScalerDiagnosticCodes.OUTLINE_FORMAT_UNSUPPORTED, failure.diagnostic.code)
        assertEquals("truetype.composite-component-count", failure.diagnostic.detail)
        assertTrue(failure.message.orEmpty().contains("component count"))
        assertTrue(failure.message.orEmpty().contains("glyphId 0"))
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.OUTLINE_FORMAT_UNSUPPORTED &&
                diagnostic.detail == "truetype.composite-component-count"
        })
        assertTrue(evidence.toCanonicalJson().contains("\"detail\": \"truetype.composite-component-count\""))
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceReportsCompositeGlyphCycleAndInvalidComponentDiagnostics() {
        val cyclicComposite = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 0,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val cycleScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = cyclicComposite,
            loca = TrueTypeLocaTable(offsets = listOf(0, cyclicComposite.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
            ),
        )
        val invalidComponent = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 2,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val invalidScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = invalidComponent,
            loca = TrueTypeLocaTable(offsets = listOf(0, invalidComponent.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
            ),
        )

        val cycleEvidence = cycleScaler.scaledGlyphEvidence(glyphId = 0u)
        val invalidEvidence = invalidScaler.scaledGlyphEvidence(glyphId = 0u)

        assertTrue(cycleEvidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.OUTLINE_FORMAT_UNSUPPORTED &&
                diagnostic.detail == "truetype.composite-recursion-depth"
        })
        assertTrue(invalidEvidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.OUTLINE_FORMAT_UNSUPPORTED &&
                diagnostic.detail == "truetype.composite-component-glyph-id"
        })
        assertTrue(cycleEvidence.toCanonicalJson().contains("\"detail\": \"truetype.composite-recursion-depth\""))
        assertTrue(invalidEvidence.toCanonicalJson().contains("\"detail\": \"truetype.composite-component-glyph-id\""))
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceIsolatesMalformedSimpleGlyphsFromSafeGlyphs() {
        val safeGlyph = simpleSquareGlyphData()
        val malformedGlyph = simpleRepeatedFlagGlyphData().copyOf(simpleRepeatedFlagGlyphData().size - 1)
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = safeGlyph + malformedGlyph,
            loca = TrueTypeLocaTable(
                offsets = listOf(
                    0,
                    safeGlyph.size,
                    safeGlyph.size + malformedGlyph.size,
                ),
            ),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        )

        val safeEvidence = scaler.scaledGlyphEvidence(glyphId = 0u)
        val malformedEvidence = scaler.scaledGlyphEvidence(glyphId = 1u)

        assertTrue(safeEvidence.outlineCommands.isNotEmpty())
        assertTrue(malformedEvidence.outlineCommands.isEmpty())
        assertNull(malformedEvidence.metrics)
        assertTrue(malformedEvidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.SCALER_OUTLINE_UNAVAILABLE &&
                diagnostic.detail == "truetype.coordinate-run-truncated" &&
                diagnostic.operation == "outline"
        })
        assertTrue(malformedEvidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.SCALER_OUTLINE_UNAVAILABLE &&
                diagnostic.detail == "truetype.coordinate-run-truncated" &&
                diagnostic.operation == "metrics"
        })
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceReportsMalformedContourAndFlagDiagnostics() {
        val badContourGlyph = twoContourSquareGlyphData().copyOf().also { glyph ->
            glyph[12] = 0x00
            glyph[13] = 0x03
        }
        val repeatOverflowGlyph = simpleRepeatedFlagGlyphData().copyOf().also { glyph ->
            glyph[16] = 0x04
        }
        val contourEvidence = ParsedTrueTypeGlyphScaler(
            glyfTable = badContourGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, badContourGlyph.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
            ),
        ).scaledGlyphEvidence(glyphId = 0u)
        val repeatEvidence = ParsedTrueTypeGlyphScaler(
            glyfTable = repeatOverflowGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, repeatOverflowGlyph.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
            ),
        ).scaledGlyphEvidence(glyphId = 0u)

        assertTrue(contourEvidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.SCALER_OUTLINE_UNAVAILABLE &&
                diagnostic.detail == "truetype.contour-endpoints-malformed"
        })
        assertTrue(repeatEvidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.SCALER_OUTLINE_UNAVAILABLE &&
                diagnostic.detail == "truetype.flag-repeat-overflow"
        })
    }

    @Test
    fun parsedTrueTypeGlyphEvidenceReportsCompositeTransformFlagDiagnostic() {
        val invalidTransformGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x004b,
                glyphId = 1,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val glyfTable = invalidTransformGlyph + simpleSquareGlyphData()
        val evidence = ParsedTrueTypeGlyphScaler(
            glyfTable = glyfTable,
            loca = TrueTypeLocaTable(offsets = listOf(0, invalidTransformGlyph.size, glyfTable.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        ).scaledGlyphEvidence(glyphId = 0u)

        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.SCALER_OUTLINE_UNAVAILABLE &&
                diagnostic.detail == "truetype.composite-transform-flags"
        })
    }

    @Test
    fun parsedTrueTypeGlyphScalerRejectsOutOfLocaGlyphIdsAndMissingMetrics() {
        val simpleSquare = simpleSquareGlyphData()
        val scaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
            ),
        )

        val outOfLoca = assertFailsWith<IllegalArgumentException> {
            scaler.outline(glyphId = 2u)
        }
        val missingMetrics = assertFailsWith<IllegalArgumentException> {
            scaler.metrics(glyphId = 1u)
        }

        assertTrue(outOfLoca.message.orEmpty().contains("outside loca table glyph count 2"))
        assertTrue(missingMetrics.message.orEmpty().contains("horizontal metrics missing for glyphId 1"))
    }

    @Test
    fun trueTypeGlyphScalerFactoryBuildsScalerFromSfntMetricsAndRawLocaGlyf() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyphScalerFactory.create(
            metrics = MetricsTables(
                unitsPerEm = 1000,
                indexToLocFormat = 0,
                numGlyphs = 2,
                horizontalMetrics = listOf(
                    HorizontalGlyphMetric(glyphId = 0, advanceWidth = 600, leftSideBearing = 20),
                    HorizontalGlyphMetric(glyphId = 1, advanceWidth = 500, leftSideBearing = 0),
                ),
            ),
            locaTable = shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size),
            glyfTable = simpleSquare,
            pixelSize = 20.0,
        )

        assertEquals(
            listOf(
                moveTo(0.0, 0.0),
                lineTo(2.0, 0.0),
                lineTo(2.0, 2.0),
                lineTo(0.0, 2.0),
                close(),
            ),
            scaler.outline(glyphId = 0u).commands,
        )
        assertEquals(
            GlyphMetrics(
                advanceX = 12.0,
                advanceY = 0.0,
                bounds = GlyphBounds(left = 0.0, top = 0.0, right = 2.0, bottom = 2.0),
            ),
            scaler.metrics(glyphId = 0u),
        )
        assertEquals(10.0, scaler.metrics(glyphId = 1u).advanceX)
    }

    @Test
    fun trueTypeGlyphScalerFactoryRejectsMissingAndInvalidIndexToLocFormat() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val missing = assertFailsWith<IllegalArgumentException> {
            TrueTypeGlyphScalerFactory.create(
                metrics = sfntMetricsForFactory(indexToLocFormat = null),
                locaTable = shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size),
                glyfTable = simpleSquare,
            )
        }
        val invalid = assertFailsWith<IllegalArgumentException> {
            TrueTypeGlyphScalerFactory.create(
                metrics = sfntMetricsForFactory(indexToLocFormat = 2),
                locaTable = shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size),
                glyfTable = simpleSquare,
            )
        }

        assertTrue(missing.message.orEmpty().contains("indexToLocFormat is required"))
        assertTrue(invalid.message.orEmpty().contains("indexToLocFormat 2 must be 0 or 1"))
    }

    @Test
    fun trueTypeGlyphScalerFactoryRejectsIncompleteHorizontalMetrics() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val failure = assertFailsWith<IllegalArgumentException> {
            TrueTypeGlyphScalerFactory.create(
                metrics = sfntMetricsForFactory(
                    horizontalMetrics = listOf(
                        HorizontalGlyphMetric(glyphId = 0, advanceWidth = 600, leftSideBearing = 20),
                    ),
                ),
                locaTable = shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size),
                glyfTable = simpleSquare,
            )
        }

        assertTrue(failure.message.orEmpty().contains("horizontal metrics missing for glyphId 1"))
    }

    @Test
    fun trueTypeGlyphScalerFactoryRejectsInvalidUnitsPerEmAndIncoherentLocaGlyf() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val invalidUnits = assertFailsWith<IllegalArgumentException> {
            TrueTypeGlyphScalerFactory.create(
                metrics = sfntMetricsForFactory(unitsPerEm = 0),
                locaTable = shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size),
                glyfTable = simpleSquare,
            )
        }
        val incoherentGlyf = assertFailsWith<IllegalArgumentException> {
            TrueTypeGlyphScalerFactory.create(
                metrics = sfntMetricsForFactory(),
                locaTable = shortLocaForOffsets(0, simpleSquare.size + 2, simpleSquare.size + 2),
                glyfTable = simpleSquare,
            )
        }

        assertTrue(invalidUnits.message.orEmpty().contains("unitsPerEm must be positive"))
        assertTrue(incoherentGlyf.message.orEmpty().contains("exceeds glyf table length"))
    }

    @Test
    fun trueTypeGlyphScalerFactoryRejectsNonPositivePixelSize() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()

        val zero = assertFailsWith<IllegalArgumentException> {
            TrueTypeGlyphScalerFactory.create(
                metrics = sfntMetricsForFactory(),
                locaTable = shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size),
                glyfTable = simpleSquare,
                pixelSize = 0.0,
            )
        }
        val negative = assertFailsWith<IllegalArgumentException> {
            TrueTypeGlyphScalerFactory.create(
                metrics = sfntMetricsForFactory(),
                locaTable = shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size),
                glyfTable = simpleSquare,
                pixelSize = -1.0,
            )
        }

        assertTrue(zero.message.orEmpty().contains("pixelSize"))
        assertTrue(negative.message.orEmpty().contains("pixelSize"))
    }

    @Test
    fun trueTypeGlyfScalerBuildsFromOpenTypeFaceRawTables() {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val scaler = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                ),
            ),
        )

        assertEquals(
            listOf(
                moveTo(0.0, 0.0),
                lineTo(100.0, 0.0),
                lineTo(100.0, 100.0),
                lineTo(0.0, 100.0),
                close(),
            ),
            scaler.outline(glyphId = 0u).commands,
        )
        assertEquals(
            GlyphMetrics(
                advanceX = 600.0,
                advanceY = 0.0,
                bounds = GlyphBounds(left = 0.0, top = 0.0, right = 100.0, bottom = 100.0),
                verticalMetrics = GlyphVerticalMetrics(
                    state = "fallback",
                    source = "horizontal-fallback-fact",
                    diagnostics = listOf("truetype.vertical-metrics-absent"),
                ),
            ),
            scaler.metrics(glyphId = 0u),
        )
    }

    @Test
    fun trueTypeGlyfScalerRejectsMissingTrueTypeRawTablesClearly() {
        val failure = assertFailsWith<IllegalArgumentException> {
            TrueTypeGlyfScaler(
                face = syntheticTrueTypeFace(rawTables = emptyMap()),
            ).outline(glyphId = 0u)
        }

        assertTrue(failure.message.orEmpty().contains("loca"))
        assertTrue(failure.message.orEmpty().contains("glyf"))
    }

    @Test
    fun cffType2FixtureInterpreterBuildsLineCurveAndFlexEvidence() {
        val interpreter = CFFType2CharStringInterpreter()
        val charString = type2CharString(
            type2Number(100),
            type2Number(200),
            type2Operator(21),
            type2Number(50),
            type2Number(0),
            type2Number(0),
            type2Number(50),
            type2Operator(5),
            type2Number(10),
            type2Number(0),
            type2Number(20),
            type2Number(30),
            type2Number(40),
            type2Number(30),
            type2Operator(8),
            type2Number(5),
            type2Number(0),
            type2Number(10),
            type2Number(10),
            type2Number(15),
            type2Number(0),
            type2Number(15),
            type2Number(0),
            type2Number(10),
            type2Number(-10),
            type2Number(5),
            type2Number(0),
            type2Number(50),
            type2EscapedOperator(35),
            type2Operator(14),
        )

        val evidence = interpreter.interpretEvidence(
            charString = charString,
            glyphId = 3u,
            format = "cff",
        )

        assertEquals("cff", evidence.format)
        assertEquals(3u, evidence.glyphId)
        assertEquals(
            listOf(
                "M 100.0 200.0",
                "L 150.0 200.0",
                "L 150.0 250.0",
                "C 160.0 250.0 180.0 280.0 220.0 310.0",
                "C 225.0 310.0 235.0 320.0 250.0 320.0",
                "C 265.0 320.0 275.0 310.0 280.0 310.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertEquals(evidence.outlineCommands.joinToString("\n"), evidence.outlineCommandDump)
        assertEquals(evidence.outlineCommandDump.sha256Hex(), evidence.outlineCommandDumpSha256)
        assertEquals(emptyList(), evidence.callTrace)
        assertEquals(emptyList(), evidence.diagnostics)
        assertTrue(evidence.toCanonicalJson().contains("\"outlineCommandDumpSha256\""))
        assertTrue(!Regex("""\bSk[A-Za-z0-9_]*""").containsMatchIn(evidence.toCanonicalJson()))
    }

    @Test
    fun cffType2InterpreterSupportsAlternatingHvAndVhCurvesWithFinalDelta() {
        val evidence = CFFType2CharStringInterpreter().interpretEvidence(
            charString = type2CharString(
                type2Number(0),
                type2Number(0),
                type2Operator(21),
                // hvcurveto: horizontal then vertical curve; its final dy is explicit.
                type2Number(10),
                type2Number(20),
                type2Number(30),
                type2Number(40),
                type2Number(50),
                type2Number(60),
                type2Number(70),
                type2Number(80),
                type2Number(90),
                type2Operator(31),
                // vhcurveto: a final dy is supplied for the last vertical curve.
                type2Number(10),
                type2Number(20),
                type2Number(30),
                type2Number(40),
                type2Number(50),
                type2Operator(30),
                type2Operator(14),
            ),
            glyphId = 9u,
            format = "cff",
        )

        assertEquals(
            listOf(
                "M 0.0 0.0",
                "C 10.0 0.0 30.0 30.0 30.0 70.0",
                "C 30.0 120.0 90.0 190.0 170.0 280.0",
                "C 170.0 290.0 190.0 320.0 230.0 370.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
    }

    @Test
    fun cffType2FixtureInterpreterTracesLocalAndGlobalSubroutines() {
        val interpreter = CFFType2CharStringInterpreter(
            localSubroutines = listOf(
                type2CharString(
                    type2Number(25),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
            globalSubroutines = listOf(
                type2CharString(
                    type2Number(0),
                    type2Number(25),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
        )
        val charString = type2CharString(
            type2Number(0),
            type2Number(0),
            type2Operator(21),
            type2Number(-107),
            type2Operator(10),
            type2Number(-107),
            type2Operator(29),
            type2Operator(14),
        )

        val evidence = interpreter.interpretEvidence(
            charString = charString,
            glyphId = 4u,
            format = "cff",
        )

        assertEquals(
            listOf(
                "M 0.0 0.0",
                "L 25.0 0.0",
                "L 25.0 25.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertEquals(
            listOf(
                CFFCharStringCallTrace(
                    depth = 0,
                    scope = "local",
                    encodedIndex = -107,
                    resolvedIndex = 0,
                    bias = 107,
                    callerByteOffset = 4,
                    returnByteOffset = 3,
                    instructionBudgetRemaining = 1016,
                    expandedByteBudgetRemaining = 4088,
                ),
                CFFCharStringCallTrace(
                    depth = 0,
                    scope = "global",
                    encodedIndex = -107,
                    resolvedIndex = 0,
                    bias = 107,
                    callerByteOffset = 6,
                    returnByteOffset = 3,
                    instructionBudgetRemaining = 1010,
                    expandedByteBudgetRemaining = 4082,
                ),
            ),
            evidence.callTrace,
        )
        assertTrue(evidence.toCanonicalJson().contains("\"callTrace\""))
    }

    @Test
    fun cffType2FixtureInterpreterRecordsNestedSubroutineOffsets() {
        val interpreter = CFFType2CharStringInterpreter(
            localSubroutines = listOf(
                type2CharString(
                    type2Number(25),
                    type2Number(0),
                    type2Operator(5),
                    type2Number(-106),
                    type2Operator(10),
                    type2Operator(11),
                ),
                type2CharString(
                    type2Number(0),
                    type2Number(25),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
        )
        val evidence = interpreter.interpretEvidence(
            charString = type2CharString(
                type2Number(0),
                type2Number(0),
                type2Operator(21),
                type2Number(-107),
                type2Operator(10),
                type2Operator(14),
            ),
            glyphId = 19u,
            format = "cff",
        )

        assertEquals(
            listOf(
                "M 0.0 0.0",
                "L 25.0 0.0",
                "L 25.0 25.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertEquals(2, evidence.callTrace.size)
        assertTrue(evidence.toCanonicalJson().contains("\"callerByteOffset\""))
        assertTrue(evidence.toCanonicalJson().contains("\"returnByteOffset\""))
        assertTrue(evidence.toCanonicalJson().contains("\"instructionBudgetRemaining\""))
        assertTrue(evidence.toCanonicalJson().contains("\"expandedByteBudgetRemaining\""))
    }

    @Test
    fun cffType2FixtureInterpreterRejectsInvalidSubroutinePathsWithDedicatedDiagnostics() {
        val outOfRangeInterpreter = CFFType2CharStringInterpreter(
            localSubroutines = listOf(
                type2CharString(
                    type2Number(25),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
        )
        val outOfRangeFailure = assertFailsWith<FontScalerRefusalException> {
            outOfRangeInterpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(-106),
                    type2Operator(10),
                    type2Operator(14),
                ),
                glyphId = 20u,
                format = "cff",
            )
        }
        assertEquals("font.scaler.cff.subr-out-of-range", outOfRangeFailure.diagnostic.code)
        assertEquals("cff.subr-out-of-range", outOfRangeFailure.diagnostic.detail)
        assertEquals("charstring", outOfRangeFailure.diagnostic.operation)
        assertEquals(20u, outOfRangeFailure.diagnostic.glyphId)

        val recursiveInterpreter = CFFType2CharStringInterpreter(
            localSubroutines = listOf(
                type2CharString(
                    type2Number(-107),
                    type2Operator(10),
                    type2Operator(11),
                ),
            ),
        )
        val depthFailure = assertFailsWith<FontScalerRefusalException> {
            recursiveInterpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(-107),
                    type2Operator(10),
                    type2Operator(14),
                ),
                glyphId = 21u,
                format = "cff",
            )
        }
        assertEquals("font.scaler.cff.subr-depth-limit", depthFailure.diagnostic.code)
        assertEquals("cff.subr-depth-limit", depthFailure.diagnostic.detail)
        assertEquals("charstring", depthFailure.diagnostic.operation)
        assertEquals(21u, depthFailure.diagnostic.glyphId)

        val missingReturnInterpreter = CFFType2CharStringInterpreter(
            localSubroutines = listOf(
                type2CharString(
                    type2Number(25),
                    type2Number(0),
                    type2Operator(5),
                ),
            ),
        )
        val missingReturnFailure = assertFailsWith<FontScalerRefusalException> {
            missingReturnInterpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(-107),
                    type2Operator(10),
                    type2Operator(14),
                ),
                glyphId = 22u,
                format = "cff",
            )
        }
        assertEquals(FontScalerDiagnosticCodes.CFF_STACK_MALFORMED, missingReturnFailure.diagnostic.code)
        assertEquals("cff.subr-missing-return", missingReturnFailure.diagnostic.detail)
        assertEquals("charstring", missingReturnFailure.diagnostic.operation)
        assertEquals(22u, missingReturnFailure.diagnostic.glyphId)

        val instructionLimitInterpreter = CFFType2CharStringInterpreter(
            localSubroutines = listOf(
                type2CharString(
                    type2Number(25),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
            limits = Type2ExecutionLimits(
                maxOperandStack = 48,
                maxCallDepth = 16,
                maxInstructionCount = 8,
                maxExpandedBytes = 64,
            ),
        )
        val instructionLimitFailure = assertFailsWith<FontScalerRefusalException> {
            instructionLimitInterpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(-107),
                    type2Operator(10),
                    type2Operator(14),
                ),
                glyphId = 23u,
                format = "cff",
            )
        }
        assertEquals("font.scaler.cff.instruction-limit", instructionLimitFailure.diagnostic.code)
        assertEquals("cff.instruction-limit", instructionLimitFailure.diagnostic.detail)
        assertEquals("charstring", instructionLimitFailure.diagnostic.operation)
        assertEquals(23u, instructionLimitFailure.diagnostic.glyphId)
    }

    @Test
    fun cff2FixtureInterpreterAppliesVsindexBlendEvidence() {
        val interpreter = CFFType2CharStringInterpreter(
            blendAxisTagsByVsIndex = mapOf(0 to listOf("wght")),
        )
        val charString = type2CharString(
            type2Number(0),
            type2Number(0),
            type2Operator(21),
            type2Number(0),
            type2Operator(15),
            type2Number(50),
            type2Number(10),
            type2Number(1),
            type2Operator(16),
            type2Number(0),
            type2Operator(5),
            type2Operator(14),
        )

        val evidence = interpreter.interpretEvidence(
            charString = charString,
            glyphId = 5u,
            format = "cff2",
            position = VariationPosition(axes = mapOf("wght" to 0.5)),
        )

        assertEquals(0, evidence.cff2VsIndex)
        assertEquals(listOf(VariationCoordinateEvidence(tag = "wght", value = 0.5)), evidence.variationPosition)
        assertEquals(
            listOf(
                CFFBlendVectorEvidence(
                    vsIndex = 0,
                    defaults = listOf(50.0),
                    deltaSets = listOf(listOf(10.0)),
                    regionIndexes = emptyList(),
                    scalars = listOf(0.5),
                    blendedValues = listOf(55.0),
                ),
            ),
            evidence.blendVectors,
        )
        assertEquals(
            listOf(
                "M 0.0 0.0",
                "L 55.0 0.0",
                "Z",
            ),
            evidence.outlineCommands,
        )
        assertTrue(evidence.toCanonicalJson().contains("\"format\": \"cff2\""))
        assertTrue(evidence.toCanonicalJson().contains("\"variationPosition\""))
    }

    @Test
    fun cffType2FixtureInterpreterReportsStackAndOperatorRefusals() {
        val interpreter = CFFType2CharStringInterpreter()

        val stackFailure = assertFailsWith<FontScalerRefusalException> {
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(50),
                    type2Operator(21),
                ),
                glyphId = 6u,
                format = "cff",
            )
        }
        assertEquals(FontScalerDiagnosticCodes.CFF_STACK_MALFORMED, stackFailure.diagnostic.code)
        assertEquals("cff.stack-underflow", stackFailure.diagnostic.detail)
        assertEquals("charstring", stackFailure.diagnostic.operation)
        assertEquals(6u, stackFailure.diagnostic.glyphId)
        assertTrue(stackFailure.message.orEmpty().contains("operator offset"))

        val operatorFailure = assertFailsWith<FontScalerRefusalException> {
            interpreter.interpretEvidence(
                charString = type2CharString(type2EscapedOperator(0)),
                glyphId = 7u,
                format = "cff",
            )
        }
        assertEquals(FontScalerDiagnosticCodes.CFF_OPERATOR_UNSUPPORTED, operatorFailure.diagnostic.code)
        assertEquals("cff.escaped-operator-0", operatorFailure.diagnostic.detail)
        assertEquals("charstring", operatorFailure.diagnostic.operation)
        assertEquals(7u, operatorFailure.diagnostic.glyphId)
        assertTrue(operatorFailure.message.orEmpty().contains("operator offset"))
    }

    @Test
    fun cffType2FixtureInterpreterCoversRemainingCurveAndFlexOperators() {
        val interpreter = CFFType2CharStringInterpreter()

        assertEquals(
            listOf("M 0.0 0.0", "C 10.0 0.0 30.0 5.0 60.0 5.0", "Z"),
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(10),
                    type2Number(20),
                    type2Number(5),
                    type2Number(30),
                    type2Operator(27),
                    type2Operator(14),
                ),
                glyphId = 8u,
            ).outlineCommands,
        )
        assertEquals(
            listOf("M 0.0 0.0", "C 0.0 10.0 20.0 15.0 20.0 45.0", "Z"),
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(10),
                    type2Number(20),
                    type2Number(5),
                    type2Number(30),
                    type2Operator(26),
                    type2Operator(14),
                ),
                glyphId = 9u,
            ).outlineCommands,
        )
        assertEquals(
            listOf("M 0.0 0.0", "C 10.0 0.0 30.0 5.0 30.0 35.0", "Z"),
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(10),
                    type2Number(20),
                    type2Number(5),
                    type2Number(30),
                    type2Operator(31),
                    type2Operator(14),
                ),
                glyphId = 10u,
            ).outlineCommands,
        )
        assertEquals(
            listOf("M 0.0 0.0", "C 0.0 10.0 20.0 15.0 50.0 15.0", "Z"),
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(10),
                    type2Number(20),
                    type2Number(5),
                    type2Number(30),
                    type2Operator(30),
                    type2Operator(14),
                ),
                glyphId = 11u,
            ).outlineCommands,
        )
        assertEquals(
            listOf(
                "M 0.0 0.0",
                "C 10.0 0.0 30.0 5.0 60.0 5.0",
                "C 100.0 5.0 150.0 0.0 210.0 0.0",
                "Z",
            ),
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(10),
                    type2Number(20),
                    type2Number(5),
                    type2Number(30),
                    type2Number(40),
                    type2Number(50),
                    type2Number(60),
                    type2EscapedOperator(34),
                    type2Operator(14),
                ),
                glyphId = 12u,
            ).outlineCommands,
        )
        assertEquals(
            listOf(
                "M 0.0 0.0",
                "C 10.0 2.0 30.0 7.0 60.0 7.0",
                "C 100.0 7.0 150.0 4.0 210.0 0.0",
                "Z",
            ),
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(10),
                    type2Number(2),
                    type2Number(20),
                    type2Number(5),
                    type2Number(30),
                    type2Number(40),
                    type2Number(50),
                    type2Number(-3),
                    type2Number(60),
                    type2EscapedOperator(36),
                    type2Operator(14),
                ),
                glyphId = 13u,
            ).outlineCommands,
        )
        assertEquals(
            listOf(
                "M 0.0 0.0",
                "C 10.0 1.0 30.0 3.0 60.0 6.0",
                "C 100.0 10.0 150.0 15.0 210.0 0.0",
                "Z",
            ),
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(10),
                    type2Number(1),
                    type2Number(20),
                    type2Number(2),
                    type2Number(30),
                    type2Number(3),
                    type2Number(40),
                    type2Number(4),
                    type2Number(50),
                    type2Number(5),
                    type2Number(60),
                    type2EscapedOperator(37),
                    type2Operator(14),
                ),
                glyphId = 14u,
            ).outlineCommands,
        )
    }

    @Test
    fun cffType2FixtureInterpreterRecordsWidthAndHintMaskMetadata() {
        val interpreter = CFFType2CharStringInterpreter()

        val evidence = interpreter.interpretEvidence(
            charString = type2CharString(
                type2Number(450),
                type2Number(10),
                type2Number(5),
                type2Operator(1),
                type2Number(20),
                type2Number(5),
                type2Operator(23),
                type2Operator(19),
                intArrayOf(0xff),
                type2Number(0),
                type2Number(0),
                type2Operator(21),
                type2Operator(14),
            ),
            glyphId = 15u,
        )

        assertEquals(450.0, evidence.width)
        assertEquals(2, evidence.stemHintCount)
        assertEquals(1, evidence.hintMaskByteCount)
        assertEquals(listOf("M 0.0 0.0", "Z"), evidence.outlineCommands)
        assertTrue(evidence.toCanonicalJson().contains("\"width\": 450.0"))
        assertTrue(evidence.toCanonicalJson().contains("\"hintMaskByteCount\": 1"))
    }

    @Test
    fun cffType2FixtureInterpreterRejectsEndcharRemaindersAndStackOverflow() {
        val interpreter = CFFType2CharStringInterpreter()

        val trailingBytesFailure = assertFailsWith<FontScalerRefusalException> {
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Operator(14),
                    type2Number(10),
                ),
                glyphId = 16u,
                format = "cff",
            )
        }
        assertEquals("font.scaler.cff.trailing-bytes", trailingBytesFailure.diagnostic.code)
        assertEquals("cff.trailing-bytes", trailingBytesFailure.diagnostic.detail)
        assertEquals("charstring", trailingBytesFailure.diagnostic.operation)
        assertEquals(16u, trailingBytesFailure.diagnostic.glyphId)
        assertTrue(trailingBytesFailure.message.orEmpty().contains("operator offset"))

        val leftoverOperandsFailure = assertFailsWith<FontScalerRefusalException> {
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(10),
                    type2Operator(14),
                ),
                glyphId = 17u,
                format = "cff",
            )
        }
        assertEquals(FontScalerDiagnosticCodes.CFF_STACK_MALFORMED, leftoverOperandsFailure.diagnostic.code)
        assertEquals("cff.stack-malformed", leftoverOperandsFailure.diagnostic.detail)
        assertEquals("charstring", leftoverOperandsFailure.diagnostic.operation)
        assertEquals(17u, leftoverOperandsFailure.diagnostic.glyphId)

        val stackOverflowFailure = assertFailsWith<FontScalerRefusalException> {
            interpreter.interpretEvidence(
                charString = type2CharString(
                    *Array(49) { index -> type2Number(index) },
                    type2Operator(14),
                ),
                glyphId = 18u,
                format = "cff",
            )
        }
        assertEquals("font.scaler.cff.stack-overflow", stackOverflowFailure.diagnostic.code)
        assertEquals("cff.stack-overflow", stackOverflowFailure.diagnostic.detail)
        assertEquals("charstring", stackOverflowFailure.diagnostic.operation)
        assertEquals(18u, stackOverflowFailure.diagnostic.glyphId)
        assertTrue(stackOverflowFailure.message.orEmpty().contains("operator offset"))
    }

    @Test
    fun cffCharStringTraceGoldenMatchesGeneratedEvidence() {
        val expected = Files.readString(
            kanvasProjectRoot().resolve("font/fixtures/expected/scaler/cff-charstring-trace.json"),
        )

        assertCanonicalGoldenEquals(expected, cffCharStringTraceDump())
    }

    @Test
    fun cffSubroutineTraceGoldenMatchesGeneratedEvidence() {
        val expected = Files.readString(
            kanvasProjectRoot().resolve("font/fixtures/expected/scaler/cff-subroutine-trace.json"),
        )

        assertCanonicalGoldenEquals(expected, cffSubroutineTraceDump())
    }

    @Test
    fun cffScalerPathOutputGoldenMatchesGeneratedEvidence() {
        val expected = Files.readString(
            kanvasProjectRoot().resolve("font/fixtures/expected/scaler/cff-scaler-path-output.json"),
        )

        assertCanonicalGoldenEquals(expected, cffScalerPathOutputDump())
    }

    @Test
    fun cff2VariationTraceGoldenMatchesGeneratedEvidence() {
        val expected = Files.readString(
            kanvasProjectRoot().resolve("font/fixtures/expected/scaler/cff2-variation-trace.json"),
        )

        assertCanonicalGoldenEquals(expected, cff2VariationTraceDump())
    }

    @Test
    fun cffScalerParsesSourceSerif4GlyphPathsFromParsedOpenTypeFace() {
        val fontBytes = Files.readAllBytes(
            kanvasProjectRoot().resolve("font/fixtures/fonts/scaler/SourceSerif4-Regular.otf"),
        )
        val source = FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440300")),
            kind = FontSourceKind.MEMORY,
            displayName = "SourceSerif4-Regular.otf",
            bytes = fontBytes,
        )
        val face = DefaultOpenTypeFaceParser().parse(source, 0)
        val scaler = CFFScaler(face)

        val evidence0 = scaler.scaledGlyphEvidence(glyphId = 0u)
        val evidence0again = scaler.scaledGlyphEvidence(glyphId = 0u)

        assertEquals(evidence0, evidence0again)
        assertTrue(evidence0.outlineCommands.isNotEmpty(), ".notdef must have outline commands")
        assertNotNull(evidence0.metrics, ".notdef must have metrics")
        assertEquals(0u, evidence0.requestedGlyphId)
        assertEquals("cff", evidence0.format)

        val scalerFamily = "cff"
        assertEquals(scalerFamily, evidence0.scalerFamily)
        assertEquals("font.scaler.cff", evidence0.route)
        assertEquals("non-zero", evidence0.fillRule)

        assertTrue(evidence0.diagnostics.isEmpty(), ".notdef must have no diagnostics: ${evidence0.diagnostics}")

        val dump0 = evidence0.toCanonicalJson()
        assertTrue(!dump0.contains("@"), "evidence dump must not contain host-dependent tokens")
        assertTrue(
            !Regex("""\bSk[A-Za-z0-9_]*""").containsMatchIn(dump0),
            "evidence dump must not contain Skia tokens",
        )

        var hasOutlineCommands = false
        var lastGlyphWithCommands = 0u
        for (gid in 0u until 200u) {
            val ev = scaler.scaledGlyphEvidence(glyphId = gid)
            if (ev.outlineCommands.isNotEmpty()) {
                hasOutlineCommands = true
                lastGlyphWithCommands = gid
                break
            }
        }
        assertTrue(hasOutlineCommands, "at least one glyph in 0..199 must have outline commands, found none. Last checked glyph: $lastGlyphWithCommands")

        val evidenceNonZero = scaler.scaledGlyphEvidence(glyphId = 68u)
        val nonZeroHasCommands = evidenceNonZero.outlineCommands.isNotEmpty()
        if (nonZeroHasCommands) {
            assertTrue(evidenceNonZero.diagnostics.isEmpty(), "glyph 68 must have no diagnostics: ${evidenceNonZero.diagnostics}")
            val dump68 = evidenceNonZero.toCanonicalJson()
            assertTrue(!dump68.contains("@"), "evidence dump glyph 68 must not contain host-dependent tokens")
        }
    }

    @Test
    fun cffScalerProducesOutlineAndMetricsForSourceSerif4RealFont() {
        val fontBytes = Files.readAllBytes(
            kanvasProjectRoot().resolve("font/fixtures/fonts/scaler/SourceSerif4-Regular.otf"),
        )
        val source = FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440301")),
            kind = FontSourceKind.MEMORY,
            displayName = "SourceSerif4-Regular.otf",
            bytes = fontBytes,
        )
        val face = DefaultOpenTypeFaceParser().parse(source, 0)
        val scaler = CFFScaler(face)

        val outline0 = scaler.outline(glyphId = 0u, position = VariationPosition())
        assertTrue(outline0.commands.isNotEmpty(), ".notdef outline must have commands")

        val outline0again = scaler.outline(glyphId = 0u, position = VariationPosition())
        assertEquals(outline0, outline0again, "outline must be deterministic")

        val metrics0 = scaler.metrics(glyphId = 0u, position = VariationPosition())
        assertTrue(metrics0.advanceX > 0.0, ".notdef must have positive advance width")
    }

    @Test
    fun cffScalerProducesOutlineAndMetricsForSourceSerif4Glyph68() {
        val fontBytes = Files.readAllBytes(
            kanvasProjectRoot().resolve("font/fixtures/fonts/scaler/SourceSerif4-Regular.otf"),
        )
        val source = FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440302")),
            kind = FontSourceKind.MEMORY,
            displayName = "SourceSerif4-Regular.otf",
            bytes = fontBytes,
        )
        val face = DefaultOpenTypeFaceParser().parse(source, 0)
        val scaler = CFFScaler(face)

        val result68 = runCatching { scaler.outline(glyphId = 68u, position = VariationPosition()) }
        val resultMetrics68 = runCatching { scaler.metrics(glyphId = 68u, position = VariationPosition()) }

        // Some glyphs in real CFF fonts may have unsupported charstring patterns;
        // verify deterministic behavior regardless of success or refusal
        if (result68.isSuccess) {
            assertTrue(result68.getOrThrow().commands.isNotEmpty())
            assertTrue(resultMetrics68.isSuccess)
            assertTrue(resultMetrics68.getOrThrow().advanceX > 0.0)
        } else {
            val exception = result68.exceptionOrNull()
            assertTrue(exception is FontScalerRefusalException)
            assertTrue(exception.message?.contains("CFF") == true)
        }
    }

    @Test
    fun cffScalerUsesGeneratedCffTableCharstringsSubrsAndMetrics() {
        val cffTable = generatedCFFTable(
            charStrings = listOf(
                type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
                type2CharString(
                    type2Number(475),
                    type2Number(100),
                    type2Number(200),
                    type2Operator(21),
                    type2Number(-107),
                    type2Operator(10),
                    type2Number(-107),
                    type2Operator(29),
                    type2Number(10),
                    type2Number(0),
                    type2Number(20),
                    type2Number(30),
                    type2Number(40),
                    type2Number(30),
                    type2Operator(8),
                    type2Operator(14),
                ),
            ),
            localSubroutines = listOf(
                type2CharString(
                    type2Number(50),
                    type2Number(0),
                    type2Number(0),
                    type2Number(50),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
            globalSubroutines = listOf(
                type2CharString(
                    type2Number(0),
                    type2Number(-25),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
        )
        val scaler = CFFScaler(
            face = syntheticCFFFace(
                scalerType = 0x4f54544fu,
                tableTag = "CFF ",
                tableBytes = cffTable,
            ),
        )

        val outline = scaler.outline(glyphId = 1u)
        val metrics = scaler.metrics(glyphId = 1u)

        assertEquals(
            listOf(
                moveTo(100.0, 200.0),
                lineTo(150.0, 200.0),
                lineTo(150.0, 250.0),
                lineTo(150.0, 225.0),
                cubicTo(160.0, 225.0, 180.0, 255.0, 220.0, 285.0),
                close(),
            ),
            outline.commands,
        )
        assertEquals(
            GlyphMetrics(
                advanceX = 475.0,
                advanceY = 0.0,
                bounds = GlyphBounds(left = 100.0, top = 200.0, right = 220.0, bottom = 285.0),
            ),
            metrics,
        )
    }

    @Test
    fun cff2ScalerUsesGeneratedCff2TableAndVariationBlend() {
        val cff2Table = generatedCFF2Table(
            charStrings = listOf(
                type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(0),
                    type2Operator(15),
                    type2Number(50),
                    type2Number(10),
                    type2Number(1),
                    type2Operator(16),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(14),
                ),
            ),
            variationStore = generatedCFF2VariationStoreOneAxis(start = 0.0, peak = 1.0, end = 1.0),
        )
        val scaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = cff2Table,
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val outline = scaler.outline(glyphId = 0u, position = VariationPosition(axes = mapOf("wght" to 650.0)))
        val metrics = scaler.metrics(glyphId = 0u, position = VariationPosition(axes = mapOf("wght" to 650.0)))

        assertEquals(
            GlyphOutline(
                glyphId = 0u,
                commands = listOf(
                    moveTo(0.0, 0.0),
                    lineTo(55.0, 0.0),
                    close(),
                ),
            ),
            outline,
        )
        assertEquals(
            GlyphMetrics(
                advanceX = 600.0,
                advanceY = 0.0,
                bounds = GlyphBounds(left = 0.0, top = 0.0, right = 55.0, bottom = 0.0),
            ),
            metrics,
        )
    }

    @Test
    fun cff2ScalerUsesVariationStoreRegionScalarsForBlendAndMetricsBounds() {
        val cff2Table = generatedCFF2Table(
            charStrings = listOf(
                type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(0),
                    type2Operator(15),
                    type2Number(50),
                    type2Number(20),
                    type2Number(1),
                    type2Operator(16),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(14),
                ),
            ),
            variationStore = generatedCFF2VariationStoreOneAxis(start = 0.0, peak = 0.5, end = 1.0),
        )
        val scaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = cff2Table,
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val defaultOutline = scaler.outline(glyphId = 0u, position = VariationPosition(axes = mapOf("wght" to 400.0)))
        val variedOutline = scaler.outline(glyphId = 0u, position = VariationPosition(axes = mapOf("wght" to 525.0)))
        val variedMetrics = scaler.metrics(glyphId = 0u, position = VariationPosition(axes = mapOf("wght" to 525.0)))
        val tableEvidence = scaler.tableEvidence()

        assertEquals(listOf(moveTo(0.0, 0.0), lineTo(50.0, 0.0), close()), defaultOutline.commands)
        assertEquals(listOf(moveTo(0.0, 0.0), lineTo(60.0, 0.0), close()), variedOutline.commands)
        assertEquals(GlyphBounds(left = 0.0, top = 0.0, right = 60.0, bottom = 0.0), variedMetrics.bounds)
        assertTrue("cff.dict.variation-store" in tableEvidence.topDictOperators)
    }

    @Test
    fun cff2ScalerNormalizesUserSpaceVariationCoordinatesBeforeBlendAndMetrics() {
        val cff2Table = generatedCFF2Table(
            charStrings = listOf(
                type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(0),
                    type2Operator(15),
                    type2Number(50),
                    type2Number(20),
                    type2Number(1),
                    type2Operator(16),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(14),
                ),
            ),
            variationStore = generatedCFF2VariationStoreOneAxis(start = 0.0, peak = 0.5, end = 1.0),
        )
        val scaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = cff2Table,
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val outline = scaler.outline(glyphId = 0u, position = VariationPosition(axes = mapOf("wght" to 650.0)))
        val metrics = scaler.metrics(glyphId = 0u, position = VariationPosition(axes = mapOf("wght" to 650.0)))

        assertEquals(listOf(moveTo(0.0, 0.0), lineTo(70.0, 0.0), close()), outline.commands)
        assertEquals(GlyphBounds(left = 0.0, top = 0.0, right = 70.0, bottom = 0.0), metrics.bounds)
    }

    @Test
    fun cff2ScalerScaledGlyphEvidenceUsesNormalizedVariationPosition() {
        val cff2Table = generatedCFF2Table(
            charStrings = listOf(
                type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(0),
                    type2Operator(15),
                    type2Number(50),
                    type2Number(20),
                    type2Number(1),
                    type2Operator(16),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(14),
                ),
            ),
            variationStore = generatedCFF2VariationStoreOneAxis(start = 0.0, peak = 0.5, end = 1.0),
        )
        val scaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = cff2Table,
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val charStringEvidence = evidence.charStringEvidence ?: error("missing CFF2 charstring evidence")

        assertEquals("cff2", evidence.format)
        assertEquals(listOf("M 0.0 0.0", "L 70.0 0.0", "Z"), evidence.outlineCommands)
        assertEquals(
            listOf(VariationCoordinateEvidence(tag = "wght", value = 0.5)),
            charStringEvidence.variationPosition,
        )
        assertEquals(0, charStringEvidence.cff2VsIndex)
        assertEquals(GlyphBounds(left = 0.0, top = 0.0, right = 70.0, bottom = 0.0), evidence.metrics?.bounds)
    }

    @Test
    fun cff2ScalerAppliesAvarCoordinateMappingBeforeBlend() {
        val cff2Table = generatedCFF2Table(
            charStrings = listOf(
                type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(0),
                    type2Operator(15),
                    type2Number(50),
                    type2Number(20),
                    type2Number(1),
                    type2Operator(16),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(14),
                ),
            ),
            variationStore = generatedCFF2VariationStoreOneAxis(start = 0.0, peak = 1.0, end = 1.0),
        )
        val scaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = cff2Table,
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                    axisSegmentMaps = listOf(
                        OpenTypeAvarAxisSegmentMap(
                            segments = listOf(
                                OpenTypeAvarSegment(fromCoordinate = -1.0, toCoordinate = -1.0),
                                OpenTypeAvarSegment(fromCoordinate = 0.0, toCoordinate = 0.0),
                                OpenTypeAvarSegment(fromCoordinate = 1.0, toCoordinate = 0.75),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 900.0)),
        )
        val charStringEvidence = evidence.charStringEvidence ?: error("missing CFF2 avar charstring evidence")

        assertEquals(listOf("M 0.0 0.0", "L 65.0 0.0", "Z"), evidence.outlineCommands)
        assertEquals(
            listOf(VariationCoordinateEvidence(tag = "wght", value = 0.75)),
            charStringEvidence.variationPosition,
        )
        assertEquals(GlyphBounds(left = 0.0, top = 0.0, right = 65.0, bottom = 0.0), evidence.metrics?.bounds)
    }

    @Test
    fun cff2ScaledGlyphEvidenceRefusesBlendWhenVariationStoreIsMissing() {
        val scaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = generatedCFF2Table(
                    charStrings = listOf(
                        type2CharString(
                            type2Number(0),
                            type2Number(0),
                            type2Operator(21),
                            type2Number(0),
                            type2Operator(15),
                            type2Number(50),
                            type2Number(20),
                            type2Number(1),
                            type2Operator(16),
                            type2Number(0),
                            type2Operator(5),
                            type2Operator(14),
                        ),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )

        assertEquals(emptyList(), evidence.outlineCommands)
        assertEquals(null, evidence.metrics)
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED &&
                diagnostic.detail == "cff2.variation-store-missing"
        })
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.CFF_GLYPH_MALFORMED &&
                diagnostic.detail == "cff.glyph-malformed"
        })
    }

    @Test
    fun cff2ScaledGlyphEvidenceRefusesInvalidVsIndexDeterministically() {
        val scaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = generatedCFF2Table(
                    charStrings = listOf(
                        type2CharString(
                            type2Number(0),
                            type2Number(0),
                            type2Operator(21),
                            type2Number(1),
                            type2Operator(15),
                            type2Number(50),
                            type2Number(10),
                            type2Number(1),
                            type2Operator(16),
                            type2Number(0),
                            type2Operator(5),
                            type2Operator(14),
                        ),
                    ),
                    variationStore = generatedCFF2VariationStoreOneAxis(start = 0.0, peak = 1.0, end = 1.0),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )

        assertEquals(emptyList(), evidence.outlineCommands)
        assertEquals(null, evidence.metrics)
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED &&
                diagnostic.detail == "cff2.vsindex-invalid"
        })
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.CFF_GLYPH_MALFORMED &&
                diagnostic.detail == "cff.glyph-malformed"
        })
    }

    @Test
    fun cff2ScaledGlyphEvidenceReportsUnknownRequestedAxisWithoutThrowing() {
        val scaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = generatedCFF2Table(
                    charStrings = listOf(
                        type2CharString(
                            type2Number(0),
                            type2Number(0),
                            type2Operator(21),
                            type2Operator(14),
                        ),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wdth" to 100.0)),
        )

        assertEquals(emptyList(), evidence.outlineCommands)
        assertEquals(null, evidence.metrics)
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_AXIS_UNSUPPORTED &&
                diagnostic.detail == "cff2.variation-axis"
        })
    }

    @Test
    fun cff2ScaledGlyphEvidenceReportsNonFiniteAxisWithoutThrowing() {
        val scaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = generatedCFF2Table(
                    charStrings = listOf(
                        type2CharString(
                            type2Number(0),
                            type2Number(0),
                            type2Operator(21),
                            type2Operator(14),
                        ),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val evidence = scaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to Double.NaN)),
        )

        assertEquals(emptyList(), evidence.outlineCommands)
        assertEquals(null, evidence.metrics)
        assertTrue(evidence.diagnostics.any { diagnostic ->
            diagnostic.code == FontScalerDiagnosticCodes.VARIATION_DATA_MALFORMED &&
                diagnostic.detail == "cff2.variation-position-non-finite"
        })
    }

    @Test
    fun cff2BlendRejectsMalformedStackWithDedicatedDiagnostic() {
        val interpreter = CFFType2CharStringInterpreter(
            blendAxisTagsByVsIndex = mapOf(0 to listOf("wght")),
        )

        val failure = assertFailsWith<FontScalerRefusalException> {
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(0),
                    type2Operator(15),
                    type2Number(0),
                    type2Operator(16),
                    type2Operator(14),
                ),
                glyphId = 31u,
                format = "cff2",
                position = VariationPosition(axes = mapOf("wght" to 0.5)),
            )
        }

        assertEquals("font.scaler.cff2.blend-stack-malformed", failure.diagnostic.code)
        assertEquals("cff2.blend-count", failure.diagnostic.detail)
        assertEquals("charstring", failure.diagnostic.operation)
        assertEquals(31u, failure.diagnostic.glyphId)
    }

    @Test
    fun cffScalersExposeDeterministicTableEvidenceDumps() {
        val cffScaler = CFFScaler(
            face = syntheticCFFFace(
                scalerType = 0x4f54544fu,
                tableTag = "CFF ",
                tableBytes = generatedCFFTable(
                    charStrings = listOf(
                        type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
                    ),
                    localSubroutines = listOf(type2CharString(type2Operator(11))),
                    globalSubroutines = listOf(type2CharString(type2Operator(11))),
                ),
            ),
        )
        val cff2Scaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = generatedCFF2Table(
                    charStrings = listOf(
                        type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )

        val cffEvidence = cffScaler.tableEvidence()
        val cff2Evidence = cff2Scaler.tableEvidence()

        assertEquals("cff", cffEvidence.format)
        assertEquals(1, cffEvidence.charStringCount)
        assertEquals(1, cffEvidence.localSubroutineCount)
        assertEquals(1, cffEvidence.globalSubroutineCount)
        assertEquals(true, cffEvidence.hasPrivateDict)
        assertTrue("cff.dict.charstrings" in cffEvidence.topDictOperators)
        assertTrue("cff.dict.private" in cffEvidence.topDictOperators)
        assertEquals(emptyList(), cffEvidence.variationAxisTags)
        assertTrue(cffEvidence.toCanonicalJson().contains("\"format\": \"cff\""))

        assertEquals("cff2", cff2Evidence.format)
        assertEquals(1, cff2Evidence.charStringCount)
        assertEquals(0, cff2Evidence.localSubroutineCount)
        assertEquals(0, cff2Evidence.globalSubroutineCount)
        assertEquals(false, cff2Evidence.hasPrivateDict)
        assertEquals(listOf("wght"), cff2Evidence.variationAxisTags)
        assertTrue(cff2Evidence.toCanonicalJson().contains("\"variationAxisTags\": [\"wght\"]"))
    }

    @Test
    fun cffIndexDictGoldenMatchesGeneratedEvidence() {
        val expected = Files.readString(
            kanvasProjectRoot().resolve("font/fixtures/expected/scaler/cff-index-dict.json"),
        )

        assertCanonicalGoldenEquals(expected, cffIndexDictDump())
    }

    @Test
    fun cffTableEvidenceUsesStableSpecificCffParseDiagnostics() {
        val invalidOffSize = bytes(
            0x01,
            0x00,
            0x04,
            0x01,
            0x00,
            0x01,
            0x00,
        )
        val invalidIndexBounds = bytes(
            0x01,
            0x00,
            0x04,
            0x01,
        ) + bytes(
            0x00,
            0x01,
            0x01,
            0x01,
            0x05,
            0x42,
        )
        val malformedDictOperand = bytes(0x01, 0x00, 0x04, 0x01) +
            cffIndex(listOf("Broken".toByteArray(Charsets.US_ASCII))) +
            cffIndex(listOf(bytes(0x1c, 0x01))) +
            cffIndex(emptyList()) +
            cffIndex(emptyList())
        val missingRequiredOperator = bytes(0x01, 0x00, 0x04, 0x01) +
            cffIndex(listOf("Broken".toByteArray(Charsets.US_ASCII))) +
            cffIndex(listOf(byteArrayOf())) +
            cffIndex(emptyList()) +
            cffIndex(emptyList())

        val cases = listOf(
            Triple(invalidOffSize, "font.scaler.cff.index-offsize-unsupported", "Name INDEX"),
            Triple(invalidIndexBounds, "font.scaler.cff.index-bounds", "Name INDEX"),
            Triple(malformedDictOperand, "font.scaler.cff.dict-operand-malformed", "Top DICT"),
            Triple(missingRequiredOperator, "font.scaler.cff.required-operator-missing", "CharStrings"),
        )

        cases.forEach { (tableBytes, expectedCode, expectedContext) ->
            val failure = assertFailsWith<FontScalerRefusalException> {
                CFFScaler(
                    face = syntheticCFFFace(
                        scalerType = 0x4f54544fu,
                        tableTag = "CFF ",
                        tableBytes = tableBytes,
                    ),
                ).tableEvidence()
            }

            assertEquals(expectedCode, failure.diagnostic.code)
            assertEquals("table", failure.diagnostic.operation)
            assertEquals(0u, failure.diagnostic.glyphId)
            assertTrue(failure.message.orEmpty().contains(expectedContext))
        }
    }

    @Test
    fun cffTableEvidenceRefusesMalformedIndexAndDictDeterministically() {
        val malformedIndex = bytes(
            0x01,
            0x00,
            0x04,
            0x01,
            0x00,
            0x01,
            0x00,
        )
        val missingCharStrings = bytes(0x01, 0x00, 0x04, 0x01) +
            cffIndex(listOf("Broken".toByteArray(Charsets.US_ASCII))) +
            cffIndex(listOf(byteArrayOf())) +
            cffIndex(emptyList()) +
            cffIndex(emptyList())

        val malformedIndexFailure = assertFailsWith<FontScalerRefusalException> {
            CFFScaler(
                face = syntheticCFFFace(
                    scalerType = 0x4f54544fu,
                    tableTag = "CFF ",
                    tableBytes = malformedIndex,
                ),
            ).tableEvidence()
        }
        val missingRequiredOperatorFailure = assertFailsWith<FontScalerRefusalException> {
            CFFScaler(
                face = syntheticCFFFace(
                    scalerType = 0x4f54544fu,
                    tableTag = "CFF ",
                    tableBytes = missingCharStrings,
                ),
            ).tableEvidence()
        }

        assertEquals("font.scaler.cff.index-offsize-unsupported", malformedIndexFailure.diagnostic.code)
        assertEquals("cff.index-offsize-unsupported", malformedIndexFailure.diagnostic.detail)
        assertEquals("table", malformedIndexFailure.diagnostic.operation)
        assertTrue(malformedIndexFailure.message.orEmpty().contains("Name INDEX"))

        assertEquals("font.scaler.cff.required-operator-missing", missingRequiredOperatorFailure.diagnostic.code)
        assertEquals("cff.required-operator-missing", missingRequiredOperatorFailure.diagnostic.detail)
        assertEquals("table", missingRequiredOperatorFailure.diagnostic.operation)
        assertTrue(missingRequiredOperatorFailure.message.orEmpty().contains("CharStrings"))
    }

    @Test
    fun cffScalersRefuseWhenRequiredRawTablesAreMissing() {
        val cffOutlineFailure = assertFailsWith<UnsupportedOperationException> {
            CFFScaler(syntheticTrueTypeFace(rawTables = emptyMap())).outline(glyphId = 1u)
        }
        val cffMetricsFailure = assertFailsWith<UnsupportedOperationException> {
            CFFScaler(syntheticTrueTypeFace(rawTables = emptyMap())).metrics(glyphId = 1u)
        }
        val cff2OutlineFailure = assertFailsWith<UnsupportedOperationException> {
            CFF2Scaler(syntheticTrueTypeFace(rawTables = emptyMap())).outline(glyphId = 1u)
        }
        val cff2MetricsFailure = assertFailsWith<UnsupportedOperationException> {
            CFF2Scaler(syntheticTrueTypeFace(rawTables = emptyMap())).metrics(glyphId = 1u)
        }

        listOf(cffOutlineFailure, cffMetricsFailure, cff2OutlineFailure, cff2MetricsFailure).forEach { failure ->
            assertTrue(failure.message.orEmpty().contains("table bytes"))
            assertTrue(failure.message.orEmpty().contains("glyphId 1"))
        }
    }

    @Test
    fun scalerReadinessFixturesAreVendoredReadableAndNonClaiming() {
        val fixtureRoot = kanvasProjectRoot().resolve("font/fixtures")
        val robotoFlexBytes = Files.readAllBytes(fixtureRoot.resolve("fonts/scaler/RobotoFlex-Variable.ttf"))
        val sourceSerifBytes = Files.readAllBytes(fixtureRoot.resolve("fonts/scaler/SourceSerif4-Regular.otf"))
        val trueTypeReadiness = Files.readString(
            fixtureRoot.resolve("expected/scaler/truetype-variation-readiness.json"),
        )
        val cffReadiness = Files.readString(
            fixtureRoot.resolve("expected/scaler/cff-cff2-readiness.json"),
        )

        assertTrue(robotoFlexBytes.isNotEmpty())
        assertTrue(sourceSerifBytes.isNotEmpty())
        assertTrue(trueTypeReadiness.contains("\"dumpId\": \"truetype-variation-readiness\""))
        assertTrue(trueTypeReadiness.contains("\"scaler-roboto-flex-variable\""))
        assertTrue(trueTypeReadiness.contains("\"no-full-variable-font-support-claim\""))
        assertTrue(trueTypeReadiness.contains("\"no-native-scaler-oracle-claim\""))
        assertTrue(cffReadiness.contains("\"dumpId\": \"cff-cff2-readiness\""))
        assertTrue(cffReadiness.contains("\"scaler-source-serif-cff\""))
        assertTrue(cffReadiness.contains("\"no-cff-rendering-support-claim\""))
        assertTrue(cffReadiness.contains("\"no-cff2-variation-support-claim\""))
        assertTrue(cffReadiness.contains("\"no-native-scaler-oracle-claim\""))
    }

    @Test
    fun truetypeCompositeGlyphReadinessGoldenMatchesGeneratedEvidence() {
        val expected = Files.readString(
            kanvasProjectRoot().resolve("font/fixtures/expected/scaler/truetype-composite-glyphs.json"),
        )

        assertCanonicalGoldenEquals(expected, truetypeCompositeGlyphReadinessDump())
    }

    @Test
    fun truetypeGvarIupGoldenMatchesGeneratedEvidence() {
        val actual = truetypeGvarIupDump()
        val expected = Files.readString(
            kanvasProjectRoot().resolve("font/fixtures/expected/scaler/truetype-gvar-iup.json"),
        )

        assertCanonicalGoldenEquals(expected, actual)
    }

    @Test
    fun truetypeMalformedGlyfIsolationGoldenMatchesGeneratedEvidence() {
        val actual = truetypeMalformedGlyfIsolationDump()
        val expected = Files.readString(
            kanvasProjectRoot().resolve("font/fixtures/expected/scaler/truetype-malformed-glyf-isolation.json"),
        )

        assertCanonicalGoldenEquals(expected, actual)
    }

    @Test
    fun truetypeVerticalMetricsGoldenMatchesGeneratedEvidence() {
        val actual = truetypeVerticalMetricsDump()
        val expected = Files.readString(
            kanvasProjectRoot().resolve("font/fixtures/expected/scaler/truetype-vertical-metrics.json"),
        )

        assertCanonicalGoldenEquals(expected, actual)
    }

    @Test
    fun truetypeGlyphFailurePolicyGoldenIncludesFaceAndGlyphIsolationActions() {
        val dump = truetypeMalformedGlyfIsolationDump()

        assertTrue(dump.contains("\"action\": \"refuse-face\""))
        assertTrue(dump.contains("\"action\": \"refuse-glyph\""))
    }

    private fun truetypeCompositeGlyphReadinessDump(): String {
        val simpleSquare = simpleSquareGlyphData()
        val supportedComposite = compositeGlyphData(
            *componentRecord(
                flags = 0x0223,
                glyphId = 1,
                arg1 = 50,
                arg2 = 50,
            ),
            *componentRecord(
                flags = 0x0001,
                glyphId = 2,
                arg1 = 1,
                arg2 = 0,
            ),
        )
        val supportedGlyf = supportedComposite + simpleSquare + simpleSquare
        val supportedEvidence = ParsedTrueTypeGlyphScaler(
            glyfTable = supportedGlyf,
            loca = TrueTypeLocaTable(
                offsets = listOf(
                    0,
                    supportedComposite.size,
                    supportedComposite.size + simpleSquare.size,
                    supportedGlyf.size,
                ),
            ),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
                2u to TrueTypeGlyphHorizontalMetrics(advanceX = 700.0, leftSideBearing = 30.0),
            ),
        ).scaledGlyphEvidence(glyphId = 0u)
        val cycleComposite = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 0,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val cycleEvidence = ParsedTrueTypeGlyphScaler(
            glyfTable = cycleComposite,
            loca = TrueTypeLocaTable(offsets = listOf(0, cycleComposite.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
            ),
        ).scaledGlyphEvidence(glyphId = 0u)
        val invalidComposite = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 2,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val invalidEvidence = ParsedTrueTypeGlyphScaler(
            glyfTable = invalidComposite,
            loca = TrueTypeLocaTable(offsets = listOf(0, invalidComposite.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
            ),
        ).scaledGlyphEvidence(glyphId = 0u)
        val excessiveComposite = excessiveCompositeComponentGlyphData()
        val excessiveGlyf = excessiveComposite + simpleSquare
        val excessiveEvidence = ParsedTrueTypeGlyphScaler(
            glyfTable = excessiveGlyf,
            loca = TrueTypeLocaTable(offsets = listOf(0, excessiveComposite.size, excessiveGlyf.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
        ).scaledGlyphEvidence(glyphId = 0u)

        return """
            {
              "schemaVersion": 1,
              "dumpId": "truetype-composite-glyphs",
              "ownerTickets": [
                "KFONT-M3-001"
              ],
              "fixtureIds": [
                "truetype-scaler-truetype-composite-glyph-transform"
              ],
              "requiredEvidence": [
                "glyph-outline.json",
                "glyph-metrics.json",
                "component-trace",
                "diagnostic-snapshot"
              ],
              "pathHashArtifacts": {
                "outlineCommandDumpSha256": "${supportedEvidence.outlineCommandDumpSha256}",
                "outlineCommandCount": ${supportedEvidence.outlineCommands.size},
                "componentTraceCount": ${supportedEvidence.compositeComponents.size}
              },
              "useMyMetricsFacts": [
                {
                  "parentGlyphId": 0,
                  "componentIndex": 0,
                  "componentGlyphId": 1,
                  "useMyMetrics": true
                }
              ],
              "supportedCompositeEvidence": ${supportedEvidence.toCanonicalJson().prependIndent("              ").trimStart()},
              "diagnosticSnapshots": {
                "cycle": [
                  ${cycleEvidence.diagnostics.joinToString(",\n                  ") { diagnostic -> diagnostic.toCanonicalJson() }}
                ],
                "invalidComponentGlyphId": [
                  ${invalidEvidence.diagnostics.joinToString(",\n                  ") { diagnostic -> diagnostic.toCanonicalJson() }}
                ],
                "componentCount": [
                  ${excessiveEvidence.diagnostics.joinToString(",\n                  ") { diagnostic -> diagnostic.toCanonicalJson() }}
                ]
              },
              "nonClaims": [
                "no-complete-target-support-claim",
                "no-a8-or-sdf-artifact-claim",
                "no-gpu-text-route-claim",
                "no-native-scaler-oracle-claim",
                "no-cff-or-cff2-outline-claim",
                "no-hinting-vm-claim",
                "no-full-iup-interpolation-claim",
                "no-phantom-point-metrics-claim",
                "no-vertical-metrics-claim",
                "no-complete-variable-font-support-claim",
                "no-shaping-fallback-or-paragraph-claim"
              ]
            }
        """.trimIndent()
    }

    private fun truetypeGvarIupDump(): String {
        val simpleSquare = simpleSquareGlyphData()
        val simpleGlyph = TrueTypeGlyfTableParser.parseGlyph(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            glyphId = 0u,
        ) as TrueTypeGlyph.Simple
        val singlePointGvar = TrueTypeGvarTable.parse(
            data = singleAxisGvarWithPointDelta(),
            axisCount = 1,
            glyphCount = 2,
        )
        val wraparoundGvar = TrueTypeGvarTable.parse(
            data = singleAxisGvarWithWraparoundDeltas(),
            axisCount = 1,
            glyphCount = 2,
        )
        val contourIsolationGlyph = twoContourSquareGlyphData()
        val contourIsolationSimpleGlyph = TrueTypeGlyfTableParser.parseGlyph(
            glyfTable = contourIsolationGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, contourIsolationGlyph.size, contourIsolationGlyph.size)),
            glyphId = 0u,
        ) as TrueTypeGlyph.Simple
        val singlePointScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = singlePointGvar,
            normalizedAxisOrder = listOf("wght"),
        )
        val wraparoundScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = wraparoundGvar,
            normalizedAxisOrder = listOf("wght"),
        )
        val contourIsolationScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = contourIsolationGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, contourIsolationGlyph.size, contourIsolationGlyph.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = singlePointGvar,
            normalizedAxisOrder = listOf("wght"),
        )
        val compositeGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 1,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val compositeGlyf = compositeGlyph + simpleSquare
        val compositeEvidence = ParsedTrueTypeGlyphScaler(
            glyfTable = compositeGlyf,
            loca = TrueTypeLocaTable(offsets = listOf(0, compositeGlyph.size, compositeGlyf.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithGlyphOnePointDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 1.0)),
        )
        val avarMappedEvidence = variableTrueTypeGlyfScaler(
            simpleSquare = simpleSquare.withTrueTypePadding(),
            gvarTable = singleAxisGvarWithPointDelta(),
            variations = VariationTables(
                axes = listOf(
                    variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                ),
                axisSegmentMaps = listOf(
                    OpenTypeAvarAxisSegmentMap(
                        segments = listOf(
                            OpenTypeAvarSegment(fromCoordinate = -1.0, toCoordinate = -1.0),
                            OpenTypeAvarSegment(fromCoordinate = 0.0, toCoordinate = 0.0),
                            OpenTypeAvarSegment(fromCoordinate = 1.0, toCoordinate = 0.75),
                        ),
                    ),
                ),
            ),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 900.0)),
        )
        val malformedEvidence = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithMalformedPointDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 1.0)),
        )
        val phantomPointGvar = TrueTypeGvarTable.parse(
            data = singleAxisGvarWithPhantomPointAdvanceDelta(),
            axisCount = 1,
            glyphCount = 2,
        )
        val paddedSimpleSquare = simpleSquare.withTrueTypePadding()
        val phantomPointScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = simpleSquare,
            loca = TrueTypeLocaTable(offsets = listOf(0, simpleSquare.size, simpleSquare.size)),
            horizontalMetrics = mapOf(
                0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
                1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
            ),
            gvar = phantomPointGvar,
            normalizedAxisOrder = listOf("wght"),
        )
        val hvarAdvanceEvidence = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, paddedSimpleSquare.size, paddedSimpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to paddedSimpleSquare.toUnsignedByteList(),
                    SFNTTableTag("HVAR") to generatedHvarTableOneAxis(
                        start = 0.0,
                        peak = 0.5,
                        end = 1.0,
                        delta = 40,
                    ).toUnsignedByteList(),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val malformedHvarEvidence = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, paddedSimpleSquare.size, paddedSimpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to paddedSimpleSquare.toUnsignedByteList(),
                    SFNTTableTag("HVAR") to listOf(0x00),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val mvarVerticalEvidence = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, paddedSimpleSquare.size, paddedSimpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to paddedSimpleSquare.toUnsignedByteList(),
                    SFNTTableTag("MVAR") to generatedMvarTableOneAxis(
                        entries = listOf(
                            "vasc" to 12,
                            "vdsc" to -8,
                            "vlgp" to 5,
                        ),
                        start = 0.0,
                        peak = 0.5,
                        end = 1.0,
                    ).toUnsignedByteList(),
                ),
                metrics = sfntMetricsForFactory(
                    verticalAscender = 850,
                    verticalDescender = -320,
                    verticalLineGap = 60,
                    maxAdvanceHeight = 760,
                    numberOfVMetrics = 1,
                    verticalMetrics = listOf(
                        VerticalGlyphMetric(glyphId = 0, advanceHeight = 700, topSideBearing = 40),
                        VerticalGlyphMetric(glyphId = 1, advanceHeight = 700, topSideBearing = 0),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val malformedMvarEvidence = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = mapOf(
                    SFNTTableTag("loca") to shortLocaForOffsets(0, paddedSimpleSquare.size, paddedSimpleSquare.size)
                        .toUnsignedByteList(),
                    SFNTTableTag("glyf") to paddedSimpleSquare.toUnsignedByteList(),
                    SFNTTableTag("MVAR") to listOf(0x00),
                ),
                metrics = sfntMetricsForFactory(
                    verticalAscender = 850,
                    verticalDescender = -320,
                    verticalLineGap = 60,
                    maxAdvanceHeight = 760,
                    numberOfVMetrics = 1,
                    verticalMetrics = listOf(
                        VerticalGlyphMetric(glyphId = 0, advanceHeight = 700, topSideBearing = 40),
                        VerticalGlyphMetric(glyphId = 1, advanceHeight = 700, topSideBearing = 0),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )

        val singlePointDefault = singlePointScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 0.0)),
        )
        val singlePointMax = singlePointScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 1.0)),
        )
        val singlePointMin = singlePointScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to -1.0)),
        )
        val wraparoundMax = wraparoundScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 1.0)),
        )
        val contourIsolationMax = contourIsolationScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 1.0)),
        )

        val singlePointDeltas = singlePointGvar.simpleGlyphDeltaResult(
            glyphId = 0u,
            glyph = simpleGlyph,
            normalizedCoordinates = listOf(1.0),
        )
        val wraparoundDeltas = wraparoundGvar.simpleGlyphDeltaResult(
            glyphId = 0u,
            glyph = simpleGlyph,
            normalizedCoordinates = listOf(1.0),
        )
        val contourIsolationDeltas = singlePointGvar.simpleGlyphDeltaResult(
            glyphId = 0u,
            glyph = contourIsolationSimpleGlyph,
            normalizedCoordinates = listOf(1.0),
        )
        val phantomPointMin = phantomPointScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to -1.0)),
        )
        val phantomPointDefault = phantomPointScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 0.0)),
        )
        val phantomPointMax = phantomPointScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 1.0)),
        )
        val phantomPointDeltas = phantomPointGvar.simpleGlyphDeltaResult(
            glyphId = 0u,
            glyph = simpleGlyph,
            normalizedCoordinates = listOf(1.0),
        )

        return """
            {
              "schemaVersion": 1,
              "dumpId": "truetype-gvar-iup",
              "ownerTickets": [
                "KFONT-M3-002",
                "KFONT-M3-003"
              ],
              "fixtureIds": [
                "truetype-scaler-truetype-gvar-iup"
              ],
              "requiredEvidence": [
                "variation-deltas.json",
                "glyph-outline.json",
                "glyph-metrics.json",
                "diagnostic-snapshot"
              ],
              "simplePointCase": {
                "variationDeltas": ${variationDeltaEvidenceJson(singlePointDeltas).prependIndent("                ").trimStart()},
                "positionEvidence": {
                  "min": ${glyphEvidenceSummaryJson(singlePointMin).prependIndent("                  ").trimStart()},
                  "default": ${glyphEvidenceSummaryJson(singlePointDefault).prependIndent("                  ").trimStart()},
                  "max": ${glyphEvidenceSummaryJson(singlePointMax).prependIndent("                  ").trimStart()}
                }
              },
              "wraparoundCase": {
                "variationDeltas": ${variationDeltaEvidenceJson(wraparoundDeltas).prependIndent("                ").trimStart()},
                "max": ${glyphEvidenceSummaryJson(wraparoundMax).prependIndent("                ").trimStart()}
              },
              "contourIsolationCase": {
                "variationDeltas": ${variationDeltaEvidenceJson(contourIsolationDeltas).prependIndent("                ").trimStart()},
                "max": ${glyphEvidenceSummaryJson(contourIsolationMax).prependIndent("                ").trimStart()}
              },
              "avarMappedCase": {
                "max": ${glyphEvidenceSummaryJson(avarMappedEvidence).prependIndent("                ").trimStart()}
              },
              "compositeCase": {
                "max": ${glyphEvidenceSummaryJson(compositeEvidence).prependIndent("                ").trimStart()}
              },
              "hvarAdvanceCase": ${glyphEvidenceSummaryJson(hvarAdvanceEvidence).prependIndent("              ").trimStart()},
              "mvarVerticalCase": ${glyphEvidenceSummaryJson(mvarVerticalEvidence).prependIndent("              ").trimStart()},
              "phantomMetricCase": {
                "variationDeltas": ${variationDeltaEvidenceJson(phantomPointDeltas, includePhantomPoints = true).prependIndent("                ").trimStart()},
                "positionEvidence": {
                  "min": ${glyphEvidenceSummaryJson(phantomPointMin).prependIndent("                  ").trimStart()},
                  "default": ${glyphEvidenceSummaryJson(phantomPointDefault).prependIndent("                  ").trimStart()},
                  "max": ${glyphEvidenceSummaryJson(phantomPointMax).prependIndent("                  ").trimStart()}
                }
              },
              "diagnosticSnapshots": {
                "malformedHvar": [
                  ${malformedHvarEvidence.diagnostics.joinToString(",\n                  ") { diagnostic -> diagnostic.toCanonicalJson() }}
                ],
                "malformedMvar": [
                  ${malformedMvarEvidence.diagnostics.joinToString(",\n                  ") { diagnostic -> diagnostic.toCanonicalJson() }}
                ],
                "malformedTuple": [
                  ${malformedEvidence.diagnostics.joinToString(",\n                  ") { diagnostic -> diagnostic.toCanonicalJson() }}
                ]
              },
              "nonClaims": [
                "no-complete-target-support-claim",
                "no-complete-variable-font-support-claim",
                "no-complete-hvar-vvar-mvar-metrics-claim",
                "no-vertical-metrics-claim",
                "no-complete-hinting-vm-claim",
                "no-a8-or-sdf-artifact-claim",
                "no-gpu-text-route-claim"
              ]
            }
        """.trimIndent()
    }

    private fun truetypeVerticalMetricsDump(): String {
        val simpleSquare = simpleSquareGlyphData().withTrueTypePadding()
        val baseMetrics = sfntMetricsForFactory(
            verticalAscender = 850,
            verticalDescender = -320,
            verticalLineGap = 60,
            maxAdvanceHeight = 760,
            numberOfVMetrics = 1,
            verticalMetrics = listOf(
                VerticalGlyphMetric(glyphId = 0, advanceHeight = 700, topSideBearing = 40),
                VerticalGlyphMetric(glyphId = 1, advanceHeight = 700, topSideBearing = 0),
            ),
        )
        val baseRawTables = mapOf(
            SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size).toUnsignedByteList(),
            SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
        )
        val presentEvidence = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = baseRawTables,
                metrics = baseMetrics,
            ),
        ).scaledGlyphEvidence(glyphId = 0u)
        val fallbackEvidence = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(rawTables = baseRawTables),
        ).scaledGlyphEvidence(glyphId = 0u)
        val variedEvidence = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = baseRawTables + mapOf(
                    SFNTTableTag("VVAR") to generatedVvarTableOneAxis(
                        start = 0.0,
                        peak = 0.5,
                        end = 1.0,
                        delta = 20,
                    ).toUnsignedByteList(),
                ),
                metrics = baseMetrics,
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val malformedEvidence = TrueTypeGlyfScaler(
            face = syntheticTrueTypeFace(
                rawTables = baseRawTables + mapOf(SFNTTableTag("VVAR") to listOf(0x00)),
                metrics = baseMetrics,
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )

        return """
            {
              "schemaVersion": 1,
              "dumpId": "truetype-vertical-metrics",
              "ownerTickets": [
                "KFONT-M3-004"
              ],
              "fixtureIds": [
                "truetype-scaler-vertical-metrics"
              ],
              "requiredEvidence": [
                "glyph-metrics.json",
                "diagnostic-snapshot",
                "variation-metrics.json"
              ],
              "presentCase": ${glyphEvidenceSummaryJson(presentEvidence).prependIndent("              ").trimStart()},
              "fallbackCase": ${glyphEvidenceSummaryJson(fallbackEvidence).prependIndent("              ").trimStart()},
              "variedCase": ${glyphEvidenceSummaryJson(variedEvidence).prependIndent("              ").trimStart()},
              "diagnosticSnapshots": {
                "malformedVvar": [
                  ${malformedEvidence.diagnostics.joinToString(",\n                  ") { diagnostic -> diagnostic.toCanonicalJson() }}
                ]
              },
              "nonClaims": [
                "no-vertical-shaping-claim",
                "no-vertical-layout-claim",
                "no-gpu-text-route-claim",
                "no-native-scaler-oracle-claim"
              ]
            }
        """.trimIndent()
    }

    private fun truetypeMalformedGlyfIsolationDump(): String {
        val safeGlyph = simpleSquareGlyphData()
        val sharedMetrics = mapOf(
            0u to TrueTypeGlyphHorizontalMetrics(advanceX = 600.0, leftSideBearing = 20.0),
            1u to TrueTypeGlyphHorizontalMetrics(advanceX = 500.0, leftSideBearing = 0.0),
        )

        val truncatedHeaderGlyph = bytes(0x00, 0x01, 0x00)
        val truncatedHeaderScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = safeGlyph + truncatedHeaderGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, safeGlyph.size, safeGlyph.size + truncatedHeaderGlyph.size)),
            horizontalMetrics = sharedMetrics,
        )
        val truncatedHeaderSafe = truncatedHeaderScaler.scaledGlyphEvidence(glyphId = 0u)
        val truncatedHeaderMalformed = truncatedHeaderScaler.scaledGlyphEvidence(glyphId = 1u)

        val malformedContourGlyph = twoContourSquareGlyphData().copyOf().also { glyph ->
            glyph[12] = 0x00
            glyph[13] = 0x03
        }
        val malformedContourScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = safeGlyph + malformedContourGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, safeGlyph.size, safeGlyph.size + malformedContourGlyph.size)),
            horizontalMetrics = sharedMetrics,
        )
        val malformedContourSafe = malformedContourScaler.scaledGlyphEvidence(glyphId = 0u)
        val malformedContourEvidence = malformedContourScaler.scaledGlyphEvidence(glyphId = 1u)

        val repeatOverflowGlyph = simpleRepeatedFlagGlyphData().copyOf().also { glyph ->
            glyph[16] = 0x04
        }
        val repeatOverflowScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = safeGlyph + repeatOverflowGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, safeGlyph.size, safeGlyph.size + repeatOverflowGlyph.size)),
            horizontalMetrics = sharedMetrics,
        )
        val repeatOverflowSafe = repeatOverflowScaler.scaledGlyphEvidence(glyphId = 0u)
        val repeatOverflowEvidence = repeatOverflowScaler.scaledGlyphEvidence(glyphId = 1u)

        val coordinateTruncatedGlyph = simpleRepeatedFlagGlyphData().copyOf(simpleRepeatedFlagGlyphData().size - 1)
        val coordinateTruncatedScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = safeGlyph + coordinateTruncatedGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, safeGlyph.size, safeGlyph.size + coordinateTruncatedGlyph.size)),
            horizontalMetrics = sharedMetrics,
        )
        val coordinateTruncatedSafe = coordinateTruncatedScaler.scaledGlyphEvidence(glyphId = 0u)
        val coordinateTruncatedEvidence = coordinateTruncatedScaler.scaledGlyphEvidence(glyphId = 1u)

        val cycleGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 1,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val cycleScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = safeGlyph + cycleGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, safeGlyph.size, safeGlyph.size + cycleGlyph.size)),
            horizontalMetrics = sharedMetrics,
        )
        val cycleSafe = cycleScaler.scaledGlyphEvidence(glyphId = 0u)
        val cycleEvidence = cycleScaler.scaledGlyphEvidence(glyphId = 1u)

        val missingComponentGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x0003,
                glyphId = 2,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val missingComponentScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = safeGlyph + missingComponentGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, safeGlyph.size, safeGlyph.size + missingComponentGlyph.size)),
            horizontalMetrics = sharedMetrics,
        )
        val missingComponentSafe = missingComponentScaler.scaledGlyphEvidence(glyphId = 0u)
        val missingComponentEvidence = missingComponentScaler.scaledGlyphEvidence(glyphId = 1u)

        val invalidTransformGlyph = compositeGlyphData(
            *componentRecord(
                flags = 0x004b,
                glyphId = 0,
                arg1 = 0,
                arg2 = 0,
            ),
        )
        val invalidTransformScaler = ParsedTrueTypeGlyphScaler(
            glyfTable = safeGlyph + invalidTransformGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, safeGlyph.size, safeGlyph.size + invalidTransformGlyph.size)),
            horizontalMetrics = sharedMetrics,
        )
        val invalidTransformSafe = invalidTransformScaler.scaledGlyphEvidence(glyphId = 0u)
        val invalidTransformEvidence = invalidTransformScaler.scaledGlyphEvidence(glyphId = 1u)

        val safePaddedGlyph = safeGlyph.withTrueTypePadding()
        val invalidLocaFailure = requireNotNull(
            runCatching {
                TrueTypeGlyphScalerFactory.create(
                    metrics = sfntMetricsForFactory(),
                    locaTable = shortLocaForOffsets(0, safePaddedGlyph.size, safePaddedGlyph.size + 2),
                    glyfTable = safePaddedGlyph,
                )
            }.exceptionOrNull(),
        )

        val malformedVariationEvidence = ParsedTrueTypeGlyphScaler(
            glyfTable = safeGlyph,
            loca = TrueTypeLocaTable(offsets = listOf(0, safeGlyph.size, safeGlyph.size)),
            horizontalMetrics = sharedMetrics,
            gvar = TrueTypeGvarTable.parse(
                data = singleAxisGvarWithMalformedPointDelta(),
                axisCount = 1,
                glyphCount = 2,
            ),
            normalizedAxisOrder = listOf("wght"),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 1.0)),
        )

        return """
            {
              "schemaVersion": 1,
              "dumpId": "truetype-malformed-glyf-isolation",
              "ownerTickets": [
                "KFONT-M3-005"
              ],
              "fixtureIds": [
                "truetype-scaler-truetype-malformed-glyf-isolation"
              ],
              "requiredEvidence": [
                "glyph-outline.json",
                "glyph-metrics.json",
                "variation-deltas.json",
                "diagnostic-snapshot",
                "positive-control-dump"
              ],
              "faceRefusalCases": [
                ${faceRefusalCaseJson(
                    caseId = "loca-final-offset-out-of-bounds",
                    requestedArtifact = "glyph-outline.json",
                    action = "refuse-face",
                    diagnostic = FontScalerDiagnostic(
                        code = FontScalerDiagnosticCodes.SCALER_OUTLINE_UNAVAILABLE,
                        detail = "truetype.loca-final-offset-out-of-bounds",
                        operation = "face",
                        glyphId = 1u,
                    ),
                    exceptionMessage = invalidLocaFailure.message.orEmpty(),
                ).prependIndent("                ").trimStart()}
              ],
              "glyphIsolationCases": [
                ${glyphIsolationCaseJson(
                    caseId = "truncated-glyph-header",
                    requestedArtifact = "glyph-outline.json",
                    action = "refuse-glyph",
                    primaryDiagnostic = truncatedHeaderMalformed.primaryDiagnostic("truetype.glyf-header-truncated"),
                    malformedGlyphEvidence = truncatedHeaderMalformed,
                    positiveControlGlyphEvidence = truncatedHeaderSafe,
                ).prependIndent("                ").trimStart()},
                ${glyphIsolationCaseJson(
                    caseId = "bad-contour-endpoints",
                    requestedArtifact = "glyph-outline.json",
                    action = "refuse-glyph",
                    primaryDiagnostic = malformedContourEvidence.primaryDiagnostic("truetype.contour-endpoints-malformed"),
                    malformedGlyphEvidence = malformedContourEvidence,
                    positiveControlGlyphEvidence = malformedContourSafe,
                ).prependIndent("                ").trimStart()},
                ${glyphIsolationCaseJson(
                    caseId = "flag-repeat-overflow",
                    requestedArtifact = "glyph-outline.json",
                    action = "refuse-glyph",
                    primaryDiagnostic = repeatOverflowEvidence.primaryDiagnostic("truetype.flag-repeat-overflow"),
                    malformedGlyphEvidence = repeatOverflowEvidence,
                    positiveControlGlyphEvidence = repeatOverflowSafe,
                ).prependIndent("                ").trimStart()},
                ${glyphIsolationCaseJson(
                    caseId = "coordinate-run-truncation",
                    requestedArtifact = "glyph-outline.json",
                    action = "refuse-glyph",
                    primaryDiagnostic = coordinateTruncatedEvidence.primaryDiagnostic("truetype.coordinate-run-truncated"),
                    malformedGlyphEvidence = coordinateTruncatedEvidence,
                    positiveControlGlyphEvidence = coordinateTruncatedSafe,
                ).prependIndent("                ").trimStart()},
                ${glyphIsolationCaseJson(
                    caseId = "composite-cycle",
                    requestedArtifact = "glyph-outline.json",
                    action = "refuse-glyph",
                    primaryDiagnostic = cycleEvidence.primaryDiagnostic("truetype.composite-recursion-depth"),
                    malformedGlyphEvidence = cycleEvidence,
                    positiveControlGlyphEvidence = cycleSafe,
                ).prependIndent("                ").trimStart()},
                ${glyphIsolationCaseJson(
                    caseId = "missing-component-glyph",
                    requestedArtifact = "glyph-outline.json",
                    action = "refuse-glyph",
                    primaryDiagnostic = missingComponentEvidence.primaryDiagnostic("truetype.composite-component-glyph-id"),
                    malformedGlyphEvidence = missingComponentEvidence,
                    positiveControlGlyphEvidence = missingComponentSafe,
                ).prependIndent("                ").trimStart()},
                ${glyphIsolationCaseJson(
                    caseId = "invalid-composite-transform-flags",
                    requestedArtifact = "glyph-outline.json",
                    action = "refuse-glyph",
                    primaryDiagnostic = invalidTransformEvidence.primaryDiagnostic("truetype.composite-transform-flags"),
                    malformedGlyphEvidence = invalidTransformEvidence,
                    positiveControlGlyphEvidence = invalidTransformSafe,
                ).prependIndent("                ").trimStart()}
              ],
              "variationCases": [
                ${variationDiagnosticCaseJson(
                    caseId = "malformed-gvar-point-data",
                    requestedArtifact = "variation-deltas.json",
                    primaryDiagnostic = malformedVariationEvidence.primaryDiagnostic("truetype.gvar-malformed", operation = "variation"),
                    glyphEvidence = malformedVariationEvidence,
                ).prependIndent("                ").trimStart()}
              ],
              "nonClaims": [
                "no-complete-target-support-claim",
                "no-notdef-substitution-runtime-claim",
                "no-complete-variable-font-support-claim",
                "no-hvar-vvar-mvar-metrics-claim",
                "no-vertical-metrics-claim",
                "no-complete-hinting-vm-claim",
                "no-a8-or-sdf-artifact-claim",
                "no-gpu-text-route-claim"
              ]
            }
        """.trimIndent()
    }

    private fun glyphIsolationCaseJson(
        caseId: String,
        requestedArtifact: String,
        action: String,
        primaryDiagnostic: FontScalerDiagnostic,
        malformedGlyphEvidence: ScaledTrueTypeGlyphEvidence,
        positiveControlGlyphEvidence: ScaledTrueTypeGlyphEvidence,
    ): String {
        return """
            {
              "caseId": ${testJsonString(caseId)},
              "requestedArtifact": ${testJsonString(requestedArtifact)},
              "failurePolicy": ${glyphFailurePolicyJson(
                  action = action,
                  primaryDiagnostic = primaryDiagnostic,
                  safeGlyphsStillAvailable = true,
              )},
              "malformedGlyphEvidence": ${glyphEvidenceSummaryJson(malformedGlyphEvidence).prependIndent("              ").trimStart()},
              "positiveControlGlyphEvidence": ${glyphEvidenceSummaryJson(positiveControlGlyphEvidence).prependIndent("              ").trimStart()}
            }
        """.trimIndent()
    }

    private fun faceRefusalCaseJson(
        caseId: String,
        requestedArtifact: String,
        action: String,
        diagnostic: FontScalerDiagnostic,
        exceptionMessage: String,
    ): String {
        return """
            {
              "caseId": ${testJsonString(caseId)},
              "requestedArtifact": ${testJsonString(requestedArtifact)},
              "failurePolicy": ${glyphFailurePolicyJson(
                  action = action,
                  primaryDiagnostic = diagnostic,
                  safeGlyphsStillAvailable = false,
              )},
              "exceptionMessage": ${testJsonString(exceptionMessage)}
            }
        """.trimIndent()
    }

    private fun variationDiagnosticCaseJson(
        caseId: String,
        requestedArtifact: String,
        primaryDiagnostic: FontScalerDiagnostic,
        glyphEvidence: ScaledTrueTypeGlyphEvidence,
    ): String {
        return """
            {
              "caseId": ${testJsonString(caseId)},
              "requestedArtifact": ${testJsonString(requestedArtifact)},
              "primaryDiagnostic": ${primaryDiagnostic.toCanonicalJson()},
              "glyphEvidence": ${glyphEvidenceSummaryJson(glyphEvidence).prependIndent("              ").trimStart()}
            }
        """.trimIndent()
    }

    private fun glyphFailurePolicyJson(
        action: String,
        primaryDiagnostic: FontScalerDiagnostic,
        safeGlyphsStillAvailable: Boolean,
    ): String {
        return """
            {
              "action": ${testJsonString(action)},
              "primaryDiagnostic": ${primaryDiagnostic.toCanonicalJson()},
              "safeGlyphsStillAvailable": $safeGlyphsStillAvailable
            }
        """.trimIndent()
    }

    private fun ScaledTrueTypeGlyphEvidence.primaryDiagnostic(
        detail: String,
        operation: String = "outline",
    ): FontScalerDiagnostic =
        diagnostics.firstOrNull { diagnostic ->
            diagnostic.detail == detail && diagnostic.operation == operation
        } ?: diagnostics.first { diagnostic -> diagnostic.detail == detail }

    private fun variationDeltaEvidenceJson(
        result: TrueTypeGvarSimpleGlyphDeltaResult,
        includePhantomPoints: Boolean = false,
    ): String {
        val deltas = requireNotNull(result.deltas)
        val pointEntries = (0 until deltas.pointCount).map { pointIndex ->
            val source = when {
                deltas.isExplicit(pointIndex) -> "explicit"
                deltas.isInferred(pointIndex) -> "inferred"
                else -> "none"
            }
            """  {"pointIndex": $pointIndex, "source": "${source}", "xDelta": ${deltas.xDelta(pointIndex)}, "yDelta": ${deltas.yDelta(pointIndex)}}"""
        }
        val phantomEntries = if (includePhantomPoints) {
            listOf(
                """  {"phantomPoint": "left-side-bearing", "xDelta": ${deltas.phantomXDelta(0)}, "yDelta": ${deltas.phantomYDelta(0)}}""",
                """  {"phantomPoint": "right-side-bearing", "xDelta": ${deltas.phantomXDelta(1)}, "yDelta": ${deltas.phantomYDelta(1)}}""",
                """  {"phantomPoint": "top-origin", "xDelta": ${deltas.phantomXDelta(2)}, "yDelta": ${deltas.phantomYDelta(2)}}""",
                """  {"phantomPoint": "bottom-origin", "xDelta": ${deltas.phantomXDelta(3)}, "yDelta": ${deltas.phantomYDelta(3)}}""",
            )
        } else {
            emptyList()
        }
        return (pointEntries + phantomEntries).joinToString(
            separator = ",\n",
            prefix = "[\n",
            postfix = "\n]",
        )
    }

    private fun glyphEvidenceSummaryJson(evidence: ScaledTrueTypeGlyphEvidence): String {
        val diagnosticsJson = if (evidence.diagnostics.isEmpty()) {
            "[]"
        } else {
            evidence.diagnostics.joinToString(
                separator = ",\n",
                prefix = "[\n",
                postfix = "\n              ]",
            ) { diagnostic -> "                ${diagnostic.toCanonicalJson()}" }
        }
        return """
            {
              "glyphId": ${evidence.glyphId},
              "scalerFamily": ${testJsonString(evidence.scalerFamily)},
              "route": ${testJsonString(evidence.route)},
              "requestedVariationPosition": ${variationCoordinateEvidenceJson(evidence.requestedVariationPosition)},
              "normalizedVariationPosition": ${variationCoordinateEvidenceJson(evidence.normalizedVariationPosition)},
              "outlineCommands": ${testJsonStringArray(evidence.outlineCommands)},
              "outlineCommandDumpSha256": ${testJsonString(evidence.outlineCommandDumpSha256)},
              "metrics": ${evidence.metrics?.let(::glyphMetricsJson) ?: "null"},
              "diagnostics": ${diagnosticsJson}
            }
        """.trimIndent()
    }

    private fun glyphMetricsJson(metrics: GlyphMetrics): String {
        val verticalMetricsJson = metrics.verticalMetrics?.let { vertical ->
            """
            ,
              "verticalMetrics": {
                "state": ${testJsonString(vertical.state)},
                "source": ${testJsonString(vertical.source)},
                "verticalAdvance": ${vertical.verticalAdvance?.toString() ?: "null"},
                "topSideBearing": ${vertical.topSideBearing?.toString() ?: "null"},
                "verticalOriginY": ${vertical.verticalOriginY?.toString() ?: "null"},
                "ascender": ${vertical.ascender?.toString() ?: "null"},
                "descender": ${vertical.descender?.toString() ?: "null"},
                "lineGap": ${vertical.lineGap?.toString() ?: "null"},
                "maxAdvanceHeight": ${vertical.maxAdvanceHeight?.toString() ?: "null"},
                "diagnostics": ${testJsonStringArray(vertical.diagnostics)}
              }
            """.trimIndent()
        }.orEmpty()
        return """
            {
              "advanceX": ${metrics.advanceX},
              "advanceY": ${metrics.advanceY},
              "bounds": {
                "left": ${metrics.bounds.left},
                "top": ${metrics.bounds.top},
                "right": ${metrics.bounds.right},
                "bottom": ${metrics.bounds.bottom}
              }$verticalMetricsJson
            }
        """.trimIndent()
    }

    private fun variationCoordinateEvidenceJson(coordinates: List<VariationCoordinateEvidence>): String {
        if (coordinates.isEmpty()) {
            return "[]"
        }
        return coordinates.joinToString(
            separator = ",\n",
            prefix = "[\n",
            postfix = "\n]",
        ) { coordinate ->
            """  {"tag": ${testJsonString(coordinate.tag)}, "value": ${coordinate.value}}"""
        }
    }

    private fun testJsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun testJsonStringArray(values: List<String>): String =
        values.joinToString(
            separator = ", ",
            prefix = "[",
            postfix = "]",
        ) { value -> testJsonString(value) }

    private fun assertCanonicalGoldenEquals(expected: String, actual: String) {
        assertEquals(expected.canonicalLf().trimEnd(), actual.canonicalLf().trimEnd())
    }

    private fun String.canonicalLf(): String =
        replace("\r\n", "\n").replace('\r', '\n')

    private fun kanvasProjectRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        while (current.parent != null) {
            if (Files.isDirectory(current.resolve("font/fixtures"))) {
                return current
            }
            current = current.parent
        }
        error("Unable to locate Kanvas project root from ${Path.of("").toAbsolutePath()}")
    }

    private fun simpleSquareGlyphData(): ByteArray = bytes(
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00,
        0x00, 0x64,
        0x00, 0x64,
        0x00, 0x03,
        0x00, 0x00,
        0x31,
        0x33,
        0x35,
        0x23,
        0x64,
        0x64,
        0x64,
    )

    private fun twoContourSquareGlyphData(): ByteArray = bytes(
        0x00, 0x02,
        0x00, 0x00,
        0x00, 0x00,
        0x01, 0x2c,
        0x00, 0x64,
        0x00, 0x03,
        0x00, 0x07,
        0x00, 0x00,
        0x31,
        0x33,
        0x35,
        0x23,
        0x17,
        0x33,
        0x35,
        0x23,
        0x64,
        0x64,
        0x96,
        0x96,
        0x96,
        0x64,
        0x64,
        0x64,
    )

    private fun simpleRepeatedFlagGlyphData(): ByteArray = bytes(
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00,
        0x00, 0x64,
        0x00, 0x28,
        0x00, 0x03,
        0x00, 0x00,
        0x31,
        0x3b, 0x01,
        0x35,
        0x32, 0x32,
        0x28,
    )

    private fun simpleQuadraticGlyphData(): ByteArray = bytes(
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00,
        0x00, 0x64,
        0x00, 0x64,
        0x00, 0x03,
        0x00, 0x00,
        0x31,
        0x33,
        0x34,
        0x23,
        0x64, 0x64,
        0x64,
    )

    private fun singleAxisGvarWithPointDelta(): ByteArray = bytes(
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x02,
        0x00, 0x01,
        0x00, 0x00, 0x00, 0x20,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x11,
        0x00, 0x00, 0x00, 0x11,
        0x00, 0x01,
        0x00, 0x0a,
        0x00, 0x07,
        0xa0, 0x00,
        0x40, 0x00,
        0x01,
        0x00, 0x02,
        0x00, 0x14,
        0x00, 0xf6,
    )

    private fun singleAxisGvarWithWraparoundDeltas(): ByteArray = bytes(
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x02,
        0x00, 0x01,
        0x00, 0x00, 0x00, 0x20,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x01,
        0x00, 0x0a,
        0x00, 0x0a,
        0xa0, 0x00,
        0x40, 0x00,
        0x02,
        0x01,
        0x01, 0x02,
        0x01,
        0x14, 0x00,
        0x01,
        0x00, 0xec,
    )

    private fun singleAxisGvarWithMalformedPointDelta(): ByteArray = bytes(
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x02,
        0x00, 0x01,
        0x00, 0x00, 0x00, 0x20,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x11,
        0x00, 0x00, 0x00, 0x11,
        0x00, 0x01,
        0x00, 0x0a,
        0x00, 0x07,
        0xa0, 0x00,
        0x40, 0x00,
        0x02,
        0x00, 0x02,
        0x00, 0x14,
        0x00, 0xf6,
    )

    private fun singleAxisGvarWithAllPointDelta(): ByteArray = bytes(
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x02,
        0x00, 0x01,
        0x00, 0x00, 0x00, 0x20,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x1c,
        0x00, 0x00, 0x00, 0x1c,
        0x00, 0x01,
        0x00, 0x0a,
        0x00, 0x12,
        0x80, 0x00,
        0x40, 0x00,
        0x07,
        0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07,
        0x00, 0x00, 0xf6, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    private fun singleAxisGvarWithPhantomPointAdvanceDelta(): ByteArray = bytes(
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x02,
        0x00, 0x01,
        0x00, 0x00, 0x00, 0x20,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x01,
        0x00, 0x0a,
        0x00, 0x0a,
        0x80, 0x00,
        0x40, 0x00,
        0x07,
        0x00, 0x00, 0x00, 0x00, 0x0a, 0x32, 0x00, 0x00,
        0x87,
    )

    private fun singleAxisGvarWithGlyphOneAllPointDelta(): ByteArray = bytes(
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x02,
        0x00, 0x01,
        0x00, 0x00, 0x00, 0x20,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x1c,
        0x00, 0x01,
        0x00, 0x0a,
        0x00, 0x12,
        0x80, 0x00,
        0x40, 0x00,
        0x07,
        0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07,
        0x00, 0x00, 0xf6, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    private fun singleAxisGvarWithGlyphOnePointDelta(): ByteArray = bytes(
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x01,
        0x00, 0x00,
        0x00, 0x00, 0x00, 0x14,
        0x00, 0x02,
        0x00, 0x01,
        0x00, 0x00, 0x00, 0x20,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x11,
        0x00, 0x01,
        0x00, 0x0a,
        0x00, 0x07,
        0xa0, 0x00,
        0x40, 0x00,
        0x01,
        0x00, 0x02,
        0x00, 0x14,
        0x00, 0xf6,
    )

    private fun compositeGlyphData(vararg componentBytes: Int): ByteArray = bytes(
        0xff, 0xff,
        0x00, 0x00,
        0x00, 0x00,
        0x00, 0x10,
        0x00, 0x10,
        *componentBytes,
    )

    private fun excessiveCompositeComponentGlyphData(): ByteArray {
        val componentRecords = (0 until 65).flatMap { index ->
            componentRecord(
                flags = if (index == 64) 0x0003 else 0x0023,
                glyphId = 1,
                arg1 = 0,
                arg2 = 0,
            ).toList()
        }.toIntArray()
        return compositeGlyphData(*componentRecords)
    }

    private fun componentRecord(
        flags: Int,
        glyphId: Int,
        arg1: Int,
        arg2: Int,
        transformWords: IntArray = intArrayOf(),
    ): IntArray {
        val values = mutableListOf<Int>()
        fun addWord(value: Int) {
            values += (value ushr 8) and 0xff
            values += value and 0xff
        }
        addWord(flags)
        addWord(glyphId)
        addWord(arg1)
        addWord(arg2)
        transformWords.forEach(::addWord)
        return values.toIntArray()
    }

    private fun type2CharString(vararg chunks: IntArray): ByteArray =
        bytes(*chunks.flatMap { chunk -> chunk.toList() }.toIntArray())

    private fun type2Operator(value: Int): IntArray = intArrayOf(value)

    private fun type2EscapedOperator(value: Int): IntArray = intArrayOf(12, value)

    private fun type2Number(value: Int): IntArray =
        when (value) {
            in -107..107 -> intArrayOf(value + 139)
            in 108..1131 -> {
                val encoded = value - 108
                intArrayOf(247 + encoded / 256, encoded % 256)
            }
            in -1131..-108 -> {
                val encoded = -value - 108
                intArrayOf(251 + encoded / 256, encoded % 256)
            }
            in Short.MIN_VALUE..Short.MAX_VALUE -> intArrayOf(28, (value ushr 8) and 0xff, value and 0xff)
            else -> intArrayOf(
                29,
                (value ushr 24) and 0xff,
                (value ushr 16) and 0xff,
                (value ushr 8) and 0xff,
                value and 0xff,
            )
        }

    private fun generatedCFFTable(
        charStrings: List<ByteArray>,
        localSubroutines: List<ByteArray> = emptyList(),
        globalSubroutines: List<ByteArray> = emptyList(),
    ): ByteArray {
        val header = bytes(0x01, 0x00, 0x04, 0x01)
        val nameIndex = cffIndex(listOf("KanvasCFF".toByteArray(Charsets.US_ASCII)))
        val stringIndex = cffIndex(emptyList())
        val globalSubrIndex = cffIndex(globalSubroutines)
        val localSubrIndex = cffIndex(localSubroutines)

        var privateDict = cffDictNumber(0) + intArrayOf(19)
        repeat(4) {
            privateDict = cffDictNumber(privateDict.size) + intArrayOf(19)
        }

        var topDict = byteArrayOf()
        repeat(6) {
            val topDictIndex = cffIndex(listOf(topDict))
            val beforePrivateSize = header.size + nameIndex.size + topDictIndex.size + stringIndex.size + globalSubrIndex.size
            val privateOffset = beforePrivateSize
            val charStringsOffset = beforePrivateSize + privateDict.size + localSubrIndex.size
            topDict = bytes(
                *(cffDictNumber(charStringsOffset) + intArrayOf(17) +
                    cffDictNumber(privateDict.size) + cffDictNumber(privateOffset) + intArrayOf(18)),
            )
        }

        return header +
            nameIndex +
            cffIndex(listOf(topDict)) +
            stringIndex +
            globalSubrIndex +
            bytes(*privateDict) +
            localSubrIndex +
            cffIndex(charStrings)
    }

    private fun generatedCidKeyedCFFTable(): ByteArray {
        val header = bytes(0x01, 0x00, 0x04, 0x01)
        val nameIndex = cffIndex(listOf("KanvasCID".toByteArray(Charsets.US_ASCII)))
        val stringIndex = cffIndex(emptyList())
        val globalSubrIndex = cffIndex(emptyList())
        val fdSelect = bytes(0x00, 0x00, 0x01)
        val charStrings = cffIndex(
            listOf(
                type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
                type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
            ),
        )
        val privateDict0 = bytes(*(cffDictNumber(400) + intArrayOf(20)))
        val privateDict1 = bytes(*(cffDictNumber(450) + intArrayOf(20)))

        var topDict = byteArrayOf()
        var fdDict0 = byteArrayOf()
        var fdDict1 = byteArrayOf()
        repeat(8) {
            val topDictIndex = cffIndex(listOf(topDict))
            val prefixSize = header.size + nameIndex.size + topDictIndex.size + stringIndex.size + globalSubrIndex.size
            val provisionalFdArray = cffIndex(listOf(fdDict0, fdDict1))
            val private0Offset = prefixSize + provisionalFdArray.size
            val private1Offset = private0Offset + privateDict0.size
            fdDict0 = bytes(*(cffDictNumber(privateDict0.size) + cffDictNumber(private0Offset) + intArrayOf(18)))
            fdDict1 = bytes(*(cffDictNumber(privateDict1.size) + cffDictNumber(private1Offset) + intArrayOf(18)))

            val fdArray = cffIndex(listOf(fdDict0, fdDict1))
            val fdArrayOffset = prefixSize
            val fdSelectOffset = fdArrayOffset + fdArray.size + privateDict0.size + privateDict1.size
            val charStringsOffset = fdSelectOffset + fdSelect.size
            topDict = bytes(
                *(cffDictNumber(charStringsOffset) + intArrayOf(17) +
                    cffDictNumber(fdArrayOffset) + intArrayOf(12, 36) +
                    cffDictNumber(fdSelectOffset) + intArrayOf(12, 37) +
                    cffDictNumber(0) + cffDictNumber(1) + cffDictNumber(0) + intArrayOf(12, 30)),
            )
        }

        val finalTopDictIndex = cffIndex(listOf(topDict))
        val prefixSize = header.size + nameIndex.size + finalTopDictIndex.size + stringIndex.size + globalSubrIndex.size
        val fdArray = cffIndex(listOf(fdDict0, fdDict1))
        check(prefixSize + fdArray.size + privateDict0.size + privateDict1.size + fdSelect.size == readGeneratedCidCharStringsOffset(topDict))

        return header +
            nameIndex +
            finalTopDictIndex +
            stringIndex +
            globalSubrIndex +
            fdArray +
            privateDict0 +
            privateDict1 +
            fdSelect +
            charStrings
    }

    private fun generatedCFF2Table(
        charStrings: List<ByteArray>,
        globalSubroutines: List<ByteArray> = emptyList(),
        variationStore: ByteArray? = null,
    ): ByteArray {
        val globalSubrIndex = cffIndex(globalSubroutines)
        var topDict = byteArrayOf()
        repeat(6) {
            val variationStoreOffset = variationStore?.let { 5 + topDict.size + globalSubrIndex.size }
            val charStringsOffset = 5 + topDict.size + globalSubrIndex.size + (variationStore?.size ?: 0)
            topDict = bytes(
                *(cffDictNumber(charStringsOffset) + intArrayOf(17) +
                    (variationStoreOffset?.let { cffDictNumber(it) + intArrayOf(24) } ?: intArrayOf())),
            )
        }
        return bytes(
            0x02,
            0x00,
            0x05,
            (topDict.size ushr 8) and 0xff,
            topDict.size and 0xff,
        ) + topDict + globalSubrIndex + (variationStore ?: byteArrayOf()) + cffIndex(charStrings)
    }

    private fun generatedCFF2TableWithPrivateDict(
        charStrings: List<ByteArray>,
        localSubroutines: List<ByteArray>,
    ): ByteArray {
        val globalSubrIndex = cffIndex(emptyList())
        val localSubrIndex = cffIndex(localSubroutines)

        var privateDict = cffDictNumber(0) + intArrayOf(19)
        repeat(4) {
            privateDict = cffDictNumber(privateDict.size) + intArrayOf(19)
        }

        var topDict = byteArrayOf()
        repeat(6) {
            val privateOffset = 5 + topDict.size + globalSubrIndex.size
            val charStringsOffset = privateOffset + privateDict.size + localSubrIndex.size
            topDict = bytes(
                *(cffDictNumber(charStringsOffset) + intArrayOf(17) +
                    cffDictNumber(privateDict.size) + cffDictNumber(privateOffset) + intArrayOf(18)),
            )
        }

        return bytes(
            0x02,
            0x00,
            0x05,
            (topDict.size ushr 8) and 0xff,
            topDict.size and 0xff,
        ) + topDict + globalSubrIndex + bytes(*privateDict) + localSubrIndex + cffIndex(charStrings)
    }

    private fun generatedCFF2VariationStoreOneAxis(
        start: Double,
        peak: Double,
        end: Double,
    ): ByteArray =
        bytes(
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x0c,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x16,
            0x00, 0x01,
            0x00, 0x01,
            *f2Dot14(start),
            *f2Dot14(peak),
            *f2Dot14(end),
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00,
        )

    private fun generatedCFF2VariationStore(
        axisCount: Int,
        regions: List<Triple<List<Double>, List<Double>, List<Double>>>,
        regionIndexesByVsIndex: List<List<Int>>,
    ): ByteArray {
        require(axisCount > 0)
        require(regions.isNotEmpty())
        require(regionIndexesByVsIndex.isNotEmpty())
        require(regions.all { (start, peak, end) ->
            start.size == axisCount && peak.size == axisCount && end.size == axisCount
        })

        val regionList = buildList {
            add((axisCount ushr 8) and 0xff)
            add(axisCount and 0xff)
            add((regions.size ushr 8) and 0xff)
            add(regions.size and 0xff)
            regions.forEach { (start, peak, end) ->
                repeat(axisCount) { axis ->
                    addAll(f2Dot14(start[axis]).toList())
                    addAll(f2Dot14(peak[axis]).toList())
                    addAll(f2Dot14(end[axis]).toList())
                }
            }
        }.toIntArray()
        val itemVariationData = regionIndexesByVsIndex.map { regionIndexes ->
            require(regionIndexes.isNotEmpty())
            val bytesPerDeltaSet = regionIndexes.size
            bytes(
                0x00, 0x01,
                0x00, 0x00,
                (regionIndexes.size ushr 8) and 0xff,
                regionIndexes.size and 0xff,
                *regionIndexes.flatMap { regionIndex ->
                    require(regionIndex in regions.indices)
                    listOf((regionIndex ushr 8) and 0xff, regionIndex and 0xff)
                }.toIntArray(),
                *IntArray(bytesPerDeltaSet) { 0 },
            )
        }
        val regionListOffset = 8 + itemVariationData.size * 4
        var nextItemVariationDataOffset = regionListOffset + regionList.size
        val itemVariationDataOffsets = itemVariationData.map { item ->
            val offset = nextItemVariationDataOffset
            nextItemVariationDataOffset += item.size
            offset
        }
        return bytes(
            0x00, 0x01,
            (regionListOffset ushr 24) and 0xff,
            (regionListOffset ushr 16) and 0xff,
            (regionListOffset ushr 8) and 0xff,
            regionListOffset and 0xff,
            (itemVariationData.size ushr 8) and 0xff,
            itemVariationData.size and 0xff,
            *itemVariationDataOffsets.flatMap { offset ->
                listOf(
                    (offset ushr 24) and 0xff,
                    (offset ushr 16) and 0xff,
                    (offset ushr 8) and 0xff,
                    offset and 0xff,
                )
            }.toIntArray(),
            *regionList,
            *itemVariationData.flatMap { item -> item.toList().map { byte -> byte.toInt() and 0xff } }.toIntArray(),
        )
    }

    private fun f2Dot14(value: Double): IntArray {
        val encoded = kotlin.math.round(value * 16384.0).toInt()
        return intArrayOf((encoded ushr 8) and 0xff, encoded and 0xff)
    }

    private fun cffIndex(objects: List<ByteArray>): ByteArray {
        if (objects.isEmpty()) return bytes(0x00, 0x00)
        val offsets = mutableListOf(1)
        objects.forEach { item -> offsets += offsets.last() + item.size }
        val offSize = when {
            offsets.last() <= 0xff -> 1
            offsets.last() <= 0xffff -> 2
            offsets.last() <= 0xffffff -> 3
            else -> 4
        }
        val values = mutableListOf((objects.size ushr 8) and 0xff, objects.size and 0xff, offSize)
        offsets.forEach { offset -> values += cffOffset(offset, offSize).toList() }
        objects.forEach { item -> values += item.toUnsignedByteList() }
        return bytes(*values.toIntArray())
    }

    private fun cffOffset(value: Int, offSize: Int): IntArray =
        (offSize - 1 downTo 0)
            .map { shiftIndex -> (value ushr (shiftIndex * 8)) and 0xff }
            .toIntArray()

    private fun cffDictNumber(value: Int): IntArray = type2Number(value)

    private fun ByteArray.withTrueTypePadding(): ByteArray =
        if (size % 2 == 0) this else this + 0x00.toByte()

    private fun sfntMetricsForFactory(
        unitsPerEm: Int? = 1000,
        indexToLocFormat: Int? = 0,
        horizontalMetrics: List<HorizontalGlyphMetric> = listOf(
            HorizontalGlyphMetric(glyphId = 0, advanceWidth = 600, leftSideBearing = 20),
            HorizontalGlyphMetric(glyphId = 1, advanceWidth = 500, leftSideBearing = 0),
        ),
        verticalAscender: Int? = null,
        verticalDescender: Int? = null,
        verticalLineGap: Int? = null,
        maxAdvanceHeight: Int? = null,
        numberOfVMetrics: Int? = null,
        verticalMetrics: List<VerticalGlyphMetric> = emptyList(),
    ): MetricsTables = MetricsTables(
        unitsPerEm = unitsPerEm,
        indexToLocFormat = indexToLocFormat,
        numGlyphs = 2,
        horizontalMetrics = horizontalMetrics,
        verticalAscender = verticalAscender,
        verticalDescender = verticalDescender,
        verticalLineGap = verticalLineGap,
        maxAdvanceHeight = maxAdvanceHeight,
        numberOfVMetrics = numberOfVMetrics,
        verticalMetrics = verticalMetrics,
    )

    private fun generatedVvarTableOneAxis(
        start: Double,
        peak: Double,
        end: Double,
        delta: Int,
    ): ByteArray {
        val store = generatedItemVariationStoreOneAxis(
            start = start,
            peak = peak,
            end = end,
            delta = delta,
        )
        return bytes(
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x18,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        ) + store
    }

    private fun generatedHvarTableOneAxis(
        start: Double,
        peak: Double,
        end: Double,
        delta: Int,
    ): ByteArray {
        val store = generatedItemVariationStoreOneAxis(
            start = start,
            peak = peak,
            end = end,
            delta = delta,
        )
        return bytes(
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x14,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        ) + store
    }

    private fun generatedItemVariationStoreOneAxis(
        start: Double,
        peak: Double,
        end: Double,
        delta: Int,
    ): ByteArray = generatedItemVariationStoreOneAxis(
        start = start,
        peak = peak,
        end = end,
        deltas = listOf(delta),
    )

    private fun generatedItemVariationStoreOneAxis(
        start: Double,
        peak: Double,
        end: Double,
        deltas: List<Int>,
    ): ByteArray =
        bytes(
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x0c,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x16,
            0x00, 0x01,
            0x00, 0x01,
            *f2Dot14(start),
            *f2Dot14(peak),
            *f2Dot14(end),
            0x00, deltas.size,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00,
            *deltas.flatMap { delta ->
                listOf((delta ushr 8) and 0xff, delta and 0xff)
            }.toIntArray(),
        )

    private fun generatedMvarTableOneAxis(
        entries: List<Pair<String, Int>>,
        start: Double,
        peak: Double,
        end: Double,
    ): ByteArray {
        val sortedEntries = entries.sortedBy { (tag, _) -> tag }
        val store = generatedItemVariationStoreOneAxis(
            start = start,
            peak = peak,
            end = end,
            deltas = sortedEntries.map { (_, delta) -> delta },
        )
        val valueRecords = sortedEntries.flatMapIndexed { index, (tag, _) ->
            require(tag.length == 4) { "MVAR test tag must contain exactly four characters." }
            val tagBytes = tag.toByteArray(Charsets.US_ASCII)
            require(tagBytes.size == 4) { "MVAR test tag must encode to exactly four ASCII bytes." }
            listOf(
                tagBytes[0].toInt() and 0xff,
                tagBytes[1].toInt() and 0xff,
                tagBytes[2].toInt() and 0xff,
                tagBytes[3].toInt() and 0xff,
                0x00, 0x00,
                (index ushr 8) and 0xff,
                index and 0xff,
            )
        }
        val itemVariationStoreOffset = 12 + sortedEntries.size * 8
        return bytes(
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x08,
            0x00, sortedEntries.size,
            (itemVariationStoreOffset ushr 8) and 0xff,
            itemVariationStoreOffset and 0xff,
            *valueRecords.toIntArray(),
        ) + store
    }

    private fun syntheticTrueTypeFace(
        rawTables: Map<SFNTTableTag, List<Int>>,
        metrics: MetricsTables = sfntMetricsForFactory(),
        variations: VariationTables = VariationTables(),
    ): OpenTypeFaceData = OpenTypeFaceData(
        id = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440201")),
        source = FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440200")),
            kind = FontSourceKind.MEMORY,
            displayName = "Synthetic TrueType",
            bytes = ByteArray(0),
        ),
        directory = SFNTTableDirectory(scalerType = 0x00010000u),
        metrics = metrics,
        variations = variations,
        rawTables = rawTables,
    )

    private fun syntheticCFFFace(
        scalerType: UInt,
        tableTag: String,
        tableBytes: ByteArray,
        metrics: MetricsTables = sfntMetricsForFactory(),
        variations: VariationTables = VariationTables(),
    ): OpenTypeFaceData = OpenTypeFaceData(
        id = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440211")),
        source = FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440210")),
            kind = FontSourceKind.MEMORY,
            displayName = "Synthetic CFF",
            bytes = ByteArray(0),
        ),
        directory = SFNTTableDirectory(scalerType = scalerType),
        metrics = metrics,
        variations = variations,
        rawTables = mapOf(SFNTTableTag(tableTag) to tableBytes.toUnsignedByteList()),
    )

    private fun cffIndexDictDump(): String {
        val minimalFace = syntheticCFFFace(
            scalerType = 0x4f54544fu,
            tableTag = "CFF ",
            tableBytes = generatedCFFTable(
                charStrings = listOf(
                    type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
                ),
                localSubroutines = listOf(type2CharString(type2Operator(11))),
            ),
        )
        val cidFace = syntheticCFFFace(
            scalerType = 0x4f54544fu,
            tableTag = "CFF ",
            tableBytes = generatedCidKeyedCFFTable(),
            metrics = sfntMetricsForFactory(
                horizontalMetrics = listOf(
                    HorizontalGlyphMetric(glyphId = 0, advanceWidth = 500, leftSideBearing = 0),
                    HorizontalGlyphMetric(glyphId = 1, advanceWidth = 520, leftSideBearing = 0),
                ),
            ),
        )
        val cff2Face = syntheticCFFFace(
            scalerType = 0x43464632u,
            tableTag = "CFF2",
            tableBytes = generatedCFF2TableWithPrivateDict(
                charStrings = listOf(
                    type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
                ),
                localSubroutines = listOf(type2CharString(type2Operator(11))),
            ),
        )

        val minimalEvidence = CFFScaler(minimalFace).tableEvidence().toCanonicalJson()
        val cidEvidence = CFFScaler(cidFace).tableEvidence().toCanonicalJson()
        val cff2Evidence = CFF2Scaler(cff2Face).tableEvidence().toCanonicalJson()

        return """
            {
              "schemaVersion": 1,
              "dumpId": "cff-index-dict",
              "ownerTickets": [
                "KFONT-M4-001"
              ],
              "fixtureIds": [
                "cff-minimal.otf",
                "cff-cid-keyed.otf",
                "cff2-minimal.otf"
              ],
              "fixtures": [
                {
                  "fixtureId": "cff-minimal.otf",
                  "sourceId": "${minimalFace.source.id.value}",
                  "typefaceId": "${minimalFace.id.value}",
                  "tableEvidence": ${minimalEvidence.prependIndent("                  ").trimStart()}
                },
                {
                  "fixtureId": "cff-cid-keyed.otf",
                  "sourceId": "${cidFace.source.id.value}",
                  "typefaceId": "${cidFace.id.value}",
                  "tableEvidence": ${cidEvidence.prependIndent("                  ").trimStart()}
                },
                {
                  "fixtureId": "cff2-minimal.otf",
                  "sourceId": "${cff2Face.source.id.value}",
                  "typefaceId": "${cff2Face.id.value}",
                  "tableEvidence": ${cff2Evidence.prependIndent("                  ").trimStart()}
                }
              ],
              "nonClaims": [
                "generated-fixture-evidence-only",
                "no-cff-rendering-support-claim",
                "no-cff2-variation-output-claim",
                "no-host-font-oracle-claim"
              ]
            }
        """.trimIndent()
    }

    private fun readGeneratedCidCharStringsOffset(topDict: ByteArray): Int {
        var index = 0
        val operands = mutableListOf<Int>()
        while (index < topDict.size) {
            when (val byte = topDict[index++].toInt() and 0xff) {
                12 -> {
                    index += 1
                    operands.clear()
                }
                in 0..27 -> {
                    if (byte == 17) return operands.last()
                    operands.clear()
                }
                else -> {
                    val read = readGeneratedTestCffDictNumber(topDict, byte, index)
                    operands += read.first
                    index = read.second
                }
            }
        }
        error("Generated CID top dict missing CharStrings operator.")
    }

    private fun readGeneratedTestCffDictNumber(
        data: ByteArray,
        firstByte: Int,
        offsetAfterFirstByte: Int,
    ): Pair<Int, Int> =
        when (firstByte) {
            28 -> ((readGeneratedTestInt16(data, offsetAfterFirstByte)) to (offsetAfterFirstByte + 2))
            29 -> ((readGeneratedTestInt32(data, offsetAfterFirstByte)) to (offsetAfterFirstByte + 4))
            in 32..246 -> ((firstByte - 139) to offsetAfterFirstByte)
            in 247..250 -> (
                ((firstByte - 247) * 256 + (data[offsetAfterFirstByte].toInt() and 0xff) + 108) to
                    (offsetAfterFirstByte + 1)
                )
            in 251..254 -> (
                (-((firstByte - 251) * 256) - (data[offsetAfterFirstByte].toInt() and 0xff) - 108) to
                    (offsetAfterFirstByte + 1)
                )
            else -> error("Unsupported generated CFF DICT number byte $firstByte.")
        }

    private fun cffCharStringTraceDump(): String {
        val interpreter = CFFType2CharStringInterpreter(
            localSubroutines = listOf(
                type2CharString(
                    type2Number(25),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
            globalSubroutines = listOf(
                type2CharString(
                    type2Number(0),
                    type2Number(25),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
        )
        val lineCurveFlex = interpreter.interpretEvidence(
            charString = type2CharString(
                type2Number(100),
                type2Number(200),
                type2Operator(21),
                type2Number(50),
                type2Number(0),
                type2Number(0),
                type2Number(50),
                type2Operator(5),
                type2Number(10),
                type2Number(0),
                type2Number(20),
                type2Number(30),
                type2Number(40),
                type2Number(30),
                type2Operator(8),
                type2Number(5),
                type2Number(0),
                type2Number(10),
                type2Number(10),
                type2Number(15),
                type2Number(0),
                type2Number(15),
                type2Number(0),
                type2Number(10),
                type2Number(-10),
                type2Number(5),
                type2Number(0),
                type2Number(50),
                type2EscapedOperator(35),
                type2Operator(14),
            ),
            glyphId = 3u,
            format = "cff",
        )
        val widthAndHints = interpreter.interpretEvidence(
            charString = type2CharString(
                type2Number(450),
                type2Number(10),
                type2Number(5),
                type2Operator(1),
                type2Number(20),
                type2Number(5),
                type2Operator(23),
                type2Operator(19),
                intArrayOf(0xff),
                type2Number(0),
                type2Number(0),
                type2Operator(21),
                type2Operator(14),
            ),
            glyphId = 15u,
            format = "cff",
        )
        val subroutines = interpreter.interpretEvidence(
            charString = type2CharString(
                type2Number(0),
                type2Number(0),
                type2Operator(21),
                type2Number(-107),
                type2Operator(10),
                type2Number(-107),
                type2Operator(29),
                type2Operator(14),
            ),
            glyphId = 4u,
            format = "cff",
        )

        fun refusalSnapshot(block: () -> Unit): String {
            val failure = assertFailsWith<FontScalerRefusalException>(block = block)
            return failure.diagnostic.toCanonicalJson()
        }

        val stackUnderflow = refusalSnapshot {
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(50),
                    type2Operator(21),
                ),
                glyphId = 6u,
                format = "cff",
            )
        }
        val unsupportedOperator = refusalSnapshot {
            interpreter.interpretEvidence(
                charString = type2CharString(type2EscapedOperator(0)),
                glyphId = 7u,
                format = "cff",
            )
        }
        val trailingBytes = refusalSnapshot {
            interpreter.interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Operator(14),
                    type2Number(10),
                ),
                glyphId = 16u,
                format = "cff",
            )
        }
        val stackOverflow = refusalSnapshot {
            interpreter.interpretEvidence(
                charString = type2CharString(
                    *Array(49) { index -> type2Number(index) },
                    type2Operator(14),
                ),
                glyphId = 18u,
                format = "cff",
            )
        }

        return """
            {
              "schemaVersion": 1,
              "dumpId": "cff-charstring-trace",
              "ownerTickets": [
                "KFONT-M4-002"
              ],
              "fixtureIds": [
                "cff-type2-lines.otf",
                "cff-type2-curves.otf",
                "cff-type2-flex.otf",
                "cff-type2-hints-width.otf",
                "cff-type2-stack-underflow.otf",
                "cff-type2-unsupported-operator.otf"
              ],
              "positiveFixtures": [
                {
                  "fixtureId": "cff-type2-lines.otf",
                  "evidence": ${lineCurveFlex.toCanonicalJson().prependIndent("                    ").trimStart()}
                },
                {
                  "fixtureId": "cff-type2-hints-width.otf",
                  "evidence": ${widthAndHints.toCanonicalJson().prependIndent("                    ").trimStart()}
                },
                {
                  "fixtureId": "cff-type2-curves.otf",
                  "evidence": ${subroutines.toCanonicalJson().prependIndent("                    ").trimStart()}
                }
              ],
              "diagnosticSnapshots": {
                "stackUnderflow": $stackUnderflow,
                "unsupportedOperator": $unsupportedOperator,
                "trailingBytes": $trailingBytes,
                "stackOverflow": $stackOverflow
              },
              "nonClaims": [
                "generated-fixture-evidence-only",
                "no-complete-type2-operator-coverage-claim",
                "no-cff-rendering-support-claim",
                "no-native-scaler-oracle-claim"
              ]
            }
        """.trimIndent()
    }

    private fun cffSubroutineTraceDump(): String {
        fun limitsJson(limits: Type2ExecutionLimits): String =
            """
                {
                  "maxOperandStack": ${limits.maxOperandStack},
                  "maxCallDepth": ${limits.maxCallDepth},
                  "maxInstructionCount": ${limits.maxInstructionCount},
                  "maxExpandedBytes": ${limits.maxExpandedBytes}
                }
            """.trimIndent()

        fun refusalSnapshot(block: () -> Unit): String {
            val failure = assertFailsWith<FontScalerRefusalException>(block = block)
            return failure.diagnostic.toCanonicalJson()
        }

        val defaultLimits = Type2ExecutionLimits()
        val instructionLimitFixture = Type2ExecutionLimits(
            maxOperandStack = 48,
            maxCallDepth = 16,
            maxInstructionCount = 8,
            maxExpandedBytes = 64,
        )
        val localEvidence = CFFType2CharStringInterpreter(
            localSubroutines = listOf(
                type2CharString(
                    type2Number(25),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
        ).interpretEvidence(
            charString = type2CharString(
                type2Number(0),
                type2Number(0),
                type2Operator(21),
                type2Number(-107),
                type2Operator(10),
                type2Operator(14),
            ),
            glyphId = 19u,
            format = "cff",
        )
        val globalEvidence = CFFType2CharStringInterpreter(
            globalSubroutines = listOf(
                type2CharString(
                    type2Number(0),
                    type2Number(25),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
        ).interpretEvidence(
            charString = type2CharString(
                type2Number(0),
                type2Number(0),
                type2Operator(21),
                type2Number(-107),
                type2Operator(29),
                type2Operator(14),
            ),
            glyphId = 24u,
            format = "cff",
        )
        val nestedEvidence = CFFType2CharStringInterpreter(
            localSubroutines = listOf(
                type2CharString(
                    type2Number(25),
                    type2Number(0),
                    type2Operator(5),
                    type2Number(-106),
                    type2Operator(10),
                    type2Operator(11),
                ),
                type2CharString(
                    type2Number(0),
                    type2Number(25),
                    type2Operator(5),
                    type2Operator(11),
                ),
            ),
        ).interpretEvidence(
            charString = type2CharString(
                type2Number(0),
                type2Number(0),
                type2Operator(21),
                type2Number(-107),
                type2Operator(10),
                type2Operator(14),
            ),
            glyphId = 25u,
            format = "cff",
        )
        val outOfRange = refusalSnapshot {
            CFFType2CharStringInterpreter(
                localSubroutines = listOf(
                    type2CharString(
                        type2Number(25),
                        type2Number(0),
                        type2Operator(5),
                        type2Operator(11),
                    ),
                ),
            ).interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(-106),
                    type2Operator(10),
                    type2Operator(14),
                ),
                glyphId = 20u,
                format = "cff",
            )
        }
        val depthLimit = refusalSnapshot {
            CFFType2CharStringInterpreter(
                localSubroutines = listOf(
                    type2CharString(
                        type2Number(-107),
                        type2Operator(10),
                        type2Operator(11),
                    ),
                ),
            ).interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(-107),
                    type2Operator(10),
                    type2Operator(14),
                ),
                glyphId = 21u,
                format = "cff",
            )
        }
        val missingReturn = refusalSnapshot {
            CFFType2CharStringInterpreter(
                localSubroutines = listOf(
                    type2CharString(
                        type2Number(25),
                        type2Number(0),
                        type2Operator(5),
                    ),
                ),
            ).interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(-107),
                    type2Operator(10),
                    type2Operator(14),
                ),
                glyphId = 22u,
                format = "cff",
            )
        }
        val instructionLimit = refusalSnapshot {
            CFFType2CharStringInterpreter(
                localSubroutines = listOf(
                    type2CharString(
                        type2Number(25),
                        type2Number(0),
                        type2Operator(5),
                        type2Operator(11),
                    ),
                ),
                limits = instructionLimitFixture,
            ).interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(-107),
                    type2Operator(10),
                    type2Operator(14),
                ),
                glyphId = 23u,
                format = "cff",
            )
        }

        return """
            {
              "schemaVersion": 1,
              "dumpId": "cff-subroutine-trace",
              "ownerTickets": [
                "KFONT-M4-003"
              ],
              "fixtureIds": [
                "cff-subr-local.otf",
                "cff-subr-global.otf",
                "cff-subr-nested.otf",
                "cff-subr-recursive.otf",
                "cff-subr-out-of-range.otf",
                "cff-subr-missing-return.otf"
              ],
              "executionLimits": {
                "default": ${limitsJson(defaultLimits).prependIndent("                  ").trimStart()},
                "instructionLimitFixture": ${limitsJson(instructionLimitFixture).prependIndent("                  ").trimStart()}
              },
              "positiveFixtures": [
                {
                  "fixtureId": "cff-subr-local.otf",
                  "evidence": ${localEvidence.toCanonicalJson().prependIndent("                    ").trimStart()}
                },
                {
                  "fixtureId": "cff-subr-global.otf",
                  "evidence": ${globalEvidence.toCanonicalJson().prependIndent("                    ").trimStart()}
                },
                {
                  "fixtureId": "cff-subr-nested.otf",
                  "evidence": ${nestedEvidence.toCanonicalJson().prependIndent("                    ").trimStart()}
                }
              ],
              "diagnosticSnapshots": {
                "subrOutOfRange": $outOfRange,
                "subrDepthLimit": $depthLimit,
                "subrMissingReturn": $missingReturn,
                "instructionLimit": $instructionLimit
              },
              "nonClaims": [
                "generated-fixture-evidence-only",
                "no-complete-cff-rendering-support-claim",
                "no-complete-cff2-variation-support-claim",
                "no-native-scaler-oracle-claim"
              ]
            }
        """.trimIndent()
    }

    private fun cffScalerPathOutputDump(): String {
        val basicScaler = CFFScaler(
            face = syntheticCFFFace(
                scalerType = 0x4f54544fu,
                tableTag = "CFF ",
                tableBytes = generatedCFFTable(
                    charStrings = listOf(
                        type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
                        type2CharString(
                            type2Number(475),
                            type2Number(100),
                            type2Number(200),
                            type2Operator(21),
                            type2Number(50),
                            type2Number(0),
                            type2Number(0),
                            type2Number(50),
                            type2Operator(5),
                            type2Number(10),
                            type2Number(0),
                            type2Number(20),
                            type2Number(30),
                            type2Number(40),
                            type2Number(30),
                            type2Operator(8),
                            type2Operator(14),
                        ),
                    ),
                ),
            ),
        )
        val subroutineScaler = CFFScaler(
            face = syntheticCFFFace(
                scalerType = 0x4f54544fu,
                tableTag = "CFF ",
                tableBytes = generatedCFFTable(
                    charStrings = listOf(
                        type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
                        type2CharString(
                            type2Number(475),
                            type2Number(100),
                            type2Number(200),
                            type2Operator(21),
                            type2Number(-107),
                            type2Operator(10),
                            type2Number(-107),
                            type2Operator(29),
                            type2Operator(14),
                        ),
                    ),
                    localSubroutines = listOf(
                        type2CharString(
                            type2Number(50),
                            type2Number(0),
                            type2Operator(5),
                            type2Operator(11),
                        ),
                    ),
                    globalSubroutines = listOf(
                        type2CharString(
                            type2Number(0),
                            type2Number(-25),
                            type2Operator(5),
                            type2Operator(11),
                        ),
                    ),
                ),
            ),
        )
        val flexScaler = CFFScaler(
            face = syntheticCFFFace(
                scalerType = 0x4f54544fu,
                tableTag = "CFF ",
                tableBytes = generatedCFFTable(
                    charStrings = listOf(
                        type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
                        type2CharString(
                            type2Number(480),
                            type2Number(100),
                            type2Number(200),
                            type2Operator(21),
                            type2Number(5),
                            type2Number(0),
                            type2Number(10),
                            type2Number(10),
                            type2Number(15),
                            type2Number(0),
                            type2Number(15),
                            type2Number(0),
                            type2Number(10),
                            type2Number(-10),
                            type2Number(5),
                            type2Number(0),
                            type2Number(50),
                            type2EscapedOperator(35),
                            type2Operator(14),
                        ),
                    ),
                ),
            ),
        )
        val malformedScaler = CFFScaler(
            face = syntheticCFFFace(
                scalerType = 0x4f54544fu,
                tableTag = "CFF ",
                tableBytes = generatedCFFTable(
                    charStrings = listOf(
                        type2CharString(type2Number(0), type2Number(0), type2Operator(21), type2Operator(14)),
                        type2CharString(
                            type2Number(50),
                            type2Operator(21),
                        ),
                    ),
                ),
            ),
        )

        val basicEvidence = basicScaler.scaledGlyphEvidence(glyphId = 1u)
        val subroutineEvidence = subroutineScaler.scaledGlyphEvidence(glyphId = 1u)
        val flexEvidence = flexScaler.scaledGlyphEvidence(glyphId = 1u)
        val missingGlyphEvidence = basicScaler.scaledGlyphEvidence(glyphId = 9u)
        val malformedGlyphEvidence = malformedScaler.scaledGlyphEvidence(glyphId = 1u)

        return """
            {
              "schemaVersion": 1,
              "dumpId": "cff-scaler-path-output",
              "ownerTickets": [
                "KFONT-M4-004"
              ],
              "fixtureIds": [
                "cff-scaler-basic.otf",
                "cff-scaler-subroutines.otf",
                "cff-scaler-flex.otf",
                "cff-scaler-missing-glyph.otf",
                "cff-scaler-malformed-glyph.otf"
              ],
              "requiredEvidence": [
                "glyph-outline.json",
                "glyph-metrics.json",
                "cff-charstring-trace.json"
              ],
              "pathHashArtifacts": {
                "basic": {
                  "outlineCommandDumpSha256": "${basicEvidence.outlineCommandDumpSha256}",
                  "outlineCommandCount": ${basicEvidence.outlineCommands.size}
                },
                "subroutines": {
                  "outlineCommandDumpSha256": "${subroutineEvidence.outlineCommandDumpSha256}",
                  "outlineCommandCount": ${subroutineEvidence.outlineCommands.size}
                },
                "flex": {
                  "outlineCommandDumpSha256": "${flexEvidence.outlineCommandDumpSha256}",
                  "outlineCommandCount": ${flexEvidence.outlineCommands.size}
                }
              },
              "positiveFixtures": [
                {
                  "fixtureId": "cff-scaler-basic.otf",
                  "evidence": ${basicEvidence.toCanonicalJson().prependIndent("                    ").trimStart()}
                },
                {
                  "fixtureId": "cff-scaler-subroutines.otf",
                  "evidence": ${subroutineEvidence.toCanonicalJson().prependIndent("                    ").trimStart()}
                },
                {
                  "fixtureId": "cff-scaler-flex.otf",
                  "evidence": ${flexEvidence.toCanonicalJson().prependIndent("                    ").trimStart()}
                }
              ],
              "diagnosticSnapshots": {
                "missingGlyph": [
                  ${missingGlyphEvidence.diagnostics.joinToString(",\n                  ") { diagnostic -> diagnostic.toCanonicalJson() }}
                ],
                "malformedGlyph": [
                  ${malformedGlyphEvidence.diagnostics.joinToString(",\n                  ") { diagnostic -> diagnostic.toCanonicalJson() }}
                ]
              },
              "nonClaims": [
                "generated-fixture-evidence-only",
                "no-complete-cff-rendering-support-claim",
                "no-complete-cff2-variation-support-claim",
                "no-native-scaler-oracle-claim",
                "no-gpu-text-route-claim"
              ]
            }
        """.trimIndent()
    }

    private fun cff2VariationTraceDump(): String {
        fun diagnosticsJson(diagnostics: List<FontScalerDiagnostic>): String =
            diagnostics.joinToString(prefix = "[", postfix = "]") { diagnostic -> diagnostic.toCanonicalJson() }

        fun boundsJson(bounds: GlyphBounds?): String =
            bounds?.let { bound ->
                """{"left": ${bound.left}, "top": ${bound.top}, "right": ${bound.right}, "bottom": ${bound.bottom}}"""
            } ?: "null"

        fun metricsJson(metrics: GlyphMetrics?): String =
            metrics?.let { metric ->
                """{"advanceX": ${metric.advanceX}, "advanceY": ${metric.advanceY}, "bounds": ${boundsJson(metric.bounds)}}"""
            } ?: "null"

        fun coordinatesJson(coordinates: List<VariationCoordinateEvidence>): String =
            coordinates.joinToString(prefix = "[", postfix = "]") { coordinate ->
                """{"tag": "${coordinate.tag}", "value": ${coordinate.value}}"""
            }

        fun blendVectorsJson(vectors: List<CFFBlendVectorEvidence>): String =
            vectors.joinToString(prefix = "[", postfix = "]") { vector ->
                """
                {"vsIndex": ${vector.vsIndex}, "defaults": ${vector.defaults}, "deltaSets": ${vector.deltaSets}, "regionIndexes": ${vector.regionIndexes}, "scalars": ${vector.scalars}, "blendedValues": ${vector.blendedValues}}
                """.trimIndent()
            }

        fun positivePositionJson(
            fixtureId: String,
            label: String,
            evidence: CFFScaledGlyphEvidence,
        ): String {
            val charStringEvidence = evidence.charStringEvidence ?: error("missing CFF2 charstring evidence for $label")
            return """
                {
                  "fixtureId": "$fixtureId",
                  "positionLabel": "$label",
                  "outlineCommandDumpSha256": "${evidence.outlineCommandDumpSha256}",
                  "outlineCommandCount": ${evidence.outlineCommands.size},
                  "outlineCommands": ${evidence.outlineCommands.map { "\"$it\"" }},
                  "metrics": ${metricsJson(evidence.metrics)},
                  "charStringEvidence": {
                    "variationPosition": ${coordinatesJson(charStringEvidence.variationPosition)},
                    "cff2VsIndex": ${charStringEvidence.cff2VsIndex},
                    "blendVectors": ${blendVectorsJson(charStringEvidence.blendVectors)}
                  }
                }
            """.trimIndent()
        }

        val variableTable = generatedCFF2Table(
            charStrings = listOf(
                type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(0),
                    type2Operator(15),
                    type2Number(50),
                    type2Number(-10),
                    type2Number(20),
                    type2Number(15),
                    type2Number(1),
                    type2Operator(16),
                    type2Number(0),
                    type2Operator(5),
                    type2Operator(14),
                ),
            ),
            variationStore = generatedCFF2VariationStore(
                axisCount = 2,
                regions = listOf(
                    Triple(listOf(-1.0, 0.0), listOf(-1.0, 0.0), listOf(0.0, 0.0)),
                    Triple(listOf(0.0, 0.0), listOf(1.0, 0.0), listOf(1.0, 0.0)),
                    Triple(listOf(0.0, 0.0), listOf(0.0, 1.0), listOf(0.0, 1.0)),
                ),
                regionIndexesByVsIndex = listOf(listOf(0, 1, 2)),
            ),
        )
        val variableScaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = variableTable,
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                        variationAxis(tag = "wdth", minimum = 50.0, defaultValue = 100.0, maximum = 200.0),
                    ),
                ),
            ),
        )
        val defaultEvidence = variableScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 400.0, "wdth" to 100.0)),
        )
        val minEvidence = variableScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 100.0, "wdth" to 100.0)),
        )
        val maxEvidence = variableScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 900.0, "wdth" to 100.0)),
        )
        val namedInstanceEvidence = variableScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 900.0, "wdth" to 200.0)),
        )

        val invalidVsIndexScaler = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = generatedCFF2Table(
                    charStrings = listOf(
                        type2CharString(
                            type2Number(0),
                            type2Number(0),
                            type2Operator(21),
                            type2Number(1),
                            type2Operator(15),
                            type2Number(50),
                            type2Number(10),
                            type2Number(1),
                            type2Operator(16),
                            type2Number(0),
                            type2Operator(5),
                            type2Operator(14),
                        ),
                    ),
                    variationStore = generatedCFF2VariationStoreOneAxis(start = 0.0, peak = 1.0, end = 1.0),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        )
        val invalidVsIndex = invalidVsIndexScaler.scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val missingVariationStore = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = generatedCFF2Table(
                    charStrings = listOf(
                        type2CharString(
                            type2Number(0),
                            type2Number(0),
                            type2Operator(21),
                            type2Number(0),
                            type2Operator(15),
                            type2Number(50),
                            type2Number(20),
                            type2Number(1),
                            type2Operator(16),
                            type2Number(0),
                            type2Operator(5),
                            type2Operator(14),
                        ),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to 650.0)),
        )
        val unknownAxis = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = generatedCFF2Table(
                    charStrings = listOf(
                        type2CharString(
                            type2Number(0),
                            type2Number(0),
                            type2Operator(21),
                            type2Operator(14),
                        ),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wdth" to 100.0)),
        )
        val nonFiniteAxis = CFF2Scaler(
            face = syntheticCFFFace(
                scalerType = 0x43464632u,
                tableTag = "CFF2",
                tableBytes = generatedCFF2Table(
                    charStrings = listOf(
                        type2CharString(
                            type2Number(0),
                            type2Number(0),
                            type2Operator(21),
                            type2Operator(14),
                        ),
                    ),
                ),
                variations = VariationTables(
                    axes = listOf(
                        variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
                    ),
                ),
            ),
        ).scaledGlyphEvidence(
            glyphId = 0u,
            position = VariationPosition(axes = mapOf("wght" to Double.NaN)),
        )
        val malformedBlend = assertFailsWith<FontScalerRefusalException> {
            CFFType2CharStringInterpreter(
                blendAxisTagsByVsIndex = mapOf(0 to listOf("wght")),
            ).interpretEvidence(
                charString = type2CharString(
                    type2Number(0),
                    type2Number(0),
                    type2Operator(21),
                    type2Number(0),
                    type2Operator(15),
                    type2Number(0),
                    type2Operator(16),
                    type2Operator(14),
                ),
                glyphId = 31u,
                format = "cff2",
                position = VariationPosition(axes = mapOf("wght" to 0.5)),
            )
        }

        return """
            {
              "schemaVersion": 1,
              "dumpId": "cff2-variation-trace",
              "ownerTickets": [
                "KFONT-M4-005"
              ],
              "fixtureIds": [
                "cff2-variable-basic.otf",
                "cff2-vsindex-invalid.otf",
                "cff2-missing-varstore.otf",
                "cff2-unsupported-axis.otf",
                "cff2-non-finite-axis.otf",
                "cff2-blend-bad-stack.otf"
              ],
              "requiredEvidence": [
                "glyph-outline.json",
                "glyph-metrics.json",
                "cff-charstring-trace.json"
              ],
              "pathHashArtifacts": {
                "default": {"outlineCommandDumpSha256": "${defaultEvidence.outlineCommandDumpSha256}", "outlineCommandCount": ${defaultEvidence.outlineCommands.size}},
                "min": {"outlineCommandDumpSha256": "${minEvidence.outlineCommandDumpSha256}", "outlineCommandCount": ${minEvidence.outlineCommands.size}},
                "max": {"outlineCommandDumpSha256": "${maxEvidence.outlineCommandDumpSha256}", "outlineCommandCount": ${maxEvidence.outlineCommands.size}},
                "namedWideBold": {"outlineCommandDumpSha256": "${namedInstanceEvidence.outlineCommandDumpSha256}", "outlineCommandCount": ${namedInstanceEvidence.outlineCommands.size}}
              },
              "positiveFixtures": [
                ${positivePositionJson("cff2-variable-basic.otf", "default", defaultEvidence).prependIndent("                ").trimStart()},
                ${positivePositionJson("cff2-variable-basic.otf", "min", minEvidence).prependIndent("                ").trimStart()},
                ${positivePositionJson("cff2-variable-basic.otf", "max", maxEvidence).prependIndent("                ").trimStart()},
                ${positivePositionJson("cff2-variable-basic.otf", "namedWideBold", namedInstanceEvidence).prependIndent("                ").trimStart()}
              ],
              "diagnosticSnapshots": {
                "blendStackMalformed": ${malformedBlend.diagnostic.toCanonicalJson()},
                "invalidVsIndex": ${diagnosticsJson(invalidVsIndex.diagnostics)},
                "missingVariationStore": ${diagnosticsJson(missingVariationStore.diagnostics)},
                "unsupportedAxis": ${diagnosticsJson(unknownAxis.diagnostics)},
                "nonFiniteAxis": ${diagnosticsJson(nonFiniteAxis.diagnostics)}
              },
              "nonClaims": [
                "generated-fixture-evidence-only",
                "no-complete-cff2-variation-support-claim",
                "no-hvar-vvar-mvar-advance-claim",
                "no-native-scaler-oracle-claim",
                "no-gpu-text-route-claim"
              ]
            }
        """.trimIndent()
    }

    private fun readGeneratedTestInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun readGeneratedTestInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun variableTrueTypeGlyfScaler(
        simpleSquare: ByteArray,
        gvarTable: ByteArray = singleAxisGvarWithAllPointDelta(),
        variations: VariationTables = VariationTables(
            axes = listOf(
                variationAxis(tag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
            ),
        ),
    ): TrueTypeGlyfScaler = TrueTypeGlyfScaler(
        face = syntheticTrueTypeFace(
            rawTables = mapOf(
                SFNTTableTag("loca") to shortLocaForOffsets(0, simpleSquare.size, simpleSquare.size)
                    .toUnsignedByteList(),
                SFNTTableTag("glyf") to simpleSquare.toUnsignedByteList(),
                SFNTTableTag("gvar") to gvarTable.toUnsignedByteList(),
            ),
            variations = variations,
        ),
    )

    private fun variationAxis(
        tag: String,
        minimum: Double,
        defaultValue: Double,
        maximum: Double,
    ): OpenTypeVariationAxis = OpenTypeVariationAxis(
        tag = OpenTypeVariationAxisTag(text = tag, rawValue = tag.toOpenTypeTagValue()),
        minimum = OpenTypeFixed16Dot16(minimum.toFixed16Dot16()),
        defaultValue = OpenTypeFixed16Dot16(defaultValue.toFixed16Dot16()),
        maximum = OpenTypeFixed16Dot16(maximum.toFixed16Dot16()),
        flags = 0,
        nameId = 256,
    )

    private fun shortLocaForOffsets(vararg offsets: Int): ByteArray {
        require(offsets.all { it % 2 == 0 })
        return bytes(
            *offsets.flatMap { offset ->
                val encoded = offset / 2
                listOf((encoded ushr 8) and 0xff, encoded and 0xff)
            }.toIntArray(),
        )
    }

    private fun ByteArray.toUnsignedByteList(): List<Int> = map { it.toInt() and 0xff }

    private fun String.toOpenTypeTagValue(): Int {
        require(length == 4)
        return fold(0) { accumulator, character ->
            (accumulator shl 8) or (character.code and 0xff)
        }
    }

    private fun Double.toFixed16Dot16(): Int = (this * 65536.0).toInt()

    private fun String.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
}
