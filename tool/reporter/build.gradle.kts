plugins {
    id("stack.spring-boot-app")
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.opentelemetry.proto)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(project(":model"))
    implementation(project(":agent-contract"))
}

// There is no Spring Boot application class yet (it arrives with the OTLP endpoint), so there is
// nothing for bootJar to resolve a main class from. Re-enable it together with the application class.
tasks.bootJar { enabled = false }
