package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.*

/** Captures readable TrueType files and collections from explicitly supplied JVM directories. */
public object FontDirectoryCatalog {
    /**
     * Captures `.ttf`, `.otf`, `.ttc` and `.otc` candidates without following symbolic links.
     * Content validates supported TrueType faces; CFF sources are reported as unsupported.
     * Unsafe examined collection directories reject their entire source; safely addressed
     * unsupported or invalid face metadata can be excluded while preserving sibling indices.
     * A collection whose face count exceeds the remaining
     * [FontDirectoryCatalogOptions.maxFacesToExamine] budget is rejected whole with
     * [FontError.ResourceLimitExceeded], before any directory of that source is examined.
     * Collections are never admitted as partially examined prefixes. Independent completely
     * examined sources may still form a usable partial catalog. The separate
     * [FontDirectoryCatalogOptions.maxFaces] limit bounds accepted faces after complete source
     * examination and can omit siblings without renumbering those retained.
     * Captured candidates are ordered lexically and collection indices retain source order.
     * Limits and exclusions produce bounded diagnostics on partial success. With no accepted face
     * the operation fails with a typed error; cancellation never publishes a partial snapshot.
     * Each success has a fresh `directory-opentype` generation and owns captured bytes, so later
     * replacement/removal cannot affect retained resources. Callers close their resolvers/assets.
     * Cancellation is cooperative between filesystem operations and cannot interrupt a blocked OS call.
     */
    public fun open(options: FontDirectoryCatalogOptions, cancellationToken: CancellationToken = CancellationToken.none): FontOperationResult<FontCatalogSnapshot> =
        captureFontDirectories(options, cancellationToken, "directory-opentype")
}
