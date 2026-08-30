# HarfBuzz JVM native resources

This artifact embeds only `libharfbuzz` from the following verified LWJGL
native artifacts. It intentionally excludes `libharfbuzz-gpu`,
`libharfbuzz-raster`, and `libharfbuzz-vector`.

| Platform | LWJGL artifact | Artifact SHA-256 | Embedded library SHA-256 |
| --- | --- | --- | --- |
| Linux x64 | `org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux` | `b9413ce2de710021d39eb08a51a4cfcac88bf84bbf3b90fd925b7bd5d7a6dba7` | `9a5e3576912c2f8c8b2533d4a264fec1eac9667adfd64f7e71e80179ba118614` |
| Linux arm64 | `org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux-arm64` | `47362b77683f5126946498ea7456c1a0b129791d585cc9f74b2c4d069ca099da` | `b1c7c67034297763e0ce46f3749c4da33a4bb4064929868446cb5a3d81dc26bc` |
| macOS x64 | `org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-macos` | `4c3c07dcdc1aa7d93899c8e0a45b15616b3a7856c3ed3ead56ee879541d80581` | `4f83ffccaf2a92e4658db8353ac7d529c52d5e4d34027a92cf9870487e1bc68b` |
| macOS arm64 | `org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-macos-arm64` | `fad37b1759c97ce74662720511c65c6b8870359dc7628c3f5bfc999cfd3cb122` | `302418f6ec10fee5e69fbe8b79f3b47e008f081ee88c912d19d2a9d820e7b9da` |

Each selected library embeds HarfBuzz source revision
`9f2f03173b7fee860cc00d999857d09fa4a362e2` and reports version `14.3.0`.

The complete offline license texts and copyright notices redistributed with this
artifact are:

* `licenses/HARFBUZZ-OLD-MIT.txt`: HarfBuzz Old MIT license and the copyright
  notice from the pinned HarfBuzz source revision.
* `licenses/LWJGL-BSD-3-CLAUSE.txt`: LWJGL BSD 3-Clause license and copyright
  notice from LWJGL 3.4.3.

The source copies were obtained respectively from the pinned HarfBuzz revision
and the LWJGL 3.4.3 tag.
