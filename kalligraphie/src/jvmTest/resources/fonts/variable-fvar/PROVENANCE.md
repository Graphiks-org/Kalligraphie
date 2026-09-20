# SyntheticVariable.ttf provenance

Deterministic, reproducible test fixture: the base Liberation Sans Regular font with an `fvar`
table added and nothing else (no `gvar`), so glyph outlines remain those of the base font.

## Base font

- Path (relative to the repository root):
  `kalligraphie/src/jvmTest/resources/fonts/liberation/LiberationSans-Regular.ttf`
- SHA-256: `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`

## Generation

- Requirement: `fonttools==4.65.0` (the version used to produce the committed bytes).
- Command (run from this directory):

  ```sh
  python3 build_variable_fixture.py
  ```

  The script resolves the base font and output paths from its own location, so it may also be run
  from the repository root as
  `python3 kalligraphie/src/jvmTest/resources/fonts/variable-fvar/build_variable_fixture.py`.
- The script loads the base font with `recalcTimestamp=False`, so regeneration is
  byte-deterministic (the base font's `head.modified` is preserved) and reproduces the SHA-256
  below.

## Committed artifact

- File: `SyntheticVariable.ttf`
- SHA-256: `c91ada6b0f83afd38bde0145a83b03008f1d4f9c10b0d6dc629ec8be708be4f3`
- Contents:
  - Axes: `opsz` (min 8, default 14, max 144) and `wght` (min 100, default 400, max 900).
  - One named instance: `subfamilyNameID=258`, coordinates `opsz=14`, `wght=700`.
  - No `avar` and no `gvar` table.
