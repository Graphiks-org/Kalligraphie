# Catalogue d'attentes e2e des polices — Spec de design

- **Date** : 2026-09-23
- **Branche de travail** : `feat/e2e-catalog`
- **Statut** : validé en brainstorming, en attente de plan d'implémentation
- **Modules touchés** : `:kalligraphie:e2e` (principal), `test-fixtures/fonts/`, `scripts/fonts/` (nouveau), `docs/docs/`, CI GitHub Actions

## 1. Objectif et intention

Faire évoluer `:kalligraphie:e2e` pour qu'il devienne le **catalogue de référence** de tout ce
que Kalligraphie doit savoir consommer comme technologies de polices :

- **exhaustif** : toute technologie de police (table, format, version, écriture) a son entrée ;
- **adossé à de vraies polices** du monde réel, épinglées par URL + SHA-256 + licence, committées
  **complètes** (pas de sous-échantillonnage) ;
- **couvrant aussi le non-supporté au jour J** : le catalogue est la carte des attentes, pas
  seulement des réussites ;
- **vérifié mécaniquement** : aucun trou silencieux, aucune dérive doc/code. Les gardes qui portent
  un concept métier (cliquet du catalogue, empreintes golden, fraîcheur de la matrice et des
  revendications) tournent en CI ; la comptabilité du corpus et le lint d'exhaustivité sont des
  vérifications locales obligatoires (voir §4).

Le public est double : les mainteneurs (progression du support, cliquet anti-régression) et tout
contributeur qui veut savoir « où en est Kalligraphie sur la technologie X, et quelle police la
représente ».

### Décisions actées en brainstorming

1. **Contrat CI** : statuts + cliquet (`Supported` / `ExpectedRejection` / `NotYet` /
   `OutOfScope`), pas de « tout testé dès le jour J », pas de simple matrice documentaire.
2. **Acquisition** : script d'acquisition automatisé + **polices complètes committées** (le
   growth du dépôt — ordre de 80–150 Mo à terme — est une contrainte assumée).
3. **Périmètre** : axes **technologies** (conteneurs, outlines, métriques, variation, couleur,
   bitmap, robustesse) **et écritures** (nouvelles polices réelles pour hébreu, thaï, hangul,
   tamil, bengali, nastaliq…). Les « polices du quotidien » génériques (Inter, Roboto, Segoe…)
   ne sont pas un axe dédié.
4. **Architecture** : modèle typé Kotlin dans `commonMain` de `:kalligraphie:e2e`
   (source unique de vérité), scènes et sondes **générées** depuis les entrées, lint
   d'exhaustivité fontTools en mode **vérification** (jamais génération), manifeste JSON
   d'acquisition côté outillage Python relié par clé de corpus.

### Hypothèses de périmètre (non-goal)

- Le catalogue visé est celui du **module e2e de test**, pas l'API publique
  `FontCatalogSnapshot`.
- L'**exécution** reste **JVM-only** : le modèle compile partout (comme le reste du module),
  mais aucune exécution iOS (base64, contrainte B3) ni device Android n'est ajoutée. Noté
  comme suite possible.
- Aucun sous-échantillonnage des nouvelles polices (la fixture NotoSansJP sous-ensembleée
  existante reste telle quelle, migée telle quelle).
- Les générateurs de fixtures synthétiques existants sont conservés et pilotés par le
  manifeste d'acquisition (`synthetic: true`).

## 2. Modèle du catalogue (`commonMain` de `:kalligraphie:e2e`)

```kotlin
enum class CatalogAxis { CONTAINER, OUTLINE, METRICS, VARIATION, COLOR, BITMAP, SCRIPT, ROBUSTNESS }

sealed interface CatalogStatus {
    /** Scène golden active, vérifiée octet par octet. */
    data class Supported(val sinceCommit: String) : CatalogStatus
    /** La police doit être refusée avec exactement ce diagnostic typé. */
    data class ExpectedRejection(val code: String) : CatalogStatus
    /** Pas encore supporté : le comportement ACTUEL est épinglé ; issue de suivi référencée. */
    data class NotYet(
        val trackingIssue: String,
        val currentBehavior: PinnedBehavior,
        /** Note honnêteté : "aucune police réelle connue" le cas échéant. */
        val noRealFontKnown: Boolean = false,
    ) : CatalogStatus
    /** Hors périmètre assumé ; rationale obligatoire. */
    data class OutOfScope(val rationale: String) : CatalogStatus
}

data class CatalogEntry(
    val id: String,          // "variation.varc", "color.colr-v1-variable", "script.thai"…
    val axis: CatalogAxis,
    val technology: String,  // description humaine courte (bilingue via la doc générée)
    val font: CorpusKey,     // clé du manifeste d'acquisition, ex. "noto-sans-thai"
    val status: CatalogStatus,
    val tags: Set<String> = emptySet(),
)
```

- Catalogue agrégé : **`ExpectationCatalog`** (nom choisi pour éviter la collision avec
  l'API publique `FontCatalogSnapshot`).
- Déclaration **un fichier Kotlin par axe** dans le module e2e : `ContainerCatalog.kt`,
  `OutlineCatalog.kt`, `MetricsCatalog.kt`, `VariationCatalog.kt`, `ColorCatalog.kt`,
  `BitmapCatalog.kt`, `ScriptCatalog.kt`, `RobustnessCatalog.kt` — chacun expose sa
  `List<CatalogEntry>` ; `ExpectationCatalog` les agrège.
- `PinnedBehavior` décrit le comportement **actuel** observable (ex. : « le décodage `fvar`
  réussit, `fontMetrics()` ne tient pas compte de `STAT`, `STAT` renvoie `Success(null)` »).
  Assez précis pour être asserté par une sonde, assez stable pour ne pas casser à chaque
  refactoring interne : on épingle des comportements de la frontière publique, pas des détails
  d'implémentation.

### Sémantique exacte des statuts

| Statut | Signifie | Artefact CI généré |
|---|---|---|
| `Supported(sinceCommit)` | supporté et vérifié golden | scène golden auto-dimensionnée + liaison de rendu |
| `ExpectedRejection(code)` | refus typé volontaire et permanent | sonde : le pipeline complet rejette avec ce code |
| `NotYet(issue, pinned)` | non supporté aujourd'hui, suivi actif | sonde : la police se charge, le comportement épinglé est asserté |
| `OutOfScope(rationale)` | décision de ne pas supporter, documentée | rien d'exécuté ; rationale non vide vérifiée |

Le cliquet : le jour où un support atterrit, la sonde `NotYet` **échoue** (le comportement
épingné a changé) — l'implémenteur doit flipper le statut vers `Supported` (ou
`ExpectedRejection` si le verdict est un rejet) et fournir la scène. Une fois `Supported`,
une entrée ne peut régresser que par décision explicite consignée dans le CHANGELOG.

### Règle d'honnêteté

Certaines tables sont si rares qu'aucune police réelle publique ne les porte (VVAR réel, VARC
réel, COLR v1 variable réel, `cvar`, `avar` v2…). L'entrée existe quand même, porte
`noRealFontKnown = true` (pour `NotYet`) ou un tag équivalent, et la couverture reste
synthétique. Le catalogue ne prétend jamais avoir une police réelle qu'il n'a pas ;
réciproquement, le lint d'exhaustivité (§4) empêche d'ignorer une table réelle présente.

## 3. Génération des scènes et sondes

### Liaisons de rendu

Chaque entrée `Supported` enregistre une liaison de scène par id (le lambda de rendu reste
écrit à la main — c'est du code irréductible) :

```kotlin
sceneBinder("bitmap.cbdt-png") { font, scene -> … }
```

Le registre des liaisons vit côté `jvmTest` (le rendu n'existe que là).

### Auto-dimensionnement

Les `GoldenScene` sont produites mécaniquement : **boîte d'encre mesurée + padding fixe**,
dimensions dérivées, plus jamais épinglées à la main. La mesure repose sur le rastériseur CPU
déterministe existant (arithmétique entière) : mêmes polices, mêmes dimensions, empreintes
stables. Le padding et la règle d'arrondi sont des constantes du générateur.

### Pipelines générés

| Statut | Génération | Échec CI si |
|---|---|---|
| `Supported` | scène golden intégrée au catalogue de scènes existant (manifeste SHA-256, comparaison exacte) | empreinte diverge / scène manquante (`MissingInManifest`, fail-closed existant) |
| `ExpectedRejection` | sonde de rejet typé sur le pipeline complet | le rejet disparaît ou change de code |
| `NotYet` | sonde de présence + assertion du `PinnedBehavior` | le comportement épinglé change (→ flip de statut requis) |
| `OutOfScope` | — | rationale vide |

### Test de cliquet : `ExpectationCatalogRatchetTest`

Paramétré sur l'agrégat `ExpectationCatalog`, il échoue si :

1. un `id` est dupliqué ;
2. une entrée exécutable (`Supported`/`ExpectedRejection`/`NotYet`) n'a pas son artefact généré ;
3. une liaison de scène référence une entrée inexistante (ou non `Supported`) ;
4. les compteurs par axe divergent de la matrice de doc générée (§6) ;
5. une entrée `NotYet` sans `trackingIssue` ou avec un `PinnedBehavior` non assertable.

## 4. Corpus et acquisition

### Manifeste `scripts/fonts/corpus.json`

Autorité unique côté outillage Python ; le modèle Kotlin s'y relie par la `CorpusKey` :

```json
{
  "key": "noto-sans-thai",
  "files": [{
    "path": "test-fixtures/fonts/noto-sans-thai/NotoSansThai-Regular.ttf",
    "url": "https://…épinglée",
    "sha256": "…",
    "sizeBytes": 123456
  }],
  "license": "OFL-1.1",
  "licenseUrl": "…",
  "licenseFile": "OFL.txt",
  "synthetic": false,
  "builtBy": null,
  "tables": ["GSUB", "GPOS", "GDEF", "mark", "mkmk"]
}
```

- `synthetic: true` + `builtBy: "build_cff_fixture.py"` pour les fixtures générées : le script
  invoque le générateur au lieu de télécharger.
- Le champ `tables` est **validé** par le lint (il doit correspondre aux tables réellement
  présentes dans le fichier) — jamais de l'information purement déclarative qui dérive.
- Les 19 familles existantes migrent avec des **clés stables** dérivées de leur nom de
  répertoire.

### Script `scripts/fonts/fetch_fonts.py`

- Télécharge depuis les URL épinglées (**réseau opt-in**, usage local uniquement — la CI ne
  télécharge jamais).
- Vérifie les SHA-256 et la taille.
- **Refuse toute licence non redistribuable** (allowlist de licences : OFL, Apache-2.0, UFL
  et équivalents à consigner) ; toute police hors allowlist est une erreur bloquante, pas un
  avertissement.
- (Re)génère et valide les `PROVENANCE.md` par famille (URL, révision, SHA-256, taille,
  licence, oracle fontTools/HarfBuzz) sur le modèle existant.
- Mode `--check` : hors ligne, ne fait que vérifier hash + licence + fraîcheur
  `PROVENANCE.md` (c'est le mode que le mainteneur appelle avant de committer).

### Vérifications locales (hors CI)

Décision du propriétaire du dépôt, consignée ici comme amendement daté du 2026-09-23 : **la CI ne
porte que des concepts métier**. La comptabilité du corpus — hash, taille, licence, fraîcheur des
`PROVENANCE.md` — et la correspondance entre les tables réellement portées et les revendications du
catalogue restent des **commandes locales**, exécutées par le mainteneur avant de committer une
modification du corpus. Aucun job ne les exécute, et le fichier
`.github/workflows/font-corpus.yml` a été retiré de la branche.

Ce que ce retrait laisse sans garde automatique, et pourquoi c'est assumé :

- Une police re-pinnée sans mise à jour de son digest n'est plus signalée par un job — mais elle
  fait échouer les tests métier : les empreintes golden pour les six familles que les scènes
  utilisent, et les suites de shaping pour les autres, dont les assertions sont calibrées sur ces
  fichiers.
- Une licence hors allowlist, une taille ou un digest erronés dans le manifeste ne sont plus
  bloquants — ce sont des erreurs de saisie du mainteneur, hors du périmètre d'un test de
  comportement.
- La correspondance tables réelles ↔ revendications du catalogue reste vérifiée par le script, mais
  seulement quand on le lance. C'est le contrôle que ce dispositif tient le plus à conserver : il
  rend le catalogue prenable en défaut. Il est donc documenté comme étape obligatoire de toute
  modification de corpus dans `scripts/fonts/README.md`.

Les procédures à lancer localement :

1. `python3 scripts/fonts/fetch_fonts.py --check --provenance` — hash, taille, licences,
   `licenseFile`, présence des `PROVENANCE.md`, règle `url` + (`rawUrl` xor `fetchNote`).
2. `python3 -m unittest discover -s scripts/fonts/tests -v` — les règles ci-dessus et celles du
   lint, dont la complétude du manifeste face à l'arborescence de `test-fixtures/fonts/`.
3. `uv run --with fonttools==4.65.0 python scripts/fonts/check_exhaustiveness.py` —
   **lint d'exhaustivité** : pour chaque police réelle du corpus, chaque table « significative »
   portée doit être revendiquée par au moins une entrée du catalogue ou figurer dans une allowlist
   explicite de non-revendication avec raison. Les revendications sont exportées du modèle Kotlin
   (`claimed-tables.json`), qui reste l'unique autorité : une seule liste de revendications, celle
   du modèle.
4. Les tables « significatives » = liste fermée maintenue dans le lint (les tables purement
   cosmétiques/optionnelles non pertinentes pour Kalligraphie y sont exclues).

## 5. Contenu initial du catalogue

Estimation : **60–80 entrées**, ~15 nouvelles polices réelles complètes. Les candidats réels
sont à **vérifier à l'acquisition** (le script refuse un hash non conforme, ce qui protège
contre les URLs mouvantes) ; si un candidat s'avère ne pas porter la table visée, il est
remplacé — l'entrée, elle, reste.

| Axe | Entrées (statut jour J présumé) | Vraies polices |
|---|---|---|
| CONTAINER | ttf ✓, otf-cff1 réel ⭐ (ex. Source Sans 3), otc/ttc réel ⭐ (Source Han — ~120 Mo, à évaluer), woff `NotYet` ⭐, woff2 `NotYet` ⭐ | + 19 familles existantes migrées |
| OUTLINE | glyf simple/composite ✓, cff1-type2 réel ⭐, cff1-cid réel ⭐ (Source Han Sans), cff2-variable réel ⭐ (ex. Source Serif Variable) | |
| METRICS | hmtx/hhea ✓, vhea/vmtx plein ⭐ (Noto Sans JP complet), HVAR lsb/rsb réel ⭐ (Roboto Flex ?), MVAR réel ⭐ (à identifier), VVAR « aucune police réelle connue » | |
| VARIATION | fvar multi-axes ✓, avar v1 (Fraunces ⭐), avar v2 `NotYet` (probablement aucune réelle), cvar `NotYet` (idem), gvar complet ✓, VARC `NotYet` (rejet typé épinglé à l'instance non-défaut, fixtures HB seulement), STAT `NotYet` ⭐ (toute VF réelle), named instances ⭐ | |
| COLOR | colr-v0 ✓ (BungeeColor), multi-palettes ✓, colr-v1 statique ✓ (color-fonts), cpal-v1 ✓, svg-in-ot complet ⭐ (twemoji non sous-ensembleée), colr-cff `OutOfScope` (rationale) | |
| BITMAP | ebdt-fmt1 ✓, cbdt 17/18 ✓, Noto Color Emoji complet ⭐ (~24 Mo), cbdt-fmt19 `NotYet`, sbix-png ✓, sbix-jpg/mask `ExpectedRejection` (fixture dédiée à construire) | |
| SCRIPT | latin/grec/cyrillique/arabe/devanagari/JP-vertical ✓, hébreu ⭐ (marks/cantillation), thaï ⭐ (empilements), hangul ⭐ (Noto Sans KR), tamil ⭐, bengali ⭐, nastaliq ⭐ (Noto Nastaliq Urdu) | |
| ROBUSTNESS | troncatures, CRC PNG invalides, bombes ✓ (fixtures existantes à documenter en entrées) | |

⭐ = nouvelle acquisition. ✓ = couvert aujourd'hui (à migrer en entrées formelles).

## 6. Documentation

- Nouvelle page bilingue `docs/docs/e2e-catalog.md` / `e2e-catalog.fr.md` : intention,
  sémantique des statuts, cliquet, mode d'emploi (`fetch_fonts.py`, `updateE2eGolden`).
- **Matrice générée** : tâche Gradle `generateCatalogMatrix` écrit
  `docs/docs/generated/e2e-catalog-matrix.md` (une table par axe : id, technologie, police,
  statut). La CI vérifie que l'artefact committé est à jour (contrôle « comme du formatage »).
  Les libellés de statut sont des identifiants stables ; les en-têtes traduits sont générés
  pour les deux langues.
- Maillage : `font-management.md`(.fr) référence la matrice pour la correspondance
  table ↔ statut ; nav MkDocs mise à jour.
- `updateE2eGolden` régénère **manifeste golden + matrice de doc** en une commande.

## 7. Gestion d'erreur (fail-closed, partout)

| Situation | Détection | Conséquence |
|---|---|---|
| Entrée orpheline / id dupliqué / liaison sans entrée | `ExpectationCatalogRatchetTest` | échec `check` |
| Hash divergent, licence absente, `PROVENANCE.md` périmé | `fetch_fonts.py --check --provenance` | erreur bloquante locale |
| Table réelle portée et non revendiquée (hors allowlist) | lint fontTools | erreur bloquante locale |
| Matrice de doc non régénérée | test de fraîcheur dans `check` (artefact pas à jour) | échec CI |
| Comportement épinglé `NotYet` qui change | sonde | échec → flip de statut obligatoire |
| Rejet typé qui disparaît ou change de code | sonde `ExpectedRejection` | échec CI |
| URL morte / hash non conforme à l'acquisition | `fetch_fonts.py` | erreur bloquante locale |

## 8. Tests du dispositif lui-même

Le catalogue **est** la suite de tests ; le dispositif a ses propres tests unitaires :

- modèle : unicité des id, cohérence statut ↔ artefact, transitions légales de statut ;
- générateur : auto-dimensionnement déterministe (mêmes entrées → mêmes dimensions) ;
- parseur `corpus.json` du lint : rejet des licences inconnues, des hash mal formés.

TDD pour le modèle et le générateur (le comportement est défini avant l'implémentation).

## 9. Phasage (chaque phase = PR(s) fusionnables indépendamment)

1. **Modèle + cliquet** : types, statuts, générateur de scènes/sondes,
   `ExpectationCatalogRatchetTest`, tâche `generateCatalogMatrix` (le test de cliquet
   s'appuie dessus dès cette phase), migration des 16 scènes existantes en entrées
   (`Supported`). Zéro nouvelle police ; la régression visuelle est impossible par
   construction (mêmes empreintes).
2. **Acquisition** : `corpus.json`, `fetch_fonts.py` (+ mode `--check`), migration des 19
   familles, lint d'exhaustivité fontTools + export des
   revendications.
3. **Remplissage technologies** : nouvelles polices réelles des axes CONTAINER, OUTLINE,
   METRICS, VARIATION, COLOR, BITMAP + entrées `NotYet`/`OutOfScope` (STAT, woff/woff2,
   cbdt-fmt19, sbix-jpg…).
4. **Écritures** : hébreu, thaï, hangul, tamil, bengali, nastaliq + scènes associées.
5. **Docs** : pages bilingues narratives (la matrice générée existe depuis la phase 1),
   nav MkDocs, maillage `font-management`, CHANGELOG.

## 10. Risques acceptés

- **Taille du dépôt** (+80–150 Mo à terme) : choix assumé (polices complètes). Le clone
  alourdit ; pas de Git LFS (les hashes sont vérifiés par la CI sur le contenu committé).
- **Candidates réelles introuvables** pour certaines tables rares : la règle d'honnêteté
  s'applique, la couverture reste synthétique, l'entrée le documente.
- **Source Han OTC (~120 Mo)** : à évaluer en phase 3 ; si le poids est jugé rédhibitoire,
  l'entrée « TTC réel » peut rester en attente documentée sans bloquer le reste de l'axe.
- **Dérive de la doc narrative** (pages mains) : atténuée par la matrice générée, qui est la
  seule source des compteurs ; les pages mains n'énoncent plus de chiffres bruts.
