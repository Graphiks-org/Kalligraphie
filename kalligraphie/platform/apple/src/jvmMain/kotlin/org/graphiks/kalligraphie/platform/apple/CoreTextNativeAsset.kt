package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*

/** Independent native parent containing only minimal native ownership and exact issued key. */
internal class CoreTextNativeAsset(override val key: FontRenderAssetKey, private val context: CoreTextFontContext,
    private val owner: CoreTextResourceOwner) : NativeFontRenderAssetHandle {
    override val faceId: FontFaceId get() = key.fontInstanceKey.face
    override fun detach(): FontOperationResult<FontRenderAssetHandle> = nativeResult {
        val child = owner.acquireChild() ?: fail(FontError.ResourceClosed("CoreText render asset is closed."))
        var transferred = false
        try { CoreTextNativeAsset(key, context, child).also { transferred = true } }
        finally { if (!transferred) child.close() }
    }
    override fun acquireNativeFontLease(cancellationToken: CancellationToken): FontOperationResult<NativeFontLease> = nativeResult {
        checkCancellation(cancellationToken)
        val child = owner.acquireChild() ?: fail(FontError.ResourceClosed("CoreText render asset is closed."))
        var transferred = false
        try {
            checkCancellation(cancellationToken)
            OwnedCoreTextFontLease(key, context, child).also { transferred = true }
        } finally { if (!transferred) child.close() }
    }
    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> = resolveGlyph(request, CancellationToken.none)
    override fun resolveGlyph(request: FontGlyphRequest, cancellationToken: CancellationToken): FontOperationResult<GlyphRepresentation> = nativeResult {
        val child = owner.acquireChild() ?: fail(FontError.ResourceClosed("CoreText render asset is closed."))
        child.use {
            checkCancellation(cancellationToken)
            fail(FontError.UnsupportedRepresentationProfile("Native CoreText access does not supply portable glyph representation data."))
        }
    }
    override fun close(): FontOperationResult<Unit> { owner.close(); return FontOperationResult.Success(Unit) }
}
