# Liberation Sans / Amiri TrueType collections

These test containers assemble the unchanged source specimens described in
[Liberation Sans provenance](../liberation/PROVENANCE.md) and
[Amiri provenance](../amiri/PROVENANCE.md). Their respective
[Liberation SIL Open Font License](../liberation/OFL-1.1.txt) and
[Amiri SIL Open Font License](../amiri/OFL.txt) apply to the included faces.
The original fixtures are preserved. Collection assembly rewrites container
directories and absolute table locations without subsetting or changing glyphs.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `LiberationAmiri.ttc` (TTC v1) | 841848 | `a214a4eea0d97ba15e575861cdd627516d06990998f3d53daf93a04f7a2f39b7` |
| `LiberationAmiri-v2.ttc` (TTC v2, absent DSIG) | 841860 | `467dcb0f7bceb673ff6182e8ce43e4e12a364e347d347a07488eeca0e0a3647f` |

Face index 0 is Liberation Sans Regular, UPEM 2048; face index 1 is Amiri
Regular, UPEM 1000. Indices describe the original collection, even when a
consumer excludes a face. The v2 recipe inserts the complete twelve-byte
absent-DSIG header and updates all absolute face/table offsets.

`audit.json` records independent fontTools 4.65.0 metadata, `hmtx`, glyph
header bounds, RecordingPen contours and HarfBuzz 14.4.0 shaping output.
The fixed `*-A-outline.txt` files expand RecordingPen quadratic segments
into explicit controls/endpoints, including implied midpoint coordinates.
They are independent fixture evidence; tests do not call the production
decoder to produce expected results. No human review of this new audit is
claimed here.

Reproduce from the repository root with Python and `hb-shape` on PATH:

```sh
python3 -m venv .fixture-venv
. .fixture-venv/bin/activate
python -m pip install fonttools==4.65.0
python kalligraphie/src/jvmTest/resources/fonts/liberation-amiri-collection/build_collection_fixture.py . generated-collections
```

The generator takes a repository root and a separate output directory; it
does not overwrite the original source TTFs. Compare the output binary
hashes and fixed outline files with this directory. HarfBuzz reproduces:

```sh
hb-shape --face-index=0 --direction=ltr --script=Latn --language=en --no-glyph-names generated-collections/LiberationAmiri.ttc Affi
hb-shape --face-index=1 --direction=ltr --script=Latn --language=en --no-glyph-names generated-collections/LiberationAmiri.ttc Affi
```

The first face yields `[36=0+1366|73=1+532|73=2+569|76=3+455]`; the second
yields `[6227=0+612|6631=1+795]`. Liberation A is glyph 36, advance 1366,
LSB 4, bounds `(4,0,1362,1409)`, first move `(1167,0)`. Amiri A is glyph
6227, advance 612, LSB -14, header bounds `(-14,-3,619,647)`, first move
`(93,136)`. Both have two complete contours, with different geometry.
