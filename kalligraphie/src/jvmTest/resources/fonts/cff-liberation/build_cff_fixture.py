#!/usr/bin/env python3
"""Build an audited CFF (OpenType/CFF1) fixture and its outline oracle.

The fixture is a real `.otf` produced by fontTools' FontBuilder from the audited
Liberation Sans outlines (OFL). The oracle is produced by an independent
fontTools RecordingPen on the built font, not by Kalligraphie, so it can detect a
regression in the portable CFF decoder.

Run from the repository root:
    python3 kalligraphie/src/jvmTest/resources/fonts/cff-liberation/build_cff_fixture.py
"""

from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.ttLib import TTFont

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / "liberation" / "LiberationSans-Regular.ttf"
OUTPUT_OTF = HERE / "LiberationSans-CFF.otf"
OUTPUT_ORACLE = HERE / "A-outline.txt"

GLYPHS = ["A"]
UNITS_PER_EM = 2048


def build() -> None:
    source = TTFont(SOURCE)
    glyph_set = source.getGlyphSet()

    charstrings = {}
    for name in [".notdef"] + GLYPHS:
        pen = T2CharStringPen(UNITS_PER_EM, glyph_set)
        glyph_set[name].draw(pen)
        charstrings[name] = pen.getCharString()

    glyph_order = [".notdef"] + GLYPHS
    builder = FontBuilder(UNITS_PER_EM, isTTF=False)
    builder.setupGlyphOrder(glyph_order)
    builder.setupCharacterMap({0x41: "A"})
    advance_widths = {name: (UNITS_PER_EM, 0) for name in glyph_order}
    builder.setupHorizontalMetrics(advance_widths)
    builder.setupHorizontalHeader(ascent=1854, descent=-434)
    builder.setupNameTable(
        {
            "familyName": "Liberation Sans CFF Fixture",
            "styleName": "Regular",
            "uniqueFontIdentifier": "LiberationSans-CFF-Fixture",
            "fullName": "Liberation Sans CFF Fixture Regular",
            "psName": "LiberationSansCFFFixture-Regular",
            "version": "1.0",
        }
    )
    builder.setupOS2()
    builder.setupPost()
    builder.setupCFF(
        "LiberationSansCFFFixture-Regular",
        {"FullName": "Liberation Sans CFF Fixture", "FamilyName": "Liberation Sans CFF Fixture"},
        charstrings,
        {},
    )
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
