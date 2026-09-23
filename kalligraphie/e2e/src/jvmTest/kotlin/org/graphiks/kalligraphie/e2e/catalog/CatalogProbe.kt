// CatalogProbe.kt
package org.graphiks.kalligraphie.e2e.catalog

/** What a probe observed on the current implementation. */
internal sealed interface ProbeObservation {
    /** The stage failed with exactly [diagnostic]. */
    data class Rejected(val stage: CatalogStage, val diagnostic: String) : ProbeObservation

    /** The stage succeeded; [observation] names the degraded behaviour. */
    data class Succeeded(val stage: CatalogStage, val observation: String) : ProbeObservation
}

/** One entry's behaviour probe, keyed by catalog entry id. */
internal class CatalogProbe(val observe: () -> ProbeObservation)
