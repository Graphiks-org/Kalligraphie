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
| `commonMain` | The catalog model (`CatalogEntry`, its axes, statuses and audit), the canonical image and its geometry (`GoldenImage`, `GoldenInkBox`, `GoldenImageReframer`, `GoldenOrientation`), the claims export (`CatalogClaims`) and the matrix renderer (`CatalogMatrixRenderer`). | Every target |
| `commonTest` | The tests of that model, which need no font and no host. | Every test target |
| `jvmTest` | The renderers and scene materializers, the font fixtures, the golden manifest reader/writer and verifier, the probes, the ratchet, and the journeys. | JVM only |

The rule for new code: **a unit that reads a font file or a host resource belongs in `jvmTest`;
everything else belongs in `commonMain` with its test in `commonTest`.** Two facts fix that
boundary, and neither is a matter of taste:

- **Font bytes are a classpath resource.** `fixtureBytes` reads
  `resources.srcDir(rootProject.file("test-fixtures"))` through `getResourceAsStream`, and
  Kotlin/Native has no classpath resources. Any test that loads a font is therefore pulled into
  `jvmTest` by construction. (`:kalligraphie:shaping` solves the same problem for iOS by generating
  an embedded base64 corpus; that is the shape to follow if this harness ever has to run on a
  non-JVM target.)
- **The harness is Gradle plumbing.** The writers are `Test` tasks gated by environment variables
  (`KALLIGRAPHIE_E2E_UPDATE`, `KALLIGRAPHIE_E2E_MATRIX`, `KALLIGRAPHIE_E2E_CLAIMS`,
  `KALLIGRAPHIE_E2E_DUMPS`), they write into the source tree through `java.nio.file`, and the claims
  round-trip shells out to `python3`. None of that is portable, and none of it is what the model
  tests need.

So the split follows the *data*, not a preference: an `expect fun fixtureBytes(...)` seam would move
files without moving the capability, because the Native actual would have no font to read. If the
harness ever needs to run those tests on the shipped targets, the work is generating an embedded
corpus, not relocating classes.
