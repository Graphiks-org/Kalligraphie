plugins {
    id("ygdrasil.conventions.jvm-library")
}

dependencies {
    implementation(project(":font:core"))
    implementation(project(":font:sfnt"))
    testImplementation(kotlin("test"))
}
