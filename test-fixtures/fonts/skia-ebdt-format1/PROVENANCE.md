# Skia EBDT Format 1 Fixture Provenance

- File: `ebdt_fmt1.ttf`.
- Immutable source: [`google/skia`](https://chromium.googlesource.com/skia/)
  repository, commit `4b24321eb36cac92020d8154307de78ecf1d1e50`,
  `resources/fonts/ebdt_fmt1.ttf`.
- Pinned download URL:
  `https://chromium.googlesource.com/skia/+/4b24321eb36cac92020d8154307de78ecf1d1e50/resources/fonts/ebdt_fmt1.ttf?format=TEXT`.
- SHA-256 digest:
  `e99cebed4d9421bc89964b9dc6a3bedfc6a286029d64336a07844708cce76274`.
- License: BSD-3-Clause; see `LICENSE.md`.
- Structurally verified format: TrueType with EBLC version 2.0 and EBDT
  version 2.0, index-subtable format 1, image format 1, and one-bit depth.

## Independent oracle

The oracle describes the EBDT table, not output from the implementation under
test:

- `cmap` maps `U+1F600` to glyph ID 3;
- the exact 16 × 16 strike provides a 13 × 13 pixel image with origin `(0,13)`
  and horizontal advance 12;
- its thirteen alpha-mask rows are `.............`, `....#####....`,
  `..#########..`, `.##########..`, `.###########.`, `.###########.`,
  `############.`, `.###########.`, `.###########.`, `.###########.`,
  `..#########..`, `...#######...`, and `.....##......`.

The values were read independently of the implementation under test with a
Python 3.14.7 structural reader based on `struct` (big-endian integers), then
checked against the EBDT bytes with `xxd` 2025-01-14.
