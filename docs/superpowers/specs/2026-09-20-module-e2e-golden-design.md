# Module `:kalligraphie:e2e` — vérification end-to-end par empreintes golden

- **Date** : 2026-09-20
- **Statut** : design validé, en attente d'implémentation
- **Portée** : création du module `:kalligraphie:e2e`, absorption des journeys et de la conformance golden de composition, consolidation des fixtures.

## 1. Contexte

Kalligraphie est une bibliothèque Kotlin Multiplatform de typographie. Son paysage de tests actuel :

- des tests unitaires par module (`commonTest` / `jvmTest`) ;
- cinq « journey tests » dans `:kalligraphie/src/jvmTest`, exerçant la facade publique de bout en bout (comportemental, sans pixel) : `AdvancedTypographyJourneyTest`, `CffOpenTypeJourneyTest`, `FlowCompositionEditorJourneyTest`, `IncrementalLayoutEditorJourneyTest`, `SystemFontCatalogJourneyTest` ;
- `:kalligraphie:conformance`, module **non publié** qui porte l'autorité de conformité portative ;
- `:kalligraphie:raster-cpu`, rasterizer CPU déterministe, qui mêle tests unitaires du rasterizer, conformance golden de composition (`OutlineConformanceTest`, `PaintConformanceTest`, `BitmapConformanceTest`, `ComposedLineDumps*`, `GlyphSheetDumps*`) et sceau des assets logo ;
- deux tests de mesure opt-in (`GlyphMaterializationBenchmarkTest`, `EditableLineMeasurementTest`), exclus de `check` ;
- CI : `.github/workflows/font-tests.yml` exécute `./gradlew check` sur les PR touchant `kalligraphie/**`.

Deux problèmes en résultent :

1. il n'existe pas de **point d'entrée E2E unique** — les journeys sont noyés dans le module facade ;
2. la vérification golden est **éclatée dans `raster-cpu`**, où elle se confond avec les tests du rasterizer lui-même.

**Objectif** : un module dédié qui devient le point d'entrée E2E unique (journeys + goldens de composition), isolé, sans polluer les modules de production.

## 2. Décisions structurantes

| Sujet | Décision |
| --- | --- |
| Architecture | Approche A — module KMP dédié `:kalligraphie:e2e`, non publié, frère de `conformance`. |
| Références golden | Hybride — manifest d'empreintes versionné (source de vérité CI) + dump d'inspection opt-in. |
| Cibles | JVM uniquement aujourd'hui ; structure KMP prête (`commonMain` = modèle, `jvmMain`/`jvmTest` = exécution), sans `expect`/`actual` anticipé. |
| CI | Bloquant via le `check` existant ; aucun workflow dédié. |
| Frontière | Deux axes — journeys comportementaux et scènes golden vivent côte à côte, sans double couverture. |
| Fixtures | Répertoire commun racine `test-fixtures/` (convention établie par la PR #74) ; pas de module fixtures ; builders Kotlin par consommateur. |

## 3. Module et câblage build

- **Chemin** : `kalligraphie/e2e/` ; **chemin Gradle** `:kalligraphie:e2e` ; ajouté à `settings.gradle.kts`.
- **Plugin** : `ygdrasil.conventions.kmp-library` — identique à `:kalligraphie:conformance`, donc **non publié**. La convention déclare les cibles `jvm`, `iosArm64`, `iosSimulatorArm64` et `android` ; seul `jvmTest` porte des tests.
- **`explicitApi()`** activé (comme `conformance`).
- **Dépendances** :
  - `commonMain` : `api(project(":kalligraphie:api"))`
  - `jvmMain` : `implementation(project(":kalligraphie"))`, `implementation(project(":kalligraphie:raster-cpu"))`
  - `jvmTest` : `kotlin("test")`
  - `jvmTest` resources : `srcDir(rootProject.file("test-fixtures"))`
- **JVM** : `jvmArgs("--enable-native-access=ALL-UNNAMED")` (comme les modules qui chargent HarfBuzz).
- **Tâches Gradle** :
  - `jvmTest` : vérification des empreintes ; filtre excluant le runner de dump.
  - `e2eGoldenDumps` : opt-in, activée par `KALLIGRAPHIE_E2E_DUMPS=true`, écrit PNG/PGM vers `KALLIGRAPHIE_E2E_DUMPS_OUTPUT` (chemin absolu **hors repo**) ; `outputs.upToDateWhen { false }` ; **hors `check`**.
  - `updateE2eGolden` : opt-in, régénère le manifest d'empreintes ; **hors `check`**.

## 4. Modèle `commonMain` / exécution `jvmMain`

**`commonMain`** (pur, zéro dépendance plateforme) — le seam KMP :

- `GoldenScene(id: String, family: GoldenSceneFamily, width: Int, height: Int, tags: Set<String>)`
- `GoldenSceneFamily` : `GLYPH_OUTLINE`, `GLYPH_PAINT`, `GLYPH_BITMAP`, `ALPHABET_SHEET`, `COMPOSED_LINE`, `PARAGRAPH`, `FLOW_REGION`
- `PixelFormat` : `ALPHA_8`, `RGBA_8888` (straight, non prémultiplié)
- `GoldenImage(bytes: ByteArray, width: Int, height: Int, format: PixelFormat)`
- `CANONICALIZATION_VERSION = 1`
- `GoldenFingerprint(sceneId, family, width, height, format, sha256)`
- `GoldenManifest` : liste triée de `GoldenFingerprint`, codec TSV parse/serialize déterministe
- `GoldenComparison` : `Matched` | `Mismatch(expected, actual, firstDifferingByte, x, y)` | `MissingInManifest` | `StaleManifestEntry` | `RenderFailed(code)`
- codes de diagnostic préfixés `e2e.` (voir §6)

**`jvmMain`** :

- `JvmGoldenSceneCatalog` : déclaration des scènes concrètes (glyphes isolés, feuilles d'alphabet, lignes composées, paragraphes, flow regions).
- `JvmGoldenRenderer` : `GoldenScene` → `GoldenImage` via la facade publique + `raster-cpu`.
- `JvmGoldenVerifier` : produit `catalogue × manifest` → résultats de comparaison.

**`jvmTest`** :

- `GoldenVerificationTest` : itère le catalogue, rend, compare ; échoue sur tout résultat non `Matched`.
- `GoldenHarnessTest` : auto-validation du harnais (voir §11).
- `GoldenDumpRunnerTest` : exclu de `jvmTest`, exécuté par la tâche `e2eGoldenDumps`.

**Arborescence** :

```
kalligraphie/e2e/
  src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/     # modèle + comparateur (§4)
  src/jvmMain/kotlin/org/graphiks/kalligraphie/e2e/        # catalogue + renderer + verifier
  src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/journey/ # journeys comportementaux (§7)
  src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/  # catalogue de scènes + vérification
  src/jvmTest/resources/golden/manifest.tsv                # source de vérité CI (§5)
```

## 5. Canonicalisation et format du manifest

**Canonicalisation** — une seule forme sérialisée, stable et portable :

- pixels row-major, aucun padding ;
- orientation source fixée, **flip interdit** (le flip vertical reste une préoccupation *dump uniquement*) ;
- empreinte = SHA-256 des octets canoniques ;
- versionnée par `CANONICALIZATION_VERSION`.

**Manifest** — `kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv`, trié, diff lisible :

```
kalligraphie.golden/v1 canonicalization=1
<sceneId>\t<family>\t<W>x<H>\t<FORMAT>\tsha256:<hex>
```

- UTF-8, LF, tri lexicographique sur `sceneId`, aucune ligne vide, aucun commentaire.
- Le champ `canonicalization=` qui diffère de `CANONICALIZATION_VERSION` invalide **tout** le manifest d'un coup : échec unique et explicite (« relancer `updateE2eGolden` »), au lieu d'une marée de mismatches.
- Le code ne réécrit **jamais** le manifest automatiquement : il est modifié uniquement par `updateE2eGolden`, puis relu en revue.

## 6. Comparaison et diagnostics

Comparaison **exacte octet à octet**, sans tolérance — cohérent avec le raster déterministe (arithmétique entière, seize sous-échantillons fixes). Une tolérance n'arriverait que si une route non déterministe apparaissait.

Règles de verdict :

- scène présente au catalogue mais absente du manifest → `MissingInManifest` → **échec** (pas de pass silencieux) ;
- entrée du manifest sans scène correspondante → `StaleManifestEntry` → **échec** ;
- `id` dupliqué (catalogue ou manifest) → échec au chargement ;
- échec de rendu → `RenderFailed` avec un code typé ;
- mismatch → message avec `id`, hashes attendu/actuel, premier octet divergent **et** ses coordonnées `(x, y)`, plus le conseil de lancer `e2eGoldenDumps`.

Codes de diagnostic :

- `e2e.render-failed`
- `e2e.scene-bounds-invalid`
- `e2e.manifest-missing-entry`
- `e2e.manifest-stale-entry`
- `e2e.manifest-duplicate-id`
- `e2e.canonicalization-version-mismatch`

## 7. Frontière journeys / scènes golden

Deux axes distincts, sans double couverture :

- `e2e/journey/` — tests comportementaux migrés (assertions de géométrie : carets, sélection, fragmentation, limites d'opération…). Diagnostic riche, exécution rapide.
- `e2e/golden/` — catalogue de scènes et comparaison d'empreintes (assertion pixel finale).

Une scène golden est **promue** depuis un journey uniquement là où l'empreinte pixel apporte ce que la géométrie ne couvre pas. Sinon la géométrie suffit.

## 8. Absorption, migration et fixtures

**Vers `:kalligraphie:e2e`** :

- les cinq `*JourneyTest.kt` listés en §1, plus les helpers qui leur sont exclusifs ;
- la conformance de composition de `raster-cpu/jvmTest` : `OutlineConformanceTest`, `PaintConformanceTest`, `BitmapConformanceTest`, `ComposedLineDumps*`, `GlyphSheetDumps*`, `RasterDumpRunnerTest`, `RasterFixtureSupport` → deviennent des scènes golden / le dump E2E. Les helpers `DumpImage` et `PngEncoder` sont réutilisés.

**Restent en place** :

- `raster-cpu/commonTest/*` — unités du rasterizer ;
- le sceau logo de `raster-cpu` (`KalligraphieLogoConformanceTest`, tâche `renderLogo`, assets `docs/assets`) — lié à la génération d'assets du repo ;
- les journeys de plateforme (`CoreTextRegistryJourneyTest`, `FontconfigRegistryJourneyTest`, `DirectWriteRegistryJourneyTest`) — testent les providers et tournent dans les jobs CI par plateforme ;
- les tests de mesure `GlyphMaterializationBenchmarkTest` et `EditableLineMeasurementTest` ;
- les tests de contrat de représentation de `:kalligraphie` (`Colr*`, `Sbix*`, `Ebdt*`, `CbdtCblc*`, etc.) — contrats du facade, pas des journeys ;
- `FontDirectoryCatalogTest` — catalogue réel sur système de fichiers, non nommé « Journey » ; reste dans `:kalligraphie`.

**Fixtures** :

- `test-fixtures/` (racine) est la source commune établie par la PR #74, consommée via `jvmTest { resources.srcDir(rootProject.file("test-fixtures")) }` ;
- on **termine la consolidation** : déplacer le jeu de polices partagé de `kalligraphie/src/jvmTest/resources/fonts` vers `test-fixtures/fonts/`, et basculer `raster-cpu` (qui copie aujourd'hui depuis `:kalligraphie`) ainsi que `e2e` sur ce `srcDir` ;
- les **builders Kotlin** de fixtures restent par consommateur ; `e2e` définit ses propres builders minces lisant `/fonts/...` sur le classpath. Aucun source set Kotlin partagé (KMP ne supporte pas proprement `java-test-fixtures`) tant qu'un troisième consommateur n'apparaît pas.

## 9. Phasage

Chaque phase est une PR atomique en Conventional Commits.

1. **P1 — Scaffold** : module + câblage build + modèle `commonMain` + codec manifest + comparateur + `GoldenHarnessTest` + une scène smoke.
2. **P2 — Fixtures et journeys** : consolidation `test-fixtures/` + migration des cinq journeys + mise à jour des filtres de `font-tests.yml`.
3. **P3 — Goldens de composition** : migration de la conformance de composition de `raster-cpu` vers des scènes golden ; `raster-cpu` allégé.
4. **P4 — Intégration** : câblage `check`, scope `e2e` dans `.github/contributing-policy.toml` et la table de `CONTRIBUTING.md`, doc `docs/docs/e2e-golden.md` (+ `.fr.md`), `CHANGELOG.md`.

## 10. CI et isolation

- Le `check` racine exécute `:kalligraphie:e2e:jvmTest` (comme tout sous-projet) → protection PR **gratuite** via `font-tests.yml`, dont le filtre `kalligraphie/**` couvre déjà le nouveau module.
- Le dump et la mise à jour du manifest sont opt-in, **hors `check`**.
- `.github/workflows/font-tests.yml` : la ligne de filtre
  `:kalligraphie:jvmTest --tests '*FontDirectoryCatalogTest*' --tests '*SystemFontCatalogJourneyTest*'`
  est scindée — `FontDirectoryCatalogTest` reste dans `:kalligraphie:jvmTest`, `SystemFontCatalogJourneyTest` passe à `:kalligraphie:e2e:jvmTest`.
- Aucun nouveau workflow n'est créé.

## 11. Auto-validation du harnais

`GoldenHarnessTest`, sur images synthétiques en mémoire :

- **stabilité d'empreinte** : même image → même hash ; ordre d'insertion / écriture différent → même hash ;
- **round-trip manifest** : serialize(parse(x)) == x ;
- **mismatch** : détecte le premier octet divergent et ses coordonnées ;
- **`MissingInManifest`**, **`StaleManifestEntry`**, **id dupliqué** ;
- **garde `canonicalization=`** contraire → échec explicite ;
- **mutation volontaire** d'une image → échec attendu (preuve que le comparateur détecte réellement).

## 12. Invariants

- `:kalligraphie:e2e` est **non publié** et n'entre jamais dans le graphe de dépendances consommateur de `org.graphiks:kalligraphie`.
- Comparaison exacte et déterministe ; pas de seuil flottant.
- Canonicalisation sans flip ; le flip reste une préoccupation de dump.
- Le manifest est la source de vérité CI ; le code ne l'écrit jamais sans une tâche opt-in explicite.
- Les tests de production ne dépendent jamais de `:kalligraphie:e2e`.

## 13. Hors périmètre et évolutions futures

- Activation des cibles natives quand `UNICODE_ANALYSIS`, `SHAPING` et `END_TO_END_LAYOUT` y seront présents.
- Tolérance numérique, si une route non déterministe apparaît.
- Hébergement externe des images de référence (LFS/artefact).
- Module `test-fixtures` partagé pour les builders Kotlin, si un troisième consommateur apparaît.
- Migration éventuelle des journeys de plateforme.

## 14. Références

- Mécanisme d'isolation existant : `kalligraphie/raster-cpu/build.gradle.kts` (tâches `rasterDumps`, `renderLogo`, exclusions de filtre).
- Patron de module non publié : `kalligraphie/conformance/build.gradle.kts`.
- Matrice de capacités par plateforme : `docs/docs/conformance-matrix.md`.
- Rasterizer déterministe : `docs/docs/raster-cpu.md`.
- Répertoire commun de fixtures : `test-fixtures/` (introduit par la PR #74).
