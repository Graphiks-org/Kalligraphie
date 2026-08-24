# Kanvas font import

- Source: https://github.com/ygdrasil-io/kanvas.git
- Commit: 71eb60ea270fab46dbcdcbc58bb923ddcfd8ef5b
- Imported modules: core, sfnt, colr, scaler, text, glyph and font.
- Excluded module: gpu-api.
- Excluded GPU bridge: glyph/color/ColorGlyphGpuHandoff.kt and its test.
- Package root: io.ygdrasil.kalligraphie.
- Fixtures: deferred pending a dedicated test plan.
- Excluded Core test: `FontTelemetrySchemaTest.kt`; it validates Kanvas report and GPU-handoff infrastructure that is outside this JVM Core/SFNT import and the explicitly excluded `gpu-api` scope.
- Excluded Core production source: `FontTelemetry.kt`; it exposed the Kanvas GPU telemetry contract (`GPUTextHandoff`, adapter/backend metadata and WebGPU samples) and its evidence writer, all outside the no-GPU Core/SFNT scope.
