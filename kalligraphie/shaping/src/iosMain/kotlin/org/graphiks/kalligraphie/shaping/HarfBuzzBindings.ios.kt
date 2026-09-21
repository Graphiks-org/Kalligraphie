package org.graphiks.kalligraphie.shaping

/**
 * iOS actual for [openHarfBuzzPlatformBinding].
 *
 * The kffi HarfBuzz binding has no Kotlin/Native target yet, so iOS reports the typed graceful
 * degradation failure (`font.shaping-native-platform-unsupported`) through [HarfBuzzBindings.open].
 * A real `iosMain` backend replaces this when a native binding lands.
 */
internal actual fun openHarfBuzzPlatformBinding(): HarfBuzzPlatformBinding =
    throw HarfBuzzBindingException(
        HarfBuzzBindingFailure.UNSUPPORTED_PLATFORM,
        "The HarfBuzz shaping backend is not available on this platform.",
    )
