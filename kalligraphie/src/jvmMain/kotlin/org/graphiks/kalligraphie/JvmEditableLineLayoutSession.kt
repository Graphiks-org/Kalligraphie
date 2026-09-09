package org.graphiks.kalligraphie

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend

/**
 * Reusable JVM editable-line layout session backed by one owned HarfBuzz instance.
 *
 * A successful [open] retains prepared native fonts in the backend's bounded per-instance cache
 * across [layout] calls. Requests admitted while the session is open may run concurrently: each
 * call keeps its Unicode analysis, HarfBuzz buffer, and final materialization local. The session
 * borrows caller-owned font instances and materialization resolvers only for the duration of the
 * corresponding synchronous call.
 *
 * [close] is idempotent and linearizable. It first prevents new admissions, then waits for every
 * admitted layout to finish before releasing the backend exactly once. A layout admitted before
 * that transition returns its complete normal outcome; a later layout returns
 * [FontError.ResourceClosed]. The owner must close every successfully opened session.
 */
public class JvmEditableLineLayoutSession private constructor(
    private val backend: ShapingBackend,
) {
    private val lifecycle = ReentrantLock(true)
    private val lifecycleChanged = lifecycle.newCondition()
    private var state: LifecycleState = LifecycleState.OPEN
    private var activeOperations: Int = 0
    private var publishedCloseResult: FontOperationResult<Unit>? = null

    /**
     * Produces one complete editable line while borrowing the session-owned backend.
     *
     * Admission, input validation, unsupported-control checks, Unicode analysis, shaping, and
     * result materialization form one lifecycle operation. Concurrent admitted calls do not
     * serialize one another. Once closing has begun, the request is rejected atomically with an
     * [EditableLineError.ShapingFailure] carrying [FontError.ResourceClosed].
     */
    public fun layout(request: JvmEditableLineFacadeRequest): EditableLineResult {
        if (!acquireOperation()) return closedResult()
        return try {
            JvmEditableLineFacade.layoutBorrowing(request, backend)
        } finally {
            releaseOperation()
        }
    }

    /**
     * Closes the owned backend after all admitted layouts have completed.
     *
     * The first caller transitions the session away from open admission and performs the single
     * backend close. Concurrent and later callers wait for, then receive, the same published
     * typed close result. Caller-owned fonts, catalogs, and materialization resolvers are never
     * closed by this operation.
     */
    public fun close(): FontOperationResult<Unit> {
        lifecycle.withLock {
            when (state) {
                LifecycleState.OPEN -> {
                    state = LifecycleState.CLOSING
                    while (activeOperations > 0) lifecycleChanged.awaitUninterruptibly()
                }

                LifecycleState.CLOSING -> {
                    while (state != LifecycleState.CLOSED) lifecycleChanged.awaitUninterruptibly()
                    return checkNotNull(publishedCloseResult)
                }

                LifecycleState.CLOSED -> return checkNotNull(publishedCloseResult)
            }
        }
        val closeResult = try {
            backend.close()
        } catch (error: Throwable) {
            FontOperationResult.Failure(
                FontError.FontDataFailure(
                    code = "font.shaping-native-release-failed",
                    message = "The pinned HarfBuzz backend could not release its native resources: " +
                        "${error.message ?: error::class.simpleName}.",
                    location = FontDiagnosticLocation.Source,
                ),
            )
        }
        lifecycle.withLock {
            publishedCloseResult = closeResult
            state = LifecycleState.CLOSED
            lifecycleChanged.signalAll()
        }
        return closeResult
    }

    private fun acquireOperation(): Boolean = lifecycle.withLock {
        if (state != LifecycleState.OPEN) return false
        activeOperations += 1
        true
    }

    private fun releaseOperation() {
        lifecycle.withLock {
            check(activeOperations > 0) { "An editable-line session operation was released more than once." }
            activeOperations -= 1
            if (activeOperations == 0) lifecycleChanged.signalAll()
        }
    }

    private fun closedResult(): EditableLineResult.Failure = EditableLineResult.Failure(
        error = EditableLineError.ShapingFailure(
            FontError.ResourceClosed("The JVM editable-line layout session is closed."),
        ),
        diagnostics = emptyList(),
    )

    /** Opens reusable editable-line sessions with the pinned JVM HarfBuzz backend. */
    public companion object {
        /**
         * Opens one reusable JVM editable-line layout session.
         *
         * Native library validation, unsupported-platform failures, and cancellation are returned
         * through [FontOperationResult]. On success, ownership of the opened backend transfers to
         * the returned session and the caller must invoke [close].
         */
        public fun open(): FontOperationResult<JvmEditableLineLayoutSession> =
            when (val opened = JvmHarfBuzzShapingBackend.open()) {
                is FontOperationResult.Success -> FontOperationResult.Success(
                    JvmEditableLineLayoutSession(opened.value),
                    opened.diagnostics,
                )

                is FontOperationResult.Failure -> opened
                is FontOperationResult.Cancelled -> opened
            }
    }

    private enum class LifecycleState {
        OPEN,
        CLOSING,
        CLOSED,
    }
}
