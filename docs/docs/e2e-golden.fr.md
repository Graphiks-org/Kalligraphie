# Empreintes golden de bout en bout

L'autorité de bout en bout de Kalligraphie est le module `:kalligraphie:e2e`, non
publié. Il porte deux axes indépendants : des journeys comportementaux qui
affirment la géométrie et les résultats de la façade publique, et des scènes
golden qui affirment les pixels finaux d'une sortie composée. Aucun des deux axes
ne dépend des modules de production.

Les pixels de référence sont hybrides : un manifeste d'empreintes versionné fait
foi pour la CI, et un dump opt-in écrit les images elles-mêmes pour l'inspection.
Le manifeste est commité et relu en revue ; les images ne sont pas hébergées.

Cette page publie le catalogue de scènes, le contrat de canonicalisation, les
verdicts de comparaison et les codes de diagnostic, les commandes opt-in, et la
frontière entre journeys et scènes.

## Module et isolation

`:kalligraphie:e2e` vit dans `kalligraphie/e2e/` et utilise la convention KMP des
modules non publiés (comme `:kalligraphie:conformance`). La convention déclare les
cibles `jvm`, `iosArm64`, `iosSimulatorArm64` et `android`, et la vérification
dorée s'exécute désormais sur toutes sauf `iosArm64` : la JVM, les compilations
hôte et appareil d'Android, et le simulateur iOS. `iosArm64` compile sans rien
exécuter, aucun runner hébergé ne pouvant fournir d'appareil. `explicitApi()` est
activé.

Ses dépendances le tiennent hors du graphe consommateur : `commonMain` ne dépend
que de `:kalligraphie:api`, et les source sets de test ajoutent `:kalligraphie`,
`:kalligraphie:conformance`, `:kalligraphie:raster-cpu`, `:kalligraphie:font:core`
et `:kalligraphie:font:sfnt` — plus `:kalligraphie:layout`, `:kalligraphie:shaping`
et `:kalligraphie:unicode` sur la JVM, où tournent les scènes de façade de
paragraphe. Rien en production ne dépend de ce module, et il n'est jamais publié.

Le modèle pur — `GoldenImage`, `GoldenScene`, `GoldenFingerprint`,
`GoldenManifest`, `GoldenComparison` et le digest SHA-256 — vit dans
`commonMain`, sans type plateforme. Le catalogue, le matérialiseur de scènes, les
renderers portables, le vérificateur, les ratchets de capacités et les fixtures
sont des sources de test partagées, compilées dans chaque cible de test qui peut
les exécuter et lisant leurs octets via un `FixtureCorpus` injecté plutôt que par
le class path ; seuls les renderers de la façade de paragraphe, les écrivains et
les parcours restent dans `jvmTest`. Une scène déclare la `CatalogRoute` dont
elle a besoin, la plateforme déclare ses capacités portables, et le ratchet
refuse un registre qui ne serait pas exactement ce que ces capacités impliquent.

La protection est gratuite sur la plateforme de référence : le `check` racine
exécute `:kalligraphie:e2e:jvmTest` comme tout sous-projet, et le workflow de pull
request existant couvre déjà `kalligraphie/**`. Les autres cibles sont exécutées par
`.github/workflows/golden-portability.yml` : la suite JVM sur les cinq architectures
de runner que la bibliothèque livre, `testAndroidHostTest` et
`connectedAndroidDeviceTest` sur un émulateur, et `iosSimulatorArm64Test` sur un
runner macOS avec la suite portable du rastériseur. Une plateforme qui cesse de
reproduire les empreintes committées échoue son propre job, et l'enregistrement
n'est jamais regelé pour lui plaire.

## Quelles scènes chaque plateforme vérifie

Chaque scène déclare la `CatalogRoute` dont elle a besoin, et chaque plateforme
vérifie les scènes que ses propres capacités déclarées peuvent servir. Sur la JVM,
c'est tout le catalogue ; sur Android et iOS, c'est la route portable des glyphes.
Le tableau ci-dessous suit la route déclarée et le manifeste committé.

| Commande | Plateforme | Scènes |
| --- | --- | --- |
| `./gradlew :kalligraphie:e2e:jvmTest` | JVM, tous les runners | Toutes les scènes cataloguées |
| `./gradlew :kalligraphie:e2e:testAndroidHostTest` | Test unitaire Android, exécution JVM | Les scènes portables |
| `./gradlew :kalligraphie:e2e:connectedAndroidDeviceTest` | Émulateur Android, ART | Les scènes portables |
| `./gradlew :kalligraphie:e2e:iosSimulatorArm64Test` | Simulateur iOS, Kotlin/Native | Les scènes portables |

Les quatre comparent le même manifeste committé, octet pour octet, sans tolérance
numérique : l'empreinte d'une scène est un fait du rastériseur, pas de la
plateforme qui l'a exécuté. Une scène qu'une plateforme ne vérifie pas est nommée
par `deferredSceneIds()`, dérivé de l'identité de capacités de cette plateforme :
elle est donc excusée, jamais sautée.

## Catalogue de scènes

Chaque scène est rendue via la façade publique et le rastériseur CPU, puis
réduite à un seul digest. Le tableau ci-dessous illustre les six familles, il
n'est pas l'inventaire : la [matrice du catalogue
d'attentes](generated/e2e-catalog-matrix.fr.md) générée énumère chaque scène et
est le seul endroit où les compteurs du catalogue apparaissent.

| Famille | Scènes | Couverture observable | Format |
| --- | --- | --- | --- |
| `GLYPH_OUTLINE` | `glyph.outline.liberation-sans.A.64` (43×45) | Un glyphe aplati puis converti en couverture à 64 pixels par em. | `ALPHA_8` |
| `GLYPH_PAINT` | `glyph.paint.emoji-two-colr-v0.u1F600.64` (71×72) | Un glyphe COLR v0 résolu en calques, composé en `SOURCE_OVER` et teinté. | `RGBA_8888` |
| `GLYPH_BITMAP` | `glyph.bitmap.skia-ebdt-format1.u1F600.16` (13×13) | Un glyphe routé vers le strike EBLC/EBDT embarqué. | `RGBA_8888` |
| `COMPOSED_LINE` | `line.latin.48`, `line.greek.48`, `line.cyrillic.48`, `line.arabic.48`, `line.devanagari.48`, `line.mixed.48`, `variation.wght-ladder` (291×306) | De vraies lignes de texte mises en forme et composées par la façade de paragraphe : latin, grec, cyrillique, arabe de droite à gauche, devanagari, et une ligne mixte dont les scripts sont résolus par repli entre trois polices — plus un mot rendu cinq fois, une par instance `wght` d'une vraie police variable, sur une même grille de lignes de base. | `ALPHA_8` |
| `MOSAIC` | `composition.every-route-mosaic` (592×366) | Sept routes dans une seule image : un mot mis en forme en contour TrueType, le A majuscule en CFF 1 puis CFF 2, une ligne multi-scripts résolue par trois polices, un glyphe à quatre graisses, une peinture couleur et un strike bitmap, composés sur un même canvas couleur. | `RGBA_8888` |
| `ALPHABET_SHEET` | `sheet.outline.liberation-latin.32`, `sheet.outline.liberation-greek.32`, `sheet.outline.liberation-cyrillic.32`, `sheet.outline.amiri-arabic.32`, `sheet.outline.noto-devanagari.32`, `sheet.paint.bungee-color-latin.48`, `sheet.paint.emoji-two-colr-v0.64` | Tous les glyphes couverts d'une police, disposés dans une grille : couverture pour les polices de contour, couleur composée pour Bungee Color et EmojiTwo. | `ALPHA_8` et `RGBA_8888` |

Les trois scènes de glyphe isolé sont la forme promue des constantes de
conformance que `:kalligraphie:raster-cpu` portait ; les lignes composées et les
planches d'alphabet sont la forme promue de ses dumps de démonstration. Leurs
digests sont inchangés par ce déplacement : l'autorité d'empreinte a simplement
changé de module.

La scène bitmap isolée est délibérément *plus stricte* que le dump qu'elle
remplace : le rastériseur retourne le cadre 13×13 propre au strike, là où l'ancien
dump ajoutait deux pixels de marge pour la lisibilité.

## Canonicalisation et manifeste

La canonicalisation fixe une seule forme sérialisée, stable et portable :

- les pixels sont en row-major, sans padding ;
- les octets conservent l'ordre de lignes déclaré par leur producteur, que
  `GoldenImage` nomme : `GoldenOrientation.DESIGN` pour les routes brutes
  d'outline et de peinture, dont la ligne zéro est le bas visuel, et
  `GoldenOrientation.IMAGE` pour les canvas composés et les strikes bitmap
  normalisés. Rien ne normalise les octets canoniques, donc la même image
  logique produit toujours la même empreinte ;
- présenter une scène à l'endroit est une préoccupation de dump seulement :
  l'écrivain retourne une trame en orientation conception une fois, et laisse
  intacte une trame déjà en orientation image ;
- l'empreinte est le SHA-256 de ces octets canoniques ;
- la forme est versionnée par `CANONICALIZATION_VERSION`, actuellement `1`.

Le manifeste vit dans
`kalligraphie/e2e/src/harnessResources/golden/manifest.tsv`. Sa première ligne
déclare le format et la version de canonicalisation, puis une ligne séparée par
des tabulations par scène, triée lexicographiquement par identifiant :

```
kalligraphie.golden/v1 canonicalization=1
<sceneId>\t<family>\t<W>x<H>\t<FORMAT>\tsha256:<hex>
```

Il est en UTF-8 avec des fins de ligne LF, sans ligne vide ni commentaire. Un
en-tête dont `canonicalization=` diffère du code invalide tout le manifeste d'un
coup, produisant un échec unique et explicite au lieu d'une marée de mismatches.

Le code ne réécrit jamais le manifeste. Il ne change que par la tâche opt-in
`updateE2eGolden`, puis par la revue.

## Comparaison et diagnostics

La comparaison est exacte, octet à octet, sans tolérance — cohérent avec un
rastériseur déterministe à arithmétique entière et seize sous-échantillons fixes.
Une tolérance n'arriverait que si une route non déterministe apparaissait.

Les verdicts échouent de façon fermée :

| Verdict | Condition |
| --- | --- |
| Matched | Le digest de la scène égale le digest enregistré. |
| Mismatch | Les digests diffèrent ; le rapport porte l'identifiant de scène, les deux digests, le premier octet divergent et sa coordonnée `(x, y)`. |
| Missing in manifest | Une scène du catalogue n'a pas d'entrée — jamais de pass silencieux. |
| Stale manifest entry | Une entrée n'a pas de scène au catalogue. |
| Render failed | Le rendu de la scène a échoué, avec un code typé. |

Un identifiant de scène dupliqué, dans le catalogue ou dans le manifeste, échoue
au chargement.

| Code | Signification |
| --- | --- |
| `e2e.render-failed` | Le rendu de la scène a échoué. |
| `e2e.mismatch` | Le digest rendu diffère du digest enregistré. |
| `e2e.scene-bounds-invalid` | Le cadre déclaré d'une scène et ses bornes rendues divergent. |
| `e2e.manifest-missing-entry` | Une scène du catalogue n'a pas d'entrée de manifeste. |
| `e2e.manifest-stale-entry` | Une entrée de manifeste n'a pas de scène au catalogue. |
| `e2e.manifest-duplicate-id` | Un identifiant de scène apparaît plus d'une fois. |
| `e2e.canonicalization-version-mismatch` | Le manifeste a été écrit sous une autre version de canonicalisation. |
| `e2e.manifest-malformed` | Le texte du manifeste est mal formé. |

## Vérification et régénération des références

```bash
./gradlew :kalligraphie:e2e:jvmTest
./gradlew :kalligraphie:e2e:updateE2eGolden
env KALLIGRAPHIE_E2E_DUMPS_OUTPUT=/tmp/kalligraphie-e2e \
    ./gradlew :kalligraphie:e2e:e2eGoldenDumps
```

`jvmTest` fait partie de `check`. Les deux autres tâches sont opt-in, sont
exclues de `check`, et se réexécutent toujours : `updateE2eGolden` écrit le
manifeste à partir du catalogue, et `e2eGoldenDumps` écrit une image PGM ou PPM
par scène, nommée d'après l'identifiant de scène, accompagnée d'une copie du
manifeste.

La sortie de dump doit être un chemin absolu hors du dépôt ; le runner vérifie
les deux et échoue plutôt que d'écrire dans le checkout. Chaque dump s'ouvre à
l'endroit : une scène qui déclare l'orientation conception — les routes brutes
de glyphe isolé, outline et peinture — est retournée une fois par l'écrivain,
tandis que les planches et lignes composées et les strikes bitmap sont déjà en
orientation image et sont écrits tels quels.

## Journeys et scènes

Les deux axes restent délibérément séparés, sans double couverture :

- les journeys comportementaux affirment la géométrie et les résultats — carets,
  sélection, fragmentation, limites d'opération — et donnent un diagnostic riche
  et rapide ;
- les scènes golden affirment les pixels finaux.

Une scène n'est promue depuis un journey que là où un digest de pixels prouve ce
que la géométrie ne couvre pas ; sinon la géométrie suffit.

Deux journeys ont migré ici : `AdvancedTypographyJourneyTest` et
`CffOpenTypeJourneyTest`. Trois restent dans `:kalligraphie` :
`FlowCompositionEditorJourneyTest` et `IncrementalLayoutEditorJourneyTest`
exercent des membres `internal` de la façade et de la session, ce sont donc des
tests en boîte blanche de leur propre module, et `SystemFontCatalogJourneyTest`
partage ses helpers avec `FontDirectoryCatalogTest`, adossé au système de
fichiers.

## Fixtures

`test-fixtures/` à la racine du dépôt est la source unique de polices pour tout le
build, consommée par chaque source set de test via
`resources.srcDir(rootProject.file("test-fixtures"))`. Les builders Kotlin de
fixtures restent par consommateur — `:kalligraphie:e2e` définit ses propres
builders minces lisant `/fonts/...` sur le classpath — plutôt que dans un module
de fixtures partagé.

## Limites connues

- Les scènes composées — lignes composées, échelle de graisses et mosaïque — ne
  s'exécutent que sur la JVM. Elles attendent un moteur portable d'analyse
  Unicode, et le ratchet de capacités les exigera sur chaque cible le jour où il
  arrivera.
- `iosArm64` compile mais n'exécute rien : aucun runner hébergé ne peut fournir
  d'appareil.
- La comparaison est exacte ; une route de rendu non déterministe exigerait
  d'introduire une tolérance.
- Les images de référence ne sont pas hébergées à l'extérieur (ni LFS ni artefact
  de build) : le manifeste est donc l'enregistrement portable et les dumps sont
  locaux.
- Les journeys de plateforme (`CoreTextRegistryJourneyTest`,
  `FontconfigRegistryJourneyTest`, `DirectWriteRegistryJourneyTest`) restent dans
  leurs modules de plateforme, car ils exercent des providers plutôt que la
  composition portable.
- Le manifeste est régénéré à la main et relu dans la pull request ; rien ne le
  régénère automatiquement.
