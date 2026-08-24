package io.ygdrasil.shared

import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName()
    override val osVersion: String = UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()
