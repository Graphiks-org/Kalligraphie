package org.graphiks.kalligraphie

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DetachedRenderAssetContractTest {
    @Test
    fun concurrentDetachAndAssetClosePublishOnlyACompleteIndependentHandleOrResourceClosed() {
        repeat(32) {
            val opened = openRenderableFont(fixtureBytes(), 2048f)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val detach = executor.submit<FontOperationResult<FontRenderAssetHandle>> {
                    start.await()
                    opened.asset.detach()
                }
                val close = executor.submit<FontOperationResult<Unit>> {
                    start.await()
                    opened.asset.close()
                }
                start.countDown()

                assertIs<FontOperationResult.Success<Unit>>(close.get(10, TimeUnit.SECONDS))
                when (val result = detach.get(10, TimeUnit.SECONDS)) {
                    is FontOperationResult.Success -> {
                        val detached = result.value
                        try {
                            val representation = success(
                                detached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none),
                            )
                            val outline = assertIs<GlyphRepresentation.Outline>(representation).outline
                            assertEquals(36, outline.glyphId)
                            assertEquals(4, outline.bounds.minX)
                            assertEquals(1362, outline.bounds.maxX)
                        } finally {
                            assertIs<FontOperationResult.Success<Unit>>(detached.close())
                        }
                    }

                    is FontOperationResult.Failure -> assertIs<FontError.ResourceClosed>(result.error)
                    is FontOperationResult.Cancelled -> error("Detachment without cancellation must not return cancellation.")
                }
                assertIs<FontError.ResourceClosed>(
                    assertIs<FontOperationResult.Failure>(
                        opened.asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none),
                    ).error,
                )
            } finally {
                opened.asset.close()
                opened.resolver.close()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun concurrentAcquireAndResolverCloseRemainLinearizableForARealFontAsset() {
        repeat(32) {
            val opened = openRenderableFont(fixtureBytes(), 2048f)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val acquire = executor.submit<FontOperationResult<FontRenderAssetHandle>> {
                    start.await()
                    opened.instance.acquireRenderAsset(
                        opened.resolver,
                        FontRenderVariantKey.default,
                        FontAccessRequirementsSnapshot.renderable(outlineProfile()),
                    )
                }
                val close = executor.submit<FontOperationResult<Unit>> {
                    start.await()
                    opened.resolver.close()
                }
                start.countDown()

                assertIs<FontOperationResult.Success<Unit>>(close.get())
                when (val result = acquire.get()) {
                    is FontOperationResult.Success -> {
                        try {
                            assertIs<GlyphRepresentation.Outline>(success(result.value.resolveGlyph(FontGlyphRequest(GlyphId(36)))))
                        } finally {
                            result.value.close()
                        }
                    }

                    is FontOperationResult.Failure -> assertIs<FontError.ResourceClosed>(result.error)
                    is FontOperationResult.Cancelled -> error("Acquiring a real asset must not be cancelled by resolver closure.")
                }
                assertIs<FontError.ResourceClosed>(
                    assertIs<FontOperationResult.Failure>(
                        opened.instance.acquireRenderAsset(
                            opened.resolver,
                            FontRenderVariantKey.default,
                            FontAccessRequirementsSnapshot.renderable(outlineProfile()),
                        ),
                    ).error,
                )
            } finally {
                executor.shutdownNow()
                opened.asset.close()
                opened.resolver.close()
            }
        }
    }

    @Test
    fun concurrentResolveAndAssetCloseRemainLinearizableForARealFontAsset() {
        repeat(32) {
            val opened = openRenderableFont(fixtureBytes(), 2048f)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val resolve = executor.submit<FontOperationResult<GlyphRepresentation>> {
                    start.await()
                    opened.asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none)
                }
                val close = executor.submit<FontOperationResult<Unit>> {
                    start.await()
                    opened.asset.close()
                }
                start.countDown()

                assertIs<FontOperationResult.Success<Unit>>(close.get())
                when (val result = resolve.get()) {
                    is FontOperationResult.Success -> assertIs<GlyphRepresentation.Outline>(result.value)
                    is FontOperationResult.Failure -> assertIs<FontError.ResourceClosed>(result.error)
                    is FontOperationResult.Cancelled -> error("Resolving without a cancellation token must not be cancelled by asset closure.")
                }
                assertIs<FontError.ResourceClosed>(
                    assertIs<FontOperationResult.Failure>(
                        opened.asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none),
                    ).error,
                )
            } finally {
                executor.shutdownNow()
                opened.asset.close()
                opened.resolver.close()
            }
        }
    }

    @Test
    fun attachedAssetRetainsItsResourceAfterResolverClose() {
        val opened = openRenderableFont(fixtureBytes(), 2048f)

        try {
            assertIs<FontOperationResult.Success<Unit>>(opened.resolver.close())

            val representation = success(
                opened.asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none),
            )
            assertIs<GlyphRepresentation.Outline>(representation)
        } finally {
            opened.asset.close()
        }
    }

    @Test
    fun detachedAssetResolvesAfterResolverAndAttachedHandleClose() {
        val catalog = catalogFor(fixtureBytes())
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.renderable(outlineProfile())))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
        val attached = success(
            instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, FontAccessRequirementsSnapshot.renderable(outlineProfile())),
        )
        val detached = success(attached.detach())

        assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        assertIs<FontOperationResult.Success<Unit>>(attached.close())
        assertIs<FontOperationResult.Success<Unit>>(attached.close())

        val attachedResult = attached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none)
        assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(attachedResult).error)

        val representation = success(detached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none))
        val outline = assertIs<GlyphRepresentation.Outline>(representation).outline
        assertEquals(36, outline.glyphId)
        assertEquals(2048, outline.unitsPerEm)
        assertEquals(4, outline.bounds.minX)
        assertEquals(1362, outline.bounds.maxX)
    }

    @Test
    fun detachedAssetDoesNotDependOnCatalogOrAttachedOwner() {
        val detached = detachedAssetAfterOwnersClose()

        val representation = success(detached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none))
        val outline = assertIs<GlyphRepresentation.Outline>(representation).outline

        assertEquals(36, outline.glyphId)
        assertEquals(4, outline.bounds.minX)
        assertEquals(1362, outline.bounds.maxX)
        assertIs<FontOperationResult.Success<Unit>>(detached.close())
    }

    @Test
    fun closingDetachedAssetDoesNotCloseAttachedAsset() {
        val opened = openRenderableFont(fixtureBytes(), 2048f)
        val detached = success(opened.asset.detach())

        assertIs<FontOperationResult.Success<Unit>>(detached.close())
        val representation = success(opened.asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none))

        assertIs<GlyphRepresentation.Outline>(representation)
        assertIs<FontOperationResult.Success<Unit>>(opened.asset.close())
        assertIs<FontOperationResult.Success<Unit>>(opened.resolver.close())
    }

    @Test
    fun closedResolverRejectsNewAttachedAssets() {
        val opened = openRenderableFont(fixtureBytes(), 2048f)
        assertIs<FontOperationResult.Success<Unit>>(opened.resolver.close())

        val result = opened.instance.acquireRenderAsset(
            opened.resolver,
            FontRenderVariantKey.default,
            FontAccessRequirementsSnapshot.renderable(outlineProfile()),
        )

        assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun interleavedSourcesMaterializeTheirAuditedGlyphOutcomesWithAnEnabledCache() {
        val liberation = FontSource(
            sourceBytes = fixtureBytes(),
            provenance = FontSourceProvenance(declaredName = "Liberation Sans Regular"),
        )
        val amiri = FontSource(
            sourceBytes = amiriFixtureBytes(),
            provenance = FontSourceProvenance(declaredName = "Amiri Regular"),
        )
        val catalog = success(
            Kalligraphie.embedded(
                sources = listOf(liberation, amiri),
                cachePolicy = FontMaterializationCachePolicy(maxEvictableBytesPerFace = 1_000_000),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(sourceIsolationOutlineProfile())
        val liberationFace = success(catalog.resolveFace(catalog.faces[0].id, requirements))
        val amiriFace = success(catalog.resolveFace(catalog.faces[1].id, requirements))
        val liberationInstance = success(liberationFace.instantiate(FontInstanceDescriptor(LayoutUnit(1_000f))))
        val amiriInstance = success(amiriFace.instantiate(FontInstanceDescriptor(LayoutUnit(1_000f))))
        val resolver = success(catalog.openAssetResolver())

        try {
            val liberationGlyph = success(liberationInstance.resolveGlyph('A'.code)).glyphId
            val amiriGlyph = success(amiriInstance.resolveGlyph('A'.code)).glyphId
            assertEquals(GlyphId(36), liberationGlyph)
            assertEquals(GlyphId(6227), amiriGlyph)

            val liberationAsset = success(
                liberationInstance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )
            val amiriAsset = success(amiriInstance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                assertAuditedLiberationA(liberationAsset, liberationGlyph)
                assertAuditedAmiriA(amiriAsset, amiriGlyph)
                assertAuditedLiberationA(liberationAsset, liberationGlyph)
            } finally {
                liberationAsset.close()
                amiriAsset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsNonPositiveInstanceSizeAsTypedFailure() {
        val result = faceFor(fixtureBytes()).instantiate(FontInstanceDescriptor(LayoutUnit(0f)))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.invalid-instance-descriptor", failure.error.code)
    }

    @Test
    fun cancelledOutlineResolutionPublishesNoPartialRepresentation() {
        val asset = openRenderableFont(fixtureBytes(), 2048f).asset

        val result = asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.cancelled)

        assertIs<FontOperationResult.Cancelled>(result)
    }

    @Test
    fun coldOutlinePreparationObservesCancellationAndCanRetry() {
        val opened = openRenderableFont(fixtureBytes(), 2048f)
        val asset = opened.asset
        var checks = 0
        val cancellationToken = CancellationToken { checks++ >= 10 }

        try {
            val cancelled = asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), cancellationToken)

            assertIs<FontOperationResult.Cancelled>(cancelled)
            assertTrue(checks > 10)

            val representation = success(asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none))
            assertIs<GlyphRepresentation.Outline>(representation)
        } finally {
            asset.close()
            opened.resolver.close()
        }
    }

    @Test
    fun restrictiveOutlineProfileReturnsTypedLimitFailureThroughPublicRoute() {
        val catalog = catalogFor(fixtureBytes())
        val resolver = success(catalog.openAssetResolver())
        val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile(maxContours = 1))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
        val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))

        val result = asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none)

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
        assertEquals("font.resource-limit-exceeded", failure.error.code)
        assertEquals("font.resource-limit-exceeded", failure.diagnostics.single().code)
    }

    private fun openRenderableFont(bytes: ByteArray, size: Float): DetachedFontResources {
        val catalog = catalogFor(bytes)
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.renderable(outlineProfile())))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(size))))
        val asset = success(
            instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, FontAccessRequirementsSnapshot.renderable(outlineProfile())),
        )
        return DetachedFontResources(resolver, instance, asset)
    }

    private fun detachedAssetAfterOwnersClose(): FontRenderAssetHandle {
        val catalog = catalogFor(fixtureBytes())
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.renderable(outlineProfile())))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
        val attached = success(
            instance.acquireRenderAsset(
                resolver,
                FontRenderVariantKey.default,
                FontAccessRequirementsSnapshot.renderable(outlineProfile()),
            ),
        )
        val detached = success(attached.detach())

        resolver.close()
        attached.close()
        return detached
    }

    private fun faceFor(bytes: ByteArray): FontFace =
        catalogFor(bytes).let { catalog ->
            success(catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()))
        }

    private fun catalogFor(bytes: ByteArray): FontCatalogSnapshot =
        success(Kalligraphie.embedded(bytes, FontSourceProvenance(declaredName = "Liberation Sans Regular")))

    /**
     * These independent fixture facts make a source-cache collision observable: Liberation's
     * U+0041 is outline glyph 36 in a 2048-unit em with bounds (4, 0, 1362, 1409), whereas
     * Amiri's U+0041 is outline glyph 6227 in a 1000-unit em with bounds (-14, -3, 619, 647).
     * Consequently a source confused with the other cannot satisfy either result, including the
     * warm Liberation resolution after Amiri has materialized.
     */
    private fun assertAuditedLiberationA(asset: FontRenderAssetHandle, glyph: GlyphId) {
        val outline = assertIs<GlyphRepresentation.Outline>(
            success(asset.resolveGlyph(FontGlyphRequest(glyph), CancellationToken.none)),
        ).outline

        assertEquals(36, outline.glyphId)
        assertEquals(2048, outline.unitsPerEm)
        assertEquals(4, outline.bounds.minX)
        assertEquals(1362, outline.bounds.maxX)
        assertEquals(1409, outline.bounds.maxY)
    }

    private fun assertAuditedAmiriA(asset: FontRenderAssetHandle, glyph: GlyphId) {
        val outline = assertIs<GlyphRepresentation.Outline>(
            success(asset.resolveGlyph(FontGlyphRequest(glyph), CancellationToken.none)),
        ).outline

        assertEquals(6227, outline.glyphId)
        assertEquals(1000, outline.unitsPerEm)
        assertEquals(-14, outline.bounds.minX)
        assertEquals(-3, outline.bounds.minY)
        assertEquals(619, outline.bounds.maxX)
        assertEquals(647, outline.bounds.maxY)
    }

    private fun sourceIsolationOutlineProfile(): OutlineProfile =
        outlineProfile(
            maxContours = 1_024,
            maxPoints = 65_536,
            maxCompositeDepth = 16,
        )

    private fun outlineProfile(
        maxBytes: Int = 1_000_000,
        maxContours: Int = 256,
        maxPoints: Int = 16_384,
        maxCompositeDepth: Int = 8,
        maxCompositeComponents: Int = 256,
    ): OutlineProfile =
        OutlineProfile(
            maxBytes = maxBytes,
            maxContours = maxContours,
            maxPoints = maxPoints,
            maxCompositeDepth = maxCompositeDepth,
            maxCompositeComponents = maxCompositeComponents,
        )

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { it.readBytes() }

    private fun amiriFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/amiri/Amiri-Regular.ttf")) {
            "Amiri Regular fixture is missing"
        }.use { it.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

private data class DetachedFontResources(
    val resolver: FontAssetResolverHandle,
    val instance: FontInstance,
    val asset: FontRenderAssetHandle,
)
