package org.graphiks.kalligraphie.shaping

/**
 * Android actual for [openHarfBuzzPlatformBinding].
 *
 * Delegates to [openAndroidHarfBuzzPlatformBinding], which loads the published kffi HarfBuzz
 * binding backed by the from-source `libharfbuzz.so` shipped in the `kffi-harfbuzz-android` AAR.
 * Only the Android source set references the native kffi surface; `commonMain` stays binding-free
 * so the module keeps compiling for targets (iOS) that have no kffi HarfBuzz artifact yet.
 */
internal actual fun openHarfBuzzPlatformBinding(): HarfBuzzPlatformBinding = openAndroidHarfBuzzPlatformBinding()
