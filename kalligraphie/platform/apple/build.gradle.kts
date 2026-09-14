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
            implementation("org.graphiks:kffi-jvm:1.0.0-SNAPSHOT")
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
configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}
tasks.named<Copy>("jvmTestProcessResources") {
    from(rootProject.file("kalligraphie/shaping/src/jvmTest/resources")) {
        include("fonts/dejavu/DejaVuSans.ttf", "fonts/dejavu/PROVENANCE.md", "fonts/dejavu/LICENSE.txt")
    }
}
dokka { dokkaSourceSets.configureEach { reportUndocumented.set(true) } }
mavenPublishing { coordinates(artifactId = "kalligraphie-platform-apple") }
tasks.withType<Test>().configureEach {
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
