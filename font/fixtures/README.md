# Font Fixtures

This directory contains vendored or synthetic non-GPU font/text fixtures for
pure Kotlin text validation.

Rules:

- Fixture creation may download files from official project sources.
- Ordinary tests and validators must not download network resources.
- Accepted licenses for this fixture wave are SIL OFL 1.1 and Apache-2.0.
- Every vendored asset must appear in `provenance/index.json` with stable
  provenance fields enforced by the validator:
  - `fixtureId` and `familyId`;
  - `ownerTickets` for related PKT/KFONT rows;
  - `source.kind`, `source.project`, `source.url`, and `source.version`;
  - `license.id` and `license.path`;
  - `assets[].path`, `assets[].sha256`, `assets[].sizeBytes`, and
    `assets[].role`;
  - `expectedDumps`;
  - `nonClaims`, including `no-complete-target-support-claim`.
- The total fixture payload budget is 20 MiB.
- These fixtures do not by themselves promote complete text, font, shaping,
  paragraph, color glyph, SVG, emoji, or GPU support.
