# End-to-end expectation catalog

Generated from `ExpectationCatalog` by `./gradlew :kalligraphie:e2e:updateE2eGolden`; do not edit by hand.

## Totals

| Status | Entries |
| --- | --- |
| Supported | 21 |
| Expected rejection | 2 |
| Not yet | 10 |
| Out of scope | 1 |

## CONTAINER

| Entry | Technology | Font | Status |
| --- | --- | --- | --- |
| `container.woff2` | WOFF 2.0 container wrapping | — | Not yet; corpus not acquired |
| `container.woff` | WOFF 1.0 container wrapping | — | Not yet; corpus not acquired |

## OUTLINE

| Entry | Technology | Font | Status |
| --- | --- | --- | --- |
| `outline.glyf-simple-composite` | TrueType glyf simple and composite outlines | liberation | Supported since 35c4422c |
| `outline.cff1-static` | Static CFF 1 Type 2 charstrings | cff-liberation | Supported since 4b156eac |
| `outline.cff2-static` | Static CFF 2 charstrings without variation deltas | cff2-liberation | Supported since 4b156eac |

## METRICS

| Entry | Technology | Font | Status |
| --- | --- | --- | --- |
| `metrics.vvar-advance-height` | Variable face carrying VVAR: the scene loads the face and rasterises its outline, and observes no vertical advance delta | kalligraphie-var-vvar | Supported since 4b156eac |
| `metrics.vvar-real-font` | vvar vertical metrics from a real font | — | Not yet; no real font known |
| `metrics.mvar-real-font` | mvar metric variations from a real font | — | Not yet; no real font known |

## VARIATION

| Entry | Technology | Font | Status |
| --- | --- | --- | --- |
| `variation.avar-v2` | avar version 2 segment maps | — | Not yet; no real font known |
| `variation.cvar` | cvar CVT variations | — | Not yet; no real font known |
| `variation.varc` | VARC variable composite glyphs | — | Not yet; no real font known |
| `variation.stat` | STAT style attributes | — | Not yet; corpus not acquired |

## COLOR

| Entry | Technology | Font | Status |
| --- | --- | --- | --- |
| `color.colr-v0-single-glyph` | COLR v0 + CPAL v0 single-glyph paint graph | emoji-two-colr-v0 | Supported since fa405247 |
| `color.colr-v0-alphabet-sheet` | COLR v0 + CPAL v0 Latin alphabet sheet | bungee-color | Supported since fa405247 |
| `color.colr-v0-emoji-sheet` | COLR v0 + CPAL v0 emoji alphabet sheet | emoji-two-colr-v0 | Supported since fa405247 |
| `color.colr-cff` | CFF-backed COLR glyphs | — | Out of scope: CFF-in-COLR is not part of the supported paint surface; the rationale is recorded in font-management.md. |
| `color.colr-v1-variable` | Variable COLR v1 paint graphs; blocked today by the CPU compositor, which does not composite GlyphClip nodes | kalligraphie-var-colr | Not yet; today pinned (`spec:§5 color`) |
| `color.cpal-variable` | variable CPAL palettes | — | Not yet; corpus not acquired |

## BITMAP

| Entry | Technology | Font | Status |
| --- | --- | --- | --- |
| `bitmap.ebdt-format1` | EBLC/EBDT embedded bitmap strikes, format 1 | skia-ebdt-format1 | Supported since fa405247 |
| `bitmap.cbdt-png.u1f600.16` | CBLC/CBDT PNG colour strike | skia-cbdt | Supported since 4b156eac |
| `bitmap.sbix-png.u1f600.16` | sbix PNG strike | skia-sbix | Supported since 4b156eac |

## SCRIPT

| Entry | Technology | Font | Status |
| --- | --- | --- | --- |
| `script.latin.composed-line` | Latin composed line through the paragraph facade | liberation | Supported since fa405247 |
| `script.greek.composed-line` | Greek composed line through the paragraph facade | liberation | Supported since fa405247 |
| `script.cyrillic.composed-line` | Cyrillic composed line through the paragraph facade | liberation | Supported since fa405247 |
| `script.arabic.composed-line` | Arabic composed line through the paragraph facade | amiri | Supported since fa405247 |
| `script.devanagari.composed-line` | Devanagari composed line through the paragraph facade | noto-devanagari | Supported since fa405247 |
| `script.mixed.composed-line` | Mixed-script composed line over the three required faces (liberation, amiri, noto-devanagari) | liberation | Supported since fa405247 |
| `script.latin.outline-sheet` | Latin outline alphabet sheet | liberation | Supported since fa405247 |
| `script.greek.outline-sheet` | Greek outline alphabet sheet | liberation | Supported since fa405247 |
| `script.cyrillic.outline-sheet` | Cyrillic outline alphabet sheet | liberation | Supported since fa405247 |
| `script.arabic.outline-sheet` | Arabic outline alphabet sheet | amiri | Supported since fa405247 |
| `script.devanagari.outline-sheet` | Devanagari outline alphabet sheet | noto-devanagari | Supported since fa405247 |

## ROBUSTNESS

| Entry | Technology | Font | Status |
| --- | --- | --- | --- |
| `robustness.truncated-sfnt` | Truncated TrueType container | liberation | Expected rejection `font.out-of-bounds` at DECODE |
| `robustness.empty-input` | Zero-byte font source | liberation | Expected rejection `font.invalid-font-data` at DECODE |

