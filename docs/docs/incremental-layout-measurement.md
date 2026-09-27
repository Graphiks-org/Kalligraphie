# Incremental layout measurement

Kalligraphie measures incremental paragraph layout in the non-published
`:kalligraphie:bench` module. It is not a functional latency test and not a
published benchmark result. The profiles execute the real
`JvmIncrementalParagraphLayoutSession`, Unicode analysis, embedded HarfBuzz, and
the checked-in DejaVu and Amiri font fixtures.

They belong to the module's paragraph half: they need the `END_TO_END_LAYOUT`
capability, which every platform declares since the facades and the portable
Unicode analysis became `commonMain` code, so they run on the JVM, on Android and
on the iOS simulator. The one profile a platform cannot serve is reported as
deferred, by name, instead of the module silently publishing fewer profiles.

The timed interval starts immediately before `session.layout(...)`. Snapshots,
font catalogs, deltas, and requests are constructed before the clock starts.
The interval ends only after a successful result has complete requested
coverage and its lines, runs, glyphs, carets, diagnostics, and tail state have
been consumed. For `Cancellation`, the profile latency still covers call entry
through the typed cancellation return, while the separate cancellation-delay
field covers the first cancellation signal through that return. Application
scheduling and rendering are excluded.

## Profiles

- `InteractiveEdit` alternates a prepared `cafe`/emoji replacement through one
  session and reuses the latest published state.
- `ViewportLayout` alternates two requested text ranges with two complete lines
  of overscan while retaining the same immutable snapshot.
- `Cancellation` requests the full corpus and signals cooperative cancellation
  after a fixed number of token checks. Only a typed cancelled result is
  accepted; no partial coverage counts as a fast success.

Each profile gets a new session. Untimed seed work, where applicable, and the
harness warm-up precede measured iterations. Every profile, including
`Cancellation`, completes one untimed, uncancelled full-layout seed in that
session before it may report a warm cache state. Two explicit `System.gc()`
requests are made before and after each profile, never between measured
iterations.

## Reproducible invocation

The module measures with kotlinx-benchmark (JMH on the JVM): warm-up, iterations,
the one-second iteration time and the JSON report format come from its benchmark
configuration, not from environment variables. One command measures every
profile the platform serves — these three are part of the thirty-nine the module
records, and the iOS simulator runs thirty-eight of them. Results and counters are
written under the module's `build` directory, which git ignores:

```bash
./gradlew :kalligraphie:bench:jvmBenchmarkBenchmark
```

`./gradlew :kalligraphie:bench:measurementReport` then joins that run with the
other platforms into `build/bench/report-jvm.md` and a comparison. Neither task
is in `check`: a measurement is requested, never scheduled, and no functional
test asserts an elapsed time.

## Report fields

The Markdown report records, for the whole run:

- measured commit, machine, operating system, runtime, and cache/GC policy;
- corpus identifier, description, and the SHA-256 of every fixture actually
  read;
- and, for every profile: its route, timed boundary, cache state, warm-up and
  measured iteration counts, nearest-rank p50, p95, and p99 latency in
  nanoseconds, the counters proving what the timed operation consumed, and
  memory figures each labelled `measured`, `estimated` or `unavailable`.

The counters include the scalars, lines and paragraphs the successful profiles
rematerialized, and the maximum cancellation delay `Cancellation` observed from
the first in-operation signal to the typed cancellation return — distinct from
that profile's total latency. The maximum is published rather than the last
reading: across millions of operations the final one can observe the
cancellation below the clock's resolution, and a zero would be refused by the
profile contract as a no-op.

Use this structure when copying a result into a review description:

```text
Commit / machine / OS / runtime / cache policy:
Corpus / SHA-256:
Profile route / timed boundary / cache state:
Warmup / iterations:
p50 / p95 / p99:
Consumed counters:
Memory figures (measured / estimated / unavailable):
Limits:
```

Allocation and retained-memory figures measure this small harness, not universal
heap or native-memory accounting. A signed retained-heap delta can be negative
after the documented GC policy. Native retained bytes are published
`unavailable` with their reason, never estimated.

## Interpretation and limits

The measurement emits observations, not pass/fail thresholds. A result supports
a performance claim only when its full environment and reference-profile policy
are identified separately. Functional Gradle checks never assert elapsed time.

The current session accepts checkpoint reuse only from its own latest
publication. Exact line selection may conservatively inspect through the next
mandatory UAX #14 boundary, or through document end when no mandatory boundary
remains. Consequently, the rematerialization diagnostics and latency may grow
for a long soft-wrapped paragraph; correctness remains authoritative.
