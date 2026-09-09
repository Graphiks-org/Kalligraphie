package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.CaretPosition
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
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmEditableLineFacadeTest {
    @Test
    fun hard_line_controls_are_rejected_with_their_exact_scalar_range_before_cancellation() {
        val cases = listOf(
            Triple("A\rB", LineControlKind.CARRIAGE_RETURN, 1 to 2),
            Triple("A\nB", LineControlKind.LINE_FEED, 1 to 2),
            Triple("A\r\nB", LineControlKind.CARRIAGE_RETURN_LINE_FEED, 1 to 3),
            Triple("A\u000BB", LineControlKind.VERTICAL_TAB, 1 to 2),
            Triple("A\u000CB", LineControlKind.FORM_FEED, 1 to 2),
            Triple("A\u0085B", LineControlKind.NEXT_LINE, 1 to 2),
            Triple("A\u2028B", LineControlKind.LINE_SEPARATOR, 1 to 2),
            Triple("A\u2029B", LineControlKind.PARAGRAPH_SEPARATOR, 1 to 2),
        )
        val fixture = renderableFixture()
        try {
            cases.forEach { (text, expectedKind, expectedBoundaries) ->
                val snapshot = Kalligraphie.decodeUtf16(
                    version = TextVersion.create(),
                    slices = listOf(TextSlice.Utf16(text.toCharArray())),
                ).snapshot

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
                assertEquals(expectedKind, control.kind)
                assertEquals(
                    org.graphiks.kalligraphie.api.TextRange(
                        snapshot.textIndexAtScalarBoundary(expectedBoundaries.first),
                        snapshot.textIndexAtScalarBoundary(expectedBoundaries.second),
                    ),
                    control.range,
                )
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
    fun explicit_tab_stop_positions_the_following_liberation_glyph_and_uses_a_glyphless_marker() {
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
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            val glyphs = line.positionedGlyphRuns.flatMap { it.glyphs }
            assertEquals(listOf(GlyphId(36), GlyphId(3), GlyphId(37)), glyphs.map { it.shapedGlyph.glyphId })
            assertEquals(listOf(0f, 1_366f, 3_000f), glyphs.map { it.origin.x.value })
            assertEquals(listOf(1_366f, 1_634f, 1_366f), glyphs.map { it.advance.x.value })
            assertEquals(
                org.graphiks.kalligraphie.api.TextRange(
                    snapshot.textIndexAtScalarBoundary(1),
                    snapshot.textIndexAtScalarBoundary(2),
                ),
                glyphs[1].mappedSourceRange,
            )
            assertEquals(
                (0..3).map(snapshot::textIndexAtScalarBoundary).toSet(),
                line.allCaretCandidates.map { it.position.index }.toSet(),
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
                    JvmEditableLineFacade.layout(lineRequest(snapshot, fixture.font)),
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
                    materialization = EditableLineMaterialization.LayoutOnly,
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
                listOf(1, 2, 1, 2, 1, 1),
                (0..5).map { line.caretCandidates(snapshot.textIndexAtScalarBoundary(it)).size },
            )
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
        cancellationToken: CancellationToken = CancellationToken.none,
        positioning: ParagraphPositioningPolicy? = null,
    ): JvmEditableLineFacadeRequest = JvmEditableLineFacadeRequest(
        snapshot = snapshot,
        font = font,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
        features = emptyList(),
        verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
        materialization = EditableLineMaterialization.LayoutOnly,
        positioning = positioning,
        cancellationToken = cancellationToken,
    )

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { it.readBytes() }

    private data class RenderableFixture(
        val font: FontInstance,
        val resolver: FontAssetResolverHandle,
    )

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
