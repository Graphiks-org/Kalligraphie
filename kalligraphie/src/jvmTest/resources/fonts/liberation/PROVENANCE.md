# Liberation Sans Regular test specimen

## Source

- Upstream project: [liberationfonts/liberation-fonts](https://github.com/liberationfonts/liberation-fonts)
- Upstream release: `2.1.5`, tag `2.1.5`, release commit `4b01920`
- Source archive: [liberation-fonts-ttf-2.1.5.tar.gz](https://github.com/liberationfonts/liberation-fonts/files/7261482/liberation-fonts-ttf-2.1.5.tar.gz)
- Selected file from the archive: `LiberationSans-Regular.ttf`
- License: `OFL-1.1.txt` in this directory; the upstream project publishes the font under the SIL Open Font License 1.1.
- SHA-256 of the checked-in TTF: `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`

## Independent audit

The expected values in the JVM contract tests were obtained independently
from the checked-in TTF with `fontTools` (`fontTools.ttLib.TTFont`), not from
the Kalligraphie parser. Reproduce the audit with:

```python
from fontTools.pens.boundsPen import BoundsPen
from fontTools.ttLib import TTFont

font = TTFont("LiberationSans-Regular.ttf")
glyph_order = font.getGlyphOrder()
cmap = font.getBestCmap()
glyph_set = font.getGlyphSet()

for code_point in (0x41, 0x00C4):
    glyph_name = cmap[code_point]
    glyph_id = glyph_order.index(glyph_name)
    metrics = font["hmtx"][glyph_name]
    pen = BoundsPen(glyph_set)
    glyph_set[glyph_name].draw(pen)
    glyph = font["glyf"][glyph_name]
    point_count = glyph.endPtsOfContours[-1] + 1 if glyph.numberOfContours > 0 else 0
    components = [(component.glyphName, component.x, component.y) for component in getattr(glyph, "components", [])]
    print(hex(code_point), glyph_id, metrics, pen.bounds, glyph.numberOfContours, point_count, components)
```

The independent output used by the tests is:

| Unicode | Glyph name | Glyph ID | Advance width | Left side bearing | Bounds | Contours / points |
| --- | --- | ---: | ---: | ---: | --- | ---: |
| `U+0041` | `A` | `36` | `1366` | `4` | `(4, 0, 1362, 1409)` | `2 / 17` |
| `U+00C4` | `Adieresis` | `134` | `1366` | `4` | `(4, 0, 1362, 1714)` | composite: `A` (36) + `dieresis` (2338), translation `(364, 0)` |

These values are an external fixture oracle: changing them requires either
replacing the exact upstream artifact and updating this provenance record, or
correcting the implementation when the independent audit still agrees with
the artifact.
