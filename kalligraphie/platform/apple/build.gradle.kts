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
            implementation("org.graphiks:kffi-jvm:1.0.0-20260913.233427-53")
        }
        jvmTest {
            resources.srcDir(rootProject.file("kalligraphie/src/jvmTest/resources"))
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":kalligraphie"))
            }
        }
    }
}
dokka { dokkaSourceSets.configureEach { reportUndocumented.set(true) } }
mavenPublishing { coordinates(artifactId = "kalligraphie-platform-apple") }
tasks.withType<Test>().configureEach {
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
