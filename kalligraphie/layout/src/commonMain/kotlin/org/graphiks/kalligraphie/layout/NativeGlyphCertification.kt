package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.*

/** Cleanup refusal is terminal regardless of the provider's cleanup error kind. */
internal const val NATIVE_GLYPH_CLEANUP_FAILURE_CODE: String = "font.native-glyph-validation-cleanup-failed"

/**
 * Proves distinct final glyph IDs through a temporary independently owned native lease.
 * No native owner escapes into evidence. Cooperative checks run between provider calls.
 * Unconditional cleanup preserves cancellation as primary; any other cleanup failure is
 * terminal, retaining the original proof and provider cleanup errors as diagnostics.
 */
internal fun validateNativeGlyphs(
    asset: NativeFontRenderAssetHandle,
    glyphIds: List<GlyphId>,
    cancellationToken: CancellationToken,
): FontOperationResult<Map<GlyphId, GlyphMaterializationRoute>> {
    if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
    if (glyphIds.isEmpty()) return FontOperationResult.Success(emptyMap())
    val acquired = asset.acquireNativeFontLease(cancellationToken)
    val lease = when (acquired) {
        is FontOperationResult.Success -> acquired.value
        is FontOperationResult.Failure -> return acquired
        is FontOperationResult.Cancelled -> return acquired
    }
    val diagnostics = acquired.diagnostics.toMutableList()
    var result: FontOperationResult<Map<GlyphId, GlyphMaterializationRoute>> = FontOperationResult.Success(emptyMap())
    fun failCleanup(error: FontError) {
        when (val proof = result) {
            is FontOperationResult.Cancelled -> Unit
            else -> {
                if (proof is FontOperationResult.Failure) diagnostics += proof.error.toDiagnostic()
                val terminalError = FontError.FontDataFailure(
                    NATIVE_GLYPH_CLEANUP_FAILURE_CODE,
                    "Native glyph validation lease cleanup failed: ${error.message}",
                    error.location,
                )
                diagnostics += terminalError.toDiagnostic()
                result = FontOperationResult.Failure(terminalError)
            }
        }
    }
    try {
        result = proveNativeGlyphs(asset, lease, glyphIds, cancellationToken)
    } finally {
        diagnostics += when (val proof = result) {
            is FontOperationResult.Success -> proof.diagnostics
            is FontOperationResult.Failure -> proof.diagnostics
            is FontOperationResult.Cancelled -> proof.diagnostics
        }
        when (val closed = lease.close()) {
            is FontOperationResult.Success -> diagnostics += closed.diagnostics
            is FontOperationResult.Failure -> {
                diagnostics += closed.diagnostics + closed.error.toDiagnostic()
                failCleanup(closed.error)
            }
            is FontOperationResult.Cancelled -> {
                val error = FontError.Cancelled("Native validation lease cleanup was cancelled.")
                diagnostics += closed.diagnostics + error.toDiagnostic()
                failCleanup(error)
            }
        }
    }
    if (result is FontOperationResult.Success && cancellationToken.isCancellationRequested()) {
        result = FontOperationResult.Cancelled()
    }
    return when (val completed = result) {
        is FontOperationResult.Success -> completed.copy(diagnostics = diagnostics)
        is FontOperationResult.Failure -> completed.copy(diagnostics = diagnostics)
        is FontOperationResult.Cancelled -> completed.copy(diagnostics = diagnostics)
    }
}

private fun proveNativeGlyphs(
    asset: NativeFontRenderAssetHandle,
    lease: NativeFontLease,
    glyphIds: List<GlyphId>,
    cancellationToken: CancellationToken,
): FontOperationResult<Map<GlyphId, GlyphMaterializationRoute>> {
    if (lease.key != asset.key || asset.key.nativeContext == null || lease.routeIdentity != asset.key.nativeContext?.routeIdentity) {
        val error = FontError.FontDataFailure("font.native-context-proof-failed", "Native validation lease does not match the complete issued asset key and runtime context.", FontDiagnosticLocation.FaceId(asset.key.fontInstanceKey.face))
        return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
    }
    val routes = linkedMapOf<GlyphId, GlyphMaterializationRoute>()
    val diagnostics = mutableListOf<FontDiagnostic>()
    for (glyphId in glyphIds.distinct()) {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics)
        when (val validated = lease.validateGlyph(glyphId, cancellationToken)) {
            is FontOperationResult.Success -> diagnostics += validated.diagnostics
            is FontOperationResult.Failure -> return validated.copy(diagnostics = diagnostics + validated.diagnostics)
            is FontOperationResult.Cancelled -> return validated.copy(diagnostics = diagnostics + validated.diagnostics)
        }
        routes[glyphId] = GlyphMaterializationRoute.NATIVE_HANDLE
    }
    if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics)
    return FontOperationResult.Success(routes, diagnostics)
}
