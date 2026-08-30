plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        jvmTest.dependencies {
            implementation(project(":kalligraphie:unicode"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
            implementation(kotlin("test"))
        }
    }
}
