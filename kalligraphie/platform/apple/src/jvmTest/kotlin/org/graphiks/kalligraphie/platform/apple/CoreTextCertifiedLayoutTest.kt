package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.*
import org.graphiks.kalligraphie.api.*
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.layout.openLayoutHandle
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CoreTextCertifiedLayoutTest {
    @Test
    fun cancellationDuringNativeRootReopeningTransfersNoParagraphOwnerAndLaterHandoffSucceeds() {
        val catalog = success(CoreTextFontCatalog.capture(portableFixture("/fonts/dejavu/DejaVuSans.ttf"),
            CoreTextFontAccessPolicy(2_000_000L, 8_000_000L, 2_271_228L)))
        val resolver = success(catalog.openAssetResolver())
        val paragraph = paragraph(catalog, resolver, "co\u00ADoperate", 1000f, 2000f)
        val certificate = checkNotNull(paragraph.lines.first().positionedGlyphRuns.flatMap { it.glyphs }
            .single { it.provenance is GlyphProvenance.Derived }.materializationCertificate)
        val token = BlockingCoreTextToken()
        val worker = Executors.newSingleThreadExecutor()
        val pending = worker.submit<FontOperationResult<LayoutHandle<ParagraphLayout>>> { paragraph.openLayoutHandle(resolver, token) }
        try {
            var refusal: NativeFontAccessLimitExceeded? = null
            // Advance real cooperative checkpoints until simultaneous reopening is refused.
            // This observes finite admission while the root is still being created, rather
            // than treating any checkpoint count as a correctness oracle.
            for (attempt in 0 until 80) {
                if (!token.awaitCheckpoint()) break
                when (val simultaneous = resolver.reopen(certificate.assetKey)) {
                    is FontOperationResult.Success -> { success(simultaneous.value.close()); token.advance() }
                    is FontOperationResult.Failure -> { refusal = assertIs(simultaneous.error); break }
                    is FontOperationResult.Cancelled -> fail("Independent root reopening was cancelled.")
                }
            }
            val exceeded = assertNotNull(refusal, "Root reopening did not forward cooperative cancellation during admitted native creation.")
            assertEquals(NativeFontAccessPhase.NATIVE_CREATION, exceeded.phase)
            assertEquals(NativeFontAccessDimension.TRANSIENT_OWNED_BYTES, exceeded.dimension)
            assertEquals(2_271_228L, exceeded.maximum)
            assertEquals(3_028_304L, exceeded.observed)
            token.cancel()
            assertIs<FontOperationResult.Cancelled>(pending.get(10, TimeUnit.SECONDS))
            val handle = success(paragraph.openLayoutHandle(resolver))
            try {
                val asset = assertIs<NativeFontRenderAssetHandle>(success(handle.retainFontAsset(certificate)))
                try { assertHyphen(asset) } finally { success(asset.close()) }
            } finally { success(handle.close()) }
        } finally {
            token.cancel()
            try {
                val result = pending.get(10, TimeUnit.SECONDS)
                if (result is FontOperationResult.Success) success(result.value.close())
            } finally { worker.shutdownNow(); success(resolver.close()) }
        }
    }

    @Test
    fun explicitlyAcceptedNativeRoutePrecedesThePortableOutline() {
        val catalog = liberationCatalog()
        val resolver = success(catalog.openAssetResolver())
        try {
            val requirements = FontAccessRequirementsSnapshot.renderable(listOf(catalog.nativeProfile, portableOutlineProfile))
            val placed = assertIs<EditableLineResult.Success>(line(catalog, resolver, requirements)).line
                .positionedGlyphRuns.single().glyphs.single()
            assertEquals(GlyphId(36), placed.shapedGlyph.glyphId)
            assertEquals(1366f, placed.advance.x.value)
            val certificate = checkNotNull(placed.materializationCertificate)
            assertEquals(GlyphMaterializationRoute.NATIVE_HANDLE, certificate.route)
            assertEquals(catalog.nativeProfile, certificate.assetKey.representationProfile)
            val asset = assertIs<NativeFontRenderAssetHandle>(success(resolver.reopen(certificate.assetKey)))
            try { assertAuditedAdvance(asset) } finally { success(asset.close()) }
        } finally { success(resolver.close()) }
    }

    @Test
    fun orderedNativeCompatibilityAndPortableRequirementPublishTheAuditedOutline() {
        val catalog = liberationCatalog()
        val resolver = success(catalog.openAssetResolver())
        try {
            val incompatible = catalog.nativeProfile.copy(bridgeVersion = "incompatible")
            for (requirements in listOf(
                FontAccessRequirementsSnapshot.renderable(listOf(incompatible, portableOutlineProfile)),
                FontAccessRequirementsSnapshot.renderable(listOf(catalog.nativeProfile, portableOutlineProfile), portableDataRequired = true),
            )) {
                val line = assertIs<EditableLineResult.Success>(line(catalog, resolver, requirements)).line
                val certificate = checkNotNull(line.positionedGlyphRuns.single().glyphs.single().materializationCertificate)
                assertEquals(GlyphId(36), certificate.glyphId)
                assertEquals(GlyphMaterializationRoute.OUTLINE, certificate.route)
                assertEquals(portableOutlineProfile, certificate.assetKey.representationProfile)
                assertNull(certificate.assetKey.nativeContext)
                val asset = success(resolver.reopen(certificate.assetKey))
                try {
                    assertEquals(DesignBounds(4, 0, 1362, 1409),
                        assertIs<GlyphRepresentation.Outline>(success(asset.resolveGlyph(FontGlyphRequest(36)))).outline.bounds)
                } finally { success(asset.close()) }
            }
        } finally { success(resolver.close()) }
    }

    @Test
    fun nativeAccessPolicyAndCancellationAreNotHiddenByPortableSuccess() {
        val catalog = liberationCatalog(CoreTextFontAccessPolicy(2_000_000L, 8_000_000L, 1_232_136L))
        val resolver = success(catalog.openAssetResolver())
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(catalog.nativeProfile, portableOutlineProfile))
        val token = BlockingCoreTextToken()
        val worker = Executors.newSingleThreadExecutor()
        val pending = worker.submit<FontOperationResult<FontRenderAssetHandle>> {
            nativeFont(catalog).acquireRenderAsset(resolver, FontRenderVariantSnapshot.default,
                FontAccessRequirementsSnapshot.renderable(listOf(catalog.nativeProfile)), token)
        }
        try {
            var refusal: NativeFontAccessLimitExceeded? = null
            for (attempt in 0 until 80) {
                if (!token.awaitCheckpoint()) break
                when (val simultaneous = line(catalog, resolver, requirements)) {
                    is EditableLineResult.Success -> token.advance()
                    is EditableLineResult.Failure -> {
                        refusal = assertIs(assertIs<EditableLineError.FontMaterializationFailure>(simultaneous.error).fontError)
                        break
                    }
                    is EditableLineResult.Cancelled -> fail("Independent non-cancelled layout was cancelled.")
                }
            }
            val exceeded = assertNotNull(refusal, "Native admission refusal was hidden by a portable layout success.")
            assertEquals(NativeFontAccessPhase.NATIVE_CREATION, exceeded.phase)
            assertEquals(1_232_136L, exceeded.maximum)
            assertEquals(1_642_848L, exceeded.observed)
            token.cancel()
            assertIs<FontOperationResult.Cancelled>(pending.get(10, TimeUnit.SECONDS))
            assertIs<EditableLineResult.Cancelled>(line(catalog, resolver, requirements, CancellationToken.cancelled))
            success(resolver.close())
            val closed = assertIs<EditableLineResult.Failure>(line(catalog, resolver, requirements))
            assertIs<FontError.ResourceClosed>(assertIs<EditableLineError.FontMaterializationFailure>(closed.error).fontError)
        } finally {
            token.cancel()
            try {
                val result = pending.get(10, TimeUnit.SECONDS)
                if (result is FontOperationResult.Success) success(result.value.close())
            } finally { worker.shutdownNow(); success(resolver.close()) }
        }
    }

    private fun line(
        catalog: CoreTextFontCatalogSnapshot,
        resolver: FontAssetResolverHandle,
        requirements: FontAccessRequirementsSnapshot,
        token: CancellationToken = CancellationToken.none,
    ): EditableLineResult {
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val font = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
        return JvmEditableLineFacade.layout(JvmEditableLineFacadeRequest(
            snapshot = snapshot("A"), font = font, baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en", featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy, features = emptyList(),
            verticalMetrics = LineVerticalMetrics(LayoutUnit(1500f), LayoutUnit(500f)),
            materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantSnapshot.default, requirements),
            cancellationToken = token,
        ))
    }

    @Test
    fun brokenSoftHyphenCertifiesTheVisibleFinalGlyphThroughNativeAccess() {
        val catalog = success(CoreTextFontCatalog.capture(portableFixture("/fonts/dejavu/DejaVuSans.ttf"), generousPolicy))
        val resolver = success(catalog.openAssetResolver())
        try {
            val snapshot = snapshot("co\u00ADoperate")
            val paragraph = paragraph(catalog, resolver, "co\u00ADoperate", 1000f, 2000f, snapshot)
            fun range(start: Int, end: Int) = TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(end))
            assertEquals(listOf(range(0, 3), range(3, 10)), paragraph.lines.map { it.range })
            val allCertificates = paragraph.lines.flatMap { it.positionedGlyphRuns }.flatMap { it.glyphs }
                .map { checkNotNull(it.materializationCertificate) }
            assertTrue(allCertificates.all { it.route == GlyphMaterializationRoute.NATIVE_HANDLE })
            assertEquals(1, allCertificates.map { it.assetKey }.distinct().size)
            val placed = paragraph.lines.first().positionedGlyphRuns.flatMap { it.glyphs }
                .single { it.provenance is GlyphProvenance.Derived }
            val provenance = assertIs<GlyphProvenance.Derived>(placed.provenance)
            assertEquals(GlyphProvenanceRole.SOFT_HYPHEN, provenance.role)
            assertEquals(range(2, 3), provenance.sourceRange)
            assertEquals(GlyphId(16), placed.shapedGlyph.glyphId)
            assertEquals(360.83984375f, placed.advance.x.value)
            val certificate = checkNotNull(placed.materializationCertificate)
            assertEquals(GlyphId(16), certificate.glyphId)
            assertEquals(GlyphMaterializationRoute.NATIVE_HANDLE, certificate.route)
            val asset = assertIs<NativeFontRenderAssetHandle>(success(resolver.reopen(certificate.assetKey)))
            try { assertHyphen(asset) } finally { success(asset.close()) }
        } finally { success(resolver.close()) }
    }

    @Test
    fun nativeParagraphRendererChildrenSurviveAllOriginalOwnersOnAnotherThread() {
        for (fixture in listOf("/fonts/dejavu/DejaVuSans.ttf", "/fonts/liberation/LiberationSans-Regular.ttf")) {
            val catalog = success(CoreTextFontCatalog.capture(portableFixture(fixture), generousPolicy))
            val resolver = success(catalog.openAssetResolver())
            val hyphen = fixture.contains("dejavu")
            val paragraph = paragraph(catalog, resolver, if (hyphen) "co\u00ADoperate" else "A", if (hyphen) 1000f else 2048f, if (hyphen) 2000f else 10000f)
            val placed = paragraph.lines.flatMap { it.positionedGlyphRuns }.flatMap { it.glyphs }
                .single { if (hyphen) it.provenance is GlyphProvenance.Derived else true }
            val certificate = checkNotNull(placed.materializationCertificate)
            val handle = success(paragraph.openLayoutHandle(resolver))
            val retained = assertIs<NativeFontRenderAssetHandle>(success(handle.retainFontAsset(certificate)))
            val first = assertIs<NativeFontRenderAssetHandle>(success(retained.detach()))
            val second = assertIs<NativeFontRenderAssetHandle>(success(retained.detach()))
            val firstLease = assertIs<CoreTextFontLease>(success(first.acquireNativeFontLease()))
            val secondLease = assertIs<CoreTextFontLease>(success(second.acquireNativeFontLease()))
            val worker = Executors.newSingleThreadExecutor()
            try {
                assertEquals(certificate.assetKey, firstLease.key)
                assertEquals(certificate.assetKey, secondLease.key)
                success(retained.close()); success(first.close()); success(second.close())
                success(handle.close()); success(resolver.close())
                assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(handle.retainFontAsset(certificate)).error)
                assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(retained.acquireNativeFontLease()).error)
                worker.submit<Unit> {
                    success(firstLease.validateGlyph(certificate.glyphId))
                    assertEquals(if (hyphen) 360.83984375 else 1366.0,
                        CoreTextConsumerProbe.horizontalAdvance(success(firstLease.fontRef()), if (hyphen) 16 else 36), 0.000001)
                    success(firstLease.close())
                    success(secondLease.validateGlyph(certificate.glyphId))
                    assertEquals(if (hyphen) 360.83984375 else 1366.0,
                        CoreTextConsumerProbe.horizontalAdvance(success(secondLease.fontRef()), if (hyphen) 16 else 36), 0.000001)
                }.get(10, TimeUnit.SECONDS)
                assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(firstLease.fontRef()).error)
            } finally {
                success(firstLease.close()); success(secondLease.close()); worker.shutdownNow()
                success(first.close()); success(second.close()); success(retained.close()); success(handle.close())
                success(resolver.close())
            }
        }
    }

    private fun assertHyphen(asset: NativeFontRenderAssetHandle) {
        val lease = assertIs<CoreTextFontLease>(success(asset.acquireNativeFontLease()))
        try {
            success(lease.validateGlyph(GlyphId(16)))
            assertEquals(360.83984375, CoreTextConsumerProbe.horizontalAdvance(success(lease.fontRef()), 16), 0.000001)
            val bounds = CoreTextConsumerProbe.pathBounds(success(lease.fontRef()), 16)
            listOf(48.828125, 233.88671875, 312.01171875, 313.96484375).zip(bounds).forEach { (expected, actual) ->
                assertEquals(expected, actual, 0.000001)
            }
        } finally { success(lease.close()) }
    }

    private fun snapshot(text: String): TextSnapshot =
        Kalligraphie.decodeUtf16(TextVersion.create(), listOf(TextSlice.Utf16(text.toCharArray()))).snapshot

    private fun paragraph(
        catalog: CoreTextFontCatalogSnapshot,
        resolver: FontAssetResolverHandle,
        text: String,
        size: Float,
        width: Float,
        textSnapshot: TextSnapshot = snapshot(text),
    ): ParagraphLayout {
        val snapshot = textSnapshot
        val face = catalog.faces.single().id
        return assertIs<ParagraphLayoutResult.Success>(JvmEditableParagraphFacade.layout(
            JvmEditableParagraphFacadeRequest(
                snapshot = snapshot, sourceRange = snapshot.range,
                constraints = HorizontalParagraphConstraints(
                    LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(100f + width), LayoutUnit(2450f)),
                    LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
                ),
                baseDirection = BaseDirection.LEFT_TO_RIGHT, language = "en", fontCatalog = catalog,
                resolutionPolicy = FontResolutionPolicySnapshot(catalog.generation, "audited-native-paragraph", "1", listOf(FontResolutionCandidate(face)), face),
                fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(size)),
                materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantSnapshot.default,
                    FontAccessRequirementsSnapshot.renderable(listOf(catalog.nativeProfile))),
            ),
        )).layout
    }

    @Test
    fun finalAmiriLigatureUsesTheExactNativeFontWithoutRemappingCharacters() {
        val catalog = success(CoreTextFontCatalog.capture(portableFixture("/fonts/amiri/Amiri-Regular.ttf"), generousPolicy))
        val resolver = success(catalog.openAssetResolver())
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(catalog.nativeProfile))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val font = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(1000f))))
        val snapshot = Kalligraphie.decodeUtf16(TextVersion.create(), listOf(TextSlice.Utf16("ffi".toCharArray()))).snapshot
        try {
            val result = JvmEditableLineFacade.layout(JvmEditableLineFacadeRequest(
                snapshot = snapshot, font = font, baseDirection = BaseDirection.LEFT_TO_RIGHT,
                language = "en", featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                features = emptyList(), verticalMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
                materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantSnapshot.default, requirements),
            ))
            val placed = assertIs<EditableLineResult.Success>(result).line.positionedGlyphRuns.single().glyphs.single()
            assertEquals(GlyphId(6631), placed.shapedGlyph.glyphId)
            assertEquals(795f, placed.advance.x.value)
            val certificate = checkNotNull(placed.materializationCertificate)
            assertEquals(GlyphMaterializationRoute.NATIVE_HANDLE, certificate.route)
            val asset = assertIs<NativeFontRenderAssetHandle>(success(resolver.reopen(certificate.assetKey)))
            try {
                val lease = assertIs<CoreTextFontLease>(success(asset.acquireNativeFontLease()))
                try {
                    success(lease.validateGlyph(GlyphId(6631)))
                    assertEquals(795.0, CoreTextConsumerProbe.horizontalAdvance(success(lease.fontRef()), 6631), 0.000001)
                } finally { success(lease.close()) }
            } finally { success(asset.close()) }
        } finally { success(resolver.close()) }
    }
}
