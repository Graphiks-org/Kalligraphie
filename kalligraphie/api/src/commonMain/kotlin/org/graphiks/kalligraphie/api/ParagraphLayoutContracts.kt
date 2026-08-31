package org.graphiks.kalligraphie.api

/**
 * Physical constraints for composing one horizontal paragraph in a rectangular region.
 *
 * Coordinates use the portable `x`-right, `y`-down paragraph space. [region] must have
 * strictly positive width and height, and [lineMetrics] fixes the compatible line-box rhythm.
 * This immutable value owns no renderer or platform resource and is safe to share concurrently.
 */
public class HorizontalParagraphConstraints(
    /** Finite, non-empty physical region available to the paragraph. */
    public val region: LayoutRect,
    /** Explicit vertical metrics used for every line box in this region. */
    public val lineMetrics: LineVerticalMetrics,
) {
    /** Exact physical width used when validating a continuation. */
    public val width: LayoutUnit = LayoutUnit(region.right.value - region.left.value)

    /** Exact physical height available for complete line boxes. */
    public val height: LayoutUnit = LayoutUnit(region.bottom.value - region.top.value)

    init {
        require(region.left < region.right) { "A horizontal paragraph region must have positive width." }
        require(region.top < region.bottom) { "A horizontal paragraph region must have positive height." }
    }

    /** Compares the physical region and line rhythm. */
    override fun equals(other: Any?): Boolean =
        other is HorizontalParagraphConstraints && region == other.region && lineMetrics == other.lineMetrics

    /** Returns a stable hash of the physical region and line rhythm. */
    override fun hashCode(): Int = 31 * region.hashCode() + lineMetrics.hashCode()

    /** Returns a diagnostic form containing the physical region and line rhythm. */
    override fun toString(): String = "HorizontalParagraphConstraints(region=$region, lineMetrics=$lineMetrics)"
}

/** Policy applied when complete source coverage does not fit in the supplied region. */
public enum class OverflowPolicy {
    /** Publish complete lines only and return an exact immutable continuation for the remainder. */
    CONTINUE,
}

/**
 * Typographic extents derived from the actual content of one final line.
 *
 * These distances are independent of the composition [LineLayout.lineBox] and glyph
 * [LineLayout.designInkBounds]. They contain no device rounding or rasterization state.
 */
public data class LineContentMetrics(
    /** Non-negative content extent above the final baseline. */
    public val ascent: LayoutUnit,
    /** Non-negative content extent below the final baseline. */
    public val descent: LayoutUnit,
    /** Non-negative physical inline advance occupied by final positioned content. */
    public val inlineAdvance: LayoutUnit,
) {
    init {
        require(ascent.value >= 0f) { "Content ascent must be non-negative." }
        require(descent.value >= 0f) { "Content descent must be non-negative." }
        require(inlineAdvance.value >= 0f) { "Content inline advance must be non-negative." }
    }
}

/**
 * One complete final line projected into paragraph coordinates.
 *
 * [line] supplies immutable line-local glyphs and carets relative to baseline `(0, 0)`.
 * Construction snapshots and translates those values by [baseline]; the published glyph
 * origins and caret segments are therefore unambiguous physical paragraph coordinates.
 * [contentMetrics], [lineBox], and [designInkBounds] remain deliberately distinct. The value
 * retains no font handle, renderer object, platform object, or mutable caller collection and
 * can be shared across threads with its source snapshot revision.
 *
 * Contract violations, non-finite translated coordinates, or a line box inconsistent with the
 * line's vertical metrics are programming errors reported by [IllegalArgumentException].
 */
public class LineLayout(
    line: EditableLine,
    /** Absolute baseline origin in physical paragraph coordinates. */
    public val baseline: LayoutPoint,
    /** Metrics derived from actual final typographic content. */
    public val contentMetrics: LineContentMetrics,
    /** Complete composition and hit-testing box in paragraph coordinates. */
    public val lineBox: LayoutRect,
    /** Union of final glyph design bounds in paragraph coordinates. */
    public val designInkBounds: LayoutBounds,
) {
    /** Complete snapshot-bound half-open source range covered by this final line. */
    public val range: TextRange = line.range

    /** Explicit direction used to classify the line's final BiDi carets. */
    public val baseDirection: ShapingDirection = line.baseDirection

    /** Line-box metrics used to produce the final physical line geometry. */
    public val verticalMetrics: LineVerticalMetrics = line.verticalMetrics

    /** Final runs in physical visual order, with every glyph origin in paragraph coordinates. */
    public val positionedGlyphRuns: List<PositionedGlyphRun> = line.positionedGlyphRuns
        .map { run -> run.translatedBy(baseline) }
        .immutableListSnapshot()

    /** Final caret candidates in visual order, with every segment in paragraph coordinates. */
    public val allCaretCandidates: List<CaretCandidate> = line.allCaretCandidates
        .map { candidate -> candidate.translatedBy(baseline) }
        .immutableListSnapshot()

    /** Immutable recoverable diagnostics produced while finalizing the line. */
    public val diagnostics: List<EditableLineDiagnostic> = line.diagnostics.immutableListSnapshot()

    init {
        require(lineBox.left < lineBox.right && lineBox.top < lineBox.bottom) {
            "A final line box must have positive width and height."
        }
        require(lineBox.left == baseline.x) {
            "A final line baseline origin must begin at the physical left edge of its line box."
        }
        require(lineBox.top == LayoutUnit(baseline.y.value - verticalMetrics.ascent.value)) {
            "A final line box top must equal its baseline minus line ascent."
        }
        require(lineBox.bottom == LayoutUnit(baseline.y.value + verticalMetrics.descent.value)) {
            "A final line box bottom must equal its baseline plus line descent."
        }
        require(designInkBounds.minX <= designInkBounds.maxX && designInkBounds.minY <= designInkBounds.maxY) {
            "Final design ink bounds must be ordered in paragraph coordinates."
        }
    }
}

/**
 * Immutable base contract for a complete set of paragraph lines and editing operations.
 *
 * The constructor defensively captures [lines], verifies that they form the complete ordered
 * partition of [range], and binds the result to [snapshot]'s [TextVersion]. Implementations of
 * the abstract editorial operations must use only the published final candidates and visual-run
 * rectangles, retain no borrowed native or renderer resource, and be safe for concurrent reads.
 */
public abstract class ParagraphLayout protected constructor(
    snapshot: TextSnapshot,
    /** Complete half-open source range covered by [lines]. */
    public val range: TextRange,
    lines: List<LineLayout>,
) {
    /** Exact immutable text revision to which every line and caret belongs. */
    public val version: TextVersion = snapshot.version

    /** Complete final lines in physical top-to-bottom order. */
    public val lines: List<LineLayout> = lines.immutableListSnapshot()

    init {
        require(snapshot.contains(range)) { "A paragraph layout range must belong to its source snapshot." }
        requireCompleteLinePartition(range, this.lines)
        require(this.lines.zipWithNext().all { (first, second) -> first.lineBox.bottom <= second.lineBox.top }) {
            "Paragraph lines must be published in non-overlapping physical top-to-bottom order."
        }
    }

    /**
     * Moves from [position] to the next editable boundary in logical scalar order.
     *
     * The current position must belong to this exact layout revision. Movement crosses line
     * boundaries and returns `null` only at the requested paragraph edge.
     */
    public abstract fun nextLogical(
        position: CaretPosition,
        direction: LogicalNavigationDirection,
    ): CaretPosition?

    /**
     * Moves from an actual published [candidate] in physical visual traversal order.
     *
     * Implementations reject reconstructed or foreign candidates even when their logical
     * position compares equal, preventing ambiguous BiDi geometry from crossing layouts.
     */
    public abstract fun nextVisual(
        candidate: CaretCandidate,
        direction: VisualNavigationDirection,
    ): CaretCandidate?

    /**
     * Returns every final concrete candidate for [position] in deterministic visual order.
     *
     * The returned list is an immutable snapshot and may contain multiple candidates at an
     * ambiguous BiDi boundary.
     */
    public abstract fun caretCandidates(position: CaretPosition): List<CaretCandidate>

    /**
     * Returns only non-empty visual-run rectangles between [anchor] and [focus].
     *
     * Geometry is in paragraph coordinates and never fills gaps between disjoint BiDi segments
     * or consults glyph ink, a renderer, device pixels, or platform state. Results are ordered by
     * physical line from top to bottom, then by [PositionedGlyphRun.visualOrder] within that line;
     * reversing anchor and focus does not reverse this geometry order.
     */
    public fun selectionGeometry(anchor: CaretPosition, focus: CaretPosition): List<LayoutRect> {
        require(lines.any { line -> line.allCaretCandidates.any { candidate -> candidate.position == anchor } }) {
            "Selection anchor must be a paragraph-local caret position."
        }
        require(lines.any { line -> line.allCaretCandidates.any { candidate -> candidate.position == focus } }) {
            "Selection focus must be a paragraph-local caret position."
        }
        if (anchor.index == focus.index) return emptyList()
        val selectedStart = if (anchor.index < focus.index) anchor.index else focus.index
        val selectedEnd = if (anchor.index < focus.index) focus.index else anchor.index
        return lines.flatMap { line ->
            line.positionedGlyphRuns.mapNotNull { run ->
                val runStart = maxParagraphIndex(selectedStart, run.sourceRun.range.start)
                val runEnd = minParagraphIndex(selectedEnd, run.sourceRun.range.endExclusive)
                if (runStart >= runEnd) return@mapNotNull null
                val caretCoordinates = line.allCaretCandidates.asSequence()
                    .filter { candidate ->
                        candidate.visualRunOrder == run.visualOrder &&
                            candidate.position.index >= runStart &&
                            candidate.position.index <= runEnd
                    }
                    .map { candidate -> candidate.geometry.start.x }
                    .toList()
                val coordinates = if (caretCoordinates.size >= 2) {
                    caretCoordinates
                } else {
                    caretCoordinates + run.glyphs.flatMap { glyph ->
                        listOf(glyph.origin.x, LayoutUnit(glyph.origin.x.value + glyph.advance.x.value))
                    }
                }
                val left = coordinates.minOrNull() ?: return@mapNotNull null
                val right = coordinates.maxOrNull() ?: return@mapNotNull null
                if (left == right) null else LayoutRect(left, line.lineBox.top, right, line.lineBox.bottom)
            }
        }.immutableListSnapshot()
    }

    /**
     * Maps a physical paragraph [point] to one deterministic final caret candidate.
     *
     * Line selection minimizes vertical distance to each closed line-box span; equal distance
     * selects the earlier physical line. Consequently a point above or below selects the first
     * or last line, and a point exactly midway between lines selects the preceding line. Within
     * that line candidates are ordered by squared distance to their vertical segment, then
     * [CaretCandidate.visualOrder], logical [TextIndex], and [CaretAffinity.DOWNSTREAM] before
     * [CaretAffinity.UPSTREAM].
     */
    public fun hitTest(point: LayoutPoint): CaretCandidate {
        require(lines.isNotEmpty()) { "Hit testing requires a paragraph with a final line." }
        val line = lines.withIndex().minWith { left, right ->
            val distance = verticalDistanceToRect(point, left.value.lineBox)
                .compareTo(verticalDistanceToRect(point, right.value.lineBox))
            if (distance != 0) distance else left.index.compareTo(right.index)
        }.value
        return line.allCaretCandidates.minWith { left, right ->
            val distance = paragraphSquaredDistanceToSegment(point, left.geometry)
                .compareTo(paragraphSquaredDistanceToSegment(point, right.geometry))
            if (distance != 0) return@minWith distance
            val visual = left.visualOrder.compareTo(right.visualOrder)
            if (visual != 0) return@minWith visual
            val index = left.position.index.compareTo(right.position.index)
            if (index != 0) return@minWith index
            paragraphAffinityRank(left.position.affinity).compareTo(paragraphAffinityRank(right.position.affinity))
        }
    }
}

/** Whether a successful result covers all requested source or leaves an exact remainder. */
public enum class CoverageStatus {
    /** Every requested source boundary is represented by complete final lines. */
    COMPLETE,

    /** Only a complete prefix is published and a compatible continuation owns the remainder. */
    PARTIAL,
}

/**
 * Resource-free identity of the line materialization mode relevant to continuation replay.
 *
 * Unlike [EditableLineMaterialization], this value never retains a borrowed resolver handle.
 */
public sealed interface ParagraphMaterializationIdentity {
    /** Layout geometry is produced without synchronously validating outline materialization. */
    public data object LayoutOnly : ParagraphMaterializationIdentity

    /** Exact render variant and outline profile required for synchronous final validation. */
    public data class Renderable(
        /** Render variant that must be replayed. */
        public val variant: FontRenderVariantKey,
        /** Outline constraints that must be replayed. */
        public val outlineProfile: OutlineProfile,
    ) : ParagraphMaterializationIdentity

    /** Factories that discard borrowed operational capability after capturing immutable identity. */
    public companion object {
        /**
         * Captures only the configuration identity of [materialization].
         *
         * A renderable resolver is inspected neither here nor later retained by the returned
         * value; the live capability must be supplied separately to [ParagraphLayouter.layout].
         */
        public fun from(materialization: EditableLineMaterialization): ParagraphMaterializationIdentity =
            materialization.toParagraphIdentity()
    }
}

/**
 * Immutable capability for resuming an incompletely covered paragraph request.
 *
 * The continuation records the original [TextVersion], exact [originalSourceRange] and
 * [remainingSourceRange], compatible rectangle width and line metrics, and every configuration
 * identity that can affect observable line breaking or final glyph geometry. The remainder is an
 * exact suffix of [originalSourceRange]; it is empty only when a required terminal empty physical
 * line remains. A partial layout prefix plus this value partitions the complete source requested
 * by the call that created it. It stores no text
 * history, incremental-edit state, borrowed resolver, native handle, renderer, or platform
 * object. Collections are defensively captured, making this value safe for concurrent reads.
 *
 * Create continuations only with [create]; a resumed [ParagraphLayoutRequest] rejects any
 * incompatible version, remaining range, geometry, Unicode data, font policy, shaping backend,
 * feature set, or materialization identity.
 */
public class LayoutContinuation private constructor(
    /** Original immutable source revision. */
    public val originalVersion: TextVersion,
    /** Complete source range requested by the call that produced this continuation. */
    public val originalSourceRange: TextRange,
    /** Exact unconsumed suffix, including its original end boundary. */
    public val remainingSourceRange: TextRange,
    /** Exact rectangle width required by a compatible resumed request. */
    public val regionWidth: LayoutUnit,
    /** Exact line rhythm required by a compatible resumed request. */
    public val lineMetrics: LineVerticalMetrics,
    /** Explicit paragraph direction that produced the covered prefix. */
    public val baseDirection: BaseDirection,
    /** Explicit language used for analysis and shaping. */
    public val language: String,
    /** Unicode data release used for segmentation, BiDi, and line breaking. */
    public val unicodeData: UnicodeDataIdentity,
    /** Immutable font catalogue generation used for fallback. */
    public val fontCatalogGeneration: FontCatalogGeneration,
    /** Stable font-resolution policy family. */
    public val resolutionPolicyId: String,
    /** Exact font-resolution policy version. */
    public val resolutionPolicyVersion: String,
    /** Font instance geometry applied to selected faces. */
    public val fontInstanceDescriptor: FontInstanceDescriptor,
    /** Pinned shaping backend and configuration identity. */
    public val shapingBackendIdentity: ShapingBackendIdentity,
    /** Baseline OpenType feature policy used for shaping. */
    public val featurePolicy: ShapingFeaturePolicy,
    features: List<OpenTypeFeature>,
    /** Resource-free identity of the requested publication mode. */
    public val materializationIdentity: ParagraphMaterializationIdentity,
    /** Overflow behavior whose remainder this value represents. */
    public val overflowPolicy: OverflowPolicy,
) {
    /** Immutable deterministic OpenType feature overrides required for replay. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    /** Returns whether [request] can consume this continuation without changing observable layout. */
    public fun isCompatibleWith(request: ParagraphLayoutRequest): Boolean =
        request.snapshot.version == originalVersion &&
            request.sourceRange == remainingSourceRange &&
            request.constraints.width == regionWidth &&
            request.constraints.lineMetrics == lineMetrics &&
            request.baseDirection == baseDirection &&
            request.language == language &&
            request.lineBreakAnalysis.unicodeData == unicodeData &&
            request.fontCatalog.generation == fontCatalogGeneration &&
            request.resolutionPolicy.policyId == resolutionPolicyId &&
            request.resolutionPolicy.version == resolutionPolicyVersion &&
            request.fontInstanceDescriptor == fontInstanceDescriptor &&
            request.shapingBackend.identity == shapingBackendIdentity &&
            request.featurePolicy == featurePolicy &&
            request.features == features &&
            request.materializationIdentity == materializationIdentity &&
            request.overflowPolicy == overflowPolicy

    /** Factories that capture compatibility inputs from validated paragraph requests. */
    public companion object {
        /**
         * Captures an exact unconsumed suffix of [request].
         *
         * [remainingSourceRange] must be a suffix of the current request range and may equal the
         * full range when the region cannot publish even one complete line. An empty remainder is
         * valid only to resume the required terminal physical empty line of an empty paragraph or
         * a source range ending at a mandatory line-break boundary.
         */
        public fun create(
            request: ParagraphLayoutRequest,
            remainingSourceRange: TextRange,
        ): LayoutContinuation {
            require(remainingSourceRange.start.sharesVersionWith(request.sourceRange.start)) {
                "A continuation remainder must use the request text version."
            }
            require(
                remainingSourceRange.start >= request.sourceRange.start &&
                    remainingSourceRange.endExclusive == request.sourceRange.endExclusive,
            ) {
                "A continuation remainder must be an exact suffix of the request source range."
            }
            val terminalEmptyLineRequired = request.sourceRange.start == request.sourceRange.endExclusive ||
                request.lineBreakAnalysis.opportunities.any { opportunity ->
                    opportunity.boundary == request.sourceRange.endExclusive && opportunity.kind == LineBreakKind.MANDATORY
                }
            require(remainingSourceRange.start < remainingSourceRange.endExclusive || terminalEmptyLineRequired) {
                "An empty continuation remainder requires a terminal physical empty line."
            }
            return LayoutContinuation(
                originalVersion = request.snapshot.version,
                originalSourceRange = request.sourceRange,
                remainingSourceRange = remainingSourceRange,
                regionWidth = request.constraints.width,
                lineMetrics = request.constraints.lineMetrics,
                baseDirection = request.baseDirection,
                language = request.language,
                unicodeData = request.lineBreakAnalysis.unicodeData,
                fontCatalogGeneration = request.fontCatalog.generation,
                resolutionPolicyId = request.resolutionPolicy.policyId,
                resolutionPolicyVersion = request.resolutionPolicy.version,
                fontInstanceDescriptor = request.fontInstanceDescriptor,
                shapingBackendIdentity = request.shapingBackend.identity,
                featurePolicy = request.featurePolicy,
                features = request.features,
                materializationIdentity = request.materializationIdentity,
                overflowPolicy = request.overflowPolicy,
            )
        }
    }
}

/**
 * Complete immutable input to pure horizontal paragraph composition.
 *
 * The request binds [sourceRange] to [snapshot], requires complete Unicode and line-break
 * analyses for that same revision, and captures all font, shaping, feature, materialization,
 * cancellation, and geometry inputs required for deterministic replay. The shaping backend and
 * backend is borrowed for composition. A renderable resolver is deliberately absent and is
 * supplied only to the synchronous [ParagraphLayouter.layout] call. All caller collections are
 * defensively copied. Invalid ranges or mismatched identities are programming errors reported
 * during construction.
 */
public class ParagraphLayoutRequest(
    /** Immutable canonical source revision. */
    public val snapshot: TextSnapshot,
    /** Half-open source range to cover, or the exact remainder of [continuation]. */
    public val sourceRange: TextRange = snapshot.range,
    /** Complete Unicode analysis reusable across line-finalization attempts. */
    public val unicodeAnalysis: UnicodeAnalysis,
    /** Complete legal line-break analysis tied to [unicodeAnalysis]. */
    public val lineBreakAnalysis: LineBreakAnalysis,
    /** Physical rectangular region and compatible line rhythm. */
    public val constraints: HorizontalParagraphConstraints,
    /** Explicit base direction; it is never inferred from source text. */
    public val baseDirection: BaseDirection,
    /** Explicit language used by Unicode analysis and shaping. */
    public val language: String,
    /** Versioned baseline feature behavior required from [shapingBackend]. */
    public val featurePolicy: ShapingFeaturePolicy,
    features: List<OpenTypeFeature> = emptyList(),
    /** Immutable catalogue generation used for deterministic fallback. */
    public val fontCatalog: FontCatalogSnapshot,
    /** Immutable total-order fallback policy for [fontCatalog]. */
    public val resolutionPolicy: FontResolutionPolicySnapshot,
    /** Geometric parameters applied to every selected font face. */
    public val fontInstanceDescriptor: FontInstanceDescriptor,
    /** Borrowed portable backend used for provisional and final shaping. */
    public val shapingBackend: ShapingBackend,
    /** Resource-free identity of layout-only or outline-validated publication. */
    public val materializationIdentity: ParagraphMaterializationIdentity,
    /** Only supported behavior when complete source coverage exceeds the region. */
    public val overflowPolicy: OverflowPolicy = OverflowPolicy.CONTINUE,
    /** Exact prior result capability when this request resumes partial coverage. */
    public val continuation: LayoutContinuation? = null,
    /** Cooperative signal observed between bounded composition operations. */
    public val cancellationToken: CancellationToken = CancellationToken.none,
) {
    /** Immutable deterministic OpenType feature overrides in caller-specified order. */
    public val features: List<OpenTypeFeature> = features.immutableListSnapshot()

    init {
        require(snapshot.contains(sourceRange)) { "Paragraph source range must belong to the supplied snapshot." }
        require(unicodeAnalysis.range == snapshot.range) {
            "Paragraph Unicode analysis must cover the complete supplied snapshot revision."
        }
        require(lineBreakAnalysis.range == unicodeAnalysis.range && lineBreakAnalysis.unicodeData == unicodeAnalysis.unicodeData) {
            "Paragraph line-break and Unicode analyses must cover the same revision and Unicode data."
        }
        require(language.isNotBlank()) { "Paragraph language must not be blank." }
        require(unicodeAnalysis.scriptLanguageRuns.all { run -> run.language == language }) {
            "Paragraph language must match every analyzed script-language run."
        }
        require(featurePolicy == shapingBackend.identity.featurePolicy) {
            "Paragraph feature policy must be implemented by the selected shaping backend."
        }
        require(this.features.map(OpenTypeFeature::tag).distinct().size == this.features.size) {
            "Paragraph shaping features must not repeat a tag."
        }
        require(fontCatalog.generation == resolutionPolicy.generation) {
            "Paragraph font catalog and resolution policy must use the same generation."
        }
        require(resolutionPolicy.candidates.all { candidate -> candidate.faceId in fontCatalog.faces.map(FontFaceRecord::id) }) {
            "Every paragraph font candidate must belong to the captured font catalog."
        }
        require(continuation == null || continuation.isCompatibleWith(this)) {
            "Paragraph continuation is incompatible with the request revision, remainder, geometry, or configuration."
        }
    }
}

/** Typed reason paragraph composition could not publish any partial line. */
public sealed interface ParagraphLayoutError {
    /** Stable machine-readable error code. */
    public val code: String

    /** Human-readable deterministic explanation. */
    public val message: String

    /** Invalid or incompatible portable paragraph inputs detected during composition. */
    public data class InvalidInput(
        override val message: String,
    ) : ParagraphLayoutError {
        override val code: String = "layout.invalid-paragraph-input"
    }

    /** Typed font or shaping failure that prevented publication of the current complete line. */
    public data class FontFailure(
        /** Underlying portable font failure. */
        public val fontError: FontError,
    ) : ParagraphLayoutError {
        override val code: String = "layout.paragraph-font-failure"
        override val message: String = fontError.message
    }

    /** A finite final paragraph coordinate could not be produced. */
    public data class GeometryOverflow(
        override val message: String,
    ) : ParagraphLayoutError {
        override val code: String = "layout.paragraph-geometry-overflow"
    }
}

/**
 * Typed outcome of one synchronous paragraph composition call.
 *
 * Failure and cancellation variants deliberately contain no [LineLayout] or [ParagraphLayout],
 * so a current partial line can never escape. Diagnostic collections are immutable snapshots,
 * and successful values contain only complete final lines.
 */
public sealed interface ParagraphLayoutResult {
    /** Complete-line publication with explicit source coverage. */
    public class Success(
        /** Immutable paragraph containing only complete final lines. */
        public val layout: ParagraphLayout,
        /** Complete or partial status for the requested range. */
        public val coverageStatus: CoverageStatus,
        /** Exact remainder capability for partial coverage, otherwise `null`. */
        public val continuation: LayoutContinuation? = null,
    ) : ParagraphLayoutResult {
        init {
            require((coverageStatus == CoverageStatus.PARTIAL) == (continuation != null)) {
                "Only partial paragraph coverage may publish a continuation."
            }
            if (continuation != null) {
                require(layout.version == continuation.originalVersion) {
                    "A partial paragraph and its continuation must use the same source revision."
                }
                require(layout.range.start == continuation.originalSourceRange.start) {
                    "A partial paragraph must begin at the original requested source boundary."
                }
                require(layout.range.endExclusive == continuation.remainingSourceRange.start) {
                    "A partial paragraph must end exactly where its continuation begins."
                }
                require(continuation.remainingSourceRange.endExclusive == continuation.originalSourceRange.endExclusive) {
                    "A partial paragraph continuation must retain the original requested end boundary."
                }
            }
        }
    }

    /** No paragraph was published because a typed failure prevented a complete current line. */
    public class Failure(
        /** Typed failure that prevented publication. */
        public val error: ParagraphLayoutError,
        diagnostics: List<EditableLineDiagnostic> = emptyList(),
    ) : ParagraphLayoutResult {
        /** Immutable diagnostics produced before the failed current line was discarded. */
        public val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableListSnapshot()
    }

    /** No paragraph was published because cooperative cancellation was observed. */
    public class Cancelled(
        diagnostics: List<EditableLineDiagnostic> = emptyList(),
    ) : ParagraphLayoutResult {
        /** Immutable diagnostics produced before cancellation discarded the current line. */
        public val diagnostics: List<EditableLineDiagnostic> = diagnostics.immutableListSnapshot()
    }
}

/** Portable boundary implemented by a pure, renderer-independent paragraph layout module. */
public interface ParagraphLayouter {
    /**
     * Composes [request] synchronously into complete immutable lines using [materialization].
     *
     * [materialization] is the only parameter allowed to carry a live resolver. Its immutable
     * identity must equal [ParagraphLayoutRequest.materializationIdentity], and a renderable
     * resolver must use the request catalog generation. Implementations borrow it only for this
     * call and must neither close nor retain it. They publish neither a current partial line nor
     * a native/platform resource: interruption returns [ParagraphLayoutResult.Cancelled], and
     * any failure while finalizing a line returns [ParagraphLayoutResult.Failure].
     */
    public fun layout(
        request: ParagraphLayoutRequest,
        materialization: EditableLineMaterialization,
    ): ParagraphLayoutResult
}

private fun PositionedGlyphRun.translatedBy(baseline: LayoutPoint): PositionedGlyphRun =
    PositionedGlyphRun(
        sourceRun = sourceRun,
        visualOrder = visualOrder,
        renderAssetKey = renderAssetKey,
        glyphs = glyphs.map { glyph ->
            PositionedGlyph(
                shapedGlyph = glyph.shapedGlyph,
                sourceClusters = glyph.sourceClusters,
                origin = glyph.origin.translatedBy(baseline),
                advance = glyph.advance,
                renderAssetKey = glyph.renderAssetKey,
                materializationCertificate = glyph.materializationCertificate,
            )
        },
    )

private fun CaretCandidate.translatedBy(baseline: LayoutPoint): CaretCandidate =
    CaretCandidate(
        position = position,
        geometry = LayoutSegment(
            start = geometry.start.translatedBy(baseline),
            end = geometry.end.translatedBy(baseline),
        ),
        visualOrder = visualOrder,
        visualRunOrder = visualRunOrder,
        bidiLevel = bidiLevel,
        direction = direction,
        strength = strength,
        edge = edge,
    )

private fun LayoutPoint.translatedBy(offset: LayoutPoint): LayoutPoint =
    LayoutPoint(LayoutUnit(x.value + offset.x.value), LayoutUnit(y.value + offset.y.value))

private fun verticalDistanceToRect(point: LayoutPoint, rect: LayoutRect): Double = when {
    point.y < rect.top -> rect.top.value.toDouble() - point.y.value.toDouble()
    point.y > rect.bottom -> point.y.value.toDouble() - rect.bottom.value.toDouble()
    else -> 0.0
}

private fun paragraphSquaredDistanceToSegment(point: LayoutPoint, segment: LayoutSegment): Double {
    val xDistance = point.x.value.toDouble() - segment.start.x.value.toDouble()
    val verticalDistance = when {
        point.y < segment.start.y -> segment.start.y.value.toDouble() - point.y.value.toDouble()
        point.y > segment.end.y -> point.y.value.toDouble() - segment.end.y.value.toDouble()
        else -> 0.0
    }
    return xDistance * xDistance + verticalDistance * verticalDistance
}

private fun paragraphAffinityRank(affinity: CaretAffinity): Int = when (affinity) {
    CaretAffinity.DOWNSTREAM -> 0
    CaretAffinity.UPSTREAM -> 1
}

private fun maxParagraphIndex(first: TextIndex, second: TextIndex): TextIndex = if (first >= second) first else second

private fun minParagraphIndex(first: TextIndex, second: TextIndex): TextIndex = if (first <= second) first else second

private fun EditableLineMaterialization.toParagraphIdentity(): ParagraphMaterializationIdentity = when (this) {
    EditableLineMaterialization.LayoutOnly -> ParagraphMaterializationIdentity.LayoutOnly
    is EditableLineMaterialization.Renderable -> ParagraphMaterializationIdentity.Renderable(variant, outlineProfile)
}

private fun requireCompleteLinePartition(range: TextRange, lines: List<LineLayout>) {
    if (range.start == range.endExclusive) {
        require(lines.size <= 1 && lines.all { line -> line.range == range }) {
            "An empty paragraph range may publish at most one matching empty line."
        }
        return
    }
    require(lines.isNotEmpty()) { "A non-empty paragraph layout requires complete final lines." }
    var expectedStart = range.start
    var terminalEmptyLineSeen = false
    lines.forEachIndexed { index, line ->
        require(line.range.start.sharesVersionWith(range.start)) {
            "Every paragraph line must use the layout source revision."
        }
        if (line.range.start == line.range.endExclusive) {
            require(
                !terminalEmptyLineSeen &&
                    index == lines.lastIndex &&
                    expectedStart == range.endExclusive &&
                    line.range == TextRange(range.endExclusive, range.endExclusive),
            ) {
                "Only one terminal empty line may follow complete non-empty paragraph coverage."
            }
            terminalEmptyLineSeen = true
            return@forEachIndexed
        }
        require(!terminalEmptyLineSeen && line.range.start == expectedStart && line.range.start < line.range.endExclusive) {
            "Paragraph lines must be non-empty, contiguous, and ordered in logical source order."
        }
        require(line.range.endExclusive <= range.endExclusive) {
            "Paragraph lines must stay within the layout source range."
        }
        expectedStart = line.range.endExclusive
    }
    require(expectedStart == range.endExclusive) {
        "Paragraph lines must cover the complete published source range."
    }
}
