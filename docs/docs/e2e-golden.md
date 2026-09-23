# End-to-end golden fingerprints

Kalligraphie's end-to-end authority is the non-published `:kalligraphie:e2e`
module. It holds two independent axes: behavioural journeys that assert the
public facade's geometry and outcomes, and golden scenes that assert the final
pixels of composed output. Neither axis depends on the production modules.

The reference pixels are a hybrid: a versioned fingerprint manifest is the
source of truth for CI, and an opt-in dump writes the images themselves for
inspection. The manifest is committed and reviewed; the images are not hosted.

This page publishes the scene catalog, the canonicalization contract, the
comparison verdicts and diagnostic codes, the opt-in commands, and the boundary
between journeys and scenes.

## Module and isolation

`:kalligraphie:e2e` lives at `kalligraphie/e2e/` and uses the standard
non-published KMP convention (like `:kalligraphie:conformance`). The convention
declares the `jvm`, `iosArm64`, `iosSimulatorArm64` and `android` targets; only
`jvmTest` carries tests today, so the other targets compile without executing
anything. `explicitApi()` is enabled.

Its dependencies keep it outside the consumer graph: `commonMain` depends only on
`:kalligraphie:api`, and the JVM test source set adds `:kalligraphie`,
`:kalligraphie:raster-cpu`, `:kalligraphie:layout`, `:kalligraphie:shaping`,
`:kalligraphie:unicode`, `:kalligraphie:font:core` and `:kalligraphie:font:sfnt`.
Nothing in production depends on this module, and it is never published.

The pure model — `GoldenImage`, `GoldenScene`, `GoldenFingerprint`,
`GoldenManifest`, `GoldenComparison` and the SHA-256 digest — lives in
`commonMain`, with no platform type. The catalog, the renderer, the verifier and
the fixtures are JVM test sources.

Protection is free: the root `check` runs `:kalligraphie:e2e:jvmTest` like any
other subproject, and the existing pull-request workflow already covers
`kalligraphie/**`. No dedicated workflow exists.

## Scene catalog

Every scene is rendered through the public facade and the CPU rasterizer, then
reduced to a single digest. The table below is an illustration of the six
families, not the inventory: the generated [expectation catalog
matrix](generated/e2e-catalog-matrix.md) lists every scene and is the only place
where the catalog's counts appear.

| Family | Scenes | Observable coverage | Format |
| --- | --- | --- | --- |
| `GLYPH_OUTLINE` | `glyph.outline.liberation-sans.A.64` (43×45) | One glyph flattened and scan-converted at 64 pixels per em. | `ALPHA_8` |
| `GLYPH_PAINT` | `glyph.paint.emoji-two-colr-v0.u1F600.64` (71×72) | A COLR v0 glyph resolved to layers, composited `SOURCE_OVER` and tinted. | `RGBA_8888` |
| `GLYPH_BITMAP` | `glyph.bitmap.skia-ebdt-format1.u1F600.16` (13×13) | A glyph routed to the embedded EBLC/EBDT strike. | `RGBA_8888` |
| `COMPOSED_LINE` | `line.latin.48`, `line.greek.48`, `line.cyrillic.48`, `line.arabic.48`, `line.devanagari.48`, `line.mixed.48`, `variation.wght-ladder` (291×306) | Real text shaped and laid out by the paragraph facade: Latin, Greek, Cyrillic, right-to-left Arabic, Devanagari, and a mixed line whose scripts are resolved by three-face fallback — plus one word rendered five times, once per `wght` instance of a real variable font, on one baseline grid. | `ALPHA_8` |
| `MOSAIC` | `composition.every-route-mosaic` (592×366) | Seven routes in one image: a shaped word in a TrueType outline, the capital A through CFF 1 and CFF 2, a three-face multi-script line, one glyph at four weights, a colour paint and a bitmap strike, composed on one colour canvas. | `RGBA_8888` |
| `ALPHABET_SHEET` | `sheet.outline.liberation-latin.32`, `sheet.outline.liberation-greek.32`, `sheet.outline.liberation-cyrillic.32`, `sheet.outline.amiri-arabic.32`, `sheet.outline.noto-devanagari.32`, `sheet.paint.bungee-color-latin.48`, `sheet.paint.emoji-two-colr-v0.64` | Every covered glyph of a face, laid out in one grid: coverage for the outline faces, composited colour for the Bungee Color and EmojiTwo faces. | `ALPHA_8` and `RGBA_8888` |

The three single-glyph scenes are the promoted form of the conformance constants
that `:kalligraphie:raster-cpu` used to hold; the composed lines and the alphabet
sheets are the promoted form of its demonstration dumps. Their digests are
unchanged by the move, so the fingerprint authority simply changed modules.

The single-glyph bitmap scene is deliberately *stricter* than the dump it
replaces: the rasterizer returns the strike's own 13×13 frame, while the old dump
padded it by two pixels for readability.

## Canonicalization and the manifest

Canonicalization fixes one serialized form, stable and portable:

- pixels are row-major with no padding;
- the bytes keep the row order their producer declared, which `GoldenImage`
  names: `GoldenOrientation.DESIGN` for the raw outline and paint routes, whose
  row zero is the visual bottom, and `GoldenOrientation.IMAGE` for the composed
  canvases and the normalized bitmap strikes. Nothing normalizes the canonical
  bytes, so the same logical image always hashes to the same digest;
- presenting a scene the right way round is a dump-time concern only: the dump
  writer reverses a design-oriented raster once and leaves an image-oriented one
  alone;
- the fingerprint is the SHA-256 of those canonical bytes;
- the form is versioned by `CANONICALIZATION_VERSION`, currently `1`.

The manifest lives at
`kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv`. Its first line
declares the format and the canonicalization version, then one tab-separated row
per scene, sorted lexicographically by scene id:

```
kalligraphie.golden/v1 canonicalization=1
<sceneId>\t<family>\t<W>x<H>\t<FORMAT>\tsha256:<hex>
```

It is UTF-8 with LF endings, with no blank line and no comment. A header whose
`canonicalization=` differs from the code invalidates the whole manifest at once,
producing a single explicit failure instead of a flood of mismatches.

The code never rewrites the manifest. It changes only through the opt-in
`updateE2eGolden` task, and then through review.

## Comparison and diagnostics

Comparison is exact, byte for byte, with no tolerance — consistent with a
deterministic rasterizer that uses integer arithmetic and sixteen fixed
sub-samples. A tolerance would be introduced only if a non-deterministic route
appeared.

The verdicts are fail-closed:

| Verdict | Condition |
| --- | --- |
| Matched | The scene's digest equals the recorded digest. |
| Mismatch | The digests differ; the report carries the scene id, both digests, the first differing byte, and its `(x, y)` coordinate. |
| Missing in manifest | A catalogued scene has no entry — never a silent pass. |
| Stale manifest entry | An entry has no catalogued scene. |
| Render failed | The scene failed to render, with a typed code. |

A duplicate scene id, in the catalog or in the manifest, fails while loading.

| Code | Meaning |
| --- | --- |
| `e2e.render-failed` | The scene failed to render. |
| `e2e.mismatch` | The rendered digest differs from the recorded digest. |
| `e2e.scene-bounds-invalid` | A scene's declared frame and its rendered bounds disagree. |
| `e2e.manifest-missing-entry` | A catalogued scene has no manifest entry. |
| `e2e.manifest-stale-entry` | A manifest entry has no catalogued scene. |
| `e2e.manifest-duplicate-id` | A scene id appears more than once. |
| `e2e.canonicalization-version-mismatch` | The manifest was written under another canonicalization version. |
| `e2e.manifest-malformed` | The manifest text is not well formed. |

## Verification and reference regeneration

```bash
./gradlew :kalligraphie:e2e:jvmTest
./gradlew :kalligraphie:e2e:updateE2eGolden
env KALLIGRAPHIE_E2E_DUMPS_OUTPUT=/tmp/kalligraphie-e2e \
    ./gradlew :kalligraphie:e2e:e2eGoldenDumps
```

`jvmTest` is part of `check`. The other two tasks are opt-in, are excluded from
`check`, and always re-run: `updateE2eGolden` writes the manifest from the
catalog, and `e2eGoldenDumps` writes one PGM or PPM per scene, named after the
scene id, next to a manifest copy.

The dump output must be an absolute path outside the repository; the runner
asserts both and fails rather than writing into the checkout. Every dump opens
upright: a scene that declares design orientation — the raw single-glyph outline
and paint routes — is reversed once by the writer, while the composed sheets and
lines and the bitmap strikes are already in image orientation and are written
untouched.

## Journeys and scenes

The two axes stay deliberately separate, with no double coverage:

- the behavioural journeys assert geometry and outcomes — carets, selection,
  fragmentation, operation bounds — and give rich, fast diagnostics;
- the golden scenes assert the final pixels.

A scene is promoted from a journey only where a pixel digest proves something the
geometry does not; otherwise the geometry is enough.

Two journeys moved here: `AdvancedTypographyJourneyTest` and
`CffOpenTypeJourneyTest`. Three remain in `:kalligraphie`:
`FlowCompositionEditorJourneyTest` and `IncrementalLayoutEditorJourneyTest`
exercise `internal` facade and session members, so they are white-box tests of
their own module, and `SystemFontCatalogJourneyTest` shares its helpers with the
filesystem-backed `FontDirectoryCatalogTest`.

## Fixtures

`test-fixtures/` at the repository root is the single font source for the whole
build, consumed by each test source set through
`resources.srcDir(rootProject.file("test-fixtures"))`. Kotlin fixture builders
stay per consumer — `:kalligraphie:e2e` defines its own thin builders reading
`/fonts/...` from the classpath — rather than in a shared test-fixtures module.

## Known limitations

- Only `jvmTest` executes. The iOS and Android targets compile, but the scene
  catalog is JVM-only.
- Comparison is exact; a non-deterministic rendering route would require
  introducing a tolerance.
- Reference images are not hosted externally (no LFS or build artifact), so the
  manifest is the portable record and the dumps are local.
- Platform journeys (`CoreTextRegistryJourneyTest`,
  `FontconfigRegistryJourneyTest`, `DirectWriteRegistryJourneyTest`) stay in
  their platform modules, because they exercise providers rather than portable
  composition.
- The manifest is regenerated by hand and reviewed in the pull request; nothing
  regenerates it automatically.
