plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ygdrasil.conventions.kmp-publish")
    id("dev.opensavvy.dokka-mkdocs")
}

kotlin {
    jvm()
    jvmToolchain(25)
    explicitApi()
    sourceSets {
        jvmMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
            implementation(libs.kffi.fontconfig.jvm)
        }
        jvmTest {
            resources.srcDir(rootProject.file("kalligraphie/src/jvmTest/resources"))
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":kalligraphie"))
                implementation(project(":kalligraphie:layout"))
                implementation(project(":kalligraphie:shaping"))
                implementation(project(":kalligraphie:unicode"))
            }
        }
    }
}
configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}
dokka { dokkaSourceSets.configureEach { reportUndocumented.set(true) } }
mavenPublishing { coordinates(artifactId = "kalligraphie-platform-linux") }
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
