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
  `BitmapProfile` de version de schéma `1`, si la face sélectionnée déclare la
  route correspondante ;
- contours `glyf` exprimés en unités de conception (unités internes de la
  fonte), avec des métriques mises à l’échelle séparément en `LayoutUnit` ;
- graphes de peinture COLR version 0 et CPAL version 0, composés de contours
  pleins, de groupes ordonnés, d’une sélection exacte de palette CPAL et d’une
  couleur de premier plan explicite ;
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

Ces limites portent sur les représentations évictables, leurs clés et diagnostics, pas sur les sources capturées, les ressources possédées par le consommateur ou la mémoire totale du processus. Aucune entrée ne possède de gestionnaire, ressource de rendu, catalogue ou ressource native. La fermeture du dernier lease (droit de durée de vie) de gestionnaire ou de ressource d'une face libère les entrées de cette face ; les ressources détachées conservent leur lease indépendant. Les autres faces restent utilisables.

Les catalogues ne partagent pas encore de budget au niveau provider/engine (fournisseur/moteur). Cette portée de propriété et la participation des ressources natives seront introduites avec une route native. Les tests de glyphes démontrent la transparence observable ; ils ne mesurent pas la rétention et ne prouvent pas l'admission du cache. La comptabilité appartient à une future instrumentation opt-in (activée explicitement), hors `check`.

Sur macOS, l’artefact JVM expose aussi `MacosSystemFontCatalog.open()`. Il
capture, sous limites, les fichiers `.ttf` réguliers dans un instantané
portable et utilise les mêmes routes que les fontes embarquées. Il n’expose pas
de handle (poignée) CoreText et ne déclare pas de prise en charge de `.otf` ni
de `.ttc`.

Hors périmètre : TTC/OTC, CFF/CFF2, variations, styles synthétiques, versions
de COLR autres que 0, contenu SVG hors du sous-ensemble déclaré, codecs et
formats bitmap autres que la route EBLC/EBDT déclarée, ajustement des contours
aux pixels (hinting), rastérisation, moteurs natifs de gestion des fontes et
descripteurs de fonte propres à la plateforme.

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
[Paragraphes éditables](editable-paragraphs.md) pour le parcours multiligne
JVM.
