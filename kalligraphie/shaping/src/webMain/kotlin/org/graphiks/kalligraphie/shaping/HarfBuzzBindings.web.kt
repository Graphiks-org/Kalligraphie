package org.graphiks.kalligraphie.shaping

/**
 * Web actual for [openHarfBuzzPlatformBinding].
 *
 * No kffi HarfBuzz web artifact exists yet (Phase 2), so the target reports the typed
 * graceful-degradation failure instead of pretending to shape. `HarfBuzzBindings.open` maps
 * [HarfBuzzBindingFailure.UNSUPPORTED_PLATFORM] to `font.shaping-native-platform-unsupported`,
 * exactly as the iOS actual did before its backend landed.
 */
internal actual fun openHarfBuzzPlatformBinding(): HarfBuzzPlatformBinding =
    throw HarfBuzzBindingException(
        HarfBuzzBindingFailure.UNSUPPORTED_PLATFORM,
        "The HarfBuzz web binding is not available on this target yet.",
    )
