# COLR v1 variable — complete the end-to-end visual proof

Design spec. Date: 2026-09-28. Status: revised after deep review, pending written-spec review.

## 1. Problem

The end-to-end expectation catalog pins the colour entry `color.colr-v1-variable` as
`NotYet` / `RejectedAt(FACE_RESOLUTION, "font.unsupported-representation-profile")`. The
portable COLR v1 variable IR is produced and covered by representation tests, but **no pixel
is ever composited**: the CPU compositor used by the catalog,
`kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/PaintCompositor.kt`,
handles only `SolidOutline`, `Path`, and `Group` with `SOURCE_OVER`. Every other node kind —
`GlyphClip` included — is refused with `RasterRequestRejected("nodeKind", …)`, as the module
README states.

So the visual claim is incomplete: the catalog can only assert a typed rejection, not a
rendered result. The `claimed-tables.json` exemption for the fixture's `glyf`/`loca` compounds
the problem by attributing the face-resolution refusal to the compositor's `GlyphClip`
refusal, which conflates two distinct stages.

## 2. Intended outcome

Complete the visual E2E proof for variable COLR v1 by making the catalog's CPU compositor
compose the node kinds the variable fixture actually carries, then promote the catalog entry
to `Supported` with a committed golden fingerprint.

Success criteria:

- `PaintCompositor` composes `GlyphClip`, `Solid`, `LinearGradient`, and `Transform`
  deterministically, with identical bytes on the JVM, the Android device runtime, and the iOS
  simulator.
- A new catalogued scene renders the `kalligraphie-var-colr` fixture at two `wght` instances
  and asserts, per raw glyph raster, that the instances differ, so a silent variation loss
  cannot be pinned as a plausible picture.
- The catalog entry becomes `Supported` with an auto-sized frame, scene id, route, and family;
  its existing face-resolution probe is removed.
- The manifest, the catalog matrix (EN/FR), and the claims export are regenerated; the
  `glyf`/`loca` exemption is removed and those tables are claimed instead.
- `./gradlew check` is green and the CI golden verification passes on every platform.

## 3. Scope

In scope:

- `PaintCompositor` support for `GlyphClip`, `Solid`, `LinearGradient`, `Transform`.
- Exact per-sample clip intersection, including root `clipBounds` and nested clips.
- Deterministic integer gradient shading honouring `LINEAR_SRGB` + `PREMULTIPLIED`.
- One new catalogued golden scene plus the catalog/claims/docs regeneration.

Out of scope (stay refused with a typed diagnostic, or documented as unproven):

- `PathClip`, `RadialGradient`, `SweepGradient`, `Composite`.
- Composition modes other than `SOURCE_OVER`.
- A clip applied to a `Group` (a composite subtree): clipping a composite correctly needs
  subtree-coverage support; the fixture has no such shape, so this is refused rather than
  mis-composited. See §5.5.
- Linear-light RGB compositing for `Group`/`Composite`: the historical integer sRGB
  `SOURCE_OVER` is preserved and pinned; COLR v1 linear-light blending is a separate change.
- CFF/CFF2-in-COLR, variable CPAL.
- Any new public `RasterLimits` field.

## 4. Current state (evidence)

- `PaintCompositor.build` switches on `SolidOutline`, `Path`, `Group`; the `else` throws
  `RasterRequestRejected("nodeKind", …)`.
- The fixture `test-fixtures/fonts/kalligraphie-var-colr/KalligraphieVarCOLRv1.ttf` carries
  `COLR`, `CPAL`, `OS/2`, `cmap`, `fvar`, `glyf`, `head`, `hhea`, `hmtx`, `loca`, `maxp`,
  `name`, `post` (no `gvar`: only the COLR paint varies).
- `VariableColrV1RepresentationTest` shows the fixture's glyphs: `0x41` =
  `GlyphClip → LinearGradient` (REPEAT, stops advancing with `wght`), `0x42` =
  `GlyphClip → Solid` (opacity `1.0 → 0.5`), `0x43` = `GlyphClip → Transform → Solid`, with
  `clipBounds` varying with `wght`.
- The E2E paint profile (`E2eFontFixture.paintProfile`) accepts only `SOLID_OUTLINE` and
  `GROUP`, so the COLR v1 face is rejected before any outline is decoded.
- `SceneFramePolicy.Pinned` is reserved for frames migrated verbatim
  (`ExpectationCatalogRatchetTest.noNewEntryMayPinItsFrame`), and `CatalogProbes.byId`
  registers a `color.colr-v1-variable` face-resolution probe that must go when the entry is
  promoted (`everyProbeableEntryHasAProbeAndEveryProbeHasAProbeableEntry`,
  `everyProbeAgreesWithItsDeclaredStatus`).

## 5. Design

### 5.1 Clip as exact per-sample intersection

Clipping is applied by sampling, at the rasterizer's existing sixteen fixed sub-pixel
positions (`CoverageRaster.kt`), not by multiplying coverage images. Multiplying two A8
masks squares coincident edges and over-counts overlapping layers, so it is rejected.

The compositor propagates a context:

```
Context(
    clips: List<ClipRegion>,              // accumulated device-space clip regions
    transform: GlyphAffineTransform,      // node-local -> parent design space, accumulated
    unitsPerEm: Int,                      // scale for paints with no outline of their own
)
```

`clips` is empty at the root, which means "unbounded". A `ClipRegion` holds device-space
flattened contours and the scale used to obtain them.

For any **leaf**:

- coverage = fraction of the sixteen samples that are inside the leaf's own winding (when the
  leaf is bounded) **and** inside every `ClipRegion` winding in `clips`;
- a `Solid`/gradient leaf has no own geometry, so coverage is the fraction inside every clip
  winding; with `clips` empty it is refused (§5.5);
- the accumulated clips are therefore intersected exactly once per sample, so a clip is never
  squared and nested clips intersect correctly.

A `GlyphClip` appends its device-space outline contours to `clips` and recurses. A
`Transform` composes its matrix and recurses; because `clips` are stored in device space when
introduced, a transform above or below a clip is handled uniformly. A `Group` composites its
children with the existing integer `SOURCE_OVER`; a `Group` reached with a non-empty `clips`
is refused (§5.5).

The root `GlyphPaintIR.clipBounds`, when present, is converted to a rectangular `ClipRegion`
in device space and appended to `clips`, so it clips the root result for every node kind —
including a bare `SolidOutline`/`Path` — with fractional edges sampled like any other clip.

Device mapping for a local design point is `origin + scale * transform(point)`.

### 5.2 Node semantics

| Node | Behaviour |
| --- | --- |
| `SolidOutline` | Flatten `outline.contours` with `context.transform`; coverage = samples inside its winding ∩ every clip; tint `color`. |
| `Path` | Flatten `path.commands` with `context.transform`; coverage = samples inside its winding ∩ every clip; tint `color`. |
| `Solid` | Require non-empty `clips`; coverage = samples inside every clip; RGB = `color`, A = `color.alpha × opacity × coverage`. |
| `LinearGradient` | Require non-empty `clips`; coverage as `Solid`; RGB from the colour line at the pixel centre; A = interpolated alpha × coverage. |
| `GlyphClip` | Flatten its outline with `context.transform`; append to `clips` with `unitsPerEm = outline.unitsPerEm`; recurse on `paint`. |
| `Transform` | `transform = compose(outer = context.transform, inner = matrix)`; recurse on `paint`. |
| `Group` | With empty `clips`, composite children in list order `SOURCE_OVER`; with non-empty `clips`, refuse. |

`scale = pixelsPerEm / unitsPerEm`. `SolidOutline` and `GlyphClip` use their own outline's
`unitsPerEm`; `Path` uses the request-level `unitsPerEm`; `Solid`/gradients use the
`context.unitsPerEm` fixed by the nearest enclosing `GlyphClip` (or the request at the root).

An affine map of a **linear** gradient keeps it linear, so mapping `p0/p1/p2` to device and
shading there is exact.

**Known fixture property:** `0x43` is `GlyphClip → Transform → Solid`. A transform on a
uniform solid is visually inert. The E2E scene therefore proves `GlyphClip`, `Solid`, and
`LinearGradient` visually; `Transform`'s pixel effect is proven by unit tests (over geometry
and over a gradient) and its presence and variation by the existing representation tests.

### 5.3 Flatten with a matrix

`ContourFlattener.flattenOutline` and `flattenPath` gain an optional `GlyphAffineTransform`
applied to local design points before `scale`/`origin`. The default identity keeps existing
call sites unchanged. `CoverageRaster` gains a coverage entry point that takes the leaf
winding plus the accumulated clip windings, so the intersection is computed sample-by-sample.

### 5.4 Deterministic linear-gradient shading

Colour-line semantics follow the IR's own KDoc (`GlyphPaintRepresentation.kt`):

1. **Empty** line → transparent black. **One stop** → its effective colour everywhere.
2. The repeat/reflect interval is `[firstStop.offset, lastStop.offset]`, **not `[0, 1]`**.
   At `wght = 900` the fixture's stops are `0.25` and `1.125`, so the repeat period is
   `0.875`; a `fract(t)` implementation over `[0, 1]` would render the scene incorrectly.
3. `PAD` clamps outside the interval; at duplicate offsets the first stop applies below the
   offset and the last at/above it. `REPEAT` repeats with period `last - first`; `REFLECT`
   mirrors. A zero-span line with `REPEAT`/`REFLECT` paints nothing; `PAD` keeps the
   duplicate-stop rule.

Evaluation:

1. Map `p0/p1/p2` into device space. `t = dot(p - p0, n) / dot(p1 - p0, n)` with
   `n = perpendicular(p2 - p0)`, using basic `+ - * /` on `Double`. IEEE-754 basic arithmetic
   is exactly reproducible on every target; no transcendental function participates.
2. The colour is evaluated at the pixel centre `(x + 0.5, y + 0.5)`.
3. A non-finite derived value (a transform whose accumulation overflows, a degenerate
   projection with `|denominator|` below a fixed epsilon) is refused typed, never allowed to
   throw an uncaught exception (§5.5).
4. Interpolation is `LINEAR_SRGB` + `PREMULTIPLIED`, entirely integer with fixed rounding:
   - each stop's 8-bit sRGB channel is converted to a 16-bit linear value through a
     **committed** 256-entry table;
   - each stop's effective alpha is `color.alpha/255 × opacity` in fixed point; RGB is
     premultiplied by it;
   - premultiplied RGB and alpha are interpolated separately in the stop segment with a fixed
     weight;
   - the result is **un-premultiplied** (divide by interpolated alpha; alpha `0` → transparent
     black) before colour-space conversion;
   - the 16-bit linear value is converted back to 8-bit sRGB through a fixed monotone reverse
     mapping (binary search over the committed forward table with a fixed tie rule).

The forward table is generated by a documented script and committed as source, so no
`pow`/`exp`/`StrictMath` call ever runs at raster time and no platform math can move a byte.

### 5.5 Refusals

All refusals are typed `RasterRequestRejected` (never an uncaught exception):

- unbounded `Solid`/gradient with empty `clips`:
  `("nodeKind", "unbounded paint requires an enclosing clip or root bounds")`;
- `Group` reached with non-empty `clips`:
  `("nodeKind", "a clip around a composite is not supported")`;
- `LinearGradient` with `colorLine.interpolationSpace != LINEAR_SRGB`:
  `("interpolationSpace", …)`;
- `LinearGradient` with `colorLine.alphaInterpolationMode != PREMULTIPLIED`:
  `("alphaInterpolationMode", …)`;
- degenerate gradient projection, non-finite accumulated transform:
  `("gradient", …)` / `("transform", …)`;
- `PathClip`, `RadialGradient`, `SweepGradient`, `Composite`, and modes other than
  `SOURCE_OVER`: the existing `("nodeKind", …)` or `("compositionMode", …)`.

An **empty clip** (`clips` non-empty but a region with no area) paints nothing for that
subtree; it is distinct from "unbounded". Traversal of a subtree that paints nothing still
counts against `maxPaintNodes`/`maxPaintDepth`, so limits stay monotonic.

### 5.6 Limits

`maxPaintNodes` and `maxPaintDepth` cover clip and transform visits; every new canvas is sized
through the existing `checkCanvasSize` (width, height, pixels, allocation guard). New mask /
contour buffers are allocated only after the same checks. No new public `RasterLimits` field.

## 6. E2E proof

### 6.1 Profile

`E2eFontFixture` gains a COLR v1 paint profile (schema 2) accepting `GLYPH_CLIP`, `SOLID`,
`LINEAR_GRADIENT`, `TRANSFORM`, `SOURCE_OVER`, extend modes `PAD`/`REPEAT`/`REFLECT`,
`LINEAR_SRGB`, `PREMULTIPLIED`, and bounded `PaintGraphLimits`; the outline profile is
unchanged. `openRenderableFixture` gains an optional
`variation: FontVariationCoordinates? = null` used in the instance descriptor, defaulted so
existing callers are unchanged.

### 6.2 Scene

A new `PAINT_SHEET` value joins `GoldenSceneFamily`. A new helper renders a grid with
`rows = instances`, `columns = code points`:

- code points `0x41`, `0x42`, `0x43`; instances `wght` 400 and 900;
- each cell resolved through the COLR v1 profile at `64` ppem, palette 0, the cell's `wght`,
  and rasterized to a raw RGBA image;
- assertions are made on the **raw rasters, before compositing over white** (an opaque white
  backing would make every finished pixel alpha 255 and hide a lost fill):
  - every one of the six rasters carries non-zero alpha;
  - the `wght` 400 and 900 rasters differ per column;
  - column `0x41` carries chroma (its red/blue gradient), and its raw raster differs between
    instances (geometry/stop movement);
  - columns `0x42` and `0x43` are **identical** at a given weight, documenting that the
    fixture's inert transform must not change a pixel;
- the grid is then composited over white, with metrics computed across all six cells, using
  the design scale read from a `GlyphClip` outline (the fixture has no `SolidOutline`, so the
  existing sheet helper's UPEM lookup cannot be reused).

The entry uses `SceneFramePolicy.AutoSized(padding = 2)`; a new scene may not pin a frame
(§4). Scene id: `sheet.paint.kalligraphie-var-colr.64`.

### 6.3 Catalog and claims

- `ColorCatalog.color.colr-v1-variable` becomes `Supported` with `sinceCommit` set to the
  feature commit's short hash, `font = KALLIGRAPHIE_VAR_COLR`, `family = PAINT_SHEET`,
  `sceneId = sheet.paint.kalligraphie-var-colr.64`, `route = PORTABLE_GLYPH`,
  `frame = AutoSized(padding = 2)`, `tables = {COLR, CPAL, fvar, glyf, loca}`, and tags
  without `blocked-by-compositor`. Its bilingual `technology` text is rewritten (the current
  text says the compositor cannot compose `GlyphClip`).
- The `color.colr-v1-variable` probe is removed from `CatalogProbes`, with the now-unused
  fixture constant/helper/imports.
- `SceneFontPaths` gains `KALLIGRAPHIE_VAR_COLR`.
- `PortableSceneRenderers` registers the entry.
- `CatalogClaims` drops the `kalligraphie-var-colr` `glyf`/`loca` exemption and the
  now-unused reason constant; those tables are claimed instead.
- `updateE2eGolden` regenerates `manifest.tsv`, `e2e-catalog-matrix.md`/`.fr.md`, and
  `claimed-tables.json`.

### 6.4 Claims lint

The fixture's significant tables are `cmap`, `COLR`, `CPAL`, `fvar`, `glyf`, `head`, `hhea`,
`hmtx`, `loca`, `maxp`, `name`, `OS/2`, `post`. After claiming `glyf`/`loca`, the remaining
tables are covered by the structural wildcard, so `check_exhaustiveness.py` stays green.

## 7. Testing strategy

Tests come first (TDD) in `:kalligraphie:raster-cpu`:

- clip: geometry outside the clip is skipped and geometry inside is kept; a clip around a
  solid/gradient bounds it; a bare root `clipBounds` clips a `SolidOutline`; nested clips
  intersect once at edges (no squaring); a clip around a `Group` is refused;
- transform: moves a geometry fill and a gradient; a non-commuting pair composes in
  `outer ∘ inner` order; non-finite accumulation is refused typed;
- gradient: colour-line `PAD`/`REPEAT`/`REFLECT` over a **non-unit** stop span, negative `t`,
  empty/one-stop/duplicate-stop/zero-span, transparent stops, premultiplied linear-sRGB at a
  known midpoint, pixel-centre sampling, degenerate projection refused;
- interpolation-space and alpha-mode refusals for schema-3 gradients;
- limits: `maxPaintNodes`/`maxPaintDepth` still bound clip and transform recursion;
- determinism: repeated runs produce identical bytes.

Then the E2E scene, then the catalog/claims/docs regeneration, then `./gradlew check` and the
CI golden verification. The iOS job already runs the `:kalligraphie:raster-cpu` portable unit
suite; the golden scene's fingerprint is verified on the JVM, ART, and iOS, which is what
proves the gradient's cross-runtime byte identity.

## 8. Documentation and changelog

- `kalligraphie/raster-cpu/README.md` and `docs/docs/raster-cpu.md` / `.fr.md`: extend "what it
  rasterizes" and shrink the refusal list.
- KDoc: `RasterLimits.maxPaintDepth` no longer says "group nesting depth" only;
  `PaintRasterRequest.unitsPerEm` documents the `Solid`/gradient use.
- `CHANGELOG.md` `[Unreleased] / Added`: the new compositing capability and the promoted scene.
- `kalligraphie/e2e/README.md` if it enumerates scenes.
- `docs/docs/font-management.md` only if a sentence must change; the library still ships no
  renderer, so the "adds no rendering backend" statement stays true.

## 9. Contribution contract

- Branch `feat/raster-cpu-colr-v1-compositing` based on the latest `master`.
- Conventional Commits; PR title `feat(raster-cpu): compose COLR v1 variable glyph paint
  graphs`.
- `raster-cpu` is added to `allowed_scopes` in `.github/contributing-policy.toml` and to the
  CONTRIBUTING scope table in the same PR. `pr-policy.yml` checks out the PR head, so the new
  scope validates.
- `CHANGELOG.md` updated; the documentation decision recorded in the PR body.
- PR opened from a fork targeting `Graphiks-org/Kalligraphie`; `./gradlew check` run locally.

## 10. Assumptions and open questions

- The scene's two `wght` instances produce different raw rasters; if they do not, the
  assertion fails and the weights are revisited.
- `sinceCommit` is free-form (validated only as non-blank). It is recorded when the feature
  commit exists; a commit cannot contain its own final hash, so the value is the short hash of
  the commit that introduces the support.
- `AutoSized` keeps the composed grid because the white backing is opaque (there is no
  transparent margin to crop).

## 11. Follow-ups (not this change)

- Exact clipping of composite subtrees (`Group`/`Composite` under a clip), via subtree
  coverage.
- `PathClip`, `RadialGradient`, `SweepGradient`, `Composite`, and the remaining composition
  modes.
- Linear-light RGB compositing for COLR v1 groups.
- Visual validation of `Transform` over geometry/gradient in the catalog (the fixture cannot
  express it).
