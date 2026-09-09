@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.EditorOperationContext
import org.graphiks.kalligraphie.api.EditorOperationLimitExceeded

/** Private control flow used to abort final layout before any candidate can be published. */
internal class EditorOperationLimitReached(
    val exceeded: EditorOperationLimitExceeded,
) : RuntimeException()

/** Reserves synthesized final glyphs before their backing collection is allocated. */
internal fun EditorOperationContext.reserveSyntheticGlyphs(count: Long) {
    chargeGlyphs(count)?.let { throw EditorOperationLimitReached(it) }
}
