plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-web-library")
}

kotlin {
    // The public portable surface is exercised in a real browser as well as under Node: the same
    // suite runs twice, so a bundler-only or DOM-only regression is caught here.
    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }

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
        webTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}




