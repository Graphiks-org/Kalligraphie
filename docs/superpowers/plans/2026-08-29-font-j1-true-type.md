# J1 — Font TrueType portable autonome Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Livrer l’issue #4 par quatre PRs verticales empilées, avec un parcours JVM consommé exclusivement via `org.graphiks:kalligraphie`, de données TrueType immuables jusqu’à `GlyphOutlineIR`.

**Architecture:** Ajouter une nouvelle arborescence KMP `kalligraphie/` sans modifier l’ancien `font/`. `:kalligraphie` est la façade publiée ; `api`, `font/sfnt`, `font/scaler`, `font/glyph` et `font/core` portent des responsabilités séparées dans `commonMain`, avec uniquement la cible JVM activée pour les tests J1.

**Tech Stack:** Kotlin 2.4.10, Gradle 9.6.1, Kotlin Multiplatform, Kotlin Test, JVM Toolchain 25, SHA-256 pur Kotlin, parser SFNT/TrueType sans dépendance externe.

**Spec:** `docs/superpowers/specs/2026-08-29-font-j1-true-type-design.md`

## Global Constraints

- Le code J1 reste dans `commonMain` et n’expose aucun type `java.*`, CoreText, DirectWrite, Skia ou renderer.
- Le support est limité à un SFNT TrueType simple (`0x00010000` ou `true`) ; TTC/OTC, CFF/CFF2, variations, COLR, SVG, bitmap glyphs, shaping, layout, hinting et rasterisation restent exclus.
- Les données d’entrée sont copiées et les snapshots/résultats/diagnostics sont immuables.
- `FontSourceId` est dérivé du contenu SHA-256 ; `FontFaceId` et `FontInstanceKey` restent des domaines d’identité distincts.
- `GlyphOutlineIR` est en design units ; `GlyphMetrics` contient design units et valeurs `LayoutUnit` mises à l’échelle.
- Les erreurs de données et de capacité sont retournées par `FontOperationResult`, avec codes `font.*`, locations typées et ordre canonique.
- Les offsets, longueurs, points, contours, composants et profondeurs sont bornés avant lecture ou récursion.
- Chaque nouveau comportement suit RED → GREEN → REFACTOR : le test métier doit échouer avant le code de production correspondant.
- Les sources existantes sous `font/` restent inchangées ; `./gradlew :font:fontTest` doit rester vert.
- Les branches autorisées sont `feat/font-j1-1`, `feat/font-j1-2`, `feat/font-j1-3`, `feat/font-j1-4`, puis le pointeur final `feat/font-j1`.

---

## Carte des fichiers

### Nouveau graphe Gradle

- `settings.gradle.kts` — inclure les projets `:kalligraphie` et ses sous-projets.
- `buildSrc/src/main/kotlin/ygdrasil/conventions/kalligraphie-kmp-library.gradle.kts` — convention KMP JVM-only avec `commonMain`, `commonTest`, `jvmTest` et toolchain 25.
- `kalligraphie/build.gradle.kts` — façade, publication `org.graphiks:kalligraphie` et dépendances `api`/implémentation.
- `kalligraphie/api/build.gradle.kts` — contrats publics sans dépendance.
- `kalligraphie/unicode/build.gradle.kts` — emplacement réservé à la couche Unicode future, sans comportement J1.
- `kalligraphie/font/core/build.gradle.kts` — catalogue, faces et instances.
- `kalligraphie/font/sfnt/build.gradle.kts` — lecture bornée du conteneur et des tables.
- `kalligraphie/font/scaler/build.gradle.kts` — interprétation des tables TrueType, cmap et métriques.
- `kalligraphie/font/glyph/build.gradle.kts` — materialization de l’IR et handles.

### Contrats et implémentation

- `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontGeometry.kt` — `LayoutUnit`, rectangles et design bounds.
- `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontIdentity.kt` — provenance, source, IDs et clés.
- `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontDiagnostics.kt` — résultats, diagnostics, erreurs et cancellation.
- `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontContracts.kt` — requests, profiles, interfaces de catalogue/face/instance/handles et représentations.
- `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/SfntReader.kt` — cursor big-endian, directory et table slices.
- `kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/EmbeddedFontCatalog.kt` — création du catalogue, sélection de face et métadonnées.
- `kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/TrueTypeFace.kt` — orchestration face/instance et dispatch vers les lecteurs spécialisés.
- `kalligraphie/font/scaler/src/commonMain/kotlin/org/graphiks/kalligraphie/font/scaler/CmapReader.kt` — formats cmap 4 et 12.
- `kalligraphie/font/scaler/src/commonMain/kotlin/org/graphiks/kalligraphie/font/scaler/MetricsReader.kt` — head, hhea et hmtx.
- `kalligraphie/font/scaler/src/commonMain/kotlin/org/graphiks/kalligraphie/font/scaler/LocaReader.kt` — offsets de glyphes.
- `kalligraphie/font/scaler/src/commonMain/kotlin/org/graphiks/kalligraphie/font/scaler/GlyfReader.kt` — contours simples et composants composites.
- `kalligraphie/font/glyph/src/commonMain/kotlin/org/graphiks/kalligraphie/font/glyph/OutlineMaterializer.kt` — conversion et contrôle de l’IR.
- `kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/Kalligraphie.kt` — façade publique et factory embedded.

### Fixtures et tests

- `kalligraphie/src/jvmTest/resources/fonts/liberation/LiberationSans-Regular.ttf` — copie bit-à-bit de la fixture OFL existante.
- `kalligraphie/src/jvmTest/resources/fonts/liberation/OFL-1.1.txt` — licence de la fixture.
- `kalligraphie/src/jvmTest/resources/fonts/liberation/PROVENANCE.md` — provenance et hash de la fixture.
- `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J11FontFaceContractTest.kt` — vraie font, source, catalogue et face.
- `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J12GlyphMetricsContractTest.kt` — cmap et metrics avec attentes indépendantes.
- `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J13GlyphOutlineContractTest.kt` — outline simple, composite et malformed fixtures.
- `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J14DetachedAssetContractTest.kt` — materialization, fermeture et détachement.
- `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/TrueTypeFixtureBuilder.kt` — génération de petites fixtures malformées et cycliques dans les tests uniquement.
- `kalligraphie/api/src/commonTest/kotlin/org/graphiks/kalligraphie/api/LayoutUnitTest.kt` — invariants numériques.
- `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/SfntReaderBoundaryTest.kt` — bornes de lecture et wrappers refusés.
- `kalligraphie/font/scaler/src/commonTest/kotlin/org/graphiks/kalligraphie/font/scaler/CmapReaderTest.kt` — tables cmap 4/12 synthétiques indépendantes.
- `kalligraphie/font/scaler/src/commonTest/kotlin/org/graphiks/kalligraphie/font/scaler/GlyfReaderTest.kt` — contours et cycle composite.

---

## Task 1: J1.1 — graphe KMP, source immuable, SFNT, catalogue et face

**Branch:** `feat/font-j1-1` from `master` plus the committed spec.

**Files:**
- Create: `buildSrc/src/main/kotlin/ygdrasil/conventions/kalligraphie-kmp-library.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `kalligraphie/build.gradle.kts`
- Create: `kalligraphie/api/build.gradle.kts`
- Create: `kalligraphie/unicode/build.gradle.kts`
- Create: `kalligraphie/font/core/build.gradle.kts`
- Create: `kalligraphie/font/sfnt/build.gradle.kts`
- Create: `kalligraphie/font/scaler/build.gradle.kts`
- Create: `kalligraphie/font/glyph/build.gradle.kts`
- Create: `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontGeometry.kt`
- Create: `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontIdentity.kt`
- Create: `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontDiagnostics.kt`
- Create: `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontContracts.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/SfntReader.kt`
- Create: `kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/EmbeddedFontCatalog.kt`
- Create: `kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/TrueTypeFace.kt`
- Create: `kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/Kalligraphie.kt`
- Create: `kalligraphie/src/jvmTest/resources/fonts/liberation/LiberationSans-Regular.ttf`
- Create: `kalligraphie/src/jvmTest/resources/fonts/liberation/OFL-1.1.txt`
- Create: `kalligraphie/src/jvmTest/resources/fonts/liberation/PROVENANCE.md`
- Create: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J11FontFaceContractTest.kt`
- Create: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/SfntReaderBoundaryTest.kt`

**Interfaces:**
- Produces immutable `FontSource`, `FontSourceId`, `FontFaceId`, `FontFaceRequest`, `FontFaceMetadata`, `FontCatalogSnapshot`, `FontFace`, `FontOperationResult` and stable diagnostic/error types.
- Produces `Kalligraphie.embedded(bytes, provenance): FontOperationResult<FontCatalogSnapshot>`.
- Produces `FontCatalogSnapshot.openAssetResolver()` and `resolveFace(request, requirements)` with the final method shapes, even if later slices add behavior behind them.
- Consumes no types from the old `font/` modules.

- [ ] **Step 1: Add the KMP module shell and the failing public scenario.**

  Add the project includes and the KMP convention with `jvm()` only. Add the
  three fixture files by copying the existing licensed fixture byte-for-byte:

  ```bash
  mkdir -p kalligraphie/src/jvmTest/resources/fonts/liberation
  cp font/sfnt/src/test/resources/fonts/liberation/LiberationSans-Regular.ttf kalligraphie/src/jvmTest/resources/fonts/liberation/LiberationSans-Regular.ttf
  cp font/sfnt/src/test/resources/fonts/liberation/OFL-1.1.txt kalligraphie/src/jvmTest/resources/fonts/liberation/OFL-1.1.txt
  cp font/sfnt/src/test/resources/fonts/liberation/PROVENANCE.md kalligraphie/src/jvmTest/resources/fonts/liberation/PROVENANCE.md
  ```

  Write `J11FontFaceContractTest.kt` so it loads the real resource and asserts
  behavior, not class existence:

  ```kotlin
  @Test
  fun opensTheAuditedTrueTypeFaceThroughThePublishedFacade() {
      val result = Kalligraphie.embedded(
          sourceBytes = fixtureBytes(),
          provenance = FontSourceProvenance(declaredName = "Liberation Sans Regular"),
      )
      val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(result).value
      val face = assertIs<FontOperationResult.Success<FontFace>>(
          catalog.resolveFace(FontFaceRequest(faceIndex = 0), FontAccessRequirementsSnapshot.layoutOnly()),
      ).value

      assertEquals("Liberation Sans", face.metadata.familyName)
      assertEquals("Regular", face.metadata.styleName)
      assertEquals(2048, face.metadata.unitsPerEm)
      assertEquals(2620, face.metadata.glyphCount)
      assertTrue(face.id.value.isNotBlank())
  }
  ```

  Run the test before adding the API or production implementation:

  ```bash
  ./gradlew :kalligraphie:jvmTest --tests org.graphiks.kalligraphie.J11FontFaceContractTest
  ```

  Expected RED: the new facade and contracts are not available yet. Fix only
  test setup errors; do not make a passing existence-only test.

- [ ] **Step 2: Define the minimum public value types and result algebra.**

  In `FontGeometry.kt`, define `LayoutUnit(Float)` with finite-value checks,
  `-0f` normalization, `DesignBounds` using signed integer design units and
  `LayoutBounds` using `LayoutUnit`. In `FontIdentity.kt`, define immutable
  provenance, `FontSource` with defensive copies, SHA-256 content identity,
  `FontSourceId`, `FontFaceId` and `FontInstanceKey`. In
  `FontDiagnostics.kt`, define:

  ```kotlin
  sealed interface FontOperationResult<out T> {
      data class Success<T>(val value: T, val diagnostics: List<FontDiagnostic> = emptyList()) : FontOperationResult<T>
      data class Failure(val error: FontError, val diagnostics: List<FontDiagnostic> = emptyList()) : FontOperationResult<Nothing>
      data class Cancelled(val diagnostics: List<FontDiagnostic> = emptyList()) : FontOperationResult<Nothing>
  }
  ```

  Define `FontDiagnosticLocation` for source/table/face/glyph and the J1
  errors `InvalidFontData`, `UnsupportedContainer`, `MissingRequiredTable`,
  `OutOfBounds`, `ResourceLimitExceeded`, `ResourceClosed`,
  `UnsupportedRepresentationProfile`, `GlyphOutOfRange`,
  `GeometryOverflow` and `Cancelled`. Sort diagnostic lists at construction.
  Keep argument validation for programmer errors separate from malformed font
  data, which must become `Failure`.

- [ ] **Step 3: Define the stable public interfaces and the profile boundary.**

  In `FontContracts.kt`, define the following public shapes:

  ```kotlin
  public interface FontCatalogSnapshot {
      public val sourceId: FontSourceId
      public fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle>
      public fun resolveFace(
          request: FontFaceRequest,
          requirements: FontAccessRequirementsSnapshot,
      ): FontOperationResult<FontFace>
  }

  public interface FontFace {
      public val id: FontFaceId
      public val metadata: FontFaceMetadata
      public fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance>
  }
  ```

  Add `FontAccessRequirementsSnapshot` with `LAYOUT_ONLY` and `RENDERABLE`,
  `OutlineProfile(schemaVersion = 1, maxBytes, maxContours, maxPoints,
  maxCompositeDepth, maxCompositeComponents)`, `FontRenderVariantKey.default`,
  `FontGlyphRequest`, `FontAssetResolverHandle`, `FontRenderAssetHandle` and
  `GlyphRepresentation`/`GlyphOutlineIR` types. Leave outline commands
  structurally defined here so later modules cannot redesign the public IR.

- [ ] **Step 4: Implement the bounded SFNT reader and J1.1 parser.**

  In `SfntReader.kt`, implement an immutable byte source and big-endian cursor
  that checks every read against its table slice. Accept exactly the TrueType
  scaler values `0x00010000` and `true`; return `UnsupportedContainer` for
  `ttcf`, `OTTO`, `typ1` and unknown wrappers. Parse the 12-byte header and
  16-byte directory records, reject duplicate required tags, zero-length
  required tables, offsets/lengths outside the source, and arithmetic overflow.
  Keep the directory records sorted only in derived evidence; preserve table
  lookup by tag for parsing.

  For J1.1, require `head`, `maxp`, `name`, `cmap`, `hhea`, `hmtx`, `loca` and
  `glyf`. Decode `head.unitsPerEm`, `head.indexToLocFormat`,
  `maxp.numGlyphs`, and English Unicode `name` IDs 1 and 2 with a deterministic
  fallback to the first valid Unicode name record. Do not parse a system font
  or call an external font engine.

- [ ] **Step 5: Implement the mono-source catalog, face selection and facade.**

  `Kalligraphie.embedded` must copy bytes, derive the content ID, invoke the
  bounded parser and return either a usable `FontCatalogSnapshot` or a typed
  failure. `resolveFace` accepts only index `0` and only `LAYOUT_ONLY` or the
  J1 outline profile. A request for another index, a missing required table or
  an unsupported profile returns a diagnostic without falling through to face
  zero. `openAssetResolver` returns a live resolver tied to the immutable
  source snapshot.

  The source and face objects retain only immutable byte arrays owned by the
  catalog implementation. Do not expose the old `FontSource` or SFNT classes
  in the public signatures.

- [ ] **Step 6: Run the J1.1 RED-to-GREEN cycle and inspect the diff.**

  Run the focused tests and the old reference suite:

  ```bash
  ./gradlew :kalligraphie:api:jvmTest :kalligraphie:font:sfnt:jvmTest :kalligraphie:jvmTest --tests org.graphiks.kalligraphie.J11FontFaceContractTest
  ./gradlew :font:fontTest
  ```

  Expected GREEN: the real fixture reports family `Liberation Sans`, style
  `Regular`, `unitsPerEm = 2048`, `glyphCount = 2620`, and a non-empty content
  identity. Malformed SFNT tests must return typed failures rather than throw.
  Inspect `git diff --check`, `git diff --stat` and `git status --short`.

- [ ] **Step 7: Commit J1.1.**

  ```bash
  git add settings.gradle.kts buildSrc/src/main/kotlin/ygdrasil/conventions/kalligraphie-kmp-library.gradle.kts kalligraphie
  git commit -m "feat(font): add autonomous TrueType catalog"
  ```

---

## Task 2: J1.2 — cmap, glyph IDs et métriques

**Branch:** `feat/font-j1-2` created from the J1.1 commit.

**Files:**
- Modify: `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontContracts.kt`
- Modify: `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontGeometry.kt`
- Create: `kalligraphie/font/scaler/src/commonMain/kotlin/org/graphiks/kalligraphie/font/scaler/CmapReader.kt`
- Create: `kalligraphie/font/scaler/src/commonMain/kotlin/org/graphiks/kalligraphie/font/scaler/MetricsReader.kt`
- Modify: `kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/TrueTypeFace.kt`
- Create: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J12GlyphMetricsContractTest.kt`
- Create: `kalligraphie/font/scaler/src/commonTest/kotlin/org/graphiks/kalligraphie/font/scaler/CmapReaderTest.kt`

**Interfaces:**
- Produces `GlyphId`, `GlyphResolution`, `GlyphMetrics`, `FontInstance.resolveGlyph(codePoint)` and `FontInstance.metrics(glyphId)`.
- Consumes the immutable face/table view from J1.1; it does not parse `glyf` yet.

- [ ] **Step 1: Add the failing real-font metrics scenario.**

  Write `J12GlyphMetricsContractTest.kt` against the public facade. The
  independent expectations for `LiberationSans-Regular.ttf` are `unitsPerEm =
  2048`, `U+0041 → glyphId 36`, `advanceWidth = 1366`,
  `leftSideBearing = 4`, and `U+00C4 → glyphId 134`, `advanceWidth = 1366`.
  Use an instance size of `2048f` so scaled advances equal the design values:

  ```kotlin
  @Test
  fun resolvesAndMeasuresAuditedGlyphs() {
      val instance = openInstance(size = 2048f)
      val a = assertIs<FontOperationResult.Success<GlyphResolution>>(instance.resolveGlyph(0x41)).value
      assertEquals(36, a.glyphId.value)
      val metrics = assertIs<FontOperationResult.Success<GlyphMetrics>>(instance.metrics(a.glyphId)).value
      assertEquals(1366, metrics.advanceWidthDesignUnits)
      assertEquals(4, metrics.leftSideBearingDesignUnits)
      assertEquals(1366f, metrics.advanceWidth.value)
  }

  @Test
  fun missingCharacterUsesNotdefAndReportsTheDecision() {
      val resolution = assertIs<FontOperationResult.Success<GlyphResolution>>(
          openInstance(2048f).resolveGlyph(0x10ffff),
      )
      assertEquals(0, resolution.value.glyphId.value)
      assertTrue(resolution.diagnostics.any { it.code == "font.cmap.glyph-not-found" })
  }
  ```

  Run the focused task before adding cmap/metrics code and confirm RED for the
  missing methods or behavior.

- [ ] **Step 2: Implement deterministic cmap selection and formats 4/12.**

  `CmapReader` must select a Unicode subtable in this order: platform 3
  encoding 10 format 12, platform 0 format 12, platform 3 encoding 1 format 4,
  then platform 0 format 4; ties are resolved by table offset. Validate each
  subtable length and segment/Group records before access. Format 4 uses signed
  `idDelta` and `idRangeOffset` arithmetic without wrapping; format 12 uses
  non-overlapping sorted groups and checks `startCharCode ≤ endCharCode`.
  Return glyph ID 0 for an absent character and attach the stable diagnostic
  `font.cmap.glyph-not-found`.

  Add synthetic common tests for one format-4 segment, one format-4
  `idRangeOffset` segment, one format-12 group, malformed segment ordering and
  a codepoint outside the Unicode scalar range. These tests assert mappings and
  failures, not private helper calls.

- [ ] **Step 3: Implement head/hhea/hmtx metrics and LayoutUnit scaling.**

  Parse `hhea.numberOfHMetrics`. For glyph IDs below that count read the
  advance and side bearing pair; for later glyphs reuse the last advance and
  read trailing side bearings. Reject truncated records and glyph IDs outside
  `0 until numGlyphs`. Compute scaling as `designValue * size / unitsPerEm`
  using Float results at the public boundary, normalizing `-0f` and returning
  `GeometryOverflow` when the result is not finite.

  Add `FontMetrics` fields for design-unit advance/side bearing and
  `LayoutUnit` advance. Bounds remain unavailable until J1.3 and are returned
  as the explicit empty design bounds value rather than fabricated ink.

- [ ] **Step 4: Wire the instance and verify the real fixture.**

  Update `TrueTypeFace.kt` so `FontFace.instantiate` stores an immutable
  descriptor and `FontInstance.resolveGlyph`/`metrics` delegate to the cmap and
  metrics readers. Keep character resolution separate from shaping: one scalar
  maps to one glyph ID and no substitutions are performed.

  Run:

  ```bash
  ./gradlew :kalligraphie:font:scaler:jvmTest :kalligraphie:jvmTest --tests org.graphiks.kalligraphie.J12GlyphMetricsContractTest
  ./gradlew :font:fontTest
  ```

  Confirm the independent values `A → 36`, `advance 1366`, `LSB 4`, and the
  `.notdef` diagnostic. Then inspect `git diff --check`.

- [ ] **Step 5: Commit J1.2.**

  ```bash
  git add kalligraphie/api kalligraphie/font/scaler kalligraphie/font/core kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J12GlyphMetricsContractTest.kt
  git commit -m "feat(font): resolve TrueType glyph metrics"
  ```

---

## Task 3: J1.3 — loca/glyf, contours simples/composites et limites

**Branch:** `feat/font-j1-3` created from the J1.2 commit.

**Files:**
- Modify: `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontGeometry.kt`
- Modify: `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontContracts.kt`
- Create: `kalligraphie/font/scaler/src/commonMain/kotlin/org/graphiks/kalligraphie/font/scaler/LocaReader.kt`
- Create: `kalligraphie/font/scaler/src/commonMain/kotlin/org/graphiks/kalligraphie/font/scaler/GlyfReader.kt`
- Create: `kalligraphie/font/glyph/src/commonMain/kotlin/org/graphiks/kalligraphie/font/glyph/OutlineMaterializer.kt`
- Modify: `kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/TrueTypeFace.kt`
- Create: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J13GlyphOutlineContractTest.kt`
- Create: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/TrueTypeFixtureBuilder.kt`
- Create: `kalligraphie/font/scaler/src/commonTest/kotlin/org/graphiks/kalligraphie/font/scaler/GlyfReaderTest.kt`

**Interfaces:**
- Produces immutable `GlyphOutlineIR`, `GlyphContour`, `GlyphOutlineCommand`, `FillRule.NON_ZERO`, `GlyphOutlineLimits` and `GlyphRepresentation.Outline`.
- Consumes `GlyphId`, `FontInstance` and table slices from J1.2.

- [ ] **Step 1: Add failing outline and malformed-font scenarios.**

  Extend the facade integration tests with these independent facts from the
  audited fixture: `U+0041 → glyphId 36`, raw glyph bounds
  `[4, 0, 1362, 1409]`, two contours and 17 points; `U+00C4 → glyphId 134`,
  raw glyph bounds `[4, 0, 1362, 1714]`, with component glyph IDs `36` and
  `2338`, the second translated by `(364, 0)`. The tests must assert bounds,
  contour count and resolved composite behavior through the public facade.

  Add tests for a truncated `glyf` range, an out-of-range `loca` entry and a
  composite fixture whose component points back to itself. Each expects a
  typed failure with the relevant diagnostic code. Run the focused test before
  adding the outline implementation and observe RED.

- [ ] **Step 2: Implement loca with format 0 and format 1 validation.**

  For `indexToLocFormat = 0`, read unsigned 16-bit offsets and multiply by 2
  only after checking the multiplication against the `glyf` table length. For
  format 1, read unsigned 32-bit offsets. Require monotonic offsets,
  `offset[glyphId] ≤ offset[glyphId + 1]`, and an end not exceeding `glyf`.
  Empty ranges produce an empty glyph representation; they do not fabricate a
  contour or bounds.

- [ ] **Step 3: Implement simple glyf point decoding and canonical contours.**

  Parse the 10-byte glyph header, contour end points, instruction length and
  packed flags. Enforce the profile’s max points/contours and every glyph
  range. Decode repeated flags and signed X/Y deltas with checked arithmetic.
  Reconstruct on-curve points and insert implied midpoints between consecutive
  off-curve points. Emit one closed contour per end point using only
  `MoveTo`, `LineTo`, `QuadraticTo` and `Close`; never emit cubic commands for
  TrueType `glyf` data. Compute bounds from the original design-unit points.

- [ ] **Step 4: Implement composite glyf resolution with cycle guards.**

  Parse component flags, component glyph IDs, XY arguments and the supported
  scale/uniform/2×2 transforms. Reject point-matching arguments, invalid
  component IDs, truncated component records and unsupported flags with typed
  diagnostics. Resolve components depth-first with a path set and a monotonic
  component/depth budget. If a glyph ID re-enters the active path, return
  `font.glyf.composite-cycle`; do not silently drop the component or recurse
  forever. Transform child points before merging bounds and contours.

- [ ] **Step 5: Materialize `GlyphOutlineIR` under the profile.**

  In `OutlineMaterializer.kt`, copy the immutable design-unit commands and
  bounds into `GlyphOutlineIR`, record the applied limits, and return
  `GlyphRepresentation.Empty` for a glyph with no ink. Check command count,
  point count, contour count and total byte budget before publication. Keep
  `GlyphOutlineIR` in design units; J1.4 will attach it to a scaled render
  asset without changing its coordinate domain.

- [ ] **Step 6: Verify true-font and malformed-font behavior.**

  Run:

  ```bash
  ./gradlew :kalligraphie:font:scaler:jvmTest :kalligraphie:jvmTest --tests org.graphiks.kalligraphie.J13GlyphOutlineContractTest
  ./gradlew :kalligraphie:fontTest
  ./gradlew :font:fontTest
  ```

  Confirm `A` has two contours, 17 points and bounds `[4, 0, 1362, 1409]`,
  `Ä` has bounds `[4, 0, 1362, 1714]` and components `36`/`2338`, and the
  malformed fixtures return bounded failures. Inspect the full diff hunks for
  all scaler and API files.

- [ ] **Step 7: Commit J1.3.**

  ```bash
  git add kalligraphie/api kalligraphie/font/scaler kalligraphie/font/core kalligraphie/src/jvmTest
  git commit -m "feat(font): add TrueType glyph outlines"
  ```

---

## Task 4: J1.4 — instances, scaling, asset handles et détachement

**Branch:** `feat/font-j1-4` created from the J1.3 commit.

**Files:**
- Modify: `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontContracts.kt`
- Modify: `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontDiagnostics.kt`
- Modify: `kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/TrueTypeFace.kt`
- Modify: `kalligraphie/font/glyph/src/commonMain/kotlin/org/graphiks/kalligraphie/font/glyph/OutlineMaterializer.kt`
- Modify: `kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/Kalligraphie.kt`
- Create: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J14DetachedAssetContractTest.kt`
- Modify: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J11FontFaceContractTest.kt`
- Modify: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/J12GlyphMetricsContractTest.kt`
- Modify: `CHANGELOG.md`
- Create: `docs/docs/font-management.md`
- Create: `docs/docs/font-management.fr.md`
- Modify: `docs/mkdocs.yml`

**Interfaces:**
- Produces the final `FontInstanceDescriptor`, `FontInstanceKey`,
  `FontAssetResolverHandle`, `FontRenderAssetHandle.detach()`,
  `FontRenderAssetHandle.resolveGlyph(...)`, `GlyphRequest` and final facade
  integration path.
- Consumes the complete `GlyphOutlineIR` and metrics behavior from J1.3.

- [ ] **Step 1: Add the failing detached-asset lifecycle scenario.**

  Write the final public path with an explicit outline profile:

  ```kotlin
  val resolver = success(catalog.openAssetResolver())
  val face = success(catalog.resolveFace(FontFaceRequest(0), OutlineRequirements.default()))
  val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
  val attached = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, OutlineRequirements.default()))
  val detached = success(attached.detach())
  resolver.close()
  attached.close()

  val representation = success(
      detached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none),
  )
  assertIs<GlyphRepresentation.Outline>(representation)
  ```

  Add assertions that closing twice is harmless, resolving through the closed
  attached handle returns `ResourceClosed`, and the detached handle remains
  usable. Run before implementing handles and observe RED.

- [ ] **Step 2: Implement immutable instance keys and deterministic scaling.**

  Make `FontFace.instantiate` validate a finite positive `LayoutUnit`, derive
  a key from face ID, parser/scaler interpretation version and size, and keep
  the source bytes immutable. Ensure repeated identical descriptors produce
  equal keys while different sizes or different source content cannot collide.
  No variation coordinates or synthetic styles are accepted in J1.

- [ ] **Step 3: Implement resolver/asset lifecycle and detachment.**

  Add a small synchronized state machine in common code using an atomic state
  abstraction owned by the implementation: `OPEN → CLOSING → CLOSED`.
  `close()` is idempotent. `acquireRenderAsset` must atomically verify the
  resolver is open. `detach()` copies the immutable table slices or parsed
  outline data required by the asset, marks the returned handle detached, and
  ensures it no longer retains resolver, catalog or instance references.
  New operations after close return `ResourceClosed`; an operation that has
  acquired its lease before `CLOSING` may finish.

- [ ] **Step 4: Implement outline-profile materialization and cancellation.**

  `resolveGlyph` validates the request and profile limits before calling the
  J1.3 outline reader. It returns `Outline(GlyphOutlineIR)` or `Empty`, never
  `Unsupported` for a certified J1 outline route. Check `CancellationToken`
  between table read, loca, glyf and composite phases; return `Cancelled`
  without publishing partial output.

- [ ] **Step 5: Verify the complete public journey.**

  Run the final focused tests and all existing font tests:

  ```bash
  ./gradlew :kalligraphie:jvmTest
  ./gradlew :kalligraphie:fontTest
  ./gradlew :font:fontTest
  ```

  Confirm the complete path succeeds through the façade, the outline remains
  in design units, metrics use `LayoutUnit`, detached assets survive owner
  closure, and malformed/cyclic fixtures remain typed failures. Inspect
  `git status --short`, `git diff --stat`, `git diff --name-only` and
  `git diff --check` before committing.

- [ ] **Step 6: Add the narrow documentation and changelog entry.**

  Document only the J1 JVM-supported path in the two language versions of
  `font-management`, link it from `docs/mkdocs.yml`, and add one `CHANGELOG.md`
  entry describing the autonomous embedded TrueType catalog, cmap/metrics,
  outlines and detached assets. Keep the documented exclusions explicit.

- [ ] **Step 7: Commit J1.4.**

  ```bash
  git add kalligraphie CHANGELOG.md docs/docs/font-management.md docs/docs/font-management.fr.md docs/mkdocs.yml
  git commit -m "feat(font): add detached TrueType render assets"
  ```

---

## Task 5: integrate the stacked branches and validate the issue

**Files:**
- Modify: GitHub PR bodies and issue #4 checklist through `gh`.
- Create: final local branch `feat/font-j1` pointing at the J1.4 commit.

- [ ] **Step 1: Reconcile branch ancestry and full verification.**

  From `feat/font-j1-4`, rebase onto the latest `master` if it moved, then
  create the final pointer:

  ```bash
  git fetch origin master
  git rebase origin/master
  git branch -f feat/font-j1 HEAD
  ./gradlew :kalligraphie:jvmTest :kalligraphie:fontTest :font:fontTest
  git log --oneline --decorate --graph master..feat/font-j1
  git status --short
  ```

  The final verification must exit 0 and the final branch must contain no
  merge commit or unrelated file.

- [ ] **Step 2: Inspect each stacked diff.**

  ```bash
  git diff --stat master..feat/font-j1-1
  git diff --stat feat/font-j1-1..feat/font-j1-2
  git diff --stat feat/font-j1-2..feat/font-j1-3
  git diff --stat feat/font-j1-3..feat/font-j1-4
  git diff --name-only master..feat/font-j1
  ```

  Confirm every incremental diff is coherent, the old `font/` tree is
  unchanged, and only the final slice changes changelog/docs.

- [ ] **Step 3: Push the branches and open the stacked PRs.**

  Use the repository’s configured origin and the required PR template. Each
  body must contain the exact headings `Description`, `Type of Change`,
  `Checklist`, `Screenshots (if applicable)` and `Additional Notes`, exactly
  one change-type checkbox, the related issue, the tests run and an explicit
  changelog/documentation decision.

  ```bash
  git push -u origin feat/font-j1-1
  git push -u origin feat/font-j1-2
  git push -u origin feat/font-j1-3
  git push -u origin feat/font-j1-4
  git push -u origin feat/font-j1
  ```

  Open PRs with titles `feat(font): add autonomous TrueType catalog`,
  `feat(font): resolve TrueType glyph metrics`,
  `feat(font): add TrueType glyph outlines`, and
  `feat(font): add detached TrueType render assets`, targeting the preceding
  branch in the stack. The first PR targets `master`; the last targets
  `feat/font-j1-3`. Keep `feat/font-j1` as the final integration pointer.

- [ ] **Step 4: Run independent review and address findings.**

  Review each non-trivial PR against #4, with special attention to true-font
  expectations, malformed ranges, composite cycles, content identity
  collisions, detached lifecycle and public package leakage. Address only
  actionable findings, rerun the full verification command, and push updates
  to the affected branch while preserving ancestry.

- [ ] **Step 5: Update issue #4 after the stack is accepted.**

  Check the four J1 checklist rows only after their corresponding PRs are
  merged or otherwise accepted by the repository policy. Add the final branch
  and PR links, the verification command/results, and the fact that the old
  `font/` sources remain reference-only. Close the issue only when the final
  consumer journey and detached-asset criterion are demonstrated.
