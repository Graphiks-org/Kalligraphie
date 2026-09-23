package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenScene

/** A catalogued scene paired with the renderer that produces its canonical image. */
internal class GoldenSceneEntry(
    val scene: GoldenScene,
    val render: () -> GoldenRenderOutcome,
)
