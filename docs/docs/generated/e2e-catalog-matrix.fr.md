# Catalogue d'attentes end-to-end

Généré depuis `ExpectationCatalog` par `./gradlew :kalligraphie:e2e:updateE2eGolden` ; ne pas modifier à la main.

## Totaux

| Statut | Entrées |
| --- | --- |
| Supporté | 21 |
| Rejet attendu | 2 |
| Pas encore | 10 |
| Hors périmètre | 1 |

## CONTAINER

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `container.woff2` | WOFF 2.0 container wrapping | — | Pas encore ; corpus non acquis |
| `container.woff` | WOFF 1.0 container wrapping | — | Pas encore ; corpus non acquis |

## OUTLINE

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `outline.glyf-simple-composite` | TrueType glyf simple and composite outlines | liberation | Supporté depuis 35c4422c |
| `outline.cff1-static` | Static CFF 1 Type 2 charstrings | cff-liberation | Supporté depuis 4b156eac |
| `outline.cff2-static` | Static CFF 2 charstrings without variation deltas | cff2-liberation | Supporté depuis 4b156eac |

## METRICS

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `metrics.vvar-advance-height` | Variable face carrying VVAR: the scene loads the face and rasterises its outline, and observes no vertical advance delta | kalligraphie-var-vvar | Supporté depuis 4b156eac |
| `metrics.vvar-real-font` | vvar vertical metrics from a real font | — | Pas encore ; aucune police réelle connue |
| `metrics.mvar-real-font` | mvar metric variations from a real font | — | Pas encore ; aucune police réelle connue |

## VARIATION

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `variation.avar-v2` | avar version 2 segment maps | — | Pas encore ; aucune police réelle connue |
| `variation.cvar` | cvar CVT variations | — | Pas encore ; aucune police réelle connue |
| `variation.varc` | VARC variable composite glyphs | — | Pas encore ; aucune police réelle connue |
| `variation.stat` | STAT style attributes | — | Pas encore ; corpus non acquis |

## COLOR

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `color.colr-v0-single-glyph` | COLR v0 + CPAL v0 single-glyph paint graph | emoji-two-colr-v0 | Supporté depuis fa405247 |
| `color.colr-v0-alphabet-sheet` | COLR v0 + CPAL v0 Latin alphabet sheet | bungee-color | Supporté depuis fa405247 |
| `color.colr-v0-emoji-sheet` | COLR v0 + CPAL v0 emoji alphabet sheet | emoji-two-colr-v0 | Supporté depuis fa405247 |
| `color.colr-cff` | CFF-backed COLR glyphs | — | Hors périmètre : CFF-in-COLR is not part of the supported paint surface; the rationale is recorded in font-management.md. |
| `color.colr-v1-variable` | Variable COLR v1 paint graphs; blocked today by the CPU compositor, which does not composite GlyphClip nodes | kalligraphie-var-colr | Pas encore ; comportement actuel épinglé (`spec:§5 color`) |
| `color.cpal-variable` | variable CPAL palettes | — | Pas encore ; corpus non acquis |

## BITMAP

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `bitmap.ebdt-format1` | EBLC/EBDT embedded bitmap strikes, format 1 | skia-ebdt-format1 | Supporté depuis fa405247 |
| `bitmap.cbdt-png.u1f600.16` | CBLC/CBDT PNG colour strike | skia-cbdt | Supporté depuis 4b156eac |
| `bitmap.sbix-png.u1f600.16` | sbix PNG strike | skia-sbix | Supporté depuis 4b156eac |

## SCRIPT

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `script.latin.composed-line` | Latin composed line through the paragraph facade | liberation | Supporté depuis fa405247 |
| `script.greek.composed-line` | Greek composed line through the paragraph facade | liberation | Supporté depuis fa405247 |
| `script.cyrillic.composed-line` | Cyrillic composed line through the paragraph facade | liberation | Supporté depuis fa405247 |
| `script.arabic.composed-line` | Arabic composed line through the paragraph facade | amiri | Supporté depuis fa405247 |
| `script.devanagari.composed-line` | Devanagari composed line through the paragraph facade | noto-devanagari | Supporté depuis fa405247 |
| `script.mixed.composed-line` | Mixed-script composed line over the three required faces (liberation, amiri, noto-devanagari) | liberation | Supporté depuis fa405247 |
| `script.latin.outline-sheet` | Latin outline alphabet sheet | liberation | Supporté depuis fa405247 |
| `script.greek.outline-sheet` | Greek outline alphabet sheet | liberation | Supporté depuis fa405247 |
| `script.cyrillic.outline-sheet` | Cyrillic outline alphabet sheet | liberation | Supporté depuis fa405247 |
| `script.arabic.outline-sheet` | Arabic outline alphabet sheet | amiri | Supporté depuis fa405247 |
| `script.devanagari.outline-sheet` | Devanagari outline alphabet sheet | noto-devanagari | Supporté depuis fa405247 |

## ROBUSTNESS

| Entrée | Technologie | Police | Statut |
| --- | --- | --- | --- |
| `robustness.truncated-sfnt` | Truncated TrueType container | liberation | Rejet attendu `font.out-of-bounds` à DECODE |
| `robustness.empty-input` | Zero-byte font source | liberation | Rejet attendu `font.invalid-font-data` à DECODE |

