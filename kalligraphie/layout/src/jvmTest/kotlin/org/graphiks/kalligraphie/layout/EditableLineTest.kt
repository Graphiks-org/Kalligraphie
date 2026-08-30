package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.CaretPosition
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineRequest
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontFaceRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GdefLigatureCaretFact
import org.graphiks.kalligraphie.api.GdefLigatureCaretState
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.LogicalNavigationDirection
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.ScriptLanguageRun
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingBackendIdentity
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy
import org.graphiks.kalligraphie.api.ShapingFeaturePolicyApplication
import org.graphiks.kalligraphie.api.ShapingSafetyFlags
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeDataIdentity
import org.graphiks.kalligraphie.api.VisualNavigationDirection
import org.graphiks.kalligraphie.api.BidiRun
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import org.graphiks.kalligraphie.unicode.TextSnapshots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EditableLineTest {
    @Test
    fun emptyLineUsesItsExplicitRightToLeftDirectionWithoutDefaultingToLeftToRight() {
        val prepared = text("")
        val font = fontFixture().instance
        val analysis = UnicodeAnalysis(
            range = prepared.range,
            unicodeData = UnicodeDataIdentity("16.0.0", "manual-audited-scenario", "1"),
            graphemeClusters = emptyList(),
            scriptLanguageRuns = emptyList(),
            logicalBidiRuns = emptyList(),
            visualBidiRuns = emptyList(),
        )

        val line = ExactEditableLineLayouter.layout(
            EditableLineRequest(
                unicodeAnalysis = analysis,
                shapedGlyphRuns = emptyList(),
                font = font,
                verticalMetrics = LineVerticalMetrics(ascent = LayoutUnit(8f), descent = LayoutUnit(2f)),
                materialization = EditableLineMaterialization.LayoutOnly,
                emptyLineDirection = ShapingDirection.RIGHT_TO_LEFT,
                emptyLineBidiLevel = 1,
            ),
        ).successValue()

        assertEquals(
            listOf(CaretAffinity.DOWNSTREAM, CaretAffinity.UPSTREAM),
            line.allCaretCandidates.map { it.position.affinity },
        )
        assertTrue(line.allCaretCandidates.all { it.direction == ShapingDirection.RIGHT_TO_LEFT })
        assertTrue(line.allCaretCandidates.all { it.bidiLevel == 1 })
    }

    @Test
    fun leftToRightLinePublishesFinalGlyphPositionsAndGraphemeCarets() {
        val prepared = text("ab")
        val font = fontFixture().instance
        val analysis = analysis(prepared, listOf(0), listOf(0))
        val line = layout(
            analysis = analysis,
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.LEFT_TO_RIGHT,
                    level = 0,
                    glyphs = listOf(glyph(10, 10f, 0), glyph(11, 20f, 1)),
                ),
            ),
        )

        assertEquals(
            listOf(LayoutPoint(LayoutUnit(0f), LayoutUnit(0f)), LayoutPoint(LayoutUnit(10f), LayoutUnit(0f))),
            line.positionedGlyphRuns.single().glyphs.map { it.origin },
        )
        assertEquals(listOf(0f, 10f, 30f), caretXs(line, prepared, 0, 1, 2))
    }

    @Test
    fun rightToLeftLineAnchorsLogicalStartAtTheRightGlyphEdge() {
        val prepared = text("אב")
        val font = fontFixture().instance
        val analysis = analysis(prepared, listOf(1), listOf(1))
        val line = layout(
            analysis = analysis,
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.RIGHT_TO_LEFT,
                    level = 1,
                    glyphs = listOf(glyph(20, 10f, 1), glyph(21, 20f, 0)),
                ),
            ),
        )

        assertEquals(listOf(0f, 10f), line.positionedGlyphRuns.single().glyphs.map { it.origin.x.value })
        assertEquals(listOf(30f, 10f, 0f), caretXs(line, prepared, 0, 1, 2))
    }

    @Test
    fun mixedBidiBoundaryExposesTwoGeometricallyDistinctCaretCandidates() {
        val prepared = text("aאבb")
        val font = fontFixture().instance
        val analysis = analysis(prepared, logicalLevels = listOf(0, 1, 0), visualLevels = listOf(0, 1, 0), bidiBoundaries = listOf(0, 1, 3, 4))
        val line = layout(
            analysis = analysis,
            font = font,
            runs = listOf(
                shapedRun(prepared, font, range(prepared, 0, 1), ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(30, 10f, 0))),
                shapedRun(prepared, font, range(prepared, 1, 3), ShapingDirection.RIGHT_TO_LEFT, 1, listOf(glyph(31, 10f, 1), glyph(32, 10f, 0))),
                shapedRun(prepared, font, range(prepared, 3, 4), ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(33, 10f, 0))),
            ),
        )

        assertEquals(listOf(10f, 30f), line.caretCandidates(index(prepared, 1)).map { it.geometry.start.x.value }.sorted())
    }

    @Test
    fun validGdefLigatureCaretsAreUsedAtTheirAuditedPositions() {
        val prepared = text("fi")
        val font = fontFixture().instance
        val analysis = analysis(prepared, listOf(0), listOf(0))
        val line = layout(
            analysis = analysis,
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.LEFT_TO_RIGHT,
                    level = 0,
                    glyphs = listOf(glyph(40, 20f, 0)),
                    clusters = listOf(cluster(prepared, 0, 2, 0)),
                    ligatureFacts = listOf(
                        GdefLigatureCaretFact(
                            glyphIndex = 0,
                            state = GdefLigatureCaretState.AVAILABLE,
                            logicalSourceBoundaries = listOf(index(prepared, 1)),
                            positions = listOf(LayoutUnit(7f)),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(0f, 7f, 20f), caretXs(line, prepared, 0, 1, 2))
        assertTrue(line.diagnostics.none { it.code == "layout.invalid-ligature-caret-data" })
    }

    @Test
    fun validGdefCaretPositionsAreMeasuredFromTheOffsetGlyphOrigin() {
        val prepared = text("fi")
        val font = fontFixture().instance
        val analysis = analysis(prepared, listOf(0), listOf(0))
        val line = layout(
            analysis = analysis,
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.LEFT_TO_RIGHT,
                    level = 0,
                    glyphs = listOf(glyph(43, 20f, 0, xOffset = 3f)),
                    clusters = listOf(cluster(prepared, 0, 2, 0)),
                    ligatureFacts = listOf(
                        GdefLigatureCaretFact(
                            glyphIndex = 0,
                            state = GdefLigatureCaretState.AVAILABLE,
                            logicalSourceBoundaries = listOf(index(prepared, 1)),
                            positions = listOf(LayoutUnit(7f)),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(0f, 10f, 20f), caretXs(line, prepared, 0, 1, 2))
    }

    @Test
    fun incoherentLigatureCaretsFallBackCompletelyToDeterministicInterpolation() {
        val prepared = text("fi")
        val font = fontFixture().instance
        val analysis = analysis(prepared, listOf(0), listOf(0))
        val line = layout(
            analysis = analysis,
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.LEFT_TO_RIGHT,
                    level = 0,
                    glyphs = listOf(glyph(41, 20f, 0)),
                    clusters = listOf(cluster(prepared, 0, 2, 0)),
                    ligatureFacts = listOf(
                        GdefLigatureCaretFact(
                            glyphIndex = 0,
                            state = GdefLigatureCaretState.AVAILABLE,
                            logicalSourceBoundaries = listOf(index(prepared, 1)),
                            positions = listOf(LayoutUnit(21f)),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(0f, 10f, 20f), caretXs(line, prepared, 0, 1, 2))
        assertEquals(listOf("layout.invalid-ligature-caret-data"), line.diagnostics.map { it.code })
    }

    @Test
    fun gdefCaretsKeepTheirLogicalBoundaryAssociationAcrossMultipleClustersOfOneGlyph() {
        val prepared = text("afi")
        val font = fontFixture().instance
        val analysis = analysis(prepared, listOf(0), listOf(0))
        val line = layout(
            analysis = analysis,
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.LEFT_TO_RIGHT,
                    level = 0,
                    glyphs = listOf(glyphWithTokens(42, 30f, listOf(0, 1))),
                    clusters = listOf(cluster(prepared, 0, 1, 0), cluster(prepared, 1, 3, 1)),
                    ligatureFacts = listOf(
                        GdefLigatureCaretFact(
                            glyphIndex = 0,
                            state = GdefLigatureCaretState.AVAILABLE,
                            logicalSourceBoundaries = listOf(index(prepared, 1), index(prepared, 2)),
                            positions = listOf(LayoutUnit(5f), LayoutUnit(20f)),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(0f, 5f, 20f, 30f), caretXs(line, prepared, 0, 1, 2, 3))
    }

    @Test
    fun combiningMarkDoesNotCreateACaretInsideItsExtendedGraphemeCluster() {
        val prepared = text("x\u0301")
        val font = fontFixture().instance
        val analysis = analysis(prepared, listOf(0), listOf(0), graphemeBoundaries = listOf(0, 2))
        val line = layout(
            analysis = analysis,
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.LEFT_TO_RIGHT,
                    level = 0,
                    glyphs = listOf(glyph(50, 10f, 0), glyph(51, 0f, 1, xOffset = -2f, yOffset = -3f)),
                    clusters = listOf(cluster(prepared, 0, 1, 0), cluster(prepared, 1, 2, 1)),
                ),
            ),
        )

        assertTrue(line.caretCandidates(index(prepared, 1)).isEmpty())
        assertEquals(listOf(0f, 10f), caretXs(line, prepared, 0, 2))
    }

    @Test
    fun logicalAndVisualNavigationRemainDistinctAtABidiBoundary() {
        val prepared = text("aאבb")
        val font = fontFixture().instance
        val analysis = analysis(prepared, logicalLevels = listOf(0, 1, 0), visualLevels = listOf(0, 1, 0), bidiBoundaries = listOf(0, 1, 3, 4))
        val line = layout(
            analysis,
            font,
            listOf(
                shapedRun(prepared, font, range(prepared, 0, 1), ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(60, 10f, 0))),
                shapedRun(prepared, font, range(prepared, 1, 3), ShapingDirection.RIGHT_TO_LEFT, 1, listOf(glyph(61, 10f, 1), glyph(62, 10f, 0))),
                shapedRun(prepared, font, range(prepared, 3, 4), ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(63, 10f, 0))),
            ),
        )
        val ltrBoundary = line.caretCandidates(index(prepared, 1)).single { it.geometry.start.x == LayoutUnit(10f) }

        assertEquals(index(prepared, 2), line.nextLogical(CaretPosition(index(prepared, 1), CaretAffinity.UPSTREAM), LogicalNavigationDirection.FORWARD)?.index)
        assertEquals(index(prepared, 3), line.nextVisual(ltrBoundary, VisualNavigationDirection.FORWARD)?.position?.index)
    }

    @Test
    fun hitTestingEqualDistanceUsesVisualOrderBeforeLogicalIndexAndAffinity() {
        val prepared = text("ab")
        val font = fontFixture().instance
        val line = layout(
            analysis(prepared, listOf(0), listOf(0)),
            font,
            listOf(shapedRun(prepared, font, ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(70, 10f, 0), glyph(71, 10f, 1)))),
        )

        assertEquals(index(prepared, 1), line.hitTest(LayoutPoint(LayoutUnit(15f), LayoutUnit(0f))).position.index)
    }

    @Test
    fun selectionGeometryFollowsVisualRunsWithoutInkBounds() {
        val prepared = text("aאבb")
        val font = fontFixture().instance
        val analysis = analysis(prepared, logicalLevels = listOf(0, 1, 0), visualLevels = listOf(0, 1, 0), bidiBoundaries = listOf(0, 1, 3, 4))
        val line = layout(
            analysis,
            font,
            listOf(
                shapedRun(prepared, font, range(prepared, 0, 1), ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(80, 10f, 0))),
                shapedRun(prepared, font, range(prepared, 1, 3), ShapingDirection.RIGHT_TO_LEFT, 1, listOf(glyph(81, 10f, 1), glyph(82, 10f, 0))),
                shapedRun(prepared, font, range(prepared, 3, 4), ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(83, 10f, 0))),
            ),
        )

        assertEquals(
            listOf(0f to 10f, 10f to 30f, 30f to 40f),
            line.selectionGeometry(
                CaretPosition(index(prepared, 0), CaretAffinity.DOWNSTREAM),
                CaretPosition(index(prepared, 4), CaretAffinity.UPSTREAM),
            ).map { it.left.value to it.right.value },
        )
    }

    @Test
    fun positionedGlyphsPreserveOneToManyAndManyToOneTextClusterRelations() {
        val prepared = text("xfi")
        val font = fontFixture().instance
        val line = layout(
            analysis(prepared, listOf(0), listOf(0)),
            font,
            listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.LEFT_TO_RIGHT,
                    level = 0,
                    glyphs = listOf(glyph(90, 4f, 0), glyph(91, 6f, 0), glyph(92, 20f, 1)),
                    clusters = listOf(cluster(prepared, 0, 1, 0), cluster(prepared, 1, 3, 1)),
                ),
            ),
        )

        val glyphs = line.positionedGlyphRuns.single().glyphs
        assertEquals(listOf(range(prepared, 0, 1)), glyphs[0].sourceClusters.map { it.sourceRange })
        assertEquals(listOf(range(prepared, 0, 1)), glyphs[1].sourceClusters.map { it.sourceRange })
        assertEquals(listOf(range(prepared, 1, 3)), glyphs[2].sourceClusters.map { it.sourceRange })
    }

    @Test
    fun renderableLineCertifiesTheOutlineRouteForEveryFinalGlyph() {
        val fixture = fontFixture()
        val prepared = text("f")
        val resolver = fixture.catalog.openAssetResolver().successValue()
        try {
            val line = layout(
                analysis(prepared, listOf(0), listOf(0)),
                fixture.instance,
                listOf(shapedRun(prepared, fixture.instance, ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(73, 10f, 0)))),
                EditableLineMaterialization.Renderable(
                    resolver = resolver,
                    variant = FontRenderVariantKey.default,
                    outlineProfile = outlineProfile(),
                ),
            )

            assertEquals(
                listOf(GlyphMaterializationRoute.OUTLINE),
                line.positionedGlyphRuns.single().glyphs.map { it.materializationCertificate?.route },
            )
        } finally {
            resolver.close()
        }
    }

    private fun layout(
        analysis: UnicodeAnalysis,
        font: FontInstance,
        runs: List<ShapedGlyphRun>,
        materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
    ) = ExactEditableLineLayouter.layout(
        EditableLineRequest(
            unicodeAnalysis = analysis,
            shapedGlyphRuns = runs,
            font = font,
            verticalMetrics = LineVerticalMetrics(ascent = LayoutUnit(8f), descent = LayoutUnit(2f)),
            materialization = materialization,
        ),
    ).successValue()

    private fun analysis(
        prepared: TextSnapshot,
        logicalLevels: List<Int>,
        visualLevels: List<Int>,
        bidiBoundaries: List<Int> = (0..prepared.scalars.size).toList(),
        graphemeBoundaries: List<Int> = (0..prepared.scalars.size).toList(),
    ): UnicodeAnalysis {
        val range = prepared.range
        val partitions = graphemeBoundaries.zipWithNext().map { (start, endExclusive) -> range(prepared, start, endExclusive) }
        val logical = partitionBidiRuns(prepared, logicalLevels, bidiBoundaries)
        val visual = partitionBidiRuns(prepared, visualLevels, bidiBoundaries)
        return UnicodeAnalysis(
            range = range,
            unicodeData = UnicodeDataIdentity("16.0.0", "manual-audited-scenario", "1"),
            graphemeClusters = partitions,
            scriptLanguageRuns = logical.map { bidi ->
                ScriptLanguageRun(
                    range = bidi.range,
                    script = if (bidi.level % 2 == 0) "Latn" else "Hebr",
                    language = "und",
                )
            },
            logicalBidiRuns = logical,
            visualBidiRuns = visual,
        )
    }

    private fun partitionBidiRuns(
        prepared: TextSnapshot,
        levels: List<Int>,
        boundaries: List<Int>,
    ): List<BidiRun> {
        if (levels.size == 1) return listOf(BidiRun(prepared.range, levels.single()))
        require(levels.size == boundaries.size - 1)
        return levels.indices.map { index -> BidiRun(range(prepared, boundaries[index], boundaries[index + 1]), levels[index]) }
    }

    private fun shapedRun(
        prepared: TextSnapshot,
        font: FontInstance,
        direction: ShapingDirection,
        level: Int,
        glyphs: List<ShapedGlyph>,
        clusters: List<ShaperCluster>? = null,
        ligatureFacts: List<GdefLigatureCaretFact> = emptyList(),
    ): ShapedGlyphRun = shapedRun(prepared, font, prepared.range, direction, level, glyphs, clusters, ligatureFacts)

    private fun shapedRun(
        prepared: TextSnapshot,
        font: FontInstance,
        range: TextRange,
        direction: ShapingDirection,
        level: Int,
        glyphs: List<ShapedGlyph>,
        clusters: List<ShaperCluster>? = null,
        ligatureFacts: List<GdefLigatureCaretFact> = emptyList(),
    ): ShapedGlyphRun = ShapedGlyphRun(
        range = range,
        fontInstanceKey = font.key,
        backendIdentity = backendIdentity,
        direction = direction,
        script = if (direction == ShapingDirection.LEFT_TO_RIGHT) OpenTypeScript("Latn") else OpenTypeScript("Hebr"),
        language = "und",
        bidiLevel = level,
        bot = true,
        eot = true,
        featurePolicy = featurePolicy,
        features = emptyList(),
        graphemeClusters = rangePartitions(prepared, range),
        glyphs = glyphs,
        clusters = clusters ?: defaultClusters(prepared, range, glyphs),
        ligatureCaretFacts = ligatureFacts,
    )

    private fun rangePartitions(prepared: TextSnapshot, range: TextRange): List<TextRange> =
        prepared.scalarRanges(range)

    private fun defaultClusters(
        prepared: TextSnapshot,
        range: TextRange,
        glyphs: List<ShapedGlyph>,
    ): List<ShaperCluster> {
        val scalars = prepared.scalarRanges(range)
        return glyphs
            .flatMap { it.clusterTokens }
            .distinct()
            .sortedBy { it.value }
            .map { token ->
                val scalar = scalars[token.value]
                ShaperCluster(
                    token = token,
                    sourceRange = scalar,
                    scalarRanges = listOf(scalar),
                    admissibleGraphemeBoundaries = listOf(scalar.start, scalar.endExclusive),
                )
            }
    }

    private fun glyph(
        glyphId: Int,
        advance: Float,
        token: Int,
        xOffset: Float = 0f,
        yOffset: Float = 0f,
    ): ShapedGlyph = ShapedGlyph(
        glyphId = GlyphId(glyphId),
        xAdvance = LayoutUnit(advance),
        yAdvance = LayoutUnit(0f),
        xOffset = LayoutUnit(xOffset),
        yOffset = LayoutUnit(yOffset),
        safetyFlags = ShapingSafetyFlags(unsafeToBreak = false, unsafeToConcat = false),
        clusterTokens = listOf(ShaperClusterToken(token)),
    )

    private fun glyphWithTokens(
        glyphId: Int,
        advance: Float,
        tokens: List<Int>,
    ): ShapedGlyph = ShapedGlyph(
        glyphId = GlyphId(glyphId),
        xAdvance = LayoutUnit(advance),
        yAdvance = LayoutUnit(0f),
        xOffset = LayoutUnit(0f),
        yOffset = LayoutUnit(0f),
        safetyFlags = ShapingSafetyFlags(unsafeToBreak = false, unsafeToConcat = false),
        clusterTokens = tokens.map(::ShaperClusterToken),
    )

    private fun cluster(prepared: TextSnapshot, start: Int, endExclusive: Int, token: Int): ShaperCluster =
        ShaperCluster(
            token = ShaperClusterToken(token),
            sourceRange = range(prepared, start, endExclusive),
            scalarRanges = prepared.scalarRanges(range(prepared, start, endExclusive)),
            admissibleGraphemeBoundaries = (start..endExclusive).map { index(prepared, it) },
        )

    private fun caretXs(line: org.graphiks.kalligraphie.api.EditableLine, prepared: TextSnapshot, vararg ordinals: Int): List<Float> =
        ordinals.map { ordinal -> line.caretCandidates(index(prepared, ordinal)).single().geometry.start.x.value }

    private fun text(value: String): TextSnapshot =
        TextSnapshots.decodeUtf16(TextVersion.create(), listOf(TextSlice.Utf16(value.toCharArray()))).snapshot

    private fun range(prepared: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(index(prepared, start), index(prepared, endExclusive))

    private fun index(prepared: TextSnapshot, ordinal: Int) = prepared.textIndexAtScalarBoundary(ordinal)

    private fun fontFixture(): FontFixture {
        val source = FontSource(fixtureBytes("/fonts/dejavu/DejaVuSans.ttf"), FontSourceProvenance("DejaVu Sans"))
        val parsed = SfntReader.readMetadata(source).successValue()
        val catalog = EmbeddedFontCatalog(source, parsed)
        val face = catalog.resolveFace(FontFaceRequest(0), FontAccessRequirementsSnapshot.layoutOnly()).successValue()
        return FontFixture(catalog, face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(2048f))).successValue())
    }

    private fun fixtureBytes(resource: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 10_000,
        maxPoints = 100_000,
        maxCompositeDepth = 32,
        maxCompositeComponents = 10_000,
    )

    private fun <T> FontOperationResult<T>.successValue(): T =
        assertIs<FontOperationResult.Success<T>>(this).value

    private fun EditableLineResult.successValue(): org.graphiks.kalligraphie.api.EditableLine =
        assertIs<EditableLineResult.Success>(this).line

    private data class FontFixture(
        val catalog: EmbeddedFontCatalog,
        val instance: FontInstance,
    )

    private companion object {
        val featurePolicy: ShapingFeaturePolicy = ShapingFeaturePolicy(
            policyId = "manual-audited-scenario",
            version = "1",
            application = ShapingFeaturePolicyApplication.PINNED_BACKEND_DEFAULTS,
        )
        val backendIdentity: ShapingBackendIdentity = ShapingBackendIdentity(
            backendId = "manual-audited-scenario",
            nativeVersion = "1",
            nativeSourceRevision = "manual-audited-scenario",
            nativeArtifactId = "manual-audited-scenario",
            nativeArtifactSha256 = "0".repeat(64),
            featurePolicy = featurePolicy,
            configurationFingerprint = "manual-audited-scenario",
        )
    }
}
