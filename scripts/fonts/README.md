# The font corpus manifest

`scripts/fonts/corpus.json` is the authority of record for `test-fixtures/fonts/`. It declares,
for every family directory, the font artifacts committed there, where each came from, under which
licence, and which sfnt tables each file really carries. Tasks 10 to 12 consume it:

- `fetch_fonts.py --check` re-hashes the committed bytes and re-checks the licences offline;
- `fetch_fonts.py --fetch` re-downloads the pinned sources; `--provenance` re-checks the
  `PROVENANCE.md` and licence files;
- `check_exhaustiveness.py` lints the tables the e2e catalog *claims* against the tables these
  fonts *carry*, so a carried table can never be ignored by omission.

The family keys are the directory names of `test-fixtures/fonts/` and are stable: the e2e catalog
entries, the golden images and the claims export all key off them, and a directory is never renamed.
`scripts/fonts/corpus.json` is hand-maintained data entry — the hashes, sizes and table lists are
read from the committed bytes, while the URLs, revisions, licences and notes are transcribed from
each family's `PROVENANCE.md`. `--check` exists precisely because that transcription is fallible.

Format: schema `kalligraphie.font-corpus/v1`, a top-level `families` array sorted by `key` in
alphabetical order, two-space indentation, no comments.

## Fields

Each family:

| Field | Meaning |
| --- | --- |
| `key` | The directory name under `test-fixtures/fonts/`. One entry per directory. |
| `files` | The committed font artifacts of the directory, sorted by `path`. Font files only (see below). |
| `license` | One of the eight licence identifiers of `fetch_fonts.ALLOWED_LICENSES`: `Apache-2.0`, `BSD-3-Clause`, `CC0-1.0`, `CC-BY-4.0`, `DejaVu`, `MIT`, `OFL-1.1`, `Unlicense`. |
| `licenseFile` | Repository-relative path of the licence text that applies. |
| `synthetic` | `true` when the fonts are constructed locally instead of distributed upstream. |
| `builtBy` | For a synthetic family, the constructor script; `null` otherwise. |

Each entry of `files`:

| Field | Meaning |
| --- | --- |
| `path` | Repository-relative path of a committed font artifact. |
| `url` | The readable page the `PROVENANCE.md` cites, or the source page the provenance names when it cites no file page. Every non-synthetic family carries one; it is the tracing anchor even when the bytes themselves are not re-downloadable. `null` for a synthetic family. |
| `rawUrl` | The URL a plain `GET` starts from; the payload may need decoding before the digest can be compared (see the transports below). `null` when no URL serves the bytes. |
| `fetchNote` | One sentence giving the manual recipe when the bytes are not a plain `GET` away; `null` otherwise. |
| `revision` | The immutable coordinate the `PROVENANCE.md` pins. |
| `sha256` | SHA-256 of the committed file (`shasum -a 256`), never copied from prose. |
| `sizeBytes` | Size of the committed file in bytes (`stat -f%z`). |
| `tables` | Every table of the file's real sfnt table directory, sorted. |

Notes on the fields:

- **Font artifacts only.** A `files` entry is a `.ttf`, `.otf` or `.ttc`, or the base64 wrapper of
  one. `PROVENANCE.md`, licence texts, builder scripts, `audit.json` and the recorded outline oracles
  are not font files, are not listed in `files`, and are re-checked by `--provenance` instead.
- **`sha256` and `sizeBytes` describe the committed file**, so for `skia-colr-v1` they are those of
  `test_glyphs-glyf_colr_1.ttf.b64` and for `twemoji-svginot-glyph5` those of the `.base64` wrapper
  — the encoded artifact is what the repository ships and what `--check` verifies. The decoded fonts
  hash to `72cb79b606c79bc49861094e25f442db0b24881504b29533bbe8ea75f3902e67` (Skia COLR v1) and
  `3321f267b8a242d96c0790ac31becc74b7f57656762037e16f465be1adcd84d2` (Twitter emoji subset).
- **`tables` is read from the real sfnt table directory**, not from fontTools' convenience view:
  the synthetic `GlyphOrder` pseudo-entry is not an sfnt table and is not listed, while the exact tag
  spelling of the directory is preserved, trailing space included (`CFF `, `SVG `, `cvt `). All
  entries are recorded, not only the ones the catalog cares about, and for a `.ttc` the list is the
  union over its faces. Read with fontTools 4.65.0, the version of the repository audits.
- **`revision`** is the 40-character commit for a GitHub source, the release or tag identifier for an
  archived source (`2.37`, `2.1.5`, `NotoSansDevanagari-v2.006`, `v15.1.0`), and `null` for a
  synthetic family.
- **`license`** is the identifier of record for the family. The corpus uses six of the eight:
  `OFL-1.1` (the Liberation, Amiri, Noto and Bungee families), `BSD-3-Clause` (the four Skia
  fixtures), `DejaVu`, `CC-BY-4.0` (the two emoji families, `emoji-two-colr-v0` and
  `twemoji-svginot-glyph5`), `CC0-1.0` (`gdef-kern`, which declares it explicitly) and `MIT`, which
  covers the fixtures generated inside the repository that declare no licence of their own
  (`cff2-variable`, `kalligraphie-var-colr`, `kalligraphie-var-vvar`; see `licenseFile` below).
  `Apache-2.0` and `Unlicense` are allowed but unused.
- **`licenseFile`** points at the family's own licence text when the directory carries one. Three
  families (the two CFF fixtures derived from Liberation and the Liberation/Amiri collection) are
  covered by `test-fixtures/fonts/liberation/OFL-1.1.txt`, which their `PROVENANCE.md` names
  explicitly. Two gaps are documented rather than hidden. `bungee-color` ships no local licence
  text — upstream's `OFL.txt` lives in the source repository — so the field points at the corpus's
  OFL-1.1 copy, whose header names Google and Red Hat while the applicable copyright, The Bungee
  Project Authors and David Jonathan Ross, is the one recorded in that family's `PROVENANCE.md`.
  And the three fixtures generated inside the repository that declare no licence of their own
  (`cff2-variable`, `kalligraphie-var-colr`, `kalligraphie-var-vvar`) point at the repository's
  `LICENSE`, the MIT text that covers generated material with no third-party content; those three
  `PROVENANCE.md` files declaring no licence at all is a known corpus gap still to close.

## The `synthetic` / `builtBy` rule

The rule is mechanical, not a judgement call: **a family is `synthetic: true` if and only if its
directory contains a Python constructor script, and `builtBy` names that script.** A synthetic
family's fonts are rebuilt locally by that script, are never fetched, and therefore carry no `url`,
`rawUrl`, `fetchNote` or `revision`.

| Synthetic family | `builtBy` |
| --- | --- |
| `cff-liberation` | `build_cff_fixture.py` |
| `cff2-liberation` | `build_cff2_fixture.py` |
| `cff2-variable` | `build_cff2_variable_fixture.py` |
| `gdef-kern` | `FixtureBuild.py` |
| `kalligraphie-var-colr` | `build_variable_colr_v1.py` |
| `kalligraphie-var-vvar` | `build_variable_vvar.py` |
| `liberation-amiri-collection` | `build_collection_fixture.py` |

The other twelve families (`amiri`, `bungee-color`, `dejavu`, `emoji-two-colr-v0`, `liberation`,
`noto-devanagari`, `noto-sans-jp`, `skia-cbdt`, `skia-colr-v1`, `skia-ebdt-format1`, `skia-sbix`,
`twemoji-svginot-glyph5`) are upstream distributions: `synthetic: false`, `builtBy: null`.

## The `url` / `rawUrl` / `fetchNote` rule

`url` is the readable page the `PROVENANCE.md` cites — a GitHub tree or blob page, a project or
repository page, a release page. When the provenance cites the repository rather than the file page
(`bungee-color`, `emoji-two-colr-v0`) or cites the pinned download only (`skia-ebdt-format1`), `url`
records exactly what is cited; `rawUrl` then identifies the file itself. When the provenance names
only the repository and a release tag without a URL (`twemoji-svginot-glyph5`), `url` is the release
page those coordinates identify, because a real family always carries an `url` as its tracing
anchor even when its bytes cannot be re-downloaded.

`rawUrl` is recorded when a single unauthenticated `GET` is enough to obtain the file. The payload
may be encoded — gitiles serves base64 — in which case a fetcher must decode it before comparing the
digest; that is a transport exception, not a licence to rewrite the URL, and the pinned digest is
what accepts or rejects the decoding. A payload that would have to be *re-encoded* to match a
committed envelope is a different matter: no transport serves that envelope, so the file carries a
`fetchNote` instead and no `rawUrl` (`skia-colr-v1`, below). The one URL rewrite this manifest
performs is the mechanical counterpart of a GitHub `/blob/<revision>/` page,
`https://raw.githubusercontent.com/<org>/<repo>/<revision>/<path>`; it is applied to nothing else and
is never guessed from a page that is not a blob page. Every `rawUrl` in this manifest has been
fetched and checked: six return the committed bytes verbatim, and the seventh, `skia-ebdt-format1`,
is served base64 and is described below.

`fetchNote` carries the manual recipe instead of `rawUrl` — one sentence naming the archive, the
member path, the transform and the digest — for the six families whose committed file is not a plain
`GET` away:

| Family | Why |
| --- | --- |
| `dejavu` | The TTF is a member of a SourceForge archive, not a hosted file. |
| `liberation` | The TTF is a member of a GitHub release attachment tarball. |
| `noto-devanagari` | The TTF is a member of a release zip. |
| `noto-sans-jp` | The committed file is a `pyftsubset` derivative of the pinned source. |
| `skia-colr-v1` | A plain `GET` returns the font itself, but the committed artifact is its `.b64` envelope: reproducing it means re-encoding with 76-column wrapping, which `--fetch` refuses to guess. |
| `twemoji-svginot-glyph5` | A release zip, then a subset, then base64 encoding; the release page is recorded as `url`, and no URL serves the font's bytes. |

One `rawUrl` does not return the committed bytes verbatim, and the acquisition code must handle it:

- `skia-ebdt-format1`: gitiles serves the payload base64 (`?format=TEXT`). The payload hashes to
  `ddae4f9b32e11fba5e461b8c9eedd06a9534e187285931c51fb1e56d16c729f9`; base64-decoded it is the
  committed font, `e99cebed4d9421bc89964b9dc6a3bedfc6a286029d64336a07844708cce76274`. A fetcher must
  decode before comparing the digest, and it may only try these two readings of one payload — the
  payload verbatim, then its base64 decoding — with the pinned digest deciding between them.

The converse case no longer carries a `rawUrl`: the page of `skia-colr-v1` serves the 21,568-byte
font, while the committed artifact is the `.b64` envelope of that font (29,139 bytes,
`84b8d05c…`), so the recorded `sha256` and `sizeBytes` are the encoding's and reproducing the
committed bytes would mean re-encoding the font with 76-column wrapping and a trailing newline. The
same caveat applies to the `.base64` artifact of `twemoji-svginot-glyph5`, which carries no `rawUrl`
either and whose `fetchNote` holds the encoding step.

Contract for `fetch_fonts.py`, which the manifest's data imposes: `rawUrl` and `fetchNote` are
alternatives. A file with a `fetchNote` and no `rawUrl` cannot be re-downloaded by a plain `GET`, so
`--check` must not report it as a non-synthetic file without a `rawUrl` and `--fetch` must skip it,
printing the note; a non-synthetic file with neither field is an error. Six files are in that
position, all of them upstream distributions: `dejavu`, `liberation`, `noto-devanagari`,
`noto-sans-jp`, `skia-colr-v1` and `twemoji-svginot-glyph5`.

## Full fonts, never subsampled

The corpus ships complete upstream specimens. A scene that cannot afford a full font is refused by
the catalog (a typed `e2e.blank-scene` or a bounds refusal) rather than fed a subset, and a new
fixture commits the whole font: reduction is not a tool for making a test cheaper.

Two deliberate, audited exceptions predate the rule and are the only subsets in the corpus:
`noto-sans-jp` (`pyftsubset --unicodes=U+0041,U+65E5`) and `twemoji-svginot-glyph5`
(`pyftsubset --gids=5`, then base64). Both say so explicitly in their `PROVENANCE.md`, both carry a
`fetchNote` that reproduces the reduction command, and neither may be used as a general font fixture.
No new subsampling may be introduced; the rule for a new fixture is the whole font.

## Commands

Task 10 — acquisition and verification:

```sh
python3 scripts/fonts/fetch_fonts.py --check --provenance   # offline: hashes, licences, provenance files
python3 scripts/fonts/fetch_fonts.py --fetch                # download the pinned sources
python3 scripts/fonts/fetch_fonts.py --fetch --key skia-cbdt # restrict to one family
python3 -m unittest discover -s scripts/fonts/tests -v      # unit tests, no network
```

Task 11 — claims export (the tables the catalog claims, one entry per corpus key):

```sh
./gradlew :kalligraphie:e2e:updateE2eGolden
./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogClaimsRunnerTest*'
```

Task 12 — exhaustiveness lint (the tables these fonts carry, against the claims):

```sh
uv run --with fonttools==4.65.0 python scripts/fonts/check_exhaustiveness.py
python3 -m unittest discover -s scripts/fonts/tests -v
```

Coverage control — the manifest and the committed fixture tree describe exactly the same files, in
both directions, asserted by `FixtureTreeCoverageTest` in `scripts/fonts/tests/test_fetch_fonts.py`
and therefore run by the command above without any argument:

```sh
python3 -c "
import json, pathlib, sys
sys.path.insert(0, 'scripts/fonts')
import fetch_fonts
root = pathlib.Path('.').resolve()
manifest = fetch_fonts.load_manifest(root / 'scripts/fonts/corpus.json')
print('\n'.join(fetch_fonts.coverage_errors(manifest, root)))
"
```

`fetch_fonts.coverage_errors` compares the directory names of `test-fixtures/fonts/` with the
manifest keys, and, per family, the committed font artifacts with the declared `path`s. A font
dropped into the tree by hand, a directory of `test-fixtures/fonts/` no family of the manifest uses
(a phantom family), a family the manifest declares with no directory behind it, and a declared file
that is not committed all fail. Only the font artifacts count — a `.ttf`, `.otf` or `.ttc`, or its
base64 wrapper; `PROVENANCE.md`, the licence texts, the builder scripts, `audit.json` and the
recorded oracles are companions and are not declared.

Expected: no output.
