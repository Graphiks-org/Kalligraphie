# Kanvas font import

- Source: https://github.com/ygdrasil-io/kanvas.git
- Commit: 71eb60ea270fab46dbcdcbc58bb923ddcfd8ef5b
- Imported modules: core, sfnt, colr, scaler, text, glyph and font.
- Excluded module: gpu-api.
- Excluded GPU bridge: glyph/color/ColorGlyphGpuHandoff.kt and its test.
- Package root: io.ygdrasil.kalligraphie.
- Fixtures: font/fixtures, copied from reports/font/fixtures with provenance and licenses unchanged.
- Core/SFNT golden fixtures: 10 JSON files in `font/fixtures/expected/pure-kotlin-text/upstream/`, copied byte-for-byte from `reports/pure-kotlin-text/` at the pinned commit because the imported Core/SFNT tests read them.
- Kalligraphie-derived goldens: 5 identity/catalog/SFNT JSON files at `font/fixtures/expected/pure-kotlin-text/`. Their fixture paths are rerooted from `reports/font/fixtures` to `font/fixtures`; because `FontSourceID` and `TypefaceID` include `originPath` in their deterministic preimages, the corresponding UUIDs and catalog hash are regenerated. The upstream bytes remain auditable in `upstream/`.
- Excluded Core test: `FontTelemetrySchemaTest.kt`; it validates Kanvas report and GPU-handoff infrastructure that is outside this JVM Core/SFNT import and the explicitly excluded `gpu-api` scope.

## Deferred GPU boundary

No file from font/gpu-api/ is present in this repository. The upstream GPU-only
bridges ColorGlyphGpuHandoff.kt and its test are excluded. A future renderer
integration must introduce a reviewed :font-gpu-contracts module rather than
adding GPU types to :font:glyph.
