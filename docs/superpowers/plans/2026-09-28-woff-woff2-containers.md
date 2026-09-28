# WOFF 1.0 / WOFF 2.0 Container Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `Kalligraphie.embedded` and byte-backed directory font capture ingest WOFF 1.0 and WOFF 2.0 by reconstructing a standalone SFNT the existing pipeline consumes unchanged, with golden e2e scenes, a semantic cross-container proof, and benchmarks.

**Architecture:** An internal `FontContainerDecoder` in `:kalligraphie:font:sfnt` sniffs `wOFF`/`wOF2` and delegates to `WoffReader`/`Woff2Reader` under an independent `WoffDecodeLimits`. WOFF 1.0 inflates each table with zlib (`okio.Inflater`); WOFF 2.0 decompresses **one** Brotli stream for the whole font-data block and reconstructs the `glyf`/`loca`/`hmtx` transforms. `SfntReassembler` builds a valid SFNT with correct checksum sequencing. The facade and capture decode at the boundary, then build `FontSource(decodedBytes, provenance)` and call `SfntReader` (Approach A).

**Tech Stack:** Kotlin Multiplatform (`commonMain` for Android/JVM/iOS), `okio` (existing), `kotlin.test`, `:kalligraphie:e2e` golden harness, `:kalligraphie:bench` kotlinx-benchmark harness, Python `fontTools` corpus tooling.

**Spec:** `docs/superpowers/specs/2026-09-28-woff-woff2-containers-design.md`

## Global Constraints

- All new production code lives in `:kalligraphie:font:sfnt` `commonMain`; no new third-party dependency. `okio` is already an `implementation` dependency there.
- Types on the `font:core`/`:kalligraphie` boundary are `public` **and** annotated `@org.graphiks.kalligraphie.api.KalligraphieInternalApi`; never expose an `internal` type from a `public` signature. No change to the public `:kalligraphie:api` surface.
- `@KalligraphieInternalApi` is an **error-level** opt-in: every new test file that names these types starts with `@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)`.
- Use the existing `FontError.ResourceLimitExceeded`; do not invent a `FontResourceLimitExceeded`.
- Single-face containers only. `flavor == 'ttcf'` returns `FontError.UnsupportedContainer`.
- No bound may derive solely from an untrusted declared length. `WoffDecodeLimits(maxDecodedFontBytes, maxWorkingBytes)` caps every produced buffer and every temporary. This is **WOFF 2.0-specific**: there `totalSfntSize` and a transformed `glyf`'s `origLength` are advisory and must never cause rejection. WOFF 1.0's `totalSfntSize` and `reserved` fields are **normative** and mismatches are rejected.
- Malformed structure returns `FontError.FontDataFailure(code, message, location)` with the exact codes from spec §6.4. A valid WOFF2 Brotli stream whose decoded length disagrees with the directory yields `font.woff2.invalid-font-data-size`, distinct from a malformed stream's `font.woff2.brotli-failed`.
- Test commands: `./gradlew :kalligraphie:font:sfnt:allTests` for the decoder; `./gradlew check` and `./gradlew allTests` for the repo.
- Conventional Commits; allowed scopes include `sfnt`, `font-core`, `kalligraphie`, `e2e`, `bench`, `docs`, `ci`. `build` is a **type**, never a scope. Branch `feat/sfnt-woff-containers`; PR from the fork to `Graphiks-org/Kalligraphie`.
- Regenerate committed artifacts with `./gradlew :kalligraphie:e2e:updateE2eGolden`; never hand-edit `manifest.tsv`, the catalog matrices or `claimed-tables.json`.
- The exhaustiveness lint reads the generated `claimed-tables.json`, not the Kotlin claims: regenerate before linting. Corpus commands in `scripts/fonts/README.md` must pass before committing fixture changes.

## Review Focus

The five input classes / failure modes most likely to bite a user, each pinned by a test in the task noted:

1. **Collection flavor.** A WOFF/WOFF2 whose `flavor` is `ttcf` must fail with `font.unsupported-container`. (Task 6, Task 7.)
2. **Decompression bomb.** A container whose Brotli/zlib stream expands past the independent `WoffDecodeLimits` must fail with `font.resource-limit-exceeded` before unbounded allocation, regardless of what its declared lengths claim. (Task 4, Task 6, Task 7.)
3. **Advise-only lengths.** A correctly reconstructed WOFF2 whose size differs from `totalSfntSize` (and whose `glyf` differs from `origLength`) must be accepted; the equivalent WOFF1 mismatches must be rejected. (Task 6, Task 7.)
4. **Null transform (`transformVersion 3`).** `glyf`/`loca` stored untransformed must pass through verbatim. (Task 7, Task 8.)
5. **`hmtx` shape.** Omitted bearings must reconstruct from each glyph's `xMin` for flags 1/2/3 and every count shape. (Task 9.)

## Test-only helpers

Defined once, in the test source set of the task named; none is production code.

- `WoffTestFonts` (`sfnt` `commonTest`, `container`): `singleTableSfnt()`, `wrapUncompressed(font, flavor = 0x00010000u)`, `wrapDeflated(font)` (okio `Deflater`), `wrapWithDuplicateTag()`, `withWrongTotalSfntSize()`.
- `Woff2TestFonts` (`sfnt` `commonTest`): `singleTableUntransformed()`, `withWrongTotalSfntSize()`, `withNonZeroReserved()`, `withCollectionFlavor()`, `withUnknownTransform()`, `withBadUIntBase128()`, `withWrongDirectoryLength()`.
- `Woff2GlyfVectors` / `Woff2HmtxVectors` (`sfnt` `commonTest`): base64 data holders generated once with fontTools + brotli.
- `BrotliVectors` (`sfnt` `commonTest`): base64 constants `EMPTY`, `TEXT`+`TEXT_EXPECTED`, `DICTIONARY_USER`+`DICTIONARY_EXPECTED`, `MULTI_BLOCK`, plus malformed streams.
- `WoffPaths` (`e2e` `sharedTest`): `WOFF`, `WOFF2` resource-path constants.
- `outlineCommandsOf(bytes, codePoint)`, `advanceOf(bytes, codePoint)`, `glyphIdOf(bytes, codePoint)` (`e2e` `sharedTest`): resolve through the public facade.
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
- Produces: `internal class BrotliBits(input: ByteArray)` with `fun readBits(count: Int): Int`, `fun readBit(): Int`, `fun alignToByte()`, `fun hasMore(): Boolean`, `var overran: Boolean`; `internal class BrotliHuffman` with `fun readCode(bits: BrotliBits): Int` and `internal companion object { fun fromCodeLengths(codeLengths: IntArray, maxBits: Int): BrotliHuffman; fun fromSingleSymbol(symbol: Int): BrotliHuffman }`; `internal object BrotliHuffmanReader { fun read(bits: BrotliBits, alphabetSize: Int): BrotliHuffman }`.

- [ ] **Step 1: Write the failing bit-reader test**

```kotlin
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliBitsTest {
    @Test
    fun readsLeastSignificantBitFirst() {
        val bits = BrotliBits(byteArrayOf(0b10110001.toByte()))
        assertEquals(0b0001, bits.readBits(4))
        assertEquals(0b1011, bits.readBits(4))
    }

    @Test
    fun readsAcrossByteBoundaries() {
        assertEquals(0x0201, BrotliBits(byteArrayOf(0x01, 0x02)).readBits(16))
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
                result = result or (((input[byteIndex].toInt() ushr (bitPosition and 7)) and 1) shl shift)
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
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliHuffmanTest {
    @Test
    fun aSingleSymbolCodeConsumesNoBits() {
        assertEquals(2, BrotliHuffman.fromSingleSymbol(2).readCode(BrotliBits(byteArrayOf())))
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

`fromSingleSymbol` returns a code that yields its symbol with no bits. `fromCodeLengths` builds the canonical tree (§3.2), where a single non-zero length decodes without bits. `BrotliHuffmanReader.read` parses: 2 bits (`1` = simple), 2 bits `NSYM-1`, `NSYM` symbols of `ALPHABET_BITS` (smallest width covering the alphabet), the `tree-select` bit for `NSYM == 4` giving lengths `2,2,2,2` (bit 0) or `1,2,3,3` (bit 1), rejecting a symbol ≥ alphabet size or a repeat; otherwise the §3.5 complex description. The complex description is a **18-symbol** code-length alphabet read in this exact order: `1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15`, whose lengths are themselves encoded with the fixed 6-symbol variable-length code (`0=00`, `1=0111`, `2=011`, `3=10`, `4=01`, `5=1111`), honouring `HSKIP`, the trailing-zero omission, the repeat rules for symbols 16 and 17 (including their chain modification), and the closing `(32768 >> len)` sum check.

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
- Produces: `internal object BrotliMetaBlock { fun readWbits(bits: BrotliBits): Int? }` (`null` = the reserved pattern) plus the §9.2 header parse; `internal object BrotliContext`; `internal object BrotliAlphabet`.

- [ ] **Step 1: Write the failing WBITS test**

```kotlin
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrotliMetaBlockTest {
    @Test
    fun aLeadingZeroBitMeansWbits16() {
        assertEquals(16, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00000010))))
    }

    @Test
    fun oneThenThreeZeroBitsMeansWbits17() {
        assertEquals(17, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00000001))))
    }

    @Test
    fun oneThenWbits24() {
        assertEquals(24, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00001111))))
    }

    @Test
    fun theReservedPatternIsRejected() {
        // "0010001" (bit0=1, then 000, then 100) names no window and must not decode.
        assertEquals(null, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00010001))))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliMetaBlockTest*'`
Expected: FAIL — `BrotliMetaBlock` unresolved.

- [ ] **Step 3: Implement the header, contexts, and alphabet tables**

`readWbits` (§9.1): bit 0 ⇒ 16; else 3 bits `v`; `v != 0` ⇒ `17 + v`; else 3 bits `w`; `w == 0` ⇒ 17, `w == 1` ⇒ `null` (the reserved `9` pattern), else `8 + w`. The §9.2 header reader is exact: read `ISLAST`; when `ISLAST == 1` read `ISLASTEMPTY` and end the stream only when both are set; otherwise **always** read `MNIBBLES` (independently of `ISLAST`), where `MNIBBLES == 0` selects a metadata meta-block (there is no `ISMETADATA` bit) and otherwise `MLEN` follows from `MNIBBLES` nibbles; then read `ISUNCOMPRESSED`, which is only possible for a non-last, non-metadata block. `BrotliContext` computes the literal context ID from the **previous two decoded bytes** and the context mode, the distance context from the copy length, and decodes the context-map RLE and inverse MTF. `BrotliAlphabet` carries the §5 insert/copy length offset tables, the §7 distance tables, and the §6 block-count/block-type tables.

- [ ] **Step 4: Run the meta-block test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliMetaBlockTest*'`
Expected: PASS (4 tests).

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

`scripts/brotli/fetch_dictionary.py` downloads the RFC 7932 static dictionary from the pinned Google Brotli source at a tagged commit, verifies its SHA-256, and emits `BrotliDictionary.kt` as several chunk-sized `ByteArray` literals plus `sizeBytes`, the `NWORDS`/`NWORDS_BITS` tables and the upstream MIT notice. `scripts/brotli/README.md` records the pinned commit, digest, licence and regeneration command.

- [ ] **Step 2: Generate and write the failing test**

```bash
python3 scripts/brotli/fetch_dictionary.py
```

```kotlin
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliDictionaryTest {
    @Test
    fun hasTheRfcSizeAndFirstWord() {
        assertEquals(122_784, BrotliDictionary.sizeBytes)
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

`word(offset, length)`: a length group's byte offset is `Σ(len × wordCount[len])` over shorter lengths; within a group the word is at `wordIndex × length`, decoded through `NWORDS`/`NWORDS_BITS` (RFC 7932 §8). `BrotliDictionaryTransforms.apply` implements all 121 §8 transforms; the output length may be shorter than the base word, including empty.

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

### Task 4: `BrotliDecoder` with independent output and working bounds

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDecoder.kt`
- Create: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliVectors.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDecoderTest.kt`

**Interfaces:**
- Produces: `internal object BrotliDecoder { fun decode(input: ByteArray, maxOutputBytes: Long, maxWorkingBytes: Long): FontOperationResult<ByteArray> }`. The output length is whatever the stream decodes to, capped by `maxOutputBytes`; the caller compares it to the expected length.

- [ ] **Step 1: Write the failing API test**

```kotlin
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

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
                BrotliDecoder.decode(BrotliVectors.EMPTY, maxOutputBytes = 16, maxWorkingBytes = 16),
            ).value,
        )
    }

    @Test
    fun decodesTextSuccessfullyAndByteForByte() {
        assertContentEquals(
            BrotliVectors.TEXT_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.TEXT, maxOutputBytes = 1_024, maxWorkingBytes = 1_024),
            ).value,
        )
    }

    @Test
    fun decodesAStreamThatUsesTheStaticDictionary() {
        assertContentEquals(
            BrotliVectors.DICTIONARY_EXPECTED,
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.DICTIONARY_USER, maxOutputBytes = 4_096, maxWorkingBytes = 4_096),
            ).value,
        )
    }

    @Test
    fun overExpansionPastTheIndependentOutputLimitFails() {
        val failure = assertIs<FontOperationResult.Failure>(
            BrotliDecoder.decode(BrotliVectors.MULTI_BLOCK, maxOutputBytes = 4, maxWorkingBytes = 1_024),
        )
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
    }

    @Test
    fun refusesATruncatedStream() {
        val truncated = BrotliVectors.TEXT.copyOf(BrotliVectors.TEXT.size / 2)
        assertIs<FontOperationResult.Failure>(
            BrotliDecoder.decode(truncated, maxOutputBytes = 1_024, maxWorkingBytes = 1_024),
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliDecoderTest*'`
Expected: FAIL — `BrotliDecoder` unresolved.

- [ ] **Step 3: Implement `BrotliDecoder`**

Wire the meta-block loop, three block categories with block switching, literal/command/distance code readers, context modelling, insert-and-copy, the four-entry distance ring buffer (initialised `16, 15, 11, 4`, never reset at meta-block boundaries, not advanced for symbol 0 or dictionary references), overlapping copies, static-dictionary references (base word length 4..24, transformed output of any length), metadata and uncompressed meta-blocks. Bound the output at `maxOutputBytes` and the maximum back-reference distance (window reach) at `maxWorkingBytes`, both `FontError.ResourceLimitExceeded` on breach; the Huffman and context tables are RFC-format-bounded and need no byte charge. Fail `FontDataFailure("font.woff2.brotli-failed", …)` on `overran`, reserved bits, invalid codes, trailing bytes after the final block, or a stream that ends early.

- [ ] **Step 4: Add the vectors**

`BrotliVectors.kt` holds base64 constants and the expected plaintext for the success cases: an empty stream, a literal-only text, a dictionary-using text, a multi-block stream, plus a truncated and a bad-block-type stream. Include at least one stream ending in a **final non-empty compressed** block and one ending in an empty final block (`ISLASTEMPTY`), so the §9.2 `ISLAST`/`ISLASTEMPTY`/`MNIBBLES` branch is exercised. Record the exact producing Brotli CLI version and command in a comment.

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
- Produces: `@KalligraphieInternalApi public class WoffDecodeLimits(val maxDecodedFontBytes: Long, val maxWorkingBytes: Long)` with `companion { val EMBEDDED: WoffDecodeLimits; fun forCapture(maxSourceBytes: Int): WoffDecodeLimits }` (`EMBEDDED` = 64 MiB each; `forCapture(n)` = `n` each); `internal class SfntTable(val tag: String, val data: ByteArray, val originalChecksum: UInt? = null)`; `internal object SfntReassembler { fun assemble(flavor: UInt, tables: List<SfntTable>, maxAssembledBytes: Long): FontOperationResult<ByteArray>; fun tableChecksum(bytes: ByteArray): UInt; fun wholeFontChecksum(font: ByteArray): UInt }`.

- [ ] **Step 1: Write the failing reassembler test**

```kotlin
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32

class SfntReassemblerTest {
    @Test
    fun writesASortedPaddedDirectory() {
        val sfnt = assertIs<FontOperationResult.Success<ByteArray>>(
            SfntReassembler.assemble(0x00010000u, listOf(SfntTable("head", ByteArray(54)), SfntTable("cmap", ByteArray(5))), 4_096),
        ).value
        assertEquals(0x00010000u, readUInt32(sfnt, 0))
        assertEquals(2, readUInt16(sfnt, 4)!!.toInt())
        assertEquals("cmap", sfnt.decodeAsciiTag(12))
        assertEquals("head", sfnt.decodeAsciiTag(28))
        assertEquals(0, sfnt.size % 4)
    }

    @Test
    fun theHeadDirectoryChecksumIsComputedWithTheAdjustmentZeroed() {
        val head = ByteArray(54).also { it[8] = 0x12; it[9] = 0x34; it[10] = 0x56; it[11] = 0x78 }
        val sfnt = assertIs<FontOperationResult.Success<ByteArray>>(
            SfntReassembler.assemble(0x00010000u, listOf(SfntTable("head", head)), 4_096),
        ).value
        assertEquals(SfntReassembler.tableChecksum(ByteArray(54)), readUInt32(sfnt, 16))
        assertEquals(0xB1B0AFBAu, SfntReassembler.wholeFontChecksum(sfnt))
    }

    @Test
    fun refusesACombinedSizeBeyondTheLimitEvenWhenEachTableFits() {
        // Each table is below 100, but the assembled font (12 + 32 + 56 + 8 = 108) is not.
        val failure = assertIs<FontOperationResult.Failure>(
            SfntReassembler.assemble(
                0x00010000u,
                listOf(SfntTable("head", ByteArray(54)), SfntTable("cmap", ByteArray(5))),
                maxAssembledBytes = 100,
            ),
        )
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*SfntReassemblerTest*'`
Expected: FAIL — `SfntReassembler` unresolved.

- [ ] **Step 3: Implement `WoffDecodeLimits` and `SfntReassembler`**

`tableChecksum` sums big-endian 32-bit words (zero-padded). `assemble` computes `searchRange`/`entrySelector`/`rangeShift`, checks the combined size `12 + 16*numTables + Σ align4(size)` against `maxAssembledBytes` **before allocating**, sorts by tag, writes `head` with `bytes[8..11]` zeroed, records each checksum (zeroed `head`, or `originalChecksum` when supplied), pads, writes the directory, then patches `head.checkSumAdjustment = 0xB1B0AFBA - wholeFontChecksum(out)`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*SfntReassemblerTest*'`
Expected: PASS (3 tests).

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
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag

class WoffReaderTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun roundTripsAnUncompressedDirectory() {
        val decoded = assertIs<FontOperationResult.Success<ByteArray>>(
            WoffReader.decode(WoffTestFonts.wrapUncompressed(WoffTestFonts.singleTableSfnt()), limits),
        ).value
        assertEquals("cmap", decoded.decodeAsciiTag(12))
        assertEquals(0xB1B0AFBAu, SfntReassembler.wholeFontChecksum(decoded))
    }

    @Test
    fun roundTripsADeflatedTable() {
        val decoded = assertIs<FontOperationResult.Success<ByteArray>>(
            WoffReader.decode(WoffTestFonts.wrapDeflated(WoffTestFonts.singleTableSfnt()), limits),
        ).value
        assertEquals("cmap", decoded.decodeAsciiTag(12))
    }

    @Test
    fun byteDistinctContainersDecodeToIdenticalBytes() {
        val font = WoffTestFonts.singleTableSfnt()
        val fromRaw = assertIs<FontOperationResult.Success<ByteArray>>(
            WoffReader.decode(WoffTestFonts.wrapUncompressed(font), limits),
        ).value
        val fromDeflated = assertIs<FontOperationResult.Success<ByteArray>>(
            WoffReader.decode(WoffTestFonts.wrapDeflated(font), limits),
        ).value
        assertContentEquals(fromRaw, fromDeflated)
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
    fun refusesAHeaderTotalSfntSizeMismatch() {
        val woff = WoffTestFonts.withWrongTotalSfntSize()
        assertEquals(
            "font.woff.invalid-header",
            assertIs<FontOperationResult.Failure>(WoffReader.decode(woff, limits)).error.code,
        )
    }

    @Test
    fun refusesADuplicateTag() {
        assertEquals(
            "font.woff.invalid-table-directory",
            assertIs<FontOperationResult.Failure>(WoffReader.decode(WoffTestFonts.wrapWithDuplicateTag(), limits)).error.code,
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*WoffReaderTest*'`
Expected: FAIL — `WoffReader` unresolved.

- [ ] **Step 3: Implement `WoffReader` and the dispatcher**

`WoffReader.decode` implements spec §6.2: the field map, complete declared extent, aligned non-overlapping table/meta/private extents (absent blocks zero), `reserved == 0`, header `totalSfntSize == 12 + 16*numTables + Σ align4(origLength)`, `compLength > origLength` reject, raw copy when equal, zlib inflate to exactly `origLength` otherwise (bounded), `ttcf` refusal, then `SfntReassembler` with `limits.maxDecodedFontBytes`. `FontContainerDecoder.decode` copies `source.copyBytes()`, returns `null` unless the first four bytes are `wOFF`/`wOF2`, and routes `wOFF` to `WoffReader` (WOFF2 → Task 7).

- [ ] **Step 4: Run the WOFF reader test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*WoffReaderTest*'`
Expected: PASS (7 tests).

- [ ] **Step 5: Write and run the dispatcher test**

```kotlin
class FontContainerDecoderTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun passesThroughANonContainerSource() {
        val sfnt = WoffTestFonts.singleTableSfnt()
        val result = assertIs<FontOperationResult.Success<DecodedFont?>>(
            FontContainerDecoder.decode(
                org.graphiks.kalligraphie.api.FontSource(sfnt, org.graphiks.kalligraphie.api.FontSourceProvenance("plain")),
                limits,
            ),
        )
        assertEquals(null, result.value)
    }

    @Test
    fun decodesAWoffSourceToItsKind() {
        val woff = WoffTestFonts.wrapUncompressed(WoffTestFonts.singleTableSfnt())
        val result = assertIs<FontOperationResult.Success<DecodedFont?>>(
            FontContainerDecoder.decode(
                org.graphiks.kalligraphie.api.FontSource(woff, org.graphiks.kalligraphie.api.FontSourceProvenance("wrapped")),
                limits,
            ),
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
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

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
        assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2Reader.decode(Woff2TestFonts.withWrongTotalSfntSize(), limits),
        )
    }

    @Test
    fun acceptsANonZeroReservedField() {
        assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2Reader.decode(Woff2TestFonts.withNonZeroReserved(), limits),
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

    @Test
    fun aValidStreamWithTheWrongDirectoryLengthIsNotABrotliFailure() {
        assertEquals(
            "font.woff2.invalid-font-data-size",
            assertIs<FontOperationResult.Failure>(Woff2Reader.decode(Woff2TestFonts.withWrongDirectoryLength(), limits)).error.code,
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2ReaderTest*'`
Expected: FAIL — `Woff2Reader` unresolved.

- [ ] **Step 3: Implement header, directory, single-stream split, reassembly**

Implement spec §6.3: header validation with `ttcf` refusal, non-rejection of a non-zero `reserved`, `flags` tag index/transform version, the §4.1 known-tag table, `UIntBase128` bounds, the transform matrix, `transformLength` only for non-null transforms, unknown-transform rejection, transformed-`loca` consistency. Compute `directorySum`; call `BrotliDecoder.decode(block, limits.maxDecodedFontBytes, limits.maxWorkingBytes)` on the **single** `totalCompressedSize` slice; if the decoded length differs from `directorySum`, fail `font.woff2.invalid-font-data-size` (a malformed stream already failed as `brotli-failed`); otherwise split in directory order, pass untransformed tables through, and reassemble with `limits.maxDecodedFontBytes`. Extend `FontContainerDecoder` to route `wOF2`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2ReaderTest*'`
Expected: PASS (7 tests).

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
- Consumes: the split transformed `glyf` block.
- Produces: `internal class GlyfReconstruction(val glyf: ByteArray, val loca: ByteArray, val indexFormat: Int)`; `internal object Woff2GlyfTransform { fun reconstruct(transformed: ByteArray, limits: WoffDecodeLimits): FontOperationResult<GlyfReconstruction> }`.

- [ ] **Step 1: Write the failing test covering both index formats and boundaries**

```kotlin
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

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
    fun reconstructsACompositeWithLongLocaAndAnEmptyGlyph() {
        val v = Woff2GlyfVectors.COMPOSITE_LONG_LOCA
        assertContentEquals(
            v.expectedLoca,
            assertIs<FontOperationResult.Success<GlyfReconstruction>>(
                Woff2GlyfTransform.reconstruct(v.transformedGlyf, limits),
            ).value.loca,
        )
    }

    @Test
    fun reconstructsAMixedOverlapBitmap() {
        val v = Woff2GlyfVectors.MIXED_OVERLAP
        assertContentEquals(
            v.expectedGlyf,
            assertIs<FontOperationResult.Success<GlyfReconstruction>>(
                Woff2GlyfTransform.reconstruct(v.transformedGlyf, limits),
            ).value.glyf,
        )
    }

    @Test
    fun reconstructsAnOddLengthGlyphFollowedByAnother() {
        val v = Woff2GlyfVectors.ODD_LENGTH_THEN_SECOND
        val r = assertIs<FontOperationResult.Success<GlyfReconstruction>>(
            Woff2GlyfTransform.reconstruct(v.transformedGlyf, limits),
        ).value
        assertContentEquals(v.expectedLoca, r.loca)
        assertEquals(0, r.loca[2].toInt())  // second glyph starts on an even offset
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2GlyfTransformTest*'`
Expected: FAIL — `Woff2GlyfTransform` unresolved.

- [ ] **Step 3: Implement the transform**

Parse the §5.1 header (`reserved == 0`, `optionFlags` bit 0 `overlapSimple`, `numGlyphs`, `indexFormat ∈ {0,1}`, seven stream sizes each charged against `limits.maxWorkingBytes` and their running total too) and the seven streams. Per glyph: read `nContour` (Int16); `0` empty (repeat the previous `loca` offset; an explicit bbox is invalid); `> 0` simple; `-1` composite. Simple: read `nContour` `255UInt16` point counts, build `endPtsOfContours` as the **cumulative sum minus one**, `nPoints` flag bytes, and for each point `tripletCode = flag & 0x7f` (high bit 7 is the **inverted** on-curve indicator) yielding 1/2/3/4 coordinate bytes for ranges 0..83 / 84..119 / 120..123 / 124..127, decoding all 128 triplet codes into delta-x/delta-y; then one `255UInt16` instruction length and its bytes. Composite: decode the composite stream (flags, args, transforms, instructions) and always carry an explicit bbox. `bboxBitmap` is `4 * floor((numGlyphs + 31) / 32)` bytes, glyph `g` at bit `g` MSB-first; explicit bboxes from `bboxStream`, simple glyphs without the bit infer bounds from all points, composites always explicit. If `optionFlags` bit 0 is set, the `overlapSimpleBitmap` is `ceil(numGlyphs/8)` bytes and, for **each** simple glyph, its own bit sets flag bit 6 of that glyph's first flag byte; otherwise clear bit 6. Emit `glyf` and `loca`, padding each glyph record to an even length so `loca[g+1]` is even for `indexFormat == 0`, and reject a short offset that is not representable. Wire into `Woff2Reader`: `glyf` version 0 is replaced by the reconstruction, its paired `loca` by the produced table, and the produced glyph `xMin` values are retained for Task 9.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2GlyfTransformTest*'`
Expected: PASS (4 tests).

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
- Consumes: the split transformed `hmtx`, `numberOfHMetrics` (`hhea`), `numGlyphs` (`maxp`) and `xMinByGlyph: IntArray` (from Task 8 or the passthrough `glyf`).
- Produces: `internal object Woff2HmtxTransform { fun reconstruct(transformed: ByteArray, numberOfHMetrics: Int, numGlyphs: Int, xMinByGlyph: IntArray, limits: WoffDecodeLimits): FontOperationResult<ByteArray> }`.

- [ ] **Step 1: Write the failing test**

```kotlin
@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult

class Woff2HmtxTransformTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun flagsOneReconstructsProportionalBearingsFromXMin() {
        val v = Woff2HmtxVectors.FLAGS_ONE
        assertContentEquals(
            v.expected,
            assertIs<FontOperationResult.Success<ByteArray>>(
                Woff2HmtxTransform.reconstruct(v.transformed, numberOfHMetrics = 2, numGlyphs = 2, xMinByGlyph = intArrayOf(3, -4), limits),
            ).value,
        )
    }

    @Test
    fun flagsTwoReconstructsTheTrailingBearingsFromXMin() {
        val v = Woff2HmtxVectors.FLAGS_TWO
        assertContentEquals(
            v.expected,
            assertIs<FontOperationResult.Success<ByteArray>>(
                Woff2HmtxTransform.reconstruct(v.transformed, numberOfHMetrics = 1, numGlyphs = 3, xMinByGlyph = intArrayOf(0, 5, 0), limits),
            ).value,
        )
    }

    @Test
    fun flagsThreeOmitsBothArraysAndTheTrailingArrayIsEmptyWhenCountsMatch() {
        val v = Woff2HmtxVectors.FLAGS_THREE_FLAT
        val hmtx = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2HmtxTransform.reconstruct(v.transformed, numberOfHMetrics = 2, numGlyphs = 2, xMinByGlyph = intArrayOf(3, -4), limits),
        ).value
        assertEquals(2 * 4, hmtx.size)
        assertContentEquals(v.expected, hmtx)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2HmtxTransformTest*'`
Expected: FAIL — `Woff2HmtxTransform` unresolved.

- [ ] **Step 3: Implement the transform**

Per spec §5.4: read `flags` and reject anything outside the three valid values (bit 0 = `lsb[]` absent, bit 1 = `leftSideBearing[]` absent; bits 2-7 zero). Read all `numberOfHMetrics` advance widths; if bit 0 is clear read the proportional `lsb[]`, else derive each from `xMinByGlyph` (zero for an empty glyph); if bit 1 is clear read the trailing `leftSideBearing[]` (`numGlyphs - numberOfHMetrics` entries; **empty** when the counts are equal), else derive; emit the interleaved `hmtx`. Charge the output against `limits.maxDecodedFontBytes`. Wire into `Woff2Reader` after all tables are decoded (so `hhea`/`maxp`/`glyf` are available), replacing an `hmtx` whose version is 1; the `xMin` source also covers the `glyf` null-transform case.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2HmtxTransformTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2HmtxTransform.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2Reader.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2HmtxTransformTest.kt
git commit -m "feat(sfnt): reconstruct the WOFF2 hmtx transform"
```

---

## Phase 4 — Corpus acquisition and tooling

### Task 10: Acquire the real fixtures and extend `scripts/fonts`

**Files:**
- Create: `test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff`
- Create: `test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff2`
- Create: `test-fixtures/fonts/woff-ibm-plex/PROVENANCE.md`
- Create: `test-fixtures/fonts/woff-ibm-plex/OFL.txt`
- Modify: `.gitattributes`
- Modify: `scripts/fonts/corpus.json`, `scripts/fonts/fetch_fonts.py`, `scripts/fonts/README.md`, `scripts/fonts/tests/test_fetch_fonts.py`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogClaims.kt` (temporary unreferenced-family excuse)
- Modify (generated): `kalligraphie/e2e/src/harnessResources/catalog/claimed-tables.json`

**Interfaces:**
- Produces: the `woff-ibm-plex` family, plus a temporary claims excuse so the exhaustiveness lint stays green until Task 13 makes real claims. This commit contains no container scene yet, so `updateE2eGolden` still succeeds.

- [ ] **Step 1: Download the pinned files and measure them**

```bash
mkdir -p test-fixtures/fonts/woff-ibm-plex
BASE=https://raw.githubusercontent.com/IBM/plex/763c36ef9117782905ae010056dfbe8fd2653a25/packages/plex-sans/fonts/complete
curl -fL "$BASE/woff/IBMPlexSans-Regular.woff"   -o test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff
curl -fL "$BASE/woff2/IBMPlexSans-Regular.woff2" -o test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff2
shasum -a 256 test-fixtures/fonts/woff-ibm-plex/*.woff*
```

- [ ] **Step 2: Provenance, licence, tooling**

Write `PROVENANCE.md` (repository, pinned commit, both raw URLs, measured digests/sizes, OFL-1.1, and the note that the two decode to different bytes) and copy the OFL text. Add `*.woff binary` to `.gitattributes`. Add `.woff`/`.woff2` to `FONT_SUFFIXES` in `scripts/fonts/fetch_fonts.py` and update its coverage tests. Update `scripts/fonts/README.md` (artifact definition; the table reader is `check_exhaustiveness.py`, needing fontTools + `brotli` for WOFF2). Populate `corpus.json` with the measured `sha256`/`sizeBytes`, real `tables` (fontTools + brotli, `GlyphOrder` excluded), `url`, `rawUrl`, `revision`, `license`, `licenseFile`.

- [ ] **Step 3: Add the temporary unreferenced-family excuse**

Add `"woff-ibm-plex"` to `CatalogClaims.UNREFERENCED_FAMILIES` with the reason `"corpus acquired ahead of the container scenes; this excuse is removed when the scenes land"`, so the lint accepts the not-yet-referenced family.

- [ ] **Step 4: Regenerate, lint, and commit the corpus**

```bash
./gradlew :kalligraphie:e2e:updateE2eGolden
uv run --with fonttools==4.65.0 --with brotli python scripts/fonts/check_exhaustiveness.py
python3 scripts/fonts/fetch_fonts.py --check --provenance
python3 -m unittest discover -s scripts/fonts/tests -v
git add test-fixtures/fonts/woff-ibm-plex .gitattributes scripts/fonts kalligraphie/e2e/src docs/docs/generated kalligraphie/e2e/src/harnessResources
git commit -m "chore(sfnt): add the WOFF/WOFF2 corpus family and tooling"
```

Expected: regeneration succeeds (no container scene yet), the lint passes with the temporary excuse, all checks pass.

---

## Phase 5 — Integration (corpus-backed)

### Task 11: `Kalligraphie.embedded` accepts WOFF/WOFF2

**Files:**
- Modify: `kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/EmbeddedFontCatalog.kt` (`EmbeddedFontCatalogFactory.create`)
- Test: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/EmbeddedWoffCatalogTest.kt`

**Interfaces:**
- Consumes: `FontContainerDecoder.decode`, `WoffDecodeLimits` (Tasks 5–7).

- [ ] **Step 1: Write the failing test**

```kotlin
package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
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
    fun identicalContainersAreRejectedAsDuplicates() {
        val font = fixture("IBMPlexSans-Regular.woff")
        val result = Kalligraphie.embedded(
            listOf(
                FontSource(font, FontSourceProvenance("a")),
                FontSource(font, FontSourceProvenance("b")),
            ),
        )
        assertIs<FontOperationResult.Failure>(result)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:jvmTest --tests '*EmbeddedWoffCatalogTest*'`
Expected: FAIL — the catalog rejects the WOFF/WOFF2 bytes.

- [ ] **Step 3: Normalise the source list before identity work**

In `create`, first map every source through `FontContainerDecoder.decode(source, WoffDecodeLimits.EMBEDDED)` into a new `List<FontSource>` (a decode failure returns the failure with accumulated diagnostics), then run the existing duplicate check, generation computation and `SfntReader.readMetadata` over that list — not the original `capturedSources`.

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

## Phase 6 — Catalog, claims, and goldens

### Task 13: Certify the container scenes and commit the goldens

**Files:**
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/{ContainerCatalog.kt,CorpusKeys.kt,CatalogClaims.kt,RobustnessCatalog.kt}`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/{SceneFontPaths.kt,PortableSceneRenderers.kt,CatalogProbes.kt}`
- Modify: `kalligraphie/e2e/build.gradle.kts` (`iosFixtureCorpus`)
- Modify (generated): `kalligraphie/e2e/src/harnessResources/golden/manifest.tsv`, `docs/docs/generated/e2e-catalog-matrix.md`/`.fr.md`, `kalligraphie/e2e/src/harnessResources/catalog/claimed-tables.json`
- Test: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/ContainerEquivalenceTest.kt`

**Interfaces:**
- Consumes: the decoder (Tasks 1–9), the facade integration (Task 11), the committed corpus (Task 10), `outlineCapitalA(corpus, path, what)`.

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
        for (codePoint in listOf(0x41, 0x00C9, 0x20)) {
            assertEquals(glyphIdOf(corpus.bytes(WoffPaths.WOFF2), codePoint), glyphIdOf(corpus.bytes(WoffPaths.WOFF), codePoint))
            assertEquals(advanceOf(corpus.bytes(WoffPaths.WOFF2), codePoint), advanceOf(corpus.bytes(WoffPaths.WOFF), codePoint))
            assertEquals(outlineCommandsOf(corpus.bytes(WoffPaths.WOFF2), codePoint), outlineCommandsOf(corpus.bytes(WoffPaths.WOFF), codePoint))
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*ContainerEquivalenceTest*'`
Expected: FAIL — `WoffPaths` unresolved.

- [ ] **Step 3: Add keys, paths, renderers, entries, claims, robustness, iOS corpus**

Add the shared `woff-ibm-plex` `CorpusKey`, the two `SceneFontPaths`, the two `PortableSceneRenderers` (`glyph.outline.woff-ibm-plex.A.64`, `glyph.outline.woff2-ibm-plex.A.64`), and promote both `ContainerCatalog` entries to `Supported` with a real `sinceCommit`, the outline-table claim set (mirror `outline.glyf-simple-composite`), motivated `UNREAD_TABLES` entries for every table the lint names, `family = GLYPH_OUTLINE`, `route = PORTABLE_GLYPH`, `frame = AutoSized(padding = 1)`. Remove the temporary `UNREFERENCED_FAMILIES` excuse added in Task 10. Add robustness entries (`robustness.woff-truncated`, `robustness.woff2-brotli-corrupted` via a deterministically malformed stream) with `CatalogProbes` under `woff-ibm-plex`. Add both fixtures to the `iosFixtureCorpus` task list.

- [ ] **Step 4: Regenerate, then lint, then commit**

```bash
./gradlew :kalligraphie:e2e:updateE2eGolden
uv run --with fonttools==4.65.0 --with brotli python scripts/fonts/check_exhaustiveness.py
python3 -m unittest discover -s scripts/fonts/tests -v
./gradlew :kalligraphie:e2e:jvmTest --tests '*ContainerEquivalenceTest*' --tests '*ExpectationCatalogRatchetTest*'
```

Expected: regeneration writes the manifest/matrices/claims; the lint and all tests pass (the family is now claimed and the excuse is gone). Commit:

```bash
git add kalligraphie/e2e docs/docs/generated
git commit -m "feat(e2e): certify the WOFF and WOFF2 container scenes"
```

## Phase 7 — Documentation

### Task 14: User docs, changelog, and scope sync

**Files:**
- Modify: `docs/docs/font-management.md` / `.fr.md`
- Modify: `CHANGELOG.md`
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Document the feature**

`font-management.md`/`.fr.md`: add WOFF/WOFF2 to the supported-scope list and `woff`/`woff2` to the discovery extensions; add the decoded-byte identity paragraph (spec §4.3), including that different containers of one font generally do **not** share identity, and that a WOFF2 reconstruction is not byte-identical to the original.

- [ ] **Step 2: Changelog and scope sync**

Add the `feat(sfnt)` `CHANGELOG.md` entry. Add the `bench` row to the `CONTRIBUTING.md` scope table so it matches `allowed_scopes` in `.github/contributing-policy.toml`.

- [ ] **Step 3: Run the docs-relevant tests**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogMatrixRunnerTest*'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add docs CHANGELOG.md CONTRIBUTING.md
git commit -m "docs(sfnt): document WOFF and WOFF2 containers"
```

---

## Phase 8 — Benchmarks

### Task 15: Container bench scenarios and per-platform wiring

**Files:**
- Create: `kalligraphie/bench/src/commonMain/kotlin/org/graphiks/kalligraphie/bench/scenarios/ContainerScenarios.kt`
- Modify: `kalligraphie/bench/src/commonMain/kotlin/org/graphiks/kalligraphie/bench/ScenarioRegistry.kt`
- Modify: `kalligraphie/bench/build.gradle.kts` (add `commonTest.dependencies { implementation(kotlin("test")) }`; iOS bench fixture corpus)
- Modify: `kalligraphie/bench/src/jvmBenchmark/.../PortableGlyphMaterializationBenchmark.kt` (`@Param` list **and** its lookup)
- Modify: `kalligraphie/bench/src/androidDeviceTest/.../AndroidGlyphMaterializationBenchmark.kt` (three methods)
- Modify: the JVM/Android bench fixture corpora
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
Expected: FAIL — `containerScenarios` unresolved (and, first, the missing `kotlin("test")` dependency).

- [ ] **Step 3: Implement the scenarios and the wiring**

Each scenario follows the `TrueTypeColdPreparation` shape (cold per sample, `PORTABLE_GLYPH`, no required capability): the two captures call `Kalligraphie.embedded(bytes)` and consume the face; `Woff2ColdGlyph` resolves the scene glyph's outline, counting `sourceBytes`. Add `containerScenarios(corpus)` to `ScenarioRegistry.all`. Extend the JVM `@Param` list **and** change its setup to select from `ScenarioRegistry` (not `glyphMaterializationScenarios`). Add three benchmark methods to the Android class. Add both files to the iOS bench fixture corpus and the JVM/Android corpora. Add the `kotlin("test")` dependency to the bench `commonTest`.

- [ ] **Step 4: Run the test and one JVM profile**

```bash
./gradlew :kalligraphie:bench:jvmTest --tests '*ContainerScenariosTest*'
./gradlew :kalligraphie:bench:jvmBenchmarkBenchmark
```

Expected: test PASS; the produced `bench/observations.jsonl` / report contains the three new profiles.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/bench
git commit -m "feat(bench): measure WOFF and WOFF2 container capture on every platform"
```

---

## Phase 9 — Final verification

### Task 16: Full local verification and the PR gate

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

- [ ] **Step 4: Platform benchmark evidence**

Run the JVM, Android and iOS benchmark harnesses and record the three new profiles. (`--tests` does not select `@Param` values; run the benchmark tasks themselves.)

- [ ] **Step 5: PR gate (CONTRIBUTING)**

Confirm: branch from the latest `master` in a fork; PR targets `Graphiks-org/Kalligraphie`; template headings (`Description`, `Type of Change`, `Checklist`, `Screenshots (if applicable)`, `Additional Notes`) with exactly one change type; changelog and documentation decisions recorded; every non-merge commit subject in Conventional Commits with an allowed scope; branch up to date with `master`.

- [ ] **Step 6: Final commit if Step 3 produced changes**

```bash
git add -A
git commit -m "chore(sfnt): finalise WOFF and WOFF2 container support"
```
