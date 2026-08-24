package io.ygdrasil.shared.domain.model

import kotlin.jvm.JvmInline

@JvmInline
value class PlatformName(val value: String) {
    init {
        require(value.isNotBlank()) { "Le nom de la plateforme ne peut pas être vide" }
    }
}

data class PlatformInfo(
    val name: PlatformName,
    val osVersion: String,
    val isSupported: Boolean
)
