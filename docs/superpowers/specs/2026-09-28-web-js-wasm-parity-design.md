# Conception — Élargissement de Kalligraphie au web `js` + `wasmJs` en parité

- **Statut :** proposé (en attente de revue)
- **Date :** 2026-09-28
- **Portée :** ajout des cibles web `js(IR)` et `wasmJs` à la bibliothèque
  Kalligraphie, avec la même surface de capacités portables que JVM, Android et
  iOS, et renommage neutre de l'API publique.
- **Décideurs :** mainteneurs Kalligraphie.

## 1. Contexte

Kalligraphie est une bibliothèque de typographie Kotlin Multiplatform (KMP). Les
cibles actuelles sont `jvm`, `android`, `iosArm64` et `iosSimulatorArm64`
(`buildSrc/src/main/kotlin/ygdrasil/conventions/kmp-library.gradle.kts`).

Le cœur portable vit déjà en `commonMain` : contrats (`api`), décodage et
analyse Unicode 16.0 (`unicode`), lecture SFNT / scaler / glyphes
(`font:core|sfnt|scaler|glyph`), composition incrémentale (`layout`) et
rastérisation CPU (`raster-cpu`). Une vérification de faisabilité a confirmé
qu'**aucun `commonMain` n'importe de type non portable** (`java.*`,
`kotlinx.cinterop`, `platform.*`, `android.*`, DOM).

Les seuls points spécifiques sont des `expect/actual` :

- shaping HarfBuzz derrière `HarfBuzzPlatformBinding` (kffi JVM/Android, cinterop
  iOS) ;
- verrous (`PortableLock`, `PortableConditionLock`, `currentThreadToken`) ;
- `FontCacheAllocationError` (`font:core`) ;
- `currentPortableCapabilityIdentity` (`conformance`) ;
- mesure et corpus de test (`bench`, `e2e`).

Aujourd'hui, aucune cible web n'existe et aucun document ne mentionne
`wasm`/`js`/navigateur.

## 2. Objectifs

1. Ajouter `js(IR)` et `wasmJs` à tous les modules KMP de la bibliothèque.
2. Atteindre la **parité de capacités** avec les autres cibles : sur web, la
   conformance déclare `UNICODE_ANALYSIS`, `SHAPING`, `END_TO_END_LAYOUT` et
   `GLYPH_REPRESENTATION_VARIANTS` **Présentes**.
3. Couvrir **toute la lib**, y compris `raster-cpu`, et fournir une **découverte
   de polices navigateur**.
4. Vérifier la parité **au même niveau** que JVM/iOS/Android : tests unitaires,
   conformance et e2e/goldens exécutés dans un runtime web et câblés au CI.
5. Publier les variantes web sur Maven Central.

## 3. Non-objectifs

- Benchmarks web (`bench`) : `kotlinx-benchmark` ne couvre pas `wasmJs`
  proprement. `bench` reste JVM/Android/iOS.
- Threads wasm / `SharedArrayBuffer` : le runtime est mono-thread.
- Découverte de polices sur les navigateurs hors Chromium.
- Publication npm (sauf demande ultérieure).
- Rendu GPU ou compositing navigateur : la lib ne rend pas de pixels ; le
  `raster-cpu` reste du calcul CPU pur.

## 4. Décisions de conception

| Sujet | Décision |
| --- | --- |
| Étendue | Toute la lib + découverte de polices navigateur (`raster-cpu` inclus) |
| Backend shaping web | Étendre **kffi-harfbuzz** (dépôt `org.graphiks`) avec des cibles `js`/`wasmJs`, même version épinglée 14.3.0 et même provenance |
| Polices | Octets fournis par l'app en voie principale ; **Local Font Access API** en option (Chromium, permission), absence déclarée sinon |
| Initialisation | `suspend fun initialize()` unique et idempotent ; facades **synchrones** ensuite |
| API publique | **Renommage neutre global**, sans alias `Jvm*` dépréciés (incubation, ruptures acceptées) |
| Vérification | Même autorité que JVM/iOS/Android, incluse au CI |
| Approche | Portage symétrique phasé, `webMain` partagé dès le départ |

## 5. Architecture

### 5.1 Cibles et source sets

- La convention `kmp-library` déclare en plus `js(IR)` et `wasmJs`. Les deux
  cibles se propagent à tous les modules KMP.
- Un **groupe de hiérarchie `web`** (via `applyDefaultHierarchyTemplate`) réunit
  `js` et `wasmJs`, avec un `webMain`/`webTest` partagé. Les `actual` web sont
  écrits **une seule fois**.
- `commonMain` reste totalement dépourvu de types DOM et de binding natif. Le DOM
  n'apparaît que dans `webMain` et `:kalligraphie:platform:browser`.

### 5.2 Modules

| Module | Aujourd'hui | Après |
| --- | --- | --- |
| `api`, `unicode`, `layout`, `font:core/sfnt/scaler/glyph`, `raster-cpu` | `commonMain` pur | compilent tels quels pour `js`/`wasmJs` ; `actual` web pour `font:core` |
| `shaping` | binding JVM/Android/iOS | + `HarfBuzzBindings.web.kt`, `PortableLock.web.kt` |
| `:kalligraphie` | facades `commonMain`, locks | + `actual` web des locks, `initialize()` |
| `conformance` | actual JVM/Android/iOS | + actual web |
| `e2e` | corpus iOS embarqué | + corpus web embarqué, scènes web |
| `:kalligraphie:platform:browser` | — | **nouveau**, cibles web seules, catalogue navigateur |
| `:kalligraphie:platform:*` | `jvmMain` uniquement | inchangés |
| `bench` | JVM/Android/iOS | inchangé (hors parité) |

### 5.3 `expect/actual` à fournir côté web

| Module | Déclaration | `actual` web |
| --- | --- | --- |
| `:kalligraphie` | `PortableLock` | verrou mono-thread (reentrant, sans contention) |
| `:kalligraphie` | `PortableConditionLock` | voir §7.2 ; `awaitUninterruptibly` ne doit être atteint par aucun chemin |
| `:kalligraphie` | `currentThreadToken` | jeton constant |
| `shaping` | `HarfBuzzPlatformBinding` | binding kffi web (§6) |
| `shaping` | `PortableLock` | verrou mono-thread |
| `font:core` | `FontCacheAllocationError` | `Error` portable |
| `conformance` | `currentPortableCapabilityIdentity` | déclaration web (§8.2) |
| `e2e` | `FixtureCorpus`, mesure | corpus base64 généré (§8.4) |

## 6. Backend shaping HarfBuzz

### 6.1 Dépendance amont kffi-harfbuzz

Le dépôt `org.graphiks` doit publier des artefacts `js` et `wasmJs` exposant la
**même surface** que les cibles existantes :

- `org.graphiks.kffi.harfbuzz.HarfBuzz.open()` et les types blob/face/font/
  buffer/feature déjà utilisés par `HarfBuzzBindings.jvm.kt` ;
- `hb_font_set_var_coords_normalized`, ligature carets et
  `hb_font_get_glyph_extents` ;
- `bindingIdentity` complet (`operatingSystem`, `architecture`, `artifactId`,
  `artifactSha256`, `upstreamSourceRevision`, `buildChainIdentity`).

Le module `.wasm` de HarfBuzz est embarqué et instancié **par kffi**, pas par
Kalligraphie. C'est la condition de préservation de la provenance et de la
version épinglée.

### 6.2 Amorçage asynchrone

Un unique point d'entrée portable et idempotent, `suspend fun initialize()` :

- **web** : attend l'instanciation du wasm kffi (et, en option, la permission
  polices), puis marque l'état initialisé.
- **JVM/Android/iOS** : `actual` no-op ; aucune rupture d'appel.

Après amorçage, `HarfBuzz.open()` est synchrone dans le navigateur. Donc
`HarfBuzzShapingBackend.open()` et **toutes les facades restent synchrones**,
identiques aux autres cibles. C'est ce qui rend la parité d'API possible malgré
`WebAssembly.instantiate` asynchrone.

### 6.3 Binding web

`HarfBuzzBindings.web.kt` mirroite `HarfBuzzBindings.jvm.kt` :

- mise à l'échelle en unités de dessin uniquement ; le facteur `unitsPerEm` du
  face est appliqué au font, jamais la taille de layout (pas de double-scaling) ;
- même ordre de possession `font → face → blob` et même libération en cas
  d'erreur ;
- même mapping des échecs vers `HarfBuzzBindingException` et les échecs
  portables `font.shaping-*` ;
- `supportsVariationLocation = true`.

## 7. Concurrence

### 7.1 Verrous

`PortableLock` web : implémentation mono-thread reentrant sans contention.
`currentThreadToken` web : constante.

### 7.2 Contrainte `PortableConditionLock`

Le contrat commun interdit la réentrance et suppose qu'`awaitUninterruptibly()`
libère le verrou puis attend qu'**un autre thread** signale. En runtime
mono-thread (Kotlin/JS, Kotlin/Wasm sans threads), aucun autre thread ne peut
signaler.

**Exigence :** aucun chemin des sessions incrémentales ne doit atteindre
`awaitUninterruptibly` sur web. Le spec de plan doit inclure une **trace
explicite des usages** de `withLock`/`awaitUninterruptibly` dans
`JvmEditableLineLayoutSession` / `JvmIncrementalParagraphLayoutSession` (renommées
au §9), et l'`actual` web ne doit **jamais** bloquer le thread principal. Si un
chemin atteignable subsiste, il est résolu par un échec portable explicite
plutôt qu'un blocage.

## 8. Polices et vérification

### 8.1 `:kalligraphie:platform:browser`

- `BrowserSystemFontCatalog` au-dessus de la **Local Font Access API**
  (`navigator.fonts.query()`), qui rend famille/style/**octets**
  (`Blob` → `ByteArray`). Chromium uniquement, sous permission, asynchrone.
- **Repli propre** : API absente ou permission refusée → résultat typé propre à
  `platform:browser` (et non `CAPABILITY_ABSENCE_DIAGNOSTIC_CODE`, qui ne
  concerne que les `PortableCapability`, cf. §8.2) ; la voie octets fournis par
  l'app (`Kalligraphie.embedded(...)`, `fetch`, `File`) fonctionne partout.
- Échecs typés : API absente, permission refusée, échec de requête.

**Frontière sync/async nette :** toute l'asynchronie (wasm, Blob) reste au bord.
`platform:browser` expose des chargements `suspend` qui rendent des **octets** ;
le cœur portable synchrone les consomme ensuite. La composition n'est jamais
colorée `suspend`.

### 8.2 Conformance

Ajout d'un `actual currentPortableCapabilityIdentity` web :

| Capacité | Disponible | `profileId` |
| --- | --- | --- |
| `UNICODE_ANALYSIS` | oui | `portable-unicode-16.0` |
| `SHAPING` | oui | `bundled-harfbuzz` |
| `END_TO_END_LAYOUT` | oui | `portable-paragraph` |
| `GLYPH_REPRESENTATION_VARIANTS` | oui | `portable-glyph` |

`docs/docs/conformance-matrix.md` (et `.fr.md`) gagnent les lignes `js` et
`wasmJs`. La présence/absence de la découverte de polices navigateur est une
capacité de `platform:browser`, **distincte** des `PortableCapability`.

### 8.3 Tests

- `jsNodeTest` / `wasmJsNodeTest` : décodage portable, UAX #14, BiDi, parsing
  SFNT, layout, **goldens raster-cpu** (pur Kotlin, déterministe, sans DOM).
- `jsBrowserTest` / `wasmJsBrowserTest` (Chrome headless) : `Blob`/`File`/
  `fetch`, Local Font Access, catalogue navigateur.

### 8.4 e2e et goldens

Le harnais lit les octets via un `FixtureCorpus` injecté. Le web n'a pas de
classpath de ressources : le corpus est **embarqué en Kotlin généré (base64)**
au build, en `webMain`, selon la technique déjà employée par `shaping` et `e2e`
iOS. Les scènes sont rejouées en **Node** (calcul pur) et en **navigateur
headless** (scènes DOM).

### 8.5 CI

Nouveaux jobs dans `.github/workflows/font-tests.yml` et
`golden-portability.yml` : `jsNodeTest`, `wasmJsNodeTest`, tests navigateur,
`conformance` web, `e2e` web. Contraintes : Node récent avec drapeaux wasm-gc,
Chrome headless.

### 8.6 Tolérance numérique

Assertions **exactes**. Le shaping web doit reproduire l'oracle HarfBuzz 14.3.0
épinglé au bit près. Une tolérance n'apparaîtra que si une géométrie réellement
flottante entre en jeu, selon la politique actuelle de la matrice.

## 9. API publique — renommage neutre

Renommage sec (aucun alias `Jvm*` conservé) :

| Avant | Après |
| --- | --- |
| `JvmEditableParagraphFacade` (+ `...Request`) | `EditableParagraphFacade` |
| `JvmEditableLineFacade` (+ `...Request`) | `EditableLineFacade` |
| `JvmFlowCompositionFacade` (+ `...Request`) | `FlowCompositionFacade` |
| `JvmIncrementalParagraphLayoutSession` (+ `...Request`) | `IncrementalParagraphLayoutSession` |
| `JvmEditableLineLayoutSession` | `EditableLineLayoutSession` |

- Mise à jour des docs FR/EN, du Dokka, des tests, des benchmarks et des
  scènes e2e qui référencent les noms actuels.
- `suspend fun initialize()` exposé en `expect/actual` : réel sur web, no-op
  ailleurs (pour ne pas imposer un appel inutile au JVM/native).

### Flux de données

`app → initialize() (une fois) → fournit texte/typographie/polices (embedded,
fetch ou BrowserSystemFontCatalog) → facade synchrone → pipeline portable
unicode → shaping → layout → raster → géométrie/assets`.

Identique aux autres cibles, à la frontière asynchrone près.

## 10. Séquencement

- **Phase 0 — Renommage neutre.** Facades/requests/sessions renommées, docs,
  Dokka et tests à jour. Vérifiable seul par `./gradlew check`.
- **Phase 1 — Socle web.** `js`+`wasmJs` dans la convention, groupe `webMain`,
  compilation du cœur portable, `actual` triviaux (locks, `font/core`,
  conformance). Sans backend : `SHAPING` déclaré **absent** avec diagnostic —
  comportement identique au host Android aujourd'hui.
- **Phase 2 — Shaping.** kffi-harfbuzz web + `HarfBuzzBindings.web.kt` +
  `initialize()` ; `SHAPING` passe **Présent**.
- **Phase 3 — Polices.** `platform:browser` + voie octets fournis par l'app.
- **Phase 4 — Parité vérifiée.** e2e/goldens + CI + publication des variantes
  web.

Les phases sont conçues pour être exécutables et relisibles séparément : le plan
d'implémentation peut être découpé par phase (la Phase 0 notamment peut être
livrée indépendamment des cibles web).

## 11. Risques et inconnues

1. **kffi-harfbuzz web** est le chemin critique : disponibilité de l'API
   synchrone après init, taille du `.wasm`, stratégie de chargement.
2. **Concurrence mono-thread** : prouver qu'aucun chemin des sessions n'exige un
   vrai blocage inter-thread (§7.2).
3. **Maturité Kotlin/Wasm** (wasm-gc) et outillage CI Node/Chrome.
4. **Local Font Access** : Chromium-only et sous permission → parité *polices
   système* partielle ; limite documentée, sans impact sur le cœur.
5. **Dérive bit-à-bit** des goldens si la couche kffi web arrondit différemment
   → décision de tolérance le moment venu.
6. **Taille mémoire** : polices embarquées en base64 dans les klibs web et blob
   wasm HarfBuzz.

## 12. Critères de succès

1. `:kalligraphie` compile et publie pour `js` et `wasmJs`.
2. La conformance web déclare les 4 capacités **Présentes**.
3. e2e + goldens web passent en CI au même niveau que JVM/iOS/Android.
4. Un consommateur peut : `initialize()` → fournir des polices (app ou
   navigateur) → composer/layout/raster, sur `js` et `wasmJs`.
5. Renommage neutre appliqué partout, docs à jour.

## 13. Questions ouvertes (à trancher pendant le plan)

- Nom exact de l'objet d'amorçage (`Kalligraphie.initialize()` vs objet dédié).
- Stratégie de chargement du `.wasm` kffi (embarqué base64 vs `fetch`).
- Répartition précise des suites entre Node et navigateur headless.
- Emplacement des jobs CI web et budget temps.
