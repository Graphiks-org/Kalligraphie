package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.*
import org.graphiks.kalligraphie.layout.openLayoutHandle
import kotlin.test.*

class ColrV1SafetyFallbackTest {
    @Test
    fun unsupportedSvgPathDoesNotPreventAnUncoveredColrGlyphFromPublishingItsPaintCertificate() {
        val complete = colrV1Profile()
        val profile = PaintGraphProfile(
            acceptedNodeKinds = complete.acceptedNodeKinds - GlyphPaintNodeKind.PATH,
            acceptedCompositionModes = complete.acceptedCompositionModes,
            acceptedGradientExtendModes = complete.acceptedGradientExtendModes,
            limits = complete.limits,
            outlineProfile = complete.outlineProfile,
            schemaVersion = 2,
        )
        ColrV1Fixture(FontAccessRequirementsSnapshot.renderable(listOf(profile)),
            mutateSource = ::addLiteralSvgForGlyphEight).use { fixture ->
            val asset = fixture.asset()
            try {
                assertIs<FontError.UnsupportedRepresentationProfile>(
                    assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(8))).error,
                )
                val paint = assertIs<GlyphRepresentation.Paint>(
                    colrSuccess(asset.resolveGlyph(FontGlyphRequest(84))),
                ).paint
                val composite = assertIs<GlyphPaintNode.Composite>(paint.nodes[paint.rootNode])
                assertEquals(GlyphPaintCompositionMode.DESTINATION_OVER, composite.mode)
                assertEquals(GlyphAffineTransform(0.5, 0.0, 0.0, 1.5, 250.0, -250.0),
                    assertIs<GlyphPaintNode.Transform>(paint.nodes[composite.source]).matrix)
                val line = assertIs<EditableLineResult.Success>(fixture.layout(colrSnapshot(0xF0300))).line
                val certificate = assertNotNull(line.positionedGlyphRuns.single().glyphs.single().materializationCertificate)
                assertEquals(GlyphId(84), certificate.glyphId)
                assertEquals(GlyphMaterializationRoute.PAINT_GRAPH, certificate.route)
                assertIs<EditableLineResult.Failure>(fixture.layout(colrSnapshot(0xF0100)))
            } finally { asset.close() }
        }
    }

    @Test
    fun zeroColrLayersPublishACompleteBoundedNoPaintGraphAndCertificate() {
        ColrV1Fixture(mutateSource = { bytes ->
            // Audited root of glyph 84: PaintColrLayers, zero layers, first layer index zero.
            byteArrayOf(1, 0, 0, 0, 0, 0).copyInto(bytes, 17939)
        }).use { fixture ->
            val paint = fixture.paint(0xF0300, 84)
            assertEquals(2, paint.schemaVersion)
            assertNull(paint.clipBounds)
            assertEquals(emptyList(), assertIs<GlyphPaintNode.Group>(paint.nodes[paint.rootNode]).children)
            assertEquals(1, paint.nodes.size)
            assertTrue(colrV1Profile().accepts(paint))
            val line = assertIs<EditableLineResult.Success>(fixture.layout(colrSnapshot(0xF0300))).line
            val certificate = assertNotNull(line.positionedGlyphRuns.single().glyphs.single().materializationCertificate)
            assertEquals(GlyphId(84), certificate.glyphId)
            assertEquals(GlyphMaterializationRoute.PAINT_GRAPH, certificate.route)
            assertEquals(2, certificate.representationSchemaVersion)
            val handle = colrSuccess(line.openLayoutHandle(fixture.resolver))
            try {
                val renderer = colrSuccess(handle.retainFontAsset(certificate))
                try {
                    val resolved = assertIs<GlyphRepresentation.Paint>(colrSuccess(renderer.resolveGlyph(FontGlyphRequest(84)))).paint
                    assertEquals(emptyList(), assertIs<GlyphPaintNode.Group>(resolved.nodes[resolved.rootNode]).children)
                    assertTrue(assertIs<PaintGraphProfile>(renderer.key.representationProfile).accepts(resolved))
                } finally { renderer.close() }
            } finally { handle.close() }
            val other = fixture.paint(0xF0100, 8)
            assertEquals(DesignBounds(100, 250, 900, 950), other.clipBounds)
            assertEquals(listOf(GlyphColor(255, 0, 0), GlyphColor(0, 0, 255)),
                other.nodes.filterIsInstance<GlyphPaintNode.LinearGradient>().single().colorLine.colorStops.map { it.color })
        }
    }

    @Test
    fun svgKeepsPriorityAndItsUncoveredGlyphsUseV1ThenLegacyThenOutline() {
        ColrV1Fixture(mutateSource = { bytes ->
            // Replace the optional post table with a literal SVG document covering glyph 8.
            val xml = "<svg xmlns=\"http://www.w3.org/2000/svg\"><g id=\"glyph8\"><path fill=\"#112233\" d=\"M 10 20 L 40 20 L 40 60 Z\"/></g></svg>".encodeToByteArray()
            val svg = ByteArray(24 + xml.size)
            svg[5] = 10; svg[11] = 1; svg[13] = 8; svg[15] = 8; svg[19] = 14
            svg[22] = (xml.size ushr 8).toByte(); svg[23] = xml.size.toByte()
            xml.copyInto(svg, 24)
            "SVG ".encodeToByteArray().copyInto(bytes, 188)
            for (index in 0..3) bytes[200 + index] = (svg.size ushr (24 - index * 8)).toByte()
            svg.copyInto(bytes, 8440)
        }).use { fixture ->
            val svg = fixture.paint(0xF0100, 8)
            val path = svg.nodes.filterIsInstance<GlyphPaintNode.Path>().single()
            assertEquals(GlyphColor(17, 34, 51), path.color)
            assertEquals(GlyphPaintPathCommand.MoveTo(10.0, 20.0), path.path.commands.first())
            val transformed = fixture.paint(0xF0300, 84)
            assertEquals(GlyphPaintCompositionMode.DESTINATION_OVER, assertIs<GlyphPaintNode.Composite>(transformed.nodes[transformed.rootNode]).mode)
            assertLegacyCircles(fixture.paint(0xF0E00, 168))
            val asset = fixture.asset()
            try {
                val outline = assertIs<GlyphRepresentation.Paint>(colrSuccess(asset.resolveGlyph(FontGlyphRequest(5)))).paint
                val solid = assertIs<GlyphPaintNode.SolidOutline>(outline.nodes[outline.rootNode])
                assertEquals(5, solid.outline.glyphId)
                assertEquals(DesignBounds(173, 246, 357, 545), solid.outline.bounds)
                assertEquals(GlyphColor(0, 0, 0), solid.color)
            } finally { asset.close() }
        }
    }

    @Test
    fun glyphsAbsentFromBothColorMapsPreserveTheEmptyOutlineFallback() {
        ColrV1Fixture().use { fixture ->
            val asset = fixture.asset()
            try { assertEquals(GlyphRepresentation.Empty, colrSuccess(asset.resolveGlyph(FontGlyphRequest(1)))) }
            finally { asset.close() }
        }
    }

    /**
     * Format 33 is reserved: the reader accepts only paint formats 1..32, so an unrecognized value
     * must fail typed. Format 19 is a supported variable transform and is pinned by
     * [variableScaleUniformAroundCenterResolvesStaticallyAtTheDefaultInstance].
     */
    @Test
    fun unknownCompositeUsesClearWhileMalformedReferencesAndVariablePaintFailTyped() {
        ColrV1Fixture(mutateSource = { it[17943] = 255.toByte() }).use { fixture ->
            val paint = fixture.paint(0xF0300, 84)
            assertEquals(GlyphPaintCompositionMode.CLEAR, assertIs<GlyphPaintNode.Composite>(paint.nodes[paint.rootNode]).mode)
        }
        val malformedSources: List<(ByteArray) -> Unit> = listOf(
            { bytes -> for (index in 17940..17942) bytes[index] = 255.toByte() },
            { bytes -> byteArrayOf(11, 0, 1).copyInto(bytes, 17947) },
        )
        malformedSources.forEach { mutation ->
            ColrV1Fixture(mutateSource = mutation).use { fixture ->
                val asset = fixture.asset()
                try {
                    assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(84))).error)
                    assertEquals(DesignBounds(100, 250, 900, 950), fixture.paint(0xF0100, 8).clipBounds)
                } finally { asset.close() }
            }
        }
        ColrV1Fixture(mutateSource = { it[17947] = 33 }).use { fixture ->
            val asset = fixture.asset()
            try {
                assertIs<FontError.UnsupportedRepresentationProfile>(assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(84))).error)
                assertEquals(DesignBounds(100, 250, 900, 950), fixture.paint(0xF0100, 8).clipBounds)
            } finally { asset.close() }
        }
    }

    /**
     * At the default instance the variable transform contributes zero deltas, so format 19
     * (`PaintVarScaleUniformAroundCenter`) resolves to the same matrix as its static counterpart.
     */
    @Test
    fun variableScaleUniformAroundCenterResolvesStaticallyAtTheDefaultInstance() {
        ColrV1Fixture(mutateSource = { it[17947] = 19 }).use { fixture ->
            val paint = fixture.paint(0xF0300, 84)
            val composite = assertIs<GlyphPaintNode.Composite>(paint.nodes[paint.rootNode])
            val transform = assertIs<GlyphPaintNode.Transform>(paint.nodes[composite.source])
            assertEquals(GlyphAffineTransform(0.5, 0.0, 0.0, 1.5, 250.0, -250.0), transform.matrix)
        }
    }

    @Test
    fun unboundedAtopGlyphCannotPublishPaintOrALayoutCertificate() {
        // Audited SFNT offsets: COLR 15072, glyph 84 root 17939, source 17947, backdrop 18765.
        for ((mode, unboundedPaint) in listOf(9 to 17947, 10 to 18765)) {
            ColrV1Fixture(mutateSource = { bytes ->
                for (index in 15094..15097) bytes[index] = 0 // No root ClipList.
                bytes[17943] = mode.toByte()
                byteArrayOf(2, 0, 0, 64, 0).copyInto(bytes, unboundedPaint) // Unbounded opaque red.
            }).use { fixture ->
                val asset = fixture.asset()
                try {
                    assertIs<FontError.UnsupportedRepresentationProfile>(assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(84))).error)
                    assertIs<EditableLineResult.Failure>(fixture.layout(colrSnapshot(0xF0300)))
                } finally { asset.close() }
            }
        }
    }

    @Test
    fun referencedGlyphsProduceFiveCompleteVisibleComponents() {
        ColrV1Fixture().use { fixture ->
            val paint = fixture.paint(0xF1200, 180)
            val root = assertIs<GlyphPaintNode.Group>(paint.nodes[paint.rootNode])
            assertEquals(5, root.children.size)
            val scales = listOf(1.0, 0.82000732421875, 0.6400146484375, 0.46002197265625, 0.280029296875)
            root.children.forEachIndexed { index, child ->
                val scale = assertIs<GlyphPaintNode.Transform>(paint.nodes[child])
                assertEquals(scales[index], scale.matrix.xx)
                val rotate = assertIs<GlyphPaintNode.Transform>(paint.nodes[scale.paint])
                assertEquals(GlyphAffineTransform(-1.0, 0.0, 0.0, -1.0, 1000.0, 1200.0), rotate.matrix)
                val group = assertIs<GlyphPaintNode.Group>(paint.nodes[rotate.paint])
                assertEquals(2, group.children.size)
                val clips = group.children.map {
                    val translate = assertIs<GlyphPaintNode.Transform>(paint.nodes[it])
                    assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[translate.paint])
                }
                assertEquals(listOf(176, 176), clips.map { it.outline.glyphId })
                assertEquals(GlyphColor(0, 128, 0), assertIs<GlyphPaintNode.Solid>(paint.nodes[clips[0].paint]).color)
                assertEquals(GlyphPaintPoint(500.0, 250.0), assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[clips[1].paint]).p0)
            }
        }
    }

    @Test
    fun aCycleFailsWithInvalidDataAndPublishesNoLayoutOrCertificateWhileAnotherGlyphStillResolves() {
        ColrV1Fixture().use { fixture ->
            val asset = fixture.asset()
            try {
                assertEquals(GlyphId(178), colrSuccess(fixture.font.resolveGlyph(0xF1100)).glyphId)
                assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(178))).error)
                assertIs<EditableLineResult.Failure>(fixture.layout(colrSnapshot(0xF0100, 0xF1100)))
                val valid = assertIs<GlyphRepresentation.Paint>(colrSuccess(asset.resolveGlyph(FontGlyphRequest(8)))).paint
                assertEquals(DesignBounds(100, 250, 900, 950), valid.clipBounds)
            } finally { asset.close() }
        }
    }

    @Test
    fun mixedVersionGlyphUsesEightAuditedLegacyLayers() {
        ColrV1Fixture().use { fixture -> assertLegacyCircles(fixture.paint(0xF0E00, 168)) }
    }

    @Test
    fun refusedCompositeFallsThroughOrderedRequirementsToTheRealOutlineCertificate() {
        val profile = colrV1Profile(modes = listOf(GlyphPaintCompositionMode.SOURCE_OVER))
        ColrV1Fixture(FontAccessRequirementsSnapshot.renderable(listOf(profile, profile.outlineProfile))).use { fixture ->
            val line = assertIs<EditableLineResult.Success>(fixture.layout(colrSnapshot(0xF0300))).line
            val certificate = assertNotNull(line.positionedGlyphRuns.single().glyphs.single().materializationCertificate)
            assertEquals(GlyphId(84), certificate.glyphId)
            assertEquals(GlyphMaterializationRoute.OUTLINE, certificate.route)
            assertIs<OutlineProfile>(certificate.assetKey.representationProfile)
            val handle = colrSuccess(line.openLayoutHandle(fixture.resolver))
            try {
                val renderer = colrSuccess(handle.retainFontAsset(certificate))
                try {
                    val outline = assertIs<GlyphRepresentation.Outline>(colrSuccess(renderer.resolveGlyph(FontGlyphRequest(84)))).outline
                    assertEquals(84, outline.glyphId)
                    assertEquals(DesignBounds(0, 0, 1000, 1000), outline.bounds)
                    assertEquals(4, outline.pointCount)
                } finally { renderer.close() }
            } finally { handle.close() }
        }
    }

    @Test
    fun transformedGraphLimitsFailTypedWithoutPoisoningSimpleGlyphs() {
        val profile = colrV1Profile(maxTransforms = 1)
        ColrV1Fixture(FontAccessRequirementsSnapshot.renderable(listOf(profile))).use { fixture ->
            val asset = fixture.asset()
            try {
                assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(180))).error)
                assertIs<FontOperationResult.Cancelled>(asset.resolveGlyph(FontGlyphRequest(180), CancellationToken.cancelled))
                assertEquals(DesignBounds(100, 250, 900, 950), fixture.paint(0xF0100, 8).clipBounds)
            } finally { asset.close() }
        }
    }
}

private fun addLiteralSvgForGlyphEight(bytes: ByteArray) {
    // Existing audited font: replace the optional post table at 8440, covering only glyph 8.
    val xml = "<svg xmlns=\"http://www.w3.org/2000/svg\"><g id=\"glyph8\"><path fill=\"#112233\" d=\"M 10 20 L 40 20 L 40 60 Z\"/></g></svg>".encodeToByteArray()
    val svg = ByteArray(24 + xml.size)
    svg[5] = 10; svg[11] = 1; svg[13] = 8; svg[15] = 8; svg[19] = 14
    svg[22] = (xml.size ushr 8).toByte(); svg[23] = xml.size.toByte()
    xml.copyInto(svg, 24)
    "SVG ".encodeToByteArray().copyInto(bytes, 188)
    for (index in 0..3) bytes[200 + index] = (svg.size ushr (24 - index * 8)).toByte()
    svg.copyInto(bytes, 8440)
}

internal fun assertLegacyCircles(paint: GlyphPaintIR) {
    val root = assertIs<GlyphPaintNode.Group>(paint.nodes[paint.rootNode])
    val layers = root.children.map { assertIs<GlyphPaintNode.SolidOutline>(paint.nodes[it]) }
    assertEquals(listOf(176, 175, 174, 173, 172, 171, 170, 5), layers.map { it.outline.glyphId })
    assertEquals(listOf(GlyphColor(255, 0, 0), GlyphColor(255, 165, 0), GlyphColor(255, 255, 0),
        GlyphColor(0, 128, 0), GlyphColor(0, 0, 255), GlyphColor(75, 0, 130), GlyphColor(238, 130, 238),
        GlyphColor(0, 0, 0)), layers.map { it.color })
}
