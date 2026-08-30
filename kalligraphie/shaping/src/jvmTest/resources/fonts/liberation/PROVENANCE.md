# Liberation Sans shaping fixture

## Source

- Upstream project: [liberationfonts/liberation-fonts](https://github.com/liberationfonts/liberation-fonts)
- Upstream release: `2.1.5`, tag `2.1.5`, release commit `4b01920`
- Source archive: [liberation-fonts-ttf-2.1.5.tar.gz](https://github.com/liberationfonts/liberation-fonts/files/7261482/liberation-fonts-ttf-2.1.5.tar.gz)
- Selected file: `LiberationSans-Regular.ttf`
- SHA-256 of the checked-in TTF: `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`
- License: [`OFL-1.1.txt`](OFL-1.1.txt), copied from the verified fixture.

The file was extracted unchanged from the release archive. It was not
subsetted, hinted, normalized, or regenerated.

## Independent shaping oracle

The expected values were frozen with the checked-in TTF and `hb-shape 14.4.0`,
outside the implementation under test:

```text
x + U+0301: glyphs 91 and 707; accent offset (-249, -340)
שלום: glyphs 1293, 1285, 1292, 1305; clusters 3, 2, 1, 0;
      HarfBuzz glyph flags 0, 2, 2, 2
```

The test never calls `hb-shape` at runtime and does not use the embedded
HarfBuzz backend as its oracle.
