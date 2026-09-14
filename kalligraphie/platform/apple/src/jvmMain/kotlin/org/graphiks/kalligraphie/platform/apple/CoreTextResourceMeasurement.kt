package org.graphiks.kalligraphie.platform.apple

import java.util.concurrent.atomic.AtomicLongArray

/** Process-scoped isolated-run counters; no context-to-recorder ownership edge or pointers. */
internal object CoreTextResourceMeasurement {
    @Volatile var enabled = false
    @Volatile private var saturated = false
    private val created = AtomicLongArray(4)
    private val released = AtomicLongArray(4)
    private val uncertain = AtomicLongArray(4)
    private val dataBytes = AtomicLongArray(2)
    fun created(kind: Int, bytes: Long = 0) {
        if (!enabled) return
        if (!increment(created, kind)) return
        if (kind == 0) dataBytes.addAndGet(0, bytes)
    }
    fun released(kind: Int, bytes: Long = 0) {
        if (!enabled) return
        if (!increment(released, kind)) return
        if (kind == 0) dataBytes.addAndGet(1, bytes)
    }
    fun uncertain(kind: Int) { if (enabled) increment(uncertain, kind) }
    private fun increment(counters: AtomicLongArray, kind: Int): Boolean {
        var old = counters.get(kind)
        while (true) {
            if (old == 1_000_000_000L) { saturated = true; return false }
            if (counters.compareAndSet(kind, old, old + 1)) return true
            old = counters.get(kind)
        }
    }
    fun report(): String = "native owned references [CFData,CGDataProvider,CGFont,CTFont]: saturated=$saturated created=${(0..3).map { created.get(it) }} confirmedApiReleaseUnits=${(0..3).map { released.get(it) }} uncertain=${(0..3).map { uncertain.get(it) }} remainingExplicitOwnershipUnits=${(0..3).map { created.get(it) - released.get(it) }} knownOwnedCFDataSourceCopyBytes=${dataBytes.get(0) - dataBytes.get(1)}"
}
