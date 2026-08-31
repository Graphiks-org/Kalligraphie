# Task 2 — Exact coverage, checkpoints and bounded reuse engine

## État

DONE après fix round 1/5. L’engine commun et synchrone matérialise une cible bornée avec
overscan, publie un suffixe explicite et ne dérive la reprise que du `LayoutStateHandle` et de la
requête. Les vérifications JVM et iOS sont vertes.

## Commits

- `031b185 feat(font): add incremental paragraph checkpoints`
- `97d4dac fix(font): bound incremental paragraph reuse`

## Preuves RED

RED initial de l’unité :

```text
rtk ./gradlew :kalligraphie:layout:jvmTest --tests org.graphiks.kalligraphie.layout.IncrementalParagraphLayoutEngineTest --no-daemon
```

Résultat observé : exit code 1 dans `:kalligraphie:layout:compileTestKotlinJvm`; les références
absentes correspondaient à l’engine, au computer, aux diagnostics, aux lignes, à la couverture et
au suffixe exact.

RED du fix round 1/5, après ajout des quatre régressions :

```text
rtk ./gradlew :kalligraphie:layout:jvmTest --tests org.graphiks.kalligraphie.layout.IncrementalParagraphLayoutEngineTest --no-daemon
```

Résultat observé : exit code 1 dans `:kalligraphie:layout:compileTestKotlinJvm`; les symboles
manquants étaient `LayoutContinuationSignature`, `LayoutTailState`, la cible et l’overscan du
computer, les lignes calculées et l’état de tail explicite. Ce RED prouvait que l’ancienne boundary
imposait encore `reflowStart..documentEnd`, ne transportait aucune continuation et ne pouvait pas
exprimer un suffixe stable distinct d’un suffixe invalidé.

Une première compilation de production a ensuite rejeté deux appels à un helper privé de l’API et
l’omission de la wrapper immutable du module layout. Ces erreurs mécaniques ont été corrigées sans
modifier les observables attendus.

## Preuves GREEN

Test focalisé frais du fix :

```text
rtk ./gradlew :kalligraphie:layout:jvmTest --tests org.graphiks.kalligraphie.layout.IncrementalParagraphLayoutEngineTest --no-daemon
```

Résultat : exit code 0, `BUILD SUCCESSFUL`; 11 tests JVM réussissent, dont les quatre nouvelles
régressions : cible bornée, continuation différente malgré une ligne identique, transition
typographique non prouvée et invariance avec budget de cache nul.

Suites complètes API et layout :

```text
rtk ./gradlew :kalligraphie:api:check :kalligraphie:layout:check --no-daemon
```

Résultat : exit code 0, `BUILD SUCCESSFUL`; 65 tâches, dont `jvmTest`,
`iosSimulatorArm64Test`, `allTests` et `check`, réussissent pour les deux modules. La suite de
contrats incrémentaux contient 18 tests JVM et la suite de l’engine incrémental 11 tests JVM, sans
failure, error ou skipped test.

Vérification du diff :

```text
rtk git diff --check
```

Résultat : exit code 0, aucune erreur de whitespace.

## Fichiers

- `kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/IncrementalLayoutContracts.kt`
- `kalligraphie/api/src/commonTest/kotlin/org/graphiks/kalligraphie/api/IncrementalLayoutContractsTest.kt`
- `kalligraphie/layout/src/commonMain/kotlin/org/graphiks/kalligraphie/layout/IncrementalParagraphLayoutEngine.kt`
- `kalligraphie/layout/src/commonTest/kotlin/org/graphiks/kalligraphie/layout/IncrementalParagraphLayoutEngineTest.kt`
- `.superpowers/sdd/kalligraphie-incremental-layout-plan/task-2-report.md`

## Auto-revue

- `IncrementalParagraphComputer` reçoit une `IncrementalMaterializationTarget` bornée et le
  `LineOverscan` demandé. Il peut analyser du contexte hors cible, mais son succès ne retourne que
  des lignes complètes et un suffixe exact; la validation n’exige plus la fin du document.
- Le choix de continuation est le couple resource-free `(boundary, semanticValue)` après chaque
  ligne. `boundary` doit être exactement la fin de la ligne et est mappée séparément entre versions;
  `semanticValue` doit sérialiser tout état producteur susceptible de modifier les breaks ou la
  géométrie suivants. La stabilisation exige simultanément la plage mappée, tous les observables de
  ligne et l’égalité sémantique de continuation.
- `LayoutTailState` distingue `MaterializedThroughDocumentEnd`, `Stable(exactSuffix)` et
  `Invalidated(exactSuffix)`. Les deux suffixes non matérialisés commencent obligatoirement à la fin
  exacte de `coveredRange` et utilisent la même `TextVersion`.
- Une `TypographyVersion` différente n’autorise une reprise que si `TypographyDelta` valide les
  versions source/cible et fournit des `RangeChange.Proven` non vides dans les bons espaces texte.
  Une invalidation complète ou une preuve absente repart donc du début du document.
- L’état public est autoritatif : configuration complète, versions, couverture, checkpoints de
  lignes et continuation suffisent à recalculer le checkpoint. Le cache de states et son estimation
  superficielle ont été supprimés selon YAGNI; `cacheBudgetBytes = 0` et un budget non nul publient
  strictement la même couverture, tail, stabilisation et les mêmes diagnostics.
- Les tests construisent de vrais `LineLayout` avec glyphes, clusters, carets et géométrie littéraux.
  Ils n’assertent ni identité d’objet, ni hit, ni call count, ni résidence de cache.
- Aucun code de session JVM, renderer, stockage documentaire ou coroutine n’a été modifié.

## Impact source et ABI

La surface du premier commit Task 2 n’est pas globalement source- ou binary-compatible après ce
correctif : `IncrementalParagraphComputer.compose` change d’arité et de résultat,
`LineCheckpointSignature.from` exige désormais une continuation et le constructeur public de
`LayoutStateHandle` gagne un paramètre avant `lineCheckpoints`. Les implémentations et appels
directs de cette surface doivent être recompilés et adaptés. Les nouveaux types et l’overload
`LayoutCoverage.create(..., tailState)` sont additifs; l’ancien overload public
`LayoutCoverage.create(..., invalidatedSuffix)` et son observable `invalidatedSuffix` restent
source-compatibles. Aucun claim de compatibilité ABI globale n’est fait.

## Préoccupations

- L’adaptateur réel entre `IncrementalParagraphComputer` et `ParagraphComposer` reste volontairement
  hors de cette unité; les fixtures prouvent le contrat avec des lignes complètes et portables.
- Le build émet l’avertissement Gradle préexistant concernant des fonctionnalités dépréciées
  incompatibles avec Gradle 10; il n’affecte aucune vérification.
