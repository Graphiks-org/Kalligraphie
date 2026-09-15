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
- The output directory receives:
  - `liberation-a-64.pgm` — P5 coverage of Liberation Sans `A` at 64 pixels per
    em;
  - `emoji-two-64.ppm` — P6 rendering of the EmojiTwo COLR v0 glyph at palette 0
    and 64 pixels per em, composited over white;
  - `manifest.md` — git commit, fixtures, pixels per em, and the SHA-256 digest
    and byte size of every image.
- Two runs at the same commit produce byte-identical outputs.

See the user guides in `docs/docs/raster-cpu.md` (English) and
`docs/docs/raster-cpu.fr.md` (French).
