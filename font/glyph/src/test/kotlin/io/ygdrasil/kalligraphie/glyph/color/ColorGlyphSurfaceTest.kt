package io.ygdrasil.kalligraphie.glyph.color

import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.glyph.GlyphStrikeKey
import io.ygdrasil.kalligraphie.glyph.OutlineGlyphRepresentation
import io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import io.ygdrasil.kalligraphie.text.shaping.EmojiSequenceFact
import io.ygdrasil.kalligraphie.text.shaping.EmojiSequenceKind
import io.ygdrasil.kalligraphie.text.shaping.EmojiSequenceShaper
import io.ygdrasil.kalligraphie.text.shaping.ShapingRequest

/**
 * Verifies that the pure Kotlin color glyph package exposes the planned public surface.
 */
class ColorGlyphSurfaceTest {
    /**
     * References every color glyph planning type so the module fails to compile until the
     * surface exists.
     */
    @Test
    fun exposesColorGlyphPipelineSurface() {
        val names = listOf(
            ColorGlyphPlanner::class.simpleName,
            COLRGlyphPlanner::class.simpleName,
            COLRPaintGraph::class.simpleName,
            COLRPaintNode::class.simpleName,
            CPALPalette::class.simpleName,
            CPALPaletteSelection::class.simpleName,
            CPALPaletteOverride::class.simpleName,
            BitmapStrikeSelector::class.simpleName,
            BitmapGlyphPlan::class.simpleName,
            PNGGlyphDecoder::class.simpleName,
            SVGGlyphRenderer::class.simpleName,
            SVGGlyphParser::class.simpleName,
            EmojiGlyphDispatcher::class.simpleName,
            SimpleEmojiSequencePlanner::class.simpleName,
            EmojiRouteTrace::class.simpleName,
            EmojiRouteRequest::class.simpleName,
            EmojiFallbackAttempt::class.simpleName,
            EmojiRoutePlanReference::class.simpleName,
            ColorGlyphDiagnostic::class.simpleName,
        )

        assertEquals(
            listOf(
                "ColorGlyphPlanner",
                "COLRGlyphPlanner",
                "COLRPaintGraph",
                "COLRPaintNode",
                "CPALPalette",
                "CPALPaletteSelection",
                "CPALPaletteOverride",
                "BitmapStrikeSelector",
                "BitmapGlyphPlan",
                "PNGGlyphDecoder",
                "SVGGlyphRenderer",
                "SVGGlyphParser",
                "EmojiGlyphDispatcher",
                "SimpleEmojiSequencePlanner",
                "EmojiRouteTrace",
                "EmojiRouteRequest",
                "EmojiFallbackAttempt",
                "EmojiRoutePlanReference",
                "ColorGlyphDiagnostic",
            ),
            names,
        )
    }

    @Test
    fun parsesCPALV0PalettesFromRawTableBytes() {
        val table = assertNotNull(CPALV0Parser.parse(syntheticCpalV0()))

        assertEquals(2, table.numPaletteEntries)
        assertEquals(4, table.numColorRecords)
        assertEquals(
            listOf(
                CPALPalette(
                    index = 0,
                    colors = listOf(
                        argb(alpha = 0x44, red = 0x11, green = 0x22, blue = 0x33),
                        argb(alpha = 0x88, red = 0x55, green = 0x66, blue = 0x77),
                    ),
                ),
                CPALPalette(
                    index = 1,
                    colors = listOf(
                        argb(alpha = 0xCC, red = 0x99, green = 0xAA, blue = 0xBB),
                        argb(alpha = 0xFF, red = 0xDD, green = 0xEE, blue = 0x10),
                    ),
                ),
            ),
            table.palettes,
        )
    }

    @Test
    fun rejectsCPALV0PalettesThatExceedCaps() {
        val bytes = ByteArray(12)
        writeU16(bytes, 0, 0)
        writeU16(bytes, 2, 4097)
        writeU16(bytes, 4, 1)
        writeU16(bytes, 6, 4097)
        writeU32(bytes, 8, 12)

        assertNull(CPALV0Parser.parse(bytes))
    }

    @Test
    fun selectsCPALPaletteByIndexAndAppliesOverridesByPaletteEntry() {
        val table = assertNotNull(CPALV0Parser.parse(syntheticCpalV0()))
        val selection = CPALPaletteSelection(
            index = 1,
            overrides = listOf(
                CPALPaletteOverride(index = 0, color = argb(alpha = 0xFF, red = 0x00, green = 0x00, blue = 0xFF)),
                CPALPaletteOverride(index = 99, color = argb(alpha = 0xFF, red = 0x00, green = 0x00, blue = 0x00)),
                CPALPaletteOverride(index = 1, color = argb(alpha = 0xFF, red = 0x00, green = 0xFF, blue = 0x00)),
                CPALPaletteOverride(index = 1, color = argb(alpha = 0xFF, red = 0xFF, green = 0x00, blue = 0x00)),
            ),
        )

        val palette = assertNotNull(selection.select(table))

        assertEquals(1, palette.index)
        assertEquals(
            listOf(
                argb(alpha = 0xFF, red = 0x00, green = 0x00, blue = 0xFF),
                argb(alpha = 0xFF, red = 0xFF, green = 0x00, blue = 0x00),
            ),
            palette.colors,
        )
    }

    @Test
    fun rejectsMissingCPALPaletteSelectionWithoutApplyingOverrides() {
        val table = assertNotNull(CPALV0Parser.parse(syntheticCpalV0()))

        assertNull(CPALPaletteSelection(index = -1).select(table))
        assertNull(CPALPaletteSelection(index = 2).select(table))
    }

    @Test
    fun parsesCOLRV0BaseGlyphsFromRawTableBytes() {
        val table = assertNotNull(COLRV0Parser.parse(syntheticColrV0()))

        assertEquals(
            listOf(
                COLRBaseGlyphRecord(
                    glyphId = 100,
                    firstLayerIndex = 0,
                    numLayers = 2,
                ),
            ),
            table.baseGlyphRecords,
        )
        assertEquals(
            listOf(
                COLRLayerRecord(glyphId = 11, paletteIndex = 0),
                COLRLayerRecord(glyphId = 12, paletteIndex = 1),
            ),
            table.layersForGlyph(100),
        )
    }

    @Test
    fun parsesRetainedCOLRV0RecordsFromVersionOneContainerWithoutWeakeningStrictParser() {
        val bytes = syntheticColrV0()
        writeU16(bytes, 0, 1)

        assertNull(COLRV0Parser.parse(bytes))
        val retained = assertNotNull(COLRV0Parser.parseRetainedV0Records(bytes))

        assertEquals(listOf(100), retained.baseGlyphRecords.map { it.glyphId })
        assertEquals(listOf(11, 12), retained.layersForGlyph(100).map { it.glyphId })
    }

    @Test
    fun rejectsCOLRV0TablesWithOutOfRangeLayerSlices() {
        val bytes = syntheticColrV0()
        writeU16(bytes, 16, 1)
        writeU16(bytes, 18, 2)

        assertNull(COLRV0Parser.parse(bytes))
    }

    @Test
    fun buildsCOLRV0PaintGraphWithPaletteIndexes() {
        val table = assertNotNull(COLRV0Parser.parse(syntheticColrV0()))
        val palette = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())).palettes[0]
        val graph = SimpleCOLRGlyphPlanner(table).plan(glyphId = 100, palette = palette)

        assertEquals("colr-v0-glyph", graph.root.kind)
        assertEquals(100, graph.root.glyphId)
        assertEquals(listOf(1, 2), graph.root.children)
        assertEquals(
            listOf(
                COLRPaintNode(id = 0, kind = "colr-v0-glyph", children = listOf(1, 2), glyphId = 100),
                COLRPaintNode(id = 1, kind = "colr-v0-layer", paletteIndex = 0, glyphId = 11),
                COLRPaintNode(id = 2, kind = "colr-v0-layer", paletteIndex = 1, glyphId = 12),
            ),
            graph.nodes,
        )
    }

    @Test
    fun parsesCOLRV1BaseGlyphPaintGlyphSolidGraph() {
        val table = assertNotNull(
            COLRV1Parser.parse(
                syntheticColrV1GlyphSolid(
                    baseGlyph = 120,
                    glyph = 121,
                    paletteIndex = 2,
                    alphaF2Dot14 = 0x2000,
                ),
            ),
        )

        assertEquals(listOf(120), table.baseGlyphPaintRecords.map { record -> record.glyphId })
        val paint = assertNotNull(table.paintForGlyph(120))
        assertEquals(
            COLRV1Paint.Glyph(
                glyphId = 121,
                paint = COLRV1Paint.Solid(
                    paletteIndex = 2,
                    alpha = 0.5f,
                ),
            ),
            paint,
        )

        val graph = assertNotNull(table.paintGraphForGlyph(120))
        assertEquals("colr-v1-glyph", graph.root.kind)
        assertEquals(120, graph.root.glyphId)
        assertEquals(listOf(1), graph.root.children)
        assertEquals(
            listOf(
                COLRPaintNode(id = 0, kind = "colr-v1-glyph", children = listOf(1), glyphId = 120),
                COLRPaintNode(id = 1, kind = "colr-v1-paint-glyph", children = listOf(2), glyphId = 121),
                COLRPaintNode(id = 2, kind = "colr-v1-paint-solid", paletteIndex = 2),
            ),
            graph.nodes,
        )
    }

    @Test
    fun parsesCOLRV1LayerListPaintColrLayersAndClipBox() {
        val table = assertNotNull(
            COLRV1Parser.parse(
                syntheticColrV1LayersAndClip(
                    baseGlyph = 130,
                    firstLayerGlyph = 131,
                    secondLayerGlyph = 132,
                ),
            ),
        )

        assertEquals(2, table.layerPaints.size)
        assertEquals(
            COLRV1Paint.Layers(
                paints = listOf(
                    COLRV1Paint.Glyph(
                        glyphId = 131,
                        paint = COLRV1Paint.Solid(paletteIndex = 0, alpha = 1f),
                    ),
                    COLRV1Paint.Glyph(
                        glyphId = 132,
                        paint = COLRV1Paint.Solid(paletteIndex = 1, alpha = 0.5f),
                    ),
                ),
            ),
            table.paintForGlyph(130),
        )
        assertEquals(
            COLRV1ClipBox(xMin = -10, yMin = -20, xMax = 30, yMax = 40),
            table.clipBoxForGlyph(130),
        )

        val graph = assertNotNull(table.paintGraphForGlyph(130))
        assertEquals(
            listOf(
                COLRPaintNode(id = 0, kind = "colr-v1-glyph", children = listOf(1), glyphId = 130),
                COLRPaintNode(id = 1, kind = "colr-v1-paint-layers", children = listOf(2, 4)),
                COLRPaintNode(id = 2, kind = "colr-v1-paint-glyph", children = listOf(3), glyphId = 131),
                COLRPaintNode(id = 3, kind = "colr-v1-paint-solid", paletteIndex = 0),
                COLRPaintNode(id = 4, kind = "colr-v1-paint-glyph", children = listOf(5), glyphId = 132),
                COLRPaintNode(id = 5, kind = "colr-v1-paint-solid", paletteIndex = 1),
            ),
            graph.nodes,
        )
    }

    @Test
    fun parsesCOLRV1GradientPaintsAsRendererNeutralData() {
        val table = assertNotNull(COLRV1Parser.parse(syntheticColrV1Gradients()))

        assertEquals(
            COLRV1Paint.LinearGradient(
                colorLine = syntheticColorLine(),
                x0 = 1,
                y0 = 2,
                x1 = 3,
                y1 = 4,
                x2 = 5,
                y2 = 6,
            ),
            table.paintForGlyph(200),
        )
        assertEquals(
            COLRV1Paint.RadialGradient(
                colorLine = syntheticColorLine(),
                x0 = -1,
                y0 = -2,
                radius0 = 7,
                x1 = 8,
                y1 = 9,
                radius1 = 10,
            ),
            table.paintForGlyph(201),
        )
        assertEquals(
            COLRV1Paint.SweepGradient(
                colorLine = syntheticColorLine(),
                centerX = 20,
                centerY = 30,
                startAngle = 0.25f,
                endAngle = 0.75f,
            ),
            table.paintForGlyph(202),
        )

        val graph = assertNotNull(table.paintGraphForGlyph(200))
        assertEquals(
            listOf(
                COLRPaintNode(id = 0, kind = "colr-v1-glyph", children = listOf(1), glyphId = 200),
                COLRPaintNode(id = 1, kind = "colr-v1-paint-linear-gradient"),
            ),
            graph.nodes,
        )
    }

    @Test
    fun parsesCOLRV1CompositeAndPaintColrGlyphAsRendererNeutralData() {
        val table = assertNotNull(COLRV1Parser.parse(syntheticColrV1CompositeAndColrGlyph()))

        assertEquals(
            COLRV1Paint.Composite(
                source = COLRV1Paint.Solid(paletteIndex = 4, alpha = 1f),
                mode = COLRV1CompositeMode.SRC_OVER,
                backdrop = COLRV1Paint.ColrGlyph(glyphId = 211),
            ),
            table.paintForGlyph(210),
        )

        val graph = assertNotNull(table.paintGraphForGlyph(210))
        assertEquals(
            listOf(
                COLRPaintNode(id = 0, kind = "colr-v1-glyph", children = listOf(1), glyphId = 210),
                COLRPaintNode(id = 1, kind = "colr-v1-paint-composite-src-over", children = listOf(2, 3)),
                COLRPaintNode(id = 2, kind = "colr-v1-paint-solid", paletteIndex = 4),
                COLRPaintNode(id = 3, kind = "colr-v1-paint-colr-glyph", glyphId = 211),
            ),
            graph.nodes,
        )
    }

    @Test
    fun rejectsCOLRV1GradientPaintWithOutOfBoundsColorLineOffset() {
        val bytes = syntheticColrV1Gradients()
        writeU24(bytes, 65, bytes.size)

        assertNull(COLRV1Parser.parse(bytes))
    }

    @Test
    fun rejectsUnsupportedOrMalformedCOLRV1PaintsWithoutThrowing() {
        assertNull(
            COLRV1Parser.parse(
                syntheticColrV1UnsupportedPaint(
                    baseGlyph = 140,
                    paintFormat = 4,
                ),
            ),
        )
        assertNull(
            COLRV1Parser.parse(
                syntheticColrV1GlyphSolid(
                    baseGlyph = 141,
                    glyph = 142,
                    paletteIndex = 0,
                    alphaF2Dot14 = 0x4000,
                ).copyOf(52),
            ),
        )
    }

    @Test
    fun buildsCOLRV1BudgetExceededRefusalDiagnostic() {
        val diagnostic = COLRV1Parser.budgetExceededDiagnostic(
            glyphId = 150,
            limitName = "expandedPaintCount",
            limit = 65_536,
            observed = 65_537,
        )

        assertEquals(150, diagnostic.glyphId)
        assertEquals("colr", diagnostic.route)
        assertEquals(ColorGlyphDiagnosticCodes.COLRV1BudgetExceeded, diagnostic.code)
        assertEquals("warning", diagnostic.severity)
        assertTrue(diagnostic.detail.contains("limitName=expandedPaintCount"))
        assertTrue(diagnostic.detail.contains("limit=65536"))
        assertTrue(diagnostic.detail.contains("observed=65537"))
        assertEquals(
            """
            {"glyphId": 150, "route": "colr", "code": "text.color.COLRv1-budget-exceeded", "detail": "${diagnostic.detail}", "severity": "warning", "message": "COLRv1 paint graph budget expandedPaintCount exceeded for glyph 150: observed 65537, limit 65536."}
            """.trimIndent(),
            diagnostic.toCanonicalJson(),
        )
    }

    @Test
    fun detectsCOLRV1PaintColrGlyphCyclesWithStableDiagnostic() {
        val table = COLRV1Table(
            baseGlyphPaintRecords = listOf(
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 160,
                    paint = COLRV1Paint.ColrGlyph(glyphId = 161),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 161,
                    paint = COLRV1Paint.Glyph(
                        glyphId = 162,
                        paint = COLRV1Paint.ColrGlyph(glyphId = 160),
                    ),
                ),
            ),
        )

        val diagnostic = assertNotNull(table.paintColrGlyphCycleDiagnostic(glyphId = 160))

        assertEquals(160, diagnostic.glyphId)
        assertEquals("colr", diagnostic.route)
        assertEquals(ColorGlyphDiagnosticCodes.COLRV1CycleDetected, diagnostic.code)
        assertEquals("warning", diagnostic.severity)
        assertTrue(diagnostic.detail.contains("cyclePath=160>161>160"))
        assertTrue(diagnostic.detail.contains("cycleLength=2"))
        assertEquals(
            """
            {"glyphId": 160, "route": "colr", "code": "text.color.COLRv1-cycle-detected", "detail": "${diagnostic.detail}", "severity": "warning", "message": "COLRv1 PaintColrGlyph cycle detected for glyph 160: 160>161>160."}
            """.trimIndent(),
            diagnostic.toCanonicalJson(),
        )
    }

    @Test
    fun dispatchesEmojiGlyphsByRoutePreference() {
        val dispatcher = SimpleEmojiGlyphDispatcher(
            EmojiGlyphRouteAvailability(
                colrGlyphs = setOf(10),
                bitmapGlyphs = setOf(10, 20),
                pngGlyphs = setOf(20, 30),
                svgGlyphs = setOf(30, 40),
                outlineGlyphs = setOf(40, 50),
            ),
        )

        assertEquals("colr", dispatcher.dispatch(glyphId = 10, strikeKey = strikeKey()).route)
        assertEquals("bitmap", dispatcher.dispatch(glyphId = 20, strikeKey = strikeKey()).route)
        assertEquals("png", dispatcher.dispatch(glyphId = 30, strikeKey = strikeKey()).route)
        assertEquals("svg", dispatcher.dispatch(glyphId = 40, strikeKey = strikeKey()).route)
        assertEquals("outline", dispatcher.dispatch(glyphId = 50, strikeKey = strikeKey()).route)
    }

    @Test
    fun canonicalRepresentationPriorityIsReusableWithoutDispatcherState() {
        assertEquals(
            EmojiGlyphRepresentationRoute.COLR,
            EmojiGlyphRepresentationPriority.select(
                colrAvailable = true,
                bitmapAvailable = true,
                pngAvailable = true,
                svgAvailable = true,
                outlineAvailable = true,
            ),
        )
        assertEquals(
            EmojiGlyphRepresentationRoute.BITMAP,
            EmojiGlyphRepresentationPriority.select(
                colrAvailable = false,
                bitmapAvailable = true,
                pngAvailable = false,
                svgAvailable = true,
                outlineAvailable = true,
            ),
        )
        assertEquals(
            EmojiGlyphRepresentationRoute.OUTLINE,
            EmojiGlyphRepresentationPriority.select(
                colrAvailable = false,
                bitmapAvailable = false,
                pngAvailable = false,
                svgAvailable = false,
                outlineAvailable = true,
            ),
        )
    }

    @Test
    fun recordsEmojiGlyphDispatchDiagnostics() {
        val dispatcher = SimpleEmojiGlyphDispatcher(
            EmojiGlyphRouteAvailability(
                bitmapGlyphs = setOf(60),
                pngGlyphs = setOf(60),
                outlineGlyphs = setOf(60),
            ),
        )

        val dispatch = dispatcher.dispatch(glyphId = 60, strikeKey = strikeKey())

        assertEquals("bitmap", dispatch.route)
        assertEquals(listOf("colr", "bitmap", "png", "outline"), dispatch.diagnostics.map { it.route })
        assertTrue(dispatch.diagnostics.first().message.contains("unavailable"))
        assertTrue(dispatch.diagnostics[1].message.contains("Selected"))
        assertTrue(dispatch.diagnostics.drop(2).all { diagnostic -> diagnostic.message.contains("lower preference") })
    }

    @Test
    fun recordsEmojiGlyphDispatchDiagnosticsWithStableCodesAndDetails() {
        val dispatcher = SimpleEmojiGlyphDispatcher(
            EmojiGlyphRouteAvailability(
                bitmapGlyphs = setOf(60),
                pngGlyphs = setOf(60),
                outlineGlyphs = setOf(60),
            ),
        )

        val dispatch = dispatcher.dispatch(glyphId = 60, strikeKey = strikeKey())
        val dump = dispatch.toCanonicalJson()

        assertEquals("bitmap", dispatch.route)
        assertEquals(
            listOf(
                ColorGlyphDiagnosticCodes.ColorGlyphUnavailable,
                ColorGlyphDiagnosticCodes.EmojiRouteSelected,
                ColorGlyphDiagnosticCodes.EmojiRouteLowerPreferenceSkipped,
                ColorGlyphDiagnosticCodes.EmojiRouteLowerPreferenceSkipped,
            ),
            dispatch.diagnostics.map { diagnostic -> diagnostic.code },
        )
        assertEquals(
            listOf(
                "candidate=colr;glyphId=60;sizePx=16.0",
                "selected=bitmap;glyphId=60;sizePx=16.0",
                "candidate=png;selected=bitmap;glyphId=60;sizePx=16.0",
                "candidate=outline;selected=bitmap;glyphId=60;sizePx=16.0",
            ),
            dispatch.diagnostics.map { diagnostic -> diagnostic.detail },
        )
        assertEquals(listOf("info", "info", "info", "info"), dispatch.diagnostics.map { it.severity })
        assertEquals(64, dispatch.dumpSha256.length)
        assertTrue(dump.contains("\"code\": \"text.emoji.color-glyph-unavailable\""))
        assertTrue(dump.contains("\"detail\": \"candidate=colr;glyphId=60;sizePx=16.0\""))
        assertTrue(dump.contains("\"severity\": \"info\""))
        assertEvidenceDumpClean(dump)
    }

    @Test
    fun recordsFullMissingEmojiRouteRefusalWithStableSpecCode() {
        val dispatch = SimpleEmojiGlyphDispatcher(EmojiGlyphRouteAvailability())
            .dispatch(glyphId = 61, strikeKey = strikeKey())

        assertEquals("missing", dispatch.route)
        assertTrue(
            dispatch.diagnostics.any { diagnostic ->
                diagnostic.route == "missing" &&
                    diagnostic.code == ColorGlyphDiagnosticCodes.EmojiFallbackUnavailable &&
                    diagnostic.detail == "glyphId=61;sizePx=16.0;availableRoutes=none" &&
                    diagnostic.severity == "warning"
            },
        )
        assertTrue(dispatch.toCanonicalJson().contains("\"code\": \"text.emoji.fallback-unavailable\""))
        assertEvidenceDumpClean(dispatch.toCanonicalJson())
    }

    @Test
    fun emojiSequencePlannerGoldenMatchesRepoFixture() {
        val planner = SimpleEmojiSequencePlanner()
        val sequenceText = "\u2764\uFE0F \uD83D\uDC4B\uD83C\uDFFD \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 1\uFE0F\u20E3 \uD83C\uDDEB\uD83C\uDDF7"
        val facts = EmojiSequenceShaper().sequenceFacts(
            ShapingRequest(
                text = sequenceText,
                textRange = sequenceText.indices,
                fontSize = 16f,
            ),
        ).associateBy { it.kind }
        val supportedRoleText = "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB"
        val supportedRoleFact = EmojiSequenceShaper().sequenceFacts(
            ShapingRequest(
                text = supportedRoleText,
                textRange = supportedRoleText.indices,
                fontSize = 16f,
            ),
        ).single()
        val unsupportedText = "\u270C\uFE0F\uD83C\uDFFF\u200D\uD83D\uDCBB"
        val unsupportedFact = EmojiSequenceShaper().sequenceFacts(
            ShapingRequest(
                text = unsupportedText,
                textRange = unsupportedText.indices,
                fontSize = 16f,
            ),
        ).single()
        val colorTypeface = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655441301"))
        val outlineTypeface = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655441302"))

        val dump = emojiRouteTraceFixtureDump(
            listOf(
                EmojiRouteTraceFixtureCase(
                    caseId = "variation-selector-colr",
                    sourceText = "\u2764\uFE0F",
                    trace = planner.plan(
                        EmojiRouteRequest(
                            sequenceFact = requireNotNull(facts[EmojiSequenceKind.VariationSelector]),
                            unicodeVersion = "16.0.0",
                            glyphId = 510,
                            strikeKey = strikeKey(typefaceId = colorTypeface),
                            fallbackAttempts = listOf(
                                emojiFallbackAttempt(
                                    familyName = "Alpha Sans",
                                    typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655441303")),
                                    covered = false,
                                    reason = "requested-family",
                                ),
                                emojiFallbackAttempt(
                                    familyName = "Noto Color Emoji",
                                    typefaceId = colorTypeface,
                                    covered = true,
                                    reason = "emoji-preference",
                                    selected = true,
                                ),
                            ),
                            availability = EmojiGlyphRouteAvailability(colrGlyphs = setOf(510)),
                            evidenceRef = EmojiRoutePlanReference(
                                dumpId = "color-glyph-plan",
                                caseId = "colrv0-layered-palette-override",
                            ),
                        ),
                    ),
                ),
                EmojiRouteTraceFixtureCase(
                    caseId = "skin-tone-bitmap",
                    sourceText = "\uD83D\uDC4B\uD83C\uDFFD",
                    trace = planner.plan(
                        EmojiRouteRequest(
                            sequenceFact = requireNotNull(facts[EmojiSequenceKind.SkinTone]),
                            unicodeVersion = "16.0.0",
                            glyphId = 511,
                            strikeKey = strikeKey(typefaceId = colorTypeface),
                            fallbackAttempts = listOf(
                                emojiFallbackAttempt(
                                    familyName = "Noto Color Emoji",
                                    typefaceId = colorTypeface,
                                    covered = true,
                                    reason = "emoji-preference",
                                    selected = true,
                                ),
                            ),
                            availability = EmojiGlyphRouteAvailability(
                                bitmapGlyphs = setOf(511),
                                pngGlyphs = setOf(511),
                                outlineGlyphs = setOf(511),
                            ),
                            evidenceRef = EmojiRoutePlanReference(
                                dumpId = "bitmap-glyph-plan",
                                caseId = "cbdt-cblc-png",
                            ),
                        ),
                    ),
                ),
                EmojiRouteTraceFixtureCase(
                    caseId = "zwj-outline-fallback",
                    sourceText = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
                    trace = planner.plan(
                        EmojiRouteRequest(
                            sequenceFact = requireNotNull(facts[EmojiSequenceKind.ZWJ]),
                            unicodeVersion = "16.0.0",
                            glyphId = 512,
                            strikeKey = strikeKey(typefaceId = outlineTypeface),
                            fallbackAttempts = listOf(
                                emojiFallbackAttempt(
                                    familyName = "Monochrome Emoji",
                                    typefaceId = outlineTypeface,
                                    covered = true,
                                    reason = "outline-fallback",
                                    selected = true,
                                ),
                            ),
                            availability = EmojiGlyphRouteAvailability(outlineGlyphs = setOf(512)),
                        ),
                    ),
                ),
                EmojiRouteTraceFixtureCase(
                    caseId = "role-skin-tone-colr",
                    sourceText = "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB",
                    trace = planner.plan(
                        EmojiRouteRequest(
                            sequenceFact = supportedRoleFact,
                            unicodeVersion = "16.0.0",
                            glyphId = 515,
                            strikeKey = strikeKey(typefaceId = colorTypeface),
                            fallbackAttempts = listOf(
                                emojiFallbackAttempt(
                                    familyName = "Noto Color Emoji",
                                    typefaceId = colorTypeface,
                                    covered = true,
                                    reason = "emoji-preference",
                                    selected = true,
                                ),
                            ),
                            availability = EmojiGlyphRouteAvailability(
                                colrGlyphs = setOf(515),
                                outlineGlyphs = setOf(515),
                            ),
                        ),
                    ),
                ),
                EmojiRouteTraceFixtureCase(
                    caseId = "keycap-png",
                    sourceText = "1\uFE0F\u20E3",
                    trace = planner.plan(
                        EmojiRouteRequest(
                            sequenceFact = requireNotNull(facts[EmojiSequenceKind.Keycap]),
                            unicodeVersion = "16.0.0",
                            glyphId = 513,
                            strikeKey = strikeKey(typefaceId = colorTypeface),
                            fallbackAttempts = listOf(
                                emojiFallbackAttempt(
                                    familyName = "Noto Color Emoji",
                                    typefaceId = colorTypeface,
                                    covered = true,
                                    reason = "emoji-preference",
                                    selected = true,
                                ),
                            ),
                            availability = EmojiGlyphRouteAvailability(
                                pngGlyphs = setOf(513),
                                outlineGlyphs = setOf(513),
                            ),
                            evidenceRef = EmojiRoutePlanReference(
                                dumpId = "bitmap-glyph-plan",
                                caseId = "sbix-png",
                            ),
                        ),
                    ),
                ),
                EmojiRouteTraceFixtureCase(
                    caseId = "flag-svg",
                    sourceText = "\uD83C\uDDEB\uD83C\uDDF7",
                    trace = planner.plan(
                        EmojiRouteRequest(
                            sequenceFact = requireNotNull(facts[EmojiSequenceKind.Flag]),
                            unicodeVersion = "16.0.0",
                            glyphId = 514,
                            strikeKey = strikeKey(typefaceId = colorTypeface),
                            fallbackAttempts = listOf(
                                emojiFallbackAttempt(
                                    familyName = "Noto Color Emoji",
                                    typefaceId = colorTypeface,
                                    covered = true,
                                    reason = "emoji-preference",
                                    selected = true,
                                ),
                            ),
                            availability = EmojiGlyphRouteAvailability(
                                svgGlyphs = setOf(514),
                                outlineGlyphs = setOf(514),
                            ),
                        ),
                    ),
                ),
                EmojiRouteTraceFixtureCase(
                    caseId = "fallback-unavailable",
                    sourceText = "1\uFE0F\u20E3",
                    trace = planner.plan(
                        EmojiRouteRequest(
                            sequenceFact = requireNotNull(facts[EmojiSequenceKind.Keycap]),
                            unicodeVersion = "16.0.0",
                            glyphId = 520,
                            strikeKey = strikeKey(),
                            fallbackAttempts = listOf(
                                emojiFallbackAttempt(
                                    familyName = "Alpha Sans",
                                    typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655441304")),
                                    covered = false,
                                    reason = "requested-family",
                                ),
                            ),
                        ),
                    ),
                ),
                EmojiRouteTraceFixtureCase(
                    caseId = "color-glyph-unavailable",
                    sourceText = "\uD83C\uDDEB\uD83C\uDDF7",
                    trace = planner.plan(
                        EmojiRouteRequest(
                            sequenceFact = requireNotNull(facts[EmojiSequenceKind.Flag]),
                            unicodeVersion = "16.0.0",
                            glyphId = 521,
                            strikeKey = strikeKey(typefaceId = colorTypeface),
                            fallbackAttempts = listOf(
                                emojiFallbackAttempt(
                                    familyName = "Noto Color Emoji",
                                    typefaceId = colorTypeface,
                                    covered = true,
                                    reason = "emoji-preference",
                                    selected = true,
                                ),
                            ),
                            availability = EmojiGlyphRouteAvailability(outlineGlyphs = setOf(521)),
                            allowMonochromeFallback = false,
                        ),
                    ),
                ),
                EmojiRouteTraceFixtureCase(
                    caseId = "unsupported-sequence",
                    sourceText = "\u270C\uFE0F\uD83C\uDFFF\u200D\uD83D\uDCBB",
                    trace = planner.plan(
                        EmojiRouteRequest(
                            sequenceFact = unsupportedFact,
                            unicodeVersion = "16.0.0",
                            glyphId = null,
                            strikeKey = strikeKey(),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(readProjectFile("reports/font/fixtures/expected/color/emoji-route-trace.json").trim(), dump.trim())
        assertEvidenceDumpClean(dump)
    }

    @Test
    fun plansColorGlyphRunByRoutePriorityWithStableFallbackDiagnostics() {
        val planner = SimpleColorGlyphPlanner(
            availability = EmojiGlyphRouteAvailability(
                colrGlyphs = setOf(10),
                bitmapGlyphs = setOf(10, 20),
                pngGlyphs = setOf(10, 20, 30),
                svgGlyphs = setOf(10, 20, 30, 40),
                outlineGlyphs = setOf(10, 20, 30, 40, 50),
            ),
            outlineRepresentations = mapOf(
                50 to OutlineGlyphRepresentation(glyphId = 50, pathCommands = listOf("M 0 0")),
            ),
        )
        val run = glyphRun(glyphIds = listOf(10, 20, 30, 40, 50, 60))

        val first = planner.plan(run = run, strikeKey = strikeKey())
        val second = planner.plan(run = run, strikeKey = strikeKey())

        assertEquals(
            listOf("colr", "bitmap", "png", "svg", "outline"),
            first.routes.map { route -> route.route },
        )
        assertEquals(first, second)
        assertTrue(
            first.diagnostics.any { diagnostic ->
                diagnostic.glyphId == 20 &&
                    diagnostic.route == "colr" &&
                    diagnostic.message.contains("unavailable")
            },
        )
        assertTrue(
            first.diagnostics.any { diagnostic ->
                diagnostic.glyphId == 10 &&
                    diagnostic.route == "bitmap" &&
                    diagnostic.message.contains("lower preference")
            },
        )
        assertTrue(
            first.diagnostics.any { diagnostic ->
                diagnostic.glyphId == 60 &&
                    diagnostic.route == "missing" &&
                    diagnostic.severity == "warning"
            },
        )
    }

    @Test
    fun dumpsColorGlyphPlanningResultWithStableRouteOrderAndHashes() {
        val planner = SimpleColorGlyphPlanner(
            availability = EmojiGlyphRouteAvailability(
                colrGlyphs = setOf(10),
                bitmapGlyphs = setOf(10, 20),
                pngGlyphs = setOf(10, 20, 30),
                svgGlyphs = setOf(10, 20, 30, 40),
                outlineGlyphs = setOf(10, 20, 30, 40, 50),
            ),
            outlineRepresentations = mapOf(
                50 to OutlineGlyphRepresentation(glyphId = 50, pathCommands = listOf("M 0 0")),
            ),
        )
        val run = glyphRun(glyphIds = listOf(10, 20, 30, 40, 50, 60))

        val first = planner.plan(run = run, strikeKey = strikeKey())
        val second = planner.plan(run = run, strikeKey = strikeKey())
        val dump = first.toCanonicalJson()

        assertEquals(first.toCanonicalJson(), second.toCanonicalJson())
        assertEquals(first.dumpSha256, second.dumpSha256)
        assertEquals(64, first.dumpSha256.length)
        assertTrue(dump.contains("\"routeOrder\": [\"colr\", \"bitmap\", \"png\", \"svg\", \"outline\"]"))
        assertTrue(dump.indexOf("\"glyphId\": 10") < dump.indexOf("\"glyphId\": 20"))
        assertTrue(dump.indexOf("\"glyphId\": 20") < dump.indexOf("\"glyphId\": 30"))
        assertTrue(dump.indexOf("\"glyphId\": 30") < dump.indexOf("\"glyphId\": 40"))
        assertTrue(dump.indexOf("\"glyphId\": 40") < dump.indexOf("\"glyphId\": 50"))
        assertTrue(dump.contains("\"outlineFallback\": true"))
        assertTrue(dump.contains("\"pathCommandCount\": 1"))
        assertTrue(dump.contains("\"code\": \"text.emoji.fallback-unavailable\""))
        assertEvidenceDumpClean(dump)
    }

    @Test
    fun buildsCOLRV0ColorGlyphPlanWithPaletteOverridesArtifactKeysAndDeterministicDump() {
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440401"))
        val strikeKey = strikeKey(
            typefaceId = typefaceId,
            paletteIdentity = "brand-alt",
        )
        val planner = COLRV0ColorGlyphPlanner(
            colr = assertNotNull(COLRV0Parser.parse(syntheticColrV0())),
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            layerBounds = mapOf(
                11 to ColorGlyphBounds(xMin = 0, yMin = -2, xMax = 6, yMax = 8),
                12 to ColorGlyphBounds(xMin = 4, yMin = -1, xMax = 10, yMax = 9),
            ),
        )

        val decision = planner.plan(
            glyphId = 100,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(
                index = 1,
                overrides = listOf(
                    CPALPaletteOverride(index = 1, color = argb(alpha = 0xFF, red = 0xFF, green = 0x00, blue = 0x00)),
                ),
            ),
        )

        val plan = assertNotNull(decision.plan)
        assertEquals("colr", decision.selectedRoute?.route)
        assertEquals(100, plan.glyphId)
        assertEquals(typefaceId, plan.typefaceId)
        assertEquals("text.glyph.color.COLR", plan.artifactKey.route)
        assertEquals("brand-alt", plan.palette.identity)
        assertEquals(1, plan.palette.selectionIndex)
        assertEquals(1, plan.palette.resolvedIndex)
        assertEquals(1, plan.palette.overrideCount)
        assertEquals(ColorGlyphBounds(xMin = 0, yMin = -2, xMax = 10, yMax = 9), plan.bounds)
        assertEquals(2, plan.layers.size)
        assertEquals(0, plan.layers[0].layerIndex)
        assertEquals(11, plan.layers[0].glyphId)
        assertEquals(0, plan.layers[0].paletteIndex)
        assertEquals(argb(alpha = 0xCC, red = 0x99, green = 0xAA, blue = 0xBB), plan.layers[0].resolvedColor)
        assertEquals("text.glyph.outline", plan.layers[0].outlineArtifactKey.route)
        assertEquals(1, plan.layers[1].layerIndex)
        assertEquals(12, plan.layers[1].glyphId)
        assertEquals(1, plan.layers[1].paletteIndex)
        assertEquals(argb(alpha = 0xFF, red = 0xFF, green = 0x00, blue = 0x00), plan.layers[1].resolvedColor)
        assertEquals("allow-monochrome-outline-fallback", plan.fallbackPolicy)
        assertTrue(decision.diagnostics.isEmpty())

        val dump = plan.toCanonicalJson()
        val expectedGolden = readProjectFile("reports/font/fixtures/expected/color/color-glyph-plan.json")
        assertEquals(dump.trim(), extractPlanCaseJson(expectedGolden, "colrv0-layered-palette-override").trim())
        assertEquals(canonicalDumpBodySha256(dump), plan.dumpSha256)
        assertEvidenceDumpClean(dump)
    }

    @Test
    fun buildsCOLRV1SolidGlyphColrGlyphPlanAndDeterministicPaintGraphDump() {
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440411"))
        val cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0()))
        val table = COLRV1Table(
            baseGlyphPaintRecords = listOf(
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 220,
                    paint = COLRV1Paint.ColrGlyph(glyphId = 221),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 221,
                    paint = COLRV1Paint.Glyph(
                        glyphId = 222,
                        paint = COLRV1Paint.Solid(
                            paletteIndex = 1,
                            alpha = 0.5f,
                        ),
                    ),
                ),
            ),
        )
        val planner = COLRV1ColorGlyphPlanner(
            colr = table,
            cpal = cpal,
            glyphBounds = mapOf(
                222 to ColorGlyphBounds(xMin = 2, yMin = -3, xMax = 12, yMax = 9),
            ),
        )

        val decision = planner.plan(
            glyphId = 220,
            typefaceId = typefaceId,
            strikeKey = strikeKey(typefaceId = typefaceId, paletteIdentity = "brand-colrv1"),
            paletteSelection = CPALPaletteSelection(index = 1),
        )

        val plan = assertNotNull(decision.plan)
        val graph = assertNotNull(plan.paintGraph)
        assertEquals("colr", decision.selectedRoute?.route)
        assertEquals("colrv1", plan.routeKind)
        assertEquals(220, plan.glyphId)
        assertEquals(typefaceId, plan.typefaceId)
        assertEquals("brand-colrv1", plan.palette.identity)
        assertEquals(ColorGlyphBounds(xMin = 2, yMin = -3, xMax = 12, yMax = 9), plan.bounds)
        assertEquals(0, plan.layers.size)
        assertEquals(0, graph.rootNodeId)
        assertEquals("solid-glyph-colr-glyph", graph.supportedOperationGroup)
        assertEquals(3, graph.nodes.size)
        assertEquals("colrv1-paint-colr-glyph", graph.nodes[0].kind)
        assertEquals(221, graph.nodes[0].referencedColrGlyphId)
        assertEquals("colrv1-paint-glyph", graph.nodes[1].kind)
        assertEquals(222, graph.nodes[1].glyphId)
        assertEquals("text.glyph.outline", graph.nodes[1].outlineArtifactKey?.route)
        assertEquals("colrv1-paint-solid", graph.nodes[2].kind)
        assertEquals(1, graph.nodes[2].paletteIndex)
        assertEquals("#7FDDEE10", graph.nodes[2].resolvedColorArgb)
        assertEquals(0.5f, graph.nodes[2].alpha)
        assertTrue(decision.diagnostics.isEmpty())

        val graphDump = graph.toCanonicalJson()
        assertEquals(
            extractGraphCaseJson(
                readProjectFile("reports/font/fixtures/expected/color/colrv1-paint-graph.json"),
                "colrv1-solid-glyph-colr-glyph",
            ).trim(),
            graphDump.trim(),
        )
        val planDump = plan.toCanonicalJson()
        assertEquals(
            extractPlanCaseJson(
                readProjectFile("reports/font/fixtures/expected/color/color-glyph-plan.json"),
                "colrv1-solid-glyph-colr-glyph",
            ).trim(),
            planDump.trim(),
        )
        assertEvidenceDumpClean(graphDump)
        assertEvidenceDumpClean(planDump)
    }

    @Test
    fun refusesCOLRV1VarSolidWithoutVariationSupportAndFallsBackToOutlineWhenAllowed() {
        val table = assertNotNull(
            COLRV1Parser.parse(
                syntheticColrV1GlyphVarSolid(
                    baseGlyph = 230,
                    glyph = 231,
                    paletteIndex = 1,
                    alphaF2Dot14 = 0x4000,
                    varIndexBase = 7,
                ),
            ),
        )
        val planner = COLRV1ColorGlyphPlanner(
            colr = table,
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            glyphBounds = mapOf(
                231 to ColorGlyphBounds(xMin = 1, yMin = -1, xMax = 5, yMax = 6),
            ),
        )

        val decision = planner.plan(
            glyphId = 230,
            typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440412")),
            strikeKey = strikeKey(),
            allowMonochromeFallback = true,
            outlineFallback = OutlineGlyphRepresentation(glyphId = 230, pathCommands = listOf("M 0 0", "L 3 4")),
        )

        assertNull(decision.plan)
        assertEquals("outline", decision.selectedRoute?.route)
        assertEquals(1, decision.diagnostics.size)
        assertEquals(ColorGlyphDiagnosticCodes.COLRV1PaintUnsupported, decision.diagnostics.single().code)
        assertTrue(decision.diagnostics.single().detail.contains("varIndexBase=7"))
        assertTrue(decision.diagnostics.single().detail.contains("nodeId=2"))
    }

    @Test
    fun buildsCOLRV1GradientPlansAndDeterministicPaintGraphDumps() {
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440413"))
        val cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0()))
        val table = COLRV1Table(
            baseGlyphPaintRecords = listOf(
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 240,
                    paint = COLRV1Paint.Glyph(
                        glyphId = 241,
                        paint = COLRV1Paint.LinearGradient(
                            colorLine = plannerLinearGradientColorLine(),
                            x0 = 1,
                            y0 = 2,
                            x1 = 9,
                            y1 = 10,
                            x2 = 4,
                            y2 = 5,
                        ),
                    ),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 242,
                    paint = COLRV1Paint.Glyph(
                        glyphId = 243,
                        paint = COLRV1Paint.RadialGradient(
                            colorLine = plannerRadialGradientColorLine(),
                            x0 = -2,
                            y0 = -1,
                            radius0 = 3,
                            x1 = 8,
                            y1 = 7,
                            radius1 = 11,
                        ),
                    ),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 244,
                    paint = COLRV1Paint.Glyph(
                        glyphId = 245,
                        paint = COLRV1Paint.SweepGradient(
                            colorLine = plannerSweepGradientColorLine(),
                            centerX = 12,
                            centerY = 14,
                            startAngle = 0.125f,
                            endAngle = 0.875f,
                        ),
                    ),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 246,
                    paint = COLRV1Paint.Glyph(
                        glyphId = 247,
                        paint = COLRV1Paint.LinearGradient(
                            colorLine = plannerVariableGradientColorLine(),
                            x0 = 0,
                            y0 = 0,
                            x1 = 6,
                            y1 = 8,
                            x2 = 2,
                            y2 = 3,
                        ),
                    ),
                ),
            ),
        )
        val planner = COLRV1ColorGlyphPlanner(
            colr = table,
            cpal = cpal,
            glyphBounds = mapOf(
                241 to ColorGlyphBounds(xMin = 1, yMin = -2, xMax = 9, yMax = 7),
                243 to ColorGlyphBounds(xMin = -3, yMin = -4, xMax = 8, yMax = 10),
                245 to ColorGlyphBounds(xMin = 0, yMin = -1, xMax = 11, yMax = 12),
                247 to ColorGlyphBounds(xMin = 2, yMin = -3, xMax = 13, yMax = 9),
            ),
            variationAlphaDeltas = mapOf(17L to 0.25f),
        )
        val strikeKey = strikeKey(
            typefaceId = typefaceId,
            paletteIdentity = "brand-gradient",
            variationCoordinates = mapOf(
                "wght" to 0.25f,
                "opsz" to 0.75f,
            ),
        )
        val graphFixture = readProjectFile("reports/font/fixtures/expected/color/colrv1-paint-graph.json")
        val planFixture = readProjectFile("reports/font/fixtures/expected/color/color-glyph-plan.json")

        val linearDecision = planner.plan(
            glyphId = 240,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 1),
        )
        val linearPlan = assertNotNull(linearDecision.plan)
        val linearGraph = assertNotNull(linearPlan.paintGraph)
        val linearGradient = assertNotNull(linearGraph.nodes[1].gradient)
        assertEquals("gradient-glyph", linearGraph.supportedOperationGroup)
        assertEquals("colrv1-paint-linear-gradient", linearGraph.nodes[1].kind)
        assertEquals(ColorGlyphBounds(xMin = 1, yMin = -2, xMax = 9, yMax = 7), linearGraph.nodes[1].bounds)
        assertEquals("repeat", linearGradient.extendMode)
        assertEquals(
            listOf("#CC99AABB", "#7FDDEE10"),
            linearGradient.stops.map { stop -> stop.resolvedColorArgb },
        )
        assertEquals(
            COLRV1LinearGradientGeometry(x0 = 1, y0 = 2, x1 = 9, y1 = 10, x2 = 4, y2 = 5),
            linearGradient.linearGeometry,
        )
        assertEquals(
            extractGraphCaseJson(graphFixture, "colrv1-linear-gradient").trim(),
            linearGraph.toCanonicalJson().trim(),
        )
        assertEquals(
            extractPlanCaseJson(planFixture, "colrv1-linear-gradient").trim(),
            linearPlan.toCanonicalJson().trim(),
        )
        val radialDecision = planner.plan(
            glyphId = 242,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 1),
        )
        val radialPlan = assertNotNull(radialDecision.plan)
        val radialGraph = assertNotNull(radialPlan.paintGraph)
        val radialGradient = assertNotNull(radialGraph.nodes[1].gradient)
        assertEquals("colrv1-paint-radial-gradient", radialGraph.nodes[1].kind)
        assertEquals("pad", radialGradient.extendMode)
        assertEquals(
            COLRV1RadialGradientGeometry(x0 = -2, y0 = -1, radius0 = 3, x1 = 8, y1 = 7, radius1 = 11),
            radialGradient.radialGeometry,
        )
        assertEquals(
            extractGraphCaseJson(graphFixture, "colrv1-radial-gradient").trim(),
            radialGraph.toCanonicalJson().trim(),
        )
        assertEquals(
            extractPlanCaseJson(planFixture, "colrv1-radial-gradient").trim(),
            radialPlan.toCanonicalJson().trim(),
        )
        val sweepDecision = planner.plan(
            glyphId = 244,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 1),
        )
        val sweepPlan = assertNotNull(sweepDecision.plan)
        val sweepGraph = assertNotNull(sweepPlan.paintGraph)
        val sweepGradient = assertNotNull(sweepGraph.nodes[1].gradient)
        assertEquals("colrv1-paint-sweep-gradient", sweepGraph.nodes[1].kind)
        assertEquals("reflect", sweepGradient.extendMode)
        assertEquals(
            COLRV1SweepGradientGeometry(centerX = 12, centerY = 14, startAngle = 0.125f, endAngle = 0.875f),
            sweepGradient.sweepGeometry,
        )
        assertEquals(
            extractGraphCaseJson(graphFixture, "colrv1-sweep-gradient").trim(),
            sweepGraph.toCanonicalJson().trim(),
        )
        assertEquals(
            extractPlanCaseJson(planFixture, "colrv1-sweep-gradient").trim(),
            sweepPlan.toCanonicalJson().trim(),
        )
        val variableDecision = planner.plan(
            glyphId = 246,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 1),
        )
        val variablePlan = assertNotNull(variableDecision.plan)
        val variableGraph = assertNotNull(variablePlan.paintGraph)
        val variableGradient = assertNotNull(variableGraph.nodes[1].gradient)
        assertEquals("colrv1-paint-linear-gradient", variableGraph.nodes[1].kind)
        assertEquals(mapOf("opsz" to 0.75f, "wght" to 0.25f), variableGradient.variationCoordinates)
        assertEquals(17L, variableGradient.stops[1].varIndexBase)
        assertEquals(0.25f, variableGradient.stops[1].appliedAlphaDelta)
        assertEquals(0.75f, variableGradient.stops[1].alpha)
        assertEquals("#BFDDEE10", variableGradient.stops[1].resolvedColorArgb)
        assertEquals(
            extractGraphCaseJson(graphFixture, "colrv1-var-linear-gradient").trim(),
            variableGraph.toCanonicalJson().trim(),
        )
        assertEquals(
            extractPlanCaseJson(planFixture, "colrv1-var-linear-gradient").trim(),
            variablePlan.toCanonicalJson().trim(),
        )
        assertEvidenceDumpClean(linearGraph.toCanonicalJson())
        assertEvidenceDumpClean(linearPlan.toCanonicalJson())
        assertEvidenceDumpClean(radialGraph.toCanonicalJson())
        assertEvidenceDumpClean(radialPlan.toCanonicalJson())
        assertEvidenceDumpClean(sweepGraph.toCanonicalJson())
        assertEvidenceDumpClean(sweepPlan.toCanonicalJson())
        assertEvidenceDumpClean(variableGraph.toCanonicalJson())
        assertEvidenceDumpClean(variablePlan.toCanonicalJson())
    }

    @Test
    fun refusesCOLRV1VariableGradientStopWithoutVariationSupportAndFallsBackToOutlineWhenAllowed() {
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440414"))
        val table = COLRV1Table(
            baseGlyphPaintRecords = listOf(
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 246,
                    paint = COLRV1Paint.Glyph(
                        glyphId = 247,
                        paint = COLRV1Paint.LinearGradient(
                            colorLine = plannerVariableGradientColorLine(),
                            x0 = 0,
                            y0 = 0,
                            x1 = 6,
                            y1 = 8,
                            x2 = 2,
                            y2 = 3,
                        ),
                    ),
                ),
            ),
        )
        val planner = COLRV1ColorGlyphPlanner(
            colr = table,
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            glyphBounds = mapOf(
                247 to ColorGlyphBounds(xMin = 2, yMin = -3, xMax = 13, yMax = 9),
            ),
        )

        val decision = planner.plan(
            glyphId = 246,
            typefaceId = typefaceId,
            strikeKey = strikeKey(
                typefaceId = typefaceId,
                paletteIdentity = "brand-gradient",
                variationCoordinates = mapOf("wght" to 0.25f),
            ),
            paletteSelection = CPALPaletteSelection(index = 1),
            allowMonochromeFallback = true,
            outlineFallback = OutlineGlyphRepresentation(glyphId = 246, pathCommands = listOf("M 0 0", "L 5 7")),
        )

        assertNull(decision.plan)
        assertEquals("outline", decision.selectedRoute?.route)
        assertEquals(1, decision.diagnostics.size)
        assertEquals(ColorGlyphDiagnosticCodes.COLRV1PaintUnsupported, decision.diagnostics.single().code)
        assertTrue(decision.diagnostics.single().detail.contains("reason=variable-color-data-unsupported"))
        assertTrue(decision.diagnostics.single().detail.contains("stopIndex=1"))
        assertTrue(decision.diagnostics.single().detail.contains("varIndexBase=17"))
        assertEquals(
            extractDiagnosticsCaseJson(
                readProjectFile("reports/font/fixtures/expected/color/color-glyph-plan.json"),
                "colrv1-var-linear-gradient-outline-fallback",
            ).trim(),
            normalizeEmbeddedJson(diagnosticsJson(decision.diagnostics, indent = "      ")).trim(),
        )
        assertEquals(
            extractDiagnosticsCaseJson(
                readProjectFile("reports/font/fixtures/expected/color/colrv1-paint-graph.json"),
                "colrv1-var-linear-gradient-outline-fallback",
            ).trim(),
            normalizeEmbeddedJson(diagnosticsJson(decision.diagnostics, indent = "      ")).trim(),
        )
    }

    @Test
    fun refusesCOLRV1GradientStopBudgetOverflowAndFallsBackToOutlineWhenAllowed() {
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440415"))
        val table = COLRV1Table(
            baseGlyphPaintRecords = listOf(
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 250,
                    paint = COLRV1Paint.Glyph(
                        glyphId = 251,
                        paint = COLRV1Paint.LinearGradient(
                            colorLine = plannerBudgetOverflowGradientColorLine(),
                            x0 = 1,
                            y0 = 1,
                            x1 = 7,
                            y1 = 5,
                            x2 = 2,
                            y2 = 3,
                        ),
                    ),
                ),
            ),
        )
        val planner = COLRV1ColorGlyphPlanner(
            colr = table,
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            glyphBounds = mapOf(
                251 to ColorGlyphBounds(xMin = 0, yMin = -2, xMax = 8, yMax = 8),
            ),
            maxGradientStopCount = 2,
        )

        val decision = planner.plan(
            glyphId = 250,
            typefaceId = typefaceId,
            strikeKey = strikeKey(typefaceId = typefaceId, paletteIdentity = "brand-gradient"),
            paletteSelection = CPALPaletteSelection(index = 1),
            allowMonochromeFallback = true,
            outlineFallback = OutlineGlyphRepresentation(glyphId = 250, pathCommands = listOf("M 0 0", "L 4 6")),
        )

        assertNull(decision.plan)
        assertEquals("outline", decision.selectedRoute?.route)
        assertEquals(ColorGlyphDiagnosticCodes.COLRV1BudgetExceeded, decision.diagnostics.single().code)
        assertTrue(decision.diagnostics.single().detail.contains("limitName=gradientStops"))
        assertTrue(decision.diagnostics.single().detail.contains("observed=3"))
        assertEquals(
            extractDiagnosticsCaseJson(
                readProjectFile("reports/font/fixtures/expected/color/color-glyph-plan.json"),
                "colrv1-linear-gradient-stop-budget-outline-fallback",
            ).trim(),
            normalizeEmbeddedJson(diagnosticsJson(decision.diagnostics, indent = "      ")).trim(),
        )
        assertEquals(
            extractDiagnosticsCaseJson(
                readProjectFile("reports/font/fixtures/expected/color/colrv1-paint-graph.json"),
                "colrv1-linear-gradient-stop-budget-outline-fallback",
            ).trim(),
            normalizeEmbeddedJson(diagnosticsJson(decision.diagnostics, indent = "      ")).trim(),
        )
    }

    @Test
    fun refusesCOLRV1MalformedGradientCoordinatesAndFallsBackToOutlineWhenAllowed() {
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440416"))
        val table = COLRV1Table(
            baseGlyphPaintRecords = listOf(
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 260,
                    paint = COLRV1Paint.Glyph(
                        glyphId = 261,
                        paint = COLRV1Paint.LinearGradient(
                            colorLine = plannerLinearGradientColorLine(),
                            x0 = 4,
                            y0 = 4,
                            x1 = 4,
                            y1 = 4,
                            x2 = 9,
                            y2 = 9,
                        ),
                    ),
                ),
            ),
        )
        val planner = COLRV1ColorGlyphPlanner(
            colr = table,
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            glyphBounds = mapOf(
                261 to ColorGlyphBounds(xMin = 1, yMin = -1, xMax = 10, yMax = 10),
            ),
        )

        val decision = planner.plan(
            glyphId = 260,
            typefaceId = typefaceId,
            strikeKey = strikeKey(typefaceId = typefaceId, paletteIdentity = "brand-gradient"),
            paletteSelection = CPALPaletteSelection(index = 1),
            allowMonochromeFallback = true,
            outlineFallback = OutlineGlyphRepresentation(glyphId = 260, pathCommands = listOf("M 0 0", "L 6 6")),
        )

        assertNull(decision.plan)
        assertEquals("outline", decision.selectedRoute?.route)
        assertEquals(ColorGlyphDiagnosticCodes.COLRMalformed, decision.diagnostics.single().code)
        assertTrue(decision.diagnostics.single().detail.contains("reason=malformed-gradient-coordinates"))
        assertTrue(decision.diagnostics.single().detail.contains("paintKind=colrv1-paint-linear-gradient"))
        assertEquals(
            extractDiagnosticsCaseJson(
                readProjectFile("reports/font/fixtures/expected/color/color-glyph-plan.json"),
                "colrv1-linear-gradient-malformed-coordinates-outline-fallback",
            ).trim(),
            normalizeEmbeddedJson(diagnosticsJson(decision.diagnostics, indent = "      ")).trim(),
        )
        assertEquals(
            extractDiagnosticsCaseJson(
                readProjectFile("reports/font/fixtures/expected/color/colrv1-paint-graph.json"),
                "colrv1-linear-gradient-malformed-coordinates-outline-fallback",
            ).trim(),
            normalizeEmbeddedJson(diagnosticsJson(decision.diagnostics, indent = "      ")).trim(),
        )
    }

    @Test
    fun buildsCOLRV1TransformCompositeClipPlansAndDeterministicPaintGraphDumps() {
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440417"))
        val table = COLRV1Table(
            baseGlyphPaintRecords = listOf(
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 270,
                    paint = COLRV1Paint.Translate(
                        paint = COLRV1Paint.Glyph(
                            glyphId = 271,
                            paint = COLRV1Paint.Solid(paletteIndex = 1, alpha = 0.75f),
                        ),
                        dx = 3,
                        dy = -4,
                    ),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 272,
                    paint = COLRV1Paint.Transform(
                        paint = COLRV1Paint.Glyph(
                            glyphId = 273,
                            paint = COLRV1Paint.Solid(paletteIndex = 0, alpha = 1f),
                        ),
                        xx = 1.25f,
                        yx = 0.5f,
                        xy = -0.25f,
                        yy = 1.5f,
                        dx = 2f,
                        dy = -1f,
                    ),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 274,
                    paint = COLRV1Paint.Transform(
                        paint = COLRV1Paint.Glyph(
                            glyphId = 275,
                            paint = COLRV1Paint.Solid(paletteIndex = 1, alpha = 1f),
                        ),
                        xx = 2f,
                        yx = 0f,
                        xy = 0f,
                        yy = 1.5f,
                        dx = 0f,
                        dy = 0f,
                    ),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 276,
                    paint = COLRV1Paint.Transform(
                        paint = COLRV1Paint.Glyph(
                            glyphId = 277,
                            paint = COLRV1Paint.Solid(paletteIndex = 0, alpha = 1f),
                        ),
                        xx = 0f,
                        yx = 1f,
                        xy = -1f,
                        yy = 0f,
                        dx = 0f,
                        dy = 0f,
                    ),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 278,
                    paint = COLRV1Paint.Transform(
                        paint = COLRV1Paint.Glyph(
                            glyphId = 279,
                            paint = COLRV1Paint.Solid(paletteIndex = 1, alpha = 1f),
                        ),
                        xx = 1f,
                        yx = 0f,
                        xy = 0.5f,
                        yy = 1f,
                        dx = 0f,
                        dy = 0f,
                    ),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 280,
                    paint = COLRV1Paint.Composite(
                        source = COLRV1Paint.Transform(
                            paint = COLRV1Paint.Glyph(
                                glyphId = 281,
                                paint = COLRV1Paint.Solid(paletteIndex = 0, alpha = 1f),
                            ),
                            xx = 1.5f,
                            yx = 0f,
                            xy = 0f,
                            yy = 1.5f,
                            dx = 1f,
                            dy = 0f,
                        ),
                        mode = COLRV1CompositeMode.MULTIPLY,
                        backdrop = COLRV1Paint.Translate(
                            paint = COLRV1Paint.Glyph(
                                glyphId = 282,
                                paint = COLRV1Paint.Solid(paletteIndex = 1, alpha = 0.5f),
                            ),
                            dx = -2,
                            dy = 1,
                        ),
                    ),
                ),
            ),
            clipRanges = listOf(
                COLRV1ClipRange(
                    startGlyphId = 280,
                    endGlyphId = 280,
                    box = COLRV1ClipBox(xMin = 2, yMin = 1, xMax = 6, yMax = 5),
                ),
            ),
        )
        val planner = COLRV1ColorGlyphPlanner(
            colr = table,
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            glyphBounds = mapOf(
                271 to ColorGlyphBounds(xMin = 1, yMin = 2, xMax = 5, yMax = 8),
                273 to ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 4, yMax = 6),
                275 to ColorGlyphBounds(xMin = 1, yMin = -2, xMax = 5, yMax = 4),
                277 to ColorGlyphBounds(xMin = 2, yMin = 1, xMax = 6, yMax = 5),
                279 to ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 4, yMax = 4),
                281 to ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 4, yMax = 4),
                282 to ColorGlyphBounds(xMin = 3, yMin = -1, xMax = 8, yMax = 5),
            ),
        )
        val strikeKey = strikeKey(typefaceId = typefaceId, paletteIdentity = "brand-transform-composite")
        val graphFixture = readProjectFile("reports/font/fixtures/expected/color/colrv1-paint-graph.json")
        val planFixture = readProjectFile("reports/font/fixtures/expected/color/color-glyph-plan.json")

        val translateDecision = planner.plan(
            glyphId = 270,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 1),
        )
        val translatePlan = assertNotNull(translateDecision.plan)
        val translateGraph = assertNotNull(translatePlan.paintGraph)
        val translateTransform = assertNotNull(translateGraph.nodes[0].transform)
        assertEquals("transform-composite-clip", translateGraph.supportedOperationGroup)
        assertEquals("colrv1-paint-translate", translateGraph.nodes[0].kind)
        assertEquals("translate", translateTransform.transformKind)
        assertEquals(ColorGlyphBounds(xMin = 4, yMin = -2, xMax = 8, yMax = 4), translateGraph.bounds)
        assertEquals(
            extractGraphCaseJson(graphFixture, "colrv1-translate").trim(),
            translateGraph.toCanonicalJson().trim(),
        )
        assertEquals(
            extractPlanCaseJson(planFixture, "colrv1-translate").trim(),
            translatePlan.toCanonicalJson().trim(),
        )

        val transformDecision = planner.plan(
            glyphId = 272,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 1),
        )
        val transformPlan = assertNotNull(transformDecision.plan)
        val transformGraph = assertNotNull(transformPlan.paintGraph)
        val transformEvidence = assertNotNull(transformGraph.nodes[0].transform)
        assertEquals("colrv1-paint-transform", transformGraph.nodes[0].kind)
        assertEquals("transform", transformEvidence.transformKind)
        assertEquals(ColorGlyphBounds(xMin = 0, yMin = -1, xMax = 7, yMax = 10), transformGraph.bounds)
        assertEquals(
            extractGraphCaseJson(graphFixture, "colrv1-transform").trim(),
            transformGraph.toCanonicalJson().trim(),
        )
        assertEquals(
            extractPlanCaseJson(planFixture, "colrv1-transform").trim(),
            transformPlan.toCanonicalJson().trim(),
        )

        val scaleDecision = planner.plan(
            glyphId = 274,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 1),
        )
        val scalePlan = assertNotNull(scaleDecision.plan)
        val scaleGraph = assertNotNull(scalePlan.paintGraph)
        val scaleTransform = assertNotNull(scaleGraph.nodes[0].transform)
        assertEquals("colrv1-paint-scale", scaleGraph.nodes[0].kind)
        assertEquals("scale", scaleTransform.transformKind)
        assertEquals(ColorGlyphBounds(xMin = 2, yMin = -3, xMax = 10, yMax = 6), scaleGraph.bounds)
        assertEquals(
            extractGraphCaseJson(graphFixture, "colrv1-scale").trim(),
            scaleGraph.toCanonicalJson().trim(),
        )
        assertEquals(
            extractPlanCaseJson(planFixture, "colrv1-scale").trim(),
            scalePlan.toCanonicalJson().trim(),
        )

        val rotateDecision = planner.plan(
            glyphId = 276,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 1),
        )
        val rotatePlan = assertNotNull(rotateDecision.plan)
        val rotateGraph = assertNotNull(rotatePlan.paintGraph)
        val rotateTransform = assertNotNull(rotateGraph.nodes[0].transform)
        assertEquals("colrv1-paint-rotate", rotateGraph.nodes[0].kind)
        assertEquals("rotate", rotateTransform.transformKind)
        assertEquals(ColorGlyphBounds(xMin = -5, yMin = 2, xMax = -1, yMax = 6), rotateGraph.bounds)
        assertEquals(
            extractGraphCaseJson(graphFixture, "colrv1-rotate").trim(),
            rotateGraph.toCanonicalJson().trim(),
        )
        assertEquals(
            extractPlanCaseJson(planFixture, "colrv1-rotate").trim(),
            rotatePlan.toCanonicalJson().trim(),
        )

        val skewDecision = planner.plan(
            glyphId = 278,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 1),
        )
        val skewPlan = assertNotNull(skewDecision.plan)
        val skewGraph = assertNotNull(skewPlan.paintGraph)
        val skewTransform = assertNotNull(skewGraph.nodes[0].transform)
        assertEquals("colrv1-paint-skew", skewGraph.nodes[0].kind)
        assertEquals("skew", skewTransform.transformKind)
        assertEquals(ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 6, yMax = 4), skewGraph.bounds)
        assertEquals(
            extractGraphCaseJson(graphFixture, "colrv1-skew").trim(),
            skewGraph.toCanonicalJson().trim(),
        )
        assertEquals(
            extractPlanCaseJson(planFixture, "colrv1-skew").trim(),
            skewPlan.toCanonicalJson().trim(),
        )

        val compositeDecision = planner.plan(
            glyphId = 280,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 1),
        )
        val compositePlan = assertNotNull(compositeDecision.plan)
        val compositeGraph = assertNotNull(compositePlan.paintGraph)
        val clipEvidence = assertNotNull(compositeGraph.nodes[0].clip)
        val compositeEvidence = assertNotNull(compositeGraph.nodes[1].composite)
        assertEquals("colrv1-paint-clip-box", compositeGraph.nodes[0].kind)
        assertEquals("colrv1-paint-composite-multiply", compositeGraph.nodes[1].kind)
        assertEquals(ColorGlyphBounds(xMin = 2, yMin = 1, xMax = 6, yMax = 5), compositeGraph.bounds)
        assertEquals(ColorGlyphBounds(xMin = 2, yMin = 1, xMax = 6, yMax = 5), clipEvidence.clipBounds)
        assertEquals("multiply", compositeEvidence.mode)
        assertEquals("shader-destination-read", compositeEvidence.destinationReadClass)
        assertTrue(compositeEvidence.requiresLayerIsolation)
        val compositePlanDump = assertNotNull(compositeGraph.toCompositePlan()).toCanonicalJson()

        val compositePlanFixture = readProjectFile("reports/font/fixtures/expected/color/color-glyph-composite-plan.json")

        assertEquals(
            extractGraphCaseJson(graphFixture, "colrv1-composite-clip").trim(),
            compositeGraph.toCanonicalJson().trim(),
        )
        assertEquals(
            extractPlanCaseJson(planFixture, "colrv1-composite-clip").trim(),
            compositePlan.toCanonicalJson().trim(),
        )
        assertEquals(
            extractCompositePlanCaseJson(compositePlanFixture, "colrv1-composite-clip").trim(),
            compositePlanDump.trim(),
        )

        listOf(
            translateGraph.toCanonicalJson(),
            translatePlan.toCanonicalJson(),
            transformGraph.toCanonicalJson(),
            transformPlan.toCanonicalJson(),
            scaleGraph.toCanonicalJson(),
            scalePlan.toCanonicalJson(),
            rotateGraph.toCanonicalJson(),
            rotatePlan.toCanonicalJson(),
            skewGraph.toCanonicalJson(),
            skewPlan.toCanonicalJson(),
            compositeGraph.toCanonicalJson(),
            compositePlan.toCanonicalJson(),
            compositePlanDump,
        ).forEach(::assertEvidenceDumpClean)
    }

    @Test
    fun refusesCOLRV1SingularTransformUnsupportedCompositeModeAndClipBudgetOverflowWhenFallbackAllowed() {
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440418"))
        val graphFixture = readProjectFile("reports/font/fixtures/expected/color/colrv1-paint-graph.json")
        val planFixture = readProjectFile("reports/font/fixtures/expected/color/color-glyph-plan.json")

        val singularPlanner = COLRV1ColorGlyphPlanner(
            colr = COLRV1Table(
                baseGlyphPaintRecords = listOf(
                    COLRV1BaseGlyphPaintRecord(
                        glyphId = 290,
                        paint = COLRV1Paint.Transform(
                            paint = COLRV1Paint.Glyph(
                                glyphId = 291,
                                paint = COLRV1Paint.Solid(paletteIndex = 1, alpha = 1f),
                            ),
                            xx = 1f,
                            yx = 0f,
                            xy = 0f,
                            yy = 0f,
                            dx = 0f,
                            dy = 0f,
                        ),
                    ),
                ),
            ),
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            glyphBounds = mapOf(291 to ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 4, yMax = 4)),
        )
        val singularDecision = singularPlanner.plan(
            glyphId = 290,
            typefaceId = typefaceId,
            strikeKey = strikeKey(typefaceId = typefaceId, paletteIdentity = "brand-transform-composite"),
            paletteSelection = CPALPaletteSelection(index = 1),
            allowMonochromeFallback = true,
            outlineFallback = OutlineGlyphRepresentation(glyphId = 290, pathCommands = listOf("M 0 0", "L 4 4")),
        )
        assertNull(singularDecision.plan)
        assertEquals("outline", singularDecision.selectedRoute?.route)
        assertEquals(ColorGlyphDiagnosticCodes.COLRMalformed, singularDecision.diagnostics.single().code)
        assertTrue(singularDecision.diagnostics.single().detail.contains("reason=singular-transform"))
        val singularDiagnosticsJson = normalizeEmbeddedJson(diagnosticsJson(singularDecision.diagnostics, indent = "      "))
        assertEquals(
            extractDiagnosticsCaseJson(planFixture, "colrv1-singular-transform-outline-fallback").trim(),
            singularDiagnosticsJson.trim(),
        )
        assertEquals(
            extractDiagnosticsCaseJson(graphFixture, "colrv1-singular-transform-outline-fallback").trim(),
            singularDiagnosticsJson.trim(),
        )

        val compositePlanner = COLRV1ColorGlyphPlanner(
            colr = COLRV1Table(
                baseGlyphPaintRecords = listOf(
                    COLRV1BaseGlyphPaintRecord(
                        glyphId = 292,
                        paint = COLRV1Paint.Composite(
                            source = COLRV1Paint.Glyph(
                                glyphId = 293,
                                paint = COLRV1Paint.Solid(paletteIndex = 0, alpha = 1f),
                            ),
                            mode = COLRV1CompositeMode.HUE,
                            backdrop = COLRV1Paint.Glyph(
                                glyphId = 294,
                                paint = COLRV1Paint.Solid(paletteIndex = 1, alpha = 1f),
                            ),
                        ),
                    ),
                ),
            ),
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            glyphBounds = mapOf(
                293 to ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 4, yMax = 4),
                294 to ColorGlyphBounds(xMin = 1, yMin = 1, xMax = 5, yMax = 5),
            ),
        )
        val unsupportedCompositeDecision = compositePlanner.plan(
            glyphId = 292,
            typefaceId = typefaceId,
            strikeKey = strikeKey(typefaceId = typefaceId, paletteIdentity = "brand-transform-composite"),
            paletteSelection = CPALPaletteSelection(index = 1),
            allowMonochromeFallback = true,
            outlineFallback = OutlineGlyphRepresentation(glyphId = 292, pathCommands = listOf("M 0 0", "L 5 5")),
        )
        assertNull(unsupportedCompositeDecision.plan)
        assertEquals("outline", unsupportedCompositeDecision.selectedRoute?.route)
        assertEquals(ColorGlyphDiagnosticCodes.COLRV1PaintUnsupported, unsupportedCompositeDecision.diagnostics.single().code)
        assertTrue(unsupportedCompositeDecision.diagnostics.single().detail.contains("reason=composite-mode-unsupported"))
        assertTrue(unsupportedCompositeDecision.diagnostics.single().detail.contains("mode=hue"))
        val unsupportedCompositeDiagnosticsJson = normalizeEmbeddedJson(
            diagnosticsJson(unsupportedCompositeDecision.diagnostics, indent = "      "),
        )
        assertEquals(
            extractDiagnosticsCaseJson(planFixture, "colrv1-unsupported-composite-mode-outline-fallback").trim(),
            unsupportedCompositeDiagnosticsJson.trim(),
        )
        assertEquals(
            extractDiagnosticsCaseJson(graphFixture, "colrv1-unsupported-composite-mode-outline-fallback").trim(),
            unsupportedCompositeDiagnosticsJson.trim(),
        )

        val clipPlanner = COLRV1ColorGlyphPlanner(
            colr = COLRV1Table(
                baseGlyphPaintRecords = listOf(
                    COLRV1BaseGlyphPaintRecord(
                        glyphId = 295,
                        paint = COLRV1Paint.Glyph(
                            glyphId = 296,
                            paint = COLRV1Paint.Solid(paletteIndex = 1, alpha = 1f),
                        ),
                    ),
                ),
                clipRanges = listOf(
                    COLRV1ClipRange(
                        startGlyphId = 295,
                        endGlyphId = 295,
                        box = COLRV1ClipBox(xMin = 0, yMin = 0, xMax = 3, yMax = 3),
                    ),
                ),
            ),
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            glyphBounds = mapOf(296 to ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 4, yMax = 4)),
            maxClipBoxCount = 0,
        )
        val clipBudgetDecision = clipPlanner.plan(
            glyphId = 295,
            typefaceId = typefaceId,
            strikeKey = strikeKey(typefaceId = typefaceId, paletteIdentity = "brand-transform-composite"),
            paletteSelection = CPALPaletteSelection(index = 1),
            allowMonochromeFallback = true,
            outlineFallback = OutlineGlyphRepresentation(glyphId = 295, pathCommands = listOf("M 0 0", "L 3 3")),
        )
        assertNull(clipBudgetDecision.plan)
        assertEquals("outline", clipBudgetDecision.selectedRoute?.route)
        assertEquals(ColorGlyphDiagnosticCodes.COLRV1BudgetExceeded, clipBudgetDecision.diagnostics.single().code)
        assertTrue(clipBudgetDecision.diagnostics.single().detail.contains("limitName=clipBoxes"))
        assertTrue(clipBudgetDecision.diagnostics.single().detail.contains("observed=1"))
        val clipBudgetDiagnosticsJson = normalizeEmbeddedJson(
            diagnosticsJson(clipBudgetDecision.diagnostics, indent = "      "),
        )
        assertEquals(
            extractDiagnosticsCaseJson(planFixture, "colrv1-clip-budget-outline-fallback").trim(),
            clipBudgetDiagnosticsJson.trim(),
        )
        assertEquals(
            extractDiagnosticsCaseJson(graphFixture, "colrv1-clip-budget-outline-fallback").trim(),
            clipBudgetDiagnosticsJson.trim(),
        )
    }

    @Test
    fun fallsBackToOutlineWhenCOLRPaletteResolutionFailsAndFallbackIsAllowed() {
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440402"))
        val planner = COLRV0ColorGlyphPlanner(
            colr = assertNotNull(COLRV0Parser.parse(syntheticColrV0())),
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            layerBounds = mapOf(
                11 to ColorGlyphBounds(xMin = 0, yMin = -2, xMax = 6, yMax = 8),
                12 to ColorGlyphBounds(xMin = 4, yMin = -1, xMax = 10, yMax = 9),
            ),
        )

        val decision = planner.plan(
            glyphId = 100,
            typefaceId = typefaceId,
            strikeKey = strikeKey(typefaceId = typefaceId),
            paletteSelection = CPALPaletteSelection(index = 9),
            allowMonochromeFallback = true,
            outlineFallback = OutlineGlyphRepresentation(glyphId = 100, pathCommands = listOf("M 0 0", "L 4 4")),
        )

        assertNull(decision.plan)
        assertEquals("outline", decision.selectedRoute?.route)
        assertTrue(decision.selectedRoute?.outline?.pathCommands?.isNotEmpty() == true)
        assertEquals(1, decision.diagnostics.size)
        assertEquals(ColorGlyphDiagnosticCodes.CPALMalformed, decision.diagnostics.single().code)
        assertTrue(decision.diagnostics.single().detail.contains("requestedPaletteIndex=9"))
        assertTrue(decision.diagnostics.single().message.contains("COLRv0 palette selection"))
    }

    @Test
    fun dumpHashesTrackMutableListContentsAfterFirstRead() {
        val dispatchDiagnostics = mutableListOf(
            ColorGlyphDiagnostic(
                glyphId = 70,
                route = "colr",
                message = "Selected colr route for glyph 70 at 16.0px.",
                code = ColorGlyphDiagnosticCodes.EmojiRouteSelected,
                detail = "selected=colr;glyphId=70;sizePx=16.0",
            ),
        )
        val dispatch = EmojiGlyphDispatch(
            glyphId = 70,
            route = "colr",
            diagnostics = dispatchDiagnostics,
        )
        val firstDispatchHash = dispatch.dumpSha256

        dispatchDiagnostics += ColorGlyphDiagnostic(
            glyphId = 71,
            route = "png",
            message = "Route png is unavailable for glyph 71 at 16.0px.",
            code = ColorGlyphDiagnosticCodes.ColorGlyphUnavailable,
            detail = "candidate=png;glyphId=71;sizePx=16.0",
        )
        val mutatedDispatchDump = dispatch.toCanonicalJson()

        assertTrue(mutatedDispatchDump.contains("\"glyphId\": 71"))
        assertEquals(canonicalDumpBodySha256(mutatedDispatchDump), dispatch.dumpSha256)
        assertTrue(dispatch.dumpSha256 != firstDispatchHash)

        val routes = mutableListOf(ColorGlyphRoute(glyphId = 72, route = "colr"))
        val planningResult = ColorGlyphPlanningResult(routes = routes)
        val firstPlanningHash = planningResult.dumpSha256

        routes += ColorGlyphRoute(glyphId = 73, route = "png")
        val mutatedPlanningDump = planningResult.toCanonicalJson()

        assertTrue(mutatedPlanningDump.contains("\"glyphId\": 73"))
        assertEquals(canonicalDumpBodySha256(mutatedPlanningDump), planningResult.dumpSha256)
        assertTrue(planningResult.dumpSha256 != firstPlanningHash)
    }

    @Test
    fun colorGlyphDiagnosticKeepsFourArgumentJvmConstructorCompatibility() {
        val constructor = ColorGlyphDiagnostic::class.java.getConstructor(
            Int::class.javaObjectType,
            String::class.java,
            String::class.java,
            String::class.java,
        )

        val diagnostic = constructor.newInstance(
            74,
            "colr",
            "Route colr is unavailable for glyph 74 at 16.0px.",
            "warning",
        ) as ColorGlyphDiagnostic

        assertEquals(74, diagnostic.glyphId)
        assertEquals("colr", diagnostic.route)
        assertEquals("Route colr is unavailable for glyph 74 at 16.0px.", diagnostic.message)
        assertEquals("warning", diagnostic.severity)
        assertEquals(ColorGlyphDiagnosticCodes.ColorGlyphUnavailable, diagnostic.code)
        assertEquals(diagnostic.message, diagnostic.detail)
    }

    @Test
    fun parsesBasicSVGGlyphViewBoxAndElementSummaries() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 64">
              <rect x="1" y="2" width="10" height="20" fill="#ff0000"/>
              <path d="M0 0 L10 10" stroke="#000000" fill="none"/>
              <metadata>ignored</metadata>
            </svg>
        """.trimIndent()

        val document = BasicSVGGlyphParser.parse(glyphId = 77, text = svg)

        assertEquals(77, document.glyphId)
        assertEquals(listOf(0f, 0f, 128f, 64f), document.viewBox)
        assertEquals(
            listOf(
                "rect{fill=#ff0000,height=20,width=10,x=1,y=2}",
                "path{d=M0 0 L10 10,fill=none,stroke=#000000}",
            ),
            document.elements,
        )
    }

    @Test
    fun ignoresBasicSVGGlyphTagsInsideCommentsAndCdata() {
        val svg = """
            <!-- <svg viewBox="0 0 1 1"><path d="M0 0"/></svg> -->
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
              <![CDATA[<circle cx="4" cy="4" r="2"/>]]>
              <rect width="8" height="8" fill="#00ff00"/>
              <!-- <path d="M0 0 L16 16"/> -->
            </svg>
        """.trimIndent()

        val document = BasicSVGGlyphParser.parse(glyphId = 79, text = svg)

        assertEquals(listOf(0f, 0f, 16f, 16f), document.viewBox)
        assertEquals(
            listOf("rect{fill=#00ff00,height=8,width=8}"),
            document.elements,
        )
    }

    @Test
    fun parsesBasicSVGGradientTransformAndClipFixture() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
              <defs>
                <linearGradient id="g" x1="0" y1="0" x2="16" y2="0" gradientUnits="userSpaceOnUse">
                  <stop offset="0" stop-color="#000000"/>
                  <stop offset="1" stop-color="#ffffff"/>
                </linearGradient>
                <clipPath id="c">
                  <rect x="1" y="1" width="14" height="14"/>
                </clipPath>
              </defs>
              <path d="M0 0 L16 0 L16 16 Z" fill="url(#g)" clip-path="url(#c)" transform="translate(2 3)"/>
            </svg>
        """.trimIndent()

        val document = BasicSVGGlyphParser.parse(glyphId = 83, text = svg)

        assertEquals(listOf(0f, 0f, 16f, 16f), document.viewBox)
        assertEquals(
            listOf(
                "defs{}",
                "lineargradient{gradientunits=userSpaceOnUse,id=g,x1=0,x2=16,y1=0,y2=0}",
                "stop{offset=0,stop-color=#000000}",
                "stop{offset=1,stop-color=#ffffff}",
                "clippath{id=c}",
                "rect{height=14,width=14,x=1,y=1}",
                "path{clip-path=url(#c),d=M0 0 L16 0 L16 16 Z,fill=url(#g),transform=translate(2 3)}",
            ),
            document.elements,
        )
    }

    @Test
    fun rejectsOversizedBasicSVGGlyphPayloadsBeforeParsing() {
        val oversizedSvg = "<svg viewBox=\"0 0 1 1\">" + " ".repeat(70_000) + "</svg>"

        val failure = assertFailsWith<IllegalArgumentException> {
            BasicSVGGlyphParser.parse(glyphId = 78, text = oversizedSvg)
        }

        assertTrue(failure.message.orEmpty().contains("exceeds"))
    }

    @Test
    fun buildsSVGExternalResourceRefusalDiagnostic() {
        val diagnostic = BasicSVGGlyphParser.externalResourceRefusedDiagnostic(
            glyphId = 80,
            elementName = "image",
            attributeName = "href",
            reference = "https://example.invalid/glyph.png",
        )

        assertEquals(80, diagnostic.glyphId)
        assertEquals("svg", diagnostic.route)
        assertEquals(ColorGlyphDiagnosticCodes.SVGExternalResourceRefused, diagnostic.code)
        assertEquals("warning", diagnostic.severity)
        assertTrue(diagnostic.detail.contains("elementName=image"))
        assertTrue(diagnostic.detail.contains("attributeName=href"))
        assertTrue(diagnostic.detail.contains("referenceSha256="))
        assertTrue(!diagnostic.detail.contains("example.invalid"))
        assertEquals(
            """
            {"glyphId": 80, "route": "svg", "code": "text.SVG.external-resource-refused", "detail": "${diagnostic.detail}", "severity": "warning", "message": "SVG glyph external resource refused for glyph 80: image href."}
            """.trimIndent(),
            diagnostic.toCanonicalJson(),
        )
    }

    @Test
    fun buildsSVGUnsupportedFeatureRefusalDiagnostic() {
        val diagnostic = BasicSVGGlyphParser.unsupportedFeatureDiagnostic(
            glyphId = 81,
            elementName = "foreignObject",
            featureName = "embedded-text-layout",
        )

        assertEquals(81, diagnostic.glyphId)
        assertEquals("svg", diagnostic.route)
        assertEquals(ColorGlyphDiagnosticCodes.SVGFeatureUnsupported, diagnostic.code)
        assertEquals("warning", diagnostic.severity)
        assertTrue(diagnostic.detail.contains("elementName=foreignObject"))
        assertTrue(diagnostic.detail.contains("featureName=embedded-text-layout"))
        assertEquals(
            """
            {"glyphId": 81, "route": "svg", "code": "text.SVG.feature-unsupported", "detail": "${diagnostic.detail}", "severity": "warning", "message": "SVG glyph feature embedded-text-layout is unsupported for glyph 81 in foreignObject."}
            """.trimIndent(),
            diagnostic.toCanonicalJson(),
        )
    }

    @Test
    fun buildsSVGUseRecursionRefusalDiagnostic() {
        val diagnostic = BasicSVGGlyphParser.useRecursionRefusedDiagnostic(
            glyphId = 82,
            referenceId = "glyph-loop",
            depth = 9,
            maxDepth = 8,
        )

        assertEquals(82, diagnostic.glyphId)
        assertEquals("svg", diagnostic.route)
        assertEquals(ColorGlyphDiagnosticCodes.SVGBudgetExceeded, diagnostic.code)
        assertEquals("warning", diagnostic.severity)
        assertTrue(diagnostic.detail.contains("referenceId=glyph-loop"))
        assertTrue(diagnostic.detail.contains("depth=9"))
        assertTrue(diagnostic.detail.contains("maxDepth=8"))
        assertEquals(
            """
            {"glyphId": 82, "route": "svg", "code": "text.SVG.budget-exceeded", "detail": "${diagnostic.detail}", "severity": "warning", "message": "SVG glyph use recursion exceeded for glyph 82 at glyph-loop: depth 9, max 8."}
            """.trimIndent(),
            diagnostic.toCanonicalJson(),
        )
    }

    @Test
    fun buildsSVGDocumentMalformedDiagnostic() {
        val malformedSvg = "<svg viewBox=\"0 0 16 16\"><path d=\"M0 0\""
        val failure = assertFailsWith<IllegalArgumentException> {
            BasicSVGGlyphParser.parse(glyphId = 84, text = malformedSvg)
        }
        val diagnostic = BasicSVGGlyphParser.documentMalformedDiagnostic(
            glyphId = 84,
            sourceText = malformedSvg,
            failure = failure,
        )

        assertEquals(84, diagnostic.glyphId)
        assertEquals("svg", diagnostic.route)
        assertEquals(ColorGlyphDiagnosticCodes.SVGDocumentMalformed, diagnostic.code)
        assertEquals("warning", diagnostic.severity)
        assertTrue(diagnostic.detail.contains("glyphId=84"))
        assertTrue(diagnostic.detail.contains("sourceDocumentSha256="))
        assertTrue(diagnostic.detail.contains("failureClass=IllegalArgumentException"))
        assertTrue(diagnostic.detail.contains("failureMessage=SVG glyph payload contains an unterminated start tag."))
        assertEquals(
            """
            {"glyphId": 84, "route": "svg", "code": "text.SVG.document-malformed", "detail": "${diagnostic.detail}", "severity": "warning", "message": "SVG glyph document is malformed for glyph 84: SVG glyph payload contains an unterminated start tag."}
            """.trimIndent(),
            diagnostic.toCanonicalJson(),
        )
    }

    @Test
    fun svgGlyphPlanBundleCapturesSupportedPrimitivesAndRefusalsDeterministically() {
        val pathShapeSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 64">
              <rect x="1" y="2" width="10" height="20" fill="#ff0000"/>
              <path d="M0 0 L10 10" stroke="#000000" fill="none"/>
            </svg>
        """.trimIndent()
        val pathShapePlan = SVGGlyphPlan.fromDocument(
            sourceText = pathShapeSvg,
            document = BasicSVGGlyphParser.parse(glyphId = 177, text = pathShapeSvg),
        )

        val gradientTransformClipSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
              <defs>
                <linearGradient id="g" x1="0" y1="0" x2="16" y2="0" gradientUnits="userSpaceOnUse">
                  <stop offset="0" stop-color="#000000"/>
                  <stop offset="1" stop-color="#ffffff"/>
                </linearGradient>
                <clipPath id="c">
                  <rect x="1" y="1" width="14" height="14"/>
                </clipPath>
              </defs>
              <g opacity="0.75" transform="translate(2 3)">
                <path d="M0 0 L16 0 L16 16 Z" fill="url(#g)" clip-path="url(#c)"/>
              </g>
            </svg>
        """.trimIndent()
        val gradientTransformClipPlan = SVGGlyphPlan.fromDocument(
            sourceText = gradientTransformClipSvg,
            document = BasicSVGGlyphParser.parse(glyphId = 178, text = gradientTransformClipSvg),
        )

        val symbolUseSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <defs>
                <radialGradient id="rg" cx="12" cy="12" r="10">
                  <stop offset="0" stop-color="#ffee00"/>
                  <stop offset="1" stop-color="#ff6600"/>
                </radialGradient>
                <symbol id="sun" viewBox="0 0 24 24">
                  <circle cx="12" cy="12" r="8" fill="url(#rg)"/>
                </symbol>
              </defs>
              <use href="#sun" opacity="0.5" transform="translate(1 2)"/>
            </svg>
        """.trimIndent()
        val symbolUsePlan = SVGGlyphPlan.fromDocument(
            sourceText = symbolUseSvg,
            document = BasicSVGGlyphParser.parse(glyphId = 179, text = symbolUseSvg),
        )

        val externalResource = BasicSVGGlyphParser.externalResourceRefusedDiagnostic(
            glyphId = 180,
            elementName = "image",
            attributeName = "href",
            reference = "https://example.invalid/glyph.png",
        )
        val unsupportedFeature = BasicSVGGlyphParser.unsupportedFeatureDiagnostic(
            glyphId = 181,
            elementName = "foreignObject",
            featureName = "embedded-text-layout",
        )
        val pathBudget = BasicSVGGlyphParser.budgetExceededDiagnostic(
            glyphId = 182,
            budgetName = "pathCommands",
            observed = 257,
            max = 256,
        )
        val gradientBudget = BasicSVGGlyphParser.budgetExceededDiagnostic(
            glyphId = 183,
            budgetName = "gradientStops",
            observed = 65,
            max = 64,
        )
        val useRecursion = BasicSVGGlyphParser.useRecursionRefusedDiagnostic(
            glyphId = 184,
            referenceId = "glyph-loop",
            depth = 9,
            maxDepth = 8,
        )

        val expected = svgGlyphPlanBundleJson(
            cases = listOf(
                SVGGlyphPlanFixtureCase(
                    caseId = "path-and-basic-shape",
                    expectedGpuHandoffArtifactType = "SVGGlyphPlan",
                    planJson = pathShapePlan.toCanonicalJson(),
                    diagnostics = emptyList(),
                ),
                SVGGlyphPlanFixtureCase(
                    caseId = "gradient-transform-clip",
                    expectedGpuHandoffArtifactType = "SVGGlyphPlan",
                    planJson = gradientTransformClipPlan.toCanonicalJson(),
                    diagnostics = emptyList(),
                ),
                SVGGlyphPlanFixtureCase(
                    caseId = "defs-symbol-use-radial-gradient",
                    expectedGpuHandoffArtifactType = "SVGGlyphPlan",
                    planJson = symbolUsePlan.toCanonicalJson(),
                    diagnostics = emptyList(),
                ),
                SVGGlyphPlanFixtureCase(
                    caseId = "external-resource-refusal",
                    expectedGpuHandoffArtifactType = "refusal",
                    planJson = null,
                    diagnostics = listOf(externalResource),
                ),
                SVGGlyphPlanFixtureCase(
                    caseId = "unsupported-feature-refusal",
                    expectedGpuHandoffArtifactType = "refusal",
                    planJson = null,
                    diagnostics = listOf(unsupportedFeature),
                ),
                SVGGlyphPlanFixtureCase(
                    caseId = "path-command-budget-refusal",
                    expectedGpuHandoffArtifactType = "refusal",
                    planJson = null,
                    diagnostics = listOf(pathBudget),
                ),
                SVGGlyphPlanFixtureCase(
                    caseId = "gradient-stop-budget-refusal",
                    expectedGpuHandoffArtifactType = "refusal",
                    planJson = null,
                    diagnostics = listOf(gradientBudget),
                ),
                SVGGlyphPlanFixtureCase(
                    caseId = "use-recursion-budget-refusal",
                    expectedGpuHandoffArtifactType = "refusal",
                    planJson = null,
                    diagnostics = listOf(useRecursion),
                ),
            ),
        )

        assertEquals(
            expected,
            readProjectFile("reports/font/fixtures/expected/color/svg-glyph-plan.json"),
        )
    }

    @Test
    fun svgGlyphFixtureManifestCapturesBoundsRefusalsAndProvenanceDeterministically() {
        val pathShapeSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 64">
              <rect x="1" y="2" width="10" height="20" fill="#ff0000"/>
              <path d="M0 0 L10 10" stroke="#000000" fill="none"/>
            </svg>
        """.trimIndent()
        val pathShapePlan = SVGGlyphPlan.fromDocument(
            sourceText = pathShapeSvg,
            document = BasicSVGGlyphParser.parse(glyphId = 177, text = pathShapeSvg),
        )

        val gradientTransformClipSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
              <defs>
                <linearGradient id="g" x1="0" y1="0" x2="16" y2="0" gradientUnits="userSpaceOnUse">
                  <stop offset="0" stop-color="#000000"/>
                  <stop offset="1" stop-color="#ffffff"/>
                </linearGradient>
                <clipPath id="c">
                  <rect x="1" y="1" width="14" height="14"/>
                </clipPath>
              </defs>
              <g opacity="0.75" transform="translate(2 3)">
                <path d="M0 0 L16 0 L16 16 Z" fill="url(#g)" clip-path="url(#c)"/>
              </g>
            </svg>
        """.trimIndent()
        val gradientTransformClipPlan = SVGGlyphPlan.fromDocument(
            sourceText = gradientTransformClipSvg,
            document = BasicSVGGlyphParser.parse(glyphId = 178, text = gradientTransformClipSvg),
        )

        val symbolUseSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <defs>
                <radialGradient id="rg" cx="12" cy="12" r="10">
                  <stop offset="0" stop-color="#ffee00"/>
                  <stop offset="1" stop-color="#ff6600"/>
                </radialGradient>
                <symbol id="sun" viewBox="0 0 24 24">
                  <circle cx="12" cy="12" r="8" fill="url(#rg)"/>
                </symbol>
              </defs>
              <use href="#sun" opacity="0.5" transform="translate(1 2)"/>
            </svg>
        """.trimIndent()
        val symbolUsePlan = SVGGlyphPlan.fromDocument(
            sourceText = symbolUseSvg,
            document = BasicSVGGlyphParser.parse(glyphId = 179, text = symbolUseSvg),
        )

        val malformedSvg = "<svg viewBox=\"0 0 16 16\"><path d=\"M0 0\""
        val malformedFailure = assertFailsWith<IllegalArgumentException> {
            BasicSVGGlyphParser.parse(glyphId = 190, text = malformedSvg)
        }

        val expected = svgGlyphFixtureManifestJson(
            cases = listOf(
                svgFixtureManifestCase(
                    fixtureId = "svg-glyphs-svg-static-path",
                    focus = "static-path",
                    plan = pathShapePlan,
                    sourceText = pathShapeSvg,
                ),
                svgFixtureManifestCase(
                    fixtureId = "svg-glyphs-svg-gradient-transform-clip",
                    focus = "gradient-transform-clip",
                    plan = gradientTransformClipPlan,
                    sourceText = gradientTransformClipSvg,
                ),
                svgFixtureManifestCase(
                    fixtureId = "svg-glyphs-svg-defs-symbol-use-radial-gradient",
                    focus = "defs-symbol-use-radial-gradient",
                    plan = symbolUsePlan,
                    sourceText = symbolUseSvg,
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-script-refusal",
                    glyphId = 185,
                    focus = "script",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><script>alert(1)</script></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.unsupportedFeatureDiagnostic(
                            glyphId = 185,
                            elementName = "script",
                            featureName = "script",
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-external-resource-refusal",
                    glyphId = 180,
                    focus = "external-resource",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><image href=\"https://example.invalid/glyph.png\"/></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.externalResourceRefusedDiagnostic(
                            glyphId = 180,
                            elementName = "image",
                            attributeName = "href",
                            reference = "https://example.invalid/glyph.png",
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-network-reference-refusal",
                    glyphId = 186,
                    focus = "network-reference",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><use href=\"https://example.invalid/glyph.svg#icon\"/></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.externalResourceRefusedDiagnostic(
                            glyphId = 186,
                            elementName = "use",
                            attributeName = "href",
                            reference = "https://example.invalid/glyph.svg#icon",
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-animation-refusal",
                    glyphId = 187,
                    focus = "animation",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><animate attributeName=\"opacity\"/></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.unsupportedFeatureDiagnostic(
                            glyphId = 187,
                            elementName = "animate",
                            featureName = "animation",
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-filter-refusal",
                    glyphId = 188,
                    focus = "filter",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><filter id=\"f\"/></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.unsupportedFeatureDiagnostic(
                            glyphId = 188,
                            elementName = "filter",
                            featureName = "filter",
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-foreign-object-refusal",
                    glyphId = 181,
                    focus = "foreignObject",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><foreignObject/></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.unsupportedFeatureDiagnostic(
                            glyphId = 181,
                            elementName = "foreignObject",
                            featureName = "embedded-text-layout",
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-embedded-text-refusal",
                    glyphId = 189,
                    focus = "embedded-text",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><text>Hello</text></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.unsupportedFeatureDiagnostic(
                            glyphId = 189,
                            elementName = "text",
                            featureName = "embedded-text-layout",
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-unsupported-css-selector-refusal",
                    glyphId = 191,
                    focus = "unsupported-css-selector",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><style>g:hover { fill: red; }</style></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.unsupportedFeatureDiagnostic(
                            glyphId = 191,
                            elementName = "style",
                            featureName = "unsupported-css-selector",
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-malformed-document-refusal",
                    glyphId = 190,
                    focus = "malformed-document",
                    sourceText = malformedSvg,
                    diagnostics = listOf(
                        BasicSVGGlyphParser.documentMalformedDiagnostic(
                            glyphId = 190,
                            sourceText = malformedSvg,
                            failure = malformedFailure,
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-malformed-path-data-refusal",
                    glyphId = 192,
                    focus = "malformed-path-data",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><path d=\"M0 0 L\"/></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.documentMalformedDiagnostic(
                            glyphId = 192,
                            sourceText = "<svg viewBox=\"0 0 16 16\"><path d=\"M0 0 L\"/></svg>",
                            failure = IllegalArgumentException("SVG glyph path data is malformed."),
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-path-command-budget-refusal",
                    glyphId = 182,
                    focus = "path-command-budget",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><path d=\"...\"/></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.budgetExceededDiagnostic(
                            glyphId = 182,
                            budgetName = "pathCommands",
                            observed = 257,
                            max = 256,
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-gradient-stop-budget-refusal",
                    glyphId = 183,
                    focus = "gradient-stop-budget",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><linearGradient id=\"g\">...</linearGradient></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.budgetExceededDiagnostic(
                            glyphId = 183,
                            budgetName = "gradientStops",
                            observed = 65,
                            max = 64,
                        ),
                    ),
                ),
                svgRefusalManifestCase(
                    fixtureId = "svg-glyphs-svg-use-recursion-refusal",
                    glyphId = 184,
                    focus = "use-recursion-budget",
                    sourceText = "<svg viewBox=\"0 0 16 16\"><use href=\"#glyph-loop\"/></svg>",
                    diagnostics = listOf(
                        BasicSVGGlyphParser.useRecursionRefusedDiagnostic(
                            glyphId = 184,
                            referenceId = "glyph-loop",
                            depth = 9,
                            maxDepth = 8,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            expected,
            readProjectFile("reports/font/fixtures/expected/color/svg-glyph-fixture-manifest.json"),
        )
    }

    @Test
    fun rejectsBasicPNGGlyphHeaderOnlyPayloadsWithoutRasterPixels() {
        val failure = assertFailsWith<IllegalArgumentException> {
            BasicPNGGlyphDecoder.decode(
                glyphId = 81,
                bytes = syntheticPngHeader(width = 19, height = 23),
            )
        }

        assertTrue(failure.message.orEmpty().contains("decode PNG glyph payload"))
    }

    @Test
    fun decodesBasicPNGGlyphRgbaPixels() {
        val image = BasicPNGGlyphDecoder.decode(
            glyphId = 83,
            bytes = syntheticRgbaPng(
                width = 2,
                height = 1,
                pixels = intArrayOf(
                    argb(alpha = 0x44, red = 0x11, green = 0x22, blue = 0x33),
                    argb(alpha = 0x88, red = 0x55, green = 0x66, blue = 0x77),
                ),
            ),
        )

        assertEquals(83, image.glyphId)
        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertEquals(
            listOf(
                argb(alpha = 0x44, red = 0x11, green = 0x22, blue = 0x33),
                argb(alpha = 0x88, red = 0x55, green = 0x66, blue = 0x77),
            ),
            image.pixels,
        )
    }

    @Test
    fun bitmapGlyphPlanDumpsPngStrikeAndPixelHashes() {
        val payload = syntheticRgbaPng(
            width = 2,
            height = 1,
            pixels = intArrayOf(
                argb(alpha = 0x44, red = 0x11, green = 0x22, blue = 0x33),
                argb(alpha = 0x88, red = 0x55, green = 0x66, blue = 0x77),
            ),
        )
        val image = BasicPNGGlyphDecoder.decode(glyphId = 85, bytes = payload)
        val plan = BitmapGlyphPlan.fromPNG(
            strike = BitmapStrikeSelection(
                glyphId = 85,
                width = 2,
                height = 1,
                format = "png",
                ppem = 16,
            ),
            requestedSizePx = 18f,
            tableFamily = "CBDT/CBLC",
            sourcePayload = payload,
            image = image,
        )

        assertEquals(85, plan.glyphId)
        assertEquals("CBDT/CBLC", plan.tableFamily)
        assertEquals("scale-to-requested-size", plan.scalingPolicy)
        assertEquals("premultiplied-argb", plan.alphaPolicy)
        assertEquals(64, plan.sourcePayloadSha256.length)
        assertEquals(64, plan.decodedPixelSha256.length)
        assertEquals(64, plan.dumpSha256.length)
        assertEvidenceDumpClean(plan.toCanonicalJson())
        assertEquals(
            """
            {
              "schema": "org.graphiks.kanvas.glyph.color.BitmapGlyphPlan.v1",
              "glyphId": 85,
              "tableFamily": "CBDT/CBLC",
              "requestedSizePx": 18,
              "selectedStrikePpem": 16,
              "sourceFormat": "png",
              "bounds": {"left": 0, "top": 0, "width": 2, "height": 1},
              "origin": {"x": 0, "y": 0},
              "scalingPolicy": "scale-to-requested-size",
              "alphaPolicy": "premultiplied-argb",
              "sourcePayloadSha256": "${plan.sourcePayloadSha256}",
              "decodedPixelSha256": "${plan.decodedPixelSha256}",
              "diagnostics": [],
              "dumpSha256": "${plan.dumpSha256}"
            }
            """.trimIndent() + "\n",
            plan.toCanonicalJson(),
        )
    }

    @Test
    fun bitmapGlyphPlanBuildsNonPngPayloadRefusalDiagnostic() {
        val diagnostic = BitmapGlyphPlan.unsupportedPayloadDiagnostic(
            glyphId = 86,
            tableFamily = "sbix",
            sourceFormat = "jpeg",
            sourcePayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00),
        )

        assertEquals(86, diagnostic.glyphId)
        assertEquals("bitmap", diagnostic.route)
        assertEquals(ColorGlyphDiagnosticCodes.BitmapPayloadFormatUnsupported, diagnostic.code)
        assertEquals("warning", diagnostic.severity)
        assertTrue(diagnostic.detail.contains("sourceFormat=jpeg"))
        assertTrue(diagnostic.detail.contains("sourcePayloadSha256="))
        assertEquals(
            """
            {"glyphId": 86, "route": "bitmap", "code": "text.bitmap.payload-format-unsupported", "detail": "${diagnostic.detail}", "severity": "warning", "message": "Bitmap glyph payload format jpeg is unsupported for glyph 86 from sbix."}
            """.trimIndent(),
            diagnostic.toCanonicalJson(),
        )
    }

    @Test
    fun bitmapGlyphPlanBuildsMalformedPngRefusalDiagnostic() {
        val payload = ByteArray(33)
        val failure = assertFailsWith<IllegalArgumentException> {
            BasicPNGGlyphDecoder.decode(glyphId = 87, bytes = payload)
        }

        val diagnostic = BitmapGlyphPlan.pngDecodeFailedDiagnostic(
            glyphId = 87,
            tableFamily = "CBDT/CBLC",
            sourcePayload = payload,
            failure = failure,
        )

        assertEquals(87, diagnostic.glyphId)
        assertEquals("bitmap", diagnostic.route)
        assertEquals(ColorGlyphDiagnosticCodes.PNGDecodeFailed, diagnostic.code)
        assertEquals("warning", diagnostic.severity)
        assertTrue(diagnostic.detail.contains("sourceFormat=png"))
        assertTrue(diagnostic.detail.contains("sourcePayloadSha256="))
        assertTrue(diagnostic.detail.contains("failureClass=IllegalArgumentException"))
        assertTrue(diagnostic.detail.contains("failureMessage=PNG glyph payload must start with the PNG signature."))
        assertEquals(
            """
            {"glyphId": 87, "route": "bitmap", "code": "text.bitmap.PNG-decode-failed", "detail": "${diagnostic.detail}", "severity": "warning", "message": "Bitmap glyph PNG decode failed for glyph 87 from CBDT/CBLC: PNG glyph payload must start with the PNG signature."}
            """.trimIndent(),
            diagnostic.toCanonicalJson(),
        )
    }

    @Test
    fun bitmapGlyphPlanBundleCapturesCbdtSbixAndBitmapRefusalsDeterministically() {
        val cbdtPayload = syntheticRgbaPng(
            width = 2,
            height = 1,
            pixels = intArrayOf(
                argb(alpha = 0x44, red = 0x11, green = 0x22, blue = 0x33),
                argb(alpha = 0x88, red = 0x55, green = 0x66, blue = 0x77),
            ),
        )
        val cbdtPlan = BitmapGlyphPlan.fromPNG(
            strike = BitmapStrikeSelection(
                glyphId = 185,
                width = 2,
                height = 1,
                format = "png",
                ppem = 16,
            ),
            requestedSizePx = 18f,
            tableFamily = "CBDT/CBLC",
            sourcePayload = cbdtPayload,
            image = assertNotNull(BasicPNGGlyphDecoder.decode(glyphId = 185, bytes = cbdtPayload)),
            originX = 0,
            originY = 0,
        )

        val sbixPayload = syntheticRgbaPng(
            width = 1,
            height = 2,
            pixels = intArrayOf(
                argb(alpha = 0xFF, red = 0x20, green = 0x40, blue = 0x60),
                argb(alpha = 0xAA, red = 0x90, green = 0x70, blue = 0x50),
            ),
        )
        val sbixPlan = BitmapGlyphPlan.fromPNG(
            strike = BitmapStrikeSelection(
                glyphId = 186,
                width = 1,
                height = 2,
                format = "png",
                ppem = 20,
            ),
            requestedSizePx = 20f,
            tableFamily = "sbix",
            sourcePayload = sbixPayload,
            image = assertNotNull(BasicPNGGlyphDecoder.decode(glyphId = 186, bytes = sbixPayload)),
            originX = -3,
            originY = 5,
        )

        val strikeUnavailable = BitmapGlyphPlan.strikeUnavailableDiagnostic(
            glyphId = 187,
            requestedSizePx = 18f,
            availableStrikes = listOf(
                BitmapStrikeSelection(glyphId = 187, width = 16, height = 16, format = "png", ppem = 16),
                BitmapStrikeSelection(glyphId = 187, width = 24, height = 24, format = "png", ppem = 24),
            ),
        )
        val malformedPayload = ByteArray(33)
        val malformedDiagnostic = BitmapGlyphPlan.pngDecodeFailedDiagnostic(
            glyphId = 188,
            tableFamily = "CBDT/CBLC",
            sourcePayload = malformedPayload,
            failure = assertFailsWith<IllegalArgumentException> {
                BasicPNGGlyphDecoder.decode(glyphId = 188, bytes = malformedPayload)
            },
        )
        val nonPngDiagnostic = BitmapGlyphPlan.unsupportedPayloadDiagnostic(
            glyphId = 189,
            tableFamily = "sbix",
            sourceFormat = "jpeg",
            sourcePayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00),
        )

        val expected = bitmapGlyphPlanBundleJson(
            cases = listOf(
                BitmapGlyphPlanFixtureCase(
                    caseId = "cbdt-cblc-png",
                    tableFamily = "CBDT/CBLC",
                    expectedGpuHandoffArtifactType = "BitmapGlyphPlan",
                    planJson = cbdtPlan.toCanonicalJson(),
                    diagnostics = emptyList(),
                ),
                BitmapGlyphPlanFixtureCase(
                    caseId = "sbix-png",
                    tableFamily = "sbix",
                    expectedGpuHandoffArtifactType = "BitmapGlyphPlan",
                    planJson = sbixPlan.toCanonicalJson(),
                    diagnostics = emptyList(),
                ),
                BitmapGlyphPlanFixtureCase(
                    caseId = "unavailable-strike-refusal",
                    tableFamily = "bitmap",
                    expectedGpuHandoffArtifactType = "refusal",
                    planJson = null,
                    diagnostics = listOf(strikeUnavailable),
                ),
                BitmapGlyphPlanFixtureCase(
                    caseId = "malformed-png-refusal",
                    tableFamily = "CBDT/CBLC",
                    expectedGpuHandoffArtifactType = "refusal",
                    planJson = null,
                    diagnostics = listOf(malformedDiagnostic),
                ),
                BitmapGlyphPlanFixtureCase(
                    caseId = "non-png-payload-refusal",
                    tableFamily = "sbix",
                    expectedGpuHandoffArtifactType = "refusal",
                    planJson = null,
                    diagnostics = listOf(nonPngDiagnostic),
                ),
            ),
        )

        val dump = readProjectFile("reports/font/fixtures/expected/color/bitmap-glyph-plan.json")
        assertEquals(expected.trim(), dump.trim())
        assertEvidenceDumpClean(dump)
    }

    @Test
    fun rejectsBasicPNGGlyphPayloadsWithInvalidCrc() {
        val png = syntheticRgbaPng(
            width = 1,
            height = 1,
            pixels = intArrayOf(argb(alpha = 0xFF, red = 0x10, green = 0x20, blue = 0x30)),
        )
        png[png.lastIndex - 4] = (png[png.lastIndex - 4].toInt() xor 0x01).toByte()

        val failure = assertFailsWith<IllegalArgumentException> {
            BasicPNGGlyphDecoder.decode(glyphId = 84, bytes = png)
        }

        assertTrue(failure.message.orEmpty().contains("decode PNG glyph payload"))
    }

    @Test
    fun rejectsBasicPNGGlyphPayloadsWithoutPngSignature() {
        val failure = assertFailsWith<IllegalArgumentException> {
            BasicPNGGlyphDecoder.decode(glyphId = 82, bytes = ByteArray(33))
        }

        assertTrue(failure.message.orEmpty().contains("PNG signature"))
    }

    @Test
    fun selectsNearestStaticBitmapStrikeFromImmutableSnapshot() {
        val sixteenPpem = BitmapStrikeSelection(
            glyphId = 90,
            width = 16,
            height = 16,
            format = "png",
            ppem = 16,
        )
        val twentyPpem = BitmapStrikeSelection(
            glyphId = 90,
            width = 20,
            height = 20,
            format = "png",
            ppem = 20,
        )
        val twentyFourPpem = BitmapStrikeSelection(
            glyphId = 90,
            width = 24,
            height = 24,
            format = "png",
            ppem = 24,
        )
        val entries = mutableListOf(
            twentyFourPpem,
            sixteenPpem,
            BitmapStrikeSelection(glyphId = 91, width = 18, height = 18, format = "png", ppem = 18),
            twentyPpem,
        )
        val selector = StaticBitmapStrikeSelector(entries)

        entries.clear()

        assertEquals(twentyPpem, selector.select(glyphId = 90, requestedSizePx = 21f))
        assertEquals(sixteenPpem, selector.select(glyphId = 90, requestedSizePx = 18f))
        assertNull(selector.select(glyphId = 92, requestedSizePx = 18f))
    }

    @Test
    fun colorSvgEmojiGoldenRecordsFixtureFamiliesRefusalsAndNonClaims() {
        val dump = readProjectFile("reports/font/fixtures/expected/color/color-svg-emoji-goldens.json")

        assertEquals("color-svg-emoji-goldens", jsonStringField(dump, "dumpId"))
        assertEquals(listOf("color-colrv1-test-glyphs"), jsonStringArrayField(dump, "fixtureIds"))
        assertEquals(
            listOf(
                "color-glyphs",
                "png-bitmap-glyphs",
                "svg-glyphs",
                "emoji",
            ),
            jsonStringArrayField(dump, "colorFamilies"),
        )
        assertEquals(
            listOf(
                "text.color.COLRv1-cycle-detected",
                "text.color.COLRv1-budget-exceeded",
                "text.bitmap.PNG-decode-failed",
                "text.bitmap.strike-unavailable",
                "text.bitmap.payload-format-unsupported",
                "text.SVG.external-resource-refused",
                "text.SVG.feature-unsupported",
                "text.SVG.budget-exceeded",
                "text.emoji.sequence-unsupported",
                "text.emoji.fallback-unavailable",
                "text.emoji.color-glyph-unavailable",
            ),
            jsonStringArrayField(dump, "requiredRefusals"),
        )
        assertEquals(
            listOf(
                "no-complete-target-support-claim",
                "no-complete-colrv1-rendering-claim",
                "no-complete-png-bitmap-glyph-routing-claim",
                "no-complete-svg-in-opentype-rendering-claim",
                "no-emoji-zwj-shaping-claim",
                "no-gpu-color-glyph-support-claim",
                "no-platform-color-font-fallback-claim",
            ),
            jsonStringArrayField(dump, "nonClaims"),
        )
    }

    @Test
    fun colorEmojiFixtureManifestConvergesM10FamiliesLegacyGatesAndRemainingGpuEvidence() {
        val expected = readProjectFile("reports/font/fixtures/expected/color/color-emoji-fixture-manifest.json")
        val actual = colorEmojiFixtureManifestJson(
            families = listOf(
                ColorEmojiFixtureManifestFamilyCase(
                    familyId = "color-glyphs",
                    coveredTickets = listOf(
                        "KFONT-M10-001",
                        "KFONT-M10-002",
                        "KFONT-M10-003",
                        "KFONT-M10-004",
                        "KFONT-M10-005",
                    ),
                    claimClassification = "current-supported",
                    fixtureIds = listOf(
                        "color-glyphs-colrv0-layer",
                        "color-glyphs-colrv1-budget-refusal",
                        "color-glyphs-colrv1-composite-clip",
                        "color-glyphs-colrv1-cycle-refusal",
                        "color-glyphs-colrv1-gradient-operation-group",
                        "color-glyphs-colrv1-malformed-offset-refusal",
                        "color-glyphs-colrv1-recursion-depth-refusal",
                        "color-glyphs-colrv1-solid-glyph-colr-glyph",
                        "color-glyphs-colrv1-solid-gradient-transform",
                    ),
                    expectedDumpFiles = listOf(
                        "reports/font/fixtures/expected/color/color-glyph-plan.json",
                        "reports/font/fixtures/expected/color/colrv1-paint-graph.json",
                        "reports/font/fixtures/expected/color/color-glyph-composite-plan.json",
                        "reports/font/fixtures/expected/color/colrv1-fixture-manifest.json",
                        "reports/font/fixtures/expected/color/color-svg-emoji-goldens.json",
                    ),
                    expectedDiagnostics = listOf(
                        "text.color.COLRv1-cycle-detected",
                        "text.color.COLRv1-budget-exceeded",
                    ),
                    legacyGates = listOf("coloremoji_blendmodes"),
                    gpuEvidenceRequired = true,
                    remainingGate =
                        "Keep coloremoji_blendmodes open until M11 links reviewed GPU composite/blend route evidence for color glyph plans and emoji color dispatch.",
                    provenanceKind = "mixed-fixture-sources",
                    provenanceSource = "reports/font/fixtures/provenance/index.json + ColorGlyphSurfaceTest synthetic COLRv0/COLRv1 fixtures",
                    licenseNotes = listOf(
                        "Apache-2.0 vendored COLRv1 fixture assets remain coordination evidence only.",
                        "Synthetic COLRv0/COLRv1 bytes generated inside ColorGlyphSurfaceTest stay CPU/text evidence only.",
                    ),
                    sourceHashes = listOf(
                        "8aa611b1ca97044ac6f13dc982fde29256612f0a5acc6ef47ca541a7a5b99b28",
                        "ad575a09d6748aebcb3b90ffd384c64d5c64b5fd9927967e7cd7cc0d70c98d34",
                    ),
                    generatedRecipe =
                        "ColorGlyphSurfaceTest synthetic CPAL/COLRv0/COLRv1 table builders feed bounded plan, graph, composite, and refusal dumps with reviewed expected outputs.",
                ),
                ColorEmojiFixtureManifestFamilyCase(
                    familyId = "emoji",
                    coveredTickets = listOf("KFONT-M10-009"),
                    claimClassification = "current-supported",
                    fixtureIds = listOf(
                        "emoji-color-glyph-unavailable-refusal",
                        "emoji-emoji-fallback-unavailable-refusal",
                        "emoji-emoji-flag",
                        "emoji-emoji-keycap",
                        "emoji-emoji-skin-tone",
                        "emoji-emoji-unsupported-sequence-refusal",
                        "emoji-emoji-vs15-vs16",
                        "emoji-emoji-zwj-family",
                    ),
                    expectedDumpFiles = listOf(
                        "reports/font/fixtures/expected/color/emoji-route-trace.json",
                        "reports/font/fixtures/expected/color/color-svg-emoji-goldens.json",
                    ),
                    expectedDiagnostics = listOf(
                        "text.emoji.sequence-unsupported",
                        "text.emoji.fallback-unavailable",
                        "text.emoji.color-glyph-unavailable",
                    ),
                    legacyGates = listOf("scaledemoji"),
                    gpuEvidenceRequired = true,
                    remainingGate =
                        "Keep scaledemoji open until reviewed emoji sequence shaping, fallback-family selection, and selected color route evidence is linked to M11 route execution proof where rendering is claimed.",
                    provenanceKind = "mixed-fixture-sources",
                    provenanceSource = "reports/font/fixtures/provenance/index.json + ColorGlyphSurfaceTest emoji route trace fixtures",
                    licenseNotes = listOf(
                        "SIL-OFL-1.1 Noto Emoji asset remains bundled provenance only.",
                        "Emoji route traces are deterministic test-owned sequence dumps, not platform-engine or GPU evidence.",
                    ),
                    sourceHashes = listOf("469e3b92d63cfc203789f8742f1835b8672c7b5995ab4a832f1699b712a5afcc"),
                    generatedRecipe =
                        "ColorGlyphSurfaceTest builds deterministic emoji sequence traces for VS15/VS16, skin tone, ZWJ, flag, keycap, fallback-unavailable, color-glyph-unavailable, and unsupported-sequence cases.",
                ),
                ColorEmojiFixtureManifestFamilyCase(
                    familyId = "png-bitmap-glyphs",
                    coveredTickets = listOf("KFONT-M10-006"),
                    claimClassification = "tracked-gap",
                    fixtureIds = listOf(
                        "png-bitmap-glyphs-cbdt-cblc-png",
                        "png-bitmap-glyphs-malformed-png-refusal",
                        "png-bitmap-glyphs-non-png-payload-refusal",
                        "png-bitmap-glyphs-sbix-png",
                        "png-bitmap-glyphs-unavailable-strike-refusal",
                    ),
                    expectedDumpFiles = listOf(
                        "reports/font/fixtures/expected/color/bitmap-glyph-plan.json",
                        "reports/font/fixtures/expected/color/color-svg-emoji-goldens.json",
                    ),
                    expectedDiagnostics = listOf(
                        "text.bitmap.PNG-decode-failed",
                        "text.bitmap.strike-unavailable",
                        "text.bitmap.payload-format-unsupported",
                    ),
                    legacyGates = listOf("scaledemoji_rendering"),
                    gpuEvidenceRequired = true,
                    remainingGate =
                        "Keep scaledemoji_rendering open until M11 links reviewed bitmap upload, sampling, and renderer-route evidence; bitmap-glyph-plan.json remains CPU/text evidence only.",
                    provenanceKind = "generated-test-data",
                    provenanceSource = "ColorGlyphSurfaceTest bitmap plan bundle fixtures",
                    licenseNotes = listOf(
                        "Bitmap fixture payloads are bounded test-generated PNG bytes checked through ColorGlyphSurfaceTest.",
                    ),
                    sourceHashes = emptyList(),
                    generatedRecipe =
                        "ColorGlyphSurfaceTest emits deterministic CBDT/CBLC and sbix PNG plan bundles plus unavailable-strike, malformed-PNG, and non-PNG refusal cases.",
                ),
                ColorEmojiFixtureManifestFamilyCase(
                    familyId = "svg-glyphs",
                    coveredTickets = listOf("KFONT-M10-007", "KFONT-M10-008"),
                    claimClassification = "fixture-gated",
                    fixtureIds = listOf(
                        "svg-glyphs-svg-animation-refusal",
                        "svg-glyphs-svg-defs-symbol-use-radial-gradient",
                        "svg-glyphs-svg-embedded-text-refusal",
                        "svg-glyphs-svg-external-resource-refusal",
                        "svg-glyphs-svg-filter-refusal",
                        "svg-glyphs-svg-foreign-object-refusal",
                        "svg-glyphs-svg-gradient-stop-budget-refusal",
                        "svg-glyphs-svg-gradient-transform-clip",
                        "svg-glyphs-svg-malformed-document-refusal",
                        "svg-glyphs-svg-malformed-path-data-refusal",
                        "svg-glyphs-svg-network-reference-refusal",
                        "svg-glyphs-svg-path-command-budget-refusal",
                        "svg-glyphs-svg-script-refusal",
                        "svg-glyphs-svg-static-path",
                        "svg-glyphs-svg-unsupported-css-selector-refusal",
                        "svg-glyphs-svg-use-recursion-refusal",
                    ),
                    expectedDumpFiles = listOf(
                        "reports/font/fixtures/expected/color/svg-glyph-plan.json",
                        "reports/font/fixtures/expected/color/svg-glyph-fixture-manifest.json",
                        "reports/font/fixtures/expected/color/color-svg-emoji-goldens.json",
                    ),
                    expectedDiagnostics = listOf(
                        "text.SVG.external-resource-refused",
                        "text.SVG.feature-unsupported",
                        "text.SVG.document-malformed",
                        "text.SVG.budget-exceeded",
                    ),
                    legacyGates = listOf("scaledemoji_rendering"),
                    gpuEvidenceRequired = true,
                    remainingGate =
                        "Keep scaledemoji_rendering open until reviewed glyph-scoped SVG renderer-route evidence lands in M11; the current SVG fixture manifest remains bounded CPU/text evidence.",
                    provenanceKind = "generated-test-data",
                    provenanceSource = "ColorGlyphSurfaceTest SVG fixture manifest fixtures",
                    licenseNotes = listOf(
                        "SVG glyph documents are deterministic test-owned inputs generated inside ColorGlyphSurfaceTest.",
                    ),
                    sourceHashes = emptyList(),
                    generatedRecipe =
                        "ColorGlyphSurfaceTest builds bounded static-path, gradient/transform/clip, defs/symbol/use, and refusal SVG documents, then records deterministic plan and fixture-manifest outputs.",
                ),
            ),
            legacyGates = listOf(
                ColorEmojiLegacyGateCase(
                    gate = "coloremoji_blendmodes",
                    familyIds = listOf("color-glyphs", "emoji"),
                    remainingEvidence =
                        "Reviewed GPU composite/blend execution evidence for COLRv1 and emoji color routes remains an M11 gate.",
                ),
                ColorEmojiLegacyGateCase(
                    gate = "scaledemoji",
                    familyIds = listOf("emoji"),
                    remainingEvidence =
                        "Emoji sequence shaping and fallback evidence is now linked here, but reviewed M11 renderer-route and GPU evidence still remain before any legacy-gate retirement.",
                ),
                ColorEmojiLegacyGateCase(
                    gate = "scaledemoji_rendering",
                    familyIds = listOf("emoji", "png-bitmap-glyphs", "svg-glyphs"),
                    remainingEvidence =
                        "Bitmap and SVG renderer upload/sampling proofs remain M11 work even when CPU/text dumps and fixture provenance are present.",
                ),
            ),
        )

        assertEquals(expected, actual)
        assertEvidenceDumpClean(expected)
        assertEquals("color-emoji-fixture-manifest", jsonStringField(expected, "dumpId"))
        assertEquals(
            listOf("color-glyphs", "emoji", "png-bitmap-glyphs", "svg-glyphs"),
            jsonStringArrayField(expected, "fixtureFamilies"),
        )
        assertEquals(
            listOf("coloremoji_blendmodes", "scaledemoji", "scaledemoji_rendering"),
            jsonStringArrayField(expected, "legacyGates"),
        )
        assertTrue(expected.contains("\"gpuEvidenceRequired\": true"))
    }

    @Test
    fun colrv1FixtureManifestRecordsBoundsRoutesAndTraversalRefusals() {
        val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440419"))
        val strikeKey = strikeKey(typefaceId = typefaceId, paletteIdentity = "brand-colrv1-fixtures")

        val cycleTable = COLRV1Table(
            baseGlyphPaintRecords = listOf(
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 160,
                    paint = COLRV1Paint.ColrGlyph(glyphId = 161),
                ),
                COLRV1BaseGlyphPaintRecord(
                    glyphId = 161,
                    paint = COLRV1Paint.Glyph(
                        glyphId = 162,
                        paint = COLRV1Paint.ColrGlyph(glyphId = 160),
                    ),
                ),
            ),
        )
        val cycleDiagnostic = assertNotNull(cycleTable.paintColrGlyphCycleDiagnostic(glyphId = 160))

        val recursionPlanner = COLRV1ColorGlyphPlanner(
            colr = COLRV1Table(
                baseGlyphPaintRecords = listOf(
                    COLRV1BaseGlyphPaintRecord(glyphId = 300, paint = COLRV1Paint.ColrGlyph(glyphId = 301)),
                    COLRV1BaseGlyphPaintRecord(glyphId = 301, paint = COLRV1Paint.ColrGlyph(glyphId = 302)),
                    COLRV1BaseGlyphPaintRecord(
                        glyphId = 302,
                        paint = COLRV1Paint.Glyph(
                            glyphId = 303,
                            paint = COLRV1Paint.Solid(paletteIndex = 0, alpha = 1f),
                        ),
                    ),
                ),
            ),
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            glyphBounds = mapOf(303 to ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 4, yMax = 4)),
            maxRecursionDepth = 2,
        )
        val recursionDecision = recursionPlanner.plan(
            glyphId = 300,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 0),
            allowMonochromeFallback = true,
            outlineFallback = OutlineGlyphRepresentation(glyphId = 300, pathCommands = listOf("M 0 0", "L 4 4")),
        )
        assertNull(recursionDecision.plan)
        assertEquals("outline", recursionDecision.selectedRoute?.route)
        val recursionDiagnostic = recursionDecision.diagnostics.single()
        assertEquals(ColorGlyphDiagnosticCodes.COLRV1BudgetExceeded, recursionDiagnostic.code)
        assertTrue(recursionDiagnostic.detail.contains("limitName=recursionDepth"))
        assertTrue(recursionDiagnostic.detail.contains("observed=3"))

        val expandedPlanner = COLRV1ColorGlyphPlanner(
            colr = COLRV1Table(
                baseGlyphPaintRecords = listOf(
                    COLRV1BaseGlyphPaintRecord(
                        glyphId = 304,
                        paint = COLRV1Paint.Transform(
                            paint = COLRV1Paint.Glyph(
                                glyphId = 305,
                                paint = COLRV1Paint.Solid(paletteIndex = 1, alpha = 1f),
                            ),
                            xx = 1f,
                            yx = 0f,
                            xy = 0f,
                            yy = 1f,
                            dx = 0f,
                            dy = 0f,
                        ),
                    ),
                ),
            ),
            cpal = assertNotNull(CPALV0Parser.parse(syntheticCpalV0())),
            glyphBounds = mapOf(305 to ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 5, yMax = 6)),
            maxExpandedNodeCount = 2,
        )
        val expandedDecision = expandedPlanner.plan(
            glyphId = 304,
            typefaceId = typefaceId,
            strikeKey = strikeKey,
            paletteSelection = CPALPaletteSelection(index = 0),
            allowMonochromeFallback = true,
            outlineFallback = OutlineGlyphRepresentation(glyphId = 304, pathCommands = listOf("M 0 0", "L 5 6")),
        )
        assertNull(expandedDecision.plan)
        assertEquals("outline", expandedDecision.selectedRoute?.route)
        val expandedDiagnostic = expandedDecision.diagnostics.single()
        assertEquals(ColorGlyphDiagnosticCodes.COLRV1BudgetExceeded, expandedDiagnostic.code)
        assertTrue(expandedDiagnostic.detail.contains("limitName=expandedPaintCount"))
        assertTrue(expandedDiagnostic.detail.contains("observed=3"))

        assertNull(
            COLRV1Parser.parse(
                syntheticColrV1GlyphSolid(
                    baseGlyph = 306,
                    glyph = 307,
                    paletteIndex = 0,
                    alphaF2Dot14 = 0x4000,
                ).copyOf(52),
            ),
        )

        val expected = colrv1FixtureManifestJson(
            cases = listOf(
                COLRV1FixtureManifestCase(
                    fixtureId = "color-glyphs-colrv1-solid-glyph-colr-glyph-bounds",
                    focus = "nested-glyph-colr-glyph-bounds",
                    fixtureKind = "synthetic-colrv1-table",
                    parserExpectation = "parsed-table",
                    expectedRoute = "colr",
                    expectedCaseIds = listOf("colrv1-solid-glyph-colr-glyph"),
                    expectedDumpIds = listOf("color-glyph-plan", "colrv1-paint-graph"),
                    expectedDiagnosticsJson = emptyList(),
                    provenanceNotes = "Synthetic COLRv1 bytes from ColorGlyphSurfaceTest cover PaintColrGlyph -> PaintGlyph -> PaintSolid bounds propagation.",
                ),
                COLRV1FixtureManifestCase(
                    fixtureId = "color-glyphs-colrv1-transform-bounds",
                    focus = "transform-bounds",
                    fixtureKind = "synthetic-colrv1-table",
                    parserExpectation = "parsed-table",
                    expectedRoute = "colr",
                    expectedCaseIds = listOf(
                        "colrv1-translate",
                        "colrv1-transform",
                        "colrv1-scale",
                        "colrv1-rotate",
                        "colrv1-skew",
                    ),
                    expectedDumpIds = listOf("color-glyph-plan", "colrv1-paint-graph"),
                    expectedDiagnosticsJson = emptyList(),
                    provenanceNotes = "Synthetic COLRv1 bytes from ColorGlyphSurfaceTest cover translate and generic transform matrices classified as transform/scale/rotate/skew.",
                ),
                COLRV1FixtureManifestCase(
                    fixtureId = "color-glyphs-colrv1-composite-clip-bounds",
                    focus = "composite-clip-bounds",
                    fixtureKind = "synthetic-colrv1-table",
                    parserExpectation = "parsed-table",
                    expectedRoute = "colr",
                    expectedCaseIds = listOf("colrv1-composite-clip"),
                    expectedDumpIds = listOf("color-glyph-composite-plan", "color-glyph-plan", "colrv1-paint-graph"),
                    expectedDiagnosticsJson = emptyList(),
                    provenanceNotes = "Synthetic COLRv1 bytes from ColorGlyphSurfaceTest cover clip-wrapped composite bounds plus destination-read and layer-isolation hints.",
                ),
                COLRV1FixtureManifestCase(
                    fixtureId = "color-glyphs-colrv1-cycle-refusal",
                    focus = "paint-colr-glyph-cycle-diagnostic",
                    fixtureKind = "synthetic-colrv1-table",
                    parserExpectation = "parsed-table",
                    expectedRoute = null,
                    expectedCaseIds = emptyList(),
                    expectedDumpIds = listOf("colrv1-fixture-manifest", "color-svg-emoji-goldens"),
                    expectedDiagnosticsJson = listOf(cycleDiagnostic.toCanonicalJson()),
                    provenanceNotes = "Synthetic parsed COLRv1 table from ColorGlyphSurfaceTest walks 160 -> 161 -> 160 and records the first stable cycle diagnostic.",
                ),
                COLRV1FixtureManifestCase(
                    fixtureId = "color-glyphs-colrv1-recursion-depth-refusal",
                    focus = "recursion-depth-refusal",
                    fixtureKind = "synthetic-colrv1-table",
                    parserExpectation = "parsed-table",
                    expectedRoute = "outline",
                    expectedCaseIds = emptyList(),
                    expectedDumpIds = listOf("colrv1-fixture-manifest"),
                    expectedDiagnosticsJson = listOf(recursionDiagnostic.toCanonicalJson()),
                    provenanceNotes = "Synthetic COLRv1 bytes from ColorGlyphSurfaceTest set maxRecursionDepth=2 and force a third nested PaintColrGlyph walk before monochrome outline fallback.",
                ),
                COLRV1FixtureManifestCase(
                    fixtureId = "color-glyphs-colrv1-expanded-paint-count-refusal",
                    focus = "expanded-paint-budget-refusal",
                    fixtureKind = "synthetic-colrv1-table",
                    parserExpectation = "parsed-table",
                    expectedRoute = "outline",
                    expectedCaseIds = emptyList(),
                    expectedDumpIds = listOf("colrv1-fixture-manifest"),
                    expectedDiagnosticsJson = listOf(expandedDiagnostic.toCanonicalJson()),
                    provenanceNotes = "Synthetic COLRv1 bytes from ColorGlyphSurfaceTest set maxExpandedNodeCount=2 and force the third expanded node before monochrome outline fallback.",
                ),
                COLRV1FixtureManifestCase(
                    fixtureId = "color-glyphs-colrv1-malformed-offset-refusal",
                    focus = "malformed-offset-parse-null",
                    fixtureKind = "synthetic-colrv1-table-bytes",
                    parserExpectation = "parse-null",
                    expectedRoute = null,
                    expectedCaseIds = emptyList(),
                    expectedDumpIds = listOf("colrv1-fixture-manifest"),
                    expectedDiagnosticsJson = emptyList(),
                    provenanceNotes = "Synthetic COLRv1 glyph-solid bytes are truncated to make the paint payload unreadable; the parser must fail closed without inventing a route claim.",
                ),
            ),
        )

        val dump = readProjectFile("reports/font/fixtures/expected/color/colrv1-fixture-manifest.json")
        assertEquals(expected.trim(), dump.trim())
        assertEvidenceDumpClean(dump)
    }

    private fun syntheticCpalV0(): ByteArray {
        val bytes = ByteArray(32)
        writeU16(bytes, 0, 0)
        writeU16(bytes, 2, 2)
        writeU16(bytes, 4, 2)
        writeU16(bytes, 6, 4)
        writeU32(bytes, 8, 16)
        writeU16(bytes, 12, 0)
        writeU16(bytes, 14, 2)
        writeBgra(bytes, 16, blue = 0x33, green = 0x22, red = 0x11, alpha = 0x44)
        writeBgra(bytes, 20, blue = 0x77, green = 0x66, red = 0x55, alpha = 0x88)
        writeBgra(bytes, 24, blue = 0xBB, green = 0xAA, red = 0x99, alpha = 0xCC)
        writeBgra(bytes, 28, blue = 0x10, green = 0xEE, red = 0xDD, alpha = 0xFF)
        return bytes
    }

    private fun syntheticColrV0(): ByteArray {
        val bytes = ByteArray(28)
        writeU16(bytes, 0, 0)
        writeU16(bytes, 2, 1)
        writeU32(bytes, 4, 14)
        writeU32(bytes, 8, 20)
        writeU16(bytes, 12, 2)
        writeU16(bytes, 14, 100)
        writeU16(bytes, 16, 0)
        writeU16(bytes, 18, 2)
        writeU16(bytes, 20, 11)
        writeU16(bytes, 22, 0)
        writeU16(bytes, 24, 12)
        writeU16(bytes, 26, 1)
        return bytes
    }

    private fun syntheticColrV1GlyphSolid(
        baseGlyph: Int,
        glyph: Int,
        paletteIndex: Int,
        alphaF2Dot14: Int,
    ): ByteArray {
        val baseGlyphListOffset = 34
        val glyphPaintOffset = baseGlyphListOffset + 10
        val solidPaintOffset = glyphPaintOffset + 6
        val bytes = ByteArray(solidPaintOffset + 5)
        writeU16(bytes, 0, 1)
        writeU32(bytes, 14, baseGlyphListOffset)

        writeU32(bytes, baseGlyphListOffset, 1)
        writeU16(bytes, baseGlyphListOffset + 4, baseGlyph)
        writeU32(bytes, baseGlyphListOffset + 6, glyphPaintOffset - baseGlyphListOffset)

        bytes[glyphPaintOffset] = 10
        writeU24(bytes, glyphPaintOffset + 1, solidPaintOffset - glyphPaintOffset)
        writeU16(bytes, glyphPaintOffset + 4, glyph)

        bytes[solidPaintOffset] = 2
        writeU16(bytes, solidPaintOffset + 1, paletteIndex)
        writeI16(bytes, solidPaintOffset + 3, alphaF2Dot14)
        return bytes
    }

    private fun syntheticColrV1GlyphVarSolid(
        baseGlyph: Int,
        glyph: Int,
        paletteIndex: Int,
        alphaF2Dot14: Int,
        varIndexBase: Long,
    ): ByteArray {
        val baseGlyphListOffset = 34
        val glyphPaintOffset = baseGlyphListOffset + 10
        val solidPaintOffset = glyphPaintOffset + 6
        val bytes = ByteArray(solidPaintOffset + 9)
        writeU16(bytes, 0, 1)
        writeU32(bytes, 14, baseGlyphListOffset)

        writeU32(bytes, baseGlyphListOffset, 1)
        writeU16(bytes, baseGlyphListOffset + 4, baseGlyph)
        writeU32(bytes, baseGlyphListOffset + 6, glyphPaintOffset - baseGlyphListOffset)

        bytes[glyphPaintOffset] = 10
        writeU24(bytes, glyphPaintOffset + 1, solidPaintOffset - glyphPaintOffset)
        writeU16(bytes, glyphPaintOffset + 4, glyph)

        bytes[solidPaintOffset] = 3
        writeU16(bytes, solidPaintOffset + 1, paletteIndex)
        writeI16(bytes, solidPaintOffset + 3, alphaF2Dot14)
        writeU32(bytes, solidPaintOffset + 5, varIndexBase.toInt())
        return bytes
    }

    private fun syntheticColrV1UnsupportedPaint(baseGlyph: Int, paintFormat: Int): ByteArray {
        val baseGlyphListOffset = 34
        val paintOffset = baseGlyphListOffset + 10
        val bytes = ByteArray(paintOffset + 1)
        writeU16(bytes, 0, 1)
        writeU32(bytes, 14, baseGlyphListOffset)

        writeU32(bytes, baseGlyphListOffset, 1)
        writeU16(bytes, baseGlyphListOffset + 4, baseGlyph)
        writeU32(bytes, baseGlyphListOffset + 6, paintOffset - baseGlyphListOffset)
        bytes[paintOffset] = paintFormat.toByte()
        return bytes
    }

    private fun syntheticColrV1LayersAndClip(
        baseGlyph: Int,
        firstLayerGlyph: Int,
        secondLayerGlyph: Int,
    ): ByteArray {
        val baseGlyphListOffset = 34
        val layerListOffset = 44
        val rootLayersPaintOffset = 56
        val firstGlyphPaintOffset = 62
        val firstSolidPaintOffset = 68
        val secondGlyphPaintOffset = 73
        val secondSolidPaintOffset = 79
        val clipListOffset = 84
        val clipBoxOffset = 96
        val bytes = ByteArray(clipBoxOffset + 9)
        writeU16(bytes, 0, 1)
        writeU32(bytes, 14, baseGlyphListOffset)
        writeU32(bytes, 18, layerListOffset)
        writeU32(bytes, 22, clipListOffset)

        writeU32(bytes, baseGlyphListOffset, 1)
        writeU16(bytes, baseGlyphListOffset + 4, baseGlyph)
        writeU32(bytes, baseGlyphListOffset + 6, rootLayersPaintOffset - baseGlyphListOffset)

        writeU32(bytes, layerListOffset, 2)
        writeU32(bytes, layerListOffset + 4, firstGlyphPaintOffset - layerListOffset)
        writeU32(bytes, layerListOffset + 8, secondGlyphPaintOffset - layerListOffset)

        bytes[rootLayersPaintOffset] = 1
        bytes[rootLayersPaintOffset + 1] = 2
        writeU32(bytes, rootLayersPaintOffset + 2, 0)

        bytes[firstGlyphPaintOffset] = 10
        writeU24(bytes, firstGlyphPaintOffset + 1, firstSolidPaintOffset - firstGlyphPaintOffset)
        writeU16(bytes, firstGlyphPaintOffset + 4, firstLayerGlyph)
        bytes[firstSolidPaintOffset] = 2
        writeU16(bytes, firstSolidPaintOffset + 1, 0)
        writeI16(bytes, firstSolidPaintOffset + 3, 0x4000)

        bytes[secondGlyphPaintOffset] = 10
        writeU24(bytes, secondGlyphPaintOffset + 1, secondSolidPaintOffset - secondGlyphPaintOffset)
        writeU16(bytes, secondGlyphPaintOffset + 4, secondLayerGlyph)
        bytes[secondSolidPaintOffset] = 2
        writeU16(bytes, secondSolidPaintOffset + 1, 1)
        writeI16(bytes, secondSolidPaintOffset + 3, 0x2000)

        bytes[clipListOffset] = 1
        writeU32(bytes, clipListOffset + 1, 1)
        writeU16(bytes, clipListOffset + 5, baseGlyph)
        writeU16(bytes, clipListOffset + 7, baseGlyph)
        writeU24(bytes, clipListOffset + 9, clipBoxOffset - clipListOffset)

        bytes[clipBoxOffset] = 1
        writeI16(bytes, clipBoxOffset + 1, -10)
        writeI16(bytes, clipBoxOffset + 3, -20)
        writeI16(bytes, clipBoxOffset + 5, 30)
        writeI16(bytes, clipBoxOffset + 7, 40)
        return bytes
    }

    private fun syntheticColrV1Gradients(): ByteArray {
        val baseGlyphListOffset = 34
        val linearPaintOffset = 64
        val radialPaintOffset = 80
        val sweepPaintOffset = 96
        val linearColorLineOffset = 112
        val radialColorLineOffset = 127
        val sweepColorLineOffset = 142
        val bytes = ByteArray(157)
        writeU16(bytes, 0, 1)
        writeU32(bytes, 14, baseGlyphListOffset)

        writeU32(bytes, baseGlyphListOffset, 3)
        writeU16(bytes, baseGlyphListOffset + 4, 200)
        writeU32(bytes, baseGlyphListOffset + 6, linearPaintOffset - baseGlyphListOffset)
        writeU16(bytes, baseGlyphListOffset + 10, 201)
        writeU32(bytes, baseGlyphListOffset + 12, radialPaintOffset - baseGlyphListOffset)
        writeU16(bytes, baseGlyphListOffset + 16, 202)
        writeU32(bytes, baseGlyphListOffset + 18, sweepPaintOffset - baseGlyphListOffset)

        bytes[linearPaintOffset] = 4
        writeU24(bytes, linearPaintOffset + 1, linearColorLineOffset - linearPaintOffset)
        writeI16(bytes, linearPaintOffset + 4, 1)
        writeI16(bytes, linearPaintOffset + 6, 2)
        writeI16(bytes, linearPaintOffset + 8, 3)
        writeI16(bytes, linearPaintOffset + 10, 4)
        writeI16(bytes, linearPaintOffset + 12, 5)
        writeI16(bytes, linearPaintOffset + 14, 6)

        bytes[radialPaintOffset] = 6
        writeU24(bytes, radialPaintOffset + 1, radialColorLineOffset - radialPaintOffset)
        writeI16(bytes, radialPaintOffset + 4, -1)
        writeI16(bytes, radialPaintOffset + 6, -2)
        writeU16(bytes, radialPaintOffset + 8, 7)
        writeI16(bytes, radialPaintOffset + 10, 8)
        writeI16(bytes, radialPaintOffset + 12, 9)
        writeU16(bytes, radialPaintOffset + 14, 10)

        bytes[sweepPaintOffset] = 8
        writeU24(bytes, sweepPaintOffset + 1, sweepColorLineOffset - sweepPaintOffset)
        writeI16(bytes, sweepPaintOffset + 4, 20)
        writeI16(bytes, sweepPaintOffset + 6, 30)
        writeI16(bytes, sweepPaintOffset + 8, 0x1000)
        writeI16(bytes, sweepPaintOffset + 10, 0x3000)

        writeColorLine(bytes, linearColorLineOffset)
        writeColorLine(bytes, radialColorLineOffset)
        writeColorLine(bytes, sweepColorLineOffset)
        return bytes
    }

    private fun syntheticColrV1CompositeAndColrGlyph(): ByteArray {
        val baseGlyphListOffset = 34
        val compositePaintOffset = 44
        val sourcePaintOffset = 52
        val backdropPaintOffset = 57
        val bytes = ByteArray(60)
        writeU16(bytes, 0, 1)
        writeU32(bytes, 14, baseGlyphListOffset)

        writeU32(bytes, baseGlyphListOffset, 1)
        writeU16(bytes, baseGlyphListOffset + 4, 210)
        writeU32(bytes, baseGlyphListOffset + 6, compositePaintOffset - baseGlyphListOffset)

        bytes[compositePaintOffset] = 32
        writeU24(bytes, compositePaintOffset + 1, sourcePaintOffset - compositePaintOffset)
        bytes[compositePaintOffset + 4] = 3
        writeU24(bytes, compositePaintOffset + 5, backdropPaintOffset - compositePaintOffset)

        bytes[sourcePaintOffset] = 2
        writeU16(bytes, sourcePaintOffset + 1, 4)
        writeI16(bytes, sourcePaintOffset + 3, 0x4000)

        bytes[backdropPaintOffset] = 11
        writeU16(bytes, backdropPaintOffset + 1, 211)
        return bytes
    }

    private fun syntheticColorLine(): COLRV1ColorLine =
        COLRV1ColorLine(
            extend = COLRV1ColorLineExtend.REPEAT,
            stops = listOf(
                COLRV1ColorStop(offset = 0f, paletteIndex = 5, alpha = 1f),
                COLRV1ColorStop(offset = 1f, paletteIndex = 6, alpha = 0.5f),
            ),
        )

    private fun plannerLinearGradientColorLine(): COLRV1ColorLine =
        COLRV1ColorLine(
            extend = COLRV1ColorLineExtend.REPEAT,
            stops = listOf(
                COLRV1ColorStop(offset = 0f, paletteIndex = 0, alpha = 1f),
                COLRV1ColorStop(offset = 1f, paletteIndex = 1, alpha = 0.5f),
            ),
        )

    private fun plannerRadialGradientColorLine(): COLRV1ColorLine =
        COLRV1ColorLine(
            extend = COLRV1ColorLineExtend.PAD,
            stops = listOf(
                COLRV1ColorStop(offset = 0f, paletteIndex = 1, alpha = 1f),
                COLRV1ColorStop(offset = 1f, paletteIndex = 0, alpha = 0.5f),
            ),
        )

    private fun plannerSweepGradientColorLine(): COLRV1ColorLine =
        COLRV1ColorLine(
            extend = COLRV1ColorLineExtend.REFLECT,
            stops = listOf(
                COLRV1ColorStop(offset = 0f, paletteIndex = 0, alpha = 0.75f),
                COLRV1ColorStop(offset = 1f, paletteIndex = 1, alpha = 1f),
            ),
        )

    private fun plannerVariableGradientColorLine(): COLRV1ColorLine =
        COLRV1ColorLine(
            extend = COLRV1ColorLineExtend.REPEAT,
            stops = listOf(
                COLRV1ColorStop(offset = 0f, paletteIndex = 0, alpha = 1f),
                COLRV1ColorStop(offset = 1f, paletteIndex = 1, alpha = 0.5f, varIndexBase = 17L),
            ),
        )

    private fun plannerBudgetOverflowGradientColorLine(): COLRV1ColorLine =
        COLRV1ColorLine(
            extend = COLRV1ColorLineExtend.PAD,
            stops = listOf(
                COLRV1ColorStop(offset = 0f, paletteIndex = 0, alpha = 1f),
                COLRV1ColorStop(offset = 0.5f, paletteIndex = 1, alpha = 0.75f),
                COLRV1ColorStop(offset = 1f, paletteIndex = 0, alpha = 0.5f),
            ),
        )

    private fun writeColorLine(bytes: ByteArray, offset: Int) {
        bytes[offset] = 1
        writeU16(bytes, offset + 1, 2)
        writeI16(bytes, offset + 3, 0)
        writeU16(bytes, offset + 5, 5)
        writeI16(bytes, offset + 7, 0x4000)
        writeI16(bytes, offset + 9, 0x4000)
        writeU16(bytes, offset + 11, 6)
        writeI16(bytes, offset + 13, 0x2000)
    }

    private fun syntheticPngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(33)
        val signature = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        signature.forEachIndexed { index, value -> bytes[index] = value.toByte() }
        writeU32(bytes, 8, 13)
        bytes[12] = 'I'.code.toByte()
        bytes[13] = 'H'.code.toByte()
        bytes[14] = 'D'.code.toByte()
        bytes[15] = 'R'.code.toByte()
        writeU32(bytes, 16, width)
        writeU32(bytes, 20, height)
        bytes[24] = 8
        bytes[25] = 6
        return bytes
    }

    private fun syntheticRgbaPng(width: Int, height: Int, pixels: IntArray): ByteArray {
        require(pixels.size == width * height)

        val raw = ByteArrayOutputStream()
        repeat(height) { y ->
            raw.write(0)
            repeat(width) { x ->
                val color = pixels[y * width + x]
                raw.write((color ushr 16) and 0xFF)
                raw.write((color ushr 8) and 0xFF)
                raw.write(color and 0xFF)
                raw.write((color ushr 24) and 0xFF)
            }
        }

        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writePngChunk("IHDR", ByteArrayOutputStream().apply {
                writeU32(width)
                writeU32(height)
                write(8)
                write(6)
                write(0)
                write(0)
                write(0)
            }.toByteArray())
            writePngChunk("IDAT", deflate(raw.toByteArray()))
            writePngChunk("IEND", ByteArray(0))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writePngChunk(type: String, data: ByteArray) {
        writeU32(data.size)
        val crc = CRC32()
        val typeBytes = type.encodeToByteArray()
        write(typeBytes)
        write(data)
        crc.update(typeBytes)
        crc.update(data)
        writeU32(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(128)
            while (!deflater.finished()) {
                output.write(buffer, 0, deflater.deflate(buffer))
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun writeBgra(bytes: ByteArray, offset: Int, blue: Int, green: Int, red: Int, alpha: Int) {
        bytes[offset] = blue.toByte()
        bytes[offset + 1] = green.toByte()
        bytes[offset + 2] = red.toByte()
        bytes[offset + 3] = alpha.toByte()
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun writeI16(bytes: ByteArray, offset: Int, value: Int) {
        writeU16(bytes, offset, value and 0xFFFF)
    }

    private fun writeU24(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 16).toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = value.toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha and 0xFF) shl 24) or
            ((red and 0xFF) shl 16) or
            ((green and 0xFF) shl 8) or
            (blue and 0xFF)

    private data class EmojiRouteTraceFixtureCase(
        val caseId: String,
        val sourceText: String,
        val trace: EmojiRouteTrace,
    )

    private fun glyphRun(glyphIds: List<Int>): ShapedGlyphRun =
        ShapedGlyphRun(glyphIds = glyphIds)

    private fun emojiFallbackAttempt(
        familyName: String,
        typefaceId: TypefaceID?,
        covered: Boolean,
        reason: String,
        selected: Boolean = false,
        diagnosticCode: String? = null,
    ): EmojiFallbackAttempt =
        EmojiFallbackAttempt(
            familyName = familyName,
            typefaceId = typefaceId,
            covered = covered,
            reason = reason,
            selected = selected,
            diagnosticCode = diagnosticCode,
        )

    private fun emojiRouteTraceFixtureDump(cases: List<EmojiRouteTraceFixtureCase>): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"emoji-route-trace\",\n")
        append("  \"ownerTickets\": [\"KFONT-M10-009\"],\n")
        append("  \"cases\": [\n")
        append(
            cases.joinToString(",\n") { case ->
                buildString {
                    append("    {\n")
                    append("      \"caseId\": ").append(jsonString(case.caseId)).append(",\n")
                    append("      \"sourceText\": ").append(jsonString(case.sourceText)).append(",\n")
                    append("      \"trace\": ").append(normalizeEmbeddedJson(case.trace.toCanonicalJson()).prependIndent("      ").trimStart())
                    append("\n    }")
                }
            },
        )
        append("\n  ],\n")
        append("  \"nonClaims\": [\n")
        append("    \"no-complete-target-support-claim\",\n")
        append("    \"no-complete-emoji-sequence-shaping-claim\",\n")
        append("    \"no-complete-color-glyph-fallback-support-claim\",\n")
        append("    \"no-gpu-color-glyph-support-claim\",\n")
        append("    \"no-platform-emoji-engine-claim\",\n")
        append("    \"no-scaledemoji-retirement\"\n")
        append("  ]\n")
        append("}\n")
    }

    private fun strikeKey(
        typefaceId: TypefaceID = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655441201")),
        paletteIdentity: String? = null,
        variationCoordinates: Map<String, Float> = emptyMap(),
    ): GlyphStrikeKey =
        GlyphStrikeKey(
            typefaceId = typefaceId,
            sizePx = 16f,
            paletteIdentity = paletteIdentity,
            variationCoordinates = variationCoordinates,
        )

    private fun canonicalDumpBodySha256(dump: String): String {
        val body = dump.replace(
            Regex(",\n  \"dumpSha256\": \"[0-9a-f]{64}\"\n}\\n$"),
            "\n}\n",
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private data class BitmapGlyphPlanFixtureCase(
        val caseId: String,
        val tableFamily: String,
        val expectedGpuHandoffArtifactType: String,
        val planJson: String?,
        val diagnostics: List<ColorGlyphDiagnostic>,
    )

    private data class ColorGlyphPlanFixtureCase(
        val caseId: String,
        val route: String,
        val planJson: String?,
        val diagnostics: List<ColorGlyphDiagnostic>,
    )

    private data class COLRV1FixtureManifestCase(
        val fixtureId: String,
        val focus: String,
        val fixtureKind: String,
        val parserExpectation: String,
        val expectedRoute: String?,
        val expectedCaseIds: List<String>,
        val expectedDumpIds: List<String>,
        val expectedDiagnosticsJson: List<String>,
        val provenanceNotes: String,
    )

    private data class ColorEmojiFixtureManifestFamilyCase(
        val familyId: String,
        val coveredTickets: List<String>,
        val claimClassification: String,
        val fixtureIds: List<String>,
        val expectedDumpFiles: List<String>,
        val expectedDiagnostics: List<String>,
        val legacyGates: List<String>,
        val gpuEvidenceRequired: Boolean,
        val remainingGate: String,
        val provenanceKind: String,
        val provenanceSource: String,
        val licenseNotes: List<String>,
        val sourceHashes: List<String>,
        val generatedRecipe: String,
    )

    private data class ColorEmojiLegacyGateCase(
        val gate: String,
        val familyIds: List<String>,
        val remainingEvidence: String,
    )

    private data class ColorGlyphCompositePlanFixtureCase(
        val caseId: String,
        val compositePlanJson: String?,
        val diagnostics: List<ColorGlyphDiagnostic>,
    )

    private fun colorGlyphPlanBundleJson(cases: List<ColorGlyphPlanFixtureCase>): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"color-glyph-plan\",\n")
        append("  \"ownerTickets\": [\"KFONT-M10-001\", \"KFONT-M10-002\", \"KFONT-M10-003\", \"KFONT-M10-004\"],\n")
        append("  \"cases\": [\n")
        append(
            cases.joinToString(",\n") { fixtureCase ->
                buildString {
                    append("    {\n")
                    append("      \"caseId\": ").append(jsonString(fixtureCase.caseId)).append(",\n")
                    append("      \"route\": ").append(jsonString(fixtureCase.route)).append(",\n")
                    append("      \"plan\": ")
                    append(
                        fixtureCase.planJson
                            ?.trimEnd()
                            ?.replace("\n", "\n      ")
                            ?: "null",
                    )
                    append(",\n")
                    append("      \"diagnostics\": ")
                    append(diagnosticsJson(fixtureCase.diagnostics, indent = "      "))
                    append("\n")
                    append("    }")
                }
            },
        )
        append("\n  ],\n")
        append(
            "  \"nonClaims\": [\"no-complete-target-support-claim\", " +
                "\"no-complete-colrv1-rendering-claim\", " +
                "\"no-complete-png-bitmap-glyph-routing-claim\", " +
                "\"no-complete-svg-in-opentype-rendering-claim\", " +
                "\"no-emoji-sequence-shaping-claim\", " +
                "\"no-gpu-color-glyph-support-claim\", " +
                "\"no-platform-color-font-fallback-claim\"]\n",
        )
        append("}\n")
    }

    private fun colorGlyphCompositePlanBundleJson(cases: List<ColorGlyphCompositePlanFixtureCase>): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"color-glyph-composite-plan\",\n")
        append("  \"ownerTickets\": [\"KFONT-M10-004\"],\n")
        append("  \"cases\": [\n")
        append(
            cases.joinToString(",\n") { fixtureCase ->
                buildString {
                    append("    {\n")
                    append("      \"caseId\": ").append(jsonString(fixtureCase.caseId)).append(",\n")
                    append("      \"compositePlan\": ")
                    append(
                        fixtureCase.compositePlanJson
                            ?.trimEnd()
                            ?.replace("\n", "\n      ")
                            ?: "null",
                    )
                    append(",\n")
                    append("      \"diagnostics\": ")
                    append(diagnosticsJson(fixtureCase.diagnostics, indent = "      "))
                    append("\n")
                    append("    }")
                }
            },
        )
        append("\n  ],\n")
        append(
            "  \"nonClaims\": [\"no-complete-target-support-claim\", " +
                "\"no-complete-colrv1-rendering-claim\", " +
                "\"no-gpu-color-glyph-support-claim\", " +
                "\"no-platform-color-font-fallback-claim\"]\n",
        )
        append("}\n")
    }

    private fun bitmapGlyphPlanBundleJson(cases: List<BitmapGlyphPlanFixtureCase>): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"bitmap-glyph-plan\",\n")
        append("  \"ownerTickets\": [\"KFONT-M10-006\"],\n")
        append("  \"cases\": [\n")
        append(
            cases.joinToString(",\n") { fixtureCase ->
                buildString {
                    append("    {\n")
                    append("      \"caseId\": ").append(jsonString(fixtureCase.caseId)).append(",\n")
                    append("      \"tableFamily\": ").append(jsonString(fixtureCase.tableFamily)).append(",\n")
                    append(
                        "      \"expectedGpuHandoffArtifactType\": " +
                            jsonString(fixtureCase.expectedGpuHandoffArtifactType) + ",\n",
                    )
                    append("      \"plan\": ")
                    append(
                        fixtureCase.planJson
                            ?.trimEnd()
                            ?.replace("\n", "\n      ")
                            ?: "null",
                    )
                    append(",\n")
                    append("      \"diagnostics\": ")
                    append(diagnosticsJson(fixtureCase.diagnostics, indent = "      "))
                    append("\n")
                    append("    }")
                }
            },
        )
        append("\n  ],\n")
        append(
            "  \"nonClaims\": [\"no-complete-target-support-claim\", " +
                "\"no-complete-png-bitmap-glyph-routing-claim\", " +
                "\"no-gpu-bitmap-glyph-route-claim\", " +
                "\"no-platform-bitmap-codec-claim\"]\n",
        )
        append("}\n")
    }

    private fun colrv1FixtureManifestJson(cases: List<COLRV1FixtureManifestCase>): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"colrv1-fixture-manifest\",\n")
        append("  \"ownerTickets\": [\"KFONT-M10-005\"],\n")
        append("  \"fixtureIds\": [\n")
        append(cases.joinToString(",\n") { case -> "    ${jsonString(case.fixtureId)}" })
        append("\n  ],\n")
        append("  \"cases\": [\n")
        append(
            cases.joinToString(",\n") { case ->
                buildString {
                    append("    {\n")
                    append("      \"fixtureId\": ").append(jsonString(case.fixtureId)).append(",\n")
                    append("      \"focus\": ").append(jsonString(case.focus)).append(",\n")
                    append("      \"fixtureKind\": ").append(jsonString(case.fixtureKind)).append(",\n")
                    append("      \"parserExpectation\": ").append(jsonString(case.parserExpectation)).append(",\n")
                    append("      \"expectedRoute\": ")
                    append(case.expectedRoute?.let(::jsonString) ?: "null")
                    append(",\n")
                    append("      \"expectedCaseIds\": ")
                    append(jsonStringArray(case.expectedCaseIds))
                    append(",\n")
                    append("      \"expectedDumpIds\": ")
                    append(jsonStringArray(case.expectedDumpIds))
                    append(",\n")
                    append("      \"expectedDiagnostics\": ")
                    append(embeddedJsonArray(case.expectedDiagnosticsJson, indent = "      "))
                    append(",\n")
                    append("      \"provenance\": {\n")
                    append("        \"generator\": \"ColorGlyphSurfaceTest\",\n")
                    append("        \"source\": \"synthetic-color-glyph-fixtures\",\n")
                    append("        \"notes\": ").append(jsonString(case.provenanceNotes)).append("\n")
                    append("      }\n")
                    append("    }")
                }
            },
        )
        append("\n  ],\n")
        append(
            "  \"nonClaims\": [\"no-complete-target-support-claim\", " +
                "\"no-complete-colrv1-rendering-claim\", " +
                "\"no-gpu-color-glyph-support-claim\", " +
                "\"no-platform-color-font-fallback-claim\"]\n",
        )
        append("}\n")
    }

    private fun colorEmojiFixtureManifestJson(
        families: List<ColorEmojiFixtureManifestFamilyCase>,
        legacyGates: List<ColorEmojiLegacyGateCase>,
    ): String = buildString {
        val componentDumpFiles = listOf(
            "reports/font/fixtures/expected/color/bitmap-glyph-plan.json",
            "reports/font/fixtures/expected/color/color-glyph-composite-plan.json",
            "reports/font/fixtures/expected/color/color-glyph-plan.json",
            "reports/font/fixtures/expected/color/color-svg-emoji-goldens.json",
            "reports/font/fixtures/expected/color/colrv1-fixture-manifest.json",
            "reports/font/fixtures/expected/color/colrv1-paint-graph.json",
            "reports/font/fixtures/expected/color/emoji-route-trace.json",
            "reports/font/fixtures/expected/color/svg-glyph-fixture-manifest.json",
            "reports/font/fixtures/expected/color/svg-glyph-plan.json",
        )

        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"color-emoji-fixture-manifest\",\n")
        append("  \"ownerTickets\": [\"KFONT-M10-010\"],\n")
        append("  \"fixtureFamilies\": ").append(jsonStringArray(families.map { family -> family.familyId })).append(",\n")
        append("  \"legacyGates\": ").append(jsonStringArray(legacyGates.map { gate -> gate.gate })).append(",\n")
        append("  \"componentDumps\": [\n")
        append(
            componentDumpFiles.joinToString(",\n") { relativePath ->
                val dump = readProjectFile(relativePath)
                val dumpId = jsonStringField(dump, "dumpId")
                buildString {
                    append("    {\n")
                    append("      \"dumpId\": ").append(jsonString(dumpId)).append(",\n")
                    append("      \"path\": ").append(jsonString(relativePath)).append(",\n")
                    append("      \"bodySha256\": ").append(jsonString(canonicalDumpBodySha256(dump))).append("\n")
                    append("    }")
                }
            },
        )
        append("\n  ],\n")
        append("  \"legacyGateCoverage\": [\n")
        append(
            legacyGates.joinToString(",\n") { gate ->
                buildString {
                    append("    {\n")
                    append("      \"gate\": ").append(jsonString(gate.gate)).append(",\n")
                    append("      \"familyIds\": ").append(jsonStringArray(gate.familyIds)).append(",\n")
                    append("      \"gpuEvidenceRequired\": true,\n")
                    append("      \"remainingEvidence\": ").append(jsonString(gate.remainingEvidence)).append("\n")
                    append("    }")
                }
            },
        )
        append("\n  ],\n")
        append("  \"families\": [\n")
        append(
            families.joinToString(",\n") { family ->
                buildString {
                    append("    {\n")
                    append("      \"familyId\": ").append(jsonString(family.familyId)).append(",\n")
                    append("      \"coveredTickets\": ").append(jsonStringArray(family.coveredTickets)).append(",\n")
                    append("      \"claimClassification\": ").append(jsonString(family.claimClassification)).append(",\n")
                    append("      \"fixtureIds\": ").append(jsonStringArray(family.fixtureIds)).append(",\n")
                    append("      \"expectedDumpFiles\": ").append(jsonStringArray(family.expectedDumpFiles)).append(",\n")
                    append("      \"expectedDiagnostics\": ").append(jsonStringArray(family.expectedDiagnostics)).append(",\n")
                    append("      \"legacyGates\": ").append(jsonStringArray(family.legacyGates)).append(",\n")
                    append("      \"gpuEvidenceRequired\": ").append(family.gpuEvidenceRequired).append(",\n")
                    append("      \"remainingGate\": ").append(jsonString(family.remainingGate)).append(",\n")
                    append("      \"provenance\": {\n")
                    append("        \"kind\": ").append(jsonString(family.provenanceKind)).append(",\n")
                    append("        \"source\": ").append(jsonString(family.provenanceSource)).append(",\n")
                    append("        \"licenseNotes\": ").append(jsonStringArray(family.licenseNotes)).append(",\n")
                    append("        \"sourceHashes\": ").append(jsonStringArray(family.sourceHashes)).append(",\n")
                    append("        \"generatedRecipe\": ").append(jsonString(family.generatedRecipe)).append("\n")
                    append("      }\n")
                    append("    }")
                }
            },
        )
        append("\n  ],\n")
        append("  \"rebaselinePolicy\": {\n")
        append("    \"ordinaryTestRuns\": \"must-not-overwrite-goldens\",\n")
        append(
            "    \"reviewRequirement\": " +
                jsonString(
                    "Review old/new diffs for the component dumps and this convergence manifest before updating expected outputs.",
                ) + ",\n",
        )
        append(
            "    \"reasonRequired\": " +
                jsonString("State whether behavior changed intentionally or the previous expectation was wrong.") + "\n",
        )
        append("  },\n")
        append(
            "  \"nonClaims\": [\"no-complete-target-support-claim\", " +
                "\"no-complete-colrv1-rendering-claim\", " +
                "\"no-complete-png-bitmap-glyph-routing-claim\", " +
                "\"no-complete-svg-in-opentype-rendering-claim\", " +
                "\"no-complete-emoji-sequence-shaping-claim\", " +
                "\"no-gpu-color-glyph-support-claim\", " +
                "\"no-platform-color-font-fallback-claim\"]\n",
        )
        append("}\n")
    }

    private fun diagnosticsJson(
        diagnostics: List<ColorGlyphDiagnostic>,
        indent: String,
    ): String = buildString {
        append("[")
        if (diagnostics.isNotEmpty()) {
            append("\n")
            append(diagnostics.joinToString(",\n") { diagnostic -> "$indent  ${diagnostic.toCanonicalJson()}" })
            append("\n")
            append(indent)
        }
        append("]")
    }

    private fun embeddedJsonArray(values: List<String>, indent: String): String = buildString {
        append("[")
        if (values.isNotEmpty()) {
            append("\n")
            append(values.joinToString(",\n") { value -> "$indent  ${normalizeEmbeddedJson(value)}" })
            append("\n")
            append(indent)
        }
        append("]")
    }

    private fun extractPlanCaseJson(bundleJson: String, caseId: String): String {
        return extractFixtureCaseFieldJson(bundleJson = bundleJson, caseId = caseId, fieldName = "plan")
    }

    private fun extractGraphCaseJson(bundleJson: String, caseId: String): String {
        return extractFixtureCaseFieldJson(bundleJson = bundleJson, caseId = caseId, fieldName = "graph")
    }

    private fun extractCompositePlanCaseJson(bundleJson: String, caseId: String): String {
        return extractFixtureCaseFieldJson(bundleJson = bundleJson, caseId = caseId, fieldName = "compositePlan")
    }

    private fun extractDiagnosticsCaseJson(bundleJson: String, caseId: String): String {
        return extractFixtureCaseFieldJson(bundleJson = bundleJson, caseId = caseId, fieldName = "diagnostics")
    }

    private fun extractFixtureCaseFieldJson(bundleJson: String, caseId: String, fieldName: String): String {
        val caseMarker = "\"caseId\": ${jsonString(caseId)}"
        val caseIndex = bundleJson.indexOf(caseMarker)
        check(caseIndex >= 0) { "Missing fixture case $caseId" }
        val fieldMarker = "\"$fieldName\":"
        val fieldIndex = bundleJson.indexOf(fieldMarker, startIndex = caseIndex)
        check(fieldIndex >= 0) { "Missing $fieldName field for fixture case $caseId" }
        var valueIndex = fieldIndex + fieldMarker.length
        while (valueIndex < bundleJson.length && bundleJson[valueIndex].isWhitespace()) {
            valueIndex += 1
        }
        return when {
            bundleJson.startsWith("null", startIndex = valueIndex) -> "null"
            bundleJson.getOrNull(valueIndex) == '{' -> normalizeEmbeddedJson(extractJsonObject(bundleJson, valueIndex))
            bundleJson.getOrNull(valueIndex) == '[' -> normalizeEmbeddedJson(extractJsonArray(bundleJson, valueIndex))
            else -> error("Unsupported $fieldName payload for fixture case $caseId")
        }
    }

    private fun extractJsonObject(text: String, startIndex: Int): String {
        var index = startIndex
        var depth = 0
        var inString = false
        var escaping = false
        while (index < text.length) {
            val character = text[index]
            when {
                escaping -> escaping = false
                character == '\\' && inString -> escaping = true
                character == '"' -> inString = !inString
                !inString && character == '{' -> depth += 1
                !inString && character == '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(startIndex, index + 1)
                    }
                }
            }
            index += 1
        }
        error("Unterminated JSON object starting at $startIndex")
    }

    private fun extractJsonArray(text: String, startIndex: Int): String {
        var index = startIndex
        var depth = 0
        var inString = false
        var escaping = false
        while (index < text.length) {
            val character = text[index]
            when {
                escaping -> escaping = false
                character == '\\' && inString -> escaping = true
                character == '"' -> inString = !inString
                !inString && character == '[' -> depth += 1
                !inString && character == ']' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(startIndex, index + 1)
                    }
                }
            }
            index += 1
        }
        error("Unterminated JSON array starting at $startIndex")
    }

    private fun normalizeEmbeddedJson(json: String): String {
        val lines = json.lines()
        if (lines.size <= 1) return json
        val commonIndent = lines
            .drop(1)
            .filter { line -> line.isNotBlank() }
            .minOfOrNull { line -> line.indexOfFirst { character -> !character.isWhitespace() }.coerceAtLeast(0) }
            ?: 0
        return lines.mapIndexed { index, line ->
            if (index == 0) {
                line
            } else {
                line.drop(minOf(commonIndent, line.length))
            }
        }.joinToString("\n")
    }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }

    private fun jsonStringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { value -> jsonString(value) }

    private fun assertEvidenceDumpClean(dump: String) {
        val forbiddenTokens = listOf(
            "Skia",
            "HarfBuzz",
            "FreeType",
            "Fontations",
            "AWT",
            "JNI",
            "CoreText",
            "DirectWrite",
            "fontconfig",
            "gpuHandle",
            "nativeHandle",
            "/Users/",
            "tmp/",
        )
        forbiddenTokens.forEach { token ->
            assertTrue(!dump.contains(token), "Dump must not contain forbidden token $token: $dump")
        }
        assertTrue(
            !Regex("""\bSk[A-Z][A-Za-z0-9_]*\b""").containsMatchIn(dump),
            "Dump must not contain Sk-prefixed API symbols: $dump",
        )
        assertTrue(!Regex("@[0-9a-fA-F]{4,}").containsMatchIn(dump), "Dump must not contain object identity: $dump")
    }

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

    private data class SVGGlyphPlanFixtureCase(
        val caseId: String,
        val expectedGpuHandoffArtifactType: String,
        val planJson: String?,
        val diagnostics: List<ColorGlyphDiagnostic>,
    )

    private fun svgGlyphPlanBundleJson(cases: List<SVGGlyphPlanFixtureCase>): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"svg-glyph-plan\",\n")
        append("  \"ownerTickets\": [\"KFONT-M10-007\"],\n")
        append("  \"cases\": [\n")
        append(
            cases.joinToString(",\n") { fixtureCase ->
                buildString {
                    append("    {\n")
                    append("      \"caseId\": ").append(jsonString(fixtureCase.caseId)).append(",\n")
                    append(
                        "      \"expectedGpuHandoffArtifactType\": " +
                            jsonString(fixtureCase.expectedGpuHandoffArtifactType) + ",\n",
                    )
                    append("      \"plan\": ")
                    append(
                        fixtureCase.planJson
                            ?.trimEnd()
                            ?.replace("\n", "\n      ")
                            ?: "null",
                    )
                    append(",\n")
                    append("      \"diagnostics\": ")
                    append(diagnosticsJson(fixtureCase.diagnostics, indent = "      "))
                    append("\n")
                    append("    }")
                }
            },
        )
        append("\n  ],\n")
        append(
            "  \"nonClaims\": [\"no-complete-target-support-claim\", " +
                "\"no-complete-svg-in-opentype-rendering-claim\", " +
                "\"no-gpu-svg-glyph-route-claim\", " +
                "\"no-native-svg-renderer-claim\"]\n",
        )
        append("}\n")
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

    private data class SVGGlyphFixtureManifestCase(
        val fixtureId: String,
        val glyphId: Int,
        val focus: String,
        val sourceDocumentSha256: String,
        val expectedRoute: String,
        val expectedBoundsSha256: String?,
        val expectedDiagnostics: List<String>,
        val expectedDumpFiles: List<String>,
        val provenanceKind: String,
        val provenanceSource: String,
    )

    private fun svgFixtureManifestCase(
        fixtureId: String,
        focus: String,
        plan: SVGGlyphPlan,
        sourceText: String,
    ): SVGGlyphFixtureManifestCase =
        SVGGlyphFixtureManifestCase(
            fixtureId = fixtureId,
            glyphId = plan.glyphId,
            focus = focus,
            sourceDocumentSha256 = sha256Utf8(sourceText),
            expectedRoute = "svg-plan",
            expectedBoundsSha256 = sha256Utf8(jsonObjectField(plan.toCanonicalJson(), "bounds")),
            expectedDiagnostics = plan.diagnostics.map { diagnostic -> diagnostic.code },
            expectedDumpFiles = listOf("svg-glyph-plan.json"),
            provenanceKind = "generated-test-data",
            provenanceSource = "ColorGlyphSurfaceTest.svgGlyphFixtureManifestCapturesBoundsRefusalsAndProvenanceDeterministically",
        )

    private fun svgRefusalManifestCase(
        fixtureId: String,
        glyphId: Int,
        focus: String,
        sourceText: String,
        diagnostics: List<ColorGlyphDiagnostic>,
    ): SVGGlyphFixtureManifestCase =
        SVGGlyphFixtureManifestCase(
            fixtureId = fixtureId,
            glyphId = glyphId,
            focus = focus,
            sourceDocumentSha256 = sha256Utf8(sourceText),
            expectedRoute = "refusal",
            expectedBoundsSha256 = null,
            expectedDiagnostics = diagnostics.map { diagnostic -> diagnostic.code },
            expectedDumpFiles = listOf("svg-glyph-plan.json"),
            provenanceKind = "route-diagnostic",
            provenanceSource = "ColorGlyphSurfaceTest.svgGlyphFixtureManifestCapturesBoundsRefusalsAndProvenanceDeterministically",
        )

    private fun svgGlyphFixtureManifestJson(cases: List<SVGGlyphFixtureManifestCase>): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"svg-glyph-fixture-manifest\",\n")
        append("  \"ownerTickets\": [\"KFONT-M10-008\"],\n")
        append("  \"fixtureFamily\": \"svg-glyphs\",\n")
        append("  \"cases\": [\n")
        append(
            cases.joinToString(",\n") { fixtureCase ->
                buildString {
                    append("    {\n")
                    append("      \"fixtureId\": ").append(jsonString(fixtureCase.fixtureId)).append(",\n")
                    append("      \"glyphId\": ").append(fixtureCase.glyphId).append(",\n")
                    append("      \"focus\": ").append(jsonString(fixtureCase.focus)).append(",\n")
                    append(
                        "      \"sourceDocumentSha256\": " +
                            jsonString(fixtureCase.sourceDocumentSha256) + ",\n",
                    )
                    append("      \"expectedRoute\": ").append(jsonString(fixtureCase.expectedRoute)).append(",\n")
                    append("      \"expectedBoundsSha256\": ")
                    append(fixtureCase.expectedBoundsSha256?.let(::jsonString) ?: "null")
                    append(",\n")
                    append("      \"expectedDiagnostics\": ")
                    append(
                        fixtureCase.expectedDiagnostics.joinToString(
                            prefix = "[",
                            postfix = "]",
                        ) { code -> jsonString(code) },
                    )
                    append(",\n")
                    append("      \"expectedDumpFiles\": ")
                    append(
                        fixtureCase.expectedDumpFiles.joinToString(
                            prefix = "[",
                            postfix = "]",
                        ) { dumpFile -> jsonString(dumpFile) },
                    )
                    append(",\n")
                    append("      \"provenance\": {\n")
                    append("        \"kind\": ").append(jsonString(fixtureCase.provenanceKind)).append(",\n")
                    append("        \"source\": ").append(jsonString(fixtureCase.provenanceSource)).append("\n")
                    append("      }\n")
                    append("    }")
                }
            },
        )
        append("\n  ],\n")
        append(
            "  \"nonClaims\": [\"no-complete-target-support-claim\", " +
                "\"no-complete-svg-in-opentype-rendering-claim\", " +
                "\"no-gpu-svg-glyph-route-claim\", " +
                "\"no-native-svg-renderer-claim\"]\n",
        )
        append("}\n")
    }

    private fun jsonObjectField(json: String, field: String): String {
        val fieldToken = "\"$field\": {"
        val start = json.indexOf(fieldToken)
        require(start >= 0) { "Missing JSON object field $field" }
        val objectStart = json.indexOf('{', start)
        var depth = 0
        for (index in objectStart until json.length) {
            when (json[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return json.substring(objectStart, index + 1)
                    }
                }
            }
        }
        error("Unterminated JSON object field $field")
    }

    private fun jsonArrayField(json: String, field: String): String {
        val fieldToken = "\"$field\": ["
        val start = json.indexOf(fieldToken)
        require(start >= 0) { "Missing JSON array field $field" }
        val arrayStart = json.indexOf('[', start)
        return extractJsonArray(json, arrayStart)
    }

    private fun assertJsonPattern(json: String, pattern: String) {
        assertTrue(
            Regex(pattern, RegexOption.DOT_MATCHES_ALL).containsMatchIn(json),
            "Missing JSON pattern $pattern in: $json",
        )
    }

    private fun sha256Utf8(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
