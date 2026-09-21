package org.graphiks.kalligraphie.shaping

/**
 * iOS actual for [openHarfBuzzPlatformBinding].
 *
 * TODO(Phase 3): replace this graceful-degradation stub with the real kffi-iOS HarfBuzz binding.
 * The kffi HarfBuzz binding has no Kotlin/Native target yet, so iOS reports the typed graceful
 * degradation failure (`font.shaping-native-platform-unsupported`) through [HarfBuzzBindings.open].
 * A real `iosMain` backend replaces this when a native binding lands.
 */
internal actual fun openHarfBuzzPlatformBinding(): HarfBuzzPlatformBinding =
    throw HarfBuzzBindingException(
        HarfBuzzBindingFailure.UNSUPPORTED_PLATFORM,
        "The HarfBuzz shaping backend is not available on this platform.",
    )
