package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PaintGraphV2ConsumerContractTest {
    @Test
    fun schemaOneGraphsRejectEmptyGroupsAtTheRootAndBelowIt() {
        val noPaint = GlyphPaintNode.Group(emptyList())
        assertFailsWith<IllegalArgumentException> { GlyphPaintIR(1, 0, listOf(noPaint)) }
        assertFailsWith<IllegalArgumentException> {
            GlyphPaintIR(1, 1, listOf(noPaint, GlyphPaintNode.Group(listOf(0))))
        }
    }

    @Test
    fun schemaTwoEmptyGroupsAreBoundedWithoutAClipAndStillRequireGroupCapability() {
        val noPaint = GlyphPaintIR(2, 0, listOf(GlyphPaintNode.Group(emptyList())))
        assertTrue(schema2Profile().accepts(noPaint))
        assertFalse(PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID),
            acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
            limits = schema2Limits(), outlineProfile = outlineProfile(), schemaVersion = 2,
        ).accepts(noPaint))
    }

    @Test
    fun compositeCertificationFollowsTheOpenTypeBoundednessTableForEveryMode() {
        // Literal rows: neither bounded, source only, backdrop only, both bounded.
        val rows = listOf(
            "CLEAR" to listOf(true, true, true, true),
            "SOURCE" to listOf(false, true, false, true),
            "DESTINATION" to listOf(false, false, true, true),
            "SOURCE_OVER" to listOf(false, false, false, true),
            "DESTINATION_OVER" to listOf(false, false, false, true),
            "SOURCE_IN" to listOf(false, true, true, true),
            "DESTINATION_IN" to listOf(false, true, true, true),
            "SOURCE_OUT" to listOf(false, true, false, true),
            "DESTINATION_OUT" to listOf(false, false, true, true),
            "SOURCE_ATOP" to listOf(false, false, false, true),
            "DESTINATION_ATOP" to listOf(false, false, false, true),
            "XOR" to listOf(false, false, false, true),
            "PLUS" to listOf(false, false, false, true),
            "SCREEN" to listOf(false, false, false, true),
            "OVERLAY" to listOf(false, false, false, true),
            "DARKEN" to listOf(false, false, false, true),
            "LIGHTEN" to listOf(false, false, false, true),
            "COLOR_DODGE" to listOf(false, false, false, true),
            "COLOR_BURN" to listOf(false, false, false, true),
            "HARD_LIGHT" to listOf(false, false, false, true),
            "SOFT_LIGHT" to listOf(false, false, false, true),
            "DIFFERENCE" to listOf(false, false, false, true),
            "EXCLUSION" to listOf(false, false, false, true),
            "MULTIPLY" to listOf(false, false, false, true),
            "HSL_HUE" to listOf(false, false, false, true),
            "HSL_SATURATION" to listOf(false, false, false, true),
            "HSL_COLOR" to listOf(false, false, false, true),
            "HSL_LUMINOSITY" to listOf(false, false, false, true),
        )
        for ((mode, expected) in rows) {
            listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).forEachIndexed { index, (source, backdrop) ->
                val nodes = listOf(GlyphPaintNode.Solid(GlyphColor(20, 40, 60), 1.0),
                    GlyphPaintNode.GlyphClip(auditedOutline(), 0),
                    GlyphPaintNode.Composite(source, backdrop, GlyphPaintCompositionMode.valueOf(mode)))
                val profile = schema2Profile(limits = schema2Limits(maxClips = 2))
                assertEquals(expected[index], profile.accepts(GlyphPaintIR(2, 2, nodes)), "$mode, case $index")
                assertTrue(profile.accepts(GlyphPaintIR(2, 2, nodes, DesignBounds(0, 0, 1000, 1000))))
            }
        }
    }

    @Test
    fun profileRejectsReachedGradientAndCompositeCapabilitiesThatItDoesNotAdvertise() {
        val paint = repeatingGradientPaint()

        assertTrue(schema2Profile().accepts(paint))
        assertFalse(
            schema2Profile(
                gradientModes = completeGradientExtendModes() - GlyphPaintExtendMode.REPEAT,
            ).accepts(paint),
        )
        assertFalse(
            schema2Profile(
                compositionModes = completeCompositionModes() - GlyphPaintCompositionMode.DESTINATION_OVER,
            ).accepts(paint),
        )
    }

    @Test
    fun profileRejectsAnUnclippedGradientRootAsUnbounded() {
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 0,
            nodes = listOf(
                GlyphPaintNode.LinearGradient(
                    colorLine = repeatingColorLine(),
                    p0 = GlyphPaintPoint(100.0, 250.0),
                    p1 = GlyphPaintPoint(900.0, 250.0),
                    p2 = GlyphPaintPoint(100.0, 300.0),
                ),
            ),
        )

        assertFalse(schema2Profile().accepts(paint))
    }

    @Test
    fun schemaOneSolidOutlineRemainsAcceptedThroughThePublicProfileContract() {
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 0,
            nodes = listOf(GlyphPaintNode.SolidOutline(auditedOutline(), GlyphColor(30, 60, 90))),
        )
        val profile = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
            acceptedCompositionModes = emptyList(),
            limits = PaintGraphLimits(maxNodes = 1, maxReferences = 0, maxDepth = 1),
            outlineProfile = outlineProfile(),
        )

        assertTrue(profile.accepts(paint))
    }

    @Test
    fun defaultVisitPolicyPreservesSchemaOneSharedNodesAndRejectsExpandedSchemaTwoWork() {
        val outline = auditedOutline()
        val schemaOnePaint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 1,
            nodes = listOf(
                GlyphPaintNode.SolidOutline(outline, GlyphColor(30, 60, 90)),
                GlyphPaintNode.Group(children = listOf(0, 0)),
            ),
        )
        val schemaOneProfile = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
            acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
            limits = PaintGraphLimits(maxNodes = 2, maxReferences = 2, maxDepth = 2),
            outlineProfile = outlineProfile(),
        )
        val schemaTwoPaint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 2,
            nodes = listOf(
                GlyphPaintNode.Solid(GlyphColor(30, 60, 90), opacity = 1.0),
                GlyphPaintNode.Group(children = listOf(0, 0)),
                GlyphPaintNode.Group(children = listOf(1, 1)),
            ),
            clipBounds = DesignBounds(0, 0, 1_000, 1_000),
        )
        val schemaTwoProfile = PaintGraphProfile(
            acceptedNodeKinds = completeNodeKinds(),
            acceptedCompositionModes = completeCompositionModes(),
            limits = PaintGraphLimits(maxNodes = 3, maxReferences = 4, maxDepth = 3),
            outlineProfile = outlineProfile(),
            schemaVersion = 2,
            acceptedGradientExtendModes = completeGradientExtendModes(),
        )

        assertTrue(schemaOneProfile.accepts(schemaOnePaint))
        assertFalse(schemaTwoProfile.accepts(schemaTwoPaint))
    }

    @Test
    fun profileRejectsReachedSchemaTwoWorkBeyondNewDeclaredLimits() {
        val paint = repeatingGradientPaint()

        assertFalse(schema2Profile(limits = schema2Limits(maxColorStops = 1)).accepts(paint))
        assertFalse(schema2Profile(limits = schema2Limits(maxTransforms = 0)).accepts(paint))
        assertFalse(schema2Profile(limits = schema2Limits(maxComposites = 0)).accepts(paint))
        assertFalse(schema2Profile(limits = schema2Limits(maxClips = 0)).accepts(paint))
        assertFalse(schema2Profile(limits = schema2Limits(maxPaintVisits = 4)).accepts(paint))
    }

    @Test
    fun schemaOneProfilesCannotAdvertiseCapabilitiesThatOnlySchemaTwoCanRepresent() {
        assertFailsWith<IllegalArgumentException> {
            PaintGraphProfile(
                acceptedNodeKinds = listOf(GlyphPaintNodeKind.LINEAR_GRADIENT),
                acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
                limits = PaintGraphLimits(maxNodes = 1, maxReferences = 0, maxDepth = 1),
                outlineProfile = outlineProfile(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PaintGraphProfile(
                acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
                acceptedCompositionModes = listOf(GlyphPaintCompositionMode.DESTINATION_OVER),
                limits = PaintGraphLimits(maxNodes = 1, maxReferences = 0, maxDepth = 1),
                outlineProfile = outlineProfile(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PaintGraphProfile(
                acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
                acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
                limits = PaintGraphLimits(maxNodes = 1, maxReferences = 0, maxDepth = 1),
                outlineProfile = outlineProfile(),
                acceptedGradientExtendModes = listOf(GlyphPaintExtendMode.REPEAT),
            )
        }
    }

    @Test
    fun schemaTwoValueObjectsRejectValuesThatCannotBeRenderedDeterministically() {
        assertFailsWith<IllegalArgumentException> { GlyphPaintPoint(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> {
            GlyphAffineTransform(1.0, 0.0, 0.0, 1.0, Double.POSITIVE_INFINITY, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            GlyphPaintColorLine(
                extendMode = GlyphPaintExtendMode.PAD,
                colorStops = listOf(
                    GlyphPaintColorStop(0.75, GlyphColor(255, 255, 255), 1.0),
                    GlyphPaintColorStop(0.25, GlyphColor(0, 0, 0), 1.0),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GlyphPaintNode.RadialGradient(
                colorLine = repeatingColorLine(),
                c0 = GlyphPaintPoint(0.0, 0.0),
                radius0 = -1.0,
                c1 = GlyphPaintPoint(1.0, 1.0),
                radius1 = 1.0,
            )
        }
    }

    private fun repeatingGradientPaint(): GlyphPaintIR = GlyphPaintIR(
        schemaVersion = 2,
        rootNode = 4,
        nodes = listOf(
            GlyphPaintNode.LinearGradient(
                repeatingColorLine(),
                GlyphPaintPoint(100.0, 250.0),
                GlyphPaintPoint(900.0, 250.0),
                GlyphPaintPoint(100.0, 300.0),
            ),
            GlyphPaintNode.GlyphClip(auditedOutline(), paint = 0),
            GlyphPaintNode.Transform(1, GlyphAffineTransform(1.0, 0.0, 0.0, 1.0, 125.0, 125.0)),
            GlyphPaintNode.Solid(GlyphColor(0, 0, 0), 0.5),
            GlyphPaintNode.Composite(source = 2, backdrop = 3, GlyphPaintCompositionMode.DESTINATION_OVER),
        ),
        clipBounds = DesignBounds(100, 250, 1025, 1075),
    )

    private fun repeatingColorLine(): GlyphPaintColorLine = GlyphPaintColorLine(
        GlyphPaintExtendMode.REPEAT,
        listOf(
            GlyphPaintColorStop(0.0, GlyphColor(255, 0, 0), 1.0),
            GlyphPaintColorStop(1.0, GlyphColor(0, 0, 255), 1.0),
        ),
    )

    private fun schema2Profile(
        gradientModes: List<GlyphPaintExtendMode> = completeGradientExtendModes(),
        compositionModes: List<GlyphPaintCompositionMode> = completeCompositionModes(),
        limits: PaintGraphLimits = schema2Limits(),
    ): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = completeNodeKinds(),
        acceptedCompositionModes = compositionModes,
        limits = limits,
        outlineProfile = outlineProfile(),
        schemaVersion = 2,
        acceptedGradientExtendModes = gradientModes,
    )

    private fun completeNodeKinds(): List<GlyphPaintNodeKind> = listOf(
        GlyphPaintNodeKind.SOLID_OUTLINE,
        GlyphPaintNodeKind.PATH,
        GlyphPaintNodeKind.GROUP,
        GlyphPaintNodeKind.SOLID,
        GlyphPaintNodeKind.LINEAR_GRADIENT,
        GlyphPaintNodeKind.RADIAL_GRADIENT,
        GlyphPaintNodeKind.SWEEP_GRADIENT,
        GlyphPaintNodeKind.GLYPH_CLIP,
        GlyphPaintNodeKind.TRANSFORM,
        GlyphPaintNodeKind.COMPOSITE,
    )

    private fun completeGradientExtendModes(): List<GlyphPaintExtendMode> = listOf(
        GlyphPaintExtendMode.PAD,
        GlyphPaintExtendMode.REPEAT,
        GlyphPaintExtendMode.REFLECT,
    )

    private fun completeCompositionModes(): List<GlyphPaintCompositionMode> = listOf(
        GlyphPaintCompositionMode.CLEAR,
        GlyphPaintCompositionMode.SOURCE,
        GlyphPaintCompositionMode.DESTINATION,
        GlyphPaintCompositionMode.SOURCE_OVER,
        GlyphPaintCompositionMode.DESTINATION_OVER,
        GlyphPaintCompositionMode.SOURCE_IN,
        GlyphPaintCompositionMode.DESTINATION_IN,
        GlyphPaintCompositionMode.SOURCE_OUT,
        GlyphPaintCompositionMode.DESTINATION_OUT,
        GlyphPaintCompositionMode.SOURCE_ATOP,
        GlyphPaintCompositionMode.DESTINATION_ATOP,
        GlyphPaintCompositionMode.XOR,
        GlyphPaintCompositionMode.PLUS,
        GlyphPaintCompositionMode.SCREEN,
        GlyphPaintCompositionMode.OVERLAY,
        GlyphPaintCompositionMode.DARKEN,
        GlyphPaintCompositionMode.LIGHTEN,
        GlyphPaintCompositionMode.COLOR_DODGE,
        GlyphPaintCompositionMode.COLOR_BURN,
        GlyphPaintCompositionMode.HARD_LIGHT,
        GlyphPaintCompositionMode.SOFT_LIGHT,
        GlyphPaintCompositionMode.DIFFERENCE,
        GlyphPaintCompositionMode.EXCLUSION,
        GlyphPaintCompositionMode.MULTIPLY,
        GlyphPaintCompositionMode.HSL_HUE,
        GlyphPaintCompositionMode.HSL_SATURATION,
        GlyphPaintCompositionMode.HSL_COLOR,
        GlyphPaintCompositionMode.HSL_LUMINOSITY,
    )

    private fun schema2Limits(
        maxColorStops: Int = 2,
        maxTransforms: Int = 1,
        maxComposites: Int = 1,
        maxClips: Int = 1,
        maxPaintVisits: Int = 5,
    ): PaintGraphLimits = PaintGraphLimits(
        maxNodes = 5,
        maxReferences = 4,
        maxDepth = 4,
        maxPaths = 0,
        maxGradients = 1,
        maxColorStops = maxColorStops,
        maxTransforms = maxTransforms,
        maxComposites = maxComposites,
        maxClips = maxClips,
        maxPaintVisits = maxPaintVisits,
    )

    private fun auditedOutline(): GlyphOutlineIR = GlyphOutlineIR(
        glyphId = 12,
        unitsPerEm = 1_000,
        bounds = DesignBounds(100, 250, 900, 950),
        commands = listOf(
            GlyphOutlineIR.Command.MoveTo(100, 250),
            GlyphOutlineIR.Command.LineTo(900, 250),
            GlyphOutlineIR.Command.LineTo(900, 950),
            GlyphOutlineIR.Command.Close,
        ),
    )

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = Int.MAX_VALUE,
        maxContours = Int.MAX_VALUE,
        maxPoints = Int.MAX_VALUE,
        maxCompositeDepth = Int.MAX_VALUE,
        maxCompositeComponents = Int.MAX_VALUE,
    )
}
