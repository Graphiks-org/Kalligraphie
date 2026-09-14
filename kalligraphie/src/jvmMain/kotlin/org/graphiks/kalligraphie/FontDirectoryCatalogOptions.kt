package org.graphiks.kalligraphie

import java.util.Collections
import org.graphiks.kalligraphie.api.FontCacheScope
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy

/** Immutable directory roots and positive bounds for a detached JVM font capture. */
public class FontDirectoryCatalogOptions(
    roots: List<String>,
    /** Paths inspected, including absent roots and root directories. */
    public val maxPathsToVisit: Int = 512,
    /** Accepted faces retained across all sources. */
    public val maxFaces: Int = 32,
    /** Bytes read per source container. */
    public val maxSourceBytes: Int = 16 * 1024 * 1024,
    /** Retained bytes of unique accepted containers, excluding defensive copies and decoding memory. */
    public val maxTotalSourceBytes: Int = 64 * 1024 * 1024,
    /** Existing per-face and aggregate portable representation retention policy. */
    public val materializationCachePolicy: FontMaterializationCachePolicy = FontMaterializationCachePolicy.disabled,
    /** Optional shared retention scope; closed scopes still allow uncached operations. */
    public val cacheScope: FontCacheScope? = null,
    /** Directories examined, including rejected faces. */
    public val maxFacesToExamine: Int = 128,
    /** Returned diagnostics, including a truncation diagnostic when necessary. */
    public val maxDiagnostics: Int = 64,
) {
    /** Immutable roots inspected in supplied order. */
    public val roots: List<String> = Collections.unmodifiableList(roots.toList())
    init {
        require(this.roots.isNotEmpty() && this.roots.all(String::isNotBlank)) { "Nonblank font roots are required." }
        require(this.roots.distinct().size == this.roots.size) { "Font roots must not repeat." }
        require(maxPathsToVisit > 0 && maxFaces > 0 && maxSourceBytes > 0 && maxTotalSourceBytes > 0 && maxFacesToExamine > 0 && maxDiagnostics > 0) { "Capture limits must be positive." }
    }
}
