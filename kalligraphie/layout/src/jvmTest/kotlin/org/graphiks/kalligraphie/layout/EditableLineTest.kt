package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.CaretBoundaryEdge
import org.graphiks.kalligraphie.api.CaretCandidate
import org.graphiks.kalligraphie.api.CaretPosition
import org.graphiks.kalligraphie.api.CaretStrength
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineRequest
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontFaceRequest
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GdefLigatureCaretFact
import org.graphiks.kalligraphie.api.GdefLigatureCaretState
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutPoint
import org.graphiks.kalligraphie.api.LayoutSegment
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LayoutVector
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.LogicalNavigationDirection
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PositionedGlyph
import org.graphiks.kalligraphie.api.PositionedGlyphRun
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EditableLineTest {
    @Test
    fun requestRejectsAShapingGraphemePartitionThatContradictsUnicodeAnalysis() {
        val prepared = text("x\u0301")
        val font = fontFixture().instance
        val analysis = analysis(
            prepared = prepared,
            logicalLevels = listOf(0),
            visualLevels = listOf(0),
            graphemeBoundaries = listOf(0, 2),
        )
        val contradictoryRun = shapedRun(
            prepared = prepared,
            font = font,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            level = 0,
            glyphs = listOf(glyph(50, 10f, 0), glyph(51, 0f, 1)),
        )

        assertFailsWith<IllegalArgumentException> {
            EditableLineRequest(
                unicodeAnalysis = analysis,
                shapedGlyphRuns = listOf(contradictoryRun),
                baseDirection = ShapingDirection.LEFT_TO_RIGHT,
                font = font,
                verticalMetrics = LineVerticalMetrics(ascent = LayoutUnit(8f), descent = LayoutUnit(2f)),
                materialization = EditableLineMaterialization.LayoutOnly,
            )
        }
    }

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
                baseDirection = ShapingDirection.RIGHT_TO_LEFT,
                font = font,
                verticalMetrics = LineVerticalMetrics(ascent = LayoutUnit(8f), descent = LayoutUnit(2f)),
                materialization = EditableLineMaterialization.LayoutOnly,
                emptyLineBidiLevel = 1,
            ),
        ).successValue()

        assertEquals(
            listOf(CaretAffinity.DOWNSTREAM, CaretAffinity.UPSTREAM),
            line.allCaretCandidates.map { it.position.affinity },
        )
        assertTrue(line.allCaretCandidates.all { it.direction == ShapingDirection.RIGHT_TO_LEFT })
        assertTrue(line.allCaretCandidates.all { it.bidiLevel == 1 })
        assertTrue(line.allCaretCandidates.all { it.strength == CaretStrength.STRONG })
        assertTrue(line.allCaretCandidates.all { it.visualRunOrder == CaretCandidate.NO_POSITIONED_RUN })
    }

    @Test
    fun leftToRightLinePublishesFinalGlyphPositionsAndGraphemeCarets() {
        val prepared = text("ab")
        val font = fontFixture().instance
        val analysis = analysis(prepared, listOf(0), listOf(0))
        val line = layout(
            analysis = analysis,
            font = font,
            baseDirection = ShapingDirection.LEFT_TO_RIGHT,
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
    fun leftToRightCaretsFollowTheSignedGlyphPenPathInsteadOfNumericExtrema() {
        val prepared = text("ab")
        val font = fontFixture().instance
        val line = layout(
            analysis = analysis(prepared, listOf(0), listOf(0)),
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.LEFT_TO_RIGHT,
                    level = 0,
                    glyphs = listOf(glyph(12, 10f, 0), glyph(13, -20f, 1)),
                ),
            ),
        )

        assertEquals(listOf(0f, 10f, -10f), caretXs(line, prepared, 0, 1, 2))
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
    fun rightToLeftCaretsFollowLogicalClustersAcrossSignedVisualAdvances() {
        val prepared = text("אב")
        val font = fontFixture().instance
        val line = layout(
            analysis = analysis(prepared, listOf(1), listOf(1)),
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.RIGHT_TO_LEFT,
                    level = 1,
                    glyphs = listOf(glyph(22, 10f, 1), glyph(23, -20f, 0)),
                ),
            ),
        )

        assertEquals(listOf(-10f, 10f, 0f), caretXs(line, prepared, 0, 1, 2))
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

        val candidates = line.caretCandidates(index(prepared, 1))
        assertEquals(listOf(10f, 30f), candidates.map { it.geometry.start.x.value }.sorted())
        assertEquals(
            mapOf(
                ShapingDirection.LEFT_TO_RIGHT to CaretStrength.STRONG,
                ShapingDirection.RIGHT_TO_LEFT to CaretStrength.WEAK,
            ),
            candidates.associate { it.direction to it.strength },
        )
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
    fun rightToLeftGdefCaretsFollowLogicalOrderAcrossANegativeAdvance() {
        val prepared = text("ffi")
        val font = fontFixture().instance
        val line = layout(
            analysis = analysis(prepared, listOf(1), listOf(1)),
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.RIGHT_TO_LEFT,
                    level = 1,
                    glyphs = listOf(glyph(44, -30f, 0)),
                    clusters = listOf(cluster(prepared, 0, 3, 0)),
                    ligatureFacts = listOf(
                        GdefLigatureCaretFact(
                            glyphIndex = 0,
                            state = GdefLigatureCaretState.AVAILABLE,
                            logicalSourceBoundaries = listOf(index(prepared, 1), index(prepared, 2)),
                            positions = listOf(LayoutUnit(-20f), LayoutUnit(-10f)),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(-30f, -20f, -10f, 0f), caretXs(line, prepared, 0, 1, 2, 3))
        assertTrue(line.diagnostics.none { it.code == "layout.invalid-ligature-caret-data" })
    }

    @Test
    fun rightToLeftMissingGdefUsesCompleteLogicalFallbackAcrossANegativeAdvance() {
        val prepared = text("ffi")
        val font = fontFixture().instance
        val line = layout(
            analysis = analysis(prepared, listOf(1), listOf(1)),
            font = font,
            runs = listOf(
                shapedRun(
                    prepared = prepared,
                    font = font,
                    direction = ShapingDirection.RIGHT_TO_LEFT,
                    level = 1,
                    glyphs = listOf(glyph(45, -30f, 0)),
                    clusters = listOf(cluster(prepared, 0, 3, 0)),
                    ligatureFacts = listOf(
                        GdefLigatureCaretFact(
                            glyphIndex = 0,
                            state = GdefLigatureCaretState.ABSENT,
                            logicalSourceBoundaries = listOf(index(prepared, 1), index(prepared, 2)),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(-30f, -20f, -10f, 0f), caretXs(line, prepared, 0, 1, 2, 3))
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
                    clusters = listOf(
                        cluster(prepared, 0, 1, 0, admissibleBoundaries = listOf(0)),
                        cluster(prepared, 1, 2, 1, admissibleBoundaries = listOf(2)),
                    ),
                    graphemeClusters = listOf(prepared.range),
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
    fun reversedPartialBidiSelectionUsesEachOwningRunCandidate() {
        val prepared = text("aאבb")
        val font = fontFixture().instance
        val analysis = analysis(prepared, logicalLevels = listOf(0, 1, 0), visualLevels = listOf(0, 1, 0), bidiBoundaries = listOf(0, 1, 3, 4))
        val line = layout(
            analysis,
            font,
            listOf(
                shapedRun(prepared, font, range(prepared, 0, 1), ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(84, 10f, 0))),
                shapedRun(prepared, font, range(prepared, 1, 3), ShapingDirection.RIGHT_TO_LEFT, 1, listOf(glyph(85, 10f, 1), glyph(86, 10f, 0))),
                shapedRun(prepared, font, range(prepared, 3, 4), ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(87, 10f, 0))),
            ),
        )

        assertEquals(
            listOf(10f to 20f, 30f to 40f),
            line.selectionGeometry(
                CaretPosition(index(prepared, 4), CaretAffinity.UPSTREAM),
                CaretPosition(index(prepared, 2), CaretAffinity.DOWNSTREAM),
            ).map { it.left.value to it.right.value },
        )
    }

    @Test
    fun verticalHitTestTieStillUsesTheCandidateVisualOrder() {
        val prepared = text("ab")
        val font = fontFixture().instance
        val line = layout(
            analysis(prepared, listOf(0), listOf(0)),
            font,
            listOf(shapedRun(prepared, font, ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(88, 10f, 0), glyph(89, 10f, 1)))),
        )

        assertEquals(
            index(prepared, 1),
            line.hitTest(LayoutPoint(LayoutUnit(15f), LayoutUnit(100f))).position.index,
        )
    }

    @Test
    fun horizontalLineRequestRejectsANonZeroVerticalAdvance() {
        val prepared = text("a")
        val font = fontFixture().instance
        val analysis = analysis(prepared, listOf(0), listOf(0))
        val run = shapedRun(
            prepared = prepared,
            font = font,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            level = 0,
            glyphs = listOf(glyph(89, 10f, 0, yAdvance = 1f)),
        )

        assertFailsWith<IllegalArgumentException> {
            EditableLineRequest(
                unicodeAnalysis = analysis,
                shapedGlyphRuns = listOf(run),
                baseDirection = ShapingDirection.LEFT_TO_RIGHT,
                font = font,
                verticalMetrics = LineVerticalMetrics(LayoutUnit(8f), LayoutUnit(2f)),
                materialization = EditableLineMaterialization.LayoutOnly,
            )
        }
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
    fun positionedRunRejectsAClusterThatOnlyReusesTheSourceToken() {
        val prepared = text("a")
        val font = fontFixture().instance
        val sourceRun = shapedRun(
            prepared = prepared,
            font = font,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            level = 0,
            glyphs = listOf(glyph(93, 10f, 0)),
        )
        val foreignCluster = cluster(prepared, 0, 1, 0)
        val positionedGlyph = PositionedGlyph(
            shapedGlyph = sourceRun.glyphs.single(),
            sourceClusters = listOf(foreignCluster),
            origin = LayoutPoint(LayoutUnit(0f), LayoutUnit(0f)),
            advance = LayoutVector(LayoutUnit(10f), LayoutUnit(0f)),
            renderAssetKey = null,
            materializationCertificate = null,
        )

        assertFailsWith<IllegalArgumentException> {
            PositionedGlyphRun(sourceRun = sourceRun, visualOrder = 0, renderAssetKey = null, glyphs = listOf(positionedGlyph))
        }
    }

    @Test
    fun positionedGlyphRejectsAnAdvanceDifferentFromItsShapingResult() {
        val prepared = text("a")
        val font = fontFixture().instance
        val sourceRun = shapedRun(
            prepared = prepared,
            font = font,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            level = 0,
            glyphs = listOf(glyph(92, 10f, 0)),
        )

        assertFailsWith<IllegalArgumentException> {
            PositionedGlyph(
                shapedGlyph = sourceRun.glyphs.single(),
                sourceClusters = sourceRun.clusters,
                origin = LayoutPoint(LayoutUnit(0f), LayoutUnit(0f)),
                advance = LayoutVector(LayoutUnit(11f), LayoutUnit(0f)),
                renderAssetKey = null,
                materializationCertificate = null,
            )
        }
    }

    @Test
    fun positionedGlyphRejectsACertificateFromAnotherRenderAssetKey() {
        val prepared = text("a")
        val font = fontFixture().instance
        val sourceRun = shapedRun(
            prepared = prepared,
            font = font,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            level = 0,
            glyphs = listOf(glyph(96, 10f, 0)),
        )
        val expectedKey = FontRenderAssetKey(font.key, FontRenderVariantKey.default, outlineProfile())
        val foreignKey = expectedKey.copy(variant = FontRenderVariantKey("foreign"))

        assertFailsWith<IllegalArgumentException> {
            PositionedGlyph(
                shapedGlyph = sourceRun.glyphs.single(),
                sourceClusters = sourceRun.clusters,
                origin = LayoutPoint(LayoutUnit(0f), LayoutUnit(0f)),
                advance = LayoutVector(LayoutUnit(10f), LayoutUnit(0f)),
                renderAssetKey = expectedKey,
                materializationCertificate = org.graphiks.kalligraphie.api.GlyphMaterializationCertificate(
                    assetKey = foreignKey,
                    glyphId = sourceRun.glyphs.single().glyphId,
                    route = GlyphMaterializationRoute.OUTLINE,
                ),
            )
        }
    }

    @Test
    fun positionedRunRejectsAGlyphUsingAnotherRenderAssetKey() {
        val prepared = text("a")
        val font = fontFixture().instance
        val sourceRun = shapedRun(
            prepared = prepared,
            font = font,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            level = 0,
            glyphs = listOf(glyph(97, 10f, 0)),
        )
        val glyphKey = FontRenderAssetKey(font.key, FontRenderVariantKey.default, outlineProfile())
        val runKey = glyphKey.copy(variant = FontRenderVariantKey("foreign"))
        val positionedGlyph = PositionedGlyph(
            shapedGlyph = sourceRun.glyphs.single(),
            sourceClusters = sourceRun.clusters,
            origin = LayoutPoint(LayoutUnit(0f), LayoutUnit(0f)),
            advance = LayoutVector(LayoutUnit(10f), LayoutUnit(0f)),
            renderAssetKey = glyphKey,
            materializationCertificate = org.graphiks.kalligraphie.api.GlyphMaterializationCertificate(
                assetKey = glyphKey,
                glyphId = sourceRun.glyphs.single().glyphId,
                route = GlyphMaterializationRoute.OUTLINE,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            PositionedGlyphRun(
                sourceRun = sourceRun,
                visualOrder = 0,
                renderAssetKey = runKey,
                glyphs = listOf(positionedGlyph),
            )
        }
    }

    @Test
    fun editableLineRejectsPositionedRunsCertifiedByDifferentRenderAssets() {
        val prepared = text("ab")
        val font = fontFixture().instance
        val firstRun = shapedRun(
            prepared,
            font,
            range(prepared, 0, 1),
            ShapingDirection.LEFT_TO_RIGHT,
            0,
            listOf(glyph(98, 10f, 0)),
        )
        val secondRun = shapedRun(
            prepared,
            font,
            range(prepared, 1, 2),
            ShapingDirection.LEFT_TO_RIGHT,
            0,
            listOf(glyph(99, 10f, 0)),
        )
        val firstKey = FontRenderAssetKey(font.key, FontRenderVariantKey.default, outlineProfile())
        val secondKey = firstKey.copy(variant = FontRenderVariantKey("foreign"))

        assertFailsWith<IllegalArgumentException> {
            EditableLine(
                range = prepared.range,
                baseDirection = ShapingDirection.LEFT_TO_RIGHT,
                verticalMetrics = LineVerticalMetrics(LayoutUnit(8f), LayoutUnit(2f)),
                positionedGlyphRuns = listOf(
                    certifiedPositionedRun(firstRun, 0, firstKey),
                    certifiedPositionedRun(secondRun, 1, secondKey),
                ),
                caretCandidates = listOf(
                    candidate(firstRun.range.start, 0, 0f, CaretBoundaryEdge.LOGICAL_START, visualOrder = 0),
                    candidate(firstRun.range.endExclusive, 0, 10f, CaretBoundaryEdge.LOGICAL_END, visualOrder = 1),
                    candidate(secondRun.range.start, 1, 10f, CaretBoundaryEdge.LOGICAL_START, visualOrder = 2),
                    candidate(secondRun.range.endExclusive, 1, 20f, CaretBoundaryEdge.LOGICAL_END, visualOrder = 3),
                ),
            )
        }
    }

    @Test
    fun editableLineRejectsPositionedRunsThatDoNotCoverItsCompleteSourceRange() {
        val prepared = text("ab")
        val font = fontFixture().instance
        val sourceRun = shapedRun(
            prepared = prepared,
            font = font,
            range = range(prepared, 0, 1),
            direction = ShapingDirection.LEFT_TO_RIGHT,
            level = 0,
            glyphs = listOf(glyph(94, 10f, 0)),
        )

        assertFailsWith<IllegalArgumentException> {
            EditableLine(
                range = prepared.range,
                baseDirection = ShapingDirection.LEFT_TO_RIGHT,
                verticalMetrics = LineVerticalMetrics(LayoutUnit(8f), LayoutUnit(2f)),
                positionedGlyphRuns = listOf(positionedRun(sourceRun)),
                caretCandidates = listOf(
                    candidate(sourceRun.range.start, 0, 0f, CaretBoundaryEdge.LOGICAL_START),
                    candidate(sourceRun.range.endExclusive, 0, 10f, CaretBoundaryEdge.LOGICAL_END),
                ),
            )
        }
    }

    @Test
    fun editableLineRejectsACaretCandidateThatNamesNoPositionedRun() {
        val prepared = text("a")
        val font = fontFixture().instance
        val sourceRun = shapedRun(
            prepared = prepared,
            font = font,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            level = 0,
            glyphs = listOf(glyph(95, 10f, 0)),
        )

        assertFailsWith<IllegalArgumentException> {
            EditableLine(
                range = prepared.range,
                baseDirection = ShapingDirection.LEFT_TO_RIGHT,
                verticalMetrics = LineVerticalMetrics(LayoutUnit(8f), LayoutUnit(2f)),
                positionedGlyphRuns = listOf(positionedRun(sourceRun)),
                caretCandidates = listOf(
                    candidate(sourceRun.range.start, 1, 0f, CaretBoundaryEdge.LOGICAL_START),
                    candidate(sourceRun.range.endExclusive, 1, 10f, CaretBoundaryEdge.LOGICAL_END),
                ),
            )
        }
    }

    @Test
    fun editableLineRejectsCaretGeometryOutsideItsVerticalMetrics() {
        val prepared = text("a")
        val font = fontFixture().instance
        val sourceRun = shapedRun(
            prepared = prepared,
            font = font,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            level = 0,
            glyphs = listOf(glyph(106, 10f, 0)),
        )

        assertFailsWith<IllegalArgumentException> {
            EditableLine(
                range = prepared.range,
                baseDirection = ShapingDirection.LEFT_TO_RIGHT,
                verticalMetrics = LineVerticalMetrics(LayoutUnit(8f), LayoutUnit(2f)),
                positionedGlyphRuns = listOf(positionedRun(sourceRun)),
                caretCandidates = listOf(
                    candidate(sourceRun.range.start, 0, 0f, CaretBoundaryEdge.LOGICAL_START, top = -9f),
                    candidate(sourceRun.range.endExclusive, 0, 10f, CaretBoundaryEdge.LOGICAL_END),
                ),
            )
        }
    }

    @Test
    fun editableLineRejectsCaretCandidatesOutsidePhysicalVisualOrder() {
        val prepared = text("a")
        val font = fontFixture().instance
        val sourceRun = shapedRun(
            prepared = prepared,
            font = font,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            level = 0,
            glyphs = listOf(glyph(107, 10f, 0)),
        )

        assertFailsWith<IllegalArgumentException> {
            EditableLine(
                range = prepared.range,
                baseDirection = ShapingDirection.LEFT_TO_RIGHT,
                verticalMetrics = LineVerticalMetrics(LayoutUnit(8f), LayoutUnit(2f)),
                positionedGlyphRuns = listOf(positionedRun(sourceRun)),
                caretCandidates = listOf(
                    candidate(sourceRun.range.start, 0, 10f, CaretBoundaryEdge.LOGICAL_START),
                    candidate(sourceRun.range.endExclusive, 0, 0f, CaretBoundaryEdge.LOGICAL_END),
                ),
            )
        }
    }

    @Test
    fun renderableLineBindsOutlineAndEmptyGlyphsToTheExactTrueTypeAssetKey() {
        val fixture = fontFixture()
        val prepared = text("f ")
        val resolver = fixture.catalog.openAssetResolver().successValue()
        val profile = outlineProfile()
        val expectedKey = FontRenderAssetKey(
            fontInstanceKey = fixture.instance.key,
            variant = FontRenderVariantKey.default,
            outlineProfile = profile,
        )
        try {
            val line = layout(
                analysis(prepared, listOf(0), listOf(0)),
                fixture.instance,
                listOf(
                    shapedRun(
                        prepared,
                        fixture.instance,
                        ShapingDirection.LEFT_TO_RIGHT,
                        0,
                        listOf(glyph(73, 10f, 0), glyph(3, 5f, 1)),
                    ),
                ),
                EditableLineMaterialization.Renderable(
                    resolver = resolver,
                    variant = FontRenderVariantKey.default,
                    outlineProfile = profile,
                ),
            )

            val run = line.positionedGlyphRuns.single()
            assertEquals(
                listOf(GlyphMaterializationRoute.OUTLINE, GlyphMaterializationRoute.EMPTY),
                run.glyphs.map { it.materializationCertificate?.route },
            )
            assertEquals(expectedKey, run.renderAssetKey)
            assertTrue(run.glyphs.all { it.renderAssetKey == expectedKey })
            assertTrue(run.glyphs.all { it.materializationCertificate?.assetKey == expectedKey })
        } finally {
            resolver.close()
        }
    }

    @Test
    fun renderableLineRejectsAnAcquiredAssetWhoseKeyDoesNotMatchTheRequest() {
        val fixture = fontFixture()
        val prepared = text("a")
        val profile = outlineProfile()
        val foreignKey = FontRenderAssetKey(
            fontInstanceKey = fixture.instance.key,
            variant = FontRenderVariantKey("foreign"),
            outlineProfile = profile,
        )
        val asset = StrictRenderAsset(
            key = foreignKey,
            scriptedResults = listOf(FontOperationResult.Success(GlyphRepresentation.Empty)),
        )
        val font = AssetBackedFontInstance(fixture.instance, asset)
        val resolver = fixture.catalog.openAssetResolver().successValue()
        try {
            val result = layoutResult(
                analysis = analysis(prepared, listOf(0), listOf(0)),
                font = font,
                runs = listOf(shapedRun(prepared, font, ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(100, 10f, 0)))),
                materialization = EditableLineMaterialization.Renderable(
                    resolver = resolver,
                    variant = FontRenderVariantKey.default,
                    outlineProfile = profile,
                ),
            )

            val failure = assertIs<EditableLineResult.Failure>(result)
            assertIs<FontError.InvalidFontData>(assertIs<org.graphiks.kalligraphie.api.EditableLineError.FontMaterializationFailure>(failure.error).fontError)
            assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(100))).error)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun renderableLineRejectsAnOutlineWhoseGlyphIdDiffersFromTheRequest() {
        val fixture = fontFixture()
        val prepared = text("a")
        val profile = outlineProfile()
        val key = FontRenderAssetKey(fixture.instance.key, FontRenderVariantKey.default, profile)
        val asset = StrictRenderAsset(
            key = key,
            scriptedResults = listOf(
                FontOperationResult.Success(
                    GlyphRepresentation.Outline(
                        GlyphOutlineIR(
                            glyphId = 999,
                            unitsPerEm = 1000,
                            bounds = DesignBounds.empty,
                            commands = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        val font = AssetBackedFontInstance(fixture.instance, asset)
        val resolver = fixture.catalog.openAssetResolver().successValue()
        try {
            val result = layoutResult(
                analysis = analysis(prepared, listOf(0), listOf(0)),
                font = font,
                runs = listOf(shapedRun(prepared, font, ShapingDirection.LEFT_TO_RIGHT, 0, listOf(glyph(101, 10f, 0)))),
                materialization = EditableLineMaterialization.Renderable(
                    resolver = resolver,
                    variant = FontRenderVariantKey.default,
                    outlineProfile = profile,
                ),
            )

            val failure = assertIs<EditableLineResult.Failure>(result)
            assertIs<FontError.InvalidFontData>(assertIs<org.graphiks.kalligraphie.api.EditableLineError.FontMaterializationFailure>(failure.error).fontError)
            assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(101))).error)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun materializationStopsAfterTheFirstFontFailureAndClosesTheAsset() {
        val fixture = fontFixture()
        val prepared = text("ab")
        val profile = outlineProfile()
        val key = FontRenderAssetKey(fixture.instance.key, FontRenderVariantKey.default, profile)
        val firstError = FontError.GlyphOutOfRange(102)
        val asset = StrictRenderAsset(key, listOf(FontOperationResult.Failure(firstError)))
        val font = AssetBackedFontInstance(fixture.instance, asset)
        val resolver = fixture.catalog.openAssetResolver().successValue()
        try {
            val result = layoutResult(
                analysis = analysis(prepared, listOf(0), listOf(0)),
                font = font,
                runs = listOf(
                    shapedRun(
                        prepared,
                        font,
                        ShapingDirection.LEFT_TO_RIGHT,
                        0,
                        listOf(glyph(102, 10f, 0), glyph(103, 10f, 1)),
                    ),
                ),
                materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantKey.default, profile),
            )

            val failure = assertIs<EditableLineResult.Failure>(result)
            assertEquals(firstError, assertIs<org.graphiks.kalligraphie.api.EditableLineError.FontMaterializationFailure>(failure.error).fontError)
            assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(103))).error)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun materializationStopsAfterTheFirstCancellationAndClosesTheAsset() {
        val fixture = fontFixture()
        val prepared = text("ab")
        val profile = outlineProfile()
        val key = FontRenderAssetKey(fixture.instance.key, FontRenderVariantKey.default, profile)
        val asset = StrictRenderAsset(key, listOf(FontOperationResult.Cancelled()))
        val font = AssetBackedFontInstance(fixture.instance, asset)
        val resolver = fixture.catalog.openAssetResolver().successValue()
        try {
            val result = layoutResult(
                analysis = analysis(prepared, listOf(0), listOf(0)),
                font = font,
                runs = listOf(
                    shapedRun(
                        prepared,
                        font,
                        ShapingDirection.LEFT_TO_RIGHT,
                        0,
                        listOf(glyph(104, 10f, 0), glyph(105, 10f, 1)),
                    ),
                ),
                materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantKey.default, profile),
            )

            assertIs<EditableLineResult.Cancelled>(result)
            assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(105))).error)
        } finally {
            resolver.close()
        }
    }

    private fun layout(
        analysis: UnicodeAnalysis,
        font: FontInstance,
        runs: List<ShapedGlyphRun>,
        materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
        baseDirection: ShapingDirection = if (analysis.logicalBidiRuns.firstOrNull()?.level?.rem(2) == 1) {
            ShapingDirection.RIGHT_TO_LEFT
        } else {
            ShapingDirection.LEFT_TO_RIGHT
        },
    ) = layoutResult(analysis, font, runs, materialization, baseDirection).successValue()

    private fun layoutResult(
        analysis: UnicodeAnalysis,
        font: FontInstance,
        runs: List<ShapedGlyphRun>,
        materialization: EditableLineMaterialization,
        baseDirection: ShapingDirection = if (analysis.logicalBidiRuns.firstOrNull()?.level?.rem(2) == 1) {
            ShapingDirection.RIGHT_TO_LEFT
        } else {
            ShapingDirection.LEFT_TO_RIGHT
        },
    ): EditableLineResult = ExactEditableLineLayouter.layout(
        EditableLineRequest(
            unicodeAnalysis = analysis,
            shapedGlyphRuns = runs,
            baseDirection = baseDirection,
            font = font,
            verticalMetrics = LineVerticalMetrics(ascent = LayoutUnit(8f), descent = LayoutUnit(2f)),
            materialization = materialization,
        ),
    )

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
        graphemeClusters: List<TextRange>? = null,
    ): ShapedGlyphRun = shapedRun(prepared, font, prepared.range, direction, level, glyphs, clusters, ligatureFacts, graphemeClusters)

    private fun shapedRun(
        prepared: TextSnapshot,
        font: FontInstance,
        range: TextRange,
        direction: ShapingDirection,
        level: Int,
        glyphs: List<ShapedGlyph>,
        clusters: List<ShaperCluster>? = null,
        ligatureFacts: List<GdefLigatureCaretFact> = emptyList(),
        graphemeClusters: List<TextRange>? = null,
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
        graphemeClusters = graphemeClusters ?: rangePartitions(prepared, range),
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
        yAdvance: Float = 0f,
    ): ShapedGlyph = ShapedGlyph(
        glyphId = GlyphId(glyphId),
        xAdvance = LayoutUnit(advance),
        yAdvance = LayoutUnit(yAdvance),
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

    private fun cluster(
        prepared: TextSnapshot,
        start: Int,
        endExclusive: Int,
        token: Int,
        admissibleBoundaries: List<Int> = (start..endExclusive).toList(),
    ): ShaperCluster =
        ShaperCluster(
            token = ShaperClusterToken(token),
            sourceRange = range(prepared, start, endExclusive),
            scalarRanges = prepared.scalarRanges(range(prepared, start, endExclusive)),
            admissibleGraphemeBoundaries = admissibleBoundaries.map { index(prepared, it) },
        )

    private fun caretXs(line: org.graphiks.kalligraphie.api.EditableLine, prepared: TextSnapshot, vararg ordinals: Int): List<Float> =
        ordinals.map { ordinal -> line.caretCandidates(index(prepared, ordinal)).single().geometry.start.x.value }

    private fun positionedRun(sourceRun: ShapedGlyphRun): PositionedGlyphRun = PositionedGlyphRun(
        sourceRun = sourceRun,
        visualOrder = 0,
        renderAssetKey = null,
        glyphs = sourceRun.glyphs.map { shapedGlyph ->
            PositionedGlyph(
                shapedGlyph = shapedGlyph,
                sourceClusters = shapedGlyph.clusterTokens.map { token -> sourceRun.clusters.single { it.token == token } },
                origin = LayoutPoint(LayoutUnit(0f), LayoutUnit(0f)),
                advance = LayoutVector(shapedGlyph.xAdvance, shapedGlyph.yAdvance),
                renderAssetKey = null,
                materializationCertificate = null,
            )
        },
    )

    private fun certifiedPositionedRun(
        sourceRun: ShapedGlyphRun,
        visualOrder: Int,
        assetKey: FontRenderAssetKey,
    ): PositionedGlyphRun = PositionedGlyphRun(
        sourceRun = sourceRun,
        visualOrder = visualOrder,
        renderAssetKey = assetKey,
        glyphs = sourceRun.glyphs.map { shapedGlyph ->
            PositionedGlyph(
                shapedGlyph = shapedGlyph,
                sourceClusters = shapedGlyph.clusterTokens.map { token -> sourceRun.clusters.single { it.token == token } },
                origin = LayoutPoint(LayoutUnit(0f), LayoutUnit(0f)),
                advance = LayoutVector(shapedGlyph.xAdvance, shapedGlyph.yAdvance),
                renderAssetKey = assetKey,
                materializationCertificate = org.graphiks.kalligraphie.api.GlyphMaterializationCertificate(
                    assetKey = assetKey,
                    glyphId = shapedGlyph.glyphId,
                    route = GlyphMaterializationRoute.OUTLINE,
                ),
            )
        },
    )

    private fun candidate(
        index: org.graphiks.kalligraphie.api.TextIndex,
        visualRunOrder: Int,
        x: Float,
        edge: CaretBoundaryEdge,
        visualOrder: Int = if (edge == CaretBoundaryEdge.LOGICAL_END) 1 else 0,
        top: Float = -8f,
        bottom: Float = 2f,
    ): CaretCandidate = CaretCandidate(
        position = CaretPosition(
            index,
            if (edge == CaretBoundaryEdge.LOGICAL_END) CaretAffinity.UPSTREAM else CaretAffinity.DOWNSTREAM,
        ),
        geometry = LayoutSegment(
            LayoutPoint(LayoutUnit(x), LayoutUnit(top)),
            LayoutPoint(LayoutUnit(x), LayoutUnit(bottom)),
        ),
        visualOrder = visualOrder,
        visualRunOrder = visualRunOrder,
        bidiLevel = 0,
        direction = ShapingDirection.LEFT_TO_RIGHT,
        strength = CaretStrength.STRONG,
        edge = edge,
    )

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

    private class AssetBackedFontInstance(
        delegate: FontInstance,
        private val asset: FontRenderAssetHandle,
    ) : FontInstance by delegate {
        override fun acquireRenderAsset(
            resolver: org.graphiks.kalligraphie.api.FontAssetResolverHandle,
            variant: FontRenderVariantKey,
            requirements: FontAccessRequirementsSnapshot,
        ): FontOperationResult<FontRenderAssetHandle> = FontOperationResult.Success(asset)
    }

    private class StrictRenderAsset(
        override val key: FontRenderAssetKey,
        private val scriptedResults: List<FontOperationResult<GlyphRepresentation>>,
    ) : FontRenderAssetHandle {
        override val faceId = key.fontInstanceKey.face
        private var nextResult: Int = 0
        private var closed: Boolean = false

        override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> {
            if (closed) return FontOperationResult.Failure(FontError.ResourceClosed("Test render asset is closed."))
            check(nextResult in scriptedResults.indices) {
                "Render asset was queried after its first terminal result."
            }
            return scriptedResults[nextResult++]
        }

        override fun close(): FontOperationResult<Unit> {
            closed = true
            return FontOperationResult.Success(Unit)
        }
    }

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
