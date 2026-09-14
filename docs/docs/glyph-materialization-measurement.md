# Glyph materialization measurement

Kalligraphie provides an opt-in JVM measurement runner for portable glyph
materialization. It is test-source tooling, not a functional latency test and
not a published benchmark result. It exercises the checked-in, audited COLR/
CPAL, SVG-in-OpenType, EBDT format 1, and Liberation Sans TrueType fixtures
through the public catalog, resolver, instance, asset, and `resolveGlyph(...)`
paths.

The runner records thirty profiles, in this order:

- cold and warm COLR v0 / CPAL v0 normalization;
- cold and warm SVG-in-OpenType normalization;
- cold and warm EBLC v2 / EBDT v2 format 1 bitmap decoding;
- CPAL palette 0 to palette 1 selection;
- SVG profile-key pressure followed by LRU eviction and re-resolution;
- cooperative cancellation during a real two-layer COLR materialization.
- cold and warm public `RENDERABLE` consumer journeys with one Bungee Color
  Latin glyph;
- cold and warm public `RENDERABLE` consumer journeys with Bungee Color Latin
  plus Liberation Sans Hebrew fallback in one BiDi paragraph.
- cold and warm reusable incremental sessions for the same single-font and
  mixed-BiDi paragraphs;
- cold and warm portable TrueType preparation, text mapping, metrics, outlines,
  and detachment stages over one stable Liberation Sans editor paragraph;
- `FontAssetRetainReopenCold`, `FontAssetRetainReopenWarm`, then
  `ConcurrentResolveWarm` over that same paragraph.

For the six historical direct-glyph profiles, cold samples start before
embedded-catalog creation and end after the returned immutable representation
is consumed. Their warm samples create and seed the catalog, resolver, instance,
and asset before the clock starts, then time only `resolveGlyph(...)` and
consumption of its result. Asset and resolver closure are intentionally excluded
from both direct-profile intervals. The palette profile starts before palette-1
asset acquisition after a palette-0 seed. The pressure profile includes seeding,
five distinct certified SVG profile keys, and the final re-resolution. The
cancellation profile measures call entry through typed cancellation, and
separately measures the first in-operation cancellation signal through that
return.

The consumer-cold profiles include catalog and resolver creation, then end once
the public paragraph facade has produced and consumed a layout whose final
glyphs all carry materialization certificates. The consumer-warm profiles keep
their catalog and resolver open, seed the portable representation cache with an
untimed first layout, then measure the same public facade boundary. The JVM
facade deliberately opens and closes its documented shaping backend for every
call, so these warm profiles report asset-cache reuse rather than hidden
backend reuse.

Each consumer sample also reports the maximum number of operation-owned render
assets simultaneously live, their conservative estimated bytes, distinct asset
openings, and final-glyph proofs reused from earlier materialization in the same
operation. These are scenario measurements reconstructed from immutable
certificates and provider estimates. They are neither a time constraint nor
proof of a particular cache or pooling algorithm.

`FontMaterializationCachePolicy` now applies independent per-face and per-catalog budgets for retained bytes, decoded pixels and native dimensions. The current portable runner does not expose this coordinator's retained totals, admission decisions or eviction counts. Its timings and heap observations do not prove those bounds. Native charges remain zero; native resources and provider-wide or engine-wide accounting are outside this measurement scope.

## Reusable HarfBuzz sessions

`SessionColdSingleFont`, `SessionWarmSingleFont`, `SessionColdMixedBidi` and
`SessionWarmMixedBidi` use `JvmIncrementalParagraphLayoutSession`. Both sides
seed the portable catalog/resolver asset state outside timing. Cold samples open
a new session inside the timed boundary; warm samples retain one session and
its HarfBuzz backend, seeded by an untimed layout. Every sample supplies a fresh
text version, lays out the whole paragraph, and consumes certified glyphs.
Session and resolver closure are outside these intervals.

The session fields report source bytes copied into retained native source
buffers during the sample, the estimated retained HarfBuzz bytes at sample end,
and reuse of an existing backend (0 cold, 1 warm, determined by runner lifecycle).
Cold source-copy counts come from the session's idle-byte accounting: these
small fixtures fit the default policy without eviction. Warm samples need no
new source copy. This is separate from the existing catalog-input source field.

`JvmPreparedFontCachePolicy` limits entries, source bytes, estimated native
bytes, and their sum across both active and idle fonts. Admission is reserved
under one lock before native allocation; only idle fonts can be evicted, and
an impossible admission returns `FontError.ResourceLimitExceeded` without a
partial layout. The session policy is independent of the render-asset pool and
the incremental layout cache. `preparedFontCacheUsage` is an immutable snapshot
readable before layout and after close. Closing the backend releases idle fonts
immediately and active fonts after their final lease; it cannot reopen.

Estimator `harfbuzz-14.3.0-4x-source-plus-256k-v1` accounts 256 KiB plus four
times the source length for HarfBuzz objects, accelerators and retained caches;
the source buffer itself is counted separately. This deliberately conservative,
versioned estimate is a policy charge, not an instrumented allocation counter
or a proven upper bound for arbitrary fonts. Transient shaping buffers, JVM
copies, allocator metadata, shared library memory and process RSS are outside
this accounting. Actual native bytes and allocation counts remain `unavailable`.
Latency, thread allocations and heap deltas are observed measurements; native
estimates must never be interpreted as measured total process memory.

## Portable TrueType stages

The ten additional portable TrueType profiles use Liberation Sans Regular and
this exact paragraph: “Readable typography keeps words, punctuation, carets,
and 0123456789 responsive while an editor changes text.” Its Unicode scalars
are enumerated once before warm timed operations. The five paired boundaries are:

- preparation: cold timing covers embedded-catalog capture, face resolution,
  and instance creation from a new catalog; warm timing repeats face resolution
  and instance creation from one captured catalog;
- text mapping: cold timing creates a new instance before resolving the full
  paragraph; warm timing resolves the same scalar sequence on one prepared
  instance, consuming every returned glyph identifier;
- metrics: cold timing creates an instance, maps the paragraph, and reads every
  glyph's advance and bounds; warm timing reads the same fields for one
  pre-mapped glyph sequence on one prepared instance;
- outlines: cold timing creates a resolver and attached asset before resolving
  every distinct nonzero paragraph glyph; warm timing repeatedly resolves the
  same glyphs from one asset seeded before the clock, consuming each outline's
  glyph id, units-per-em, bounds, contour count, and command count;
- detachment: cold timing creates and detaches an asset, closes its attached
  owner, then resolves glyph 36 through the detached handle; warm timing uses
  one seeded attached owner for independent detach, resolve, and detached-close
  cycles, then closes that owner after all samples.

Every owned resolver, attached asset, and detached asset is closed in a
`finally` path. Cold profiles include the preparation named by their boundary;
warm profiles keep that state prepared or seeded outside the clock. Each stage
reports positive p50, p95, and p99 latency observations, measured-thread
allocation status, retained JVM-memory observation, source bytes, and the
Liberation Sans SHA-256 alongside the other fixture hashes. Native retained
memory and native allocations remain explicitly `unavailable`: the portable
API exposes no trustworthy accounting boundary for either value.

## Public font-asset handoff

The final three profiles use the stable paragraph above and the audited
Liberation Sans fixture. Before timing, the runner verifies the fixture hash
and literal glyph 36 outline facts from its independent audit (2048 units per
em, bounds `(4, 0, 1362, 1409)`, two contours). It also checks the paragraph's
final distinct glyph sequence against the fixed corpus below.

`FontAssetRetainReopenCold` creates a fresh embedded catalog, resolver, resolved
face/font instance and public `JvmEditableLineLayoutSession` for every sample.
The session owns its real HarfBuzz backend. Its public `layout` method takes a
`JvmEditableLineFacadeRequest` and composes the stable text as one renderable
`EditableLine`. Each sample then calls `openLayoutHandle`, groups all final certificates by complete
`FontRenderAssetKey`, retains one renderer asset per key, and resolves and
consumes every final certified glyph, including repeats. The total includes
renderer assets, layout handle, backend and resolver cleanup.

`FontAssetRetainReopenWarm` prepares one catalog, resolver, face/font instance
and reusable public line session outside timing and seeds the complete portable
path before warmup.
Every sample supplies a fresh text version and creates a new editable line, handle
and renderer assets. Their closure is timed; persistent session/backend and
resolver cleanup occurs outside sample timing. This profile is the named
60 Hz observation, with an objective of p95 <= 8,000,000 ns.

Both profiles keep per-sample durations for one chain and publish nearest-rank
p50/p95/p99 for these boundaries:

| Stage | Timed work |
| --- | --- |
| `layout-certification` | Fresh text version, public line layout and final certification; cold also creates catalog, resolver, face/font and public session/backend |
| `layout-handle-open` | Public `openLayoutHandle` and registration of its owner |
| `renderer-asset-retain` | One public `retainFontAsset` per complete key and registration of each owner |
| `glyph-resolve-consume` | Every final certified glyph's resolution and actual representation-field consumption |
| `owned-resource-close` | All per-sample owners, including backend and resolver for cold |
| `total` | Complete sample, including small orchestration gaps such as certificate grouping |

Stage percentiles are computed separately; their sums need not equal a total
percentile. Cleanup runs on failure/cancellation and continues after cleanup
errors. No production instrumentation or internal cache counters are added.

`ConcurrentResolveWarm` obtains one renderer asset through the same public
layout, handle and retention chain, then closes the layout handle, backend and
resolver before warmup or timing. It pre-resolves this fixed corpus of 35
distinct nonzero glyph IDs in paragraph first-occurrence order:

```text
53, 72, 68, 71, 69, 79, 3, 87, 92, 83, 82, 74, 85, 75, 78, 86, 90, 15,
88, 81, 70, 76, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 89, 91, 17
```

Four persistent worker threads share this asset; round-robin partitioning gives
them 9, 9, 9 and 8 glyphs. Each sample is one concurrent wave resolving and
consuming every corpus glyph exactly once. Main latency is whole-wave wall
time from dispatch to all-worker completion, never divided by operation count.
The report records four workers and 35 operations per wave. Allocation is the
sum of trustworthy nonnegative per-thread deltas inside the four resolve/consume
intervals, averaged per wave; coordinator and dispatch allocations are excluded.
If any worker lacks such a counter, allocation is `unavailable` with a reason.
Workers terminate before the shared renderer asset closes, including on failure.
Interruption during coordinator result collection is restored after owner cleanup.
This is the named 120 Hz observation, with an objective of p95 <= 4,000,000 ns.

The two objectives produce observational `PASS` or `ABOVE` fields only; neither
can fail the runner or `check`. These tooling changes use smoke execution and
real measurements as evidence, with no artificial structural test or latency
assertion. See the [Apple M2 Max reference](glyph-materialization-reference-apple-m2-max.md)
for one machine observation, not a universal performance promise.

## Reproducible invocation

The report must be written outside the repository. `--rerun-tasks` prevents a
previous Gradle result from suppressing an explicitly requested measurement.

```bash
env \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_MEASUREMENT=true \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_WARMUP=5 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_ITERATIONS=20 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_OUTPUT=/tmp/kalligraphie-glyph-materialization.md \
  ./gradlew :kalligraphie:glyphMaterializationMeasurement \
  --rerun-tasks --no-daemon
```

Use one warmup and two iterations only as a smoke run. It verifies that every
route can produce a report but is not suitable for comparisons.

## Report contents and limits

Every report records the measured commit, machine, OS, architecture, JVM,
fixture SHA-256 hashes, corpus, exact route, timed boundary, cache state,
warmup count, iteration count, nearest-rank latency percentiles, measured-thread
allocations, and a signed used-heap delta sampled after the documented forced
GC requests. It also records the input source bytes supplied to an embedded
catalog in the timed interval, decoded bitmap bytes and pixels, and normalized
paint-node counts.

The four operation-asset fields are available for public paragraph consumer
and session profiles. Direct-glyph and portable TrueType stage profiles report
them as `unavailable` because those routes do not execute an operation-scoped
paragraph composition.

Source-byte values are fixture buffer sizes supplied to the portable catalog;
they are not filesystem-I/O counters. A warm profile reports zero source bytes
because its catalog is intentionally opened before the timed boundary. The
portable routes in this runner expose no trustworthy native-memory or
native-allocation accounting boundary, so those fields explicitly report
`unavailable` rather than estimate a platform value. The retained JVM-memory
field is a runner-scoped heap observation, not cache accounting or a universal
process-memory measurement; it can be negative after GC.

The runner has no latency threshold. Functional `check` runs exclude the
measurement task even when the opt-in variable is set, and the runner does
not add a renderer, rasterizer, GPU API, or native bridge.

## Shared retention and native ownership

The Apple module provides a separate executable measurement through real public
portable outline, paint, bitmap and CoreText consumers. It is a `JavaExec` task,
outside `check`, and requires the same macOS/JDK/native-access support as the Apple
route. Enable it explicitly and write its report outside the repository:

```bash
env KALLIGRAPHIE_SHARED_FONT_CACHE_MEASUREMENT=true \
  KALLIGRAPHIE_SHARED_FONT_CACHE_OUTPUT=/tmp/kalligraphie-shared-retention.txt \
  ./gradlew :kalligraphie:platform:apple:sharedFontCacheMeasurement --rerun-tasks
```

The report records corpus hashes and source sizes, measured revision/working-tree
state, OS, architecture, JVM and full platform route identity. The bounded internal
recorder is disabled by default and attached before retention; its preallocated
cells archive pruned ledgers. It reports configured budgets, current charge and
event-time maxima for all four dimensions and active/reserved/retiring/residual
categories at scope, capture and face levels. Category moves are observed only
after complete accounting, and confirmed acknowledgements before pruning. Separate
category maxima may occur at different times: their sum is not a simultaneous peak.
Recorder saturation explicitly invalidates completeness rather than hiding events.

Profiles apply pressure to every scope/capture/face dimension, zero and individually
oversized budgets, and concurrent real outline/paint/bitmap/native acquisitions.
Audited contour bounds, palette colors, exact decoded pixels and independent native
advances validate consumer behavior. A native-byte profile seeds 48 actual sized
contexts from the 1772-byte GDEF font, closes their consumer owners, then acquires
the 757076-byte DejaVu font under a 757076-byte native scope limit. The candidate
individually fits but requires all 48 small charges to be relinquished. Recorded
decisions, victim counts and uncached fallbacks expose the current internal
32-victim/two-decision quota. Cold seeding, hot indexed acquisition, fallback,
scope drainage and caller closure have separate observed latencies and index-visit
evidence; there is no timing threshold or universal frame-budget promise.
These are single scenario observations without statistical warmup. Cold seeding
times all 48 public face/instance/asset acquisitions, native metric validation and
consumer closure; catalog capture/resolver opening occur beforehand. Hot timing
repeats one seeded acquisition with validation and closure. Fallback timing acquires
and validates the held large consumer; its final closure is separate. First native
probe setup can affect the cold observation. Recorder/report allocation is outside
those intervals and is measurement-harness memory, not retained cache charge.

Process-scoped opt-in native counters report successful owned CFData,
CGDataProvider, CGFont and CTFont creation references, confirmed API release units,
uncertain release outcomes and known CFData source-copy bytes under explicit
ownership. They do not measure `malloc`, private OS caching or prove physical OS
deallocation. At a drained boundary with no reservations, retirement or residual
uncertainty, remaining explicit native ownership after cache-reference release
belongs exclusively to surviving consumers. Their metrics remain usable through
scope closure. A release fault retains conservative residual cache charge and
uncertainty; it cannot be reported as confirmed drainage or caller-only memory.
