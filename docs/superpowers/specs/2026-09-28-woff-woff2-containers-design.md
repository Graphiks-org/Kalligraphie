# WOFF 1.0 / WOFF 2.0 container support — design

Status: approved in brainstorming; implementation plan pending.
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
  committed golden scene, and a cross-container equivalence test proves the decoded glyph data is
  identical.
- Decoding is portable: `commonMain` only, no new third-party dependency.
- Malformed and hostile containers are refused with stable typed diagnostics before any unbounded
  allocation.
- Bench scenarios measure WOFF and WOFF2 capture (decode + parse) and end-to-end glyph resolution.

Success is measured by: `./gradlew check` and `./gradlew allTests` passing; the catalog ratchets and
golden/manifest/claims freshness tests passing; the corpus tooling checks in
`scripts/fonts/README.md` passing; and the new bench profiles running to completion.

## 3. Non-goals (v1)

- WOFF/WOFF2 **collections** (`flavor == 'ttcf'`). A collection container is refused with
  `FontError.UnsupportedContainer`. Single-face containers only. This is a tracked follow-up.
- WOFF metadata (`meta`) and private (`priv`) blocks. They are parsed for bounds/consistency only
  and never surfaced.
- Encoding (WOFF/WOFF2 writing). Decoding only.
- Brotli **compression**. Decoder only.
- Any change to the platform font-access routes (CoreText/Fontconfig/DirectWrite); those are OS
  APIs, not byte containers.

## 4. Decision record

### 4.1 First-version scope

WOFF 1.0 **and** WOFF 2.0, both complete. WOFF 2.0 brings the Brotli decoder and the
`glyf`/`loca`/`hmtx` transform reconstruction into scope.

### 4.2 Brotli strategy

Vendor a pure-Kotlin Brotli decoder (RFC 7932) inside `:kalligraphie:font:sfnt`, internal, with the
built-in static dictionary as a generated, pinned artifact. Rationale: `commonMain` must serve
Android, JVM and iOS; the only maintained KMP Brotli options found were native-backed (wrapping the
C `libbrotli`), which would introduce third-party native libraries, a BOM and a security-update
surface into a module that today depends only on `okio`. The repository already vendors its
decoders (`PngDecoder`, `SvgDocumentDecoder`/`SvgOpenTypeReader`, the CFF/CFF2 readers) in
`commonMain`, and its
safety-focused, bounded-decoding idiom fits a first-party decoder.

### 4.3 Integration seam — Approach A: decode at the boundary

`FontContainerDecoder` (internal to `:kalligraphie:font:sfnt`) sniffs the signature. For
`wOFF`/`wOF2` it reconstructs a standalone SFNT; `EmbeddedFontCatalogFactory.create` and
`captureFontDirectories` then build `FontSource(decodedBytes, provenance)` and call the existing
`SfntReader`/`TrueTypeCollectionReader` path. Offsets stay single-buffer, `PreparedTrueTypeFont`
and everything downstream is untouched.

Consequences, documented explicitly in `docs/docs/font-management.md` and `CHANGELOG.md`:

- `FontFaceId.source` for a WOFF/WOFF2 input is the content digest of the **decoded SFNT**, not of
  the container file. Two encodings of the same font therefore share an identity.
- Directory capture deduplicates a `.woff`/`.woff2` against an equivalent `.ttf`/`.otf` by that
  decoded digest.
- `EmbeddedFontCatalogFactory.create` rejects two sources that decode to the same content as
  duplicates (existing duplicate rule, new meaning).

This mirrors the iOS CoreText route, which already rebuilds a standalone SFNT container whose bytes
are not the original file's.

Rejected alternatives: (B) retaining the original container identity while carrying decoded bytes
through `EmbeddedFontCatalogEntry`/`PreparedTrueTypeFont` — wider regression surface across
`font:core` and every `source.copyBytes()` call site for a debatable identity gain; (C) decoding
inside `SfntReader` — `ParsedTrueTypeFont` owns no buffer and the current contract is "offsets into
the caller's bytes", which a private decoded buffer would break at every consumer.

### 4.4 Corpus

Real, upstream-distributed files. The new family `woff-ibm-plex` holds the **same text font** in
`.woff` and `.woff2` so the two containers and the cross-container equivalence can be proved.

Pinned source: IBM Plex Sans Regular (OFL-1.1) from `IBM/plex` at commit
`763c36ef9117782905ae010056dfbe8fd2653a25` (2026-09-22, the current `master`; the commit's only
changes are `package.json`/`yarn.lock`, so the font tree is unchanged):

- `packages/plex-sans/fonts/complete/woff/IBMPlexSans-Regular.woff`
- `packages/plex-sans/fonts/complete/woff2/IBMPlexSans-Regular.woff2`

with `rawUrl` = `https://raw.githubusercontent.com/IBM/plex/<commit>/packages/plex-sans/fonts/complete/{woff,woff2}/IBMPlexSans-Regular.{woff,woff2}`.
The license file is the repository's OFL text; the exact path is recorded in `PROVENANCE.md` and
`corpus.json`. `sha256`, `sizeBytes` and `tables` are measured from the committed bytes by
`scripts/fonts/fetch_fonts.py` at acquisition, as the corpus contract requires.

The untransformed (`transformVersion 3`) path is covered by constructed unit-test vectors rather
than a second corpus family.

### 4.5 Entry surface

Both `Kalligraphie.embedded(bytes)` and directory/system capture accept WOFF/WOFF2.

## 5. Architecture

### 5.1 Components (all in `:kalligraphie:font:sfnt`, `commonMain`, `@KalligraphieInternalApi`)

| Component | Responsibility |
| --- | --- |
| `container/FontContainerDecoder` | Sniff `wOFF`/`wOF2`; dispatch to the reader; return `DecodedFont?` (`null` = not a managed container, caller proceeds unchanged). |
| `container/WoffReader` | WOFF 1.0: 44-byte header, 20-byte table directory, per-table zlib inflate, SFNT reassembly. |
| `container/Woff2Reader` | WOFF 2.0: 48-byte header, `flags`+UIntBase128 directory, per-table Brotli inflate, transform dispatch, SFNT reassembly. |
| `container/Woff2GlyfTransform` | Reconstruct `glyf` and `loca` (7 streams, `optionFlags`/`overlapSimple`, `indexFormat`). |
| `container/Woff2HmtxTransform` | Reconstruct `hmtx` (`flags`, optional lsb arrays when not monospaced). |
| `container/SfntReassembler` | Build the SFNT: big-endian header, tag-sorted table directory, 4-byte padding, per-table checksums, `head.checkSumAdjustment`. |
| `brotli/BrotliDecoder` | RFC 7932 decoder: block types, context modeling, insert-and-copy, distance codes, static dictionary. |
| `brotli/BrotliDictionary` (generated) | The pinned static dictionary bytes. |

No new Gradle dependency. The Brotli dictionary is generated from a pinned source by a small
script under `scripts/` (the repository's established pattern for generated tables) and committed.

### 5.2 Data flow

```
bytes
  └─ FontContainerDecoder.decode
       ├─ wOFF  → WoffReader  ──┐
       ├─ wOF2  → Woff2Reader ──┤→ DecodedFont(SFNT bytes)
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
- WOFF 1.0 carries `origChecksum`; it is reused. WOFF 2.0 has no checksums; they are recomputed.
- `head.checkSumAdjustment` is set so the whole font's checksum is `0xB1B0AFBA` (WOFF2 spec
  requirement). `head` itself is stored untransformed by WOFF2.

## 6. Bounded decoding and failure contract

Every size is validated **before** the corresponding allocation:

- WOFF 1.0: `signature`, `length`, `numTables`, `reserved`, `totalSfntSize`, table directory ranges,
  `compLength`/`origLength` consistency. A table with `compLength == origLength` is stored
  uncompressed and copied; otherwise its zlib stream is inflated to exactly `origLength`.
- WOFF 2.0: `signature`, `length`, `numTables`, `reserved`, `totalSfntSize`, `totalCompressedSize`,
  `numFonts`/collection refusal, table directory ranges and tag indices, `origLength`,
  `transformLength` presence per transformation rule. Each Brotli stream is inflated to exactly its
  declared length; each transform's output is bounded by `origLength`.
- The reconstructed SFNT size is bounded by the input's declared `totalSfntSize` and by the
  caller's `maxSourceBytes` (directory capture). Exceeding a bound returns
  `FontError.ResourceLimitExceeded`; nothing partial is published.

Typed failures (all `font.`-prefixed, so `FontError.FontDataFailure` is valid where a specific code
is needed):

| Code | Meaning |
| --- | --- |
| `font.woff.invalid-header` | Short or inconsistent WOFF 1.0 header. |
| `font.woff.invalid-table-directory` | Overlapping/out-of-range/duplicate WOFF 1.0 table records. |
| `font.woff.invalid-deflate` | A table's zlib stream is malformed or inflates to the wrong length. |
| `font.woff2.invalid-header` | Short or inconsistent WOFF 2.0 header. |
| `font.woff2.invalid-table-directory` | Bad tag index, bad UIntBase128, bad ranges, wrong `totalCompressedSize`. |
| `font.woff2.brotli-failed` | A table's Brotli stream is malformed or inflates to the wrong length. |
| `font.woff2.transform-failed` | A `glyf`/`loca`/`hmtx` transform is structurally invalid. |
| `font.woff2.reconstructed-size-mismatch` | Reassembled size disagrees with `totalSfntSize`. |
| `font.unsupported-container` | `ttcf`-flavor collection, unknown/unsupported version. |

A malformed container is a per-candidate rejection in directory capture (recorded, capture
continues); in `Kalligraphie.embedded` it is a typed `FontOperationResult.Failure`, exactly like a
malformed SFNT today.

## 7. Integration

### 7.1 `EmbeddedFontCatalogFactory.create`

For each captured source: sniff; if `wOFF`/`wOF2`, decode and replace the source with a new
`FontSource(decodedBytes, provenance)`; parse with `SfntReader`. Decode failure returns the failure
with the accumulated diagnostics, as the current `SfntReader` failure branch does. The catalog
generation and duplicate check use the decoded identities.

### 7.2 `captureFontDirectories`

- `isFontCandidate` accepts `woff` and `woff2`.
- After reading a candidate, `wOFF`/`wOF2` are decoded before the existing `ttcf`/SFNT branch;
  the retained `FontSource` is the decoded SFNT.
- `maxSourceBytes` bounds the read container and the reconstructed size; `maxTotalSourceBytes`
  counts decoded sizes.
- A decode failure routes through `reject(...)` like any other malformed candidate.

### 7.3 Documentation

- `docs/docs/font-management.md` and `.fr.md`: supported-scope list and discovery-extension list
  gain WOFF/WOFF2; add an identity paragraph stating the decoded-SFNT digest semantics.
- `CHANGELOG.md`: a `feat(sfnt)` entry (final scope decides the exact header).

## 8. Corpus and tooling

- New family `test-fixtures/fonts/woff-ibm-plex/` with `IBMPlexSans-Regular.woff`,
  `IBMPlexSans-Regular.woff2`, `PROVENANCE.md` and the applicable OFL text, obtained from
  `IBM/plex@763c36ef9117782905ae010056dfbe8fd2653a25` as pinned in §4.4.
- `.gitattributes`: add `*.woff binary` (`*.woff2 binary` already present).
- `scripts/fonts/corpus.json`: new family (`synthetic: false`), with `url`, `rawUrl`, `revision`,
  `license` (`OFL-1.1`), `sha256`, `sizeBytes` and the real `tables` read from the committed bytes.
- `scripts/fonts/README.md`, `fetch_fonts.py` and its tests: accept `.woff`/`.woff2` artifacts;
  `--check` reads tables through fontTools, which requires `brotli` for WOFF2
  (`uv run --with fonttools==4.65.0 --with brotli`). `check_exhaustiveness.py` is unchanged in
  principle.
- The corpus commands in `scripts/fonts/README.md` are local obligations and must pass before
  committing fixture changes.

## 9. e2e catalog, goldens and generated artifacts

- `ContainerCatalog`: both entries become `CatalogStatus.Supported`. `CorpusKeys` gains the family
  key; `SceneFontPaths` gains the two paths; `tables` claims the tables the outline scene reads;
  carried-but-unread tables get motivated `CatalogClaims.UNREAD_TABLES` entries.
- Two renderers in `PortableSceneRenderers` reuse the existing `outlineCapitalA(corpus, path, what)`
  shape; `family = GLYPH_OUTLINE`, `route = PORTABLE_GLYPH`, `frame = AutoSized(padding = 1)`
  (new entries may not pin a frame), distinct `sceneId`s.
- Two robustness entries pin typed refusals via probes that truncate/corrupt the committed
  fixtures: `robustness.woff-truncated`, `robustness.woff2-brotli-corrupted`.
- `./gradlew :kalligraphie:e2e:updateE2eGolden` regenerates `manifest.tsv`, the catalog matrices and
  `claimed-tables.json`.
- The two fixtures are added to the e2e `iosFixtureCorpus` task list so the iOS harness renders the
  container scenes like JVM and Android.
- A shared harness test asserts cross-container equivalence: the `.woff` and `.woff2` decode to
  identical `glyf`/`loca`/`hmtx`/`cmap` data and identical outlines for the scene glyph.

## 10. Benchmarks

- New `containerScenarios(corpus)` in `:kalligraphie:bench` common code, added to
  `ScenarioRegistry.all`, so `portable-glyph` selection picks it up on every platform:
  - `WoffColdCapture` — cold `Kalligraphie.embedded(woffBytes)` decode + parse;
  - `Woff2ColdCapture` — cold WOFF2 decode (Brotli + reassembly) + parse;
  - `Woff2ColdGlyph` — cold decode through to resolving the scene glyph's outline, so the transform
    reconstruction cost is inside the timed boundary.
- Update the `@Param` list of `PortableGlyphMaterializationBenchmark` and the per-platform bench
  fixture corpora (JVM, iOS, Android) to expose the new files.
- Update the measurement documentation that enumerates the portable profiles.

## 11. Testing

- `commonTest` (sfnt): WOFF header/directory validation and round-trip; WOFF2 header/directory;
  Brotli decode against RFC 7932 vectors and W3C WOFF2 conformance vectors; `glyf`/`loca`/`hmtx`
  transforms including `transformVersion 3`; truncation, overlap, bad UIntBase128, wrong lengths,
  decompression-bomb bounds; cross-format equivalence on the corpus.
- Brotli: dedicated vectors plus malformed streams (reserved bits, bad block type, over-long
  dictionary distance, truncated final block).
- e2e: golden scenes for both entries; cross-container equivalence; catalog matrix / claims /
  manifest freshness; robustness probes.
- Directory-capture tests: `.woff`/`.woff2` discovered, decoded, deduplicated, and bounded.
- Bench: the three profiles run to completion and publish observations.

## 12. Process (CONTRIBUTING)

- Branch `feat/sfnt-woff-containers` from the latest `master` in a fork; PR targets
  `Graphiks-org/Kalligraphie`.
- Atomic Conventional Commits with scopes from the policy table: `sfnt` (decoder and transforms),
  `font-core`/`kalligraphie` (integration), `e2e`, `bench`, `docs`, and `build`/`ci` only if the
  corpus tooling requires it.
- `CHANGELOG.md` updated; PR template headings and exactly one change type; changelog and
  documentation decisions recorded.
- Local verification: `./gradlew check`, `./gradlew allTests`, and the `scripts/fonts` commands.

## 13. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| A from-scratch Brotli decoder is subtle and security-sensitive. | Bounded output, W3C + RFC vectors, fuzzed/truncated-stream tests, typed refusals. |
| The static dictionary adds ~120 KB of committed data. | Generated and pinned by script, like the Unicode tables; documented provenance. |
| WOFF2 `glyf`/`loca` reconstruction is intricate and easy to get subtly wrong. | Cross-container equivalence test against a real WOFF2 shipping the same font; structural + outline equality. |
| Identity change surprises consumers. | Explicit documentation and `CHANGELOG` note; behaviour is deliberate and content-addressed. |
| Corpus tooling only knows `.ttf`/`.otf`/`.ttc`. | Extend `fetch_fonts.py`, its tests and `README.md`; keep exhaustiveness lint semantics. |
| iOS golden portability. | Add the fixtures to the embedded iOS corpus task and run the scenes on the simulator. |

## 14. Implementation outline (for the plan)

1. Brotli decoder + tests (isolated, no integration).
2. `SfntReassembler` + WOFF 1.0 reader + tests.
3. WOFF 2.0 header/directory + Brotli table inflate + tests.
4. `glyf`/`loca` transform, then `hmtx` transform, + tests.
5. `FontContainerDecoder` + integration in `create` and capture; unit and capture tests.
6. Corpus acquisition, provenance, `scripts/fonts` tooling + checks.
7. Catalog entries, renderers, robustness probes, goldens, generated docs, iOS corpus.
8. Bench scenarios and per-platform corpora.
9. `docs/docs/font-management.md`/`.fr.md`, `CHANGELOG.md`.
10. Full local verification.
