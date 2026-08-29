package org.graphiks.kalligraphie.api

public interface FontCatalogSnapshot {
    public val sourceId: FontSourceId
    public fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle>
    public fun resolveFace(
        request: FontFaceRequest,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontFace>
}

public interface FontFace {
    public val id: FontFaceId
    public val metadata: FontFaceMetadata
    public fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance>
}

public data class FontFaceRequest(
    public val faceIndex: Int,
) {
    init {
        require(faceIndex >= 0) { "faceIndex must be non-negative." }
    }
}

public data class FontFaceMetadata(
    public val familyName: String,
    public val styleName: String,
    public val unitsPerEm: Int,
    public val glyphCount: Int,
) {
    init {
        require(familyName.isNotBlank()) { "familyName must not be blank." }
        require(styleName.isNotBlank()) { "styleName must not be blank." }
        require(unitsPerEm > 0) { "unitsPerEm must be positive." }
        require(glyphCount >= 0) { "glyphCount must be non-negative." }
    }
}

public class FontAccessRequirementsSnapshot private constructor(
    public val mode: Mode,
    public val outlineProfile: OutlineProfile?,
) {
    public enum class Mode {
        LAYOUT_ONLY,
        RENDERABLE,
    }

    public companion object {
        public fun layoutOnly(): FontAccessRequirementsSnapshot =
            FontAccessRequirementsSnapshot(Mode.LAYOUT_ONLY, null)

        public fun renderable(outlineProfile: OutlineProfile): FontAccessRequirementsSnapshot =
            FontAccessRequirementsSnapshot(Mode.RENDERABLE, outlineProfile)
    }
}

public data class OutlineProfile(
    public val schemaVersion: Int = 1,
    public val maxBytes: Int,
    public val maxContours: Int,
    public val maxPoints: Int,
    public val maxCompositeDepth: Int,
    public val maxCompositeComponents: Int,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(maxBytes > 0) { "maxBytes must be positive." }
        require(maxContours > 0) { "maxContours must be positive." }
        require(maxPoints > 0) { "maxPoints must be positive." }
        require(maxCompositeDepth > 0) { "maxCompositeDepth must be positive." }
        require(maxCompositeComponents > 0) { "maxCompositeComponents must be positive." }
    }
}

public data class FontRenderVariantKey(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "FontRenderVariantKey value must not be blank." }
    }

    public companion object {
        public val default: FontRenderVariantKey = FontRenderVariantKey("default")
    }
}

public data class FontGlyphRequest(
    public val glyphId: GlyphId,
)

public fun interface CancellationToken {
    public fun isCancellationRequested(): Boolean

    public companion object {
        public val none: CancellationToken = CancellationToken { false }
        public val cancelled: CancellationToken = CancellationToken { true }
    }
}

public interface FontAssetResolverHandle {
    public val sourceId: FontSourceId
    public fun close(): FontOperationResult<Unit>
}

public interface FontRenderAssetHandle {
    public val faceId: FontFaceId
    public fun detach(): FontOperationResult<FontRenderAssetHandle>
    public fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<GlyphRepresentation>
    public fun close(): FontOperationResult<Unit>
}

public data class FontInstanceDescriptor(
    public val layoutSize: LayoutUnit = LayoutUnit(12f),
)

public interface FontInstance {
    public val key: FontInstanceKey
    public fun resolveGlyph(codePoint: Int): FontOperationResult<GlyphResolution>
    public fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics>
    public fun acquireRenderAsset(
        resolver: FontAssetResolverHandle,
        variant: FontRenderVariantKey,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontRenderAssetHandle>
}

@JvmInline
public value class GlyphId(public val value: Int) {
    init {
        require(value >= 0) { "GlyphId value must be non-negative." }
    }
}

public data class GlyphResolution(
    public val codePoint: Int,
    public val glyphId: GlyphId,
)

public data class GlyphMetrics(
    public val advanceWidthDesignUnits: Int,
    public val leftSideBearingDesignUnits: Int,
    public val advanceWidth: LayoutUnit,
    public val leftSideBearing: LayoutUnit,
    public val bounds: DesignBounds = DesignBounds.empty,
)

public sealed interface GlyphRepresentation {
    public data object Empty : GlyphRepresentation

    public data class Outline(
        public val outline: GlyphOutlineIR,
    ) : GlyphRepresentation
}

public data class GlyphOutlineIR(
    public val glyphId: Int,
    public val unitsPerEm: Int,
    public val bounds: DesignBounds,
    public val contours: List<GlyphContour>,
    public val pointCount: Int,
    public val components: List<GlyphComponentReference> = emptyList(),
    public val limits: GlyphOutlineLimits,
    public val fillRule: FillRule = FillRule.NON_ZERO,
) {
    init {
        require(glyphId >= 0) { "glyphId must be non-negative." }
        require(unitsPerEm > 0) { "unitsPerEm must be positive." }
        require(pointCount >= 0) { "pointCount must be non-negative." }
    }

    public enum class FillRule {
        NON_ZERO,
    }
}

public data class GlyphContour(
    public val commands: List<GlyphOutlineCommand>,
) {
    init {
        require(commands.isNotEmpty()) { "GlyphContour commands must not be empty." }
        require(commands.first() is GlyphOutlineCommand.MoveTo) { "GlyphContour must start with MoveTo." }
        require(commands.last() is GlyphOutlineCommand.Close) { "GlyphContour must end with Close." }
    }
}

public sealed interface GlyphOutlineCommand {
    public data class MoveTo(public val x: Int, public val y: Int) : GlyphOutlineCommand
    public data class LineTo(public val x: Int, public val y: Int) : GlyphOutlineCommand
    public data class QuadraticTo(
        public val controlX: Int,
        public val controlY: Int,
        public val endX: Int,
        public val endY: Int,
    ) : GlyphOutlineCommand

    public data object Close : GlyphOutlineCommand
}

public data class GlyphComponentReference(
    public val glyphId: Int,
    public val transform: GlyphComponentTransform,
) {
    init {
        require(glyphId >= 0) { "Component glyphId must be non-negative." }
    }
}

public data class GlyphComponentTransform(
    public val translationX: Int,
    public val translationY: Int,
    public val xxF2Dot14: Int = 16_384,
    public val yxF2Dot14: Int = 0,
    public val xyF2Dot14: Int = 0,
    public val yyF2Dot14: Int = 16_384,
)

public data class GlyphOutlineLimits(
    public val maxBytes: Int,
    public val maxContours: Int,
    public val maxPoints: Int,
    public val maxCompositeDepth: Int,
    public val maxCompositeComponents: Int,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive." }
        require(maxContours > 0) { "maxContours must be positive." }
        require(maxPoints > 0) { "maxPoints must be positive." }
        require(maxCompositeDepth > 0) { "maxCompositeDepth must be positive." }
        require(maxCompositeComponents > 0) { "maxCompositeComponents must be positive." }
    }
}

public fun OutlineProfile.toGlyphOutlineLimits(): GlyphOutlineLimits =
    GlyphOutlineLimits(
        maxBytes = maxBytes,
        maxContours = maxContours,
        maxPoints = maxPoints,
        maxCompositeDepth = maxCompositeDepth,
        maxCompositeComponents = maxCompositeComponents,
    )
