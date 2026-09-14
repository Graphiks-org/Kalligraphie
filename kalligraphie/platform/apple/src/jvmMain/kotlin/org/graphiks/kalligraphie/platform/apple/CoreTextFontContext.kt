package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kffi.MemoryAllocator
import org.graphiks.kalligraphie.api.*

/** Minimal immutable proven native context; owns explicit refs, no catalogue/source/layout. */
internal class CoreTextFontContext private constructor(val font: Long, val glyphCount: Int,
    val route: PlatformFontRouteIdentity, private val graphics: Long, private val provider: Long,
    private val data: Long, private val bindings: CoreTextBindings) {
    fun release() {
        try { bindings.releaseFont(font) } finally {
            try { bindings.releaseGraphics(graphics) } finally {
                try { bindings.releaseProvider(provider) } finally { bindings.releaseData(data) }
            }
        }
    }
    companion object {
        /** Source and native selection eligibility must be checked even before a warm lookup. */
        fun validate(source: CoreTextCapturedSource, key: FontRenderAssetKey) {
            if (!source.platformEligible || key.fontInstanceKey.face != source.face ||
                key.fontInstanceKey.layoutSize.value <= 0f || !key.fontInstanceKey.layoutSize.value.isFinite() ||
                key.fontInstanceKey.geometry != FontGeometryParameters() || key.variant != FontRenderVariantKey.default ||
                key.variantSnapshot != null || key.platformContext == null) {
                fail(FontError.UnsupportedRepresentationProfile("CoreText requires supported static TrueType source, positive size and default geometry/variant."))
            }
        }
        fun create(source: CoreTextCapturedSource, key: FontRenderAssetKey, bindings: CoreTextBindings,
            admission: CoreTextByteAdmission, token: CancellationToken): CoreTextFontContext {
            checkCancellation(token)
            validate(source, key)
            val context = checkNotNull(key.platformContext)
            val charge = source.bytes.size.toLong() * 2L
            admission.reserve(charge, PlatformFontAccessPhase.NATIVE_CREATION).use {
                checkCancellation(token)
                var data = 0L; var provider = 0L; var graphics = 0L; var font = 0L
                var transferred = false
                fun created(value: Long): Long {
                    if (value == 0L) nativeFailure("font.native-font-creation-failed", "CoreText construction returned a null owned font resource.")
                    return value
                }
                try {
                    MemoryAllocator().use { allocator ->
                        checkCancellation(token)
                        val buffer = allocator.allocateBuffer(source.bytes.size.toULong())
                        buffer.writeBytes(source.bytes)
                        checkCancellation(token)
                        data = created(bindings.data(buffer.handler.rawValue, source.bytes.size.toLong()))
                        checkCancellation(token)
                        provider = created(bindings.provider(data))
                        checkCancellation(token)
                        graphics = created(bindings.graphics(provider))
                        checkCancellation(token)
                        font = created(bindings.font(graphics, key.fontInstanceKey.layoutSize.value.toDouble()))
                        checkCancellation(token)
                    }
                    // Free the N-byte transfer before the 48-byte matrix result is allocated.
                    // With CFData's N-byte copy retained, the controlled peak stays within 2N.
                    checkCancellation(token)
                    val size = bindings.size(font)
                    checkCancellation(token)
                    val upem = bindings.upem(font)
                    checkCancellation(token)
                    val glyphs = bindings.glyphCount(font)
                    checkCancellation(token)
                    val identity = MemoryAllocator().use { allocator -> bindings.identityMatrix(font, allocator) }
                    checkCancellation(token)
                    if (size != key.fontInstanceKey.layoutSize.value.toDouble() || upem != source.metadata.unitsPerEm.toLong() ||
                        glyphs != source.metadata.glyphCount.toLong() || !identity) {
                        nativeFailure("font.platform-context-proof-failed", "The native font disagrees with exact source metadata, size or identity matrix.")
                    }
                    val result = CoreTextFontContext(font, glyphs.toInt(), context.routeIdentity, graphics, provider, data, bindings)
                    transferred = true
                    return result
                } finally {
                    if (!transferred) {
                        try { bindings.releaseFont(font) } finally {
                            try { bindings.releaseGraphics(graphics) } finally {
                                try { bindings.releaseProvider(provider) } finally { bindings.releaseData(data) }
                            }
                        }
                    }
                }
            }
        }
    }
}
