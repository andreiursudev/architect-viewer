import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

kotlin {
    jvmToolchain(25)
}

// Like orders-service, inventory-service knows nothing about the tool: the OpenTelemetry agent and the stack
// extension are attached from outside (JAVA_TOOL_OPTIONS in docker-compose.yml or an IntelliJ run configuration).
dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
}

// A fixed name keeps the Dockerfile independent of the project version.
tasks.bootJar {
    archiveFileName = "inventory-service.jar"
}
