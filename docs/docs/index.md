# Kalligraphie documentation

Kalligraphie is a JVM font library. Its API is split into focused Gradle modules so parsing, shaping, scaling, and glyph access can evolve independently.

## Modules

- `:font:core` provides the common font primitives.
- `:font:sfnt` parses SFNT and OpenType data.
- `:font:colr` handles COLR color-font tables.
- `:font:scaler` scales and rasterizes glyphs.
- `:font:text` shapes text.
- `:font:glyph` exposes the glyph-oriented API.
- `:font` aggregates the public modules for consumers.

## Useful commands

```bash
# Run all JVM tests for the font stack.
./gradlew :font:fontTest

# Generate and embed the API reference (Dokka → MkDocs).
./gradlew :docs:embedDokkaIntoMkDocs

# Build the site locally.
mkdocs build -f docs/mkdocs.yml
```
