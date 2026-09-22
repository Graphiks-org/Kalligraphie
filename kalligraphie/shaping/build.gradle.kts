plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    android {
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            managedDevices {
                localDevices {
                    create("mediumPhone") {
                        device = "Medium Phone"
                        apiLevel = 35
                        systemImageSource = "aosp"
                    }
                }
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        iosMain.dependencies {
            implementation(libs.kotlinx.atomicfu)
        }
        iosArm64Main.dependencies {
            implementation(libs.kffi.harfbuzz.iosarm64)
        }
        iosSimulatorArm64Main.dependencies {
            implementation(libs.kffi.harfbuzz.iossimulatorarm64)
        }
        jvmMain.dependencies {
            implementation(libs.kffi.harfbuzz.jvm)
        }
        androidMain.dependencies {
            implementation(libs.kffi.harfbuzz.android)
        }
        jvmTest.dependencies {
            implementation(project(":kalligraphie"))
            implementation(project(":kalligraphie:unicode"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
            implementation(kotlin("test"))
        }
        jvmTest {
            resources.srcDir(rootProject.file("test-fixtures"))
        }
        val androidDeviceTest by getting {
            dependencies {
                implementation(project(":kalligraphie"))
                implementation(project(":kalligraphie:unicode"))
                implementation(project(":kalligraphie:font:core"))
                implementation(project(":kalligraphie:font:sfnt"))
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.test.runner)
                implementation(kotlin("test"))
            }
            // The shared JVM fixture corpus is reused on-device: AGP packages this Kotlin
            // resource directory into the device-test APK, so the suite resolves the same
            // `/fonts/...` entries through the class loader as `jvmTest`.
            resources.srcDir(rootProject.file("test-fixtures"))
        }
    }
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}
