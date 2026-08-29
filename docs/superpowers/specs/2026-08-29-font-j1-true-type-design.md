# J1 — Font TrueType portable autonome

## Statut

Spec d’implémentation validée pour l’issue #4, alignée sur les invariants
normatifs de l’issue #2. Cette spec décrit uniquement le premier jalon
exécutable ; elle ne prétend pas implémenter l’architecture complète du
moteur d’édition.

## Objectif

À partir d’un tableau d’octets SFNT TrueType statique et capturé en mémoire,
un consommateur de l’artefact `org.graphiks:kalligraphie` peut construire un
catalogue embarqué mono-source, ouvrir sa face unique, créer une instance,
résoudre un caractère en glyph ID, obtenir ses métriques et produire un
`GlyphOutlineIR` utilisable sans `TextLayout`, renderer, GPU API ou font
système.

Le résultat doit fonctionner sur la cible JVM de référence tout en gardant
les contrats et le code métier dans `commonMain`, sans dépendance à un type
de plateforme.

## Contraintes normatives

- L’issue #2 est la référence pour les frontières publiques, l’identité, les
  handles, la matérialisabilité et le lifecycle.
- L’issue #4 est la référence pour le périmètre J1 et ses quatre tranches
  verticales.
- Le support porte sur un SFNT simple TrueType (`0x00010000` ou `true`).
- TTC/OTC, CFF/CFF2, variations, COLR, SVG, bitmap glyphs, fonts système,
  shaping, layout, hinting et rasterisation sont exclus.
- Les données reçues sont copiées à l’entrée ; aucune API ne conserve un
  buffer mutable appartenant à l’appelant.
- Les snapshots, valeurs de font, résultats observables et diagnostics sont
  immuables.
- Les erreurs de données ou de capacité sont des résultats typés, jamais des
  exceptions de parser traversant la façade.
- Les plages d’octets sont bornées et validées avant toute lecture ; les
  calculs de fin de plage utilisent une arithmétique qui détecte les
  dépassements.
- Toute profondeur de glyphe composite et tout budget de lecture sont bornés
  par une policy explicite.
- Les coordonnées de `GlyphOutlineIR` restent en design units. Les métriques
  exposent séparément les valeurs design units et les valeurs mises à
  l’échelle en `LayoutUnit`.

## Architecture Gradle et arborescence

Les sources actuellement présentes sous `font/` restent intactes et servent
de référence technique uniquement. La nouvelle implémentation est ajoutée
sous `kalligraphie/` :

```text
kalligraphie/
├── build.gradle.kts                         # façade publiée
├── api/
│   ├── build.gradle.kts
│   └── src/commonMain/kotlin/org/graphiks/kalligraphie/api/
├── unicode/
│   ├── build.gradle.kts
│   └── src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/
├── font/
│   ├── core/
│   │   ├── build.gradle.kts
│   │   └── src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/
│   ├── sfnt/
│   │   ├── build.gradle.kts
│   │   └── src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/
│   ├── scaler/
│   │   ├── build.gradle.kts
│   │   └── src/commonMain/kotlin/org/graphiks/kalligraphie/font/scaler/
│   └── glyph/
│       ├── build.gradle.kts
│       └── src/commonMain/kotlin/org/graphiks/kalligraphie/font/glyph/
└── src/commonMain/kotlin/org/graphiks/kalligraphie/
```

Le graphe J1 est acyclique :

```text
api ← unicode
api ← font/sfnt
api + font/sfnt ← font/scaler
api + font/scaler ← font/glyph
api + font/sfnt + font/scaler + font/glyph ← font/core
api + font/core + font/glyph ← kalligraphie
```

`unicode` est présent pour respecter la frontière du design #2 mais ne
fournit pas de comportement J1. Les futurs modules `colr`, `shaping`,
`layout`, `engine`, `platform`, `coroutines` et `raster-cpu` ne sont pas
créés comme dépendances de J1. La façade `:kalligraphie` publie le contrat
consommateur et assemble les implémentations ; les modules internes restent
accessibles seulement comme dépendances de construction.

Chaque module Kotlin utilise `commonMain` et une cible JVM de test. Aucun
code J1 ne dépend de `java.*`, de CoreText, DirectWrite, Skia ou d’un backend
graphique.

## Contrat public

Le package public est `org.graphiks.kalligraphie`. Les packages internes des
modules ne sont pas requis par le consommateur.

### Entrée et identité

`FontSource` encapsule les octets capturés, un nom d’affichage et une
provenance stable. Le constructeur copie les octets ; toute lecture retourne
une copie ou une vue immuable contrôlée par l’implémentation. Son
`FontSourceId` est une identité portable dérivée du SHA-256 du contenu selon
la convention de digest de J1. Une modification réelle des octets produit
donc une identité différente, même si les métadonnées de face sont
identiques.

`FontFaceId` combine l’identité de source et l’index de face. J1 n’accepte
qu’un SFNT simple et l’index `0`. `FontInstanceKey` combine au minimum la
face, la version d’interprétation des données, la taille de layout et les
synthèses géométriques ; J1 n’a ni axes ni synthèse. Les clés sont des types
distincts et ne sont jamais fabriquées par concaténation ambiguë dans l’API.

### Parcours autonome

La façade fournit les opérations conceptuellement suivantes, toutes sous la
forme `FontOperationResult<T>` :

```text
Kalligraphie.embedded(sourceBytes, provenance)
  → FontOperationResult<FontCatalogSnapshot>
FontCatalogSnapshot.openAssetResolver()
  → FontOperationResult<FontAssetResolverHandle>
FontCatalogSnapshot.resolveFace(faceRequest, requirements)
  → FontOperationResult<FontFace>
FontFace.instantiate(instanceDescriptor)
  → FontOperationResult<FontInstance>
FontInstance.resolveGlyph(codePoint)
  → FontOperationResult<GlyphId>
FontInstance.metrics(glyphId)
  → FontOperationResult<GlyphMetrics>
FontInstance.acquireRenderAsset(resolver, renderVariant, requirements)
  → FontOperationResult<FontRenderAssetHandle>
FontRenderAssetHandle.resolveGlyph(glyphRequest, cancellationToken)
  → FontOperationResult<GlyphRepresentation>
```

Les noms Kotlin exacts peuvent être organisés en classes auxiliaires, mais
les frontières et les résultats ci-dessus sont stables. Un chemin de
convenance privé peut regrouper des appels ; il ne doit pas contourner les
exigences d’accès ni publier une seconde façade.

`FontAccessRequirementsSnapshot` distingue `LAYOUT_ONLY` et `RENDERABLE`.
Pour J1, `RENDERABLE` accepte uniquement un `OutlineProfile` versionné,
portable et borné. Un profil uniquement natif, une représentation inconnue
ou une limite incompatible rend l’opération inéligible ; il n’existe pas de
fallback implicite.

### Représentations et géométrie

`GlyphRepresentation` est une hiérarchie fermée dont J1 implémente les
routes `Outline` et `Empty`. `GlyphOutlineIR` contient :

- le glyph ID et `unitsPerEm` ;
- des contours immuables composés de `MoveTo`, `LineTo`, `QuadraticTo` et
  `Close` ;
- un fill rule non nul versionné ;
- les bounds d’encre en design units ;
- les limites appliquées lors de la production.

Un glyphe sans contour retourne `Empty`, pas une fausse outline.

`LayoutUnit` encapsule un `Float` binary32 fini. Il normalise `-0`, rejette
NaN et infini, et transforme tout overflow de scaling en erreur
`GeometryOverflow`. `GlyphMetrics` conserve les valeurs TrueType brutes
(`advanceWidth`, `leftSideBearing`, bounds) et expose les équivalents
`LayoutUnit` calculés avec `size / unitsPerEm`. L’IR d’outline reste en design
units ; le renderer aval, s’il existe, applique sa transformation de layout.

## Répartition des responsabilités

### `api`

Définit les types publics : provenance, IDs, `LayoutUnit`, géométrie,
descripteurs de face et d’instance, exigences d’accès, profils d’outline,
résultats, diagnostics, erreurs, cancellation token, handles et
`GlyphRepresentation`/`GlyphOutlineIR`. Il ne lit aucun octet SFNT.

### `font/sfnt`

Fournit un lecteur big-endian borné et une directory SFNT immutable. Il
accepte uniquement le scaler TrueType simple, valide l’en-tête, le nombre de
tables, les tags, les offsets et les longueurs, puis expose des slices
protégées pour `head`, `maxp`, `name`, `cmap`, `hhea`, `hmtx`, `loca` et
`glyf`. Il ne choisit pas une face système et ne répare pas des offsets
invalides.

### `font/scaler`

Interprète les tables TrueType nécessaires à J1 :

- `head` : `unitsPerEm`, `indexToLocFormat`, bounds globaux ;
- `maxp` : `numGlyphs` et limites de points/contours ;
- `name` : noms minimaux de famille/style ;
- `hhea`/`hmtx` : metrics horizontales ;
- `cmap` : sélection déterministe des formats Unicode 4 et 12 ;
- `loca`/`glyf` : contours simples et composites.

Le scaler travaille dans les limites du profil et retourne des erreurs
structurées pour les tables tronquées, les glyph IDs hors plage, les ranges
invalides, les points incohérents ou les cycles de composites.

### `font/glyph`

Convertit la sortie du scaler en `GlyphOutlineIR`, attache les bornes et
assure la materialization selon l’`OutlineProfile`. Il implémente les
handles de représentation, la copie détachée et le lifecycle linéarisable
`OPEN → CLOSING → CLOSED`.

### `font/core`

Construit le catalogue mono-source, orchestre le parser et le scaler,
construit les identités, expose les métadonnées minimales et produit les
instances immuables. Il ne contient aucune résolution de fallback ni aucun
accès de plateforme.

### `kalligraphie`

Publie la façade principale, les factories d’entrée et les assemblages
concrets. Les consommateurs de J1 n’importent que cet artefact.

## Diagnostics et lifecycle

Chaque diagnostic possède un code stable `font.*`, une sévérité, une
location typée (`Source`, `Table`, `Face` ou `Glyph`) et des données
structurées bornées. L’ordre de publication est canonique par code,
location et données. Les messages peuvent expliquer l’erreur mais ne sont
pas l’identité de l’erreur.

Les erreurs fatales J1 comprennent notamment :

- `InvalidFontData` pour un SFNT ou une table incohérente ;
- `UnsupportedContainer` pour TTC/OTC, CFF/CFF2 ou wrapper inconnu ;
- `MissingRequiredTable` pour les tables nécessaires ;
- `OutOfBounds` pour tout range hors de la source capturée ;
- `GlyphOutOfRange` pour un glyph ID invalide ;
- `ResourceLimitExceeded` pour bytes, points, contours, composants ou
  profondeur dépassés ;
- `GeometryOverflow` pour une sortie `LayoutUnit` non représentable ;
- `ResourceClosed` pour une acquisition après fermeture ;
- `UnsupportedRepresentationProfile` pour une exigence non matérialisable ;
- `Cancelled` pour une annulation coopérative.

Les arguments de programmation manifestement invalides peuvent être rejetés
au constructeur ; les données de font malformées reçues d’un consommateur
doivent produire `Failure`.

Un `FontAssetResolverHandle` vivant capture la source et la génération
d’interprétation nécessaires aux acquisitions. `FontRenderAssetHandle` peut
être explicitement détaché : le handle détaché possède les données
immuables dont `resolveGlyph` a besoin et ne retient ni catalogue, ni
resolver, ni instance propriétaire. `close()` est idempotent ; une
acquisition qui a franchi atomiquement le point de fermeture peut terminer,
mais aucune nouvelle acquisition n’est acceptée après `CLOSING`.

J1 ne publie pas de handle natif. La cancellation token est coopérative et
consultée entre les phases de lecture, de contour et de composant ; aucun
résultat partiel n’est publié lorsqu’une opération est annulée.

## Stratégie de tests métier

Les tests de la nouvelle façade doivent échouer si le comportement TrueType
est faux. Les seuls tests de compilation ou d’existence de modules ne
comptent pas comme preuve.

La fixture principale est une vraie `LiberationSans-Regular.ttf` versionnée
avec son hash, sa licence OFL et sa provenance. Les attentes de cmap,
metrics et outlines sont écrites indépendamment du code de production et
incluent au moins :

- un caractère Latin présent avec son glyph ID et son advance connus ;
- un caractère absent qui sélectionne `.notdef` avec son diagnostic ;
- un glyph de contour simple dont les bounds et les commandes sont audités ;
- un glyph composite réel (par exemple une lettre accentuée) dont les
  composants sont résolus et transformés ;
- deux contenus de font réellement différents qui ne partagent pas un
  `FontSourceId`.

Les scénarios de sécurité utilisent des copies contrôlées de la vraie font
et des fixtures TrueType minimales générées dans les tests :

- table requise tronquée ;
- offset et longueur hors limites ;
- `loca` incohérente ;
- glyph simple avec compte de points impossible ;
- composite cyclique ;
- dépassement de profondeur ou de nombre de composants ;
- asset détaché encore résoluble après fermeture du resolver propriétaire.

Chaque tranche ajoute d’abord un test qui échoue pour l’absence du
comportement, observe cette failure, puis ajoute le minimum d’implémentation
et vérifie le test ciblé avant la suite du module. Les tests d’intégration
finals passent exclusivement par `org.graphiks:kalligraphie`.

Les commandes de vérification minimales sont :

```bash
./gradlew :kalligraphie:jvmTest
./gradlew :kalligraphie:fontTest
./gradlew :font:fontTest
```

Le dernier appel protège l’ancien corpus de référence. Les warnings Gradle
existants ne doivent pas être transformés en nouveaux diagnostics ou
erreurs.

## Pile de livraison

La pile respecte les branches `feat/` du guide de contribution et part de
`master` :

1. `feat/font-j1-1` — graphe `kalligraphie`, API, source immuable, SFNT,
   catalogue, face, métadonnées et diagnostics de limites ;
2. `feat/font-j1-2` — sélection `cmap`, glyph IDs, `head`/`hhea`/`hmtx` et
   métriques design units + `LayoutUnit` ;
3. `feat/font-j1-3` — `loca`/`glyf`, contours simples/composites, bounds,
   cycles et budgets ;
4. `feat/font-j1-4` — instance complète, profils d’outline, asset handle,
   fermeture et détachement ;
5. `feat/font-j1` — pointeur final regroupant la pile complète pour la
   validation de sortie de l’issue.

Chaque PR est testable seule sur sa branche de base, contient les tests du
comportement qu’elle ajoute et ne revendique pas les modules futurs. La
changelog et la décision documentaire seront renseignées dans chaque PR
selon `CONTRIBUTING.md`.

## Critère de sortie

Sur la JVM de référence, le parcours bytes → catalogue → face → instance →
glyph ID → métriques → `GlyphOutlineIR` fonctionne via l’artefact principal,
sur la vraie fixture auditée et sur les fixtures de sécurité, avec des
diagnostics structurés, des limites observables, des identités fondées sur le
contenu et un asset détaché utilisable après fermeture de son propriétaire.
