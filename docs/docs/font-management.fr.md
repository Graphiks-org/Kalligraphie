# Gestion des fontes

Kalligraphie propose des fontes TrueType embarquées ou capturées dans des répertoires via
`org.graphiks:kalligraphie`, uniquement sur la cible de référence de la
machine virtuelle Java (JVM). Les contrats publics restent portables, mais
cette prise en charge exécutable est limitée à la JVM. L’utilisateur de la
bibliothèque fournit des octets SFNT capturés à `Kalligraphie.embedded(...)`,
sélectionne un enregistrement de face stable, crée une instance de fonte, puis utilise une
ressource de rendu pour matérialiser une représentation portable de glyphe.

Le module facultatif d’[accès aux fontes via la plateforme](platform-font-access.md) ajoute
une route CoreText explicite sur les JVM macOS prises en charge sans modifier
le shaping (transformation du texte en glyphes) ni la géométrie d’édition
portables. Le consommateur possède la durée de vie des ressources de plateforme et le rendu.

Le périmètre fonctionnel supporté est volontairement étroit :

- cible JVM de référence uniquement ;
- fontes TrueType SFNT statiques uniquement : `0x00010000` et `true` ;
- des sources OpenType embarquées à face unique, d’index `0`, et des collections
  TTC version 1 ou 2 capturées dans des répertoires, avec leurs indices d’origine ;
- `LAYOUT_ONLY` pour la table `cmap` (correspondance entre caractères et
  glyphes) et les métriques ;
- `RENDERABLE` avec un `OutlineProfile`, un `PaintGraphProfile` ou un
  `BitmapProfile` explicite, si la face sélectionnée déclare la route
  correspondante ;
- contours `glyf` exprimés en unités de conception (unités internes de la
  fonte), avec des métriques mises à l’échelle séparément en `LayoutUnit` ;
- `paint graph` (graphe de peinture portable) de schéma 1 pour COLR version 0 /
  CPAL version 0, composé de contours pleins et de groupes ordonnés, ainsi
  qu’un graphe de schéma 2 ou 3 pour COLR version 1 statique avec CPAL version
  0 ou 1 ;
- table SVG-in-OpenType version 0 avec documents UTF-8 bruts ou avec un
  transport gzip mono-membre (encodage compressé du document) :
  conteneurs `svg` et `g` ; éléments `path` (chemins) auto-fermants avec les
  commandes `M`, `L`, `H`, `V`, `C`, `S` et `Z` ; et sous-ensemble statique
  `defs` (définitions), `linearGradient` (gradient linéaire), `radialGradient`
  (gradient radial) et `stop` (arrêt de couleur). Les chemins et rectangles
  acceptent un remplissage `#RRGGBB` ou `fill="none"`, avec un `fill-opacity`
  facultatif, et peuvent référencer un gradient linéaire ou radial concentrique
  local antérieur dans les espaces de coordonnées décrits plus bas. Ils peuvent
  aussi référencer un `clipPath` (définition de découpe) borné en
  `userSpaceOnUse`, avec un unique enfant chemin ou rectangle à angles vifs.
  Les transformations
  `translate`, `scale`, `rotate`, `skewX`, `skewY` et `matrix` (matrice affine
  à six coefficients) sont prises en charge. Le sous-ensemble exact des
  gradients et les exclusions restantes sont décrits ci-dessous ;
- strikes (tailles matricielles) bitmap schéma version 2, identifiés par leur
  couple exact de pixels par em (ppem) et leur profondeur de bits, jamais par
  une taille voisine : voir la
  [matrice des formats bitmap](#matrice-des-formats-bitmap) ;
- bornes de ressources bitmap rapportées par dimension via
  `BitmapResourceLimit` et `FontError.BitmapResourceLimitExceeded`, avec
  dimensions déclarées vérifiées avant toute allocation de pixels et, pour les
  images compressées, avant inflation, et aucun pixel partiel publié ;
- ressources de rendu détachées qui restent utilisables après la fermeture du
  gestionnaire propriétaire ou de la ressource attachée.

## Matrice des formats bitmap

Les strikes (tailles matricielles) bitmap de schéma version 2 sont identifiés
par leur couple exact de pixels par em (ppem) et leur profondeur de bits, jamais
par une taille voisine, via trois routes :

| Route / tables | Versions et enregistrements acceptés | Sélection | Pixels décodés | Exclusions |
| --- | --- | --- | --- | --- |
| `EBLC` / `EBDT` | version 2.0 ; sous-table d’index format 1 ; format d’image 1 | `(ppemX, ppemY, 1)` exact | `ALPHA_8` monochrome un bit aligné sur les octets en sRGB | formats d’index autres que 1 ; formats d’image autres que 1 |
| `CBLC` / `CBDT` | versions 2.0 ou 3.0, vérifiées indépendamment pour chaque table (un couple mixte est accepté délibérément) ; sous-table d’index format 1 ; formats d’image 17 ou 18 ; métriques horizontales | `(ppemX, ppemY, 32)` exact | sous-ensemble borné de PNG (Portable Network Graphics, format d’image sans perte) décodé en `RGBA_8888` droit non prémultiplié en sRGB ; les métriques doivent correspondre aux dimensions de l’image embarquée | données BGRA 32 bits non compressées ; format d’image 19 ; strikes verticaux |
| `sbix` (« table bitmap d’Apple ») | version 1 uniquement (bit 0 de `flags` défini, bits réservés à zéro) ; graphiques `'png '` uniquement ; les enregistrements `'dupe'` se résolvent vers l’image du glyphe référencé dans le même strike (chaque enregistrement `'dupe'` conserve sa propre origine) | `ppem` exact (le champ `resolution` (`ppi`) est ignoré pour la sélection) | sous-ensemble PNG borné décodé en `RGBA_8888` droit non prémultiplié en sRGB ; origines et avances normalisées des unités de conception vers les pixels du strike, avec un arrondi des demi-valeurs en s’éloignant de zéro ; les avances viennent de `hhea`/`hmtx` (aucune exigence de `glyf`) | `'jpg '` ; `'tiff'` ; `'pdf '` ; `'mask'` |

Les pixels décodés sont des RGBA droits (non prémultipliés) en sRGB, avec les
octets ordonnés R, G, B, A, les lignes du haut vers le bas, sans remplissage
(padding), et exactement `width × height × 4` octets. `ALPHA_8` est un canal
alpha d’un octet par pixel. Le décodeur n’effectue aucune prémultiplication.

Le décodage PNG accepte les images truecolor (couleur directe) 8 bits (type de
couleur 2) et truecolor avec alpha 8 bits (type 6), non entrelacées, avec des
méthodes de compression et de filtre nulles, et vérifie la somme de contrôle
CRC de chaque chunk. Un chunk `PLTE` suggéré est toléré et les chunks
auxiliaires sont ignorés ; les images à palette, en niveaux de gris, 16 bits et
entrelacées sont refusées. Les dimensions déclarées sont validées par rapport
au profil avant toute inflation, et le flux d’inflation est plafonné au total
exact de lignes déclaré, de sorte qu’une bombe de décompression est refusée
avant toute allocation de pixels. PNG n’est jamais exposé au consommateur.

La priorité des routes couleur est déterministe et s’applique à toute la face :
lorsqu’une face certifie les deux routes couleur, CBDT/CBLC est choisie de
façon déterministe et son échec est définitif. Il n’existe aucun repli entre
routes, car une autre route constitue une autre image, pas une autre taille.

La découverte des capacités bitmap est conservatrice et porte sur toute la
face : une face ne déclare une route que si chaque strike déclaré de cette
route est structurellement valide ; un seul strike malformé, même non
sélectionné, retire la route. Les budgets de balayage des capacités pour les
octets compressés et décodés s’accumulent sur tous les strikes déclarés, tandis
que la matérialisation applique des budgets par strike. Les déclarations en
double ne fournissent jamais de départage implicite : le prédicat de capacité
retire la route lorsqu’un strike déclaré se répète, et la lecture d’un strike
demandé déclaré deux fois échoue comme donnée invalide avec
`font.eblc.duplicate-strike`, `font.cblc.duplicate-strike` ou
`font.sbix.duplicate-strike`.

Un glyphe sans enregistrement dans le strike sélectionné échoue avec
`font.glyph-representation-unavailable`. Un enregistrement validé dont les
pixels décodés sont tous nuls constitue un résultat vide légitime
(`GlyphRepresentation.Empty`), jamais une erreur ; l’absence d’enregistrement
n’est jamais traitée comme une absence d’encre.

Aucune autre table ni aucun autre format d’enregistrement bitmap n’est reconnu.

## Exemple : un strike bitmap exact

À partir d’une valeur `BitmapLimits` fournie par l’application, un consommateur
déclare un strike monochrome exact et obtient les exigences de rendu avec
`FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile))` :

```kotlin
val bitmapProfile = BitmapProfile(
    strike = BitmapStrike(pixelsPerEmX = 16, pixelsPerEmY = 16, bitDepth = 1),
    acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
    acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
    limits = bitmapLimits,
)
```

Seul le strike exact déclaré est certifié : une face dépourvue de ce strike est
refusée avec `font.unsupported-representation-profile`, et un glyphe sans
enregistrement source dans le strike sélectionné est refusé avec
`font.glyph-representation-unavailable` ; aucune taille voisine n’est jamais
substituée. Tout dépassement d’une borne déclarée échoue avec
`font.bitmap-resource-limit-exceeded`, et cet échec est terminal : la résolution
appelante s’arrête.

Un strike couleur utilise la même forme de profil avec
`BitmapStrike(pixelsPerEmX = 16, pixelsPerEmY = 16, bitDepth = 32)` et
`BitmapPixelFormat.RGBA_8888` ; un strike 32 bits est certifié par la route
CBDT/CBLC ou sbix, sélectionnée selon la [priorité documentée](#matrice-des-formats-bitmap), toujours sans
substitution par une taille voisine.

## Capturer des répertoires de fontes sur la JVM

`FontDirectoryCatalog.open(options, cancellationToken)` capture les fichiers
lisibles de racines explicites. Les fournisseurs Linux et macOS appliquent les
mêmes règles dans des domaines distincts :

```kotlin
import org.graphiks.kalligraphie.FontDirectoryCatalog
import org.graphiks.kalligraphie.FontDirectoryCatalogOptions
import org.graphiks.kalligraphie.LinuxSystemFontCatalog
import org.graphiks.kalligraphie.MacosSystemFontCatalog
import org.graphiks.kalligraphie.MacosSystemFontCatalogOptions
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult

fun requireCapture(result: FontOperationResult<FontCatalogSnapshot>): FontCatalogSnapshot =
    when (result) {
        is FontOperationResult.Success -> {
            result.diagnostics.forEach { println(it) }
            result.value
        }
        is FontOperationResult.Failure -> {
            result.diagnostics.forEach { println(it) }
            error("Échec de capture : ${result.error}")
        }
        is FontOperationResult.Cancelled -> {
            result.diagnostics.forEach { println(it) }
            error("Capture annulée")
        }
    }

val options = FontDirectoryCatalogOptions(
    roots = listOf("/usr/share/fonts"),
    maxPathsToVisit = 512,
    maxFaces = 32,
    maxFacesToExamine = 128,
    maxSourceBytes = 16 * 1024 * 1024,
    maxTotalSourceBytes = 64 * 1024 * 1024,
    maxDiagnostics = 64,
)
val explicit = requireCapture(FontDirectoryCatalog.open(options))
// Sur Linux ; sans options, utilise les racines système, utilisateur et XDG.
val linux = requireCapture(LinuxSystemFontCatalog.open(options = options))
// Autre possibilité sur macOS ; sans options, utilise les racines standard.
val macos = requireCapture(MacosSystemFontCatalog.open(
    options = MacosSystemFontCatalogOptions(roots = listOf("/Library/Fonts")),
))
```

Les appels propres à un OS sont des alternatives : chacun retourne un échec
typé de prise en charge sur un autre OS. Chaque `open` accepte aussi un
`CancellationToken` ; l’annulation est coopérative entre opérations du système
de fichiers et n’interrompt pas un appel OS bloqué. Une annulation ne publie
aucun snapshot (instantané immuable) partiel. Les options invalides, comme des
limites non positives ou des racines répétées, sont refusées à la construction.

La découverte considère `.ttf`, `.otf`, `.ttc` et `.otc` sans suivre les liens
symboliques. L’extension `.otf` n’implique pas des contours CFF : le contenu SFNT
détermine la prise en charge. Le TrueType statique est supporté ; CFF/CFF2 et
les données de fontes variables sont exclus de cette route. Les candidats
capturés sont ordonnés lexicalement, puis par indice d’origine dans chaque
collection. La découverte est bornée et peut omettre des candidats ; elle ne
reproduit pas exactement les fontes activées par Fontconfig ou CoreText. La
capture du système de fichiers n’est pas globalement atomique.

Examiner les diagnostics même sur `Success` : racines absentes ou illisibles,
sources ou faces refusées et limites atteintes peuvent laisser un inventaire
partiel utilisable. Sans face acceptée, l’opération retourne `Failure`.
`maxDiagnostics` borne les diagnostics retournés ;
`font.capture.diagnostics-truncated` est inclus dans cette limite lorsque des
détails sont omis. `maxPathsToVisit` compte les chemins inspectés, racines comprises ;
`maxFacesToExamine` compte les répertoires de faces examinés, refus compris ;
`maxFaces` limite les faces acceptées après examen de la source. Ces limites ont des sens distincts.

Chaque collection retenue doit pouvoir faire examiner toutes ses faces dans le
budget `maxFacesToExamine` restant. Une source TTC/OTC dépassant ce budget est
refusée entièrement avec `ResourceLimitExceeded` et un diagnostic avant tout
examen de ses répertoires de faces ; aucun préfixe de collection partiellement
examinée n’est admis. Une collection valide à deux faces exige donc au moins
deux places d’examen restantes, même avec `maxFaces` égal à un. Ce refus fondé
sur le nombre de faces ne consomme aucune place d’examen : des sources distinctes
qui tiennent dans le budget peuvent encore former un catalogue partiel utilisable.

Les en-têtes de collection et les plages de tous leurs répertoires de faces doivent
être adressables sans débordement. Un répertoire tenté non sûr fait refuser tout
son conteneur d’origine, avec des diagnostics d’offset (décalage dans les octets)
numériques ; cette tentative compte dans le budget d’examen. Des faces voisines
adressables mais incompatibles ou aux métadonnées invalides peuvent être exclues
individuellement ; les faces retenues gardent leur indice d’origine. Après examen
complet de la source, la limite distincte `maxFaces` peut omettre des faces acceptées.
Ces contrôles empêchent l’admission d’un conteneur dont des répertoires n’ont pas
été examinés ; ils ne prétendent pas reproduire le container sanitizer (validateur
de sécurité du conteneur) de HarfBuzz pour toute table non prise en charge.

`FontFaceId.source` identifie le conteneur capturé d’origine et `faceIndex`
sélectionne sa face. `copyOpenTypeData()` retourne les octets du conteneur
d’origine et l’identité de face sélectionnée : aucune extraction de fonte à
face unique, réécriture ou renumérotation n’est effectuée. Les faces voisines
partagent la source retenue. `maxSourceBytes` borne chaque conteneur lu ;
`maxTotalSourceBytes` compte une fois chaque conteneur unique accepté. Ces
budgets ne plafonnent pas la mémoire du processus : copies défensives, lectures
temporaires, métadonnées et mémoire de décodage sont exclues. Utiliser
`estimateOpenTypeDataCopy()` pour estimer les allocations contrôlées avant copie.
La rétention des représentations portables relève séparément de
`materializationCachePolicy` et de `cacheScope`, facultatif : le cache (mémoire
de rétention réutilisable) fermé permet encore des opérations sans rétention.

Rafraîchir explicitement avec un nouvel `open` après installation ou suppression :

```kotlin
val refreshed = requireCapture(FontDirectoryCatalog.open(options))
// Utiliser refreshed.generation et un nouveau résolveur pour les nouvelles mises en page.
```

Chaque capture réussie possède une nouvelle génération, même à fichiers
identiques. Conserver les clés de layout (mise en page), résolveur et ressources
dans cette génération ; rouvrir une ancienne clé de ressource dans une nouvelle
génération échoue. Les instances capturées et ressources de rendu possédées
indépendamment gardent leurs données après remplacement ou suppression des
fichiers. Fermer chaque résolveur, propriétaire de mise en page et ressource de
rendu acquis ; fermer un propriétaire initial n’invalide pas ses enfants
indépendants déjà admis. Le snapshot lui-même n’a pas d’opération `close`.

Les appels Kotlin existants à `MacosSystemFontCatalogOptions` et
`MacosSystemFontCatalog.open` restent compatibles au niveau source grâce aux
paramètres finaux par défaut. Leurs signatures JVM ont changé : recompiler les
consommateurs des anciens constructeurs ou points d’entrée ; les appels déjà
compilés ne sont pas compatibles au niveau binaire.

### Transport et limites des documents SVG

La route SVG entièrement normalisée décode tous les documents lors de
l’acquisition d’une ressource de rendu, puis retient un IR (*intermediate
representation*, représentation intermédiaire) portable et immuable au lieu
des documents SVG encodés. Le transport est soit UTF-8 brut, soit un unique
membre gzip (encodage compressé du document) contenant de l’UTF-8 ; son décodage
utilise le streaming (traitement en flux) pendant l’application des limites.
Une ressource mixte SVG/COLR v1 retient au contraire, de manière privée, la
source SVG encodée et son index bornés, puis normalise tout le document
sélectionné lorsqu’un glyphe couvert est demandé. Les glyphes COLR non couverts
restent ainsi utilisables même si ce document SVG ne peut pas être décodé ou
normalisé. Les résultats publics réussis de `resolveGlyph(...)` n’exposent que
de la peinture portable immuable ou un glyphe vide, jamais le SVG encodé, XML,
gzip, une URI ou une ressource de moteur de rendu. Les ressources détachées
possèdent les données immuables nécessaires à leur route.

`PaintGraphLimits` applique les limites suivantes :

- `maxSourceBytes` borne la table SVG entière et le cumul des octets encodés
  des documents ;
- `maxSvgCompressedDocumentBytes` borne les octets encodés de chaque document
  gzip ;
- `maxSvgDecodedDocumentBytes` borne les octets UTF-8 décodés de chaque
  document brut ou gzip ;
- `maxSvgTotalDecodedBytes` borne le cumul des octets UTF-8 décodés lors d’une
  opération de normalisation : tous les enregistrements dans la route
  entièrement normalisée, ou tout le document sélectionné dans la route mixte.

Chaque limite dédiée à gzip ou au décodage vaut par défaut `maxSourceBytes`,
dont la valeur par défaut est 1 048 576 octets. Le dépassement de l’une de ces
limites retourne `FontError.ResourceLimitExceeded` pour la table `SVG `.
Les limites de toute la source encodée et de son index s’appliquent à
l’acquisition dans les deux routes. Les contrôles du décodage, de l’intégrité,
de l’UTF-8, du balisage et du graphe refusent l’acquisition dans la route
entièrement normalisée, ou la demande du glyphe couvert dans la route mixte ;
aucun résultat SVG partiel n’est publié et la certification des autres glyphes
COLR non couverts reste valide. Un en-tête gzip mal
formé, un membre non-DEFLATE, des drapeaux réservés dans l’en-tête gzip, un
échec des contrôles d’intégrité gzip, des membres gzip concaténés ou des octets
supplémentaires après un membre retournent `FontError.FontDataFailure` avec le
code `font.svg.invalid-gzip` au point de normalisation de la route concernée. Un UTF-8
invalide ou un SVG mal formé suit le contrat existant de
`FontDataFailure` typée, tandis qu’un balisage SVG hors du sous-ensemble sûr
retourne `FontError.UnsupportedRepresentationProfile`.

### Gradients SVG statiques sûrs

Le sous-ensemble accepté de serveurs de peinture comprend des éléments `defs`
qui contiennent des définitions `linearGradient` ou des définitions
`radialGradient` concentriques nommées, elles-mêmes composées d’éventuels
éléments `stop` auto-fermants. Un élément `rect`
auto-fermant peut utiliser un remplissage opaque `#RRGGBB`, `fill="none"` ou
une référence `url(#id)` vers un gradient unique défini plus tôt dans
le même document ; un remplissage absent utilise du noir opaque par défaut. Un
élément `path` auto-fermant pris en charge accepte les mêmes remplissages si le
gradient référencé déclare explicitement `userSpaceOnUse`. Les gradients
`objectBoundingBox` restent incompatibles avec les chemins, y compris lorsqu’ils
sont vides, unis, dégénérés ou utilisés sous une transformation singulière. Avec
`gradientUnits` absent ou explicitement égal à `objectBoundingBox` (boîte
englobante de l’objet), les valeurs sans unité et les pourcentages sont résolus
relativement au rectangle sans être bornés à sa boîte unité. Les valeurs
linéaires par défaut sont `x1=0%`, `y1=0%`,
`x2=100%` et `y2=0%`. Les valeurs radiales par défaut sont `cx=50%`, `cy=50%`,
`r=50%`, `fx=cx` et `fy=cy`.

`gradientUnits=userSpaceOnUse` (coordonnées absolues dans l’espace utilisateur)
est accepté uniquement avec des coordonnées finies, sans unité et indépendantes
du viewport (fenêtre de visualisation). Une définition linéaire doit fournir
explicitement `x1`, `y1`, `x2` et `y2`. Une définition radiale doit fournir
explicitement `cx`, `cy` et un `r` positif ou nul ; `fx` et `fy` absents héritent
des valeurs absolues acceptées de `cx` et `cy`, tandis qu’un foyer explicite doit
lui aussi être sans unité. Les pourcentages et les valeurs par défaut en
pourcentage sélectionnées par l’omission d’un attribut requis restent hors de
ce sous-ensemble borné, car ils dépendent du viewport et d’un éventuel `viewBox`.
Ils retournent `UnsupportedRepresentationProfile`, tout comme une valeur
`gradientUnits` inconnue ; un nombre absolu mal formé ou non fini et un rayon
radial négatif non nul retournent `FontDataFailure`. Les deux types utilisent
par défaut `spreadMethod=pad` et l’interpolation sRGB.

`spreadMethod` accepte les trois modes d’extension portables : `pad`, `repeat`
et `reflect` correspondent à `PAD`, `REPEAT` et `REFLECT`. Une valeur
`color-interpolation` absente ou égale à `sRGB` correspond à `SRGB` ;
`linearRGB` correspond à `LINEAR_SRGB`. Un arrêt accepte un `offset` (position
le long du gradient) conforme à la grammaire numérique SVG finie,
éventuellement en pourcentage, une couleur opaque `#RRGGBB` dans `stop-color`,
et une valeur `stop-opacity` conforme à la même grammaire, éventuellement en
pourcentage ; leurs valeurs par défaut respectives sont `0`, le noir opaque et
`1`. Cette grammaire stricte s’applique aussi aux coordonnées et dimensions
des rectangles et gradients. La notation exponentielle décimale est acceptée,
mais le point décimal doit être suivi d’au moins un chiffre et le signe de
pourcentage doit suivre immédiatement son nombre. Les formes `1.`, `1.e2` et
`50 %`, ainsi que les nombres hexadécimaux à virgule et suffixes `f`/`d`,
propres à Java, sont refusés. Les offsets et opacités sont bornés dans
`0.0..1.0`, puis les offsets
sont rendus non décroissants dans l’ordre du document. Pour une définition à
plusieurs arrêts en mode `repeat` ou `reflect` dont le premier ou le dernier
offset n’atteint pas `0` ou `1`, Kalligraphie insère aux extrémités une copie
de l’arrêt terminal correspondant. Les discontinuités à offsets égaux
conservent l’ordre source, et les arrêts insérés comptent dans `maxColorStops`
pour le graphe atteint.

Les attributs de groupe `transform`, les deux types de gradient et les
définitions bornées de découpe en espace utilisateur partagent les mêmes
opérations SVG prises en charge. Un gradient accepte un attribut
`gradientTransform` (transformation du repère du gradient) absent, vide ou
composé uniquement d’espaces XML comme identité. Sinon, sa `transform list`
(liste de transformations SVG) peut contenir `translate` (translation),
`scale` (mise à l’échelle), `rotate(angle)`, `rotate(angle cx cy)`,
`skewX(angle)`, `skewY(angle)` et `matrix`. Ce sont les six noms de fonction
de transformation SVG 1.1 acceptés par cette liste bornée.
Une matrice possède exactement six
coefficients
`matrix(a b c d e f)` et transforme un point selon
`x' = a*x + c*y + e`, `y' = b*x + d*y + f`. `translate` et `scale` acceptent
un ou deux opérandes, `rotate` en exige exactement un ou trois, tandis que
chaque inclinaison en exige exactement un et `matrix` exactement six.
`rotate(angle)` tourne autour de l’origine ;
`rotate(angle cx cy)` tourne autour du centre déclaré `(cx, cy)`, ce qui
équivaut à `translate(cx cy) rotate(angle) translate(-cx -cy)`, tout en restant
une seule opération déclarée. `skewX(angle)` conserve `y` et applique
`x' = x + tan(angle)*y` (coefficient affine `c`) ; `skewY(angle)` conserve `x`
et applique `y' = y + tan(angle)*x` (coefficient affine `b`). Les angles sont
exprimés en degrés SVG ; les angles finis négatifs ou ramenés par période sont
acceptés. Les rotations multiples exactes de 90 degrés sont canonicalisées en
matrices de quadrant stables. Pour une inclinaison, l’angle fini est réduit
selon la période de 180 degrés de la tangente avant la conversion en radians ;
le zéro exact et les angles équivalents à +45 ou -45 degrés sont ensuite
canonicalisés en coefficients `0`, `1` ou `-1`, sans résidu trigonométrique.
Chaque facteur de rotation ou d’inclinaison conserve une orientation de
déterminant positive. Entre les
fonctions, une ou plusieurs séquences SVG de virgule et/ou espaces sont
acceptées : des virgules répétées y sont donc valides, mais restent invalides
entre opérandes. Contrairement aux coordonnées ordinaires des rectangles et
gradients, cette grammaire propre aux transformations accepte notamment `1.`
et `1.e2`. Une échelle négative, finie et non nulle ou une matrice de
déterminant négatif produit une réflexion. Une transformation de gradient
singulière reste non prise en charge. Une syntaxe mal formée — notamment une
arité, un séparateur ou une unité invalide pour une rotation ou inclinaison, un
nom de fonction inconnu ou une liste partielle —, une valeur non finie ou une
composition hors du domaine numérique portable constitue une donnée invalide
et empêche la normalisation réussie des données SVG concernées. Les quarts de tour impairs
exacts (`90 + 180*k` degrés) sont des asymptotes d’inclinaison et sont donc
invalides. Un facteur d’échelle, un coefficient de matrice, un angle de
rotation ou d’inclinaison écrit comme non nul mais converti en zéro est
invalide, tout comme une tangente non finie ; les coordonnées du centre suivent
le comportement existant des coordonnées de translation. Une composition de
facteurs qui
préservent chacun l’aire mais dont le produit mémorisé la perd ou inverse
l’orientation du déterminant imposée par les facteurs est également invalide,
y compris entre groupes imbriqués. Une transformation de groupe explicitement
singulière reste valide, tandis qu’une transformation de gradient explicitement
singulière n’est pas prise en charge.

L’orientation du déterminant est déterminée exactement pour les coefficients
`Double` décodés en comparant `a*d` et `b*c`, sans seuil de tolérance, puis
propagée comme positive, négative ou singulière pendant la composition. Un
facteur connu comme singulier maintient toute la liste ou composition de
groupes imbriqués singulière, même si l’arrondi de ses coefficients mémorisés
semblait restaurer une aire.

Chaque appel complet d’une fonction de transformation consomme une opération
SVG déclarée, quel que soit son nombre d’opérandes ; chaque appel à un opérande
`skewX` ou `skewY` coûte donc exactement une opération. Les listes de groupe,
de gradient et de définition de découpe partagent ce budget, et le fallback
(repli) de profil recommence la validation sans publier de donnée partielle.

Avec des vecteurs-colonnes, une peinture en boîte englobante utilise
`T * B * G` : `T` est la transformation de groupe effective du rectangle, `B`
applique sa boîte englobante normalisée et `G` compose `gradientTransform` dans
l’ordre source. Une peinture absolue en espace utilisateur utilise à la place
`T * G`, indépendamment des bornes du rectangle. La transformation ne modifie
que la géométrie du gradient ; le chemin de découpe du rectangle reste soumis à
`T` seul. Un gradient linéaire incorpore la transformation choisie dans `p0`,
`p1` et `p2`, sans nœud `Transform` supplémentaire. Un gradient radial conserve
ses cercles normalisés ou absolus et place la transformation choisie sur son
unique nœud `Transform` existant.

L’espace de coordonnées et les coordonnées d’une définition sont entièrement
validés avant toute réduction vide ou unie : une entrée dépendante du viewport
ne peut donc pas être masquée par l’absence d’arrêts, un arrêt unique ou un
rayon nul. Une définition valide sans arrêt ne produit aucune encre. Pour un
rectangle ou chemin pris en charge dont la transformation de groupe
effective `T` préserve l’aire, un gradient linéaire à un seul arrêt ou un
vecteur source aux extrémités identiques est normalisé avec le dernier arrêt en
`Solid` (peinture unie), sous
le `PathClip` (découpe par chemin) de la forme. Pour tout autre gradient linéaire d’au
moins deux arrêts, Kalligraphie résout d’abord ses points normalisés `p0` et
`p1` ; s’ils coïncident, il applique la même réduction en peinture unie, sinon
il produit un `LinearGradient` (gradient linéaire) sous le chemin de la forme.
Avant cette production, les points normalisés `p0`, `p1` et `p2` doivent former
un triplet non colinéaire. Un triplet colinéaire retourne
`font.svg.invalid-gradient` et aucune ressource partielle n’est publiée. Une
transformation de groupe effective `T` singulière omet un rectangle ou chemin
rempli après validation de sa géométrie et de son remplissage. Pour un gradient
de chemin, la référence locale, l’espace de coordonnées, les capacités atteintes
et les limites projetées du graphe sont aussi validés avant cette omission.

Un rayon radial `r < 0` constitue une donnée invalide. Avec un seul arrêt ou
`r == 0`, le gradient est pareillement réduit au dernier arrêt sous forme de
`Solid` sous un `PathClip`. Avec `r > 0`, le foyer doit être exactement
concentrique après l’analyse numérique (`fx == cx` et `fy == cy`). Tout foyer
décalé est un SVG valide hors de ce sous-ensemble : il retourne
`UnsupportedRepresentationProfile` sans être ramené dans le cercle. Une
peinture radiale non dégénérée conserve ses deux cercles normalisés
(`c0=(fx,fy), radius0=0` et `c1=(cx,cy), radius1=r`) sous un nœud `Transform`
(transformation du repère enfant vers le repère parent). Cette transformation
porte `T * B * G` ; un radial absolu conserve les mêmes champs de cercle sans
mise à l’échelle sous `T * G`. Dans les deux cas, un `PathClip` contenant le
chemin de la forme, transformé uniquement par `T`, découpe le résultat. La forme
en boîte englobante est réservée aux rectangles et préserve l’ellipse produite
par un rectangle non carré.

Chaque gradient linéaire ou radial déclaré exige un `PaintGraphProfile` de schéma 3
exact. Lorsque la normalisation produit réellement un `LinearGradient`, le
profil doit accepter l’espace d’interpolation, le mode d’interpolation d’alpha
`UNPREMULTIPLIED` (non prémultipliée : les composantes RGB et l’alpha sont
interpolés séparément) et le mode d’extension atteints, ainsi que `LINEAR_GRADIENT`
et `PATH_CLIP`. Un `RadialGradient` produit exige les mêmes capacités
d’interpolation et d’extension, ainsi que `RADIAL_GRADIENT`, `TRANSFORM` et
`PATH_CLIP`. Une réduction en peinture unie exige à la place `SOLID` et
`PATH_CLIP`, mais pas l’espace d’interpolation ni le mode d’interpolation
d’alpha ou d’extension de la définition. Les rectangles et chemins unis opaques
utilisent `PATH` ; les peintures unies translucides utilisent la forme
`SOLID`/`PATH_CLIP` décrite plus bas. Un document qui possède plusieurs racines
peintes exige aussi `GROUP` et
`SOURCE_OVER`. Les limites existantes sont contrôlées
avant publication : les octets source et décodés, transformations, définitions
de gradient et arrêts analysés, ainsi que les nœuds, références, chemins,
découpes, gradients, arrêts de couleur et profondeurs produits doivent tous
respecter les bornes. Les visites de peinture sont également bornées à partir
du schéma 2 ; le schéma 1 conserve ses contrôles historiques des nœuds et de la
profondeur sans appliquer `maxPaintVisits`. Chaque chemin de forme créé
doit aussi respecter l’`outlineProfile` (profil de contours) du profil. Un
nœud `Transform` radial produit compte dans `maxTransforms`, indépendamment
des appels aux fonctions de transformation SVG déclarés par les groupes ou
les gradients. Chaque opération déclarée complète compte une fois dans le
budget source `maxSvgTransformOperations` partagé lors d’une normalisation :
toute la table pendant l’acquisition SVG entièrement normalisée, ou tout le
document sélectionné pendant une demande de glyphe mixte SVG/COLR à
normalisation différée. Elle est comptée lors de l’analyse de sa définition,
y compris une opération identité ou une définition
inutilisée. Un appel à trois opérandes `rotate(angle cx cy)` compte toujours
une seule fois ; réutiliser une définition ne la facture pas de nouveau. Le
repli ordonné entre profils peut donc ignorer un profil de schéma 3 qui ne
déclare pas chaque capacité atteinte et sélectionner un profil compatible
ultérieur.

### Opacité du remplissage SVG

Les éléments `path` et `rect` peints et pris en charge peuvent déclarer
`fill-opacity` comme un nombre fini sans unité ou un pourcentage. Sa valeur par
défaut est `1` et elle est ramenée dans `0.0..1.0`. Une entrée mal formée ou
non finie retourne `FontDataFailure` avec le code
`font.svg.invalid-fill-opacity`, même avec `fill="none"`.

Une peinture unie opaque conserve son nœud `Path` existant. Une peinture unie
translucide est normalisée en `Solid(color, opacity)` sous le `PathClip`
(découpe par chemin) de la forme : elle exige `SOLID` et `PATH_CLIP` plutôt que
`PATH`. Une découpe externe enveloppe tout ce sous-graphe. Pour un gradient,
chaque référence produit une ligne de couleur immuable dont les opacités des
arrêts sont multipliées par l’opacité de la forme, y compris l’arrêt terminal
d’une réduction unie. La définition partagée et les utilisations suivantes
restent inchangées. Les modes d’interpolation et capacités de gradient
existants continuent de s’appliquer.

Une opacité nulle omet la peinture normalisée seulement après validation de la
géométrie atteinte, de la peinture et de ses références, des types de nœud
requis et des limites de ressources projetées. Elle ne contourne ni un
gradient non vide incompatible ni une forme invalide. Les définitions de
gradient vides valides, `fill="none"` et les rectangles peints d’aire nulle
conservent leurs comportements distincts existants sans encre. Les enfants de
découpe ne peuvent déclarer ni `fill-opacity`, ni `fill`, ni autre attribut de
peinture. L’attribut `opacity` sur un élément ou groupe et l’opacité CSS restent
hors du sous-ensemble.

### Découpe SVG bornée dans l’espace utilisateur

Le sous-ensemble accepte un `clip path` (chemin de découpe) sous la forme d’un
`clipPath` non auto-fermant directement
dans `defs`. Il doit porter un `id` valide et globalement unique ;
`clipPathUnits` doit être absent ou exactement égal à `userSpaceOnUse`
(coordonnées absolues dans l’espace utilisateur). Son unique enfant est
exactement un `path` auto-fermant, avec `d` requis, les commandes de chemin
statiques prises en charge et la règle de remplissage non nulle, ou un `rect`
à angles vifs. Ce rectangle accepte `x/y` facultatifs (défaut `0`) et
`width/height` requis, finis, sans unité et non négatifs. Une dimension
négative non nulle reste invalide même si un sous-dépassement numérique la
décodait comme zéro. Les angles arrondis et autres attributs de rectangle ne
sont pas pris en charge. Un
`path` ou `rect` peint et pris en charge peut ajouter un attribut
`clip-path="url(#id)"` qui référence un `clipPath` local antérieur. La
définition et son enfant acceptent chacun un attribut `transform` absent, vide
ou composé uniquement d’espaces comme identité, ou une `transform list`
(liste de transformations) qui suit la même grammaire bornée décrite plus
haut. Ils n’acceptent ni remplissage, style, `clip-rule`, ID sur l’enfant,
groupe, autre forme, découpe imbriquée, référence, animation, ni élément ou
attribut non déclaré.

Pour chaque utilisation atteinte, la transformation effective `T` de la forme
matérialise la forme peinte, tandis que le chemin de la définition utilise
`T * C * P`, où `C` est la transformation de la définition et `P` celle de son
enfant, toutes deux composées dans l’ordre déclaré. Les coordonnées de découpe
déclarées doivent rester finies et respecter exactement les limites de
`outlineProfile` (profil de contours), même pour une définition inutilisée ;
les bornes entières des coordonnées de conception ne sont toutefois imposées
qu’après cette matérialisation complète pour une référence atteinte. Le
sous-graphe de peinture existant conserve exactement sa topologie
et ses valeurs, puis un `PathClip` (découpe par chemin) externe l’enveloppe. La
forme reste donc sous `T` ; la géométrie du gradient conserve son repère
existant `T * G` ou `T * B * G`. Un gradient conserve son `PathClip` de forme ;
un gradient radial conserve aussi son éventuel `Transform` sous cette découpe
de forme. La découpe externe représente une intersection par imbrication, sans
union de chemins ni calcul de boîte englobante. La réutilisation d’une
définition sous plusieurs transformations matérialise et comptabilise une
découpe distincte à chaque référence sans réanalyse ni capture de `T`.

Les définitions de découpe exigent exactement le schéma de peinture 3, même
inutilisées ; chaque découpe atteinte exige aussi `PATH_CLIP`. Chaque
utilisation ajoute un nœud, une référence, un
chemin, une découpe et un niveau de profondeur produits ; elle est facturée
indépendamment à `maxNodes`, `maxReferences`, `maxPaths`, `maxClips`,
`maxDepth` et `maxPaintVisits`. Son chemin et le chemin peint doivent tous deux
respecter exactement l’`outlineProfile` (profil de contours). Un dépassement
des limites de points, contours ou octets retourne `ResourceLimitExceeded`
pour la table `SVG `, pas une incompatibilité de capacité. Les opérations
déclarées de la définition et de son enfant consomment une seule fois le budget
source partagé `maxSvgTransformOperations` lors de leur analyse, même
inutilisées. Ce budget couvre toute la table pendant l’acquisition entièrement
normalisée, ou tout le document sélectionné pendant la matérialisation différée
mixte SVG/COLR. La réutilisation ne consomme aucune opération source
supplémentaire. La matérialisation du chemin de découpe ne
produit aucun nœud `Transform` et ne facture pas `maxTransforms` : les budgets
existants du gradient, des arrêts et des transformations de la peinture enfant
restent inchangés. Une composition effective `T * C * P` singulière ou un
rectangle de découpe d’aire nulle omet la peinture seulement après validation
de la forme, de la
définition, de la référence, des capacités, des limites de contours et des
limites projetées du graphe. `fill="none"` et
les rectangles d’aire nulle conservent leur résultat sans encre après validation
des attributs source et de toute référence locale de découpe.

`objectBoundingBox` (boîte englobante de l’objet), les unités inconnues, les
définitions vides ou contenant plusieurs enfants, le contenu interdit et les
références mal formées, externes, futures, non résolues ou visant un élément
qui n’est pas un `clipPath` restent hors du sous-ensemble, même sans utilisation
ou lorsque la forme ne produirait aucune encre. Les données de chemin mal
formées, une syntaxe ou composition de transformation mal formée, le XML
invalide et les ID dupliqués restent des `FontDataFailure`
typées. Tout échec est atomique pour l’acquisition publique complète, et le
repli ordonné peut sélectionner un profil exact compatible ultérieur.

Tous les identifiants d’élément acceptés sur `svg`, `g`, `linearGradient`,
`radialGradient` et `clipPath` sont globalement uniques. L’unicité des cibles
par identifiant de glyphe reste un invariant distinct. Les références de
peinture et de découpe sont locales, limitées à un fragment `#id` et uniquement
dirigées vers une définition antérieure. Une référence non résolue, future,
externe ou contenant autrement une URI échoue avant la publication d’une
ressource, même si la forme référente ne devait ensuite produire aucune encre.
Une entrée mal formée ou non prise en charge ne publie jamais de graphe partiel.

Le sous-ensemble ne prend pas en charge `viewBox`, les coordonnées en
pourcentage ou valeurs par défaut de l’espace utilisateur qui dépendent du
viewport, `href`, `xlink:href`, le rayon focal `fr`, les foyers
radiaux non concentriques, les gradients `objectBoundingBox` sur `path`, CSS ou
les attributs `style`, les découpes SVG hors du sous-ensemble exact
`userSpaceOnUse` à enfant unique décrit ci-dessus, les masques, contours tracés,
scripts, entités, animations, ressources externes, ni les éléments et attributs
non déclarés. Les formats de compression autres que le transport gzip
mono-membre autorisé restent refusés.

### Schémas de graphe de peinture et COLR version 1 statique

Un `GlyphPaintIR` est un graphe de peinture complet et immuable, pas des pixels
ni des commandes envoyées à une API de dessin de plateforme.
`GlyphRepresentation.Paint` et un certificat
`GlyphMaterializationRoute.PAINT_GRAPH` forment la frontière publique :
Kalligraphie valide et matérialise le graphe portable, mais ne fournit ni
`renderer` (moteur de rendu consommateur) ni rastériseur.

Les versions de schéma expriment des capacités exactes du consommateur :

- le schéma 1 conserve les nœuds `SolidOutline`, `Path` et `Group`, dans
  l’ordre source. Les groupes utilisent `SOURCE_OVER` ; ce schéma ne peut
  annoncer ni nœud du schéma 2, ni mode d’extension de gradient, ni autre mode
  de composition, et un `Group` de schéma 1 doit contenir au moins un enfant.
  Les graphes COLR version 0 et les graphes SVG-in-OpenType restreints qui ne
  contiennent que des peintures unies restent représentables avec ce schéma ;
- le schéma 2 ajoute les peintures non bornées `Solid`, `LinearGradient`,
  `RadialGradient` et `SweepGradient`, ainsi que `GlyphClip`, `Transform` et
  `Composite`, les extensions de gradient `PAD`, `REPEAT` et `REFLECT`, et les
  28 valeurs nommées de `GlyphPaintCompositionMode`. Le fournisseur ne
  certifie le graphe que si tout son contenu atteignable respecte les types de
  nœud, extensions, modes de composition et limites du `PaintGraphProfile`
  exact. Un `Group` vide de schéma 2 représente `no paint` (absence de
  peinture) et reste structurellement borné. Dans ce schéma, les lignes de
  couleur des gradients utilisent uniquement `LINEAR_SRGB` : l’interpolation
  RGB s’effectue en `linear-light` (« lumière linéaire ») sRGB, avec uniquement
  l’interpolation d’alpha `PREMULTIPLIED` (prémultipliée). Le schéma 2 ne
  peut ni annoncer ni transporter `SRGB`, `UNPREMULTIPLIED` (non prémultipliée)
  ou un nœud
  `PathClip` (« découpe par chemin ») ;
- le schéma 3 ajoute des capacités d’interpolation explicites à chaque ligne de
  couleur de gradient. `LINEAR_SRGB` conserve le comportement en lumière
  linéaire du schéma 2, tandis que `SRGB` interpole dans l’espace de transfert
  sRGB. `PREMULTIPLIED` et `UNPREMULTIPLIED` indiquent si le RGB est
  prémultiplié par l’alpha effectif avant l’interpolation. Il ajoute aussi ce
  nœud `PathClip`, qui restreint une peinture enfant à
  la région de remplissage d’un chemin portable. Le
  consommateur déclare précisément les espaces et modes d’alpha qu’il accepte
  avec `acceptedGradientInterpolationSpaces` et
  `acceptedGradientAlphaInterpolationModes` ; leurs valeurs par défaut restent
  `LINEAR_SRGB` et `PREMULTIPLIED` tant que d’autres capacités ne sont pas
  ajoutées explicitement. Le graphe n’est accepté que si chaque gradient
  atteint utilise un espace et une sémantique d’alpha déclarés. Le schéma 1 ne
  peut annoncer aucune de ces deux listes de capacités de gradient.

Les parcours JVM pour fontes embarquées et fichiers `.ttf` capturés acceptent
exactement les schémas 2 et 3 pour COLR version 1 statique ; ils ne déduisent
pas la prise en charge du schéma 4 ou d’un schéma ultérieur. Le même graphe
COLR peut être publié avec le schéma 2 ou 3 sélectionné tout en conservant
l’interpolation `LINEAR_SRGB`, la même géométrie et les mêmes couleurs
littérales. Ces parcours acceptent les structures globales et les formats de
peinture `1`, `2`, `4`, `6`, `8`, `10`, `11`, `12`, `14`, `16`, `18`, `20`,
`22`, `24`, `26`, `28`, `30` et `32`. Les peintures affine, translation, mise
à l’échelle, rotation et inclinaison spécialisées sont normalisées en un
`GlyphAffineTransform` fini ; les références à d’autres glyphes COLR sont
résolues dans le graphe et ne sont pas exposées comme références à la table
source. Un enregistrement `PaintColrLayers` valide de format 1 avec zéro couche
est préservé sous forme d’un graphe de peinture à un seul nœud `Group` vide ;
il n’est pas réduit à `GlyphRepresentation.Empty`. La `ClipList` (liste de
découpes) de format 1 avec une `ClipBox` (boîte de découpe) de format 1 est
acceptée. Les formats variables `PaintVar*`, la `VarColorLine` variable, la
`ClipBox` (boîte de découpe) de format 2, les magasins `ItemVariationStore` et la
table d’index `DeltaSetIndexMap` COLR sont résolus à l’emplacement normalisé de
l’instance via l’évaluateur de variations partagé : à une instance non par
défaut, le graphe de peinture résolu porte des alphas de solides variés, une
géométrie de dégradé, des décalages et alphas d’arrêts de couleur, des
coefficients de transformation et des bornes de découpe variés, les valeurs étant
figées dans les mêmes nœuds de schéma 2/3. Les indices de palette ne sont pas
variables, et ni le CFF/CFF2 dans COLR ni les valeurs CPAL variables ne sont pris
en charge. Le parcours SVG-in-OpenType distinct accepte les
gradients linéaires et radiaux concentriques statiques ainsi que les découpes
bornées à enfant unique dans l’espace utilisateur décrites plus haut via le
schéma 3 ; il n’acquiert pas pour autant les découpes SVG générales, masques,
contours tracés ou animations.

Cette extension de schéma n’ajoute aucun moteur de rendu. Le consommateur
reste responsable de la rastérisation, de l’intégration au processeur
graphique (GPU) et de l’affichage final.

La palette et la couleur de premier plan sont résolues avant publication. Une
valeur nulle de `FontRenderVariantSnapshot.cpalPaletteIndex` sélectionne la
palette 0 ; un index explicite sélectionne cette palette CPAL exacte ou échoue.
L’index CPAL `0xFFFF` est remplacé par la valeur exacte de `foregroundColor`
(couleur de premier plan) de la variante, ou par du noir opaque si elle est
nulle. Le graphe contient donc des couleurs sRGB huit bits littérales et non
prémultipliées dans `GlyphColor`, jamais des index de palette. L’opacité d’un
nœud ou d’un arrêt reste une valeur finie séparée dans `0.0..1.0`. Le moteur de
rendu calcule l’alpha effectif de chaque arrêt comme
`color.alpha / 255.0 * opacity` et représente le RGB dans l’espace déclaré.
Avec `PREMULTIPLIED`, il prémultiplie le RGB de chaque arrêt par son alpha
effectif, puis interpole séparément le RGB prémultiplié et l’alpha ; c’est le
comportement COLR et la valeur par défaut du constructeur et du profil. Avec
`UNPREMULTIPLIED`, il interpole séparément le RGB non prémultiplié et l’alpha,
puis ne prémultiplie le résultat que si une composition ultérieure l’exige ;
c’est le comportement du SVG normalisé. `LINEAR_SRGB` commence par linéariser
les composantes sRGB littérales, puis reconvertit depuis la lumière linéaire
vers l’encodage de sortie requis ; `SRGB` interpole le RGB directement dans
l’espace de transfert sRGB.
Changer la palette ou le premier plan modifie les couleurs littérales et
l’identité de la ressource, mais pas la composition, les avances, les
positions du `caret`
(curseur d’insertion), le hit-testing (test de point) ni la géométrie de
sélection.

Tous les points, limites de racine, contours et transformations utilisent les
coordonnées de conception de la fonte. Pour
`GlyphAffineTransform(xx, yx, xy, yy, dx, dy)`, le consommateur applique
`x' = xx*x + xy*y + dx` et `y' = yx*x + yy*y + dy`.
`Composite.source` désigne la `source` (peinture avant) et
`Composite.backdrop` le `backdrop` (fond déjà peint) ; `children` les ordonne
comme `[backdrop, source]`, c’est-à-dire dans l’ordre de peinture. Un `Group`
peint également ses enfants dans l’ordre de la liste avec `SOURCE_OVER`.

Les angles de `SweepGradient` partent de l’axe x positif et croissent dans le
sens antihoraire, avec l’axe y positif vers le haut. Des extrémités inversées
conservent une progression horaire des couleurs ; ne les triez pas. Voir la
[convention angulaire OpenType](https://learn.microsoft.com/en-us/typography/opentype/spec/colr#sweep-gradients).

`GlyphPaintIR.clipBounds`, lorsqu’il existe, applique une découpe à tout le
résultat de la racine. Sans ces limites racine, un graphe de schéma 2 ou 3
n’est accepté que si sa racine atteignable est structurellement bornée :
`SolidOutline`, `Path` et `GlyphClip` sont bornés ; `Solid` et les trois
gradients ne le sont pas ; `Transform` conserve la bornitude de son enfant ;
un `Group` n’est borné que si tous ses enfants le sont. Dans le schéma 3,
`PathClip` est borné parce que son chemin portable restreint toute la peinture
enfant, y compris un gradient autrement non borné. Un `Group` vide de schéma 2
ou 3 est donc borné. Pour les compositions, `CLEAR` est toujours borné ;
`SOURCE` et `SOURCE_OUT` suivent la source ;
`DESTINATION` et `DESTINATION_OUT` suivent le fond ; `SOURCE_IN` et
`DESTINATION_IN` sont bornés si l’une des deux entrées l’est ; tous les autres
modes exigent que les deux entrées soient bornées. Une découpe racine borne
toute combinaison par ailleurs acceptée.

`PaintGraphLimits` est appliqué avant la certification. Il borne les nœuds,
références et profondeurs ; à partir du schéma 2, il borne aussi les visites
développées du graphe. Il borne également les chemins,
gradients, arrêts de couleur, transformations, compositions et découpes de
glyphe ; les octets source ; les palettes CPAL, leurs entrées, leurs
enregistrements de couleur et leurs octets décodés ; les enregistrements COLR
de glyphes de base, de couches et de découpes ; ainsi que chaque contour
référencé via `outlineProfile`. Chaque `PathClip` atteint consomme une unité de
`maxPaths` et de `maxClips`, et son chemin portable doit respecter
`outlineProfile` ; les données de ce chemin participent aussi à l’admission
conservatrice selon la taille retenue. Un dépassement renvoie
`FontError.ResourceLimitExceeded`. Une capacité atteinte mais non annoncée, ou
une palette CPAL sélectionnée par le consommateur mais indisponible dans la
fonte, renvoie `FontError.UnsupportedRepresentationProfile`. Une référence, un
cycle, une géométrie ou un index COLR mal formé renvoie
`FontError.InvalidFontData`. Une structure CPAL mal formée ou tronquée renvoie
`FontError.FontDataFailure` avec un code stable tel que
`font.cpal.truncated`, `font.cpal.invalid-table` ou
`font.cpal.invalid-palette-index`. Aucun de ces cas ne publie de graphe partiel
ni de certificat.

La résolution de route et le `fallback` (repli déterministe) de représentation
s’effectuent pour chaque glyphe final. Un glyphe couvert par SVG conserve la
priorité SVG existante. Sinon, son enregistrement de peinture version 1 est
utilisé s’il existe ; s’il est absent, un enregistrement de couches version 0
est utilisé s’il existe ; seul un glyphe absent des deux tables de couleurs
utilise son contour `glyf` ou un résultat explicitement sans encre.
Lorsqu’une capacité COLR v1 atteinte n’est pas prise en charge ou dépasse une
limite, les profils de représentation ordonnés peuvent sélectionner un résultat
compatible pour ce glyphe. Si aucun n’y parvient, le repli de fonte configuré
par l’éditeur suit sa politique d’unité de repli atomique. L’échec n’empoisonne
pas les autres glyphes de la face.

Dans une ressource mixte SVG/COLR v1, les limites de toute la source SVG et de
son index sont vérifiées avant acquisition. Les capacités et limites du graphe
sont vérifiées seulement lorsqu’un document SVG couvert est demandé. La
priorité SVG reste inchangée pour ses glyphes ; un chemin SVG non accepté ne
dégrade pas un autre glyphe COLR. La validation atomique de tout le document
SVG sélectionné reste inchangée, même lorsqu’il cible plusieurs glyphes.

### Migration des consommateurs de peinture

#### Ajouts du schéma 3

La compatibilité de schéma ne préserve pas l’ABI (interface binaire
d’application) JVM. Le constructeur `GlyphPaintColorLine` ajoute
`interpolationSpace` et `alphaInterpolationMode`, avec des valeurs par défaut
qui conservent les règles COLR historiques `LINEAR_SRGB` et `PREMULTIPLIED`.
Le constructeur `PaintGraphProfile` ajoute
`acceptedGradientInterpolationSpaces` et
`acceptedGradientAlphaInterpolationModes`. Par défaut, ces listes sont vides
pour le schéma 1 et n’acceptent que `LINEAR_SRGB` / `PREMULTIPLIED` pour le
schéma 2 et les suivants ; choisir le schéma 3 seul n’autorise donc pas les
gradients SVG sRGB ou à alpha non prémultiplié. Déclarez explicitement ces
capacités ainsi que les catégories de nœuds nécessaires.

`PaintGraphLimits` ajoute trois limites de transport avec des valeurs par
défaut : `maxSvgCompressedDocumentBytes`, `maxSvgDecodedDocumentBytes` et
`maxSvgTotalDecodedBytes`, chacune égale à `maxSourceBytes` par défaut. Les
descripteurs de son constructeur, du constructeur à arguments par défaut, et
des méthodes générées `copy` et `copy$default` changent. `component1` à
`component20` conservent leur position et leur type de retour `Int` ; les
nouvelles limites occupent `component21` à `component23`. Les appels ordinaires
aux constructeurs Kotlin et les appels `copy` nommés conservent leurs arguments
après recompilation, mais les anciens descripteurs JVM ne sont pas conservés.
Recompilez toutes les applications et bibliothèques qui utilisent ces
signatures modifiées, y compris celles qui n’appellent que l’ancien
constructeur `GlyphPaintColorLine` à deux arguments : les anciens binaires
peuvent lancer `NoSuchMethodError`.

Traitez `GlyphPaintNode.PathClip`, `GlyphPaintNodeKind.PATH_CLIP` et les
nouvelles énumérations `GlyphPaintInterpolationSpace` /
`GlyphPaintAlphaInterpolationMode` dans la répartition des opérations du
consommateur. Complétez les gestionnaires exhaustifs ; les gestionnaires
sealed (scellés) déjà compilés peuvent lancer `NoWhenBranchMatchedException`.
`PATH_CLIP` est inséré avant `TRANSFORM`, décalant les positions ordinales
suivantes : ne persistez jamais ces positions, mais des noms ou identifiants
explicitement versionnés.

Les empreintes canoniques de profils ajoutent `gradientInterpolation=`,
`gradientAlphaInterpolation=` et les trois limites de transport dans
`limits=`. Même les profils de schéma 1 ou 2 par ailleurs inchangés ont des
empreintes différentes. Régénérez ou invalidez les empreintes persistées et
les entrées de cache dérivées, puis rouvrez et certifiez avec des clés fraîches
d’un fournisseur vivant ; les empreintes stockées ne sont pas des
localisateurs de ressources.

#### Ajouts du schéma bitmap 2

Les empreintes (fingerprints) de profil bitmap incluent désormais la profondeur
de bits exacte du strike. Les consommateurs qui persistent des valeurs
`GlyphRepresentationProfileKey` (clé d’identité de représentation) doivent les
régénérer une fois ; une empreinte périmée ne provoque qu’un défaut de cache,
jamais un changement de glyphe matérialisé.

#### Changements antérieurs du schéma 2

La compatibilité de schéma ne signifie pas compatibilité binaire JVM.
`GlyphPaintIR` ajoute `clipBounds`, `PaintGraphProfile` ajoute
`acceptedGradientExtendModes` et `PaintGraphLimits` ajoute six champs, tous
avec une valeur par défaut. Les appels ordinaires aux constructeurs Kotlin
restent valides après recompilation, mais les anciens descripteurs de
constructeur JVM, y compris ceux des arguments par défaut, ne sont pas
conservés. Recompilez ensemble les applications et bibliothèques qui les
utilisent avec cette version : l’ABI (interface binaire d’application) change.

`PaintGraphLimits` est une data class (classe de données Kotlin) : les
descripteurs générés `copy` et `copy$default` changent avec ses champs.
`component1` à `component14` conservent leur position et leur type de retour
`Int` ; les six composants ajoutés ne les décalent pas. Les appels `copy`
nommés recompilés conservent les noms existants, mais les appels déjà compilés
aux anciennes signatures générées nécessitent une recompilation. Aucune
compatibilité binaire entre versions n’est promise.

Complétez les `when` Kotlin exhaustifs sur `GlyphPaintNode`,
`GlyphPaintNodeKind` et `GlyphPaintCompositionMode` pour les nouveaux cas.
La hiérarchie sealed (scellée) de nœuds s’étend ; un gestionnaire exhaustif
déjà compilé peut lancer `NoWhenBranchMatchedException` devant un nouveau cas.
Ne persistez pas les positions ordinales des énumérations : `SOURCE_OVER`
passe de 0 à 3. Préférez des noms ou identifiants explicitement versionnés.

Les empreintes canoniques de profils incluent désormais `gradientExtend=`
et les six nouvelles limites, même pour un profil de schéma 1 inchangé.
Régénérez ou invalidez les empreintes persistées et entrées de cache dérivées ;
ne supposez pas leur égalité entre versions de la bibliothèque. Rouvrez et
certifiez avec les clés fraîches d’un fournisseur vivant, pas avec une empreinte
persistée utilisée comme localisateur de ressource.

`Group(emptyList())` est maintenant constructible pour représenter l’absence
de peinture en schéma 2. Le refus en schéma 1 passe du constructeur `Group`
au constructeur `GlyphPaintIR(schemaVersion = 1, ...)`, même pour un groupe
vide imbriqué. Les applications dépendant de l’ancien point de validation
doivent valider lors de la construction du graphe ; les routes non vides de
schéma 1 restent inchangées.

```kotlin
val catalogResult = Kalligraphie.embedded(bytes, provenance)
val faceId = catalog.faces.single().id
val size = FontInstanceDescriptor(LayoutUnit(2048f))
val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile)
```

L’accès aux glyphes pour le rendu exige un profil de représentation explicite. Fermer
un gestionnaire de ressources ou une ressource de rendu est idempotent (répéter
la fermeture produit le même résultat). Les nouvelles acquisitions après
fermeture renvoient `font.resource-closed` ; une ressource détachée conserve les
données immuables requises par `resolveGlyph(...)`.

### Rétention bornée des représentations

`FontMaterializationCachePolicy` peut conserver les succès complets, immuables et portables des contours, graphes de peinture et bitmaps (images matricielles). Le cache (mémoire interne de réutilisation) est désactivé par défaut. La politique se passe à `Kalligraphie.embedded(...)` ou à `MacosSystemFontCatalogOptions`.

`FontCacheBudget` fixe quatre limites indépendantes, positives ou nulles : octets retenus estimés, pixels bitmap décodés, octets natifs et allocations natives. Les limites `perFace` et `perCatalog` doivent être respectées simultanément. Un bitmap conservé compte largeur × hauteur pixels ; les contours et la peinture comptent zéro pixel. Les entrées portables comptent zéro octet natif et zéro allocation native. `Long.MAX_VALUE` laisse une dimension pratiquement non bornée.

Chaque catalogue coordonne atomiquement l'admission et l'ordre LRU (`least recently used`, moins récemment utilisé) de toutes ses faces. Un dépassement par face retire la plus ancienne entrée de cette face ; un dépassement du cumul retire la plus ancienne entrée globale. Un résultat trop lourd est retourné sans être conservé. La sélection de route, les identités, certificats et diagnostics restent identiques. Les annulations et erreurs opérationnelles ne sont jamais conservées.

```kotlin
val cachePolicy = FontMaterializationCachePolicy(
    perFace = FontCacheBudget(
        retainedBytes = 4L * 1024L * 1024L,
        decodedPixels = 1_000_000L,
        nativeBytes = 0L,
        nativeAllocations = 0L,
    ),
    perCatalog = FontCacheBudget(
        retainedBytes = 16L * 1024L * 1024L,
        decodedPixels = 4_000_000L,
        nativeBytes = 0L,
        nativeAllocations = 0L,
    ),
)
val catalogResult = Kalligraphie.embedded(bytes, provenance, cachePolicy)
```

Le constructeur historique `FontMaterializationCachePolicy(maxEvictableBytesPerFace = 4L * 1024L * 1024L)` et le getter (accesseur) `maxEvictableBytesPerFace` restent disponibles. Ce constructeur borne uniquement les octets retenus par face ; ses autres dimensions et le cumul du catalogue restent non bornés. `FontMaterializationCachePolicy.disabled` ne conserve aucune représentation.

Malgré la conservation du constructeur et de l'accesseur, il s'agit d'un changement incompatible de l'API source et binaire pour les opérations Kotlin générées. Migrer `copy(maxEvictableBytesPerFace = …)` vers `perFace`/`perCatalog` : le premier composant passe de `Long` à `FontCacheBudget` et la déstructuration est un accès positionnel aux composants ; les consommateurs JVM compilés contre les anciennes opérations générées `copy`, `copy$default` ou `component1` doivent être recompilés.

```kotlin
val updatedPolicy = cachePolicy.copy(
    perFace = cachePolicy.perFace.copy(retainedBytes = 8L * 1024L * 1024L),
)
val (perFaceBudget, perCatalogBudget) = updatedPolicy
val retainedBytesPerFace = perFaceBudget.retainedBytes
```

Ces limites portent sur les représentations évictables, leurs clés et diagnostics, pas sur les sources capturées, les ressources possédées par le consommateur ou la mémoire totale du processus. Les entrées portables gardent les données immuables de représentation sans posséder de gestionnaire, ressource de rendu, catalogue ou ressource de plateforme. La fermeture du dernier lease (droit temporaire de durée de vie) de gestionnaire ou de ressource d'une face libère les entrées de cette face ; les ressources détachées conservent leur lease indépendant. Les autres faces restent utilisables.

### Partager la rétention entre captures

Créez un `FontCacheScope`, domaine de rétention possédé par l'appelant, et passez-le
à chaque capture participante :

```kotlin
val scope = Kalligraphie.fontCacheScope(
    FontCacheBudget(32L * 1024 * 1024, 4_000_000, 16L * 1024 * 1024, 64),
)
val first = Kalligraphie.embedded(firstBytes, firstProvenance, cachePolicy, scope)
val second = Kalligraphie.embedded(secondBytes, secondProvenance, cachePolicy, scope)
// Fontes système : MacosSystemFontCatalogOptions(materializationCachePolicy = cachePolicy, cacheScope = scope).
```

Le domaine ajoute une limite cumulative ; `perCatalog` et `perFace` continuent de
s'appliquer dans chaque dimension. Il n'active pas une politique locale désactivée.
Sans domaine explicite, une politique activée garde son budget privé par capture.
Il n'existe aucun cache global au processus ni partage implicite entre sources
identiques. Les providers (fournisseurs) personnalisés qui gardent leurs propres
caches hors de ces fabriques ne participent pas. Pour inclure CoreText, passez le
même domaine à la capture portable et à
`CoreTextFontCatalog.capture(..., cachePolicy = nativePolicy, cacheScope = scope)` :
l'adaptation d'un catalogue portable ne reconfigure ni sa politique ni son domaine.
Voir [l'accès aux fontes de plateforme](platform-font-access.fr.md#retention-partagee-des-contextes).

Chaque limite cumule les entrées actives, les réservations en attente, les références
retirées dont la libération est en cours et les charges résiduelles de nettoyage
incertain. Retirer une entrée de l'index ne crée aucune place avant l'abandon confirmé
de sa référence de cache. L'estimation gérée comprend clés, diagnostics, données
immuables et une enveloppe prudente de métadonnées (actuellement 4096 octets par
entrée portable, auxquels s'ajoutent les données variables de clé et résultat).
Sources capturées, ressources possédées uniquement par l'appelant, buffers (tampons)
temporaires, mémoire privée du système et délai du GC (ramasse-miettes) sont exclus.
Une référence de cache en attente ou en cours de libération n'est jamais exclue comme
mémoire temporaire ou système. Les entrées portables comptent zéro octet et zéro
unité natifs ; le contexte CoreText actuel compte la longueur source N et quatre
ressources natives explicites, indépendamment de la mémoire inconnue des frameworks
(bibliothèques système).

`scope.close()` désactive la rétention et libère les références de cache sans fermer
catalogues ni propriétaires consommateurs. Les acquisitions existantes, ressources
détachées et nouvelles captures utilisant ce domaine fermé restent utilisables sans
cache ; aucun cache privé de remplacement n'est créé. La première fermeture et les
suivantes rapportent uniquement les défauts de nettoyage connus, sans retenter une
libération partielle. Un nettoyage concurrent peut finir après le retour : un succès
ne prouve donc pas le drainage complet. Effectuez ce drainage hors du chemin critique
de rendu, car la fermeture explicite peut libérer toutes les entrées. La mémoire
native exclusivement possédée par les consommateurs peut rester vivante après
drainage réussi, jusqu'à leur propre fermeture.

Les paramètres de domaine ajoutés en fin de signature préservent les appels Kotlin
ordinaires recompilés, mais les signatures JVM modifiées exigent une recompilation.
Les déclarations d'assemblage sont des contrats internes opt-in (activés explicitement),
pas une SPI (interface d'extension) publique de cache personnalisé. Les tests de glyphes
démontrent la transparence observable ; la
[mesure facultative de rétention](glyph-materialization-measurement.fr.md#retention-partagee-et-propriete-native)
consigne comptabilité et coûts structurels hors `check`.

Sur macOS, l’artefact JVM expose aussi `MacosSystemFontCatalog.open()`. Il
capture, sous limites, les fichiers `.ttf` réguliers dans un instantané
portable et utilise les mêmes routes que les fontes embarquées. Il n’expose pas
de handle (référence opaque vers une ressource de plateforme) CoreText et ne
déclare pas de prise en charge de `.otf` ni
de `.ttc`.

## Sélection de fonte variable

Une face variable expose ses axes `fvar` et ses instances nommées via
`FontFace.variationAxes()` et `FontFace.namedInstances()`, et convertit une
sélection en coordonnées de conception vers des coordonnées normalisées via
`FontFace.normalize(design)`. Les deux appels de métadonnées renvoient des
instantanés immuables et sont vides pour une face statique. `FontFace.stat()`
renvoie facultativement une surface `STAT` en lecture seule, avec
`Success(null)` lorsque la face n’a pas de table `STAT` utilisable.

Passez des coordonnées de conception à une instance avec le champ facultatif
`FontInstanceDescriptor.variation` :

```kotlin
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.LayoutUnit

val descriptor = FontInstanceDescriptor(
    layoutSize = LayoutUnit(72f),
    variation = FontVariationCoordinates(
        listOf(FontVariationCoordinate("wght", 700f)),
    ),
)
val variable = when (val result = face.instantiate(descriptor)) {
    is FontOperationResult.Success -> result.value
    is FontOperationResult.Failure -> error(result.error.message)
    is FontOperationResult.Cancelled -> error("Instanciation annulée.")
}
```

`instantiate` normalise la sélection de conception via les tables `fvar` et
`avar` version 1 de la face, puis reconstruit les axes normalisés dans
`FontInstanceKey.geometry`. Ces axes participent à
`FontGeometryParameters.normalizedAxes` : la sélection fait donc partie de
l’identité de l’instance, et deux descripteurs qui ne diffèrent que par les
valeurs d’axes produisent des clés qui ne sont pas égales. Appelez
`normalize(design)` directement pour prévalider une sélection ; elle renvoie
des coordonnées triées et uniques par tag.

Une valeur hors des bornes déclarées d’un axe subit un clamp (limitation à la
borne la plus proche) et est signalée par un diagnostic informatif
`font.variation.axis-clamped` sur le résultat réussi. Un tag d’axe non déclaré
par la face échoue avec `font.variation.unknown-axis`. Combiner une sélection de
conception avec des `FontGeometryParameters.normalizedAxes` non vides échoue
avec `font.variation.ambiguous-request`, car les deux décrivent la même
sélection à des niveaux différents. Les tables de variation malformées échouent
avec les codes typés `font.variation.invalid-fvar`,
`font.variation.unsupported-fvar-version`, `font.variation.invalid-avar` et
`font.variation.unsupported-avar-version` (`avar` version 2 n’est pas prise en
charge), et une face sans table `fvar` utilisable échoue avec
`font.variation.not-variable`.

Une sélection non par défaut contribue désormais à la fois à l’identité de
l’instance et à la variation des contours. La route portable de contours
TrueType lit la table `gvar` de la face, évalue le facteur de région de chaque
tuple aux axes normalisés de l’instance, interpole les points non touchés des
glyphes simples via l’IUP TrueType, applique les deltas résolus par point aux
coordonnées des glyphes simples et à leurs bornes recalculées, puis applique
les deltas des glyphes composites aux décalages de placement de leurs
composants. Les deltas de composant ne s’appliquent que si le composant
sélectionne `ARGS_ARE_XY_VALUES`, s’ajoutent au décalage brut avant la
transformation éventuelle de décalage mis à l’échelle, et sont ignorés pour les
composants alignés par points, conformément aux règles `gvar` des composites.
Les quatre deltas de points fantômes qui suivent les points de contour ou de
composant sont décodés en même temps que le contour et exposés via
`GlyphVariationPhantoms` du module scaler (delta d’avance horizontale
droite moins gauche, delta d’avance verticale haut moins bas) ; ils valent
`null` à l’instance par défaut. La route des métriques les consomme en repli
lorsque `HVAR`/`VVAR` sont absents ; une sélection non par défaut change
désormais les métriques renvoyées par un fournisseur portable.

La route portable de contours CFF2 varie elle aussi désormais. Les opérandes
`blend` de chaque charstring sont évaluées aux axes normalisés de l’instance,
les facteurs de région provenant des données de variation d’item du charstring
via un évaluateur `ItemVariationStore` format 1 partagé, borné et annulable
(`VariationStoreEvaluator` avec `VariationStoreLimits`, dans le paquet
`org.graphiks.kalligraphie.font.sfnt.variation`). Ses bornes déclarées
`maxRegions`, `maxItemData`, `maxAxes` et `maxSourceBytes` sont appliquées de
façon incrémentale pendant le décodage. Le `vsindex` utilisé avant le premier
`blend` du charstring est initialisé depuis le DICT Privé du Font DICT
sélectionné (`vsindex`, opérateur 22, résolu par glyphe via `FDSelect` et
valant FD 0 par défaut), et une surcharge `vsindex` du charstring est validée
contre les données de région du store. Les axes normalisés de l’instance
atteignent la route via la même table d’ordre d’axes
(`VariationAxisOrder.orderedNormalizedAxes`) que celle utilisée par la route
`gvar`. Une sélection non par défaut change désormais le contour CFF2 renvoyé
par un fournisseur portable : le sommet de `A` de la fixture CFF2 variable
auditée vaut 200 à l’instance par défaut et 300 à `wght = 1.0` normalisé. Un
store dont le format n’est pas 1 échoue avec
`font.variation.unsupported-store-format`, un store tronqué échoue avec
`font.variation.truncated-store`, une référence de région hors limites échoue
avec `font.variation.invalid-store`, et un dépassement des bornes déclarées
réutilise `font.resource-limit-exceeded`. Les tags d’axes `fvar` sont lus de
façon paresseuse et uniquement lorsqu’un emplacement non vide est fourni, de
sorte que le chemin par défaut n’ajoute aucun décodage et emprunte le même
chemin d’appel qu’auparavant. L’évaluateur partagé corrige toutefois la règle
de région : un store qui contient une région traversant zéro ou un ordre de
bornes invalide change donc à l’instance par défaut (un correctif délibéré, et
non une régression), et un store déclarant zéro entrée de données d’item est
désormais accepté au lieu d’être rejeté.
La variation des métriques `HVAR`/`VVAR`/`MVAR` est implémentée : une instance non
par défaut fait varier les avances horizontales via `HVAR`, les avances verticales
via `VVAR`, les métriques de fonte via `MVAR`, et retombe sur les deltas de points
fantômes `gvar` lorsque `HVAR`/`VVAR` sont absents ; la couleur variable est
implémentée sur la route portable COLR v1 : une instance non par défaut fait
varier le graphe de peinture de couleur résolu ; et la géométrie synthétique
gras/italique est implémentée sur la route de contour portable. Les données `gvar`
malformées échouent
avec `font.variation.invalid-gvar`, une version de table non prise en charge
échoue avec `font.variation.unsupported-gvar-version`, et les bornes de
ressources `gvar` réutilisent `font.resource-limit-exceeded` avec
l’emplacement de table `gvar`.

`FontFace.stat()` reste un espace réservé avec valeur par défaut qui renvoie
`Success(null)` : la lecture portable de `STAT` est un sujet distinct et reste
différée, au même titre que les ponts natifs de métriques (limités au cas par
défaut), `avar` version 2, `cvar`, `VARC`, les champs de limite de profil qui régénèrent les
empreintes et la facturation de budget de cache §8
`maxVariationTableBytes`/`retainedBytes` des tables de métriques décodées. Le
sous-plan « composition à l’instance » lie désormais la vérification croisée
**horizontale** des métriques HarfBuzz sur JVM et Android : la location normalisée
de l’instance atteint la fonte HarfBuzz préparée (ordonnée selon l’ordre des axes
`fvar`) et notre `metrics()` est égale à l’avance HarfBuzz à la même location sur
la fixture auditée `NotoSansJP-VerticalFixture.ttf` (`A` = `660` à `wght = 900`,
`622` à `wght = 500`, `574` au défaut). L’avance verticale et les bornes de la
spécification ne sont pas vérifiées (la liaison n’expose aucun
`hb_font_get_glyph_v_advance`).
`FontInstance.fontMetrics()` est implémentée : elle renvoie les métriques
de fonte de l’instance issues de `OS/2` (avec repli sur `hhea`), `post` et `MVAR`,
en unités de design. Les métriques horizontales suivent la priorité `HVAR` puis
deltas de points fantômes `gvar` puis `hmtx` ; les métriques verticales suivent
`VVAR` puis deltas de points fantômes `gvar` puis `vmtx`. Les demi-approches
(side bearings) ne sont ajustées que si la table de correspondance `HVAR`/`VVAR`
est présente ; sans correspondance, la valeur `hmtx`/`vmtx` est conservée, ce qui
suit la spécification mais diffère de fontTools `varLib.instancer`, qui recalcule
la demi-approche gauche à partir du contour varié. Les bornes d’encre restent les
bornes d’en-tête `glyf` non variées sur la route TrueType. La sélection d’axes est
conservée telle quelle : un axe explicitement réglé à sa valeur par défaut est
gardé, se normalise à `0` et produit une `FontInstanceKey` distincte de l’omission
de cet axe (aucun élagage, ou pruning, des valeurs par défaut). La couverture de
la variation des métriques reste partielle : `VVAR` et `MVAR` ne sont exercés que
sur des octets synthétiques, aucune fixture réelle ne les portant ; le chemin de
repli horizontal composite + points fantômes `gvar` (sans `HVAR`) est correct dans
le code mais non testé ; et les chemins de correspondance présents `lsb`/`rsb` de
`HVAR` et `tsb`/`bsb` de `VVAR` ne sont couverts qu’au niveau du lecteur, de sorte
que le chemin de delta non nul des demi-approches du scaler n’est pas testé. La
fixture CFF2 variable synthétique porte un `HVAR` dont le store ne déclare ni
région ni delta d’item ; fontTools confirme donc que l’avance du glyphe `A` reste
`1000` à `wght = 1.0`, et aucune avance variée ne peut être affirmée via la route
de métriques CFF2.

### Gras et italique synthétiques

`FontGeometryParameters.syntheticBold` et `syntheticItalic` appliquent un style
géométrique fixe et versionné sur la route de contour portable. Le gras synthétique
décale chaque contour vers l’extérieur de `0,02 em` par côté (`unitsPerEm * 0,02`
unités de design) par un décalage à onglet signé qui épaissit les contours
extérieurs et rétrécit les trous ; l’italique synthétique cisaille chaque point
autour de la ligne de base avec la tangente de `14°`
(`tan 14° = 0,2493280028431807`). L’italique est appliqué d’abord, donc le gras est
calculé sur la géométrie déjà inclinée. La transformation préserve chaque contour,
point et commande : elle ne peut donc pas dépasser une limite d’`OutlineProfile` et
ne recalcule que l’enveloppe entière `DesignBounds`. `SyntheticGeometry.VERSION`
marque les montants épinglés ; changer un montant modifie la géométrie rendue et
exige d’incrémenter cette version et la version d’interprétation TrueType.
`FontInstanceKey.geometry` porte déjà les deux drapeaux, donc une instance
synthétique a une identité de cache et de certificat distincte.

Conformément au §7 du design parapluie, la géométrie synthétique ne modifie ni les
avances, ni les side bearings, ni `GlyphMetrics.bounds`, ni `VerticalGlyphMetrics`,
ni `FontMetrics` : la transformation s’exécute uniquement dans la matérialisation de
contour, et les lecteurs de métriques ne la voient jamais. Un contour gras peut donc
chevaucher les glyphes voisins ; c’est le comportement CSS `synthetic` et une
limitation assumée. Une sélection de variation non-défaut se compose comme
`synthetic(instance(outline))` — les deltas `gvar`/CFF2 sont appliqués avant le
style, et les points fantômes `gvar` qui alimentent le repli de variation
métrique sont transmis inchangés.

La géométrie synthétique n’est honorée que par la route de contour portable.
Acquérir une ressource pour une route couleur (`COLR` v0, `COLR` v1 ou
SVG-in-OpenType `PaintGraphProfile`) ou bitmap (`BitmapProfile`) sur une instance
synthétique échoue avec `font.geometry.synthetic-unsupported-route` ; ces routes
transforment un graphe de peinture, un document ou un bitmap déjà rastérisé que
cette capacité ne restyle pas, donc un glyphe non transformé n’est jamais renvoyé
silencieusement. Lorsque le gras synthétique est combiné à une face qui déclare un
axe `wght`, `instantiate` applique quand même le style demandé et attache le
diagnostic informatif `font.geometry.synthetic-over-axis`, y compris lorsque le
descripteur est par ailleurs à l’instance par défaut (la condition est littéralement
`syntheticBold` et la présence d’un axe `wght` déclaré). Une coordonnée
transformée hors de la plage de design `Int` échoue avec `font.geometry-overflow`.

Trois notes pour finir. Les deux nouveaux diagnostics utilisent l’espace de noms
pointé `font.geometry.synthetic-*` imposé par la spécification, tandis que l’échec
de dépassement préexistant reste à trait d’union (`font.geometry-overflow`) :
l’espace de noms est incohérent, mais les deux graphies sont épinglées.
`estimateRenderAssetBytes` n’est pas conditionné par le mode synthétique : il peut
donc rapporter une taille pour un profil couleur ou bitmap qu’`acquireRenderAsset`
refusera sur une instance synthétique — une asymétrie, et non un chemin d’asset
silencieux. La latéralité du gras est dérivée une fois par glyphe à partir du
contour dont l’aire absolue est la plus grande, de sorte qu’un contour extérieur
s’épaissit et qu’un trou de sens opposé se rétrécit (un signe par contour ferait
grossir les trous) ; un contour contenant plus d’un `MoveTo` échoue avec
`font.geometry-overflow` au lieu d’être silencieusement décalé sur son premier
sous-chemin. Les ponts natifs restent limités au cas par défaut : seul le pont
CoreText enveloppe une face portable, et il rejette un descripteur synthétique à son
propre `instantiate`.

## Lignes Unicode éditables exactes

La cible JVM de référence fournit aussi un parcours sans interface graphique
pour une seule ligne éditable non renvoyée à la ligne. `Kalligraphie.decodeUtf8(...)`
ou `Kalligraphie.decodeUtf16(...)` crée un `TextSnapshot` immuable.
`JvmEditableLineFacade`, disponible uniquement sur la JVM, analyse ensuite
Unicode, résout les runs (séquences homogènes) de script et BiDi
(bidirectionnel), compose chaque run avec son backend (moteur d’exécution)
HarfBuzz embarqué, puis positionne la ligne finale.

```kotlin
val decoded = Kalligraphie.decodeUtf8(
    version = TextVersion.create(),
    slices = listOf(TextSlice.Utf8(editorBytes)),
)
val result = JvmEditableLineFacade.layout(
    JvmEditableLineFacadeRequest(
        snapshot = decoded.snapshot,
        font = instance,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        featurePolicy = HarfBuzzShapingBackend.pinnedFeaturePolicy,
        features = emptyList(),
        verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
        materialization = EditableLineMaterialization.LayoutOnly,
    ),
)
```

La direction, la langue, la politique de fonctionnalités OpenType (features),
les surcharges de fonctionnalités, les métriques de ligne et le mode de
publication sont des entrées obligatoires. Le script et la direction résolue
de chaque run proviennent de l’analyse Unicode épinglée et sont transmis
explicitement à chaque demande de composition. Le résultat est un
`EditableLineResult` : en cas de succès, il contient les glyphes composés et
positionnés, les relations texte-vers-clusters-vers-glyphes, la navigation de
caret (repère d’insertion) logique et visuelle, la géométrie de sélection et
le hit-testing (test de point) déterministe.

Le résultat Unicode JVM est vérifié contre chaque cas Unicode 16.0 applicable
de `GraphemeBreakTest`, `BidiTest` et `BidiCharacterTest`, ainsi que contre les
données complètes `Script`, `Script_Extensions` (extensions de script) et
`Bidi_Paired_Bracket` (paire de crochets bidirectionnels). La demande publique
impose une direction de paragraphe explicite ; les variantes BiDi officielles
à direction automatique restent donc hors du contrat de cette API. Les
caractères supprimés par la règle X9 d’UAX #9 ne sont omis que de la comparaison
normative des niveaux et du réordonnancement ; les résultats éditables
conservent leurs positions source.

Le parcours sans renvoi à la ligne rejette CR, LF, CRLF comme une seule unité,
la tabulation verticale, le saut de page, NEL, `U+2028 LINE SEPARATOR`
(séparateur de ligne) et `U+2029 PARAGRAPH SEPARATOR` (séparateur de paragraphe)
avant l’analyse Unicode ou la composition. L’erreur typée
`EditableLineError.UnsupportedLineControl` indique un `LineControlKind` et le
`TextRange` exact, lié au snapshot (instantané), qu’occupe le contrôle. Une
`U+0009 CHARACTER TABULATION` (tabulation horizontale) est également rejetée
si la demande ne fournit pas de `ParagraphPositioningPolicy` explicite.

Avec cette politique de positionnement, TAB avance jusqu’au prochain `TabStop`
(taquet de tabulation) explicite ou jusqu’au prochain intervalle défini par
`defaultTabInterval`. La composition glyphique est découpée autour de chaque
TAB : la valeur n’est jamais soumise comme U+0009, U+0020 ou `.notdef`, et
aucun glyphe de fonte n’est résolu ni certifié pour elle. Le résultat publie à
la place un `PositionedLineControl` associé exactement à la source, avec la
géométrie du taquet et, en mode rendu, la route de matérialisation `EMPTY`
(sans encre). Le contenu suivant et toutes les frontières du caret conservent
leur couverture source exacte. Les contrôles de formatage
BiDi LRE, RLE, PDF, LRI, RLI, FSI et PDI restent acceptés, sans encre et associés
exactement à leur source.

Pour obtenir `RENDERABLE`, remplacez `LayoutOnly` par
`EditableLineMaterialization.Renderable` et fournissez un gestionnaire ouvert,
un `FontRenderVariantSnapshot` (sélection visuelle) et un
`FontAccessRequirementsSnapshot` contenant un ou plusieurs profils de
représentation ordonnés. Le provider (fournisseur) sélectionne le premier
profil qu’il peut certifier. Chaque glyphe final publié porte alors un
certificat exact de route contour, graphe de peinture, bitmap (image
matricielle) ou sans encre, lié à son `FontRenderAssetKey`. Le gestionnaire
reste la propriété de l’appelant ; la façade ne l’emprunte que pendant l’appel
synchrone.

### Posséder les ressources certifiées pour un rendu différé

Le handoff (transfert de propriété) explicite sépare deux succès. Un layout
(résultat de composition) rendable réussi reste sans ressource, mais ce premier
succès ne garantit pas que ses racines de fonte pourront être rouvertes plus
tard. Tant que le résolveur est ouvert, appelez
`openLayoutHandle(resolver)`. Ce second succès, faillible et atomique, retourne
soit un `LayoutHandle`, handle (gestionnaire de durée de vie) qui possède
toutes les racines certifiées, soit aucun gestionnaire. La même extension
existe sur `EditableLine`, `ParagraphLayout` et `FlowLayout`.

Ce contrat de propriété couvre aussi COLR version 1 au schéma 2. Le certificat
et le résultat de composition immuable ne possèdent aucune ressource de fonte.
Chaque ressource conservée depuis le `LayoutHandle` ouvert possède les données
nécessaires pour résoudre ses graphes certifiés après la fermeture de la
session de composition, du résolveur d’origine, de la ressource attachée et du
gestionnaire de résultat.

```kotlin
val layout = (renderableResult as EditableLineResult.Success).line
val handle = try {
    when (val opened = layout.openLayoutHandle(resolver)) {
        is FontOperationResult.Success -> opened.value
        is FontOperationResult.Failure -> error(opened.error.message)
        is FontOperationResult.Cancelled -> error("Asset ownership was cancelled.")
    }
} finally {
    session.close()
    resolver.close()
}

val certificatesByAsset = layout.positionedGlyphRuns
    .flatMap { run -> run.glyphs }
    .mapNotNull { glyph -> glyph.materializationCertificate }
    .groupBy { certificate -> certificate.assetKey }
val rendererAssets = mutableMapOf<FontRenderAssetKey, FontRenderAssetHandle>()
try {
    for ((key, certificates) in certificatesByAsset) {
        rendererAssets[key] = when (val retained = handle.retainFontAsset(certificates.first())) {
            is FontOperationResult.Success -> retained.value
            is FontOperationResult.Failure -> error(retained.error.message)
            is FontOperationResult.Cancelled -> error("Asset retention was cancelled.")
        }
    }

    val certificate = certificatesByAsset.values.first().first()
    val representation = rendererAssets.getValue(certificate.assetKey)
        .resolveGlyph(FontGlyphRequest(certificate.glyphId))
} finally {
    rendererAssets.values.forEach { it.close() }
    handle.close()
}
val geometryIsStillReadable = handle.layout
```

Regroupez les certificats par `assetKey` : un asset (ressource de rendu)
conservé sert tous les glyphes certifiés de cette combinaison exacte
d’instance, de variante et de profil de représentation. Chaque
`retainFontAsset(...)` réussi fournit au renderer (moteur de rendu) une
ressource possédée indépendamment et son propre lease (droit temporaire de
durée de vie). Elle reste valide après la fermeture du `LayoutHandle` et doit
être fermée par le moteur de rendu. Fermer le gestionnaire refuse les nouvelles
rétentions et libère ses racines, mais n’invalide jamais `handle.layout` : le
layout immuable reste lisible.

Note de migration : `FontError.CertificateNotInLayout` est un nouveau membre
de la sealed error surface (surface d’erreurs scellée) utilisée par ce parcours
de handoff. Ajoutez une branche lors de la recompilation d’un `when` Kotlin
exhaustif sur `FontError` ; les signatures de méthodes et JVM restent
inchangées. Un gestionnaire exhaustif déjà compilé peut lancer
`NoWhenBranchMatchedException` si le nouveau parcours lui fournit ce membre,
alors que les appels existants ne commencent pas automatiquement à le retourner.

Le gestionnaire possède de la mémoire externe du consommateur, hors du budget
du cache (mémoire interne de réutilisation). L’annulation est observée entre
les appels indivisibles au fournisseur ; elle n’interrompt pas un `reopen`,
`detach` ou `close` déjà en cours. La factory (fabrique) initiale réalise
actuellement son acquisition atomique par réouverture et détachement, mais ces
opérations ne font pas partie de l’abstraction `LayoutHandle`. Cette API
n’attribue pas ces ressources à une session de composition et n’introduit
aucune politique de GPU, d’atlas, de rendu de plateforme ou de rendu.

Le backend HarfBuzz 14.3.0 embarqué est l’implémentation de référence. Ses
ressources Linux et macOS x64/arm64 et Windows x64 sont fournies par la liaison
publiée `org.graphiks:kffi-harfbuzz`, et Android embarque le même moteur via
l’artefact `org.graphiks:kffi-harfbuzz-android`. iOS embarque le même moteur,
lié statiquement via cinterop, sous les artefacts
`org.graphiks:kffi-harfbuzz-iosarm64` (appareil) et
`org.graphiks:kffi-harfbuzz-iossimulatorarm64` (simulateur) ; la bibliothèque est
vérifiée par hash (empreinte cryptographique) au chargement et jamais recherchée
dans les bibliothèques du système. Le socle Android partagé est l’API 28, relevé
depuis l’API 24 — un changement cassant délibéré pour les consommateurs API
24–27. Les contrats publics ne contiennent ni type JNI ni type natif, et aucune
plateforme n’est laissée sans backend de shaping embarqué.

Une location de variation non-défaut descend jusqu’à la fonte HarfBuzz préparée sur
JVM, Android et iOS via `setVarCoordsNormalized` de la liaison republiée
`org.graphiks:kffi-harfbuzz`, appliquée selon l’ordre des axes `fvar` en virgule
fixe 2.14. Une location vide ou explicitement au défaut (face non-variable,
instance par défaut, ou `[0.0]`) ne change pas le rendu : le chemin par défaut
reste identique au bit près. L’avance verticale (et les bornes de la
spécification) ne sont pas vérifiées : la requête d’avance verticale n’est pas
exposée par la liaison.

## Repli déterministe entre fontes

`Kalligraphie.embedded(sources)` capture plusieurs sources OpenType auditées
dans une `FontCatalogGeneration` (génération immuable du catalogue).
`FontResolutionPolicySnapshot` associe à cette génération un ordre total de
candidats versionné et une face explicite de dernier recours. La façade JVM de
ligne éditable dérive des unités de repli à partir de l’analyse réelle des
grappes de graphèmes Unicode, attribue chaque unité à une seule face, puis
compose le contexte contigu affecté.

En mode `LAYOUT_ONLY`, un candidat doit couvrir et composer toute l’unité. En
mode `RENDERABLE`, il doit aussi matérialiser chaque glyphe final composé dans
un profil de représentation accepté. Les candidats rejetés sont placés dans une
blacklist (liste d’exclusion) propre à l’opération et ne sont jamais réessayés
silencieusement pour la même unité et le même profil. Chaque
`PositionedGlyphRun` publié identifie sa `FontInstanceKey` (clé d’instance de
fonte) réelle ; chaque glyphe rendable porte un certificat lié à sa clé d’asset
(ressource) et à sa génération exactes. Un gestionnaire peut rouvrir cette clé
uniquement dans la génération capturée ; un asset détaché reste utilisable de
façon indépendante après la fermeture de son gestionnaire d’origine.

Hors périmètre de l’API de ligne éditable : césure,
justification, écriture verticale, rendu en pixels, API GPU, TTC/OTC,
CFF/CFF2, variations et styles synthétiques. Consultez
[Paragraphes éditables](editable-paragraphs.fr.md) pour le parcours multiligne
JVM.
