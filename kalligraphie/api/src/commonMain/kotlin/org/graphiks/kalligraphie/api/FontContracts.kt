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

    public constructor(
        glyphId: GlyphId,
        @Suppress("UNUSED_PARAMETER") typedGlyphIdMarker: Unit = Unit,
    ) : this(glyphId.value)

    public val typedGlyphId: GlyphId
        get() = GlyphId(glyphId)
}

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
    public fun detach(): FontOperationResult<FontRenderAssetHandle> =
        unsupportedContractOperation("This render asset does not support detachment.")

    public fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation>

    public fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> =
        if (cancellationToken.isCancellationRequested()) {
            FontOperationResult.Cancelled()
        } else {
            resolveGlyph(request)
        }

    public fun close(): FontOperationResult<Unit>
}

public data class FontInstanceDescriptor(
    public val layoutSize: LayoutUnit = LayoutUnit(12f),
)

public interface FontInstance {
    public val key: FontInstanceKey
    public fun resolveGlyph(codePoint: Int): FontOperationResult<GlyphResolution> =
        unsupportedContractOperation("This font instance does not support glyph resolution.")

    public fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics> =
        unsupportedContractOperation("This font instance does not support glyph metrics.")

    public fun acquireRenderAsset(
        resolver: FontAssetResolverHandle,
        variant: FontRenderVariantKey,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontRenderAssetHandle> =
        unsupportedContractOperation("This font instance does not support render assets.")
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
    public val scaledBounds: LayoutBounds = LayoutBounds.empty,
)

public sealed interface GlyphRepresentation {
    public data object Empty : GlyphRepresentation

    public data class Outline(
        public val outline: GlyphOutlineIR,
    ) : GlyphRepresentation
}

public class GlyphOutlineIR(
    public val glyphId: Int,
    public val unitsPerEm: Int,
    public val bounds: DesignBounds,
    contours: List<GlyphContour>,
    public val pointCount: Int,
    components: List<GlyphComponentReference> = emptyList(),
    public val limits: GlyphOutlineLimits,
    public val fillRule: FillRule = FillRule.NON_ZERO,
) {
    public val contours: List<GlyphContour> = contours.immutableListSnapshot()
    public val components: List<GlyphComponentReference> = components.immutableListSnapshot()
    public val commands: List<Command> = this.contours
        .flatMap { contour -> contour.commands.map(GlyphOutlineCommand::toLegacyCommand) }
        .immutableListSnapshot()

    init {
        require(glyphId >= 0) { "glyphId must be non-negative." }
        require(unitsPerEm > 0) { "unitsPerEm must be positive." }
        require(pointCount >= 0) { "pointCount must be non-negative." }
    }

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

    public operator fun component1(): Int = glyphId

    public operator fun component2(): Int = unitsPerEm

    public operator fun component3(): DesignBounds = bounds

    public operator fun component4(): List<Command> = commands

    public operator fun component5(): FillRule = fillRule

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

public class GlyphContour(
    commands: List<GlyphOutlineCommand>,
) {
    public val commands: List<GlyphOutlineCommand> = commands.immutableListSnapshot()

    init {
        require(commands.isNotEmpty()) { "GlyphContour commands must not be empty." }
        require(commands.first() is GlyphOutlineCommand.MoveTo) { "GlyphContour must start with MoveTo." }
        require(commands.last() is GlyphOutlineCommand.Close) { "GlyphContour must end with Close." }
    }

    public operator fun component1(): List<GlyphOutlineCommand> = commands

    public fun copy(commands: List<GlyphOutlineCommand> = this.commands): GlyphContour = GlyphContour(commands)

    override fun equals(other: Any?): Boolean = this === other || other is GlyphContour && commands == other.commands

    override fun hashCode(): Int = commands.hashCode()

    override fun toString(): String = "GlyphContour(commands=$commands)"
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

    public companion object {
        public val compatibility: GlyphOutlineLimits = GlyphOutlineLimits(
            maxBytes = Int.MAX_VALUE,
            maxContours = Int.MAX_VALUE,
            maxPoints = Int.MAX_VALUE,
            maxCompositeDepth = Int.MAX_VALUE,
            maxCompositeComponents = Int.MAX_VALUE,
        )
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
