# GDEF kerning shaping fixture

## Purpose

This deliberately minimal TrueType font exercises one observable case that is
rare in production fixtures: a `fi` ligature with a GDEF caret and a following
GPOS kerning adjustment. Its source text is `fiV`.

The OpenType source defines these independent values in design units:

- `f_i` advance: `900`;
- GDEF caret for `f_i`: `450`;
- GPOS pair adjustment `f_i` → `V`: `-100`;
- final shaped `f_i` advance: `800`.

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
  `08c70b485b94b86738b1cfef8e544102f023c8d76807403c46470df5034f81ce`,
  `1636` bytes.

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
