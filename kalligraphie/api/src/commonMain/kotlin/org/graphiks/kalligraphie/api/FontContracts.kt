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
    public val glyphId: Int,
) {
    init {
        require(glyphId >= 0) { "glyphId must be non-negative." }
    }
}

public interface FontAssetResolverHandle {
    public val sourceId: FontSourceId
    public fun close(): FontOperationResult<Unit>
}

public interface FontRenderAssetHandle {
    public val faceId: FontFaceId
    public fun close(): FontOperationResult<Unit>
}

public data class FontInstanceDescriptor(
    public val layoutSize: LayoutUnit = LayoutUnit(12f),
)

public interface FontInstance {
    public val key: FontInstanceKey
}

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
    public val commands: List<Command>,
    public val fillRule: FillRule = FillRule.NON_ZERO,
) {
    init {
        require(glyphId >= 0) { "glyphId must be non-negative." }
        require(unitsPerEm > 0) { "unitsPerEm must be positive." }
    }

    public enum class FillRule {
        NON_ZERO,
    }

    public sealed interface Command {
        public data class MoveTo(public val x: Int, public val y: Int) : Command
        public data class LineTo(public val x: Int, public val y: Int) : Command
        public data class QuadraticTo(
            public val controlX: Int,
            public val controlY: Int,
            public val endX: Int,
            public val endY: Int,
        ) : Command

        public data object Close : Command
    }
}
