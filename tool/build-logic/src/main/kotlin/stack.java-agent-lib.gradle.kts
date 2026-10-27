plugins {
    `java-library`
}

group = "io.stackvibed"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Plain Java that is loaded into the instrumented application's JVM: Java 17 bytecode, no Kotlin.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}
