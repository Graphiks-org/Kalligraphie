# WOFF 1.0 / WOFF 2.0 Container Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `Kalligraphie.embedded` and byte-backed directory font capture ingest WOFF 1.0 and WOFF 2.0 by reconstructing a standalone SFNT the existing pipeline consumes unchanged, with golden e2e scenes, a semantic cross-container proof, and benchmarks.

**Architecture:** An internal `FontContainerDecoder` in `:kalligraphie:font:sfnt` sniffs `wOFF`/`wOF2` and delegates to `WoffReader`/`Woff2Reader` under an independent `WoffDecodeLimits`. WOFF 1.0 inflates each table with zlib (`okio.Inflater`); WOFF 2.0 decompresses **one** Brotli stream for the whole font-data block and reconstructs the `glyf`/`loca`/`hmtx` transforms. `SfntReassembler` builds a valid SFNT with correct checksum sequencing. The facade and capture decode at the boundary, then build `FontSource(decodedBytes, provenance)` and call `SfntReader` (Approach A).

**Tech Stack:** Kotlin Multiplatform (`commonMain` for Android/JVM/iOS), `okio` (existing), `kotlin.test`, `:kalligraphie:e2e` golden harness, `:kalligraphie:bench` kotlinx-benchmark harness, Python `fontTools` corpus tooling.

**Spec:** `docs/superpowers/specs/2026-09-28-woff-woff2-containers-design.md`

## Global Constraints

- All new production code lives in `:kalligraphie:font:sfnt` `commonMain`; no new third-party dependency. `okio` is already an `implementation` dependency there.
- Types on the `font:core`/`:kalligraphie` boundary are `public` **and** annotated `@org.graphiks.kalligraphie.api.KalligraphieInternalApi`; do not make an internal type part of a public signature. No change to the public `:kalligraphie:api` surface.
- Single-face containers only. `flavor == 'ttcf'` returns `FontError.UnsupportedContainer`.
- No bound may derive solely from an untrusted declared length. `WoffDecodeLimits.maxDecodedFontBytes` caps every produced buffer and `maxWorkingBytes` caps temporary work; `totalSfntSize` and a transformed `glyf`'s `origLength` are **advisory** and must never cause rejection. Breaches return `FontResourceLimitExceeded`.
- Malformed structure returns `FontError.FontDataFailure(code, message, location)` with the exact codes from spec §6.4.
- Test commands: `./gradlew :kalligraphie:font:sfnt:allTests` for the decoder; `./gradlew check` and `./gradlew allTests` for the repo.
- Conventional Commits; allowed scopes include `sfnt`, `font-core`, `kalligraphie`, `e2e`, `bench`, `docs`, `ci`. `build` is a **type**, never a scope. Branch `feat/sfnt-woff-containers`; PR from the fork to `Graphiks-org/Kalligraphie`.
- Regenerate committed artifacts with `./gradlew :kalligraphie:e2e:updateE2eGolden`; never hand-edit `manifest.tsv`, the catalog matrices or `claimed-tables.json`.
- Corpus commands in `scripts/fonts/README.md` are local obligations and must pass before committing fixture changes.

## Review Focus

The five input classes / failure modes most likely to bite a user, each pinned by a test in the task noted:

1. **Collection flavor.** A WOFF/WOFF2 whose `flavor` is `ttcf` must fail with `font.unsupported-container`, never crash or parse one face. (Task 6, Task 7.)
2. **Decompression bomb.** A container declaring a huge `totalSfntSize`/`origLength`, or whose Brotli/zlib stream expands past the declared and independent limits, must fail with `font.resource-limit-exceeded` before allocation. (Task 4, Task 6, Task 7.)
3. **Advisory lengths.** A correctly reconstructed font whose size differs from `totalSfntSize` (and whose `glyf` differs from `origLength`) must be **accepted**. (Task 7.)
4. **Null transform (`transformVersion 3`).** `glyf`/`loca` stored untransformed must pass through verbatim. (Task 7, Task 8.)
5. **`hmtx` shape.** The transformed `hmtx` must reconstruct omitted bearings from glyph `xMin` for flags 1/2/3 and every count shape. (Task 9.)

## Test-only helpers

Defined once, in the test source set of the task named; none is production code.

- `WoffTestFonts` (`sfnt` `commonTest`, `container`): `singleTableSfnt()`, `wrapUncompressed(font, flavor = 0x00010000u)`, `wrapDeflated(font)` (okio `Deflater`).
- `Woff2TestFonts` (`sfnt` `commonTest`): `singleTableUntransformed()`, `withCollectionFlavor()`, `withUnknownTransform()`, `withBadUIntBase128()`.
- `Woff2GlyfVectors` / `Woff2HmtxVectors` (`sfnt` `commonTest`): base64 data holders generated once with fontTools + brotli.
- `BrotliVectors` (`sfnt` `commonTest`): base64 constants `EMPTY`, `TEXT`, `DICTIONARY_USER`, `MULTI_BLOCK`, plus malformed streams.
- `WoffPaths` (`e2e` `sharedTest`): `WOFF`, `WOFF2` resource-path constants.
- `outlineCommandsOf(bytes, codePoint)` / `advanceOf(bytes, codePoint)` (`e2e` `sharedTest`): resolve through the public facade and return comparable values.
- `TestCorpus` (`bench` `commonTest`): an in-memory `FixtureCorpus`.

---

## Phase 1 — Vendored Brotli decoder

### Task 1: Bit reader and Huffman code reader

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliBits.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliHuffman.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliBitsTest.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliHuffmanTest.kt`

**Interfaces:**
- Produces: `internal class BrotliBits(input: ByteArray)` with `fun readBits(count: Int): Int`, `fun readBit(): Int`, `fun alignToByte()`, `fun hasMore(): Boolean`, `var overran: Boolean`; `internal class BrotliHuffman` with `fun readCode(bits: BrotliBits): Int` and `internal companion object { fun fromCodeLengths(codeLengths: IntArray, maxBits: Int): BrotliHuffman; fun fromSingleSymbol(symbol: Int): BrotliHuffman }`; `internal object BrotliHuffmanReader { fun read(bits: BrotliBits, alphabetSize: Int): BrotliHuffman }` (parses the §3.4 simple / §3.5 complex code description from the stream, including the four-symbol `tree-select` bit, then builds the tree).

- [ ] **Step 1: Write the failing bit-reader test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliBitsTest {
    @Test
    fun readsLeastSignificantBitFirst() {
        // 0b10110001 => first four bits (LSB first) are 1,0,0,0
        val bits = BrotliBits(byteArrayOf(0b10110001.toByte()))
        assertEquals(0b0001, bits.readBits(4))
        assertEquals(0b1011, bits.readBits(4))
    }

    @Test
    fun readsAcrossByteBoundaries() {
        val bits = BrotliBits(byteArrayOf(0x01, 0x02))
        assertEquals(0x0201, bits.readBits(16))
    }

    @Test
    fun overrunReturnsZerosAndFlags() {
        val bits = BrotliBits(byteArrayOf(0x00))
        assertEquals(0, bits.readBits(20))
        assertEquals(true, bits.overran)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliBitsTest*'`
Expected: FAIL — `BrotliBits` unresolved.

- [ ] **Step 3: Implement `BrotliBits`**

```kotlin
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/** LSB-first bit reader over a byte array, the bit order RFC 7932 uses for integer fields. */
@KalligraphieInternalApi
internal class BrotliBits(private val input: ByteArray) {
    private var bitPosition = 0

    /** True once a read reached past the last byte; the caller must fail the stream. */
    var overran: Boolean = false
        private set

    fun readBit(): Int = readBits(1)

    fun readBits(count: Int): Int {
        var result = 0
        repeat(count) { shift ->
            val byteIndex = bitPosition ushr 3
            if (byteIndex >= input.size) {
                overran = true
            } else {
                val bit = (input[byteIndex].toInt() ushr (bitPosition and 7)) and 1
                result = result or (bit shl shift)
            }
            bitPosition += 1
        }
        return result
    }

    fun alignToByte() {
        val remainder = bitPosition and 7
        if (remainder != 0) bitPosition += 8 - remainder
    }

    fun hasMore(): Boolean = (bitPosition ushr 3) < input.size
}
```

- [ ] **Step 4: Run the bit-reader test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliBitsTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Write the failing Huffman test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliHuffmanTest {
    @Test
    fun aSingleSymbolCodeConsumesNoBits() {
        val code = BrotliHuffman.fromSingleSymbol(2)
        // A one-symbol code returns that symbol without reading any bits.
        assertEquals(2, code.readCode(BrotliBits(byteArrayOf())))
    }

    @Test
    fun aTwoSymbolCanonicalTreeDecodesBothSymbols() {
        val code = BrotliHuffman.fromCodeLengths(intArrayOf(1, 1), maxBits = 1)
        assertEquals(0, code.readCode(BrotliBits(byteArrayOf(0b00000000))))
        assertEquals(1, code.readCode(BrotliBits(byteArrayOf(0b00000001))))
    }

    @Test
    fun readsASimpleOneSymbolCodeDescription() {
        // LSB-first: 2 bits "simple" = 1 (bit0=1), 2 bits NSYM-1 = 0, 1 symbol bit = 1 => 0x11.
        val code = BrotliHuffmanReader.read(BrotliBits(byteArrayOf(0x11)), alphabetSize = 2)
        assertEquals(1, code.readCode(BrotliBits(byteArrayOf())))
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliHuffmanTest*'`
Expected: FAIL — `BrotliHuffman` unresolved.

- [ ] **Step 7: Implement `BrotliHuffman` and `BrotliHuffmanReader`**

`fromCodeLengths` builds the canonical tree from lengths (§3.2), where a code with a single non-zero length decodes with no bits. `readCode` walks the tree LSB-first (prefix codes are read starting at the most significant bit of the code, which is the reverse of the integer bit order). `BrotliHuffmanReader.read` parses: 2 bits (`1` = simple), 2 bits `NSYM-1`, `NSYM` symbols of `ALPHABET_BITS` (the smallest width holding every alphabet symbol), the `tree-select` bit for `NSYM == 4` giving lengths `2,2,2,2` (bit 0) or `1,2,3,3` (bit 1), rejects a symbol ≥ alphabet size or a repeated symbol; otherwise parses the §3.5 complex description (HSKIP, the six code-length code lengths in the fixed order, the repeat codes 16/17 with their repeat-count modification rules, and the closing `(32768 >> len)` sum rule). Return `BrotliHuffman`.

- [ ] **Step 8: Run the Huffman test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliHuffmanTest*'`
Expected: PASS (3 tests).

- [ ] **Step 9: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliBits.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliHuffman.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliBitsTest.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliHuffmanTest.kt
git commit -m "feat(sfnt): add Brotli bit reader and Huffman code reader"
```

### Task 2: Meta-block header, contexts, and alphabets

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliMetaBlock.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliContext.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliAlphabet.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliMetaBlockTest.kt`

**Interfaces:**
- Consumes: `BrotliBits`, `BrotliHuffmanReader` (Task 1).
- Produces: `internal object BrotliMetaBlock { fun readWbits(bits: BrotliBits): Int }` plus the §9.2 header parse; `internal object BrotliContext` with literal/distance context-ID computation; `internal object BrotliAlphabet` with the §5/§7/§6 base tables.

- [ ] **Step 1: Write the failing WBITS test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliMetaBlockTest {
    @Test
    fun aLeadingZeroBitMeansWbits16() {
        // RFC 7932 §9.1: the "16" pattern is the single bit 0.
        assertEquals(16, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00000010))))
    }

    @Test
    fun oneThenThreeZeroBitsMeansWbits17() {
        // "0000001" parsed right-to-left: first bit 1, then 000, then 000.
        assertEquals(17, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00000001))))
    }

    @Test
    fun oneThenWbits24() {
        // "1111" parsed right-to-left: first bit 1, next three bits 111 => 17 + 7 = 24.
        assertEquals(24, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00001111))))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliMetaBlockTest*'`
Expected: FAIL — `BrotliMetaBlock` unresolved.

- [ ] **Step 3: Implement the header, contexts, and alphabet tables**

`readWbits` implements §9.1 exactly: bit 0 ⇒ 16; otherwise read 3 bits `v`; if `v != 0` ⇒ `17 + v`; otherwise read 3 more bits `w`; if `w == 0` ⇒ 17, else `8 + w`. The §9.2 header reader reads `ISLAST`, optional `ISEMPTY`/`ISLASTEMPTY`, `MNIBBLES`/`MLEN`, then `ISUNCOMPRESSED` / `ISMETADATA` / the compressed path. `BrotliContext` computes the literal context ID from the **previous two decoded bytes** (never the byte being decoded) and the selected context mode, and the distance context ID from the current copy length; it also implements the context-map RLE and inverse-MTF decode. `BrotliAlphabet` carries the §5 insert/copy length offset tables, the §7 distance tables, and the §6 block-count/block-type tables.

- [ ] **Step 4: Run the meta-block test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliMetaBlockTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliMetaBlock.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliContext.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliAlphabet.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliMetaBlockTest.kt
git commit -m "feat(sfnt): add Brotli meta-block header, contexts and alphabets"
```

### Task 3: Static dictionary, transforms, and provenance

**Files:**
- Create: `scripts/brotli/fetch_dictionary.py`
- Create: `scripts/brotli/README.md`
- Create (generated): `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionary.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionaryTransforms.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionaryTest.kt`

**Interfaces:**
- Produces: `internal object BrotliDictionary` with `val sizeBytes: Int`, `fun word(offset: Int, length: Int): ByteArray`; `internal object BrotliDictionaryTransforms` with `fun apply(transformId: Int, word: ByteArray): ByteArray`.

- [ ] **Step 1: Write the generator and pin the provenance**

`scripts/brotli/fetch_dictionary.py` downloads the RFC 7932 static dictionary from the pinned Google Brotli source at a tagged commit, verifies its SHA-256, and emits `BrotliDictionary.kt` as several chunk-sized `ByteArray` literals (never one oversized JVM initialiser) plus `sizeBytes`, the `NWORDS`/`NWORDS_BITS` tables and the upstream MIT notice as a comment. `scripts/brotli/README.md` records the pinned commit, the digest, the licence and the regeneration command.

- [ ] **Step 2: Generate and write the failing test**

```bash
python3 scripts/brotli/fetch_dictionary.py
```

```kotlin
package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliDictionaryTest {
    @Test
    fun hasTheRfcSizeAndFirstWord() {
        assertEquals(122_784, BrotliDictionary.sizeBytes)
        // RFC 7932 §8: the first word of the length-4 group is "time".
        assertEquals("time", BrotliDictionary.word(offset = 0, length = 4).decodeToString())
    }

    @Test
    fun identityTransformLeavesTheWordUnchanged() {
        assertEquals("world", BrotliDictionaryTransforms.apply(0, "world".encodeToByteArray()).decodeToString())
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliDictionaryTest*'`
Expected: FAIL — `BrotliDictionary` unresolved.

- [ ] **Step 4: Implement the dictionary accessors and all transforms**

`word(offset, length)`: the byte offset of a length group is `Σ(len × wordCount[len])` over shorter lengths; within a group the word is at `wordIndex × length`, decoded through `NWORDS`/`NWORDS_BITS` (RFC 7932 §8). `BrotliDictionaryTransforms.apply` implements all 121 §8 transforms (prefix/suffix, identity, uppercase/capitalise); the output length may be shorter than the base word, including empty.

- [ ] **Step 5: Run the dictionary test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliDictionaryTest*'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add scripts/brotli \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionary.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionaryTransforms.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionaryTest.kt
git commit -m "feat(sfnt): add Brotli static dictionary and transforms"
```

### Task 4: `BrotliDecoder` with independent bounds and successful vectors

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDecoder.kt`
- Create: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliVectors.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDecoderTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: `internal object BrotliDecoder { fun decode(input: ByteArray, expectedLength: Int, limit: Long): FontOperationResult<ByteArray> }`. `expectedLength` is the exact required output; `limit` is the independent cap; `expectedLength > limit` fails before decoding.

- [ ] **Step 1: Write the failing API test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

class BrotliDecoderTest {
    @Test
    fun decodesAnEmptyStreamToAnEmptyArray() {
        assertContentEquals(
            ByteArray(0),
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.EMPTY, expectedLength = 0, limit = 16),
            ).value,
        )
    }

    @Test
    fun decodesTextSuccessfullyAndByteForByte() {
        assertContentEquals(
            BrotliVectors.TEXT_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.TEXT, expectedLength = BrotliVectors.TEXT_EXPECTED.size, limit = 1_024),
            ).value,
        )
    }

    @Test
    fun decodesAStreamThatUsesTheStaticDictionary() {
        assertContentEquals(
            BrotliVectors.DICTIONARY_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(
                    BrotliVectors.DICTIONARY_USER,
                    expectedLength = BrotliVectors.DICTIONARY_EXPECTED.size,
                    limit = 4_096,
                ),
            ).value,
        )
    }

    @Test
    fun refusesADeclaredLengthBeyondTheIndependentLimit() {
        val failure = assertIs<FontOperationResult.Failure>(
            BrotliDecoder.decode(BrotliVectors.TEXT, expectedLength = 10_000, limit = 16),
        )
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
    }

    @Test
    fun refusesATruncatedStream() {
        val truncated = BrotliVectors.TEXT.copyOf(BrotliVectors.TEXT.size / 2)
        assertIs<FontOperationResult.Failure>(
            BrotliDecoder.decode(truncated, expectedLength = BrotliVectors.TEXT_EXPECTED.size, limit = 1_024),
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliDecoderTest*'`
Expected: FAIL — `BrotliDecoder` unresolved.

- [ ] **Step 3: Implement `BrotliDecoder`**

Wire the meta-block loop, three block categories with block switching, literal/command/distance code readers, context modelling, insert-and-copy, the four-entry distance ring buffer (initialised `16, 15, 11, 4`, never reset at meta-block boundaries, not advanced for symbol 0 or dictionary references), overlapping copies, and static-dictionary references (base word length 4..24, transformed output of any length), plus metadata and uncompressed meta-blocks. Write into a bounded buffer that fails `ResourceLimitExceeded` past `limit` (or before decoding if `expectedLength > limit`), and fail `FontDataFailure("font.woff2.brotli-failed", …)` on `overran`, reserved bits, invalid codes, an over-long stream, or an output length other than `expectedLength`. A stream with trailing bytes after the final block is rejected.

- [ ] **Step 4: Add the vectors**

`BrotliVectors.kt` holds base64 constants and, for the two success cases, the expected plaintext: a literal-only text, a dictionary-using text, a multi-block stream, and the empty stream, produced with a recorded Brotli CLI version; plus malformed streams (truncated, bad block type, reserved bit). Record the exact producing command in a comment.

- [ ] **Step 5: Run the API test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliDecoderTest*'`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDecoder.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDecoderTest.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliVectors.kt
git commit -m "feat(sfnt): expose a bounded Brotli decoder"
```

---

## Phase 2 — Limits, SFNT reassembly, WOFF 1.0, and dispatch

### Task 5: `WoffDecodeLimits` and `SfntReassembler`

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/WoffDecodeLimits.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/SfntReassembler.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/SfntReassemblerTest.kt`

**Interfaces:**
- Produces: `@KalligraphieInternalApi public class WoffDecodeLimits(val maxDecodedFontBytes: Long, val maxWorkingBytes: Long)` with `companion { val EMBEDDED: WoffDecodeLimits; fun forCapture(maxSourceBytes: Int): WoffDecodeLimits }`; `internal class SfntTable(val tag: String, val data: ByteArray, val originalChecksum: UInt? = null)`; `internal object SfntReassembler { fun assemble(flavor: UInt, tables: List<SfntTable>): ByteArray; fun tableChecksum(bytes: ByteArray): UInt; fun wholeFontChecksum(font: ByteArray): UInt }`.

- [ ] **Step 1: Write the failing reassembler test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertEquals

class SfntReassemblerTest {
    @Test
    fun writesASortedPaddedDirectory() {
        val sfnt = SfntReassembler.assemble(0x00010000u, listOf(SfntTable("head", ByteArray(54)), SfntTable("cmap", ByteArray(5))))
        assertEquals(0x00010000u, readUInt32(sfnt, 0))
        assertEquals(2, readUInt16(sfnt, 4)!!.toInt())
        assertEquals("cmap", sfnt.decodeAsciiTag(12))
        assertEquals("head", sfnt.decodeAsciiTag(28))
        assertEquals(0, sfnt.size % 4)
    }

    @Test
    fun theHeadDirectoryChecksumIsComputedWithTheAdjustmentZeroed() {
        // A head whose incoming checkSumAdjustment is non-zero must still get the right directory checksum.
        val head = ByteArray(54).also { it[8] = 0x12; it[9] = 0x34; it[10] = 0x56; it[11] = 0x78 }
        val sfnt = SfntReassembler.assemble(0x00010000u, listOf(SfntTable("head", head)))
        val recorded = readUInt32(sfnt, 12 + 4)!!  // head is the only table, first record, checksum at +4
        val expected = SfntReassembler.tableChecksum(ByteArray(54))
        assertEquals(expected, recorded)
        assertEquals(0xB1B0AFBAu, SfntReassembler.wholeFontChecksum(sfnt))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*SfntReassemblerTest*'`
Expected: FAIL — `SfntReassembler` unresolved.

- [ ] **Step 3: Implement `SfntReassembler`**

Expose `tableChecksum(bytes): UInt` (sum of big-endian 32-bit words, zero-padded). `assemble` computes `searchRange`/`entrySelector`/`rangeShift`, sorts by tag, copies `head` with `bytes[8..11]` zeroed, records each table's checksum (zeroed `head` for the `head` record, or `originalChecksum` when supplied), 4-byte pads, writes the directory, then patches `head.checkSumAdjustment = 0xB1B0AFBA - wholeFontChecksum(out)`. `WoffDecodeLimits` is a small immutable value class as above.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*SfntReassemblerTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/WoffDecodeLimits.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/SfntReassembler.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/SfntReassemblerTest.kt
git commit -m "feat(sfnt): reassemble a standalone SFNT with correct checksum sequencing"
```

### Task 6: `WoffReader` and `FontContainerDecoder`

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/WoffReader.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/FontContainerDecoder.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/WoffReaderTest.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/FontContainerDecoderTest.kt`

**Interfaces:**
- Consumes: `SfntReassembler`, `WoffDecodeLimits` (Task 5), `okio.Inflater`/`InflaterSource`.
- Produces: `internal object WoffReader { fun decode(bytes: ByteArray, limits: WoffDecodeLimits): FontOperationResult<ByteArray> }`; `@KalligraphieInternalApi public enum class ContainerKind { WOFF, WOFF2 }`; `@KalligraphieInternalApi public class DecodedFont(val bytes: ByteArray, val kind: ContainerKind)`; `@KalligraphieInternalApi public object FontContainerDecoder { fun decode(source: FontSource, limits: WoffDecodeLimits): FontOperationResult<DecodedFont?> }` (WOFF2 routing lands in Task 7).

- [ ] **Step 1: Write the failing WOFF reader test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

class WoffReaderTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun roundTripsAnUncompressedDirectory() {
        val font = WoffTestFonts.singleTableSfnt()
        val decoded = assertIs<FontOperationResult.Success<ByteArray>>(
            WoffReader.decode(WoffTestFonts.wrapUncompressed(font), limits),
        ).value
        assertEquals("cmap", decoded.decodeAsciiTag(12))
        assertEquals(0xB1B0AFBAu, SfntReassembler.wholeFontChecksum(decoded))
    }

    @Test
    fun roundTripsADeflatedTable() {
        val font = WoffTestFonts.singleTableSfnt()
        val decoded = assertIs<FontOperationResult.Success<ByteArray>>(
            WoffReader.decode(WoffTestFonts.wrapDeflated(font), limits),
        ).value
        assertEquals("cmap", decoded.decodeAsciiTag(12))
    }

    @Test
    fun refusesATruncatedHeader() {
        assertEquals(
            "font.woff.invalid-header",
            assertIs<FontOperationResult.Failure>(WoffReader.decode(ByteArray(10), limits)).error.code,
        )
    }

    @Test
    fun refusesACollectionFlavor() {
        val woff = WoffTestFonts.wrapUncompressed(WoffTestFonts.singleTableSfnt(), flavor = 0x74746366u)
        assertIs<FontError.UnsupportedContainer>(
            assertIs<FontOperationResult.Failure>(WoffReader.decode(woff, limits)).error,
        )
    }

    @Test
    fun refusesAnOverlapOrDuplicateTag() {
        val woff = WoffTestFonts.wrapWithDuplicateTag()
        assertEquals(
            "font.woff.invalid-table-directory",
            assertIs<FontOperationResult.Failure>(WoffReader.decode(woff, limits)).error.code,
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*WoffReaderTest*'`
Expected: FAIL — `WoffReader` unresolved.

- [ ] **Step 3: Implement `WoffReader` and the dispatcher**

`WoffReader.decode` implements spec §6.2: field-map validation, complete declared extent, aligned non-overlapping table/meta/private extents (absent blocks zero), `compLength > origLength` reject, raw copy when equal, zlib inflate to exactly `origLength` otherwise (bounded by `maxDecodedFontBytes`), `ttcf` refusal, exact reassembled size, then `SfntReassembler`. `FontContainerDecoder.decode` copies `source.copyBytes()`, returns `null` unless the first four bytes are `wOFF`/`wOF2`, and routes `wOFF` to `WoffReader` (WOFF2 → Task 7).

- [ ] **Step 4: Run the WOFF reader test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*WoffReaderTest*'`
Expected: PASS (5 tests).

- [ ] **Step 5: Write and run the dispatcher test**

```kotlin
class FontContainerDecoderTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun passesThroughANonContainerSource() {
        val sfnt = WoffTestFonts.singleTableSfnt()
        val result = assertIs<FontOperationResult.Success<DecodedFont?>>(
            FontContainerDecoder.decode(FontSource(sfnt, FontSourceProvenance("plain")), limits),
        )
        assertEquals(null, result.value)
    }

    @Test
    fun decodesAWoffSourceToItsKind() {
        val woff = WoffTestFonts.wrapUncompressed(WoffTestFonts.singleTableSfnt())
        val result = assertIs<FontOperationResult.Success<DecodedFont?>>(
            FontContainerDecoder.decode(FontSource(woff, FontSourceProvenance("wrapped")), limits),
        )
        assertEquals(ContainerKind.WOFF, result.value?.kind)
    }
}
```

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*FontContainerDecoderTest*'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container
git commit -m "feat(sfnt): decode WOFF 1.0 containers under explicit limits"
```

---

## Phase 3 — WOFF 2.0 header, single-stream Brotli, and transforms

### Task 7: `Woff2Reader` header, directory, and untransformed reassembly

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2Reader.kt`
- Modify: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/FontContainerDecoder.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2ReaderTest.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2TestFonts.kt`

**Interfaces:**
- Consumes: `BrotliDecoder.decode` (Task 4), `SfntReassembler`, `WoffDecodeLimits` (Task 5), `ContainerKind` (Task 6).
- Produces: `internal object Woff2Reader { fun decode(bytes: ByteArray, limits: WoffDecodeLimits): FontOperationResult<ByteArray> }`; `internal object Woff2KnownTags { val tagsByIndex: List<String> }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

class Woff2ReaderTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun decodesAnUntransformedWoff2AndRecomputesChecksums() {
        val decoded = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2Reader.decode(Woff2TestFonts.singleTableUntransformed(), limits),
        ).value
        assertEquals(0xB1B0AFBAu, SfntReassembler.wholeFontChecksum(decoded))
    }

    @Test
    fun acceptsAReconstructedSizeThatDiffersFromTotalSfntSize() {
        // totalSfntSize is advisory; a wrong declaration must not reject a decodable font.
        assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2Reader.decode(Woff2TestFonts.withWrongTotalSfntSize(), limits),
        )
    }

    @Test
    fun refusesACollectionFlavor() {
        assertIs<FontError.UnsupportedContainer>(
            assertIs<FontOperationResult.Failure>(Woff2Reader.decode(Woff2TestFonts.withCollectionFlavor(), limits)).error,
        )
    }

    @Test
    fun refusesAnUnknownTransformVersion() {
        assertEquals(
            "font.woff2.unknown-transform",
            assertIs<FontOperationResult.Failure>(Woff2Reader.decode(Woff2TestFonts.withUnknownTransform(), limits)).error.code,
        )
    }

    @Test
    fun refusesABadUIntBase128() {
        assertEquals(
            "font.woff2.invalid-table-directory",
            assertIs<FontOperationResult.Failure>(Woff2Reader.decode(Woff2TestFonts.withBadUIntBase128(), limits)).error.code,
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2ReaderTest*'`
Expected: FAIL — `Woff2Reader` unresolved.

- [ ] **Step 3: Implement header, directory, single-stream split, reassembly**

Implement spec §6.3: header validation with `ttcf` refusal, non-rejection of non-zero `reserved`, `flags` tag index/transform version, the §4.1 known-tag table, `UIntBase128` bounds, the transform matrix, `transformLength` presence only for non-null transforms, unknown-transform rejection, and transformed-`loca` consistency. Decompress **one** `totalCompressedSize` slice with `BrotliDecoder.decode(block, expectedLength = directorySum, limit = limits.maxDecodedFontBytes)`, split it across directory entries in order (transformed tables take `transformLength`, others `origLength`), pass untransformed tables through, and reassemble. Extend `FontContainerDecoder` to route `wOF2`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2ReaderTest*'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2Reader.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/FontContainerDecoder.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2ReaderTest.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2TestFonts.kt
git commit -m "feat(sfnt): read WOFF 2.0 headers and the single Brotli font-data stream"
```

### Task 8: `glyf` / `loca` transform reconstruction

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2GlyfTransform.kt`
- Modify: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2Reader.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2GlyfTransformTest.kt`

**Interfaces:**
- Consumes: the split transformed `glyf` block and the transformation's declared `numGlyphs`/`indexFormat`.
- Produces: `internal class GlyfReconstruction(val glyf: ByteArray, val loca: ByteArray, val indexFormat: Int)`; `internal object Woff2GlyfTransform { fun reconstruct(transformed: ByteArray, limits: WoffDecodeLimits): FontOperationResult<GlyfReconstruction> }`.

- [ ] **Step 1: Write the failing test covering both index formats**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult

class Woff2GlyfTransformTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun reconstructsASimpleOneGlyphWithShortLoca() {
        val v = Woff2GlyfVectors.SIMPLE_SHORT_LOCA
        val r = assertIs<FontOperationResult.Success<GlyfReconstruction>>(
            Woff2GlyfTransform.reconstruct(v.transformedGlyf, limits),
        ).value
        assertEquals(0, r.indexFormat)
        assertContentEquals(v.expectedLoca, r.loca)
        assertContentEquals(v.expectedGlyf, r.glyf)
    }

    @Test
    fun reconstructsACaseWithLongLocaAndAComposite() {
        val v = Woff2GlyfVectors.COMPOSITE_LONG_LOCA
        val r = assertIs<FontOperationResult.Success<GlyfReconstruction>>(
            Woff2GlyfTransform.reconstruct(v.transformedGlyf, limits),
        ).value
        assertEquals(1, r.indexFormat)
        assertContentEquals(v.expectedLoca, r.loca)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2GlyfTransformTest*'`
Expected: FAIL — `Woff2GlyfTransform` unresolved.

- [ ] **Step 3: Implement the transform**

Parse the §5.1 header (`reserved == 0`, `optionFlags` bit 0 `overlapSimple`, `numGlyphs`, `indexFormat ∈ {0,1}`, seven stream sizes each bounded under `limits.maxWorkingBytes`) and the seven streams. Per glyph: `nContour` (`0` empty repeats the previous `loca` offset and must not have an explicit bbox; `> 0` simple; `-1` composite); simple glyphs read `nContour` `255UInt16` point counts (cumulative → `endPtsOfContours`), `nPoints` flag bytes, then per-flag triplet bytes (1 byte for flags `< 0x7f` in 0..83, 2 for 84..119, 3 for 120..123, 4 for 124..127) decoding all 128 triplet codes to delta-x/delta-y, then a `255UInt16` instruction length and its bytes from the instruction stream; composite glyphs decode the composite stream (flags, args, transforms, instructions). Bbox: `bboxBitmap` is `4 * floor((numGlyphs + 31) / 32)` bytes, MSB-first per glyph; explicit bboxes from `bboxStream`, simple glyphs without the bit infer from points, composites always explicit. If `optionFlags` bit 0 is set, read the `overlapSimpleBitmap` (`ceil(numGlyphs/8)` bytes, MSB-first) and set flag bit 6 on each simple glyph's first flag byte; otherwise clear it. Emit the reconstructed `glyf` and `loca`; enforce `loca` size `(numGlyphs + 1) * entrySize` and that short-format offsets are representable (`offset/2` fits `UInt16`). Wire into `Woff2Reader`: a `glyf` with version 0 is replaced by the reconstruction and its paired `loca` with the produced table.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2GlyfTransformTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2GlyfTransform.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2Reader.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2GlyfTransformTest.kt
git commit -m "feat(sfnt): reconstruct the WOFF2 glyf and loca transforms"
```

### Task 9: `hmtx` transform reconstruction

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2HmtxTransform.kt`
- Modify: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2Reader.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2HmtxTransformTest.kt`

**Interfaces:**
- Consumes: the split transformed `hmtx`, `numberOfHMetrics` (`hhea`), `numGlyphs` (`maxp`) and `xMinByGlyph: IntArray` (from the reconstructed/paired `glyf`).
- Produces: `internal object Woff2HmtxTransform { fun reconstruct(transformed: ByteArray, numberOfHMetrics: Int, numGlyphs: Int, xMinByGlyph: IntArray): FontOperationResult<ByteArray> }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult

class Woff2HmtxTransformTest {
    @Test
    fun flagsOneReconstructsProportionalBearingsFromXMin() {
        val v = Woff2HmtxVectors.FLAGS_ONE
        val hmtx = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2HmtxTransform.reconstruct(v.transformed, numberOfHMetrics = 2, numGlyphs = 2, xMinByGlyph = intArrayOf(3, -4)),
        ).value
        assertContentEquals(v.expected, hmtx)
    }

    @Test
    fun flagsTwoReconstructsTheTrailingBearingsFromXMin() {
        val v = Woff2HmtxVectors.FLAGS_TWO
        val hmtx = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2HmtxTransform.reconstruct(v.transformed, numberOfHMetrics = 1, numGlyphs = 3, xMinByGlyph = intArrayOf(0, 5, 0)),
        ).value
        assertEquals(1 * 4 + (3 - 1) * 2, hmtx.size)
        assertContentEquals(v.expected, hmtx)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2HmtxTransformTest*'`
Expected: FAIL — `Woff2HmtxTransform` unresolved.

- [ ] **Step 3: Implement the transform**

Per spec §5.4: read `flags` and reject anything outside the three valid values (bit 0 = `lsb[]` absent, bit 1 = `leftSideBearing[]` absent; bits 2-7 must be zero). Read all `numberOfHMetrics` advance widths; if bit 0 is clear read the proportional `lsb[]`, else derive each from `xMinByGlyph` (zero for empty glyphs); if bit 1 is clear read the trailing `leftSideBearing[]` (`numGlyphs - numberOfHMetrics` entries), else derive; emit the normal interleaved `hmtx` (`numberOfHMetrics` advance/lsb pairs followed by the trailing bearings). Wire into `Woff2Reader` after all tables are decoded so `hhea`/`maxp`/`glyf` are available; this includes the case where `glyf` itself used the null transform (then `xMin` is read from the passthrough `glyf` records).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2HmtxTransformTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2HmtxTransform.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2Reader.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2HmtxTransformTest.kt
git commit -m "feat(sfnt): reconstruct the WOFF2 hmtx transform"
```

---

## Phase 4 — Corpus and tooling

### Task 10: Acquire the real fixtures and extend `scripts/fonts`

**Files:**
- Create: `test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff`
- Create: `test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff2`
- Create: `test-fixtures/fonts/woff-ibm-plex/PROVENANCE.md`
- Create: `test-fixtures/fonts/woff-ibm-plex/OFL.txt`
- Modify: `.gitattributes`
- Modify: `scripts/fonts/corpus.json`
- Modify: `scripts/fonts/fetch_fonts.py`
- Modify: `scripts/fonts/README.md`
- Modify: `scripts/fonts/tests/test_fetch_fonts.py`

**Interfaces:**
- Produces: the `woff-ibm-plex` family used by later tasks. **Not committed in this task** — the commit happens in Task 13 once the exhaustiveness lint passes.

- [ ] **Step 1: Download the pinned files and measure them**

```bash
mkdir -p test-fixtures/fonts/woff-ibm-plex
BASE=https://raw.githubusercontent.com/IBM/plex/763c36ef9117782905ae010056dfbe8fd2653a25/packages/plex-sans/fonts/complete
curl -fL "$BASE/woff/IBMPlexSans-Regular.woff"   -o test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff
curl -fL "$BASE/woff2/IBMPlexSans-Regular.woff2" -o test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff2
shasum -a 256 test-fixtures/fonts/woff-ibm-plex/*.woff*
```

- [ ] **Step 2: Write `PROVENANCE.md` and copy the licence**

Record the repository, the pinned commit, both raw URLs, each measured SHA-256 and size, the OFL-1.1 licence, and a note that the two files are the same font and (per spec §4.3) may decode to different bytes.

- [ ] **Step 3: `.gitattributes` and `fetch_fonts.py`**

Add `*.woff binary` (verify `*.woff2 binary` exists). Add `.woff`/`.woff2` to `FONT_SUFFIXES` in `scripts/fonts/fetch_fonts.py` (line ~48) and update its coverage tests for both extensions. Update `scripts/fonts/README.md`: the artifact definition, and that the table reader is `check_exhaustiveness.py`, which for WOFF2 needs fontTools + `brotli`.

- [ ] **Step 4: Populate `corpus.json`**

Add the `woff-ibm-plex` family (`synthetic: false`) with measured `sha256`/`sizeBytes`, real `tables` read through fontTools + brotli (every tag, `GlyphOrder` excluded), `url`, `rawUrl`, `revision`, `license` (`OFL-1.1`), `licenseFile`.

- [ ] **Step 5: Run the offline checks and capture the unclaimed-table list**

```bash
python3 scripts/fonts/fetch_fonts.py --check --provenance
uv run --with fonttools==4.65.0 --with brotli python scripts/fonts/check_exhaustiveness.py
python3 -m unittest discover -s scripts/fonts/tests -v
```

Expected: `--check` and the unit tests pass; the exhaustiveness lint reports the new family's carried tables as unclaimed — record that list for Task 13. Do not commit yet.

### Task 11: `Kalligraphie.embedded` accepts WOFF/WOFF2

**Files:**
- Modify: `kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/EmbeddedFontCatalog.kt` (`EmbeddedFontCatalogFactory.create`)
- Test: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/EmbeddedWoffCatalogTest.kt`

**Interfaces:**
- Consumes: `FontContainerDecoder.decode`, `WoffDecodeLimits` (Tasks 5–7).
- Produces: no new type; `create` normalises the source list to decoded SFNT before duplicate/generation/parse.

- [ ] **Step 1: Write the failing test (corpus-backed, `:kalligraphie` jvmTest)**

```kotlin
package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance

class EmbeddedWoffCatalogTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/woff-ibm-plex/$name")).use { it!!.readBytes() }

    @Test
    fun createsACatalogFromWoffAndWoff2() {
        for (name in listOf("IBMPlexSans-Regular.woff", "IBMPlexSans-Regular.woff2")) {
            val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
                Kalligraphie.embedded(fixture(name), FontSourceProvenance(name)),
            ).value
            assertEquals(1, catalog.faces.size)
        }
    }

    @Test
    fun twoIdenticalContainersAreRejectedAsDuplicates() {
        val bytes = fixture("IBMPlexSans-Regular.woff")
        val result = Kalligraphie.embedded(
            listOf(
                org.graphiks.kalligraphie.api.FontSource(bytes, FontSourceProvenance("a")),
                org.graphiks.kalligraphie.api.FontSource(bytes, FontSourceProvenance("b")),
            ),
        )
        assertIs<FontOperationResult.Failure>(result)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:jvmTest --tests '*EmbeddedWoffCatalogTest*'`
Expected: FAIL — the catalog rejects the WOFF/WOFF2 bytes.

- [ ] **Step 3: Implement the normalised decode**

In `create`, first map every source through `FontContainerDecoder.decode(source, WoffDecodeLimits.EMBEDDED)` into a `List<FontSource>` (a decode failure returns the failure with accumulated diagnostics), then run the existing duplicate check, generation computation and `SfntReader.readMetadata` over that list.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:jvmTest --tests '*EmbeddedWoffCatalogTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/EmbeddedFontCatalog.kt \
        kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/EmbeddedWoffCatalogTest.kt
git commit -m "feat(font-core): accept WOFF and WOFF2 in the embedded facade"
```

### Task 12: Directory capture discovers and decodes `.woff`/`.woff2`

**Files:**
- Modify: `kalligraphie/src/jvmMain/kotlin/org/graphiks/kalligraphie/FontDirectoryCapture.kt`
- Test: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/FontDirectoryWoffCaptureTest.kt`

**Interfaces:**
- Consumes: `FontContainerDecoder.decode`, `WoffDecodeLimits.forCapture` (Tasks 5–7).

- [ ] **Step 1: Write the failing test**

```kotlin
package org.graphiks.kalligraphie

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult

class FontDirectoryWoffCaptureTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/fonts/woff-ibm-plex/$name")).use { it!!.readBytes() }

    @Test
    fun discoversAndDecodesBothContainers() {
        val root = createTempDirectory("woff-capture")
        Files.write(root.resolve("plex.woff"), fixture("IBMPlexSans-Regular.woff"))
        Files.write(root.resolve("plex.woff2"), fixture("IBMPlexSans-Regular.woff2"))
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            FontDirectoryCatalog.open(FontDirectoryCatalogOptions(roots = listOf(root.toString()))),
        ).value
        assertEquals(2, catalog.faces.size)
    }

    @Test
    fun anInvalidWoffIsRejectedWithoutFailingTheCapture() {
        val root = createTempDirectory("woff-capture-bad")
        Files.write(root.resolve("broken.woff"), "wOFF".encodeToByteArray())
        Files.write(root.resolve("plex.woff"), fixture("IBMPlexSans-Regular.woff"))
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            FontDirectoryCatalog.open(FontDirectoryCatalogOptions(roots = listOf(root.toString()))),
        ).value
        assertEquals(1, catalog.faces.size)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:jvmTest --tests '*FontDirectoryWoffCaptureTest*'`
Expected: FAIL — `.woff` is not a candidate.

- [ ] **Step 3: Implement discovery and decoded accounting**

Add `woff`/`woff2` to `isFontCandidate`. For each candidate, build the `FontSource`, decode with `WoffDecodeLimits.forCapture(options.maxSourceBytes)`, deduplicate by **decoded** id, count `retainedBytes` and check `maxTotalSourceBytes` on the decoded size, charge the examination budget for every attempted decode, set `limited = true` when the failure is `ResourceLimitExceeded`, and route other decode failures through `reject(...)`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:jvmTest --tests '*FontDirectoryWoffCaptureTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/src/jvmMain/kotlin/org/graphiks/kalligraphie/FontDirectoryCapture.kt \
        kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/FontDirectoryWoffCaptureTest.kt
git commit -m "feat(kalligraphie): capture WOFF and WOFF2 directory candidates"
```

---

## Phase 5 — e2e catalog, goldens, and generated docs

### Task 13: Promote the container entries, register scenes, and commit the corpus

**Files:**
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/ContainerCatalog.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CorpusKeys.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogClaims.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/RobustnessCatalog.kt`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/SceneFontPaths.kt`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/PortableSceneRenderers.kt`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogProbes.kt`
- Test: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/ContainerEquivalenceTest.kt`

**Interfaces:**
- Consumes: the corpus from Task 10, `outlineCapitalA(corpus, path, what)`.

- [ ] **Step 1: Write the failing semantic equivalence test**

```kotlin
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment

class ContainerEquivalenceTest {
    @Test
    fun woffAndWoff2ResolveTheSameGlyphBehaviour() {
        val corpus = E2eTestEnvironment.corpus
        assertEquals(
            outlineCommandsOf(corpus.bytes(WoffPaths.WOFF2), 0x41),
            outlineCommandsOf(corpus.bytes(WoffPaths.WOFF), 0x41),
        )
        assertEquals(
            advanceOf(corpus.bytes(WoffPaths.WOFF2), 0x41),
            advanceOf(corpus.bytes(WoffPaths.WOFF), 0x41),
        )
    }
}
```

(`outlineCommandsOf`/`advanceOf` resolve through `Kalligraphie.embedded` and the public facade; raw table bytes are not compared because WOFF2 reconstruction legitimately differs.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*ContainerEquivalenceTest*'`
Expected: FAIL — `WoffPaths` unresolved.

- [ ] **Step 3: Add keys, paths, renderers, entries, claims, robustness**

Add the `woff-ibm-plex` `CorpusKey` shared by both entries, the two `SceneFontPaths`, the two `PortableSceneRenderers` (`glyph.outline.woff-ibm-plex.A.64`, `glyph.outline.woff2-ibm-plex.A.64`), and promote both `ContainerCatalog` entries to `Supported` with a real `sinceCommit`, the outline-table claim set (mirroring `outline.glyf-simple-composite`), motivated `UNREAD_TABLES` entries for every table the Task 10 lint named, `family = GLYPH_OUTLINE`, `route = PORTABLE_GLYPH`, `frame = AutoSized(padding = 1)`. Add robustness entries (`robustness.woff-truncated`, `robustness.woff2-brotli-corrupted` using a deterministically malformed Brotli stream) with `CatalogProbes` whose `fontPath` is under `woff-ibm-plex`.

- [ ] **Step 4: Run the equivalence and ratchets**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*ContainerEquivalenceTest*' --tests '*ExpectationCatalogRatchetTest*'`
Expected: PASS.

- [ ] **Step 5: Run the corpus lint and commit fixtures + claims together**

```bash
uv run --with fonttools==4.65.0 --with brotli python scripts/fonts/check_exhaustiveness.py
python3 -m unittest discover -s scripts/fonts/tests -v
```

Expected: no output (lint passes). Then commit the corpus and catalog claims in one commit:

```bash
git add test-fixtures/fonts/woff-ibm-plex .gitattributes scripts/fonts kalligraphie/e2e/src
git commit -m "feat(e2e): add the WOFF/WOFF2 corpus family and certify its scenes"
```

### Task 14: Regenerate goldens and generated docs; update user docs

**Files:**
- Modify (generated): `kalligraphie/e2e/src/harnessResources/golden/manifest.tsv`, `docs/docs/generated/e2e-catalog-matrix.md`/`.fr.md`, `kalligraphie/e2e/src/harnessResources/catalog/claimed-tables.json`
- Modify: `kalligraphie/e2e/build.gradle.kts` (`iosFixtureCorpus`)
- Modify: `docs/docs/font-management.md` / `.fr.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add the fixtures to the iOS embedded corpus**

Append the two `woff-ibm-plex` files to the `iosFixtureCorpus` `entries` list.

- [ ] **Step 2: Regenerate**

```bash
./gradlew :kalligraphie:e2e:updateE2eGolden
```

Expected: the manifest, both matrices and `claimed-tables.json` gain the two scenes and the promoted entries.

- [ ] **Step 3: Document the feature**

`font-management.md`/`.fr.md`: WOFF/WOFF2 in the supported scope, `woff`/`woff2` discovery extensions, and the decoded-byte identity paragraph (§4.3, including that different containers of one font generally do not share identity). `CHANGELOG.md`: the `feat(sfnt)` entry.

- [ ] **Step 4: Run the freshness tests**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogMatrixRunnerTest*' --tests '*CatalogClaimsRunnerTest*' --tests '*GoldenVerificationTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/e2e docs CHANGELOG.md
git commit -m "test(e2e): commit the WOFF and WOFF2 goldens and docs"
```

---

## Phase 6 — Benchmarks

### Task 15: Container bench scenarios and per-platform wiring

**Files:**
- Create: `kalligraphie/bench/src/commonMain/kotlin/org/graphiks/kalligraphie/bench/scenarios/ContainerScenarios.kt`
- Modify: `kalligraphie/bench/src/commonMain/kotlin/org/graphiks/kalligraphie/bench/ScenarioRegistry.kt`
- Modify: `kalligraphie/bench/src/jvmBenchmark/kotlin/org/graphiks/kalligraphie/bench/PortableGlyphMaterializationBenchmark.kt` (`@Param` list **and** its `ScenarioRegistry` lookup)
- Modify: `kalligraphie/bench/src/androidDeviceTest/kotlin/org/graphiks/kalligraphie/bench/AndroidGlyphMaterializationBenchmark.kt` (three methods)
- Modify: `kalligraphie/bench/build.gradle.kts` (iOS bench fixture corpus) and the JVM/Android bench fixture corpora
- Test: `kalligraphie/bench/src/commonTest/kotlin/org/graphiks/kalligraphie/bench/scenarios/ContainerScenariosTest.kt`

**Interfaces:**
- Produces: `public fun containerScenarios(corpus: FixtureCorpus): List<MeasurementScenario>` with `WoffColdCapture`, `Woff2ColdCapture`, `Woff2ColdGlyph`.

- [ ] **Step 1: Write the failing scenario test**

```kotlin
package org.graphiks.kalligraphie.bench.scenarios

import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerScenariosTest {
    @Test
    fun exposesTheThreeContainerProfiles() {
        assertEquals(
            listOf("WoffColdCapture", "Woff2ColdCapture", "Woff2ColdGlyph"),
            containerScenarios(TestCorpus).map { it.name },
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:bench:jvmTest --tests '*ContainerScenariosTest*'`
Expected: FAIL — `containerScenarios` unresolved.

- [ ] **Step 3: Implement the scenarios and the wiring**

Each scenario follows the `TrueTypeColdPreparation` shape (cold per sample, `PORTABLE_GLYPH` route, no required capability): the two captures call `Kalligraphie.embedded(bytes)` and consume the face; `Woff2ColdGlyph` resolves the scene glyph's outline and consumes it, counting `sourceBytes`. Add `containerScenarios(corpus)` to `ScenarioRegistry.all`. Extend the JVM `@Param` list **and** change its setup to search `ScenarioRegistry.select(...)` (not `glyphMaterializationScenarios`). Add three benchmark methods to the Android class. Add both files to the iOS bench fixture corpus and to the JVM/Android corpora.

- [ ] **Step 4: Run the test and one JVM profile**

Run: `./gradlew :kalligraphie:bench:jvmTest --tests '*ContainerScenariosTest*'`
Run: `./gradlew :kalligraphie:bench:jvmBenchmarkBenchmark -P... ` (the JVM benchmark `JavaExec`; `--tests` does not select `@Param` values, so run it or scope with the harness's own filter)
Expected: test PASS; the `WoffColdCapture` profile appears in the produced observations/report.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/bench
git commit -m "feat(bench): measure WOFF and WOFF2 container capture on every platform"
```

---

## Phase 7 — Final verification

### Task 16: Full local verification and the PR gate

**Files:**
- Modify: `CHANGELOG.md` only if wording drifts.

- [ ] **Step 1: Full check and all tests**

```bash
./gradlew check
./gradlew allTests
```

Expected: PASS.

- [ ] **Step 2: Corpus obligations**

```bash
python3 scripts/fonts/fetch_fonts.py --check --provenance
uv run --with fonttools==4.65.0 --with brotli python scripts/fonts/check_exhaustiveness.py
python3 -m unittest discover -s scripts/fonts/tests -v
```

Expected: no output / all pass.

- [ ] **Step 3: Generated artifacts are fresh**

```bash
./gradlew :kalligraphie:e2e:updateE2eGolden
git diff --exit-code
```

Expected: no diff.

- [ ] **Step 4: PR gate (CONTRIBUTING)**

Confirm: branch from the latest `master` in a fork; PR targets `Graphiks-org/Kalligraphie`; the PR template headings (`Description`, `Type of Change`, `Checklist`, `Screenshots (if applicable)`, `Additional Notes`) with exactly one change type; the changelog decision and documentation decision recorded; every non-merge commit subject in Conventional Commits with an allowed scope; branch up to date with `master`.

- [ ] **Step 5: Final commit if Step 3 produced changes**

```bash
git add -A
git commit -m "chore(sfnt): finalise WOFF and WOFF2 container support"
```
