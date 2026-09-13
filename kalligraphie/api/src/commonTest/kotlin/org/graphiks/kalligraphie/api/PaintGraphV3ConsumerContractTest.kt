package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaintGraphV3ConsumerContractTest {
    @Test
    fun schemaThreeConsumerMustAdvertiseTheAlphaInterpolationModeItRenders() {
        val paint = clippedGradient(
            interpolationSpace = GlyphPaintInterpolationSpace.SRGB,
            alphaInterpolationMode = GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED,
        )

        assertTrue(
            schema3Profile(
                interpolationSpaces = listOf(GlyphPaintInterpolationSpace.SRGB),
                alphaInterpolationModes = listOf(GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED),
            ).accepts(paint),
        )
        assertFalse(
            schema3Profile(
                interpolationSpaces = listOf(GlyphPaintInterpolationSpace.SRGB),
                alphaInterpolationModes = listOf(GlyphPaintAlphaInterpolationMode.PREMULTIPLIED),
            ).accepts(paint),
        )
    }

    @Test
    fun schemaThreeConsumerMustAdvertiseTheGradientInterpolationSpaceItRenders() {
        val paint = clippedGradient(GlyphPaintInterpolationSpace.SRGB)

        assertTrue(schema3Profile(interpolationSpaces = listOf(GlyphPaintInterpolationSpace.SRGB)).accepts(paint))
        assertFalse(schema3Profile(interpolationSpaces = listOf(GlyphPaintInterpolationSpace.LINEAR_SRGB)).accepts(paint))
    }

    @Test
    fun pathClipBoundsGradientPaintAndConsumesBothPathAndClipBudgets() {
        val gradient = GlyphPaintIR(
            schemaVersion = 3,
            rootNode = 0,
            nodes = listOf(linearGradient(GlyphPaintInterpolationSpace.SRGB)),
        )
        val clippedGradient = clippedGradient(GlyphPaintInterpolationSpace.SRGB)

        assertFalse(schema3Profile().accepts(gradient))
        assertTrue(schema3Profile().accepts(clippedGradient))
        assertFalse(schema3Profile(nodeKinds = listOf(GlyphPaintNodeKind.LINEAR_GRADIENT)).accepts(clippedGradient))
        assertFalse(schema3Profile(limits = limits(maxPaths = 0)).accepts(clippedGradient))
        assertFalse(schema3Profile(limits = limits(maxClips = 0)).accepts(clippedGradient))
        assertFalse(schema3Profile(outlineProfile = outlineProfile(maxPoints = 3)).accepts(clippedGradient))
    }

    @Test
    fun schemaTwoGraphsCannotCarryUnpremultipliedGradientAlpha() {
        val colorLine = GlyphPaintColorLine(
            extendMode = GlyphPaintExtendMode.PAD,
            colorStops = listOf(
                GlyphPaintColorStop(0.0, GlyphColor(12, 34, 56), 0.25),
                GlyphPaintColorStop(1.0, GlyphColor(210, 180, 140), 0.75),
            ),
            interpolationSpace = GlyphPaintInterpolationSpace.LINEAR_SRGB,
            alphaInterpolationMode = GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED,
        )
        val gradients = listOf(
            GlyphPaintNode.LinearGradient(
                colorLine,
                GlyphPaintPoint(0.0, 0.0),
                GlyphPaintPoint(100.0, 0.0),
                GlyphPaintPoint(0.0, 100.0),
            ),
            GlyphPaintNode.RadialGradient(
                colorLine,
                GlyphPaintPoint(0.0, 0.0),
                0.0,
                GlyphPaintPoint(100.0, 100.0),
                50.0,
            ),
            GlyphPaintNode.SweepGradient(
                colorLine,
                GlyphPaintPoint(50.0, 50.0),
                0.0,
                360.0,
            ),
        )

        for (gradient in gradients) {
            assertFailsWith<IllegalArgumentException> {
                GlyphPaintIR(
                    schemaVersion = 2,
                    rootNode = 0,
                    nodes = listOf(gradient),
                    clipBounds = DesignBounds(0, 0, 100, 100),
                )
            }
        }
    }

    @Test
    fun earlierSchemasCannotClaimOrCarrySchemaThreePaintSemantics() {
        assertFailsWith<IllegalArgumentException> {
            PaintGraphProfile(
                acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
                acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
                limits = limits(),
                outlineProfile = outlineProfile(),
                schemaVersion = 1,
                acceptedGradientAlphaInterpolationModes =
                    listOf(GlyphPaintAlphaInterpolationMode.PREMULTIPLIED),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PaintGraphProfile(
                acceptedNodeKinds = listOf(GlyphPaintNodeKind.LINEAR_GRADIENT),
                acceptedCompositionModes = emptyList(),
                limits = limits(),
                outlineProfile = outlineProfile(),
                schemaVersion = 2,
                acceptedGradientExtendModes = listOf(GlyphPaintExtendMode.PAD),
                acceptedGradientInterpolationSpaces = listOf(GlyphPaintInterpolationSpace.LINEAR_SRGB),
                acceptedGradientAlphaInterpolationModes =
                    listOf(GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PaintGraphProfile(
                acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
                acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
                limits = limits(),
                outlineProfile = outlineProfile(),
                schemaVersion = 1,
                acceptedGradientInterpolationSpaces = listOf(GlyphPaintInterpolationSpace.LINEAR_SRGB),
                acceptedGradientAlphaInterpolationModes = listOf(GlyphPaintAlphaInterpolationMode.PREMULTIPLIED),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PaintGraphProfile(
                acceptedNodeKinds = listOf(GlyphPaintNodeKind.LINEAR_GRADIENT),
                acceptedCompositionModes = emptyList(),
                limits = limits(),
                outlineProfile = outlineProfile(),
                schemaVersion = 2,
                acceptedGradientExtendModes = listOf(GlyphPaintExtendMode.PAD),
                acceptedGradientInterpolationSpaces = listOf(GlyphPaintInterpolationSpace.SRGB),
                acceptedGradientAlphaInterpolationModes = listOf(GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PaintGraphProfile(
                acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH_CLIP),
                acceptedCompositionModes = emptyList(),
                limits = limits(),
                outlineProfile = outlineProfile(),
                schemaVersion = 2,
                acceptedGradientInterpolationSpaces = listOf(GlyphPaintInterpolationSpace.LINEAR_SRGB),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GlyphPaintIR(
                schemaVersion = 2,
                rootNode = 1,
                nodes = listOf(
                    linearGradient(GlyphPaintInterpolationSpace.LINEAR_SRGB),
                    GlyphPaintNode.PathClip(rectanglePath(), paint = 0),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GlyphPaintIR(
                schemaVersion = 2,
                rootNode = 0,
                nodes = listOf(linearGradient(GlyphPaintInterpolationSpace.SRGB)),
                clipBounds = DesignBounds(100, 200, 500, 800),
            )
        }
    }

    @Test
    fun twoArgumentColorLineRetainsLinearSrgbSemanticsForSchemaTwo() {
        val colorLine = GlyphPaintColorLine(
            GlyphPaintExtendMode.PAD,
            listOf(
                GlyphPaintColorStop(0.0, GlyphColor(12, 34, 56), 0.25),
                GlyphPaintColorStop(1.0, GlyphColor(210, 180, 140), 0.75),
            ),
        )
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 0,
            nodes = listOf(
                GlyphPaintNode.LinearGradient(
                    colorLine,
                    GlyphPaintPoint(100.0, 200.0),
                    GlyphPaintPoint(500.0, 200.0),
                    GlyphPaintPoint(100.0, 800.0),
                ),
            ),
            clipBounds = DesignBounds(100, 200, 500, 800),
        )
        val profile = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.LINEAR_GRADIENT),
            acceptedCompositionModes = emptyList(),
            limits = limits(maxNodes = 1, maxReferences = 0, maxDepth = 1, maxPaths = 0, maxClips = 0),
            outlineProfile = outlineProfile(),
            schemaVersion = 2,
            acceptedGradientExtendModes = listOf(GlyphPaintExtendMode.PAD),
            acceptedGradientInterpolationSpaces = listOf(GlyphPaintInterpolationSpace.LINEAR_SRGB),
        )

        assertEquals(GlyphPaintInterpolationSpace.LINEAR_SRGB, colorLine.interpolationSpace)
        assertEquals(GlyphPaintAlphaInterpolationMode.PREMULTIPLIED, colorLine.alphaInterpolationMode)
        assertTrue(profile.accepts(paint))
    }

    @Test
    fun callerMutationCannotChangeWhichAlphaGraphsAProfileAccepts() {
        val callerOwnedAlphaModes = mutableListOf(GlyphPaintAlphaInterpolationMode.PREMULTIPLIED)
        val profile = schema3Profile(alphaInterpolationModes = callerOwnedAlphaModes)
        val premultipliedPaint = clippedGradient(
            GlyphPaintInterpolationSpace.SRGB,
            GlyphPaintAlphaInterpolationMode.PREMULTIPLIED,
        )
        val unpremultipliedPaint = clippedGradient(
            GlyphPaintInterpolationSpace.SRGB,
            GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED,
        )

        assertTrue(profile.accepts(premultipliedPaint))
        assertFalse(profile.accepts(unpremultipliedPaint))

        callerOwnedAlphaModes.clear()
        callerOwnedAlphaModes += GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED

        assertTrue(profile.accepts(premultipliedPaint))
        assertFalse(profile.accepts(unpremultipliedPaint))
    }

    private fun clippedGradient(
        interpolationSpace: GlyphPaintInterpolationSpace,
        alphaInterpolationMode: GlyphPaintAlphaInterpolationMode = GlyphPaintAlphaInterpolationMode.PREMULTIPLIED,
    ): GlyphPaintIR = GlyphPaintIR(
        schemaVersion = 3,
        rootNode = 1,
        nodes = listOf(
            linearGradient(interpolationSpace, alphaInterpolationMode),
            GlyphPaintNode.PathClip(rectanglePath(), paint = 0),
        ),
    )

    private fun linearGradient(
        interpolationSpace: GlyphPaintInterpolationSpace,
        alphaInterpolationMode: GlyphPaintAlphaInterpolationMode = GlyphPaintAlphaInterpolationMode.PREMULTIPLIED,
    ): GlyphPaintNode.LinearGradient =
        GlyphPaintNode.LinearGradient(
            GlyphPaintColorLine(
                GlyphPaintExtendMode.PAD,
                listOf(
                    GlyphPaintColorStop(0.0, GlyphColor(12, 34, 56), 0.25),
                    GlyphPaintColorStop(1.0, GlyphColor(210, 180, 140), 0.75),
                ),
                interpolationSpace,
                alphaInterpolationMode,
            ),
            GlyphPaintPoint(100.0, 200.0),
            GlyphPaintPoint(500.0, 200.0),
            GlyphPaintPoint(100.0, 800.0),
        )

    private fun rectanglePath(): GlyphPaintPath = GlyphPaintPath(
        listOf(
            GlyphPaintPathCommand.MoveTo(100.0, 200.0),
            GlyphPaintPathCommand.LineTo(500.0, 200.0),
            GlyphPaintPathCommand.LineTo(500.0, 800.0),
            GlyphPaintPathCommand.LineTo(100.0, 800.0),
            GlyphPaintPathCommand.Close,
        ),
    )

    private fun schema3Profile(
        interpolationSpaces: List<GlyphPaintInterpolationSpace> = listOf(GlyphPaintInterpolationSpace.SRGB),
        alphaInterpolationModes: List<GlyphPaintAlphaInterpolationMode> =
            listOf(GlyphPaintAlphaInterpolationMode.PREMULTIPLIED),
        nodeKinds: List<GlyphPaintNodeKind> = listOf(
            GlyphPaintNodeKind.LINEAR_GRADIENT,
            GlyphPaintNodeKind.PATH_CLIP,
        ),
        limits: PaintGraphLimits = limits(),
        outlineProfile: OutlineProfile = outlineProfile(),
    ): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = nodeKinds,
        acceptedCompositionModes = emptyList(),
        limits = limits,
        outlineProfile = outlineProfile,
        schemaVersion = 3,
        acceptedGradientExtendModes = listOf(GlyphPaintExtendMode.PAD),
        acceptedGradientInterpolationSpaces = interpolationSpaces,
        acceptedGradientAlphaInterpolationModes = alphaInterpolationModes,
    )

    private fun limits(
        maxNodes: Int = 2,
        maxReferences: Int = 1,
        maxDepth: Int = 2,
        maxPaths: Int = 1,
        maxClips: Int = 1,
    ): PaintGraphLimits = PaintGraphLimits(
        maxNodes = maxNodes,
        maxReferences = maxReferences,
        maxDepth = maxDepth,
        maxPaths = maxPaths,
        maxGradients = 1,
        maxColorStops = 2,
        maxClips = maxClips,
        maxPaintVisits = maxNodes,
    )

    private fun outlineProfile(maxPoints: Int = 4): OutlineProfile = OutlineProfile(
        maxBytes = 1_024,
        maxContours = 1,
        maxPoints = maxPoints,
        maxCompositeDepth = 1,
        maxCompositeComponents = 1,
    )
}
