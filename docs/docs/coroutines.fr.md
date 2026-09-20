# Coroutines

`org.graphiks:kalligraphie-coroutines` est un module optionnel qui adapte les routes synchrones
du consommateur Kalligraphie aux coroutines Kotlin. Le cœur portable ne dépend pas de
`kotlinx-coroutines`, et chaque point d'entrée synchrone reste utilisable tel quel. Le module
cible uniquement la cible de référence JVM ; le cœur portable et les autres cibles de plateforme
restent sans coroutines.

## Points d'entrée

```kotlin
suspend fun KalligraphieCoroutines.layout(request: JvmEditableLineFacadeRequest): EditableLineResult
suspend fun KalligraphieCoroutines.layout(request: JvmEditableParagraphFacadeRequest): ParagraphLayoutResult
suspend fun KalligraphieCoroutines.open(options: FontDirectoryCatalogOptions): FontOperationResult<FontCatalogSnapshot>
```

Les quatre parcours couverts sont la mise en forme et la mise en page d'une ligne, la composition
de paragraphes (initiale et en continuation) décrite dans les
[Paragraphes éditables](editable-paragraphs.fr.md), la capture du catalogue de polices via les
routes documentées dans la [Gestion des fontes](font-management.fr.md), et la matérialisation d'un
rendu via une requête `EditableLineMaterialization.Renderable`.

## Annulation

Chaque appel fait le pont entre le `Job` appelant et le `CancellationToken` de Kalligraphie. La
route de capture du catalogue n'a pas de jeton fourni par le consommateur ; elle n'observe que
l'annulation du `Job` appelant.

- Un jeton annulé par le consommateur sans annuler la coroutine renvoie le résultat typé
  `.Cancelled`, diagnostics compris.
- L'annulation de la coroutine appelante, observée à l'entrée ou à la sortie, lève une
  `KalligraphieCancellationException` portant le résultat typé dans `result`. La concurrence
  structurée (*structured concurrency*) continue de fonctionner, et la valeur typée n'est jamais
  masquée.

## Contexte d'exécution

La façade ne crée jamais de *dispatcher* (ordonnanceur de coroutines) et n'a aucune portée
globale. Elle s'exécute dans le `CoroutineContext` de l'appelant ; choisissez un *dispatcher* au
point d'appel :

```kotlin
val line = withContext(Dispatchers.Default) { KalligraphieCoroutines.layout(request) }
```

## Propriété des ressources

La façade n'ouvre ni ne ferme jamais de *handle* (poignée d'accès). Un résolveur emprunté par
une requête de rendu reste la propriété de l'appelant, et un *handle* ne survit à une coroutine
annulée que selon son propre contrat. L'annulation et le `close()` explicite conservent la priorité
définie par le contrat synchrone du moteur : une annulation reste une annulation, et un échec de
fermeture ne transforme jamais une opération annulée en succès.

## Patron d'éditeur interactif

Annulez la composition en cours et relancez-la sur l'instantané le plus récent afin que seule la
dernière version soit publiée. La temporisation anti-rebond (*debounce*), les transactions du
document et les opérations annuler/rétablir (*undo/redo*) restent de la responsabilité de
l'application.
