from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Iterable

import tomllib


TITLE_RE = re.compile(
    r"^(?P<type>[a-z]+)(?:\((?P<scope>[A-Za-z0-9_-]+)\))?: (?P<description>\S.*)$"
)

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


def read_lines(path: str | Path) -> list[str]:
    return [line.strip() for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def read_text(path: str | Path) -> str:
    return Path(path).read_text(encoding="utf-8")


def load_policy(policy_path: str | Path) -> dict[str, object]:
    return tomllib.loads(Path(policy_path).read_text(encoding="utf-8"))


def section_block(body: str, heading: str) -> str:
    marker = f"## {heading}"
    lines = body.splitlines()
    start = None
    for index, line in enumerate(lines):
        if line.strip() == marker:
            start = index + 1
            break
    if start is None:
        return ""
    end = len(lines)
    for index in range(start, len(lines)):
        if lines[index].startswith("## "):
            end = index
            break
    return "\n".join(lines[start:end]).strip()


def heading_present(body: str, heading: str) -> bool:
    return any(line.strip() == f"## {heading}" for line in body.splitlines())


def parse_conventional_commit(subject: str) -> tuple[str, str | None, str] | None:
    match = TITLE_RE.match(subject.strip())
    if not match:
        return None
    return match.group("type"), match.group("scope"), match.group("description")


def validate_title(title: str, policy: dict[str, object]) -> list[str]:
    errors: list[str] = []
    match = TITLE_RE.match(title.strip())
    if not match:
        return ["title must follow Conventional Commits format: <type>(<scope>): <description>"]

    allowed_types = set(policy["allowed_types"])
    allowed_scopes = set(policy["allowed_scopes"])
    commit_type = match.group("type")
    scope = match.group("scope")

    if commit_type not in allowed_types:
        errors.append(f"title type {commit_type!r} is not allowed")
    if scope is not None and scope not in allowed_scopes:
        errors.append(f"title scope {scope!r} is not allowed")
    return errors


def validate_branch(branch: str, policy: dict[str, object]) -> list[str]:
    prefixes = tuple(policy["branch_prefixes"])
    if not branch.startswith(prefixes):
        return [f"branch {branch!r} must start with one of: {', '.join(prefixes)}"]
    return []


def validate_required_sections(body: str, policy: dict[str, object]) -> list[str]:
    missing = [heading for heading in policy["required_sections"] if not heading_present(body, heading)]
    if missing:
        return [f"PR body is missing required section(s): {', '.join(missing)}"]
    return []


def validate_type_selection(body: str, policy: dict[str, object]) -> list[str]:
    block = section_block(body, "Type of Change")
    selected: list[str] = []
    for line in block.splitlines():
        line = line.strip()
        if not line.startswith("- [x]"):
            continue
        match = re.search(r"`([a-z]+)`", line)
        if match and match.group(1) in policy["allowed_types"]:
            selected.append(match.group(1))
    if len(selected) != 1:
        return [f"PR body must select exactly one change type, found {len(selected)}"]
    return []


def validate_changelog_selection(body: str, changed_files: Iterable[str], policy: dict[str, object]) -> list[str]:
    checklist = section_block(body, "Checklist")
    lines = [line.strip() for line in checklist.splitlines()]
    changed = set(changed_files)
    changelog_files = list(policy["changelog_files"])
    update_markers = {
        name: f"- [x] {name} has been updated"
        for name in changelog_files
    }
    no_update_marker = "- [x] No changelog update needed:"

    checked_updates = [name for name, marker in update_markers.items() if marker in lines]
    no_update_checked = any(line.startswith(no_update_marker) for line in lines)

    if checked_updates:
        errors = []
        missing_files = [name for name in checked_updates if name not in changed]
        if missing_files:
            errors.append(f"changed files are missing required changelog file(s): {', '.join(missing_files)}")
        if len(checked_updates) != len(changelog_files):
            errors.append("PR body must select all configured changelog files when declaring a changelog update")
        if no_update_checked:
            errors.append("PR body must not select no-changelog together with changelog updates")
        return errors

    if no_update_checked:
        reason_line = next((line for line in lines if line.startswith(no_update_marker)), "")
        reason_text = reason_line.removeprefix(no_update_marker).strip()
        if not reason_text:
            return ["PR body must provide a justification for the no-changelog decision"]
        return []

    return ["PR body must select the configured changelog files or a no-changelog decision"]


def validate_documentation_selection(body: str, changed_files: Iterable[str]) -> list[str]:
    changed_docs = any(path.startswith("docs/") for path in changed_files)
    if not changed_docs:
        return []

    checklist = section_block(body, "Checklist")
    lines = [line.strip() for line in checklist.splitlines()]
    docs_selected = "- [x] Documentation updated if needed" in lines
    if not docs_selected:
        return ["PR changes docs/ paths and must select the documentation update decision"]
    return []


def validate_commit_subjects(commit_subjects: Iterable[str], policy: dict[str, object]) -> list[str]:
    errors: list[str] = []
    allowed_types = set(policy["allowed_types"])
    allowed_scopes = set(policy["allowed_scopes"])
    for subject in commit_subjects:
        parsed = parse_conventional_commit(subject)
        if parsed is None:
            errors.append(f"commit subject {subject!r} is not Conventional Commits compliant")
            continue
        commit_type, scope, _description = parsed
        if commit_type not in allowed_types:
            errors.append(f"commit subject {subject!r} uses an unsupported type")
        if scope is not None and scope not in allowed_scopes:
            errors.append(f"commit subject {subject!r} uses an unsupported scope")
    return errors


def validate_policy(
    *,
    policy_path: str | Path,
    title: str,
    body_file: str | Path,
    branch: str,
    changed_files_file: str | Path,
    commit_subjects_file: str | Path,
    base_ancestor: bool,
) -> list[str]:
    policy = load_policy(policy_path)
    body = read_text(body_file)
    changed_files = read_lines(changed_files_file)
    commit_subjects = read_lines(commit_subjects_file)

    errors: list[str] = []
    errors.extend(validate_title(title, policy))
    errors.extend(validate_branch(branch, policy))
    errors.extend(validate_required_sections(body, policy))
    errors.extend(validate_type_selection(body, policy))
    errors.extend(validate_changelog_selection(body, changed_files, policy))
    errors.extend(validate_documentation_selection(body, changed_files))
    errors.extend(validate_commit_subjects(commit_subjects, policy))

    if not base_ancestor:
        errors.append("base commit is not an ancestor of the head commit")
    return errors


def parse_bool(value: str) -> bool:
    normalized = value.strip().lower()
    if normalized in {"1", "true", "yes", "y"}:
        return True
    if normalized in {"0", "false", "no", "n"}:
        return False
    raise argparse.ArgumentTypeError("expected a boolean value")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--body-file", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--changed-files-file", required=True)
    parser.add_argument("--commit-subjects-file", required=True)
    parser.add_argument("--base-ancestor", required=True, type=parse_bool)
    args = parser.parse_args(argv)

    errors = validate_policy(
        policy_path=args.policy,
        title=args.title,
        body_file=args.body_file,
        branch=args.branch,
        changed_files_file=args.changed_files_file,
        commit_subjects_file=args.commit_subjects_file,
        base_ancestor=args.base_ancestor,
    )
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
