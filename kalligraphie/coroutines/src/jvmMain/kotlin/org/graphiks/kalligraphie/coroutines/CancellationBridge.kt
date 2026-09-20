package org.graphiks.kalligraphie.coroutines

import kotlinx.coroutines.Job
import org.graphiks.kalligraphie.api.CancellationToken

/**
 * Combines the calling coroutine's [job] state with a consumer [delegate] signal.
 *
 * The combined token requests cancellation as soon as either source does. It holds only an
 * immutable reference to [job]; it never starts, completes or joins a coroutine and never creates
 * a dispatcher.
 */
internal fun bridgeCancellationToken(job: Job?, delegate: CancellationToken): CancellationToken =
    CancellationToken { job?.isCancelled == true || delegate.isCancellationRequested() }
