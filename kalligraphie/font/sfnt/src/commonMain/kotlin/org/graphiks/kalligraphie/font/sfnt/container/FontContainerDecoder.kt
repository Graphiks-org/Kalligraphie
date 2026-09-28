@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.KalligraphieInternalApi
import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag

/** Managed font-container signal recognised from a source's leading bytes. */
@KalligraphieInternalApi
public enum class ContainerKind {
    /** WOFF 1.0, decoded by [WoffReader]. */
    WOFF,

    /** WOFF 2.0, decoded by [Woff2Reader]. */
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
 * [WoffReader] and `wOF2` to [Woff2Reader]; a recognised container never yields `Success(null)`,
 * it either decodes or returns a typed failure.
 */
@KalligraphieInternalApi
public object FontContainerDecoder {
    /** Decodes [source] under [limits], or returns `null` when it is not a managed container. */
    public fun decode(source: FontSource, limits: WoffDecodeLimits): FontOperationResult<DecodedFont?> =
        try {
            decodeContainer(source, limits)
        } catch (_: ContainerAllocationError) {
            // Defensive decoder boundary: a malformed container that slips past the independent
            // limits must never let an Error escape the public API.
            FontOperationResult.Failure(
                FontError.ResourceLimitExceeded(
                    "The container decode exhausted available memory.",
                    FontDiagnosticLocation.Source,
                ),
            )
        }

    private fun decodeContainer(source: FontSource, limits: WoffDecodeLimits): FontOperationResult<DecodedFont?> {
        val bytes = source.copyBytes()
        if (bytes.size < CONTAINER_SIGNATURE_BYTES) {
            return FontOperationResult.Success(null)
        }
        return when (bytes.decodeAsciiTag(0)) {
            "wOFF" -> decodeWoff(bytes, limits)
            "wOF2" -> decodeWoff2(bytes, limits)
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

    private fun decodeWoff2(bytes: ByteArray, limits: WoffDecodeLimits): FontOperationResult<DecodedFont?> =
        when (val decoded = Woff2Reader.decode(bytes, limits)) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                DecodedFont(decoded.value, ContainerKind.WOFF2),
                decoded.diagnostics,
            )
            is FontOperationResult.Failure -> decoded
            is FontOperationResult.Cancelled -> decoded
        }

    private const val CONTAINER_SIGNATURE_BYTES: Int = 4
}
