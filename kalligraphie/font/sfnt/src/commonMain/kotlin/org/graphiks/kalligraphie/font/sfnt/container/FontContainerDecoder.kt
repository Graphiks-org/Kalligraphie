@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.KalligraphieInternalApi
import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag

/** Managed font-container signal recognised from a source's leading bytes. */
@KalligraphieInternalApi
public enum class ContainerKind {
    /** WOFF 1.0, decoded by [WoffReader]. */
    WOFF,

    /** WOFF 2.0, decoded once its reader lands. */
    WOFF2,
}

/** One decoded managed-container font and the container it arrived in. */
@KalligraphieInternalApi
public class DecodedFont(
    /** Decoded standalone SFNT bytes. */
    public val bytes: ByteArray,
    /** Kind of container the bytes were decoded from. */
    public val kind: ContainerKind,
)

/**
 * Recognises and decodes managed font containers before the plain SFNT path.
 *
 * A source whose first four bytes are neither `wOFF` nor `wOF2` is not a container and is returned
 * as a `null` value, leaving the caller's existing SFNT handling untouched. `wOFF` routes to
 * [WoffReader]; `wOF2` routing lands with the WOFF 2.0 reader.
 */
@KalligraphieInternalApi
public object FontContainerDecoder {
    /** Decodes [source] under [limits], or returns `null` when it is not a managed container. */
    public fun decode(source: FontSource, limits: WoffDecodeLimits): FontOperationResult<DecodedFont?> {
        val bytes = source.copyBytes()
        if (bytes.size < CONTAINER_SIGNATURE_BYTES) {
            return FontOperationResult.Success(null)
        }
        return when (bytes.decodeAsciiTag(0)) {
            "wOFF" -> decodeWoff(bytes, limits)
            // WOFF 2.0 routing is added with its reader in a follow-up task.
            "wOF2" -> FontOperationResult.Success(null)
            else -> FontOperationResult.Success(null)
        }
    }

    private fun decodeWoff(bytes: ByteArray, limits: WoffDecodeLimits): FontOperationResult<DecodedFont?> =
        when (val decoded = WoffReader.decode(bytes, limits)) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                DecodedFont(decoded.value, ContainerKind.WOFF),
                decoded.diagnostics,
            )
            is FontOperationResult.Failure -> decoded
            is FontOperationResult.Cancelled -> decoded
        }

    private const val CONTAINER_SIGNATURE_BYTES: Int = 4
}
