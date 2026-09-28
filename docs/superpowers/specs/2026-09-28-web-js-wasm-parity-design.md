# Conception — Élargissement de Kalligraphie au web `js` + `wasmJs` en parité

- **Statut :** proposé (révisé après revue `very-high-review`)
- **Date :** 2026-09-28
- **Portée :** ajout des cibles web `js(IR)` et `wasmJs` à la bibliothèque
  Kalligraphie, avec la même surface de capacités portables que JVM, Android et
  iOS, et renommage neutre de l'API publique.
- **Décideurs :** mainteneurs Kalligraphie.

## 1. Contexte

Kalligraphie est une bibliothèque de typographie Kotlin Multiplatform (KMP). Les
cibles actuelles sont `jvm`, `android`, `iosArm64` et `iosSimulatorArm64`
(`buildSrc/src/main/kotlin/ygdrasil/conventions/kmp-library.gradle.kts`).

Le cœur portable vit en `commonMain` : contrats (`api`), décodage et analyse
Unicode 16.0 (`unicode`), lecture SFNT / scaler / glyphes
(`font:core|sfnt|scaler|glyph`), composition incrémentale (`layout`) et
rastérisation CPU (`raster-cpu`). `commonMain` n'importe **ni** `java.*`, **ni**
`kotlinx.cinterop`, **ni** type DOM — mais « sans import non portable » ne suffit
pas à garantir la compilation web. Deux dépendances non triviales s'ajoutent :

1. **Compression Okio indisponible sur js/wasmJs.** `font/sfnt` `commonMain`
   utilise `okio.Inflater`/`InflaterSource`
   (`font/sfnt/src/commonMain/.../PngDecoder.kt:5-6,146`) et `okio.GzipSource`
   (`font/sfnt/src/commonMain/.../SvgDocumentDecoder.kt:4,40`). Or le metadata
   `com.squareup.okio:okio:3.18.1` place ces API dans `zlibMain`, présent dans
   `jvmApiElements` et `iosArm64ApiElements` mais **absent** de
   `jsApiElements` et `wasmJsApiElements`. Le cœur portable ne compile donc
   **pas** tel quel pour le web.
2. **Backend HarfBuzz.** Le shaping passe par `expect fun
   openHarfBuzzPlatformBinding()` (`shaping/src/commonMain/.../HarfBuzzPlatformBinding.kt:150`),
   implémenté en `jvmMain`/`androidMain`/`iosMain` au-dessus de
   `org.graphiks:kffi-harfbuzz`. Aucune cible web n'existe en amont.

Inventaire exact des déclarations `expect` (11 au total) :

| Module | Déclarations |
| --- | --- |
| `:kalligraphie` | `PortableLock`, `PortableConditionLock`, `currentThreadToken` |
| `shaping` | `openHarfBuzzPlatformBinding`, `PortableLock` |
| `font:core` | `FontCacheAllocationError` |
| `conformance` | `currentPortableCapabilityIdentity` |
| `bench` | `measurementInstruments`, `measurementIdentity`, `allocationFigures`, `ThreadAllocationProbe` (hors périmètre web) |

Il n'existe **aucun** `expect` dans `e2e` : `FixtureCorpus` y est une interface
de test (`e2e/src/sharedTest/.../fixture/FixtureCorpus.kt:14`).

## 2. Objectifs

1. Ajouter `js(IR)` et `wasmJs` aux modules KMP participants (voir §5.1).
2. Atteindre la **parité de capacités** : `UNICODE_ANALYSIS`, `SHAPING`,
   `END_TO_END_LAYOUT` et `GLYPH_REPRESENTATION_VARIANTS` **Présentes**.
3. Couvrir **toute la lib** (y compris `raster-cpu`) et fournir une **découverte
   de polices navigateur**.
4. Fournir une **décompression synchrone** DEFLATE/zlib/gzip pour les décodeurs
   PNG et SVG-in-OT sur le web.
5. Établir tôt une **sonde de parité numérique** inter-cibles (entiers HarfBuzz
   exacts ; géométrie flottante normalisée).
6. Vérifier au **même niveau** que JVM/iOS/Android (tests, conformance, e2e) et
   publier les variantes web.

## 3. Non-objectifs

- Benchmarks web : `bench` **n'est pas enregistré** pour les cibles web
  (`kotlinx-benchmark` ne couvre pas `wasmJs`).
- Threads wasm / `SharedArrayBuffer` : runtime mono-thread.
- Découverte de polices hors Chromium.
- Publication npm (sauf demande ultérieure).
- Rendu GPU ou compositing navigateur.

## 4. Décisions de conception

| Sujet | Décision |
| --- | --- |
| Étendue | Toute la lib + découverte de polices navigateur (`raster-cpu` inclus) |
| Enregistrement des cibles | **Opt-in par module**, pas via la convention de base (§5.1) |
| Décompression | Décodeur DEFLATE/zlib/gzip **synchrone et borné** derrière les décodeurs PNG/SVG ; Okio conservé, `kotlinx-io` écarté (§5.4) |
| Backend shaping web | Étendre **kffi-harfbuzz** avec des cibles `js`/`wasmJs`, même version épinglée 14.3.0 et même provenance |
| Polices | Octets fournis par l'app en voie principale ; `window.queryLocalFonts()` en option, **opération séparée et déclenchée par l'utilisateur** (§8.1) |
| Initialisation | `suspend fun initialize()` unique et idempotent pour le **wasm seul** ; facades **synchrones** ensuite |
| API publique | **Renommage neutre global**, sans alias `Jvm*` dépréciés |
| Parité numérique | Entiers HarfBuzz exacts ; géométrie layout/raster normalisée après sonde (§8.6) |
| Vérification | Même autorité que JVM/iOS/Android, incluse au CI |
| Approche | Portage symétrique phasé, `webMain`/`webTest` partagés |

## 5. Architecture

### 5.1 Enregistrement opt-in des cibles web

La convention `kmp-library` est appliquée par les modules **cœur et
vérification**, dont `bench` (`bench/build.gradle.kts:19`) et `e2e` ; les
adaptateurs `platform:*` appliquent KMP directement. Ajouter `js`/`wasmJs` dans
cette convention contaminerait donc `bench` et ses 4 `expect` non voulus.

**Décision :** ne pas modifier les cibles de la convention de base. Introduire un
mécanisme opt-in (nouvelle convention dédiée `kalligraphie-kmp-web`, appliquée
en plus, ou propriété Gradle) qui enregistre `js(IR)` + `wasmJs`. Modules
**participants** : `api`, `unicode`, `shaping`, `layout`,
`font:core|sfnt|scaler|glyph`, `raster-cpu`, `:kalligraphie`, `conformance`,
`e2e`, plus le nouveau `:kalligraphie:platform:browser`. Modules **exclus** :
`bench`, `platform:apple|linux|windows` (jvmMain), `platform:android`
(Android-only), `platform:ios` (natif only).

### 5.2 Source sets web

Un groupe de hiérarchie `web` réunit `js` + `wasmJs`, avec `webMain`/`webTest`
partagés ; les `actual` web sont écrits **une seule fois**. Le template de
hiérarchie par défaut de KGP 2.4.10 **définit déjà** ce groupe
(`group("web") { withJs(); withWasmJs() }`) : on s'appuie dessus, une simple
compilation le confirme — ce n'est pas une porte de conception. `commonMain`
reste sans type DOM.

### 5.3 Modules

| Module | Aujourd'hui | Après |
| --- | --- | --- |
| `api`, `unicode`, `layout`, `font:sfnt/scaler/glyph`, `raster-cpu` | `commonMain` pur | compilent pour `js`/`wasmJs` (participent) |
| `font:core` | `commonMain` + `jvmMain`/`nativeMain`/`androidMain` | + `actual` web |
| `shaping` | binding JVM/Android/iOS | + `HarfBuzzBindings.web.kt`, `PortableLock.web.kt` |
| `:kalligraphie` | facades `commonMain`, locks | + `actual` web des locks, `initialize()` |
| `conformance` | actual JVM/Android/iOS | + actual web |
| `e2e` | corpus iOS embarqué | + corpus web (`webTest`), scènes web |
| `:kalligraphie:platform:browser` | — | **nouveau**, cibles web seules |
| `platform:apple|linux|windows`, `platform:android`, `platform:ios` | jvmMain / Android-only / natif only | inchangés |
| `bench` | JVM/Android/iOS | inchangé (non enregistré web) |

### 5.4 Décompression synchrone

Introduire un seam interne de décompression derrière les deux points d'entrée de
`PngDecoder` et `SvgDocumentDecoder` :

- **JVM/native/Android** : continuent d'utiliser Okio (`InflaterSource`,
  `GzipSource`) — aucun changement de comportement.
- **web** : implémentation **pure Kotlin synchrone** de DEFLATE (RFC 1951) +
  zlib (RFC 1950) + gzip (RFC 1952), **partagée entre `js` et `wasmJs`** ; elle
  ne remplace pas Okio sur les cibles existantes.

**`kotlinx-io` a été évalué et écarté** : il ne fournit **aucune** API de
compression (son API publique se limite à `Buffer`, `Source`/`Sink`,
`ByteString`, `FileSystem`), et il est en Alpha. Le migrer ne résoudrait donc pas
le bloqueur et introduirait du churn sur une fondation instable.

Contraintes obligatoires : vérification des sommes de contrôle (Adler-32,
CRC-32), **application incrémentale** des limites (jamais « tout décompresser puis
vérifier »), **bornes de taille** préservées, et échecs typés inchangés
(`font.png.invalid-deflate`, `font.svg.invalid-gzip`). `DecompressionStream`
(navigateur) est **banni** ici car asynchrone. Le seam peut être un
`expect/actual` interne ou une implémentation partagée par les deux cibles web ;
le choix est reporté au plan, la sémantique ne l'est pas.

**Tests d'acceptation obligatoires** (cas différentiels contre les décodeurs
actuels) : flux tronqués, sommes de contrôle invalides, données résiduelles,
**membres gzip multiples**, franchissements de limites, et respect des règles
gzip d'Okio 3.18.1 (validation du trailer, rejet du membre suivant).

### 5.5 Politique d'allocation web (`font:core`)

`FontCacheAllocationError` est un `typealias` vers `OutOfMemoryError` sur
jvm/native/android, et `FontMaterializationCache` ne l'attrape que pour
abandonner proprement l'ownership optionnel du cache
(`FontMaterializationCache.kt:35-44`). Le mapping web doit préserver cette
sémantique : **ne pas** aliaser `Error` générique (qui masquerait des
défaillances sans rapport) et **ne pas** promettre de récupération après un OOM
fatal du moteur JS ou un trap Wasm. L'`actual` web et la portée exacte de la
récupération sont fixés au plan ; **ce n'est pas un `actual` trivial**.

## 6. Backend shaping HarfBuzz

### 6.1 Contrat amont (porte de faisabilité)

**Porte préalable à la finalisation du plan web** (la Phase 0 « renommage » en
est exemptée) : le dépôt `org.graphiks` doit publier des artefacts `js`/`wasmJs`
exposant la même surface `org.graphiks.kffi.harfbuzz.*` que JVM/Android/iOS
(blob/face/font/buffer/feature, `hb_font_set_var_coords_normalized`, ligature
carets, `hb_font_get_glyph_extents`) et `bindingIdentity` complet. Un
**prototype amont doit établir un contrat sélectionné**, pas seulement montrer
qu'un comportement existe :

- **contrat d'initialisation** : initialisation partagée (appels concurrents),
  comportement observable avant/pendant l'init, politique de retry, et
  **propriété de l'annulation** ;
- `open`, allocation, `shape` et release **synchrones** en **Node et
  navigateur**, pour **les deux** cibles Kotlin ;
- copie correcte et **rafraîchissement des vues mémoire** après croissance de la
  mémoire linéaire HarfBuzz ;
- résolution de l'asset embarqué et provenance réelle.

Rappel d'interop Kotlin/Wasm : les `ByteArray` managés ne se passent pas
directement à une API C/Wasm arbitraire ; la mémoire linéaire est un domaine de
possession distinct. C'est cette couche de pont que le prototype doit valider.

### 6.2 Amorçage asynchrone

`suspend fun initialize()` unique et idempotent, **limité au moteur wasm** :

- **web** : attend l'instanciation wasm kffi ; partage l'init en vol, échec/retry
  typés ; ne demande **aucune** permission.
- **JVM/Android/iOS** : `actual` no-op.

Après amorçage, `HarfBuzz.open()` est synchrone ; `HarfBuzzShapingBackend.open()`
et **toutes les facades restent synchrones**. `initialize()` doit être
accessible sans forcer un consommateur de `:shaping` seul à dépendre de la
facade. La permission polices est **hors** de `initialize()` (§8.1).

### 6.3 Binding web

`HarfBuzzBindings.web.kt` mirroite `HarfBuzzBindings.jvm.kt` : échelle en unités
de dessin uniquement (jamais la taille de layout → pas de double-scaling), même
ordre de possession `font → face → blob`, même mapping des échecs,
`supportsVariationLocation = true`, et rafraîchissement des vues après croissance
mémoire.

## 7. Concurrence

`PortableLock` web : verrou mono-thread reentrant. `currentThreadToken` web :
constante.

**Constat vérifié :** les chemins ordinaires des sessions n'exigent **pas** de
blocage inter-thread — les seules attentes de condition sont dans
`JvmEditableLineLayoutSession.kt:121,126`, la fermeture de layout sur le même
thread est rejetée avant (`:115-117`), et la session incrémentale utilise un
verrou réentrant, pas une condition
(`JvmIncrementalParagraphLayoutSession.kt:129-141,178-193`).

**Exigence :** préserver cette structure sur web. `awaitUninterruptibly` ne doit
être atteint par aucun chemin ; l'`actual` web ne bloque jamais le thread
principal. Des tests de réentrance et de non-atteignabilité l'établissent. Si un
chemin atteignable subsiste, il produit un échec portable explicite, pas un
blocage.

## 8. Polices et vérification

### 8.1 `:kalligraphie:platform:browser`

- Découverte système via **`window.queryLocalFonts()`** → `FontData[]` ; octets
  via `await fontData.blob()` puis `await blob.arrayBuffer()`.
- Exigences navigateur : **secure context**, permission, **Permissions Policy**
  autorisée si dans une iframe, et **activation utilisateur** — une requête au
  démarrage (sans activation) peut lever `SecurityError`. Attendre un
  téléchargement wasm lent avant de demander la permission peut épuiser
  l'activation transitoire.
- **Décision :** la découverte de polices est une **opération `suspend` séparée,
  déclenchée explicitement par l'utilisateur**, à invoquer avant toute autre
  suspension. Elle est **retirée** de `initialize()`.
- Repli propre : API absente / permission refusée / policy bloquée → résultat
  typé propre à `platform:browser` (distinct de
  `CAPABILITY_ABSENCE_DIAGNOSTIC_CODE`, qui ne vise que les `PortableCapability`).
  La voie octets fournis par l'app (`Kalligraphie.embedded(...)`, `fetch`,
  `File`) fonctionne partout.
- `document.fonts` n'est **pas** nécessaire au shaping/raster par octets.

**Frontière sync/async :** toute l'asynchronie (wasm, Blob) reste au bord ; le
cœur synchrone consomme des octets. La composition n'est jamais `suspend`.

### 8.2 Conformance

`actual currentPortableCapabilityIdentity` web :

| Capacité | Disponible | `profileId` |
| --- | --- | --- |
| `UNICODE_ANALYSIS` | oui | `portable-unicode-16.0` |
| `SHAPING` | oui | `bundled-harfbuzz` |
| `END_TO_END_LAYOUT` | oui | `portable-paragraph` |
| `GLYPH_REPRESENTATION_VARIANTS` | oui | `portable-glyph` |

`docs/docs/conformance-matrix.md` (+ `.fr.md`) gagnent les lignes `js`/`wasmJs`.
La découverte de polices navigateur est une capacité de `platform:browser`,
distincte des `PortableCapability`.

### 8.3 Tests — câblage explicite et bootstrap asynchrone

Les suites ne s'exécutent pas par simple ajout de cible : elles sont câblées à
des compilations précises (`e2e/build.gradle.kts:90-104,143-150,190-200` ;
`unicode/build.gradle.kts:103-142` ; dépendances de test `layout` en `jvmTest`
seulement, `layout/build.gradle.kts:15-25`). Le plan doit **énumérer** pour
chaque tâche web : répertoires source, entrées de corpus, entrées de registre et
nombre de tests.

- `jsNodeTest` / `wasmJsNodeTest` : décodage portable, UAX #14, BiDi, parsing
  SFNT, layout, **goldens raster-cpu**.
- `jsBrowserTest` / `wasmJsBrowserTest` (Chrome headless) : `Blob`/`File`/
  `fetch`, `queryLocalFonts`, catalogue navigateur.

**Bootstrap asynchrone :** un `@BeforeTest` synchrone ne peut pas `await`
`initialize()`. Le harnais doit prévoir un mécanisme d'attente supporté par le
runner (ex. `runTest`/coroutine de test) avant la première composition. Au moins
**un e2e en navigateur** est requis : Node ne valide pas le chargement navigateur.

### 8.4 Corpus e2e

Le corpus est **généré dans `webTest`** (pas `webMain`, pour ne pas embarquer des
fixtures dans les artefacts publiés), en Kotlin base64, sur le modèle des tâches
iOS existantes. Les scènes composées/portables existantes sont réutilisées autant
que possible plutôt que d'introduire une scission DOM arbitraire.

### 8.5 CI

- Le job racine `check` (`.github/workflows/font-tests.yml:30-42`) exécute déjà
  `check`, et les tâches de test enregistrées y sont rattachées : les nouvelles
  tâches web impactent **ce job**, pas seulement des jobs neufs.
- Configurer explicitement `nodejs()` / `browser()`, ChromeHeadless, et **épingler**
  les versions de runtime ; ne pas ajouter d'anciens drapeaux
  `--experimental-wasm-gc` sans vérifier la version de Node (des versions
  récentes les rejettent).
- Automatiser la permission polices et installer une **police de test** ;
  séparer les tests de refus de permission des tests de succès.
- Épingler les polices installées pour rendre les scènes déterministes.

### 8.6 Police de parité numérique

Séparer deux niveaux :

1. **Sortie entière HarfBuzz** (glyph ids, advances, offsets, carets, extents) :
   parité **exacte** avec l'oracle 14.3.0 épinglé.
2. **Géométrie layout/raster Kotlin** : `Float` en Kotlin/JS n'est pas du
   float32 JVM/Wasm ; `DesignToLayoutScale.convert()` calcule en `Double` puis
   `toFloat()` (`HarfBuzzShapingBackend.kt:591-610`), et les scènes composées
   arrondissent au pixel avant comparaison d'empreintes exactes
   (`ComposedLineScenes.kt:136-140`, `GoldenVerifier.kt:47-55`).

Une **sonde précoce inter-cibles** couvre : échelles fractionnaires, accumulation
d'advances, seuils de retour à la ligne, bords de pixel. Une normalisation
numérique délibérée est introduite là où c'est nécessaire, puis la sonde **doit
passer** : une divergence simplement documentée ne vaut pas succès. On ne
suppose pas que « pur Kotlin ⇒ déterministe entre cibles ».

## 9. API publique — renommage neutre

Renommage sec (aucun alias `Jvm*` conservé) :

| Avant | Après |
| --- | --- |
| `JvmEditableParagraphFacade` (+ `...Request`) | `EditableParagraphFacade` |
| `JvmEditableLineFacade` (+ `...Request`) | `EditableLineFacade` |
| `JvmFlowCompositionFacade` (+ `...Request`) | `FlowCompositionFacade` |
| `JvmIncrementalParagraphLayoutSession` (+ `...Request`) | `IncrementalParagraphLayoutSession` |
| `JvmEditableLineLayoutSession` | `EditableLineLayoutSession` |

Mise à jour docs FR/EN, Dokka, tests, benchmarks et scènes e2e. `suspend fun
initialize()` reste `expect/actual` (réel sur web, no-op ailleurs).

**Flux :** `app → initialize() (une fois) → polices (embedded, fetch, ou
découverte utilisateur) → facade synchrone → pipeline portable unicode → shaping
→ layout → raster → géométrie/assets`.

## 10. Séquencement

- **Phase 0 — Renommage neutre.** Facades/requests/sessions, docs, Dokka, tests.
  Vérifiable seul par `./gradlew check`.
- **Phase 1 — Socle web.** Enregistrement opt-in, `webMain`/`webTest`,
  compilation du cœur portable, **seam de décompression synchrone** (§5.4),
  `actual` web (locks, `font/core` selon §5.5), conformance (sans backend :
  `SHAPING` absent + diagnostic, comme le host Android aujourd'hui), **sonde
  numérique** (§8.6).
- **Phase 2 — Shaping.** Contrat amont kffi + prototype (§6.1), puis
  `HarfBuzzBindings.web.kt` + `initialize()` ; `SHAPING` passe Présent.
- **Phase 3 — Polices.** `platform:browser` + découverte utilisateur.
- **Phase 4 — Parité vérifiée.** e2e/goldens + CI + publication.

Les phases sont conçues pour être exécutables séparément ; la Phase 0 peut être
livrée indépendamment du web. La **porte du §6.1 conditionne la finalisation du
plan web** (Phase 0 exceptée) : on ne fige pas le plan des phases 1–4 avant que
le contrat d'init amont soit établi.

## 11. Risques et inconnues

1. **Compression Okio (bloqueur confirmé)** : nécessite un DEFLATE/zlib/gzip
   synchrone correct et borné ; chemin critique au même titre que HarfBuzz.
2. **Contrat d'init kffi web** : non prouvé ; exige un prototype amont (§6.1).
3. **Parité numérique** : risque réel de divergence float sur Kotlin/JS ; sonde
   précoce et normalisation.
4. **Activation/permission polices** : découverte séparée et déclenchée ;
   Chromium-only → parité *polices système* partielle, documentée.
5. **Maturité Kotlin/Wasm** et outillage CI (Node/Chrome, versions épinglées).
6. **Croissance mémoire** HarfBuzz → vues périmées si non rafraîchies.
7. **Publication/résolution** : filtres de dépôt kffi à étendre aux nouvelles
   coordonnées ; suffixe d'artefact `-wasm-js` ; ajouter `platform:browser` à la
   doc agrégée.

## 12. Critères de succès

1. `:kalligraphie` compile et publie pour `js` et `wasmJs`.
2. Décompression PNG/SVG synchrone opérationnelle sur web (checksums et bornes).
3. La conformance web déclare les 4 capacités **Présentes**.
4. e2e + goldens web passent en CI au même niveau que JVM/iOS/Android, dont au
   moins un e2e navigateur.
5. Sonde numérique inter-cibles **verte après normalisation éventuelle** (une
   divergence seulement documentée ne suffit pas).
6. Un consommateur peut : `initialize()` → fournir des polices → composer/layout/
   raster, sur `js` et `wasmJs`, **prouvé par un smoke test externe** qui résout
   les artefacts publiés et charge l'asset HarfBuzz dans les deux environnements
   (Node et navigateur).
7. Renommage neutre appliqué partout, docs à jour.

## 13. Questions ouvertes (à trancher au plan)

- Nom exact de l'objet d'amorçage (`Kalligraphie.initialize()` vs objet dédié).
- Mécanisme d'enregistrement opt-in (nouvelle convention vs propriété Gradle).
- Seam de décompression : `expect/actual` web vs implémentation portable unique.
- Stratégie de chargement du `.wasm` kffi (embarqué base64 vs `fetch`).
- Répartition précise Node vs navigateur headless ; emplacement et budget CI.
- Forme du smoke test externe (projet consommateur jetable, ou test d'intégration
  de résolution) et environnements couverts.
