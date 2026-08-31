package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.LineBreakAnalysis
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

/** Portable contract for UAX #14 line-break opportunities over a complete Unicode analysis. */
public fun interface LineBreakAnalyzer {
    /**
     * Analyzes [snapshot] using the exact range, Unicode identity, and extended grapheme
     * clusters in [unicodeAnalysis].
     *
     * Both inputs must describe the same complete immutable snapshot revision. Implementations
     * return only [org.graphiks.kalligraphie.api.TextIndex] boundaries and expose no platform
     * string offsets or borrowed native resources.
     */
    public fun analyze(snapshot: TextSnapshot, unicodeAnalysis: UnicodeAnalysis): LineBreakAnalysis
}
