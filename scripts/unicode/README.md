# The Unicode Character Database sources

`scripts/unicode/ucd.json` is the authority of record for the UCD files the portable Unicode tables
of `:kalligraphie:unicode` are derived from. It declares, for every source, the exact upstream URL,
the SHA-256 of the payload, which of its properties the tables read, and where that payload is read
from. `generate_ucd_tables.py` consumes it and emits one Kotlin table per property;
`fetch_ucd.py` obtains and verifies the payloads. **The digest, never the URL, is what accepts a
payload.**

The tables are generated, never hand-edited. The one table that predates the generator,
`UnicodeVerticalOrientation`, is reproduced by it **byte for byte** — which is what fixed the
run-table shape and the wrap width the other tables follow.

## Where a source is read from

A source is read from exactly one of two places, and the manifest says which; `null` marks the other.

| Field | Meaning |
| --- | --- |
| `repositoryPath` | The payload is already committed in this repository, under that repository-relative path — today the three files `:kalligraphie:unicode` also uses as conformance oracles. Read in place, never re-downloaded. |
| `cacheFile` | The payload is downloaded into `scripts/unicode/.cache/` under that bare file name. The directory is untracked: the tables it produces are committed, the source it was derived from is not. |
| `kdocFileName` | The name the emitted KDoc cites. `null` for every source whose published name is the payload's own; set for `vertical-orientation`, whose 2005-revision name `VerticalOrientation-16.0.0.txt` is the one the committed table already cites. |

The distinction matters for `--check`: a committed source that is missing or altered is an error,
while a cache entry that is simply absent is a note, because `--fetch` is what obtains it.

## Fields

Each source:

| Field | Meaning |
| --- | --- |
| `key` | Stable identifier of the source; the generator asks for a source by key, never by path. |
| `url` | The exact upstream URL the digest was taken from. |
| `unicodeVersion` | The Unicode Standard release the payload belongs to. |
| `retrieved` | The date the payload was retrieved, as its `PROVENANCE.md` records it. |
| `license` | The licence of record for the payload: `Unicode-3.0`. |
| `sha256` | SHA-256 of the payload, lowercase hexadecimal, never copied from prose. |
| `sizeBytes` | Size of the payload in bytes. |
| `repositoryPath` / `cacheFile` | Where the payload is read from, exactly one of the two. |
| `kdocFileName` | The published name the emitted KDoc cites, or `null`. |
| `provides` | The UCD properties the tables read from this source, sorted. A closed allowlist: a typo cannot silently declare a property nothing reads. |

## The tables, and how they are shaped

One emitted file per property, in `kalligraphie/unicode/src/commonMain/.../unicode/`:

| Table | Source | Shape |
| --- | --- | --- |
| `UnicodeVerticalOrientation` | `VerticalOrientation.txt` | Sparse. The union of `U` and `Tu`, as start/end pairs. **Public, and unchanged** — the generator reproduces it byte for byte. |
| `BidiClass`, `GraphemeClusterBreak`, `LineBreakClass` | `DerivedBidiClass.txt`, `GraphemeBreakProperty.txt`, `LineBreak.txt` | Dense: the source's ranges with its `@missing` defaults laid beneath them, so every scalar of the codespace carries a class. |
| `IndicConjunctBreak` | `DerivedCoreProperties.txt` (`InCB`) | Sparse: only the scalars the source declares; every other is `None`. |
| `UnicodeExtendedPictographic` | `emoji-data.txt` (`Extended_Pictographic`) | Sparse: start/end pairs of the scalars the source marks. |
| `UnicodeScript` | `Scripts.txt` | Dense; the source's long value spellings are normalised to ISO 15924 short codes through `PropertyValueAliases.txt`. |
| `UnicodeScriptExtensions` | `ScriptExtensions.txt` | Sparse; each distinct set of codes is emitted once and the ranges index into it. |
| `UnicodeBidiBrackets` | `BidiBrackets.txt` | Sparse; three parallel arrays over the bracketed scalars. |
| `UnicodeLikelyScript` | `likelySubtags.xml` (CLDR) | Sparse; the languages carrying no region, mapped to the script their maximised tag names. |

Two shapes of binary search, and the difference is not cosmetic. A **dense** table bounds each range
by the next range's start, so testing `scalar >= start` is enough. A **sparse** table has gaps, so it
also carries a `RANGE_ENDS` array: a scalar between two of its ranges falls in a gap and must take
the property's own default rather than inherit the range before it. Emitting a sparse table with the
dense search returned an answer for scalars the source never mentions.

Every table is searched by hand, without allocating, so a consumer can classify every scalar on the
analysis path. The run tables wrap at 10 hexadecimal numbers per line and never past column 79, the
width the committed `UnicodeVerticalOrientation` uses; a start/end pair counts as the two numbers it
is and is never split.

`UnicodeLikelyScript` is the one table that could not be emitted as array literals: 7 000-odd string
entries put a single initializer past the JVM's 64 KiB method limit, and the module stopped
compiling with `Method too large`. The language tags therefore travel as three fixed-width
characters each — padded with a space, so a two-letter tag still sorts before every three-letter tag
that extends it — and the script of each as two hexadecimal digits, both read in place. The file's
KDoc states the encoding.

## Commands

**These are local obligations, not CI steps.** As with `scripts/fonts/`, this repository's continuous
integration carries business concepts: what guards these tables in CI is the behavioural test suite
of `:kalligraphie:unicode`, which pins each table against the Unicode Character Database's own
published values. Run all of the commands below after touching `ucd.json`, the generator, or any
committed table.

```sh
python3 scripts/unicode/fetch_ucd.py --check                # offline: every source on disk matches its digest
python3 scripts/unicode/fetch_ucd.py --fetch                # download the pinned sources into .cache/
python3 scripts/unicode/fetch_ucd.py --fetch --key line-break # restrict to one source
python3 scripts/unicode/generate_ucd_tables.py --check      # regenerate in memory and compare
python3 scripts/unicode/generate_ucd_tables.py --write      # write the tables into the source tree
python3 -m unittest discover -s scripts/unicode/tests -v    # unit tests, no network
```

Only `--fetch` reaches the network. `--check` compares the committed tables with what the pinned
sources produce and names the file and the first differing line, so a table edited by hand, a source
whose digest moved, or a generator change that alters the output all fail the same way. The unit
suite is runnable offline: the tests that need the payloads are skipped, with that reason, until
`--fetch` has run.

The tables are compiled by `:kalligraphie:unicode`, so a change here is verified by the module's own
suite on every target:

```sh
./gradlew :kalligraphie:unicode:jvmTest
./gradlew :kalligraphie:unicode:iosSimulatorArm64Test
```

## Licences

Every Unicode payload is distributed under the [Unicode License V3](https://www.unicode.org/license.txt);
`likelySubtags.xml` is CLDR data under the same licence. The required copyright and permission
notice, and the exact URL and digest of each payload, are recorded in `PROVENANCE.md`.
