# The Brotli static dictionary source

`BrotliDictionary.kt` is the RFC 7932 static dictionary the WOFF 2.0 decoder needs: a fixed
122,784-byte table of words, bucketed by word length 4..24, that a Brotli back-reference can name
when its distance exceeds the sliding window. The file is generated, never hand-edited;
`fetch_dictionary.py` is the generator and the only writer. **The digest, never the URL, is what
accepts the source.**

The dictionary is not distributed as a standalone file. Google Brotli embeds it as the C array
`kBrotliDictionaryData` in `c/common/dictionary.c`, so the script pins that file and extracts the
array from it. Both the downloaded source and the extracted bytes are checked against a SHA-256.

## The pinned origin

| Field | Value |
| --- | --- |
| Repository | `https://github.com/google/brotli` |
| Release tag | `v1.1.0` |
| Commit | `ed738e842d2fbdf2d6459e39267a633c4a9b2f5d` |
| Source path | `c/common/dictionary.c` |
| Source URL | `https://raw.githubusercontent.com/google/brotli/ed738e842d2fbdf2d6459e39267a633c4a9b2f5d/c/common/dictionary.c` |
| Source SHA-256 | `03e47c2060c511144045e1cedc0174a8ae076941aaa8060ac3a65bcb33ab392b` |
| Dictionary SHA-256 | `20e42eb1b511c21806d4d227d07e5dd06877d8ce7b3a817f378f313653f35c70` |
| Dictionary size | 122,784 bytes |
| Licence | MIT (see below) |

`v1.1.0` is a release tag and the commit is the tag's immutable target; the raw URL names the
commit, not the tag, so a moved tag cannot change what the digest accepts. The downloaded payload
is cached in the untracked `scripts/brotli/.cache/` and is never committed: the generated Kotlin
is what the repository ships and what the digest pins.

## The format the generator emits

The JVM refuses a method whose bytecode exceeds 64 KiB, so a single `byteArrayOf` holding all
122,784 bytes cannot compile (the same reason `scripts/unicode/generate_ucd_tables.py` splits its
tables). The generator emits the data as 30 functions of at most 4,096 bytes each, and the object
concatenates them into a single `ByteArray` on first use. Bytes at or above `0x80` are written as
signed two's-complement literals so they are in range for a Kotlin `Byte` literal.

Alongside the bytes the file carries `sizeBytes`, `NWORDS_BITS` (`NDBITS[length]` of RFC 7932 §8),
`NWORDS` (`1 shl NDBITS[length]`), the derived byte offsets and `word(offset, length)`. A length
group's byte offset is the sum of `length * NWORDS[length]` over the shorter lengths; within a
group the word is at `index * length`, where `index = offset % NWORDS[length]`. The upstream MIT
notice is reproduced in the file header.

`BrotliDictionaryTransforms.kt` is not generated. It is the hand-written table of the 121 word
transformations of RFC 7932 §8 and Appendix B, alongside the elementary Identity, FermentFirst,
FermentAll and OmitFirst1..9 / OmitLast1..9 transforms. Its provenance is the same upstream
commit: the table was transcribed from `c/common/transform.c` (SHA-256
`9f91d725dd2e6cbfd886ece385bed079edd2288a610031384f985dba38839486`) and `c/common/transform.h`
(SHA-256 `3a28f38539e661f22b8f7cd6478b76f1916903caa5b6ae708577c62ef3559122`). The file cannot
drift unnoticed: RFC 7932 Appendix B publishes the check value of the 648-byte encoding of the
table, and `BrotliDictionaryTest` recomputes it and asserts CRC-32 `0x3d965f81`.

## Commands

**These are local obligations, not CI steps**, exactly as with `scripts/fonts/` and
`scripts/unicode/`. Run them after touching the generator or the generated source.

```sh
python3 scripts/brotli/fetch_dictionary.py            # fetch if needed, verify, write the source
python3 scripts/brotli/fetch_dictionary.py --fetch    # force a fresh download
python3 scripts/brotli/fetch_dictionary.py --check    # offline: the committed file is the pinned data
python3 -m unittest discover -s scripts/brotli/tests -v
./gradlew :kalligraphie:font:sfnt:jvmTest --tests '*BrotliDictionaryTest*'
```

Only `--fetch`, or the default run when the cache is absent, reaches the network. `--check` never
does: it reads the bytes back out of the committed `BrotliDictionary.kt`, verifies their size and
digest, and re-emits the source in memory to compare it byte for byte. A source whose digest
moved, an array that no longer parses, a wrong length, a wrong data digest, a hand-edited chunk or
a formatting drift all fail the same way.

## Licence

The Brotli dictionary and source are distributed under the [MIT
licence](https://github.com/google/brotli/blob/v1.1.0/LICENSE). The required copyright and
permission notice is reproduced verbatim in the header of the generated `BrotliDictionary.kt`.
