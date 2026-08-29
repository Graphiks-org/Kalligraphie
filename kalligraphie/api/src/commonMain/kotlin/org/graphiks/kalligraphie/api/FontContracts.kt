package org.graphiks.kalligraphie.api

import kotlin.jvm.JvmInline

/** Parsed, immutable view of the faces available in a font source. */
public interface FontCatalogSnapshot {
    /** Identifier of the source from which this catalog was parsed. */
    public val sourceId: FontSourceId

    /** Opens a handle used to acquire render assets from this catalog. */
    public fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle>

    /** Resolves a face while applying the caller's access requirements. */
    public fun resolveFace(
        request: FontFaceRequest,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontFace>
}

/** A face that can create layout or rendering instances. */
public interface FontFace {
    /** Stable identifier of this face. */
    public val id: FontFaceId

    /** Metadata declared by the face. */
    public val metadata: FontFaceMetadata

    /** Creates an immutable instance for the requested layout size. */
    public fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance>
}

/** Selects a face within a font source. */
public data class FontFaceRequest(
    /** Zero-based face index. */
    public val faceIndex: Int,
) {
    init {
        require(faceIndex >= 0) { "faceIndex must be non-negative." }
    }
}

/** Descriptive and structural metadata for a font face. */
public data class FontFaceMetadata(
    /** Family name declared by the font. */
    public val familyName: String,
    /** Style name declared by the font. */
    public val styleName: String,
    /** Number of design units in one em. */
    public val unitsPerEm: Int,
    /** Number of glyphs addressable by the face. */
    public val glyphCount: Int,
) {
    init {
        require(familyName.isNotBlank()) { "familyName must not be blank." }
        require(styleName.isNotBlank()) { "styleName must not be blank." }
        require(unitsPerEm > 0) { "unitsPerEm must be positive." }
        require(glyphCount >= 0) { "glyphCount must be non-negative." }
    }
}

/** Immutable description of the data a caller needs from a face. */
public class FontAccessRequirementsSnapshot private constructor(
    /** Requested access mode. */
    public val mode: Mode,
    /** Outline representation constraints, when rendering is requested. */
    public val outlineProfile: OutlineProfile?,
) {
    /** Supported levels of font access. */
    public enum class Mode {
        /** Metrics and glyph mapping only. */
        LAYOUT_ONLY,

        /** Metrics, glyph mapping, and outlines. */
        RENDERABLE,
    }

    public companion object {
        /** Creates requirements for layout-only access. */
        public fun layoutOnly(): FontAccessRequirementsSnapshot =
            FontAccessRequirementsSnapshot(Mode.LAYOUT_ONLY, null)

        /** Creates requirements for bounded outline access. */
        public fun renderable(outlineProfile: OutlineProfile): FontAccessRequirementsSnapshot =
            FontAccessRequirementsSnapshot(Mode.RENDERABLE, outlineProfile)
    }
}

/** Resource and geometry limits applied while materializing outlines. */
public data class OutlineProfile(
    /** Version of the outline representation contract. */
    public val schemaVersion: Int = 1,
    /** Maximum number of bytes that may be materialized. */
    public val maxBytes: Int,
    /** Maximum number of contours in one outline. */
    public val maxContours: Int,
    /** Maximum number of points in one outline. */
    public val maxPoints: Int,
    /** Maximum depth of a composite glyph. */
    public val maxCompositeDepth: Int,
    /** Maximum number of composite components in one outline. */
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

/** Selects a rendering variant for a font instance. */
public data class FontRenderVariantKey(
    /** Stable variant key. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "FontRenderVariantKey value must not be blank." }
    }

    public companion object {
        /** Default variant used when no specialized renderer is selected. */
        public val default: FontRenderVariantKey = FontRenderVariantKey("default")
    }
}

/** Selects a glyph by its numeric identifier. */
public data class FontGlyphRequest(
    /** Non-negative glyph identifier. */
    public val glyphId: Int,
) {
    init {
        require(glyphId >= 0) { "glyphId must be non-negative." }
    }

    /** Creates a request from the type-safe [GlyphId] wrapper. */
    public constructor(
        glyphId: GlyphId,
        @Suppress("UNUSED_PARAMETER") typedGlyphIdMarker: Unit = Unit,
    ) : this(glyphId.value)

    /** Returns [glyphId] as a type-safe identifier. */
    public val typedGlyphId: GlyphId
        get() = GlyphId(glyphId)
}

/** Cooperative cancellation signal for potentially expensive font operations. */
public fun interface CancellationToken {
    /** Returns whether the current operation should stop. */
    public fun isCancellationRequested(): Boolean

    public companion object {
        /** Token that never requests cancellation. */
        public val none: CancellationToken = CancellationToken { false }
        /** Token that is already cancelled. */
        public val cancelled: CancellationToken = CancellationToken { true }
    }
}

/** Lifetime handle for render assets associated with a source. */
public interface FontAssetResolverHandle {
    /** Identifier of the source served by this resolver. */
    public val sourceId: FontSourceId

    /** Closes the resolver and releases its source resources. */
    public fun close(): FontOperationResult<Unit>
}

/** Handle providing glyph data for one face and render variant. */
public interface FontRenderAssetHandle {
    /** Identifier of the face served by this asset. */
    public val faceId: FontFaceId

    /** Detaches the asset from its resolver when the implementation supports it. */
    public fun detach(): FontOperationResult<FontRenderAssetHandle> =
        unsupportedContractOperation("This render asset does not support detachment.")

    /** Resolves a glyph representation. */
    public fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation>

    /** Resolves a glyph representation while honoring cancellation. */
    public fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> =
        if (cancellationToken.isCancellationRequested()) {
            FontOperationResult.Cancelled()
        } else {
            resolveGlyph(request)
        }

    /** Closes the asset and releases its resources. */
    public fun close(): FontOperationResult<Unit>
}

/** Selects the layout size for a font instance. */
public data class FontInstanceDescriptor(
    /** Requested size in layout units. */
    public val layoutSize: LayoutUnit = LayoutUnit(12f),
)

/** Immutable operational view of a font face at one size. */
public interface FontInstance {
    /** Stable key for this instance descriptor. */
    public val key: FontInstanceKey

    /** Resolves a Unicode scalar value to a glyph. */
    public fun resolveGlyph(codePoint: Int): FontOperationResult<GlyphResolution> =
        unsupportedContractOperation("This font instance does not support glyph resolution.")

    /** Returns metrics for a glyph in this instance. */
    public fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics> =
        unsupportedContractOperation("This font instance does not support glyph metrics.")

    /** Acquires a render asset for this instance. */
    public fun acquireRenderAsset(
        resolver: FontAssetResolverHandle,
        variant: FontRenderVariantKey,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontRenderAssetHandle> =
        unsupportedContractOperation("This font instance does not support render assets.")
}

@JvmInline
/** Type-safe non-negative glyph identifier. */
public value class GlyphId(public val value: Int) {
    init {
        require(value >= 0) { "GlyphId value must be non-negative." }
    }
}

/** Result of mapping a Unicode scalar to a glyph. */
public data class GlyphResolution(
    /** Unicode scalar value that was resolved. */
    public val codePoint: Int,
    /** Glyph selected for the code point. */
    public val glyphId: GlyphId,
)

/** Horizontal metrics and bounds for one glyph. */
public data class GlyphMetrics(
    /** Advance width in design units. */
    public val advanceWidthDesignUnits: Int,
    /** Left side bearing in design units. */
    public val leftSideBearingDesignUnits: Int,
    /** Advance width in layout units. */
    public val advanceWidth: LayoutUnit,
    /** Left side bearing in layout units. */
    public val leftSideBearing: LayoutUnit,
    /** Ink bounds in design units. */
    public val bounds: DesignBounds = DesignBounds.empty,
    /** Ink bounds in layout units. */
    public val scaledBounds: LayoutBounds = LayoutBounds.empty,
)

/** Representation returned for a resolved glyph. */
public sealed interface GlyphRepresentation {
    /** Represents a glyph without materialized outline data. */
    public data object Empty : GlyphRepresentation

    /** Represents a glyph with a materialized outline. */
    public data class Outline(
        /** Materialized outline intermediate representation. */
        public val outline: GlyphOutlineIR,
    ) : GlyphRepresentation
}

/** Immutable intermediate representation of a glyph outline. */
public class GlyphOutlineIR(
    /** Numeric glyph identifier. */
    public val glyphId: Int,
    /** Design units in one em. */
    public val unitsPerEm: Int,
    /** Bounds of the glyph in design units. */
    public val bounds: DesignBounds,
    /** Contours making up the outline. */
    contours: List<GlyphContour>,
    /** Number of outline points. */
    public val pointCount: Int,
    /** Composite glyph references, when present. */
    components: List<GlyphComponentReference> = emptyList(),
    /** Limits used to produce this representation. */
    public val limits: GlyphOutlineLimits,
    /** Fill rule consumers should use when rasterizing the outline. */
    public val fillRule: FillRule = FillRule.NON_ZERO,
) {
    /** Immutable contour snapshot. */
    public val contours: List<GlyphContour> = contours.immutableListSnapshot()
    /** Immutable composite component snapshot. */
    public val components: List<GlyphComponentReference> = components.immutableListSnapshot()
    /** Flattened command view retained for compatibility with the original contract. */
    public val commands: List<Command> = this.contours
        .flatMap { contour -> contour.commands.map(GlyphOutlineCommand::toLegacyCommand) }
        .immutableListSnapshot()

    init {
        require(glyphId >= 0) { "glyphId must be non-negative." }
        require(unitsPerEm > 0) { "unitsPerEm must be positive." }
        require(pointCount >= 0) { "pointCount must be non-negative." }
    }

    /**
     * Creates an outline from the legacy flattened command representation.
     *
     * The commands are converted into validated contours and use unlimited compatibility limits.
     */
    public constructor(
        glyphId: Int,
        unitsPerEm: Int,
        bounds: DesignBounds,
        commands: List<Command>,
        fillRule: FillRule = FillRule.NON_ZERO,
    ) : this(
        glyphId = glyphId,
        unitsPerEm = unitsPerEm,
        bounds = bounds,
        contours = commands.toLegacyContours(),
        pointCount = commands.sumOf { command -> command.pointContribution() },
        components = emptyList(),
        limits = GlyphOutlineLimits.compatibility,
        fillRule = fillRule,
    )

    /** Winding rule used to fill an outline. */
    public enum class FillRule {
        /** Non-zero winding rule. */
        NON_ZERO,
    }

    /** Legacy flattened command representation. */
    public sealed interface Command {
        /** Starts a contour at a design-space point. */
        public data class MoveTo(
            /** Horizontal coordinate. */
            public val x: Int,
            /** Vertical coordinate. */
            public val y: Int,
        ) : Command
        /** Adds a line segment to a design-space point. */
        public data class LineTo(
            /** Horizontal coordinate. */
            public val x: Int,
            /** Vertical coordinate. */
            public val y: Int,
        ) : Command
        /** Adds a quadratic Bézier segment. */
        public data class QuadraticTo(
            /** Control-point horizontal coordinate. */
            public val controlX: Int,
            /** Control-point vertical coordinate. */
            public val controlY: Int,
            /** End-point horizontal coordinate. */
            public val endX: Int,
            /** End-point vertical coordinate. */
            public val endY: Int,
        ) : Command

        /** Closes the current contour. */
        public data object Close : Command
    }

    /** Returns the glyph identifier. */
    public operator fun component1(): Int = glyphId

    /** Returns the units-per-em value. */
    public operator fun component2(): Int = unitsPerEm

    /** Returns the design-space bounds. */
    public operator fun component3(): DesignBounds = bounds

    /** Returns the compatibility command view. */
    public operator fun component4(): List<Command> = commands

    /** Returns the fill rule. */
    public operator fun component5(): FillRule = fillRule

    /** Copies the legacy view of this outline. */
    public fun copy(
        glyphId: Int = this.glyphId,
        unitsPerEm: Int = this.unitsPerEm,
        bounds: DesignBounds = this.bounds,
        commands: List<Command> = this.commands,
        fillRule: FillRule = this.fillRule,
    ): GlyphOutlineIR = GlyphOutlineIR(
        glyphId = glyphId,
        unitsPerEm = unitsPerEm,
        bounds = bounds,
        commands = commands,
        fillRule = fillRule,
    )

    /** Copies the structured outline representation. */
    public fun copy(
        glyphId: Int = this.glyphId,
        unitsPerEm: Int = this.unitsPerEm,
        bounds: DesignBounds = this.bounds,
        contours: List<GlyphContour>,
        pointCount: Int = this.pointCount,
        components: List<GlyphComponentReference> = this.components,
        limits: GlyphOutlineLimits = this.limits,
        fillRule: FillRule = this.fillRule,
    ): GlyphOutlineIR = GlyphOutlineIR(
        glyphId,
        unitsPerEm,
        bounds,
        contours,
        pointCount,
        components,
        limits,
        fillRule,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GlyphOutlineIR &&
            glyphId == other.glyphId &&
            unitsPerEm == other.unitsPerEm &&
            bounds == other.bounds &&
            contours == other.contours &&
            pointCount == other.pointCount &&
            components == other.components &&
            limits == other.limits &&
            fillRule == other.fillRule

    override fun hashCode(): Int {
        var result = glyphId
        result = 31 * result + unitsPerEm
        result = 31 * result + bounds.hashCode()
        result = 31 * result + contours.hashCode()
        result = 31 * result + pointCount
        result = 31 * result + components.hashCode()
        result = 31 * result + limits.hashCode()
        result = 31 * result + fillRule.hashCode()
        return result
    }

    override fun toString(): String =
        "GlyphOutlineIR(glyphId=$glyphId, unitsPerEm=$unitsPerEm, bounds=$bounds, contours=$contours, " +
            "pointCount=$pointCount, components=$components, limits=$limits, fillRule=$fillRule)"
}

/** A validated contour made of outline commands. */
public class GlyphContour(
    commands: List<GlyphOutlineCommand>,
) {
    /** Immutable commands forming this contour. */
    public val commands: List<GlyphOutlineCommand> = commands.immutableListSnapshot()

    init {
        require(commands.isNotEmpty()) { "GlyphContour commands must not be empty." }
        require(commands.first() is GlyphOutlineCommand.MoveTo) { "GlyphContour must start with MoveTo." }
        require(commands.last() is GlyphOutlineCommand.Close) { "GlyphContour must end with Close." }
    }

    /** Returns the contour commands. */
    public operator fun component1(): List<GlyphOutlineCommand> = commands

    /** Copies this contour with a new command list. */
    public fun copy(commands: List<GlyphOutlineCommand> = this.commands): GlyphContour = GlyphContour(commands)

    override fun equals(other: Any?): Boolean = this === other || other is GlyphContour && commands == other.commands

    override fun hashCode(): Int = commands.hashCode()

    override fun toString(): String = "GlyphContour(commands=$commands)"
}

/** Command in a structured glyph contour. */
public sealed interface GlyphOutlineCommand {
    /** Starts a contour at a design-space point. */
    public data class MoveTo(
        /** Horizontal coordinate. */
        public val x: Int,
        /** Vertical coordinate. */
        public val y: Int,
    ) : GlyphOutlineCommand
    /** Adds a line segment to a design-space point. */
    public data class LineTo(
        /** Horizontal coordinate. */
        public val x: Int,
        /** Vertical coordinate. */
        public val y: Int,
    ) : GlyphOutlineCommand
    /** Adds a quadratic Bézier segment. */
    public data class QuadraticTo(
        /** Control-point horizontal coordinate. */
        public val controlX: Int,
        /** Control-point vertical coordinate. */
        public val controlY: Int,
        /** End-point horizontal coordinate. */
        public val endX: Int,
        /** End-point vertical coordinate. */
        public val endY: Int,
    ) : GlyphOutlineCommand

    /** Closes the current contour. */
    public data object Close : GlyphOutlineCommand
}

/** Reference to a component glyph and its transform. */
public data class GlyphComponentReference(
    /** Referenced glyph identifier. */
    public val glyphId: Int,
    /** Transform applied to the component. */
    public val transform: GlyphComponentTransform,
) {
    init {
        require(glyphId >= 0) { "Component glyphId must be non-negative." }
    }
}

/** Two-dimensional affine transform for a composite glyph component. */
public data class GlyphComponentTransform(
    /** Horizontal translation in design units. */
    public val translationX: Int,
    /** Vertical translation in design units. */
    public val translationY: Int,
    /** F2DOT14 horizontal-to-horizontal scale. */
    public val xxF2Dot14: Int = 16_384,
    /** F2DOT14 horizontal-to-vertical shear. */
    public val yxF2Dot14: Int = 0,
    /** F2DOT14 vertical-to-horizontal shear. */
    public val xyF2Dot14: Int = 0,
    /** F2DOT14 vertical-to-vertical scale. */
    public val yyF2Dot14: Int = 16_384,
)

/** Resource limits attached to a materialized outline. */
public data class GlyphOutlineLimits(
    /** Maximum materialized byte count. */
    public val maxBytes: Int,
    /** Maximum contour count. */
    public val maxContours: Int,
    /** Maximum point count. */
    public val maxPoints: Int,
    /** Maximum composite depth. */
    public val maxCompositeDepth: Int,
    /** Maximum component count. */
    public val maxCompositeComponents: Int,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive." }
        require(maxContours > 0) { "maxContours must be positive." }
        require(maxPoints > 0) { "maxPoints must be positive." }
        require(maxCompositeDepth > 0) { "maxCompositeDepth must be positive." }
        require(maxCompositeComponents > 0) { "maxCompositeComponents must be positive." }
    }

    public companion object {
        /** Unlimited limits used by the legacy constructor. */
        public val compatibility: GlyphOutlineLimits = GlyphOutlineLimits(
            maxBytes = Int.MAX_VALUE,
            maxContours = Int.MAX_VALUE,
            maxPoints = Int.MAX_VALUE,
            maxCompositeDepth = Int.MAX_VALUE,
            maxCompositeComponents = Int.MAX_VALUE,
        )
    }
}

/** Converts public access requirements to the outline limits used by the scaler. */
public fun OutlineProfile.toGlyphOutlineLimits(): GlyphOutlineLimits =
    GlyphOutlineLimits(
        maxBytes = maxBytes,
        maxContours = maxContours,
        maxPoints = maxPoints,
        maxCompositeDepth = maxCompositeDepth,
        maxCompositeComponents = maxCompositeComponents,
    )

private fun List<GlyphOutlineIR.Command>.toLegacyContours(): List<GlyphContour> {
    if (isEmpty()) return emptyList()
    val contours = mutableListOf<GlyphContour>()
    var current = mutableListOf<GlyphOutlineCommand>()
    for (command in this) {
        val contourCommand = command.toContourCommand()
        if (contourCommand is GlyphOutlineCommand.MoveTo && current.isNotEmpty()) {
            require(current.last() is GlyphOutlineCommand.Close) { "Each legacy contour must end with Close." }
            contours += GlyphContour(current)
            current = mutableListOf()
        }
        current += contourCommand
        if (contourCommand is GlyphOutlineCommand.Close) {
            contours += GlyphContour(current)
            current = mutableListOf()
        }
    }
    require(current.isEmpty()) { "Legacy outline commands must end with Close." }
    return contours
}

private fun GlyphOutlineIR.Command.pointContribution(): Int =
    when (this) {
        is GlyphOutlineIR.Command.MoveTo,
        is GlyphOutlineIR.Command.LineTo -> 1
        is GlyphOutlineIR.Command.QuadraticTo -> 2
        GlyphOutlineIR.Command.Close -> 0
    }

private fun GlyphOutlineIR.Command.toContourCommand(): GlyphOutlineCommand =
    when (this) {
        is GlyphOutlineIR.Command.MoveTo -> GlyphOutlineCommand.MoveTo(x, y)
        is GlyphOutlineIR.Command.LineTo -> GlyphOutlineCommand.LineTo(x, y)
        is GlyphOutlineIR.Command.QuadraticTo ->
            GlyphOutlineCommand.QuadraticTo(controlX, controlY, endX, endY)
        GlyphOutlineIR.Command.Close -> GlyphOutlineCommand.Close
    }

private fun GlyphOutlineCommand.toLegacyCommand(): GlyphOutlineIR.Command =
    when (this) {
        is GlyphOutlineCommand.MoveTo -> GlyphOutlineIR.Command.MoveTo(x, y)
        is GlyphOutlineCommand.LineTo -> GlyphOutlineIR.Command.LineTo(x, y)
        is GlyphOutlineCommand.QuadraticTo ->
            GlyphOutlineIR.Command.QuadraticTo(controlX, controlY, endX, endY)
        GlyphOutlineCommand.Close -> GlyphOutlineIR.Command.Close
    }

private fun <Value> unsupportedContractOperation(message: String): FontOperationResult<Value> {
    val error = FontError.UnsupportedRepresentationProfile(message)
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}
