// CatalogProbe.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.golden.fixtureBytes

/** What a probe observed on the current implementation. */
internal sealed interface ProbeObservation {
    /** The stage failed with exactly [diagnostic]. */
    data class Rejected(val stage: CatalogStage, val diagnostic: String) : ProbeObservation

    /** The stage succeeded; [observation] names the degraded behaviour. */
    data class Succeeded(val stage: CatalogStage, val observation: String) : ProbeObservation
}

/**
 * One entry's behaviour probe, keyed by catalog entry id.
 *
 * [fontPath] is the fixture resource the probe reads, and [observeBytes] sees exactly those bytes,
 * so the path a probe declares is structurally the path it exercises. A probe can therefore not
 * document another corpus font while still reporting the couple its entry declares: several fonts
 * share a refusal, and only the declared path tells the ratchet which one was observed.
 */
internal class CatalogProbe(
    /** Fixture resource the probe exercises; it must live under its entry's corpus key. */
    val fontPath: String,
    private val observeBytes: (ByteArray) -> ProbeObservation,
) {
    /** Reads [fontPath] and reports what the implementation did with it. */
    fun observe(): ProbeObservation = observeBytes(fixtureBytes(fontPath))
}
