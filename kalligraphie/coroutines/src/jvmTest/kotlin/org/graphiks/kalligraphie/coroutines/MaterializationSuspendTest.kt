package org.graphiks.kalligraphie.coroutines

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.graphiks.kalligraphie.JvmEditableLineFacade
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaterializationSuspendTest {
    private fun renderableMaterialization(fixture: LineFixture): EditableLineMaterialization =
        EditableLineMaterialization.Renderable(
            resolver = fixture.resolver,
            variant = FontRenderVariantKey.default,
            outlineProfile = COROUTINES_OUTLINE_PROFILE,
        )

    @Test
    fun suspendRenderableLineMatchesTheSynchronousLine() = runTest {
        val fixture = lineFixture("A")
        try {
            val request = lineRequest(fixture, materialization = renderableMaterialization(fixture))

            val synchronous = assertIs<EditableLineResult.Success>(JvmEditableLineFacade.layout(request))
            val suspended = assertIs<EditableLineResult.Success>(KalligraphieCoroutines.layout(request))

            val synchronousGlyph = synchronous.line.positionedGlyphRuns.single().glyphs.single()
            val suspendedGlyph = suspended.line.positionedGlyphRuns.single().glyphs.single()
            assertEquals(synchronousGlyph.shapedGlyph.glyphId, suspendedGlyph.shapedGlyph.glyphId)
            assertEquals(GlyphMaterializationRoute.OUTLINE, suspendedGlyph.materializationCertificate?.route)
            assertSame(suspendedGlyph.renderAssetKey, suspendedGlyph.materializationCertificate?.assetKey)
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }

    @Test
    fun theBorrowedResolverRemainsOperationalAfterTheSuspendCall() = runTest {
        val fixture = lineFixture("A")
        try {
            val request = lineRequest(fixture, materialization = renderableMaterialization(fixture))

            val suspended = assertIs<EditableLineResult.Success>(KalligraphieCoroutines.layout(request))
            val assetKey = assertNotNull(suspended.line.positionedGlyphRuns.single().glyphs.single().renderAssetKey)

            // The facade must not have closed a resolver it does not own: reopening must still work.
            val reopened = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
                fixture.resolver.reopen(assetKey),
            ).value
            reopened.close()
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }

    /** Tracks resolver closure while forwarding every other operation to the borrowed resolver. */
    private class SpyResolver(
        private val delegate: FontAssetResolverHandle,
    ) : FontAssetResolverHandle by delegate {
        var closeCalls: Int = 0
            private set

        override fun close(): FontOperationResult<Unit> {
            closeCalls += 1
            return delegate.close()
        }
    }

    /**
     * Intercepts the engine's real acquisition path.
     *
     * The embedded engine acquires through `FontInstance.acquireRenderAsset(resolver, ...)` and
     * requires its own concrete resolver type, so a public-interface resolver spy cannot intercept
     * acquisition. This wrapper observes that call and forwards to the concrete [resolver] the
     * engine accepts.
     */
    private class SpyFontInstance(
        private val delegate: FontInstance,
        private val resolver: FontAssetResolverHandle,
        private val onFirstAcquire: () -> Unit,
    ) : FontInstance by delegate {
        var acquireCalls: Int = 0
            private set

        // Kotlin interface delegation forwards default methods (including this 4-arg overload,
        // which is the one the engine actually calls) straight to the delegate, so it must be
        // overridden explicitly to be observed.
        override fun acquireRenderAsset(
            borrowedResolver: FontAssetResolverHandle,
            variant: FontRenderVariantKey,
            requirements: FontAccessRequirementsSnapshot,
            cancellationToken: CancellationToken,
        ): FontOperationResult<FontRenderAssetHandle> {
            acquireCalls += 1
            // Acquire through the concrete resolver the engine accepts, so the resolver is
            // genuinely borrowed before the first acquisition triggers the cancellation.
            val acquired = delegate.acquireRenderAsset(resolver, variant, requirements)
            if (acquireCalls == 1) onFirstAcquire()
            return acquired
        }
    }

    @Test
    fun aCancelledRenderableCallLeavesTheBorrowedResolverUsable() = runTest {
        val fixture = lineFixture("A")
        try {
            // A first successful renderable call produces a real asset key.
            val first = assertIs<EditableLineResult.Success>(
                KalligraphieCoroutines.layout(lineRequest(fixture, materialization = renderableMaterialization(fixture))),
            )
            val assetKey = assertNotNull(first.line.positionedGlyphRuns.single().glyphs.single().renderAssetKey)

            // Cancel the calling Job at the engine's first renderable acquisition, so the engine
            // has genuinely borrowed the resolver before the cancellation is observed.
            val jobRef = AtomicReference<Job?>()
            val spyResolver = SpyResolver(fixture.resolver)
            val spyFont = SpyFontInstance(fixture.font, fixture.resolver) { jobRef.get()?.cancel() }
            val spiedFixture = LineFixture(fixture.snapshot, spyFont, fixture.resolver)
            val materialization = EditableLineMaterialization.Renderable(
                resolver = spyResolver,
                variant = FontRenderVariantKey.default,
                outlineProfile = COROUTINES_OUTLINE_PROFILE,
            )

            val exception = captureCancellation { context ->
                jobRef.set(context[Job]!!)
                KalligraphieCoroutines.layout(lineRequest(spiedFixture, materialization = materialization))
            }

            assertIs<EditableLineResult.Cancelled>(exception.result)
            assertTrue(spyFont.acquireCalls >= 1, "the cancelled call must have acquired through the engine")
            assertEquals(0, spyResolver.closeCalls, "the facade must never close a borrowed resolver")

            // The resolver is still operational after the cancelled call.
            val reopened = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
                fixture.resolver.reopen(assetKey),
            ).value
            reopened.close()
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }
}
