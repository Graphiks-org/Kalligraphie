package org.graphiks.kalligraphie

/**
 * Portable mutual-exclusion lock for the text facades, replacing the JVM monitor the facades used
 * to hold through `@Synchronized` and `java.util.concurrent.locks.ReentrantLock`.
 *
 * The lock is reentrant, matching the object monitor it replaces: a holder may call [withLock]
 * again on the same instance without deadlocking. The `jvmMain` and `androidMain` actuals delegate
 * to that monitor, preserving its exact fairness and reentrancy semantics; the `iosMain` actual
 * delegates to `kotlinx.atomicfu.locks.reentrantLock`, the portable reentrant lock available to
 * Kotlin/Native, which has no object monitor. The `atomicfu` runtime artifact is the only reason
 * that dependency sits on the iOS class path, exactly as it does for `:kalligraphie:shaping`.
 *
 * This is the sibling of the shaping module's own lock rather than a shared type: the facades must
 * not widen a published API to reach an internal primitive.
 */
internal expect class PortableLock() {
    /** Runs [block] while holding this lock and returns its result. */
    internal fun <T> withLock(block: () -> T): T
}
