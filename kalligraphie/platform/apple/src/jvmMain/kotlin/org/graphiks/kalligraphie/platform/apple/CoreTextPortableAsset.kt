package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*

/** Portable detached ownership retains only the delegate and immutable exact key remapping. */
internal class CoreTextPortableAsset(private val delegate: FontRenderAssetHandle, override val key: FontRenderAssetKey) : FontRenderAssetHandle {
    private val underlyingKey = delegate.key
    override val faceId: FontFaceId get() = key.fontInstanceKey.face
    override fun detach(): FontOperationResult<FontRenderAssetHandle> = adaptCoreTextAsset(delegate.detach()) { detached ->
        if (detached.key != underlyingKey) fail(FontError.AssetUnavailable("Portable detachment returned a different complete underlying asset key."))
        CoreTextPortableAsset(detached, key)
    }
    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> = delegate.resolveGlyph(request)
    override fun resolveGlyph(request: FontGlyphRequest, cancellationToken: CancellationToken): FontOperationResult<GlyphRepresentation> = delegate.resolveGlyph(request, cancellationToken)
    override fun close(): FontOperationResult<Unit> = delegate.close()
}
