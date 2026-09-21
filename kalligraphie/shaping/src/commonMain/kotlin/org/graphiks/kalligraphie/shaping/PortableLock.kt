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
 * 1. **Mutual exclusion** — this `expect`/`actual` lock. The `jvmMain` and
 *    `androidMain` actuals delegate to the object monitor
 *    (`synchronized(monitor) { block() }`), preserving the exact reentrant
 *    monitor semantics of the `@Synchronized`/`synchronized` blocks it replaces
 *    without adding a dependency. The `iosMain` actual delegates to
 *    `kotlinx.atomicfu.locks.reentrantLock`, the portable reentrant lock
 *    available to Kotlin/Native (which has no object monitor); that is the only
 *    reason the `atomicfu` runtime artifact sits on the iOS classpath.
 * 2. **Atomicity for the `closed` flag** — `kotlin.concurrent.atomics`
 *    (`AtomicBoolean`), which replaces the `@Volatile var closed` field.
 *
 * The lock is reentrant, matching the JVM/ART object monitor it wraps.
 */
internal expect class PortableLock() {
    /**
     * Runs [block] while holding this lock and returns its result.
     *
     * The lock is reentrant: a lock holder may call [withLock] again on the same
     * instance without deadlocking.
     */
    internal fun <T> withLock(block: () -> T): T
}
