# Accès natif aux fontes

Kalligraphie peut certifier une route CoreText explicitement acceptée tout en
conservant l’analyse du texte, le shaping (transformation du texte en glyphes),
le repli entre fontes, le positionnement et la géométrie d’édition dans son
pipeline (chaîne de traitement) portable. L’application garde son document et son renderer (moteur
de rendu). L’accès natif ne remplace pas un `GlyphRun` par une mise en page
de plateforme et ne dessine aucun pixel à sa place.

## Module Apple facultatif

Ajouter `:kalligraphie:platform:apple` au module principal `:kalligraphie`.
Sa coordonnée de publication est `org.graphiks:kalligraphie-platform-apple` ;
un consommateur Kotlin Multiplatform sélectionne la variante JVM (machine
virtuelle Java). Cette route exige macOS 15 ou ultérieur, une JVM x64 ou
arm64 et JDK 25. Lancer la JVM applicative avec
`--enable-native-access=ALL-UNNAMED` pour les appels natifs.

Voir la [référence API Apple](api/kalligraphie/platform/apple/org.graphiks.kalligraphie.platform.apple/index.md)
pour les contrats du catalogue, des limites et des propriétaires natifs.

L’artefact principal ne dépend pas de ce module et ne charge aucun framework
Apple (bibliothèque de plateforme). L’API commune ne transporte que des
identités de route et des contrats de propriété, jamais des pointeurs CoreText
ou des types kffi. Le module facultatif utilise kffi en interne et ne charge
que la surface native nécessaire à l’accès aux fontes.

La dépendance kffi interne est épinglée à
`org.graphiks:kffi-jvm:1.0.0-20260913.233427-53`, pas à un snapshot mobile
(version de développement dont le contenu peut changer). Sa résolution exige
le dépôt de développement Central Portal, filtré pour cet artefact. Le
SHA-256 exact du JAR (archive Java) est
`11508ebc6e06de32fc9dbe3e9e745c9e0b6d66e2abd177837113f5406a1e1a59`.
Le build (construction) vérifie les sommes de contrôle des dépendances. Un
artefact de développement horodaté peut être supprimé ultérieurement par son
dépôt : ce pin (épinglage) ne garantit pas sa disponibilité permanente et
n’affirme pas l’existence d’une version stable de kffi.

Ajouter ce dépôt au `settings.gradle.kts` du consommateur, à côté de ses
dépôts Maven habituels :

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            content { includeModule("org.graphiks", "kffi-jvm") }
        }
    }
}
```

Le filtre de dépôt ne rend pas l’artefact de développement immuable ;
conserver la vérification des dépendances lors de sa résolution.

## Capturer une source exacte

`CoreTextFontCatalog.capture(portable, policy, cancellationToken)` adapte
un catalogue portable dont les octets OpenType sont accessibles et immuables,
avec une estimation fiable des copies avant leur exécution. Les identifiants
de face, les métadonnées et les `FontInstanceKey` complets restent identiques,
y compris la taille et la géométrie. Les correspondances caractères/glyphes,
les métriques et l’interprétation du shaping portable sont conservées.

La fonte native est construite depuis ces octets exacts via `CGFont` puis
`CTFont`, jamais par une recherche de nom de famille. Cela n’autorise ni
substitution de fonte par la plateforme, ni fallback caché (repli implicite),
ni nouvelle correspondance entre les caractères et les glyphes finaux.

L’éligibilité native est volontairement restrictive : TrueType statique,
monochrome, à face unique, géométrie et variante de rendu par défaut.
Les collections, CFF/CFF2, données de variation, gras/italique synthétiques,
tables couleur ou bitmap (images matricielles) et variantes visuelles non
canoniques sont exclues de cette route. Les faces incompatibles avec le natif
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

## Négocier l’accès natif ou portable

Le catalogue adapté expose son `nativeProfile` exact. L’inclure dans
`FontAccessRequirementsSnapshot.renderable(...)` uniquement si le
consommateur sait utiliser ce bridge (liaison avec la plateforme). Les profils
ordonnés expriment une préférence, pas l’autorisation de masquer une
annulation ou un échec d’allocation par une route moins coûteuse.

Pour accepter l’accès natif puis un contour portable :

```kotlin
val requirements = FontAccessRequirementsSnapshot.renderable(
    acceptedProfiles = listOf(catalog.nativeProfile, outlineProfile),
)
```

Ici `catalog` est le catalogue adapté obtenu avec succès et `outlineProfile`
est le profil portable compris par le consommateur. Fournir
`portableDataRequired = true` lorsqu’un contour, un graphe de peinture ou un
bitmap portable est réellement nécessaire. Les profils natifs sont alors
exclus avant négociation : un certificat natif n’est pas une représentation
portable de glyphe.

Utiliser ensemble le résolveur adapté et la génération du catalogue adapté
dans les demandes de layout (mise en page). Les ressources portables acquises
via ce résolveur exposent sa génération publique tout en déléguant à des
ressources sous-jacentes possédées indépendamment.

## Certifier les glyphes finaux

Le moteur de shaping portable et la mise en page produisent les identifiants et
placements finaux. La certification native valide le contexte exact de fonte,
puis chaque nouvel identifiant final distinct dans la plage vérifiée des
glyphes natifs et du type `CGGlyph`. Cela comprend ligatures, substitutions et
glyphes dérivés par la mise en page, notamment le tiret visible à une coupure.
Les caractères sources ne sont pas remappés.

Le glyphe zéro et un glyphe sans encre peuvent être des identifiants natifs
valides. La politique existante des caractères manquants détermine toujours
leur présence dans le texte composé. Un identifiant hors plage est refusé,
jamais transformé en fausse représentation vide. La certification ne génère
aucun contour, ne rasterise pas (ne convertit pas en pixels) et ne dessine pas.

Un certificat `NATIVE_HANDLE` porte la clé effectivement émise par le
fournisseur. Il garantit l’acquisition de la route native correspondante tant
que la ressource est vivante, sous réserve d’échecs opérationnels distincts.
`resolveGlyph` portable sur une ressource exclusivement native retourne une
incompatibilité de route typée, pas un faux contour.

## Posséder la durée de vie native

La valeur de mise en page immuable et ses clés ne possèdent aucune ressource
de fonte. Ouvrir un `LayoutHandle` tant que son résolveur correspondant est
vivant, puis retenir le certificat publié exact via
`retainFontAsset(certificate)`. La ressource retournée est un propriétaire
indépendant, capable d’en détacher un autre.

`JvmEditableParagraphFacade.layout` possède son backend de shaping (moteur
sous-jacent) utilisé et le ferme avant publication du résultat. Le paragraphe
immuable publié ne conserve pas ce moteur ; son résolveur correspondant doit
toutefois rester vivant pour ouvrir un propriétaire de mise en page.

Un `NativeFontRenderAssetHandle` acquiert un `NativeFontLease` (propriétaire
natif indépendant). Pour cette liaison, le propriétaire de plateforme est
un `CoreTextFontLease` ; `fontRef()` retourne le pointeur CoreText utilisable
pendant sa durée de vie. Traiter ces opérations comme des
`FontOperationResult`, avec leurs annulations et échecs typés.

Fermer la ressource, le résolveur ou le propriétaire de mise en page initial
n’invalide pas un enfant indépendant déjà admis. Cet enfant peut être utilisé
depuis un autre thread (fil d’exécution) et doit lui-même être fermé. Un
propriétaire fermé refuse les nouvelles acquisitions. La fermeture est
idempotente et n’attend pas les enfants admis ; le dernier propriétaire ou
opération libère le contexte natif sous-jacent.

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
Aucune identité pixel par pixel n’est garantie entre rendu natif et
rasterisation d’une représentation portable.

L’[exemple Kotlin complet du guide anglais](https://graphiks-org.github.io/Kalligraphie/native-font-access.html#drawing-geometry-belongs-to-the-consumer)
utilise les bindings applicatifs (liaisons aux fonctions C), non exportés par
Kalligraphie, avec les véritables `openLayoutHandle`, `retainFontAsset`,
`acquireNativeFontLease`, `fontRef` et `close`. Les buffers `CGGlyph` et
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
du runtime capturée (environnement natif d’exécution).

Le token de réouverture natif (jeton opaque) n’est pas une adresse mémoire.
La réouverture crée une fonte sémantiquement équivalente pour la clé exacte,
sans garantir le même pointeur. Les identités sémantiques portables peuvent
partager un contenu source égal entre générations ; les identités natives
conservent leur fournisseur/génération et leur contexte de liaison/runtime.

## Annulation et échecs

Les surcharges d’acquisition et de `reopen` avec token préservent le
comportement `CancellationToken.none` des signatures historiques. Le pipeline
de mise en page et `openLayoutHandle(resolver, cancellationToken)` transmettent
leur token à la préparation native. Les contrôles ont lieu avant le travail,
entre les créations natives, entre les nouveaux identifiants validés et avant
transfert de propriété. Un appel C natif n’est pas forcément interruptible
pendant son exécution ; les contrôles reprennent à son retour. Un échec ou une
annulation ne transfère ni propriétaire ni certificat partiel. Le nettoyage
est inconditionnel et non annulable.

Une incompatibilité de fonte/profil/géométrie/variante native permet d’essayer
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
Le fournisseur ne conserve aucun cache (mémoire de réutilisation) de fontes natives inactives. Les
propriétaires retenus par le consommateur exigent une fermeture explicite et
ne sont pas des entrées de cache évincées par le moteur. Une référence
complète de performance et un budget de cache natif partagé sont des
capacités distinctes, pas des garanties de cette route d’accès.
