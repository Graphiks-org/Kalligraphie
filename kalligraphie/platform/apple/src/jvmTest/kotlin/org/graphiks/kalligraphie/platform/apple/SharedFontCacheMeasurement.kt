@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.*
import org.graphiks.kalligraphie.font.core.FontCacheCoordinator
import org.graphiks.kalligraphie.font.core.FontCacheMeasurement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Optional executable public-consumer probe, deliberately outside functional test discovery. */
internal object SharedFontCacheMeasurement {
    private val output = StringBuilder()
    private val unlimited = FontCacheBudget(2_000_000, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
    private val generous = FontMaterializationCachePolicy(unlimited, unlimited)
    private val small = "gdef-kern/GdefKerningFixture.ttf"
    private val large = "dejavu/DejaVuSans.ttf"
    private val outline = portableOutlineProfile
    private val bitmap = BitmapProfile(BitmapStrike(16, 16), listOf(BitmapPixelFormat.ALPHA_8), listOf(GlyphColorSpace.SRGB),
        BitmapLimits(3, 16, 16, 16_384, 16_384, 16, 16, 256, 64, 1_024, 256, 1_024))
    private val paint = PaintGraphProfile(listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
        listOf(GlyphPaintCompositionMode.SOURCE_OVER), PaintGraphLimits(maxNodes = 3, maxReferences = 2, maxDepth = 2,
            maxSourceBytes = 16_384, maxPaths = 2, maxPalettes = 9, maxPaletteEntries = 2,
            maxColorRecords = 16, maxDecodedPaletteBytes = 72, maxBaseGlyphRecords = 288, maxLayerRecords = 576), outline)

    @JvmStatic fun main(args: Array<String>) {
        check(System.getenv("KALLIGRAPHIE_SHARED_FONT_CACHE_MEASUREMENT") == "true")
        val destination = Path.of(checkNotNull(System.getenv("KALLIGRAPHIE_SHARED_FONT_CACHE_OUTPUT"))).toAbsolutePath()
        check(!destination.startsWith(Path.of(git("rev-parse", "--show-toplevel")))) { "Write measurement reports outside the repository." }
        output.appendLine("Shared retention public-consumer measurement")
        output.appendLine("commit=${git("rev-parse", "HEAD")} workingTree=${if (git("status", "--porcelain").isEmpty()) "clean" else "dirty"} instrumentation=bounded-opt-in")
        output.appendLine("os=${System.getProperty("os.name")} ${System.getProperty("os.version")} arch=${System.getProperty("os.arch")} jvm=${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}")
        listOf(small, large, "liberation/LiberationSans-Regular.ttf", "bungee-color/BungeeColor-Regular.ttf", "skia-ebdt-format1/ebdt_fmt1.ttf").forEach { path ->
            val bytes = bytes(path)
            output.appendLine("corpus=$path bytes=${bytes.size} sha256=${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}")
        }
        CoreTextResourceMeasurement.enabled = true
        try {
            quota()
            concurrentReservation()
            for (level in 0..2) for (dimension in 0..3) pressure(level, dimension)
            mixed()
            output.appendLine("FINAL ${CoreTextResourceMeasurement.report()}")
            output.appendLine("Native known bytes count CFData source-copy bytes under explicit ownership only. Four units count successful owned creation references and confirmed API release units, not malloc calls or proven physical OS deallocation. Private OS caching remains unknown and excluded. Uncertain releases remain unknown. Caller-exclusive derivation is valid only at drained boundaries, with no pending/retiring/residual cache charges.")
            Files.writeString(destination, output)
            println("Measurement written to $destination")
        } finally { CoreTextResourceMeasurement.enabled = false }
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "Cannot record repository revision/state." }
        return result
    }

    private fun bytes(path: String) = checkNotNull(javaClass.getResourceAsStream("/fonts/$path")).use { it.readBytes() }
    private fun domain(budget: FontCacheBudget): Pair<FontCacheScope, FontCacheMeasurement> {
        val scope = Kalligraphie.fontCacheScope(budget)
        val recorder = FontCacheMeasurement()
        (scope.backend as FontCacheCoordinator).measure(recorder)
        return scope to recorder
    }
    private fun capture(path: String, scope: FontCacheScope, policy: FontMaterializationCachePolicy = generous): CoreTextFontCatalogSnapshot =
        success(CoreTextFontCatalog.capture(success(Kalligraphie.embedded(bytes(path), FontSourceProvenance("audited retention corpus"), generous, scope)), generousPolicy, cachePolicy = policy, cacheScope = scope))
    private fun asset(catalog: CoreTextFontCatalogSnapshot, resolver: FontAssetResolverHandle, size: Float, token: CancellationToken = CancellationToken.none): PlatformFontRenderAssetHandle {
        val req = FontAccessRequirementsSnapshot.renderable(listOf(catalog.platformProfile))
        val face = success(catalog.resolveFace(catalog.faces.single().id, req))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(size))))
        return assertIs(success(instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, req, token)))
    }
    private fun metric(asset: PlatformFontRenderAssetHandle, glyph: Int, expected: Double) {
        val lease = assertIs<CoreTextFontLease>(success(asset.acquirePlatformFontLease()))
        try { assertEquals(expected, CoreTextConsumerProbe.horizontalAdvance(success(lease.fontRef()), glyph), 0.000001) }
        finally { success(lease.close()) }
    }
    private fun timed(name: String, body: () -> Unit) {
        val start = System.nanoTime()
        body()
        output.appendLine("$name latencyNs=${System.nanoTime() - start}")
    }
    private fun quota() {
        output.appendLine("\nPROFILE native-byte many-small-victims, 48 actual contexts, N=1772, candidate N=757076, scope nativeBytes=757076")
        val (scope, recorder) = domain(FontCacheBudget(2_000_000, Long.MAX_VALUE, 757076, Long.MAX_VALUE))
        val tiny = capture(small, scope)
        val big = capture(large, scope)
        output.appendLine("route=${tiny.platformProfile} largeRoute=${big.platformProfile}")
        val one = success(tiny.openAssetResolver()); val two = success(big.openAssetResolver())
        var held: PlatformFontRenderAssetHandle? = null
        var runtimeRoute: PlatformFontRouteIdentity? = null
        try {
            timed("cold-seed") { repeat(48) { index ->
                val size = (index + 1).toFloat()
                val value = asset(tiny, one, size)
                if (index == 0) runtimeRoute = value.key.platformContext?.routeIdentity
                try { metric(value, 3, 900.0 * size / 1000.0) } finally { success(value.close()) }
            } }
            output.appendLine("runtimeRoute=$runtimeRoute")
            output.appendLine("seed evidence\n${recorder.report()}")
            output.appendLine(CoreTextResourceMeasurement.report())
            timed("hot-indexed") {
                val value = asset(tiny, one, 48f)
                try { metric(value, 3, 43.2) } finally { success(value.close()) }
            }
            output.appendLine("hot evidence\n${recorder.report()}")
            output.appendLine(CoreTextResourceMeasurement.report())
            timed("over-32-uncached-fallback") { held = asset(big, two, 2048f); metric(checkNotNull(held), 16, 739.0) }
            output.appendLine("fallback evidence\n${recorder.report()}\n${CoreTextResourceMeasurement.report()}")
            timed("scope-close") { success(scope.close()) }
            recorder.verifyDrainedBounds()
            metric(checkNotNull(held), 16, 739.0)
            output.appendLine("drained cache; surviving caller-exclusive resource N=757076, units=4\n${recorder.report()}\n${CoreTextResourceMeasurement.report()}")
            timed("caller-close") { success(checkNotNull(held).close()); held = null }
            output.appendLine(CoreTextResourceMeasurement.report())
        } finally { held?.close(); one.close(); two.close(); scope.close() }
    }

    private fun concurrentReservation() {
        output.appendLine("\nPROFILE concurrent reserved/retiring native charge, public cancellation checkpoints, scope nativeBytes=3544")
        val (scope, recorder) = domain(FontCacheBudget(2_000_000, Long.MAX_VALUE, 3544, 8))
        val catalog = capture(small, scope)
        val resolver = success(catalog.openAssetResolver())
        val seed = asset(catalog, resolver, 1f)
        metric(seed, 3, 0.9); success(seed.close())
        val before = recorder.eventCount
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val once = AtomicBoolean()
        val token = object : CancellationToken {
            override fun isCancellationRequested(): Boolean {
                // The existing public checkpoint after native cache reservation runs outside
                // coordination. Hold that admitted real operation while another acquires.
                if (recorder.eventCount > before && once.compareAndSet(false, true)) {
                    paused.countDown()
                    if (!resume.await(10, TimeUnit.SECONDS)) return true
                }
                return false
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        val pending = worker.submit<PlatformFontRenderAssetHandle> { asset(catalog, resolver, 2f, token) }
        var second: PlatformFontRenderAssetHandle? = null
        try {
            check(paused.await(10, TimeUnit.SECONDS)) { "Public native reservation checkpoint was not reached." }
            timed("concurrent-admission-with-pending-reference") {
                val third = asset(catalog, resolver, 3f)
                try { metric(third, 3, 2.7) } finally { success(third.close()) }
            }
            resume.countDown()
            second = pending.get(10, TimeUnit.SECONDS)
            metric(checkNotNull(second), 3, 1.8)
            timed("concurrent-profile-close") { success(scope.close()) }
            metric(checkNotNull(second), 3, 1.8)
            recorder.verifyDrainedBounds()
            output.appendLine(recorder.report())
            output.appendLine(CoreTextResourceMeasurement.report())
        } finally {
            resume.countDown()
            if (second == null) runCatching { second = pending.get(10, TimeUnit.SECONDS) }
            second?.close(); worker.shutdownNow(); resolver.close(); scope.close()
        }
    }

    private fun pressure(level: Int, dimension: Int) {
        val cap = when (dimension) {
            0 -> FontCacheBudget(8000, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
            1 -> FontCacheBudget(2_000_000, 169, Long.MAX_VALUE, Long.MAX_VALUE)
            2 -> FontCacheBudget(2_000_000, Long.MAX_VALUE, 1772, Long.MAX_VALUE)
            else -> FontCacheBudget(2_000_000, Long.MAX_VALUE, Long.MAX_VALUE, 4)
        }
        val local = FontMaterializationCachePolicy(if (level == 2) cap else unlimited, if (level == 1) cap else unlimited)
        val (scope, recorder) = domain(if (level == 0) cap else unlimited)
        output.appendLine("\nPROFILE pressure level=$level dimension=$dimension")
        if (dimension == 1) {
            val catalog = success(Kalligraphie.embedded(bytes("skia-ebdt-format1/ebdt_fmt1.ttf"), FontSourceProvenance("audited retention corpus"), local, scope))
            val resolver = success(catalog.openAssetResolver())
            try { repeat(4) { index ->
                val value = portableAsset(catalog, resolver, bitmap, (index + 16).toFloat())
                try { consume(value, 3) } finally { success(value.close()) }
            } } finally { resolver.close(); scope.close() }
        } else {
            val catalog = capture(small, scope, local)
            val resolver = success(catalog.openAssetResolver())
            try { repeat(4) { index ->
                val value = asset(catalog, resolver, (index + 1).toFloat())
                try { metric(value, 3, 900.0 * (index + 1) / 1000.0) } finally { value.close() }
            } } finally { resolver.close(); scope.close() }
        }
        output.appendLine(recorder.report())
        recorder.verifyDrainedBounds()
    }

    private fun portable(path: String, profile: GlyphRepresentationProfile, scope: FontCacheScope, policy: FontMaterializationCachePolicy = generous): Pair<FontAssetResolverHandle, FontRenderAssetHandle> {
        val catalog = success(Kalligraphie.embedded(bytes(path), FontSourceProvenance("audited retention corpus"), policy, scope))
        val resolver = success(catalog.openAssetResolver())
        return resolver to portableAsset(catalog, resolver, profile, 16f)
    }
    private fun portableAsset(catalog: FontCatalogSnapshot, resolver: FontAssetResolverHandle, profile: GlyphRepresentationProfile, size: Float): FontRenderAssetHandle {
        val req = FontAccessRequirementsSnapshot.renderable(listOf(profile))
        val face = success(catalog.resolveFace(catalog.faces.single().id, req))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(size))))
        val variant = if (profile is PaintGraphProfile) FontRenderVariantSnapshot(cpalPaletteIndex = 0) else FontRenderVariantSnapshot.default
        return success(instance.acquireRenderAsset(resolver, variant, req))
    }
    private fun consume(asset: FontRenderAssetHandle, glyph: Int) {
        when (val value = success(asset.resolveGlyph(FontGlyphRequest(GlyphId(glyph))))) {
            is GlyphRepresentation.Outline -> { assertEquals(2048, value.outline.unitsPerEm); assertEquals(2, value.outline.contours.size); assertEquals(1362, value.outline.bounds.maxX) }
            is GlyphRepresentation.Paint -> {
                val solids = value.paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>()
                assertEquals(listOf(292, 293), solids.map { it.outline.glyphId })
                assertEquals(listOf(GlyphColor(201, 9, 0), GlyphColor(255, 149, 128)), solids.map { it.color })
            }
            is GlyphRepresentation.Bitmap -> {
                assertEquals(13, value.bitmap.width); assertEquals(13, value.bitmap.height); assertEquals(12, value.bitmap.metrics.advanceX)
                assertContentEquals(listOf(".............", "....#####....", "..#########..", ".##########..", ".###########.", ".###########.", "############.", ".###########.", ".###########.", ".###########.", "..#########..", "...#######...", ".....##......").flatMap { row -> row.map { if (it == '#') 255.toByte() else 0 } }.toByteArray(), value.bitmap.copyDecodedPixels())
            }
            else -> error("Unexpected audited representation")
        }
    }
    private fun mixed() {
        for (budget in listOf(FontCacheBudget(0, 0, 0, 0), FontCacheBudget(1, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE), unlimited, FontCacheBudget(12000, 169, 1772, 4))) {
            output.appendLine("\nPROFILE concurrent mixed budget=$budget")
            val (scope, recorder) = domain(budget)
            val outlines = portable("liberation/LiberationSans-Regular.ttf", outline, scope)
            val paints = portable("bungee-color/BungeeColor-Regular.ttf", paint, scope)
            val bitmaps = portable("skia-ebdt-format1/ebdt_fmt1.ttf", bitmap, scope)
            val native = capture(small, scope); val resolver = success(native.openAssetResolver())
            val pool = Executors.newFixedThreadPool(4)
            try {
                timed("concurrent-acquire-evict") {
                    val jobs = listOf(
                        pool.submit { repeat(8) { consume(outlines.second, 36) } },
                        pool.submit { repeat(8) { consume(paints.second, 43) } },
                        pool.submit { repeat(8) { consume(bitmaps.second, 3) } },
                        pool.submit { repeat(8) { index -> val value = asset(native, resolver, (index + 1).toFloat()); try { metric(value, 3, 900.0 * (index + 1) / 1000.0) } finally { value.close() } } },
                    )
                    jobs.forEach { it.get() }
                }
                timed("scope-close-surviving-portable-owners") { success(scope.close()) }
                consume(outlines.second, 36); consume(paints.second, 43); consume(bitmaps.second, 3)
            } finally {
                pool.shutdownNow(); resolver.close()
                listOf(outlines, paints, bitmaps).forEach { it.second.close(); it.first.close() }
                scope.close()
            }
            output.appendLine(recorder.report())
            recorder.verifyDrainedBounds()
        }
    }
}
