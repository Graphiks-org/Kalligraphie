# Exact-source Liberation advance variant

This test creates an in-memory derivative of the unchanged audited Liberation Sans Regular
fixture at `/fonts/liberation/LiberationSans-Regular.ttf`; no binary fixture is overwritten.
Original provenance and OFL license remain in that fixture's resource directory.

An independent byte-level inspection of its SFNT directory establishes:

- `hmtx` starts at `0x218`, length `0x28f0`, with its directory checksum at `0xd0`.
- `hhea.numberOfHMetrics` is 2620, so glyph A=36 has its four-byte metric at
  `0x218 + 36 * 4 = 0x2a8`. The unsigned big-endian advance there is `0x0556` (1366),
  and its left bearing is `0x0004`.
- `head` starts at `0x13c`; its checksum adjustment is at `0x144`, originally `0xbd239d90`.
- The name table starts at `0x4992c`, length `0xb88`, and is unchanged.

The deterministic recipe changes only A's unsigned advance to `0x07d0` (2000).
The corresponding 32-bit hmtx checksum increases by `(2000 - 1366) << 16 = 0x027a0000`,
from `0x7cd4d31d` to `0x7f4ed31d`. Both the table data and its directory checksum contribute
that increase to the whole SFNT checksum, so the head adjustment decreases by `0x04f40000`,
to `0xb82f9d90`. The head table checksum excludes that adjustment and remains unchanged.
The complete SFNT checksum is therefore preserved at `0xb1b0afba`.

At numeric CTFont size 2048 (units/em 2048), the independently specified horizontal advance
is exactly 2000. A name-based lookup of the unmodified installed namesake would return 1366,
so this consumer assertion distinguishes exact-byte construction from family/style matching.
The production parser and CoreText probe do not generate the expected value or this recipe.
