plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
            api(project(":kalligraphie:unicode"))
            api(project(":kalligraphie:shaping"))
            api(project(":kalligraphie:layout"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register("fontTest") {
    group = "verification"
    dependsOn(
        ":kalligraphie:api:jvmTest",
        ":kalligraphie:font:sfnt:jvmTest",
        ":kalligraphie:font:scaler:jvmTest",
        ":kalligraphie:font:glyph:jvmTest",
        ":kalligraphie:unicode:jvmTest",
        ":kalligraphie:shaping:jvmTest",
        ":kalligraphie:layout:jvmTest",
        ":kalligraphie:jvmTest",
    )
}
