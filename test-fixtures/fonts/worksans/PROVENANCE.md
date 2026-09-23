# Work Sans variable-weight fixture

## Source

- Upstream project: [Google Fonts — Work Sans](https://github.com/google/fonts/tree/8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5/ofl/worksans)
- Font: Work Sans variable, `wght` axis `100`–`900` with default `400`, plus an `avar` segment map; units per em: `1000`
- Pinned source revision: `8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5`
- Source URL: <https://raw.githubusercontent.com/google/fonts/8b0a1d0f5983c89bc2b93f1b5fb55f9e252744b5/ofl/worksans/WorkSans%5Bwght%5D.ttf>
- SHA-256 of `WorkSans[wght].ttf`: `f50f61f2ba738e239442d40bf1069adb195c224b6a5a73a581fc2f3ed62a9f63`
- Size of `WorkSans[wght].ttf`: `361072` bytes
- License: [SIL Open Font License 1.1](OFL.txt), SHA-256 `749aca05078664ce682dce1b1b10096ac397cb088c1a6df4e1bb56f0092a9272`

The font is an unchanged upstream artifact. It was not subsetted, hinted,
normalized, or regenerated: it is committed whole, as the corpus rule requires,
because the catalogue scene that reads it wants real Latin text, real named
instances and real per-weight outlines rather than a single-glyph derivative.

## Why this family is in the corpus

The catalogue's variation axis had no executable evidence: the variable fonts
already committed are minimal fixtures — `kalligraphie-var-vvar` and
`kalligraphie-var-colr` are synthetic two-glyph files, `cff2-variable` is a
synthetic two-glyph CFF 2 file, and `noto-sans-jp` is a single-glyph subset — so
no scene could show a style change on real text. Work Sans carries `fvar`,
`avar`, `gvar`, `HVAR` and a `STAT` table with the nine named instances of the
`wght` axis, which is what the ladder scene reads: one word, five weights,
outlines and advances varying together.

HarfBuzz publishes no cross-checked oracle for this family, and none is claimed:
the scene's assertion is internal — the same text at five design weights must
produce five different ink profiles — and the fingerprint is the regression
authority.
