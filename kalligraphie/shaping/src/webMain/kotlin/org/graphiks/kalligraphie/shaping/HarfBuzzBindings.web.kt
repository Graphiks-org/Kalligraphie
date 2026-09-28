package org.graphiks.kalligraphie.shaping

/**
 * Web (js + wasmJs) actual for [openHarfBuzzPlatformBinding].
 *
 * Delegates to [openWebHarfBuzzPlatformBinding], which opens the published kffi HarfBuzz
 * binding backed by the bundled WebAssembly module. The module must have been awaited through
 * [initializeShapingRuntime] first; opening before that reports the typed
 * `font.shaping-native-platform-unsupported` failure, exactly as an unloadable native library
 * would on the other targets.
 */
internal actual fun openHarfBuzzPlatformBinding(): HarfBuzzPlatformBinding =
    openWebHarfBuzzPlatformBinding()
