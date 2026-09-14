package org.graphiks.kalligraphie.platform.apple

import java.lang.foreign.MemorySegment
import org.graphiks.kalligraphie.api.*

/** Independently owned CoreText font reference, usable from any thread while its lease is open. */
public interface CoreTextFontLease : NativeFontLease {
    /**
     * Returns the exact CTFontRef pointer while this owning lease is open, or ResourceClosed.
     * The caller must retain this lease for every unmanaged native use and must not close it
     * concurrently with that call. Returning a segment does not prevent raw pointer escape.
     * Numeric font size equals layout units; consumers apply their own device/axis conversion.
     */
    public fun fontRef(): FontOperationResult<MemorySegment>
}

internal class OwnedCoreTextFontLease(override val key: FontRenderAssetKey, private val context: CoreTextFontContext,
    private val owner: CoreTextResourceOwner) : CoreTextFontLease {
    override val routeIdentity: NativeFontRouteIdentity get() = context.route
    override fun fontRef(): FontOperationResult<MemorySegment> = nativeResult {
        if (!owner.isOpen()) fail(FontError.ResourceClosed("CoreText font lease is closed."))
        MemorySegment.ofAddress(context.font)
    }
    override fun validateGlyph(glyphId: GlyphId, cancellationToken: CancellationToken): FontOperationResult<Unit> = nativeResult {
        val operation = owner.acquireChild() ?: fail(FontError.ResourceClosed("CoreText font lease is closed."))
        operation.use {
            checkCancellation(cancellationToken)
            if (key.nativeContext?.routeIdentity != context.route) nativeFailure("font.native-context-proof-failed", "Native font lease has a different exact context.")
            if (glyphId.value > 65535 || glyphId.value >= context.glyphCount) fail(FontError.GlyphOutOfRange(glyphId.value))
            checkCancellation(cancellationToken)
        }
    }
    override fun close(): FontOperationResult<Unit> { owner.close(); return FontOperationResult.Success(Unit) }
}
