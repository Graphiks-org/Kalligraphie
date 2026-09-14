package org.graphiks.kalligraphie.api

/** Immutable bridge contract and runtime interpretation identifying an exact platform route. */
public data class PlatformFontRouteIdentity(
    /** Stable platform bridge kind. */
    public val bridgeKind: String,
    /** Stable namespace distinguishing bridges of the same kind. */
    public val bridgeId: String,
    /** Bridge contract version, independent of OS releases. */
    public val bridgeVersion: String,
    /** Immutable platform runtime interpretation, including OS build and architecture. */
    public val runtimeInterpretationId: String,
) {
    init { require(listOf(bridgeKind, bridgeId, bridgeVersion, runtimeInterpretationId).all { it.isNotBlank() }) }
}

/** Exact provider-issued reopening proof; this immutable value owns no resource. */
public data class PlatformFontAssetContext(
    /** Exact bridge/runtime domain required when reopening. */
    public val routeIdentity: PlatformFontRouteIdentity,
    /** Opaque deterministic selection token, never a memory address or universal locator. */
    public val reopenToken: String,
) { init { require(reopenToken.isNotBlank()) } }

/** Render asset owning separately retainable platform font access rather than portable glyph IR. */
public interface PlatformFontRenderAssetHandle : FontRenderAssetHandle {
    /**
     * Transfers one independently closeable lease admitted before parent closure.
     * Children survive asset/resolver closure. Cancellation transfers no lease and cleanup
     * cannot be cancelled; closed admission returns ResourceClosed. Safe for concurrent callers.
     */
    public fun acquirePlatformFontLease(cancellationToken: CancellationToken = CancellationToken.none): FontOperationResult<PlatformFontLease>
}

/** Independently owned exact platform font, with linearizable nonblocking and idempotent close. */
public interface PlatformFontLease {
    /** Complete immutable asset identity; retaining this value alone keeps nothing alive. */
    public val key: FontRenderAssetKey
    /** Exact bridge and runtime context proven at platform font construction. */
    public val routeIdentity: PlatformFontRouteIdentity
    /**
     * Validates a final shaped glyph in the exact proven platform context, including zero/no-ink.
     * Checks platform identifier width and range, never paths or character remapping. An admitted
     * operation survives concurrent close; cancellation returns no partial proof.
     */
    public fun validateGlyph(glyphId: GlyphId, cancellationToken: CancellationToken = CancellationToken.none): FontOperationResult<Unit>
    /** Closes admission without waiting for existing children; repeated calls succeed. */
    public fun close(): FontOperationResult<Unit>
}
