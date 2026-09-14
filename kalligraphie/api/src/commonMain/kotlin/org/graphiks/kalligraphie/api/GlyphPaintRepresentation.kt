package org.graphiks.kalligraphie.api

/** An eight-bit, non-premultiplied color used by a portable paint graph. */
public data class GlyphColor(
    /** Red component in the inclusive range `0..255`. */
    public val red: Int,
    /** Green component in the inclusive range `0..255`. */
    public val green: Int,
    /** Blue component in the inclusive range `0..255`. */
    public val blue: Int,
    /** Alpha component in the inclusive range `0..255`. */
    public val alpha: Int = 255,
) {
    init {
        require(red in 0..255) { "red must be between 0 and 255." }
        require(green in 0..255) { "green must be between 0 and 255." }
        require(blue in 0..255) { "blue must be between 0 and 255." }
        require(alpha in 0..255) { "alpha must be between 0 and 255." }
    }
}

/** Composition operation declared by a portable paint graph. */
public enum class GlyphPaintCompositionMode {
    /** Clears source and destination. */
    CLEAR,

    /** Keeps only the source. */
    SOURCE,

    /** Keeps only the destination. */
    DESTINATION,

    /** Paint source over the current destination. */
    SOURCE_OVER,

    /** Paints the destination over the source. */
    DESTINATION_OVER,

    /** Keeps the source where it overlaps the destination. */
    SOURCE_IN,

    /** Keeps the destination where it overlaps the source. */
    DESTINATION_IN,

    /** Keeps the source where it does not overlap the destination. */
    SOURCE_OUT,

    /** Keeps the destination where it does not overlap the source. */
    DESTINATION_OUT,

    /** Paints the source only within the destination. */
    SOURCE_ATOP,

    /** Paints the destination only within the source. */
    DESTINATION_ATOP,

    /** Keeps the non-overlapping portions of source and destination. */
    XOR,

    /** Adds source and destination color components. */
    PLUS,

    /** Applies the screen blend mode. */
    SCREEN,

    /** Applies the overlay blend mode. */
    OVERLAY,

    /** Selects the darker source or destination components. */
    DARKEN,

    /** Selects the lighter source or destination components. */
    LIGHTEN,

    /** Applies the color-dodge blend mode. */
    COLOR_DODGE,

    /** Applies the color-burn blend mode. */
    COLOR_BURN,

    /** Applies the hard-light blend mode. */
    HARD_LIGHT,

    /** Applies the soft-light blend mode. */
    SOFT_LIGHT,

    /** Applies the difference blend mode. */
    DIFFERENCE,

    /** Applies the exclusion blend mode. */
    EXCLUSION,

    /** Multiplies source and destination color components. */
    MULTIPLY,

    /** Uses source hue with destination saturation and luminosity. */
    HSL_HUE,

    /** Uses source saturation with destination hue and luminosity. */
    HSL_SATURATION,

    /** Uses source hue and saturation with destination luminosity. */
    HSL_COLOR,

    /** Uses source luminosity with destination hue and saturation. */
    HSL_LUMINOSITY,
}

/** A finite point in paint-graph design space. */
public data class GlyphPaintPoint(
    /** Horizontal coordinate in design units. */
    public val x: Double,
    /** Vertical coordinate in design units. */
    public val y: Double,
) {
    init {
        require(x.isFinite()) { "x must be finite." }
        require(y.isFinite()) { "y must be finite." }
    }
}

/**
 * A finite design-space affine transform: `x' = xx*x + xy*y + dx`,
 * `y' = yx*x + yy*y + dy`.
 */
public data class GlyphAffineTransform(
    /** Horizontal scale and rotation coefficient. */
    public val xx: Double,
    /** Vertical shear and rotation coefficient. */
    public val yx: Double,
    /** Horizontal shear and rotation coefficient. */
    public val xy: Double,
    /** Vertical scale and rotation coefficient. */
    public val yy: Double,
    /** Horizontal translation in design units. */
    public val dx: Double,
    /** Vertical translation in design units. */
    public val dy: Double,
) {
    init {
        require(xx.isFinite()) { "xx must be finite." }
        require(yx.isFinite()) { "yx must be finite." }
        require(xy.isFinite()) { "xy must be finite." }
        require(yy.isFinite()) { "yy must be finite." }
        require(dx.isFinite()) { "dx must be finite." }
        require(dy.isFinite()) { "dy must be finite." }
    }
}

/** Extension behavior outside a color line's declared stops. */
public enum class GlyphPaintExtendMode {
    /** Extends the nearest endpoint color. */
    PAD,

    /** Repeats the color line in the same direction. */
    REPEAT,

    /** Repeats the color line while alternating its direction. */
    REFLECT,
}

/** One finite color stop in a portable paint color line. */
public data class GlyphPaintColorStop(
    /** Position along the color line. */
    public val offset: Double,
    /** Non-premultiplied stop color. */
    public val color: GlyphColor,
    /** Additional stop opacity in the inclusive range `0.0..1.0`. */
    public val opacity: Double,
) {
    init {
        require(offset.isFinite()) { "offset must be finite." }
        require(opacity.isFinite() && opacity in 0.0..1.0) { "opacity must be finite and between 0 and 1." }
    }
}

/**
 * Immutable ordered stops, following [OpenType color lines](https://learn.microsoft.com/en-us/typography/opentype/spec/colr#color-lines).
 *
 * Empty lines paint transparent black; one stop supplies its effective color everywhere.
 * At duplicate offsets, the first stop applies below the offset and the last at/above it.
 * A zero-span line with REPEAT or REFLECT paints nothing; PAD retains the duplicate-stop rule.
 */
public class GlyphPaintColorLine(
    /** Extension behavior outside the first and last stop. */
    public val extendMode: GlyphPaintExtendMode,
    colorStops: List<GlyphPaintColorStop>,
) {
    /** Immutable stops in stable non-decreasing offset order. */
    public val colorStops: List<GlyphPaintColorStop> = colorStops.immutableListSnapshot()

    init {
        require(this.colorStops.zipWithNext().all { (left, right) -> left.offset <= right.offset }) {
            "Color stops must be in non-decreasing offset order."
        }
    }

    /** Compares color-line values in stable stop order. */
    override fun equals(other: Any?): Boolean =
        other is GlyphPaintColorLine && extendMode == other.extendMode && colorStops == other.colorStops

    /** Returns a hash derived from the extension mode and ordered stops. */
    override fun hashCode(): Int = 31 * extendMode.hashCode() + colorStops.hashCode()

    /** Returns a diagnostic representation of this color line. */
    override fun toString(): String = "GlyphPaintColorLine(extendMode=$extendMode, colorStops=$colorStops)"
}

/**
 * One immutable node in a portable glyph paint graph.
 *
 * References are zero-based indexes in [GlyphPaintIR.nodes]. Constructors retain only immutable
 * values; [GlyphPaintIR] validates every reference and cycle before exposing the graph.
 */
public sealed interface GlyphPaintNode {
    /** Child node indexes in deterministic paint order. */
    public val children: List<Int>

    /** Paints one complete portable outline with a solid color. */
    public data class SolidOutline(
        /** Outline to paint. */
        public val outline: GlyphOutlineIR,
        /** Color applied to the outline. */
        public val color: GlyphColor,
    ) : GlyphPaintNode {
        override val children: List<Int> = emptyList()
    }

    /** Paints one resolved portable path with a solid color. */
    public data class Path(
        /** Path geometry to paint. */
        public val path: GlyphPaintPath,
        /** Color applied to the path. */
        public val color: GlyphColor,
    ) : GlyphPaintNode {
        override val children: List<Int> = emptyList()
    }

    /** Supplies an unbounded solid paint for a surrounding paint operation. */
    public data class Solid(
        /** Non-premultiplied paint color. */
        public val color: GlyphColor,
        /** Additional paint opacity in the inclusive range `0.0..1.0`. */
        public val opacity: Double,
    ) : GlyphPaintNode {
        override val children: List<Int> = emptyList()

        init {
            require(opacity.isFinite() && opacity in 0.0..1.0) { "opacity must be finite and between 0 and 1." }
        }
    }

    /** Supplies a linear gradient defined by three design-space points. */
    public data class LinearGradient(
        /** Ordered colors and extension behavior. */
        public val colorLine: GlyphPaintColorLine,
        /** Start point of the gradient axis. */
        public val p0: GlyphPaintPoint,
        /** End point of the gradient axis. */
        public val p1: GlyphPaintPoint,
        /** Point defining the color-projection direction, parallel to the line from [p0] to this point. */
        public val p2: GlyphPaintPoint,
    ) : GlyphPaintNode {
        override val children: List<Int> = emptyList()
    }

    /** Supplies a radial gradient between two circles. */
    public data class RadialGradient(
        /** Ordered colors and extension behavior. */
        public val colorLine: GlyphPaintColorLine,
        /** Center of the first circle. */
        public val c0: GlyphPaintPoint,
        /** Non-negative radius of the first circle. */
        public val radius0: Double,
        /** Center of the second circle. */
        public val c1: GlyphPaintPoint,
        /** Non-negative radius of the second circle. */
        public val radius1: Double,
    ) : GlyphPaintNode {
        override val children: List<Int> = emptyList()

        init {
            require(radius0.isFinite() && radius0 >= 0.0) { "radius0 must be finite and non-negative." }
            require(radius1.isFinite() && radius1 >= 0.0) { "radius1 must be finite and non-negative." }
        }
    }

    /**
     * Supplies an [OpenType sweep gradient](https://learn.microsoft.com/en-us/typography/opentype/spec/colr#sweep-gradients).
     *
     * Zero degrees points along positive x; positive angles turn counter-clockwise in y-up font
     * design space. Start/end angles map color-line offsets 0/1 respectively. Reversed endpoints
     * preserve clockwise color progression; consumers must not sort or swap them.
     */
    public data class SweepGradient(
        /** Ordered colors and extension behavior. */
        public val colorLine: GlyphPaintColorLine,
        /** Center of the sweep. */
        public val center: GlyphPaintPoint,
        /** Finite starting angle in degrees. */
        public val startAngleDegrees: Double,
        /** Finite ending angle in degrees. */
        public val endAngleDegrees: Double,
    ) : GlyphPaintNode {
        override val children: List<Int> = emptyList()

        init {
            require(startAngleDegrees.isFinite()) { "startAngleDegrees must be finite." }
            require(endAngleDegrees.isFinite()) { "endAngleDegrees must be finite." }
        }
    }

    /** Restricts a child paint to one portable glyph outline. */
    public data class GlyphClip(
        /** Outline used as the clip shape. */
        public val outline: GlyphOutlineIR,
        /** Child paint node index. */
        public val paint: Int,
    ) : GlyphPaintNode {
        override val children: List<Int> = listOf(paint)
    }

    /** Applies an affine transform to one child paint. */
    public data class Transform(
        /** Child paint node index. */
        public val paint: Int,
        /** Affine transform applied to the child. */
        public val matrix: GlyphAffineTransform,
    ) : GlyphPaintNode {
        override val children: List<Int> = listOf(paint)
    }

    /** Composites a source paint with a backdrop paint. */
    public data class Composite(
        /** Source paint node index. */
        public val source: Int,
        /** Backdrop paint node index. */
        public val backdrop: Int,
        /** Composition operation applied to the two paints. */
        public val mode: GlyphPaintCompositionMode,
    ) : GlyphPaintNode {
        override val children: List<Int> = listOf(backdrop, source).immutableListSnapshot()
    }

    /**
     * Groups child nodes in source order with one explicitly declared composition operation.
     * An empty group means no paint and is structurally bounded; it is valid in schema 2 only.
     */
    public class Group(
        children: List<Int>,
        /** Composition operation applied while painting the group. */
        public val compositionMode: GlyphPaintCompositionMode = GlyphPaintCompositionMode.SOURCE_OVER,
    ) : GlyphPaintNode {
        override val children: List<Int> = children.immutableListSnapshot()

        override fun equals(other: Any?): Boolean =
            other is Group && children == other.children && compositionMode == other.compositionMode

        override fun hashCode(): Int = 31 * children.hashCode() + compositionMode.hashCode()

        override fun toString(): String = "Group(children=$children, compositionMode=$compositionMode)"
    }
}

/**
 * Complete immutable portable paint graph for one glyph.
 *
 * The graph contains no SVG source, external reference, native object, or renderer state. It
 * validates node indexes and rejects reference cycles during construction, so consumers cannot
 * discover an unsupported cyclic subgraph after a provider certifies this representation.
 * Schema 1 rejects empty groups; schema 2 allows them to represent no paint.
 */
public class GlyphPaintIR(
    /** Version of the graph schema. */
    public val schemaVersion: Int,
    /** Root node index in [nodes]. */
    public val rootNode: Int,
    nodes: List<GlyphPaintNode>,
    /** Optional finite design-space bounds that clip the root result. */
    public val clipBounds: DesignBounds? = null,
) {
    /** Immutable graph nodes in stable index order. */
    public val nodes: List<GlyphPaintNode> = nodes.immutableListSnapshot()

    init {
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(this.nodes.isNotEmpty()) { "A paint graph must contain at least one node." }
        require(rootNode in this.nodes.indices) { "rootNode must identify a graph node." }
        this.nodes.forEachIndexed { index, node ->
            require(schemaVersion != 1 || node !is GlyphPaintNode.Group || node.children.isNotEmpty()) {
                "A schema 1 paint group must contain at least one child."
            }
            require(node.children.all { child -> child in this.nodes.indices }) {
                "Paint node $index references a node outside the graph."
            }
        }
        validateAcyclic()
    }

    private fun validateAcyclic() {
        val state = ByteArray(nodes.size)
        val nextChild = IntArray(nodes.size)
        val stack = ArrayDeque<Int>()

        nodes.indices.forEach { start ->
            if (state[start] != UNVISITED) return@forEach
            state[start] = VISITING
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val current = stack.last()
                val children = nodes[current].children
                val childIndex = nextChild[current]
                if (childIndex == children.size) {
                    state[current] = VISITED
                    stack.removeLast()
                    continue
                }
                nextChild[current] = childIndex + 1
                val child = children[childIndex]
                when (state[child]) {
                    UNVISITED -> {
                        state[child] = VISITING
                        stack.addLast(child)
                    }
                    VISITING -> require(false) { "Paint graph contains a reference cycle." }
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is GlyphPaintIR &&
            schemaVersion == other.schemaVersion &&
            rootNode == other.rootNode &&
            nodes == other.nodes &&
            clipBounds == other.clipBounds

    override fun hashCode(): Int {
        var result = 31 * (31 * schemaVersion + rootNode) + nodes.hashCode()
        result = 31 * result + (clipBounds?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "GlyphPaintIR(schemaVersion=$schemaVersion, rootNode=$rootNode, nodes=$nodes, clipBounds=$clipBounds)"
}

private const val UNVISITED: Byte = 0
private const val VISITING: Byte = 1
private const val VISITED: Byte = 2
