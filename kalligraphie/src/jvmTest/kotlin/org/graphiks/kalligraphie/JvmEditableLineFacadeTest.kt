package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.CaretBoundaryEdge
import org.graphiks.kalligraphie.api.CaretPosition
import org.graphiks.kalligraphie.api.CaretStrength
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import org.graphiks.kalligraphie.api.GlyphProvenance
import org.graphiks.kalligraphie.api.GlyphProvenanceRole
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.LineControlKind
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.ParagraphPositioningPolicy
import org.graphiks.kalligraphie.api.TabStop
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.api.SourceEncoding
import org.graphiks.kalligraphie.api.SourceOffset
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmEditableLineFacadeTest {
    @Test
    fun java_consumer_using_the_original_constructor_still_lays_out_real_liberation_text() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("A".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val request = LegacyJvmEditableLineFacadeRequestFactory.create(
                snapshot,
                fixture.font,
                BaseDirection.LEFT_TO_RIGHT,
                "en",
                JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                emptyList(),
                LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                EditableLineMaterialization.LayoutOnly,
                null,
                CancellationToken.none,
                UnicodeAnalysisProfile.unbounded,
                ShapingResourceProfile.unbounded,
            )

            val glyph = assertIs<EditableLineResult.Success>(JvmEditableLineFacade.layout(request))
                .line.positionedGlyphRuns.single().glyphs.single()
            assertEquals(GlyphId(36), glyph.shapedGlyph.glyphId)
            assertEquals(0f, glyph.origin.x.value)
            assertEquals(1_366f, glyph.advance.x.value)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun hard_line_controls_are_rejected_with_their_exact_scalar_range_before_cancellation() {
        val cases = listOf(
            HardSeparatorOracle("\r", LineControlKind.CARRIAGE_RETURN, scalarEnd = 2, utf8SourceRange = 4 to 5, utf16SourceRange = 2 to 3),
            HardSeparatorOracle("\n", LineControlKind.LINE_FEED, scalarEnd = 2, utf8SourceRange = 4 to 5, utf16SourceRange = 2 to 3),
            HardSeparatorOracle(
                "\r\n",
                LineControlKind.CARRIAGE_RETURN_LINE_FEED,
                scalarEnd = 3,
                utf8SourceRange = 4 to 6,
                utf16SourceRange = 2 to 4,
            ),
            HardSeparatorOracle("\u000B", LineControlKind.VERTICAL_TAB, scalarEnd = 2, utf8SourceRange = 4 to 5, utf16SourceRange = 2 to 3),
            HardSeparatorOracle("\u000C", LineControlKind.FORM_FEED, scalarEnd = 2, utf8SourceRange = 4 to 5, utf16SourceRange = 2 to 3),
            HardSeparatorOracle("\u0085", LineControlKind.NEXT_LINE, scalarEnd = 2, utf8SourceRange = 4 to 6, utf16SourceRange = 2 to 3),
            HardSeparatorOracle("\u2028", LineControlKind.LINE_SEPARATOR, scalarEnd = 2, utf8SourceRange = 4 to 7, utf16SourceRange = 2 to 3),
            HardSeparatorOracle("\u2029", LineControlKind.PARAGRAPH_SEPARATOR, scalarEnd = 2, utf8SourceRange = 4 to 7, utf16SourceRange = 2 to 3),
        )
        val fixture = renderableFixture()
        try {
            cases.forEach { case ->
                val text = "\uD83D\uDE00${case.text}B"
                listOf(SourceEncoding.UTF8, SourceEncoding.UTF16).forEach { encoding ->
                    val version = TextVersion.create()
                    val snapshot = when (encoding) {
                        SourceEncoding.UTF8 -> Kalligraphie.decodeUtf8(
                            version = version,
                            slices = listOf(TextSlice.Utf8(text.encodeToByteArray())),
                        ).snapshot

                        SourceEncoding.UTF16 -> Kalligraphie.decodeUtf16(
                            version = version,
                            slices = listOf(TextSlice.Utf16(text.toCharArray())),
                        ).snapshot
                    }

                    val result = JvmEditableLineFacade.layout(
                        lineRequest(
                            snapshot = snapshot,
                            font = fixture.font,
                            cancellationToken = CancellationToken.cancelled,
                        ),
                    )

                    val control = assertIs<EditableLineError.UnsupportedLineControl>(
                        assertIs<EditableLineResult.Failure>(result).error,
                    )
                    assertEquals(case.kind, control.kind)
                    assertEquals(
                        org.graphiks.kalligraphie.api.TextRange(
                            snapshot.textIndexAtScalarBoundary(1),
                            snapshot.textIndexAtScalarBoundary(case.scalarEnd),
                        ),
                        control.range,
                    )
                    val sourceRange = if (encoding == SourceEncoding.UTF8) case.utf8SourceRange else case.utf16SourceRange
                    assertEquals(
                        SourceOffset(version, encoding, sourceRange.first),
                        snapshot.textIndexToSource(control.range.start),
                    )
                    assertEquals(
                        SourceOffset(version, encoding, sourceRange.second),
                        snapshot.textIndexToSource(control.range.endExclusive),
                    )
                }
            }
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun horizontal_tab_without_positioning_is_rejected_with_its_exact_range_before_cancellation() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("A\tB".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                lineRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    cancellationToken = CancellationToken.cancelled,
                ),
            )

            val control = assertIs<EditableLineError.UnsupportedLineControl>(
                assertIs<EditableLineResult.Failure>(result).error,
            )
            assertEquals(LineControlKind.HORIZONTAL_TAB, control.kind)
            assertEquals(
                org.graphiks.kalligraphie.api.TextRange(
                    snapshot.textIndexAtScalarBoundary(1),
                    snapshot.textIndexAtScalarBoundary(2),
                ),
                control.range,
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun explicit_tab_stop_publishes_a_source_mapped_no_ink_control_instead_of_a_font_glyph() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("A\tB".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                lineRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    positioning = ParagraphPositioningPolicy(
                        tabStops = listOf(TabStop(LayoutUnit(3_000f))),
                    ),
                    materialization = EditableLineMaterialization.Renderable(
                        resolver = fixture.resolver,
                        variant = FontRenderVariantKey.default,
                        outlineProfile = OUTLINE_PROFILE,
                    ),
                    shapingResourceProfile = ShapingResourceProfile(maxScalars = 1),
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            val glyphs = line.positionedGlyphRuns.flatMap { it.glyphs }
            assertEquals(listOf(GlyphId(36), GlyphId(37)), glyphs.map { it.shapedGlyph.glyphId })
            assertEquals(listOf(0f, 3_000f), glyphs.map { it.origin.x.value })
            assertEquals(listOf(1_366f, 1_366f), glyphs.map { it.advance.x.value })
            assertTrue(glyphs.all { it.materializationCertificate?.route == GlyphMaterializationRoute.OUTLINE })
            val tab = line.positionedLineControls.single()
            assertEquals(LineControlKind.HORIZONTAL_TAB, tab.kind)
            assertEquals(
                org.graphiks.kalligraphie.api.TextRange(
                    snapshot.textIndexAtScalarBoundary(1),
                    snapshot.textIndexAtScalarBoundary(2),
                ),
                tab.sourceRange,
            )
            assertEquals(1_366f, tab.origin.x.value)
            assertEquals(1_634f, tab.advance.x.value)
            assertEquals(GlyphMaterializationRoute.EMPTY, tab.materializationRoute)
            assertEquals(
                (0..3).map(snapshot::textIndexAtScalarBoundary).toSet(),
                line.allCaretCandidates.map { it.position.index }.toSet(),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun tabLeaderGlyphsStayInsideTheCompleteSourceMappedControlSpan() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("A\tB".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val line = assertIs<EditableLineResult.Success>(
                JvmEditableLineFacade.layout(
                    lineRequest(
                        snapshot = snapshot,
                        font = fixture.font,
                        positioning = ParagraphPositioningPolicy(
                            tabStops = listOf(TabStop(LayoutUnit(3_000f), leader = 0x2E)),
                        ),
                        materialization = EditableLineMaterialization.Renderable(
                            resolver = fixture.resolver,
                            variant = FontRenderVariantKey.default,
                            outlineProfile = OUTLINE_PROFILE,
                        ),
                    ),
                ),
            ).line

            val glyphs = line.positionedGlyphRuns.flatMap { run -> run.glyphs }
            assertEquals(
                listOf(
                    Triple(GlyphId(36), 0f, 1_366f),
                    Triple(GlyphId(17), 1_366f, 569f),
                    Triple(GlyphId(17), 1_935f, 569f),
                    Triple(GlyphId(37), 3_000f, 1_366f),
                ),
                glyphs.map { glyph -> Triple(glyph.shapedGlyph.glyphId, glyph.origin.x.value, glyph.advance.x.value) },
            )
            assertTrue(glyphs.all { glyph -> glyph.materializationCertificate?.route == GlyphMaterializationRoute.OUTLINE })
            val leaders = glyphs.filter { glyph ->
                val provenance = glyph.provenance
                provenance is GlyphProvenance.Synthetic && provenance.role == GlyphProvenanceRole.TAB_LEADER
            }
            assertEquals(listOf(1_366f to 1_935f, 1_935f to 2_504f), leaders.map { glyph ->
                glyph.origin.x.value to glyph.origin.x.value + glyph.advance.x.value
            })
            val control = line.positionedLineControls.single()
            assertEquals(1_366f, control.origin.x.value)
            assertEquals(1_634f, control.advance.x.value)
            assertEquals(GlyphMaterializationRoute.EMPTY, control.materializationRoute)
            assertEquals(
                listOf(1_366f to 3_000f),
                line.selectionGeometry(
                    CaretPosition(snapshot.textIndexAtScalarBoundary(1), CaretAffinity.DOWNSTREAM),
                    CaretPosition(snapshot.textIndexAtScalarBoundary(2), CaretAffinity.UPSTREAM),
                ).map { fragment -> fragment.left.value to fragment.right.value },
            )
            assertEquals(
                listOf(
                    CaretOracle(0, CaretAffinity.DOWNSTREAM, 0f, 0, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(1, CaretAffinity.UPSTREAM, 1_366f, 1, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(1, CaretAffinity.DOWNSTREAM, 1_366f, 2, 1, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(2, CaretAffinity.UPSTREAM, 3_000f, 3, 1, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(2, CaretAffinity.DOWNSTREAM, 3_000f, 4, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(3, CaretAffinity.UPSTREAM, 4_366f, 5, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                ),
                caretOracles(snapshot, line.allCaretCandidates),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun repeated_tabs_use_the_literal_default_interval_without_publishing_font_glyphs() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("A\tA\tA".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val line = assertIs<EditableLineResult.Success>(
                JvmEditableLineFacade.layout(
                    lineRequest(
                        snapshot = snapshot,
                        font = fixture.font,
                        positioning = ParagraphPositioningPolicy(defaultTabInterval = LayoutUnit(2_000f)),
                        materialization = EditableLineMaterialization.Renderable(
                            resolver = fixture.resolver,
                            variant = FontRenderVariantKey.default,
                            outlineProfile = OUTLINE_PROFILE,
                        ),
                        shapingResourceProfile = ShapingResourceProfile(maxScalars = 1),
                    ),
                ),
            ).line

            val glyphs = line.positionedGlyphRuns.flatMap { it.glyphs }
            assertEquals(listOf(GlyphId(36), GlyphId(36), GlyphId(36)), glyphs.map { it.shapedGlyph.glyphId })
            assertEquals(listOf(0f, 2_000f, 4_000f), glyphs.map { it.origin.x.value })
            assertEquals(listOf(1_366f, 1_366f, 1_366f), glyphs.map { it.advance.x.value })
            assertTrue(glyphs.all { it.materializationCertificate?.route == GlyphMaterializationRoute.OUTLINE })
            assertEquals(
                listOf(1_366f to 634f, 3_366f to 634f),
                line.positionedLineControls.map { it.origin.x.value to it.advance.x.value },
            )
            assertTrue(line.positionedLineControls.all { it.materializationRoute == GlyphMaterializationRoute.EMPTY })
            assertEquals(
                listOf(
                    CaretOracle(0, CaretAffinity.DOWNSTREAM, 0f, 0, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(1, CaretAffinity.UPSTREAM, 1_366f, 1, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(1, CaretAffinity.DOWNSTREAM, 1_366f, 2, 1, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(2, CaretAffinity.UPSTREAM, 2_000f, 3, 1, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(2, CaretAffinity.DOWNSTREAM, 2_000f, 4, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(3, CaretAffinity.UPSTREAM, 3_366f, 5, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(3, CaretAffinity.DOWNSTREAM, 3_366f, 6, 3, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(4, CaretAffinity.UPSTREAM, 4_000f, 7, 3, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(4, CaretAffinity.DOWNSTREAM, 4_000f, 8, 4, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(5, CaretAffinity.UPSTREAM, 5_366f, 9, 4, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                ),
                caretOracles(snapshot, line.allCaretCandidates),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun right_to_left_tab_uses_the_literal_default_interval_and_keeps_exact_carets() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("\u05D0\t\u05D0".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val line = assertIs<EditableLineResult.Success>(
                JvmEditableLineFacade.layout(
                    lineRequest(
                        snapshot = snapshot,
                        font = fixture.font,
                        baseDirection = BaseDirection.RIGHT_TO_LEFT,
                        positioning = ParagraphPositioningPolicy(defaultTabInterval = LayoutUnit(2_000f)),
                        materialization = EditableLineMaterialization.Renderable(
                            resolver = fixture.resolver,
                            variant = FontRenderVariantKey.default,
                            outlineProfile = OUTLINE_PROFILE,
                        ),
                        shapingResourceProfile = ShapingResourceProfile(maxScalars = 1),
                    ),
                ),
            ).line

            assertEquals(listOf(
                Triple(GlyphId(1280), 714f, 1_286f),
                Triple(GlyphId(1280), 4_000f, 1_286f),
            ), line.positionedGlyphRuns.flatMap { it.glyphs }.map {
                Triple(it.shapedGlyph.glyphId, it.origin.x.value, it.advance.x.value)
            })
            assertEquals(listOf(Triple(2_000f, 2_000f, GlyphMaterializationRoute.EMPTY)), line.positionedLineControls.map {
                Triple(it.origin.x.value, it.advance.x.value, it.materializationRoute)
            })
            assertEquals(
                listOf(2_000f to 4_000f),
                line.selectionGeometry(
                    CaretPosition(snapshot.textIndexAtScalarBoundary(1), CaretAffinity.DOWNSTREAM),
                    CaretPosition(snapshot.textIndexAtScalarBoundary(2), CaretAffinity.UPSTREAM),
                ).map { fragment -> fragment.left.value to fragment.right.value },
            )
            assertEquals(
                listOf(
                    CaretOracle(3, CaretAffinity.UPSTREAM, 714f, 0, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(2, CaretAffinity.DOWNSTREAM, 2_000f, 1, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(2, CaretAffinity.UPSTREAM, 2_000f, 2, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(1, CaretAffinity.DOWNSTREAM, 4_000f, 3, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(1, CaretAffinity.UPSTREAM, 4_000f, 4, 2, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(0, CaretAffinity.DOWNSTREAM, 5_286f, 5, 2, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                ),
                caretOracles(snapshot, line.allCaretCandidates),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun consecutiveRightToLeftTabsPublishGloballyLogicalControlsWithExactGeometry() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("\u05D1\t\tA".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val line = assertIs<EditableLineResult.Success>(
                JvmEditableLineFacade.layout(
                    lineRequest(
                        snapshot = snapshot,
                        font = fixture.font,
                        baseDirection = BaseDirection.RIGHT_TO_LEFT,
                        positioning = ParagraphPositioningPolicy(defaultTabInterval = LayoutUnit(2_000f)),
                        materialization = EditableLineMaterialization.Renderable(
                            resolver = fixture.resolver,
                            variant = FontRenderVariantKey.default,
                            outlineProfile = OUTLINE_PROFILE,
                        ),
                    ),
                ),
            ).line

            assertEquals(
                listOf(
                    Triple(GlyphId(36), 2_634f, 1_366f),
                    Triple(GlyphId(1281), 8_000f, 1_225f),
                ),
                line.positionedGlyphRuns.flatMap { run -> run.glyphs }.map { glyph ->
                    Triple(glyph.shapedGlyph.glyphId, glyph.origin.x.value, glyph.advance.x.value)
                },
            )
            assertEquals(
                listOf(
                    Triple(1, 6_000f, 2_000f),
                    Triple(2, 4_000f, 2_000f),
                ),
                line.positionedLineControls.map { control ->
                    val scalar = (0..4).single { ordinal ->
                        snapshot.textIndexAtScalarBoundary(ordinal) == control.sourceRange.start
                    }
                    Triple(scalar, control.origin.x.value, control.advance.x.value)
                },
            )
            assertTrue(line.positionedLineControls.all { control -> control.materializationRoute == GlyphMaterializationRoute.EMPTY })
            assertEquals(
                listOf(
                    CaretOracle(3, CaretAffinity.DOWNSTREAM, 2_634f, 0, 0, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(4, CaretAffinity.UPSTREAM, 4_000f, 1, 0, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(3, CaretAffinity.UPSTREAM, 4_000f, 2, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(2, CaretAffinity.DOWNSTREAM, 6_000f, 3, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(2, CaretAffinity.UPSTREAM, 6_000f, 4, 2, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(1, CaretAffinity.DOWNSTREAM, 8_000f, 5, 2, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(1, CaretAffinity.UPSTREAM, 8_000f, 6, 3, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(0, CaretAffinity.DOWNSTREAM, 9_225f, 7, 3, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                ),
                caretOracles(snapshot, line.allCaretCandidates),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun bidi_formatting_controls_remain_glyphless_and_preserve_every_source_and_caret_boundary() {
        val cases = listOf(
            "A\u202AB\u202CC" to listOf(1, 3),
            "A\u202BB\u202CC" to listOf(1, 3),
            "A\u2066B\u2069C" to listOf(1, 3),
            "A\u2067B\u2069C" to listOf(1, 3),
            "A\u2068B\u2069C" to listOf(1, 3),
        )
        val fixture = renderableFixture()
        try {
            cases.forEach { (text, controlOrdinals) ->
                val snapshot = Kalligraphie.decodeUtf16(
                    version = TextVersion.create(),
                    slices = listOf(TextSlice.Utf16(text.toCharArray())),
                ).snapshot

                val line = assertIs<EditableLineResult.Success>(
                    JvmEditableLineFacade.layout(
                        lineRequest(
                            snapshot = snapshot,
                            font = fixture.font,
                            materialization = EditableLineMaterialization.Renderable(
                                resolver = fixture.resolver,
                                variant = FontRenderVariantKey.default,
                                outlineProfile = OUTLINE_PROFILE,
                            ),
                        ),
                    ),
                ).line
                val glyphs = line.positionedGlyphRuns.flatMap { it.glyphs }
                controlOrdinals.forEach { ordinal ->
                    val range = org.graphiks.kalligraphie.api.TextRange(
                        snapshot.textIndexAtScalarBoundary(ordinal),
                        snapshot.textIndexAtScalarBoundary(ordinal + 1),
                    )
                    val controlGlyph = glyphs.single { it.mappedSourceRange == range }
                    assertEquals(GlyphId(3), controlGlyph.shapedGlyph.glyphId)
                    assertEquals(0f, controlGlyph.advance.x.value)
                    assertEquals(GlyphMaterializationRoute.EMPTY, controlGlyph.materializationCertificate?.route)
                    val sourceRun = line.positionedGlyphRuns.single {
                        range.start >= it.sourceRun.range.start &&
                            range.endExclusive <= it.sourceRun.range.endExclusive
                    }.sourceRun
                    val cluster = sourceRun.clusters.single { it.sourceRange == range }
                    assertEquals(listOf(cluster.token), sourceRun.mappings.clustersForSource(range))
                    assertEquals(listOf(range), sourceRun.mappings.sourcesForCluster(cluster.token))
                }
                assertEquals(
                    (0..5).map(snapshot::textIndexAtScalarBoundary).toSet(),
                    line.allCaretCandidates.map { it.position.index }.toSet(),
                )
            }
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun x9_controls_keep_literal_text_cluster_glyph_and_caret_mappings_through_the_public_facade() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("a\u202Eb\u202Cc".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.Renderable(
                        resolver = fixture.resolver,
                        variant = FontRenderVariantKey.default,
                        outlineProfile = OUTLINE_PROFILE,
                    ),
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            val scalarRanges = List(5) { scalar ->
                org.graphiks.kalligraphie.api.TextRange(
                    snapshot.textIndexAtScalarBoundary(scalar),
                    snapshot.textIndexAtScalarBoundary(scalar + 1),
                )
            }
            val clusters = line.positionedGlyphRuns
                .flatMap { it.sourceRun.clusters }
                .sortedBy { scalarRanges.indexOf(it.sourceRange) }
            assertEquals(scalarRanges, clusters.map { it.sourceRange })
            clusters.forEachIndexed { index, cluster ->
                val scalarRange = scalarRanges[index]
                val run = line.positionedGlyphRuns.single {
                    it.sourceRun.range.start <= scalarRange.start && it.sourceRun.range.endExclusive >= scalarRange.endExclusive
                }.sourceRun
                assertEquals(listOf(cluster.token), run.mappings.clustersForSource(scalarRanges[index]))
                assertEquals(listOf(scalarRanges[index]), run.mappings.sourcesForCluster(cluster.token))
                assertEquals(1, run.mappings.glyphsForCluster(cluster.token).size)
            }
            assertEquals(
                listOf(scalarRanges[0], scalarRanges[2], scalarRanges[1], scalarRanges[3], scalarRanges[4]),
                line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { it.mappedSourceRange } },
            )
            assertEquals(
                listOf(GlyphMaterializationRoute.EMPTY, GlyphMaterializationRoute.EMPTY),
                listOf(1, 3).map { ordinal ->
                    line.positionedGlyphRuns.flatMap { it.glyphs }.single {
                        it.mappedSourceRange == scalarRanges[ordinal]
                    }.materializationCertificate?.route
                },
            )
            assertEquals(
                listOf(
                    CaretOracle(0, CaretAffinity.DOWNSTREAM, 0f, 0, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(1, CaretAffinity.UPSTREAM, 1_139f, 1, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(3, CaretAffinity.UPSTREAM, 1_139f, 2, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(1, CaretAffinity.DOWNSTREAM, 2_278f, 3, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(2, CaretAffinity.DOWNSTREAM, 2_278f, 4, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.WEAK, CaretBoundaryEdge.INTERNAL),
                    CaretOracle(3, CaretAffinity.DOWNSTREAM, 2_278f, 5, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(4, CaretAffinity.DOWNSTREAM, 2_278f, 6, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
                    CaretOracle(5, CaretAffinity.UPSTREAM, 3_302f, 7, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                ),
                caretOracles(snapshot, line.allCaretCandidates),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun right_to_left_isolate_controls_keep_empty_materialization_and_literal_caret_order() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("\u05D0\u2066A\u2069\u05D1".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val line = assertIs<EditableLineResult.Success>(
                JvmEditableLineFacade.layout(
                    lineRequest(
                        snapshot = snapshot,
                        font = fixture.font,
                        baseDirection = BaseDirection.RIGHT_TO_LEFT,
                        materialization = EditableLineMaterialization.Renderable(
                            resolver = fixture.resolver,
                            variant = FontRenderVariantKey.default,
                            outlineProfile = OUTLINE_PROFILE,
                        ),
                    ),
                ),
            ).line
            val scalarRanges = List(5) { scalar ->
                org.graphiks.kalligraphie.api.TextRange(
                    snapshot.textIndexAtScalarBoundary(scalar),
                    snapshot.textIndexAtScalarBoundary(scalar + 1),
                )
            }
            assertEquals(
                listOf(GlyphMaterializationRoute.EMPTY, GlyphMaterializationRoute.EMPTY),
                listOf(1, 3).map { ordinal ->
                    line.positionedGlyphRuns.flatMap { it.glyphs }.single {
                        it.mappedSourceRange == scalarRanges[ordinal]
                    }.materializationCertificate?.route
                },
            )
            assertEquals(
                listOf(
                    Triple(GlyphId(1281), 0f, 1_225f),
                    Triple(GlyphId(3), 1_225f, 0f),
                    Triple(GlyphId(36), 1_225f, 1_366f),
                    Triple(GlyphId(3), 2_591f, 0f),
                    Triple(GlyphId(1280), 2_591f, 1_286f),
                ),
                line.positionedGlyphRuns.flatMap { it.glyphs }.map {
                    Triple(it.shapedGlyph.glyphId, it.origin.x.value, it.advance.x.value)
                },
            )
            assertEquals(
                listOf(
                    CaretOracle(5, CaretAffinity.UPSTREAM, 0f, 0, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(4, CaretAffinity.DOWNSTREAM, 1_225f, 1, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(3, CaretAffinity.DOWNSTREAM, 1_225f, 2, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(4, CaretAffinity.UPSTREAM, 1_225f, 3, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(2, CaretAffinity.DOWNSTREAM, 1_225f, 4, 2, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_START),
                    CaretOracle(3, CaretAffinity.UPSTREAM, 2_591f, 5, 2, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(1, CaretAffinity.DOWNSTREAM, 2_591f, 6, 3, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
                    CaretOracle(2, CaretAffinity.UPSTREAM, 2_591f, 7, 3, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
                    CaretOracle(0, CaretAffinity.DOWNSTREAM, 3_877f, 8, 3, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
                ),
                caretOracles(snapshot, line.allCaretCandidates),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun every_bidi_control_family_has_literal_left_to_right_and_right_to_left_editor_geometry() {
        val inputs = listOf(
            Triple("ltr-lre", "A\u202AB\u202CC", BaseDirection.LEFT_TO_RIGHT),
            Triple("ltr-rle", "A\u202BB\u202CC", BaseDirection.LEFT_TO_RIGHT),
            Triple("ltr-lri", "A\u2066B\u2069C", BaseDirection.LEFT_TO_RIGHT),
            Triple("ltr-rli", "A\u2067\u05D0\u2069C", BaseDirection.LEFT_TO_RIGHT),
            Triple("ltr-fsi", "A\u2068\u05D0\u2069C", BaseDirection.LEFT_TO_RIGHT),
            Triple("rtl-lre", "\u05D0\u202AA\u202C\u05D1", BaseDirection.RIGHT_TO_LEFT),
            Triple("rtl-rle", "\u05D0\u202B\u05D1\u202C\u05D2", BaseDirection.RIGHT_TO_LEFT),
            Triple("rtl-lri", "\u05D0\u2066A\u2069\u05D1", BaseDirection.RIGHT_TO_LEFT),
            Triple("rtl-rli", "\u05D0\u2067\u05D1\u2069\u05D2", BaseDirection.RIGHT_TO_LEFT),
            Triple("rtl-fsi", "\u05D0\u2068A\u2069\u05D1", BaseDirection.RIGHT_TO_LEFT),
        )
        val fixture = renderableFixture()
        try {
            val observed = inputs.map { (name, text, baseDirection) ->
                val snapshot = Kalligraphie.decodeUtf16(
                    version = TextVersion.create(),
                    slices = listOf(TextSlice.Utf16(text.toCharArray())),
                ).snapshot
                val line = assertIs<EditableLineResult.Success>(
                    JvmEditableLineFacade.layout(
                        lineRequest(
                            snapshot = snapshot,
                            font = fixture.font,
                            baseDirection = baseDirection,
                            materialization = EditableLineMaterialization.Renderable(
                                resolver = fixture.resolver,
                                variant = FontRenderVariantKey.default,
                                outlineProfile = OUTLINE_PROFILE,
                            ),
                        ),
                    ),
                ).line
                val scalarRanges = List(5) { ordinal ->
                    org.graphiks.kalligraphie.api.TextRange(
                        snapshot.textIndexAtScalarBoundary(ordinal),
                        snapshot.textIndexAtScalarBoundary(ordinal + 1),
                    )
                }
                BidiObservation(
                    name = name,
                    glyphs = line.positionedGlyphRuns.flatMap { run ->
                        run.glyphs.map { glyph ->
                            BidiGlyphOracle(
                                scalar = scalarRanges.indexOf(glyph.mappedSourceRange),
                                visualRunOrder = run.visualOrder,
                                glyphId = glyph.shapedGlyph.glyphId.value,
                                x = glyph.origin.x.value,
                                advance = glyph.advance.x.value,
                                route = glyph.materializationCertificate?.route,
                            )
                        }
                    },
                    carets = caretOracles(snapshot, line.allCaretCandidates),
                )
            }

            assertEquals(bidiControlOracles(), observed)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun acceptsAGraphemeClusterThatSpansAnalyzedBidiLevels() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("\u0600a".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.LayoutOnly,
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            assertEquals(snapshot.range, line.range)
            assertTrue(line.caretCandidates(snapshot.textIndexAtScalarBoundary(1)).isEmpty())
            assertTrue(
                line.selectionGeometry(
                    CaretPosition(snapshot.range.start, CaretAffinity.DOWNSTREAM),
                    CaretPosition(snapshot.range.endExclusive, CaretAffinity.UPSTREAM),
                ).isNotEmpty(),
            )
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun resolvesMixedBidiRunsWithoutAnImplicitLeftToRightShapingDirection() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("Aא".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.LayoutOnly,
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            assertEquals(
                listOf("LEFT_TO_RIGHT", "RIGHT_TO_LEFT"),
                line.positionedGlyphRuns.map { it.sourceRun.direction.name }.sorted(),
            )
            assertTrue(line.caretCandidates(snapshot.textIndexAtScalarBoundary(1)).size >= 2)
            assertTrue(line.positionedGlyphRuns.all { it.sourceRun.graphemeClusters.isNotEmpty() })
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun publishesACertifiedOutlineLineThroughTheReferenceJvmBackend() {
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf(0x41))),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.Renderable(
                        resolver = fixture.resolver,
                        variant = FontRenderVariantKey.default,
                        outlineProfile = OUTLINE_PROFILE,
                    ),
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            val glyph = line.positionedGlyphRuns.single().glyphs.single()
            assertEquals(snapshot.range, line.range)
            assertEquals("14.3.0", line.positionedGlyphRuns.single().sourceRun.backendIdentity.semantic.engineVersion)
            assertEquals(GlyphId(36), glyph.shapedGlyph.glyphId)
            assertEquals(GlyphMaterializationRoute.OUTLINE, glyph.materializationCertificate?.route)
            assertEquals(glyph.renderAssetKey, glyph.materializationCertificate?.assetKey)
            assertEquals(2, line.caretCandidates(snapshot.range.start).size + line.caretCandidates(snapshot.range.endExclusive).size)
            assertTrue(line.positionedGlyphRuns.single().sourceRun.clusters.single().sourceRange == snapshot.range)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun doesNotPublishASuccessfulLineWhenClosingTheShapingBackendFails() {
        val snapshot = Kalligraphie.decodeUtf8(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf8(byteArrayOf(0x41))),
        ).snapshot
        val fixture = renderableFixture()
        val backend = assertIs<FontOperationResult.Success<ShapingBackend>>(JvmHarfBuzzShapingBackend.open()).value
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.LayoutOnly,
                ),
                CloseFailingBackend(backend),
            )

            val failure = assertIs<EditableLineResult.Failure>(result)
            val shapingFailure = assertIs<EditableLineError.ShapingFailure>(failure.error)
            assertEquals("font.test-close-failure", shapingFailure.fontError.code)
            assertEquals("font.test-close-failure", failure.diagnostics.single().code)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun cancelled_unicode_analysis_does_not_publish_an_editable_line() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("A\uD83D\uDE00".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.LayoutOnly,
                    cancellationToken = CancellationToken.cancelled,
                ),
            )

            assertIs<EditableLineResult.Cancelled>(result)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun unicode_scalar_budget_rejects_the_line_before_shaping_or_publication() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("A\uD83D\uDE00".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.LayoutOnly,
                    unicodeAnalysisProfile = UnicodeAnalysisProfile(maxScalars = 1),
                ),
            )

            val failure = assertIs<EditableLineResult.Failure>(result)
            assertIs<EditableLineError.UnicodeAnalysisLimitExceeded>(failure.error)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    @Test
    fun shaping_scalar_budget_does_not_publish_an_editable_line() {
        val snapshot = Kalligraphie.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16("fi".toCharArray())),
        ).snapshot
        val fixture = renderableFixture()
        try {
            val result = JvmEditableLineFacade.layout(
                JvmEditableLineFacadeRequest(
                    snapshot = snapshot,
                    font = fixture.font,
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                    features = emptyList(),
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                    materialization = EditableLineMaterialization.LayoutOnly,
                    shapingResourceProfile = ShapingResourceProfile(maxScalars = 1),
                ),
            )

            val failure = assertIs<EditableLineResult.Failure>(result)
            val shapingFailure = assertIs<EditableLineError.ShapingFailure>(failure.error)
            val limit = assertIs<FontError.ShapingResourceLimitExceeded>(shapingFailure.fontError)
            assertEquals(2, limit.observed)
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(fixture.resolver.close())
        }
    }

    private fun renderableFixture(): RenderableFixture {
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(
                sourceBytes = fixtureBytes(),
                provenance = FontSourceProvenance(declaredName = "Liberation Sans Regular"),
            ),
        ).value
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(catalog.openAssetResolver()).value
        val requirements = FontAccessRequirementsSnapshot.renderable(OUTLINE_PROFILE)
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(catalog.faces.single().id, requirements),
        ).value
        val font = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(2048f))),
        ).value
        return RenderableFixture(font, resolver)
    }

    private fun lineRequest(
        snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
        font: FontInstance,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        cancellationToken: CancellationToken = CancellationToken.none,
        positioning: ParagraphPositioningPolicy? = null,
        materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
        shapingResourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
    ): JvmEditableLineFacadeRequest = JvmEditableLineFacadeRequest(
        snapshot = snapshot,
        font = font,
        baseDirection = baseDirection,
        language = "en",
        featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
        features = emptyList(),
        verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
        materialization = materialization,
        positioning = positioning,
        cancellationToken = cancellationToken,
        shapingResourceProfile = shapingResourceProfile,
    )

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { it.readBytes() }

    private data class RenderableFixture(
        val font: FontInstance,
        val resolver: FontAssetResolverHandle,
    )

    private data class HardSeparatorOracle(
        val text: String,
        val kind: LineControlKind,
        val scalarEnd: Int,
        val utf8SourceRange: Pair<Int, Int>,
        val utf16SourceRange: Pair<Int, Int>,
    )

    private data class CaretOracle(
        val scalarBoundary: Int,
        val affinity: CaretAffinity,
        val x: Float,
        val visualOrder: Int,
        val visualRunOrder: Int,
        val bidiLevel: Int,
        val direction: ShapingDirection,
        val strength: CaretStrength,
        val edge: CaretBoundaryEdge,
        val endX: Float = x,
        val top: Float = -18f,
        val bottom: Float = 6f,
    )

    private data class BidiGlyphOracle(
        val scalar: Int,
        val visualRunOrder: Int,
        val glyphId: Int,
        val x: Float,
        val advance: Float,
        val route: GlyphMaterializationRoute?,
    )

    private data class BidiObservation(
        val name: String,
        val glyphs: List<BidiGlyphOracle>,
        val carets: List<CaretOracle>,
    )

    /**
     * Hand-audited UAX #9 run levels and retained-control order combined with the fixed
     * Liberation Sans glyph IDs and advances used by this fixture.
     */
    private fun bidiControlOracles(): List<BidiObservation> {
        val leftToRightEmbeddingGlyphs = listOf(
            BidiGlyphOracle(0, 0, 36, 0f, 1_366f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(1, 1, 3, 1_366f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(2, 1, 37, 1_366f, 1_366f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(3, 2, 3, 2_732f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(4, 2, 38, 2_732f, 1_479f, GlyphMaterializationRoute.OUTLINE),
        )
        val leftToRightEmbeddingCarets = listOf(
            CaretOracle(0, CaretAffinity.DOWNSTREAM, 0f, 0, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(1, CaretAffinity.UPSTREAM, 1_366f, 1, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(1, CaretAffinity.DOWNSTREAM, 1_366f, 2, 1, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(2, CaretAffinity.DOWNSTREAM, 1_366f, 3, 1, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
            CaretOracle(3, CaretAffinity.UPSTREAM, 2_732f, 4, 1, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(3, CaretAffinity.DOWNSTREAM, 2_732f, 5, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(4, CaretAffinity.DOWNSTREAM, 2_732f, 6, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
            CaretOracle(5, CaretAffinity.UPSTREAM, 4_211f, 7, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
        )
        val leftToRightLriGlyphs = listOf(
            BidiGlyphOracle(0, 0, 36, 0f, 1_366f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(1, 0, 3, 1_366f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(2, 1, 37, 1_366f, 1_366f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(3, 2, 3, 2_732f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(4, 2, 38, 2_732f, 1_479f, GlyphMaterializationRoute.OUTLINE),
        )
        val leftToRightLriCarets = listOf(
            CaretOracle(0, CaretAffinity.DOWNSTREAM, 0f, 0, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(1, CaretAffinity.DOWNSTREAM, 1_366f, 1, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
            CaretOracle(2, CaretAffinity.UPSTREAM, 1_366f, 2, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(2, CaretAffinity.DOWNSTREAM, 1_366f, 3, 1, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(3, CaretAffinity.UPSTREAM, 2_732f, 4, 1, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(3, CaretAffinity.DOWNSTREAM, 2_732f, 5, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(4, CaretAffinity.DOWNSTREAM, 2_732f, 6, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
            CaretOracle(5, CaretAffinity.UPSTREAM, 4_211f, 7, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
        )
        val leftToRightRliGlyphs = listOf(
            BidiGlyphOracle(0, 0, 36, 0f, 1_366f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(1, 0, 3, 1_366f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(2, 1, 1280, 1_366f, 1_286f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(3, 2, 3, 2_652f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(4, 3, 38, 2_652f, 1_479f, GlyphMaterializationRoute.OUTLINE),
        )
        val leftToRightRliCarets = listOf(
            CaretOracle(0, CaretAffinity.DOWNSTREAM, 0f, 0, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(1, CaretAffinity.DOWNSTREAM, 1_366f, 1, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
            CaretOracle(2, CaretAffinity.UPSTREAM, 1_366f, 2, 0, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(3, CaretAffinity.UPSTREAM, 1_366f, 3, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(2, CaretAffinity.DOWNSTREAM, 2_652f, 4, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(3, CaretAffinity.DOWNSTREAM, 2_652f, 5, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(4, CaretAffinity.UPSTREAM, 2_652f, 6, 2, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(4, CaretAffinity.DOWNSTREAM, 2_652f, 7, 3, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(5, CaretAffinity.UPSTREAM, 4_131f, 8, 3, 0, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
        )
        val rightToLeftLreGlyphs = listOf(
            BidiGlyphOracle(4, 0, 1281, 0f, 1_225f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(3, 1, 3, 1_225f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(1, 2, 3, 1_225f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(2, 3, 36, 1_225f, 1_366f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(0, 4, 1280, 2_591f, 1_286f, GlyphMaterializationRoute.OUTLINE),
        )
        val rightToLeftLreCarets = listOf(
            CaretOracle(5, CaretAffinity.UPSTREAM, 0f, 0, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(4, CaretAffinity.DOWNSTREAM, 1_225f, 1, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(3, CaretAffinity.DOWNSTREAM, 1_225f, 2, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(4, CaretAffinity.UPSTREAM, 1_225f, 3, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(1, CaretAffinity.DOWNSTREAM, 1_225f, 4, 2, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(2, CaretAffinity.UPSTREAM, 1_225f, 5, 2, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(2, CaretAffinity.DOWNSTREAM, 1_225f, 6, 3, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(3, CaretAffinity.UPSTREAM, 2_591f, 7, 3, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(1, CaretAffinity.UPSTREAM, 2_591f, 8, 4, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(0, CaretAffinity.DOWNSTREAM, 3_877f, 9, 4, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
        )
        val rightToLeftRleGlyphs = listOf(
            BidiGlyphOracle(4, 0, 1282, 0f, 866f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(3, 0, 3, 866f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(2, 1, 1281, 866f, 1_225f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(1, 1, 3, 2_091f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(0, 2, 1280, 2_091f, 1_286f, GlyphMaterializationRoute.OUTLINE),
        )
        val rightToLeftRleCarets = listOf(
            CaretOracle(5, CaretAffinity.UPSTREAM, 0f, 0, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(3, CaretAffinity.DOWNSTREAM, 866f, 1, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(4, CaretAffinity.DOWNSTREAM, 866f, 2, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
            CaretOracle(3, CaretAffinity.UPSTREAM, 866f, 3, 1, 3, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(1, CaretAffinity.DOWNSTREAM, 2_091f, 4, 1, 3, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(2, CaretAffinity.DOWNSTREAM, 2_091f, 5, 1, 3, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
            CaretOracle(1, CaretAffinity.UPSTREAM, 2_091f, 6, 2, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(0, CaretAffinity.DOWNSTREAM, 3_377f, 7, 2, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
        )
        val rightToLeftLriGlyphs = listOf(
            BidiGlyphOracle(4, 0, 1281, 0f, 1_225f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(3, 1, 3, 1_225f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(2, 2, 36, 1_225f, 1_366f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(1, 3, 3, 2_591f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(0, 3, 1280, 2_591f, 1_286f, GlyphMaterializationRoute.OUTLINE),
        )
        val rightToLeftLriCarets = listOf(
            CaretOracle(5, CaretAffinity.UPSTREAM, 0f, 0, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(4, CaretAffinity.DOWNSTREAM, 1_225f, 1, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(3, CaretAffinity.DOWNSTREAM, 1_225f, 2, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(4, CaretAffinity.UPSTREAM, 1_225f, 3, 1, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(2, CaretAffinity.DOWNSTREAM, 1_225f, 4, 2, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(3, CaretAffinity.UPSTREAM, 2_591f, 5, 2, 2, ShapingDirection.LEFT_TO_RIGHT, CaretStrength.WEAK, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(1, CaretAffinity.DOWNSTREAM, 2_591f, 6, 3, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
            CaretOracle(2, CaretAffinity.UPSTREAM, 2_591f, 7, 3, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(0, CaretAffinity.DOWNSTREAM, 3_877f, 8, 3, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
        )
        val rightToLeftRliGlyphs = listOf(
            BidiGlyphOracle(4, 0, 1282, 0f, 866f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(3, 0, 3, 866f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(2, 1, 1281, 866f, 1_225f, GlyphMaterializationRoute.OUTLINE),
            BidiGlyphOracle(1, 2, 3, 2_091f, 0f, GlyphMaterializationRoute.EMPTY),
            BidiGlyphOracle(0, 2, 1280, 2_091f, 1_286f, GlyphMaterializationRoute.OUTLINE),
        )
        val rightToLeftRliCarets = listOf(
            CaretOracle(5, CaretAffinity.UPSTREAM, 0f, 0, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(3, CaretAffinity.DOWNSTREAM, 866f, 1, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(4, CaretAffinity.DOWNSTREAM, 866f, 2, 0, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
            CaretOracle(3, CaretAffinity.UPSTREAM, 866f, 3, 1, 3, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(2, CaretAffinity.DOWNSTREAM, 2_091f, 4, 1, 3, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
            CaretOracle(1, CaretAffinity.DOWNSTREAM, 2_091f, 5, 2, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.INTERNAL),
            CaretOracle(2, CaretAffinity.UPSTREAM, 2_091f, 6, 2, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_END),
            CaretOracle(0, CaretAffinity.DOWNSTREAM, 3_377f, 7, 2, 1, ShapingDirection.RIGHT_TO_LEFT, CaretStrength.STRONG, CaretBoundaryEdge.LOGICAL_START),
        )

        return listOf(
            BidiObservation("ltr-lre", leftToRightEmbeddingGlyphs, leftToRightEmbeddingCarets),
            BidiObservation("ltr-rle", leftToRightEmbeddingGlyphs, leftToRightEmbeddingCarets),
            BidiObservation("ltr-lri", leftToRightLriGlyphs, leftToRightLriCarets),
            BidiObservation("ltr-rli", leftToRightRliGlyphs, leftToRightRliCarets),
            BidiObservation("ltr-fsi", leftToRightRliGlyphs, leftToRightRliCarets),
            BidiObservation("rtl-lre", rightToLeftLreGlyphs, rightToLeftLreCarets),
            BidiObservation("rtl-rle", rightToLeftRleGlyphs, rightToLeftRleCarets),
            BidiObservation("rtl-lri", rightToLeftLriGlyphs, rightToLeftLriCarets),
            BidiObservation("rtl-rli", rightToLeftRliGlyphs, rightToLeftRliCarets),
            BidiObservation("rtl-fsi", rightToLeftLriGlyphs, rightToLeftLriCarets),
        )
    }

    private fun caretOracles(
        snapshot: org.graphiks.kalligraphie.api.TextSnapshot,
        candidates: List<org.graphiks.kalligraphie.api.CaretCandidate>,
    ): List<CaretOracle> = candidates.map { candidate ->
        CaretOracle(
            scalarBoundary = (0..snapshot.scalars.size).single {
                snapshot.textIndexAtScalarBoundary(it) == candidate.position.index
            },
            affinity = candidate.position.affinity,
            x = candidate.geometry.start.x.value,
            visualOrder = candidate.visualOrder,
            visualRunOrder = candidate.visualRunOrder,
            bidiLevel = candidate.bidiLevel,
            direction = candidate.direction,
            strength = candidate.strength,
            edge = candidate.edge,
            endX = candidate.geometry.end.x.value,
            top = candidate.geometry.start.y.value,
            bottom = candidate.geometry.end.y.value,
        )
    }

    private class CloseFailingBackend(
        private val delegate: ShapingBackend,
    ) : ShapingBackend {
        override val identity = delegate.identity

        override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> = delegate.shape(request)

        override fun close(): FontOperationResult<Unit> = FontOperationResult.Failure(
            FontError.FontDataFailure(
                code = "font.test-close-failure",
                message = "The test backend could not close.",
                location = org.graphiks.kalligraphie.api.FontDiagnosticLocation.Source,
            ),
        )
    }

    private companion object {
        val OUTLINE_PROFILE: OutlineProfile = OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 256,
            maxPoints = 16_384,
            maxCompositeDepth = 8,
            maxCompositeComponents = 256,
        )
    }
}
