# Editable-line measurement

Kalligraphie measures the public editable-line consumer journey in the
non-published `:kalligraphie:bench` module. It emits unpublished observations
from one explicitly configured run; it is not a reference benchmark and contains
no latency threshold or performance assertion. The measurement is opt-in: it
runs only through the module's `jvmBenchmarkBenchmark` task and never as part of
`check`.

The editable-line profiles belong to the module's paragraph half. They compose
text through the paragraph facade. That half is measured on the JVM today: the
paragraph scenarios and the support they use are still JVM sources, so the other
platforms have none to run. A missing capability is no longer the reason: every
platform declares `END_TO_END_LAYOUT` present.

The fixed real-text corpus is `Edit سلام 😀 café`. Its UTF-8 and UTF-16 decode
profiles borrow immutable application-owned storage through four fragments:

- UTF-8: 24 bytes split as `[5, 9, 5, 5]`;
- UTF-16: 17 code units split as `[5, 5, 3, 4]`.

Every seam is a complete Unicode-scalar boundary. The layout profiles use the
same 16-scalar text as one LTR-base line with Latin, Arabic, emoji, and BiDi
runs. They shape the checked-in
`kalligraphie/shaping/src/jvmTest/resources/fonts/dejavu/DejaVuSans.ttf`
fixture through the embedded HarfBuzz backend. Font file reading and text
decoding happen before layout timing. No renderer, rasterizer, GPU operation, or
glyph-materialization route is measured.

## Profiles and timed boundaries

The report records these profiles in order:

1. `BorrowedFragmentedUtf8Decode` starts immediately before public
   `decodeUtf8(...)` and ends after scalars, source ranges, and diagnostics have
   been consumed and checked against literal independent oracles.
2. `BorrowedFragmentedUtf16Decode` applies the same boundary to
   `decodeUtf16(...)`.
3. `ColdMixedBidiLine` starts before in-memory embedded-catalog capture and
   session opening. It includes face resolution, font instantiation, request
   creation, layout, public-result consumption, and session closure. Every
   warmup and measured sample prepares a new font catalog, font instance, and
   session.
4. `WarmMixedBidiLine` prepares one font instance and opens and seeds one
   session before timing. Warmup and measured samples reuse that session. The
   clock surrounds `session.layout(...)` and the consumption of line and run
   ranges, glyphs, carets, diagnostics, provenance, and advances; preparation
   and closure are excluded.

The decode profiles are warm/stateless: their borrowed storage and slice
objects are prepared outside timing, their configured warmup precedes measured
samples, and no retained engine cache is claimed. The runner
accepts only complete successful decodes and layouts. Its fixed validity
oracles cover scalar values, source boundaries, clean diagnostics, complete run
partitioning, both LTR and RTL directions, checked-in DejaVu glyph identifiers
and advances independently audited with `hb-shape` 14.4.0, direct glyph provenance,
and all scalar-boundary carets.

## Reproducible invocation

The module measures with kotlinx-benchmark (JMH on the JVM): warm-up, iterations,
the one-second iteration time and the JSON report format come from its benchmark
configuration, not from environment variables. One command measures every
profile the platform serves — the editable-line profiles are four of the
thirty-seven the JVM runs. Results and counters are written under the module's
`build` directory, which git ignores:

```bash
./gradlew :kalligraphie:bench:jvmBenchmarkBenchmark
```

`./gradlew :kalligraphie:bench:measurementReport` then joins that run with the
other platforms into `build/bench/report-jvm.md` and a comparison. Neither task
is in `check`: a measurement is requested, never scheduled, and no functional
test asserts an elapsed time.

A report remains tied to the environment it recorded and is not a project
performance target.

## Report contents and limits

The Markdown report records, for the whole run:

- measured commit, machine, operating system, runtime, and cache/GC policy;
- corpus identifier, description, and the SHA-256 of every fixture actually
  read;
- and, for every profile: its route, timed boundary, cache state, warm-up and
  measured iteration counts, nearest-rank p50, p95, and p99 latency in
  nanoseconds, the counters proving what the timed operation consumed, and
  memory figures each labelled `measured`, `estimated` or `unavailable`.

The editable-line profiles publish the scalars, glyphs and layouts they consumed
alongside the latency, because a harness cannot tell a fast operation from an
operation that did nothing: the module refuses a profile whose counters are
missing or empty. Allocation and heap figures describe this small harness, not
universal process or cache accounting. Native memory is published as
`unavailable` with its reason rather than estimated — never as a measurement.
The report contains no success threshold and no renderer measurement.
