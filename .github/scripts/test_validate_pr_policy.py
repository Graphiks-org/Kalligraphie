import tempfile
import unittest
from pathlib import Path

from validate_pr_policy import validate_policy


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


def make_body(
    *,
    selected_type: str | None = "feat",
    selected_types: list[str] | None = None,
    changelog_state: str = "updated",
    changelog_reason: str = "release notes are tracked elsewhere",
    documentation_updated: bool = False,
) -> str:
    selected_types = selected_types or ([selected_type] if selected_type else [])
    type_lines = []
    for item in ALLOWED_TYPES:
        checked = "x" if item in selected_types else " "
        label = {
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
        }[item]
        type_lines.append(f"- [{checked}] `{item}` — {label}")

    changelog_lines = [
        f"- [{'x' if changelog_state == 'updated' else ' '}] CHANGELOG.md has been updated",
        f"- [{'x' if changelog_state == 'no' else ' '}] No changelog update needed: {changelog_reason}",
    ]
    documentation_line = f"- [{'x' if documentation_updated else ' '}] Documentation updated if needed"

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
            documentation_line,
            "",
            "## Screenshots (if applicable)",
            "",
            "## Additional Notes",
            "",
        ]
    )


def write_text_file(directory: Path, name: str, content: str) -> Path:
    path = directory / name
    path.write_text(content, encoding="utf-8")
    return path


def write_lines_file(directory: Path, name: str, lines: list[str]) -> Path:
    return write_text_file(directory, name, "\n".join(lines) + "\n")


class ValidatePrPolicyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)
        self.policy = write_text_file(
            self.root,
            "contributing-policy.toml",
            "\n".join(
                [
                    'allowed_types = ["feat", "fix", "build", "chore", "ci", "docs", "perf", "refactor", "test", "style"]',
                    'allowed_scopes = ["shared", "buildSrc", "docs", "release"]',
                    'branch_prefixes = ["feat/", "fix/", "chore/"]',
                    'changelog_files = ["CHANGELOG.md"]',
                    'required_sections = ["Description", "Type of Change", "Checklist", "Screenshots (if applicable)", "Additional Notes"]',
                    "",
                ]
            ),
        )

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
        base_ancestor: bool,
    ) -> list[str]:
        body_file = write_text_file(self.root, "body.md", body)
        changed_files_file = write_lines_file(self.root, "changed-files.txt", changed_files)
        commit_subjects_file = write_lines_file(self.root, "commit-subjects.txt", commit_subjects)
        return validate_policy(
            policy_path=self.policy,
            title=title,
            body_file=body_file,
            branch=branch,
            changed_files_file=changed_files_file,
            commit_subjects_file=commit_subjects_file,
            base_ancestor=base_ancestor,
        )

    def assertInvalid(self, errors: list[str], expected_fragment: str) -> None:
        self.assertTrue(errors, "expected validation errors")
        self.assertTrue(
            any(expected_fragment in error for error in errors),
            f"missing fragment {expected_fragment!r} in {errors!r}",
        )

    def test_valid_pr_passes(self) -> None:
        errors = self.validate(
            title="feat(shared): add policy validation",
            body=make_body(selected_type="feat", changelog_state="updated", documentation_updated=False),
            branch="feat/add-policy-validation",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(shared): add policy validation", "docs: update release notes"],
            base_ancestor=True,
        )
        self.assertEqual(errors, [])

    def test_invalid_title_is_rejected(self) -> None:
        errors = self.validate(
            title="add policy validation",
            body=make_body(),
            branch="feat/add-policy-validation",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(shared): add policy validation"],
            base_ancestor=True,
        )
        self.assertInvalid(errors, "title")

    def test_invalid_branch_is_rejected(self) -> None:
        errors = self.validate(
            title="feat(shared): add policy validation",
            body=make_body(),
            branch="main/add-policy-validation",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(shared): add policy validation"],
            base_ancestor=True,
        )
        self.assertInvalid(errors, "branch")

    def test_missing_required_body_section_is_rejected(self) -> None:
        body = "\n".join(
            [
                "## Description",
                "",
                "Brief summary.",
                "",
                "## Type of Change",
                "",
                "- [x] `feat` — New feature",
                "- [ ] `fix` — Bug fix",
                "- [ ] `build` — Build or dependencies",
                "- [ ] `chore` — Maintenance or tooling",
                "- [ ] `ci` — CI/CD configuration",
                "- [ ] `docs` — Documentation",
                "- [ ] `perf` — Performance improvement",
                "- [ ] `refactor` — Refactoring",
                "- [ ] `test` — Tests",
                "- [ ] `style` — Code style",
                "",
                "## Checklist",
                "",
                "- [x] CHANGELOG.md has been updated",
                "- [ ] No changelog update needed: release notes are tracked elsewhere",
                "- [ ] Documentation updated if needed",
                "",
                "## Additional Notes",
                "",
            ]
        )
        errors = self.validate(
            title="feat(shared): add policy validation",
            body=body,
            branch="feat/add-policy-validation",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(shared): add policy validation"],
            base_ancestor=True,
        )
        self.assertInvalid(errors, "Screenshots (if applicable)")

    def test_two_selected_change_types_are_rejected(self) -> None:
        errors = self.validate(
            title="feat(shared): add policy validation",
            body=make_body(selected_types=["feat", "docs"]),
            branch="feat/add-policy-validation",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(shared): add policy validation"],
            base_ancestor=True,
        )
        self.assertInvalid(errors, "exactly one")

    def test_missing_changelog_file_is_rejected(self) -> None:
        errors = self.validate(
            title="feat(shared): add policy validation",
            body=make_body(changelog_state="updated"),
            branch="feat/add-policy-validation",
            changed_files=["src/main.py"],
            commit_subjects=["feat(shared): add policy validation"],
            base_ancestor=True,
        )
        self.assertInvalid(errors, "CHANGELOG.md")

    def test_no_changelog_without_justification_is_rejected(self) -> None:
        errors = self.validate(
            title="feat(shared): add policy validation",
            body=make_body(changelog_state="no", changelog_reason=""),
            branch="feat/add-policy-validation",
            changed_files=["src/main.py"],
            commit_subjects=["feat(shared): add policy validation"],
            base_ancestor=True,
        )
        self.assertInvalid(errors, "justification")

    def test_docs_changes_require_documentation_decision(self) -> None:
        errors = self.validate(
            title="docs(shared): update user guide",
            body=make_body(selected_type="docs", changelog_state="no", changelog_reason="documentation only"),
            branch="chore/update-guide",
            changed_files=["docs/guide.md"],
            commit_subjects=["docs(shared): update user guide"],
            base_ancestor=True,
        )
        self.assertInvalid(errors, "documentation")

    def test_non_conventional_commit_subject_is_rejected(self) -> None:
        errors = self.validate(
            title="feat(shared): add policy validation",
            body=make_body(),
            branch="feat/add-policy-validation",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["add policy validation"],
            base_ancestor=True,
        )
        self.assertInvalid(errors, "commit")

    def test_non_ancestor_base_is_rejected(self) -> None:
        errors = self.validate(
            title="feat(shared): add policy validation",
            body=make_body(),
            branch="feat/add-policy-validation",
            changed_files=["src/main.py", "CHANGELOG.md"],
            commit_subjects=["feat(shared): add policy validation"],
            base_ancestor=False,
        )
        self.assertInvalid(errors, "ancestor")

if __name__ == "__main__":
    unittest.main()
