package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.*
import kotlin.test.assertIs

internal fun <T> success(result: FontOperationResult<T>): T = assertIs<FontOperationResult.Success<T>>(result, result.toString()).value
internal fun portableFixture(path: String): FontCatalogSnapshot {
    val bytes = checkNotNull(CoreTextFontAccessTest::class.java.getResourceAsStream(path)).use { it.readBytes() }
    return success(Kalligraphie.embedded(bytes, FontSourceProvenance("audited native access fixture")))
}
internal val generousPolicy = CoreTextFontAccessPolicy(2_000_000L, 8_000_000L, 8_000_000L)

internal val portableOutlineProfile = OutlineProfile(maxBytes = 1_000_000, maxContours = 1_024, maxPoints = 65_536, maxCompositeDepth = 16, maxCompositeComponents = 256)
internal fun liberationCatalog(policy: CoreTextFontAccessPolicy = generousPolicy): CoreTextFontCatalogSnapshot =
    success(CoreTextFontCatalog.capture(portableFixture("/fonts/liberation/LiberationSans-Regular.ttf"), policy))
internal fun nativeFont(catalog: CoreTextFontCatalogSnapshot): FontInstance {
    val face = success(catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.renderable(listOf(catalog.nativeProfile))))
    return success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
}
internal fun nativeAsset(catalog: CoreTextFontCatalogSnapshot, resolver: FontAssetResolverHandle, token: CancellationToken = CancellationToken.none): NativeFontRenderAssetHandle =
    assertIs(success(nativeFont(catalog).acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, FontAccessRequirementsSnapshot.renderable(listOf(catalog.nativeProfile)), token)))
internal fun assertAuditedAdvance(asset: NativeFontRenderAssetHandle, advance: Double = 1366.0) {
    val lease = assertIs<CoreTextFontLease>(success(asset.acquireNativeFontLease()))
    try { kotlin.test.assertEquals(advance, CoreTextConsumerProbe.horizontalAdvance(success(lease.fontRef()), 36), 0.000001) }
    finally { success(lease.close()) }
}

/** Fixed audited hmtx/checksum edits; retains Liberation's complete unchanged name table. */
internal fun liberationWithDistinctAdvance(): ByteArray {
    val bytes = checkNotNull(CoreTextFontAccessTest::class.java.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")).use { it.readBytes() }
    fun write32(offset: Int, value: Long) { repeat(4) { index -> bytes[offset + index] = (value ushr (24 - index * 8)).toByte() } }
    bytes[0x2a8] = 0x07
    bytes[0x2a9] = 0xd0.toByte()
    write32(0xd0, 0x7f4ed31dL)
    write32(0x144, 0xb82f9d90L)
    return bytes
}

/** Coordinates token checkpoints; checkpoint counts are never an oracle or an assertion. */
internal class BlockingCoreTextToken : CancellationToken {
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean()
    private val checkpoints = java.util.concurrent.LinkedBlockingQueue<Unit>()
    private val resumes = java.util.concurrent.LinkedBlockingQueue<Boolean>()
    override fun isCancellationRequested(): Boolean {
        if (cancelled.get()) return true
        checkpoints.offer(Unit)
        val stop = try { resumes.poll(10, java.util.concurrent.TimeUnit.SECONDS) ?: true }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); true }
        if (stop) cancelled.set(true)
        return cancelled.get()
    }
    fun awaitCheckpoint(): Boolean = checkpoints.poll(2, java.util.concurrent.TimeUnit.SECONDS) != null
    fun advance() { resumes.offer(false) }
    fun cancel() { cancelled.set(true); resumes.offer(true) }
}
