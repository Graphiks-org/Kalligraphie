package io.ygdrasil.shared

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android - ${Build.MODEL}"
    override val osVersion: String = "SDK ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()
