# GDEF kerning shaping fixture

## Purpose

This deliberately minimal TrueType font exercises two observable cases that are
rare in production fixtures: a `fi` ligature with a GDEF caret and a following
GPOS kerning adjustment, plus the explicit Unicode variation sequence
`U+2764 U+FE0F`. Its ligature source text is `fiV`.

The OpenType source defines these independent values in design units:

- `f_i` advance: `900`;
- GDEF caret for `f_i`: `450`;
- GPOS pair adjustment `f_i` → `V`: `-100`;
- final shaped `f_i` advance: `800`.
- default `U+2764` mapping: glyph ID `5`, advance `700`;
- non-default `U+2764 U+FE0F` mapping in `cmap` format 14: glyph ID `6`,
  advance `900`.

The fixture therefore makes unadjusted GDEF data invalid for the final shaped
advance. The expected shaping outcome is audited independently and frozen in
the JVM backend test: the ligature’s GDEF caret fact is `INCONSISTENT` and it
publishes no raw caret position.

## Provenance and reproducibility

- Author: Kalligraphie contributors.
- License: [CC0 1.0 Universal](LICENSE.md).
- Generator: [FixtureBuild.py](FixtureBuild.py), using `fontTools` `4.59.2`.
- OpenType source: [FixtureBuild.fea](FixtureBuild.fea).
- Generated font: `GdefKerningFixture.ttf`, SHA-256
  `c9f28286059cf869a80340af0edd035cfb83d10da586f67c728234f2d63b90a8`,
  `1772` bytes.

## External audit

The frozen expectation was audited with [AuditProbe.c](AuditProbe.c), a
standalone C ABI probe linked against HarfBuzz `14.3.0` source headers and the
checked-in macOS arm64 HarfBuzz library. The source archive SHA-256 is
`16070d77cfc4ba1f1e7327e83bf9b3f55898081cabdb94e56a33e04fc8874eae`.

The probe uses the explicit `ot` shaper, LTR, `Latn`, language `en`, BOT/EOT,
monotone-character clusters, and a layout size of `1000`. Its recorded output
is:

```text
glyphs: 3+800@0 4+600@2
gdef-caret-count: 1 copied: 1
raw-gdef-carets: 450
```

This is an external ABI audit, not a test oracle call from the Kotlin adapter.
The JVM backend test freezes the consumer-level consequence: the final advance
does not match the unshaped glyph advance, so the raw GDEF caret is reported as
`INCONSISTENT` and must not be published.

## Unicode variation audit

The additional sequence was independently inspected with `fontTools 4.59.2`.
The generated fixture has one format-14 record, `U+FE0F → U+2764 → heart_emoji`;
the checked-in DejaVu Sans fixture has no format-14 record. A maintainer reviewed
the decoded records and the fixture glyph order, then froze the consumer oracle:
the GDEF fixture shapes `U+2764 U+FE0F` as glyph ID `6` with advance `900`.

`hb-shape 14.4.0`, outside the JVM adapter under test, records that output as:

```text
[6=0+900]
```

The multi-font consumer test places DejaVu first precisely because it maps the
base character but cannot declare the requested variation sequence; it must be
rejected before the format-14-capable fixture is selected. The test stores only
the reviewed glyph ID and face choice and never invokes HarfBuzz as its oracle.

To reproduce the audit on macOS arm64, unpack the verified release archive as
`$SOURCE_ROOT`, then run:

```sh
cc -std=c11 -I "$SOURCE_ROOT/src" AuditProbe.c \
  -L ../../../../jvmMain/resources/kalligraphie/harfbuzz/macos/arm64 \
  -lharfbuzz \
  -Wl,-rpath,"$PWD/../../../../jvmMain/resources/kalligraphie/harfbuzz/macos/arm64" \
  -o audit-probe
./audit-probe GdefKerningFixture.ttf
```

## Outline fallback oracle

The same checked-in CC0 artifact was also inspected with `hb-shape 14.4.0`:

```text
hb-shape --no-glyph-names GdefKerningFixture.ttf 'fi'
[3=0+900]
```

`FixtureBuild.py` constructs `f_i` as one closed rectangular contour with four
points. Human review verified that this geometry fits an outline profile whose
maximum is one contour, so it is the audited fallback for a DejaVu `fi`
ligature that exceeds that profile. The consumer test freezes these external
facts and does not invoke HarfBuzz to derive an expected value.
