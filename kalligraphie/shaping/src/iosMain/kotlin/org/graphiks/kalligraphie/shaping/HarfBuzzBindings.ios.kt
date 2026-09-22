package org.graphiks.kalligraphie.shaping

/**
 * iOS actual for [openHarfBuzzPlatformBinding].
 *
 * Delegates to [openIosHarfBuzzPlatformBinding], which loads the published kffi HarfBuzz
 * binding backed by the per-target `libharfbuzz` shipped in the `kffi-harfbuzz-iosarm64` and
 * `kffi-harfbuzz-iossimulatorarm64` klibs. Only the iOS source set references the native kffi
 * surface; `commonMain` stays binding-free so the module keeps compiling for every target.
 */
internal actual fun openHarfBuzzPlatformBinding(): HarfBuzzPlatformBinding = openIosHarfBuzzPlatformBinding()
