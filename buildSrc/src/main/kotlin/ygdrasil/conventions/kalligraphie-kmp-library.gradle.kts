package ygdrasil.conventions

plugins {
    id("ygdrasil.conventions.kmp-library")
    id("ygdrasil.conventions.kmp-publish")
    id("dev.opensavvy.dokka-mkdocs")
}

group = "org.graphiks"
version = providers.gradleProperty("releaseVersion")
    .orElse("1.0.0-SNAPSHOT")
    .get()

dokka {
    dokkaSourceSets.configureEach {
        reportUndocumented.set(true)
    }
}
