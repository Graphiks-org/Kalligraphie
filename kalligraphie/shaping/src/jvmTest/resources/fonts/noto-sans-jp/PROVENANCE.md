# Noto Sans JP vertical-metrics fixture

## Source

- Upstream project: [Google Fonts — Noto Sans JP](https://github.com/google/fonts/tree/5e35378e6bda803962ee6fd257e444a7d459660d/ofl/notosansjp)
- Pinned source revision: `5e35378e6bda803962ee6fd257e444a7d459660d`
- Source URL: <https://raw.githubusercontent.com/google/fonts/5e35378e6bda803962ee6fd257e444a7d459660d/ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf>
- SHA-256 of the source variable TTF: `c2f3b4d463500a2ddcd3849cded1fceeb9fd6d1c32e6cbecd568453ba50fc68f`
- License: [SIL Open Font License 1.1](OFL.txt), SHA-256 `1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9`

`NotoSansJP-VerticalFixture.ttf` is a deliberately minimal derived test
artifact. It was generated from that exact source with FontTools 4.60.1:

```sh
pyftsubset 'NotoSansJP[wght].ttf' --unicodes=U+0041,U+65E5 \
  --layout-features='*' --output-file=NotoSansJP-VerticalFixture.ttf
```

Its SHA-256 is
`f6ac0c94eb58e14c5fd6cfa5aa867ea029f1a21b449a2b58031bb986e940997b`.
The audited subset retains the OpenType `vhea`, `vmtx`, `vert`, and `vrt2`
data required by the consumer scenario; it must not be used as a general font
fixture.

## Independent vertical oracle

The expected default-instance shaping was audited outside the implementation
under test with HarfBuzz 14.4.0:

```sh
hb-shape --direction=ttb --features=vert=1,vrt2=1 \
  NotoSansJP-VerticalFixture.ttf '日A' --output-format=json
```

```text
日: glyph 2, advance (0, -1000), offset (-500, -880)
A: glyph 1, advance (0, -1000), offset (-287, -880)
```

The consumer test records only the externally audited, renderer-visible
consequences: a one-em vertical advance, upright ideographic orientation, and
sideways Latin orientation. It never invokes this command at runtime and does
not use the embedded backend as its oracle.
