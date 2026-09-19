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
        jvmMain.dependencies {
            implementation(libs.kffi.harfbuzz.jvm)
        }
        jvmTest.dependencies {
            implementation(project(":kalligraphie"))
            implementation(project(":kalligraphie:unicode"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
            implementation(kotlin("test"))
        }
    }
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

val extractHarfBuzzResources by tasks.registering {
    group = "verification"
    description = "Extracts the kffi-harfbuzz embedded native resources for the dependency audit."
    val runtimeClasspath = configurations.named("jvmRuntimeClasspath")
    val outputDir = layout.buildDirectory.dir("harfbuzz-resources")
    inputs.files(runtimeClasspath)
    outputs.dir(outputDir)
    doLast {
        val destination = outputDir.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()
        runtimeClasspath.get().files
            .filter { it.name.startsWith("kffi-harfbuzz-jvm") && it.extension == "jar" }
            .forEach { jar ->
                project.copy {
                    from(project.zipTree(jar))
                    include("kffi/harfbuzz/**")
                    into(destination)
                }
            }
    }
}
