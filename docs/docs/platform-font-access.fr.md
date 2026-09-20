# Accès aux fontes via la plateforme

Kalligraphie peut certifier une route CoreText explicitement acceptée tout en
conservant l’analyse du texte, le shaping (transformation du texte en glyphes),
le repli entre fontes, le positionnement et la géométrie d’édition dans son
pipeline (chaîne de traitement) portable. L’application garde son document et son renderer (moteur
de rendu). L’accès de plateforme ne remplace pas un `GlyphRun` par une mise en page
de plateforme et ne dessine aucun pixel à sa place.

L’accès de plateforme désigne une route dépendant d’une liaison explicitement
acceptée par le consommateur, pas le langage de son implémentation. Un handle
de plateforme (référence opaque vers une ressource) n’est pas nécessairement
une adresse mémoire. Dans ce guide, appels, pointeurs et allocations natifs
désignent précisément l’implémentation d’interopérabilité avec l’API C CoreText.

## Capacités des cibles et découverte

La découverte dans les répertoires et le rendu de plateforme sont des capacités
distinctes. L’artefact principal JVM (machine virtuelle Java) propose
`FontDirectoryCatalog`, `LinuxSystemFontCatalog` et `MacosSystemFontCatalog` ;
voir [la capture et ses limites](font-management.md#capturer-des-repertoires-de-fontes-sur-la-jvm).
Les fournisseurs de répertoires JVM capturent des fichiers accessibles, sans
reproduire exactement le registre des fontes activées ; le fournisseur macOS
`CoreTextSystemFontCatalog` de `:kalligraphie:platform:apple` capture, lui, le
registre CoreText activé, et le fournisseur Linux `FontconfigSystemFontCatalog`
de `:kalligraphie:platform:linux` capture la configuration Fontconfig activée. Le
fournisseur Windows `DirectWriteSystemFontCatalog` de
`:kalligraphie:platform:windows` capture la collection de fontes système
DirectWrite activée. Le fournisseur Android `AndroidSystemFontCatalog` de
`:kalligraphie:platform:android` capture la collection de fontes système de la
plateforme sur Android 10 et ultérieur. Un
nouvel `open` rafraîchit explicitement le snapshot (instantané immuable) ; les
ressources indépendantes de la génération précédente restent possédées par leurs
consommateurs.

| Cible / fournisseur | Découverte et données sources | Shaping opérationnel | Accès aux glyphes et rafraîchissement |
|---|---|---|---|
| JVM `FontDirectoryCatalog` | Racines lisibles explicites ; TrueType statique à face unique et TTC 1/2, source/indice d’origine | HarfBuzz embarqué sur Linux/macOS x64 et arm64 | Profils portables de contour/peinture/bitmap déclarés ; nouvel `open` |
| JVM Linux `LinuxSystemFontCatalog` | Racines système, utilisateur historique et XDG, ou racines explicites ; même capture TrueType/TTC | HarfBuzz embarqué sur Linux x64 et arm64 | Mêmes routes portables ; pas de matching (sélection) par registre Fontconfig ni rafraîchissement automatique |
| JVM Linux `FontconfigSystemFontCatalog` (`:kalligraphie:platform:linux`) | Configuration Fontconfig activée via `kffi-fontconfig`, pas une liste de répertoires ; octets `.ttf`/`.ttc`/`.otf` capturés avec les indices de face d’origine | HarfBuzz embarqué sur Linux x64 et arm64 | Mêmes routes portables ; un nouvel `open` observe une installation/suppression contrôlée et crée une nouvelle génération `fontconfig-registry` |
| JVM macOS `MacosSystemFontCatalog` | Racines système/utilisateur standard, ou racines explicites ; même capture TrueType/TTC | HarfBuzz embarqué sur macOS x64 et arm64 | Mêmes routes portables ; pas de sélection par registre CoreText ni rafraîchissement automatique |
| JVM macOS `CoreTextSystemFontCatalog` (`:kalligraphie:platform:apple`) | Registre CoreText activé via `kffi-coretext`, pas une liste de répertoires ; octets `.ttf`/`.ttc`/`.otf` capturés avec les indices de face d’origine | HarfBuzz embarqué sur macOS x64 et arm64 | Mêmes routes portables ; un nouvel `open` observe une installation/suppression contrôlée et crée une nouvelle génération `coretext-registry` |
| Adaptateur CoreText facultatif sur JVM macOS | Octets exacts d’un catalogue portable ; TrueType statique monochrome à face unique éligible uniquement | Conserve le shaping portable ; aucune substitution par une mise en page CoreText | Handle de plateforme explicitement accepté, ou routes portables sous-jacentes ; collections exclues de la route de plateforme |
| JVM Windows `DirectWriteSystemFontCatalog` (`:kalligraphie:platform:windows`) | Collection de fontes système DirectWrite activée via `kffi-directwrite`, pas une liste de répertoires ; octets `.ttf`/`.ttc`/`.otf` capturés avec les indices de face d’origine | Aucune cible HarfBuzz opérationnelle embarquée | Mêmes routes portables ; un nouvel `open` observe une installation/suppression contrôlée et crée une nouvelle génération `directwrite-registry` |
| JVM Android `AndroidSystemFontCatalog` (`:kalligraphie:platform:android`) | Collection de fontes système de la plateforme via `android.graphics.fonts.SystemFonts` (Android 10+), pas un parcours de chemins arbitraires ; octets `.ttf`/`.ttc`/`.otf` capturés avec les indices de face d’origine ; les noms de famille et de face proviennent de l’analyse des octets capturés | Aucun backend HarfBuzz embarqué dans ce module ; la pile texte de la plateforme s’applique | Mêmes routes portables ; un nouvel `open` observe un changement contrôlé et crée une nouvelle génération `android-platform-fonts` |
| iOS `IosSystemFontCatalog` (`:kalligraphie:platform:ios`) | Registre CoreText via les bindings CoreText de la plateforme, pas une liste de répertoires ; iOS isole (sandbox) les fichiers de fontes système, donc le contenu `.ttf`/`.ttc`/`.otf` est reconstruit à partir des tables copiées de chaque fonte | Aucun backend HarfBuzz embarqué dans ce module ; la pile texte de la plateforme s’applique | Mêmes routes portables ; un nouvel `open` observe un changement contrôlé et crée une nouvelle génération `ios-coretext-registry` |
| Kotlin Native (autres cibles) | Aucun fournisseur de fontes système sur ces cibles | Aucun parcours de shaping complet implémenté | Contrats communs portables ; ces parcours exécutables ne sont pas implémentés |
| Données CFF/CFF2 sur toute cible | Les contours CFF1 `.otf` isolés et CFF2 sont lus ; les collections portant des faces CFF sont capturées | Shaping CFF1 par le shaper portable ; CFF2 matérialisé uniquement à l’instance par défaut | Route portable de contours cubiques pour CFF1 et CFF2 (instance par défaut) ; aucune instance de variation CFF2 non par défaut ni route CFF CoreText |

La route embarquée à face unique reste disponible sur la JVM. Une extension ne
garantit pas le type de contours : `.otf` peut contenir du TrueType, du CFF1 ou
du CFF2. Les contours cubiques CFF1 sont livrés de bout en bout ; le CFF2 n’est
matérialisé qu’à son instance par défaut, et une instance de variation non par
défaut n’est pas prise en charge. L’admission en répertoire est bornée : une source TTC/OTC dont le
nombre total de faces dépasse le budget d’examen restant est refusée entièrement,
avec un diagnostic de limite typé, avant tout examen de ses répertoires. Aucun
préfixe de collection partiellement examinée n’est publié. Des sources distinctes
entièrement examinées peuvent encore former un catalogue partiel ; la limite de
faces acceptées s’applique séparément après examen de la source, en conservant les
indices sélectionnés d’origine. Ces contrôles ne prétendent pas reproduire le
container sanitizer (validateur de sécurité du conteneur) de HarfBuzz pour les
tables non prises en charge. Une face
découverte ne garantit pas son utilisation par tout backend (moteur de
traitement) ou profil de représentation. La matrice CI (intégration continue)
JVM Linux/macOS à quatre cibles exécute de vrais parcours de découverte,
shaping et glyphes avec les tests complets du shaper (moteur de shaping) et
l’audit des dépendances natives ; elle n’établit pas de support Windows, mobile
ou CFF.

Les nouveaux symboles et types natifs bruts, déclarations ABI (interface binaire),
constantes et accès aux bibliothèques appartiennent à kffi. Kalligraphie garde
l’adaptation typographique, la capture, la provenance, l’identité, les
générations, les diagnostics et la durée de vie des ressources de fonte.
Le module Apple facultatif utilise les bindings (liaisons natives) CoreText
spécialisés de kffi et son service Darwin d’information système ; le chargement
des bibliothèques, les symboles, les signatures et la disposition mémoire de la
matrice appartiennent à kffi. Le backend (moteur) de shaping HarfBuzz JVM utilise
de même la liaison publiée `org.graphiks:kffi-harfbuzz-jvm` : le chargement de la
bibliothèque, les signatures ABI, les dispositions mémoire et les propriétaires
natifs appartiennent à kffi, tandis que Kalligraphie garde la politique de
features (options OpenType), l’interprétation des clusters et des carets de
ligature GDEF (coupures de ligature) et la conversion unités de design → unités
de layout. La capture de répertoires n’ajoute aucune liaison native brute.

## Module Apple facultatif

Ajouter `:kalligraphie:platform:apple` au module principal `:kalligraphie`.
Sa coordonnée de publication est `org.graphiks:kalligraphie-platform-apple` ;
un consommateur Kotlin Multiplatform sélectionne la variante JVM (machine
virtuelle Java). Cette route exige macOS 15 ou ultérieur, une JVM x64 ou
arm64 et JDK 25. Lancer la JVM applicative avec
`--enable-native-access=ALL-UNNAMED` pour les appels natifs.

Voir la [référence API Apple](api/kalligraphie/platform/apple/org.graphiks.kalligraphie.platform.apple/index.md)
pour les contrats du catalogue, des limites et des propriétaires de plateforme.

L’artefact principal ne dépend pas de ce module et ne charge aucun framework
Apple (bibliothèque de plateforme). L’API commune ne transporte que des
identités de route et des contrats de propriété, jamais des pointeurs CoreText
ou des types kffi. Le module facultatif utilise les liaisons CoreText de kffi en
interne et ne charge que la surface native nécessaire à l’accès aux fontes,
quand la fabrique explicitement acceptée crée son adaptateur sur une plateforme
prise en charge. Les cibles portables conservent leurs exigences actuelles,
dont Android API 24.

Les dépendances kffi internes utilisent
`org.graphiks:kffi-coretext-jvm:1.0.0-SNAPSHOT` (module Apple) et
`org.graphiks:kffi-harfbuzz-jvm:1.0.0-SNAPSHOT` (shaping JVM) et suivent la
dernière publication de la ligne de développement actuelle. Un snapshot est une
version de développement dont le contenu peut changer. Leur résolution exige le
dépôt de snapshots Central Portal, filtré pour les artefacts racines/JVM
CoreText, HarfBuzz et du runtime (moteur d’exécution) générique requis par les
métadonnées de publication. Les modules consommateurs revérifient les artefacts
modifiables à chaque résolution en ligne ; ils n’épinglent pas d’artefact
horodaté et n’imposent pas de politique globale de vérification des sommes de
contrôle. Une publication plus récente peut changer entre deux constructions et
nécessiter une adaptation du code source.

Ajouter ce dépôt au `settings.gradle.kts` du consommateur, à côté de ses
dépôts Maven habituels :

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            content {
                includeModule("org.graphiks", "kffi-coretext")
                includeModule("org.graphiks", "kffi-coretext-jvm")
                includeModule("org.graphiks", "kffi-harfbuzz")
                includeModule("org.graphiks", "kffi-harfbuzz-jvm")
                includeModule("org.graphiks", "kffi")
                includeModule("org.graphiks", "kffi-jvm")
            }
        }
    }
}
```

Les réglages du cache des dépendances ne sont pas publiés aux consommateurs.
Pour rechercher immédiatement une publication kffi plus récente, lancer la
construction du consommateur avec `--refresh-dependencies` ou régler ses
durées de cache des modules modifiables. Une
construction hors ligne utilise les dépendances déjà en cache.

## Capturer une source exacte

`CoreTextFontCatalog.capture(portable, policy, cancellationToken)` adapte
un catalogue portable dont les octets OpenType sont accessibles et immuables,
avec une estimation fiable des copies avant leur exécution. Les identifiants
de face, les métadonnées et les `FontInstanceKey` complets restent identiques,
y compris la taille et la géométrie. Les correspondances caractères/glyphes,
les métriques et l’interprétation du shaping portable sont conservées.

La fonte de plateforme est construite depuis ces octets exacts via `CGFont` puis
`CTFont`, jamais par une recherche de nom de famille. Cela n’autorise ni
substitution de fonte par la plateforme, ni fallback caché (repli implicite),
ni nouvelle correspondance entre les caractères et les glyphes finaux.

L’éligibilité à la plateforme est volontairement restrictive : TrueType statique,
monochrome, à face unique, géométrie et variante de rendu par défaut.
Les collections, CFF/CFF2, données de variation, gras/italique synthétiques,
tables couleur ou bitmap (images matricielles) et variantes visuelles non
canoniques sont exclues de cette route. Les faces incompatibles avec la plateforme
conservent les capacités portables de leur fournisseur sous-jacent.

La fabrique exige des limites explicites dans `CoreTextFontAccessPolicy` ;
aucune capture implicitement illimitée n’est choisie. Une application peut,
par exemple, sélectionner :

```kotlin
val policy = CoreTextFontAccessPolicy(
    maxSourceBytesPerFace = 2_000_000L,
    maxCapturedSourceBytes = 8_000_000L,
    maxTransientOwnedBytes = 8_000_000L,
)
```

Ces valeurs illustrent un choix applicatif, pas des seuils universellement
recommandés. Traiter la réussite, l’échec typé ou l’annulation de la fabrique
avant d’utiliser le catalogue adapté.

## Négocier l’accès de plateforme ou portable

Le catalogue adapté expose son `platformProfile` exact. L’inclure dans
`FontAccessRequirementsSnapshot.renderable(...)` uniquement si le
consommateur sait utiliser ce bridge (liaison avec la plateforme). Les profils
ordonnés expriment une préférence, pas l’autorisation de masquer une
annulation ou un échec d’allocation par une route moins coûteuse.

Pour accepter l’accès de plateforme puis un contour portable :

```kotlin
val requirements = FontAccessRequirementsSnapshot.renderable(
    acceptedProfiles = listOf(catalog.platformProfile, outlineProfile),
)
```

Ici `catalog` est le catalogue adapté obtenu avec succès et `outlineProfile`
est le profil portable compris par le consommateur. Fournir
`portableDataRequired = true` lorsqu’un contour, un graphe de peinture ou un
bitmap portable est réellement nécessaire. Les profils de plateforme sont alors
exclus avant négociation : un certificat de plateforme n’est pas une représentation
portable de glyphe.

Utiliser ensemble le résolveur adapté et la génération du catalogue adapté
dans les demandes de layout (mise en page). Les ressources portables acquises
via ce résolveur exposent sa génération publique tout en déléguant à des
ressources sous-jacentes possédées indépendamment.

## Certifier les glyphes finaux

Le moteur de shaping portable et la mise en page produisent les identifiants et
placements finaux. La certification de plateforme valide le contexte exact de fonte,
puis chaque nouvel identifiant final distinct dans la plage vérifiée des
glyphes de plateforme et du type `CGGlyph`. Cela comprend ligatures, substitutions et
glyphes dérivés par la mise en page, notamment le tiret visible à une coupure.
Les caractères sources ne sont pas remappés.

Le glyphe zéro et un glyphe sans encre peuvent être des identifiants de plateforme
valides. La politique existante des caractères manquants détermine toujours
leur présence dans le texte composé. Un identifiant hors plage est refusé,
jamais transformé en fausse représentation vide. La certification ne génère
aucun contour, ne rasterise pas (ne convertit pas en pixels) et ne dessine pas.

Un certificat `PLATFORM_HANDLE` porte la clé effectivement émise par le
fournisseur. Il garantit l’acquisition de la route de plateforme correspondante tant
que la ressource est vivante, sous réserve d’échecs opérationnels distincts.
`resolveGlyph` portable sur une ressource exclusivement de plateforme retourne une
incompatibilité de route typée, pas un faux contour.

## Posséder la durée de vie des ressources de plateforme

La valeur de mise en page immuable et ses clés ne possèdent aucune ressource
de fonte. Ouvrir un `LayoutHandle` tant que son résolveur correspondant est
vivant, puis retenir le certificat publié exact via
`retainFontAsset(certificate)`. La ressource retournée est un propriétaire
indépendant, capable d’en détacher un autre.

`JvmEditableParagraphFacade.layout` possède son backend de shaping (moteur
sous-jacent) utilisé et le ferme avant publication du résultat. Le paragraphe
immuable publié ne conserve pas ce moteur ; son résolveur correspondant doit
toutefois rester vivant pour ouvrir un propriétaire de mise en page.

Un `PlatformFontRenderAssetHandle` acquiert un `PlatformFontLease` (propriétaire
de plateforme indépendant). Pour cette liaison, le propriétaire spécialisé est
un `CoreTextFontLease` ; `fontRef()` retourne le pointeur CoreText utilisable
pendant sa durée de vie. Traiter ces opérations comme des
`FontOperationResult`, avec leurs annulations et échecs typés.

Fermer la ressource, le résolveur ou le propriétaire de mise en page initial
n’invalide pas un enfant indépendant déjà admis. Cet enfant peut être utilisé
depuis un autre thread (fil d’exécution) et doit lui-même être fermé. Un
propriétaire fermé refuse les nouvelles acquisitions. La fermeture est
idempotente et n’attend pas les enfants admis ; le dernier propriétaire ou
opération libère le contexte natif sous-jacent.

Le résolveur adapté conserve le résultat typé de fermeture de son résolveur
portable privé lorsque le drainage est immédiat. Si des acquire/reopen admis
sont encore en cours, la fermeture retourne sans attendre ; la dernière
opération terminée porte les diagnostics du drainage différé. Un refus de
cleanup (nettoyage) est terminal (`font.platform-resolver-cleanup-failed`),
tandis qu’une annulation primaire reste une annulation. Toute ressource non
transférable est d’abord fermée. Une fermeture répétée ne retente pas le
drainage. Les adaptations portables resolve/instantiate et acquire/reopen/detach
conservent les diagnostics de réussite du fournisseur ; le détachement vérifie
la clé sous-jacente complète avant d’exposer la clé
publique d’origine.

**Obligation liée au pointeur brut :** garder le propriétaire ouvert pendant
tout appel natif non géré utilisant son pointeur et ne pas le fermer en
concurrence avec cet appel. Un pointeur seul ne maintient rien en vie. Les
callbacks Kotlin (fonctions de rappel) et helpers de portée (fonctions
d’encadrement) ne peuvent pas empêcher mécaniquement la fuite d’un pointeur ;
ils ne remplacent pas la propriété. Les opérations de Kalligraphie se
protègent elles-mêmes par des opérations enfants admises.

## La géométrie de dessin appartient au consommateur

`CTFont` utilise une matrice identité et une taille numérique en points égale
à `layoutSize.value`. Cette convention ne convertit pas les unités de mise en
page en points physiques d’affichage.

Transmettre au moteur de rendu les identifiants et origines finaux, y compris
les offsets de shaping (décalages de placement). Respecter la transformation
visuelle de chaque placement autour de son origine. Appliquer de manière
cohérente et unique la conversion vers le périphérique, le zoom et la
convention des axes. Ne pas refaire le shaping, recalculer les avances ou
positions de curseur, ni multiplier une seconde fois les origines déjà
positionnées par la taille de fonte.

Avec `CTFontDrawGlyphs`, sauvegarder/restaurer l’état graphique applicatif et
préserver explicitement la matrice de texte avec `CGContextGetTextMatrix` et
`CGContextSetTextMatrix`. L’appel modifie la fonte, la taille de texte et la
matrice de texte ; cette dernière ne fait pas partie des paramètres
documentés de l’état graphique sauvegardé. Voir le [contrat de dessin Apple](https://developer.apple.com/documentation/coretext/ctfontdrawglyphs(_:_:_:_:_:))
et les [paramètres sauvegardés](https://developer.apple.com/documentation/coregraphics/cgcontext/savegstate()).
Aucune identité pixel par pixel n’est garantie entre rendu de plateforme et
rasterisation d’une représentation portable.

L’[exemple Kotlin complet du guide anglais](https://graphiks-org.github.io/Kalligraphie/platform-font-access.html#drawing-geometry-belongs-to-the-consumer)
utilise les bindings applicatifs (liaisons aux fonctions C), non exportés par
Kalligraphie, avec les véritables `openLayoutHandle`, `retainFontAsset`,
`acquirePlatformFontLease`, `fontRef` et `close`. Les buffers `CGGlyph` et
`CGPoint` restent vivants pendant l’appel. Le propriétaire conserve le lease
ouvert durant tout le dessin et ferme chaque ressource dans `finally`.

La CTM (matrice de transformation courante) du contexte doit déjà convertir
les coordonnées physiques de mise en page vers le périphérique, zoom compris
une seule fois. Par exemple, une échelle `0.1` convertit dix unités en un pixel.
Le dessin applique
`deviceFromLayout * translate(origin) * glyphTransform * flipGlyphY` :
translation par l’origine finale, transformation visuelle du glyphe, puis
inversion locale de y pour passer de x-droite/y-bas à x-droite/y-haut CoreText.
Ne pas inverser l’origine ni multiplier à nouveau par la taille de fonte.
Les origines de `LineLayout.positionedGlyphRuns` et des fragments sont déjà
traduites dans le paragraphe : ne jamais ajouter `line.baseline` une seconde
fois. Celles de `EditableLine.positionedGlyphRuns` sont relatives à la baseline
(ligne de base) et exigent exactement une translation vers le paragraphe.

Pour le A Liberation Sans audité à taille 2048, origine `(100,950)`, échelle
`0.1` et translation périphérique `(20,0)`, le point intérieur de barre
`(686,480)` devient `(98.6,47)` et le point vide du contreforme `(686,800)`
devient `(98.6,15)`. Ces points audités indépendamment vérifient le placement
et les axes sans exiger une égalité de pixels entre moteurs de rendu.

## Réouverture et identité immuable

Conserver une clé lorsque la réouverture ultérieure est nécessaire, sans la
considérer comme un localisateur universel de fonte. La réouverture exige un
résolveur vivant du fournisseur et de la génération adaptés exacts, avec le
même profil, la variante complète, le contrat de liaison et l’interprétation
du runtime capturée (environnement d’exécution de la plateforme).

Le token de réouverture de plateforme (jeton opaque) n’est pas une adresse mémoire.
La réouverture crée une fonte sémantiquement équivalente pour la clé exacte,
sans garantir le même pointeur. Les identités sémantiques portables peuvent
partager un contenu source égal entre générations ; les identités de plateforme
conservent leur fournisseur/génération et leur contexte de liaison/runtime.

## Annulation et échecs

Les surcharges d’acquisition et de `reopen` avec token préservent le
comportement `CancellationToken.none` des signatures historiques. Le pipeline
de mise en page et `openLayoutHandle(resolver, cancellationToken)` transmettent
leur token à la préparation de plateforme. Les contrôles ont lieu avant le travail,
entre les créations natives, entre les nouveaux identifiants validés et avant
transfert de propriété. Un appel C natif n’est pas forcément interruptible
pendant son exécution ; les contrôles reprennent à son retour. Un échec ou une
annulation ne transfère ni propriétaire ni certificat partiel. Le nettoyage
est inconditionnel et non annulable.

Une incompatibilité de fonte/profil/géométrie/variante de plateforme permet d’essayer
une alternative ultérieure explicitement acceptée. Une ressource fermée,
mauvaise génération/contexte, estimation obligatoire indisponible, limite
d’accès/opération, annulation, erreur de chargement de bibliothèque/symbole ou
échec d’allocation/création native arrête l’opération. Un constructeur natif
retournant `NULL` est un échec opérationnel, pas un glyphe vide.

Les surcharges coopératives par défaut appellent l’opération historique.
La délégation Kotlin `FontAssetResolverHandle by delegate` et
`FontInstance by delegate` délègue toutefois chaque surcharge indépendamment
après compilation contre l’interface actualisée. Un décorateur personnalisant
`reopen` ou `acquireRenderAsset` doit aussi redéfinir la surcharge avec token
effectivement appelée et transmettre le token reçu. Redéfinir uniquement la
surcharge historique n’intercepte pas sa contrepartie déléguée avec token.

## Octets contrôlés, pas mémoire du processus

`FontInstance.estimateOpenTypeDataCopy()` annonce la taille source immuable
exacte et une borne prudente des allocations de copie possédées par le
fournisseur, conteneur de résultat compris. Une borne obligatoire inconnue
entraîne un refus avant copie ; inconnu ne signifie pas zéro.

`maxSourceBytesPerFace` borne chaque source capturée.
`maxCapturedSourceBytes` borne le total des nouvelles sources capturées.
`maxTransientOwnedBytes` admet les buffers simultanés contrôlés (zones mémoire)
de copie et de création native entre les résolveurs issus de ce snapshot
(instantané immuable). Les réservations sont libérées à la réussite, à
l’échec ou à l’annulation. Les erreurs structurées identifient la phase,
la dimension, le maximum et la charge observée.

Acquisition normale et réouverture directe appliquent l’admission de la
liaison ; une limite d’octets facultative illimitée du pool d’opération
(ensemble des ressources réutilisées) ne la contourne pas. Les limites
d’opération existantes bornent aussi les ressources vivantes possédées par
la composition. Détacher ou emprunter partage le contexte existant sans
recopier les octets ni créer une nouvelle fonte.

Ces charges excluent le catalogue initial possédé par l’appelant, le surcoût
des objets JVM, la collecte mémoire différée et les allocations privées du
système. Elles ne bornent pas la RSS (mémoire physique résidente) du processus.
Les propriétaires retenus par le consommateur exigent une fermeture explicite et
ne sont pas des entrées de cache évincées par le moteur.

## Rétention partagée des contextes

La rétention de contextes CoreText est désactivée par défaut. Une politique locale
activée sans domaine garde un budget privé par capture. Pour cumuler représentations
portables et contextes éligibles, injectez un seul domaine dans les deux captures :

```kotlin
val budget = FontCacheBudget(16L * 1024 * 1024, 1_000_000, 8L * 1024 * 1024, 32)
val policy = FontMaterializationCachePolicy(budget, budget)
val scope = Kalligraphie.fontCacheScope(budget)
val portable = success(Kalligraphie.embedded(bytes, provenance, policy, scope))
val native = success(CoreTextFontCatalog.capture(
    portable, accessPolicy, cachePolicy = policy, cacheScope = scope,
))
```

L'adaptation ne change ni la politique ni le domaine du catalogue portable. Toutes
les dimensions par domaine, capture et face doivent tenir simultanément. Seule la
route TrueType statique monochrome existante, avec géométrie et variante par défaut,
est conservée ; aucun format, provider (fournisseur) ou moteur n'est ajouté. Les
caches indépendants des fournisseurs personnalisés sont exclus. Chaque contexte
compte une enveloppe gérée prudente de 4096 octets, plus 1024 octets de métadonnées
fixes de clé/propriétaire et les chaînes variables, N octets pour la copie CFData
connue de la source, zéro pixel décodé et quatre unités natives : CFData,
CGDataProvider, CGFont, CTFont. Ces unités dénombrent les ressources explicites,
pas les appels d'allocation internes ni la mémoire inconnue des frameworks
(bibliothèques système). L'admission temporaire de création reste indépendante.

Les charges actives, réservées, en cours de libération et résiduelles comptent à
chaque niveau. Une référence évincée reste facturée jusqu'à son abandon confirmé ;
un nettoyage partiel ou échoué conserve prudemment toute sa charge résiduelle. Après
l'abandon de la référence de cache, les ressources indépendantes du consommateur
deviennent de la mémoire externe, même si elles gardent le contexte physique vivant.
Sources capturées, propriétaires exclusivement consommateurs, surcoût JVM, délai
du GC (ramasse-miettes) et mémoire privée du système sont exclus ; une libération
de cache en cours ne l'est jamais.

La fermeture du dernier résolveur, après drainage de ses opérations admises, libère
les références de cache de sa capture. Une réouverture préserve son identité et ses
charges en attente. Un défaut de nettoyage propre au cache ne transforme pas une
acquisition typographique réussie en échec : la première fermeture et les suivantes
rapportent le premier défaut connu sous une forme bornée, y compris un défaut appris
pendant un drainage différé. Un échec ou une annulation de fermeture portable reste
primaire et reçoit les diagnostics du cache. Les fermetures répétées ne retentent
jamais la libération de cette référence.

`scope.close()` désactive la rétention et libère les références hors coordination.
Il rapporte les défauts connus et peut retourner avant la fin d'un nettoyage
concurrent. Les propriétaires existants, acquisitions suivantes et nouvelles
captures utilisant ce domaine fermé restent utilisables sans cache privé de
remplacement. Fermez résolveurs/domaines hors du chemin critique de rendu : le
drainage peut libérer toutes les entrées et la latence native n'est pas universellement
bornée. Fermez chaque ressource, propriétaire détaché et lease (droit temporaire de
durée de vie) indépendant selon son contrat habituel.
Un ordre de fermeture pratique consiste à fermer chaque résolveur à la fin de son
travail d'acquisition, puis le domaine partagé à la fin de la réutilisation, tous
deux hors du chemin de rendu. Les ressources et leases de rendu différé peuvent
rester utilisables et être fermés indépendamment plus tard.

Les signatures JVM de capture/constructeur modifiées exigent une recompilation ; les
appels Kotlin ordinaires retrouvent leurs valeurs par défaut après recompilation.
Les déclarations d'assemblage internes ne proposent pas de SPI (interface d'extension)
publique de cache personnalisé. La
[mesure facultative](glyph-materialization-measurement.fr.md#retention-partagee-et-propriete-native)
consigne séparément maxima instantanés, travail d'admission borné et propriété des
ressources physiques ; elle ne promet pas une latence de rendu universelle.
