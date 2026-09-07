# Kalligraphie

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple?logo=kotlin)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.6.1-blue?logo=gradle)](https://gradle.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Contributing](https://img.shields.io/badge/Contributing-guide-purple)](CONTRIBUTING.md)

Kalligraphie is a renderer-independent Kotlin Multiplatform typography
library. Applications provide immutable text, fonts, typographic options, and
composition geometry; Kalligraphie returns exact glyph, caret, selection, and
hit-testing geometry without retaining platform or renderer resources.

The JVM reference route includes:

- canonical Unicode decoding, paragraph-level BiDi analysis, UAX #14 line
  breaking, and HarfBuzz shaping;
- ordered multi-font fallback, OpenType features, hyphenation, justification,
  tabs, ellipsis, inline objects, and horizontal or vertical writing;
- editable paragraph geometry and incremental layout after text or typography
  changes;
- application-supplied `FlowRegion` exclusions and ordered `FlowChain`
  composition, including geometric `LineFragment` output;
- deterministic fragmentation constraints and structured diagnostics;
- identity-attested continuations and bounded forward rematerialization with an
  explicit unmaterialized suffix.

## Consumer route

Applications consume the JVM facade from `org.graphiks:kalligraphie`.
`JvmEditableParagraphFacade` composes a rectangular paragraph. For composition
through exclusions and multiple regions, build a portable
`IncrementalFlowLayoutRequest` and pass it to `JvmFlowCompositionFacade`:

```kotlin
val portable = requireFlowSuccess(createIncrementalFlowLayoutRequest(
    input = LayoutInput(snapshot, typography),
    requestedRange = visibleSourceRange,
    constraints = paragraphConstraints,
    flowChain = FlowChain(applicationRegions),
    overscan = LineOverscan(2),
))

val result = JvmFlowCompositionFacade.layout(
    JvmFlowCompositionRequest(
        request = portable,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
    ),
)
```

`requireFlowSuccess(...)` represents application code that unwraps
`FlowCompositionResult.Success`; production code must handle every typed
`FlowCompositionResult.Failure`.

Each successful `FlowLayout` publishes complete `ParagraphFragment` values in
source order. A logical `LineLayout` may contain several geometric
`LineFragment` values when an exclusion splits the available inline space.
BiDi resolution and line selection still happen once for the logical line;
clusters and inline objects remain indivisible. Editing methods use the final
fragment geometry, so selection never fills an excluded gap and hit testing in
that gap selects the nearest valid caret deterministically.

Kalligraphie composes only inside the supplied regions. The application still
owns the mutable document, pages, global page placement, viewport, scrolling,
scheduling, renderer, and GPU work.

See the [editable paragraph guide](docs/docs/editable-paragraphs.md) for region
invariants, continuations, fragmentation, and incremental flow composition.
The [French guide](docs/docs/editable-paragraphs.fr.md) covers the same
consumer route.

## Modules

- `:kalligraphie` — public application facade;
- `:kalligraphie:api` — portable immutable contracts;
- `:kalligraphie:unicode` — canonical decoding and JVM Unicode analysis;
- `:kalligraphie:shaping` — portable shaping contracts and the JVM HarfBuzz
  adapter;
- `:kalligraphie:layout` — editable line, paragraph, flow, and incremental
  composition;
- `:kalligraphie:font:*` — font sources, SFNT/OpenType parsing, metrics,
  outlines, and detached render assets.

## Build and documentation

```bash
# Run the standard verification lifecycle.
./gradlew check

# Generate and embed the API reference into MkDocs.
./gradlew :docs:embedDokkaIntoMkDocs

# Build the documentation site after installing MkDocs Material.
mkdocs build -f docs/mkdocs.yml
```

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md), the
[Code of Conduct](CODE_OF_CONDUCT.md), and the [security policy](SECURITY.md)
before submitting a change. Notable changes are recorded in
[CHANGELOG.md](CHANGELOG.md).
