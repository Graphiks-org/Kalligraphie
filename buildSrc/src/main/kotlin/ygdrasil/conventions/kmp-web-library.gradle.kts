package ygdrasil.conventions

plugins {
    id("ygdrasil.conventions.kmp-library")
}

kotlin {
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
}
