plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:${libs.versions.dokka.get()}")
    implementation("com.vanniktech:gradle-maven-publish-plugin:${libs.versions.maven.publish.get()}")
}
