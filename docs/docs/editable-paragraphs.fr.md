# Paragraphes éditables

Kalligraphie fournit un parcours de référence JVM pour composer un paragraphe
multiligne horizontal, immuable et éditable. Il prolonge le parcours de ligne
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

## Périmètre et limites

Il s’agit d’une API de référence JVM pour un paragraphe horizontal dans une
région rectangulaire. Elle ne rend pas de pixels et ne possède aucun état
d’éditeur. La césure, la justification, l’écriture verticale, `CLIP`,
`ELLIPSIS`, la pagination, `FlowRegion`/`FlowChain`, les caches, les benchmarks,
les façades exécutables non JVM et les API de layout incrémental restent hors
périmètre.
