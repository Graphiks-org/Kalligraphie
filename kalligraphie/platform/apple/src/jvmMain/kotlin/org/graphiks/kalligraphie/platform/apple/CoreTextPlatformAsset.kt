package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*

/** Independent platform parent containing only minimal CoreText ownership and exact issued key. */
internal class CoreTextPlatformAsset(override val key: FontRenderAssetKey, private val context: CoreTextFontContext,
    private val owner: CoreTextResourceOwner) : PlatformFontRenderAssetHandle {
    override val faceId: FontFaceId get() = key.fontInstanceKey.face
    override fun detach(): FontOperationResult<FontRenderAssetHandle> = nativeResult {
        val child = owner.acquireChild() ?: fail(FontError.ResourceClosed("CoreText render asset is closed."))
        var transferred = false
        try { CoreTextPlatformAsset(key, context, child).also { transferred = true } }
        finally { if (!transferred) child.close() }
    }
    override fun acquirePlatformFontLease(cancellationToken: CancellationToken): FontOperationResult<PlatformFontLease> = nativeResult {
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
            fail(FontError.UnsupportedRepresentationProfile("CoreText platform access does not supply portable glyph representation data."))
        }
    }
    override fun close(): FontOperationResult<Unit> { owner.close(); return FontOperationResult.Success(Unit) }
}
