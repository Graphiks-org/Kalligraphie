# Task 6 report — glyph planning without GPU contracts

## Source

- Upstream: `https://github.com/ygdrasil-io/kanvas.git`
- Pinned commit: `71eb60ea270fab46dbcdcbc58bb923ddcfd8ef5b`

## Imported files

- `font/glyph/src/main/kotlin/io/ygdrasil/kalligraphie/glyph/GlyphMaskBlur.kt`
- `font/glyph/src/main/kotlin/io/ygdrasil/kalligraphie/glyph/GlyphMaskKey.kt`
- `font/glyph/src/main/kotlin/io/ygdrasil/kalligraphie/glyph/GlyphSurface.kt`
- `font/glyph/src/main/kotlin/io/ygdrasil/kalligraphie/glyph/color/ColorGlyphSurface.kt`
- Glyph tests under `font/glyph/src/test/kotlin/io/ygdrasil/kalligraphie/glyph/`

`ColorGlyphGpuHandoff.kt` and `ColorGlyphGpuHandoffTest.kt` were not copied.

## Decisions

- Glyph and colour planners now consume `ShapedGlyphRun` and use `glyphIds`; no GPU type occurs in a planner signature or test helper.
- `packAtlasItems` uses the prescribed deterministic padded row cursor. `GlyphSurfaceTest` adds a two-row assertion for the first second-row placement.
- The existing `font/glyph/build.gradle.kts` already had the required non-GPU dependency graph, so it was retained unchanged; `:font:gpu-api` was not introduced.
- Persisted upstream schema strings and fixture content remain unchanged. Test fixture lookup maps the local fixture root from `reports/font/fixtures` to `font/fixtures`.
- For the plan evidence `runId`, the renderer-neutral `ShapedGlyphRun.typefaceId` is used when available; this preserves deterministic plan evidence without reintroducing a GPU run descriptor.

## Verification

- RED: `rtk ./gradlew --no-daemon :font:glyph:test` failed before the replacement because the copied upstream sources referenced unavailable GPU contracts.
- GREEN: `rtk ./gradlew --no-daemon :font:glyph:test` succeeded (154 tests).
- GPU boundary: `! rtk rg -n 'org\\.graphiks\\.kanvas\\.glyph\\.gpu|GPUGlyphRunDescriptor|GPUTextAtlas' font/glyph --glob '*.kt'` succeeded with no matches.
- Package-import boundary: no `package` or `import` remains under `org.graphiks.kanvas.{font,glyph,text}` in `font/glyph` Kotlin files.
