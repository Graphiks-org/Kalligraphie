import tempfile
import unittest
from pathlib import Path

from validate_pr_policy import validate_policy


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
ALLOWED_TYPES = [
    "feat",
    "fix",
    "build",
    "chore",
    "ci",
    "docs",
    "perf",
    "refactor",
    "test",
    "style",
]
TYPE_LABELS = {
    "feat": "New feature",
    "fix": "Bug fix",
    "build": "Build or dependencies",
    "chore": "Maintenance or tooling",
    "ci": "CI/CD configuration",
    "docs": "Documentation",
    "perf": "Performance improvement",
    "refactor": "Refactoring",
    "test": "Tests",
    "style": "Code style",
}


def make_body(
    *,
    selected_types: list[str] | None = None,
    changelog_state: str = "updated",
    changelog_reason: str = "release notes are tracked elsewhere",
    documentation_updated: bool = False,
) -> str:
    selected_types = selected_types or ["feat"]
    type_lines = [
        f"- [{'x' if item in selected_types else ' '}] `{item}` — {TYPE_LABELS[item]}"
        for item in ALLOWED_TYPES
    ]
    changelog_lines = [
        f"- [{'x' if changelog_state == 'updated' else ' '}] CHANGELOG.md has been updated",
        f"- [{'x' if changelog_state == 'no' else ' '}] No changelog update needed: {changelog_reason}",
    ]
    return "\n".join(
        [
            "## Description",
            "",
            "Brief summary.",
            "",
            "## Type of Change",
            "",
            *type_lines,
            "",
            "## Checklist",
            "",
            *changelog_lines,
            f"- [{'x' if documentation_updated else ' '}] Documentation updated if needed",
            "",
            "## Screenshots (if applicable)",
            "",
            "## Additional Notes",
            "",
        ]
    )


def write_text(directory: Path, name: str, content: str) -> Path:
    path = directory / name
    path.write_text(content, encoding="utf-8")
    return path


def write_lines(directory: Path, name: str, lines: list[str]) -> Path:
    return write_text(directory, name, "\n".join(lines) + "\n")


class ValidatePrPolicyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    def validate(
        self,
        *,
        title: str,
        body: str,
        branch: str,
        changed_files: list[str],
        commit_subjects: list[str],
        base_ancestor: bool = True,
    ) -> list[str]:
        return validate_policy(
            policy_path=REPOSITORY_ROOT / ".github/contributing-policy.toml",
            title=title,
            body_file=write_text(self.root, "body.md", body),
            branch=branch,
            changed_files_file=write_lines(self.root, "changed-files.txt", changed_files),
            commit_subjects_file=write_lines(self.root, "commit-subjects.txt", commit_subjects),
            base_ancestor=base_ancestor,
        )

    def assertInvalid(self, errors: list[str], fragment: str) -> None:
        self.assertTrue(errors, "expected validation errors")
        self.assertTrue(any(fragment in error for error in errors), f"missing {fragment!r} in {errors!r}")

    def test_valid_font_change_passes(self) -> None:
        errors = self.validate(
            title="feat(font): add glyph parser",
            body=make_body(),
            branch="feat/font-parser",
            changed_files=["kalligraphie/font/core/src/main/kotlin/Parser.kt", "CHANGELOG.md"],
            commit_subjects=["feat(font): add glyph parser", "docs: explain the parser"],
        )
        self.assertEqual(errors, [])

    def test_retired_scope_is_rejected(self) -> None:
        errors = self.validate(
            title="feat(shared): add glyph parser",
            body=make_body(),
            branch="feat/font-parser",
            changed_files=["kalligraphie/font/core/src/main/kotlin/Parser.kt", "CHANGELOG.md"],
            commit_subjects=["feat(shared): add glyph parser"],
        )
        self.assertInvalid(errors, "scope")

    def test_invalid_title_is_rejected(self) -> None:
        errors = self.validate(
            title="add glyph parser",
            body=make_body(),
            branch="feat/font-parser",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(font): add glyph parser"],
        )
        self.assertInvalid(errors, "title")

    def test_invalid_branch_is_rejected(self) -> None:
        errors = self.validate(
            title="feat(font): add glyph parser",
            body=make_body(),
            branch="main/font-parser",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(font): add glyph parser"],
        )
        self.assertInvalid(errors, "branch")

    def test_missing_required_section_is_rejected(self) -> None:
        body = make_body().replace("## Screenshots (if applicable)", "## Screenshots")
        errors = self.validate(
            title="feat(font): add glyph parser",
            body=body,
            branch="feat/font-parser",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(font): add glyph parser"],
        )
        self.assertInvalid(errors, "Screenshots (if applicable)")

    def test_multiple_change_types_are_rejected(self) -> None:
        errors = self.validate(
            title="feat(font): add glyph parser",
            body=make_body(selected_types=["feat", "docs"]),
            branch="feat/font-parser",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(font): add glyph parser"],
        )
        self.assertInvalid(errors, "exactly one")

    def test_missing_changelog_file_is_rejected(self) -> None:
        errors = self.validate(
            title="feat(font): add glyph parser",
            body=make_body(),
            branch="feat/font-parser",
            changed_files=["src/main.py"],
            commit_subjects=["feat(font): add glyph parser"],
        )
        self.assertInvalid(errors, "CHANGELOG.md")

    def test_no_changelog_requires_justification(self) -> None:
        errors = self.validate(
            title="feat(font): add glyph parser",
            body=make_body(changelog_state="no", changelog_reason=""),
            branch="feat/font-parser",
            changed_files=["src/main.py"],
            commit_subjects=["feat(font): add glyph parser"],
        )
        self.assertInvalid(errors, "justification")

    def test_documentation_change_requires_documentation_decision(self) -> None:
        errors = self.validate(
            title="docs(docs): update user guide",
            body=make_body(selected_types=["docs"], changelog_state="no", documentation_updated=False),
            branch="docs/update-guide",
            changed_files=["docs/guide.md"],
            commit_subjects=["docs(docs): update user guide"],
        )
        self.assertInvalid(errors, "documentation")

    def test_non_conventional_commit_subject_is_rejected(self) -> None:
        errors = self.validate(
            title="feat(font): add glyph parser",
            body=make_body(),
            branch="feat/font-parser",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["add glyph parser"],
        )
        self.assertInvalid(errors, "commit")

    def test_non_ancestor_base_is_rejected(self) -> None:
        errors = self.validate(
            title="feat(font): add glyph parser",
            body=make_body(),
            branch="feat/font-parser",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(font): add glyph parser"],
            base_ancestor=False,
        )
        self.assertInvalid(errors, "ancestor")


if __name__ == "__main__":
    unittest.main()
