plugins {
    id("ygdrasil.conventions.jvm-library")
}

dependencies {
    api(project(":font:core"))
    api(project(":font:sfnt"))
    implementation(project(":font:scaler"))
    testImplementation(kotlin("test"))
}
