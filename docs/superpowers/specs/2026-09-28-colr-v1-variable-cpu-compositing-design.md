# COLR v1 variable — complete the end-to-end visual proof

Design spec. Date: 2026-09-28. Status: approved design, pending written-spec review.

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
  and asserts that the instances differ, so a silent variation loss cannot be pinned as a
  plausible picture.
- The catalog entry becomes `Supported` with an entry frame, scene id, route, and family.
- The manifest, the catalog matrix (EN/FR), and the claims export are regenerated; the
  `glyf`/`loca` exemption is removed and those tables are claimed instead.
- `./gradlew check` is green and the CI golden verification passes on every platform.

## 3. Scope

In scope:

- `PaintCompositor` support for `GlyphClip`, `Solid`, `LinearGradient`, `Transform`.
- Clip-region bounding for otherwise-unbounded paints.
- Deterministic integer gradient shading honouring `LINEAR_SRGB` + `PREMULTIPLIED`.
- One new catalogued golden scene plus the catalog/claims/docs regeneration.

Out of scope (stay refused with the existing typed diagnostic):

- `PathClip`, `RadialGradient`, `SweepGradient`, `Composite`.
- Composition modes other than `SOURCE_OVER`.
- CFF/CFF2-in-COLR, variable CPAL.
- Any new public `RasterLimits` field.

## 4. Current state (evidence)

- `PaintCompositor.build` switches on `SolidOutline`, `Path`, `Group`; the `else` throws
  `RasterRequestRejected("nodeKind", …)`.
- The fixture `test-fixtures/fonts/kalligraphie-var-colr/KalligraphieVarCOLRv1.ttf` carries
  `COLR`, `CPAL`, `OS/2`, `cmap`, `fvar`, `glyf`, `head`, `hhea`, `hmtx`, `loca`, `maxp`,
  `name`, `post` (no `gvar`: only the COLR paint varies).
- `VariableColrV1RepresentationTest` shows the fixture's glyphs: `0x41` =
  `GlyphClip → LinearGradient` (REPEAT), `0x42` = `GlyphClip → Solid`, `0x43` =
  `GlyphClip → Transform → Solid`, with `clipBounds` and paint values varying with `wght`.
- The E2E paint profile (`E2eFontFixture.paintProfile`) accepts only `SOLID_OUTLINE` and
  `GROUP`, so the COLR v1 face is rejected before any outline is decoded.

## 5. Design

### 5.1 Paint context

`PaintCompositor` is restructured around a context propagated through the recursion:

```
Context(
    bounds: PixelBounds?,                 // device region bounding unbounded paints
    mask: A8Image?,                       // device-aligned coverage to intersect, or null
    transform: GlyphAffineTransform,      // node-local -> parent design space, accumulated
    unitsPerEm: Int?,                     // scale for paints with no outline of their own
)
```

Device mapping for any local design point is `origin + scale * transform(point)`, where
`scale = pixelsPerEm / unitsPerEm` and `unitsPerEm` is node-specific:

- `SolidOutline` and `GlyphClip` use their own outline's `unitsPerEm`;
- `Path` uses the request-level `unitsPerEm` (unchanged);
- gradient geometry has no intrinsic `unitsPerEm`: it is evaluated in the local space of the
  enclosing `GlyphClip`, which supplies the scale.

`build(nodeIndex, context, depth): Layer?` returns one RGBA layer in device coordinates. Every
leaf multiplies its alpha by `context.mask` where the mask covers its pixels.

### 5.2 Node semantics

| Node | Behaviour |
| --- | --- |
| `SolidOutline` | Flatten `outline.contours` with `context.transform`; coverage; × `context.mask`; tint `color`. |
| `Path` | Flatten `path.commands` with `context.transform`; coverage; × `context.mask`; tint `color`. |
| `Solid` | Require `context.bounds`; fill bounds with `color`, alpha = `color.alpha × opacity × mask` (fixed rounding). |
| `LinearGradient` | Require `context.bounds`; map `p0/p1/p2` into device space with the context scale; shade per pixel; × `context.mask`. |
| `GlyphClip` | Flatten its outline with `context.transform`; clip bbox ∩ `context.bounds`; rasterize clip coverage; child context = `(bounds = clip bbox, mask = mask ∩ clipCoverage, transform, unitsPerEm = outline.unitsPerEm)`; recurse; result carries the composed mask. |
| `Transform` | `context.transform = compose(outer = context.transform, inner = matrix)`; recurse. |
| `Group` | Composite children in list order with the existing integer `SOURCE_OVER`; identical child context. |

`Solid` and `LinearGradient` have no intrinsic `unitsPerEm`; they use the `unitsPerEm` the
context carries, which the nearest enclosing `GlyphClip` (or the request, for the root) fixes.
Absent a `GlyphClip`, a `Solid`/gradient root uses the request `unitsPerEm` together with the
root `clipBounds`.

An affine map of a **linear** gradient keeps it linear, so mapping `p0/p1/p2` to device and
shading there is exact.

**Known fixture property:** `0x43` is `GlyphClip → Transform → Solid`. A transform on a
uniform solid is visually inert (it moves a constant fill). The E2E scene therefore proves
`GlyphClip`, `Solid`, and `LinearGradient` visually; `Transform`'s pixel effect is proven by
unit tests (over geometry and over a gradient) and its presence and variation by the existing
representation tests. This is a fixture property, not a compositor gap.

### 5.3 Flatten with a matrix

`ContourFlattener.flattenOutline` and `flattenPath` gain an optional
`GlyphAffineTransform` parameter applied to local design points before the `scale`/`origin`
mapping. The default identity keeps every existing call site unchanged.

### 5.4 Deterministic linear-gradient shading

New internal unit (e.g. `LinearGradientShader`) producing a layer:

1. Map `p0/p1/p2` into device space.
2. Per pixel, compute the colour-line parameter `t = dot(p - p0, n) / dot(p1 - p0, n)` where
   `n` is perpendicular to `p2 - p0` (the OpenType colour-projection direction), using basic
   `+ - * /` on `Double`. IEEE-754 basic arithmetic is exactly reproducible on every target; no
   transcendental function participates.
3. Apply `PAD` / `REPEAT` / `REFLECT` (integer/floored arithmetic).
4. Locate the stop segment and interpolate.
5. Interpolation is `LINEAR_SRGB` + `PREMULTIPLIED`: each stop's 8-bit sRGB channel is
   converted to a 16-bit linear value through a fixed 256-entry table; RGB (premultiplied by
   effective alpha) and alpha are interpolated separately with fixed rounding; the result is
   converted back to 8-bit non-premultiplied sRGB through a fixed monotone inverse table.
6. Intersect the result with `context.mask`.

No `pow`, `exp`, or `StrictMath` call is used, which is what lets the committed golden
fingerprint be verified byte-for-byte on the JVM, ART, and Kotlin/Native.

### 5.5 Boundedness and refusals

- A `Solid` or gradient reached with `context.bounds == null` (no enclosing `GlyphClip` and no
  root `clipBounds`) is refused with
  `RasterRequestRejected("nodeKind", "unbounded paint requires an enclosing clip or root bounds")`.
- `GlyphPaintIR.clipBounds`, when present, seeds the root `bounds`; the mask starts absent.
- Out-of-scope nodes keep the existing `nodeKind` refusal.

### 5.6 Limits

`maxPaintNodes` and `maxPaintDepth` cover clip and transform visits; every new canvas is
sized through the existing `checkCanvasSize` (width, height, pixels, allocation guard). No new
public `RasterLimits` field.

## 6. E2E proof

### 6.1 Profile

`E2eFontFixture` gains a COLR v1 paint profile (schema 2) accepting `GLYPH_CLIP`, `SOLID`,
`LINEAR_GRADIENT`, `TRANSFORM`, `SOURCE_OVER`, extend modes `PAD`/`REPEAT`/`REFLECT`,
`LINEAR_SRGB`, `PREMULTIPLIED`, and bounded `PaintGraphLimits`; the outline profile is
unchanged. `openRenderableFixture` gains an optional
`variation: FontVariationCoordinates? = null` used in the instance descriptor, defaulted so
existing callers are unchanged.

### 6.2 Scene

A new `PAINT_SHEET` value joins `GoldenSceneFamily`. A new helper renders a grid of
`rows = instances × columns = code points`:

- code points `0x41`, `0x42`, `0x43`; instances `wght` 400 and 900;
- each cell resolved through the COLR v1 profile at `64` ppem, palette 0, the cell's `wght`;
- one shared cell grid composited over white, in the sheet orientation the existing paint
  sheets use;
- the scene asserts (a) the two rows differ in alpha coverage, and (b) the canvas carries
  chroma, mirroring `VariationLadderScene`'s premise assertions.

Scene id: `sheet.paint.kalligraphie-var-colr.64`. Frame: `SceneFramePolicy.Pinned(w, h)`,
pinned from the first generated render.

### 6.3 Catalog and claims

- `ColorCatalog.color.colr-v1-variable` becomes `Supported` with `sinceCommit` set to the
  feature commit's short hash, `font = KALLIGRAPHIE_VAR_COLR`, `family = PAINT_SHEET`,
  `sceneId = sheet.paint.kalligraphie-var-colr.64`, `route = PORTABLE_GLYPH`,
  `tables = {COLR, CPAL, fvar, glyf, loca}`, and tags without `blocked-by-compositor`.
- `SceneFontPaths` gains `KALLIGRAPHIE_VAR_COLR`.
- `PortableSceneRenderers` registers the entry.
- `CatalogClaims` drops the `kalligraphie-var-colr` `glyf`/`loca` exemption (and the now-unused
  reason constant); those tables are claimed instead.
- `updateE2eGolden` regenerates `manifest.tsv`, `e2e-catalog-matrix.md`/`.fr.md`, and
  `claimed-tables.json`.

### 6.4 Claims lint

The fixture's significant tables are `cmap`, `COLR`, `CPAL`, `fvar`, `glyf`, `head`, `hhea`,
`hmtx`, `loca`, `maxp`, `name`, `OS/2`, `post`. After claiming `glyf`/`loca`, the remaining
tables are covered by the structural wildcard, so `check_exhaustiveness.py` stays green.

## 7. Testing strategy

Tests come first (TDD) in `:kalligraphie:raster-cpu:check`:

- clip skips ink outside the clip outline and keeps ink inside;
- clip bounds an otherwise-unbounded `Solid`/gradient;
- transform moves a geometry fill and a gradient;
- `PAD` / `REPEAT` / `REFLECT` colour-line extension;
- premultiplied linear-sRGB interpolation at a known midpoint;
- unbounded paint without a bounded region is refused;
- `maxPaintNodes` / `maxPaintDepth` still bound clip and transform recursion;
- determinism: repeated runs produce identical bytes.

Then the E2E scene, then the catalog/claims/docs regeneration, then `./gradlew check` and the
CI golden verification.

## 8. Documentation and changelog

- `kalligraphie/raster-cpu/README.md` and `docs/docs/raster-cpu.md` / `.fr.md`: extend "what it
  rasterizes" and shrink the refusal list.
- `CHANGELOG.md` `[Unreleased] / Added`: the new compositing capability and the promoted scene.
- `kalligraphie/e2e/README.md` if it enumerates scenes.
- `docs/docs/font-management.md` only if a sentence must change; the library still ships no
  renderer, so the "adds no rendering backend" statement stays true.

## 9. Contribution contract

- Branch `feat/raster-cpu-colr-v1-compositing` based on the latest `master`.
- Conventional Commits; PR title `feat(raster-cpu)`.
- `raster-cpu` is added to `allowed_scopes` in `.github/contributing-policy.toml` and to the
  CONTRIBUTING scope table in the same PR. `pr-policy.yml` checks out the PR head, so the new
  scope validates.
- `CHANGELOG.md` updated; the documentation decision recorded in the PR body.
- PR opened from a fork targeting `Graphiks-org/Kalligraphie`; `./gradlew check` run locally.

## 10. Assumptions and open questions

- The scene's two `wght` instances produce visibly different rows; if they do not, the
  assertion fails and the weights are revisited.
- `sinceCommit` is free-form (validated only as non-blank) and is set to the feature commit's
  short hash.
- The exact pinned frame is determined by the first generated render; no hand-picked size.

## 11. Follow-ups (not this change)

- `PathClip`, `RadialGradient`, `SweepGradient`, `Composite`, and the remaining composition
  modes remain typed refusals.
- Visual validation of `Transform` over geometry/gradient in the catalog (the fixture cannot
  express it).
