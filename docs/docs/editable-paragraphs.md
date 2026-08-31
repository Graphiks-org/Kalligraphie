# Editable paragraphs

Kalligraphie provides a JVM reference route for composing one immutable,
editable, horizontal multiline paragraph. It extends the exact editable-line
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

## Line-breaking and shaping guarantees

The JVM route analyzes legal UAX #14 break opportunities with versioned
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

## Scope and limits

This is a JVM reference API for one horizontal rectangular paragraph. It does
not render pixels or own editor state. Hyphenation, justification, vertical
writing, `CLIP`, `ELLIPSIS`, pagination, `FlowRegion`/`FlowChain`, caching,
benchmarks, non-JVM executable facades, and incremental-layout APIs remain out
of scope.
