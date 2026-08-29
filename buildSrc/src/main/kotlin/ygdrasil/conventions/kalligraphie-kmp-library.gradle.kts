package ygdrasil.conventions

import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("maven-publish")
}

group = "org.graphiks"
version = providers.gradleProperty("releaseVersion")
    .orElse("1.0.0-SNAPSHOT")
    .get()

val j1ArtifactBase = when (project.path) {
    ":kalligraphie" -> "kalligraphie"
    else -> "kalligraphie-" + project.path.removePrefix(":kalligraphie:").replace(':', '-')
}

kotlin {
    jvmToolchain(25)

    jvm()
}

publishing {
    repositories {
        maven {
            name = "J1Test"
            url = uri(rootProject.layout.buildDirectory.dir("j1-test-repository").get().asFile)
        }
    }
}

afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        artifactId = when (name) {
            "kotlinMultiplatform" -> j1ArtifactBase
            else -> "$j1ArtifactBase-$name"
        }
    }
}
