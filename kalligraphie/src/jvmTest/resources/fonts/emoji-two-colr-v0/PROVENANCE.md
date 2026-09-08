# EmojiTwo COLRv0 Fixture Provenance

- File: `EmojiTwoCOLRv0.ttf`.
- Immutable source: [`Emoji-COLRv0/Emoji-COLRv0`](https://github.com/Emoji-COLRv0/Emoji-COLRv0)
  repository, commit `1b81dcf46545252bdc82f6c6309335fb1c73b8c9`,
  `fonts/EmojiTwoCOLRv0.ttf`.
- Pinned download URL:
  `https://raw.githubusercontent.com/Emoji-COLRv0/Emoji-COLRv0/1b81dcf46545252bdc82f6c6309335fb1c73b8c9/fonts/EmojiTwoCOLRv0.ttf`.
- SHA-256 digest:
  `b5ba9f3a70f5d674f85d12f3f32e846f396965753aaed5b3d1e2ffee3fe94ef4`.
- License: CC-BY-4.0; attribution EmojiTwo / EmojiOne 2.2 / Ranks.com and the
  EmojiTwo community, as detailed in `LICENSE.md`.
- Structurally verified format: TrueType with COLR version 0 and CPAL version
  0.

## Independent oracle

The oracle is the font's OpenType encoding, read directly from its tables
before the test was added:

- `cmap` maps `U+1F600` to glyph ID 1443;
- COLR record 1443 references glyph IDs 2650, 10717, 10718, 10719, 10720,
  and 10721, in that order;
- their CPAL indices are 1182, 265, 162, 1102, 1233, and 265;
- CPAL palette 0 contains RGBA `(255,221,103,255)`, `(102,78,39,255)`,
  `(76,53,38,255)`, `(255,113,127,255)`, `(255,255,255,255)`, and
  `(102,78,39,255)` respectively.

These values were extracted independently of the implementation under test by
a Python 3.14.7 structural reader (`struct`, big-endian integers) and
cross-checked with Fontconfig 2.18.3 for the `EmojiTwo COLRv0 Regular`
identity.
