package org.graphiks.kalligraphie.coroutines

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import org.graphiks.kalligraphie.FontDirectoryCatalog
import org.graphiks.kalligraphie.FontDirectoryCatalogOptions
import org.graphiks.kalligraphie.JvmEditableLineFacade
import org.graphiks.kalligraphie.JvmEditableLineFacadeRequest
import org.graphiks.kalligraphie.JvmEditableParagraphFacade
import org.graphiks.kalligraphie.JvmEditableParagraphFacadeRequest
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.ParagraphLayoutResult

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

    /**
     * Composes an editable paragraph, or resumes a partial one through its request continuation.
     *
     * A calling-Job cancellation observed at entry or exit raises a
     * [KalligraphieCancellationException] carrying the engine's typed result; a consumer-token
     * cancellation that does not cancel the coroutine returns the typed
     * [ParagraphLayoutResult.Cancelled]. A borrowed renderable resolver is never closed here.
     *
     * The exit check can also discard a *complete* engine result when the Job is cancelled after the
     * engine's last token poll; that window is not deterministically reachable in tests because the
     * bridge exposes the Job state to the engine, so the engine observes the cancellation at its next
     * poll and returns `.Cancelled`.
     */
    public suspend fun layout(request: JvmEditableParagraphFacadeRequest): ParagraphLayoutResult {
        val job = coroutineContext[Job]
        if (job?.isCancelled == true) {
            throw KalligraphieCancellationException(
                ParagraphLayoutResult.Cancelled(),
                "The coroutine was cancelled before the editable paragraph started.",
            )
        }
        val result = JvmEditableParagraphFacade.layout(
            request.withCancellationToken(bridgeCancellationToken(job, request.cancellationToken)),
        )
        if (job?.isCancelled == true) {
            throw KalligraphieCancellationException(
                result,
                "The coroutine was cancelled while composing the editable paragraph.",
            )
        }
        return result
    }

    /**
     * Captures a detached JVM font catalog through [FontDirectoryCatalog].
     *
     * A calling-Job cancellation observed at entry or exit raises a
     * [KalligraphieCancellationException] carrying the engine's typed result. This route has no
     * consumer-supplied token, so no consumer-token path exists.
     */
    public suspend fun open(options: FontDirectoryCatalogOptions): FontOperationResult<FontCatalogSnapshot> {
        val job = coroutineContext[Job]
        if (job?.isCancelled == true) {
            throw KalligraphieCancellationException(
                FontOperationResult.Cancelled(),
                "The coroutine was cancelled before the font catalog capture started.",
            )
        }
        val result = FontDirectoryCatalog.open(options, bridgeCancellationToken(job, CancellationToken.none))
        if (job?.isCancelled == true) {
            throw KalligraphieCancellationException(
                result,
                "The coroutine was cancelled while capturing the font catalog.",
            )
        }
        return result
    }
}
