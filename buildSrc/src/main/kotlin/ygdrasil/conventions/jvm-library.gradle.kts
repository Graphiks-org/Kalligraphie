package ygdrasil.conventions

import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
    id("java-library")
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
