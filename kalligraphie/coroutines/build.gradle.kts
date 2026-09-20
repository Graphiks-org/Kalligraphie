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
            api(project(":kalligraphie"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest {
            resources.srcDir(rootProject.file("kalligraphie/src/jvmTest/resources"))
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

dokka { dokkaSourceSets.configureEach { reportUndocumented.set(true) } }
mavenPublishing { coordinates(artifactId = "kalligraphie-coroutines") }
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
