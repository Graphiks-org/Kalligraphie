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
| `commonTest` | The tests of that model, which need no font and no host. | `jvmTest`, `androidHostTest` and `iosSimulatorArm64Test`. `androidDeviceTest` cannot see `commonTest` in this repository, so the device run executes the shared harness — scenes, verifier and ratchets — and not the model tests. |
| `sharedTest` | The portable half of the harness: the corpus seam (`FixtureCorpus`), the scene renderers that need only the portable glyph route, the scene materializer and its catalog, the probes, the golden verifier, the capability ratchets, and the tests of all of them. | `jvmTest`, `androidHostTest`, `androidDeviceTest`, `iosSimulatorArm64Test` |
| `classpathTest` | The corpus implementation of the JVM and Android family: fonts and committed resources read through the class loader. | `jvmTest` and the two Android test compilations |
| `androidFamilyTest` | The Android test environment, shared by the host and device compilations so the two cannot drift. | `androidHostTest`, `androidDeviceTest` |
| `harnessResources` | The committed harness resources: the golden manifest, the auto-sizing exemptions, the claims export. Wired as a resource directory where the platform has a class path, embedded where it does not. | Every test target |
| `jvmTest` | What genuinely needs this platform: the paragraph-facade renderers (`JvmSceneRenderers` — the composed lines, the weight ladder, the mosaic), the writers (`updateE2eGolden`, the matrix, the claims, the dumps), the journeys, and the reference platform's declaration (`E2eTestEnvironment`). | JVM only |
| `iosSimulatorArm64Test` | The embedded corpus (`EmbeddedFixtureCorpus`, fed by the generated `E2eFixtureCorpus` source) and the iOS declaration. The paragraph-facade scenes are absent: their renderers do not compile here. | iOS simulator |

`sharedTest`, `classpathTest` and `androidFamilyTest` are not Kotlin source sets of their own: they
are directories added to several test compilations with `kotlin.srcDir`. That is deliberate.
`androidDeviceTest` cannot see `commonTest` in this repository, so a shared directory is the only
way to compile one copy of the portable harness into every test target — the same arrangement
`:kalligraphie:shaping` uses for its device goldens.

The rule for new code: **a scene that needs no paragraph facade belongs in `sharedTest`, and reads
its font bytes from the injected `FixtureCorpus`; anything that writes into the repository belongs
in `jvmTest`.** Two facts fix that boundary:

- **Font bytes reach the harness through the corpus seam, never through the class path.**
  `FixtureCorpus` has one implementation per platform family — `ClasspathFixtureCorpus` for JVM and
  Android, `EmbeddedFixtureCorpus` for Kotlin/Native, which has no classpath resources at all and
  reads a corpus the `iosFixtureCorpus` task generates as base64 Kotlin source. A test that calls
  `bytes("/fonts/…")` therefore runs wherever a corpus exists, and the *same* committed fingerprint
  is verified by every platform rather than re-frozen per platform.
- **The suite asserts; the tooling acts.** Regenerating an artefact is not a test: the writers live
  in `GoldenWriterMain`, one command per artefact, driven by `JavaExec` tasks on the test runtime
  class path (`updateE2eGolden`, `e2eGoldenDumps`). They write into the source tree through
  `java.nio.file`, so they stay on the reference platform — but they are never excluded from
  `check`, because they are no longer tests. No test in this module spawns a host tool either: a
  suite that has to discover an interpreter, or that switches itself off, is a suite that reports
  on its plumbing rather than on the product.

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

The deferred set is derived the same way: a platform's catalog reports its scenes, and
`deferredSceneIds()` reports the manifest keys its capabilities excuse. The golden verifier receives
both, so an entry a platform does not render is named rather than reported as a stale manifest
entry — and one that is neither rendered nor excused is still stale.

The journeys are not on this axis: they assert behaviour through the facade with their own fixture
access and stay on the reference platform.

## What each target verifies

| Target | Command | Scenes verified |
| --- | --- | --- |
| JVM | `./gradlew :kalligraphie:e2e:jvmTest` | Every catalogued scene |
| Android (host, JVM runtime) | `./gradlew :kalligraphie:e2e:testAndroidHostTest` | The portable scenes |
| Android (device, ART) | `./gradlew :kalligraphie:e2e:connectedAndroidDeviceTest` | The portable scenes |
| iOS simulator | `./gradlew :kalligraphie:e2e:iosSimulatorArm64Test` | The portable scenes |

Every one of them compares against the same committed `manifest.tsv`, exactly, with no numeric
tolerance. `iosArm64` compiles but executes nothing: no hosted runner can supply a device.
