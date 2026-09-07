# Paragraphes éditables

Kalligraphie fournit un parcours de référence JVM pour composer un paragraphe
multiligne, immuable et éditable. Il prolonge le parcours de ligne
éditable exacte présenté dans la [Gestion des fontes](font-management.md) : la
façade publique `JvmEditableParagraphFacade` effectue, pour un appel, l’analyse
ICU et la composition HarfBuzz, puis retourne des valeurs portables et
indépendantes du renderer (moteur de rendu). Elle ne conserve ni handle natif,
ni gestionnaire de ressources, ni backend, ni renderer, ni objet de plateforme.

## Composer un paragraphe

Créez un `TextSnapshot` (instantané de texte) immuable, un catalogue ordonné de
fontes embarquées et une politique dont le dernier candidat est une fonte de
secours explicite. L’ordre fourni à `Kalligraphie.embedded(...)` est préservé ;
utilisez-le pour construire la `FontResolutionPolicySnapshot` complète.

```kotlin
val decoded = Kalligraphie.decodeUtf16(
    version = TextVersion.create(),
    slices = listOf(TextSlice.Utf16(editorText.toCharArray())),
)
val catalog = requireSuccess(
    Kalligraphie.embedded(
        listOf(
            FontSource(latinBytes, FontSourceProvenance("Latin")),
            FontSource(arabicBytes, FontSourceProvenance("Arabic")),
        ),
    ),
)
val candidates = catalog.faces.map { FontResolutionCandidate(it.id) }
val policy = FontResolutionPolicySnapshot(
    generation = catalog.generation,
    policyId = "editor-fallback",
    version = "1",
    candidates = candidates,
    lastResortFace = candidates.last().faceId,
)
val lineMetrics = LineVerticalMetrics(
    ascent = LayoutUnit(900f),
    descent = LayoutUnit(300f),
)

val result = JvmEditableParagraphFacade.layout(
    JvmEditableParagraphFacadeRequest(
        snapshot = decoded.snapshot,
        constraints = HorizontalParagraphConstraints(
            region = LayoutRect(
                left = LayoutUnit(100f),
                top = LayoutUnit(50f),
                right = LayoutUnit(1_500f),
                bottom = LayoutUnit(2_450f),
            ),
            lineMetrics = lineMetrics,
        ),
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        fontCatalog = catalog,
        resolutionPolicy = policy,
        fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
        materialization = EditableLineMaterialization.LayoutOnly,
    ),
)
```

`requireSuccess(...)` représente du code applicatif qui extrait un
`FontOperationResult.Success` ; un appelant de production doit traiter les
échecs de fonte typés plutôt que de supposer que le catalogue s’ouvre toujours.

Pour un `ParagraphLayoutResult.Success`, `layout.lines` ne contient que des
lignes finales complètes. Chaque `LineLayout` est exprimé dans les coordonnées
physiques du paragraphe et sépare :

- `contentMetrics`, les métriques du contenu typographique final ;
- `lineBox`, l’espace de composition et de hit-testing (test de point) ;
- `designInkBounds`, l’union déterministe des bounds (bornes) des glyphes
  placés.

Le `ParagraphLayout` obtenu fournit la navigation logique et visuelle des
carets (repères d’insertion), tous les candidats aux frontières BiDi ambiguës,
`selectionGeometry(...)` et un `hitTest(...)` déterministe. Les résultats sont
immuables et liés à la version du snapshot d’entrée.

## Garanties de coupure et de composition

Le parcours JVM analyse les opportunités légales UAX #14 avec des frontières
`TextIndex` versionnées, jamais des offsets UTF-16. Il choisit le dernier
candidat légal qui tient dans la région ; si la première unité légale est plus
large que la région, cette unité complète est publiée afin de garantir la
progression.

Les grappes de graphèmes étendues, les variation selectors (sélecteurs de
variation) et les séquences emoji ZWJ ne sont jamais coupés. Les candidats de
ligne finaux sont recomposés dans leur propre contexte local ; l’information
HarfBuzz unsafe-to-break (coupure non sûre) peut imposer un candidat antérieur.
UAX #9 est ensuite appliqué à chaque ligne finale, y compris les espaces de fin
de ligne et l’ordre visuel final. Une unité de repli est affectée à une seule
fonte sélectionnée, tandis qu’un paragraphe peut employer plusieurs fontes.

## Continuer une région trop basse

`OverflowPolicy.CONTINUE` est la seule politique de débordement. Si la hauteur
fournie ne contient pas toutes les lignes complètes, un succès possède
`coverageStatus == CoverageStatus.PARTIAL` et une `LayoutContinuation`
immuable. Réutilisez sa plage restante et son origine physique exacte :

```kotlin
val partial = result as? ParagraphLayoutResult.Success
    ?: error("Traitez d’abord un échec ou une annulation.")
val continuation = checkNotNull(partial.continuation)
val resumedRegion = LayoutRect(
    left = continuation.regionLeft,
    top = continuation.resumptionRegionTop,
    right = LayoutUnit(continuation.regionLeft.value + continuation.regionWidth.value),
    bottom = LayoutUnit(continuation.resumptionRegionTop.value + 1_200f),
)
val resumed = JvmEditableParagraphFacade.layout(
    JvmEditableParagraphFacadeRequest(
        snapshot = decoded.snapshot,
        sourceRange = continuation.remainingSourceRange,
        constraints = HorizontalParagraphConstraints(resumedRegion, lineMetrics),
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        fontCatalog = catalog,
        resolutionPolicy = policy,
        fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
        materialization = EditableLineMaterialization.LayoutOnly,
        continuation = continuation,
    ),
)
```

La demande reprise doit conserver le même snapshot, catalogue, politique de
repli, direction, langue, features (fonctionnalités OpenType), descripteur
d’instance de fonte et identité de matérialisation. Une autre version, plage
restante, origine gauche, top de reprise, largeur, métrique de ligne ou
configuration de composition est refusée comme entrée invalide. Concaténer le
préfixe publié et un résultat repris compatible est observable comme une seule
composition dans une région assez haute.

## Composer dans des régions avec exclusions

Utilisez `JvmFlowCompositionFacade` lorsque l’application fournit plusieurs
régions de composition ou exclut une partie de l’espace en ligne dans une
bande. Une `FlowRegion` est un fournisseur de géométrie immuable, pur,
déterministe et sûr pour les accès concurrents. Son
`FlowRegionIdentity` opaque doit changer dès que ses bornes ou son comportement
de requête changent.

Les requêtes emploient des axes logiques. `LineBand.blockStart` et
`blockExtent` décrivent la boîte de ligne candidate dans la progression de
bloc ; chaque `InlineInterval` semi-ouvert décrit l’espace disponible dans la
progression en ligne. Le même contrat prend donc en charge `HORIZONTAL_TB`,
`VERTICAL_RL` et `VERTICAL_LR` sans convention de coordonnées dépendante du
moteur de rendu.

```kotlin
class ArticleRegion(
    override val bounds: LayoutRect,
    private val exclusionStart: Float,
    private val exclusionEnd: Float,
) : FlowRegion {
    override val identity = FlowRegionIdentity.create()
    private val horizontalInlineExtent = bounds.right.value - bounds.left.value

    override fun query(
        writingMode: WritingMode,
        lineBand: LineBand,
    ): FlowRegionResult =
        if (lineBand.blockStart < 1_200f &&
            lineBand.blockStart + lineBand.blockExtent > 400f
        ) {
            FlowRegionResult.AvailableIntervals(
                listOf(
                    InlineInterval(0f, exclusionStart),
                    InlineInterval(exclusionEnd, horizontalInlineExtent),
                ),
            )
        } else {
            FlowRegionResult.AvailableIntervals(
                listOf(InlineInterval(0f, horizontalInlineExtent)),
            )
        }
}

val chain = FlowChain(
    regions = listOf(firstRegion, secondRegion),
    fragmentationConstraints = FragmentationConstraints(
        minLinesAtStart = 2,
        minLinesAtEnd = 2,
        keepTogether = false,
    ),
)
val portable = requireFlowSuccess(createIncrementalFlowLayoutRequest(
    input = LayoutInput(decoded.snapshot, typography),
    requestedRange = visibleSourceRange,
    constraints = paragraphConstraints,
    flowChain = chain,
    overscan = LineOverscan(2),
))
val flow = JvmFlowCompositionFacade.layout(
    JvmFlowCompositionRequest(
        request = portable,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "fr",
    ),
)
```

Dans cet exemple, `typography` est un `TypographySnapshot` construit avec le
même catalogue, la même politique de repli, la même instance de fonte et les
mêmes fonctionnalités OpenType que le parcours rectangulaire.
`requireFlowSuccess(...)` représente du code applicatif qui extrait un succès ;
un appelant de production doit traiter chaque échec typé. L’exemple de
région est horizontal ; un fournisseur indépendant du mode d’écriture doit
déduire son étendue logique en ligne de `writingMode`, au lieu de toujours
employer la largeur physique.

La réponse de région validée est exactement l’une des suivantes :

- `AvailableIntervals`, avec des intervalles finis, non vides, ordonnés,
  disjoints et compris dans la région ;
- `Empty(nextBlockOffset)`, qui progresse strictement et avec une coordonnée
  finie sur l’axe de bloc ;
- `EndOfRegion`.

Kalligraphie ne trie, ne fusionne, ne rogne et ne répare jamais une réponse
incorrecte. Le moteur interroge d’abord l’étendue minimale de la boîte de
ligne, compose une ligne logique candidate, puis interroge à nouveau le même
départ de bloc avec une étendue réelle non décroissante. Il accepte uniquement
une plage source, une bande et des intervalles stables. Une réponse instable ou
non monotone, un cycle ou un dépassement de raffinement, un rétrécissement de
bande, une géométrie non finie ou en débordement et une absence de progression
produisent une `FlowCompositionError` typée ; aucun résultat approximatif
n’est publié.

## Lire la géométrie fragmentée

Un `FlowLayout` réussi contient des `ParagraphFragment` ordonnés selon la
source. Chaque fragment enregistre la plage exacte du paragraphe et la plage
composée, ses indicateurs premier/dernier, ses diagnostics structurés et son
éventuelle continuation. Ses `LineLayout` restent des lignes logiques. Quand
une exclusion fournit plusieurs intervalles, une ligne contient plusieurs
`LineFragment` géométriques.

La résolution BiDi (bidirectionnelle) du paragraphe, le choix de la ligne et
les étapes L1–L4 de l’UAX #9 sont appliqués une seule fois à cette ligne
logique. Les séquences visuelles sont ensuite réparties entre les intervalles
et ne peuvent être coupées qu’aux frontières sûres de grappes. Une grappe de
graphèmes, une ligature ou un objet dans la ligne n’est jamais partagé entre
deux fragments, et une frontière géométrique ne crée jamais de `TextIndex`.

Employez les opérations d’édition de chaque `LineLayout` publié ; les valeurs
rectangulaires `ParagraphLayout` délèguent à la même géométrie finale de ligne.
`selectionGeometry(...)` retourne uniquement des rectangles de fragments
occupés et ne remplit donc pas une exclusion. Un `hitTest(...)` dans cet espace
exclu choisit le repère d’insertion valide le plus proche selon une règle de
départage déterministe.

## Fragmentation et continuations exactes

`FragmentationConstraints` demande `minLinesAtStart`, `minLinesAtEnd`,
`keepTogether` et `keepWithNext`. Quand les régions disponibles ne permettent
pas de tout satisfaire, Kalligraphie conserve toute la source et relâche les
règles dans cet ordre déterministe : `KEEP_WITH_NEXT`, `KEEP_TOGETHER`,
`MIN_LINES_AT_END`, puis `MIN_LINES_AT_START`. Chaque relâchement apparaît sous
la forme d’un `FlowCompositionDiagnostic.FragmentationRelaxed`. La façade JVM
pour un seul paragraphe ne peut pas prouver de relation avec le paragraphe
suivant ; une demande `keepWithNext` est donc signalée par le même mécanisme de
relâchement.

Une `FlowContinuation` est une capacité immuable et non une clé textuelle
définie par l’appelant. Elle lie les révisions exactes du texte et de la
typographie, le suffixe du paragraphe, toutes les entrées nécessaires à une
reprise identique, les identités de la chaîne et de la région, l’état de
fragmentation, le mode d’écriture, l’indice de région et le curseur de bloc.
Une réutilisation étrangère, obsolète, contradictoire ou insuffisamment prouvée
est rejetée par une erreur typée avant toute requête de région ou composition.
Conserver la continuation ne conserve ni le texte, ni le fournisseur de
région, ni une page, ni le moteur de rendu, ni une ressource native.

## Couverture bornée et recomposition en aval

`requestedRange` demande les lignes complètes qui contiennent la plage source
visée ; `LineOverscan` ajoute un nombre borné de lignes complètes après cette
plage, comme marge de préchargement. Pour un `FlowLayout` réussi,
`coverage` décrit exactement le préfixe publié. Quand le paragraphe physique se
poursuit, `unmaterializedTail` contient la `FlowContinuation` exacte du suffixe
non matérialisé au lieu de masquer une troncature.

Conservez `FlowLayout.state` pour la requête suivante. Avec les mêmes entrées,
une nouvelle requête peut étendre la couverture sans reconstruire une
couverture déjà suffisante. Après une édition, fournissez cet état avec un
`LayoutDelta` faisant autorité et menant au nouveau `LayoutInput`. Le moteur
reprend au dernier point de contrôle valide avant la première dépendance
affectée, recompose en aval, puis s’arrête à la convergence sémantique ou quand
la couverture demandée et sa marge sont complètes. La couverture publiée est
observable comme celle d’une composition complète avec les mêmes entrées ; une
édition pathologique peut néanmoins imposer une reprise depuis le début.
L’annulation retourne une erreur typée et ne modifie jamais le façonnage, les
coupures, les positions, les repères d’insertion ou les règles de fragmentation
pour respecter une contrainte de temps.

## Périmètre et limites

Les façades exécutables sont des API de référence JVM ; les contrats et la
géométrie retournée restent portables et sans ressource. Kalligraphie compose
exactement dans les régions fournies par l’application, mais ne crée pas les
pages et ne possède pas leur placement global. Elle ne possède pas non plus le
document mutable, la fenêtre d’affichage, le défilement, l’ordonnanceur, le
moteur de rendu ou une API GPU. Consultez la
[Typographie avancée](advanced-typography.fr.md) pour la césure, la justification,
l’ellipsis (points de suspension), les objets dans la ligne, l’écriture
verticale et l’équivalence incrémentale.
