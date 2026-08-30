package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.TextDecodingResult
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import org.graphiks.kalligraphie.unicode.TextSnapshots

/**
 * Entry point for loading font sources supported by the portable API.
 *
 * The facade performs parsing and validation only; it does not select a
 * document, renderer, platform font service, or rasterization backend.
 */
public object Kalligraphie {
    /**
     * Decodes UTF-8 source slices into one immutable, canonical [TextDecodingResult].
     *
     * [version] remains the opaque identity of the returned snapshot. Every slice is copied by
     * the text contract before decoding, so callers retain ownership of their byte arrays and
     * may mutate or release them after this call. Malformed subsequences are replaced according
     * to Unicode maximal-subpart rules and reported as structured diagnostics. The result is
     * independent of physical slice boundaries and safe to share between threads.
     */
    public fun decodeUtf8(
        version: TextVersion,
        slices: List<TextSlice.Utf8>,
    ): TextDecodingResult = TextSnapshots.decodeUtf8(version, slices)

    /**
     * Decodes UTF-16 source slices into one immutable, canonical [TextDecodingResult].
     *
     * [version] remains the opaque identity of the returned snapshot. Every slice is copied by
     * the text contract before decoding, so callers retain ownership of their code-unit arrays
     * and may mutate or release them after this call. Malformed subsequences are replaced
     * according to Unicode maximal-subpart rules and reported as structured diagnostics. The
     * result is independent of physical slice boundaries and safe to share between threads.
     */
    public fun decodeUtf16(
        version: TextVersion,
        slices: List<TextSlice.Utf16>,
    ): TextDecodingResult = TextSnapshots.decodeUtf16(version, slices)

    /**
     * Loads one single-face TrueType font from an in-memory byte array.
     *
     * The input is captured before parsing, so subsequent mutations of
     * [sourceBytes] cannot change the returned catalog. [provenance] is
     * retained as the caller-supplied origin label for diagnostics; it is not
     * treated as a cryptographic identity. The accepted SFNT signatures are
     * TrueType `0x00010000` and the legacy `true` tag. Collection (`ttcf`),
     * CFF/OpenType (`OTTO`), Type 1 (`typ1`), truncated data, missing tables,
     * and malformed table ranges produce typed [FontOperationResult.Failure]
     * values.
     *
     * A successful catalog is an immutable snapshot safe to share between
     * threads. It owns the parsed source snapshot but not any renderer
     * resources; callers own every resolver and render asset returned from it
     * and must close those handles. Closing a resolver drains already-admitted
     * acquisitions while rejecting later ones. The catalog and layout values
     * remain usable independently of resolver closure.
     *
     * @param sourceBytes bytes containing exactly one supported SFNT face.
     * @param provenance caller-declared name or origin used for diagnostics and
     * audit trails.
     * @return a catalog snapshot, or a typed failure describing why the bytes
     * cannot be consumed.
     */
    public fun embedded(
        sourceBytes: ByteArray,
        provenance: FontSourceProvenance,
    ): FontOperationResult<FontCatalogSnapshot> {
        val source = FontSource(sourceBytes = sourceBytes, provenance = provenance)
        return when (val parsed = SfntReader.readMetadata(source)) {
            is FontOperationResult.Success<*> -> {
                val parsedFont = parsed.value as org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
                FontOperationResult.Success(EmbeddedFontCatalog(source, parsedFont), parsed.diagnostics)
            }
            is FontOperationResult.Failure -> parsed
            is FontOperationResult.Cancelled -> parsed
        }
    }
}
