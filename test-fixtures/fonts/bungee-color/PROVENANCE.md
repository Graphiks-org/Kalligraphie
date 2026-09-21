# Bungee Color Fixture Provenance

- File: `BungeeColor-Regular.ttf`.
- Immutable source: [`google/fonts`](https://github.com/google/fonts) repository,
  commit `5e35378e6bda803962ee6fd257e444a7d459660d`,
  `ofl/bungeecolor/BungeeColor-Regular.ttf`.
- Pinned download URL:
  `https://raw.githubusercontent.com/google/fonts/5e35378e6bda803962ee6fd257e444a7d459660d/ofl/bungeecolor/BungeeColor-Regular.ttf`.
- SHA-256 digest:
  `cf21a786e54f43694f4edbb51a38f81331a4c3414217c524c8cb2d091aa7fd63`.
- License: SIL Open Font License 1.1; copyright The Bungee Project Authors and
  David Jonathan Ross, as specified by `OFL.txt` in the source repository.
- Structurally verified format: TrueType with COLR version 0 and CPAL version
  0, with nine palettes of two entries.

## Independent oracle

Before the test was added, the oracle was extracted by a Python 3.14.7
structural reader (`struct`, big-endian integers) without calling the
implementation under test:

- `cmap` maps `U+0041` to glyph ID 43;
- COLR record 43 references layer records 86 and 87, in that order, for glyph
  IDs 292 and 293 with CPAL indices 0 and 1;
- CPAL palette 0 contains RGBA `(201,9,0,255)` and `(255,149,128,255)`;
- CPAL palette 1 contains RGBA `(255,255,255,255)` and
  `(232,232,231,255)`.

The values are cross-checked against the tables published in the pinned Google
Fonts revision.
