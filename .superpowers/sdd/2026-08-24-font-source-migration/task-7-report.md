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
