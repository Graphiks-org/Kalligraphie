"""Generate and independently audit real-face TTC specimens; no production decoder used."""
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys

import fontTools
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTCollection, TTFont

root = Path(sys.argv[1])
destination = Path(sys.argv[2])
destination.mkdir(parents=True, exist_ok=True)
inputs = [
    root / "kalligraphie/src/jvmTest/resources/fonts/liberation/LiberationSans-Regular.ttf",
    root / "kalligraphie/src/jvmTest/resources/fonts/amiri/Amiri-Regular.ttf",
]
collection = TTCollection()
collection.fonts = [TTFont(path, recalcTimestamp=False) for path in inputs]
v1 = destination / "LiberationAmiri.ttc"
collection.save(v1, shareTables=True)
collection.close()

# FontTools writes TTC v1. Convert to v2 by adding the absent DSIG fields and
# shifting every absolute directory/table offset. Table-relative data is unchanged.
raw = bytearray(v1.read_bytes())
face_count = struct.unpack_from(">I", raw, 8)[0]
insertion = 12 + 4 * face_count
old_directories = list(struct.unpack_from(f">{face_count}I", raw, 12))
v2_bytes = raw[:insertion] + bytearray(12) + raw[insertion:]
struct.pack_into(">I", v2_bytes, 4, 0x00020000)
for index, directory in enumerate(old_directories):
    shifted_directory = directory + 12
    struct.pack_into(">I", v2_bytes, 12 + 4 * index, shifted_directory)
    table_count = struct.unpack_from(">H", v2_bytes, shifted_directory + 4)[0]
    for table_index in range(table_count):
        position = shifted_directory + 12 + 16 * table_index + 8
        old_table = struct.unpack_from(">I", v2_bytes, position)[0]
        struct.pack_into(">I", v2_bytes, position, old_table + 12)
v2 = destination / "LiberationAmiri-v2.ttc"
v2.write_bytes(v2_bytes)

audit = {"python": sys.version, "fontTools": fontTools.__version__, "inputs": [], "collections": []}
for path in inputs:
    audit["inputs"].append({"file": path.name, "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
for path in (v1, v2):
    captured = TTCollection(path)
    entry = {"file": path.name, "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "faces": []}
    for face_index, font in enumerate(captured.fonts):
        glyph_set = font.getGlyphSet()
        glyph_order = font.getGlyphOrder()
        name = font.getBestCmap()[0x41]
        bounds = BoundsPen(glyph_set)
        recording = RecordingPen()
        glyph_set[name].draw(bounds)
        glyph_set[name].draw(recording)
        command = ["hb-shape", f"--face-index={face_index}", "--direction=ltr", "--script=Latn", "--language=en", "--no-glyph-names", str(path), "Affi"]
        entry["faces"].append({
            "index": face_index,
            "family": font["name"].getDebugName(1),
            "unitsPerEm": font["head"].unitsPerEm,
            "glyphCount": len(glyph_order),
            "A": {"glyphId": glyph_order.index(name), "hmtx": font["hmtx"][name], "bounds": bounds.bounds, "headerBounds": [getattr(font["glyf"][name], field) for field in ("xMin", "yMin", "xMax", "yMax")], "contours": font["glyf"][name].numberOfContours, "outline": recording.value},
            "shapingCommand": command[:-2] + [path.name, "Affi"],
            "shaping": subprocess.check_output(command, text=True).strip(),
        })
    if path == v1:
        for face in entry["faces"]:
            rows = []
            for operation, points in face["A"]["outline"]:
                if operation in ("moveTo", "lineTo"):
                    rows.append(("M" if operation == "moveTo" else "L") + " " + " ".join(format(v, "g") for v in points[0]))
                elif operation == "qCurveTo":
                    for index, control in enumerate(points[:-1]):
                        endpoint = points[-1] if index == len(points) - 2 else tuple((a + b) / 2 for a, b in zip(control, points[index + 1]))
                        rows.append("Q " + " ".join(format(v, "g") for v in (*control, *endpoint)))
                elif operation == "closePath":
                    rows.append("C")
                else:
                    raise ValueError(operation)
            name = "liberation" if face["index"] == 0 else "amiri"
            (destination / (name + "-A-outline.txt")).write_text("\n".join(rows) + "\n")
    captured.close()
    audit["collections"].append(entry)
(destination / "audit.json").write_text(json.dumps(audit, indent=2) + "\n")
summary = json.loads(json.dumps(audit))
for entry in summary["collections"]:
    for face in entry["faces"]:
        face["A"]["outlineFirst"] = face["A"].pop("outline")[:2]
print(json.dumps(summary, indent=2))
