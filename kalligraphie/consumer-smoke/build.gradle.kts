plugins {
    application
}

repositories {
    maven {
        url = uri(requireNotNull(providers.gradleProperty("j1Repository").orNull) {
            "The j1Repository property is required."
        })
    }
    mavenCentral()
}

dependencies {
    implementation(
        "org.graphiks:kalligraphie:" +
            providers.gradleProperty("kalligraphieVersion").orElse("1.0.0-SNAPSHOT").get(),
    )
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "org.graphiks.kalligraphie.consumer.ConsumerSmoke"
}
