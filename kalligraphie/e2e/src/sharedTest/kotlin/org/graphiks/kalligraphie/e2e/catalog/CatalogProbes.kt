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
