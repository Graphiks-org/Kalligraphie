# Skia CBDT/CBLC colour bitmap fixtures

Sources:
[`resources/fonts/cbdt.ttf`](https://github.com/google/skia/blob/5f094e6de86302f604e6c3226b4ffc0afff1aa65/resources/fonts/cbdt.ttf)
and
[`resources/fonts/planetcbdt.ttf`](https://github.com/google/skia/blob/5f094e6de86302f604e6c3226b4ffc0afff1aa65/resources/fonts/planetcbdt.ttf)
at Skia commit `5f094e6de86302f604e6c3226b4ffc0afff1aa65`. Both files are the
unmodified resources:

- `cbdt.ttf` (16,760 bytes), SHA-256
  `eb66fce167177e4df2355ed2c7a519e6a17a1efd062556a1ef4cea342318c680`.
- `planetcbdt.ttf` (115,512 bytes), SHA-256
  `53a88a71a10c2a32abc91284f95f71711b1a3010a5f6a6f9c67ceb499d866533`.

The complete Skia BSD-3-Clause notice is retained in `LICENSE.md`.

## Independent audit

Audited with fontTools 4.65.0 plus a raw big-endian structural reader written
for the audit, independently of the Kotlin parser under test:

```sh
shasum -a 256 cbdt.ttf planetcbdt.ttf
uv run --with fonttools==4.65.0 python -c 'from fontTools.ttLib import TTFont; f=TTFont("cbdt.ttf"); print(f["CBLC"].version, f["CBDT"].version, len(f["CBLC"].strikes), f["maxp"].numGlyphs)'
```

Literal structure observed in `cbdt.ttf`:

- Four glyphs, no outlines. CBLC and CBDT both declare version 2.0.
- Three strikes: 16, 64, and 128 pixels per em. Every strike uses bit depth
  32, horizontal-metrics flag `0x01`, CBLC index-subtable format 1, CBDT
  image format 17, and embedded PNG records.
- Glyph 0 (`.notdef`) at the 16 ppem strike is an 11 × 13 PNG with left side
  bearing 1, top bearing 13, and advance 12.
- Glyph 0 at the 128 ppem strike is a 78 × 103 PNG with left side bearing 12,
  top bearing 103, and advance 102.

Literal structure observed in `planetcbdt.ttf`:

- Ten glyphs. CBLC and CBDT both declare version 2.0.
- Two strikes: 8 and 16 pixels per em, both bit depth 32 with CBLC index
  format 1 and CBDT image format 17.
- Glyphs 6 and 7 carry multi-`IDAT` PNGs: glyph 6 has two `IDAT` chunks at
  8 ppem and four at 16 ppem; glyph 7 has two at 8 ppem and six at 16 ppem.
- Glyph 7 has negative left side bearings: `-12` at 8 ppem and `-24` at
  16 ppem.
