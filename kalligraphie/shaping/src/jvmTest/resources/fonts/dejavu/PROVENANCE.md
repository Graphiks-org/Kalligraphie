# DejaVu Sans shaping fixture

## Source

- Upstream project: [DejaVu Fonts](https://dejavu-fonts.github.io/)
- Upstream release: `2.37`
- Source archive: [dejavu-sans-ttf-2.37.zip](https://sourceforge.net/projects/dejavu/files/dejavu/2.37/dejavu-sans-ttf-2.37.zip/download)
- SHA-256 of the archive: `5c6e497a2f36552cb5ffb112c413a6af39c0f3c47653662b90b4fa6499822fd7`
- Selected file: `dejavu-sans-ttf-2.37/ttf/DejaVuSans.ttf`
- SHA-256 of the checked-in TTF: `7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954`
- License: [`LICENSE.txt`](LICENSE.txt), copied byte-for-byte from the archive.

The file was extracted unchanged from the verified archive. It was not
subsetted, hinted, normalized, or regenerated.

## Independent shaping oracle

The expected `fi` result was frozen with the checked-in TTF and
`hb-shape 14.4.0`, outside the implementation under test:

```text
fi with liga=1: glyph 5042, advance 1290, cluster 0
fi with liga=0: glyphs 73 and 76, advances 721 and 569, clusters 0 and 1
```

An independent inspection of the font's GDEF table found no ligature caret for
that glyph. The shaping contract therefore records `ABSENT`; later layout is
responsible for its deterministic interpolation fallback. The test never calls
`hb-shape` at runtime and does not use the embedded HarfBuzz backend as its
oracle.
