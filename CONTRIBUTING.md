# Contributing Guide

Thank you for contributing to this project. This file is the sole contribution guide for the repository and defines the automated contract enforced by the pull request policy, CI checks, and the `master` branch ruleset. It also records maintainer-reviewed expectations that apply when relevant.

## Code of Conduct

This project follows a [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold it.

## Reporting a Bug

**Before creating a report:**
- Check if the bug has already been reported
- Make sure you're on the latest version
- Check existing discussions

**When creating a report:**
- Use the [Bug Report](.github/ISSUE_TEMPLATE/bug_report.md) template
- Include steps to reproduce
- Provide environment details (OS, JDK version, etc.)
- Add logs or screenshots if possible

## Suggesting a Feature

- Use the [Feature Request](.github/ISSUE_TEMPLATE/feature_request.md) template
- Describe the problem you want to solve
- Explain why this feature would be useful
- If possible, suggest an implementation approach

## Submitting a Pull Request

Every pull request must satisfy the repository contract below before it can merge.

### Machine-enforced blocking rules

These are the checks that must pass before merge. They are enforced by the PR policy, CI, or the protected `master` branch ruleset.

- Open the PR from a branch prefixed with `feat/`, `fix/`, or `chore/`.
- Keep history linear: rebase on the latest `master`, do not introduce merge commits, and keep the branch ancestry aligned with `master`.
- Use Conventional Commits for the PR title and every commit subject: `<type>(<scope>): <description>`.
- Allowed PR and commit types are `feat`, `fix`, `build`, `chore`, `ci`, `docs`, `perf`, `refactor`, `test`, and `style`.
- Allowed scopes are `font`, `buildSrc`, `docs`, and `release`.
- Use the exact [PR template](.github/PULL_REQUEST_TEMPLATE.md) headings: `Description`, `Type of Change`, `Checklist`, `Screenshots (if applicable)`, and `Additional Notes`.
- Select exactly one change type checkbox in the PR body.
- Record the changelog decision explicitly in the PR checklist:
  - check `CHANGELOG.md has been updated`, or
  - check `No changelog update needed:` and provide a justification.
- Record the documentation decision explicitly by checking or leaving unchecked `Documentation updated if needed`.
- The blocking GitHub check is `PR policy`.
- The `master` branch ruleset requires no direct pushes, one approval, resolved review conversations, branches up to date with `master`, linear history, and squash-only merges.
- Maintainer-only exceptions must stay limited to the bypass configuration of the GitHub repository ruleset.

Repository settings automatically delete head branches after successful merges.

### Maintainer-reviewed expectations

These items are reviewed by maintainers when applicable; they are not automatically enforced by CI or the branch ruleset.

- Keep commits atomic when practical.
- Run local verification before requesting review: `./gradlew :font:fontTest`.
- Reference the related issue in the PR description when relevant.
- Add screenshots when relevant.
- Keep the `Screenshots (if applicable)` and `Additional Notes` sections when relevant.

### Submission checklist

Before submitting a PR, make sure:

**Blocking checks**

- [ ] Title follows Conventional Commits format
- [ ] Commit subjects follow Conventional Commits format
- [ ] PR body uses the required template headings and exactly one change type
- [ ] `CHANGELOG.md` is updated, or the PR body justifies why no changelog update is needed
- [ ] Documentation decision is recorded in the PR body
- [ ] Branch is rebased on `master` with no merge commits
- [ ] Branch uses a permitted prefix: `feat/`, `fix/`, or `chore/`
- [ ] The PR targets a branch that satisfies the protected `master` ruleset

**Maintainer-reviewed expectations**

- [ ] Tests pass locally (`./gradlew :font:fontTest`)
- [ ] Commits are atomic when practical
- [ ] The PR description references the related issue when relevant
- [ ] Screenshots are included when relevant
- [ ] Additional notes are included when relevant

### Local Build

```bash
# Fast JVM tests
./gradlew :font:fontTest

# All tests
./gradlew allTests

# Generate and embed API docs into MkDocs
./gradlew :docs:embedDokkaIntoMkDocs
```

### Conventional Commits

This project uses [Conventional Commits](https://www.conventionalcommits.org/).

**Format:** `<type>(<scope>): <description>`

**Allowed types:**

| Type       | Usage                                              |
|-----------|----------------------------------------------------|
| `feat`    | New feature                                        |
| `fix`     | Bug fix                                            |
| `build`   | Build system or dependencies                       |
| `chore`   | Maintenance, tooling, dependencies                 |
| `ci`      | CI/CD configuration                                |
| `docs`    | Documentation changes                              |
| `perf`    | Performance improvement                            |
| `refactor`| Code refactoring (no behavior change)              |
| `test`    | Adding or fixing tests                             |
| `style`   | Code style (formatting, imports ordering)          |

**Scopes:** `font`, `buildSrc`, `docs`, `release`

**Examples:**
```
feat(font): add glyph parser
fix(buildSrc): resolve AGP compatibility issue
docs: update README with new badges
```

### Git Workflow

**Branches:**
- `master` — release branch (protected)
- `feat/*` — new features
- `fix/*` — bug fixes
- `chore/*` — maintenance, tooling

**Rules:**
- No direct commits to `master`
- Branches must be rebased on `master` before PR
- Merge commits are not allowed
- Commits should be atomic (one change per commit)

### Review Process

1. **Create a Pull Request**
   - Use the [PR template](.github/PULL_REQUEST_TEMPLATE.md)
   - Title in Conventional Commits format
   - Reference related issues
   - Select exactly one change type
   - Declare the changelog decision for `CHANGELOG.md`
   - Record the documentation decision explicitly

2. **Review**
   - At least 1 approval is required
   - `PR policy` must pass as the blocking check
   - All review conversations must be resolved
   - The branch must be up to date with `master`

3. **Merge**
   - Strategy: squash merge only
   - Squash title must keep Conventional Commits format
   - Delete branch after merge

### Versioning

This project follows [Semantic Versioning](https://semver.org/).

- `MAJOR` — breaking change
- `MINOR` — backward-compatible feature
- `PATCH` — backward-compatible fix

SNAPSHOT versions (`1.0.0-SNAPSHOT`) are used during active development.
Release versions are published via the release workflow (`releaseVersion` property).

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
