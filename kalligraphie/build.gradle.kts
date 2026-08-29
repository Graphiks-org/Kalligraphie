plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val j1PublishedProjects = listOf(
    ":kalligraphie",
    ":kalligraphie:api",
    ":kalligraphie:unicode",
    ":kalligraphie:font:core",
    ":kalligraphie:font:sfnt",
    ":kalligraphie:font:scaler",
    ":kalligraphie:font:glyph",
)

tasks.register("publishJ1ToTestRepository") {
    group = "publishing"
    description = "Publishes the complete J1 module topology to an isolated local repository."
    dependsOn(j1PublishedProjects.map { projectPath -> "$projectPath:publishAllPublicationsToJ1TestRepository" })
}

tasks.register<Exec>("consumerSmoke") {
    group = "verification"
    description = "Compiles and runs an external consumer of org.graphiks:kalligraphie."
    dependsOn("publishJ1ToTestRepository")

    val repositoryDirectory = rootProject.layout.buildDirectory.dir("j1-test-repository")
    commandLine(
        rootProject.file("gradlew").absolutePath,
        "--no-daemon",
        "--offline",
        "-p",
        project.file("consumer-smoke").absolutePath,
        "clean",
        "run",
        "-Pj1Repository=${repositoryDirectory.get().asFile.toURI()}",
        "-PkalligraphieVersion=${project.version}",
    )
}

tasks.register("fontTest") {
    group = "verification"
    dependsOn(
        ":kalligraphie:api:jvmTest",
        ":kalligraphie:font:sfnt:jvmTest",
        ":kalligraphie:font:scaler:jvmTest",
        ":kalligraphie:font:glyph:jvmTest",
        ":kalligraphie:jvmTest",
    )
}
