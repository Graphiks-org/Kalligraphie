package ygdrasil.conventions

plugins {
    id("ygdrasil.conventions.kalligraphie-internal-kmp-library")
}

kotlin {
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
}
