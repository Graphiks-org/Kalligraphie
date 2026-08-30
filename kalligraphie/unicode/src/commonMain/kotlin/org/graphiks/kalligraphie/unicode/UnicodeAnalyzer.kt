package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest

/** Portable contract for complete, snapshot-bound Unicode line analysis. */
public fun interface UnicodeAnalyzer {
    /**
     * Analyzes [snapshot] using the explicit direction and language in [request].
     *
     * Implementations return immutable ranges over the complete snapshot and
     * reject unsupported or malformed explicit inputs deterministically.
     */
    public fun analyze(snapshot: TextSnapshot, request: UnicodeAnalysisRequest): UnicodeAnalysis
}
