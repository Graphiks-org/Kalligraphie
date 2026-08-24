plugins {
    id("ygdrasil.conventions.jvm-library")
}

dependencies {
    api(project(":font:core"))
    api(project(":font:text"))
    implementation(project(":font:colr"))
    implementation(project(":font:scaler"))
    testImplementation(kotlin("test"))
}
