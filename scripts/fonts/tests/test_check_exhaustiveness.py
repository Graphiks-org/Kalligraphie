import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import check_exhaustiveness


class ExhaustivenessTest(unittest.TestCase):
    def test_a_table_present_in_the_font_and_claimed_nowhere_is_reported(self):
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap", "glyf", "COLR"},
            claimed={"cmap", "glyf"},
            allow_unclaimed={},
        )
        self.assertEqual(1, len(violations))
        self.assertIn("COLR", violations[0])

    def test_an_allowlisted_table_is_accepted(self):
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap", "DSIG"},
            claimed={"cmap"},
            allow_unclaimed={"DSIG": "digital signature, not consumed"},
        )
        self.assertEqual([], violations)

    def test_a_claimed_table_the_font_does_not_carry_is_reported(self):
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap"},
            claimed={"cmap", "SVG "},
            allow_unclaimed={},
        )
        self.assertEqual(1, len(violations))
        self.assertIn("SVG", violations[0])

    def test_a_claimed_table_covered_by_an_excuse_is_accepted(self):
        """An excuse covers a table nobody claims; it never withdraws a claim that exists."""
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap"},
            claimed={"cmap"},
            allow_unclaimed={"cmap": "structural; claimed where a scene really reads it"},
        )
        self.assertEqual([], violations)

    def test_a_table_outside_the_significant_set_is_ignored(self):
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap", "DSIG", "LTSH"},
            claimed={"cmap"},
            allow_unclaimed={},
        )
        self.assertEqual([], violations)

    def test_excuse_all_covers_every_table_the_family_leaves_unclaimed(self):
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap", "GPOS", "kern"},
            claimed={"cmap"},
            allow_unclaimed={},
            excuse_all=True,
        )
        self.assertEqual([], violations)

    def test_excuse_all_does_not_excuse_a_claim_on_a_table_the_font_lacks(self):
        """A family-wide excuse is about the tables a family carries, not about its claims."""
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap"},
            claimed={"cmap", "COLR"},
            allow_unclaimed={},
            excuse_all=True,
        )
        self.assertEqual(1, len(violations))
        self.assertIn("COLR", violations[0])


class MergedExcusesTest(unittest.TestCase):
    def claims(self, allow_unclaimed):
        return {"allowUnclaimed": allow_unclaimed}

    def test_the_family_excuses_are_merged_with_the_global_ones(self):
        merged, excuse_all = check_exhaustiveness.merged_excuses(
            self.claims(
                {
                    "*": {"cmap": "structural"},
                    "tiny": {"DSIG": "digital signature, not consumed"},
                }
            ),
            "tiny",
        )
        self.assertEqual({"cmap": "structural", "DSIG": "digital signature, not consumed"}, merged)
        self.assertFalse(excuse_all)

    def test_a_star_inside_the_family_map_excuses_the_whole_family(self):
        merged, excuse_all = check_exhaustiveness.merged_excuses(
            self.claims(
                {
                    "*": {"cmap": "structural"},
                    "tiny": {"*": "no catalogued scene references this family"},
                }
            ),
            "tiny",
        )
        self.assertTrue(excuse_all)
        self.assertEqual({"cmap": "structural"}, merged)

    def test_a_family_excuse_wins_over_the_global_one_on_the_same_table(self):
        merged, _ = check_exhaustiveness.merged_excuses(
            self.claims({"*": {"cmap": "structural"}, "tiny": {"cmap": "read by the metrics scene"}}),
            "tiny",
        )
        self.assertEqual({"cmap": "read by the metrics scene"}, merged)

    def test_a_family_without_an_allowlist_entry_gets_the_global_excuses_only(self):
        merged, excuse_all = check_exhaustiveness.merged_excuses(
            self.claims({"*": {"cmap": "structural"}}),
            "absent",
        )
        self.assertEqual({"cmap": "structural"}, merged)
        self.assertFalse(excuse_all)


if __name__ == "__main__":
    unittest.main()
