pluginManagement {
    includeBuild("build-logic")
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
include("test-harness")
