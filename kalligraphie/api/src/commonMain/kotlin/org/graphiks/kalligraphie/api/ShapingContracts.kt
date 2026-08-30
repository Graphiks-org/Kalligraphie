package org.graphiks.kalligraphie.api

import kotlin.jvm.JvmInline

/** Direction explicitly supplied to an OpenType shaping operation. */
public enum class ShapingDirection {
    /** Left-to-right shaping; the associated resolved BiDi level must be even. */
    LEFT_TO_RIGHT,

    /** Right-to-left shaping; the associated resolved BiDi level must be odd. */
    RIGHT_TO_LEFT,
}

/**
 * Opaque local token identifying a HarfBuzz cluster within one shaped run.
 *
 * Tokens are allocated by the shaping backend for one request only. Their numeric values
 * have no relation to [TextIndex], source offsets, or any private text-index representation.
 */
@JvmInline
public value class ShaperClusterToken(
    /** Non-negative local token value. */
    public val value: Int,
) {
    init {
        require(value >= 0) { "Shaper cluster tokens must be non-negative." }
    }
}

/** ISO 15924 script tag passed explicitly to an OpenType shaper. */
public class OpenTypeScript(value: String) {
    /** Four ASCII letters normalized to ISO 15924 title case. */
    public val value: String = value.canonicalScriptTag()

    override fun equals(other: Any?): Boolean = other is OpenTypeScript && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "OpenTypeScript($value)"
}

/**
 * Immutable explicit OpenType feature setting.
 *
 * The tag is normalized to lowercase ASCII. Feature selection is applied only by backends
 * that accept the setting; requests for non-deterministic features are rejected by the
 * backend before native shaping begins.
 */
public class OpenTypeFeature(
    tag: String,
    /** Unsigned OpenType feature value represented in a signed Kotlin [Int]. */
    public val value: Int,
) {
    /** Canonical lowercase four-character OpenType feature tag. */
    public val tag: String = tag.canonicalOpenTypeTag()

    override fun equals(other: Any?): Boolean = other is OpenTypeFeature && tag == other.tag && value == other.value

    override fun hashCode(): Int = 31 * tag.hashCode() + value

    override fun toString(): String = "OpenTypeFeature(tag=$tag, value=$value)"
}

/** Immutable identity of a shaping implementation and its pinned native dependency. */
public data class ShapingBackendIdentity(
    /** Stable backend implementation identifier. */
    public val backendId: String,
    /** Native shaping-engine version reported by the loaded library. */
    public val nativeVersion: String,
    /** Immutable source revision embedded in the selected native artifact. */
    public val nativeSourceRevision: String,
    /**
     * Pinned Maven coordinate, classifier, and embedded-library path selected at runtime.
     *
     * This identifies the exact checked-in library resource, rather than a library found
     * through a platform search path.
     */
    public val nativeArtifactId: String,
    /** Lowercase SHA-256 digest of the library resource identified by [nativeArtifactId]. */
    public val nativeArtifactSha256: String,
    /** Versioned fingerprint of the backend's fixed shaping configuration. */
    public val configurationFingerprint: String,
) {
    init {
        require(backendId.isNotBlank()) { "Backend identifier must not be blank." }
        require(nativeVersion.isNotBlank()) { "Native version must not be blank." }
        require(nativeSourceRevision.isNotBlank()) { "Native source revision must not be blank." }
        require(nativeArtifactId.isNotBlank()) { "Native artifact identifier must not be blank." }
        require(nativeArtifactSha256.matches(SHA256_HEX)) { "Native artifact SHA-256 must be a lowercase hexadecimal digest." }
        require(configurationFingerprint.isNotBlank()) { "Configuration fingerprint must not be blank." }
    }
}

/**
 * Fully explicit, immutable input to one relative shaping operation.
 *
 * The snapshot and every range must share one [TextVersion]. Direction, script, language,
 * resolved BiDi level, boundary flags, and features are never inferred by this contract.
 * Collections are captured immutably, so requests may be shared between threads when their
 * [font] implementation supports concurrent reads.
 */
public class ShapingRequest(
    /** Immutable canonical text snapshot containing [range]. */
    public val snapshot: TextSnapshot,
    /** Half-open scalar range to shape. */
    public val range: TextRange,
    /** Concrete font instance supplying owned OpenType data to a backend. */
    public val font: FontInstance,
    /** Explicit shaping direction compatible with [bidiLevel]. */
    public val direction: ShapingDirection,
    /** Explicit ISO 15924 script. */
    public val script: OpenTypeScript,
    /**
     * Explicit language tag forwarded to the shaping engine.
     *
     * commonMain checks only basic tag syntax: non-empty alphanumeric subtags separated by
     * single hyphens. It neither applies the BCP 47 registry nor canonicalizes casing,
     * aliases, or extensions.
     */
    public val language: String,
    /** Resolved UAX #9 embedding level, from 0 through 126. */
    public val bidiLevel: Int,
    /** Whether the range begins the text context supplied to the shaper. */
    public val bot: Boolean,
    /** Whether the range ends the text context supplied to the shaper. */
    public val eot: Boolean,
    features: List<OpenTypeFeature>,
    graphemeClusters: List<TextRange>,
) {
    /** Immutable feature settings in caller-specified deterministic order. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    /** Immutable extended-grapheme partition of [range] in logical order. */
    public val graphemeClusters: List<TextRange> = graphemeClusters.immutableListSnapshot()

    init {
        require(snapshot.contains(range)) { "Shaping range must belong to the supplied snapshot." }
        require(language.hasBasicLanguageTagSyntax()) { "Language must use non-empty alphanumeric subtags separated by single hyphens." }
        require(bidiLevel in 0..126) { "BiDi level must be between 0 and 126." }
        require(direction.matches(bidiLevel)) { "Shaping direction must agree with the resolved BiDi level." }
        require(features.map(OpenTypeFeature::tag).distinct().size == features.size) {
            "Shaping features must not repeat a tag."
        }
        requireTextPartition(range, this.graphemeClusters, "Grapheme clusters")
    }
}

/** Safety information reported by a shaping engine for a produced glyph. */
public data class ShapingSafetyFlags(
    /** A break adjacent to this glyph is unsafe for shaping continuity. */
    public val unsafeToBreak: Boolean,
    /** Concatenating this glyph's context can change shaping. */
    public val unsafeToConcat: Boolean,
)

/**
 * Relative glyph output from one shaping operation.
 *
 * Advances and offsets are portable layout units relative to the run origin; no final line
 * coordinate is present. A glyph can relate to several local [clusterTokens], permitting
 * a many-to-many projection through the run's cluster mapping.
 */
public class ShapedGlyph(
    /** Glyph selected by the shaping engine. */
    public val glyphId: GlyphId,
    /** Signed horizontal advance relative to the run origin. */
    public val xAdvance: LayoutUnit,
    /** Signed vertical advance relative to the run origin. */
    public val yAdvance: LayoutUnit,
    /** Signed horizontal placement offset relative to the glyph advance. */
    public val xOffset: LayoutUnit,
    /** Signed vertical placement offset relative to the glyph advance. */
    public val yOffset: LayoutUnit,
    /** Engine safety flags associated with this glyph. */
    public val safetyFlags: ShapingSafetyFlags,
    clusterTokens: List<ShaperClusterToken>,
) {
    /** Immutable local cluster tokens related to this glyph. */
    public val clusterTokens: List<ShaperClusterToken> = clusterTokens.immutableListSnapshot()

    /**
     * Sole cluster token for HarfBuzz monotone-character output.
     *
     * This convenience accessor throws if a future backend exposes a true multi-cluster
     * glyph; callers requiring the general relation use [clusterTokens].
     */
    public val clusterToken: ShaperClusterToken
        get() = clusterTokens.single()

    init {
        require(this.clusterTokens.isNotEmpty()) { "Every shaped glyph must relate to a cluster." }
    }
}

/**
 * One local shaping cluster and the complete source span that produced it.
 *
 * [sourceRange] may cover multiple Unicode scalars for a ligature and one scalar may map to
 * several glyphs. [scalarRanges] always retains the scalar mapping independently of
 * [admissibleGraphemeBoundaries]. A HarfBuzz cluster is not presumed to be a grapheme:
 * a cluster covering only part of a combining sequence can expose zero or one admissible
 * grapheme boundary.
 */
public class ShaperCluster(
    /** Local token allocated for one shaping request. */
    public val token: ShaperClusterToken,
    /** Complete half-open source range contributing to this cluster. */
    public val sourceRange: TextRange,
    scalarRanges: List<TextRange>,
    admissibleGraphemeBoundaries: List<TextIndex>,
) {
    /** Immutable logical partition of [sourceRange] into contributing scalar ranges. */
    public val scalarRanges: List<TextRange> = scalarRanges.immutableListSnapshot()

    /**
     * Grapheme boundaries from the request partition that lie in [sourceRange].
     *
     * This list can be empty when the shaping cluster covers only a fragment of an extended
     * grapheme cluster; it never manufactures scalar boundaries as grapheme boundaries.
     */
    public val admissibleGraphemeBoundaries: List<TextIndex> = admissibleGraphemeBoundaries.immutableListSnapshot()

    init {
        requireTextPartition(sourceRange, this.scalarRanges, "Cluster scalar ranges")
        require(this.admissibleGraphemeBoundaries.all { boundary ->
            boundary.sharesVersionWith(sourceRange.start) &&
                boundary.compareTo(sourceRange.start) >= 0 &&
                boundary.compareTo(sourceRange.endExclusive) <= 0
        }) { "Admissible grapheme boundaries must lie within the cluster source range." }
        require(this.admissibleGraphemeBoundaries.zipWithNext().all { (left, right) -> left.compareTo(right) < 0 }) {
            "Admissible grapheme boundaries must be strictly increasing."
        }
    }
}

/** Availability state of font-provided GDEF ligature caret positions. */
public enum class GdefLigatureCaretState {
    /** The font provided no caret positions for the ligature glyph. */
    ABSENT,

    /** The font provided a complete, valid set of caret positions. */
    AVAILABLE,

    /** The font provided positions that do not match the required internal boundaries. */
    INCONSISTENT,
}

/**
 * GDEF ligature-caret fact associated with one glyph in a shaped run.
 *
 * Positions are relative layout-unit offsets in logical source-boundary order. They are
 * immutable snapshots. Consumers must use their documented deterministic fallback when
 * [state] is not [GdefLigatureCaretState.AVAILABLE].
 */
public class GdefLigatureCaretFact(
    /** Zero-based glyph index in the enclosing [ShapedGlyphRun]. */
    public val glyphIndex: Int,
    /** Audited availability of font-provided caret data. */
    public val state: GdefLigatureCaretState,
    positions: List<LayoutUnit> = emptyList(),
) {
    /** Immutable positions supplied by GDEF when they are complete and valid. */
    public val positions: List<LayoutUnit> = positions.immutableListSnapshot()

    init {
        require(glyphIndex >= 0) { "Ligature caret glyph index must be non-negative." }
        require(state != GdefLigatureCaretState.AVAILABLE || this.positions.isNotEmpty()) {
            "Available GDEF caret data must contain at least one position."
        }
        require(state == GdefLigatureCaretState.AVAILABLE || this.positions.isEmpty()) {
            "Unavailable or inconsistent GDEF caret data must not publish positions."
        }
    }
}

/**
 * Queryable many-to-many projections between source ranges, local clusters, and glyphs.
 *
 * Instances are derived from an immutable [ShapedGlyphRun] and are safe to share between
 * threads. Query ranges must be bound to the same text version; violations are programming
 * errors and fail deterministically instead of being coerced to another text revision.
 */
public class ShapingMappings internal constructor(
    private val range: TextRange,
    private val clusters: List<ShaperCluster>,
    private val glyphs: List<ShapedGlyph>,
) {
    /** Returns local clusters whose source spans overlap [sourceRange], in logical order. */
    public fun clustersForSource(sourceRange: TextRange): List<ShaperClusterToken> {
        requireSameTextVersion(sourceRange, range)
        return clusters
            .filter { cluster -> rangesOverlap(sourceRange, cluster.sourceRange) }
            .map(ShaperCluster::token)
            .immutableListSnapshot()
    }

    /** Returns one scalar source range per scalar contributing to [token], in logical order. */
    public fun sourcesForCluster(token: ShaperClusterToken): List<TextRange> {
        val cluster = clusterFor(token)
        return cluster.scalarRanges
    }

    /** Returns glyph indexes related to [token], in produced glyph order. */
    public fun glyphsForCluster(token: ShaperClusterToken): List<Int> =
        glyphs.indices.filter { index -> token in glyphs[index].clusterTokens }.immutableListSnapshot()

    /** Returns local clusters related to a produced glyph index, in its declared order. */
    public fun clustersForGlyph(glyphIndex: Int): List<ShaperClusterToken> {
        require(glyphIndex in glyphs.indices) { "Glyph index lies outside the shaped run." }
        return glyphs[glyphIndex].clusterTokens
    }

    private fun clusterFor(token: ShaperClusterToken): ShaperCluster =
        clusters.firstOrNull { it.token == token }
            ?: throw IllegalArgumentException("Cluster token does not belong to the shaped run.")
}

/**
 * Immutable, relative result of one explicit shaping operation.
 *
 * The run has no final line coordinates and can therefore be positioned only by a later
 * layout layer. Its clusters partition [range], preserve source-to-cluster-to-glyph
 * relations, retain the request's true grapheme partition, and carry no native resource; it
 * is safe to share across threads indefinitely.
 */
public class ShapedGlyphRun(
    /** Source range shaped by this run. */
    public val range: TextRange,
    /** Exact font instance identity used for shaping. */
    public val fontInstanceKey: FontInstanceKey,
    /** Pinned backend identity that produced this immutable run. */
    public val backendIdentity: ShapingBackendIdentity,
    /** Explicit direction used by the backend. */
    public val direction: ShapingDirection,
    /** Explicit ISO 15924 script used by the backend. */
    public val script: OpenTypeScript,
    /** Explicit language tag passed to the backend without common canonicalization. */
    public val language: String,
    /** Resolved UAX #9 level used by the backend. */
    public val bidiLevel: Int,
    /** Whether the shaped context began at the supplied text boundary. */
    public val bot: Boolean,
    /** Whether the shaped context ended at the supplied text boundary. */
    public val eot: Boolean,
    features: List<OpenTypeFeature>,
    graphemeClusters: List<TextRange>,
    glyphs: List<ShapedGlyph>,
    clusters: List<ShaperCluster>,
    ligatureCaretFacts: List<GdefLigatureCaretFact> = emptyList(),
) {
    /** Immutable deterministic feature settings used by the backend. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    /** Immutable true extended-grapheme partition supplied with the request. */
    public val graphemeClusters: List<TextRange> = graphemeClusters.immutableListSnapshot()

    /** Immutable glyphs in shaping-engine output order. */
    public val glyphs: List<ShapedGlyph> = glyphs.immutableListSnapshot()

    /** Immutable clusters in logical source order. */
    public val clusters: List<ShaperCluster> = clusters.immutableListSnapshot()

    /** Immutable GDEF caret facts for ligature glyphs where inspection was required. */
    public val ligatureCaretFacts: List<GdefLigatureCaretFact> = ligatureCaretFacts.immutableListSnapshot()

    /** Immutable query facade for text, cluster, and glyph relationships. */
    public val mappings: ShapingMappings = ShapingMappings(range, this.clusters, this.glyphs)

    init {
        require(bidiLevel in 0..126) { "BiDi level must be between 0 and 126." }
        require(direction.matches(bidiLevel)) { "Shaped run direction must agree with its BiDi level." }
        require(language.hasBasicLanguageTagSyntax()) { "Shaped run language has invalid basic tag syntax." }
        require(this.features.map(OpenTypeFeature::tag).distinct().size == this.features.size) {
            "Shaped run features must not repeat a tag."
        }
        requireTextPartition(range, this.graphemeClusters, "Run grapheme clusters")
        requireTextPartition(range, this.clusters.map(ShaperCluster::sourceRange), "Shaping clusters")
        val graphemeBoundaries = this.graphemeClusters.flatMap { cluster -> listOf(cluster.start, cluster.endExclusive) }.toSet()
        require(this.clusters.all { cluster -> cluster.admissibleGraphemeBoundaries.all(graphemeBoundaries::contains) }) {
            "Cluster grapheme boundaries must come from the run grapheme partition."
        }
        val definedTokens = this.clusters.map(ShaperCluster::token).toSet()
        require(this.glyphs.all { glyph -> glyph.clusterTokens.all(definedTokens::contains) }) {
            "Every glyph cluster token must be declared by the shaped run."
        }
        require(this.ligatureCaretFacts.map(GdefLigatureCaretFact::glyphIndex).all(this.glyphs.indices::contains)) {
            "Ligature caret facts must identify glyphs in the shaped run."
        }
    }
}

/** Portable backend boundary for explicit OpenType shaping. */
public interface ShapingBackend {
    /** Pinned identity and configuration of this backend. */
    public val identity: ShapingBackendIdentity

    /**
     * Shapes [request] into relative glyph output without assigning line coordinates.
     *
     * Backends return typed failures for unavailable font data, unsupported platform
     * capability, rejected non-deterministic features, and native shaping failures. The
     * operation retains no caller-owned resources and is safe for concurrent calls when the
     * backend's identity is successfully opened.
     */
    public fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun>
}

private fun String.canonicalScriptTag(): String {
    require(length == 4 && all { character -> character in 'A'..'Z' || character in 'a'..'z' }) {
        "ISO 15924 scripts must contain four ASCII letters."
    }
    return buildString(4) {
        append(this@canonicalScriptTag[0].uppercaseChar())
        append(this@canonicalScriptTag.substring(1).lowercase())
    }
}

private fun String.canonicalOpenTypeTag(): String {
    require(length == 4 && all { it.code in 0x20..0x7E }) { "OpenType feature tags must contain four printable ASCII characters." }
    return lowercase()
}

private fun String.hasBasicLanguageTagSyntax(): Boolean =
    isNotBlank() &&
        all { character -> character.isLetterOrDigit() || character == '-' } &&
        !startsWith('-') &&
        !endsWith('-') &&
        !contains("--")

private fun ShapingDirection.matches(level: Int): Boolean =
    when (this) {
        ShapingDirection.LEFT_TO_RIGHT -> level % 2 == 0
        ShapingDirection.RIGHT_TO_LEFT -> level % 2 != 0
    }

private fun requireTextPartition(owner: TextRange, ranges: List<TextRange>, label: String) {
    if (owner.start == owner.endExclusive) {
        require(ranges.isEmpty()) { "$label must be empty for an empty range." }
        return
    }
    require(ranges.isNotEmpty()) { "$label must cover the complete range." }
    var expectedStart = owner.start
    ranges.forEach { item ->
        require(item.start != item.endExclusive) { "$label must not contain empty ranges." }
        requireSameTextVersion(item, owner)
        require(item.start == expectedStart) { "$label must be contiguous and ordered." }
        require(item.endExclusive.compareTo(owner.endExclusive) <= 0) { "$label must stay within the owner range." }
        expectedStart = item.endExclusive
    }
    require(expectedStart == owner.endExclusive) { "$label must cover the complete range." }
}

private fun requireSameTextVersion(first: TextRange, second: TextRange) {
    require(first.start.sharesVersionWith(second.start)) { "Text ranges must belong to the same version." }
}

private fun rangesOverlap(first: TextRange, second: TextRange): Boolean =
    first.start.compareTo(second.endExclusive) < 0 && second.start.compareTo(first.endExclusive) < 0

private val SHA256_HEX: Regex = Regex("[0-9a-f]{64}")
