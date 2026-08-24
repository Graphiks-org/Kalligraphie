package io.ygdrasil.shared

interface Platform {
    val name: String
    val osVersion: String
}

expect fun getPlatform(): Platform
