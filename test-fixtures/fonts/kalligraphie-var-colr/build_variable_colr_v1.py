#!/usr/bin/env python3
"""Build the variable COLR v1 fixture used by the portable variable-colour tests.

The font is synthetic and deterministic. It carries one `wght` axis (100/400/900), a COLR v1
table with an `ItemVariationStore` (one region on wght 0..1..1) and a `DeltaSetIndexMap`, and
three base glyphs: `linear` (PaintVarLinearGradient with a VarColorLine and a variable ClipBox),
`solid` (PaintVarSolid) and `moved` (PaintVarTranslate -> PaintVarSolid). At the default
location every region scalar is zero, so the audited output equals the static paint values.

Run from the repository root (requires fonttools 4.65.0):
    python3 test-fixtures/fonts/kalligraphie-var-colr/build_variable_colr_v1.py
"""

import hashlib
import io
from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib.tables import otTables as ot
from fontTools.varLib.builder import buildVarRegionList, buildVarData, buildVarStore

HERE = Path(__file__).resolve().parent
OUTPUT = HERE / "KalligraphieVarCOLRv1.ttf"
GLYPHS = [".notdef", "box", "linear", "solid", "moved"]
# Fixed LONGDATETIME so the generated binary is byte-reproducible.
FIXED_DATE = 3_600_000_000
# Row index -> one int16 delta for the single wght region.
DELTAS = [0, 100, -50, 200, 25, -25, 75, 4096, -8192, 2048, 4096, -8192, 10, 20, -30, -40, 50, -60]


def box_glyph():
    pen = TTGlyphPen(None)
    pen.moveTo((100, 100))
    pen.lineTo((900, 100))
    pen.lineTo((900, 900))
    pen.lineTo((100, 900))
    pen.closePath()
    return pen.glyph()


def glyph_clip(child):
    return {"Format": ot.PaintFormat.PaintGlyph, "Glyph": "box", "Paint": child}


def build() -> bytes:
    fb = FontBuilder(1000, isTTF=True)
    fb.updateHead(created=FIXED_DATE, modified=FIXED_DATE, fontRevision=1.0)
    fb.setupGlyphOrder(GLYPHS)
    fb.setupCharacterMap({0x41: "linear", 0x42: "solid", 0x43: "moved"})
    fb.setupGlyf({g: (TTGlyphPen(None).glyph() if g == ".notdef" else box_glyph()) for g in GLYPHS})
    fb.setupHorizontalMetrics({g: (600, 50) for g in GLYPHS})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupNameTable({"familyName": "Kalligraphie Variable COLR v1 Fixture", "styleName": "Regular"})
    fb.setupOS2()
    fb.setupPost()
    fb.setupFvar(
        [("wght", 100, 400, 900, "Weight")],
        [{"location": {"wght": 400}, "stylename": "Regular"}],
    )
    fb.setupCPAL([[(1.0, 0.0, 0.0, 1.0), (0.0, 0.0, 1.0, 1.0), (0.0, 1.0, 0.0, 1.0)]])

    regions = buildVarRegionList([{"wght": (0.0, 1.0, 1.0)}], ["wght"])
    var_store = buildVarStore(regions, [buildVarData([0], [[d] for d in DELTAS])])
    index_map = ot.DeltaSetIndexMap()
    index_map.mapping = list(range(len(DELTAS)))

    linear = {
        "Format": ot.PaintFormat.PaintVarLinearGradient,
        "ColorLine": {
            "Extend": "REPEAT",
            "ColorStop": [
                {"StopOffset": 0.0, "PaletteIndex": 0, "Alpha": 1.0, "VarIndexBase": 7},
                {"StopOffset": 1.0, "PaletteIndex": 1, "Alpha": 1.0, "VarIndexBase": 9},
            ],
        },
        "x0": 100, "y0": 250, "x1": 900, "y1": 250, "x2": 100, "y2": 300,
        "VarIndexBase": 1,
    }
    solid = {"Format": ot.PaintFormat.PaintVarSolid, "PaletteIndex": 0, "Alpha": 1.0, "VarIndexBase": 11}
    moved = {"Format": ot.PaintFormat.PaintVarTranslate, "dx": 10, "dy": 20, "VarIndexBase": 16, "Paint": solid}
    color_layers = {
        "linear": glyph_clip(linear),
        "solid": glyph_clip(solid),
        "moved": glyph_clip(moved),
    }
    clip_boxes = {"linear": (100, 250, 900, 950, 12)}
    fb.setupCOLR(
        color_layers,
        varStore=var_store,
        varIndexMap=index_map,
        clipBoxes=clip_boxes,
        allowLayerReuse=False,
    )

    out = io.BytesIO()
    fb.save(out)
    return out.getvalue()


if __name__ == "__main__":
    data = build()
    OUTPUT.write_bytes(data)
    print(f"wrote {OUTPUT.name} ({len(data)} bytes)")
    print(f"sha256: {hashlib.sha256(data).hexdigest()}")
