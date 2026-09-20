package org.graphiks.kalligraphie.coroutines

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import org.graphiks.kalligraphie.JvmEditableLineFacade
import org.graphiks.kalligraphie.JvmEditableLineFacadeRequest
import org.graphiks.kalligraphie.api.EditableLineResult

/**
 * Optional suspend facade over the synchronous Kalligraphie consumer routes.
 *
 * Each function runs the equivalent synchronous call in the caller's [coroutineContext] and
 * bridges the calling [Job] into the Kalligraphie cancellation token. It never creates a
 * dispatcher, never owns a resource and never publishes a partial result.
 */
public object KalligraphieCoroutines {
    /**
     * Lays out one editable line through [JvmEditableLineFacade].
     *
     * Any calling-[Job] cancellation observed at entry or at exit raises a
     * [KalligraphieCancellationException] carrying the engine's typed result, which may be a typed
     * [EditableLineResult.Cancelled] or a complete result — the engine result is never masked.
     * Cancellation requested by the request token without cancelling the calling coroutine is
     * returned as [EditableLineResult.Cancelled]. The resolver borrowed by a `Renderable` request
     * stays the caller's property.
     */
    public suspend fun layout(request: JvmEditableLineFacadeRequest): EditableLineResult {
        val job = coroutineContext[Job]
        if (job?.isCancelled == true) {
            throw KalligraphieCancellationException(
                EditableLineResult.Cancelled(),
                "The coroutine was cancelled before the editable line started.",
            )
        }
        val result = JvmEditableLineFacade.layout(
            request.withCancellationToken(bridgeCancellationToken(job, request.cancellationToken)),
        )
        if (job?.isCancelled == true) {
            throw KalligraphieCancellationException(
                result,
                "The coroutine was cancelled while laying out the editable line.",
            )
        }
        return result
    }
}
