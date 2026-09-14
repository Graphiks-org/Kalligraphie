package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.*
import org.graphiks.kalligraphie.api.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CoreTextSharedCacheOwnershipTest {
    private val local = FontMaterializationCachePolicy(1_000_000L)

    @Test fun distinctCapturedSourcesAndSizesKeepAuditedMetricsUnderEveryNativeBudget() {
        // A wrong source/size cache selection would change these independently audited advances.
        val budgets = listOf(FontCacheBudget(0, 0, 0, 0), FontCacheBudget(1, 0, 2_000_000, 8),
            FontCacheBudget(8_000, 0, 2_000_000, 8), FontCacheBudget(100_000, 0, 758_000, 8),
            FontCacheBudget(100_000, 0, 2_000_000, 4))
        for (budget in budgets) {
            val scope = Kalligraphie.fontCacheScope(budget)
            val first = capture("/fonts/liberation/LiberationSans-Regular.ttf", scope)
            val second = capture("/fonts/dejavu/DejaVuSans.ttf", scope)
            var one = success(first.openAssetResolver())
            val two = success(second.openAssetResolver())
            try {
                val original = asset(first, one, 2048f)
                val key = original.key
                val detached = assertIs<PlatformFontRenderAssetHandle>(success(original.detach()))
                val lease = assertIs<CoreTextFontLease>(success(detached.acquirePlatformFontLease()))
                try {
                    success(original.close())
                    repeat(3) {
                        checked(first, one, 1024f, 36, 683.0)
                        checked(second, two, 2048f, 16, 739.0)
                        checked(second, two, 1000f, 16, 360.83984375)
                        val reopened = assertIs<PlatformFontRenderAssetHandle>(success(one.reopen(key)))
                        try { metrics(reopened, 36, 1366.0); metrics(reopened, 3, 569.0) }
                        finally { success(reopened.close()) }
                    }
                    assertIs<FontError.IncompatibleCatalogGeneration>(assertIs<FontOperationResult.Failure>(two.reopen(key)).error)
                    success(one.close())
                    one = success(first.openAssetResolver())
                    val reopened = assertIs<PlatformFontRenderAssetHandle>(success(one.reopen(key)))
                    try { metrics(reopened, 36, 1366.0) } finally { success(reopened.close()) }
                    success(one.close()); success(two.close()); success(scope.close()); success(scope.close())
                    metrics(detached, 36, 1366.0)
                    success(detached.close())
                    assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(detached.acquirePlatformFontLease()).error)
                    assertEquals(1366.0, CoreTextConsumerProbe.horizontalAdvance(success(lease.fontRef()), 36), 0.000001)
                    assertEquals(569.0, CoreTextConsumerProbe.horizontalAdvance(success(lease.fontRef()), 3), 0.000001)
                    val afterClose = capture("/fonts/dejavu/DejaVuSans.ttf", scope)
                    val later = success(afterClose.openAssetResolver())
                    try { repeat(2) { checked(afterClose, later, 2048f, 16, 739.0) } }
                    finally { success(later.close()) }
                    success(lease.close())
                    assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(lease.fontRef()).error)
                } finally { success(lease.close()); success(detached.close()); success(original.close()) }
            } finally { success(one.close()); success(two.close()); success(scope.close()) }
        }
    }

    @Test fun locallyAndSharedCachedReopeningSurvivesAdmittedCloseAndCancellation() {
        // Premature final-capture drainage would invalidate the independently admitted operation.
        for (shared in listOf(false, true)) {
            val scope = if (shared) Kalligraphie.fontCacheScope(FontCacheBudget(100_000, 0, 2_000_000, 8)) else null
            val catalog = capture("/fonts/liberation/LiberationSans-Regular.ttf", scope)
            val resolver = success(catalog.openAssetResolver())
            val original = asset(catalog, resolver, 2048f)
            val key = original.key
            success(original.close())
            val worker = Executors.newSingleThreadExecutor()
            val entered = java.util.concurrent.CountDownLatch(1)
            val resume = java.util.concurrent.CountDownLatch(1)
            val stopped = java.util.concurrent.atomic.AtomicBoolean()
            val token = CancellationToken {
                entered.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
                stopped.get()
            }
            val pending = worker.submit<FontOperationResult<FontRenderAssetHandle>> { resolver.reopen(key, token) }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                success(resolver.close()); scope?.let { success(it.close()) }
                assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(resolver.reopen(key)).error)
                resume.countDown()
                val held = assertIs<PlatformFontRenderAssetHandle>(success(pending.get(10, TimeUnit.SECONDS)))
                try { metrics(held, 36, 1366.0) } finally { success(held.close()) }
                var later = success(catalog.openAssetResolver())
                try {
                    repeat(3) {
                        checked(catalog, later, 2048f, 36, 1366.0)
                        assertIs<FontOperationResult.Cancelled>(later.reopen(key, CancellationToken { true }))
                    }
                    checked(catalog, later, 1024f, 36, 683.0)
                    val cancelEntered = java.util.concurrent.CountDownLatch(1)
                    val cancelResume = java.util.concurrent.CountDownLatch(1)
                    val cancelling = CancellationToken {
                        cancelEntered.countDown(); check(cancelResume.await(10, TimeUnit.SECONDS)); true
                    }
                    val cancelled = worker.submit<FontOperationResult<FontRenderAssetHandle>> { later.reopen(key, cancelling) }
                    assertTrue(cancelEntered.await(10, TimeUnit.SECONDS))
                    success(later.close())
                    cancelResume.countDown()
                    assertIs<FontOperationResult.Cancelled>(cancelled.get(10, TimeUnit.SECONDS))
                    later = success(catalog.openAssetResolver())
                    checked(catalog, later, 2048f, 3, 569.0)
                } finally { success(later.close()) }
                success(resolver.close())
            } finally {
                stopped.set(true); resume.countDown()
                val result = pending.get(10, TimeUnit.SECONDS)
                if (result is FontOperationResult.Success) success(result.value.close())
                worker.shutdownNow(); success(resolver.close()); scope?.let { success(it.close()) }
            }
        }
    }

    @Test fun retainedConsumerFontDrawsAuditedInkAfterPressureAndCaptureDrainage() {
        val scope = Kalligraphie.fontCacheScope(FontCacheBudget(100_000, 0, 2_000_000, 4))
        val catalog = capture("/fonts/liberation/LiberationSans-Regular.ttf", scope)
        val resolver = success(catalog.openAssetResolver())
        val foreign = capture("/fonts/dejavu/DejaVuSans.ttf", scope)
        val pressure = success(foreign.openAssetResolver())
        val source = Kalligraphie.decodeUtf16(TextVersion.create(), listOf(TextSlice.Utf16("A".toCharArray()))).snapshot
        val face = catalog.faces.single().id
        try {
            val paragraph = assertIs<ParagraphLayoutResult.Success>(JvmEditableParagraphFacade.layout(JvmEditableParagraphFacadeRequest(
                snapshot = source, sourceRange = source.range,
                constraints = HorizontalParagraphConstraints(LayoutRect(LayoutUnit(100f), LayoutUnit(50f), LayoutUnit(10100f), LayoutUnit(2450f)),
                    LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f))),
                baseDirection = BaseDirection.LEFT_TO_RIGHT, language = "en", fontCatalog = catalog,
                resolutionPolicy = FontResolutionPolicySnapshot(catalog.generation, "audited-platform-paragraph", "1", listOf(FontResolutionCandidate(face)), face),
                fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(2048f)),
                materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantSnapshot.default,
                    FontAccessRequirementsSnapshot.renderable(listOf(catalog.platformProfile)))))).layout
            val placed = paragraph.lines.single().positionedGlyphRuns.single().glyphs.single()
            val consumer = asset(catalog, resolver, 2048f)
            val lease = assertIs<CoreTextFontLease>(success(consumer.acquirePlatformFontLease()))
            try {
                checked(foreign, pressure, 2048f, 16, 739.0)
                success(consumer.close()); success(resolver.close()); success(pressure.close()); success(scope.close())
                val observed = CoreTextDrawingProbe.draw(success(lease.fontRef()), placed)
                assertTrue(observed.crossbarAlpha > 240)
                assertEquals(0, observed.counterAlpha); assertEquals(0, observed.outsideAlpha)
                assertEquals(listOf(1.2, 0.1, 0.2, 0.9, 7.0, 11.0), observed.textMatrix)
                assertEquals(listOf(0.1, 0.0, 0.0, 0.1, 20.0, 0.0), observed.ctm)
            } finally { success(lease.close()); success(consumer.close()) }
        } finally { success(resolver.close()); success(pressure.close()); success(scope.close()) }
    }

    private fun capture(path: String, scope: FontCacheScope?): CoreTextFontCatalogSnapshot =
        success(CoreTextFontCatalog.capture(portableFixture(path), generousPolicy, cachePolicy = local, cacheScope = scope))
    private fun asset(catalog: CoreTextFontCatalogSnapshot, resolver: FontAssetResolverHandle, size: Float): PlatformFontRenderAssetHandle {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(catalog.platformProfile))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val font = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(size))))
        return assertIs(success(font.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements)))
    }
    private fun checked(catalog: CoreTextFontCatalogSnapshot, resolver: FontAssetResolverHandle, size: Float, glyph: Int, advance: Double) {
        val selected = asset(catalog, resolver, size)
        try { metrics(selected, glyph, advance) } finally { success(selected.close()) }
    }
    private fun metrics(asset: PlatformFontRenderAssetHandle, glyph: Int, advance: Double) {
        val lease = assertIs<CoreTextFontLease>(success(asset.acquirePlatformFontLease()))
        try {
            success(lease.validateGlyph(GlyphId(glyph)))
            assertEquals(advance, CoreTextConsumerProbe.horizontalAdvance(success(lease.fontRef()), glyph), 0.000001)
        } finally { success(lease.close()) }
    }
}
