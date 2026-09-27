package org.graphiks.kalligraphie

/**
 * Android actual for [PortableLock], backed by the object monitor.
 *
 * ART implements the same reentrant object monitor as the JVM, so the `synchronized` block
 * preserves the exact semantics of the `@Synchronized` sections it replaces.
 */
internal actual class PortableLock actual constructor() {
    private val monitor: Any = Any()

    internal actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
