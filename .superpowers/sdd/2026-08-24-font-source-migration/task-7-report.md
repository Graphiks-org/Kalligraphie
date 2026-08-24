# Task 7 report — renderer-neutral font atlas façade

## Source

- Upstream: `https://github.com/ygdrasil-io/kanvas.git`
- Pinned SHA: `71eb60ea270fab46dbcdcbc58bb923ddcfd8ef5b`
- `LiberationSans-Regular.ttf` blob SHA: `e6339859d0b24bee79ae3f64e0071900170224ba`

## Imported files

- `font/src/main/kotlin/io/ygdrasil/kalligraphie/font/atlas/GlyphAtlasUploadPlan.kt`
- `font/src/main/kotlin/io/ygdrasil/kalligraphie/font/glyph/{A8Rasterizer.kt,GlyphCache.kt,GlyphStrikeKey.kt}`
- `font/src/main/kotlin/io/ygdrasil/kalligraphie/font/handoff/GlyphRunDescriptor.kt`
- `font/src/test/kotlin/io/ygdrasil/kalligraphie/font/{atlas/GlyphAtlasUploadPlanTest.kt,glyph/A8RasterizerTest.kt}`
- `font/src/test/resources/fonts/liberation/LiberationSans-Regular.ttf`

## Decisions

- `GlyphAtlasPacker` uses the local private `AtlasCursor` shelf packer; no GPU contract or type is imported.
- The façade `io.ygdrasil.kalligraphie.font.glyph.GlyphStrikeKey` remains distinct from `io.ygdrasil.kalligraphie.glyph.GlyphStrikeKey`.
- The former GPU-comparison test now verifies the observable deterministic shelf placements, exact-boundary placement, and overflow refusal without a GPU dependency.
- `font/build.gradle.kts` already declared the required renderer-neutral dependency graph, so it was retained unchanged.

## Verification

- RED: `rtk ./gradlew --no-daemon :font:test` failed on the absent `GPUTextAtlasPageCursor` and `GPUTextAtlasRectItem` contracts.
- GREEN: `rtk ./gradlew --no-daemon :font:test` succeeded (19 tests).
- Aggregate: `rtk ./gradlew --no-daemon :font:test :font:fontTest` succeeded.
- GPU boundary: `! rtk rg -n 'glyph\\.gpu|GPUTextAtlasPageCursor|GPUTextAtlasRectItem' font/src --glob '*.kt'` succeeded with no matches.
- Hygiene: `rtk git diff --check` succeeded.

## P1 follow-up — safe atlas-cursor arithmetic

- `AtlasCursor` promotes horizontal and vertical bounds calculations to `Long` before updating coordinates.
- `packer wraps at Int maximum width without placing outside the atlas` uses zero-height bitmaps of widths `Int.MAX_VALUE` and `1`, so it allocates no large pixel buffer. It proves the second placement wraps to `x = 0` and every region stays within the atlas.
- RED: the focused test failed with the previous overflowing `Int` addition. GREEN: the focused test succeeded after the `Long` calculations.

## Post-review follow-up — valid A8 bitmap inputs

- `A8Bitmap` now rejects negative dimensions, a pixel count beyond `Int.MAX_VALUE`, and any buffer whose size differs from `width * height`; the multiplication uses `Long`.
- Constructor invariants make an invalid `A8Bitmap` impossible to pass to `GlyphAtlasPacker`, so it cannot create a placement with negative dimensions or a short pixel buffer.
- RED: the new negative-width, empty-`1x1`-buffer, and non-representable-buffer tests all failed before validation. GREEN: the focused `A8RasterizerTest` succeeded after validation.
