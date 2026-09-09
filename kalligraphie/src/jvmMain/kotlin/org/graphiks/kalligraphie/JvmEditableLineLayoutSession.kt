@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.EditorOperationContext
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
 * corresponding synchronous call. This concurrency contract guarantees complete independent
 * results, not a throughput ratio; parallel performance is evaluated separately by opt-in editor
 * journey measurements.
 *
 * [close] is idempotent and linearizable. It first prevents new admissions, then waits for every
 * admitted layout to finish before releasing the backend exactly once. A layout admitted before
 * that transition returns its complete normal outcome; a later layout returns
 * [FontError.ResourceClosed]. A reentrant close from the same thread's admitted layout is rejected
 * before changing an otherwise open session. The owner must close every successfully opened
 * session.
 */
public class JvmEditableLineLayoutSession private constructor(
    private val backend: ShapingBackend,
) {
    private val lifecycle = ReentrantLock(true)
    private val lifecycleChanged = lifecycle.newCondition()
    private val currentThreadOperations = ThreadLocal.withInitial { 0 }
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
        val context = EditorOperationContext.create(request.operationProfile, request.cancellationToken)
        return layout(request, context)
    }

    internal fun layout(
        request: JvmEditableLineFacadeRequest,
        context: EditorOperationContext,
    ): EditableLineResult {
        if (!acquireOperation()) return closedResult()
        return try {
            JvmEditableLineFacade.layoutBorrowing(request, backend, context)
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
     * closed by this operation. Calling `close` reentrantly from a synchronous callback of an
     * admitted [layout] on the same thread returns a `font.editable-line-session-close-reentrant`
     * [FontOperationResult.Failure] before changing an open session's state; the layout may finish
     * normally and the session remains usable and ordinarily closable afterward.
     */
    public fun close(): FontOperationResult<Unit> {
        lifecycle.withLock {
            if (currentThreadOperations.get() > 0) return reentrantCloseFailure()
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
        currentThreadOperations.set(currentThreadOperations.get() + 1)
        true
    }

    private fun releaseOperation() {
        lifecycle.withLock {
            check(activeOperations > 0) { "An editable-line session operation was released more than once." }
            val threadOperationCount = currentThreadOperations.get()
            check(threadOperationCount > 0) { "The current thread does not own an editable-line session operation." }
            if (threadOperationCount == 1) {
                currentThreadOperations.remove()
            } else {
                currentThreadOperations.set(threadOperationCount - 1)
            }
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

    private fun reentrantCloseFailure(): FontOperationResult.Failure = FontOperationResult.Failure(
        FontError.FontDataFailure(
            code = "font.editable-line-session-close-reentrant",
            message = "The JVM editable-line layout session cannot close from its own admitted layout callback.",
            location = FontDiagnosticLocation.Source,
        ),
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
