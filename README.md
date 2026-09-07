# Kalligraphie

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple?logo=kotlin)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.6.1-blue?logo=gradle)](https://gradle.org)
[![AGP](https://img.shields.io/badge/AGP-9.0.0-green?logo=android)](https://developer.android.com/studio/releases/gradle-plugin)
[![Java](https://img.shields.io/badge/Java-25-red?logo=openjdk)](https://openjdk.org)
[![CI/CD](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions-blue?logo=github-actions)](https://github.com/features/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Contributing](https://img.shields.io/badge/Contributing-guide-purple)](CONTRIBUTING.md)
[![Projet: Planning](https://img.shields.io/badge/Statut-Planning-blue)](https://github.com)
[![Projet: Incubating](https://img.shields.io/badge/Statut-Incubating-orange)](https://github.com)
[![Projet: Stable](https://img.shields.io/badge/Statut-Stable-green)](https://github.com)
[![Projet: Deprecated](https://img.shields.io/badge/Statut-Deprecated-red)](https://github.com)
[![Projet: Archived](https://img.shields.io/badge/Statut-Archived-lightgrey)](https://github.com)

---

<!-- ==========================================
     BADGES DE STATUT DE PROJET PERSONNALISABLES
     Décommentez/copiez simplement le badge correspondant au statut actuel de votre projet.
     ========================================== -->

<!-- STATUT : EN PLANIFICATION (PLANNING) -->
<!-- [![Projet: Planning](https://img.shields.io/badge/Statut-Planning-blue)](https://github.com) -->

<!-- STATUT : INCUBATION / EN DÉVELOPPEMENT (INCUBATING) -->
<!-- [![Projet: Incubating](https://img.shields.io/badge/Statut-Incubating-orange)](https://github.com) -->

<!-- STATUT : STABLE / PRÊT PRODUCTION (STABLE) -->
<!-- [![Projet: Stable](https://img.shields.io/badge/Statut-Stable-green)](https://github.com) -->

<!-- STATUT : DEPRÉCIÉ (DEPRECATED) -->
<!-- [![Projet: Deprecated](https://img.shields.io/badge/Statut-Deprecated-red)](https://github.com) -->

<!-- STATUT : ARCHIVÉ (ARCHIVED) -->
<!-- [![Projet: Archived](https://img.shields.io/badge/Statut-Archived-lightgrey)](https://github.com) -->

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
