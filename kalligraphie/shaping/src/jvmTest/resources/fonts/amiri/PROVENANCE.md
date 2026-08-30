# Amiri shaping fixture

## Source

- Upstream project: [aliftype/amiri](https://github.com/aliftype/amiri)
- Font: Amiri Regular 1.002; units per em: `1000`
- Pinned source revision: [`ade3d1533e06b2b1462ffcde8e08b129627ca360`](https://github.com/google/fonts/tree/ade3d1533e06b2b1462ffcde8e08b129627ca360/ofl/amiri)
- Source URL: <https://raw.githubusercontent.com/google/fonts/ade3d1533e06b2b1462ffcde8e08b129627ca360/ofl/amiri/Amiri-Regular.ttf>
- SHA-256 of `Amiri-Regular.ttf`: `ab391c4147d054c48976e98322ad0eefe1427aa0e0502a12a4c75d80a70cfcd7`
- Size of `Amiri-Regular.ttf`: `431116` bytes
- License: [SIL Open Font License 1.1](OFL.txt), SHA-256 `b185b109c5342bf3fa6d812f3ca37bd52f94d7086536eae804ec1c699144d2dd`

The font is an unchanged test fixture. It was not subsetted, hinted,
normalized, or regenerated.

## Independent shaping oracle

The expectations were produced outside the implementation under test by an
external C ABI audit probe linked with HarfBuzz `14.3.0`, against this exact
TTF. The `ffi` request used LTR, `Latn`, `en`, BiDi level `0`, BOT/EOT, the
pinned HarfBuzz feature policy, and a layout size of `1000`:

```text
glyphs: [6631]
advance: [795]
GDEF ligature carets: AVAILABLE, logical boundaries [1, 2], positions [269, 537]
```

This oracle is fixed fixture data. The test calls the Kotlin backend once and
compares its result with these constants; it neither invokes a second
HarfBuzz call nor uses the backend under test as an oracle.
