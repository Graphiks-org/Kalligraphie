package org.graphiks.kalligraphie.shaping

/**
 * Android actual for [openHarfBuzzPlatformBinding].
 *
 * TODO(B4): replace this graceful-degradation stub with the real kffi-Android HarfBuzz binding.
 * Until then Android reports the same typed failure as any other target without a native library
 * (`font.shaping-native-platform-unsupported`) through [HarfBuzzBindings.open], so the module keeps
 * compiling while every target shares the common adapter.
 */
internal actual fun openHarfBuzzPlatformBinding(): HarfBuzzPlatformBinding =
    throw HarfBuzzBindingException(
        HarfBuzzBindingFailure.UNSUPPORTED_PLATFORM,
        "The HarfBuzz shaping backend is not available on Android in this revision.",
    )
