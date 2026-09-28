@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.io.encoding.Base64

/**
 * Byte-for-byte Brotli vectors for [BrotliDecoderTest].
 *
 * The `EMPTY`, `TEXT` and `DICTIONARY_USER` streams were produced with the Brotli CLI
 * **1.2.0** (Homebrew `brotli`, `/opt/homebrew/bin/brotli`):
 *
 * ```
 * printf ''            | brotli -q 11 -c   # EMPTY
 * printf 'ABC…+-'      | brotli -q 11 -c   # TEXT (64 distinct bytes; emitted uncompressed)
 * printf 'time year …' | brotli -q 11 -c   # DICTIONARY_USER (unique common English words)
 * ```
 *
 * `MULTI_BLOCK`, `METADATA_THEN_LAST`, `LITERAL_COMPRESSED`, `DICTIONARY_HAND_BUILT`,
 * `LONG_DISTANCE`, `OVERLAPPING` and the malformed (non-zero-fill, bad-block-type, reserved-WBITS)
 * streams are hand-assembled with the same LSB-first bit order as [BrotliTestBitWriter]; each
 * documents the exact meta-blocks it encodes. No byte flip or random corruption is used anywhere: a
 * malformed stream is malformed deterministically, since Brotli has no content checksum.
 */
internal object BrotliVectors {
    /** `printf '' | brotli -q 11 -c`; it is the single `ISLAST = ISLASTEMPTY = 1` header. */
    val EMPTY: ByteArray = decodeBase64("Pw==")

    /** The empty stream decodes to no bytes. */
    val EMPTY_EXPECTED: ByteArray = ByteArray(0)

    /**
     * `printf 'ABC…+-' | brotli -q 11 -c`: 64 distinct printable characters with no repeated pair.
     * The encoder emits it as an **uncompressed** meta-block followed by `ISLASTEMPTY`.
     */
    val TEXT: ByteArray = decodeBase64(
        "jx+AQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5ejAxMjM0" +
            "NTY3ODkrLQM=",
    )

    /** The plaintext [TEXT] was compressed from. */
    val TEXT_EXPECTED: ByteArray =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-".encodeToByteArray()

    /**
     * A literal-only, final, non-empty compressed meta-block, hand-assembled: one command with
     * insert length `4` and no copy, and a four-symbol literal code, so all four bytes are decoded
     * as literals through the literal context model.
     *
     * Bits: `ISLAST = 1`, `ISLASTEMPTY = 0`, `MNIBBLES = 4`, `MLEN = 4`, `NBLTYPES* = 1`,
     * `NPOSTFIX = NDIRECT = 0`, LSB6 context mode, one literal tree with a simple four-symbol code
     * (`A`, `B`, `C`, `D`, all length two), one insert-and-copy simple code for symbol `32`
     * (insert code 4, copy code 0), one distance simple code; then the four literals.
     */
    val LITERAL_COMPRESSED: ByteArray = decodeBase64("YgAAAHSQ0BCRACEAbA==")

    /** The four literals [LITERAL_COMPRESSED] encodes. */
    val LITERAL_COMPRESSED_EXPECTED: ByteArray = "ABCD".encodeToByteArray()

    /**
     * The same literal-only compressed body as [LITERAL_COMPRESSED] but with `ISLAST = 0`, followed
     * immediately (mid-byte, so the next header does not start on a byte boundary) by
     * `ISLASTEMPTY`. This proves the decoder leaves the bit position exact between a compressed block
     * and the next header.
     */
    val COMPRESSED_THEN_LAST: ByteArray = decodeBase64("MAAAAHSQ0BCRACEA7AE=")

    /** The four literals [COMPRESSED_THEN_LAST] encodes. */
    val COMPRESSED_THEN_LAST_EXPECTED: ByteArray = "ABCD".encodeToByteArray()

    /** Unique common English words at quality 11, which makes the encoder use static-dictionary words. */
    val DICTIONARY_USER: ByteArray = decodeBase64(
        "H1oCQMS5eb3er7URqyAQQgZSnsYD0STfgu9WkQKNaOqG1VG1fDG9wIUL0sRjNsJsJQ136hyfahLhexs3jNr8" +
            "0kwZZgEYhTrkB+sFRcOTSDJ1Lbiw5h9dqkHaWBDCsUzIlbjIQRqZPHQ0dQVkqtjozk7sN8C0klFaZAghyVV" +
            "JjZ7yVgo0cMDLbkEv7oiW96UxKYPtPfCg2R0E//pLIPN7Kvz23zcEV2sBzPpE5oVNNJAbkyL7cEyNiDyP0mF" +
            "GHHqH3NU1sP5bv4dbh6UZoyfS7js=",
    )

    /** The plaintext [DICTIONARY_USER] was compressed from. */
    val DICTIONARY_EXPECTED: ByteArray = (
        "time year people way day thing woman life child world school state family student group " +
            "country problem hand part place case week company system program question work " +
            "government number night point home water room mother area money story fact month right " +
            "study book eye job word business issue side kind head house service friend father power " +
            "hour game line end member law car city community name president team minute idea kid " +
            "body information back parent face others level office door health person art war history " +
            "party result change morning reason research girl guy moment air teacher force education"
        ).encodeToByteArray()

    /**
     * Two non-last uncompressed meta-blocks (`ABCD`, `EFGH`) followed by `ISLASTEMPTY`.
     *
     * Hand-assembled: `WBITS = 16`, then each uncompressed header is `ISLAST = 0`, `MNIBBLES = 4`,
     * `MLEN - 1 = 3`, `ISUNCOMPRESSED = 1`, zero fill, four literal bytes; the stream ends with
     * `ISLAST = ISLASTEMPTY = 1`.
     */
    val MULTI_BLOCK: ByteArray = decodeBase64("MAAQQUJDRBgACEVGR0gD")

    /** The concatenation of both meta-blocks of [MULTI_BLOCK]. */
    val MULTI_BLOCK_EXPECTED: ByteArray = "ABCDEFGH".encodeToByteArray()

    /** A metadata meta-block (`MSKIPLEN = 2`, bytes `xy`) followed by `ISLASTEMPTY`; output is empty. */
    val METADATA_THEN_LAST: ByteArray = decodeBase64("rAB4eQM=")

    /**
     * A compressed meta-block header whose literal block-type description is a simple code that
     * names the same symbol twice; [BrotliHuffmanReader.read] rejects it.
     */
    val BAD_BLOCK_TYPE: ByteArray = decodeBase64("AgAgCgA=")

    /**
     * A minimal dictionary reference, hand-assembled: one command with no literals, implicit
     * distance symbol 0 (the initial last distance `4`), and copy length `4`.
     *
     * With no output yet the maximum allowed distance is `0`, so `4` is a dictionary reference with
     * `word_id = 3`, `transform_id = 3 >> NDBITS[4] = 0`, `index = 3`, i.e. `DICT[12..16) = "left"`.
     */
    val DICTIONARY_HAND_BUILT: ByteArray = decodeBase64("YgAAAARACBAA")

    /** The exact dictionary word [DICTIONARY_HAND_BUILT] resolves to. */
    val DICTIONARY_HAND_BUILT_EXPECTED: ByteArray = "left".encodeToByteArray()

    /**
     * An uncompressed `ABCDEFGH` block followed by a final compressed copy at distance `8`.
     *
     * Hand-assembled: the compressed block sets `NDIRECT = 8` and uses insert-and-copy symbol `128`
     * (insert `0`, copy length `2`, explicit distance) with direct distance symbol `23`, which is
     * distance `23 - 16 + 1 = 8`, so the output is `ABCDEFGHAB`. Decoding it with a working limit
     * below `8` must breach the window bound even though the output is only ten bytes.
     */
    val LONG_DISTANCE: ByteArray = decodeBase64("cAAQQUJDREVGR0gRAAAQAiAAiQs=")

    /** The ten bytes [LONG_DISTANCE] produces when the working limit admits the distance. */
    val LONG_DISTANCE_EXPECTED: ByteArray = "ABCDEFGHAB".encodeToByteArray()

    /**
     * A final compressed meta-block with an overlapping copy: two literals `X`, `Y` followed by a
     * copy of length `5` at distance `2`, which the RFC defines as `XYXYX`.
     *
     * Hand-assembled with `NDIRECT = 2` and direct distance symbol `17` (distance `2`); the
     * insert-and-copy symbol is `147` (`insert = 2`, `copy code = 3`, explicit distance). The
     * output is the seven-byte `XYXYXYX`.
     */
    val OVERLAPPING: ByteArray = decodeBase64("wgAACBRWVkwSEQE=")

    /** The seven bytes [OVERLAPPING] produces. */
    val OVERLAPPING_EXPECTED: ByteArray = "XYXYXYX".encodeToByteArray()

    /**
     * A metadata meta-block whose single skipped fill bit is `1`; RFC 7932 §9.2 requires rejection.
     *
     * Bits: `WBITS = 16`, `ISLAST = 0`, `MNIBBLES = 0` (metadata), `reserved = 0`, `MSKIPBYTES = 0`,
     * then the one fill bit set instead of zero.
     */
    val METADATA_NONZERO_FILL: ByteArray = decodeBase64("jA==")

    /**
     * An uncompressed meta-block whose ignored fill bits are not all zero; RFC 7932 §9.2 requires
     * rejection.
     *
     * Bits: `WBITS = 16`, `ISLAST = 0`, `MNIBBLES = 4`, `MLEN - 1 = 1`, `ISUNCOMPRESSED = 1`, then
     * the first ignored bit set instead of zero.
     */
    val UNCOMPRESSED_NONZERO_FILL: ByteArray = decodeBase64("EAAw")

    /** The reserved `WBITS` pattern `0010001`, which names no window. */
    val RESERVED_WBITS: ByteArray = byteArrayOf(0x11)

    private fun decodeBase64(encoded: String): ByteArray = Base64.decode(encoded)
}
