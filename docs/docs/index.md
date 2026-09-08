# Kalligraphie documentation

Kalligraphie is a portable KMP typography library. Applications provide text,
fonts, and composition geometry; Kalligraphie publishes exact renderer-neutral
glyph and editing geometry. Its API is split into focused Gradle modules so
font contracts, Unicode analysis, shaping, layout, and glyph access can evolve
independently.

## Modules

- `:kalligraphie` is the public facade consumed by applications.
- `:kalligraphie:api` contains the portable public contracts and immutable value types.
- `:kalligraphie:unicode` provides canonical text decoding and the JVM reference Unicode analysis.
- `:kalligraphie:shaping` provides the reference JVM HarfBuzz adapter behind portable shaping contracts.
- `:kalligraphie:layout` positions shaped runs and provides exact editable-line, editable-paragraph, flow-region, and incremental geometry.

The `:kalligraphie:font:core`, `:kalligraphie:font:sfnt`,
`:kalligraphie:font:scaler`, and `:kalligraphie:font:glyph` modules are
internal implementation details. They are neither supported consumer artifacts
nor extension APIs; applications load embedded catalogs through
`Kalligraphie.embedded(...)` and use the public font contracts.

## Consumer guides

- [Font management](font-management.md) covers embedded catalogs, ordered
  fallback, exact editable lines, and detached render assets.
- [Editable paragraphs](editable-paragraphs.md) covers rectangular paragraphs,
  application-supplied `FlowRegion` exclusions, multi-region `FlowChain`
  composition, geometric line fragments, exact continuations, and bounded
  forward rematerialization.
- [Advanced typography](advanced-typography.md) covers derived glyph
  provenance, hyphenation, justification, tabs, inline objects, ellipsis, and
  vertical writing.

Kalligraphie does not create pages or render pixels. The application owns its
document, page objects and their global placement, viewport, scheduling,
renderer, and GPU resources.

## Useful commands

```bash
# Run the standard Gradle verification lifecycle.
./gradlew check

# Generate and embed the API reference (Dokka → MkDocs).
./gradlew :docs:embedDokkaIntoMkDocs

# Build the site locally.
mkdocs build -f docs/mkdocs.yml
```
