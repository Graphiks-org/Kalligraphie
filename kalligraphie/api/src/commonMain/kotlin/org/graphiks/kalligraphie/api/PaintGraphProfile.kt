package org.graphiks.kalligraphie.api

/** Portable paint-node category accepted by a paint-graph consumer. */
public enum class GlyphPaintNodeKind {
    /** A solid fill of one complete outline. */
    SOLID_OUTLINE,

    /** A solid fill of one portable path that can contain cubic Bézier segments. */
    PATH,

    /** An ordered compositing group. */
    GROUP,

    /** An unbounded solid paint. */
    SOLID,

    /** A three-point linear gradient. */
    LINEAR_GRADIENT,

    /** A radial gradient between two circles. */
    RADIAL_GRADIENT,

    /** An angular sweep gradient. */
    SWEEP_GRADIENT,

    /** A glyph-outline clip around a child paint. */
    GLYPH_CLIP,

    /** A portable-path clip around a child paint. */
    PATH_CLIP,

    /** An affine transform around a child paint. */
    TRANSFORM,

    /** A two-input composition operation. */
    COMPOSITE,
}

/** Resource limits enforced while validating one portable paint graph. */
public data class PaintGraphLimits(
    /** Maximum graph nodes. */
    public val maxNodes: Int,
    /** Maximum node-to-node references. */
    public val maxReferences: Int,
    /** Maximum root-to-leaf reference depth. */
    public val maxDepth: Int,
    /** Maximum source bytes accepted for one paint-table route. */
    public val maxSourceBytes: Int = 1_048_576,
    /** Maximum path-bearing paint nodes accepted by this profile. */
    public val maxPaths: Int = maxNodes,
    /** Maximum gradient-bearing paint nodes accepted by this profile. */
    public val maxGradients: Int = 0,
    /** Maximum CPAL palettes decoded for this route. */
    public val maxPalettes: Int = 256,
    /** Maximum entries in every decoded CPAL palette. */
    public val maxPaletteEntries: Int = 4_096,
    /** Maximum CPAL color records decoded for this route. */
    public val maxColorRecords: Int = 65_536,
    /** Maximum bytes retained by expanded CPAL palettes after shared source records are resolved. */
    public val maxDecodedPaletteBytes: Int = maxColorRecords.coerceAtMost(Int.MAX_VALUE / 4) * 4,
    /** Maximum COLR base-glyph records decoded before selecting one glyph. */
    public val maxBaseGlyphRecords: Int = 65_536,
    /** Maximum COLR layer records decoded before selecting one glyph. */
    public val maxLayerRecords: Int = 65_536,
    /** Maximum SVG-in-OpenType document records decoded for one paint route. */
    public val maxSvgDocuments: Int = 4_096,
    /**
     * Maximum authored SVG group and gradient transform operations parsed across the complete
     * `SVG ` table for one paint-route acquisition.
     *
     * This source-processing budget is independent of [maxTransforms], which bounds reached
     * transform nodes in the generated paint graph.
     */
    public val maxSvgTransformOperations: Int = 4_096,
    /** Maximum color stops across reached gradient paints. */
    public val maxColorStops: Int = 0,
    /** Maximum reached affine-transform paints. */
    public val maxTransforms: Int = 0,
    /** Maximum reached two-input composite paints. */
    public val maxComposites: Int = 0,
    /** Maximum reached glyph-outline and portable-path clip paints. */
    public val maxClips: Int = 0,
    /** Maximum COLR clip records decoded before selecting one glyph. */
    public val maxClipRecords: Int = 65_536,
    /**
     * Maximum paint-node visits while validating one graph root.
     *
     * The default follows [maxNodes] for conservative schema-2 validation. Schema-1 profiles
     * retain their original serialized-node and depth validation without a visit budget.
     */
    public val maxPaintVisits: Int = maxNodes,
    /** Maximum encoded bytes accepted for one gzip SVG document. */
    public val maxSvgCompressedDocumentBytes: Int = maxSourceBytes,
    /** Maximum decoded UTF-8 bytes accepted for one raw or gzip SVG document. */
    public val maxSvgDecodedDocumentBytes: Int = maxSourceBytes,
    /** Maximum decoded UTF-8 bytes accumulated across all SVG document records. */
    public val maxSvgTotalDecodedBytes: Int = maxSourceBytes,
) {
    init {
        require(maxNodes > 0) { "maxNodes must be positive." }
        require(maxReferences >= 0) { "maxReferences must be non-negative." }
        require(maxDepth > 0) { "maxDepth must be positive." }
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive." }
        require(maxPaths >= 0) { "maxPaths must be non-negative." }
        require(maxGradients >= 0) { "maxGradients must be non-negative." }
        require(maxPalettes > 0) { "maxPalettes must be positive." }
        require(maxPaletteEntries > 0) { "maxPaletteEntries must be positive." }
        require(maxColorRecords > 0) { "maxColorRecords must be positive." }
        require(maxDecodedPaletteBytes > 0) { "maxDecodedPaletteBytes must be positive." }
        require(maxBaseGlyphRecords > 0) { "maxBaseGlyphRecords must be positive." }
        require(maxLayerRecords > 0) { "maxLayerRecords must be positive." }
        require(maxSvgDocuments > 0) { "maxSvgDocuments must be positive." }
        require(maxSvgTransformOperations > 0) { "maxSvgTransformOperations must be positive." }
        require(maxColorStops >= 0) { "maxColorStops must be non-negative." }
        require(maxTransforms >= 0) { "maxTransforms must be non-negative." }
        require(maxComposites >= 0) { "maxComposites must be non-negative." }
        require(maxClips >= 0) { "maxClips must be non-negative." }
        require(maxClipRecords > 0) { "maxClipRecords must be positive." }
        require(maxPaintVisits > 0) { "maxPaintVisits must be positive." }
        require(maxSvgCompressedDocumentBytes > 0) { "maxSvgCompressedDocumentBytes must be positive." }
        require(maxSvgDecodedDocumentBytes > 0) { "maxSvgDecodedDocumentBytes must be positive." }
        require(maxSvgTotalDecodedBytes > 0) { "maxSvgTotalDecodedBytes must be positive." }
    }
}

/**
 * Consumer capabilities and resource bounds for one portable paint graph.
 *
 * [accepts] is deterministic and rejects an unsupported node, composition operation, or resource
 * bound before a provider can certify the graph. The profile owns immutable snapshots of all
 * caller-supplied collections and is safe to share between concurrent operations.
 */
public class PaintGraphProfile(
    acceptedNodeKinds: List<GlyphPaintNodeKind>,
    acceptedCompositionModes: List<GlyphPaintCompositionMode>,
    /** Resource limits applied to every accepted graph. */
    public val limits: PaintGraphLimits,
    /** Bounds enforced while materializing every outline and portable path referenced by the graph. */
    public val outlineProfile: OutlineProfile,
    /** Version of the paint-graph schema accepted by the consumer. */
    override val schemaVersion: Int = 1,
    acceptedGradientExtendModes: List<GlyphPaintExtendMode> = emptyList(),
    /**
     * Gradient RGB interpolation spaces accepted by the consumer.
     *
     * The default is empty for schema 1 and contains only [GlyphPaintInterpolationSpace.LINEAR_SRGB]
     * for schema 2 and later.
     */
    acceptedGradientInterpolationSpaces: List<GlyphPaintInterpolationSpace> =
        if (schemaVersion == 1) emptyList() else listOf(GlyphPaintInterpolationSpace.LINEAR_SRGB),
    /**
     * Gradient alpha-interpolation modes accepted by the consumer.
     *
     * The default is empty for schema 1 and contains only
     * [GlyphPaintAlphaInterpolationMode.PREMULTIPLIED] for schema 2 and later.
     */
    acceptedGradientAlphaInterpolationModes: List<GlyphPaintAlphaInterpolationMode> =
        if (schemaVersion == 1) emptyList() else listOf(GlyphPaintAlphaInterpolationMode.PREMULTIPLIED),
) : GlyphRepresentationProfile {
    /** Immutable node categories accepted by this consumer. */
    public val acceptedNodeKinds: List<GlyphPaintNodeKind> = acceptedNodeKinds.immutableListSnapshot()
    /** Immutable composition operations accepted by this consumer. */
    public val acceptedCompositionModes: List<GlyphPaintCompositionMode> = acceptedCompositionModes.immutableListSnapshot()
    /** Immutable gradient extension modes accepted by this consumer. */
    public val acceptedGradientExtendModes: List<GlyphPaintExtendMode> = acceptedGradientExtendModes.immutableListSnapshot()
    /** Immutable RGB interpolation spaces accepted for gradient color lines. */
    public val acceptedGradientInterpolationSpaces: List<GlyphPaintInterpolationSpace> =
        acceptedGradientInterpolationSpaces.immutableListSnapshot()
    /** Immutable alpha-interpolation modes accepted for gradient color lines. */
    public val acceptedGradientAlphaInterpolationModes: List<GlyphPaintAlphaInterpolationMode> =
        acceptedGradientAlphaInterpolationModes.immutableListSnapshot()

    init {
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(this.acceptedNodeKinds.isNotEmpty()) { "At least one paint node kind must be accepted." }
        require(this.acceptedNodeKinds.distinct().size == this.acceptedNodeKinds.size) { "Paint node kinds must not repeat." }
        require(this.acceptedCompositionModes.distinct().size == this.acceptedCompositionModes.size) {
            "Paint composition modes must not repeat."
        }
        require(this.acceptedGradientExtendModes.distinct().size == this.acceptedGradientExtendModes.size) {
            "Paint gradient extension modes must not repeat."
        }
        require(
            this.acceptedGradientInterpolationSpaces.distinct().size ==
                this.acceptedGradientInterpolationSpaces.size,
        ) {
            "Paint gradient interpolation spaces must not repeat."
        }
        require(
            this.acceptedGradientAlphaInterpolationModes.distinct().size ==
                this.acceptedGradientAlphaInterpolationModes.size,
        ) {
            "Paint gradient alpha-interpolation modes must not repeat."
        }
        if (schemaVersion == 1) {
            require(this.acceptedNodeKinds.none(GlyphPaintNodeKind::requiresSchemaTwo)) {
                "Schema 1 profiles cannot advertise schema 2 paint nodes."
            }
            require(this.acceptedGradientExtendModes.isEmpty()) {
                "Schema 1 profiles cannot advertise gradient extension modes."
            }
            require(this.acceptedGradientInterpolationSpaces.isEmpty()) {
                "Schema 1 profiles cannot advertise gradient interpolation spaces."
            }
            require(this.acceptedGradientAlphaInterpolationModes.isEmpty()) {
                "Schema 1 profiles cannot advertise gradient alpha-interpolation modes."
            }
            require(this.acceptedCompositionModes.all { mode -> mode == GlyphPaintCompositionMode.SOURCE_OVER }) {
                "Schema 1 profiles can advertise only SOURCE_OVER composition."
            }
        }
        if (schemaVersion < 3) {
            require(GlyphPaintNodeKind.PATH_CLIP !in this.acceptedNodeKinds) {
                "PathClip requires paint schema 3 or later."
            }
        }
        if (schemaVersion == 2) {
            require(
                this.acceptedGradientInterpolationSpaces.all { space ->
                    space == GlyphPaintInterpolationSpace.LINEAR_SRGB
                },
            ) {
                "Schema 2 profiles can advertise only linear-sRGB gradient interpolation."
            }
            require(
                this.acceptedGradientAlphaInterpolationModes.all { mode ->
                    mode == GlyphPaintAlphaInterpolationMode.PREMULTIPLIED
                },
            ) {
                "Schema 2 profiles can advertise only premultiplied gradient alpha interpolation."
            }
        }
    }

    /** Returns whether [paint] is completely supported within this profile's declared bounds. */
    public fun accepts(paint: GlyphPaintIR): Boolean {
        if (paint.schemaVersion != schemaVersion || paint.nodes.size > limits.maxNodes) return false
        val references = paint.nodes.sumOf { node -> node.children.size.toLong() }
        if (references > limits.maxReferences) return false
        if (schemaVersion == 1) return acceptsSchemaOne(paint)
        if (!acceptsReachedNodes(paint)) return false
        return paint.clipBounds != null || paint.hasBoundedRoot()
    }

    override fun equals(other: Any?): Boolean =
        other is PaintGraphProfile &&
            acceptedNodeKinds == other.acceptedNodeKinds &&
            acceptedCompositionModes == other.acceptedCompositionModes &&
            acceptedGradientExtendModes == other.acceptedGradientExtendModes &&
            acceptedGradientInterpolationSpaces == other.acceptedGradientInterpolationSpaces &&
            acceptedGradientAlphaInterpolationModes == other.acceptedGradientAlphaInterpolationModes &&
            limits == other.limits &&
            outlineProfile == other.outlineProfile &&
            schemaVersion == other.schemaVersion

    override fun hashCode(): Int {
        var result = acceptedNodeKinds.hashCode()
        result = 31 * result + acceptedCompositionModes.hashCode()
        result = 31 * result + acceptedGradientExtendModes.hashCode()
        result = 31 * result + acceptedGradientInterpolationSpaces.hashCode()
        result = 31 * result + acceptedGradientAlphaInterpolationModes.hashCode()
        result = 31 * result + limits.hashCode()
        result = 31 * result + outlineProfile.hashCode()
        return 31 * result + schemaVersion
    }

    private fun acceptsSchemaOne(paint: GlyphPaintIR): Boolean {
        val paths = paint.nodes.count { node -> node is GlyphPaintNode.SolidOutline || node is GlyphPaintNode.Path }
        if (paths > limits.maxPaths) return false
        if (paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().any { node -> !outlineProfile.acceptsOutline(node.outline) }) {
            return false
        }
        if (paint.nodes.filterIsInstance<GlyphPaintNode.Path>().any { node -> !outlineProfile.acceptsPath(node.path) }) {
            return false
        }
        if (paint.nodes.any { node -> node.kind() !in acceptedNodeKinds }) return false
        if (paint.nodes.filterIsInstance<GlyphPaintNode.Group>().any { group -> group.compositionMode !in acceptedCompositionModes }) {
            return false
        }
        return !paint.exceedsSchemaOneDepth(limits.maxDepth)
    }

    private fun acceptsReachedNodes(paint: GlyphPaintIR): Boolean {
        var visits = 0L
        var paths = 0L
        var gradients = 0L
        var colorStops = 0L
        var transforms = 0L
        var composites = 0L
        var clips = 0L
        val pendingNodes = ArrayDeque<Int>()
        val pendingDepths = ArrayDeque<Int>()
        pendingNodes.addLast(paint.rootNode)
        pendingDepths.addLast(1)

        while (pendingNodes.isNotEmpty()) {
            val node = paint.nodes[pendingNodes.removeLast()]
            val depth = pendingDepths.removeLast()
            visits += 1
            if (visits > limits.maxPaintVisits || depth > limits.maxDepth) return false
            if (node.kind() !in acceptedNodeKinds) return false

            when (node) {
                is GlyphPaintNode.SolidOutline -> {
                    paths += 1
                    if (!outlineProfile.acceptsOutline(node.outline)) return false
                }
                is GlyphPaintNode.Path -> {
                    paths += 1
                    if (!outlineProfile.acceptsPath(node.path)) return false
                }
                is GlyphPaintNode.Group -> {
                    if (node.compositionMode !in acceptedCompositionModes) return false
                }
                is GlyphPaintNode.Solid -> Unit
                is GlyphPaintNode.LinearGradient -> {
                    gradients += 1
                    colorStops += node.colorLine.colorStops.size
                    if (node.colorLine.extendMode !in acceptedGradientExtendModes) return false
                    if (node.colorLine.interpolationSpace !in acceptedGradientInterpolationSpaces) return false
                    if (node.colorLine.alphaInterpolationMode !in acceptedGradientAlphaInterpolationModes) return false
                }
                is GlyphPaintNode.RadialGradient -> {
                    gradients += 1
                    colorStops += node.colorLine.colorStops.size
                    if (node.colorLine.extendMode !in acceptedGradientExtendModes) return false
                    if (node.colorLine.interpolationSpace !in acceptedGradientInterpolationSpaces) return false
                    if (node.colorLine.alphaInterpolationMode !in acceptedGradientAlphaInterpolationModes) return false
                }
                is GlyphPaintNode.SweepGradient -> {
                    gradients += 1
                    colorStops += node.colorLine.colorStops.size
                    if (node.colorLine.extendMode !in acceptedGradientExtendModes) return false
                    if (node.colorLine.interpolationSpace !in acceptedGradientInterpolationSpaces) return false
                    if (node.colorLine.alphaInterpolationMode !in acceptedGradientAlphaInterpolationModes) return false
                }
                is GlyphPaintNode.GlyphClip -> {
                    clips += 1
                    if (!outlineProfile.acceptsOutline(node.outline)) return false
                }
                is GlyphPaintNode.PathClip -> {
                    paths += 1
                    clips += 1
                    if (!outlineProfile.acceptsPath(node.path)) return false
                }
                is GlyphPaintNode.Transform -> transforms += 1
                is GlyphPaintNode.Composite -> {
                    composites += 1
                    if (node.mode !in acceptedCompositionModes) return false
                }
            }
            if (
                paths > limits.maxPaths ||
                gradients > limits.maxGradients ||
                colorStops > limits.maxColorStops ||
                transforms > limits.maxTransforms ||
                composites > limits.maxComposites ||
                clips > limits.maxClips
            ) {
                return false
            }
            node.children.forEach { child ->
                pendingNodes.addLast(child)
                pendingDepths.addLast(depth + 1)
            }
        }
        return true
    }
}

private fun GlyphPaintNode.kind(): GlyphPaintNodeKind = when (this) {
    is GlyphPaintNode.SolidOutline -> GlyphPaintNodeKind.SOLID_OUTLINE
    is GlyphPaintNode.Path -> GlyphPaintNodeKind.PATH
    is GlyphPaintNode.Group -> GlyphPaintNodeKind.GROUP
    is GlyphPaintNode.Solid -> GlyphPaintNodeKind.SOLID
    is GlyphPaintNode.LinearGradient -> GlyphPaintNodeKind.LINEAR_GRADIENT
    is GlyphPaintNode.RadialGradient -> GlyphPaintNodeKind.RADIAL_GRADIENT
    is GlyphPaintNode.SweepGradient -> GlyphPaintNodeKind.SWEEP_GRADIENT
    is GlyphPaintNode.GlyphClip -> GlyphPaintNodeKind.GLYPH_CLIP
    is GlyphPaintNode.PathClip -> GlyphPaintNodeKind.PATH_CLIP
    is GlyphPaintNode.Transform -> GlyphPaintNodeKind.TRANSFORM
    is GlyphPaintNode.Composite -> GlyphPaintNodeKind.COMPOSITE
}

private fun GlyphPaintNodeKind.requiresSchemaTwo(): Boolean = when (this) {
    GlyphPaintNodeKind.SOLID_OUTLINE,
    GlyphPaintNodeKind.PATH,
    GlyphPaintNodeKind.GROUP,
    -> false
    GlyphPaintNodeKind.SOLID,
    GlyphPaintNodeKind.LINEAR_GRADIENT,
    GlyphPaintNodeKind.RADIAL_GRADIENT,
    GlyphPaintNodeKind.SWEEP_GRADIENT,
    GlyphPaintNodeKind.GLYPH_CLIP,
    GlyphPaintNodeKind.PATH_CLIP,
    GlyphPaintNodeKind.TRANSFORM,
    GlyphPaintNodeKind.COMPOSITE,
    -> true
}

internal fun OutlineProfile.acceptsOutline(outline: GlyphOutlineIR): Boolean =
    outline.contours.size <= maxContours &&
        outline.pointCount <= maxPoints &&
        outline.components.size <= maxCompositeComponents &&
        outline.limits.maxBytes <= maxBytes &&
        outline.limits.maxContours <= maxContours &&
        outline.limits.maxPoints <= maxPoints &&
        outline.limits.maxCompositeDepth <= maxCompositeDepth &&
        outline.limits.maxCompositeComponents <= maxCompositeComponents

internal fun OutlineProfile.acceptsPath(path: GlyphPaintPath): Boolean =
    path.contourCount <= maxContours &&
        path.pointCount <= maxPoints &&
        path.estimatedByteSize <= maxBytes

private fun GlyphPaintIR.exceedsSchemaOneDepth(maximum: Int): Boolean {
    val greatestVisitedDepth = IntArray(nodes.size)
    val pendingNodes = ArrayDeque<Int>()
    val pendingDepths = ArrayDeque<Int>()
    pendingNodes.addLast(rootNode)
    pendingDepths.addLast(1)

    while (pendingNodes.isNotEmpty()) {
        val node = pendingNodes.removeLast()
        val depth = pendingDepths.removeLast()
        if (depth > maximum) return true
        if (depth <= greatestVisitedDepth[node]) continue
        greatestVisitedDepth[node] = depth
        nodes[node].children.forEach { child ->
            pendingNodes.addLast(child)
            pendingDepths.addLast(depth + 1)
        }
    }
    return false
}

private fun GlyphPaintIR.hasBoundedRoot(): Boolean {
    val bounded = ByteArray(nodes.size)
    val pendingNodes = ArrayDeque<Int>()
    val readyToEvaluate = ArrayDeque<Boolean>()
    pendingNodes.addLast(rootNode)
    readyToEvaluate.addLast(false)

    while (pendingNodes.isNotEmpty()) {
        val index = pendingNodes.removeLast()
        val ready = readyToEvaluate.removeLast()
        if (bounded[index] != UNKNOWN_BOUNDEDNESS) continue
        val node = nodes[index]
        if (!ready) {
            pendingNodes.addLast(index)
            readyToEvaluate.addLast(true)
            node.children.forEach { child ->
                if (bounded[child] == UNKNOWN_BOUNDEDNESS) {
                    pendingNodes.addLast(child)
                    readyToEvaluate.addLast(false)
                }
            }
            continue
        }
        bounded[index] = if (node.isBounded(bounded)) BOUNDED else UNBOUNDED
    }
    return bounded[rootNode] == BOUNDED
}

private fun GlyphPaintNode.isBounded(bounded: ByteArray): Boolean = when (this) {
    is GlyphPaintNode.SolidOutline,
    is GlyphPaintNode.Path,
    is GlyphPaintNode.GlyphClip,
    is GlyphPaintNode.PathClip,
    -> true
    is GlyphPaintNode.Solid,
    is GlyphPaintNode.LinearGradient,
    is GlyphPaintNode.RadialGradient,
    is GlyphPaintNode.SweepGradient,
    -> false
    is GlyphPaintNode.Group -> children.all { child -> bounded[child] == BOUNDED }
    is GlyphPaintNode.Transform -> bounded[paint] == BOUNDED
    is GlyphPaintNode.Composite -> compositeIsBounded(
        sourceIsBounded = bounded[source] == BOUNDED,
        backdropIsBounded = bounded[backdrop] == BOUNDED,
        mode = mode,
    )
}

private fun compositeIsBounded(
    sourceIsBounded: Boolean,
    backdropIsBounded: Boolean,
    mode: GlyphPaintCompositionMode,
): Boolean = when (mode) {
    GlyphPaintCompositionMode.CLEAR -> true
    GlyphPaintCompositionMode.SOURCE -> sourceIsBounded
    GlyphPaintCompositionMode.DESTINATION -> backdropIsBounded
    GlyphPaintCompositionMode.SOURCE_IN,
    GlyphPaintCompositionMode.DESTINATION_IN,
    -> sourceIsBounded || backdropIsBounded
    GlyphPaintCompositionMode.SOURCE_OUT -> sourceIsBounded
    GlyphPaintCompositionMode.DESTINATION_OUT -> backdropIsBounded
    GlyphPaintCompositionMode.SOURCE_ATOP,
    GlyphPaintCompositionMode.DESTINATION_ATOP,
    GlyphPaintCompositionMode.SOURCE_OVER,
    GlyphPaintCompositionMode.DESTINATION_OVER,
    GlyphPaintCompositionMode.XOR,
    GlyphPaintCompositionMode.PLUS,
    GlyphPaintCompositionMode.SCREEN,
    GlyphPaintCompositionMode.OVERLAY,
    GlyphPaintCompositionMode.DARKEN,
    GlyphPaintCompositionMode.LIGHTEN,
    GlyphPaintCompositionMode.COLOR_DODGE,
    GlyphPaintCompositionMode.COLOR_BURN,
    GlyphPaintCompositionMode.HARD_LIGHT,
    GlyphPaintCompositionMode.SOFT_LIGHT,
    GlyphPaintCompositionMode.DIFFERENCE,
    GlyphPaintCompositionMode.EXCLUSION,
    GlyphPaintCompositionMode.MULTIPLY,
    GlyphPaintCompositionMode.HSL_HUE,
    GlyphPaintCompositionMode.HSL_SATURATION,
    GlyphPaintCompositionMode.HSL_COLOR,
    GlyphPaintCompositionMode.HSL_LUMINOSITY,
    -> sourceIsBounded && backdropIsBounded
}

private const val UNKNOWN_BOUNDEDNESS: Byte = 0
private const val BOUNDED: Byte = 1
private const val UNBOUNDED: Byte = 2
