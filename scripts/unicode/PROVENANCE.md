# Unicode Character Database provenance

The payloads the portable Unicode tables are generated from are fetched from the exact upstream
URLs below and verified locally with SHA-256. `scripts/unicode/ucd.json` is the machine-readable
form of this page, and `fetch_ucd.py --check` re-verifies it offline.

| File | Exact upstream URL | Unicode version | Retrieved | SHA-256 |
| --- | --- | --- | --- | --- |
| `BidiBrackets.txt` | <https://www.unicode.org/Public/16.0.0/ucd/BidiBrackets.txt> | 16.0.0 | 2026-09-09 | `b8f32554c6f658821fb0ee742d21c5b1f2086b9bf13071fed04894b022f93d67` |
| `DerivedBidiClass.txt` | <https://www.unicode.org/Public/16.0.0/ucd/extracted/DerivedBidiClass.txt> | 16.0.0 | 2026-09-24 | `71ed943a49c58568d8d92e80ecc2ba2f06e62aee9c8ebb0e6e8bd2c3ed8b180e` |
| `DerivedCoreProperties.txt` | <https://www.unicode.org/Public/16.0.0/ucd/DerivedCoreProperties.txt> | 16.0.0 | 2026-09-24 | `39d35161f2954497f69e08bdb9e701493f476a3d30222de20028feda36c1dabd` |
| `emoji-data.txt` | <https://www.unicode.org/Public/16.0.0/ucd/emoji/emoji-data.txt> | 16.0.0 | 2026-09-24 | `f1365a5173eee18e1f98b240cdc492e84a25f1ce7e0c9d1094eb29c41a22696a` |
| `GraphemeBreakProperty.txt` | <https://www.unicode.org/Public/16.0.0/ucd/auxiliary/GraphemeBreakProperty.txt> | 16.0.0 | 2026-09-24 | `c29360bd6f7132811d701d29069541e827eb44bfc4c8fbde8c370d6982689dc1` |
| `likelySubtags.xml` | <https://raw.githubusercontent.com/unicode-org/cldr/release-46/common/supplemental/likelySubtags.xml> | 16.0.0 | 2026-09-24 | `a8c085186c074062c9665cab5270313e77e118184dfd80465864e24de674bf42` |
| `LineBreak.txt` | <https://www.unicode.org/Public/16.0.0/ucd/LineBreak.txt> | 16.0.0 | 2026-09-24 | `e97e4259d0d20fab150b9c7b4b28abfae5cd78ca97e7f4ac6ed20d685d5f4a7c` |
| `PropertyValueAliases.txt` | <https://www.unicode.org/Public/16.0.0/ucd/PropertyValueAliases.txt> | 16.0.0 | 2026-09-24 | `440fd3e5460b9bfe31da67b6f923992e1989d31fe2ed91e091c4b8f8e2620bf9` |
| `ScriptExtensions.txt` | <https://www.unicode.org/Public/16.0.0/ucd/ScriptExtensions.txt> | 16.0.0 | 2026-09-09 | `049117ce26b9769fe2749b06eef51a50a89faef4a97764dd2d81daa715980700` |
| `Scripts.txt` | <https://www.unicode.org/Public/16.0.0/ucd/Scripts.txt> | 16.0.0 | 2026-09-09 | `9e88f0a677df47311106340be8ede2ecdacd9c1c931831218d2be6d5508e0039` |
| `VerticalOrientation-16.0.0.txt` | <https://www.unicode.org/Public/16.0.0/ucd/VerticalOrientation.txt> | 16.0.0 | 2026-09-24 | `24ac1474554eb55c85ace5c16d551535a8c52cdc2f4c13cf68c3839214339bbe` |

## Where each payload lives

Three sources are already committed in this repository as the conformance oracles of
`:kalligraphie:unicode`, and are read in place rather than re-downloaded: `bidi-brackets`, `script-extensions`, `scripts`.

The remaining 8 are downloaded into the untracked `scripts/unicode/.cache/` directory by
`fetch_ucd.py --fetch`: `bidi-class`, `core-properties`, `emoji-data`, `grapheme-break`, `likely-subtags`, `line-break`, `property-value-aliases`, `vertical-orientation`.

## What the tables derive from them

| Emitted table | Source | Property read |
| --- | --- | --- |
| `UnicodeVerticalOrientation` | `VerticalOrientation.txt` | `Vertical_Orientation`, the union of `U` and `Tu` |
| `BidiClass` | `DerivedBidiClass.txt` | `Bidi_Class` |
| `BidiBracketType` | `BidiBrackets.txt` | `Bidi_Paired_Bracket`, `Bidi_Paired_Bracket_Type` |
| `GraphemeClusterBreak` | `GraphemeBreakProperty.txt` | `Grapheme_Cluster_Break` |
| `IndicConjunctBreak` | `DerivedCoreProperties.txt` | `Indic_Conjunct_Break` |
| `LineBreakClass` | `LineBreak.txt` | `Line_Break` |
| `UnicodeExtendedPictographic` | `emoji-data.txt` | `Extended_Pictographic` |
| `UnicodeScript` | `Scripts.txt` | `Script` |
| `UnicodeScriptExtensions` | `ScriptExtensions.txt` | `Script_Extensions` |
| the ISO 15924 codes above | `PropertyValueAliases.txt` | the `sc` value aliases |
| `UnicodeLikelyScript` | `likelySubtags.xml` | the languages carrying no region, and the script of their maximised tag |

## Unicode data license

These data files are distributed under the [Unicode License V3](https://www.unicode.org/license.txt).
The required copyright and permission notice follows.

> COPYRIGHT AND PERMISSION NOTICE
>
> Copyright © 1991-2026 Unicode, Inc.
>
> NOTICE TO USER: Carefully read the following legal agreement. BY DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of data files and any associated documentation (the “Data Files”) or software and any associated documentation (the “Software”) to deal in the Data Files or Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, and/or sell copies of the Data Files or Software, and to permit persons to whom the Data Files or Software are furnished to do so, provided that either (a) this copyright and permission notice appear with all copies of the Data Files or Software, or (b) this copyright and permission notice appear in associated Documentation.
>
> THE DATA FILES AND SOFTWARE ARE PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF THIRD PARTY RIGHTS.
>
> IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES, OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA FILES OR SOFTWARE.
>
> Except as contained in this notice, the name of a copyright holder shall not be used in advertising or otherwise to promote the sale, use or other dealings in these Data Files or Software without prior written authorization of the copyright holder.
