@file:OptIn(
    org.graphiks.kalligraphie.api.KalligraphieInternalApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.GdefLigatureCaretFact
import org.graphiks.kalligraphie.api.GdefLigatureCaretState
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingBackendIdentity
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy
import org.graphiks.kalligraphie.api.ShapingFeaturePolicyApplication
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceLimit
import org.graphiks.kalligraphie.api.ShapingSemanticIdentity
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.toDiagnostic
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * Opens the pinned HarfBuzz reference backend for the current platform.
 *
 * The backend loads only the native HarfBuzz binding available to this module. On the JVM it ships
 * for Linux and macOS on x64 and arm64 JVMs and for Windows on x64 JVMs; unsupported platform or
 * architecture combinations return a typed failure. Targets with no native binding return
 * `font.shaping-native-platform-unsupported` through [open].
 * No JNI type, native handle, or platform dependency escapes through [ShapingBackend].
 * JVM launchers must enable native access with `--enable-native-access=ALL-UNNAMED`.
 */
public object HarfBuzzShapingBackend {
    /**
     * Explicit baseline feature policy implemented by the pinned HarfBuzz reference backend.
     *
     * The policy delegates baseline selection to HarfBuzz 14.3.0 and therefore does not claim
     * to enumerate choices that HarfBuzz derives from font tables or segment properties.
     * Callers must place this policy in every [ShapingRequest] sent to this backend; individual
     * [OpenTypeFeature] values remain explicit request overrides.
     */
    public val pinnedFeaturePolicy: ShapingFeaturePolicy = PINNED_FEATURE_POLICY

    /**
     * Opens the pinned library with the [PreparedFontCachePolicy.default] admission bounds.
     * The owner must close the returned backend.
     */
    public fun open(): FontOperationResult<ShapingBackend> = open(PreparedFontCachePolicy.default)

    /**
     * Opens the pinned library with independent prepared-font admission bounds.
     * [preparedFontCachePolicy] counts active and idle resources; a rejected preparation is a
     * typed resource-limit failure. The owner must close the returned backend.
     */
    public fun open(
        preparedFontCachePolicy: PreparedFontCachePolicy,
    ): FontOperationResult<ShapingBackend> =
        when (val loaded = HarfBuzzBindings.open()) {
            is FontOperationResult.Success -> FontOperationResult.Success(HarfBuzzJvmBackend(loaded.value, preparedFontCachePolicy))
            is FontOperationResult.Failure -> loaded
            is FontOperationResult.Cancelled -> loaded
        }

    /**
     * Captures an internal accounting reader without exposing the native backend type.
     * Other backend implementations report an immutable empty usage.
     */
    @org.graphiks.kalligraphie.api.KalligraphieInternalApi
    public fun preparedFontCacheUsageInspector(backend: ShapingBackend): () -> PreparedFontCacheUsage =
        if (backend is HarfBuzzJvmBackend) {
            { backend.preparedFontCacheUsage }
        } else {
            { PreparedFontCacheUsage(0, 0, 0, 0, 0, 0) }
        }
}

private class HarfBuzzJvmBackend(
    private val bindings: HarfBuzzBindings,
    policy: PreparedFontCachePolicy,
) : ShapingBackend {
    override val identity: ShapingBackendIdentity = bindings.identity
    private val preparedFonts = PreparedFontCache(policy)
    val preparedFontCacheUsage: PreparedFontCacheUsage get() = preparedFonts.usage

    /**
     * Dedicated monitor for [close], distinct from the prepared-font cache lock.
     *
     * The `closed` flag is read lock-free on the [shape] fast path; [close] publishes it under this
     * lock. It is never routed through the cache monitor, so a suspended provider can never deadlock
     * the fast path.
     */
    private val closeLock = PortableLock()
    private val closed = AtomicBoolean(false)

    override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> {
        if (closed.load()) {
            return FontOperationResult.Failure(
                FontError.ResourceClosed("The pinned HarfBuzz backend is closed."),
            )
        }
        if (request.cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        val scalarCount = request.snapshot.scalarCount(request.contextRange)
        if (scalarCount > request.resourceProfile.maxScalars) {
            return shapingResourceLimitFailure(ShapingResourceLimit.SCALARS, scalarCount)
        }
        if (request.featurePolicy != identity.semantic.featurePolicy) {
            return shapingFailure(
                code = "font.shaping-feature-policy-unsupported",
                message = "The requested OpenType feature policy is not implemented by this pinned HarfBuzz backend.",
            )
        }
        if (request.features.any { feature -> feature.tag in NON_DETERMINISTIC_FEATURES }) {
            return shapingFailure(
                code = "font.shaping-feature-not-deterministic",
                message = "The requested OpenType feature is non-deterministic and cannot be shaped reproducibly.",
            )
        }

        val layoutSize = request.font.key.layoutSize.value
        if (!layoutSize.isFinite() || layoutSize <= 0f) {
            return shapingFailure(
                code = "font.shaping-scale-invalid",
                message = "The font instance layout size must be finite and positive.",
            )
        }

        return try {
            val prepared = preparedFonts.acquire(
                request.font.key,
                source = { copyFontBytes(request) },
                create = { bytes -> bindings.prepare(bytes, request.font.key.face.faceIndex, layoutSize) },
            )
            try {
                observeCancellation(request)
                FontOperationResult.Success(bindings.shape(request, prepared.value))
            } finally {
                prepared.close()
            }
        } catch (failure: PreparedFontFailure) {
            failure.result
        } catch (cancelled: PreparedFontCancelled) {
            cancelled.result
        } catch (_: ShapingCancelled) {
            FontOperationResult.Cancelled()
        } catch (limitExceeded: ShapingLimitExceeded) {
            shapingResourceLimitFailure(limitExceeded.limit, limitExceeded.observed)
        } catch (error: Throwable) {
            shapingFailure(
                code = "font.shaping-native-failure",
                message = "The pinned HarfBuzz backend failed while shaping: ${error.message ?: error::class.simpleName}.",
            )
        }
    }

    override fun close(): FontOperationResult<Unit> = closeLock.withLock {
        if (closed.load()) return@withLock FontOperationResult.Success(Unit)
        closed.store(true)
        aggregateFailures(preparedFonts.close())?.let { error ->
            FontOperationResult.Failure(
                FontError.FontDataFailure(
                    code = "font.shaping-native-release-failed",
                    message = "The pinned HarfBuzz backend could not release its native resources: ${error.message ?: error::class.simpleName}.",
                    location = FontDiagnosticLocation.Source,
                ),
            )
        } ?: FontOperationResult.Success(Unit)
    }

    private fun copyFontBytes(request: ShapingRequest): ByteArray {
        observeCancellation(request)
        val fontData = when (val result = request.font.copyOpenTypeData()) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> throw PreparedFontFailure(result)
            is FontOperationResult.Cancelled -> throw PreparedFontCancelled(result)
        }
        observeCancellation(request)
        if (fontData.face != request.font.key.face) {
            throw PreparedFontFailure(
                shapingFailure(
                    code = "font.shaping-font-data-mismatch",
                    message = "The OpenType bytes do not identify the requested font instance face.",
                ),
            )
        }
        val bytes = fontData.copyBytes()
        observeCancellation(request)
        return bytes
    }
}

private data class PreparedFontFootprint(
    val sourceBytes: Long,
    val estimatedNativeBytes: Long,
    val estimatorVersion: String,
) {
    val totalBytes: Long get() = saturatedAdd(sourceBytes, estimatedNativeBytes)
}

/** See PreparedFontCachePolicy.nativeEstimatorVersion for scope and limitations. */
private fun preparedFontFootprint(sourceBytes: Long): PreparedFontFootprint = PreparedFontFootprint(
    sourceBytes,
    saturatedAdd(256L * 1024, if (sourceBytes > Long.MAX_VALUE / 4) Long.MAX_VALUE else sourceBytes * 4),
    PreparedFontCachePolicy.nativeEstimatorVersion,
)

private fun saturatedAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

/**
 * Source copying and its public callbacks run outside the lock. Admission, native preparation
 * and release share one lock; after copying, acquisition rechecks closure and an existing font
 * before reserving any native allocation. Keeping destruction under the lock prevents another
 * allocation from racing an idle eviction.
 *
 * [entries] preserves insertion order explicitly, so oldest-idle-first eviction is identical on
 * every target instead of relying on the JVM `LinkedHashMap` iteration order.
 */
private class PreparedFontCache(private val policy: PreparedFontCachePolicy) {
    private val lock = PortableLock()
    private val entries = InsertionOrderedMap<FontInstanceKey, Entry>()
    private var closed = false
    private var releaseFailed = false

    val usage: PreparedFontCacheUsage get() = lock.withLock {
        val idle = entries.values.filter { it.activeLeases == 0 }
        val active = entries.values.filter { it.activeLeases > 0 }
        PreparedFontCacheUsage(
            idle.size, active.sumOf { it.activeLeases },
            idle.sumOf { it.footprint.sourceBytes }, active.sumOf { it.footprint.sourceBytes },
            idle.sumOf { it.footprint.estimatedNativeBytes }, active.sumOf { it.footprint.estimatedNativeBytes },
        )
    }

    fun acquire(
        key: FontInstanceKey,
        source: () -> ByteArray,
        create: (ByteArray) -> PreparedHarfBuzzFont,
    ): Lease {
        val existingLease = lock.withLock {
            if (closed || releaseFailed) throw PreparedFontFailure(FontOperationResult.Failure(
                FontError.ResourceClosed("The pinned HarfBuzz backend is closed."),
            ))
            entries[key]?.let { entry ->
                entry.activeLeases += 1
                Lease(checkNotNull(entry.value)) { releaseLease(key, entry) }
            }
        }
        if (existingLease != null) return existingLease

        // Font providers and cancellation tokens may reenter this backend or close it.
        val bytes = source()
        val footprint = preparedFontFootprint(bytes.size.toLong())
        return lock.withLock {
            if (closed || releaseFailed) throw PreparedFontFailure(FontOperationResult.Failure(
                FontError.ResourceClosed("The pinned HarfBuzz backend is closed."),
            ))
            // A concurrent or nested acquisition may have prepared this key while copying.
            val prepared = entries[key]
            if (prepared != null) {
                prepared.activeLeases += 1
                return@withLock Lease(checkNotNull(prepared.value)) { releaseLease(key, prepared) }
            }
            if (!fits(footprint, emptyList())) reject()
            while (!fits(footprint, entries.values)) {
                val idle = entries.firstIdle { it.activeLeases == 0 } ?: reject()
                // Release before subtracting accounting or admitting new native memory.
                try {
                    checkNotNull(idle.value.value).close()
                } catch (error: Throwable) {
                    // A failed native destruction must neither be retried nor permit more allocation.
                    releaseFailed = true
                    throw error
                } finally {
                    entries.remove(idle.key)
                }
            }
            val reservation = Entry(footprint, activeLeases = 1)
            entries[key] = reservation
            try {
                reservation.value = create(bytes)
                Lease(checkNotNull(reservation.value)) { releaseLease(key, reservation) }
            } catch (error: Throwable) {
                entries.remove(key)
                throw error
            }
        }
    }

    private fun fits(footprint: PreparedFontFootprint, retained: Collection<Entry>): Boolean {
        if (retained.size >= policy.maxEntries) return false
        val source = retained.sumOf { it.footprint.sourceBytes }
        val native = retained.sumOf { it.footprint.estimatedNativeBytes }
        val total = source + native // Existing admission guarantees this sum fits in Long.
        return footprint.sourceBytes <= policy.maxSourceBytes - source &&
            footprint.estimatedNativeBytes <= policy.maxEstimatedNativeBytes - native &&
            footprint.totalBytes <= policy.maxTotalBytes - total
    }

    private fun reject(): Nothing = throw PreparedFontFailure(FontOperationResult.Failure(
        FontError.ResourceLimitExceeded(
            "The prepared HarfBuzz font cannot fit the session cache policy, including active leases.",
            FontDiagnosticLocation.Source,
        ),
    ))

    fun close(): List<Throwable> = lock.withLock {
        if (closed) return@withLock emptyList()
        closed = true
        val failures = mutableListOf<Throwable>()
        for (key in entries.keysInInsertionOrder()) {
            val entry = entries[key] ?: continue
            if (entry.activeLeases == 0) {
                try {
                    checkNotNull(entry.value).close()
                } catch (error: Throwable) {
                    failures += error
                } finally {
                    entries.remove(key)
                }
            }
        }
        failures
    }

    private fun releaseLease(key: FontInstanceKey, entry: Entry): Unit = lock.withLock {
        check(entry.activeLeases > 0)
        entry.activeLeases -= 1
        if (closed && entry.activeLeases == 0) {
            try {
                checkNotNull(entry.value).close()
            } finally {
                entries.remove(key)
            }
        }
    }

    private class Entry(
        val footprint: PreparedFontFootprint,
        var activeLeases: Int,
        var value: PreparedHarfBuzzFont? = null,
    )

    class Lease(val value: PreparedHarfBuzzFont, private val release: () -> Unit) : AutoCloseable {
        private val lock = PortableLock()
        private var closed = false

        override fun close() = lock.withLock {
            if (closed) return@withLock
            closed = true
            release()
        }
    }
}

/**
 * Insertion-ordered map used for the prepared-font cache.
 *
 * `commonMain` cannot use the JVM `LinkedHashMap`, and common `mutableMapOf` iteration order is
 * unspecified. Order is therefore tracked separately from the lookup map: keys iterate in
 * insertion order, preserving oldest-idle-first eviction on every target.
 */
private class InsertionOrderedMap<K, V> {
    private val order = mutableListOf<K>()
    private val valuesByKey = mutableMapOf<K, V>()

    val values: Collection<V> get() = valuesByKey.values

    operator fun get(key: K): V? = valuesByKey[key]

    operator fun set(key: K, value: V) {
        if (valuesByKey.put(key, value) == null) order += key
    }

    fun remove(key: K): V? = valuesByKey.remove(key)?.also { order.remove(key) }

    /** Snapshot of the keys in insertion order; safe to remove entries while iterating. */
    fun keysInInsertionOrder(): List<K> = order.toList()

    /** First entry, in insertion order, whose value satisfies [isIdle]. */
    fun firstIdle(isIdle: (V) -> Boolean): IdleEntry<K, V>? {
        for (key in order) {
            val value = valuesByKey.getValue(key)
            if (isIdle(value)) return IdleEntry(key, value)
        }
        return null
    }

    class IdleEntry<K, V>(val key: K, val value: V)
}

private class PreparedFontFailure(
    val result: FontOperationResult.Failure,
) : RuntimeException()

private class PreparedFontCancelled(
    val result: FontOperationResult.Cancelled,
) : RuntimeException()

internal fun aggregateFailures(failures: List<Throwable>): Throwable? {
    val primary = failures.firstOrNull() ?: return null
    failures.drop(1).forEach { failure ->
        if (failure !== primary) primary.addSuppressed(failure)
    }
    return primary
}

internal data class ContextScalar(
    val sourceRange: TextRange,
    val itemToken: ShaperClusterToken?,
)

internal data class NativeGlyphRecord(
    val glyphId: Int,
    val tokenValue: Int,
    val safetyMask: Int,
    val xAdvance: Int,
    val yAdvance: Int,
    val xOffset: Int,
    val yOffset: Int,
)

/**
 * Direct result of the native `hb_ot_layout_get_ligature_carets` ABI call.
 *
 * [totalCount] is the function return value and [copiedCount] is the in/out count after the
 * call. They are deliberately retained separately so the portable interpretation can reject a
 * truncated or otherwise inconsistent native response. Tests may construct this value to audit
 * the ABI boundary without using a second live HarfBuzz invocation as an oracle.
 */
internal data class NativeLigatureCaretResponse(
    val totalCount: Int,
    val copiedCount: Int,
    /** Whether the final shaped horizontal advance still equals the unshaped glyph advance. */
    val finalAdvanceMatchesUnshapedAdvance: Boolean = true,
    val positions: List<LayoutUnit>,
)

/** Interprets an audited native GDEF response against a cluster's editable grapheme boundaries. */
internal object LigatureCaretFactInterpreter {
    /**
     * Returns a fact whose boundaries are logical-source ordered, independently of glyph output
     * order. HarfBuzz exposes GDEF carets in increasing glyph-coordinate order; that order is
     * reversed for right-to-left source text while each signed position remains relative to the
     * same glyph origin and baseline.
     */
    fun fromNativeResponse(
        glyphIndex: Int,
        direction: ShapingDirection,
        cluster: ShaperCluster,
        response: NativeLigatureCaretResponse,
    ): GdefLigatureCaretFact {
        val logicalSourceBoundaries = cluster.internalAdmissibleGraphemeBoundaries()
        require(logicalSourceBoundaries.isNotEmpty()) {
            "GDEF caret interpretation requires an editable internal grapheme boundary."
        }
        val expectedCount = logicalSourceBoundaries.size
        return when {
            response.totalCount == 0 && response.copiedCount == 0 ->
                GdefLigatureCaretFact(
                    glyphIndex = glyphIndex,
                    state = GdefLigatureCaretState.ABSENT,
                    logicalSourceBoundaries = logicalSourceBoundaries,
                )

            response.totalCount != expectedCount ||
                response.copiedCount != expectedCount ||
                response.positions.size != expectedCount ||
                !response.finalAdvanceMatchesUnshapedAdvance ->
                GdefLigatureCaretFact(
                    glyphIndex = glyphIndex,
                    state = GdefLigatureCaretState.INCONSISTENT,
                    logicalSourceBoundaries = logicalSourceBoundaries,
                )

            else ->
                GdefLigatureCaretFact(
                    glyphIndex = glyphIndex,
                    state = GdefLigatureCaretState.AVAILABLE,
                    logicalSourceBoundaries = logicalSourceBoundaries,
                    positions = if (direction == ShapingDirection.RIGHT_TO_LEFT) {
                        response.positions.asReversed()
                    } else {
                        response.positions
                    },
                )
        }
    }
}

/**
 * Groups the shaped glyph records into the source clusters of the shaped item.
 *
 * HarfBuzz reports a cluster token per glyph; this function maps each observed token back to
 * the contiguous run of scalar ranges it covers and records the editable grapheme boundaries
 * that fall strictly inside that range.
 */
internal fun buildClusters(
    request: ShapingRequest,
    scalarRanges: List<TextRange>,
    glyphs: List<NativeGlyphRecord>,
): List<ShaperCluster> {
    if (scalarRanges.isEmpty()) return emptyList()
    val observedTokens = buildSet {
        glyphs.forEachIndexed { glyphIndex, glyph ->
            observeCancellation(request, glyphIndex)
            add(glyph.tokenValue)
        }
    }.sorted()
    if (observedTokens.isEmpty()) return emptyList()
    val requestBoundaries = buildList {
        request.graphemeClusters.forEachIndexed { graphemeIndex, grapheme ->
            observeCancellation(request, graphemeIndex)
            if (graphemeIndex == 0) add(grapheme.start)
            add(grapheme.endExclusive)
        }
    }
    var firstBoundaryIndex = 0
    var cancellationCountdown = request.resourceProfile.cancellationCheckInterval
    fun observeBoundaryCancellation() {
        cancellationCountdown -= 1
        if (cancellationCountdown == 0) {
            observeCancellation(request)
            cancellationCountdown = request.resourceProfile.cancellationCheckInterval
        }
    }
    fun compareBoundary(boundary: TextIndex, limit: TextIndex): Int {
        observeBoundaryCancellation()
        return boundary.compareTo(limit)
    }
    return observedTokens.mapIndexed { index, tokenValue ->
        observeCancellation(request, index)
        val endTokenExclusive = observedTokens.getOrNull(index + 1) ?: scalarRanges.size
        val sourceScalars = scalarRanges.subList(tokenValue, endTokenExclusive)
        val sourceRange = TextRange(sourceScalars.first().start, sourceScalars.last().endExclusive)
        while (
            firstBoundaryIndex + 1 < requestBoundaries.size &&
            compareBoundary(requestBoundaries[firstBoundaryIndex + 1], sourceRange.start) <= 0
        ) {
            firstBoundaryIndex += 1
        }
        val admissibleBoundaries = buildList {
            var boundaryIndex = firstBoundaryIndex
            while (
                boundaryIndex < requestBoundaries.size &&
                compareBoundary(requestBoundaries[boundaryIndex], sourceRange.endExclusive) <= 0
            ) {
                val boundary = requestBoundaries[boundaryIndex]
                if (compareBoundary(boundary, sourceRange.start) >= 0) add(boundary)
                boundaryIndex += 1
            }
            firstBoundaryIndex = (boundaryIndex - 1).coerceAtLeast(firstBoundaryIndex)
        }
        ShaperCluster(
            token = ShaperClusterToken(tokenValue),
            sourceRange = sourceRange,
            scalarRanges = sourceScalars,
            admissibleGraphemeBoundaries = admissibleBoundaries,
        )
    }
}

internal fun ShaperCluster.internalAdmissibleGraphemeBoundaries() = admissibleGraphemeBoundaries.filter { boundary ->
    boundary.compareTo(sourceRange.start) > 0 && boundary.compareTo(sourceRange.endExclusive) < 0
}

internal fun advancesMatch(shapedAdvance: Int, unshapedAdvance: Int): Boolean =
    absoluteMagnitude(shapedAdvance) == absoluteMagnitude(unshapedAdvance)

private fun absoluteMagnitude(value: Int): Long =
    value.toLong().let { if (it < 0L) -it else it }

internal class DesignToLayoutScale private constructor(
    private val layoutSize: Double,
    val unitsPerEm: Int,
) {
    fun convert(designUnit: Int): LayoutUnit {
        val converted = designUnit.toDouble() * layoutSize / unitsPerEm.toDouble()
        val narrowed = converted.toFloat()
        require(converted.isFinite() && narrowed.isFinite()) {
            "A HarfBuzz design-unit value cannot be represented as a finite layout unit."
        }
        return LayoutUnit(narrowed)
    }

    companion object {
        fun create(layoutSize: Float, unitsPerEm: Int): DesignToLayoutScale {
            require(layoutSize.isFinite() && layoutSize > 0f) {
                "The layout size must be finite and positive."
            }
            require(unitsPerEm > 0) { "HarfBuzz returned a non-positive face units-per-em value." }
            return DesignToLayoutScale(layoutSize.toDouble(), unitsPerEm)
        }
    }
}

/** Converts HarfBuzz's upward-positive vertical values to portable downward-positive layout values. */
internal fun Int.toPhysicalVerticalCoordinate(
    direction: ShapingDirection,
    scale: DesignToLayoutScale,
): LayoutUnit = when (direction) {
    ShapingDirection.TOP_TO_BOTTOM -> scale.convert(-this)
    ShapingDirection.LEFT_TO_RIGHT,
    ShapingDirection.RIGHT_TO_LEFT,
    -> scale.convert(this)
}

internal fun shapingFailure(code: String, message: String): FontOperationResult.Failure {
    val error = FontError.FontDataFailure(code, message, FontDiagnosticLocation.Source)
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}

private fun shapingResourceLimitFailure(
    limit: ShapingResourceLimit,
    observed: Int,
): FontOperationResult.Failure {
    val error = FontError.ShapingResourceLimitExceeded(limit, observed)
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}

internal fun observeCancellation(request: ShapingRequest) {
    if (request.cancellationToken.isCancellationRequested()) throw ShapingCancelled
}

internal fun observeCancellation(request: ShapingRequest, itemIndex: Int) {
    if (itemIndex % request.resourceProfile.cancellationCheckInterval == 0) observeCancellation(request)
}

internal data object ShapingCancelled : RuntimeException()

internal class ShapingLimitExceeded(
    val limit: ShapingResourceLimit,
    val observed: Int,
) : RuntimeException()

private const val HARFBUZZ_VERSION: String = "14.3.0"
private val PINNED_FEATURE_POLICY: ShapingFeaturePolicy = ShapingFeaturePolicy(
    policyId = "harfbuzz-defaults",
    version = HARFBUZZ_VERSION,
    application = ShapingFeaturePolicyApplication.PINNED_BACKEND_DEFAULTS,
)
private const val CONFIGURATION_FINGERPRINT: String =
    "harfbuzz-14.3.0;shaper=ot;ot-font-funcs;scale=face-upem;layout-conversion=layout-size-over-upem;explicit-direction-script-language-bot-eot;" +
        "cluster-level=monotone-characters;flags=produce-unsafe-to-concat;feature-policy=harfbuzz-defaults@14.3.0;feature-overrides=explicit"
internal val HARFBUZZ_SEMANTIC_IDENTITY: ShapingSemanticIdentity = ShapingSemanticIdentity(
    backendId = "harfbuzz-jvm",
    engineId = "harfbuzz",
    engineVersion = HARFBUZZ_VERSION,
    shaperId = "ot",
    featurePolicy = PINNED_FEATURE_POLICY,
    configurationFingerprint = CONFIGURATION_FINGERPRINT,
)
private val NON_DETERMINISTIC_FEATURES: Set<String> = setOf("rand")
internal const val HB_GLYPH_FLAG_UNSAFE_TO_BREAK: Int = 0x00000001
internal const val HB_GLYPH_FLAG_UNSAFE_TO_CONCAT: Int = 0x00000002
