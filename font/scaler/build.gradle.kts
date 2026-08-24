plugins {
    id("ygdrasil.conventions.jvm-library")
}

dependencies {
    api(project(":font:core"))
    implementation(project(":font:sfnt"))
    implementation(project(":font:colr"))
    testImplementation(kotlin("test"))
}
