package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.*
import org.graphiks.kalligraphie.layout.openLayoutHandle
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import java.util.Base64
import kotlin.test.*

class ColrV1TransformCompositeTest {
    @Test
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UNCHECKED_CAST")
    fun certifiedCompositeCannotBeChangedByKotlinOrJavaConsumers() {
        ColrV1Fixture().use { fixture ->
            val line = assertIs<EditableLineResult.Success>(fixture.layout(colrSnapshot(0xF0300))).line
            val certificate = assertNotNull(line.positionedGlyphRuns.single().glyphs.single().materializationCertificate)
            assertEquals(GlyphId(84), certificate.glyphId)
            assertEquals(GlyphMaterializationRoute.PAINT_GRAPH, certificate.route)
            val handle = colrSuccess(line.openLayoutHandle(fixture.resolver))
            try {
                val renderer = colrSuccess(handle.retainFontAsset(certificate))
                try {
                    val paint = assertIs<GlyphRepresentation.Paint>(colrSuccess(renderer.resolveGlyph(FontGlyphRequest(84)))).paint
                    val composite = assertIs<GlyphPaintNode.Composite>(paint.nodes[paint.rootNode])
                    assertFailsWith<UnsupportedOperationException> {
                        (composite.children as MutableList<Int>)[0] = paint.rootNode
                    }
                    assertFailsWith<UnsupportedOperationException> {
                        (composite.children as java.util.List<Int>).set(1, Int.MAX_VALUE)
                    }
                    assertEquals(listOf(composite.backdrop, composite.source), composite.children)
                    assertEquals(3, assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[composite.children[0]]).outline.glyphId)
                    assertEquals(GlyphAffineTransform(0.5, 0.0, 0.0, 1.5, 250.0, -250.0),
                        assertIs<GlyphPaintNode.Transform>(paint.nodes[composite.children[1]]).matrix)
                    val profile = assertIs<PaintGraphProfile>(certificate.assetKey.representationProfile)
                    assertTrue(profile.accepts(paint))
                    assertTrue(profile.accepts(GlyphPaintIR(paint.schemaVersion, paint.rootNode, paint.nodes, paint.clipBounds)))
                    assertTrue(certificate.matches(renderer.key, GlyphId(84)))
                } finally { renderer.close() }
            } finally { handle.close() }
        }
    }

    @Test
    fun normalizesTheAuditedScaleAffineAndTranslationWithSourceBackdropRoles() {
        ColrV1Fixture().use { fixture ->
            for ((codePoint, glyph, matrix) in listOf(
                Triple(0xF0300, 84, GlyphAffineTransform(0.5, 0.0, 0.0, 1.5, 250.0, -250.0)),
                Triple(0xF0301, 85, GlyphAffineTransform(1.5, 0.0, 0.0, 1.5, -250.0, -250.0)),
                Triple(0xF0302, 86, GlyphAffineTransform(0.5, 0.0, 0.0, 1.5, 0.0, 0.0)),
                Triple(0xF0303, 87, GlyphAffineTransform(1.5, 0.0, 0.0, 1.5, 0.0, 0.0)),
                Triple(0xF0800, 109, GlyphAffineTransform(1.0, 0.0, 0.0, 1.0, 125.0, 125.0)),
                Triple(0xF0901, 114, GlyphAffineTransform(1.0, 0.0, 0.0, 1.0, 0.0, 100.0)),
            )) {
                val paint = fixture.paint(codePoint, glyph)
                val composite = assertIs<GlyphPaintNode.Composite>(paint.nodes[paint.rootNode])
                assertEquals(GlyphPaintCompositionMode.DESTINATION_OVER, composite.mode)
                assertEquals(matrix, assertIs<GlyphPaintNode.Transform>(paint.nodes[composite.source]).matrix)
                assertEquals(3, assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[composite.backdrop]).outline.glyphId)
            }
        }
    }

    @Test
    fun normalizesAuditedRotationsAndSkewsInFontCoordinates() {
        // Numeric oracles from the fontTools-decoded F2DOT14 angles and independent trigonometry.
        val cases = listOf(
            Triple(0xF0600, 99, listOf(0.9848151513672891, 0.17360621428227538, -0.17360621428227538, 0.9848151513672891, 0.0, 0.0)),
            Triple(0xF0601, 100, listOf(0.9848151513672891, -0.17360621428227538, 0.17360621428227538, 0.9848151513672891, -158.42136564956448, 188.79106291498636)),
            Triple(0xF0700, 103, listOf(1.0, 0.0, -0.4664114141625897, 1.0, 0.0, 0.0)),
            Triple(0xF0701, 104, listOf(1.0, 0.0, -0.4664114141625897, 1.0, 233.20570708129483, 0.0)),
            Triple(0xF0702, 105, listOf(1.0, 0.2678806887853504, 0.0, 1.0, 0.0, 0.0)),
        )
        ColrV1Fixture().use { fixture ->
            cases.forEach { (codePoint, glyph, expected) ->
                val paint = fixture.paint(codePoint, glyph)
                val composite = assertIs<GlyphPaintNode.Composite>(paint.nodes[paint.rootNode])
                val matrix = assertIs<GlyphPaintNode.Transform>(paint.nodes[composite.source]).matrix
                listOf(matrix.xx, matrix.yx, matrix.xy, matrix.yy, matrix.dx, matrix.dy).forEachIndexed { index, actual ->
                    assertEquals(expected[index], actual, absoluteTolerance = 1e-12)
                }
            }
        }
    }

    @Test
    fun preservesAllTwentyEightAuditedCompositeOperationsAndTheirPaintRoles() {
        val modes = listOf(
            "CLEAR", "SOURCE", "DESTINATION", "SOURCE_OVER", "DESTINATION_OVER", "SOURCE_IN",
            "DESTINATION_IN", "SOURCE_OUT", "DESTINATION_OUT", "SOURCE_ATOP", "DESTINATION_ATOP",
            "XOR", "PLUS", "SCREEN", "OVERLAY", "DARKEN", "LIGHTEN", "COLOR_DODGE", "COLOR_BURN",
            "HARD_LIGHT", "SOFT_LIGHT", "DIFFERENCE", "EXCLUSION", "MULTIPLY", "HSL_HUE",
            "HSL_SATURATION", "HSL_COLOR", "HSL_LUMINOSITY",
        )
        ColrV1Fixture().use { fixture ->
            modes.forEachIndexed { index, mode ->
                val paint = fixture.paint(0xF0A00 + index, 120 + index)
                val composite = paint.nodes.filterIsInstance<GlyphPaintNode.Composite>().single()
                assertEquals(mode, composite.mode.name)
                val source = assertIs<GlyphPaintNode.Transform>(paint.nodes[composite.source])
                val backdrop = assertIs<GlyphPaintNode.Transform>(paint.nodes[composite.backdrop])
                assertEquals(GlyphAffineTransform(0.5, 0.0, 0.0, 0.5, 333.5, 166.5), source.matrix)
                assertEquals(GlyphAffineTransform(0.5, 0.0, 0.0, 0.5, 166.5, 333.5), backdrop.matrix)
                fun color(transform: GlyphPaintNode.Transform): GlyphColor {
                    val clip = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[transform.paint])
                    return assertIs<GlyphPaintNode.Solid>(paint.nodes[clip.paint]).color
                }
                assertEquals(GlyphColor(104, 199, 232), color(source))
                assertEquals(GlyphColor(255, 220, 1), color(backdrop))
            }
        }
    }

    @Test
    fun preservesTheAuditedRootClipAndForegroundSentinel() {
        ColrV1Fixture(variant = FontRenderVariantSnapshot(foregroundColor = GlyphColor(17, 34, 51, 192))).use { fixture ->
            assertEquals(DesignBounds(0, 500, 500, 1000), fixture.paint(0xF0C00, 156).clipBounds)
            val gradient = fixture.paint(0xF0B00, 148).nodes.filterIsInstance<GlyphPaintNode.LinearGradient>().single()
            assertEquals(GlyphColor(17, 34, 51, 192), gradient.colorLine.colorStops[1].color)
            assertEquals(1.0, gradient.colorLine.colorStops[1].opacity)
        }
    }
}

internal fun colrV1Profile(
    modes: List<GlyphPaintCompositionMode> = GlyphPaintCompositionMode.entries.toList(),
    maxTransforms: Int = 128,
): PaintGraphProfile = PaintGraphProfile(
    acceptedNodeKinds = GlyphPaintNodeKind.entries.toList(),
    acceptedCompositionModes = modes,
    acceptedGradientExtendModes = GlyphPaintExtendMode.entries.toList(),
    limits = PaintGraphLimits(maxNodes = 256, maxReferences = 512, maxDepth = 64,
        maxGradients = 64, maxColorStops = 256, maxClips = 128, maxTransforms = maxTransforms,
        maxComposites = 64, maxPaintVisits = 2048),
    outlineProfile = OutlineProfile(maxBytes = 1_000_000, maxContours = 1024, maxPoints = 65_536,
        maxCompositeDepth = 16, maxCompositeComponents = 256),
    schemaVersion = 2,
)

internal fun <T> colrSuccess(result: FontOperationResult<T>): T =
    assertIs<FontOperationResult.Success<T>>(result, result.toString()).value

internal class ColrV1Fixture(
    val requirements: FontAccessRequirementsSnapshot = FontAccessRequirementsSnapshot.renderable(listOf(colrV1Profile())),
    val variant: FontRenderVariantSnapshot = FontRenderVariantSnapshot.default,
    mutateSource: (ByteArray) -> Unit = {},
) : AutoCloseable {
    private val bytes = checkNotNull(javaClass.getResourceAsStream("/fonts/skia-colr-v1/test_glyphs-glyf_colr_1.ttf.b64"))
        .use { Base64.getMimeDecoder().decode(it.readBytes()) }.also(mutateSource)
    val catalog = colrSuccess(Kalligraphie.embedded(bytes, FontSourceProvenance("Skia COLR v1 test glyphs")))
    val resolver = colrSuccess(catalog.openAssetResolver())
    val session = colrSuccess(JvmEditableLineLayoutSession.open())
    val font = colrSuccess(colrSuccess(catalog.resolveFace(catalog.faces.single().id, requirements))
        .instantiate(FontInstanceDescriptor(LayoutUnit(1000f))))

    fun asset(): FontRenderAssetHandle = colrSuccess(font.acquireRenderAsset(resolver, variant, requirements))

    fun paint(codePoint: Int, glyph: Int): GlyphPaintIR {
        assertEquals(GlyphId(glyph), colrSuccess(font.resolveGlyph(codePoint)).glyphId)
        val asset = asset()
        try {
            return assertIs<GlyphRepresentation.Paint>(colrSuccess(asset.resolveGlyph(FontGlyphRequest(glyph)))).paint
        } finally { asset.close() }
    }

    fun layout(snapshot: TextSnapshot): EditableLineResult = session.layout(JvmEditableLineFacadeRequest(
        snapshot = snapshot, font = font, baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en", featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy, features = emptyList(),
        verticalMetrics = LineVerticalMetrics(LayoutUnit(1000f), LayoutUnit(250f)),
        materialization = EditableLineMaterialization.Renderable(resolver, variant, requirements),
    ))

    override fun close() { session.close(); resolver.close() }
}

internal fun colrSnapshot(vararg codePoints: Int): TextSnapshot = Kalligraphie.decodeUtf8(
    TextVersion.create(), listOf(TextSlice.Utf8(codePoints.joinToString("") { String(Character.toChars(it)) }.encodeToByteArray())),
).snapshot
