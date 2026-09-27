# Editable paragraphs

Kalligraphie provides a JVM reference route for composing one immutable,
editable multiline paragraph. It extends the exact editable-line
route described in [Font Management](font-management.md): the public
`JvmEditableParagraphFacade` owns the ICU and HarfBuzz work for one call, then
returns portable, renderer-independent values. It does not retain a native
handle, resolver, backend, renderer, or platform object.

## Compose a paragraph

Start with an immutable `TextSnapshot`, an ordered embedded font catalog, and
a policy whose final candidate is an explicit last-resort face. Provider order
is preserved by `Kalligraphie.embedded(...)`; use it to construct the complete
`FontResolutionPolicySnapshot`.

```kotlin
val decoded = Kalligraphie.decodeUtf16(
    version = TextVersion.create(),
    slices = listOf(TextSlice.Utf16(editorText.toCharArray())),
)
val catalog = requireSuccess(
    Kalligraphie.embedded(
        listOf(
            FontSource(latinBytes, FontSourceProvenance("Latin")),
            FontSource(arabicBytes, FontSourceProvenance("Arabic")),
        ),
    ),
)
val candidates = catalog.faces.map { FontResolutionCandidate(it.id) }
val policy = FontResolutionPolicySnapshot(
    generation = catalog.generation,
    policyId = "editor-fallback",
    version = "1",
    candidates = candidates,
    lastResortFace = candidates.last().faceId,
)
val lineMetrics = LineVerticalMetrics(
    ascent = LayoutUnit(900f),
    descent = LayoutUnit(300f),
)

val result = JvmEditableParagraphFacade.layout(
    JvmEditableParagraphFacadeRequest(
        snapshot = decoded.snapshot,
        constraints = HorizontalParagraphConstraints(
            region = LayoutRect(
                left = LayoutUnit(100f),
                top = LayoutUnit(50f),
                right = LayoutUnit(1_500f),
                bottom = LayoutUnit(2_450f),
            ),
            lineMetrics = lineMetrics,
        ),
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        fontCatalog = catalog,
        resolutionPolicy = policy,
        fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
        materialization = EditableLineMaterialization.LayoutOnly,
    ),
)
```

`requireSuccess(...)` is application code that unwraps a
`FontOperationResult.Success`; production callers must handle typed font
failures instead of assuming a catalog can always be opened.

On `ParagraphLayoutResult.Success`, `layout.lines` contains complete final
lines only. Each `LineLayout` is expressed in physical paragraph coordinates
and separates:

- `contentMetrics`, derived from final typographic glyph content;
- `lineBox`, the composition and hit-testing region;
- `designInkBounds`, the deterministic union of the placed glyph bounds.

The resulting `ParagraphLayout` provides logical and visual caret navigation,
all candidates at ambiguous BiDi boundaries, `selectionGeometry(...)`, and
deterministic `hitTest(...)`. All results are immutable and bound to the input
snapshot version.

### Retain font assets for a delayed renderer

When the request uses `EditableLineMaterialization.Renderable`, a successful
`ParagraphLayout` contains certificates but owns no font resource. Before
closing the borrowed resolver, obtain the separate atomic ownership success
with `paragraph.openLayoutHandle(resolver)`. Group certificates from
`paragraph.lines` by `assetKey`, call `retainFontAsset(...)` once per group,
and close each returned renderer asset independently. The layout remains
readable after the handle closes, and assets already retained remain usable.
Layout success alone does not guarantee later reopening. See
[Font Management](font-management.md#own-certified-assets-for-delayed-rendering)
for the complete sequence and lifecycle boundaries.

## Style individual spans

Set `styleSpans` to give a sub-range its own preferred face and/or design-axis
variation. `ParagraphStyleSnapshot` holds an ordered, non-overlapping list of
`ParagraphStyleSpan(range, face?, variation?)` values over one text version;
`styleAt(index)` returns the covering span or `null` in a gap, and positions
outside every span use the paragraph default. A span must be non-empty, must
name at least one of `face` and `variation`, and its bounds must fall on
extended grapheme cluster boundaries. Both request types carry the optional
field — `ParagraphLayoutRequest.styleSpans` and
`JvmEditableParagraphFacadeRequest.styleSpans` — defaulting to `null`, which
keeps the paragraph behavior exactly unchanged.

### Face preference and fallback

A span's `face` is a preference resolved like Skia: for each fallback unit the
preferred face is tried first, then the resolution policy's candidates in their
declared order. The preferred face must be a member of
`resolutionPolicy.candidates`. `lastResortFace` is never promoted: naming it, or
leaving `face` null, leaves the policy order untouched. When the preferred face
cannot shape the whole unit but a later candidate can, the unit still resolves
through fallback and the line carries the `font.fallback.span-face-unavailable`
`WARNING` diagnostic.

### Variation inheritance, default, and tolerance

`variation` is a `FontVariationCoordinates` selection applied to the face that
is finally selected for the span:

- `null` inherits `fontInstanceDescriptor.variation` from the paragraph;
- `FontVariationCoordinates.default` asks for no axes and resets the selected
  face to its own default instance, which is distinct from `null`;
- a non-empty selection is strict on the primary candidate — every requested
  axis must be declared by the face, or the candidate is rejected — and
  tolerant on fallback candidates: axes the fallback does not declare are
  dropped and the line carries the `font.fallback.span-variation-projected`
  `WARNING` diagnostic. A static fallback face therefore stays usable under a
  variation span.

A non-empty span variation cannot be combined with a paragraph descriptor that
already carries non-empty `geometry.normalizedAxes`; the request is rejected as
invalid input. A face whose `fvar` table is present but unreadable is a data failure,
never treated as a static face.

### v1 boundaries

A span changes only the face and the variation applied to the selected run:
font size, OpenType features, paint, and materialization remain paragraph-wide.
The incremental and flow routes explicitly reject a request whose `styleSpans`
is non-null with a typed invalid-input failure before any region query or state
reuse, and the standalone editable-line facade composes a single instance and
has no span input at all. Per-span size, features, and paint are deferred to an
additive later phase.

### Line-box rhythm

Changing the face or variation of a run does not resize the line-box rhythm:
`ParagraphConstraints.lineMetrics` fixes every line box, while a line's content
envelope follows the instance of each run. Mixed-instance lines therefore keep
one row pitch even when their content envelopes differ. A `LayoutContinuation`
captures and compares `styleSpans`, so resuming with different spans is rejected
as incompatible instead of silently reusing the published prefix.

## Line-breaking and shaping guarantees

The paragraph route analyzes legal UAX #14 break opportunities with versioned
`TextIndex` boundaries, never UTF-16 offsets. It chooses the last legal
candidate that fits; when the first legal unit itself is wider than the region,
it publishes that complete unit to guarantee progress.

Extended grapheme clusters, variation selectors, and emoji ZWJ sequences are
never split. Final line candidates are reshaped in their own local context;
HarfBuzz unsafe-to-break information can force an earlier candidate. UAX #9 is
then applied per final line, including line-ending whitespace and final visual
order. A fallback unit is assigned to exactly one selected face, while a
paragraph may use several faces.

## Continue a short region

`OverflowPolicy.CONTINUE` is the only overflow policy. If the supplied height
cannot contain every complete line, a success result has
`coverageStatus == CoverageStatus.PARTIAL` and an immutable
`LayoutContinuation`. Reuse its exact remaining range and physical origin:

```kotlin
val partial = result as? ParagraphLayoutResult.Success
    ?: error("Handle the failure or cancellation result first.")
val continuation = checkNotNull(partial.continuation)
val resumedRegion = LayoutRect(
    left = continuation.regionLeft,
    top = continuation.resumptionRegionTop,
    right = LayoutUnit(continuation.regionLeft.value + continuation.regionWidth.value),
    bottom = LayoutUnit(continuation.resumptionRegionTop.value + 1_200f),
)
val resumed = JvmEditableParagraphFacade.layout(
    JvmEditableParagraphFacadeRequest(
        snapshot = decoded.snapshot,
        sourceRange = continuation.remainingSourceRange,
        constraints = HorizontalParagraphConstraints(resumedRegion, lineMetrics),
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        fontCatalog = catalog,
        resolutionPolicy = policy,
        fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
        materialization = EditableLineMaterialization.LayoutOnly,
        continuation = continuation,
    ),
)
```

The resumed request must keep the same snapshot, catalog, fallback policy,
direction, language, features, font instance descriptor, and materialization
identity. A different snapshot, remaining range, left origin, resumption top,
width, line metrics, or shaping configuration is rejected as invalid input.
Concatenating the published prefix and a compatible resumed result is
observable as one sufficiently tall composition.

## Compose through regions and exclusions

Use `JvmFlowCompositionFacade` when the application supplies several
composition regions or excludes inline space inside a line band. A
`FlowRegion` is an immutable, pure, deterministic, thread-safe geometry
provider. Its opaque `FlowRegionIdentity` must change whenever its bounds or
query behavior changes.

Queries use logical axes. `LineBand.blockStart` and `blockExtent` describe the
candidate line box along block progression; each half-open `InlineInterval`
describes available space along inline progression. The same contract
therefore supports `HORIZONTAL_TB`, `VERTICAL_RL`, and `VERTICAL_LR` without a
renderer-dependent coordinate convention.

```kotlin
class ArticleRegion(
    override val bounds: LayoutRect,
    private val exclusionStart: Float,
    private val exclusionEnd: Float,
) : FlowRegion {
    override val identity = FlowRegionIdentity.create()
    private val horizontalInlineExtent = bounds.right.value - bounds.left.value

    override fun query(
        writingMode: WritingMode,
        lineBand: LineBand,
    ): FlowRegionResult =
        if (lineBand.blockStart < 1_200f &&
            lineBand.blockStart + lineBand.blockExtent > 400f
        ) {
            FlowRegionResult.AvailableIntervals(
                listOf(
                    InlineInterval(0f, exclusionStart),
                    InlineInterval(exclusionEnd, horizontalInlineExtent),
                ),
            )
        } else {
            FlowRegionResult.AvailableIntervals(
                listOf(InlineInterval(0f, horizontalInlineExtent)),
            )
        }
}

val chain = FlowChain(
    regions = listOf(firstRegion, secondRegion),
    fragmentationConstraints = FragmentationConstraints(
        minLinesAtStart = 2,
        minLinesAtEnd = 2,
        keepTogether = false,
    ),
)
val portable = requireFlowSuccess(createIncrementalFlowLayoutRequest(
    input = LayoutInput(decoded.snapshot, typography),
    requestedRange = visibleSourceRange,
    constraints = paragraphConstraints,
    flowChain = chain,
    overscan = LineOverscan(2),
))
val flow = JvmFlowCompositionFacade.layout(
    JvmFlowCompositionRequest(
        request = portable,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
    ),
)
```

`typography` above is a `TypographySnapshot` built from the same catalog,
fallback policy, font instance, and OpenType features used by the rectangular
route. `requireFlowSuccess(...)` represents application code that unwraps a
success; production callers must handle each typed failure. The region example
is horizontal; a writing-mode-independent provider
should derive its logical inline extent from `writingMode` instead of always
using the physical width.

The validated region answer is exactly one of:

- `AvailableIntervals`, containing finite, non-empty, ordered, disjoint,
  in-bounds intervals;
- `Empty(nextBlockOffset)`, which makes strict finite block-axis progress;
- `EndOfRegion`.

Kalligraphie never sorts, merges, clips, or otherwise repairs a malformed
answer. It first queries the minimum line-box extent, composes one logical line
candidate, queries again at the same block start with a non-decreasing actual
extent, and accepts only a stable source range, band, and interval set.
Provider instability, a non-monotone answer, a refinement cycle or limit, a
shrinking band, non-finite or overflowing geometry, and lack of progress all
produce a typed `FlowCompositionError`; no approximate layout is published.

## Read fragmented geometry

A successful `FlowLayout` contains source-ordered `ParagraphFragment` values.
Each fragment records its exact paragraph and laid-out ranges, first/last
flags, structured diagnostics, and optional continuation. Its `LineLayout`
values remain logical lines. When an exclusion supplies several intervals,
one line contains several geometric `LineFragment` values.

For renderable flow output, `flow.openLayoutHandle(resolver)` performs the same
second atomic ownership operation over all certificates in the published
`flow.lines`. It does not attach resource ownership to the continuation or the
incremental layout state.

Paragraph BiDi resolution, line selection, and UAX #9 L1–L4 happen once for
that logical line. Visual runs are then assigned to intervals and may split
only at safe cluster boundaries. A grapheme cluster, ligature cluster, or
inline object is never divided between fragments, and a fragment boundary
never creates a `TextIndex`.

Use the editing methods on each published `LineLayout`; rectangular
`ParagraphLayout` values delegate to the same final line geometry.
`selectionGeometry(...)` returns rectangles for occupied fragments only, so it
does not paint an exclusion. `hitTest(...)` inside an excluded gap returns the
nearest valid caret with a deterministic tie-break.

## Fragmentation and exact continuations

`FragmentationConstraints` requests `minLinesAtStart`, `minLinesAtEnd`,
`keepTogether`, and `keepWithNext`. When the available regions cannot satisfy
all requests, Kalligraphie preserves the complete source and relaxes rules in
this deterministic order: `KEEP_WITH_NEXT`, `KEEP_TOGETHER`,
`MIN_LINES_AT_END`, then `MIN_LINES_AT_START`. Every relaxation appears as a
`FlowCompositionDiagnostic.FragmentationRelaxed`. The single-paragraph JVM
facade has no following-paragraph relationship to prove, so a requested
`keepWithNext` is reported through that same relaxation mechanism.

A `FlowContinuation` is an immutable capability, not a caller-defined string
key. It binds the exact text and typography revisions, paragraph suffix,
complete replay-relevant paragraph inputs, chain and region revision
identities, fragmentation state, writing mode, region index, and block cursor.
Reuse with a foreign, stale, contradictory, or incompletely proven identity is
rejected with a typed error before region or shaping work. Retaining a
continuation does not retain the text, region provider, page, renderer, or a
native handle.

## Bounded coverage and forward reflow

`requestedRange` asks for complete flow lines containing the target source
range; `LineOverscan` adds a bounded number of complete following lines. A
successful `FlowLayout.coverage` describes the exact contiguous source range
represented by its published fragments. After an incremental restart, that
range may begin at the mapped restart checkpoint rather than at the paragraph
start. When the physical paragraph continues, `unmaterializedTail` is the exact
`FlowContinuation` for the suffix after the covered range rather than an
implicit truncation.

Retain `FlowLayout.state` for the next request. With unchanged inputs, a later
request can extend coverage without rebuilding already sufficient coverage.
After an edit, pass that state with an authoritative `LayoutDelta` leading to
the new `LayoutInput`. The engine restarts at the last valid checkpoint before
the first affected dependency, composes forward, and stops at semantic
convergence or when requested coverage plus overscan is complete. The
published coverage is observably identical to a full composition with the
same inputs; pathological edits may still require a restart from the beginning.
Cancellation returns a typed failure and never changes shaping, breaks,
positions, carets, or fragmentation rules to meet a time budget.

## Scope and limits

The executable facades are JVM reference APIs; the contracts and returned
geometry remain portable and resource-free. Kalligraphie composes exactly
inside application-supplied regions, but it does not create pages or own their
global placement. It also owns no mutable document, viewport, scrolling,
scheduler, renderer, or GPU API. See [Advanced Typography](advanced-typography.md)
for hyphenation, justification, ellipsis, inline objects, vertical writing,
and incremental equivalence.
