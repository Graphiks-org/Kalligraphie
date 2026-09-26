"""Generate the compact Unicode tables the portable analyzer reads, from the pinned UCD sources.

`scripts/unicode/fetch_ucd.py` obtains and verifies the sources; this script turns them into Kotlin
tables in `:kalligraphie:unicode`'s `commonMain`. Each emitted table follows the shape the module
already uses for `UnicodeVerticalOrientation`: a sorted, non-overlapping, contiguous run table
searched by hand without allocating, with the Unicode version and the SHA-256 of the source it was
derived from stated in its KDoc.

    python3 scripts/unicode/generate_ucd_tables.py --check   # regenerate in memory and compare
    python3 scripts/unicode/generate_ucd_tables.py --write   # write the tables into the source tree

`--check` is the guard that matters when reviewing a change: it fails on any drift between the
committed tables and what the pinned sources produce, naming the file and the first differing line.
Neither mode reaches the network — run `fetch_ucd.py --fetch` first when the cache is cold. The one
table this script does not own is `UnicodeVerticalOrientation`: it predates the generator and keeps
its hand wrapping, and `--check` reproduces it byte for byte, which is the proof that the run-table
shape and the wrap width are the committed ones.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from dataclasses import dataclass

# The run tables are wrapped to the widest line the committed `UnicodeVerticalOrientation` uses,
# which was recovered from that file: 10 hexadecimal numbers per line, never past column 79. A
# start/end pair counts as the two numbers it is, so a pair is never split across lines.
MAX_LINE_WIDTH = 79
MAX_NUMBERS_PER_LINE = 10

# String literals do not pack as tightly as numbers, so their arrays wrap on width alone.
STRING_ITEMS_PER_LINE = None

# The scalars of the Unicode codespace.
UNICODE_LIMIT = 0x10FFFF

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parent.parent

PACKAGE = "org.graphiks.kalligraphie.unicode"

TABLE_DIRECTORY = "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode"

MEMBER_INDENT = "    "
ITEM_INDENT = "        "


def table_path(name: str) -> str:
    """The source path of the table declared in `<name>.kt`."""
    return f"{TABLE_DIRECTORY}/{name}.kt"


@dataclass(frozen=True)
class Run:
    """A closed scalar range sharing one value."""

    start: int
    end: int
    value: str


# --------------------------------------------------------------------------------------------------
# Reading the UCD files
# --------------------------------------------------------------------------------------------------


def data_lines(text: str) -> list[str]:
    """The `;`-bearing lines of a UCD file, comments and blank lines removed."""
    lines = []
    for raw in text.splitlines():
        body = raw.split("#", 1)[0].strip()
        if body:
            lines.append(body)
    return lines


def parse_scalar_range(field: str) -> tuple[int, int]:
    field = field.strip()
    if ".." in field:
        start, end = field.split("..", 1)
        return int(start, 16), int(end, 16)
    scalar = int(field, 16)
    return scalar, scalar


def parse_ranges(text: str, property_name: str | None = None) -> list[Run]:
    """Read `<range> ; value` lines, or `<range> ; property ; value` when [property_name] is given."""
    runs = []
    for line in data_lines(text):
        fields = [field.strip() for field in line.split(";")]
        if property_name is None:
            if len(fields) < 2:
                continue
            start, end = parse_scalar_range(fields[0])
            runs.append(Run(start, end, fields[1]))
        else:
            if len(fields) < 3 or fields[1] != property_name:
                continue
            start, end = parse_scalar_range(fields[0])
            runs.append(Run(start, end, fields[2]))
    return runs


def parse_missing_defaults(text: str) -> list[Run]:
    """The `# @missing: <range> ; <value>` defaults, in file order (the last one wins)."""
    defaults = []
    for raw in text.splitlines():
        match = re.match(r"\s*#\s*@missing:\s*(\S+)\s*;\s*(\S+)", raw)
        if match:
            start, end = parse_scalar_range(match.group(1))
            defaults.append(Run(start, end, match.group(2)))
    return defaults


def parse_value_aliases(text: str) -> dict[str, dict[str, str]]:
    """`PropertyValueAliases.txt` as `property -> alias -> canonical short value`.

    Every alias of a value — its short form, its long form and any further spelling the file lists —
    maps to the short form, which is what the tables and the ISO 15924 codes are written in.
    """
    aliases: dict[str, dict[str, str]] = {}
    for line in data_lines(text):
        fields = [field.strip() for field in line.split(";")]
        if len(fields) < 3:
            continue
        property_name, short = fields[0], fields[1]
        table = aliases.setdefault(property_name, {})
        for spelling in fields[1:]:
            table[spelling] = short
    return aliases


def parse_script_extensions(text: str) -> list[tuple[int, int, tuple[str, ...]]]:
    """`ScriptExtensions.txt`: `range ; code code …`, the codes already in ISO 15924 short form."""
    extensions = []
    for line in data_lines(text):
        fields = [field.strip() for field in line.split(";")]
        if len(fields) < 2:
            continue
        start, end = parse_scalar_range(fields[0])
        extensions.append((start, end, tuple(fields[1].split())))
    return extensions


def parse_bidi_brackets(text: str) -> list[tuple[int, int, str]]:
    """`BidiBrackets.txt`: `scalar ; pairedScalar ; o|c`."""
    brackets = []
    for line in data_lines(text):
        fields = [field.strip() for field in line.split(";")]
        if len(fields) < 3:
            continue
        scalar, _ = parse_scalar_range(fields[0])
        paired, _ = parse_scalar_range(fields[1])
        brackets.append((scalar, paired, fields[2]))
    return brackets


def parse_likely_subtags(text: str) -> list[tuple[str, str]]:
    """`likelySubtags.xml` reduced to language -> script, for the entries with a bare language.

    Those are the entries `ULocale.addLikelySubtags` answers for a language carrying no region,
    which is exactly what the analyzer asks of an explicit analysis language. The script is the
    second field of the maximised tag.
    """
    pairs = set()
    for source_tag, maximal in re.findall(r'<likelySubtag from="([^"]+)" to="([^"]+)"', text):
        # A tag carrying a region or a script is not a bare language: CLDR spells those with an
        # underscore, and a hyphen is accepted too so a future revision that switches separator
        # cannot quietly smuggle a compound tag into the table.
        if "_" in source_tag or "-" in source_tag:
            continue
        fields = maximal.split("_")
        if len(fields) >= 2:
            pairs.add((source_tag, fields[1]))
    return sorted(pairs)


# --------------------------------------------------------------------------------------------------
# Turning ranges into a complete, compact partition
# --------------------------------------------------------------------------------------------------


def merge_contiguous(runs: list[Run]) -> list[Run]:
    """Sort [runs] and fuse every pair of adjacent ranges that share a value."""
    merged: list[Run] = []
    for run in sorted(runs, key=lambda item: (item.start, item.end)):
        if merged and run.start <= merged[-1].end + 1 and run.value == merged[-1].value:
            merged[-1] = Run(merged[-1].start, max(merged[-1].end, run.end), run.value)
        else:
            merged.append(run)
    return merged


def overlay(partition: list[Run], override: Run) -> list[Run]:
    """[partition] with [override]'s scalars carrying [override]'s value."""
    result: list[Run] = []
    for run in partition:
        if run.end < override.start or run.start > override.end:
            result.append(run)
            continue
        if run.start < override.start:
            result.append(Run(run.start, override.start - 1, run.value))
        result.append(Run(max(run.start, override.start), min(run.end, override.end), override.value))
        if run.end > override.end:
            result.append(Run(override.end + 1, run.end, run.value))
    return result


def default_partition(defaults: list[Run]) -> list[Run]:
    """The complete codespace partition the `@missing` lines describe."""
    partition = [Run(0, UNICODE_LIMIT, defaults[0].value)]
    for override in defaults[1:]:
        partition = overlay(partition, override)
    return partition


def slice_partition(partition: list[Run], start: int, end: int) -> list[Run]:
    return [
        Run(max(run.start, start), min(run.end, end), run.value)
        for run in partition
        if run.end >= start and run.start <= end
    ]


def complete(runs: list[Run], defaults: list[Run]) -> list[Run]:
    """[runs] laid over the `@missing` defaults, so every scalar of the codespace has a value."""
    partition = default_partition(defaults)
    result: list[Run] = []
    cursor = 0
    for run in sorted(runs, key=lambda item: (item.start, item.end)):
        if run.start > cursor:
            result.extend(slice_partition(partition, cursor, run.start - 1))
        result.append(run)
        cursor = run.end + 1
    if cursor <= UNICODE_LIMIT:
        result.extend(slice_partition(partition, cursor, UNICODE_LIMIT))
    return merge_contiguous(result)


# --------------------------------------------------------------------------------------------------
# Emitting Kotlin
# --------------------------------------------------------------------------------------------------


def wrap_units(units: list[str], width: int, units_per_line: int | None, indent: str = ITEM_INDENT) -> list[str]:
    """Greedy wrap of [units]: a line grows until [units_per_line] units or [width] columns."""
    lines: list[str] = []
    current = ""
    count = 0
    for unit in units:
        candidate = unit if not current else f"{current} {unit}"
        too_long = len(indent) + len(candidate) > width
        too_many = units_per_line is not None and count + 1 > units_per_line
        if current and (too_long or too_many):
            lines.append(indent + current)
            current, count = unit, 1
        else:
            current, count = candidate, count + 1
    if current:
        lines.append(indent + current)
    return lines


def format_array(
    name: str,
    type_name: str,
    opener: str,
    units: list[str],
    units_per_line: int | None = MAX_NUMBERS_PER_LINE,
) -> str:
    body = wrap_units(units, MAX_LINE_WIDTH, units_per_line)
    return "\n".join([f"{MEMBER_INDENT}private val {name}: {type_name} = {opener}", *body, f"{MEMBER_INDENT})"])


def format_int_array(name: str, values: list[int]) -> str:
    return format_array(name, "IntArray", "intArrayOf(", [f"0x{value:X}," for value in values])


def format_pair_array(name: str, pairs: list[tuple[int, int]]) -> str:
    return format_array(
        name,
        "IntArray",
        "intArrayOf(",
        [f"0x{start:X}, 0x{end:X}," for start, end in pairs],
        units_per_line=MAX_NUMBERS_PER_LINE // 2,
    )


def format_byte_array(name: str, values: list[int]) -> str:
    return format_array(name, "ByteArray", "byteArrayOf(", [f"{value}," for value in values])


def format_short_array(name: str, values: list[int]) -> str:
    return format_array(name, "ShortArray", "shortArrayOf(", [f"{value}," for value in values])


def format_string_array(name: str, values: list[str]) -> str:
    return format_array(
        name,
        "Array<String>",
        "arrayOf(",
        [f'"{value}",' for value in values],
        units_per_line=STRING_ITEMS_PER_LINE,
    )


def kotlin_constant(value: str) -> str:
    """A SCREAMING_SNAKE Kotlin identifier for a UCD property value."""
    return re.sub(r"[^A-Za-z0-9]+", "_", value).strip("_").upper()


def enum_source(name: str, kdoc: str, values: list[str]) -> str:
    constants = [kotlin_constant(value) for value in values]
    if len(set(constants)) != len(constants):
        raise SystemExit(f"{name}: UCD values collide once spelled as Kotlin identifiers: {constants}")
    entries = "\n".join(
        f"{MEMBER_INDENT}/** `{value}` */\n{MEMBER_INDENT}{constant},"
        for value, constant in zip(values, constants)
    )
    return f"{kdoc}\ninternal enum class {name} {{\n{entries}\n}}\n"


def search_by_start(returned: str, body: str, fallback: str | None = None) -> str:
    """The hand-written binary search every emitted run table uses, allocation-free.

    A table whose ranges cover the whole codespace can answer `scalar >= start` alone and ends on
    the unreachable branch. A sparse one ([fallback] given) must also test the range's *end*,
    because a scalar between two of its ranges falls in a gap and must take the property's own
    default rather than inherit the range before it; it therefore carries a `RANGE_ENDS` array.
    """
    if fallback is None:
        return f"""    internal fun of(scalar: Int): {returned} {{
        require(scalar in 0..0x10FFFF) {{ "Unicode scalar values stop at 0x10FFFF: $scalar" }}
        var low = 0
        var high = RANGE_STARTS.size - 1
        while (low <= high) {{
            val middle = (low + high) ushr 1
            when {{
                scalar < RANGE_STARTS[middle] -> high = middle - 1
                middle + 1 < RANGE_STARTS.size && scalar >= RANGE_STARTS[middle + 1] -> low = middle + 1
                else -> {body}
            }}
        }}
        error("Unreachable: the run table covers every Unicode scalar value.")
    }}"""
    return f"""    internal fun of(scalar: Int): {returned} {{
        require(scalar in 0..0x10FFFF) {{ "Unicode scalar values stop at 0x10FFFF: $scalar" }}
        var low = 0
        var high = RANGE_STARTS.size - 1
        while (low <= high) {{
            val middle = (low + high) ushr 1
            when {{
                scalar < RANGE_STARTS[middle] -> high = middle - 1
                middle + 1 < RANGE_STARTS.size && scalar >= RANGE_STARTS[middle + 1] -> low = middle + 1
                scalar > RANGE_ENDS[middle] -> return {fallback}
                else -> {body}
            }}
        }}
        return {fallback}
    }}"""


@dataclass(frozen=True)
class Sources:
    """The pinned UCD payloads, read from the committed or cached location the manifest names."""

    manifest: dict
    root: pathlib.Path
    cache_dir: pathlib.Path

    @property
    def unicode_version(self) -> str:
        return self.manifest["unicodeVersion"]

    @property
    def short_version(self) -> str:
        """`16.0.0` as the tables spell it: `16.0`."""
        major, minor, *_ = self.unicode_version.split(".")
        return f"{major}.{minor}"

    def at(self, key: str) -> "PinnedSource":
        source = next((item for item in self.manifest["sources"] if item["key"] == key), None)
        if source is None:
            raise SystemExit(f"the manifest declares no source {key!r}")
        path = (
            self.root / source["repositoryPath"]
            if source["repositoryPath"] is not None
            else self.cache_dir / source["cacheFile"]
        )
        if not path.is_file():
            raise SystemExit(f"source {key!r} is not on disk at {path}; run fetch_ucd.py --fetch")
        return PinnedSource(source, path)


@dataclass(frozen=True)
class PinnedSource:
    entry: dict
    path: pathlib.Path

    @property
    def text(self) -> str:
        return self.path.read_text(encoding="utf-8")

    @property
    def kdoc_file_name(self) -> str:
        """The name the emitted KDoc cites: the published one when the manifest names it."""
        return self.entry["kdocFileName"] or self.path.name

    @property
    def digest(self) -> str:
        return self.entry["sha256"]


def header(sources: Sources, source: PinnedSource, property_name: str, used_by: str, extra: str) -> str:
    return f"""package {PACKAGE}

/**
 * Unicode {sources.short_version} `{property_name}` data.
 *
 * {used_by}
 *
 * The table is derived from
 * `{source.kdoc_file_name}`, SHA-256
 * `{source.digest}`.
{extra}
 */"""


VERSION_CONSTANT = (
    '    /** Pinned Unicode Character Database version that supplied this table. */\n'
    '    internal const val unicodeVersion: String = "{version}"\n'
)


def version_constant(sources: Sources) -> str:
    return VERSION_CONSTANT.replace("{version}", sources.short_version)


# --------------------------------------------------------------------------------------------------
# The tables
# --------------------------------------------------------------------------------------------------


def emit_vertical_orientation(sources: Sources) -> str:
    """The committed `UnicodeVerticalOrientation`, regenerated from its pinned source."""
    source = sources.at("vertical-orientation")
    # U and Tu are one class as far as this table is concerned: both stay upright. Normalising them
    # to a single value before merging is what lets a run of `U` and a run of `Tu` that touch fuse.
    upright = [
        Run(run.start, run.end, "upright") for run in parse_ranges(source.text) if run.value in ("U", "Tu")
    ]
    pairs = [(run.start, run.end) for run in merge_contiguous(upright)]
    return f"""package {PACKAGE}

/**
 * Unicode {sources.short_version} `Vertical_Orientation` data used by vertical paragraph layout.
 *
 * The table is derived from
 * `{source.kdoc_file_name}`, SHA-256
 * `{source.digest}`.
 * It contains the union of the `U` (upright) and `Tu` (typographically
 * transformed with upright fallback) values from UTR #50. Every scalar not in
 * that union has the Unicode default rotated fallback (`R` or `Tr`).
 *
 * The compact sorted ranges are searched without allocating, so consumers can
 * classify every extended-grapheme base scalar on the shaping path.
 */
public object UnicodeVerticalOrientation {{
    /** Pinned Unicode Character Database version that supplied this table. */
    public const val unicodeVersion: String = "{sources.short_version}"

    /** Returns whether [scalar] has an upright fallback in Unicode vertical text. */
    public fun isUpright(scalar: Int): Boolean {{
        if (scalar !in 0..0x10FFFF) return false
        var low = 0
        var high = UPRIGHT_RANGE_BOUNDARIES.size / 2 - 1
        while (low <= high) {{
            val middle = (low + high) ushr 1
            val start = UPRIGHT_RANGE_BOUNDARIES[middle * 2]
            val end = UPRIGHT_RANGE_BOUNDARIES[middle * 2 + 1]
            when {{
                scalar < start -> high = middle - 1
                scalar > end -> low = middle + 1
                else -> return true
            }}
        }}
        return false
    }}

{format_pair_array("UPRIGHT_RANGE_BOUNDARIES", pairs)}
}}
"""


def emit_enum_table(
    *,
    sources: Sources,
    source: PinnedSource,
    property_name: str,
    used_by: str,
    extra: str,
    object_name: str,
    enum_name: str,
    values: list[str],
    runs: list[Run],
    sparse_default: str | None = None,
) -> str:
    """An enum of property values plus the run table that maps a scalar to one of them."""
    indices = {value: index for index, value in enumerate(values)}
    ordinals = [indices[run.value] for run in runs]
    fallback = (
        None if sparse_default is None else f"{enum_name}.{kotlin_constant(sparse_default)}"
    )
    return (
        enum_source(enum_name, header(sources, source, property_name, used_by, extra), values)
        + f"\n/** The run table of [scalar] to [{enum_name}]. */\ninternal object {object_name} {{\n"
        + version_constant(sources)
        + "\n"
        + search_by_start(
            enum_name, f"return {enum_name}.entries[RANGE_VALUES[middle].toInt()]", fallback
        )
        + "\n\n"
        + format_int_array("RANGE_STARTS", [run.start for run in runs])
        + "\n"
        + (
            ""
            if sparse_default is None
            else format_int_array("RANGE_ENDS", [run.end for run in runs]) + "\n"
        )
        + format_byte_array("RANGE_VALUES", ordinals)
        + "\n}\n"
    )


def emit_bidi_class(sources: Sources) -> str:
    source = sources.at("bidi-class")
    aliases = parse_value_aliases(sources.at("property-value-aliases").text)["bc"]
    runs = complete(parse_ranges(source.text), parse_missing_defaults(source.text))
    runs = [Run(run.start, run.end, aliases[run.value]) for run in runs]
    values = sorted({run.value for run in runs})
    return emit_enum_table(
        sources=sources,
        source=source,
        property_name="Bidi_Class",
        used_by="Used by the portable Unicode analyzer.",
        extra=(
            " * It contains the explicit ranges of the source with its `@missing` defaults laid\n"
            " * beneath them, so every scalar of the codespace carries a class, and the long value\n"
            " * spellings of the `@missing` lines are normalised to their short codes."
        ),
        object_name="UnicodeBidiClass",
        enum_name="BidiClass",
        values=values,
        runs=runs,
    )


def emit_grapheme_break(sources: Sources) -> str:
    source = sources.at("grapheme-break")
    runs = complete(parse_ranges(source.text), parse_missing_defaults(source.text))
    values = sorted({run.value for run in runs})
    return emit_enum_table(
        sources=sources,
        source=source,
        property_name="Grapheme_Cluster_Break",
        used_by="Used by the portable extended-grapheme cluster segmenter.",
        extra=(
            " * It contains the explicit ranges of the source with its `@missing` default (`Other`)\n"
            " * laid beneath them, so every scalar of the codespace carries a break class."
        ),
        object_name="UnicodeGraphemeBreak",
        enum_name="GraphemeClusterBreak",
        values=values,
        runs=runs,
    )


def emit_indic_conjunct_break(sources: Sources) -> str:
    source = sources.at("core-properties")
    runs = merge_contiguous(parse_ranges(source.text, "InCB"))
    values = sorted({run.value for run in runs} | {"None"})
    return emit_enum_table(
        sources=sources,
        source=source,
        property_name="Indic_Conjunct_Break",
        used_by="Used by the portable extended-grapheme cluster segmenter (GB9c).",
        extra=(
            " * It contains only the scalars the source declares; every other scalar is `None`, the\n"
            " * property's own default, which is why the table carries no `None` range."
        ),
        object_name="UnicodeIndicConjunctBreak",
        enum_name="IndicConjunctBreak",
        values=values,
        runs=runs,
        sparse_default="None",
    )


def emit_line_break(sources: Sources) -> str:
    source = sources.at("line-break")
    runs = complete(parse_ranges(source.text), parse_missing_defaults(source.text))
    values = sorted({run.value for run in runs})
    return emit_enum_table(
        sources=sources,
        source=source,
        property_name="Line_Break",
        used_by="Used by the portable UAX #14 line-break analyzer.",
        extra=(
            " * It contains the explicit ranges of the source with its `@missing` default (`XX`)\n"
            " * laid beneath them, so every scalar of the codespace carries a class. No tailoring is\n"
            " * applied: the classes are the ones the Unicode Character Database publishes."
        ),
        object_name="UnicodeLineBreak",
        enum_name="LineBreakClass",
        values=values,
        runs=runs,
    )


def emit_extended_pictographic(sources: Sources) -> str:
    source = sources.at("emoji-data")
    ranges = [
        Run(run.start, run.end, "pictographic")
        for run in parse_ranges(source.text)
        if run.value == "Extended_Pictographic"
    ]
    pairs = [(run.start, run.end) for run in merge_contiguous(ranges)]
    return (
        header(
            sources,
            source,
            "Extended_Pictographic",
            "Used by the portable extended-grapheme cluster segmenter (GB11).",
            " * It contains only the scalars the source marks `Extended_Pictographic`; every other\n"
            " * scalar is not one, which is why the `@missing` default needs no range.",
        )
        + f"\ninternal object UnicodeExtendedPictographic {{\n"
        + version_constant(sources)
        + """
    /** Returns whether [scalar] is Extended_Pictographic, the emoji property GB11 keys on. */
    internal fun isExtendedPictographic(scalar: Int): Boolean {
        if (scalar !in 0..0x10FFFF) return false
        var low = 0
        var high = RANGE_BOUNDARIES.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val start = RANGE_BOUNDARIES[middle * 2]
            val end = RANGE_BOUNDARIES[middle * 2 + 1]
            when {
                scalar < start -> high = middle - 1
                scalar > end -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

"""
        + format_pair_array("RANGE_BOUNDARIES", pairs)
        + "\n}\n"
    )


def emit_script(sources: Sources) -> str:
    source = sources.at("scripts")
    aliases = parse_value_aliases(sources.at("property-value-aliases").text)["sc"]
    runs = complete(parse_ranges(source.text), parse_missing_defaults(source.text))
    runs = [Run(run.start, run.end, aliases[run.value]) for run in runs]
    codes = sorted({run.value for run in runs})
    indices = {code: index for index, code in enumerate(codes)}
    return (
        header(
            sources,
            source,
            "Script",
            "Used by the portable Unicode analyzer's script resolution.",
            " * It contains the explicit ranges of the source with its `@missing` default (`Zzzz`)\n"
            " * laid beneath them, so every scalar of the codespace carries a script, and the long\n"
            " * value spellings of the source are normalised to their ISO 15924 short codes.",
        )
        + f"\ninternal object UnicodeScript {{\n"
        + version_constant(sources)
        + """
    /** Returns the ISO 15924 short code of the script [scalar] belongs to. */
    internal fun codeOf(scalar: Int): String {
        require(scalar in 0..0x10FFFF) { "Unicode scalar values stop at 0x10FFFF: $scalar" }
        var low = 0
        var high = RANGE_STARTS.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                scalar < RANGE_STARTS[middle] -> high = middle - 1
                middle + 1 < RANGE_STARTS.size && scalar >= RANGE_STARTS[middle + 1] -> low = middle + 1
                else -> return SCRIPT_CODES[RANGE_VALUES[middle].toInt()]
            }
        }
        error("Unreachable: the run table covers every Unicode scalar value.")
    }

"""
        + format_string_array("SCRIPT_CODES", codes)
        + "\n"
        + format_int_array("RANGE_STARTS", [run.start for run in runs])
        + "\n"
        + format_short_array("RANGE_VALUES", [indices[run.value] for run in runs])
        + "\n}\n"
    )


def emit_script_extensions(sources: Sources) -> str:
    source = sources.at("script-extensions")
    entries = parse_script_extensions(source.text)
    sets: list[tuple[str, ...]] = []
    set_indices: dict[tuple[str, ...], int] = {}
    for _, _, codes in entries:
        if codes not in set_indices:
            set_indices[codes] = len(sets)
            sets.append(codes)
    flattened: list[str] = []
    boundaries = [0]
    for codes in sets:
        flattened.extend(codes)
        boundaries.append(len(flattened))
    return (
        header(
            sources,
            source,
            "Script_Extensions",
            "Used by the portable Unicode analyzer's script resolution.",
            " * It contains only the scalars the source lists, and repeats each distinct set of codes\n"
            " * once: a scalar the source does not list has no extensions and falls back to its\n"
            " * `Script`, which is what an empty result from [extensionsOf] means.",
        )
        + f"\ninternal object UnicodeScriptExtensions {{\n"
        + version_constant(sources)
        + """
    /** Returns the ISO 15924 codes of [scalar]'s `Script_Extensions`, empty when it declares none. */
    internal fun extensionsOf(scalar: Int): List<String> {
        val index = setIndexOf(scalar)
        if (index < 0) return emptyList()
        val from = SET_BOUNDARIES[index]
        val until = SET_BOUNDARIES[index + 1]
        return (from until until).map { position -> SET_CODES[position] }
    }

    /** Returns whether [scalar] declares any `Script_Extensions`, without building the list. */
    internal fun hasExtensions(scalar: Int): Boolean = setIndexOf(scalar) >= 0

    private fun setIndexOf(scalar: Int): Int {
        if (scalar !in 0..0x10FFFF) return -1
        var low = 0
        var high = RANGE_STARTS.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                scalar < RANGE_STARTS[middle] -> high = middle - 1
                middle + 1 < RANGE_STARTS.size && scalar >= RANGE_STARTS[middle + 1] -> low = middle + 1
                scalar > RANGE_ENDS[middle] -> return -1
                else -> return RANGE_SETS[middle].toInt()
            }
        }
        return -1
    }

"""
        + format_int_array("RANGE_STARTS", [start for start, _, _ in entries])
        + "\n"
        + format_int_array("RANGE_ENDS", [end for _, end, _ in entries])
        + "\n"
        + format_short_array("RANGE_SETS", [set_indices[codes] for _, _, codes in entries])
        + "\n"
        + format_int_array("SET_BOUNDARIES", boundaries)
        + "\n"
        + format_string_array("SET_CODES", flattened)
        + "\n}\n"
    )


def emit_bidi_brackets(sources: Sources) -> str:
    source = sources.at("bidi-brackets")
    brackets = sorted(parse_bidi_brackets(source.text))
    types = {"o": 1, "c": 2}
    unknown = {kind for _, _, kind in brackets} - set(types)
    if unknown:
        raise SystemExit(f"{source.path}: unknown Bidi_Paired_Bracket_Type values {sorted(unknown)}")
    enum = enum_source(
        "BidiBracketType",
        header(
            sources,
            source,
            "Bidi_Paired_Bracket_Type",
            "Used by the portable Unicode analyzer (BD16).",
            " * It contains only the scalars the source lists as brackets; every other scalar is\n"
            " * `NONE`, which is why the table carries no `NONE` row.",
        ),
        ["NONE", "OPEN", "CLOSE"],
    )
    return (
        enum
        + "\n/** The bracket table of BD16, keyed by scalar. */\ninternal object UnicodeBidiBrackets {\n"
        + version_constant(sources)
        + """
    /** Returns whether [scalar] is an opening bracket, a closing bracket, or neither. */
    internal fun typeOf(scalar: Int): BidiBracketType {
        val index = indexOf(scalar)
        return if (index < 0) BidiBracketType.NONE else BidiBracketType.entries[BRACKET_TYPES[index].toInt()]
    }

    /**
     * Returns the scalar [scalar] is paired with, or [scalar] itself when it is not a bracket,
     * which is what `UCharacter.getBidiPairedBracket` answers for an unpaired scalar.
     */
    internal fun pairedOf(scalar: Int): Int {
        val index = indexOf(scalar)
        return if (index < 0) scalar else PAIRED_SCALARS[index]
    }

    private fun indexOf(scalar: Int): Int {
        var low = 0
        var high = BRACKET_SCALARS.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                scalar < BRACKET_SCALARS[middle] -> high = middle - 1
                scalar > BRACKET_SCALARS[middle] -> low = middle + 1
                else -> return middle
            }
        }
        return -1
    }

"""
        + format_int_array("BRACKET_SCALARS", [scalar for scalar, _, _ in brackets])
        + "\n"
        + format_int_array("PAIRED_SCALARS", [paired for _, paired, _ in brackets])
        + "\n"
        + format_byte_array("BRACKET_TYPES", [types[kind] for _, _, kind in brackets])
        + "\n}\n"
    )


def format_string_chunks(chunks: list[str]) -> str:
    """One `String` expression concatenated from [chunks], which the compiler folds to a constant."""
    return "\n".join(
        f'{ITEM_INDENT}"{chunk}"' + (" +" if index < len(chunks) - 1 else "")
        for index, chunk in enumerate(chunks)
    )


def chunk_text(value: str, width: int) -> list[str]:
    return [value[offset : offset + width] for offset in range(0, len(value), width)]


def emit_likely_script(sources: Sources) -> str:
    source = sources.at("likely-subtags")
    pairs = parse_likely_subtags(source.text)
    tags = [tag for tag, _ in pairs]
    duplicates = sorted({tag for tag in tags if tags.count(tag) > 1})
    if duplicates:
        raise SystemExit(f"{source.path}: a language tag is listed twice: {duplicates[:5]}")

    codes = sorted({code for _, code in pairs})
    indices = {code: index for index, code in enumerate(codes)}
    longest = max(len(tag) for tag in tags)

    # The table holds 7 000-odd entries: emitted as array literals they put one initializer past the
    # JVM's 64 KiB method limit. The tags therefore travel as `longest` fixed-width characters each
    # — padded with a space, so a two-letter tag still sorts before every three-letter tag that
    # extends it — and the script of each tag as two hexadecimal digits. Both are read in place, so
    # the object holds two small string constants and one array instead of a wall of numbers.
    padded_tags = "".join(tag.ljust(longest) for tag in tags)
    script_digits = "".join(f"{indices[code]:02x}" for _, code in pairs)
    return (
        header(
            sources,
            source,
            "likelySubtags",
            "Used by the portable Unicode analyzer's script resolution.",
            " * It is the source reduced to the languages that carry no region, mapped to the script\n"
            " * their maximised tag names: that is the lookup the analyzer makes for an explicit\n"
            " * analysis language, and no other entry of the source can answer it.\n"
            f" * The tags are stored {longest} characters each in [LANGUAGE_TAGS], the shorter ones\n"
            " * padded with a space, and the script of each is the [SCRIPT_CODES] index spelled as\n"
            " * two hexadecimal digits in [LANGUAGE_SCRIPTS].",
        )
        + "\ninternal object UnicodeLikelyScript {\n"
        + version_constant(sources)
        + """
    /**
     * Returns the likely ISO 15924 short code of [language], or null when the source has none.
     *
     * [language] is a bare language subtag, not a whole BCP 47 tag: the source keys on the language
     * alone, so a tag such as `en-US` has to be reduced before it is asked here.
     */
    internal fun scriptOf(language: String): String? {
        var low = 0
        var high = TAG_COUNT - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val comparison = compareTagAt(middle, language)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return SCRIPT_CODES[hexPair(LANGUAGE_SCRIPTS, middle)]
            }
        }
        return null
    }

    /** Compares the tag of [index] with [language], reading both one character at a time. */
    private fun compareTagAt(index: Int, language: String): Int {
        val base = index * TAG_WIDTH
        var offset = 0
        while (offset < TAG_WIDTH) {
            val stored = LANGUAGE_TAGS[base + offset]
            val given = if (offset < language.length) language[offset] else ' '
            if (stored != given) return stored.compareTo(given)
            offset += 1
        }
        return 0
    }

    private fun hexPair(hex: String, index: Int): Int =
        (hexDigit(hex[index * 2]) shl 4) or hexDigit(hex[index * 2 + 1])

    private fun hexDigit(character: Char): Int {
        val digit = character - '0'
        return if (digit < 10) digit else character - 'a' + 10
    }

    private const val TAG_WIDTH: Int = TAG_LENGTH
    private const val TAG_COUNT: Int = TAG_TOTAL

    /** The source's language subtags, [TAG_WIDTH] characters each. */
    private val LANGUAGE_TAGS: String =
TAG_CHUNKS

    /** The [SCRIPT_CODES] index of each tag, two hexadecimal digits each. */
    private val LANGUAGE_SCRIPTS: String =
SCRIPT_CHUNKS

"""
        .replace("TAG_LENGTH", str(longest))
        .replace("TAG_TOTAL", str(len(tags)))
        .replace("TAG_CHUNKS", format_string_chunks(chunk_text(padded_tags, longest * 32)))
        .replace("SCRIPT_CHUNKS", format_string_chunks(chunk_text(script_digits, 96)))
        + format_string_array("SCRIPT_CODES", codes)
        + "\n}\n"
    )


TABLES = {
    "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/UnicodeVerticalOrientation.kt": emit_vertical_orientation,
    table_path("BidiClass"): emit_bidi_class,
    table_path("BidiBracketType"): emit_bidi_brackets,
    table_path("GraphemeClusterBreak"): emit_grapheme_break,
    table_path("IndicConjunctBreak"): emit_indic_conjunct_break,
    table_path("LineBreakClass"): emit_line_break,
    table_path("UnicodeExtendedPictographic"): emit_extended_pictographic,
    table_path("UnicodeScript"): emit_script,
    table_path("UnicodeScriptExtensions"): emit_script_extensions,
    table_path("UnicodeLikelyScript"): emit_likely_script,
}


def render(sources: Sources) -> dict[str, str]:
    return {path: emitter(sources) for path, emitter in sorted(TABLES.items())}


def check(root: pathlib.Path, rendered: dict[str, str]) -> list[str]:
    errors = []
    for path, text in rendered.items():
        target = root / path
        if not target.is_file():
            errors.append(f"{path}: the committed table is missing")
            continue
        committed = target.read_text(encoding="utf-8")
        if committed == text:
            continue
        expected = text.splitlines()
        actual = committed.splitlines()
        for index in range(max(len(expected), len(actual))):
            wanted = expected[index] if index < len(expected) else "<absent>"
            found = actual[index] if index < len(actual) else "<absent>"
            if wanted != found:
                errors.append(
                    f"{path}:{index + 1}: regenerated and committed tables differ\n"
                    f"    regenerated: {wanted}\n    committed:   {found}"
                )
                break
    return errors


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="regenerate in memory and compare")
    parser.add_argument("--write", action="store_true", help="write the tables into the source tree")
    parser.add_argument("--root", default=".", help="repository root the manifest paths resolve against")
    parser.add_argument("--manifest", default="scripts/unicode/ucd.json", help="manifest to read")
    args = parser.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    manifest_path = root / args.manifest
    if not manifest_path.is_file():
        print(f"manifest {manifest_path} is missing", file=sys.stderr)
        return 2
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    sources = Sources(manifest, root, manifest_path.parent / ".cache")

    if not args.check and not args.write:
        parser.print_help()
        return 2

    rendered = render(sources)

    if args.write:
        for path, text in rendered.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
            print(f"wrote {path}")
        return 0

    errors = check(root, rendered)
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        return 1
    print(f"ok: {len(rendered)} tables match their pinned sources")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
