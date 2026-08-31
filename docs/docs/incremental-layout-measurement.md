# Incremental layout measurement

Kalligraphie includes an opt-in, engine-only JVM measurement entry point for
incremental paragraph layout. It is test-source tooling, not a functional
latency test and not a published benchmark result. The runner executes the
real `JvmIncrementalParagraphLayoutSession`, ICU analysis, embedded HarfBuzz,
and the checked-in DejaVu and Amiri font fixtures.

The timed interval starts immediately before `session.layout(...)`. Snapshots,
font catalogs, deltas, and requests are constructed before the clock starts.
The interval ends only after a successful result has complete requested
coverage and its lines, runs, glyphs, carets, diagnostics, and tail state have
been consumed. Application scheduling and rendering are excluded.

## Profiles

- `InteractiveEdit` alternates a prepared `cafe`/emoji replacement through one
  session and reuses the latest published state.
- `ViewportLayout` alternates two requested text ranges with two complete lines
  of overscan while retaining the same immutable snapshot.
- `Cancellation` requests the full corpus and signals cooperative cancellation
  after a fixed number of token checks. Only a typed cancelled result is
  accepted; no partial coverage counts as a fast success.

Each profile gets a new session. Untimed seed work, where applicable, and the
configured warmup precede measured iterations. The measured cache state is
therefore reported as warm. Two explicit `System.gc()` requests are made before
and after each profile, never between measured iterations.

## Reproducible invocation

Run the opt-in JUnit entry point explicitly. `--rerun-tasks` is required so a
previous Gradle test result cannot suppress a new measurement whose environment
variables changed. Keep the generated report outside the repository:

```bash
rtk env \
  KALLIGRAPHIE_MEASUREMENT=true \
  KALLIGRAPHIE_MEASUREMENT_WARMUP=5 \
  KALLIGRAPHIE_MEASUREMENT_ITERATIONS=20 \
  KALLIGRAPHIE_MEASUREMENT_OUTPUT=/tmp/kalligraphie-incremental-layout.md \
  ./gradlew :kalligraphie:jvmTest \
  --tests org.graphiks.kalligraphie.IncrementalLayoutBenchmarkTest.runConfiguredMeasurementProfiles \
  --rerun-tasks --no-daemon
```

For a smoke run that exercises all three profiles without producing a result
suitable for comparison, set warmup to `1` and iterations to `2`. Do not use a
smoke report to claim a latency target.

## Report fields

The Markdown report records:

- measured Git commit, host, OS, architecture, and JVM;
- Unicode and HarfBuzz versions;
- SHA-256 hashes for every font fixture;
- corpus identity, description, scalar count, and paragraph count;
- requested coverage, overscan, cache state, GC policy, warmup, and iterations;
- nearest-rank p50, p95, and p99 latency in nanoseconds;
- per-thread allocated bytes when the JVM exposes a thread allocation counter;
- a signed used-heap delta sampled after the documented forced-GC requests;
- an explicit unavailable state for retained native memory, which the backend
  does not expose;
- p95 cancellation return delay for `Cancellation`;
- average rematerialized scalars, lines, and paragraphs for successful
  profiles, or an explicit unavailable state when cancellation intentionally
  withholds partial diagnostics.

Use this structure when copying a result into a review description:

```text
Commit / machine / OS / JVM:
Unicode / HarfBuzz / font SHA-256:
Corpus / coverage / overscan:
Cache state / warmup / iterations / GC policy:
p50 / p95 / p99:
Allocations:
Retained JVM memory:
Retained native memory:
Cancellation delay:
Rematerialized text / lines / paragraphs:
Limits and unavailable measurements:
```

Allocation and retained-memory fields are measurements of this small runner,
not universal heap or native-memory accounting. A signed retained-heap delta
can be negative after GC. Native retained bytes remain unavailable until the
backend exposes a trustworthy accounting boundary.

## Interpretation and limits

The runner emits observations, not pass/fail thresholds. A result supports a
performance claim only when its full environment and reference-profile policy
are identified separately. Functional Gradle checks never assert elapsed time.

The current session accepts checkpoint reuse only from its own latest
publication. Exact line selection may conservatively inspect through the next
mandatory UAX #14 boundary, or through document end when no mandatory boundary
remains. Consequently, the rematerialization diagnostics and latency may grow
for a long soft-wrapped paragraph; correctness remains authoritative.
