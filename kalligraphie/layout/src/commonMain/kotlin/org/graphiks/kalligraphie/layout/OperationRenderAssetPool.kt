package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.NativeFontRenderAssetHandle
import org.graphiks.kalligraphie.api.NativeHandleProfile
import org.graphiks.kalligraphie.api.NativeFontAccessLimitExceeded
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationLimitExceeded
import org.graphiks.kalligraphie.api.EditorOperationLimitKind
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphRepresentationProfile
import org.graphiks.kalligraphie.api.MaterializationResourceProfile
import org.graphiks.kalligraphie.api.toDiagnostic

private data class RenderAssetSelection(
    val instance: FontInstanceKey,
    val renderVariant: FontRenderVariantSnapshot,
    val profile: GlyphRepresentationProfile,
    val generation: FontCatalogGeneration,
)

/** Assets owned, bounded, and reused by one synchronous materialization operation. */
internal class OperationRenderAssetPool(
    private val profile: MaterializationResourceProfile,
) {
    private val assets = linkedMapOf<FontRenderAssetKey, FontRenderAssetHandle>()
    private val issuedKeys = mutableMapOf<RenderAssetSelection, FontRenderAssetKey>()
    private var estimatedAssetBytes: Long = 0L
    private var closed: Boolean = false
    private var terminalFailure: FontOperationResult.Failure? = null

    var closeFailure: FontError? = null
        private set

    fun owns(key: FontRenderAssetKey): Boolean = !closed && terminalFailure == null && key in assets

    fun isTerminalMaterializationFailure(failure: FontOperationResult.Failure): Boolean =
        failure === terminalFailure || failure.error.isTerminalMaterializationFailure()

    fun acquire(
        instance: FontInstance,
        materialization: EditableLineMaterialization.Renderable,
        representationProfile: GlyphRepresentationProfile,
        cancellationToken: CancellationToken,
    ): FontOperationResult<FontRenderAssetHandle> {
        check(!closed) { "An operation render-asset pool cannot acquire after closure." }
        terminalFailure?.let { return it }
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        val selection = RenderAssetSelection(instance.key, materialization.renderVariant, representationProfile, materialization.resolver.generation)
        issuedKeys[selection]?.let { key -> assets[key]?.let { return FontOperationResult.Success(it) } }

        val estimate = if (profile.maxEstimatedAssetBytes == Long.MAX_VALUE) {
            0L
        } else {
            when (val estimated = instance.estimateRenderAssetBytes(materialization.renderVariant, representationProfile)) {
                is FontOperationResult.Success -> {
                    if (estimated.value < 0L) {
                        return estimationFailure(instance, "Render-asset byte estimates must be non-negative.")
                    }
                    estimated.value
                }
                is FontOperationResult.Failure -> return estimated
                is FontOperationResult.Cancelled -> return estimated
            }
        }
        // An unsupported profile is a local rejection, even when all live slots
        // are occupied. Estimate before admission, without opening an asset.
        val observedAssets = assets.size.toLong() + 1L
        if (observedAssets > profile.maxLiveAssets.toLong()) {
            return operationLimitFailure(
                EditorOperationLimitExceeded(
                    EditorOperationLimitKind.MATERIALIZATION_ASSETS,
                    profile.maxLiveAssets.toLong(),
                    observedAssets,
                ),
            )
        }
        val observedBytes = saturatedAdd(estimatedAssetBytes, estimate)
        if (observedBytes > profile.maxEstimatedAssetBytes) {
            return operationLimitFailure(
                EditorOperationLimitExceeded(
                    EditorOperationLimitKind.MATERIALIZATION_ASSET_BYTES,
                    profile.maxEstimatedAssetBytes,
                    observedBytes,
                ),
            )
        }

        val requirements = FontAccessRequirementsSnapshot.renderable(
            acceptedProfiles = listOf(representationProfile),
            portableDataRequired = materialization.requirements.portableDataRequired,
        )
        val acquired = if (materialization.renderVariant == FontRenderVariantSnapshot.default) {
            instance.acquireRenderAsset(materialization.resolver, materialization.variant, requirements, cancellationToken)
        } else {
            instance.acquireRenderAsset(materialization.resolver, materialization.renderVariant, requirements, cancellationToken)
        }
        if (acquired is FontOperationResult.Success) {
            val key = acquired.value.key
            val nativeProfile = representationProfile as? NativeHandleProfile
            val context = key.nativeContext
            val nativeMatches = if (nativeProfile == null) context == null else
                acquired.value is NativeFontRenderAssetHandle && context != null &&
                    context.reopenToken.isNotBlank() &&
                    context.routeIdentity.bridgeKind == nativeProfile.bridgeKind &&
                    context.routeIdentity.bridgeId == nativeProfile.bridgeId &&
                    context.routeIdentity.bridgeVersion == nativeProfile.bridgeVersion &&
                    context.routeIdentity.runtimeInterpretationId.isNotBlank()
            if (key.fontInstanceKey != selection.instance ||
                (key.variantSnapshot ?: FontRenderVariantSnapshot.default) != selection.renderVariant ||
                key.variant != selection.renderVariant.key || key.representationProfile != selection.profile ||
                key.generation != selection.generation || !nativeMatches) {
                val mismatch = if (!nativeMatches) FontError.FontDataFailure(
                    "font.native-context-proof-failed", "Acquired native asset does not match the requested bridge context.",
                    FontDiagnosticLocation.FaceId(instance.key.face),
                ) else FontError.InvalidFontData("Acquired render asset key does not match the complete operation selection.", FontDiagnosticLocation.FaceId(instance.key.face))
                val closeDiagnostics = closeUnexpectedAsset(acquired.value)
                val failure = FontOperationResult.Failure(
                    mismatch,
                    acquired.diagnostics + closeDiagnostics + mismatch.toDiagnostic(),
                )
                if (closeFailure != null) terminalFailure = failure
                return failure
            }
            if (cancellationToken.isCancellationRequested()) {
                val diagnostics = acquired.diagnostics + closeUnexpectedAsset(acquired.value)
                return FontOperationResult.Cancelled(diagnostics)
            }
            assets[key] = acquired.value
            issuedKeys[selection] = key
            estimatedAssetBytes = observedBytes
        }
        return acquired
    }

    fun close(): List<FontDiagnostic> {
        if (closed) return emptyList()
        closed = true
        val diagnostics = mutableListOf<FontDiagnostic>()
        assets.values.toList().asReversed().forEach { asset ->
            when (val result = asset.close()) {
                is FontOperationResult.Success -> diagnostics += result.diagnostics
                is FontOperationResult.Failure -> {
                    if (closeFailure == null) closeFailure = result.error
                    diagnostics += result.diagnostics + result.error.toDiagnostic()
                }
                is FontOperationResult.Cancelled -> {
                    val error = FontError.Cancelled("Render-asset closure was cancelled.")
                    if (closeFailure == null) closeFailure = error
                    diagnostics += result.diagnostics + error.toDiagnostic()
                }
            }
        }
        assets.clear()
        issuedKeys.clear()
        return diagnostics
    }

    private fun closeUnexpectedAsset(asset: FontRenderAssetHandle): List<FontDiagnostic> = when (val closed = asset.close()) {
        is FontOperationResult.Success -> closed.diagnostics
        is FontOperationResult.Failure -> {
            if (closeFailure == null) closeFailure = closed.error
            closed.diagnostics + closed.error.toDiagnostic()
        }
        is FontOperationResult.Cancelled -> {
            val error = FontError.Cancelled("Unexpected render-asset closure was cancelled.")
            if (closeFailure == null) closeFailure = error
            closed.diagnostics + error.toDiagnostic()
        }
    }

    private fun estimationFailure(instance: FontInstance, message: String): FontOperationResult.Failure {
        val error = FontError.FontDataFailure(
            code = ESTIMATE_UNAVAILABLE_CODE,
            message = message,
            location = FontDiagnosticLocation.FaceId(instance.key.face),
        )
        return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
    }

    private fun operationLimitFailure(exceeded: EditorOperationLimitExceeded): FontOperationResult.Failure {
        val error = FontError.EditorOperationLimitExceeded(exceeded)
        return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    companion object {
        const val ESTIMATE_UNAVAILABLE_CODE: String = "font.render-asset-estimate-unavailable"
    }
}

// A representation-resource rejection remains local to that profile. Failures of the owning
// operation, its shared shaping budget, lifecycle, or mandatory byte estimate are terminal.
internal fun FontError.isTerminalMaterializationFailure(): Boolean =
    this is FontError.ResourceClosed ||
        this is FontError.IncompatibleCatalogGeneration ||
        this is NativeFontAccessLimitExceeded ||
        this is FontError.ShapingResourceLimitExceeded ||
        this is FontError.EditorOperationLimitExceeded ||
        this is FontError.Cancelled ||
        code == OperationRenderAssetPool.ESTIMATE_UNAVAILABLE_CODE ||
        code == NATIVE_GLYPH_CLEANUP_FAILURE_CODE ||
        code in setOf(
            "font.open-type-copy-estimate-unavailable", "font.open-type-copy-estimate-invalid",
            "font.native-context-proof-failed", "font.native-library-load-failed",
            "font.native-symbol-resolution-failed", "font.native-allocation-failed",
            "font.native-font-creation-failed", "font.native-runtime-identity-unavailable",
        )
