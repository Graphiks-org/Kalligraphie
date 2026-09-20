package org.graphiks.kalligraphie.coroutines

import kotlin.coroutines.cancellation.CancellationException

/**
 * Cancellation observed through a coroutine [kotlinx.coroutines.Job] rather than through the
 * consumer-supplied [org.graphiks.kalligraphie.api.CancellationToken].
 *
 * A Kotlin `Job` cancellation is reported as a [CancellationException] so structured concurrency
 * keeps working. The engine's typed cancellation result is never masked: it stays available on
 * [result], so callers can inspect the exact `.Cancelled` value and its diagnostics.
 *
 * Consumer-token cancellation that does not cancel the calling coroutine is returned as the typed
 * result and does not produce this exception.
 *
 * Kotlin forbids type parameters on `Throwable` subclasses, so [result] is exposed as [Any]; callers
 * cast it back to the concrete engine result type.
 *
 * @property result the typed engine result, never a partial publication.
 */
public class KalligraphieCancellationException(
    public val result: Any,
    message: String,
) : CancellationException(message)
