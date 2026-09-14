package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*

/** Portable detached ownership retains only the delegate and immutable exact key remapping. */
internal class CoreTextPortableAsset(private val delegate: FontRenderAssetHandle, override val key: FontRenderAssetKey) : FontRenderAssetHandle {
    override val faceId: FontFaceId get() = key.fontInstanceKey.face
    override fun detach(): FontOperationResult<FontRenderAssetHandle> = nativeResult {
        val detached = delegate.detach().valueOrAbort()
        var transferred = false
        try { CoreTextPortableAsset(detached, key).also { transferred = true } }
        finally { if (!transferred) detached.close() }
    }
    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> = delegate.resolveGlyph(request)
    override fun resolveGlyph(request: FontGlyphRequest, cancellationToken: CancellationToken): FontOperationResult<GlyphRepresentation> = delegate.resolveGlyph(request, cancellationToken)
    override fun close(): FontOperationResult<Unit> = delegate.close()
}
