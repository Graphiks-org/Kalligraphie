@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.io.encoding.Base64

/**
 * One WOFF2 `glyf` transform vector plus the reconstruction this decoder must produce.
 *
 * [transformedGlyf] is the transformed `glyf` table exactly as it appears in the WOFF2 font-data
 * stream. [expectedGlyf] and [expectedLoca] are the reconstruction this decoder pins.
 */
internal class Woff2GlyfVector(
    val transformedGlyf: ByteArray,
    val expectedGlyf: ByteArray,
    val expectedLoca: ByteArray,
    val indexFormat: Int,
)

/**
 * Test-only WOFF2 `glyf` transform fixtures.
 *
 * The four **hand-constructed** vectors were built byte-by-byte from W3C WOFF2 §5.1 with a
 * throwaway reference encoder that mirrors the specification. The transformed streams contain no
 * encoder-specific optimisation, so the reconstructed `glyf`/`loca` are asserted byte-exactly.
 * Their semantics were cross-checked against `fontTools 4.65.0`'s `WOFF2GlyfTable.reconstruct`
 * (identical point coordinates, on-curve flags, contour counts and composite structure); the only
 * difference is that our emitter does not apply the optional `REPEAT_FLAG` compression and always
 * pads each glyph record to an even length, which the specification explicitly permits.
 *
 * The **real fontTools-derived** fixture is different: [REAL_TRANSFORMED] was produced by
 * fontTools' own encoder, so per W3C WOFF2 §4.1 the reconstruction is not required to be a binary
 * match. It is compared semantically instead.
 *
 * Commands used to produce the fontTools-derived fixtures (Python 3.12):
 *
 * ```
 * uv run --with fonttools==4.65.0 --with brotli python3 gen_hand.py
 * uv run --with fonttools==4.65.0 --with brotli python3 gen_integration.py
 * ```
 *
 * The real font uses glyph order `[".notdef", "A", "B", "C"]`: `.notdef` is empty, `A` is a simple
 * two-contour glyph with an off-curve point, `B` is a two-component composite (translation plus a
 * 2.14 scale) and `C` is a simple off-curve glyph. The `fontTools` builder produced short `loca`
 * (`indexFormat == 0`).
 */
internal object Woff2GlyfVectors {
    /**
     * One simple glyph, short `loca`: a triangle whose middle point is off-curve. Exercises
     * positive/negative short deltas, the `< 84` triplet range and even-record padding.
     */
    val SIMPLE_SHORT_LOCA: Woff2GlyfVector = vector(
        transformed = "AAAAAAABAAAAAAACAAAAAQAAAAMAAAAEAAAAAAAAAAQAAAAAAAEDG6UWk9ROAAAAAAA=",
        glyf = "AAEACgAPACgAHgACAAA3FicKHgUUBQ8A",
        loca = "AAAADA==",
        indexFormat = 0,
    )

    /**
     * A composite glyph followed by an empty glyph, long `loca`. The composite carries an explicit
     * bounding box; the empty glyph repeats the previous offset.
     */
    val COMPOSITE_LONG_LOCA: Woff2GlyfVector = vector(
        transformed = "AAAAAAACAAEAAAAEAAAAAAAAAAAAAAAAAAAABgAAAAwAAAAA//8AAAACAAEFBoAAAAAAAAAAAAoACg==",
        glyf = "//8AAAAAAAoACgACAAEFBg==",
        loca = "AAAAAAAAABAAAAAQ",
        indexFormat = 1,
    )

    /**
     * Two simple glyphs with a mixed `overlapSimpleBitmap`: glyph 0 sets its bit (and its first
     * output flag gains bit 6), glyph 1 clears it. Glyph 0 also carries an explicit bounding box,
     * exercising the simple-glyph bbox path.
     */
    val MIXED_OVERLAP: Woff2GlyfVector = vector(
        transformed = "AAAAAQACAAAAAAAEAAAAAgAAAAIAAAAEAAAAAAAAAAwAAAAAAAEAAQEBAQsAAAoAgAAAAAAAAAAABQAFgA==",
        glyf = "AAEAAAAAAAUABQAAAABxAAABAAoAAAAKAAAAAAAAMwo=",
        loca = "AAAACAAQ",
        indexFormat = 0,
    )

    /** Two odd-length glyphs in a row, short `loca`; the first record must be padded to even. */
    val ODD_LENGTH_THEN_SECOND: Woff2GlyfVector = vector(
        transformed = "AAAAAAACAAAAAAAEAAAAAgAAAAIAAAAEAAAAAAAAAAQAAAAAAAEAAQEBAQEAAAAAAAAAAA==",
        glyf = "AAEAAAAAAAAAAAAAAAAxAAABAAAAAAAAAAAAAAAAMQA=",
        loca = "AAAACAAQ",
        indexFormat = 0,
    )

    /** A real `fontTools`-generated transformed `glyf` block (semantic comparison only). */
    val REAL_TRANSFORMED: ByteArray =
        Base64.decode("AAAAAAAEAAAAAAAIAAAAAwAAAAoAAAARAAAAEAAAAAwAAAAAAAAAAv//AAEEAwML11YKVQsBAdtZZGNjY2NkMZUeHgAAMSsxKwAAIwABAPr/tQAKAAP2FGAAIAAAAP/2/7UBwgHW")

    /** The original TTF `glyf` table the real fixture was derived from. */
    val REAL_REFERENCE_GLYF: ByteArray =
        Base64.decode("AAIAAAAAAMgAyAADAAYAADM2JyMXMzVkZGRkMh5kZJYeAP////b/tQHCAdYAIwABAPr/tQAKAAP2FGAAAAEAAAAAAGQBLAACAAAxEhMyMgEs/tQA")

    /** The original TTF `loca` table for [REAL_REFERENCE_GLYF]. */
    val REAL_REFERENCE_LOCA: ByteArray = Base64.decode("AAAAAAARAB4AKg==")

    /** `indexFormat` of the real fixture's `loca`. */
    const val REAL_INDEX_FORMAT: Int = 0

    /** One single-point glyph per WOFF2 §5.2 triplet code 0..127, decoded by fontTools. */
    val ALL_TRIPLET_CODES_TRANSFORMED: ByteArray =
        Base64.decode("AAAAAACAAAAAAAEAAAAAgAAAAIAAAAE4AAAAAAAAABAAAAAAAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQABAAEAAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0BBQkNERUZHSElKS0xNTk9QUVJTVFVWV1hZWltcXV5fYGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8SABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASABIAEgASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNAASNFYAEjRWABI0VgASNFYAEjRWeAASNFZ4ABI0VngAEjRWeAAAAAAAAAAAAAAAAAAAAAAA")

    /** `(deltaX, deltaY)` per triplet code 0..127, decoded independently by fontTools 4.65.0. */
    val ALL_TRIPLET_DELTAS: IntArray = intArrayOf(0, -18, 0, 18, 0, -274, 0, 274, 0, -530, 0, 530, 0, -786, 0, 786, 0, -1042, 0, 1042, -18, 0, 18, 0, -274, 0, 274, 0, -530, 0, 530, 0, -786, 0, 786, 0, -1042, 0, 1042, 0, -2, -3, 2, -3, -2, 3, 2, 3, -2, -19, 2, -19, -2, 19, 2, 19, -2, -35, 2, -35, -2, 35, 2, 35, -2, -51, 2, -51, -2, 51, 2, 51, -18, -3, 18, -3, -18, 3, 18, 3, -18, -19, 18, -19, -18, 19, 18, 19, -18, -35, 18, -35, -18, 35, 18, 35, -18, -51, 18, -51, -18, 51, 18, 51, -34, -3, 34, -3, -34, 3, 34, 3, -34, -19, 34, -19, -34, 19, 34, 19, -34, -35, 34, -35, -34, 35, 34, 35, -34, -51, 34, -51, -34, 51, 34, 51, -50, -3, 50, -3, -50, 3, 50, 3, -50, -19, 50, -19, -50, 19, 50, 19, -50, -35, 50, -35, -50, 35, 50, 35, -50, -51, 50, -51, -50, 51, 50, 51, -19, -53, 19, -53, -19, 53, 19, 53, -19, -309, 19, -309, -19, 309, 19, 309, -19, -565, 19, -565, -19, 565, 19, 565, -275, -53, 275, -53, -275, 53, 275, 53, -275, -309, 275, -309, -275, 309, 275, 309, -275, -565, 275, -565, -275, 565, 275, 565, -531, -53, 531, -53, -531, 53, 531, 53, -531, -309, 531, -309, -531, 309, 531, 309, -531, -565, 531, -565, -531, 565, 531, 565, -291, -1110, 291, -1110, -291, 1110, 291, 1110, -4660, -22136, 4660, -22136, -4660, 22136, 4660, 22136)

    /** A synthetic WOFF2 wrapping [REAL_TRANSFORMED] with a matching transformed `loca`. */
    val WOFF2_VALID: ByteArray =
        Base64.decode("d09GMgABAAAAAACTAAIAAAAAAAAAAABdAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAClRmCwoAG2UA+C8G7LaytPEffmnkDQu9oUNJ46H39rcb9FpQwOFoYlKQ+CC5pgCpFdZjCjFkO6DKKmt6gFWWKDDFAD1oYENgx4UTB6Bm0iL6u+rK98M3zDEHt+LPJBYRuZQ9")

    /** [WOFF2_VALID] whose transformed `loca` declares a wrong original size (12, not 10). */
    val WOFF2_BAD_LOCA_LENGTH: ByteArray =
        Base64.decode("d09GMgABAAAAAACTAAIAAAAAAAAAAABdAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAClRmCwwAG2UA+C8G7LaytPEffmnkDQu9oUNJ46H39rcb9FpQwOFoYlKQ+CC5pgCpFdZjCjFkO6DKKmt6gFWWKDDFAD1oYENgx4UTB6Bm0iL6u+rK98M3zDEHt+LPJBYRuZQ9")

    /** [WOFF2_VALID] whose `loca` is the null transform while `glyf` stays transformed. */
    val WOFF2_UNPAIRED_LOCA: ByteArray =
        Base64.decode("d09GMgABAAAAAACXAAIAAAAAAAAAAABiAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAClRmywobbwD4nwe2rWK9sRyynlDkJ7cSD+/v+HM9wRDY5Fc0khbYhFwIv3Thl1sgwj2mEEM2ayvBShWdAoEFtKBDQY8ZE0ZAkynaUob36hwifvfhu1aO/dhZhX+S8i2B/FKMh1m1AQ==")

    /** [WOFF2_VALID] with no `loca` entry at all. */
    val WOFF2_NO_LOCA: ByteArray =
        Base64.decode("d09GMgABAAAAAACQAAEAAAAAAAAAAABdAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAClRmG2UA+C8G7LaytPEffmnkDQu9oUNJ46H39rcb9FpQwOFoYlKQ+CC5pgCpFdZjCjFkO6DKKmt6gFWWKDDFAD1oYENgx4UTB6Bm0iL6u+rK98M3zDEHt+LPJBYRuZQ9")

    /** [WOFF2_VALID] whose `glyf` is untransformed while `loca` stays transformed. */
    val WOFF2_GLYF_UNTRANSFORMED: ByteArray =
        Base64.decode("d09GMgABAAAAAACNAAIAAAAAAAAAAABYAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAylQLCgAbUwD4H4VxY70qRcSkLX85Caq27cMMgajB6QSLxWc0povZGEzERDD46QFmnbUU1KUJTBHARgJgMMgU6Pfetn5JkpMeRMQM9to0jC9QybPAAJAdJDiX1d0/")

    private fun vector(transformed: String, glyf: String, loca: String, indexFormat: Int): Woff2GlyfVector =
        Woff2GlyfVector(
            transformedGlyf = Base64.decode(transformed),
            expectedGlyf = Base64.decode(glyf),
            expectedLoca = Base64.decode(loca),
            indexFormat = indexFormat,
        )
}
