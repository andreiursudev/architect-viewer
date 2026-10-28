plugins {
    id("stack.spring-boot-app")
}

// A small Spring MVC + H2 app that only exists to be run under the agent by :agent-extension:agentTest.
dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.restclient)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.h2)
}

// Exposes the executable jar to :agent-extension without reaching into this project's tasks.
val fixtureJar = configurations.create("fixtureJar") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(fixtureJar.name, tasks.bootJar)
}
