# SVG-in-OpenType Fixture Provenance

- Source: `13rac1/twemoji-color-font`, release `v15.1.0`, archive
  `TwitterColorEmoji-SVGinOT-15.1.0.zip`.
- Source archive SHA-256:
  `9075de7a1c9dd660782d02b5c5be1c1524e16db13a6d7d4264b9aabbd056b692`.
- Source font SHA-256:
  `42d4b2a827c9ed557dff88c2f4723cb93985003a81dee76797324d66d04a57b8`.
- License: CC-BY-4.0; see `LICENSE.md`.
- Transformation: a source-font subset retaining glyph ID 5, then renumbered
  to glyph ID 1 by FontTools 4.55.0 and lxml 5.3.0, run with Python 3.14.7.
  The command was:
  `pyftsubset TwitterColorEmoji-SVGinOT.ttf --gids=5 --output-file=TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf`.
- Decoded subset SHA-256:
  `3321f267b8a242d96c0790ac31becc74b7f57656762037e16f465be1adcd84d2`.
- Oracle: glyph ID 1's SVG document contains one group with two `translate`
  transforms, one scale transform, and two filled cubic paths in `#31373D`.
  The expected test values are calculated directly from these attributes and
  document numbers without calling Kalligraphie's SVG reader.
