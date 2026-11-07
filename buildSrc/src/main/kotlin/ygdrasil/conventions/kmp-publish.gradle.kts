package ygdrasil.conventions

plugins {
    id("com.vanniktech.maven.publish")
}

group = "org.graphiks.kalligraphie"
version = (project.findProperty("releaseVersion") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: "1.0.0-SNAPSHOT"

val isPublishing = project.findProperty("signingInMemoryKey")?.toString()?.isNotBlank() == true
    || project.findProperty("signing.keyId")?.toString()?.isNotBlank() == true

mavenPublishing {
    if (isPublishing) {
        publishToMavenCentral()
        signAllPublications()
    }
    coordinates(group.toString(), project.name, version.toString())

    pom {
        // A searchable display name on Maven Central: `:kalligraphie:font:core` publishes as
        // "Kalligraphie Font Core" rather than the bare leaf name "core".
        name.set(
            project.path.trim(':').split(':').joinToString(" ") { segment ->
                segment.replaceFirstChar { it.uppercaseChar() }
            }
        )
        description.set("Kalligraphie portable font pipeline")
        url.set("https://github.com/Graphiks-org/Kalligraphie")

        licenses {
            license {
                name.set("The MIT License")
                url.set("https://opensource.org/license/mit/")
            }
        }

        developers {
            developer {
                id.set("ygdrasil-io")
                name.set("Ygdrasil team")
                email.set("contact@ygdrasil.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/Graphiks-org/Kalligraphie.git")
            developerConnection.set("scm:git:ssh://github.com/Graphiks-org/Kalligraphie.git")
            url.set("https://github.com/Graphiks-org/Kalligraphie")
        }
    }
}
