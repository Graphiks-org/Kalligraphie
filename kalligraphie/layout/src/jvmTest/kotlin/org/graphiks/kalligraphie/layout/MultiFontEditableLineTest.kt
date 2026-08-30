package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.MultiFontEditableLineRequest
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalogEntry
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.JvmUnicodeAnalyzer
import org.graphiks.kalligraphie.unicode.TextSnapshots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultiFontEditableLineTest {
    @Test
    fun renderableMultiscriptLineSelectsLatinAndArabicFacesWithCertifiedFinalGlyphs() {
        val latin = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val arabic = source("/fonts/amiri/Amiri-Regular.ttf", "Amiri Regular")
        val generation = FontCatalogGeneration("audited-multiscript-fixture-v1")
        val catalog = EmbeddedFontCatalog(
            generation = generation,
            entries = listOf(
                EmbeddedFontCatalogEntry(latin, SfntReader.readMetadata(latin).successValue()),
                EmbeddedFontCatalogEntry(arabic, SfntReader.readMetadata(arabic).successValue()),
            ),
        )
        val latinFace = FontFaceId(latin.id, 0)
        val arabicFace = FontFaceId(arabic.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-multiscript-fixture",
            version = "1",
            candidates = listOf(FontResolutionCandidate(latinFace), FontResolutionCandidate(arabicFace)),
            lastResortFace = arabicFace,
        )
        val text = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(org.graphiks.kalligraphie.api.TextSlice.Utf16("fiسلام".toCharArray())),
        ).snapshot
        val analysis = JvmUnicodeAnalyzer.create().analyze(
            text,
            org.graphiks.kalligraphie.api.UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, "ar"),
        )
        val resolver = catalog.openAssetResolver().successValue()
        try {
            val result = ExactEditableLineLayouter.layout(
                MultiFontEditableLineRequest(
                    snapshot = text,
                    unicodeAnalysis = analysis,
                    fontCatalog = catalog,
                    resolutionPolicy = policy,
                    fontInstanceDescriptor = FontInstanceDescriptor(layoutSize = LayoutUnit(1000f)),
                    shapingBackend = JvmHarfBuzzShapingBackend.open().successValue(),
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    verticalMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
                    materialization = EditableLineMaterialization.Renderable(
                        resolver = resolver,
                        variant = FontRenderVariantKey.default,
                        outlineProfile = outlineProfile(),
                    ),
                ),
            )

            val line = assertIs<EditableLineResult.Success>(result).line
            assertEquals(listOf(latinFace, arabicFace), line.positionedGlyphRuns.map { it.fontInstanceKey.face })
            assertEquals(listOf(3), line.positionedGlyphRuns[0].glyphs.map { it.shapedGlyph.glyphId.value })
            assertEquals(listOf(900f), line.positionedGlyphRuns[0].glyphs.map { it.advance.x.value })
            assertEquals(listOf(85, 3080, 3075, 1919), line.positionedGlyphRuns[1].glyphs.map { it.shapedGlyph.glyphId.value })
            assertEquals(listOf(452f, 446f, 245f, 568f), line.positionedGlyphRuns[1].glyphs.map { it.advance.x.value })
            assertEquals(listOf(5, 4, 3, 2), line.positionedGlyphRuns[1].glyphs.map { glyph ->
                text.scalarRanges(text.range)
                    .indexOfFirst { range -> range == glyph.sourceClusters.single().sourceRange }
            })
            line.positionedGlyphRuns.forEach { run ->
                assertNotNull(run.renderAssetKey)
                run.glyphs.forEach { glyph -> assertNotNull(glyph.materializationCertificate) }
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun detachedAssetsRemainUsableAndReopenOnlyInTheirCapturedGeneration() {
        val latin = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val generation = FontCatalogGeneration("audited-detached-asset-fixture-v1")
        val catalog = EmbeddedFontCatalog(
            generation = generation,
            entries = listOf(EmbeddedFontCatalogEntry(latin, SfntReader.readMetadata(latin).successValue())),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile())
        val face = catalog.resolveFace(catalog.faces.single().id, requirements).successValue()
        val instance = face.instantiate(FontInstanceDescriptor(LayoutUnit(1000f))).successValue()
        val originalResolver = catalog.openAssetResolver().successValue()
        val attached = instance.acquireRenderAsset(originalResolver, FontRenderVariantKey.default, requirements).successValue()
        val detached = attached.detach().successValue()

        try {
            originalResolver.close()
            attached.close()
            assertIs<GlyphRepresentation.Outline>(detached.resolveGlyph(FontGlyphRequest(GlyphId(3))).successValue())

            val sameGenerationResolver = catalog.openAssetResolver().successValue()
            try {
                val reopened = sameGenerationResolver.reopen(detached.key).successValue()
                try {
                    assertIs<GlyphRepresentation.Outline>(reopened.resolveGlyph(FontGlyphRequest(GlyphId(3))).successValue())
                } finally {
                    reopened.close()
                }
                val unavailable = assertIs<FontOperationResult.Failure>(
                    sameGenerationResolver.reopen(detached.key.copy(variant = FontRenderVariantKey("unsupported"))),
                )
                assertIs<FontError.AssetUnavailable>(unavailable.error)
            } finally {
                sameGenerationResolver.close()
            }

            val incompatibleCatalog = EmbeddedFontCatalog(
                generation = FontCatalogGeneration("audited-detached-asset-fixture-v2"),
                entries = listOf(EmbeddedFontCatalogEntry(latin, SfntReader.readMetadata(latin).successValue())),
            )
            val incompatibleResolver = incompatibleCatalog.openAssetResolver().successValue()
            try {
                val mismatchedAcquisition = assertIs<FontOperationResult.Failure>(
                    instance.acquireRenderAsset(incompatibleResolver, FontRenderVariantKey.default, requirements),
                )
                assertIs<FontError.IncompatibleCatalogGeneration>(mismatchedAcquisition.error)
                val failure = assertIs<FontOperationResult.Failure>(incompatibleResolver.reopen(detached.key))
                assertIs<FontError.IncompatibleCatalogGeneration>(failure.error)
            } finally {
                incompatibleResolver.close()
            }
        } finally {
            detached.close()
        }
    }

    @Test
    fun renderableFallbackRejectsAShapedGlyphWhoseOutlineExceedsTheProfile() {
        val complex = source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val simple = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val generation = FontCatalogGeneration("audited-outline-fallback-fixture-v1")
        val catalog = EmbeddedFontCatalog(
            generation = generation,
            entries = listOf(
                EmbeddedFontCatalogEntry(complex, SfntReader.readMetadata(complex).successValue()),
                EmbeddedFontCatalogEntry(simple, SfntReader.readMetadata(simple).successValue()),
            ),
        )
        val complexFace = FontFaceId(complex.id, 0)
        val simpleFace = FontFaceId(simple.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-outline-fallback",
            version = "1",
            candidates = listOf(FontResolutionCandidate(complexFace), FontResolutionCandidate(simpleFace)),
            lastResortFace = simpleFace,
        )
        val text = text("fi")
        val analysis = analyze(text, "en")
        val backend = JvmHarfBuzzShapingBackend.open().successValue()
        val profile = outlineProfile(maxContours = 1)

        val layoutOnly = ExactEditableLineLayouter.layout(
            request(text, analysis, catalog, policy, backend, EditableLineMaterialization.LayoutOnly),
        )
        val layoutOnlyLine = assertIs<EditableLineResult.Success>(layoutOnly).line
        assertEquals(listOf(complexFace), layoutOnlyLine.positionedGlyphRuns.map { it.fontInstanceKey.face })
        assertEquals(listOf(5042), layoutOnlyLine.positionedGlyphRuns.single().glyphs.map { it.shapedGlyph.glyphId.value })
        assertEquals(null, layoutOnlyLine.positionedGlyphRuns.single().renderAssetKey)

        val resolver = catalog.openAssetResolver().successValue()
        try {
            val renderable = ExactEditableLineLayouter.layout(
                request(
                    text,
                    analysis,
                    catalog,
                    policy,
                    backend,
                    EditableLineMaterialization.Renderable(resolver, FontRenderVariantKey.default, profile),
                ),
            )
            val renderableLine = assertIs<EditableLineResult.Success>(renderable).line
            assertEquals(listOf(simpleFace), renderableLine.positionedGlyphRuns.map { it.fontInstanceKey.face })
            assertEquals(listOf(3), renderableLine.positionedGlyphRuns.single().glyphs.map { it.shapedGlyph.glyphId.value })
            renderableLine.positionedGlyphRuns.single().let { run ->
                val asset = resolver.reopen(assertNotNull(run.renderAssetKey)).successValue()
                try {
                    run.glyphs.forEach { glyph ->
                        assertEquals(run.renderAssetKey, glyph.materializationCertificate?.assetKey)
                        assertIs<GlyphRepresentation.Outline>(asset.resolveGlyph(FontGlyphRequest(glyph.shapedGlyph.glyphId)).successValue())
                    }
                } finally {
                    asset.close()
                }
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun deepFallbackIsDeterministicAndDiagnosesTheExplicitLastResort() {
        val latin = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val universal = source("/fonts/liberation/LiberationSans-Regular.ttf", "Liberation Sans Regular")
        val arabic = source("/fonts/amiri/Amiri-Regular.ttf", "Amiri Regular")
        val generation = FontCatalogGeneration("audited-deep-fallback-fixture-v1")
        val catalog = EmbeddedFontCatalog(
            generation = generation,
            entries = listOf(
                EmbeddedFontCatalogEntry(latin, SfntReader.readMetadata(latin).successValue()),
                EmbeddedFontCatalogEntry(universal, SfntReader.readMetadata(universal).successValue()),
                EmbeddedFontCatalogEntry(arabic, SfntReader.readMetadata(arabic).successValue()),
            ),
        )
        val latinFace = FontFaceId(latin.id, 0)
        val universalFace = FontFaceId(universal.id, 0)
        val arabicFace = FontFaceId(arabic.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-deep-fallback",
            version = "1",
            candidates = listOf(
                FontResolutionCandidate(latinFace),
                FontResolutionCandidate(universalFace),
                FontResolutionCandidate(arabicFace),
            ),
            lastResortFace = arabicFace,
        )
        val text = text("سلام")
        val analysis = analyze(text, "ar")
        val backend = JvmHarfBuzzShapingBackend.open().successValue()
        val resolver = catalog.openAssetResolver().successValue()
        try {
            val results = List(6) {
                assertIs<EditableLineResult.Success>(
                    ExactEditableLineLayouter.layout(
                        request(
                            text,
                            analysis,
                            catalog,
                            policy,
                            backend,
                            EditableLineMaterialization.Renderable(resolver, FontRenderVariantKey.default, outlineProfile()),
                        ),
                    ),
                ).line
            }
            val first = results.first()
            assertEquals(listOf(arabicFace), first.positionedGlyphRuns.map { it.fontInstanceKey.face })
            assertEquals(listOf(85, 3080, 3075, 1919), first.positionedGlyphRuns.single().glyphs.map { it.shapedGlyph.glyphId.value })
            assertEquals(
                2,
                first.diagnostics.count { diagnostic -> diagnostic.code == "font.fallback-candidate-rejected" },
            )
            assertEquals(
                1,
                first.diagnostics.count { diagnostic -> diagnostic.code == "font.fallback-last-resort" },
            )
            assertTrue(results.drop(1).all { line ->
                line.positionedGlyphRuns.map { it.fontInstanceKey } == first.positionedGlyphRuns.map { it.fontInstanceKey } &&
                    line.diagnostics == first.diagnostics
            })
        } finally {
            resolver.close()
        }
    }

    @Test
    fun exhaustionReturnsATypedFailureWithoutPublishingAPartialLine() {
        val latin = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val generation = FontCatalogGeneration("audited-exhaustion-fixture-v1")
        val catalog = EmbeddedFontCatalog(
            generation = generation,
            entries = listOf(EmbeddedFontCatalogEntry(latin, SfntReader.readMetadata(latin).successValue())),
        )
        val latinFace = FontFaceId(latin.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-exhaustion",
            version = "1",
            candidates = listOf(FontResolutionCandidate(latinFace)),
            lastResortFace = latinFace,
        )
        val text = text("سلام")
        val result = ExactEditableLineLayouter.layout(
            request(text, analyze(text, "ar"), catalog, policy, JvmHarfBuzzShapingBackend.open().successValue(), EditableLineMaterialization.LayoutOnly),
        )

        val failure = assertIs<EditableLineResult.Failure>(result)
        assertIs<org.graphiks.kalligraphie.api.FontError.UnrenderableFontResolution>(
            assertIs<EditableLineError.FontResolutionFailure>(failure.error).fontError,
        )
        assertEquals(1, failure.diagnostics.count { diagnostic -> diagnostic.code == "font.fallback-candidate-rejected" })
    }

    @Test
    fun graphemeVariationAndEmojiZwJSequencesRemainAssignedToOneFace() {
        val incomplete = source("/fonts/gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture")
        val complete = source("/fonts/dejavu/DejaVuSans.ttf", "DejaVu Sans")
        val generation = FontCatalogGeneration("audited-unicode-unit-fixture-v1")
        val catalog = EmbeddedFontCatalog(
            generation = generation,
            entries = listOf(
                EmbeddedFontCatalogEntry(incomplete, SfntReader.readMetadata(incomplete).successValue()),
                EmbeddedFontCatalogEntry(complete, SfntReader.readMetadata(complete).successValue()),
            ),
        )
        val completeFace = FontFaceId(complete.id, 0)
        val policy = FontResolutionPolicySnapshot(
            generation = generation,
            policyId = "audited-unicode-units",
            version = "1",
            candidates = listOf(FontResolutionCandidate(FontFaceId(incomplete.id, 0)), FontResolutionCandidate(completeFace)),
            lastResortFace = completeFace,
        )
        val backend = JvmHarfBuzzShapingBackend.open().successValue()

        listOf("f\u0301", "\u2764\uFE0F", "\u2764\uFE0F\u200D\u2764\uFE0F").forEach { value ->
            val text = text(value)
            val analysis = analyze(text, "und")
            val line = assertIs<EditableLineResult.Success>(
                ExactEditableLineLayouter.layout(request(text, analysis, catalog, policy, backend, EditableLineMaterialization.LayoutOnly)),
            ).line
            assertEquals(listOf(completeFace), line.positionedGlyphRuns.map { it.fontInstanceKey.face })
            assertEquals(text.range, line.positionedGlyphRuns.single().sourceRun.range)
            assertEquals(analysis.graphemeClusters, line.positionedGlyphRuns.single().sourceRun.graphemeClusters)
        }
    }

    private fun source(resource: String, declaredName: String): FontSource =
        FontSource(fixtureBytes(resource), FontSourceProvenance(declaredName))

    private fun fixtureBytes(resource: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }

    private fun outlineProfile(maxContours: Int = 10_000): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = maxContours,
        maxPoints = 100_000,
        maxCompositeDepth = 32,
        maxCompositeComponents = 10_000,
    )

    private fun <T> FontOperationResult<T>.successValue(): T =
        assertIs<FontOperationResult.Success<T>>(this).value

    private fun text(value: String) = TextSnapshots.decodeUtf16(
        version = TextVersion.create(),
        slices = listOf(org.graphiks.kalligraphie.api.TextSlice.Utf16(value.toCharArray())),
    ).snapshot

    private fun analyze(
        text: org.graphiks.kalligraphie.api.TextSnapshot,
        language: String,
    ) = JvmUnicodeAnalyzer.create().analyze(
        text,
        org.graphiks.kalligraphie.api.UnicodeAnalysisRequest(BaseDirection.LEFT_TO_RIGHT, language),
    )

    private fun request(
        text: org.graphiks.kalligraphie.api.TextSnapshot,
        analysis: org.graphiks.kalligraphie.api.UnicodeAnalysis,
        catalog: EmbeddedFontCatalog,
        policy: FontResolutionPolicySnapshot,
        backend: org.graphiks.kalligraphie.api.ShapingBackend,
        materialization: EditableLineMaterialization,
    ): MultiFontEditableLineRequest = MultiFontEditableLineRequest(
        snapshot = text,
        unicodeAnalysis = analysis,
        fontCatalog = catalog,
        resolutionPolicy = policy,
        fontInstanceDescriptor = FontInstanceDescriptor(layoutSize = LayoutUnit(1000f)),
        shapingBackend = backend,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        verticalMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
        materialization = materialization,
    )
}
