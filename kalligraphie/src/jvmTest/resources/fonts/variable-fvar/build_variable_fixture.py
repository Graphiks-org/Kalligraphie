"""Regenerates SyntheticVariable.ttf: a base TTF plus an fvar table only (no gvar)."""
from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables._f_v_a_r import Axis, NamedInstance
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / "liberation" / "LiberationSans-Regular.ttf"
OUTPUT = HERE / "SyntheticVariable.ttf"

# recalcTimestamp=False keeps the base font's head.modified, so regeneration is byte-deterministic.
font = TTFont(SOURCE, recalcTimestamp=False)
fvar = newTable("fvar")
fvar.majorVersion, fvar.minorVersion = 1, 0
opsz = Axis(); opsz.axisTag, opsz.minValue, opsz.defaultValue, opsz.maxValue, opsz.axisNameID = "opsz", 8, 14, 144, 256
wght = Axis(); wght.axisTag, wght.minValue, wght.defaultValue, wght.maxValue, wght.axisNameID = "wght", 100, 400, 900, 257
fvar.axes = [opsz, wght]
instance = NamedInstance(); instance.subfamilyNameID = 258; instance.flags = 0
instance.coordinates = {"opsz": 14, "wght": 700}
fvar.instances = [instance]
font["fvar"] = fvar
font.save(OUTPUT)
print("wrote", OUTPUT)
