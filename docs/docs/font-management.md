# Font management

Kalligraphie currently provides a JVM-only OpenType font stack. It is designed
for deterministic font processing and planning; it is not a cross-platform
rendering backend.

## Supported on JVM

- Loading font data from bytes and files.
- Deterministic source provenance.
- SFNT/OpenType parsing.
- Font scaling.
- Unicode data processing.
- Text shaping and glyph planning.

Glyph planning produces renderer-neutral information. It does not imply GPU
upload or complete text rendering support.

## Not supported

The current stack does not provide:

- Android or iOS execution.
- GPU text contracts or GPU atlas upload.
- Native shaping bridges.
- Automatic system font fallback.
- A complete emoji or rendering claim.

Android and iOS require separate platform implementations and tests. GPU and
native integration remain deferred until a renderer or platform boundary needs
them.
