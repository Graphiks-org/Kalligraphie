package org.graphiks.kalligraphie.shaping

/**
 * Portable mutual-exclusion lock for the shaping engine.
 *
 * Decision recorded for the HarfBuzz adapter hoist (multiplatform backends
 * design, §5.2 "Kalligraphie boundary"). Moving the adapter from `jvmMain` to
 * `commonMain` requires replacing the JVM-only concurrency primitives used by
 * `JvmHarfBuzzShapingBackend` (`@Volatile`, `@Synchronized` and
 * `synchronized(lock)`) with portable equivalents. The chosen layer is:
 *
 * 1. **Mutual exclusion** — this `expect`/`actual` lock. Both the `jvmMain` and
 *    `androidMain` actuals delegate to the object monitor
 *    (`synchronized(monitor) { block() }`), preserving the exact reentrant
 *    monitor semantics of the `@Synchronized`/`synchronized` blocks it replaces
 *    without adding a dependency.
 * 2. **Atomicity for the `closed` flag** — `kotlin.concurrent.atomics`
 *    (`AtomicBoolean`), which replaces the `@Volatile var closed` field. No
 *    `kotlinx-atomicfu` dependency is introduced.
 *
 * The lock is reentrant, matching the JVM/ART object monitor it wraps.
 */
public expect class PortableLock() {
    /**
     * Runs [block] while holding this lock and returns its result.
     *
     * The lock is reentrant: a lock holder may call [withLock] again on the same
     * instance without deadlocking.
     */
    public fun <T> withLock(block: () -> T): T
}
