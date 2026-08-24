package io.ygdrasil.shared

class JVMPlatform : Platform {
    override val name: String = System.getProperty("os.name") ?: "Desktop JVM"
    override val osVersion: String = System.getProperty("os.version") ?: "1.0"
}

actual fun getPlatform(): Platform = JVMPlatform()
