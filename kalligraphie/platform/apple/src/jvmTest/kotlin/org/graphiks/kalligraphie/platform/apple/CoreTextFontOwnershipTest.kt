package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*
import kotlin.test.*

class CoreTextFontOwnershipTest {
    @Test fun portableAcquisitionAndReopeningCheckCancellationBeforeReturningTheirOutcome() {
        val catalog = portableFixture("/fonts/liberation/LiberationSans-Regular.ttf")
        val resolver = success(catalog.openAssetResolver())
        fun cancellingAfterDispatch(): CancellationToken {
            val first = java.util.concurrent.atomic.AtomicBoolean(true)
            return CancellationToken { !first.getAndSet(false) }
        }
        try {
            val requirements = FontAccessRequirementsSnapshot.renderable(portableOutlineProfile)
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val font = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
            val unsupported = FontAccessRequirementsSnapshot.renderable(listOf(NativeHandleProfile("another.bridge", "1")))
            assertIs<FontOperationResult.Cancelled>(font.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, unsupported, cancellingAfterDispatch()))
            assertIs<FontOperationResult.Cancelled>(font.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements, cancellingAfterDispatch()))
            assertIs<FontOperationResult.Cancelled>(font.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements, cancellingAfterDispatch()))
            val asset = success(font.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements))
            try {
                assertIs<FontOperationResult.Cancelled>(resolver.reopen(asset.key, cancellingAfterDispatch()))
                val reopened = success(resolver.reopen(asset.key))
                try {
                    val outline = assertIs<GlyphRepresentation.Outline>(success(reopened.resolveGlyph(FontGlyphRequest(36)))).outline
                    assertEquals(DesignBounds(4, 0, 1362, 1409), outline.bounds)
                } finally { success(reopened.close()) }
            } finally { success(asset.close()) }
        } finally { success(resolver.close()) }
    }
    @Test fun simultaneousDirectAcquireAndReopenShareTheFiniteCreationAdmissionAcrossResolvers() {
        val catalog = liberationCatalog(CoreTextFontAccessPolicy(2_000_000L, 8_000_000L, 1_232_136L))
        val first = success(catalog.openAssetResolver())
        val second = success(catalog.openAssetResolver())
        val savedAsset = nativeAsset(catalog, second)
        val savedKey = savedAsset.key
        success(savedAsset.close())
        val font = nativeFont(catalog)
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(catalog.nativeProfile))
        val token = BlockingCoreTextToken()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val held = worker.submit<FontOperationResult<FontRenderAssetHandle>> { font.acquireRenderAsset(first, FontRenderVariantSnapshot.default, requirements, token) }
        try {
            var refused: NativeFontAccessLimitExceeded? = null
            // Advance actual checkpoints until a real simultaneous creation is refused, rather
            // than assuming a wrapper-specific token invocation number indicates reservation.
            for (attempt in 0 until 80) {
                if (!token.awaitCheckpoint()) break
                when (val candidate = font.acquireRenderAsset(second, FontRenderVariantSnapshot.default, requirements)) {
                    is FontOperationResult.Success -> { success(candidate.value.close()); token.advance() }
                    is FontOperationResult.Failure -> { refused = assertIs(candidate.error); break }
                    is FontOperationResult.Cancelled -> fail("Independent non-cancelled acquisition was cancelled.")
                }
            }
            val exceeded = assertNotNull(refused, "Simultaneous direct acquisitions did not enforce shared finite admission.")
            assertEquals(NativeFontAccessPhase.NATIVE_CREATION, exceeded.phase)
            assertEquals(NativeFontAccessDimension.TRANSIENT_OWNED_BYTES, exceeded.dimension)
            assertEquals(1_232_136L, exceeded.maximum)
            assertEquals(1_642_848L, exceeded.observed)
            val reopenLimit = assertIs<NativeFontAccessLimitExceeded>(assertIs<FontOperationResult.Failure>(second.reopen(savedKey)).error)
            assertEquals(exceeded, reopenLimit)
            token.cancel()
            assertIs<FontOperationResult.Cancelled>(held.get(10, java.util.concurrent.TimeUnit.SECONDS))
            val later = nativeAsset(catalog, second)
            try { assertAuditedAdvance(later) } finally { success(later.close()) }
        } finally {
            token.cancel()
            try {
                val result = held.get(10, java.util.concurrent.TimeUnit.SECONDS)
                if (result is FontOperationResult.Success) success(result.value.close())
            } finally { worker.shutdownNow(); success(first.close()); success(second.close()) }
        }
    }
    @Test fun portableDataNegotiationAndDetachedOutlineKeepTheFrozenSourceAfterResolverClosure() {
        val catalog = liberationCatalog()
        val resolver = success(catalog.openAssetResolver())
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(catalog.nativeProfile, portableOutlineProfile), portableDataRequired = true)
        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val font = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
            val asset = success(font.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements))
            try {
                assertFalse(asset is NativeFontRenderAssetHandle)
                assertEquals(catalog.generation, asset.key.generation)
                assertEquals(portableOutlineProfile, asset.key.representationProfile)
                assertNull(asset.key.nativeContext)
                val reopened = success(resolver.reopen(asset.key))
                try {
                    val detached = success(asset.detach())
                    try {
                        success(resolver.close()); success(asset.close())
                        val second = success(detached.detach())
                        try {
                            success(detached.close())
                            for (owner in listOf(second, reopened)) {
                                val outline = assertIs<GlyphRepresentation.Outline>(success(owner.resolveGlyph(FontGlyphRequest(36)))).outline
                                assertEquals(DesignBounds(4, 0, 1362, 1409), outline.bounds)
                                assertEquals(asset.key, owner.key)
                            }
                        } finally { success(second.close()) }
                    } finally { success(detached.close()) }
                } finally { success(reopened.close()) }
            } finally { success(asset.close()) }
        } finally { success(resolver.close()) }
    }
    @Test fun exactNativeKeyReopensAuditedAccessAndRejectsForeignGenerationOrContext() {
        val catalog = liberationCatalog()
        val resolver = success(catalog.openAssetResolver())
        val foreignCatalog = liberationCatalog()
        val foreign = success(foreignCatalog.openAssetResolver())
        try {
            val parent = nativeAsset(catalog, resolver)
            val key = parent.key
            success(parent.close())
            val reopened = assertIs<NativeFontRenderAssetHandle>(success(resolver.reopen(key)))
            try {
                assertEquals(key, reopened.key)
                assertAuditedAdvance(reopened)
                assertIs<FontError.IncompatibleCatalogGeneration>(assertIs<FontOperationResult.Failure>(foreign.reopen(key)).error)
                val context = checkNotNull(key.nativeContext)
                val altered = listOf(key.copy(nativeContext = context.copy(reopenToken = "another.token")),
                    key.copy(nativeContext = context.copy(routeIdentity = context.routeIdentity.copy(runtimeInterpretationId = "another.runtime"))),
                    key.copy(representationProfile = catalog.nativeProfile.copy(bridgeId = "another.bridge")),
                    key.copy(fontInstanceKey = key.fontInstanceKey.copy(layoutSize = LayoutUnit(1024f))))
                for (bad in altered) assertIs<FontError.AssetUnavailable>(assertIs<FontOperationResult.Failure>(resolver.reopen(bad)).error)
                val other = nativeAsset(foreignCatalog, foreign)
                try { assertNotEquals(key.semanticIdentity, other.key.semanticIdentity); assertAuditedAdvance(other) }
                finally { success(other.close()) }
                success(resolver.close())
                assertAuditedAdvance(reopened)
            } finally { success(reopened.close()) }
        } finally { success(resolver.close()); success(foreign.close()) }
    }
    @Test fun independentlyDetachedOwnersAndAdmittedChildSurviveParentClosureAcrossThreads() {
        val catalog = liberationCatalog()
        val resolver = success(catalog.openAssetResolver())
        val parent = nativeAsset(catalog, resolver)
        val first = assertIs<NativeFontRenderAssetHandle>(success(parent.detach()))
        val second = assertIs<NativeFontRenderAssetHandle>(success(parent.detach()))
        val grandchild = assertIs<NativeFontRenderAssetHandle>(success(first.detach()))
        val lease = assertIs<CoreTextFontLease>(success(grandchild.acquireNativeFontLease()))
        val entered = java.util.concurrent.CountDownLatch(1)
        val query = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val answer = worker.submit<Double> {
            entered.countDown()
            check(query.await(10, java.util.concurrent.TimeUnit.SECONDS))
            CoreTextConsumerProbe.horizontalAdvance(success(lease.fontRef()), 36)
        }
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            success(parent.close()); success(first.close()); success(grandchild.close()); success(resolver.close())
            assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(parent.acquireNativeFontLease()).error)
            assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(grandchild.acquireNativeFontLease()).error)
            query.countDown()
            assertEquals(1366.0, answer.get(10, java.util.concurrent.TimeUnit.SECONDS), 0.000001)
            assertAuditedAdvance(second)
            success(lease.close()); success(lease.close())
            assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(lease.fontRef()).error)
        } finally {
            query.countDown(); worker.shutdownNow()
            success(lease.close()); success(grandchild.close()); success(second.close()); success(first.close()); success(parent.close()); success(resolver.close())
        }
    }
    @Test fun nativeValidationAcceptsZeroAndNoInkButRejectsTheFirstMissingIdentifier() {
        val catalog = liberationCatalog()
        val resolver = success(catalog.openAssetResolver())
        val asset = nativeAsset(catalog, resolver)
        try {
            val lease = assertIs<CoreTextFontLease>(success(asset.acquireNativeFontLease()))
            try {
                success(lease.validateGlyph(GlyphId(0)))
                success(lease.validateGlyph(GlyphId(3))) // Liberation space is glyph 3, with no ink.
                assertEquals(569.0, CoreTextConsumerProbe.horizontalAdvance(success(lease.fontRef()), 3), 0.000001)
                val rejected = assertIs<FontOperationResult.Failure>(lease.validateGlyph(GlyphId(2620)))
                assertIs<FontError.GlyphOutOfRange>(rejected.error)
                assertIs<FontError.GlyphOutOfRange>(assertIs<FontOperationResult.Failure>(lease.validateGlyph(GlyphId(65536))).error)
                assertIs<FontError.UnsupportedRepresentationProfile>(assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(3))).error)
            } finally { success(lease.close()) }
        } finally { success(asset.close()); success(resolver.close()) }
    }
}
