# raster-cpu

Deterministic CPU rasterizer used only by tests and demonstrations. It never
enters the consumer dependency graph of `org.graphiks:kalligraphie`.

## What it rasterizes

- outlines (`GlyphOutlineIR`) into eight-bit coverage images;
- solid paint graphs (`GlyphPaintIR`: `SolidOutline`, `Path`, `Group`) into
  non-premultiplied RGBA with `SOURCE_OVER` composition;
- bitmap strikes (`BitmapGlyphIR`, `ALPHA_8`) with an explicit ink color.

Gradients, clips, transforms, composites, and unbounded solids from paint schema
2/3 are refused with a typed `InvalidRequest("nodeKind", ...)`.

Every operation checks its declared `RasterLimits` before allocating and returns
either an immutable image (`A8Image`, `Rgba8Image`) or a typed
`RasterResult.Failure` (`InvalidRequest`, `LimitExceeded`). No exception crosses
the public API (`GlyphRasterizer`).

## Determinism

Fixed De Casteljau tolerance, sixteen fixed sub-pixel samples, and integer
composition — no locale or platform functions. Identical inputs produce
identical bytes on every target, and conformance fingerprints (SHA-256) are
sealed on the audited LiberationSans, EmojiTwo COLR v0, and EBDT format 1
fixtures.

## Tests

```bash
./gradlew :kalligraphie:raster-cpu:check
```

## Demonstration dumps

The opt-in runner writes binary PGM/PPM images plus a manifest:

```bash
env KALLIGRAPHIE_RASTER_DUMPS=true \
    KALLIGRAPHIE_RASTER_DUMPS_OUTPUT=/tmp/kalligraphie-raster \
    ./gradlew :kalligraphie:raster-cpu:rasterDumps
```

- `KALLIGRAPHIE_RASTER_DUMPS_OUTPUT` must be an **absolute** path **outside the
  repository**; any other value is rejected and no directory is created.
- The task always executes when invoked explicitly, and it is excluded from
  `check`.
- Without `KALLIGRAPHIE_RASTER_DUMPS=true`, the task runs but writes nothing.
- The output directory receives, per run:
  - `liberation-a-64.pgm` and `emoji-two-64.ppm` — raw single-glyph reference
    dumps in source orientation;
  - `sheet-liberation-latin-32.pgm`, `sheet-liberation-greek-32.pgm`,
    `sheet-liberation-cyrillic-32.pgm`, `sheet-amiri-arabic-32.pgm`,
    `sheet-noto-devanagari-32.pgm` — alphabet sheets, 16 columns, white ink on
    black;
  - `sheet-bungee-latin-48.ppm` and `sheet-emoji-two-64.ppm` — color sheets
    over white;
  - `glyph-ebdt-format1-16.ppm` — normalized bitmap strike over white;
  - `line-latin-48.pgm`, `line-greek-48.pgm`, `line-cyrillic-48.pgm`,
    `line-arabic-48.pgm`, `line-devanagari-48.pgm`, `line-mixed-48.pgm` — real
    text composed by the paragraph facade (shaping, BiDi, three-font fallback),
    white ink on black;
  - `manifest.md` — git commit, fixtures, pixels per em, image count,
    orientation note, and the SHA-256 digest and byte size of every image.
- Composed sheets and lines are flipped vertically for readability; the raw
  single-glyph dumps keep the rasterizer's source orientation, and the EBDT
  strike is image-oriented by construction.
- Two runs at the same commit produce byte-identical outputs.

See the user guides in `docs/docs/raster-cpu.md` (English) and
`docs/docs/raster-cpu.fr.md` (French).

## README logo

The repository logo assets are rasterized by this module as two separate
artefacts. The badge composes a filled rounded square with the Amiri `K` knocked
out of it and frames it on a 512 × 512 transparent canvas, so the mark drops into
avatars and favicons uncropped. The wordmark composes the Great Vibes wordmark
shaped through the pinned HarfBuzz backend on a transparent strip. Both are
flipped vertically into image orientation and rendered once per theme ink:

```bash
./gradlew :kalligraphie:raster-cpu:renderLogo
```

- The task writes four transparent PNGs — the badge as
  `docs/assets/kalligraphie-logo-light.png` and
  `docs/assets/kalligraphie-logo-dark.png`, the wordmark as
  `docs/assets/kalligraphie-wordmark-light.png` and
  `docs/assets/kalligraphie-wordmark-dark.png` — and a manifest beside them,
  inside the repository. No environment variable is required.
- It always executes when invoked explicitly and is excluded from `check`.
- `KalligraphieLogoConformanceTest` seals the rendered pixels and the committed
  files: a code change without regeneration, or an edited asset, fails `check`.
- The bundled Great Vibes fixture and its licence are recorded with pinned
  digests in `src/jvmTest/resources/fonts/great-vibes/PROVENANCE.md`.

