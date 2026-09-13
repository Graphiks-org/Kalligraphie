# Skia COLR v1 test font

Source: [`resources/fonts/test_glyphs-glyf_colr_1.ttf`](https://github.com/google/skia/blob/90ce051cbbf81c5e27b272bac330f96c7f4c0441/resources/fonts/test_glyphs-glyf_colr_1.ttf)
at Skia commit `90ce051cbbf81c5e27b272bac330f96c7f4c0441`.
The base64 resource decodes to the unmodified 21,568-byte font, SHA-256
`72cb79b606c79bc49861094e25f442db0b24881504b29533bbe8ea75f3902e67`.
The complete Skia BSD-3-Clause notice is retained in `LICENSE.md`.

Generator: [googlefonts/color-fonts/config/test_glyphs-glyf_colr_1.py](https://github.com/googlefonts/color-fonts/blob/main/config/test_glyphs-glyf_colr_1.py).
Skia's [gm/colrv1.cpp](https://github.com/google/skia/blob/90ce051cbbf81c5e27b272bac330f96c7f4c0441/gm/colrv1.cpp)
records the generation command `python3 config/test_glyphs-glyf_colr_1.py -vvv --generate-descriptions fonts/`.

## Independent audit

Audited with fontTools 4.65.0. Given the original downloaded font as `test_glyphs-glyf_colr_1.ttf`:

```sh
rtk proxy shasum -a 256 test_glyphs-glyf_colr_1.ttf
rtk proxy uv run --with fonttools==4.65.0 python -c 'import fontTools; from fontTools.ttLib import TTFont; print(fontTools.__version__); f=TTFont("test_glyphs-glyf_colr_1.ttf"); print(f["head"].unitsPerEm); print(f["CPAL"].version, len(f["CPAL"].palettes), f["CPAL"].numPaletteEntries); cmap=f.getBestCmap(); print([(hex(c),cmap[c],f.getGlyphID(cmap[c])) for c in (0xF0100,0xF0200,0xF0503)]); ps=[r.Paint for r in f["COLR"].table.BaseGlyphList.BaseGlyphPaintRecord if r.BaseGlyph in [cmap[c] for c in (0xF0100,0xF0200,0xF0503)]]; print([(p.__dict__,p.Paint.__dict__,p.Paint.ColorLine.__dict__,[s.__dict__ for s in p.Paint.ColorLine.ColorStop]) for p in ps]); print(f["CPAL"].palettes[:2]); print(f["COLR"].table.ClipList.clips[cmap[0xF0100]].__dict__); print([(p.Glyph,f.getGlyphID(p.Glyph)) for p in ps])'
```

Literal oracles (independent of the Kotlin parser):

- UPEM 1000; CPAL version 1, three palettes, fourteen entries each.
- U+F0100 is glyph 8 (`linear_repeat_0_1`). ClipBox format 1 is `(100,250,900,950)`. PaintGlyph format 10 clips outline glyph 8 over PaintLinearGradient format 4: p0 `(100,250)`, p1 `(900,250)`, p2 `(100,300)`, REPEAT, stops `(0.0, palette 0, alpha 1.0)` and `(1.0, palette 4, alpha 1.0)`. Palette 0 colors are RGBA `(255,0,0,255)` and `(0,0,255,255)`.
- U+F0200 is glyph 12 (`sweep_0_360_pad_narrow`). PaintGlyph clips glyph 176 (`circle_r350`) over PaintSweepGradient format 8: center `(500,600)`, angles 0 and 360 degrees, PAD. Stop offsets are `0.25`, `0.41668701171875`, `0.58331298828125`, `0.75`; palette indices 7, 4, 0, 8; alpha 1.0 each. Palette 0 RGBA colors are `(250,240,230,255)`, `(0,0,255,255)`, `(255,0,0,255)`, `(47,79,79,255)`.
- U+F0503 is glyph 93 (`radial_contained_gradient_extend_mode_pad`). PaintGlyph clips glyph 2 (`upem_box_glyph`) over PaintRadialGradient format 6: circles `(166,768,0)` and `(166,768,256)`, PAD, stop offsets 0.0, 0.5, 1.0, palette indices 3, 9, 0, alpha 1.0 each. Palette 0 RGBA colors are `(0,128,0,255)`, `(255,255,255,255)`, `(255,0,0,255)`.
- Palette 1 replaces linear colors with RGBA `(42,41,74,255)` and `(14,154,194,255)` without changing glyph IDs, clips or gradient geometry.

OpenType structure and numeric semantics: [COLR](https://learn.microsoft.com/en-us/typography/opentype/spec/colr) and [CPAL](https://learn.microsoft.com/en-us/typography/opentype/spec/cpal).
