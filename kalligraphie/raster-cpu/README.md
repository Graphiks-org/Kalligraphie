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
identical bytes on every target. The conformance fingerprints that seal those
bytes (SHA-256) live in the `:kalligraphie:e2e` golden manifest, not here:
`glyph.outline.*`, `glyph.paint.*` and `glyph.bitmap.*` scenes for isolated
glyphs, `line.*` scenes for composed lines, and `sheet.*` scenes for alphabet
sheets.

## Tests

```bash
./gradlew :kalligraphie:raster-cpu:check
```

## Demonstration dumps

The composition dumps moved to the end-to-end module, which renders every golden
scene as a PGM/PPM image:

```bash
env KALLIGRAPHIE_E2E_DUMPS=true \
    KALLIGRAPHIE_E2E_DUMPS_OUTPUT=/tmp/kalligraphie-e2e \
    ./gradlew :kalligraphie:e2e:e2eGoldenDumps
```

- `KALLIGRAPHIE_E2E_DUMPS_OUTPUT` must be an **absolute** path **outside the
  repository**; any other value is rejected and no directory is created.
- The task always executes when invoked explicitly, and it is excluded from
  `check`.
- Without `KALLIGRAPHIE_E2E_DUMPS=true`, the task runs but writes nothing.
- One file is written per scene, named `<sceneId>.pgm` (coverage) or
  `<sceneId>.ppm` (color composited over white); the scene ids are declared in
  the end-to-end golden scene catalog.
- Composed sheets and lines are flipped vertically for readability inside their
  scene; canonicalization never flips again.
- Regenerating the committed fingerprints is a separate opt-in task:
  `./gradlew :kalligraphie:e2e:updateE2eGolden`.

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

