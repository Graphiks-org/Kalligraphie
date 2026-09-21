# Liberation Sans Regular test specimen

## Source

- Upstream project: [liberationfonts/liberation-fonts](https://github.com/liberationfonts/liberation-fonts)
- Upstream release: `2.1.5`, tag `2.1.5`, release commit `4b01920`
- Source archive: [liberation-fonts-ttf-2.1.5.tar.gz](https://github.com/liberationfonts/liberation-fonts/files/7261482/liberation-fonts-ttf-2.1.5.tar.gz)
- Selected file from the archive: `LiberationSans-Regular.ttf`
- Acquisition condition: the selected file was extracted byte-for-byte from the
  archive; it was not subsetted, hinted, normalized, or regenerated.
- License: `OFL-1.1.txt` in this directory; the upstream project publishes the font under the SIL Open Font License 1.1.
- SHA-256 of the checked-in TTF: `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`

## Independent audit

The expected values in the JVM contract tests were obtained independently
from the checked-in TTF with `fontTools` (`fontTools.ttLib.TTFont`), not from
the Kalligraphie parser. The audit used `fontTools==4.59.1` under Python
`3.14.7`. Reproduce the environment and audit with:

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install fonttools==4.59.1
python audit_liberation.py LiberationSans-Regular.ttf
```

The command is run from a directory containing the extracted font. The
following is the complete `audit_liberation.py` body used to produce the
expectations recorded below:

```python
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTFont

font = TTFont("LiberationSans-Regular.ttf")
glyph_order = font.getGlyphOrder()
cmap = font.getBestCmap()
glyph_set = font.getGlyphSet()

for code_point in (0x24, 0x41, 0x00C4):
    glyph_name = cmap[code_point]
    glyph_id = glyph_order.index(glyph_name)
    metrics = font["hmtx"][glyph_name]
    pen = BoundsPen(glyph_set)
    glyph_set[glyph_name].draw(pen)
    glyph = font["glyf"][glyph_name]
    point_count = glyph.endPtsOfContours[-1] + 1 if glyph.numberOfContours > 0 else 0
    components = [(component.glyphName, component.x, component.y) for component in getattr(glyph, "components", [])]
    print(hex(code_point), glyph_name, glyph_id, metrics, pen.bounds, glyph.numberOfContours, point_count, components)
    if code_point == 0x24:
        recording_pen = RecordingPen()
        glyph_set[glyph_name].draw(recording_pen)
        print("dollar recording:", recording_pen.value)
```

The independent output used by the tests is:

| Unicode | Glyph name | Glyph ID | Advance width | Left side bearing | Bounds | Contours / points |
| --- | --- | ---: | ---: | ---: | --- | ---: |
| `U+0041` | `A` | `36` | `1366` | `4` | `(4, 0, 1362, 1409)` | `2 / 17` |
| `U+00C4` | `Adieresis` | `134` | `1366` | `4` | `(4, 0, 1362, 1714)` | composite: `A` (36) + `dieresis` (2338), translation `(364, 0)` |

The fractional-coordinate expectation is also independently recorded for
`U+0024` (`dollar`, glyph ID `7`). `fontTools` reports the first contour
segment as `qCurveTo((217, 297), (376, 177), (518, 168))`. The two consecutive
off-curve points therefore require the exact implicit on-curve point
`(296.5, 237)`, which is what the contour-command test asserts. The dollar
glyph has `3` contours and endpoints `[36, 44, 51]`.

## Human validation

The checked-in archive file was verified against the recorded SHA-256 and
`fc-scan` reported family `Liberation Sans`, style `Regular`, font version
`137625`. A maintainer reviewed the independent `fontTools` output, matched
the glyph IDs, metrics, composite translation, contour counts, and the dollar
implicit point to the expectations in the JVM tests. This review is separate
from the Kalligraphie parser and is intentionally reproducible from the
artifact and commands above.

These values are an external fixture oracle: changing them requires either
replacing the exact upstream artifact and updating this provenance record, or
correcting the implementation when the independent audit still agrees with
the artifact.
