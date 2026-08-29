plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

group = "org.graphiks.kalligraphie"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.register("fontTest") {
    group = "verification"
    dependsOn(
        ":kalligraphie:api:jvmTest",
        ":kalligraphie:font:sfnt:jvmTest",
        ":kalligraphie:jvmTest",
    )
}
