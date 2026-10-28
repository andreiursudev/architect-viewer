pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Downloads the Java 25 toolchain into ~/.gradle/jdks when no local JDK 25 is found.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "stack-tool"

include("model")
include("agent-contract")
include("agent-extension")
include("agent-fixture-app")
include("reporter")
include("test-harness")
include("viewer-backend")
include("viewer-ui")
