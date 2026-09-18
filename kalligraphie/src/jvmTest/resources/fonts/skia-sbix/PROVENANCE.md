# Skia sbix colour bitmap fixture

Source:
[`resources/fonts/sbix.ttf`](https://github.com/google/skia/blob/5f094e6de86302f604e6c3226b4ffc0afff1aa65/resources/fonts/sbix.ttf)
at Skia commit `5f094e6de86302f604e6c3226b4ffc0afff1aa65`. The file is the
unmodified resource:

- `sbix.ttf` (16,692 bytes), SHA-256
  `466dfbce293909c858fe800f0cd856746b88a03cafa1a6750ba923ab99790888`.

The complete Skia BSD-3-Clause notice is retained in `LICENSE.md`.

## Independent audit

Audited with fontTools 4.65.0 plus a raw big-endian structural reader written
for the audit, independently of the Kotlin parser under test:

```sh
shasum -a 256 sbix.ttf
uv run --with fonttools==4.65.0 python -c 'from fontTools.ttLib import TTFont; import struct; f=TTFont("sbix.ttf"); s=f["sbix"]; print(s.version, hex(s.flags), sorted(s.strikes)); print([(p, g, x.graphicType, x.originOffsetX, x.originOffsetY, struct.unpack(">II", x.imageData[16:24]) if x.imageData else None) for p, st in sorted(s.strikes.items()) for g, x in st.glyphs.items()]); print(f["head"].unitsPerEm, [f["hmtx"][g] for g in f.getGlyphOrder()])'
```

Literal structure observed in `sbix.ttf`:

- sbix version 1, flags `0x0001`, and three strikes at 16, 64, and 128 pixels
  per em, each with resolution 72.
- Glyphs 0 (`.notdef`), 2 (`uni2662`), and 3 (`u1F600`) carry `'png '` records
  in every strike; glyph 1 (`space`) carries no record. Every record origin is
  `(0, 0)`.
- Glyph PNG dimensions are 11 × 13 (glyph 0), 11 × 13 (glyph 2), and 13 × 13
  (glyph 3) at 16 ppem; 39 × 52, 39 × 52, and 52 × 52 at 64 ppem; and 78 × 103,
  78 × 103, and 103 × 103 at 128 ppem.
- `head.unitsPerEm` is 1000 and the `hmtx` advance is 800 design units for
  every glyph (`numberOfHMetrics` is 1, so that final entry applies to the
  remaining glyphs).
