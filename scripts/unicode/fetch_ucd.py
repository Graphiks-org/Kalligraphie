"""Acquire and verify the Unicode Character Database sources of the portable Unicode tables.

`scripts/unicode/ucd.json` is the authority of record for the UCD files the table generator of
`scripts/unicode/generate_ucd_tables.py` reads. Every source pins its exact upstream URL, the
SHA-256 of the payload, and where that payload is read from: three files are already committed in
this repository as conformance oracles of `:kalligraphie:unicode`, and the rest are downloaded into
the untracked `scripts/unicode/.cache/` directory. The digest, never the URL, is what accepts a
payload.

Commands:

    python3 scripts/unicode/fetch_ucd.py --check              # offline: verify every source on disk
    python3 scripts/unicode/fetch_ucd.py --fetch              # download the pinned sources
    python3 scripts/unicode/fetch_ucd.py --fetch --key line-break
    python3 -m unittest discover -s scripts/unicode/tests -v  # unit tests, no network

`--check` never reaches the network and draws a deliberate line: a payload whose SHA-256 is not the
pinned one is an error, while a cache entry that is simply absent is a note, because `--fetch` is
what obtains it. `--fetch` refuses any payload that does not hash to the pinned digest.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import urllib.error
import urllib.request

SCHEMA = "kalligraphie.ucd-sources/v1"

CACHE_DIRNAME = ".cache"

# The properties the generator may claim a source provides. A closed set, so a typo in the manifest
# cannot silently declare a property nothing reads.
ALLOWED_PROVIDES = frozenset(
    {
        "Bidi_Class",
        "Bidi_Paired_Bracket",
        "Bidi_Paired_Bracket_Type",
        "Extended_Pictographic",
        "Grapheme_Cluster_Break",
        "ISO-15924-short-names",
        "Indic_Conjunct_Break",
        "Line_Break",
        "Script",
        "Script_Extensions",
        "Vertical_Orientation",
        "language-to-script",
    }
)

HEX_DIGITS = frozenset("0123456789abcdef")


def load_manifest(path: pathlib.Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def sha256_of(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_digest(value: object) -> bool:
    return isinstance(value, str) and len(value) == 64 and set(value) <= HEX_DIGITS


def manifest_errors(manifest: dict) -> list[str]:
    """Structural defects of the manifest itself, which make `--check` meaningless."""
    errors: list[str] = []
    if manifest.get("schema") != SCHEMA:
        errors.append(f"unexpected schema {manifest.get('schema')!r}, expected {SCHEMA!r}")
    if not isinstance(manifest.get("unicodeVersion"), str) or not manifest["unicodeVersion"]:
        errors.append("the manifest requires a non-blank unicodeVersion")
    sources = manifest.get("sources")
    if not isinstance(sources, list) or not sources:
        return errors + ["the manifest requires a non-empty sources array"]

    keys = [source.get("key") for source in sources]
    if keys != sorted(keys):
        errors.append("sources must be sorted by key")
    duplicates = {key for key in keys if keys.count(key) > 1}
    if duplicates:
        errors.append(f"duplicate source keys: {sorted(duplicates)}")

    for source in sources:
        key = source.get("key")
        where = f"source {key!r}"
        if not isinstance(key, str) or not key:
            errors.append(f"{where}: a source requires a non-blank key")
        url = source.get("url")
        if not isinstance(url, str) or not url.startswith("https://"):
            errors.append(f"{where}: url must be an https URL")
        if not is_digest(source.get("sha256")):
            errors.append(f"{where}: sha256 must be 64 lowercase hexadecimal digits")
        size = source.get("sizeBytes")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            errors.append(f"{where}: sizeBytes must be a positive integer")
        repository_path = source.get("repositoryPath")
        cache_file = source.get("cacheFile")
        if (repository_path is None) == (cache_file is None):
            errors.append(f"{where}: exactly one of repositoryPath and cacheFile must be set")
        if repository_path is not None and (not isinstance(repository_path, str) or not repository_path):
            errors.append(f"{where}: repositoryPath must be a non-blank path or null")
        if cache_file is not None:
            if not isinstance(cache_file, str) or not cache_file:
                errors.append(f"{where}: cacheFile must be a non-blank file name or null")
            elif pathlib.PurePosixPath(cache_file).name != cache_file:
                errors.append(f"{where}: cacheFile must be a bare file name, not a path")
        if "kdocFileName" not in source:
            errors.append(f"{where}: kdocFileName must be declared, as a published name or null")
        elif source["kdocFileName"] is not None and (
            not isinstance(source["kdocFileName"], str) or not source["kdocFileName"]
        ):
            errors.append(f"{where}: kdocFileName must be a non-blank name or null")
        provides = source.get("provides")
        if not isinstance(provides, list) or not provides:
            errors.append(f"{where}: provides must be a non-empty array")
        else:
            unknown = sorted(set(provides) - ALLOWED_PROVIDES)
            if unknown:
                errors.append(f"{where}: unknown provides values {unknown}")
            if provides != sorted(provides):
                errors.append(f"{where}: provides must be sorted")
        if not isinstance(source.get("unicodeVersion"), str) or not source["unicodeVersion"]:
            errors.append(f"{where}: a source requires a non-blank unicodeVersion")
        if not isinstance(source.get("retrieved"), str) or not source["retrieved"]:
            errors.append(f"{where}: a source requires a retrieval date")
        if not isinstance(source.get("license"), str) or not source["license"]:
            errors.append(f"{where}: a source requires a licence identifier")
    return errors


def resolve(source: dict, root: pathlib.Path, cache_dir: pathlib.Path) -> pathlib.Path:
    """The path of [source]'s payload: the committed copy when it has one, the cache otherwise."""
    repository_path = source["repositoryPath"]
    return root / repository_path if repository_path is not None else cache_dir / source["cacheFile"]


def check_sources(
    manifest: dict, root: pathlib.Path, cache_dir: pathlib.Path
) -> tuple[list[str], list[str]]:
    """Verify every source present on disk. Returns the errors, then the notes."""
    errors: list[str] = []
    notes: list[str] = []
    for source in manifest["sources"]:
        path = resolve(source, root, cache_dir)
        if not path.is_file():
            if source["repositoryPath"] is not None:
                errors.append(f"{source['key']}: committed source {path} is missing")
            else:
                notes.append(f"{source['key']}: not in the cache, run --fetch to obtain it")
            continue
        actual = sha256_of(path)
        if actual != source["sha256"]:
            errors.append(
                f"{source['key']}: {path} hashes to {actual}, but the manifest pins {source['sha256']}"
            )
            continue
        size = path.stat().st_size
        if size != source["sizeBytes"]:
            errors.append(f"{source['key']}: {path} is {size} bytes, but the manifest pins {source['sizeBytes']}")
    return errors, notes


def coverage_errors(manifest: dict, cache_dir: pathlib.Path) -> list[str]:
    """The manifest and the cache directory describe exactly the same payloads, both ways."""
    errors: list[str] = []
    declared = {source["cacheFile"] for source in manifest["sources"] if source["cacheFile"] is not None}
    if not cache_dir.is_dir():
        return errors
    present = {entry.name for entry in cache_dir.iterdir() if entry.is_file()}
    for phantom in sorted(present - declared):
        errors.append(f"{cache_dir / phantom} is not declared by any source of the manifest")
    return errors


def fetch(manifest: dict, root: pathlib.Path, cache_dir: pathlib.Path, only_key: str | None) -> int:
    """Download the cached sources, verifying every payload against its pinned digest."""
    if only_key is not None and only_key not in {source["key"] for source in manifest["sources"]}:
        print(f"unknown source key {only_key!r}", file=sys.stderr)
        return 2
    cache_dir.mkdir(parents=True, exist_ok=True)
    failures = 0
    for source in manifest["sources"]:
        if only_key is not None and source["key"] != only_key:
            continue
        if source["repositoryPath"] is not None:
            print(f"{source['key']}: committed in the repository, not fetched")
            continue
        target = cache_dir / source["cacheFile"]
        print(f"{source['key']}: fetching {source['url']}")
        try:
            with urllib.request.urlopen(source["url"], timeout=120) as response:
                payload = response.read()
        except (urllib.error.URLError, TimeoutError) as error:
            print(f"{source['key']}: download failed: {error}", file=sys.stderr)
            failures += 1
            continue
        actual = hashlib.sha256(payload).hexdigest()
        if actual != source["sha256"]:
            print(
                f"{source['key']}: payload hashes to {actual}, but the manifest pins "
                f"{source['sha256']}; refusing to write it",
                file=sys.stderr,
            )
            failures += 1
            continue
        target.write_bytes(payload)
        print(f"{source['key']}: wrote {target} ({len(payload)} bytes)")
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="verify every source on disk, offline")
    parser.add_argument("--fetch", action="store_true", help="download the pinned sources into the cache")
    parser.add_argument("--key", help="restrict --fetch to one source key")
    parser.add_argument("--root", default=".", help="repository root the manifest paths resolve against")
    parser.add_argument("--manifest", default="scripts/unicode/ucd.json", help="manifest to read")
    args = parser.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    manifest_path = root / args.manifest
    if not manifest_path.is_file():
        print(f"manifest {manifest_path} is missing", file=sys.stderr)
        return 2
    manifest = load_manifest(manifest_path)
    cache_dir = manifest_path.parent / CACHE_DIRNAME

    errors = manifest_errors(manifest)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1

    if not args.check and not args.fetch:
        parser.print_help()
        return 2

    if args.fetch:
        return fetch(manifest, root, cache_dir, args.key)

    errors, notes = check_sources(manifest, root, cache_dir)
    errors += coverage_errors(manifest, cache_dir)
    for note in notes:
        print(f"note: {note}")
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        return 1
    print(f"ok: {len(manifest['sources'])} sources declared, every one present matches its pinned digest")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
