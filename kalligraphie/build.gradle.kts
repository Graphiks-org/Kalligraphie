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
        iosMain.dependencies {
            implementation(libs.kotlinx.atomicfu)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest {
            resources.srcDir(rootProject.file("test-fixtures"))
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}




