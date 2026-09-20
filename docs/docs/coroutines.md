# Coroutines

`org.graphiks:kalligraphie-coroutines` is an optional module that adapts the synchronous
Kalligraphie consumer routes to Kotlin coroutines. The portable core does not depend on
`kotlinx-coroutines`, and every synchronous entry point remains usable unchanged. The module
targets the JVM reference target only; the portable core and the other platform targets remain
coroutine-free.

## Entry points

```kotlin
suspend fun KalligraphieCoroutines.layout(request: JvmEditableLineFacadeRequest): EditableLineResult
suspend fun KalligraphieCoroutines.layout(request: JvmEditableParagraphFacadeRequest): ParagraphLayoutResult
suspend fun KalligraphieCoroutines.open(options: FontDirectoryCatalogOptions): FontOperationResult<FontCatalogSnapshot>
```

The four covered journeys are line shaping and layout, paragraph composition (initial and
continuation) as described in [Editable Paragraphs](editable-paragraphs.md), font catalog
capture through the routes documented in [Font Management](font-management.md), and renderable
materialization through an `EditableLineMaterialization.Renderable` request.

## Cancellation

Each call bridges the calling `Job` into the Kalligraphie `CancellationToken`. The catalog
capture route has no consumer-supplied token; it observes calling-Job cancellation only.

- A token cancelled by the consumer without cancelling the coroutine returns the typed
  `.Cancelled` result, including its diagnostics.
- Cancellation of the calling coroutine, observed at entry or at exit, raises a
  `KalligraphieCancellationException` carrying the typed result on `result`. Structured concurrency
  keeps working, and the typed value is never masked.

## Execution context

The facade never creates a dispatcher and has no global scope. It runs in the caller's
`CoroutineContext`; choose a dispatcher at the call site:

```kotlin
val line = withContext(Dispatchers.Default) { KalligraphieCoroutines.layout(request) }
```

## Resource ownership

The facade never opens or closes a handle. A resolver borrowed by a renderable request stays the
caller's property, and a handle survives a cancelled coroutine only according to its own contract.

## Interactive editor pattern

Cancel the in-flight composition and relaunch on the latest snapshot so only the last version is
published. Debounce, document transactions and undo/redo remain application responsibilities.
