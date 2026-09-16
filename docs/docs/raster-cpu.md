# Raster CPU

Kalligraphie ships a deterministic CPU rasterizer used only by tests and
demonstrations. It never enters the consumer dependency graph of
`org.graphiks:kalligraphie`.

The module rasterizes the three certified portable representation routes:

- outlines (`GlyphOutlineIR`) into eight-bit coverage images;
- paint graphs (`GlyphPaintIR`) into non-premultiplied RGBA images with
  `SOURCE_OVER` composition;
- bitmap strikes (`BitmapGlyphIR`, `ALPHA_8`) with an explicit ink color.

Every operation enforces declared bounds before allocation and returns either an
immutable image or a typed failure (`InvalidRequest`, `LimitExceeded`). Curves
are flattened with a fixed tolerance, coverage uses sixteen fixed sub-pixel
samples, and composition uses integer arithmetic, so identical inputs produce
identical bytes on every platform.

The opt-in demonstration runner writes PGM and PPM images plus a manifest. Each
run publishes the raw single-glyph reference dumps, alphabet sheets for Latin,
Greek, Cyrillic, Arabic and Devanagari, color sheets (Bungee Color and
EmojiTwo), a normalized EBDT bitmap strike, and real text lines composed
through the paragraph facade — including a mixed multi-script line resolved by
three-font fallback. Composed sheets and lines are flipped vertically for
readability; the raw single-glyph dumps keep the rasterizer's source
orientation; the EBDT strike keeps its image orientation. The dedicated task
always executes when invoked explicitly:

```bash
env KALLIGRAPHIE_RASTER_DUMPS=true \
    KALLIGRAPHIE_RASTER_DUMPS_OUTPUT=/tmp/kalligraphie-raster \
    ./gradlew :kalligraphie:raster-cpu:rasterDumps
```

Without `KALLIGRAPHIE_RASTER_DUMPS=true`, the task still runs but writes nothing.

`KALLIGRAPHIE_RASTER_DUMPS_OUTPUT` must be an absolute path outside the
repository. The runner is excluded from `check`; it contains no performance
threshold.

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

