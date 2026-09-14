package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*
import java.security.MessageDigest

internal class CoreTextAbort(val outcome: FontOperationResult<Nothing>) : RuntimeException()
internal fun fail(error: FontError): Nothing = throw CoreTextAbort(FontOperationResult.Failure(error))
internal fun nativeFailure(code: String, message: String): Nothing = fail(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Source))
internal fun checkCancellation(token: CancellationToken) {
    if (token.isCancellationRequested()) throw CoreTextAbort(FontOperationResult.Cancelled())
}
internal fun <T> FontOperationResult<T>.valueOrAbort(): T = when (this) {
    is FontOperationResult.Success -> value
    is FontOperationResult.Failure -> throw CoreTextAbort(this)
    is FontOperationResult.Cancelled -> throw CoreTextAbort(this)
}
internal inline fun <T> nativeResult(diagnostics: List<FontDiagnostic> = emptyList(), block: () -> T): FontOperationResult<T> = try {
    val value = block()
    try { FontOperationResult.Success(value, diagnostics) } catch (failure: OutOfMemoryError) {
        val cleanup = when (value) {
            is FontRenderAssetHandle -> value.close()
            is PlatformFontLease -> value.close()
            is FontAssetResolverHandle -> value.close()
            else -> FontOperationResult.Success(Unit)
        }
        completeCoreTextCleanup(FontOperationResult.Failure(FontError.FontDataFailure("font.native-allocation-failed",
            "Native access could not allocate its admitted buffers.", FontDiagnosticLocation.Source), diagnostics), cleanup)
    }
} catch (abort: CoreTextAbort) {
    abort.outcome.withCoreTextDiagnostics(diagnostics + abort.outcome.coreTextDiagnostics())
} catch (failure: OutOfMemoryError) {
    FontOperationResult.Failure(FontError.FontDataFailure("font.native-allocation-failed", "Native access could not allocate its admitted buffers.", FontDiagnosticLocation.Source), diagnostics)
}
internal fun checkedAdd(left: Long, right: Long): Long = try { Math.addExact(left, right) } catch (_: ArithmeticException) { Long.MAX_VALUE }
internal fun checkLimit(phase: PlatformFontAccessPhase, dimension: PlatformFontAccessDimension, maximum: Long, observed: Long) {
    if (observed > maximum) fail(PlatformFontAccessLimitExceeded(phase, dimension, maximum, observed))
}

/** Minimal shared transient admission, containing no catalogue or source reference. */
internal class CoreTextByteAdmission(private val maximum: Long) {
    private var reserved = 0L
    fun reserve(bytes: Long, phase: PlatformFontAccessPhase): AutoCloseable {
        val reservation = AutoCloseable { synchronized(this) { reserved -= bytes } }
        synchronized(this) {
            val observed = checkedAdd(reserved, bytes)
            checkLimit(phase, PlatformFontAccessDimension.TRANSIENT_OWNED_BYTES, maximum, observed)
            reserved = observed
        }
        return reservation
    }
}

/** Private captured leaf and once-validated supported-source proof; never exposed or recopied. */
internal class CoreTextCapturedSource(val bytes: ByteArray, val face: FontFaceId, val metadata: FontFaceMetadata, val platformEligible: Boolean)

internal object CoreTextSourceCapture {
    private data class Preflight(val record: FontFaceRecord, val instance: FontInstance, val estimate: OpenTypeDataCopyEstimate)
    fun capture(portable: FontCatalogSnapshot, policy: CoreTextFontAccessPolicy, admission: CoreTextByteAdmission,
        token: CancellationToken): Map<FontFaceId, CoreTextCapturedSource> {
        var total = 0L
        val preflights = portable.faces.map { record ->
            checkCancellation(token)
            val face = portable.resolveFace(record.id, FontAccessRequirementsSnapshot.layoutOnly()).valueOrAbort()
            val instance = face.instantiate(FontInstanceDescriptor()).valueOrAbort()
            val estimate = instance.estimateOpenTypeDataCopy().valueOrAbort()
            if (estimate.sourceBytes < 0 || estimate.maxOwnedCopyBytes < estimate.sourceBytes) {
                nativeFailure("font.open-type-copy-estimate-invalid", "The provider supplied an invalid immutable source-copy bound.")
            }
            checkLimit(PlatformFontAccessPhase.SOURCE_CAPTURE, PlatformFontAccessDimension.SOURCE_BYTES_PER_FACE, policy.maxSourceBytesPerFace, estimate.sourceBytes)
            total = checkedAdd(total, estimate.sourceBytes)
            checkLimit(PlatformFontAccessPhase.SOURCE_CAPTURE, PlatformFontAccessDimension.CAPTURED_SOURCE_BYTES, policy.maxCapturedSourceBytes, total)
            // Every mandatory transient bound is also admitted before the first copy begins.
            checkLimit(PlatformFontAccessPhase.SOURCE_CAPTURE, PlatformFontAccessDimension.TRANSIENT_OWNED_BYTES,
                policy.maxTransientOwnedBytes, checkedAdd(estimate.maxOwnedCopyBytes, estimate.sourceBytes))
            Preflight(record, instance, estimate)
        }
        val captured = linkedMapOf<FontFaceId, CoreTextCapturedSource>()
        for ((record, instance, estimate) in preflights) {
            checkCancellation(token)
            admission.reserve(checkedAdd(estimate.maxOwnedCopyBytes, estimate.sourceBytes), PlatformFontAccessPhase.SOURCE_CAPTURE).use {
                checkCancellation(token)
                val data = instance.copyOpenTypeData().valueOrAbort()
                checkCancellation(token)
                if (data.face != record.id || data.sizeInBytes.toLong() != estimate.sourceBytes) {
                    nativeFailure("font.open-type-source-contract-violated", "The copied source disagrees with its immutable face/size preflight.")
                }
                val leaf = data.copyBytes()
                checkCancellation(token)
                val portableSource = record.id.source as? FontSourceId.Portable
                if (portableSource != null && digest(leaf) != portableSource.contentDigest.value) {
                    nativeFailure("font.open-type-source-contract-violated", "The copied source does not match its portable content digest.")
                }
                captured[record.id] = CoreTextCapturedSource(leaf, record.id, record.metadata, inspect(leaf, record))
            }
        }
        checkCancellation(token)
        return java.util.Collections.unmodifiableMap(captured)
    }

    private fun inspect(bytes: ByteArray, record: FontFaceRecord): Boolean {
        fun u16(offset: Int): Int = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        fun u32(offset: Int): Long = (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255L) }
        if (bytes.size < 12) nativeFailure("font.open-type-source-contract-violated", "Captured source has no complete SFNT header.")
        if (u32(0) != 0x00010000L || record.id.faceIndex != 0) return false
        val count = u16(4)
        if (12L + count * 16L > bytes.size) nativeFailure("font.open-type-source-contract-violated", "Captured SFNT directory is truncated.")
        val tables = linkedMapOf<String, Pair<Int, Int>>()
        repeat(count) { index ->
            val entry = 12 + index * 16
            val tag = String(bytes, entry, 4, Charsets.ISO_8859_1)
            val offset = u32(entry + 8)
            val length = u32(entry + 12)
            if (offset + length > bytes.size.toLong() || tables.containsKey(tag)) {
                nativeFailure("font.open-type-source-contract-violated", "Captured SFNT table directory has an invalid range or duplicate tag.")
            }
            tables[tag] = offset.toInt() to length.toInt()
        }
        if (tables.keys.any { it in excludedTags } || !tables.keys.containsAll(listOf("head", "maxp", "loca", "glyf"))) return false
        val head = tables.getValue("head")
        val maxp = tables.getValue("maxp")
        if (head.second < 54 || maxp.second < 6 || u32(head.first + 12) != 0x5F0F3CF5L || u32(maxp.first) != 0x00010000L) {
            nativeFailure("font.open-type-source-contract-violated", "Captured TrueType metadata has an invalid head/maxp record.")
        }
        val upem = u16(head.first + 18)
        val glyphs = u16(maxp.first + 4)
        val locaFormat = u16(head.first + 50)
        if (upem != record.metadata.unitsPerEm || glyphs != record.metadata.glyphCount || locaFormat !in 0..1 ||
            tables.getValue("loca").second.toLong() < (glyphs + 1L) * if (locaFormat == 0) 2L else 4L) {
            nativeFailure("font.open-type-source-contract-violated", "Captured source metadata disagrees with its face or loca bounds.")
        }
        return record.id.source is FontSourceId.Portable
    }
    private val excludedTags = setOf("CFF ", "CFF2", "fvar", "gvar", "cvar", "avar", "HVAR", "VVAR", "MVAR", "COLR", "CPAL", "SVG ", "CBDT", "CBLC", "sbix", "EBDT", "EBLC", "bdat", "bloc")
}
internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
