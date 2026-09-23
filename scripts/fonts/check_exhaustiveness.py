#!/usr/bin/env python3
"""Checks that every significant table a corpus font carries is claimed by the catalog.

Reads the claims exported from the Kotlin catalog (`claimed-tables.json`), so there is exactly one
authority on what is claimed: the model. Requires fontTools.
"""

from __future__ import annotations

import argparse
import base64
import json
import pathlib
import sys
import tempfile

import fetch_fonts

# Tables whose presence in a corpus font must be claimed by the catalog or explicitly allowlisted.
# Cosmetic or purely informational tables are left out deliberately: claiming them would add noise
# without adding an expectation.
SIGNIFICANT_TABLES = {
    "avar", "BASE", "CBDT", "CBLC", "CFF ", "CFF2", "cmap", "COLR", "CPAL", "cvar", "EBDT",
    "EBLC", "fvar", "GDEF", "glyf", "GPOS", "gvar", "GSUB", "head", "hhea", "hmtx", "HVAR",
    "kern", "loca", "maxp", "morx", "MVAR", "name", "OS/2", "post", "sbix", "STAT", "SVG ",
    "vhea", "vmtx", "VORG", "VARC", "VVAR",
}


def compare(
    key: str,
    real_tables: set[str],
    claimed: set[str],
    allow_unclaimed: dict[str, str],
    excuse_all: bool = False,
) -> list[str]:
    """Compares the claimed tables of one font with the tables it really carries.

    A claimed table is accepted however it is excused. [excuse_all] covers the families no
    catalog entry references at all, where every unclaimed table shares one reason. [real_tables]
    may be wider than the significant set: anything outside it is ignored, so a cosmetic table
    can never produce a violation.
    """
    violations: list[str] = []
    significant = real_tables & SIGNIFICANT_TABLES
    for table in sorted(significant):
        if table in claimed or excuse_all or table in allow_unclaimed:
            continue
        violations.append(f"{key}: {table} is carried by the font and claimed nobody; claim it or allowlist it")
    for table in sorted(claimed - real_tables):
        violations.append(f"{key}: {table} is claimed but the font does not carry it")
    return violations


def merged_excuses(claims: dict, key: str) -> tuple[dict[str, str], bool]:
    """Returns this family's excuses merged with the global ones, and whether the whole family is excused.

    The global entry `"*"` excuses the structural tables for the families where no entry claims
    one; an entry keyed `"*"` *inside* a family's map excuses the family whole, and says why it
    stays outside the claimed perimeter. The family's own reason wins on a table both cover.
    """
    global_excuses = claims["allowUnclaimed"].get("*", {})
    family_excuses = claims["allowUnclaimed"].get(key, {})
    excuse_all = "*" in family_excuses
    merged = dict(global_excuses)
    merged.update({table: reason for table, reason in family_excuses.items() if table != "*"})
    return merged, excuse_all


def real_tables_of(path: pathlib.Path, keys: set[str]) -> set[str]:
    """Returns the significant tables of the font or collection at [path], using fontTools."""
    from fontTools.ttLib import TTCollection, TTFont  # imported lazily so --help works without fontTools

    found: set[str] = set()
    if path.suffix.lower() == ".ttc":
        with TTCollection(path, lazy=True) as collection:
            for font in collection.fonts:
                found |= {tag for tag in font.keys() if tag in keys}
        return found
    font = TTFont(path, lazy=True)
    try:
        return {tag for tag in font.keys() if tag in keys}
    finally:
        font.close()


def decoded_font_path(path: pathlib.Path, scratch: pathlib.Path) -> pathlib.Path:
    """Returns [path] itself, or the decoded font when [path] is a base64-wrapped fixture.

    The committed base64 fixtures are transport encodings, not fonts: decoded outside the
    repository, into a scratch directory, so nothing is written next to the manifest.
    """
    if path.suffix not in {".b64", ".base64"}:
        return path
    payload = base64.b64decode(path.read_bytes())
    scratch.mkdir(parents=True, exist_ok=True)
    decoded = scratch / (path.stem if path.suffix == ".b64" else path.stem + ".ttf")
    decoded.write_bytes(payload)
    return decoded


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--corpus", default="scripts/fonts/corpus.json")
    parser.add_argument("--claims", default="kalligraphie/e2e/src/jvmTest/resources/catalog/claimed-tables.json")
    arguments = parser.parse_args(argv)

    root = pathlib.Path(__file__).resolve().parents[2]
    manifest = fetch_fonts.load_manifest(root / arguments.corpus)
    with open(root / arguments.claims, encoding="utf-8") as handle:
        claims = json.load(handle)
    if claims.get("schema") != "kalligraphie.e2e-claims/v1":
        raise SystemExit(f"{arguments.claims}: unexpected schema {claims.get('schema')!r}")

    violations: list[str] = []
    with tempfile.TemporaryDirectory() as scratch:
        scratch_path = pathlib.Path(scratch)
        for family in manifest["families"]:
            tables: set[str] = set()
            for record in family["files"]:
                path = decoded_font_path(root / record["path"], scratch_path)
                tables |= real_tables_of(path, SIGNIFICANT_TABLES)
            if not tables:
                violations.append(f"{family['key']}: no font file yielded any significant table; the lint would be blind here")
                continue
            allow_unclaimed, excuse_all = merged_excuses(claims, family["key"])
            violations += compare(
                key=family["key"],
                real_tables=tables,
                claimed=set(claims["fonts"].get(family["key"], [])),
                allow_unclaimed=allow_unclaimed,
                excuse_all=excuse_all,
            )

    for violation in violations:
        print(f"error: {violation}", file=sys.stderr)
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
