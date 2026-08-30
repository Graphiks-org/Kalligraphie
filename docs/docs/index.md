# Kalligraphie documentation

Kalligraphie is a portable KMP font library. Its API is split into focused Gradle modules so font contracts, SFNT parsing, metrics, and glyph access can evolve independently.

## Modules

- `:kalligraphie` is the public facade consumed by applications.
- `:kalligraphie:api` contains the portable public contracts and immutable value types.
- `:kalligraphie:unicode` provides canonical text decoding and the JVM reference Unicode analysis.
- `:kalligraphie:font:core` provides font sources, faces, and instances.
- `:kalligraphie:font:sfnt` parses bounded SFNT and OpenType data.
- `:kalligraphie:font:scaler` resolves metrics and TrueType outlines.
- `:kalligraphie:font:glyph` materializes detached render assets.

## Useful commands

```bash
# Run all JVM tests for the font stack.
./gradlew :kalligraphie:fontTest

# Generate and embed the API reference (Dokka → MkDocs).
./gradlew :docs:embedDokkaIntoMkDocs

# Build the site locally.
mkdocs build -f docs/mkdocs.yml
```
