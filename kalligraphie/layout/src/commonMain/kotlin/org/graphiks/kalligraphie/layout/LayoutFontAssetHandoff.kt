package org.graphiks.kalligraphie.layout

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FlowLayout
import org.graphiks.kalligraphie.api.GlyphMaterializationCertificate
import org.graphiks.kalligraphie.api.LayoutHandle
import org.graphiks.kalligraphie.api.ParagraphLayout
import org.graphiks.kalligraphie.api.toDiagnostic

/**
 * Transfers this line's certified font assets into independent ownership.
 *
 * [resolver] is borrowed for this synchronous call and remains owned by the caller, who must
 * keep it open until the call returns. This operation neither closes nor retains the resolver;
 * the caller may close it after return without invalidating a successfully returned handle.
 * The line itself remains a resource-free value.
 *
 * Each distinct complete certified asset key is reopened and detached. Both returned keys must
 * exactly match that certified key, otherwise the operation returns [FontError.InvalidFontData].
 * The attached owner is closed before its detached root is adopted. Provider failures, including
 * unsupported detachment or closure failures, are propagated as typed operation results.
 * No partial layout handle is published on failure or cancellation.
 *
 * [cancellationToken] is observed cooperatively before work, before each root acquisition, after
 * successful reopening and detachment, and before publication. Provider calls are indivisible
 * from this operation's perspective: cancellation does not interrupt an in-progress reopen,
 * detach, or close. Cancellation observed at a checkpoint returns [FontOperationResult.Cancelled].
 *
 * On failure or observed cancellation, current owners and previously adopted roots are closed,
 * with roots cleaned up in reverse acquisition order. Cleanup continues after typed cleanup
 * failures and preserves the primary failure or cancellation. Provider diagnostics and cleanup
 * error diagnostics are retained; [FontOperationResult] publishes them in its canonical order.
 */
public fun EditableLine.openLayoutHandle(
    resolver: FontAssetResolverHandle,
    cancellationToken: CancellationToken = CancellationToken.none,
): FontOperationResult<LayoutHandle<EditableLine>> = openLayoutHandle(
    layout = this,
    certificates = {
        positionedGlyphRuns.flatMap { run ->
            run.glyphs.mapNotNull { it.materializationCertificate }
        }.toSet()
    },
    resolver = resolver,
    cancellationToken = cancellationToken,
)

/**
 * Transfers every final line certificate into an independently owned paragraph handle.
 *
 * [resolver] is borrowed and must remain open until this synchronous call returns; it is neither
 * closed nor retained. Creation is atomic: failure or cooperative [cancellationToken] observation
 * publishes no partial handle. See [EditableLine.openLayoutHandle] for the shared ownership,
 * cancellation checkpoint, exact-key validation, cleanup, and diagnostics contract.
 */
public fun ParagraphLayout.openLayoutHandle(
    resolver: FontAssetResolverHandle,
    cancellationToken: CancellationToken = CancellationToken.none,
): FontOperationResult<LayoutHandle<ParagraphLayout>> = openLayoutHandle(
    layout = this,
    certificates = {
        lines.flatMap { line ->
            line.positionedGlyphRuns.flatMap { run ->
                run.glyphs.mapNotNull { glyph -> glyph.materializationCertificate }
            }
        }.toSet()
    },
    resolver = resolver,
    cancellationToken = cancellationToken,
)

/**
 * Transfers every published final flow-line certificate into an independently owned handle.
 *
 * [resolver] is borrowed and must remain open until this synchronous call returns; it is neither
 * closed nor retained. Creation is atomic: failure or cooperative [cancellationToken] observation
 * publishes no partial handle. See [EditableLine.openLayoutHandle] for the shared ownership,
 * cancellation checkpoint, exact-key validation, cleanup, and diagnostics contract.
 */
public fun FlowLayout.openLayoutHandle(
    resolver: FontAssetResolverHandle,
    cancellationToken: CancellationToken = CancellationToken.none,
): FontOperationResult<LayoutHandle<FlowLayout>> = openLayoutHandle(
    layout = this,
    certificates = {
        lines.flatMap { line ->
            line.positionedGlyphRuns.flatMap { run ->
                run.glyphs.mapNotNull { glyph -> glyph.materializationCertificate }
            }
        }.toSet()
    },
    resolver = resolver,
    cancellationToken = cancellationToken,
)

private fun <T> openLayoutHandle(
    layout: T,
    certificates: () -> Set<GlyphMaterializationCertificate>,
    resolver: FontAssetResolverHandle,
    cancellationToken: CancellationToken,
): FontOperationResult<LayoutHandle<T>> {
    if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
    val extractedCertificates = certificates()
    if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
    val roots = linkedMapOf<FontRenderAssetKey, FontRenderAssetHandle>()
    val diagnostics = mutableListOf<FontDiagnostic>()
    fun abort(result: FontOperationResult<Nothing>): FontOperationResult<Nothing> =
        result.withDiagnostics(diagnostics + closeAssets(roots.values.toList()).diagnostics())

    for (key in extractedCertificates.map { it.assetKey }.distinct()) {
        if (cancellationToken.isCancellationRequested()) return abort(FontOperationResult.Cancelled())
        val attached = when (val reopened = resolver.reopen(key)) {
            is FontOperationResult.Success -> {
                diagnostics += reopened.diagnostics
                reopened.value
            }
            is FontOperationResult.Failure -> return abort(reopened)
            is FontOperationResult.Cancelled -> return abort(reopened)
        }
        if (attached.key != key) {
            diagnostics += closeAssets(listOf(attached)).diagnostics()
            return abort(keyMismatch())
        }
        if (cancellationToken.isCancellationRequested()) {
            diagnostics += closeAssets(listOf(attached)).diagnostics()
            return abort(FontOperationResult.Cancelled())
        }
        val detached = attached.detach()
        diagnostics += detached.diagnostics()
        if (detached !is FontOperationResult.Success) {
            diagnostics += closeAssets(listOf(attached)).diagnostics()
            return when (detached) {
                is FontOperationResult.Failure -> abort(detached.copy(diagnostics = emptyList()))
                is FontOperationResult.Cancelled -> abort(detached.copy(diagnostics = emptyList()))
            }
        }
        if (detached.value.key != key) {
            diagnostics += closeAssets(listOf(attached, detached.value)).diagnostics()
            return abort(keyMismatch())
        }
        if (cancellationToken.isCancellationRequested()) {
            diagnostics += closeAssets(listOf(attached, detached.value)).diagnostics()
            return abort(FontOperationResult.Cancelled())
        }
        val closed = closeAssets(listOf(attached))
        diagnostics += closed.diagnostics()
        if (closed !is FontOperationResult.Success) {
            diagnostics += closeAssets(listOf(detached.value)).diagnostics()
            return when (closed) {
                is FontOperationResult.Failure -> abort(closed.copy(diagnostics = emptyList()))
                is FontOperationResult.Cancelled -> abort(closed.copy(diagnostics = emptyList()))
            }
        }
        roots[key] = detached.value
    }
    if (cancellationToken.isCancellationRequested()) return abort(FontOperationResult.Cancelled())
    return FontOperationResult.Success(RetainedLayoutHandle(layout, extractedCertificates, roots), diagnostics)
}

@OptIn(ExperimentalAtomicApi::class)
private class RetainedLayoutHandle<T>(
    override val layout: T,
    private val certificates: Set<GlyphMaterializationCertificate>,
    roots: Map<FontRenderAssetKey, FontRenderAssetHandle>,
) : LayoutHandle<T> {
    private sealed interface State {
        data class Open(
            val activeRetentions: Int,
            val roots: Map<FontRenderAssetKey, FontRenderAssetHandle>,
        ) : State
        data class Closing(
            val activeRetentions: Int,
            val roots: Map<FontRenderAssetKey, FontRenderAssetHandle>,
        ) : State
        data object Closed : State
    }

    private val state = AtomicReference<State>(State.Open(0, roots))

    override fun retainFontAsset(
        certificate: GlyphMaterializationCertificate,
    ): FontOperationResult<FontRenderAssetHandle> {
        val roots = acquireRetention()
            ?: return FontOperationResult.Failure(FontError.ResourceClosed("Layout handle is closed."))
        val cleanup: List<FontDiagnostic>
        val result = try {
            if (certificate !in certificates) {
                FontOperationResult.Failure(FontError.CertificateNotInLayout(certificate.glyphId.value))
            } else {
                val detached = checkNotNull(roots[certificate.assetKey]).detach()
                if (detached is FontOperationResult.Success && detached.value.key != certificate.assetKey) {
                    keyMismatch().withDiagnostics(
                        detached.diagnostics + closeAssets(listOf(detached.value)).diagnostics(),
                    )
                } else {
                    detached
                }
            }
        } finally {
            cleanup = releaseRetention()
        }
        // Cleanup cannot revoke an independently detached renderer owner.
        return result.withDiagnostics(cleanup)
    }

    override fun close(): FontOperationResult<Unit> {
        while (true) {
            when (val current = state.load()) {
                is State.Open -> {
                    val next = if (current.activeRetentions == 0) State.Closed
                    else State.Closing(current.activeRetentions, current.roots)
                    if (state.compareAndSet(current, next)) {
                        return if (next == State.Closed) closeAssets(current.roots.values.toList())
                        else FontOperationResult.Success(Unit)
                    }
                }
                is State.Closing, State.Closed -> return FontOperationResult.Success(Unit)
            }
        }
    }

    private fun acquireRetention(): Map<FontRenderAssetKey, FontRenderAssetHandle>? {
        while (true) {
            when (val current = state.load()) {
                is State.Open -> if (
                    state.compareAndSet(current, State.Open(current.activeRetentions + 1, current.roots))
                ) {
                    return current.roots
                }
                is State.Closing, State.Closed -> return null
            }
        }
    }

    private fun releaseRetention(): List<FontDiagnostic> {
        while (true) {
            val current = state.load()
            val next = when (current) {
                is State.Open -> State.Open(current.activeRetentions - 1, current.roots)
                is State.Closing -> if (current.activeRetentions == 1) State.Closed
                    else State.Closing(current.activeRetentions - 1, current.roots)
                State.Closed -> error("A closed layout handle cannot own an admitted retention.")
            }
            if (state.compareAndSet(current, next)) {
                return if (current is State.Closing && next == State.Closed) {
                    closeAssets(current.roots.values.toList()).diagnostics()
                }
                else emptyList()
            }
        }
    }
}

private fun keyMismatch(): FontOperationResult.Failure = FontOperationResult.Failure(
    FontError.InvalidFontData("Render asset key does not match the complete certified layout key."),
)

private fun closeAssets(assets: List<FontRenderAssetHandle>): FontOperationResult<Unit> {
    val diagnostics = mutableListOf<FontDiagnostic>()
    var failure: FontOperationResult<Nothing>? = null
    for (asset in assets.asReversed()) {
        val result = asset.close()
        diagnostics += result.diagnostics()
        when (result) {
            is FontOperationResult.Success -> Unit
            is FontOperationResult.Failure -> {
                if (failure == null) failure = result
                diagnostics += result.error.toDiagnostic()
            }
            is FontOperationResult.Cancelled -> {
                if (failure == null) failure = result
                diagnostics += FontError.Cancelled("Render-asset closure was cancelled.").toDiagnostic()
            }
        }
    }
    return when (val primary = failure) {
        null, is FontOperationResult.Success -> FontOperationResult.Success(Unit, diagnostics)
        is FontOperationResult.Failure -> primary.copy(diagnostics = diagnostics)
        is FontOperationResult.Cancelled -> primary.copy(diagnostics = diagnostics)
    }
}

private fun FontOperationResult<*>.diagnostics(): List<FontDiagnostic> = when (this) {
    is FontOperationResult.Success -> diagnostics
    is FontOperationResult.Failure -> diagnostics
    is FontOperationResult.Cancelled -> diagnostics
}

private fun <T> FontOperationResult<T>.withDiagnostics(additional: List<FontDiagnostic>): FontOperationResult<T> =
    when (this) {
        is FontOperationResult.Success -> copy(diagnostics = diagnostics + additional)
        is FontOperationResult.Failure -> copy(diagnostics = diagnostics + additional)
        is FontOperationResult.Cancelled -> copy(diagnostics = diagnostics + additional)
    }
