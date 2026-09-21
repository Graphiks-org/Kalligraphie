#!/usr/bin/env python3
"""Build an audited CFF2 (OpenType/CFF2) fixture and its default-instance oracle.

The fixture is a real `.otf` whose `CFF2` table is produced by fontTools'
FontBuilder from the audited Liberation Sans outlines (OFL). The oracle is
produced by an independent fontTools RecordingPen on the built font, so it can
detect a regression in the portable CFF2 decoder.

This is a static CFF2 face (no variation store): it validates the CFF2 container,
FDArray/FDSelect and charstring route against an independent oracle. Variable
CFF2 `blend` deltas are covered by the synthetic unit tests.

Run from the repository root:
    python3 kalligraphie/src/jvmTest/resources/fonts/cff2-liberation/build_cff2_fixture.py
"""

from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.ttLib import TTFont

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / "liberation" / "LiberationSans-Regular.ttf"
OUTPUT_OTF = HERE / "LiberationSans-CFF2.otf"
OUTPUT_ORACLE = HERE / "A-outline.txt"

GLYPHS = ["A"]
UNITS_PER_EM = 2048


def build() -> None:
    source = TTFont(SOURCE)
    glyph_set = source.getGlyphSet()

    charstrings = {}
    for name in [".notdef"] + GLYPHS:
        pen = T2CharStringPen(None, glyph_set)
        glyph_set[name].draw(pen)
        charstrings[name] = pen.getCharString()

    glyph_order = [".notdef"] + GLYPHS
    builder = FontBuilder(UNITS_PER_EM, isTTF=False)
    builder.setupGlyphOrder(glyph_order)
    builder.setupCharacterMap({0x41: "A"})
    builder.setupHorizontalMetrics({name: (UNITS_PER_EM, 0) for name in glyph_order})
    builder.setupHorizontalHeader(ascent=1854, descent=-434)
    builder.setupNameTable(
        {
            "familyName": "Liberation Sans CFF2 Fixture",
            "styleName": "Regular",
            "uniqueFontIdentifier": "LiberationSans-CFF2-Fixture",
            "fullName": "Liberation Sans CFF2 Fixture Regular",
            "psName": "LiberationSansCFF2Fixture-Regular",
            "version": "1.0",
        }
    )
    builder.setupOS2()
    builder.setupPost()
    builder.setupCFF2(charstrings)
    builder.save(OUTPUT_OTF)

    verified = TTFont(OUTPUT_OTF)
    recorder = RecordingPen()
    verified.getGlyphSet()["A"].draw(recorder)
    lines = []
    for operator, points in recorder.value:
        if operator == "moveTo":
            (x, y), = points
            lines.append(f"M {round(x, 6)} {round(y, 6)}")
        elif operator == "lineTo":
            (x, y), = points
            lines.append(f"L {round(x, 6)} {round(y, 6)}")
        elif operator == "curveTo":
            (c1x, c1y), (c2x, c2y), (x, y) = points
            lines.append(
                f"C {round(c1x, 6)} {round(c1y, 6)} {round(c2x, 6)} {round(c2y, 6)} {round(x, 6)} {round(y, 6)}"
            )
        elif operator == "closePath":
            lines.append("Z")
    OUTPUT_ORACLE.write_text("\n".join(lines) + "\n")
    print(f"wrote {OUTPUT_OTF.name} and {OUTPUT_ORACLE.name} ({len(lines)} commands)")


if __name__ == "__main__":
    build()
