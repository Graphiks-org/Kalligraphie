package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.FontOperationResult

/** Independent admission parent; each admitted child owns one reference to minimal resources. */
internal class CoreTextResourceOwner private constructor(private val shared: Shared) : AutoCloseable {
    constructor(cleanup: () -> FontOperationResult<Unit>) : this(Shared(cleanup))
    private var closed = false
    fun acquireChild(): CoreTextResourceOwner? = synchronized(this) {
        if (closed) null else {
            val child = CoreTextResourceOwner(shared)
            shared.retain()
            child
        }
    }
    fun isOpen(): Boolean = synchronized(this) { !closed }
    override fun close() { closeResult() }
    /** Reports only drainage performed by this release; never waits for other children. */
    fun closeResult(): FontOperationResult<Unit> {
        val release = synchronized(this) { if (closed) false else { closed = true; true } }
        return if (release) shared.release() else FontOperationResult.Success(Unit)
    }
    private class Shared(private val cleanup: () -> FontOperationResult<Unit>) {
        private var references = 1L
        fun retain() = synchronized(this) { references = Math.addExact(references, 1) }
        fun release(): FontOperationResult<Unit> {
            val final = synchronized(this) { --references == 0L }
            return if (final) cleanup() else FontOperationResult.Success(Unit)
        }
    }
}
