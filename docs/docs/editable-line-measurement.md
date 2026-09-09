# Editable-line measurement

Kalligraphie includes an opt-in JVM runner for the public editable-line consumer
journey. It emits unpublished observations from one explicitly configured run;
it is not a reference benchmark and contains no latency threshold or performance
assertion. The runner is test-source tooling, is excluded from `jvmTest` and
`check` by default, and runs only through the dedicated
`:kalligraphie:editableLineMeasurement` task.

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

## Reproducible smoke invocation

Explicit activation and an absolute Markdown output path outside the repository
are mandatory. Use `--rerun-tasks` so Gradle cannot reuse an earlier result when
environment variables change. One warmup and two iterations exercise the whole
runner but do not produce observations suitable for comparison:

```bash
env \
  KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT=true \
  KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT_WARMUP=1 \
  KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT_ITERATIONS=2 \
  KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT_OUTPUT=/tmp/kalligraphie-editable-line.md \
  ./gradlew :kalligraphie:editableLineMeasurement \
  --rerun-tasks --no-daemon
```

Use larger positive warmup and iteration counts only when recording a deliberate
local observation. A report remains tied to its recorded environment and is not
a project performance target.

## Report contents and limits

The Markdown report records:

- Git commit, machine, operating system, architecture, and JVM;
- Unicode data, ICU4J, and embedded HarfBuzz versions;
- SHA-256 of the checked-in DejaVu font;
- corpus identity, description, encoding, source-unit and scalar sizes, and
  exact fragmentation;
- timed boundary and cold, warm, or stateless state for every profile;
- warmup and measured iteration counts;
- nearest-rank p50, p95, and p99 latency in nanoseconds;
- average measured-thread allocated bytes when the JVM exposes that counter;
- signed used-heap change after two explicit `System.gc()` requests before and
  after each profile, with no requested GC between measured iterations;
- native memory as explicitly `unavailable` because the public JVM journey has
  no reliable retained-native-byte boundary.

Allocation and heap fields describe this small runner, not universal process or
cache accounting. The signed heap change can be negative after the documented
GC policy. Native memory is not estimated. The report contains no call counter,
cache-internal counter, success threshold, or renderer measurement.
