# Raster CPU

Kalligraphie ships a deterministic CPU rasterizer used only by tests and
demonstrations. It never enters the consumer dependency graph of
`org.graphiks:kalligraphie`.

The module rasterizes the three certified portable representation routes:

- outlines (`GlyphOutlineIR`) into eight-bit coverage images;
- paint graphs (`GlyphPaintIR`) into non-premultiplied RGBA images with
  `SOURCE_OVER` composition;
- bitmap strikes (`BitmapGlyphIR`) as `ALPHA_8` pixels tinted by the explicit
  ink color, or as straight non-premultiplied `RGBA_8888` pixels copied
  unchanged with the ink ignored.

Every operation enforces declared bounds before allocation and returns either an
immutable image or a typed failure (`InvalidRequest`, `LimitExceeded`). Curves
are flattened with a fixed tolerance, coverage uses sixteen fixed sub-pixel
samples, and composition uses integer arithmetic, so identical inputs produce
identical bytes on every platform.

The conformance fingerprints that seal those bytes live in the end-to-end module
(`:kalligraphie:e2e`), not here: its golden manifest records a SHA-256 for
isolated glyphs (outline, paint, bitmap), for alphabet sheets across Latin,
Greek, Cyrillic, Arabic and Devanagari, and for real text lines composed through
the paragraph facade — including a mixed multi-script line resolved by
three-font fallback.

That module also hosts the opt-in demonstration runner, which writes one PGM or
PPM image per golden scene. Composed sheets and lines are flipped vertically for
readability; the raw single-glyph dumps keep the rasterizer's source orientation;
the EBDT strike keeps its image orientation. The dedicated task always executes
when invoked explicitly:

```bash
env KALLIGRAPHIE_E2E_DUMPS=true \
    KALLIGRAPHIE_E2E_DUMPS_OUTPUT=/tmp/kalligraphie-e2e \
    ./gradlew :kalligraphie:e2e:e2eGoldenDumps
```

Without `KALLIGRAPHIE_E2E_DUMPS=true`, the task still runs but writes nothing.

`KALLIGRAPHIE_E2E_DUMPS_OUTPUT` must be an absolute path outside the repository.
The runner is excluded from `check`; it contains no performance threshold.
Regenerating the committed fingerprints is a separate opt-in task,
`./gradlew :kalligraphie:e2e:updateE2eGolden`.

The repository logo assets are produced by the same module as two separate
artefacts. The badge composes a filled rounded square with the Amiri `K` knocked
out of it on a 512 × 512 transparent canvas for avatars and other uses; the
wordmark composes the Great Vibes wordmark shaped through the pinned HarfBuzz
backend on a transparent strip used by the repository README. Both are flipped
vertically into image orientation and padded before rendering once per theme ink:

```bash
./gradlew :kalligraphie:raster-cpu:renderLogo
```

The task writes the four transparent PNGs and a manifest under `docs/assets/`
inside the repository, runs without any environment variable, and is excluded
from `check`. `KalligraphieLogoConformanceTest` seals the rendered pixels and the
committed files, so a code change without regeneration, or an edited asset, fails
`check`.

