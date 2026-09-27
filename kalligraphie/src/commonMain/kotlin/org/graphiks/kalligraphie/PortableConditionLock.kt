package org.graphiks.kalligraphie

/**
 * Portable mutual-exclusion lock with one condition variable, replacing the JVM pair the editable
 * line session used to hold through `java.util.concurrent.locks.ReentrantLock.newCondition`.
 *
 * The two halves belong to one type on purpose: [awaitUninterruptibly] must release exactly the
 * lock [withLock] holds, which only holds if both come from the same object. That is what the JVM
 * pair does through its `Condition`, and what Apple's `NSCondition` does natively.
 *
 * **The lock must not be used reentrantly.** The `jvmMain` and `androidMain` actuals are reentrant
 * (that is what the JVM lock they replace was) but check it and fail loudly, while the `iosMain`
 * actual is Apple's `NSCondition`, which a nested `withLock` would deadlock rather than reject.
 * The constraint is therefore stated here and enforced where it can be, rather than left to differ
 * silently between the platforms.
 *
 * [awaitUninterruptibly] is not interruptible on any platform: a caller waiting for another thread
 * to finish is not a cancellation point.
 */
internal expect class PortableConditionLock() {
    /** Runs [block] while holding this lock and returns its result. Must not be called reentrantly. */
    internal fun <T> withLock(block: () -> T): T

    /** Releases the lock and blocks until another thread signals, then reacquires it. */
    internal fun awaitUninterruptibly()

    /** Wakes every thread waiting on this lock's condition. */
    internal fun signalAll()
}

/**
 * A value identifying the calling thread, usable as a map key.
 *
 * The editable line session counts the operations each thread is running, which the JVM did with a
 * `ThreadLocal`. A portable per-thread map needs a portable thread identity instead, and one that
 * compares equal for the same thread and differs for another: the platform's own thread object is
 * exactly that, and it needs no thread-local storage of its own.
 */
internal expect fun currentThreadToken(): Any
