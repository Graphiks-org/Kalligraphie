# Gestion des fontes

Kalligraphie propose une prise en charge de fontes TrueType embarquées via
`org.graphiks:kalligraphie`, uniquement sur la cible de référence de la
machine virtuelle Java (JVM). Les contrats publics restent portables, mais
cette prise en charge exécutable est limitée à la JVM. L’utilisateur de la
bibliothèque fournit des octets SFNT capturés à `Kalligraphie.embedded(...)`,
sélectionne un enregistrement de face stable, crée une instance de fonte, puis utilise une
ressource de rendu pour matérialiser une représentation portable de glyphe.

Le périmètre fonctionnel supporté est volontairement étroit :

- cible JVM de référence uniquement ;
- fontes TrueType SFNT statiques uniquement : `0x00010000` et `true` ;
- des sources OpenType embarquées, avec l’index de face `0` pour chaque source ;
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
  (gradient radial) et `stop` (arrêt de couleur), appliqué uniquement aux éléments `rect` (rectangles)
  auto-fermants. Les chemins et rectangles acceptent un remplissage opaque
  `#RRGGBB` ou `fill="none"` ; un rectangle peut aussi référencer un gradient
  linéaire ou radial concentrique local défini auparavant. Les transformations
  `translate`, `scale`, `rotate`, `skewX`, `skewY` et `matrix` (matrice affine
  à six coefficients) sont prises en charge. Le sous-ensemble exact des
  gradients et les exclusions restantes sont décrits ci-dessous ;
- strikes bitmap (images matricielles, tailles bitmap exactes) EBLC version 2 / EBDT version 2,
  avec sous-table d’index format 1 et image format 1 uniquement : alpha un bit
  aligné sur les octets, décodé en `ALPHA_8` sRGB, pour un strike demandé à
  l’identique ;
- ressources de rendu détachées qui restent utilisables après la fermeture du
  gestionnaire propriétaire ou de la ressource attachée.

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
le même document ; un remplissage absent utilise du noir opaque par défaut. Les
coordonnées du gradient utilisent uniquement
`objectBoundingBox` (boîte englobante de l’objet) : les valeurs sans unité et
les pourcentages sont résolus relativement au rectangle sans être bornés à sa
boîte unité. Les valeurs linéaires par défaut sont `x1=0%`, `y1=0%`,
`x2=100%` et `y2=0%`. Les valeurs radiales par défaut sont `cx=50%`, `cy=50%`,
`r=50%`, `fx=cx` et `fy=cy`. Les deux types utilisent par défaut
`spreadMethod=pad` et l’interpolation sRGB.

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

Les attributs de groupe `transform` et les deux types de gradient partagent les
mêmes opérations SVG prises en charge. Un gradient accepte un attribut
`gradientTransform` (transformation du repère du gradient) absent, vide ou
composé uniquement d’espaces XML comme identité. Sinon, sa `transform-list`
(liste SVG de transformations) peut contenir `translate` (translation),
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
et empêche la publication de la ressource complète. Les quarts de tour impairs
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
`skewX` ou `skewY` coûte donc exactement une opération. Les listes de groupe et
de gradient partagent ce budget, et le fallback (repli) de profil recommence la
validation sans publier de donnée partielle.

Avec des vecteurs-colonnes, la transformation de peinture vaut `T * B * G` :
`T` est la transformation de groupe effective du rectangle, `B` applique sa
boîte englobante normalisée et `G` compose `gradientTransform` dans l’ordre
source. `G` modifie uniquement la géométrie du gradient ; le chemin de découpe
du rectangle reste soumis à `T` seul. Un gradient linéaire incorpore cette
transformation dans `p0`, `p1` et `p2`, sans nœud `Transform` supplémentaire.
Un gradient radial conserve ses cercles normalisés et place la même
transformation sur son unique nœud `Transform` existant.

Une définition sans arrêt ne
produit aucune encre. Pour un rectangle dont la transformation de groupe
effective `T` préserve l’aire, un gradient linéaire à un seul arrêt ou un
vecteur source aux extrémités identiques est normalisé avec le dernier arrêt en
`Solid` (peinture unie), sous
le `PathClip` (découpe par chemin) du rectangle. Pour tout autre gradient linéaire d’au
moins deux arrêts, Kalligraphie résout d’abord ses points normalisés `p0` et
`p1` ; s’ils coïncident, il applique la même réduction en peinture unie, sinon
il produit un `LinearGradient` (gradient linéaire) sous ce chemin rectangulaire.
Avant cette production, les points normalisés `p0`, `p1` et `p2` doivent former
un triplet non colinéaire. Un triplet colinéaire retourne
`font.svg.invalid-gradient` et aucune ressource partielle n’est publiée. Une
transformation de groupe effective `T` singulière omet un rectangle ou chemin
rempli après validation de sa géométrie, de son remplissage et de toute
référence de peinture.

Un rayon radial `r < 0` constitue une donnée invalide. Avec un seul arrêt ou
`r == 0`, le gradient est pareillement réduit au dernier arrêt sous forme de
`Solid` sous un `PathClip`. Avec `r > 0`, le foyer doit être exactement
concentrique après l’analyse numérique (`fx == cx` et `fy == cy`). Tout foyer
décalé est un SVG valide hors de ce sous-ensemble : il retourne
`UnsupportedRepresentationProfile` sans être ramené dans le cercle. Une
peinture radiale non dégénérée conserve ses deux cercles normalisés
(`c0=(fx,fy), radius0=0` et `c1=(cx,cy), radius1=r`) sous un nœud `Transform`
(transformation du repère enfant vers le repère parent). Cette transformation
porte `T * B * G`, tandis qu’un `PathClip` contenant le chemin rectangulaire
transformé uniquement par `T` découpe le résultat.
Ce modèle préserve l’ellipse produite par un rectangle non carré.

Chaque gradient linéaire ou radial déclaré exige un `PaintGraphProfile` de schéma 3
exact. Lorsque la normalisation produit réellement un `LinearGradient`, le
profil doit accepter l’espace d’interpolation, le mode d’interpolation d’alpha
`UNPREMULTIPLIED` (non prémultipliée : les composantes RGB et l’alpha sont
interpolés séparément) et le mode d’extension atteints, ainsi que `LINEAR_GRADIENT`
et `PATH_CLIP`. Un `RadialGradient` produit exige les mêmes capacités
d’interpolation et d’extension, ainsi que `RADIAL_GRADIENT`, `TRANSFORM` et
`PATH_CLIP`. Une réduction en peinture unie exige à la place `SOLID` et
`PATH_CLIP`, mais pas l’espace d’interpolation ni le mode d’interpolation
d’alpha ou d’extension de la définition. Les rectangles unis utilisent
`PATH`. Un document qui possède plusieurs racines peintes exige aussi `GROUP` et
`SOURCE_OVER`. Les limites existantes sont contrôlées
avant publication : les octets source et décodés, transformations, définitions
de gradient et arrêts analysés, ainsi que les nœuds, références, chemins,
découpes, gradients, arrêts de couleur et profondeurs produits doivent tous
respecter les bornes. Les visites de peinture sont également bornées à partir
du schéma 2 ; le schéma 1 conserve ses contrôles historiques des nœuds et de la
profondeur sans appliquer `maxPaintVisits`. Chaque chemin rectangulaire créé
doit aussi respecter l’`outlineProfile` (profil de contours) du profil. Un
nœud `Transform` radial produit compte dans `maxTransforms`, indépendamment
des opérations SVG `translate`, `scale`, `rotate` et `matrix` déclarées par les
groupes ou les gradients. Chaque opération déclarée complète compte une fois
dans le budget
source `maxSvgTransformOperations`, partagé à l’échelle de la table, lors de
l’analyse de sa définition, y compris une opération identité ou une définition
inutilisée. Un appel à trois opérandes `rotate(angle cx cy)` compte toujours
une seule fois ; réutiliser une définition ne la facture pas de nouveau. Le
repli ordonné entre profils peut donc ignorer un profil de schéma 3 qui ne
déclare pas chaque capacité atteinte et sélectionner un profil compatible
ultérieur.

Tous les identifiants d’élément acceptés sur `svg`, `g`, `linearGradient` et `radialGradient`
sont globalement uniques. L’unicité des cibles par identifiant de glyphe reste
un invariant distinct. Les références de peinture sont locales, limitées à un
fragment `#id` et uniquement dirigées vers une définition antérieure. Une
référence non résolue, future, externe ou contenant autrement une URI échoue
avant la publication d’une ressource, même si le rectangle ne devait ensuite
produire aucune encre. Une entrée mal formée ou non prise en charge ne publie
jamais de graphe partiel.

Le sous-ensemble ne prend pas en charge `viewBox`, `userSpaceOnUse`,
`href`, `xlink:href`, le rayon focal `fr`, les foyers
radiaux non concentriques, les remplissages par gradient sur `path`, CSS ou les attributs `style`, les découpes SVG générales
ou chemins de découpe, les masques, contours tracés, scripts, entités,
animations, ressources externes, ni les éléments et attributs non déclarés.
Les formats de compression autres que le transport gzip mono-membre autorisé
restent refusés.

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
acceptée. Les formats variables `PaintVar*`, `ClipBox` format 2, les magasins
et tables d’index de variations, CFF/CFF2 et les valeurs CPAL/COLR variables ne
sont pas pris en charge. Le parcours SVG-in-OpenType distinct accepte les
gradients linéaires et radiaux concentriques statiques, bornés par un
rectangle, décrits plus haut via le
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

Ces limites portent sur les représentations évictables, leurs clés et diagnostics, pas sur les sources capturées, les ressources possédées par le consommateur ou la mémoire totale du processus. Aucune entrée ne possède de gestionnaire, ressource de rendu, catalogue ou ressource native. La fermeture du dernier lease (droit temporaire de durée de vie) de gestionnaire ou de ressource d'une face libère les entrées de cette face ; les ressources détachées conservent leur lease indépendant. Les autres faces restent utilisables.

Les catalogues ne partagent pas encore de budget au niveau provider/engine (fournisseur/moteur). Cette portée de propriété et la participation des ressources natives seront introduites avec une route native. Les tests de glyphes démontrent la transparence observable ; ils ne mesurent pas la rétention et ne prouvent pas l'admission du cache. La comptabilité appartient à une future instrumentation opt-in (activée explicitement), hors `check`.

Sur macOS, l’artefact JVM expose aussi `MacosSystemFontCatalog.open()`. Il
capture, sous limites, les fichiers `.ttf` réguliers dans un instantané
portable et utilise les mêmes routes que les fontes embarquées. Il n’expose pas
de handle (gestionnaire de durée de vie ; ici, poignée native) CoreText et ne
déclare pas de prise en charge de `.otf` ni
de `.ttc`.

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
        featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
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
aucune politique de GPU, d’atlas, de rendu natif ou de rendu.

Le backend HarfBuzz 14.3.0 embarqué est l’implémentation de référence JVM. Ses
ressources Linux et macOS x64/arm64 sont épinglées, vérifiées par hash
(empreinte cryptographique) et jamais recherchées dans les bibliothèques du
système. Les contrats publics ne contiennent ni type JNI ni type natif.
Android et Apple ne possèdent pas encore d’adapter (adaptateur de plateforme)
de composition exécutable : ce parcours ne doit donc pas être considéré comme
conforme sur ces plateformes.

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
