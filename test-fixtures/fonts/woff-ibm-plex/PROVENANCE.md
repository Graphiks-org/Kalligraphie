# IBM Plex Sans WOFF and WOFF2 container fixtures

## Source

- Upstream project: [IBM Plex](https://github.com/IBM/plex/tree/763c36ef9117782905ae010056dfbe8fd2653a25)
- Font: IBM Plex Sans Regular, packaged in the two web-font containers the library now decodes
- Pinned source revision: `763c36ef9117782905ae010056dfbe8fd2653a25`
- WOFF source page: <https://github.com/IBM/plex/tree/763c36ef9117782905ae010056dfbe8fd2653a25/packages/plex-sans/fonts/complete/woff>
- WOFF raw URL: <https://raw.githubusercontent.com/IBM/plex/763c36ef9117782905ae010056dfbe8fd2653a25/packages/plex-sans/fonts/complete/woff/IBMPlexSans-Regular.woff>
- WOFF2 source page: <https://github.com/IBM/plex/tree/763c36ef9117782905ae010056dfbe8fd2653a25/packages/plex-sans/fonts/complete/woff2>
- WOFF2 raw URL: <https://raw.githubusercontent.com/IBM/plex/763c36ef9117782905ae010056dfbe8fd2653a25/packages/plex-sans/fonts/complete/woff2/IBMPlexSans-Regular.woff2>
- SHA-256 of `IBMPlexSans-Regular.woff`: `b731cf56514a4bd711ab2f9acf641f9311707f4386772eb306d25c2b29b73b1a`
- Size of `IBMPlexSans-Regular.woff`: `87508` bytes
- SHA-256 of `IBMPlexSans-Regular.woff2`: `ba711a3085ff9f27440b6b9c4550cfc47c97bf36591d5da958b975bb3add8c1a`
- Size of `IBMPlexSans-Regular.woff2`: `63020` bytes
- License: [SIL Open Font License 1.1](OFL.txt), SHA-256 `37784b44044a4ffd9256702b7c0982c37e5c8887ba90c6dca0479aea93dc898d` (`4363` bytes; the upstream `LICENSE.txt` uses CRLF and is committed with the repository's LF line endings, as `.gitattributes` requires)
- Retrieved: 2026-09-28

Both files are unchanged upstream artifacts: they were not subsetted, hinted,
normalized, re-encoded or regenerated. They are committed whole, as the corpus
rule requires, because the container scenes need real container streams rather
than a hand-built minimal header.

## The two containers decode to different bytes

The two files package the same face, but their decompressed SFNT payloads are
**not byte-identical**: WOFF 1.0 stores each table with zlib compression while
WOFF 2.0 stores one Brotli stream with the `glyf`/`loca` and `hmtx` transforms,
so a decoder that reassembles the tables produces two distinct SFNT
serializations.

The decoded digests below are reproducible. A plain `TTFont(...).save()` is not
enough: `save()` recalculates `head.modified` from the wall clock by default, so
its output changes between invocations. The exact command below disables both
recalculations (`recalcTimestamp=False`, `recalcBBoxes=False`) and clears the
container `flavor` so the save produces SFNT; it was run twice in separate
processes and printed identical bytes both times:

```python
import hashlib, pathlib, tempfile
from fontTools.ttLib import TTFont

for src in ("test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff",
            "test-fixtures/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff2"):
    with tempfile.TemporaryDirectory() as td:
        out = pathlib.Path(td) / "out.ttf"
        font = TTFont(src, recalcBBoxes=False, recalcTimestamp=False)
        font.flavor = None
        font.save(out, reorderTables=False)
        font.close()
        data = out.read_bytes()
        print(src, len(data), hashlib.sha256(data).hexdigest())
```

Run with `uv run --with fonttools==4.65.0 --with brotli python <script>`:

| Committed file | Decoded SFNT size | Decoded SFNT SHA-256 |
| --- | --- | --- |
| `IBMPlexSans-Regular.woff` | `200388` | `3761bea7ea7c938f59be5f3f85b2aed2b862d70dfcd5c753e4f14a41fa3c51cd` |
| `IBMPlexSans-Regular.woff2` | `199392` | `db8229184e94a6218484230da2d94fb7d4e7b459f78c4f5be47d484ce0fa8018` |

Both containers describe the same sfnt table directory, so the corpus `tables`
list is the same for the two records. These decoded digests are evidence for
this note only; the committed artifacts and the corpus manifest describe the
container bytes, not the decoded SFNT.

## Why this family is in the corpus

The WOFF 1.0 and WOFF 2.0 decoders had no real corpus-backed evidence: the
container scenes could only point at synthetic bytes. IBM Plex Sans Regular is
an OFL-1.1 face distributed upstream in both web-font containers from one pinned
commit, so the two scenes read the same real typeface through the two decode
paths and the corpus can verify the container bytes with `--check` while the
exhaustiveness lint reads the tables through fontTools + `brotli`.
