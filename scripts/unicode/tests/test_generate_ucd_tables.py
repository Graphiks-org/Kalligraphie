import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import generate_ucd_tables as generate


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
MANIFEST_PATH = REPOSITORY_ROOT / "scripts" / "unicode" / "ucd.json"
CACHE_DIR = MANIFEST_PATH.parent / ".cache"


def run(start: int, end: int, value: str) -> generate.Run:
    return generate.Run(start, end, value)


class ParseScalarRangeTest(unittest.TestCase):
    def test_a_single_scalar(self):
        self.assertEqual((0x41, 0x41), generate.parse_scalar_range("0041"))

    def test_a_range(self):
        self.assertEqual((0x41, 0x5A), generate.parse_scalar_range("0041..005A"))

    def test_surrounding_space_is_ignored(self):
        self.assertEqual((0x41, 0x41), generate.parse_scalar_range("  0041  "))


class ParseRangesTest(unittest.TestCase):
    def test_comments_and_blank_lines_are_skipped(self):
        text = "# a comment\n\n0041 ; Latin # trailing comment\n   \n"
        self.assertEqual([run(0x41, 0x41, "Latin")], generate.parse_ranges(text))

    def test_a_named_property_filters_the_lines_of_other_properties(self):
        text = "0300 ; Other ; Value # x\n094D ; InCB ; Linker # y\n"
        self.assertEqual([run(0x94D, 0x94D, "Linker")], generate.parse_ranges(text, "InCB"))
        self.assertEqual([run(0x300, 0x300, "Value")], generate.parse_ranges(text, "Other"))


class ParseMissingDefaultsTest(unittest.TestCase):
    def test_the_missing_lines_keep_their_file_order(self):
        text = "# @missing: 0000..10FFFF; Left_To_Right\n# @missing: 0590..05FF; Right_To_Left\n"
        self.assertEqual(
            [run(0, 0x10FFFF, "Left_To_Right"), run(0x590, 0x5FF, "Right_To_Left")],
            generate.parse_missing_defaults(text),
        )

    def test_a_file_without_a_missing_line_declares_no_default(self):
        self.assertEqual([], generate.parse_missing_defaults("0041 ; Latin\n"))


class DefaultPartitionTest(unittest.TestCase):
    def test_the_general_default_covers_the_whole_codespace(self):
        defaults = generate.parse_missing_defaults("# @missing: 0000..10FFFF; L\n")
        self.assertEqual([run(0, 0x10FFFF, "L")], generate.default_partition(defaults))

    def test_a_later_missing_line_overrides_the_general_one(self):
        defaults = generate.parse_missing_defaults(
            "# @missing: 0000..10FFFF; L\n# @missing: 0590..05FF; R\n"
        )
        self.assertEqual(
            [run(0, 0x58F, "L"), run(0x590, 0x5FF, "R"), run(0x600, 0x10FFFF, "L")],
            generate.default_partition(defaults),
        )

    def test_an_override_that_touches_a_boundary_splits_it(self):
        defaults = [run(0, 0x10, "L"), run(0x5, 0x10, "R")]
        self.assertEqual(
            [run(0, 0x4, "L"), run(0x5, 0x10, "R"), run(0x11, 0x10FFFF, "L")],
            generate.default_partition(defaults),
        )


class CompleteTest(unittest.TestCase):
    def test_a_gap_between_two_ranges_takes_the_default(self):
        runs = [run(0x41, 0x41, "Latin"), run(0x61, 0x61, "Latin")]
        defaults = [run(0, 0x10FFFF, "Unknown")]
        self.assertEqual(
            [
                run(0, 0x40, "Unknown"),
                run(0x41, 0x41, "Latin"),
                run(0x42, 0x60, "Unknown"),
                run(0x61, 0x61, "Latin"),
                run(0x62, 0x10FFFF, "Unknown"),
            ],
            generate.complete(runs, defaults),
        )

    def test_the_default_follows_its_own_regions(self):
        runs = [run(0x41, 0x41, "Latin")]
        defaults = generate.parse_missing_defaults(
            "# @missing: 0000..10FFFF; Unknown\n# @missing: 0590..05FF; Hebrew\n"
        )
        completed = generate.complete(runs, defaults)
        self.assertEqual(run(0x41, 0x41, "Latin"), completed[1])
        self.assertEqual(run(0x42, 0x58F, "Unknown"), completed[2])
        self.assertEqual(run(0x590, 0x5FF, "Hebrew"), completed[3])


class MergeContiguousTest(unittest.TestCase):
    def test_adjacent_ranges_of_one_value_fuse(self):
        self.assertEqual([run(0x41, 0x42, "Latin")], generate.merge_contiguous([run(0x41, 0x41, "Latin"), run(0x42, 0x42, "Latin")]))

    def test_a_gap_stops_the_fusion(self):
        merged = generate.merge_contiguous([run(0x41, 0x41, "Latin"), run(0x43, 0x43, "Latin")])
        self.assertEqual([run(0x41, 0x41, "Latin"), run(0x43, 0x43, "Latin")], merged)

    def test_a_different_value_stops_the_fusion(self):
        merged = generate.merge_contiguous([run(0x41, 0x41, "Latin"), run(0x42, 0x42, "Greek")])
        self.assertEqual([run(0x41, 0x41, "Latin"), run(0x42, 0x42, "Greek")], merged)

    def test_the_input_is_sorted_first(self):
        merged = generate.merge_contiguous([run(0x43, 0x43, "Latin"), run(0x41, 0x42, "Latin")])
        self.assertEqual([run(0x41, 0x43, "Latin")], merged)


class KotlinConstantTest(unittest.TestCase):
    def test_a_short_code_is_kept(self):
        self.assertEqual("AL", generate.kotlin_constant("AL"))

    def test_an_underscored_value_keeps_its_words(self):
        self.assertEqual("REGIONAL_INDICATOR", generate.kotlin_constant("Regional_Indicator"))

    def test_a_camel_case_value_is_flattened(self):
        self.assertEqual("SPACINGMARK", generate.kotlin_constant("SpacingMark"))


class EnumSourceTest(unittest.TestCase):
    def test_every_value_becomes_a_documented_constant(self):
        source = generate.enum_source("Sample", "/** k */", ["Open", "Close"])
        self.assertIn("internal enum class Sample {", source)
        self.assertIn("/** `Open` */\n    OPEN,", source)
        self.assertIn("/** `Close` */\n    CLOSE,", source)

    def test_values_that_collide_once_spelled_fail_loudly(self):
        with self.assertRaises(SystemExit):
            generate.enum_source("Sample", "/** k */", ["Spacing-Mark", "Spacing_Mark"])


class WrapUnitsTest(unittest.TestCase):
    def test_a_line_stops_at_the_item_cap(self):
        units = [f"{index}," for index in range(30)]
        lines = generate.wrap_units(units, width=200, units_per_line=10)
        self.assertEqual(3, len(lines))
        self.assertEqual(10, lines[0].count(","))

    def test_a_line_stops_at_the_width(self):
        units = ["0xAAA,"] * 40
        lines = generate.wrap_units(units, width=40, units_per_line=None)
        self.assertTrue(all(len(line) <= 40 for line in lines), lines)

    def test_the_cap_never_splits_a_pair(self):
        pairs = [f"0x{index:X}, 0x{index + 1:X}," for index in range(30)]
        lines = generate.wrap_units(pairs, width=79, units_per_line=generate.MAX_NUMBERS_PER_LINE // 2)
        for line in lines:
            self.assertEqual(0, line.strip().count(",") % 2, line)


class FormatArrayTest(unittest.TestCase):
    def test_a_pair_array_reproduces_the_committed_shape(self):
        text = generate.format_pair_array("SAMPLE", [(0xA7, 0xA7), (0xA9, 0xA9), (0xBC, 0xBE)])
        self.assertEqual(
            "    private val SAMPLE: IntArray = intArrayOf(\n"
            "        0xA7, 0xA7, 0xA9, 0xA9, 0xBC, 0xBE,\n"
            "    )",
            text,
        )

    def test_an_int_array_wraps_at_ten_numbers(self):
        text = generate.format_int_array("SAMPLE", list(range(12)))
        lines = text.splitlines()
        self.assertEqual(10, lines[1].count(","))
        self.assertEqual(2, lines[2].count(","))

    def test_a_string_array_wraps_on_width_alone(self):
        text = generate.format_string_array("SAMPLE", [f"S{index:03d}" for index in range(40)])
        self.assertTrue(all(len(line) <= generate.MAX_LINE_WIDTH + len(generate.ITEM_INDENT) for line in text.splitlines()))


class SearchShapeTest(unittest.TestCase):
    """The two search shapes: dense tables bound a range by the next start, sparse ones need the end."""

    def test_a_dense_search_does_not_carry_range_ends(self):
        text = generate.search_by_start("Sample", "return Sample.A")
        self.assertNotIn("RANGE_ENDS", text)
        self.assertIn("Unreachable", text)

    def test_a_sparse_search_tests_the_range_end_and_answers_the_default(self):
        text = generate.search_by_start("Sample", "return Sample.A", "Sample.NONE")
        self.assertIn("scalar > RANGE_ENDS[middle] -> return Sample.NONE", text)
        self.assertIn("\n        return Sample.NONE\n", text)
        self.assertNotIn("Unreachable", text)


class ParseValueAliasesTest(unittest.TestCase):
    def test_the_long_and_short_spellings_map_to_the_short_one(self):
        aliases = generate.parse_value_aliases("bc ; AL ; Arabic_Letter\nsc ; Latn ; Latin\n")
        self.assertEqual({"AL": "AL", "Arabic_Letter": "AL"}, aliases["bc"])
        self.assertEqual({"Latn": "Latn", "Latin": "Latn"}, aliases["sc"])

    def test_a_line_without_a_third_field_is_ignored(self):
        self.assertEqual({}, generate.parse_value_aliases("bc ; AL\n"))


class ParseScriptExtensionsTest(unittest.TestCase):
    def test_codes_are_split_and_ranges_kept(self):
        entries = generate.parse_script_extensions("00B7 ; Avst Latn #Po DOT\n0041..0042 ; Latn\n")
        self.assertEqual([(0xB7, 0xB7, ("Avst", "Latn")), (0x41, 0x42, ("Latn",))], entries)


class ParseBidiBracketsTest(unittest.TestCase):
    def test_a_bracket_keeps_its_pair_and_kind(self):
        self.assertEqual([(0x28, 0x29, "o"), (0x29, 0x28, "c")], generate.parse_bidi_brackets("0028; 0029; o # L\n0029; 0028; c # R\n"))


class ParseLikelySubtagsTest(unittest.TestCase):
    def test_only_bare_language_tags_are_kept(self):
        text = '\t<likelySubtag from="aa" to="aa_Latn_ET"/>\n\t<likelySubtag from="en_US" to="en_Latn_US"/>\n'
        self.assertEqual([("aa", "Latn")], generate.parse_likely_subtags(text))

    def test_the_result_is_sorted_and_deduplicated(self):
        text = '\t<likelySubtag from="b" to="b_Latn_BB"/>\n\t<likelySubtag from="a" to="a_Latn_AA"/>\n'
        self.assertEqual([("a", "Latn"), ("b", "Latn")], generate.parse_likely_subtags(text))


class SourcesTest(unittest.TestCase):
    def setUp(self):
        self.manifest = generate.json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.sources = generate.Sources(self.manifest, REPOSITORY_ROOT, CACHE_DIR)

    def test_the_short_version_drops_the_patch_component(self):
        self.assertEqual("16.0", self.sources.short_version)

    def test_an_unknown_key_fails_loudly(self):
        with self.assertRaises(SystemExit):
            self.sources.at("no-such-source")

    def test_a_committed_source_is_read_from_its_repository_path(self):
        source = self.sources.at("scripts")
        self.assertEqual("Scripts.txt", source.path.name)
        self.assertTrue(source.path.is_file())

    def test_the_kdoc_name_falls_back_to_the_payload_name(self):
        self.assertEqual("LineBreak.txt", self.sources.at("line-break").kdoc_file_name)

    def test_a_declared_kdoc_name_wins_over_the_payload_name(self):
        self.assertEqual("VerticalOrientation-16.0.0.txt", self.sources.at("vertical-orientation").kdoc_file_name)


def cache_is_warm() -> bool:
    manifest = generate.json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    sources = generate.Sources(manifest, REPOSITORY_ROOT, CACHE_DIR)
    try:
        for source in manifest["sources"]:
            sources.at(source["key"])
    except SystemExit:
        return False
    return True


@unittest.skipUnless(cache_is_warm(), "run scripts/unicode/fetch_ucd.py --fetch first")
class CommittedTablesTest(unittest.TestCase):
    """The tables of record: what the pinned sources produce is what the module carries."""

    def setUp(self):
        manifest = generate.json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.sources = generate.Sources(manifest, REPOSITORY_ROOT, CACHE_DIR)
        self.rendered = generate.render(self.sources)

    def test_every_expected_table_is_emitted(self):
        self.assertEqual(
            {
                "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/UnicodeVerticalOrientation.kt",
                "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/BidiClass.kt",
                "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/BidiBracketType.kt",
                "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/GraphemeClusterBreak.kt",
                "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/IndicConjunctBreak.kt",
                "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/LineBreakClass.kt",
                "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/UnicodeExtendedPictographic.kt",
                "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/UnicodeScript.kt",
                "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/UnicodeScriptExtensions.kt",
                "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/UnicodeLikelyScript.kt",
            },
            set(self.rendered),
        )

    def test_the_committed_tree_matches_what_the_pinned_sources_produce(self):
        self.assertEqual([], generate.check(REPOSITORY_ROOT, self.rendered))

    def test_a_drifted_table_is_reported_with_the_first_differing_line(self):
        target = pathlib.Path(self.rendered.keys().__iter__().__next__())
        errors = generate.check(
            REPOSITORY_ROOT,
            {str(target): "package org.graphiks.kalligraphie.unicode\n\n/** a */\n"},
        )
        self.assertTrue(errors, errors)
        self.assertIn("regenerated and committed tables differ", errors[0])
        self.assertIn(":3:", errors[0])

    def test_a_missing_table_is_reported(self):
        errors = generate.check(REPOSITORY_ROOT, {"kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/Absent.kt": ""})
        self.assertEqual(["kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/Absent.kt: the committed table is missing"], errors)

    def test_no_table_reaches_the_jvm_method_limit(self):
        # A table emitted as one array literal per value fails to compile past 64 KiB of bytecode,
        # which is why the language table travels as a packed string. This pins the shape that
        # avoided it rather than the encoded words themselves.
        script = self.rendered[
            "kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/UnicodeLikelyScript.kt"
        ]
        self.assertIn("private val LANGUAGE_TAGS: String =", script)
        self.assertNotIn("private val LANGUAGE_TAGS: Array<String>", script)

    def test_sparse_tables_carry_their_range_ends_and_dense_ones_do_not(self):
        def table(name: str) -> str:
            return self.rendered[
                f"kalligraphie/unicode/src/commonMain/kotlin/org/graphiks/kalligraphie/unicode/{name}.kt"
            ]

        self.assertIn("RANGE_ENDS", table("IndicConjunctBreak"))
        self.assertIn("RANGE_ENDS", table("UnicodeScriptExtensions"))
        self.assertNotIn("RANGE_ENDS", table("BidiClass"))
        self.assertNotIn("RANGE_ENDS", table("LineBreakClass"))


if __name__ == "__main__":
    unittest.main()
