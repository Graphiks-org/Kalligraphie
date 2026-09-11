# Glyph materialization reference — Apple M2 Max, 2026-09-10

This page publishes one reproducible observation from the opt-in
[glyph materialization measurement](glyph-materialization-measurement.md). It
is a named comparison point, not a portable latency target. Results from a
different machine, operating system, JVM, power state, or concurrent workload
must not be treated as regressions without a controlled comparison.

## Environment and protocol

- Date: 2026-09-10
- Commit: `7d3524957de23b431eeff4609d51546d4d0787f4`
- Machine: Mac Studio (`Mac14,13`), Apple M2 Max, 12 cores, 32 GB memory
- OS: macOS 26.6.2 (`aarch64`)
- JVM: OpenJDK 64-Bit Server VM `25.0.1+8-LTS`
- Corpus: `portable-glyph-materialization-v3`, 23 profiles and 115 glyphs
- Samples: 5 warmup iterations followed by 20 measured iterations per profile
- GC policy: two `System.gc()` requests before and after each profile; no
  requested collection between samples
- Percentiles: nearest-rank p50, p95, and p99
- Allocation: bytes allocated by the measured thread per iteration
- Retained JVM memory: signed used-heap delta after the documented collection
  requests
- Font SHA-256:
  - `BungeeColor-Regular.ttf`: `cf21a786e54f43694f4edbb51a38f81331a4c3414217c524c8cb2d091aa7fd63`
  - `TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf`: `3321f267b8a242d96c0790ac31becc74b7f57656762037e16f465be1adcd84d2`
  - `ebdt_fmt1.ttf`: `e99cebed4d9421bc89964b9dc6a3bedfc6a286029d64336a07844708cce76274`
  - `LiberationSans-Regular.ttf`: `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`

The local host name was deliberately excluded because it does not affect
reproducibility. Native retained memory and native allocations were unavailable
for every profile: the portable routes expose no reliable accounting boundary.

The recorded run used the documented command with
`KALLIGRAPHIE_GLYPH_MATERIALIZATION_WARMUP=5`,
`KALLIGRAPHIE_GLYPH_MATERIALIZATION_ITERATIONS=20`, `--rerun-tasks`, and
`--no-daemon`. Its output was written outside the repository before the values
below were checked against all 23 generated profiles.

## Portable representation routes

All latency values are nanoseconds; allocation and retained-memory values are
bytes.

| Profile | p50 | p95 | p99 | Allocation/iteration | Retained JVM memory |
| --- | ---: | ---: | ---: | ---: | ---: |
| `ColrColdNormalization` | 1,321,500 | 2,374,334 | 2,588,042 | 933,618 | 53,480 |
| `ColrWarmResolution` | 12,833 | 19,625 | 20,208 | 3,272 | 10,632 |
| `SvgColdNormalization` | 617,958 | 1,142,250 | 1,411,375 | 224,705 | 61,848 |
| `SvgWarmResolution` | 8,041 | 9,459 | 10,250 | 3,624 | 4,472 |
| `BitmapColdDecode` | 172,833 | 348,500 | 364,583 | 73,464 | 19,208 |
| `BitmapWarmResolution` | 6,167 | 7,375 | 8,333 | 2,440 | 2,680 |
| `PaletteChange` | 432,542 | 610,709 | 909,166 | 278,411 | 156,160 |
| `CachePressureAndEviction` | 986,417 | 1,675,459 | 1,803,875 | 717,339 | 49,456 |
| `CooperativeCancellation` | 5,750 | 7,916 | 7,917 | 3,544 | 4,880 |

## Public consumer routes

| Profile | p50 | p95 | p99 | Allocation/iteration | Retained JVM memory |
| --- | ---: | ---: | ---: | ---: | ---: |
| `RenderableConsumerColdSingleFont` | 2,215,000 | 3,034,667 | 3,493,959 | 1,269,495 | 2,682,256 |
| `RenderableConsumerWarmSingleFont` | 841,542 | 1,194,000 | 1,366,708 | 551,677 | 7,248 |
| `RenderableConsumerColdMixedBidi` | 4,626,459 | 5,893,792 | 6,500,541 | 5,366,046 | 25,008 |
| `RenderableConsumerWarmMixedBidi` | 1,249,500 | 3,177,208 | 3,530,708 | 1,829,168 | 11,496 |

## Portable TrueType editor stages

| Profile | p50 | p95 | p99 | Allocation/iteration | Retained JVM memory |
| --- | ---: | ---: | ---: | ---: | ---: |
| `TrueTypeColdPreparation` | 2,000,250 | 2,667,750 | 2,743,166 | 2,354,456 | 5,784 |
| `TrueTypeWarmPreparation` | 1,792 | 9,375 | 10,833 | 1,088 | 3,344 |
| `TrueTypeColdTextMapping` | 1,889,917 | 2,464,167 | 2,537,333 | 2,403,856 | 12,704 |
| `TrueTypeWarmTextMapping` | 31,792 | 38,208 | 50,208 | 31,112 | 3,344 |
| `TrueTypeColdMetrics` | 2,442,916 | 3,719,125 | 3,935,166 | 2,892,980 | 6,296 |
| `TrueTypeWarmMetrics` | 142,542 | 155,625 | 158,333 | 131,252 | 3,056 |
| `TrueTypeColdOutlines` | 2,437,917 | 3,184,000 | 3,246,959 | 3,150,658 | 5,264 |
| `TrueTypeWarmOutlines` | 21,000 | 31,417 | 33,458 | 33,712 | 3,488 |
| `TrueTypeColdDetach` | 1,884,417 | 2,592,708 | 2,645,584 | 2,719,380 | 4,384 |
| `TrueTypeWarmDetach` | 1,709 | 3,125 | 4,958 | 1,320 | 2,976 |

## Interpretation

The cold/warm gaps are consistent with the preparation and cache-reuse
boundaries defined by the protocol. The largest allocation and latency figures
occur on the mixed-font bidirectional consumer route, while prepared TrueType
operations remain much cheaper than their cold counterparts. These observations
describe this exact environment only. A future threshold requires repeated
measurements on a controlled reference machine and an explicit link to a stable
performance profile.
