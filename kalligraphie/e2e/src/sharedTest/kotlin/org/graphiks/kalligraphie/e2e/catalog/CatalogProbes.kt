// CatalogProbes.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance

/** The registered probes, keyed by catalog entry id. */
internal object CatalogProbes {
    private const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"

    /** Every registered probe. */
    val byId: Map<String, CatalogProbe> = mapOf(
        "robustness.truncated-sfnt" to CatalogProbe(LIBERATION_SANS) { full ->
            decodeOutcome(full.copyOf(full.size / 3))
        },
        // The zero-byte source is the point of this probe: it declares the corpus key its entry
        // names and hands the facade no font at all.
        "robustness.empty-input" to CatalogProbe(LIBERATION_SANS) { decodeOutcome(ByteArray(0)) },
        "robustness.woff-truncated" to CatalogProbe(WOFF_IBM_PLEX_WOFF) { full ->
            decodeOutcome(full.copyOf(full.size / 3))
        },
        // A random byte flip is not used: Brotli has no content checksum, so the malformed stream
        // is built deterministically instead. The container stays structurally valid and the
        // decoder reaches the Brotli stream only to reject its reserved window-size encoding.
        "robustness.woff2-brotli-corrupted" to CatalogProbe(WOFF_IBM_PLEX_WOFF2) { full ->
            decodeOutcome(corruptBrotliStream(full))
        },
        // The container header, the Brotli stream and the declared sizes are untouched; only the
        // table directory is malformed, so the failure is observed before decompression.
        "robustness.woff2-directory-corrupted" to CatalogProbe(WOFF_IBM_PLEX_WOFF2) { full ->
            decodeOutcome(corruptTableDirectory(full))
        },
    )

    /**
     * Returns a description of the corpus-key mismatch for [entry] and [probe], or `null` when they
     * agree: a probe must exercise a font of the corpus key its entry announces.
     *
     * [CatalogSceneMaterializer.fontPathMismatch] can defer to the auditor's `supported-missing-font`
     * rule when an entry names no corpus key; no rule covers probes, so this ratchet carries the
     * guard instead of silently skipping the entry.
     */
    fun fontPathMismatch(entry: CatalogEntry, probe: CatalogProbe): String? {
        val key = entry.font ?: return "entry ${entry.id} has a probe but declares no corpus key"
        return if (probe.fontPath.contains("/${key.value}/")) {
            null
        } else {
            "entry ${entry.id} declares corpus key ${key.value} but probes ${probe.fontPath}"
        }
    }

    /**
     * Returns [woff2] with the first byte of its font-data block replaced by the reserved Brotli
     * window-size encoding.
     *
     * The block begins at `size - totalCompressedSize` (the big-endian `totalCompressedSize` at
     * header offset 20) because the `woff-ibm-plex` fixture carries no metadata or private-data
     * block after it. The substitution is deterministic and leaves the container header, directory
     * and declared sizes valid, so the decoder reaches the Brotli stream and rejects it with
     * `font.woff2.brotli-failed` rather than failing at the container layer.
     */
    private fun corruptBrotliStream(woff2: ByteArray): ByteArray {
        val corrupted = woff2.copyOf()
        val totalCompressedSize = ((corrupted[20].toInt() and 0xFF) shl 24) or
            ((corrupted[21].toInt() and 0xFF) shl 16) or
            ((corrupted[22].toInt() and 0xFF) shl 8) or
            (corrupted[23].toInt() and 0xFF)
        corrupted[corrupted.size - totalCompressedSize] = RESERVED_WINDOW_SIZE_BYTE
        return corrupted
    }

    /** RFC 7932's reserved window-size encoding, which a compliant decoder must reject. */
    private const val RESERVED_WINDOW_SIZE_BYTE: Byte = 0x11

    /**
     * Returns [woff2] with the first table directory entry's `origLength` set to the forbidden
     * leading-zero `UIntBase128` encoding.
     *
     * The directory begins immediately after the 48-byte header. An entry is one `flags` byte,
     * followed by a four-byte custom tag exactly when the low six bits are the `0x3F` sentinel, then
     * `origLength` as a `UIntBase128`. Replacing the first byte of that value with `0x80` — the
     * encoding W3C WOFF2 §4.2 forbids — leaves the header, the transform selection, the declared
     * sizes and the font-data block intact, so the reader rejects the directory before it reaches
     * the Brotli stream with `font.woff2.invalid-table-directory`. The substitution is a fixed byte
     * at a computed offset, never a random flip.
     */
    private fun corruptTableDirectory(woff2: ByteArray): ByteArray {
        val corrupted = woff2.copyOf()
        val flags = corrupted[DIRECTORY_HEADER_BYTES].toInt() and 0xFF
        val origLengthOffset = if (flags and CUSTOM_TAG_INDEX == CUSTOM_TAG_INDEX) {
            DIRECTORY_HEADER_BYTES + 1 + CUSTOM_TAG_BYTES
        } else {
            DIRECTORY_HEADER_BYTES + 1
        }
        corrupted[origLengthOffset] = FORBIDDEN_BASE128_LEADING_BYTE
        return corrupted
    }

    /** Bytes of the fixed WOFF2 header, after which the table directory starts. */
    private const val DIRECTORY_HEADER_BYTES: Int = 48

    /** The `flags` tag-index sentinel that makes a four-byte custom tag follow. */
    private const val CUSTOM_TAG_INDEX: Int = 0x3F

    /** Width of a WOFF2 custom table tag. */
    private const val CUSTOM_TAG_BYTES: Int = 4

    /** The leading byte RFC 7932/W3C WOFF2 §4.2 forbids in a `UIntBase128`. */
    private const val FORBIDDEN_BASE128_LEADING_BYTE: Byte = 0x80.toByte()

    /** Translates the facade's decode result into a probe observation. */
    private fun decodeOutcome(bytes: ByteArray): ProbeObservation =
        when (val result = Kalligraphie.embedded(sourceBytes = bytes, provenance = FontSourceProvenance(declaredName = "e2e robustness probe"))) {
            is FontOperationResult.Success<*> -> ProbeObservation.Succeeded(
                stage = CatalogStage.DECODE,
                observation = "decoding succeeds; the facade accepts ${bytes.size} bytes",
            )

            is FontOperationResult.Failure -> ProbeObservation.Rejected(
                stage = CatalogStage.DECODE,
                diagnostic = result.error.code,
            )

            is FontOperationResult.Cancelled -> ProbeObservation.Succeeded(
                stage = CatalogStage.DECODE,
                observation = "cancelled; no decode verdict",
            )
        }
}
