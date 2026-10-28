// The sample system the tool is built against. It is a separate build that uses the tool the way any outside
// project would: published artifacts from mavenLocal and the reporter Docker image, never tool/ sources.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // Only the tool's own artifacts come from mavenLocal (`tool/gradlew -p tool publishToMavenLocal`).
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroup("io.stackvibed") }
        }
        mavenCentral()
    }
}

rootProject.name = "stack-sandbox"

include("services:orders-service")
include("e2e-tests")
