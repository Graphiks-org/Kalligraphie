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
  qu’un graphe de schéma 2 pour COLR version 1 statique avec CPAL version 0 ou
  1 ;
- table SVG-in-OpenType version 0 avec documents UTF-8 bruts uniquement :
  éléments `svg` et `g` non auto-fermants, et éléments `path` auto-fermants ;
  transformations `translate` et `scale` ; commandes de chemin `M`, `L`, `H`,
  `V`, `C`, `S` et `Z` ; remplissages opaques `#RRGGBB`, ou `fill="none"` pour
  un chemin explicitement sans encre. Les scripts, ressources externes, entités,
  animations, compression, gradients, clips (découpes), masques, contours tracés et
  attributs non déclarés sont refusés avant publication d’une ressource ;
- strikes bitmap (images matricielles, tailles bitmap exactes) EBLC version 2 / EBDT version 2,
  avec sous-table d’index format 1 et image format 1 uniquement : alpha un bit
  aligné sur les octets, décodé en `ALPHA_8` sRGB, pour un strike demandé à
  l’identique ;
- ressources de rendu détachées qui restent utilisables après la fermeture du
  gestionnaire propriétaire ou de la ressource attachée.

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
  Les graphes COLR version 0 et SVG-in-OpenType restreint existants restent
  représentables avec ce schéma ;
- le schéma 2 ajoute les peintures non bornées `Solid`, `LinearGradient`,
  `RadialGradient` et `SweepGradient`, ainsi que `GlyphClip`, `Transform` et
  `Composite`, les extensions de gradient `PAD`, `REPEAT` et `REFLECT`, et les
  28 valeurs nommées de `GlyphPaintCompositionMode`. Le fournisseur ne
  certifie le graphe que si tout son contenu atteignable respecte les types de
  nœud, extensions, modes de composition et limites du `PaintGraphProfile`
  exact. Un `Group` vide de schéma 2 représente `no paint` (absence de
  peinture) et reste structurellement borné.

Les parcours JVM pour fontes embarquées et fichiers `.ttf` capturés acceptent
les structures globales COLR version 1 statiques et les formats de peinture
`1`, `2`, `4`, `6`, `8`, `10`, `11`, `12`, `14`, `16`, `18`, `20`, `22`,
`24`, `26`, `28`, `30` et `32`. Les peintures affine, translation, mise à
l’échelle, rotation et inclinaison spécialisées sont normalisées en un
`GlyphAffineTransform` fini ; les références à d’autres glyphes COLR sont
résolues dans le graphe et ne sont pas exposées comme références à la table
source. Un enregistrement `PaintColrLayers` valide de format 1 avec zéro couche
est préservé sous forme d’un graphe de peinture à un seul nœud `Group` vide ;
il n’est pas réduit à `GlyphRepresentation.Empty`. La `ClipList` (liste de
découpes) de format 1 avec une `ClipBox` (boîte de découpe) de format 1 est
acceptée. Les formats variables `PaintVar*`, `ClipBox` format 2, les magasins
et tables d’index de variations, CFF/CFF2 et les valeurs CPAL/COLR variables ne
sont pas pris en charge. Le sous-ensemble SVG-in-OpenType reste inchangé et
n’acquiert ni gradients SVG, ni `clip` (découpe), masque, contour tracé ou
animation.

La palette et la couleur de premier plan sont résolues avant publication. Une
valeur nulle de `FontRenderVariantSnapshot.cpalPaletteIndex` sélectionne la
palette 0 ; un index explicite sélectionne cette palette CPAL exacte ou échoue.
L’index CPAL `0xFFFF` est remplacé par la valeur exacte de `foregroundColor`
(couleur de premier plan) de la variante, ou par du noir opaque si elle est
nulle. Le graphe contient donc des couleurs sRGB huit bits littérales et non
prémultipliées dans `GlyphColor`, jamais des index de palette. L’opacité d’un
nœud ou d’un arrêt reste une valeur finie séparée dans `0.0..1.0`. Pour
interpoler un gradient, le moteur de rendu doit linéariser les composantes sRGB
de chaque arrêt, calculer son alpha effectif comme
`color.alpha / 255 * opacity`, puis prémultiplier les composantes RGB
linéaires par cet alpha avant l’interpolation. Il interpole séparément le RGB
prémultiplié et l’alpha, puis déprémultiplie en traitant l’alpha nul et convertit
depuis la lumière linéaire vers l’encodage de sortie requis. Changer la palette
ou le premier plan modifie les couleurs littérales et l’identité de la
ressource, mais pas la composition, les avances, les positions du `caret`
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

`GlyphPaintIR.clipBounds`, lorsqu’il existe, applique une découpe à tout le
résultat de la racine. Sans ces limites racine, un graphe de schéma 2 n’est
accepté que si sa racine atteignable est structurellement bornée :
`SolidOutline`, `Path` et `GlyphClip` sont bornés ; `Solid` et les trois
gradients ne le sont pas ; `Transform` conserve la bornitude de son enfant ;
un `Group` n’est borné que si tous ses enfants le sont. Un `Group` vide de
schéma 2 est donc borné. Pour les compositions, `CLEAR` est toujours borné ;
`SOURCE` et `SOURCE_OUT` suivent la source ;
`DESTINATION` et `DESTINATION_OUT` suivent le fond ; `SOURCE_IN` et
`DESTINATION_IN` sont bornés si l’une des deux entrées l’est ; tous les autres
modes exigent que les deux entrées soient bornées. Une découpe racine borne
toute combinaison par ailleurs acceptée.

`PaintGraphLimits` est appliqué avant la certification. Il borne les nœuds,
références, profondeurs et visites développées du graphe ; les chemins,
gradients, arrêts de couleur, transformations, compositions et découpes de
glyphe ; les octets source ; les palettes CPAL, leurs entrées, leurs
enregistrements de couleur et leurs octets décodés ; les enregistrements COLR
de glyphes de base, de couches et de découpes ; ainsi que chaque contour
référencé via `outlineProfile`. Un dépassement renvoie
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
