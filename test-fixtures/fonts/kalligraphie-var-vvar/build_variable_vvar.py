#!/usr/bin/env python3
"""Build the variable vertical-metrics fixture used by the vertical cross-check.

The font is synthetic and deterministic. It carries one `wght` axis (0/0/1000), a
`vhea`/`vmtx` base advance height of 1000 for every glyph, and a `VVAR` table with one
region `(0.0, 1.0, 1.0)` on `wght`, one `VarData` whose glyph `A` row adds 200 at the
axis maximum, and a present `AdvHeightMap`. At the default location the region scalar is
zero, so the audited advance equals the `vmtx` base; at `wght = 500`/`1000` it moves.

Run from the repository root (requires fonttools 4.65.0):
    python3 test-fixtures/fonts/kalligraphie-var-vvar/build_variable_vvar.py
"""

import hashlib
import io
from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import newTable
from fontTools.ttLib.tables import otTables as ot
from fontTools.varLib.builder import buildVarRegionList, buildVarData, buildVarStore, buildVarIdxMap

HERE = Path(__file__).resolve().parent
OUTPUT = HERE / "KalligraphieVarVVAR.ttf"
GLYPHS = [".notdef", "A"]
# Fixed LONGDATETIME so the generated binary is byte-reproducible.
FIXED_DATE = 3_600_000_000
# Glyph `A` (gid 1) gains 200 units of advance height at the axis maximum.
ADVANCE_HEIGHT_DELTAS = [[0], [200]]


def triangle_glyph():
    pen = TTGlyphPen(None)
    pen.moveTo((100, 0))
    pen.lineTo((500, 700))
    pen.lineTo((900, 0))
    pen.closePath()
    return pen.glyph()


def build() -> bytes:
    fb = FontBuilder(1000, isTTF=True)
    fb.updateHead(created=FIXED_DATE, modified=FIXED_DATE, fontRevision=1.0)
    fb.setupGlyphOrder(GLYPHS)
    fb.setupCharacterMap({0x41: "A"})
    fb.setupGlyf({".notdef": TTGlyphPen(None).glyph(), "A": triangle_glyph()})
    fb.setupHorizontalMetrics({g: (600, 50) for g in GLYPHS})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupVerticalMetrics({g: (1000, 100) for g in GLYPHS})
    fb.setupVerticalHeader(ascent=880, descent=-120)
    fb.setupNameTable({"familyName": "Kalligraphie Variable VVAR Fixture", "styleName": "Regular"})
    fb.setupOS2()
    fb.setupPost()
    fb.setupFvar(
        [("wght", 0, 0, 1000, "Weight")],
        [{"location": {"wght": 0}, "stylename": "Regular"}],
    )

    region_list = buildVarRegionList([{"wght": (0.0, 1.0, 1.0)}], ["wght"])
    var_data = buildVarData([0], ADVANCE_HEIGHT_DELTAS)
    store = buildVarStore(region_list, [var_data])

    vvar = ot.VVAR()
    vvar.Version = 0x00010000
    vvar.VarStore = store
    vvar.AdvHeightMap = buildVarIdxMap([0, 1], GLYPHS)
    vvar.TsbMap = None
    vvar.BsbMap = None
    vvar.VOrgMap = None
    table = newTable("VVAR")
    table.table = vvar
    fb.font["VVAR"] = table

    out = io.BytesIO()
    fb.save(out)
    return out.getvalue()


if __name__ == "__main__":
    data = build()
    OUTPUT.write_bytes(data)
    print(f"wrote {OUTPUT.name} ({len(data)} bytes)")
    print(f"sha256: {hashlib.sha256(data).hexdigest()}")
