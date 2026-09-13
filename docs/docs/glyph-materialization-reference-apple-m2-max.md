# Apple M2 Max font-asset handoff reference

This is one machine observation from 2026-09-11, not a universal performance
promise or a CI gate. Only the three public font-asset handoff profiles are
published here. The opt-in runner executed all thirty profiles in their
documented order. See [measurement methodology](glyph-materialization-measurement.md).

## Measured environment

- Measured code/docs commit: `f4e08d8ce677f84c79402addc4fefcda992adbfb`.
- Machine: Apple M2 Max, 32 GiB RAM (34359738368 bytes), hostname `Omega.local`.
- OS: macOS 26.6.2, build `25G83`, arm64; JVM reports `Mac OS X 26.6.2 (aarch64)`.
- JVM: Eclipse Temurin 25.0.1+8-LTS; runner reports `OpenJDK 64-Bit Server VM 25.0.1+8-LTS`.
- Gradle: 9.6.1, `--no-daemon --rerun-tasks`.
- Warmup: 5 iterations per profile; measured iterations: 20 per profile.
- GC policy: `System.gc()` twice before and after each profile; no requested GC between samples.
- Corpus identifier: `portable-glyph-materialization-v4`.
- Font: checked-in, audited `LiberationSans-Regular.ttf`, 410712 bytes.
- Font SHA-256: `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`.
- External report: `/private/tmp/kalligraphie-font-asset-handoff-m2-max.md`.

The measured revision is the runner/descriptive-docs commit immediately before
the separate commit adding these reference pages. No runner changes were made
between that commit and the reference execution. The numbers below retain the
runner's integer nanosecond/byte format without further rounding.

## Corpus and public boundaries

The exact stable editor paragraph is:

> Readable typography keeps words, punctuation, carets, and 0123456789 responsive while an editor changes text.

The entire text is composed as one editable line using English, left-to-right
base direction, size 1000, default render variant, ascent/descent 800/200,
the pinned shaping feature policy with no extra features, and the runner's
portable outline profile. The fixture hash and independent audited glyph 36
facts (2048 units per em, bounds `(4, 0, 1362, 1409)`, two contours) are checked
outside timing. No expected outline value is derived with Kalligraphie.

`FontAssetRetainReopenCold` creates a fresh embedded catalog, resolver, resolved
face/font instance and public `JvmEditableLineLayoutSession` for every sample.
This session owns its real HarfBuzz backend and its public `layout` call accepts
`JvmEditableLineFacadeRequest` to publish a renderable `EditableLine`.
The sample opens its `LayoutHandle`, groups final
certificates by complete `FontRenderAssetKey`, retains one renderer asset per
key, and resolves/consumes every final certified glyph. All owner cleanup is
included in the total.

`FontAssetRetainReopenWarm` prepares and seeds the real catalog/resolver/font
and public line session outside timing. Every sample has a fresh text version, editable line,
layout handle and renderer assets. Per-sample assets and handle close inside
the measured interval; persistent session/backend and resolver cleanup is
outside it. This is the named 60 Hz reference profile.

`ConcurrentResolveWarm` obtains one renderer-owned asset through public
`JvmEditableLineLayoutSession.layout` -> `openLayoutHandle` -> `retainFontAsset`, then closes the layout handle,
session/backend and resolver before warmup/timing. It pre-resolves the following
fixed 35 distinct nonzero glyph IDs, in paragraph first-occurrence order:

```text
53, 72, 68, 71, 69, 79, 3, 87, 92, 83, 82, 74, 85, 75, 78, 86, 90, 15,
88, 81, 70, 76, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 89, 91, 17
```

Exactly four persistent worker threads share that one asset. Round-robin
partitioning assigns 9/9/9/8 glyphs. Each wave resolves and consumes all 35
glyphs exactly once. Main latency is whole-wave wall time from dispatch to
all-worker completion, never divided into a per-operation latency. The shared
asset closes after all samples and worker termination, including failure paths.
Coordinator interruption during result collection is restored after owner cleanup.
This is the named 120 Hz reference profile.

## Observed total latency

All values are nanoseconds; nearest-rank p50/p95/p99 over 20 samples.

| Profile | p50 | p95 | p99 | Observational objective |
| --- | ---: | ---: | ---: | --- |
| `FontAssetRetainReopenCold` | 5069208 | 5463833 | 5969375 | No named objective |
| `FontAssetRetainReopenWarm` | 1878250 | 2435625 | 2486042 | 60 Hz: p95 <= 8000000 ns — PASS |
| `ConcurrentResolveWarm` | 86458 | 113417 | 173042 | 120 Hz: p95 <= 4000000 ns — PASS |

The concurrent profile records **4 workers and 35 operations per wave**. The
handoff profiles run their complete editable-line chain on the coordinator thread.
`PASS` and `ABOVE` are observational labels only. Neither makes the runner or
functional `check` succeed or fail.

## Stages in the same handoff chain

Durations are retained per sample. Each row measures its own public boundary;
the total also includes small orchestration gaps such as certificate grouping.
Percentiles are computed separately, so stage percentile sums need not equal a
total percentile. All values are nanoseconds.

| Profile | Stage | p50 | p95 | p99 |
| --- | --- | ---: | ---: | ---: |
| Cold | `layout-certification` | 4687666 | 4962083 | 5598666 |
| Cold | `layout-handle-open` | 190542 | 249042 | 577916 |
| Cold | `renderer-asset-retain` | 6792 | 9459 | 12541 |
| Cold | `glyph-resolve-consume` | 105958 | 120750 | 121417 |
| Cold | `owned-resource-close` | 64666 | 86958 | 89000 |
| Cold | `total` | 5069208 | 5463833 | 5969375 |
| Warm | `layout-certification` | 1673208 | 2185583 | 2275334 |
| Warm | `layout-handle-open` | 74042 | 103583 | 647167 |
| Warm | `renderer-asset-retain` | 5209 | 10000 | 12834 |
| Warm | `glyph-resolve-consume` | 93000 | 102667 | 109083 |
| Warm | `owned-resource-close` | 2917 | 3417 | 5875 |
| Warm | `total` | 1878250 | 2435625 | 2486042 |

`layout-certification` includes fresh text creation and public layout/final
certification; cold additionally creates catalog/resolver/face/font/public-session/backend. Opening and
retention stages include registering their returned owners. Consumption reads
actual glyph identifiers, units per em, bounds, contour and command counts.
Closing covers all per-sample owners, including backend/resolver for cold.
There are no separate stage microprofiles for the concurrent wave.

## Memory observations and availability

| Profile | Allocated bytes per sample/wave | Retained JVM heap delta, bytes | Source bytes supplied during timing |
| --- | ---: | ---: | ---: |
| `FontAssetRetainReopenCold` | 6826722 | 42976 | 410712 |
| `FontAssetRetainReopenWarm` | 2433856 | 14432 | 0 |
| `ConcurrentResolveWarm` | 45184 | 11704 | 0 |

All three allocation fields are `available`. Handoff allocations are the
measured thread's average per iteration. Concurrent allocation is the sum of
the four workers' trustworthy nonnegative per-thread deltas inside their
resolve/consume intervals, averaged per wave; coordinator and dispatch
allocations are excluded. If any worker counter is unavailable, the runner
reports `unavailable` with a reason instead of coordinator-only allocation.

Retained JVM memory is `available`: the signed used-heap delta after the
documented forced-GC requests surrounding each complete profile, including its
setup and cleanup. This is neither cache accounting nor a universal process
memory measurement, and can be negative. Source bytes describe fixture buffers
supplied to the catalog, not filesystem I/O.

For each of these three profiles, retained native memory is `unavailable`
(no retained native-memory accounting boundary), and native allocations are
`unavailable` (no native-allocation counter). Decoded bitmap bytes, normalized
paint nodes and decoded pixels are `available: 0`. Cancellation delay is
`unavailable` because these profiles do not signal cancellation. Maximum live
assets, estimated asset bytes, asset openings, operation reuses, prepared font
source bytes copied, estimated prepared native bytes and backend reuses are
`unavailable`: these profiles do not inspect those accounting dimensions.
No internal cache counters or native estimates are substituted.

## Reproduction and limits

```sh
rtk env \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_MEASUREMENT=true \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_WARMUP=5 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_ITERATIONS=20 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_OUTPUT=/private/tmp/kalligraphie-font-asset-handoff-m2-max.md \
  ./gradlew :kalligraphie:glyphMaterializationMeasurement --no-daemon --rerun-tasks
```

The run completed successfully, replaying 42 tasks. Twenty samples give limited
tail evidence: nearest-rank p95 is the 19th sorted sample, p99 the maximum.
Scheduling, JIT compilation, heap state and competing work can change results.
The observations include no rendering, rasterization, GPU work or native bridge.
Measurement remains opt-in and excluded from functional `check`, even when its
environment variable is set. The reference does not impose timing thresholds.
