package org.graphiks.kalligraphie

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FlowChain
import org.graphiks.kalligraphie.api.FlowCompositionResult
import org.graphiks.kalligraphie.api.FlowLayout
import org.graphiks.kalligraphie.api.FlowLayoutState
import org.graphiks.kalligraphie.api.FlowRegion
import org.graphiks.kalligraphie.api.FlowRegionIdentity
import org.graphiks.kalligraphie.api.FlowRegionResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMaterializationCertificate
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.InlineInterval
import org.graphiks.kalligraphie.api.LayoutHandle
import org.graphiks.kalligraphie.api.LayoutInput
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineBand
import org.graphiks.kalligraphie.api.LineOverscan
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.WritingMode
import org.graphiks.kalligraphie.api.createIncrementalFlowLayoutRequest
import org.graphiks.kalligraphie.layout.openLayoutHandle
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LayoutFontAssetHandoffTest {
    @Test
    fun paragraphHandsAuditedAssetsFromEveryFinalFontToTheRenderer() {
        val fixture = incrementalRealFontFixture(
            "A\u0633",
            fonts = listOf(
                IncrementalFontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
                IncrementalFontFixture("amiri/Amiri-Regular.ttf", "Amiri"),
            ),
        )
        val resolver = success(fixture.catalog.openAssetResolver())
        val backend = success(JvmHarfBuzzShapingBackend.open())
        val request = JvmEditableParagraphFacadeRequest(
            snapshot = fixture.snapshot,
            sourceRange = fixture.snapshot.range,
            constraints = incrementalTestConstraints(width = 10_000f, top = 0f, height = 2_400f),
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            fontCatalog = fixture.catalog,
            resolutionPolicy = fixture.policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
            materialization = EditableLineMaterialization.Renderable(
                resolver,
                FontRenderVariantSnapshot.default,
                FontAccessRequirementsSnapshot.renderable(auditedOutlineProfile()),
            ),
        )
        val paragraph = assertIs<ParagraphLayoutResult.Success>(
            JvmEditableParagraphFacade.layoutBorrowing(request, backend),
        ).layout
        val publishedGeometry = paragraph.lines.map { line ->
            listOf(
                line.range,
                line.baseline,
                line.contentMetrics,
                line.lineBox,
                line.designInkBounds,
                line.allCaretCandidates.map { caret -> caret.position to caret.geometry },
            )
        }

        val handle = success(paragraph.openLayoutHandle(resolver))
        success(resolver.close())
        success(backend.close())
        val certificatesByAsset = paragraph.lines
            .flatMap { line -> line.positionedGlyphRuns }
            .flatMap { run -> run.glyphs }
            .mapNotNull { glyph -> glyph.materializationCertificate }
            .groupBy { certificate -> certificate.assetKey }
        assertEquals(2, certificatesByAsset.size)
        val rendererAssets = certificatesByAsset.map { (key, certificates) ->
            key.fontInstanceKey.face to success(handle.retainFontAsset(certificates.first()))
        }.toMap()
        try {
            assertAuditedOutline(
                checkNotNull(rendererAssets[fixture.faces[0]]),
                GlyphId(36),
                unitsPerEm = 2048,
                bounds = DesignBounds(4, 0, 1362, 1409),
            )
            assertAuditedOutline(
                checkNotNull(rendererAssets[fixture.faces[1]]),
                GlyphId(6227),
                unitsPerEm = 1000,
                bounds = DesignBounds(-14, -3, 619, 647),
            )
            success(handle.close())
            assertEquals(
                publishedGeometry,
                paragraph.lines.map { line ->
                    listOf(
                        line.range,
                        line.baseline,
                        line.contentMetrics,
                        line.lineBox,
                        line.designInkBounds,
                        line.allCaretCandidates.map { caret -> caret.position to caret.geometry },
                    )
                },
            )
        } finally {
            rendererAssets.values.forEach { asset -> success(asset.close()) }
            success(handle.close())
        }
    }

    @Test
    fun flowHandsAssetsFromReusedAndNewFragmentsToTheRenderer() {
        val fixture = incrementalRealFontFixture(
            "A\n\u0633",
            fonts = listOf(
                IncrementalFontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
                IncrementalFontFixture("amiri/Amiri-Regular.ttf", "Amiri"),
            ),
        )
        val resolver = success(fixture.catalog.openAssetResolver())
        val backend = success(JvmHarfBuzzShapingBackend.open())
        val chain = FlowChain(List(2) { index ->
            TestFlowRegion(
                LayoutRect(
                    LayoutUnit(100f),
                    LayoutUnit(100f + index * 2_000f),
                    LayoutUnit(4_100f),
                    LayoutUnit(1_300f + index * 2_000f),
                ),
                listOf(InlineInterval(0f, 4_000f)),
            )
        })
        val materialization = EditableLineMaterialization.Renderable(
            resolver,
            FontRenderVariantSnapshot.default,
            FontAccessRequirementsSnapshot.renderable(auditedOutlineProfile()),
        )
        fun request(previousState: FlowLayoutState?, requestedEnd: Int): JvmFlowCompositionRequest {
            val portable = assertIs<FlowCompositionResult.Success<org.graphiks.kalligraphie.api.IncrementalFlowLayoutRequest>>(
                createIncrementalFlowLayoutRequest(
                    input = LayoutInput(fixture.snapshot, fixture.typography),
                    requestedRange = fixture.snapshot.incrementalRange(0, requestedEnd),
                    constraints = incrementalTestConstraints(width = 4_000f, top = 100f, height = 1_200f),
                    flowChain = chain,
                    overscan = LineOverscan(0),
                    previousState = previousState,
                ),
            ).value
            return JvmFlowCompositionRequest(
                request = portable,
                baseDirection = BaseDirection.LEFT_TO_RIGHT,
                language = "en",
                materialization = materialization,
            )
        }

        val first = flowSuccess(JvmFlowCompositionFacade.layoutBorrowing(request(null, 1), backend))
        assertEquals(listOf(fixture.snapshot.incrementalRange(0, 2)), first.lines.map { it.range })
        assertEquals(
            fixture.snapshot.incrementalRange(2, 3),
            checkNotNull(first.unmaterializedTail).remainingSourceRange,
        )
        val prefixCertificate = checkNotNull(
            first.lines.single().positionedGlyphRuns.single().glyphs.single().materializationCertificate,
        )
        val completed = flowSuccess(
            JvmFlowCompositionFacade.layoutBorrowing(request(first.state, 3), backend),
        )
        assertEquals(
            listOf(fixture.snapshot.incrementalRange(0, 2), fixture.snapshot.incrementalRange(2, 3)),
            completed.lines.map { it.range },
        )
        val publishedCertificates = completed.lines.map { line ->
            line.positionedGlyphRuns.flatMap { run ->
                run.glyphs.mapNotNull { glyph -> glyph.materializationCertificate }
            }
        }
        assertEquals(prefixCertificate, publishedCertificates.first().single())
        val suffixCertificate = publishedCertificates.last().single()
        assertEquals(fixture.faces[0], prefixCertificate.assetKey.fontInstanceKey.face)
        assertEquals(fixture.faces[1], suffixCertificate.assetKey.fontInstanceKey.face)
        assertEquals(GlyphId(67), suffixCertificate.glyphId)

        val handle = success(completed.openLayoutHandle(resolver))
        success(resolver.close())
        success(backend.close())
        val rendererAssets = publishedCertificates.flatten()
            .groupBy { certificate -> certificate.assetKey }
            .map { (key, certificates) ->
                key.fontInstanceKey.face to success(handle.retainFontAsset(certificates.first()))
            }
            .toMap()
        try {
            assertAuditedOutline(
                checkNotNull(rendererAssets[fixture.faces[0]]),
                GlyphId(36),
                unitsPerEm = 2048,
                bounds = DesignBounds(4, 0, 1362, 1409),
            )
            assertAuditedOutline(
                checkNotNull(rendererAssets[fixture.faces[1]]),
                suffixCertificate.glyphId,
                unitsPerEm = 1000,
                bounds = DesignBounds(57, -233, 929, 373),
            )
        } finally {
            rendererAssets.values.forEach { asset -> success(asset.close()) }
            success(handle.close())
        }
    }

    @Test
    fun rendererAssetSurvivesARealRetainCloseRace() {
        val fixture = openFixture()
        val workers = Executors.newFixedThreadPool(9)
        try {
            repeat(100) {
                val handle = success(fixture.line.openLayoutHandle(fixture.resolver))
                val retainedBeforeRace = success(handle.retainFontAsset(fixture.certificate))
                val start = CountDownLatch(1)
                val results = List(8) {
                    workers.submit(Callable {
                        start.await()
                        handle.retainFontAsset(fixture.certificate)
                    })
                }
                val closure = workers.submit(Callable {
                    start.await()
                    handle.close()
                })
                start.countDown()
                val retainedResults = results.map { it.get(10, TimeUnit.SECONDS) }
                try {
                    success(closure.get(10, TimeUnit.SECONDS))
                    assertBitmap(retainedBeforeRace)
                    for (result in retainedResults) {
                        when (result) {
                            is FontOperationResult.Success -> assertBitmap(result.value)
                            is FontOperationResult.Failure -> assertIs<FontError.ResourceClosed>(result.error)
                            is FontOperationResult.Cancelled -> error("Retention without cancellation was cancelled.")
                        }
                    }
                } finally {
                    retainedResults.filterIsInstance<FontOperationResult.Success<FontRenderAssetHandle>>()
                        .forEach { success(it.value.close()) }
                    success(retainedBeforeRace.close())
                    success(handle.close())
                }
            }
        } finally {
            workers.shutdownNow()
            fixture.close()
        }
    }

    @Test
    fun closedHandleReportsResourceClosedBeforeCertificateMembership() {
        val fixture = openFixture()
        try {
            val handle = success(fixture.line.openLayoutHandle(fixture.resolver))
            success(handle.close())
            val unknown = fixture.certificate.copy(glyphId = GlyphId(1))
            assertIs<FontError.ResourceClosed>(
                assertIs<FontOperationResult.Failure>(handle.retainFontAsset(unknown)).error,
            )
            assertIs<FontError.ResourceClosed>(
                assertIs<FontOperationResult.Failure>(handle.retainFontAsset(fixture.certificate)).error,
            )
            assertEquals(fixture.line, handle.layout)
            success(handle.close())
        } finally { fixture.close() }
    }

    @Test
    fun openHandleRejectsAnotherRealGlyphCertificateFromTheSameAsset() {
        val fixture = openFixture()
        try {
            val handle = success(fixture.line.openLayoutHandle(fixture.resolver))
            try {
                assertEquals(GlyphId(1), success(fixture.font.resolveGlyph(' '.code)).glyphId)
                val certificate = fixture.certificate.copy(glyphId = GlyphId(1))
                val result = handle.retainFontAsset(certificate)
                if (result is FontOperationResult.Success) result.value.close()
                assertIs<FontError.CertificateNotInLayout>(assertIs<FontOperationResult.Failure>(result).error)
            } finally { success(handle.close()) }
        } finally { fixture.close() }
    }

    @Test
    fun cancelledHandoffPublishesNoLayoutHandle() {
        val fixture = openFixture()
        try {
            val result = fixture.line.openLayoutHandle(fixture.resolver, CancellationToken.cancelled)
            if (result is FontOperationResult.Success) result.value.close()
            assertIs<FontOperationResult.Cancelled>(result)
            val retry = success(fixture.line.openLayoutHandle(fixture.resolver))
            try {
                val renderer = success(retry.retainFontAsset(fixture.certificate))
                try { assertBitmap(renderer) } finally { success(renderer.close()) }
            } finally { success(retry.close()) }
        } finally { fixture.close() }
    }

    @Test
    fun retainedBitmapSurvivesSessionResolverAndLayoutHandleClosure() {
        val fixture = openFixture()
        try {
            val handle: LayoutHandle<EditableLine> = success(fixture.line.openLayoutHandle(fixture.resolver))
            try {
                success(fixture.session.close())
                success(fixture.resolver.close())
                val renderer = success(handle.retainFontAsset(fixture.certificate))
                try {
                    success(handle.close())
                    assertBitmap(renderer)
                } finally {
                    success(renderer.close())
                }
            } finally {
                success(handle.close())
            }
        } finally {
            fixture.close()
        }
    }

    // Catches an abort that forgets an earlier detached root after a later public reopen failure.
    @Test
    fun laterRootFailurePublishesNoHandleClosesCapturedOwnersAndLeavesResolverBorrowed() {
        val fixture = openMultiFontParagraphFixture()
        val captured = mutableListOf<FontRenderAssetHandle>()
        try {
            val certificates = fixture.certificates
            val failedKey = certificates.last().assetKey
            val resolver = object : FontAssetResolverHandle by fixture.resolver {
                override fun reopen(key: FontRenderAssetKey, cancellationToken: CancellationToken): FontOperationResult<FontRenderAssetHandle> =
                    if (key == failedKey) {
                        FontOperationResult.Failure(FontError.InvalidFontData("Later certified root is unavailable."))
                    } else {
                        captureAssets(fixture.resolver.reopen(key, cancellationToken), captured)
                    }
            }

            val opened = fixture.paragraph.openLayoutHandle(resolver)

            assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(opened).error)
            assertCapturedOwnersAreClosed(captured, certificates.first().glyphId)
            val stillBorrowed = success(fixture.resolver.reopen(certificates.first().assetKey))
            try {
                assertAuditedOutline(stillBorrowed, GlyphId(36), 2048, DesignBounds(4, 0, 1362, 1409))
            } finally {
                success(stillBorrowed.close())
            }
        } finally {
            closeCapturedAssets(captured)
            fixture.close()
        }
    }

    // Catches cancellation cleanup that loses its Cancelled primary result or leaves acquired owners live.
    @Test
    fun cancellationAfterActualAcquisitionClosesOwnersAndKeepsCancellationWithCleanupDiagnostics() {
        val fixture = openFixture()
        val captured = mutableListOf<FontRenderAssetHandle>()
        val cancellation = SwitchableCancellationToken()
        try {
            val resolver = object : FontAssetResolverHandle by fixture.resolver {
                override fun reopen(key: FontRenderAssetKey, cancellationToken: CancellationToken): FontOperationResult<FontRenderAssetHandle> =
                    cancelAfterActualDetach(fixture.resolver.reopen(key, cancellationToken), cancellation, captured)
            }

            val opened = fixture.line.openLayoutHandle(resolver, cancellation)

            val cancelled = assertIs<FontOperationResult.Cancelled>(opened)
            assertEquals(true, cancelled.diagnostics.any { it.code == "font.test-close-after-cancellation" })
            assertCapturedOwnersAreClosed(captured, fixture.certificate.glyphId)
        } finally {
            closeCapturedAssets(captured)
            fixture.close()
        }
    }

    // Catches acceptance of either an attached or detached owner whose complete asset key is not certified.
    @Test
    fun attachedAndDetachedCompleteKeyMismatchesAreRejectedInsteadOfServingAnotherGlyphAsset() {
        for (case in listOf("attached", "detached")) {
            val fixture = openFixture()
            val captured = mutableListOf<FontRenderAssetHandle>()
            try {
                val resolver = object : FontAssetResolverHandle by fixture.resolver {
                    override fun reopen(key: FontRenderAssetKey, cancellationToken: CancellationToken): FontOperationResult<FontRenderAssetHandle> = when (case) {
                        "attached" -> reportWrongAttachedKey(fixture.resolver.reopen(key, cancellationToken), captured)
                        "detached" -> reportWrongDetachedKey(fixture.resolver.reopen(key, cancellationToken), captured)
                        else -> error("Unknown complete-key case: $case")
                    }
                }

                val opened = fixture.line.openLayoutHandle(resolver)

                assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(opened).error)
                assertCapturedOwnersAreClosed(captured, fixture.certificate.glyphId)
            } finally {
                closeCapturedAssets(captured)
                fixture.close()
            }
        }
    }

    // Catches unsupported detachment that publishes a partial handle or forgets roots acquired before it.
    @Test
    fun unsupportedDetachClosesAttachedAndPreviouslyAcquiredRoots() {
        val fixture = openMultiFontParagraphFixture()
        val captured = mutableListOf<FontRenderAssetHandle>()
        try {
            val unsupportedKey = fixture.certificates.last().assetKey
            val resolver = object : FontAssetResolverHandle by fixture.resolver {
                override fun reopen(key: FontRenderAssetKey, cancellationToken: CancellationToken): FontOperationResult<FontRenderAssetHandle> = when (
                    val reopened = fixture.resolver.reopen(key, cancellationToken)
                ) {
                    is FontOperationResult.Success -> FontOperationResult.Success(
                        if (key == unsupportedKey) UnsupportedDetachAsset(reopened.value, captured)
                        else CapturedAsset(reopened.value, captured),
                        reopened.diagnostics,
                    )
                    is FontOperationResult.Failure -> reopened
                    is FontOperationResult.Cancelled -> reopened
                }
            }

            val opened = fixture.paragraph.openLayoutHandle(resolver)

            assertIs<FontError.UnsupportedRepresentationProfile>(assertIs<FontOperationResult.Failure>(opened).error)
            assertCapturedOwnersAreClosed(captured, fixture.certificates.first().glyphId)
        } finally {
            closeCapturedAssets(captured)
            fixture.close()
        }
    }

    // Catches an attached-close failure path that retains the detached renderer root after aborting.
    @Test
    fun attachedCloseFailureAbortsAndClosesDetachedRootWithDiagnostics() {
        val fixture = openFixture()
        val captured = mutableListOf<FontRenderAssetHandle>()
        try {
            val resolver = object : FontAssetResolverHandle by fixture.resolver {
                override fun reopen(key: FontRenderAssetKey, cancellationToken: CancellationToken): FontOperationResult<FontRenderAssetHandle> =
                    closeFailingAssets(fixture.resolver.reopen(key, cancellationToken), captured)
            }

            val opened = fixture.line.openLayoutHandle(resolver)

            val failure = assertIs<FontOperationResult.Failure>(opened)
            assertEquals("font.test-attached-close-failure", assertIs<FontError.FontDataFailure>(failure.error).code)
            assertEquals(true, failure.diagnostics.any { it.code == "font.test-attached-close-failure" })
            assertCapturedOwnersAreClosed(captured, fixture.certificate.glyphId)
        } finally {
            closeCapturedAssets(captured)
            fixture.close()
        }
    }

    // Catches closing roots before an admitted retention detaches, or discarding its deferred cleanup diagnostics.
    @Test
    fun admittedRetentionSurvivesCloseWithAuditedGlyphAndDeferredRootCleanupDiagnostics() {
        val fixture = openFixture()
        val captured = mutableListOf<FontRenderAssetHandle>()
        val detachEntered = CountDownLatch(1)
        val releaseDetach = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        var admitted: Future<FontOperationResult<FontRenderAssetHandle>>? = null
        var handle: LayoutHandle<EditableLine>? = null
        val cleanup = CleanupState()
        var primaryFailure: Throwable? = null
        try {
            val resolver = object : FontAssetResolverHandle by fixture.resolver {
                override fun reopen(key: FontRenderAssetKey, cancellationToken: CancellationToken): FontOperationResult<FontRenderAssetHandle> =
                    gateRootDetach(fixture.resolver.reopen(key, cancellationToken), detachEntered, releaseDetach, captured)
            }
            val openedHandle = success(fixture.line.openLayoutHandle(resolver))
            handle = openedHandle
            admitted = workers.submit<FontOperationResult<FontRenderAssetHandle>> {
                openedHandle.retainFontAsset(fixture.certificate)
            }
            check(detachEntered.await(10, TimeUnit.SECONDS)) { "Admitted retention never reached real detach." }
            success(openedHandle.close())
            assertIs<FontError.ResourceClosed>(
                assertIs<FontOperationResult.Failure>(openedHandle.retainFontAsset(fixture.certificate)).error,
            )
            releaseDetach.countDown()

            val result = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
                admitted.get(10, TimeUnit.SECONDS),
            )
            assertBitmap(result.value)
            assertEquals(true, result.diagnostics.any { it.code == "font.test-deferred-root-close" })
        } catch (error: Throwable) {
            primaryFailure = error
            if (error is InterruptedException) cleanup.markInterrupted()
            throw error
        } finally {
            cleanup.clearAndRecordInterrupt()
            releaseDetach.countDown()
            drainRetainedAsset(admitted, cleanup)
            if (terminateWorkers(workers, cleanup)) {
                handle?.let { opened -> runCatching { opened.close() }.onFailure { cleanup.record(it) } }
                closeCapturedAssets(captured, cleanup)
                runCatching { fixture.close() }.onFailure { cleanup.record(it) }
            }
            primaryFailure?.let { primary -> cleanup.failure?.let(primary::addSuppressed) }
            cleanup.restoreInterrupt()
            if (primaryFailure == null) cleanup.failure?.let { throw it }
        }
    }

    private fun openMultiFontParagraphFixture(): MultiFontParagraphFixture {
        val fixture = incrementalRealFontFixture(
            "A\u0633",
            fonts = listOf(
                IncrementalFontFixture("liberation/LiberationSans-Regular.ttf", "Liberation Sans"),
                IncrementalFontFixture("amiri/Amiri-Regular.ttf", "Amiri"),
            ),
        )
        val resolver = success(fixture.catalog.openAssetResolver())
        val backend = success(JvmHarfBuzzShapingBackend.open())
        try {
            val paragraph = assertIs<ParagraphLayoutResult.Success>(
                JvmEditableParagraphFacade.layoutBorrowing(JvmEditableParagraphFacadeRequest(
                    snapshot = fixture.snapshot,
                    sourceRange = fixture.snapshot.range,
                    constraints = incrementalTestConstraints(width = 10_000f, top = 0f, height = 2_400f),
                    baseDirection = BaseDirection.LEFT_TO_RIGHT,
                    language = "en",
                    fontCatalog = fixture.catalog,
                    resolutionPolicy = fixture.policy,
                    fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
                    materialization = EditableLineMaterialization.Renderable(
                        resolver,
                        FontRenderVariantSnapshot.default,
                        FontAccessRequirementsSnapshot.renderable(auditedOutlineProfile()),
                    ),
                ), backend),
            ).layout
            return MultiFontParagraphFixture(
                paragraph = paragraph,
                resolver = resolver,
                backend = backend,
                certificates = paragraph.lines.flatMap { line ->
                    line.positionedGlyphRuns.flatMap { run ->
                        run.glyphs.mapNotNull { glyph -> glyph.materializationCertificate }
                    }
                }.distinctBy { certificate -> certificate.assetKey },
            )
        } catch (error: Throwable) {
            resolver.close()
            backend.close()
            throw error
        }
    }

    private fun captureAssets(
        result: FontOperationResult<FontRenderAssetHandle>,
        captured: MutableList<FontRenderAssetHandle>,
    ): FontOperationResult<FontRenderAssetHandle> = when (result) {
        is FontOperationResult.Success -> FontOperationResult.Success(CapturedAsset(result.value, captured), result.diagnostics)
        is FontOperationResult.Failure -> result
        is FontOperationResult.Cancelled -> result
    }

    private fun cancelAfterActualDetach(
        result: FontOperationResult<FontRenderAssetHandle>,
        cancellation: SwitchableCancellationToken,
        captured: MutableList<FontRenderAssetHandle>,
    ): FontOperationResult<FontRenderAssetHandle> = when (result) {
        is FontOperationResult.Success -> FontOperationResult.Success(
            CancelAfterDetachAsset(result.value, cancellation, captured),
            result.diagnostics,
        )
        is FontOperationResult.Failure -> result
        is FontOperationResult.Cancelled -> result
    }

    private fun reportWrongAttachedKey(
        result: FontOperationResult<FontRenderAssetHandle>,
        captured: MutableList<FontRenderAssetHandle>,
    ): FontOperationResult<FontRenderAssetHandle> = when (result) {
        is FontOperationResult.Success -> FontOperationResult.Success(
            WrongKeyAsset(result.value, wrongKey(result.value.key), captured),
            result.diagnostics,
        )
        is FontOperationResult.Failure -> result
        is FontOperationResult.Cancelled -> result
    }

    private fun reportWrongDetachedKey(
        result: FontOperationResult<FontRenderAssetHandle>,
        captured: MutableList<FontRenderAssetHandle>,
    ): FontOperationResult<FontRenderAssetHandle> = when (result) {
        is FontOperationResult.Success -> FontOperationResult.Success(
            WrongDetachedKeyAsset(result.value, wrongKey(result.value.key), captured),
            result.diagnostics,
        )
        is FontOperationResult.Failure -> result
        is FontOperationResult.Cancelled -> result
    }

    private fun closeFailingAssets(
        result: FontOperationResult<FontRenderAssetHandle>,
        captured: MutableList<FontRenderAssetHandle>,
    ): FontOperationResult<FontRenderAssetHandle> = when (result) {
        is FontOperationResult.Success -> FontOperationResult.Success(
            CloseFailingAsset(result.value, "font.test-attached-close-failure", captured),
            result.diagnostics,
        )
        is FontOperationResult.Failure -> result
        is FontOperationResult.Cancelled -> result
    }

    private fun gateRootDetach(
        result: FontOperationResult<FontRenderAssetHandle>,
        entered: CountDownLatch,
        release: CountDownLatch,
        captured: MutableList<FontRenderAssetHandle>,
    ): FontOperationResult<FontRenderAssetHandle> = when (result) {
        is FontOperationResult.Success -> FontOperationResult.Success(
            GateRootDetachAttachedAsset(result.value, entered, release, captured),
            result.diagnostics,
        )
        is FontOperationResult.Failure -> result
        is FontOperationResult.Cancelled -> result
    }

    private fun wrongKey(key: FontRenderAssetKey): FontRenderAssetKey =
        key.copy(representationProfile = auditedOutlineProfile())

    private fun assertCapturedOwnersAreClosed(
        captured: List<FontRenderAssetHandle>,
        glyphId: GlyphId,
    ) {
        check(captured.isNotEmpty()) { "The public resolver did not return a capturable owner." }
        captured.forEach { asset ->
            assertIs<FontError.ResourceClosed>(
                assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(glyphId))).error,
            )
        }
    }

    private fun closeCapturedAssets(
        captured: List<FontRenderAssetHandle>,
        cleanup: CleanupState? = null,
    ) {
        captured.asReversed().forEach { asset ->
            runCatching { asset.close() }.exceptionOrNull()?.let { cleanup?.record(it) }
        }
    }

    private fun drainRetainedAsset(
        result: Future<FontOperationResult<FontRenderAssetHandle>>?,
        cleanup: CleanupState,
    ) {
        if (result == null) return
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                result.cancel(true)
                cleanup.record(IllegalStateException("Admitted retention did not finish during cleanup."))
                return
            }
            try {
                when (val completed = result.get(remaining, TimeUnit.NANOSECONDS)) {
                    is FontOperationResult.Success -> {
                        runCatching { completed.value.close() }.exceptionOrNull()?.let { cleanup.record(it) }
                    }
                    is FontOperationResult.Failure, is FontOperationResult.Cancelled -> Unit
                }
                return
            } catch (_: java.util.concurrent.CancellationException) {
                return
            } catch (error: java.util.concurrent.ExecutionException) {
                cleanup.record(error.cause ?: error)
                return
            } catch (_: java.util.concurrent.TimeoutException) {
                result.cancel(true)
                cleanup.record(IllegalStateException("Admitted retention did not finish during cleanup."))
                return
            } catch (_: InterruptedException) {
                cleanup.markInterrupted()
            }
        }
    }

    private fun terminateWorkers(
        workers: java.util.concurrent.ExecutorService,
        cleanup: CleanupState,
    ): Boolean {
        workers.shutdown()
        workers.shutdownNow()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!workers.isTerminated) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                cleanup.record(IllegalStateException("Handoff cleanup worker did not terminate."))
                return false
            }
            try {
                val terminated = workers.awaitTermination(remaining, TimeUnit.NANOSECONDS)
                if (terminated || workers.isTerminated) return true
            } catch (_: InterruptedException) {
                cleanup.markInterrupted()
            }
        }
        return true
    }

    private class CleanupState {
        private var interrupted: Boolean = false
        var failure: Throwable? = null
            private set

        fun clearAndRecordInterrupt() {
            interrupted = Thread.interrupted() || interrupted
        }

        fun markInterrupted() {
            interrupted = true
        }

        fun record(error: Throwable) {
            val existing = failure
            if (existing == null) failure = error else existing.addSuppressed(error)
        }

        fun restoreInterrupt() {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun openFixture(): Fixture {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/fonts/skia-ebdt-format1/ebdt_fmt1.ttf"))
            .use { it.readBytes() }
        val catalog = success(Kalligraphie.embedded(bytes, FontSourceProvenance("Skia EBDT format 1")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(BitmapProfile(
            strike = BitmapStrike(16, 16, 1),
            acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
            acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
            limits = BitmapLimits(
                maxStrikes = 3, maxIndexSubtables = 16, maxRecordCount = 16,
                maxIndexTableBytes = 16_384, maxSourceTableBytes = 16_384,
                maxWidth = 16, maxHeight = 16, maxPixels = 256,
                maxCompressedBytes = 64, maxTotalCompressedBytes = 1_024,
                maxDecodedBytes = 256, maxTotalDecodedBytes = 1_024,
            ),
        )))
        val resolver = success(catalog.openAssetResolver())
        val session = success(JvmEditableLineLayoutSession.open())
        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val font = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val snapshot = Kalligraphie.decodeUtf8(
                TextVersion.create(), listOf(TextSlice.Utf8("😀".encodeToByteArray())),
            ).snapshot
            val line = assertIs<EditableLineResult.Success>(session.layout(JvmEditableLineFacadeRequest(
                snapshot = snapshot,
                font = font,
                baseDirection = BaseDirection.LEFT_TO_RIGHT,
                language = "en",
                featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
                features = emptyList(),
                verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
                materialization = EditableLineMaterialization.Renderable(
                    resolver, FontRenderVariantSnapshot.default, requirements,
                ),
            ))).line
            return Fixture(session, resolver, line, font)
        } catch (error: Throwable) {
            session.close()
            resolver.close()
            throw error
        }
    }

    private fun assertAuditedOutline(
        asset: FontRenderAssetHandle,
        glyphId: GlyphId,
        unitsPerEm: Int,
        bounds: DesignBounds,
    ) {
        val outline = assertIs<GlyphRepresentation.Outline>(
            success(asset.resolveGlyph(FontGlyphRequest(glyphId))),
        ).outline
        assertEquals(glyphId.value, outline.glyphId)
        assertEquals(unitsPerEm, outline.unitsPerEm)
        assertEquals(bounds, outline.bounds)
    }

    private fun auditedOutlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 1_024,
        maxPoints = 65_536,
        maxCompositeDepth = 16,
        maxCompositeComponents = 256,
    )

    // Literal oracle independently audited in fonts/skia-ebdt-format1/PROVENANCE.md.
    private fun assertBitmap(renderer: FontRenderAssetHandle) {
        val bitmap = assertIs<GlyphRepresentation.Bitmap>(
            success(renderer.resolveGlyph(FontGlyphRequest(GlyphId(3)))),
        ).bitmap
        assertEquals(GlyphId(3), bitmap.glyphId)
        assertEquals(BitmapStrike(16, 16, 1), bitmap.strike)
        assertEquals(13, bitmap.width)
        assertEquals(13, bitmap.height)
        assertEquals(0, bitmap.originX)
        assertEquals(13, bitmap.originY)
        assertEquals(12, bitmap.metrics.advanceX)
        assertEquals(0, bitmap.metrics.advanceY)
        assertEquals(BitmapPixelFormat.ALPHA_8, bitmap.pixelFormat)
        assertEquals(GlyphColorSpace.SRGB, bitmap.colorSpace)
        val pixels = listOf(
            ".............", "....#####....", "..#########..", ".##########..",
            ".###########.", ".###########.", "############.", ".###########.",
            ".###########.", ".###########.", "..#########..", "...#######...", ".....##......",
        ).flatMap { row -> row.map { if (it == '#') 255.toByte() else 0.toByte() } }.toByteArray()
        assertContentEquals(pixels, bitmap.copyDecodedPixels())
    }

    private data class Fixture(
        val session: JvmEditableLineLayoutSession,
        val resolver: FontAssetResolverHandle,
        val line: EditableLine,
        val font: FontInstance,
    ) {
        val certificate: GlyphMaterializationCertificate
            get() = checkNotNull(line.positionedGlyphRuns.single().glyphs.single().materializationCertificate)

        fun close() {
            session.close()
            resolver.close()
        }
    }

    private data class MultiFontParagraphFixture(
        val paragraph: org.graphiks.kalligraphie.api.ParagraphLayout,
        val resolver: FontAssetResolverHandle,
        val backend: org.graphiks.kalligraphie.api.ShapingBackend,
        val certificates: List<GlyphMaterializationCertificate>,
    ) {
        fun close() {
            resolver.close()
            backend.close()
        }
    }

    private open class CapturedAsset(
        protected val delegate: FontRenderAssetHandle,
        protected val captured: MutableList<FontRenderAssetHandle>,
    ) : FontRenderAssetHandle {
        init {
            captured += this
        }

        override val key: FontRenderAssetKey
            get() = delegate.key

        override val faceId
            get() = delegate.faceId

        override fun detach(): FontOperationResult<FontRenderAssetHandle> = when (val detached = delegate.detach()) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                CapturedAsset(detached.value, captured),
                detached.diagnostics,
            )
            is FontOperationResult.Failure -> detached
            is FontOperationResult.Cancelled -> detached
        }

        override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
            delegate.resolveGlyph(request)

        override fun close(): FontOperationResult<Unit> = delegate.close()
    }

    private class WrongKeyAsset(
        delegate: FontRenderAssetHandle,
        private val reportedKey: FontRenderAssetKey,
        captured: MutableList<FontRenderAssetHandle>,
    ) : CapturedAsset(delegate, captured) {
        override val key: FontRenderAssetKey
            get() = reportedKey
    }

    private class WrongDetachedKeyAsset(
        delegate: FontRenderAssetHandle,
        private val reportedKey: FontRenderAssetKey,
        captured: MutableList<FontRenderAssetHandle>,
    ) : CapturedAsset(delegate, captured) {
        override fun detach(): FontOperationResult<FontRenderAssetHandle> = when (val detached = delegate.detach()) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                WrongKeyAsset(detached.value, reportedKey, captured),
                detached.diagnostics,
            )
            is FontOperationResult.Failure -> detached
            is FontOperationResult.Cancelled -> detached
        }
    }

    private class UnsupportedDetachAsset(
        private val delegate: FontRenderAssetHandle,
        captured: MutableList<FontRenderAssetHandle>,
    ) : FontRenderAssetHandle {
        init {
            captured += this
        }

        override val key: FontRenderAssetKey
            get() = delegate.key

        override val faceId
            get() = delegate.faceId

        override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
            delegate.resolveGlyph(request)

        override fun close(): FontOperationResult<Unit> = delegate.close()
    }

    private open class CloseFailingAsset(
        delegate: FontRenderAssetHandle,
        private val failureCode: String,
        captured: MutableList<FontRenderAssetHandle>,
    ) : CapturedAsset(delegate, captured) {
        override fun detach(): FontOperationResult<FontRenderAssetHandle> = when (val detached = delegate.detach()) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                CloseFailingAsset(detached.value, failureCode, captured),
                detached.diagnostics,
            )
            is FontOperationResult.Failure -> detached
            is FontOperationResult.Cancelled -> detached
        }

        override fun close(): FontOperationResult<Unit> = when (val closed = delegate.close()) {
            is FontOperationResult.Success -> FontOperationResult.Failure(
                FontError.FontDataFailure(
                    failureCode,
                    "Injected public asset close failure.",
                    FontDiagnosticLocation.Source,
                ),
            )
            is FontOperationResult.Failure -> closed
            is FontOperationResult.Cancelled -> closed
        }
    }

    private class CancelAfterDetachAsset(
        delegate: FontRenderAssetHandle,
        private val cancellation: SwitchableCancellationToken,
        captured: MutableList<FontRenderAssetHandle>,
    ) : CloseFailingAsset(delegate, "font.test-close-after-cancellation", captured) {
        override fun detach(): FontOperationResult<FontRenderAssetHandle> = when (val detached = delegate.detach()) {
            is FontOperationResult.Success -> {
                cancellation.cancel()
                FontOperationResult.Success(
                    CloseFailingAsset(detached.value, "font.test-close-after-cancellation", captured),
                    detached.diagnostics,
                )
            }
            is FontOperationResult.Failure -> detached
            is FontOperationResult.Cancelled -> detached
        }
    }

    private class GateRootDetachAttachedAsset(
        delegate: FontRenderAssetHandle,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
        captured: MutableList<FontRenderAssetHandle>,
    ) : CapturedAsset(delegate, captured) {
        override fun detach(): FontOperationResult<FontRenderAssetHandle> = when (val detached = delegate.detach()) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                GateRootDetachAsset(detached.value, entered, release, captured),
                detached.diagnostics,
            )
            is FontOperationResult.Failure -> detached
            is FontOperationResult.Cancelled -> detached
        }
    }

    private class GateRootDetachAsset(
        delegate: FontRenderAssetHandle,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
        captured: MutableList<FontRenderAssetHandle>,
    ) : CloseFailingAsset(delegate, "font.test-deferred-root-close", captured) {
        override fun detach(): FontOperationResult<FontRenderAssetHandle> {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "The test did not release the admitted real detach." }
            return when (val detached = delegate.detach()) {
                is FontOperationResult.Success -> FontOperationResult.Success(
                    CapturedAsset(detached.value, captured),
                    detached.diagnostics,
                )
                is FontOperationResult.Failure -> detached
                is FontOperationResult.Cancelled -> detached
            }
        }
    }

    private class SwitchableCancellationToken : CancellationToken {
        private var cancelled: Boolean = false

        fun cancel() {
            cancelled = true
        }

        override fun isCancellationRequested(): Boolean = cancelled
    }

    private class TestFlowRegion(
        override val bounds: LayoutRect,
        private val intervals: List<InlineInterval>,
    ) : FlowRegion {
        override val identity: FlowRegionIdentity = FlowRegionIdentity.create()

        override fun query(writingMode: WritingMode, lineBand: LineBand): FlowRegionResult =
            FlowRegionResult.AvailableIntervals(intervals)
    }

    private fun flowSuccess(result: FlowCompositionResult<FlowLayout>): FlowLayout =
        assertIs<FlowCompositionResult.Success<FlowLayout>>(result).value

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}
