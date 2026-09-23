# `:kalligraphie:e2e`

The end-to-end verification harness. It holds two independent axes: **behavioural journeys** that
assert geometry and outcomes through the public facade, and **golden scenes** that reduce a
composed artefact to one digest and fail on any byte of drift. Alongside them sits the **font
expectation catalog**, the module's record of what Kalligraphie expects from fonts, published as a
generated bilingual matrix in `docs/docs/generated/e2e-catalog-matrix.md`.

The module is not published and is absent from the consumer dependency graph.
`docs/docs/e2e-golden.md` documents the golden contract in prose; this file documents where its
code lives and why.

## Source sets, and the rule that decides

| Source set | Holds | Compiled for |
| --- | --- | --- |
| `commonMain` | The catalog model (`CatalogEntry`, its axes, routes, statuses and audit), the canonical image and its geometry (`GoldenImage`, `GoldenInkBox`, `GoldenImageReframer`, `GoldenOrientation`), the claims export (`CatalogClaims`) and the matrix renderer (`CatalogMatrixRenderer`). | Every target |
| `commonTest` | The tests of that model, which need no font and no host. | Every test target |
| `sharedTest` | The portable half of the harness: the corpus seam (`FixtureCorpus`), the scene renderers that need only the portable glyph route, the scene materializer and its catalog, the probes, the golden verifier, the capability ratchets, and the tests of all of them. | Every test target that can run it |
| `classpathTest` | The corpus implementation of the JVM and Android family: fonts and committed resources read through the class loader. | JVM and Android test targets |
| `jvmTest` | What genuinely needs this platform: the paragraph-facade renderers (`JvmSceneRenderers` — the composed lines, the weight ladder, the mosaic), the writers (`updateE2eGolden`, the matrix, the claims, the dumps), the journeys, and the platform declaration itself (`E2eTestEnvironment`). | JVM only |

`sharedTest` and `classpathTest` are not Kotlin source sets of their own: they are directories added
to several test compilations with `kotlin.srcDir`. That is deliberate. `androidDeviceTest` cannot
see `commonTest` in this repository, so a shared directory is the only way to compile one copy of
the portable harness into every test target — the same arrangement `:kalligraphie:shaping` uses for
its device goldens.

The rule for new code: **a scene that needs no paragraph facade belongs in `sharedTest`, and reads
its font bytes from the injected `FixtureCorpus`; anything that writes into the repository belongs
in `jvmTest`.** Two facts fix that boundary:

- **Font bytes reach the harness through the corpus seam, never through the class path.**
  `FixtureCorpus` has one implementation per platform family — the class-path reader for JVM and
  Android, an embedded base64 corpus for Kotlin/Native, which has no classpath resources at all (the
  shape `:kalligraphie:shaping` uses for its iOS goldens). A test that calls `bytes("/fonts/…")`
  therefore compiles wherever a corpus exists, and the *same* committed fingerprint is verified by
  every platform rather than re-frozen per platform.
- **The harness's platform half is Gradle plumbing.** The writers are `Test` tasks gated by
  environment variables (`KALLIGRAPHIE_E2E_UPDATE`, `KALLIGRAPHIE_E2E_MATRIX`,
  `KALLIGRAPHIE_E2E_CLAIMS`, `KALLIGRAPHIE_E2E_DUMPS`), they write into the source tree through
  `java.nio.file`, and the claims round-trip shells out to `python3`. None of that is portable: the
  *verification* is shared, the *authoring* is not.

## Which scenes a platform verifies, and why that is not a preference

Every supported entry declares a `CatalogRoute`, and its renderer declares the same one; the
materializer refuses a disagreement. The route maps to the portable capabilities of
`:kalligraphie:conformance` — `PORTABLE_GLYPH` to `GLYPH_REPRESENTATION_VARIANTS`,
`PARAGRAPH_LAYOUT` to that plus `END_TO_END_LAYOUT` — so each platform derives the scenes it must
verify from its *own* declared capability identity, and the ratchet requires the registry to match
that set exactly in both directions.

Two consequences worth stating, because they are the reason the route exists at all:

- A scene cannot be skipped in silence. An entry the platform does not verify is one its declared
  capabilities excuse, and the comparison is checked, not narrated.
- When a portable Unicode-analysis backend lands and `END_TO_END_LAYOUT` flips to available on a
  shipped target, the ratchet stops excusing the composed lines, the weight ladder and the mosaic
  there and *demands* their renderers — the work becomes forced rather than remembered.

The journeys are not on this axis: they assert behaviour through the facade with their own fixture
access and stay on the reference platform.
