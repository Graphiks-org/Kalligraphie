package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*

/** Adapts a value without discarding successful portable-provider diagnostics. */
internal inline fun <T, R> adaptCoreTextResult(result: FontOperationResult<T>, adapt: (T) -> R): FontOperationResult<R> =
    when (result) {
        is FontOperationResult.Success -> nativeResult(result.diagnostics) { adapt(result.value) }
        is FontOperationResult.Failure -> result
        is FontOperationResult.Cancelled -> result
    }

/** Owns the produced delegate until complete identity validation and adaptation succeed. */
internal inline fun adaptCoreTextAsset(result: FontOperationResult<FontRenderAssetHandle>,
    adapt: (FontRenderAssetHandle) -> FontRenderAssetHandle): FontOperationResult<FontRenderAssetHandle> =
    adaptCoreTextOwnedResult(result, { it.close() }, adapt)

/** Retains a produced portable owner until its adapting result can be transferred. */
internal inline fun <T, R> adaptCoreTextOwnedResult(result: FontOperationResult<T>,
    close: (T) -> FontOperationResult<Unit>, adapt: (T) -> R): FontOperationResult<R> {
    when (result) {
        is FontOperationResult.Failure -> return result
        is FontOperationResult.Cancelled -> return result
        is FontOperationResult.Success -> Unit
    }
    var adapted: FontOperationResult<R>? = null
    try {
        adapted = nativeResult(result.diagnostics) { adapt(result.value) }
    } finally {
        if (adapted !is FontOperationResult.Success) {
            val closed = close(result.value)
            adapted = adapted?.let { completeCoreTextCleanup(it, closed) }
        }
    }
    return checkNotNull(adapted)
}

/** Cleanup is unconditional; cancellation stays primary, other refusals are terminal. */
internal fun <T> completeCoreTextCleanup(primary: FontOperationResult<T>, cleanup: FontOperationResult<Unit>): FontOperationResult<T> {
    val diagnostics = primary.coreTextDiagnostics() + cleanup.coreTextDiagnostics()
    if (cleanup is FontOperationResult.Success) return primary.withCoreTextDiagnostics(diagnostics)
    val cleanupError = when (cleanup) {
        is FontOperationResult.Failure -> cleanup.error
        is FontOperationResult.Cancelled -> FontError.Cancelled("CoreText adapter cleanup was cancelled.")
        is FontOperationResult.Success -> error("Already handled successful cleanup.")
    }
    val retained = diagnostics + cleanupError.toDiagnostic()
    if (primary is FontOperationResult.Cancelled) return primary.copy(diagnostics = retained)
    val original = if (primary is FontOperationResult.Failure) listOf(primary.error.toDiagnostic()) else emptyList()
    val terminal = FontError.FontDataFailure("font.platform-resolver-cleanup-failed",
        "CoreText adapter resource cleanup failed: ${cleanupError.message}", cleanupError.location)
    return FontOperationResult.Failure(terminal, retained + original + terminal.toDiagnostic())
}

internal fun FontOperationResult<*>.coreTextDiagnostics(): List<FontDiagnostic> = when (this) {
    is FontOperationResult.Success -> diagnostics
    is FontOperationResult.Failure -> diagnostics
    is FontOperationResult.Cancelled -> diagnostics
}

internal fun <T> FontOperationResult<T>.withCoreTextDiagnostics(diagnostics: List<FontDiagnostic>): FontOperationResult<T> = when (this) {
    is FontOperationResult.Success -> nativeResult(diagnostics) { value }
    is FontOperationResult.Failure -> copy(diagnostics = diagnostics)
    is FontOperationResult.Cancelled -> copy(diagnostics = diagnostics)
}

/** Result-preserving boundary for checked native failures, without extracting successful values. */
internal inline fun <T> coreTextResult(block: () -> FontOperationResult<T>): FontOperationResult<T> = try {
    block()
} catch (abort: CoreTextAbort) {
    abort.outcome
} catch (_: OutOfMemoryError) {
    FontOperationResult.Failure(FontError.FontDataFailure("font.native-allocation-failed",
        "Native access could not allocate its admitted buffers.", FontDiagnosticLocation.Source))
}
