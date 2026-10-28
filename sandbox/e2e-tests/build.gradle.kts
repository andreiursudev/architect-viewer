import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

// main: the scenarios (reusable user journeys). test: the tagged e2e tests, run against a stack that is
// already up (`e2eTest`). acceptanceTest: brings the whole stack up with Docker Compose and checks the recordings.
val acceptanceTest: SourceSet = sourceSets.create("acceptanceTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations.named(acceptanceTest.implementationConfigurationName) {
    extendsFrom(configurations.implementation.get())
}
configurations.named(acceptanceTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.runtimeOnly.get())
}

dependencies {
    implementation(libs.stack.test.harness)
    implementation(libs.rest.assured)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    "acceptanceTestImplementation"(platform(libs.junit.bom))
    "acceptanceTestImplementation"(libs.junit.jupiter)
    "acceptanceTestImplementation"(libs.kotest.assertions.core)
    "acceptanceTestImplementation"(libs.jackson.databind)
    "acceptanceTestImplementation"(libs.testcontainers)
    "acceptanceTestImplementation"(libs.testcontainers.junit.jupiter)
    "acceptanceTestRuntimeOnly"(libs.junit.platform.launcher)
    "acceptanceTestRuntimeOnly"(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    // Both suites talk to live services, so their outcome is never "up to date".
    outputs.upToDateWhen { false }
}

// The e2e tests need a running stack (docker compose up), so they are not part of `build`.
tasks.test {
    enabled = false
}

tasks.register<Test>("e2eTest") {
    description = "Runs the tagged e2e scenarios against the running stack (docker compose up -d --build first)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<Test>("acceptanceTest") {
    description = "Starts the stack with Docker Compose, runs the scenarios and checks what the reporter recorded."
    group = "verification"
    testClassesDirs = acceptanceTest.output.classesDirs
    classpath = acceptanceTest.runtimeClasspath
    // docker-compose.yml builds orders-service from its jar and mounts the agent jars.
    dependsOn(":services:orders-service:bootJar", ":stackAgent")
}
