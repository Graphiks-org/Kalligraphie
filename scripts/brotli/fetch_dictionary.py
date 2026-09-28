#!/usr/bin/env python3
"""Generate the Brotli static dictionary from its pinned upstream source.

The RFC 7932 static dictionary is a fixed 122,784-byte table of words the Brotli decoder uses
for back-references larger than the sliding window.  Google Brotli ships it as the C array
``kBrotliDictionaryData`` in ``c/common/dictionary.c``; this script downloads that file at the
immutable commit below, verifies the SHA-256 of the downloaded source *and* of the byte array it
parses out of it, and emits ``kalligraphie/font/sfnt/.../brotli/BrotliDictionary.kt`` with the
bytes as several chunk-sized ``ByteArray`` literals, the RFC's ``NWORDS``/``NWORDS_BITS`` tables
and the upstream MIT notice.  The digest, never the URL, is what accepts the source.

Commands (run from the repository root):

    python3 scripts/brotli/fetch_dictionary.py            # fetch if needed, verify, write
    python3 scripts/brotli/fetch_dictionary.py --fetch    # force a fresh download
    python3 scripts/brotli/fetch_dictionary.py --check    # offline: the committed file is the pinned data
    python3 -m unittest discover -s scripts/brotli/tests -v

The extracted dictionary is real upstream data, never invented: a wrong source digest, a wrong
length or a wrong data digest all abort before anything is written.  ``--check`` never reaches
the network: it reads the bytes back out of the committed Kotlin file, verifies their digest, and
re-emits the source in memory to compare it byte for byte, the same proof of non-drift the other
generated artifacts use.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import re
import sys
import urllib.error
import urllib.request

# The immutable pinned upstream coordinate.  v1.1.0 is a release tag; the commit is the tag's
# target, which is the coordinate recorded in scripts/brotli/README.md.
PINNED_TAG = "v1.1.0"
PINNED_COMMIT = "ed738e842d2fbdf2d6459e39267a633c4a9b2f5d"
SOURCE_PATH = "c/common/dictionary.c"
SOURCE_URL = (
    f"https://raw.githubusercontent.com/google/brotli/{PINNED_COMMIT}/{SOURCE_PATH}"
)

# SHA-256 of the downloaded c/common/dictionary.c, and of the 122,784 bytes this script parses
# out of it.  Both are checked; neither is copied from prose.
SOURCE_SHA256 = "03e47c2060c511144045e1cedc0174a8ae076941aaa8060ac3a65bcb33ab392b"
DICTIONARY_SHA256 = "20e42eb1b511c21806d4d227d07e5dd06877d8ce7b3a817f378f313653f35c70"
DICTIONARY_SIZE = 122_784

CACHE_FILE = "scripts/brotli/.cache/dictionary.c"
TARGET = (
    "kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/"
    "brotli/BrotliDictionary.kt"
)

# The JVM refuses a method whose bytecode exceeds 64 KiB, so a single `byteArrayOf` holding the
# whole dictionary cannot compile.  Each chunk is emitted in its own function; 4096 bytes cost
# well under the limit.  Bytes >= 0x80 are emitted as signed twos-complement literals so they
# are accepted as in-range `Byte` literals.
CHUNK_BYTES = 4096
VALUES_PER_LINE = 20

# NDBITS[length] of RFC 7932 §8 (Appendix A); NWORDS[length] = 1 << NDBITS[length].
NWORDS_BITS = [0, 0, 0, 0, 10, 10, 11, 11, 10, 10, 10, 10, 10, 9, 9, 8, 7, 7, 8, 7, 7, 6, 6, 5, 5]

MIT_NOTICE = """\
Copyright (c) 2009, 2010, 2013-2016 by the Brotli Authors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
"""


def sha256_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_dictionary(source_text: str) -> bytes:
    """Returns the bytes of ``kBrotliDictionaryData`` in a ``c/common/dictionary.c`` text."""
    try:
        start = source_text.index("GENERATED CODE START")
        end = source_text.index("GENERATED CODE END", start)
    except ValueError as error:
        raise SystemExit("the pinned source no longer carries the generated-code markers") from error
    values = [int(token) for token in re.findall(r"\d+", source_text[start:end])]
    return bytes(values)


def nwords() -> list[int]:
    return [0 if length < 4 else 1 << NWORDS_BITS[length] for length in range(25)]


def offsets() -> list[int]:
    """DOFFSET[length] = sum of ``length * NWORDS[length]`` over shorter lengths (RFC 7932 §8)."""
    counts = nwords()
    result = [0] * 25
    for length in range(24):
        result[length + 1] = result[length] + length * counts[length]
    return result


def _int_lines(values: list[int]) -> str:
    lines = []
    for start in range(0, len(values), VALUES_PER_LINE):
        piece = values[start:start + VALUES_PER_LINE]
        lines.append("        " + ", ".join(str(value) for value in piece) + ",")
    return "\n".join(lines)


def _wrapped_bytes(chunk: bytes, indent: str) -> str:
    lines = []
    for start in range(0, len(chunk), VALUES_PER_LINE):
        piece = chunk[start:start + VALUES_PER_LINE]
        values = ", ".join(str(value if value < 0x80 else value - 0x100) for value in piece)
        lines.append(f"{indent}{values},")
    return "\n".join(lines)


def _chunk_function(index: int, chunk: bytes) -> str:
    first = index * CHUNK_BYTES
    return (
        f"    /** Bytes {first}..{first + len(chunk) - 1} of the dictionary. */\n"
        f"    private fun dictionaryChunk{index}(): ByteArray = byteArrayOf(\n"
        f"{_wrapped_bytes(chunk, '        ')}\n"
        f"    )\n"
    )


def render(dictionary: bytes) -> str:
    if len(dictionary) != DICTIONARY_SIZE:
        raise SystemExit(
            f"the parsed dictionary is {len(dictionary)} bytes, expected {DICTIONARY_SIZE}"
        )
    digest = sha256_of(dictionary)
    if digest != DICTIONARY_SHA256:
        raise SystemExit(f"the parsed dictionary hashes to {digest}, expected {DICTIONARY_SHA256}")

    chunks = [dictionary[start:start + CHUNK_BYTES] for start in range(0, len(dictionary), CHUNK_BYTES)]
    chunk_functions = "\n".join(_chunk_function(index, chunk) for index, chunk in enumerate(chunks))
    chunk_list = ",\n".join(f"        dictionaryChunk{index}()" for index in range(len(chunks)))
    notice = "\n".join(f"// {line}" if line else "//" for line in MIT_NOTICE.splitlines())

    return f"""// @generated by scripts/brotli/fetch_dictionary.py -- do not edit.
//
// The RFC 7932 static dictionary, extracted from Google Brotli {PINNED_TAG}
// (commit {PINNED_COMMIT}), c/common/dictionary.c.
//   source  SHA-256: {SOURCE_SHA256}
//   data    SHA-256: {DICTIONARY_SHA256}
{notice}

@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/**
 * The Brotli static dictionary (RFC 7932 §8 and Appendix A).
 *
 * The {DICTIONARY_SIZE}-byte word table is bucketed by word length 4..24. [NWORDS_BITS] gives
 * `NDBITS[length]`, the number of bits of a word index within that length group, and [NWORDS]
 * gives the number of words, `1 shl NDBITS[length]`. A length group's byte offset is the sum of
 * `length * NWORDS[length]` over the shorter lengths, and the word itself is at `index * length`
 * within its group, where `index = offset % NWORDS[length]`. [word] resolves that address.
 *
 * The bytes are the pinned upstream data, not a reconstruction; the generator verifies both the
 * upstream source and this table against their SHA-256 digests. The upstream MIT notice is
 * reproduced in this file header.
 */
@KalligraphieInternalApi
internal object BrotliDictionary {{
    /** Size in bytes of the RFC 7932 static dictionary. */
    val sizeBytes: Int = {DICTIONARY_SIZE}

    /** `NDBITS[length]` of RFC 7932 §8: the bit width of a word index in that length group. */
    val NWORDS_BITS: IntArray = intArrayOf(
{_int_lines(NWORDS_BITS)}
    )

    /** `NWORDS[length]` of RFC 7932 §8: the number of words of that length. */
    val NWORDS: IntArray = intArrayOf(
{_int_lines(nwords())}
    )

    /**
     * Resolves a dictionary reference to its base word.
     *
     * [offset] is the word id the stream produced (`distance - max_distance - 1`); the low
     * `NDBITS[length]` bits select the word within the length group. [length] is the copy length
     * of the reference and must be in `4..24` (RFC 7932 §8).
     *
     * @throws IllegalArgumentException if [length] is out of range or [offset] is negative.
     */
    fun word(offset: Int, length: Int): ByteArray {{
        require(length in 4..24) {{ "Brotli dictionary word length $length is out of range." }}
        require(offset >= 0) {{ "Brotli dictionary offset $offset is negative." }}
        val index = offset and (NWORDS[length] - 1)
        val start = DOFFSET[length] + index * length
        return data.copyOfRange(start, start + length)
    }}

    /** Byte offset of each length group: `DOFFSET[length] = sum of length * NWORDS[length]`. */
    private val DOFFSET: IntArray = IntArray(NWORDS.size).also {{ offsets ->
        for (length in 0 until NWORDS.size - 1) {{
            offsets[length + 1] = offsets[length] + length * NWORDS[length]
        }}
    }}

{chunk_functions}
    /** The chunk functions in dictionary order. */
    private fun dictionaryChunks(): Array<ByteArray> = arrayOf(
{chunk_list},
    )

    /** The full dictionary, concatenated from the chunks on first use. */
    private val data: ByteArray by lazy {{
        val combined = ByteArray(sizeBytes)
        var position = 0
        for (chunk in dictionaryChunks()) {{
            chunk.copyInto(combined, position)
            position += chunk.size
        }}
        combined
    }}
}}
"""


def parse_committed_dictionary(text: str) -> bytes:
    """Returns the dictionary bytes embedded in a committed ``BrotliDictionary.kt``."""
    values: list[int] = []
    for match in re.finditer(
        r"private fun dictionaryChunk\d+\(\): ByteArray = byteArrayOf\((.*?)\)",
        text,
        re.S,
    ):
        values.extend(int(token) for token in re.findall(r"-?\d+", match.group(1)))
    return bytes(value & 0xFF for value in values)


def verify_dictionary(dictionary: bytes) -> None:
    if len(dictionary) != DICTIONARY_SIZE:
        raise SystemExit(
            f"the parsed dictionary is {len(dictionary)} bytes, expected {DICTIONARY_SIZE}"
        )
    digest = sha256_of(dictionary)
    if digest != DICTIONARY_SHA256:
        raise SystemExit(f"the parsed dictionary hashes to {digest}, expected {DICTIONARY_SHA256}")


def obtain_source(root: pathlib.Path, force_fetch: bool) -> str:
    """Returns the verified ``dictionary.c`` text, from the cache or the network."""
    cache = root / CACHE_FILE
    if cache.exists() and not force_fetch:
        payload = cache.read_bytes()
        if sha256_of(payload) == SOURCE_SHA256:
            return payload.decode("ascii")
        print(f"cache {cache} does not match its digest; re-downloading", file=sys.stderr)
    try:
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
            payload = response.read()
    except urllib.error.URLError as error:
        raise SystemExit(f"cannot download {SOURCE_URL}: {error}") from error
    digest = sha256_of(payload)
    if digest != SOURCE_SHA256:
        raise SystemExit(f"the downloaded source hashes to {digest}, expected {SOURCE_SHA256}")
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_bytes(payload)
    return payload.decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fetch", action="store_true", help="force a fresh download of the pinned source")
    parser.add_argument("--check", action="store_true", help="offline: the committed file is the pinned data")
    parser.add_argument("--root", default=".", help="repository root the paths resolve against")
    args = parser.parse_args()
    if args.fetch and args.check:
        parser.error("--fetch and --check are mutually exclusive")

    root = pathlib.Path(args.root)
    target = root / TARGET
    if args.check:
        if not target.exists():
            raise SystemExit(f"{target} is missing; run without --check to write it")
        text = target.read_text(encoding="utf-8")
        dictionary = parse_committed_dictionary(text)
        verify_dictionary(dictionary)
        if render(dictionary) != text:
            raise SystemExit(f"{target} does not match the pinned dictionary")
        print("ok: the committed Brotli dictionary is the pinned upstream data")
        return

    dictionary = parse_dictionary(obtain_source(root, force_fetch=args.fetch))
    verify_dictionary(dictionary)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(render(dictionary), encoding="utf-8")
    print(f"wrote {target}")


if __name__ == "__main__":
    main()
