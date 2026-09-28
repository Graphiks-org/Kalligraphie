package ygdrasil.conventions

plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

kotlin {
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
}
