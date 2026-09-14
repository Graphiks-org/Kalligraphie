package org.graphiks.kalligraphie.api

/** Kind of representation described by a profile key. */
public enum class GlyphRepresentationProfileKind {
    /** A portable vector outline. */
    OUTLINE,

    /** A portable paint graph. */
    PAINT_GRAPH,

    /** A portable decoded bitmap. */
    BITMAP,

    /** A platform-specific borrowed handle. */
    PLATFORM_HANDLE,
}

/**
 * Comparable immutable identity of one selected materialization profile.
 *
 * The key records every profile parameter that may change a portable payload. It is safe for
 * consumer caches but is not a locator and does not retain a font provider or asset.
 */
public data class GlyphRepresentationProfileKey(
    /** Representation category selected by the consumer. */
    public val kind: GlyphRepresentationProfileKind,
    /** Consumer-understood schema version. */
    public val schemaVersion: Int,
    /** Canonical immutable profile parameter fingerprint. */
    public val parameters: String,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(parameters.isNotBlank()) { "parameters must not be blank." }
    }

    /** Factories that construct canonical keys for the common profile types. */
    public companion object {
        /** Returns the complete identity of one outline profile. */
        public fun outline(profile: OutlineProfile): GlyphRepresentationProfileKey =
            GlyphRepresentationProfileKey(
                kind = GlyphRepresentationProfileKind.OUTLINE,
                schemaVersion = profile.schemaVersion,
                parameters = profile.canonicalOutlineLimits(),
            )

        /** Returns the complete identity of one portable paint-graph profile. */
        public fun paintGraph(profile: PaintGraphProfile): GlyphRepresentationProfileKey =
            GlyphRepresentationProfileKey(
                kind = GlyphRepresentationProfileKind.PAINT_GRAPH,
                schemaVersion = profile.schemaVersion,
                parameters = listOf(
                    "nodes=${profile.acceptedNodeKinds.joinToString(",")}",
                    "composition=${profile.acceptedCompositionModes.joinToString(",")}",
                    "gradientExtend=${profile.acceptedGradientExtendModes.joinToString(",")}",
                    "gradientInterpolation=${profile.acceptedGradientInterpolationSpaces.joinToString(",")}",
                    "gradientAlphaInterpolation=${profile.acceptedGradientAlphaInterpolationModes.joinToString(",")}",
                    "limits=${profile.limits.canonicalPaintLimits()}",
                    "outline=${profile.outlineProfile.schemaVersion},${profile.outlineProfile.canonicalOutlineLimits(",")}",
                ).joinToString(";"),
            )

        /** Returns the complete identity of one portable bitmap profile. */
        public fun bitmap(profile: BitmapProfile): GlyphRepresentationProfileKey =
            GlyphRepresentationProfileKey(
                kind = GlyphRepresentationProfileKind.BITMAP,
                schemaVersion = profile.schemaVersion,
                parameters = listOf(
                    "strike=${profile.strike.pixelsPerEmX},${profile.strike.pixelsPerEmY}",
                    "pixels=${profile.acceptedPixelFormats.joinToString(",")}",
                    "colors=${profile.acceptedColorSpaces.joinToString(",")}",
                    "limits=${profile.limits.canonicalBitmapLimits()}",
                ).joinToString(";"),
            )

        /** Returns the complete identity of one platform-handle profile. */
        public fun platformHandle(profile: PlatformHandleProfile): GlyphRepresentationProfileKey =
            GlyphRepresentationProfileKey(
                kind = GlyphRepresentationProfileKind.PLATFORM_HANDLE,
                schemaVersion = profile.schemaVersion,
                parameters = listOf(profile.bridgeKind, profile.bridgeVersion, profile.bridgeId)
                    .joinToString(":") { value -> "${value.length}:$value" },
            )
    }
}

private fun OutlineProfile.canonicalOutlineLimits(separator: CharSequence = ":"): String =
    listOf(
        maxBytes,
        maxContours,
        maxPoints,
        maxCompositeDepth,
        maxCompositeComponents,
    ).joinToString(separator)

private fun PaintGraphLimits.canonicalPaintLimits(): String =
    listOf(
        maxNodes,
        maxReferences,
        maxDepth,
        maxSourceBytes,
        maxPaths,
        maxGradients,
        maxPalettes,
        maxPaletteEntries,
        maxColorRecords,
        maxDecodedPaletteBytes,
        maxBaseGlyphRecords,
        maxLayerRecords,
        maxSvgDocuments,
        maxSvgTransformOperations,
        maxColorStops,
        maxTransforms,
        maxComposites,
        maxClips,
        maxClipRecords,
        maxPaintVisits,
        maxSvgCompressedDocumentBytes,
        maxSvgDecodedDocumentBytes,
        maxSvgTotalDecodedBytes,
    ).joinToString(",")

private fun BitmapLimits.canonicalBitmapLimits(): String =
    listOf(
        maxStrikes,
        maxIndexSubtables,
        maxRecordCount,
        maxIndexTableBytes,
        maxBitmapTableBytes,
        maxWidth,
        maxHeight,
        maxPixels,
        maxCompressedBytes,
        maxTotalCompressedBytes,
        maxDecodedBytes,
        maxTotalDecodedBytes,
    ).joinToString(",")

/**
 * Stable cache identity of one glyph representation request.
 *
 * A key binds a semantic asset identity, glyph id, visual variant, and selected profile. It has
 * no reopening capability. Portable identities exclude the catalog generation; platform identities
 * retain their exact generation and bridge/runtime context. Reopening remains the responsibility
 * of [FontRenderAssetKey] plus a live resolver in the matching provider domain.
 */
public data class GlyphRepresentationKey(
    /** Semantic asset identity, without an ownership or reopening capability. */
    public val assetIdentity: FontRenderAssetSemanticIdentity,
    /** Glyph selected from the asset's face. */
    public val glyphId: GlyphId,
    /** Geometry-neutral visual variant used while materializing the payload. */
    public val variant: FontRenderVariantKey,
    /** Exact representation profile accepted by the consumer. */
    public val profile: GlyphRepresentationProfileKey,
    /** Canonical parameters specific to a bitmap or platform representation, if any. */
    public val routeParameters: String = "none",
) {
    init {
        require(assetIdentity.variant == variant) { "Glyph representation variant must match its asset identity." }
        require(routeParameters.isNotBlank()) { "routeParameters must not be blank." }
    }

    /**
     * Creates a semantic representation key from one generation-bound asset key.
     *
     * Portable reopening context is discarded: equal portable assets captured by later generations
     * receive the same representation key. Platform identities retain generation and bridge/runtime
     * context. Their [FontRenderAssetKey] values are still required for reopening.
     */
    public constructor(
        assetKey: FontRenderAssetKey,
        glyphId: GlyphId,
        variant: FontRenderVariantKey,
        profile: GlyphRepresentationProfileKey,
        routeParameters: String = "none",
    ) : this(assetKey.semanticIdentity, glyphId, variant, profile, routeParameters)
}
