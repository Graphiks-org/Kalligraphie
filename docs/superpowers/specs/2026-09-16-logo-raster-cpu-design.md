# Kalligraphie logo rendered by the CPU rasterizer — design

## Purpose

Give the project a README logo that is produced by Kalligraphie itself: the
`raster-cpu` demonstration rasterizer composites a portable paint graph built
from real glyph outlines, and the resulting images are committed as README
assets under `docs/assets/`.

The logo is both an identity asset and a living demonstration of the certified
portable routes (outlines and paint graphs) working end to end with the
project's own shaping backend.

## Decisions

| Question | Decision |
| --- | --- |
| Logo form | Horizontal lockup: badge on the left, wordmark on the right |
| Badge letter | `K` from Amiri (already a repository fixture) |
| Wordmark | `Kalligraphie` from Great Vibes (new fixture) |
| Badge treatment | Filled rounded square, knocked-out `K` |
| Colour | Monochrome, never an accent colour |
| README theming | Two transparent PNGs selected with `<picture>` and `prefers-color-scheme` |
| Generation pipeline | Approach 1: demo generator inside `raster-cpu`, assets committed under `docs/assets/` |

Decisions rejected:

- A single opaque PNG on white: shows a white rectangle on GitHub's dark theme.
- A single transparent PNG in grey ink: poor contrast in both themes.
- Extending the existing `rasterDumps` runner: it is contractually scoped to
  disposable output outside the repository, and a manual copy step would let the
  committed asset drift from the code.
- A dedicated `:kalligraphie:logo` Gradle module: extra module and convention
  wiring for a single asset, while `raster-cpu` is already chartered for tests
  and demonstrations.

## Composition

A single line, left to right: rounded square, knocked-out `K`, wordmark.

| Element | Source | Treatment |
| --- | --- | --- |
| Rounded square | Hand-authored path (`GlyphPaintNode.Path`) | Ink fill, corner radius ≈ 22 % of the side, optical height matched to the `K` capital |
| `K` | Amiri, glyph `K` | Painted in the background colour over the square, optically centred |
| `Kalligraphie` | Great Vibes, shaped glyphs | Full ink, aligned to the badge's optical centre, a half-em gap after the badge |

Ink: pure black for the light variant, pure white for the dark variant, fully
transparent background in both. The README already carries eight colour badges;
a monochrome logo stays legible next to them.

Geometry: a tight ink bounding box with a uniform margin of 2 % of the width, so
the README display size is predictable. Target canvas width ≈ 1200 px (2× a
600 px display) with the height derived from the ink bounds.

Three constants are expected to be tuned after the first real render (see
Verification): the corner radius, the margin, and the size of the `K` inside the
square. All three are constants of the composer, not of the rasterizer.

### Font weight risk

The design mockups showed a bold `K`. The repository fixture is
`Amiri-Regular.ttf`, so the first render uses Regular. If the `K` reads as too
light inside the filled square, `Amiri-Bold.ttf` is added as a second documented
fixture and the composer switches to it. This is a deliberate checkpoint, not an
open question: the opt-in task makes the comparison immediate.

## Where things live

### Composition code

`kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/`

- `KalligraphieLogo.kt` — composes the `GlyphPaintIR` (square, `K`, wordmark) and
  renders both variants. No public API is touched.
- `KalligraphieLogoFonts.kt` — opens both font fixtures, shapes the wordmark with
  HarfBuzz, resolves glyph outlines, and translates glyphs to their positions.
- `PngEncoder.kt` — minimal deterministic PNG encoding (`java.util.zip.Deflater`
  and `CRC32`, fixed PNG filters, no platform-dependent setting).
- `KalligraphieLogoDumpTest.kt` — the opt-in runner that writes the files.

### Font fixture

`kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes/`

- `GreatVibes-Regular.ttf`
- `OFL.txt`
- `PROVENANCE.md` — upstream project, pinned revision, source URL, SHA-256 of the
  font and of the licence, byte size; identical in form to the existing fixtures.

Amiri is not duplicated: `raster-cpu` already receives `:kalligraphie`'s
`jvmTest` resources through the existing `jvmTestProcessResources` copy task.

### Committed assets

`docs/assets/` (first content of that directory)

- `kalligraphie-logo-light.png`
- `kalligraphie-logo-dark.png`
- `kalligraphie-logo.manifest.md` — git commit, fonts and their SHA-256 values,
  the render fingerprint, and the SHA-256 and byte size of each PNG.

### Gradle wiring

`kalligraphie/raster-cpu/build.gradle.kts` gains an opt-in `renderLogo` task,
modelled on `rasterDumps`: it is excluded from `check`, always executes when
invoked explicitly, and writes into the repository's `docs/assets/` directory
resolved from the Gradle project root. The "outside the repository" contract of
`rasterDumps` is untouched: the two tasks have different purposes — one discards
demonstration dumps, the other produces a versioned asset.

### README

The `<picture>` block is placed directly under `# Kalligraphie`, above the badge
row:

```html
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kalligraphie-logo-dark.png">
  <img alt="Kalligraphie" src="docs/assets/kalligraphie-logo-light.png" width="600">
</picture>
```

Scope: the README only. Reusing these PNGs for the MkDocs site logo or favicon is
possible later and explicitly out of scope for this change.

## Data flow

```text
GreatVibes-Regular.ttf ─┐
                        ├─► Kalligraphie.embedded(...) ─► catalog ─► face ─► instance
Amiri-Regular.ttf ──────┘                                          │
                                                                   ├─► resolver ─► render asset ─► glyph outlines
                                                                   │
                              "Kalligraphie" ─► JvmHarfBuzzShapingBackend.shape(...)
                                                    │
                                                    └─► glyph ids + positions (kerning, script joins)
                                                                   │
                                                                   ▼
                     Amiri `K` outlines (translated)  ─┐
                     Great Vibes outlines (translated) ─┼─► GlyphPaintIR :
                     rounded-square path              ─┘    Group [ Path, SolidOutline K, SolidOutline… ]
                                                                   │
                                                                   ▼
                    GlyphRasterizer.rasterizePaint(ink = black) ─► Rgba8Image ─► PNG ─► …-light.png
                    GlyphRasterizer.rasterizePaint(ink = white) ─► Rgba8Image ─► PNG ─► …-dark.png
                                                                   │
                                                                   ▼
                    manifest: RGBA buffer digest + SHA-256 and size of every written file
```

Key properties:

- **Shaping is required.** Great Vibes is a connected script; without GPOS the
  letters do not join. The wordmark therefore goes through the project's real
  HarfBuzz backend, never through naive advance-width placement.
- **Each font declares its own `unitsPerEm`** (Amiri declares 1000; Great Vibes
  is read from its `head` table, not assumed). Every `SolidOutline` carries its
  own `unitsPerEm`, so relative scale stays correct at a single `pixelsPerEm`;
  glyph translations are computed in font units and converted.
- **No transforms.** The CPU rasterizer refuses `Transform` nodes, so glyph
  outlines are translated on their coordinates before the graph is built,
  not positioned by a node.
- **Paint order is child order** in the `Group` under `SOURCE_OVER`: square,
  knocked-out `K`, then the wordmark. Everything outside the ink stays fully
  transparent.
- **Nothing is written outside `docs/assets/`**; the path is derived from the
  Gradle project root.

## Errors and bounds

No exception crosses the public API — that is the module's existing rule.

- Every call (`Kalligraphie.embedded`, `resolveFace`, `instantiate`,
  `acquireRenderAsset`, `resolveGlyph`, `shape`, `rasterizePaint`) returns a
  `FontOperationResult` or a `RasterResult`. The composer propagates the first
  `Failure` as an explicit test error carrying the original field and detail.
- The runner fails loudly. A partial or silently degraded logo is worse than a
  failure.
- Bounds are declared explicitly rather than inherited from defaults:
  - `OutlineProfile` sized for Great Vibes (many curve segments) and Amiri;
  - `RasterLimits.maxPixelsPerImage` sized to the real canvas (~1200 × ~400 px);
  - `maxContours` / `maxTotalPoints` sized for the sum of wordmark glyphs plus
    the square;
  - refusal on exceedance, never a silent crop.
- The render is checked to be non-empty and its ink bounds strictly inside the
  canvas; a clipped logo fails generation.
- The encoder rejects zero dimensions and any buffer whose size does not match
  the declared geometry.
- `renderLogo` fails with an actionable message if `docs/assets` cannot be
  created or written.
- The dark variant is composed independently with its own ink; it is never a
  filtered copy of the light PNG, which would produce inverted anti-aliasing
  artefacts.

## Verification

Two levels, so the committed asset cannot drift from the code.

### Sealed fingerprint (non-opt-in, runs in `check`)

`KalligraphieLogoConformanceTest`:

- re-composes and re-renders both variants in memory and compares the SHA-256 of
  the RGBA buffer against constants sealed in the test — the convention already
  used for the LiberationSans, EmojiTwo COLR v0, and EBDT format 1 conformance
  fingerprints;
- compares the SHA-256 of the committed PNGs against the digests recorded in
  `kalligraphie-logo.manifest.md`, so a hand-edited PNG or a code change without
  regeneration fails `check`;
- compares `GreatVibes-Regular.ttf` and `OFL.txt` against the SHA-256 values in
  their `PROVENANCE.md`, matching the repository's fixture discipline.

### Opt-in generator (excluded from `check`)

```bash
./gradlew :kalligraphie:raster-cpu:renderLogo
```

Regenerates both PNGs and the manifest into `docs/assets/`, then asserts — like
the existing demonstration runner — that two successive renders are identical
byte for byte.

### Deliberately not tested

- PNG file bytes are not compared across JDKs: `Deflater` depends on the zlib
  version bundled with the runtime. What is guaranteed is **pixel stability**
  (sealed fingerprint) and **committed file integrity** (SHA-256 in the
  manifest). Together they give the same practical guarantee without a fragile
  test.
- Aesthetic quality has no automated threshold. Visual validation is a manual
  checkpoint: the real PNGs are opened in the browser and the corner radius,
  margin, and `K` size are tuned if needed.

### CI

Nothing to add. The conformance test joins `jvmTest`, already executed by
`./gradlew check`; the `renderLogo` task is excluded like the other opt-in tasks.

## Out of scope

- MkDocs site logo, favicon, and social preview images.
- Any colour accent, gradient, or second logo variant.
- Changes to the public API of `raster-cpu`, `:kalligraphie`, or any other module.
- Subsetting or modifying the bundled fonts.
