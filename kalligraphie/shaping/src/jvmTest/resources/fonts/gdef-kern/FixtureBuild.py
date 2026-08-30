#!/usr/bin/env python3
"""Builds the deterministic GDEF-plus-GPOS shaping fixture."""

from __future__ import annotations

import argparse
from pathlib import Path

from fontTools import __version__ as fonttools_version
from fontTools.feaLib.builder import addOpenTypeFeaturesFromString
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen


EXPECTED_FONTTOOLS_VERSION = "4.59.2"
UNITS_PER_EM = 1000
GLYPH_ORDER = [".notdef", "f", "i", "f_i", "V"]
HORIZONTAL_METRICS = {
    ".notdef": (500, 0),
    "f": (500, 0),
    "i": (500, 0),
    "f_i": (900, 0),
    "V": (600, 0),
}


def glyph(width: int):
    pen = TTGlyphPen(None)
    pen.moveTo((50, 0))
    pen.lineTo((width - 50, 0))
    pen.lineTo((width - 50, 700))
    pen.lineTo((50, 700))
    pen.closePath()
    return pen.glyph()


def build(output: Path, feature_source: Path) -> None:
    if fonttools_version != EXPECTED_FONTTOOLS_VERSION:
        raise RuntimeError(
            f"This fixture requires fontTools {EXPECTED_FONTTOOLS_VERSION}, got {fonttools_version}."
        )

    builder = FontBuilder(UNITS_PER_EM, isTTF=True)
    builder.setupGlyphOrder(GLYPH_ORDER)
    builder.setupCharacterMap({0x0066: "f", 0x0069: "i", 0x0056: "V"})
    builder.setupGlyf({name: glyph(advance) for name, (advance, _) in HORIZONTAL_METRICS.items()})
    builder.setupHorizontalMetrics(HORIZONTAL_METRICS)
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable(
        {
            "familyName": "Kalligraphie GDEF Kerning Fixture",
            "styleName": "Regular",
            "uniqueFontIdentifier": "KalligraphieGdefKerningFixture-Regular",
            "fullName": "Kalligraphie GDEF Kerning Fixture Regular",
            "psName": "KalligraphieGdefKerningFixture-Regular",
            "version": "Version 1.000",
            "copyright": "Dedicated to the public domain under CC0 1.0.",
        },
    )
    builder.setupOS2(
        sTypoAscender=800,
        sTypoDescender=-200,
        usWinAscent=800,
        usWinDescent=200,
    )
    builder.setupPost(keepGlyphNames=True)
    builder.setupMaxp()
    builder.setupHead()
    builder.font.recalcTimestamp = False
    builder.font["head"].created = 0
    builder.font["head"].modified = 0
    addOpenTypeFeaturesFromString(builder.font, feature_source.read_text(encoding="utf-8"))
    builder.font.save(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--features",
        type=Path,
        default=Path(__file__).with_name("FixtureBuild.fea"),
    )
    arguments = parser.parse_args()
    build(arguments.output, arguments.features)


if __name__ == "__main__":
    main()
