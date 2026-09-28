# WOFF 1.0 / WOFF 2.0 Container Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `Kalligraphie.embedded` and directory font capture ingest WOFF 1.0 and WOFF 2.0 containers by reconstructing a standalone SFNT that the existing pipeline consumes unchanged, with golden e2e scenes and benchmarks.

**Architecture:** An internal `FontContainerDecoder` in `:kalligraphie:font:sfnt` sniffs `wOFF`/`wOF2` and delegates to `WoffReader`/`Woff2Reader`, which inflate tables (zlib via `okio.Inflater`; Brotli via a vendored pure-Kotlin decoder) and reassemble an SFNT (`SfntReassembler`). WOFF 2.0 additionally reconstructs the `glyf`/`loca`/`hmtx` transforms. The facade and directory capture decode at the boundary, then build `FontSource(decodedBytes, provenance)` and call `SfntReader` as today (Approach A).

**Tech Stack:** Kotlin Multiplatform (`commonMain` for Android/JVM/iOS), `okio` (existing), `kotlin.test`, `:kalligraphie:e2e` golden harness, `:kalligraphie:bench` kotlinx-benchmark harness, Python `fontTools` corpus tooling.

**Spec:** `docs/superpowers/specs/2026-09-28-woff-woff2-containers-design.md`

## Global Constraints

- All new production code lives in `:kalligraphie:font:sfnt` `commonMain`; no new third-party dependency. `okio` is already an `implementation` dependency there.
- Every new type is internal: annotate with `@org.graphiks.kalligraphie.api.KalligraphieInternalApi` and `@file:OptIn(...)` where needed. No change to the public `:kalligraphie:api` surface.
- Single-face containers only. A `flavor` of `ttcf` (or any unsupported version) returns `FontError.UnsupportedContainer`.
- Every declared size is validated against the available bytes **before** any allocation; the Brotli/zlib output is bounded to the table's declared reconstructed length; the reassembled size is bounded by the declared `totalSfntSize` and by `maxSourceBytes` in directory capture. Breaches return `FontError.ResourceLimitExceeded`.
- Malformed structure returns `FontError.FontDataFailure(code, message, location)` with the exact codes from spec §6.
- Test commands: `./gradlew :kalligraphie:font:sfnt:jvmTest` for the decoder; `./gradlew check` and `./gradlew allTests` for the whole repo.
- Conventional Commits; allowed scopes include `sfnt`, `font-core`, `kalligraphie`, `e2e`, `bench`, `docs`, `build`, `ci`. Branch `feat/sfnt-woff-containers`, PR from the fork to `Graphiks-org/Kalligraphie`.
- Regenerate committed artifacts with `./gradlew :kalligraphie:e2e:updateE2eGolden`; never hand-edit `manifest.tsv`, the catalog matrices or `claimed-tables.json`.
- Corpus commands from `scripts/fonts/README.md` are local obligations and must pass before committing fixture changes.

## Review Focus

The five input classes / failure modes most likely to bite a user, each pinned by a test in the task noted:

1. **Collection flavor.** A WOFF/WOFF2 whose `flavor` is `ttcf` must fail with `font.unsupported-container`, never crash or silently parse one face. (Task 6, Task 7.)
2. **Decompression bomb.** A table whose declared reconstructed length is far larger than the input, or whose Brotli/zlib stream expands past the declared length, must fail with `font.resource-limit-exceeded` before allocation. (Task 4, Task 6, Task 7.)
3. **Null transform (`transformVersion 3`).** `glyf`/`loca` stored untransformed must pass through verbatim, not be run through the transform reconstruction. (Task 8.)
4. **`hmtx` shape.** The transformed `hmtx` must reconstruct correctly for `numberOfHMetrics < numGlyphs` (with lsb arrays) and the flat case, cross-checked against the original metrics. (Task 9.)
5. **Overlapping / duplicate tables.** Directory records that overlap, duplicate a tag, or point outside the file must be refused with a stable typed code, not read out of bounds. (Task 6, Task 7.)

## Test-only helpers

These names are referenced by the tasks below and are defined once, in the test source set of the task named; none is production code.

- `WoffTestFonts` (`sfnt` `commonTest`, `container` package): `singleTableSfnt(): ByteArray` builds a minimal valid one-table SFNT; `wrapUncompressed(font: ByteArray, flavor: UInt = 0x00010000u): ByteArray` builds a WOFF whose single table is stored uncompressed. (Task 6.)
- `Woff2TestFonts` (`sfnt` `commonTest`): `singleTableUntransformed()`, `withWrongTotalSfntSize()`, `withCollectionFlavor()`, each a constructed WOFF2 with `transformVersion 3`. (Task 7.)
- `Woff2GlyfVectors` (`sfnt` `commonTest`): a data holder `(transformedGlyf, origGlyfLength, expectedGlyf, expectedLoca, indexFormat)`, produced once with `fontTools` `woff2` and embedded as base64. (Task 8.)
- `Woff2HmtxVectors` (`sfnt` `commonTest`): data holders `FLAT` and `WITH_LSB` `(transformed, expected)`. (Task 9.)
- `BrotliVectors` (`sfnt` `commonTest`): base64 constants `EMPTY`, `LOREM_IPSUM`, `DICTIONARY_USER`. (Task 4.)
- `EmbeddedWoffTestData` (`font:core` `commonTest`): `wrappedLiberation()`, `decodedLiberation()`, `faceDigest(bytes)`. (Task 10.)
- `WoffCaptureFixtures` (`:kalligraphie` `jvmTest`): `woffPath()`, `woff2Path()` resolve the committed `woff-ibm-plex` corpus resources. (Task 11.)
- `WoffPaths` (`:kalligraphie:e2e` `sharedTest`): `WOFF`, `WOFF2` resource-path constants. (Task 13.)
- `decodeTable(bytes, tag)` (`e2e` `sharedTest`): decodes a source through `Kalligraphie.embedded` and returns the raw SFNT table bytes read through the internal `ParsedTrueTypeFont`. (Task 13.)
- `TestCorpus` (`bench` `commonTest`): an in-memory `FixtureCorpus` for the container-scenario registry test. (Task 15.)

---

## Phase 1 — Vendored Brotli decoder

### Task 1: Brotli bit reader, Huffman decoder, and framing constants

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliBits.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliHuffman.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliBitsTest.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliHuffmanTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal class BrotliBits(input: ByteArray)` with `fun readBits(count: Int): Int`, `fun readBit(): Int`, `fun alignToByte()`, `fun hasMore(): Boolean`; `internal class BrotliHuffman` with `internal fun readCode(bits: BrotliBits): Int` and `internal companion object { fun fromSimple(alphabetSize: Int, symbols: IntArray): BrotliHuffman; fun fromComplex(codeLengths: IntArray, maxBits: Int): BrotliHuffman }`; `BrotliFraming.WBITS_MAX = 24`.

- [ ] **Step 1: Write the failing bit-reader test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliBitsTest {
    @Test
    fun readsLittleEndianBitOrderWithinBytes() {
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

/**
 * LSB-first bit reader over a byte array, the bit order RFC 7932 uses for every field.
 *
 * Reading past the end returns zero bits and sets [overran], so the decoder can finish a code and
 * then fail the whole stream once, instead of throwing from deep inside a tree walk.
 */
@KalligraphieInternalApi
internal class BrotliBits(private val input: ByteArray) {
    private var bitPosition = 0

    /** True once a read reached past the last byte; the caller must fail the stream. */
    var overran: Boolean = false
        private set

    fun readBit(): Int = readBits(1)

    fun readBits(count: Int): Int {
        var result = 0
        var shift = 0
        repeat(count) {
            val byteIndex = bitPosition ushr 3
            if (byteIndex >= input.size) {
                overran = true
                bitPosition += 1
                shift += 1
                return@repeat
            }
            val bit = (input[byteIndex].toInt() ushr (bitPosition and 7)) and 1
            result = result or (bit shl shift)
            bitPosition += 1
            shift += 1
        }
        return result
    }

    fun alignToByte() {
        val remainder = bitPosition and 7
        if (remainder != 0) bitPosition += 8 - remainder
    }

    fun hasMore(): Boolean = (bitPosition ushr 3) < input.size
}

/** Framing constants of RFC 7932 §9.1. */
@KalligraphieInternalApi
internal object BrotliFraming {
    /** Maximum window bits; the window size is `(1 shl wbits) - 16`. */
    const val WBITS_MAX = 24
}
```

- [ ] **Step 4: Run the bit-reader test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliBitsTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Write the failing Huffman test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliHuffmanTest {
    @Test
    fun simpleSingleSymbolDecodesWithoutBits() {
        // RFC 7932 §3.4: a simple code with one symbol read consumes zero bits and always yields it.
        val code = BrotliHuffman.fromSimple(alphabetSize = 4, symbols = intArrayOf(2))
        assertEquals(2, code.readCode(BrotliBits(byteArrayOf())))
    }

    @Test
    fun complexTwoSymbolTreeDecodesBothSymbols() {
        // Two symbols of length 1: symbol 0 on bit 0, symbol 1 on bit 1.
        val code = BrotliHuffman.fromComplex(codeLengths = intArrayOf(1, 1), maxBits = 1)
        assertEquals(0, code.readCode(BrotliBits(byteArrayOf(0b00000000))))
        assertEquals(1, code.readCode(BrotliBits(byteArrayOf(0b00000001))))
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliHuffmanTest*'`
Expected: FAIL — `BrotliHuffman` unresolved.

- [ ] **Step 7: Implement `BrotliHuffman`**

Build a canonical prefix tree from the code lengths (RFC 7932 §3.2), then a direct-lookup fast table plus a canonical decoding walk. `fromSimple` implements §3.4 including the two "single symbol" and "tree-select" bit forms; `fromComplex` runs the code-length code from §3.5 to materialise lengths, then builds the same tree. Return the symbol as `Int`. The tree walk must consume exactly the bits of the matched code and must tolerate `BrotliBits.overran` by returning the accumulator; the caller fails the stream.

- [ ] **Step 8: Run the Huffman test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliHuffmanTest*'`
Expected: PASS (2 tests).

- [ ] **Step 9: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliBits.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliHuffman.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliBitsTest.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliHuffmanTest.kt
git commit -m "feat(sfnt): add Brotli bit reader and Huffman decoder"
```

### Task 2: Brotli meta-block header, block switching, and literal/command/distance alphabets

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliMetaBlock.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliContext.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliAlphabet.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliMetaBlockTest.kt`

**Interfaces:**
- Consumes: `BrotliBits`, `BrotliHuffman` (Task 1).
- Produces: `internal object BrotliMetaBlock` implementing the §9.2 header (`wbits`, `ISLAST`, `MNIBBLES`, `MLEN`, `ISUNCOMPRESSED`, `ISMETADATA`) and dispatching an uncompressed meta-block flush; `internal object BrotliAlphabet` with the RFC 7932 §5 insert-and-copy length code tables, the §7 distance code tables, and `internal object BrotliContext` with the §7.1 context lookup modes (`LSB6`, `MSB6`, `UTF8`, `SIGNED`).

- [ ] **Step 1: Write the failing meta-block test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliMetaBlockTest {
    @Test
    fun wbitsIsReadWithTheReservedBitRule() {
        // RFC 7932 §9.1: first bit 0 => WBITS 16; the following 3 bits, if non-zero, add to 17.
        assertEquals(16, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00000000))))
    }

    @Test
    fun reservedWbitPatternIsRecognised() {
        // §9.1: first bit 0 => 16 + the next three bits; here they are 1,0,0 => WBITS 17.
        assertEquals(17, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00000010))))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliMetaBlockTest*'`
Expected: FAIL — `BrotliMetaBlock` unresolved.

- [ ] **Step 3: Implement the meta-block header and the alphabet/context tables**

`BrotliMetaBlock.readWbits` implements §9.1 exactly. The header reader implements §9.2: `ISLAST`, then optional `ISEMPTY`/`ISLASTEMPTY`, `MNIBBLES` and `MLEN`, then either `ISUNCOMPRESSED` (byte-align and copy `MLEN` bytes) or `ISMETADATA` (skip a reserved meta-block) or the compressed path. `BrotliAlphabet` carries, as `IntArray` constants, the §5 `insertLengthCode`/`copyLengthCode` offset tables and the §7 `distanceShortCode` offset tables; `BrotliContext` implements the four context ID computations over the last two output bytes and the current literal byte.

- [ ] **Step 4: Run the meta-block test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliMetaBlockTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliMetaBlock.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliContext.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliAlphabet.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliMetaBlockTest.kt
git commit -m "feat(sfnt): add Brotli meta-block header and alphabets"
```

### Task 3: Brotli static dictionary and word transforms

**Files:**
- Create: `scripts/brotli/fetch_dictionary.py`
- Create: `scripts/brotli/README.md`
- Create (generated): `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionary.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionaryTransforms.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionaryTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal object BrotliDictionary` with `val offsetByLength: IntArray`, `val sizeBitsByLength: IntArray`, `fun word(offset: Int, length: Int): ByteArray`; `internal object BrotliDictionaryTransforms` with `fun apply(transformId: Int, word: ByteArray): ByteArray`.

- [ ] **Step 1: Write the dictionary generator script**

`scripts/brotli/fetch_dictionary.py` downloads the RFC 7932 static dictionary from the pinned Google Brotli source at a tagged commit, verifies its SHA-256, and emits `BrotliDictionary.kt` containing the 122,784 dictionary bytes as a chunked `ByteArray` literal, plus the `NWORDS`, `NWORDS_BITS`, `NWORDS_LENGTHS` tables from §8. Write `scripts/brotli/README.md` documenting the pinned origin, the digest and the regeneration command.

- [ ] **Step 2: Generate the dictionary and write the failing test**

```bash
python3 scripts/brotli/fetch_dictionary.py
```

```kotlin
package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliDictionaryTest {
    @Test
    fun dictionaryHasRfcSizeAndKnownWord() {
        assertEquals(122_784, BrotliDictionary.sizeBytes)
        // RFC 7932 §8: the first word of length 4 is "time".
        assertEquals("time", BrotliDictionary.word(offset = 0, length = 4).decodeToString())
    }

    @Test
    fun identityTransformLeavesWordUnchanged() {
        val word = "world".encodeToByteArray()
        assertEquals("world", BrotliDictionaryTransforms.apply(transformId = 0, word).decodeToString())
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliDictionaryTest*'`
Expected: FAIL — `BrotliDictionary` unresolved.

- [ ] **Step 4: Implement the dictionary accessors and all 121 transforms**

`BrotliDictionary.word` computes the §8 offset: entries are grouped by length 4..24; for a length `l` the base is the sum of `NWORDS[l']` for smaller `l'`, and the offset within the group is decoded through `sizeBitsByLength`. `BrotliDictionaryTransforms` implements the complete RFC 7932 §8 transform table (prefix/suffix strings, the identity, and the uppercase/capitalise operations), indexed by the transform id.

- [ ] **Step 5: Run the dictionary test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliDictionaryTest*'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add scripts/brotli kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionary.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionaryTransforms.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDictionaryTest.kt
git commit -m "feat(sfnt): add Brotli static dictionary and transforms"
```

### Task 4: `BrotliDecoder` public API, bounded output, and conformance vectors

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDecoder.kt`
- Create: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDecoderTest.kt`
- Create: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliVectors.kt`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: `internal object BrotliDecoder { fun decode(input: ByteArray, expectedLength: Int): FontOperationResult<ByteArray> }`.

- [ ] **Step 1: Write the failing API test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertContentEquals
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

class BrotliDecoderTest {
    @Test
    fun decodesAnEmptyStreamToAnEmptyArray() {
        assertContentEquals(
            ByteArray(0),
            assertIs<FontOperationResult.Success<ByteArray>>(
                BrotliDecoder.decode(BrotliVectors.EMPTY, expectedLength = 0),
            ).value,
        )
    }

    @Test
    fun refusesOutputLongerThanTheDeclaredLength() {
        val result = BrotliDecoder.decode(BrotliVectors.LOREM_IPSUM, expectedLength = 3)
        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
    }

    @Test
    fun refusesATruncatedStream() {
        val truncated = BrotliVectors.LOREM_IPSUM.copyOf(BrotliVectors.LOREM_IPSUM.size / 2)
        assertIs<FontOperationResult.Failure>(BrotliDecoder.decode(truncated, expectedLength = 1_000))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliDecoderTest*'`
Expected: FAIL — `BrotliDecoder` unresolved.

- [ ] **Step 3: Implement `BrotliDecoder`**

Wire the framing (WBITS), meta-block loop, block-switch state for the three block categories, the literal/command/distance Huffman decoders, context modelling, insert-and-copy, the distance ring buffer and the static-dictionary references (with a transformed-word match length ≥ 4). Write into a bounded output buffer that refuses to grow past `expectedLength` (returning `ResourceLimitExceeded`); return `FontDataFailure("font.woff2.brotli-failed", …)` when `BrotliBits.overran`, a reserved bit is set, a code is invalid, or the stream ends before `expectedLength` bytes are produced. Expose `decode(input, expectedLength): FontOperationResult<ByteArray>`.

- [ ] **Step 4: Add the conformance vector constants**

`BrotliVectors.kt` holds base64 constants for: the empty stream; a short ASCII stream; a stream that uses the static dictionary; and the RFC 7932 test corpus entries. Record in a comment the exact command used to produce each (`brotli --stdout -q 5 < input > out`, Brotli CLI version) so vectors are reproducible.

- [ ] **Step 5: Run the API test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliDecoderTest*'`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDecoder.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliDecoderTest.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/brotli/BrotliVectors.kt
git commit -m "feat(sfnt): expose a bounded Brotli decoder"
```

---

## Phase 2 — SFNT reassembly, WOFF 1.0, and container dispatch

### Task 5: `SfntReassembler`

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/SfntReassembler.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/SfntReassemblerTest.kt`

**Interfaces:**
- Consumes: `readUInt32`, `checkedRangeEnd`, `decodeAsciiTag` from `org.graphiks.kalligraphie.font.sfnt`.
- Produces: `internal class SfntTable(val tag: String, val data: ByteArray, val originalChecksum: UInt? = null)`; `internal object SfntReassembler { fun assemble(flavor: UInt, tables: List<SfntTable>): ByteArray }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertEquals

class SfntReassemblerTest {
    @Test
    fun writesASortedDirectoryWithPaddedTables() {
        val head = SfntTable("head", ByteArray(54))
        val cmap = SfntTable("cmap", ByteArray(5))
        val sfnt = SfntReassembler.assemble(0x00010000u, listOf(head, cmap))
        assertEquals(0x00010000u, readUInt32(sfnt, 0))
        assertEquals(2, readUInt16(sfnt, 4)!!.toInt())
        assertEquals("cmap", sfnt.decodeAsciiTag(12))  // sorted: cmap before head
        assertEquals("head", sfnt.decodeAsciiTag(28))
        assertEquals(0, (sfnt.size % 4))               // every table 4-byte aligned/padded
    }

    @Test
    fun headChecksumAdjustmentMakesTheWholeFontSumToTheMagic() {
        val sfnt = SfntReassembler.assemble(0x00010000u, listOf(SfntTable("head", ByteArray(54))))
        assertEquals(0xB1B0AFBAu, SfntReassembler.wholeFontChecksum(sfnt))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*SfntReassemblerTest*'`
Expected: FAIL — `SfntReassembler` unresolved.

- [ ] **Step 3: Implement `SfntReassembler`**

Compute `searchRange`/`entrySelector`/`rangeShift` for `numTables`; write the directory tag-sorted with each table's recomputed checksum (or `originalChecksum` when supplied); pad each table to a 4-byte boundary; then patch `head.checkSumAdjustment` to `0xB1B0AFBA - wholeFontChecksum(withAdjustmentZeroed)`. Expose `wholeFontChecksum(font: ByteArray): UInt` summing big-endian `UInt32` words.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*SfntReassemblerTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/SfntReassembler.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/SfntReassemblerTest.kt
git commit -m "feat(sfnt): reassemble a standalone SFNT from decoded tables"
```

### Task 6: `WoffReader` and `FontContainerDecoder` (WOFF 1.0)

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/WoffReader.kt`
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/FontContainerDecoder.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/WoffReaderTest.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/FontContainerDecoderTest.kt`

**Interfaces:**
- Consumes: `SfntReassembler`, `SfntTable` (Task 5), `org.graphiks.kalligraphie.api.FontSource`, `okio.Inflater`/`InflaterSource`.
- Produces: `internal object WoffReader { fun decode(bytes: ByteArray): FontOperationResult<ByteArray> }`; `internal enum class ContainerKind { WOFF, WOFF2 }`; `@KalligraphieInternalApi public class DecodedContainer(val bytes: ByteArray, val kind: ContainerKind)`; `@KalligraphieInternalApi public object FontContainerDecoder { fun decode(source: FontSource): FontOperationResult<DecodedContainer?> }`.

- [ ] **Step 1: Write the failing WOFF reader test (uncompressed tables, no compressor needed)**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

class WoffReaderTest {
    @Test
    fun roundTripsAnUncompressedTableDirectory() {
        val font = WoffTestFonts.singleTableSfnt()
        val woff = WoffTestFonts.wrapUncompressed(font)
        val decoded = assertIs<FontOperationResult.Success<ByteArray>>(WoffReader.decode(woff)).value
        // The decoded SFNT carries the same table bytes, though padding/checksums may be recomputed.
        assertEquals("cmap", decoded.decodeAsciiTag(12))
        assertEquals(font.size, decoded.size)
    }

    @Test
    fun refusesATruncatedHeader() {
        val failure = assertIs<FontOperationResult.Failure>(WoffReader.decode(ByteArray(10)))
        assertEquals("font.woff.invalid-header", failure.error.code)
    }

    @Test
    fun refusesACollectionFlavor() {
        val woff = WoffTestFonts.wrapUncompressed(WoffTestFonts.singleTableSfnt(), flavor = 0x74746366u)
        assertIs<FontError.UnsupportedContainer>(
            assertIs<FontOperationResult.Failure>(WoffReader.decode(woff)).error,
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*WoffReaderTest*'`
Expected: FAIL — `WoffReader` unresolved.

- [ ] **Step 3: Implement `WoffReader` and `FontContainerDecoder`**

`WoffReader.decode` validates the 44-byte header (`wOFF`, `flavor`, `length ≤ bytes.size`, `numTables > 0`, `reserved == 0`, `totalSfntSize`), returns `UnsupportedContainer` for `flavor == "ttcf"`, validates each 20-byte record (ranges inside the file, no overlap, no duplicate tag), copies uncompressed tables (`compLength == origLength`) and inflates the others to exactly `origLength` through `okio.InflaterSource` with a bounded sink, then calls `SfntReassembler.assemble(flavor, tables)` carrying `origChecksum`. `FontContainerDecoder.decode` copies `source.copyBytes()`, returns `null` unless the first four bytes are `wOFF`/`wOF2`, and routes to `WoffReader` (WOFF2 lands in Task 7).

- [ ] **Step 4: Run the WOFF reader test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*WoffReaderTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Write and run the dispatch test**

```kotlin
class FontContainerDecoderTest {
    @Test
    fun passesThroughANonContainerSource() {
        val sfnt = WoffTestFonts.singleTableSfnt()
        val decoded = FontContainerDecoder.decode(
            org.graphiks.kalligraphie.api.FontSource(sfnt, org.graphiks.kalligraphie.api.FontSourceProvenance("plain")),
        )
        assertEquals(null, assertIs<FontOperationResult.Success<DecodedContainer?>>(decoded).value)
    }

    @Test
    fun decodesAWoffSourceToItsKind() {
        val woff = WoffTestFonts.wrapUncompressed(WoffTestFonts.singleTableSfnt())
        val decoded = FontContainerDecoder.decode(
            org.graphiks.kalligraphie.api.FontSource(woff, org.graphiks.kalligraphie.api.FontSourceProvenance("wrapped")),
        )
        assertEquals(ContainerKind.WOFF, assertIs<FontOperationResult.Success<DecodedContainer?>>(decoded).value?.kind)
    }
}
```

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*FontContainerDecoderTest*'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container
git commit -m "feat(sfnt): decode WOFF 1.0 containers"
```

---

## Phase 3 — WOFF 2.0 header, Brotli tables, and transforms

### Task 7: `Woff2Reader` header, directory, and untransformed reassembly

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2Reader.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2ReaderTest.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2TestFonts.kt`

**Interfaces:**
- Consumes: `BrotliDecoder.decode` (Task 4), `SfntReassembler` (Task 5), `ContainerKind`/`FontContainerDecoder` (Task 6).
- Produces: `internal object Woff2Reader { fun decode(bytes: ByteArray): FontOperationResult<ByteArray> }`; `internal object Woff2KnownTags { val tagsByIndex: List<String> }`.

- [ ] **Step 1: Write the failing test using a constructed `transformVersion 3` vector**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

class Woff2ReaderTest {
    @Test
    fun decodesAnUntransformedWoff2AndRecomputesChecksums() {
        val woff2 = Woff2TestFonts.singleTableUntransformed()
        val decoded = assertIs<FontOperationResult.Success<ByteArray>>(Woff2Reader.decode(woff2)).value
        assertEquals(0xB1B0AFBAu, SfntReassembler.wholeFontChecksum(decoded))
    }

    @Test
    fun refusesAReconstructedSizeMismatch() {
        val woff2 = Woff2TestFonts.withWrongTotalSfntSize()
        val failure = assertIs<FontOperationResult.Failure>(Woff2Reader.decode(woff2))
        assertEquals("font.woff2.reconstructed-size-mismatch", failure.error.code)
    }

    @Test
    fun refusesACollectionFlavor() {
        val woff2 = Woff2TestFonts.withCollectionFlavor()
        assertIs<FontError.UnsupportedContainer>(
            assertIs<FontOperationResult.Failure>(Woff2Reader.decode(woff2)).error,
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2ReaderTest*'`
Expected: FAIL — `Woff2Reader` unresolved.

- [ ] **Step 3: Implement the WOFF2 header, directory reader, and untransformed path**

Validate the 48-byte header (`wOF2`, `flavor`, `length`, `numTables`, `reserved`, `totalSfntSize`, `totalCompressedSize`, `majorVersion`, `minorVersion`); refuse `ttcf`. Read each directory entry: `flags` (tag index `flags & 0x3F`, transform version `flags ushr 6`), an explicit `tag` when the index is `0x3F`, `origLength` as `UIntBase128`, and `transformLength` as `UIntBase128` **only when the transform applies**. Record the known-tag table (§5.2). Brotli-inflate each table to its declared length. For `transformVersion == 3`, or any table other than `glyf`/`loca`/`hmtx`, pass the decoded bytes through byte-for-byte. Assemble with `SfntReassembler`, then verify the reassembled size equals `totalSfntSize` or fail `font.woff2.reconstructed-size-mismatch`. Extend `FontContainerDecoder` to route `wOF2` to `Woff2Reader`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2ReaderTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2Reader.kt \
        kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/FontContainerDecoder.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2ReaderTest.kt \
        kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2TestFonts.kt
git commit -m "feat(sfnt): read WOFF 2.0 headers and Brotli tables"
```

### Task 8: `glyf` / `loca` transform reconstruction

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2GlyfTransform.kt`
- Modify: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2Reader.kt`
- Test: `kalligraphie/font/sfnt/src/commonTest/kotlin/org/graphiks/kalligraphie/font/sfnt/container/Woff2GlyfTransformTest.kt`

**Interfaces:**
- Consumes: the decoded table map from Task 7.
- Produces: `internal class GlyfReconstruction(val glyf: ByteArray, val loca: ByteArray, val indexFormat: Int)`; `internal object Woff2GlyfTransform { fun reconstruct(transformed: ByteArray, origLength: Int): FontOperationResult<GlyfReconstruction> }`.

- [ ] **Step 1: Write the failing test from a real transformed vector**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult

class Woff2GlyfTransformTest {
    @Test
    fun reconstructsGlyfAndLocaWithIndexFormatOne() {
        val vector = Woff2GlyfVectors.TRANSFORMED_ONE_GLYPH
        val reconstruction = assertIs<FontOperationResult.Success<GlyfReconstruction>>(
            Woff2GlyfTransform.reconstruct(vector.transformedGlyf, origLength = vector.origGlyfLength),
        ).value
        assertEquals(1, reconstruction.indexFormat)
        assertContentEquals(vector.expectedLoca, reconstruction.loca)
        assertContentEquals(vector.expectedGlyf, reconstruction.glyf)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2GlyfTransformTest*'`
Expected: FAIL — `Woff2GlyfTransform` unresolved.

- [ ] **Step 3: Implement the transform**

Parse the §5.1 transform header: `reserved` (must be 0), `optionFlags` (bit 0 `overlapSimple`), `numGlyphs`, `indexFormat` (0 or 1), then the seven stream sizes. Read the seven streams; rebuild, per glyph, the `glyf` record (header, contour/point encoding, optional overlap flag, composite records, bbox, instructions) and the `loca` offsets in `indexFormat`; enforce that the reconstructed `glyf` is exactly `origLength` and that `loca` is `(numGlyphs + 1) * entrySize`. Wire it into `Woff2Reader` so a `glyf` entry with `transformVersion 0` is reconstructed and its paired `loca` entry (also transform 0) is replaced by the produced `loca`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2GlyfTransformTest*'`
Expected: PASS (1 test).

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
- Consumes: the decoded `hhea`/`maxp` tables (for `numberOfHMetrics` and `numGlyphs`) and the transformed `hmtx` bytes from Task 7.
- Produces: `internal object Woff2HmtxTransform { fun reconstruct(transformed: ByteArray, numberOfHMetrics: Int, numGlyphs: Int): FontOperationResult<ByteArray> }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.api.FontOperationResult

class Woff2HmtxTransformTest {
    @Test
    fun flatCaseReconstructsAdvanceWidths() {
        val vector = Woff2HmtxVectors.FLAT
        val hmtx = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2HmtxTransform.reconstruct(vector.transformed, numberOfHMetrics = 2, numGlyphs = 2),
        ).value
        assertContentEquals(vector.expected, hmtx)
    }

    @Test
    fun lsbArraysAreExpandedWhenNumberOfHMetricsIsSmaller() {
        val vector = Woff2HmtxVectors.WITH_LSB
        val hmtx = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2HmtxTransform.reconstruct(vector.transformed, numberOfHMetrics = 1, numGlyphs = 3),
        ).value
        assertEquals(1 * 4 + (3 - 1) * 2, hmtx.size)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*Woff2HmtxTransformTest*'`
Expected: FAIL — `Woff2HmtxTransform` unresolved.

- [ ] **Step 3: Implement the transform**

Implement §5.3: read the `flags` byte; when the optional lsb arrays are present (`flags` low bit clear), expand them; read the advance widths as 16-bit big-endian; when `numberOfHMetrics == numGlyphs` the last lsb is omitted, otherwise emit the trailing `numGlyphs - numberOfHMetrics` lsb values. Wire it into `Woff2Reader` after all tables are inflated (so `hhea`/`maxp` are available), replacing an `hmtx` entry whose `transformVersion == 1`.

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

## Phase 4 — Integration

### Task 10: `Kalligraphie.embedded` accepts WOFF/WOFF2

**Files:**
- Modify: `kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/EmbeddedFontCatalog.kt` (`EmbeddedFontCatalogFactory.create`)
- Test: `kalligraphie/font/core/src/commonTest/kotlin/org/graphiks/kalligraphie/font/core/EmbeddedWoffCatalogTest.kt`

**Interfaces:**
- Consumes: `FontContainerDecoder.decode`, `DecodedContainer` (Task 6, Task 7).
- Produces: no new type; `create` maps each source to its decoded SFNT before `SfntReader.readMetadata`.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.graphiks.kalligraphie.font.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance

class EmbeddedWoffCatalogTest {
    @Test
    fun createsACatalogFromAWoffSource() {
        val woff = EmbeddedWoffTestData.wrappedLiberation()
        val catalog = assertIs<FontOperationResult.Success<*>>(
            EmbeddedFontCatalogFactory.create(
                listOf(FontSource(woff, FontSourceProvenance("wrapped"))),
            ),
        ).value as org.graphiks.kalligraphie.api.FontCatalogSnapshot
        assertEquals(1, catalog.faces.size)
    }

    @Test
    fun theFaceIdentityIsTheDecodedSfntDigest() {
        val woff = EmbeddedWoffTestData.wrappedLiberation()
        val decoded = EmbeddedWoffTestData.decodedLiberation()
        val woffFace = EmbeddedWoffTestData.faceDigest(woff)
        val sfntFace = EmbeddedWoffTestData.faceDigest(decoded)
        assertEquals(sfntFace, woffFace)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:font:core:jvmTest --tests '*EmbeddedWoffCatalogTest*'`
Expected: FAIL — the catalog rejects the WOFF bytes.

- [ ] **Step 3: Implement the decode step in `create`**

At the top of the per-source loop, call `FontContainerDecoder.decode(source)`; on failure return the failure with accumulated diagnostics; on success with a non-null value, replace the source with `FontSource(decoded.bytes, source.provenance)` before parsing and before the generation/dedup computation. Add the test data helper `EmbeddedWoffTestData` producing a real Liberation-based WOFF (compressed) from the corpus fixture, plus the decoded SFNT.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:font:core:jvmTest --tests '*EmbeddedWoffCatalogTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/core/src/commonMain/kotlin/org/graphiks/kalligraphie/font/core/EmbeddedFontCatalog.kt \
        kalligraphie/font/core/src/commonTest/kotlin/org/graphiks/kalligraphie/font/core/EmbeddedWoffCatalogTest.kt
git commit -m "feat(font-core): accept WOFF and WOFF2 in the embedded facade"
```

### Task 11: Directory capture discovers and decodes `.woff`/`.woff2`

**Files:**
- Modify: `kalligraphie/src/jvmMain/kotlin/org/graphiks/kalligraphie/FontDirectoryCapture.kt`
- Test: `kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/FontDirectoryWoffCaptureTest.kt`

**Interfaces:**
- Consumes: `FontContainerDecoder.decode` (Tasks 6–7).
- Produces: no new type; the capture retains the decoded `FontSource` and counts decoded sizes.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.graphiks.kalligraphie

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontCatalogSnapshot

class FontDirectoryWoffCaptureTest {
    @Test
    fun discoversAndDecodesWoffAndWoff2Candidates() {
        val root = createTempDirectory("woff-capture")
        Files.copy(WoffCaptureFixtures.woffPath(), root.resolve("wrapped.woff"))
        Files.copy(WoffCaptureFixtures.woff2Path(), root.resolve("wrapped.woff2"))
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            FontDirectoryCatalog.open(FontDirectoryCatalogOptions(roots = listOf(root.toString()))),
        ).value
        assertEquals(2, catalog.faces.size)
    }

    @Test
    fun anInvalidWoffIsRejectedWithoutFailingTheCapture() {
        val root = createTempDirectory("woff-capture-bad")
        Files.write(root.resolve("broken.woff"), ByteArray(8))
        Files.copy(WoffCaptureFixtures.woffPath(), root.resolve("good.woff"))
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

- [ ] **Step 3: Implement discovery and decoding**

Add `"woff"` and `"woff2"` to `isFontCandidate`. After reading a candidate's bytes, build the `FontSource`, call `FontContainerDecoder.decode`; if it returns a value, re-bind to `FontSource(decoded.bytes, provenance)`; a decode failure routes through `reject(...)` and `continue`. `retainedBytes` accumulates decoded sizes; `maxSourceBytes` bounds the decoded size as documented.

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

## Phase 5 — Corpus and tooling

### Task 12: Acquire the real fixtures and extend `scripts/fonts`

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
- Consumes: the pinned source in spec §4.4 / §8.
- Produces: a corpus family keyed `woff-ibm-plex` that later tasks reference.

- [ ] **Step 1: Download the pinned files and compute their digests**

```bash
mkdir -p test-fixtures/fonts/woff-ibm-plex
BASE=https://raw.githubusercontent.com/IBM/plex/763c36ef9117782905ae010056dfbe8fd2653a25/packages/plex-sans/fonts/complete
curl -fL "$BASE/woff/IBMPlexSans-Regular.woff"  -o test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff
curl -fL "$BASE/woff2/IBMPlexSans-Regular.woff2" -o test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff2
shasum -a 256 test-fixtures/fonts/woff-ibm-plex/*.woff*
```

- [ ] **Step 2: Write `PROVENANCE.md` and copy the licence**

Record the repository, the pinned commit, both raw URLs, the measured SHA-256 and size of each file, the OFL-1.1 licence, and the note that both files are the same font. Copy `IBM/plex`'s OFL text to `OFL.txt`.

- [ ] **Step 3: Add `*.woff binary` to `.gitattributes`**

Confirm `*.woff2 binary` is already present (it is) and add the missing `*.woff binary` line.

- [ ] **Step 4: Extend `fetch_fonts.py` and its tests for `.woff`/`.woff2`**

Generalise the artifact-extension check so `.woff`/`.woff2` are font artifacts, and ensure the table reader opens them through `fontTools` (which requires `brotli` for WOFF2). Add a unit test covering a `.woff2` artifact path. Update `scripts/fonts/README.md`'s artifact definition and the WOFF2 brotli requirement.

- [ ] **Step 5: Populate `corpus.json` from the committed bytes**

Add the `woff-ibm-plex` family (`synthetic: false`) with the measured `sha256`/`sizeBytes` and the `tables` read by fontTools, plus `url`, `rawUrl`, `revision`, `license` (`OFL-1.1`), `licenseFile`.

- [ ] **Step 6: Run the corpus checks**

```bash
python3 scripts/fonts/fetch_fonts.py --check --provenance
uv run --with fonttools==4.65.0 --with brotli python scripts/fonts/check_exhaustiveness.py
python3 -m unittest discover -s scripts/fonts/tests -v
```

Expected: `--check` passes; the exhaustiveness lint reports the new family's carried tables as unclaimed — that is Task 13's work, so record the exact list and continue.

- [ ] **Step 7: Commit**

```bash
git add test-fixtures/fonts/woff-ibm-plex .gitattributes scripts/fonts
git commit -m "chore(sfnt): add the WOFF/WOFF2 corpus family and tooling"
```

---

## Phase 6 — e2e catalog, goldens, and generated docs

### Task 13: Promote the container entries and register their scenes

**Files:**
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/ContainerCatalog.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CorpusKeys.kt`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/SceneFontPaths.kt`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/PortableSceneRenderers.kt`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogProbes.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/RobustnessCatalog.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogClaims.kt`
- Test: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/ContainerEquivalenceTest.kt`
- Test: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/ExpectationCatalogRatchetTest.kt` (existing, must still pass)

**Interfaces:**
- Consumes: the corpus family from Task 12, `outlineCapitalA(corpus, path, what)`.
- Produces: two `Supported` container entries with scenes, two robustness entries with probes.

- [ ] **Step 1: Write the failing cross-container equivalence test**

```kotlin
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertContentEquals
import org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment

class ContainerEquivalenceTest {
    @Test
    fun woffAndWoff2DecodeToTheSameGlyphData() {
        val corpus = E2eTestEnvironment.corpus
        val a = decodeTable(corpus.bytes(WoffPaths.WOFF), "glyf")
        val b = decodeTable(corpus.bytes(WoffPaths.WOFF2), "glyf")
        assertContentEquals(a, b)
    }
}
```

(`decodeTable` reads the `.ttf`/`.otf` face from the decoded catalog via `Kalligraphie.embedded` and returns the raw `glyf` table bytes through the internal `ParsedTrueTypeFont`.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*ContainerEquivalenceTest*'`
Expected: FAIL — `WoffPaths` unresolved.

- [ ] **Step 3: Add paths, keys, renderers, and entries**

Add `WOFF_IBM_PLEX`/`WOFF2_IBM_PLEX` to `CorpusKeys` (both keyed `woff-ibm-plex`), the two paths to `SceneFontPaths`, the two renderers to `PortableSceneRenderers` (`glyph.outline.woff-ibm-plex.A.64`, `glyph.outline.woff2-ibm-plex.A.64`), and promote both `ContainerCatalog` entries to `CatalogStatus.Supported` with `family = GLYPH_OUTLINE`, `route = PORTABLE_GLYPH`, `frame = AutoSized(padding = 1)`, and the outline-table claim set (spec §9). Add the motivated `CatalogClaims.UNREAD_TABLES` entry for every carried-but-unread table the lint named in Task 12 Step 6.

- [ ] **Step 4: Run the equivalence and ratchet tests**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*ContainerEquivalenceTest*' --tests '*ExpectationCatalogRatchetTest*' --tests '*CatalogClaimsRunnerTest*'`
Expected: PASS.

- [ ] **Step 5: Add the robustness entries and probes**

Add `robustness.woff-truncated` (truncate the WOFF fixture) and `robustness.woff2-brotli-corrupted` (flip a byte in the WOFF2 payload) to `RobustnessCatalog`, with `CatalogProbes` entries whose `fontPath` lives under `woff-ibm-plex`, pinning the exact codes the decoder returns.

- [ ] **Step 6: Run the probe ratchet**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*ExpectationCatalogRatchetTest*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add kalligraphie/e2e/src
git commit -m "feat(e2e): certify the WOFF and WOFF2 container scenes"
```

### Task 14: Regenerate goldens and generated docs; update user docs

**Files:**
- Modify: `kalligraphie/e2e/src/harnessResources/golden/manifest.tsv` (generated)
- Modify: `docs/docs/generated/e2e-catalog-matrix.md` / `.fr.md` (generated)
- Modify: `kalligraphie/e2e/src/harnessResources/catalog/claimed-tables.json` (generated)
- Modify: `kalligraphie/e2e/build.gradle.kts` (iOS embedded corpus list)
- Modify: `docs/docs/font-management.md` / `docs/docs/font-management.fr.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 13's catalog.
- Produces: committed goldens and docs consistent with the catalog.

- [ ] **Step 1: Add the fixtures to the iOS embedded corpus**

Add the two `woff-ibm-plex` files to the `iosFixtureCorpus` `entries` list in `kalligraphie/e2e/build.gradle.kts`.

- [ ] **Step 2: Regenerate the committed artifacts**

```bash
./gradlew :kalligraphie:e2e:updateE2eGolden
```

Expected: `manifest.tsv`, both catalog matrices and `claimed-tables.json` gain the two scenes and the promoted entries.

- [ ] **Step 3: Document the feature**

In `font-management.md`/`.fr.md`: add WOFF/WOFF2 to the supported-scope list, add `woff`/`woff2` to the discovery extensions, and add the identity paragraph (decoded-SFNT digest; `.woff` and `.ttf` share identity). Add the `CHANGELOG.md` entry.

- [ ] **Step 4: Run the freshness tests**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogMatrixRunnerTest*' --tests '*CatalogClaimsRunnerTest*' --tests '*GoldenVerificationTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/e2e docs CHANGELOG.md
git commit -m "test(e2e): commit the WOFF and WOFF2 goldens and docs"
```

---

## Phase 7 — Benchmarks

### Task 15: Container bench scenarios

**Files:**
- Create: `kalligraphie/bench/src/commonMain/kotlin/org/graphiks/kalligraphie/bench/scenarios/ContainerScenarios.kt`
- Modify: `kalligraphie/bench/src/commonMain/kotlin/org/graphiks/kalligraphie/bench/ScenarioRegistry.kt`
- Modify: `kalligraphie/bench/src/jvmBenchmark/kotlin/org/graphiks/kalligraphie/bench/PortableGlyphMaterializationBenchmark.kt`
- Modify: the per-platform bench fixture corpora (`JvmBenchmarkFixtureCorpus`, `IosBenchFixtureCorpus`, and the Android corpus) to expose the two files.
- Test: `kalligraphie/bench/src/commonTest/kotlin/org/graphiks/kalligraphie/bench/scenarios/ContainerScenariosTest.kt`

**Interfaces:**
- Consumes: `FixtureCorpus`, `Kalligraphie.embedded`.
- Produces: `public fun containerScenarios(corpus: FixtureCorpus): List<MeasurementScenario>` with `WoffColdCapture`, `Woff2ColdCapture`, `Woff2ColdGlyph`.

- [ ] **Step 1: Write the failing scenario test**

```kotlin
package org.graphiks.kalligraphie.bench.scenarios

import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus

class ContainerScenariosTest {
    @Test
    fun exposesTheThreeContainerProfiles() {
        val names = containerScenarios(TestCorpus).map { it.name }
        assertEquals(listOf("WoffColdCapture", "Woff2ColdCapture", "Woff2ColdGlyph"), names)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kalligraphie:bench:jvmTest --tests '*ContainerScenariosTest*'`
Expected: FAIL — `containerScenarios` unresolved.

- [ ] **Step 3: Implement the scenarios and wire the registry**

Each scenario follows the `TrueTypeColdPreparation` shape (cold per sample, `PORTABLE_GLYPH` route, no required capability): `WoffColdCapture`/`Woff2ColdCapture` call `Kalligraphie.embedded(bytes)` and consume the face; `Woff2ColdGlyph` goes through to resolving the scene glyph's outline and consumes it, counting `sourceBytes`. Add `containerScenarios(corpus)` to `ScenarioRegistry.all`. Add the three names to the `@Param` list of `PortableGlyphMaterializationBenchmark` and register the fixtures in the per-platform bench corpora.

- [ ] **Step 4: Run the test and the JVM profile**

Run: `./gradlew :kalligraphie:bench:jvmTest --tests '*ContainerScenariosTest*'`
Run: `./gradlew :kalligraphie:bench:jvmBenchmark --tests '*PortableGlyphMaterializationBenchmark*WoffColdCapture*'`
Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/bench
git commit -m "feat(bench): measure WOFF and WOFF2 container capture"
```

---

## Phase 8 — Final verification

### Task 16: Full local verification

**Files:**
- Modify: `CHANGELOG.md` if the verification surfaces wording drift only.

- [ ] **Step 1: Run the full check and all tests**

```bash
./gradlew check
./gradlew allTests
```

Expected: PASS.

- [ ] **Step 2: Re-run the corpus obligations**

```bash
python3 scripts/fonts/fetch_fonts.py --check --provenance
uv run --with fonttools==4.65.0 --with brotli python scripts/fonts/check_exhaustiveness.py
python3 -m unittest discover -s scripts/fonts/tests -v
```

Expected: no output / all pass.

- [ ] **Step 3: Confirm the generated artifacts are fresh**

```bash
./gradlew :kalligraphie:e2e:updateE2eGolden
git diff --exit-code
```

Expected: no diff.

- [ ] **Step 4: Final commit if Step 3 produced changes**

```bash
git add -A
git commit -m "chore(sfnt): finalise WOFF and WOFF2 container support"
```
