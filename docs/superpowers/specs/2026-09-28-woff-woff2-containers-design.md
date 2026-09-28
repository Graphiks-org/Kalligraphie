# WOFF 1.0 / WOFF 2.0 container support — design

Status: revised after a very-high review. Implementation plan pending re-review.
Date: 2026-09-28.
Tracking: `container.woff` and `container.woff2` in the e2e catalog, currently
`CatalogStatus.NotYet` with `UnpinnedReason.CORPUS_NOT_ACQUIRED`.

## 1. Context

Kalligraphie's portable font pipeline ingests SFNT bytes only. `Kalligraphie.embedded`,
`FontDirectoryCatalog` and `TrueTypeCollectionReader` hand raw bytes to `SfntReader`, which
validates a TrueType (`0x00010000` / `true`) or OpenType CFF (`OTTO`) table directory and returns
`ParsedTrueTypeFont` — table records with absolute byte offsets into the **same buffer** that
`PreparedTrueTypeFont` later slices. That single-buffer invariant is the load-bearing constraint of
every downstream reader (scaler, glyph, colour, bitmap).

The e2e catalog declares two container expectations, `container.woff` (WOFF 1.0 wrapping) and
`container.woff2` (WOFF 2.0 wrapping), and reports them as "Not yet; corpus not acquired". This
design makes both supported, following `CONTRIBUTING.md`, with end-to-end golden verification and
associated benchmarks.

## 2. Goal and success criteria

- `Kalligraphie.embedded(bytes)` and `FontDirectoryCatalog`/system-font capture accept WOFF 1.0 and
  WOFF 2.0 in addition to the SFNT and TTC containers accepted today.
- A wrapped font is decoded to a standalone SFNT that the existing pipeline consumes unchanged;
  layout, metrics, outlines, colour and bitmap routes behave exactly as for the unwrapped font.
- `container.woff` and `container.woff2` reach `CatalogStatus.Supported`, each verified by a
  committed golden scene, and a **semantic** cross-container equivalence test (outlines, metrics,
  mappings) proves the decoded glyph behaviour is identical even though the reconstructed bytes are
  not.
- Decoding is portable: `commonMain` only, no new third-party dependency.
- Malformed, hostile, or oversized containers are refused with stable typed diagnostics, under
  limits independent of the untrusted declared lengths, before any unbounded allocation.
- Bench scenarios measure WOFF and WOFF2 capture (decode + parse) and end-to-end glyph resolution.

Success is measured by: `./gradlew check` and `./gradlew allTests` passing; the catalog ratchets and
golden/manifest/claims freshness tests passing; the corpus tooling checks in
`scripts/fonts/README.md` passing; and the new bench profiles running on the platforms that serve
the portable glyph route.

## 3. Non-goals (v1)

- WOFF/WOFF2 **collections** (`flavor == 'ttcf'`). A collection container is refused with
  `FontError.UnsupportedContainer`. Single-face containers only.
- WOFF metadata (`meta`) and private (`priv`) blocks are bounded and structurally validated but
  never decoded or surfaced. No declared-size assumption is made about them.
- Encoding (WOFF/WOFF2 writing). Decoding only.
- Brotli **compression**. Decoder only.
- The platform **registry** routes (CoreText/Fontconfig/DirectWrite/Android) are OS APIs, not byte
  containers, and are out of scope. Only byte-backed directory roots are extended, and WOFF is a
  web packaging format, not a conventional installed-system-font format, so `.woff`/`.woff2`
  candidates appear only where a caller points capture at a directory that contains them.
- Byte-identical reconstruction. WOFF2 reconstruction is explicitly permitted to differ from the
  original font's bytes (W3C WOFF2 §5); the design preserves semantics, not encoding.

## 4. Decision record

### 4.1 First-version scope

WOFF 1.0 **and** WOFF 2.0, both complete. WOFF 2.0 brings the Brotli decoder and the
`glyf`/`loca`/`hmtx` transform reconstruction into scope.

### 4.2 Brotli strategy

Vendor a pure-Kotlin Brotli decoder (RFC 7932) inside `:kalligraphie:font:sfnt`, internal, with the
built-in static dictionary as a generated, pinned artifact carrying the upstream Brotli MIT
licence notice. Rationale: `commonMain` must serve Android, JVM and iOS; the only maintained KMP
Brotli options found were native-backed (wrapping the C `libbrotli`), which would introduce
third-party native libraries, a BOM and a security-update surface into a module that today depends
only on `okio`. The repository already vendors its decoders (`PngDecoder`, `SvgDocumentDecoder`/
`SvgOpenTypeReader`, the CFF/CFF2 readers) in `commonMain`.

### 4.3 Integration seam — Approach A: decode at the boundary

`FontContainerDecoder` (internal API in `:kalligraphie:font:sfnt`) sniffs the signature. For
`wOFF`/`wOF2` it reconstructs a standalone SFNT; `EmbeddedFontCatalogFactory.create` and
`captureFontDirectories` then build `FontSource(decodedBytes, provenance)` and call the existing
`SfntReader`/`TrueTypeCollectionReader` path. Offsets stay single-buffer, `PreparedTrueTypeFont`
and everything downstream is untouched.

**Identity is decoded-byte identity, and nothing more.** `FontIdentity` hashes every byte of the
`FontSource`, so two inputs share a face identity **exactly when their retained decoded SFNT bytes
are identical**. Consequences, documented explicitly in `docs/docs/font-management.md` and
`CHANGELOG.md`:

- `FontFaceId.source` for a WOFF/WOFF2 input is the content digest of the decoded SFNT, not of the
  container file.
- Two encodings of the same font do **not** in general share an identity: WOFF2 reconstructs
  `glyf`/`loca` in a permitted, non-byte-identical form (the approved corpus reconstructs to
  199,392 bytes from a 200,388-byte declaration, with different `glyf` bytes but identical glyph
  semantics). A `.woff` and its `.ttf` share identity only when their decoded bytes coincide.
- Directory capture deduplicates candidates by decoded digest; a byte-identical pair collapses,
  a semantically equal but differently encoded pair does not.
- `EmbeddedFontCatalogFactory.create` rejects two sources that decode to identical bytes as
  duplicates (existing duplicate rule, new meaning).

This mirrors the iOS CoreText route, which already rebuilds a standalone SFNT container whose bytes
are not the original file's. Universal *semantic* identity would require additional canonicalisation
and is explicitly out of scope.

Rejected alternatives: (B) retaining the original container identity while carrying decoded bytes
through `EmbeddedFontCatalogEntry`/`PreparedTrueTypeFont` — wider regression surface across
`font:core` and every `source.copyBytes()` call site for a debatable identity gain; (C) decoding
inside `SfntReader` — `ParsedTrueTypeFont` owns no buffer and the current contract is "offsets into
the caller's bytes", which a private decoded buffer would break at every consumer.

### 4.4 Corpus

Real, upstream-distributed files. The new family `woff-ibm-plex` holds the **same text font** in
`.woff` and `.woff2` so the two containers and their semantic equivalence can be proved.

Pinned source: IBM Plex Sans Regular (OFL-1.1) from `IBM/plex` at commit
`763c36ef9117782905ae010056dfbe8fd2653a25` (2026-09-22, the current `master`; the commit's only
changes are `package.json`/`yarn.lock`, so the font tree is unchanged):

- `packages/plex-sans/fonts/complete/woff/IBMPlexSans-Regular.woff`
- `packages/plex-sans/fonts/complete/woff2/IBMPlexSans-Regular.woff2`

with `rawUrl` = `https://raw.githubusercontent.com/IBM/plex/<commit>/packages/plex-sans/fonts/complete/{woff,woff2}/IBMPlexSans-Regular.{woff,woff2}`.
The license text is the repository's OFL. `sha256`, `sizeBytes` and `tables` are measured from the
committed bytes by the corpus tooling at acquisition.

The two files do **not** decode to identical bytes (see §4.3); the e2e proof is semantic, not
byte-level.

The untransformed (`transformVersion 3`) path is covered by constructed unit-test vectors rather
than a second corpus family.

### 4.5 Entry surface

Both `Kalligraphie.embedded(bytes)` and directory capture accept WOFF/WOFF2. Native registry
catalogs are unchanged (§3).

## 5. Architecture

### 5.1 Components (all in `:kalligraphie:font:sfnt`, `commonMain`, internal API)

| Component | Responsibility |
| --- | --- |
| `container/FontContainerDecoder` | Sniff `wOFF`/`wOF2`; dispatch to the reader under `WoffDecodeLimits`; return `DecodedFont?` (`null` = not a managed container). |
| `container/WoffDecodeLimits` | The independent bounds object: `maxDecodedFontBytes`, `maxWorkingBytes`. |
| `container/WoffReader` | WOFF 1.0: 44-byte header, 20-byte records, per-table zlib inflate, exact-size SFNT reassembly. |
| `container/Woff2Reader` | WOFF 2.0: 48-byte header, `flags`+UIntBase128 directory, **one** Brotli stream for the whole font-data block, transform dispatch, SFNT reassembly. |
| `container/Woff2GlyfTransform` | Reconstruct `glyf` and `loca` (7 streams, `optionFlags`/`overlapSimpleBitmap`, `indexFormat`). |
| `container/Woff2HmtxTransform` | Reconstruct `hmtx` from `flags` and, for omitted bearings, the glyph `xMin` values. |
| `container/SfntReassembler` | Build the SFNT: big-endian header, tag-sorted directory, 4-byte padding, per-table checksums (with `head.checkSumAdjustment` zeroed first), then patch `head.checkSumAdjustment`; refuses a combined size over `maxDecodedFontBytes`. |
| `brotli/BrotliDecoder` | RFC 7932 decoder, single stream, bounded output (`maxOutputBytes`) and bounded working set (`maxWorkingBytes`). |
| `brotli/BrotliDictionary` (generated) | The pinned static dictionary bytes and the MIT notice. |

No new Gradle dependency. `okio.Inflater` (already used by `PngDecoder`) serves WOFF 1.0 zlib;
`okio.Deflater` is available for compressed test vectors.

**Public-internal contract types** (annotated `@KalligraphieInternalApi`, and genuinely `public`
so that `font:core` and `:kalligraphie` can use them):

```kotlin
public enum class ContainerKind { WOFF, WOFF2 }

public class DecodedFont(
    public val bytes: ByteArray,
    public val kind: ContainerKind,
)

public object FontContainerDecoder {
    public fun decode(source: FontSource, limits: WoffDecodeLimits): FontOperationResult<DecodedFont?>
}
```

Internal contracts that thread the limits:

```kotlin
internal object BrotliDecoder {
    fun decode(input: ByteArray, maxOutputBytes: Long, maxWorkingBytes: Long): FontOperationResult<ByteArray>
}
internal object SfntReassembler {
    fun assemble(flavor: UInt, tables: List<SfntTable>, maxAssembledBytes: Long): FontOperationResult<ByteArray>
    fun tableChecksum(bytes: ByteArray): UInt
    fun wholeFontChecksum(font: ByteArray): UInt
}
```

`WoffDecodeLimits.EMBEDDED` is `(maxDecodedFontBytes = 64 MiB, maxWorkingBytes = 64 MiB)`; `forCapture(maxSourceBytes)` uses that value for both. Accounting: every produced buffer is charged against `maxDecodedFontBytes` (a table, the decompressed font-data block, and the combined reassembled font); `maxWorkingBytes` bounds the maximum Brotli back-reference distance (window reach) and a transform's live streams. The Brotli Huffman and context tables are bounded by RFC 7932 format constants and need no byte charge. In WOFF 2.0 the Brotli stream decodes with only `maxDecodedFontBytes` as the cap; the decoded length is then compared to the directory sum, so a valid stream with the wrong length yields `font.woff2.invalid-font-data-size`, distinct from a malformed stream (`font.woff2.brotli-failed`).

### 5.2 Data flow

```
bytes
  └─ FontContainerDecoder.decode(source, limits)
       ├─ wOFF  → WoffReader  ──┐
       ├─ wOF2  → Woff2Reader ──┤→ DecodedFont(SFNT bytes, kind)
       └─ else  → null          │
                                ▼
             FontSource(decodedBytes, provenance)
                                ▼
             SfntReader / TrueTypeCollectionReader → ParsedTrueTypeFont
                                ▼
             PreparedTrueTypeFont → (unchanged downstream pipeline)
```

### 5.3 SFNT reassembly rules

- The sfnt header's `sfntVersion` is the container `flavor`.
- The table directory is written tag-sorted; `searchRange`, `entrySelector` and `rangeShift` follow
  the OpenType algorithm.
- Each table's bytes are 4-byte padded; the directory records the unpadded length and the decoded
  offset.
- Cheksum sequencing: **zero `head[8..11]` before** computing the `head` table checksum; write the
  directory and padded tables; compute the whole-font checksum; patch
  `head.checkSumAdjustment = 0xB1B0AFBA - wholeFontChecksum`, which must not change the directory's
  `head` checksum. (The existing iOS `assembleSfnt` computes the directory checksum before zeroing
  and shares this hazard; fixing it is a recorded follow-up, not part of this feature.)
- WOFF 1.0 reuses the directory `origChecksum`; WOFF 2.0 has none and recomputes all of them.
- `DSIG` policy: a compliant WOFF2 encoder removes `DSIG` before encoding; if bytes still carry one,
  it is copied through as opaque table data and is **stale** after reassembly. The decoder never
  attempts to re-sign or repair it, and this is stated in the docs.

## 6. Bounded decoding and failure contract

### 6.1 Independent limits

No allocation or loop bound may be derived solely from an untrusted declared length
(`totalSfntSize`, `origLength`, `transformLength`, `compLength`, `totalCompressedSize`). A single
`WoffDecodeLimits` is threaded through Brotli/zlib inflation, transform reconstruction and
reassembly:

- `maxDecodedFontBytes` caps every produced buffer (a table, the decompressed font-data block, and
  the reassembled SFNT). Capture passes its `maxSourceBytes` (default 16 MiB); embedded ingestion
  uses a documented finite default (64 MiB) since it has no options object.
- `maxWorkingBytes` caps the maximum Brotli back-reference distance (window reach) and a transform's
  live streams. Brotli's Huffman and context tables are bounded by RFC 7932 format constants, so
  they need no byte charge.
- All offsets/lengths use checked `Long` arithmetic before conversion to array sizes; an overflow is
  a `FontError.OutOfBounds` or `ResourceLimitExceeded`, never a negative allocation.

A breach returns `FontError.ResourceLimitExceeded` with numeric context, and nothing partial is
published.

The advisory rule is **WOFF 2.0-specific**: there, `totalSfntSize` and a transformed `glyf`'s
`origLength` are references only and must never reject a correctly decoded font. WOFF 1.0 is
different (§6.2): its `totalSfntSize` and `reserved` fields are normative.

### 6.2 WOFF 1.0 (`WoffReader`)

Big-endian header: `signature 0`, `flavor 4`, `length 8`, `numTables 12`, `reserved 14`,
`totalSfntSize 16`, `majorVersion 20`, `minorVersion 22`, `metaOffset 24`, `metaLength 28`,
`metaOrigLength 32`, `privOffset 36`, `privLength 40` (`numTables`/`reserved`/versions 16-bit, the
rest 32-bit). Record-relative offsets: `tag 0`, `offset 4`, `compLength 8`, `origLength 12`,
`origChecksum 16`.

Validations: signature; complete declared file extent including permitted padding and no extraneous
data; aligned, non-overlapping table/metadata/private extents; absent optional blocks have zero
offset and length; `numTables > 0`; `compLength > origLength` is rejected; `compLength == origLength`
copies raw; `compLength < origLength` inflates a **zlib-wrapped** stream to exactly `origLength`
(bounded by `maxDecodedFontBytes`); `reserved` must be `0`; the header `totalSfntSize` must equal
`12 + 16*numTables + Σ align4(origLength)`, and the reassembled size must equal the same value
(WOFF 1.0 requires header equality, unlike WOFF 2.0); `flavor == 'ttcf'` returns
`UnsupportedContainer`.

### 6.3 WOFF 2.0 (`Woff2Reader`)

Header: `signature`, `flavor`, `length`, `numTables`, `reserved`, `totalSfntSize`,
`totalCompressedSize`, `majorVersion`, `minorVersion`, `meta*`, `priv*`.

Correct rules, per W3C WOFF2:

- `ttcf` flavor is refused immediately.
- A non-zero `reserved` is **not** grounds for rejection (the decoder rule explicitly forbids it).
- `majorVersion`/`minorVersion` are file metadata, not format gates.
- Directory `flags`: bits 0-5 are the known-tag index (63 = a 4-byte tag follows); bits 6-7 are the
  transform version. Known-tag table is §4.1 (63 entries; preserve `'cvt '`, `'CFF '`, `'SVG '` and
  the distinct `'feat'`/`'Feat'`).
- `transformLength` is present **iff** the table uses a non-null transform:
  `glyf`/`loca` non-null is version 0, null is version 3; `hmtx` non-null is version 1, null is 0;
  all other tags are always null (version 0). Any unknown transform version is rejected
  (`font.woff2.unknown-transform`).
- `UIntBase128` rejects a leading `0x80`, more than five bytes, or a value above 2^32-1.
- A transformed `loca` carries `transformLength == 0`; its declared original size must equal
  `(numGlyphs+1) * entrySize` and its transform mode must agree with `glyf`.
- **One Brotli stream.** `totalCompressedSize` bytes are decompressed in a single call bounded by
  `maxDecodedFontBytes`. The decompressed size must equal
  `Σ(transformLength for transformed tables, else origLength)`, and the block is split across the
  directory entries in directory order; extraneous or missing bytes are rejected
  (`font.woff2.invalid-font-data-size`).
- `totalSfntSize` is **advisory**: a correctly reconstructed font must not be rejected for
  disagreeing with it. Likewise a reconstructed `glyf` must not be rejected for differing from its
  `origLength`.
- After reassembly, the produced SFNT must be a valid sfnt (directory ranges in bounds); its size is
  bounded by `maxDecodedFontBytes`, not by `totalSfntSize`.

### 6.4 Failure codes

| Code | Meaning |
| --- | --- |
| `font.woff.invalid-header` | Short or inconsistent WOFF 1.0 header / declared extent. |
| `font.woff.invalid-table-directory` | Overlapping/out-of-range/duplicate records; `compLength > origLength`. |
| `font.woff.invalid-deflate` | A table's zlib stream is malformed or inflates to the wrong length. |
| `font.woff2.invalid-header` | Short or inconsistent WOFF 2.0 header. |
| `font.woff2.invalid-table-directory` | Bad tag index, bad UIntBase128, bad ranges, wrong `totalCompressedSize`. |
| `font.woff2.unknown-transform` | An undeclared transform version for a table tag. |
| `font.woff2.brotli-failed` | The font-data Brotli stream is malformed. |
| `font.woff2.invalid-font-data-size` | The decompressed block disagrees with the directory sum. |
| `font.woff2.transform-failed` | A `glyf`/`loca`/`hmtx` transform is structurally invalid. |
| `font.unsupported-container` | `ttcf`-flavor collection, unknown/unsupported version. |
| `font.resource-limit-exceeded` | Any independent bound breached. |

A malformed container is a per-candidate rejection in directory capture (recorded, capture
continues); in `Kalligraphie.embedded` it is a typed `FontOperationResult.Failure`.

## 7. Integration

### 7.1 `EmbeddedFontCatalogFactory.create`

Decode **all** sources first into a normalised list `(decodedSource, decoded)?`, then run the
existing duplicate check, generation computation and parse over that normalised list — not the
original `capturedSources`. A decode failure returns the failure with accumulated diagnostics. The
generation and duplicate semantics therefore use decoded identities (§4.3).

### 7.2 `captureFontDirectories`

- `isFontCandidate` accepts `woff` and `woff2`.
- Decode `wOFF`/`wOF2` candidates before the `ttcf`/SFNT branch, with `maxSourceBytes` as
  `maxDecodedFontBytes`.
- Deduplicate by **decoded** `FontSourceId`.
- Count retained and aggregate bytes by decoded size; check `maxTotalSourceBytes` on the decoded
  size.
- Charge the face-examination budget for every attempted single-face decode, and preserve the
  `limited` classification when the failure is `ResourceLimitExceeded`.
- A decode failure routes through `reject(...)` and continues.

### 7.3 Documentation

- `docs/docs/font-management.md` and `.fr.md`: supported-scope list, discovery extensions, and the
  decoded-byte identity paragraph (§4.3), including that different containers of one font generally
  do not share identity.
- `CHANGELOG.md`: a `feat(sfnt)` entry.

## 8. Corpus and tooling

- New family `test-fixtures/fonts/woff-ibm-plex/` with `IBMPlexSans-Regular.woff`,
  `IBMPlexSans-Regular.woff2`, `PROVENANCE.md` and the applicable OFL text.
- `.gitattributes`: add `*.woff binary` (`*.woff2 binary` already present).
- `scripts/fonts/fetch_fonts.py`: add `.woff`/`.woff2` to `FONT_SUFFIXES` so the suffix-based tree
  scan and coverage tests accept them. `--check` verifies hashes, sizes and metadata; it does **not**
  read table directories.
- `scripts/fonts/check_exhaustiveness.py` is the table reader; for WOFF2 it needs fontTools +
  `brotli` (`uv run --with fonttools==4.65.0 --with brotli …`). Record every real tag in
  `corpus.json`, `GlyphOrder` excluded.
- `scripts/fonts/README.md` and its tests updated accordingly.
- The family is committed only after the exhaustiveness lint passes (with the catalog claims made in
  §9); a commit that leaves the lint failing is not acceptable.

## 9. e2e catalog, goldens and generated artifacts

- `ContainerCatalog`: both entries become `CatalogStatus.Supported` with a real nonblank
  `sinceCommit`. `CorpusKeys` gains the shared family key; `SceneFontPaths` gains the two paths;
  `tables` declares the actual outline/metadata tables the scene reads (mirror
  `OutlineCatalog.entry("outline.glyf-simple-composite")`); carried-but-unread tables get motivated
  `CatalogClaims.UNREAD_TABLES` entries.
- Two renderers in `PortableSceneRenderers` reuse `outlineCapitalA(corpus, path, what)`;
  `family = GLYPH_OUTLINE`, `route = PORTABLE_GLYPH`, `frame = AutoSized(padding = 1)`, distinct
  `sceneId`s. No new auto-sizing exemption is needed.
- **Semantic cross-container equivalence**, asserted through the public facade (raw table bytes are
  not reachable from the e2e harness and differ by design): the two containers resolve a set of code
  points — a capital letter, a composite glyph and an empty glyph — to the same glyph ids, outline
  commands/points and advances. No raw table-byte comparison is asserted here.
- Robustness entries pin typed refusals via probes built from the committed fixtures: truncation,
  a deterministically malformed Brotli stream (corpus for `font.woff2.brotli-failed`), and a
  directory-level malformation. A random byte flip is not used: Brotli has no content checksum.
- `./gradlew :kalligraphie:e2e:updateE2eGolden` regenerates `manifest.tsv`, the catalog matrices and
  `claimed-tables.json`.
- The two fixtures are added to the `iosFixtureCorpus` task list; the scenes are `PORTABLE_GLYPH`
  and run on JVM, Android and iOS.

## 10. Benchmarks

- New `containerScenarios(corpus)` in `:kalligraphie:bench` common code, added to
  `ScenarioRegistry.all`:
  - `WoffColdCapture` — cold `Kalligraphie.embedded(woffBytes)` decode + parse;
  - `Woff2ColdCapture` — cold WOFF2 decode (one Brotli stream + transforms) + parse;
  - `Woff2ColdGlyph` — cold decode through to resolving the scene glyph's outline.
- Platform wiring is explicit, because registry membership alone does not run anything:
  - JVM `PortableGlyphMaterializationBenchmark` must search `ScenarioRegistry` (it currently searches
    only `glyphMaterializationScenarios`) in addition to extending its `@Param` list;
  - Android `AndroidGlyphMaterializationBenchmark` declares benchmark methods, so three methods are
    added;
  - iOS `IosBenchMain` selects the registry automatically, but the two files must be added to the
    iOS bench fixture corpus in `bench/build.gradle.kts`.
- The JVM task is `:kalligraphie:bench:jvmBenchmarkBenchmark`; `--tests` does not select `@Param`
  values.
- Update the measurement documentation that enumerates the portable profiles.

## 11. Testing

- `commonTest` (sfnt):
  - Brotli: **successful, byte-for-byte** non-empty vectors (literal-only, dictionary-using,
    block-switching, short- and long-distance), plus malformed streams; context-map RLE and inverse
    MTF; `NPOSTFIX`/`NDIRECT`; distance-cache updates; overlapping copies; complete input
    consumption. Pin the vector source and version.
  - WOFF 1.0: header/directory, compressed (okio `Deflater`-built) and uncompressed vectors,
    overlap/duplicate rejection, exact-size reassembly, checksum sequencing with a nonzero incoming
    `checkSumAdjustment`.
  - WOFF 2.0: header/directory, single-stream split, `UIntBase128` bounds, `reserved` non-rejection,
    unknown-transform rejection, `transformVersion 3` pass-through, both `loca` index formats.
  - Transforms: `glyf` simple/composite/empty, bbox inference and explicit bbox, overlap bitmap,
    255UInt16, all triplet codes; `hmtx` flags 1/2/3, empty glyphs, both count shapes, cross-checked
    against original metrics.
  - Bounds: declared-size overflow, actual over-expansion, combined-buffer and combined-assembled
    breaches, `maxDecodedFontBytes`/`maxWorkingBytes` breaches, checked-conversion overflow.
  - Brotli protocol corners: the reserved WBITS encoding is rejected; complex Huffman with repeat
    symbols 16/17; metadata and final-block headers; a valid stream whose decoded length disagrees
    with the WOFF2 directory yields `font.woff2.invalid-font-data-size`, distinct from a malformed
    stream's `font.woff2.brotli-failed`.
  - WOFF 1.0 `reserved != 0` and header `totalSfntSize` mismatch are rejected; the equivalent WOFF 2.0
    fields are accepted.
  - `glyf`: mixed `overlapSimpleBitmap` (the flag follows each glyph's bit), an odd-length glyph
    followed by another, off-curve triplets, and exact `endPtsOfContours`.
- e2e: golden scenes for both entries; semantic cross-container equivalence; robustness probes;
  catalog matrix/claims/manifest freshness.
- Directory-capture and facade tests: `.woff`/`.woff2` discovered and decoded; two byte-distinct
  containers that decode identically deduplicate; decoded-size aggregate limits; failed-decode
  examination charging; `ResourceLimitExceeded` classification preserved.
- W3C WOFF2 compiled decoder tests are used as a conformance source where feasible.

## 12. Process (CONTRIBUTING)

- Branch `feat/sfnt-woff-containers` from the latest `master` in a fork; PR targets
  `Graphiks-org/Kalligraphie`, branch up to date with `master`, squash-only.
- Atomic Conventional Commits with allowed scopes: `sfnt` (decoder and transforms),
  `font-core`/`kalligraphie` (integration), `e2e`, `bench`, `docs`, `ci` (tooling). `build` is a
  type, not a scope, and is not used as one.
- `CHANGELOG.md` updated; PR template headings; exactly one change type; changelog and documentation
  decisions recorded.
- Local verification: `./gradlew check`, `./gradlew allTests`, the `scripts/fonts` commands, and the
  bench profiles.

## 13. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| A from-scratch Brotli decoder is subtle and security-sensitive. | Independent output bounds, successful differential/conformance vectors, malformed-stream tests. |
| Untrusted declared lengths driving allocation. | `WoffDecodeLimits` independent of file fields; checked arithmetic; overflow and over-expansion tests. |
| Correctly decoded fonts rejected (advisory lengths treated as authoritative). | In **WOFF 2.0**, never reject on `totalSfntSize` or reconstructed-`glyf` size; only the decompressed font-data sum is normative. In WOFF 1.0, `totalSfntSize` is normative and a mismatch is rejected. |
| Incorrect identity/dedup expectations. | Identity narrowed to decoded bytes; semantic equivalence in e2e; byte-identity tested with a constructed byte-identical pair. |
| `glyf`/`loca`/`hmtx` reconstruction wrong on real fonts. | Full transform test matrix; semantic cross-check against the approved corpus and fontTools. |
| A plan that runs nothing on some platform. | Explicit per-platform bench entry points; iOS corpus embedding; documented platform runs. |
| Vendored dictionary provenance/licence. | Pinned source commit + hash + MIT notice in the generated artifact and `scripts/brotli/README.md`. |

## 14. Implementation outline (for the plan)

1. Brotli decoder (bit reader, Huffman, meta-blocks, contexts, dictionary) + successful/malformed
   vectors.
2. `WoffDecodeLimits` + `SfntReassembler` (correct checksum sequencing) + WOFF 1.0 reader + dispatch.
3. WOFF 2.0 header/directory + single-stream Brotli split.
4. `glyf`/`loca` transform, then `hmtx` transform (with `xMin` dependency).
5. Corpus acquisition + provenance + `scripts/fonts` tooling, committed with a temporary
   unreferenced-family excuse so the exhaustiveness lint stays green.
6. Integration in `create` (normalised list) and capture (decoded accounting); its tests use the
   acquired fixtures.
7. Catalog entries/renderers/probes + real claims (the temporary excuse is removed) + regenerated
   goldens/claims, committed once the lint passes.
8. Bench scenarios and explicit per-platform wiring.
9. `docs/docs/font-management.md`/`.fr.md`, `CHANGELOG.md`, and the `bench` scope row in
   `CONTRIBUTING.md`.
10. Full local verification and the PR gate.
