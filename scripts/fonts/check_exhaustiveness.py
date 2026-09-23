#!/usr/bin/env python3
"""Checks the corpus against the catalog's claims and against the manifest's own table lists.

Two contracts are verified, both requiring fontTools:

* every significant table a corpus font *carries* is claimed by the catalog or excused
  (`claimed-tables.json`, the export of the Kotlin catalog, is the only authority on what is
  claimed);
* every `tables` list the manifest *declares* for a file is that file's real sfnt table
  directory — the declaration is data, and data that is never checked drifts.
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

# Pseudo-entries fontTools reports in a table directory without an sfnt tag of their own. They are
# never declared in the manifest, so reading one as a missing declaration would be a false alarm.
PSEUDO_TABLES = frozenset({"GlyphOrder"})


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


def sfnt_tags_of(tags) -> set[str]:
    """Returns the real sfnt tags of a fontTools table directory, pseudo-entries dropped."""
    return {tag for tag in tags if tag not in PSEUDO_TABLES}


def sfnt_table_directory(path: pathlib.Path) -> set[str]:
    """Returns every sfnt table tag the font or collection at [path] really carries.

    The whole directory is returned, cosmetic tables included, because the manifest records all
    of them; callers that care about the significant subset intersect the result themselves. For a
    collection the directory is the union over its faces.
    """
    from fontTools.ttLib import TTCollection, TTFont  # imported lazily so --help works without fontTools

    if path.suffix.lower() == ".ttc":
        with TTCollection(path, lazy=True) as collection:
            found: set[str] = set()
            for font in collection.fonts:
                found |= sfnt_tags_of(font.keys())
            return found
    font = TTFont(path, lazy=True)
    try:
        return sfnt_tags_of(font.keys())
    finally:
        font.close()


def compare_declared_tables(key: str, path: str, declared, real: set[str]) -> list[str]:
    """Compares the tables a manifest record declares with the sfnt tables the file carries.

    Both directions violate: a declaration nothing carries is stale, and a carried table the
    manifest does not name is invisible to every reader of the manifest. A `.ttc` compares against
    the union over its faces, which is what the manifest is documented to declare.
    """
    declared_set = set(declared or ())
    stale = [
        f"{key}: {path} declares the table {table} and the file does not carry it"
        for table in sorted(declared_set - real)
    ]
    undeclared = [
        f"{key}: {path} carries the table {table} and the manifest does not declare it"
        for table in sorted(real - declared_set)
    ]
    return stale + undeclared


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
                directory = sfnt_table_directory(path)
                tables |= directory & SIGNIFICANT_TABLES
                violations += compare_declared_tables(
                    key=family["key"],
                    path=record["path"],
                    declared=record.get("tables", []),
                    real=directory,
                )
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
