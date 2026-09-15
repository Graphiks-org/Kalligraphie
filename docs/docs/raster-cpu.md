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

The opt-in demonstration runner writes PGM and PPM images plus a manifest. The
dedicated task always executes when invoked explicitly:

```bash
env KALLIGRAPHIE_RASTER_DUMPS=true \
    KALLIGRAPHIE_RASTER_DUMPS_OUTPUT=/tmp/kalligraphie-raster \
    ./gradlew :kalligraphie:raster-cpu:rasterDumps
```

`KALLIGRAPHIE_RASTER_DUMPS_OUTPUT` must be an absolute path outside the
repository. The runner is excluded from `check`; it contains no performance
threshold.
