#!/usr/bin/env python3
"""Acquires and verifies the font corpus described by corpus.json.

Modes:
  --check        offline verification of the committed files (hash, size, licences, provenance)
  --fetch        downloads the pinned sources and writes them in place (network, local use only)
  --key KEY      restricts --fetch to one family
  --provenance   checks that every family still has its PROVENANCE.md and licence file
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import pathlib
import sys
import urllib.request

# Licences whose terms allow redistributing the font file in this repository.
# A new licence requires a human review first; the check below fails closed.
# CC-BY-4.0 covers the two emoji families (emoji-two-colr-v0, twemoji-svginot-glyph5);
# MIT is the repository licence and covers the fixtures generated in-tree.
ALLOWED_LICENSES = frozenset(
    {
        "Apache-2.0",
        "BSD-3-Clause",
        "CC0-1.0",
        "CC-BY-4.0",
        "DejaVu",
        "MIT",
        "OFL-1.1",
        "Unlicense",
    }
)

SCHEMA = "kalligraphie.font-corpus/v1"
SIGNIFICANT_LICENSE_FIELD = "license"

# The fixture tree the manifest describes: one directory per family key.
FIXTURES_ROOT = "test-fixtures/fonts"

# The font artifacts a family directory may commit: a `.ttf`, `.otf` or `.ttc`, or the base64
# wrapper of one. Every other file of a family directory (PROVENANCE.md, a licence text, the
# builder script, an audit record, a recorded outline oracle) is a companion, not an artifact.
FONT_SUFFIXES = frozenset({".ttf", ".otf", ".ttc", ".b64", ".base64"})


def load_manifest(path: pathlib.Path) -> dict:
    """Loads and shape-checks the corpus manifest."""
    with open(path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    if manifest.get("schema") != SCHEMA:
        raise SystemExit(f"{path}: expected schema {SCHEMA}, found {manifest.get('schema')!r}")
    return manifest


def sha256_of(path: pathlib.Path) -> str:
    """Returns the SHA-256 of a file."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_files(manifest: dict, root: pathlib.Path) -> list[str]:
    """Verifies every declared file and every family's metadata. Returns the error list."""
    errors: list[str] = []
    for family in manifest["families"]:
        key = family["key"]
        license_id = family.get("license")
        if license_id not in ALLOWED_LICENSES:
            errors.append(f"{key}: licence {license_id!r} is not in the allowed set; review it before adding")
        if family.get("synthetic") and not family.get("builtBy"):
            errors.append(f"{key}: a synthetic family must name its builtBy script")
        if license_id in ALLOWED_LICENSES:
            # A redistributed font ships its licence: the declaration is mandatory, and the
            # file it names must exist. `--provenance` re-checks the same field.
            license_file = family.get("licenseFile")
            if not license_file:
                errors.append(f"{key}: no licenseFile declared")
            elif not (root / license_file).exists():
                errors.append(f"{key}: license file {license_file} is missing")
        for record in family["files"]:
            path = root / record["path"]
            if not path.exists():
                errors.append(f"{key}: {record['path']} is missing")
                continue
            size = path.stat().st_size
            if size != record["sizeBytes"]:
                errors.append(f"{key}: {record['path']} size is {size}, manifest expects {record['sizeBytes']}")
            digest = sha256_of(path)
            if digest != record["sha256"]:
                errors.append(f"{key}: {record['path']} sha256 is {digest}, manifest expects {record['sha256']}")
            if not family.get("synthetic") and not record.get("url"):
                errors.append(f"{key}: {record['path']} is not synthetic and declares no url")
            if not family.get("synthetic") and not (record.get("rawUrl") or record.get("fetchNote")):
                errors.append(
                    f"{key}: {record['path']} needs a rawUrl or a fetchNote explaining the manual re-download"
                )
    return errors


def committed_fonts(root: pathlib.Path, key: str) -> set[str]:
    """Returns the repository-relative paths of the font artifacts of one family directory."""
    directory = root / FIXTURES_ROOT / key
    if not directory.is_dir():
        return set()
    return {
        f"{FIXTURES_ROOT}/{key}/{path.name}"
        for path in directory.iterdir()
        if path.is_file() and path.suffix.lower() in FONT_SUFFIXES
    }


def coverage_errors(manifest: dict, root: pathlib.Path) -> list[str]:
    """Checks that the manifest and the committed fixture tree describe exactly the same files.

    Every other check of the corpus walks the families the manifest declares, so a directory
    dropped into `test-fixtures/fonts/` by hand, or a font file added to a declared family, would
    otherwise never be looked at. Both directions are errors, so a directory the manifest does not
    know and a family it declares with no directory behind it are equally loud.
    """
    errors: list[str] = []
    fixtures = root / FIXTURES_ROOT
    directories = {path.name for path in fixtures.iterdir() if path.is_dir()} if fixtures.is_dir() else set()
    keys = {family["key"] for family in manifest["families"]}
    errors += [
        f"{FIXTURES_ROOT}/{key}: committed and no family of the manifest uses this directory"
        for key in sorted(directories - keys)
    ]
    errors += [
        f"{key}: declared as a family and no directory of {FIXTURES_ROOT}/ carries it"
        for key in sorted(keys - directories)
    ]
    for family in manifest["families"]:
        key = family["key"]
        declared = {record["path"] for record in family["files"]}
        committed = committed_fonts(root, key)
        errors += [
            f"{key}: {path} is declared by the manifest and not committed"
            for path in sorted(declared - committed)
        ]
        errors += [
            f"{key}: {path} is committed and the manifest declares no such file"
            for path in sorted(committed - declared)
        ]
    return errors


def decode_base64(payload: bytes) -> bytes | None:
    """Returns the base64 decoding of a payload, or None when the payload is not base64.

    Transport whitespace is ignored (gitiles and `.b64` artifacts wrap their output); the
    digest comparison is what accepts or rejects the result, never the decoding alone.
    """
    try:
        return base64.b64decode(b"".join(payload.split()), validate=True)
    except (binascii.Error, ValueError):
        return None


def fetch(manifest: dict, root: pathlib.Path, only_key: str | None) -> int:
    """Downloads the declared sources, refusing to overwrite a file whose hash already matches."""
    written = 0
    for family in manifest["families"]:
        if only_key and family["key"] != only_key:
            continue
        if family.get("synthetic"):
            if only_key:
                raise SystemExit(
                    f"{family['key']}: synthetic families are rebuilt by {family.get('builtBy')}, not fetched"
                )
            print(f"skipping {family['key']}: synthetic, rebuilt by {family.get('builtBy')}")
            continue
        for record in family["files"]:
            path = root / record["path"]
            if path.exists() and sha256_of(path) == record["sha256"]:
                continue
            if not record.get("rawUrl"):
                print(f"skipping {record['path']}: no rawUrl, {record['fetchNote']}")
                continue
            print(f"fetching {record['rawUrl']}")
            with urllib.request.urlopen(record["rawUrl"]) as response:  # noqa: S310 - pinned, reviewed URLs
                payload = response.read()
            digest = hashlib.sha256(payload).hexdigest()
            if digest == record["sha256"]:
                committed = payload
            else:
                # The payload may only be a transport encoding of the committed file: the
                # pinned source of skia-ebdt-format1 is served base64. Two interpretations
                # are allowed, the payload verbatim and its base64 decoding; nothing is guessed.
                decoded = decode_base64(payload)
                if decoded is None:
                    raise SystemExit(
                        f"{record['path']}: the payload hashes to {digest} and is not base64, "
                        f"but the manifest pins {record['sha256']}"
                    )
                decoded_digest = hashlib.sha256(decoded).hexdigest()
                if decoded_digest != record["sha256"]:
                    raise SystemExit(
                        f"{record['path']}: neither the payload ({digest}) nor its base64 decoding "
                        f"({decoded_digest}) matches the pinned {record['sha256']}"
                    )
                committed = decoded
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(committed)
            written += 1
    return written


def provenance(manifest: dict, root: pathlib.Path) -> list[str]:
    """Checks that every family directory still carries its PROVENANCE.md and licence file."""
    errors: list[str] = []
    for family in manifest["families"]:
        directory = (root / family["files"][0]["path"]).parent
        if not (directory / "PROVENANCE.md").exists():
            errors.append(f"{family['key']}: PROVENANCE.md is missing in {directory.name}/")
        license_file = family.get("licenseFile")
        if license_file and not (root / license_file).exists():
            errors.append(f"{family['key']}: licence file {license_file} is missing")
    return errors


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="verify committed files offline")
    parser.add_argument("--fetch", action="store_true", help="download pinned sources")
    parser.add_argument("--key", help="restrict --fetch to one family key")
    parser.add_argument("--provenance", action="store_true", help="verify PROVENANCE.md presence")
    parser.add_argument("--manifest", default="scripts/fonts/corpus.json")
    arguments = parser.parse_args(argv)

    root = pathlib.Path(__file__).resolve().parents[2]
    manifest = load_manifest(root / arguments.manifest)
    errors: list[str] = []
    if arguments.check:
        errors += check_files(manifest, root)
    if arguments.provenance:
        errors += provenance(manifest, root)
    if arguments.fetch:
        print(f"wrote {fetch(manifest, root, arguments.key)} file(s)")
    if not (arguments.check or arguments.provenance or arguments.fetch):
        parser.print_help()
        return 0
    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
