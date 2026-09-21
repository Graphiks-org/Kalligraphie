#!/usr/bin/env python3
"""Build a variable CFF2 fixture (real `blend` + ItemVariationStore) and its oracle.

Two synthetic masters differ only in the apex height of glyph `A`. fontTools
`varLib.build` merges them into a variable CFF2 whose charstring contains a real
`blend` operator over an ItemVariationStore region. The default instance applies
a zero scalar, so the audited default outline keeps the master-0 apex (200): a
decoder that mis-computes the default region scalar would instead report 300.

This differs from the static CFF2 fixture: it exercises `vsindex`/`blend` and the
VariationStore default-instance scalars on real compiled data rather than the
hand-built synthetic varstore in the unit tests.

Run from the repository root:
    python3 kalligraphie/src/jvmTest/resources/fonts/cff2-variable/build_cff2_variable_fixture.py
"""

from pathlib import Path

from fontTools.designspaceLib import AxisDescriptor, DesignSpaceDocument, SourceDescriptor
from fontTools.fontBuilder import FontBuilder
from fontTools.misc.psCharStrings import T2CharString
from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTFont
from fontTools import varLib

HERE = Path(__file__).resolve().parent
OUTPUT_OTF = HERE / "SyntheticVariable-CFF2.otf"
OUTPUT_ORACLE = HERE / "A-outline.txt"
UNITS_PER_EM = 1000
GLYPH_ORDER = [".notdef", "A"]


def make_master(path: Path, apex: int) -> None:
    triangle = T2CharString(program=[0, 0, "rmoveto", 100, 0, "rlineto", -100, apex, "rlineto"])
    notdef = T2CharString(program=[0, 0, "rmoveto", 0, 0, "rlineto"])
    builder = FontBuilder(UNITS_PER_EM, isTTF=False)
    builder.setupGlyphOrder(GLYPH_ORDER)
    builder.setupCharacterMap({0x41: "A"})
    builder.setupHorizontalMetrics({name: (UNITS_PER_EM, 0) for name in GLYPH_ORDER})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable(
        {
            "familyName": "Synthetic Variable CFF2 Fixture",
            "styleName": "Regular",
            "psName": "SyntheticVariableCFF2Fixture-Regular",
            "fullName": "Synthetic Variable CFF2 Fixture Regular",
            "uniqueFontIdentifier": "SyntheticVariableCFF2Fixture",
            "version": "1.0",
        }
    )
    builder.setupOS2()
    builder.setupPost()
    builder.setupCFF2({".notdef": notdef, "A": triangle})
    builder.save(path)


def build() -> None:
    default_master = HERE / "_master_default.otf"
    bold_master = HERE / "_master_bold.otf"
    make_master(default_master, apex=200)
    make_master(bold_master, apex=300)

    designspace = DesignSpaceDocument()
    axis = AxisDescriptor()
    axis.name = "Weight"
    axis.tag = "wght"
    axis.minimum = 0
    axis.default = 0
    axis.maximum = 1000
    designspace.addAxis(axis)
    default_source = SourceDescriptor()
    default_source.name = "default"
    default_source.path = str(default_master)
    default_source.location = {"Weight": 0}
    designspace.addSource(default_source)
    bold_source = SourceDescriptor()
    bold_source.name = "bold"
    bold_source.path = str(bold_master)
    bold_source.location = {"Weight": 1000}
    designspace.addSource(bold_source)

    variable, _, _ = varLib.build(designspace)
    variable.save(OUTPUT_OTF)
    default_master.unlink()
    bold_master.unlink()

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
    print(f"wrote {OUTPUT_OTF.name} and {OUTPUT_ORACLE.name}")


if __name__ == "__main__":
    build()
