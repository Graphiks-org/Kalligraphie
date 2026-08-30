# HarfBuzz JVM native resources

This artifact embeds only `libharfbuzz`. It intentionally excludes
`libharfbuzz-gpu`, `libharfbuzz-raster`, and `libharfbuzz-vector`. The JVM
adapter loads the checked-in resource by its SHA-256 digest; it never searches
for a system HarfBuzz installation.

## Provenance matrix

The package is intentionally hybrid. All targets report HarfBuzz `14.3.0` and
use the same JVM adapter configuration, while each target records its own
source provenance and binary digest in `ShapingBackendIdentity`.

| Platform | Origin | Source revision | Embedded library SHA-256 |
| --- | --- | --- | --- |
| Linux x64 | `org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux` (artifact SHA-256 `b9413ce2de710021d39eb08a51a4cfcac88bf84bbf3b90fd925b7bd5d7a6dba7`) | `9f2f03173b7fee860cc00d999857d09fa4a362e2` | `9a5e3576912c2f8c8b2533d4a264fec1eac9667adfd64f7e71e80179ba118614` |
| Linux arm64 | `org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux-arm64` (artifact SHA-256 `47362b77683f5126946498ea7456c1a0b129791d585cc9f74b2c4d069ca099da`) | `9f2f03173b7fee860cc00d999857d09fa4a362e2` | `b1c7c67034297763e0ce46f3749c4da33a4bb4064929868446cb5a3d81dc26bc` |
| macOS x64 | HarfBuzz release source archive `harfbuzz-14.3.0.tar.xz` (SHA-256 `16070d77cfc4ba1f1e7327e83bf9b3f55898081cabdb94e56a33e04fc8874eae`) | `4c2aa804671d7276e8a0eb95da07202ead05c843` | `9d1ee85a217d781f91c00627248c8f9611058796f49aaf146dc88c1a1439776c` |
| macOS arm64 | HarfBuzz release source archive `harfbuzz-14.3.0.tar.xz` (SHA-256 `16070d77cfc4ba1f1e7327e83bf9b3f55898081cabdb94e56a33e04fc8874eae`) | `4c2aa804671d7276e8a0eb95da07202ead05c843` | `504948a7301dc70b1bf9c2f8dc02171c7b7bf35b14d4d5590a8af2a813d73e22` |

The macOS release tag is `14.3.0` (`07d67dc1d1675fc175e17eade0f2144dd958e977`),
whose target commit is `4c2aa804671d7276e8a0eb95da07202ead05c843`.

## macOS source configuration

The macOS libraries were built twice from clean build directories with CMake
`4.4.3`, Xcode `26.6` (AppleClang `21.0.0`), macOS SDK `26.5`, deployment
target `11.0`, `BUILD_SHARED_LIBS=ON`, and `BUILD_FRAMEWORK=OFF`. Both builds
of each architecture produced the digest in the table. The complete CMake
configuration is:

```text
CMAKE_BUILD_TYPE=Release
CMAKE_DISABLE_FIND_PACKAGE_Python3=TRUE
HB_HAVE_CORETEXT=OFF
HB_HAVE_CAIRO=OFF
HB_HAVE_FREETYPE=OFF
HB_HAVE_GRAPHITE2=OFF
HB_HAVE_GLIB=OFF
HB_HAVE_ICU=OFF
HB_HAVE_GOBJECT=OFF
HB_HAVE_INTROSPECTION=OFF
HB_BUILD_UTILS=OFF
HB_BUILD_SUBSET=OFF
HB_BUILD_RASTER=OFF
HB_BUILD_VECTOR=OFF
HB_BUILD_GPU=OFF
HB_BUILD_GPU_DEMO=OFF
```

The macOS dependency allowlist is exactly `@rpath/libharfbuzz.dylib` and
`/usr/lib/libSystem.B.dylib`. The Linux resource allowlists contain only the
platform C runtime, math runtime, pthread runtime where applicable, and the
dynamic loader. CI audits these lists, required FFM exports, and the reported
version. It rebuilds each macOS library twice from the pinned source and
compares the result byte-for-byte with the embedded resource. For Linux, it
downloads the pinned LWJGL native artifact, checks its digest, extracts the
declared library, and compares that library byte-for-byte with the embedded
resource.

## Fixed adapter configuration

The adapter explicitly selects the OpenType `ot` shaper through `hb_shape_full`,
sets OpenType font functions, scales to the face UPEM, converts with
`layout-size / UPEM`, supplies direction/script/language/BOT/EOT explicitly,
uses monotone-character clusters, and requests unsafe-to-concatenate flags.

## License notices

The complete offline license texts and notices redistributed with this artifact
are retained for both provenance routes:

* `licenses/HARFBUZZ-OLD-MIT.txt`: HarfBuzz Old MIT license and copyright notice.
* `licenses/LWJGL-BSD-3-CLAUSE.txt`: LWJGL BSD 3-Clause license and copyright notice.
