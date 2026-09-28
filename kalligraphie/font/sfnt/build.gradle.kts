plugins {
    id("ygdrasil.conventions.kalligraphie-internal-kmp-web-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        webTest.dependencies {
            implementation(kotlin("test"))
        }
        // The WOFF 1.0 round-trip tests build their containers with Okio's `Deflater`, which exists on
        // the JVM and native targets alone, so they compile where the compressor does. The web target
        // exercises the same decoders on the real `woff-ibm-plex` fixtures through `:kalligraphie:e2e`
        // (the container scenes and the container robustness probes), which is where it is measured.
        val okioBackedTestDirectories = listOf("src/okioTest/kotlin")
        getByName("jvmTest") {
            okioBackedTestDirectories.forEach { directory -> kotlin.srcDir(directory) }
        }
        listOf("nativeTest", "iosTest", "iosArm64Test", "iosSimulatorArm64Test")
            .firstNotNullOfOrNull { name -> findByName(name) }
            ?.let { sourceSet ->
                okioBackedTestDirectories.forEach { directory -> sourceSet.kotlin.srcDir(directory) }
            }
    }
}
