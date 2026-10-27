import org.apache.tools.ant.filters.ReplaceTokens

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

    testImplementation(libs.spring.boot.starter.test)
}

tasks.processResources {
    filesMatching("application.properties") {
        filter<ReplaceTokens>("tokens" to mapOf("version" to project.version.toString()))
    }
}

// A fixed file name keeps the Dockerfile independent of the project version.
tasks.bootJar { archiveFileName = "reporter.jar" }

val dockerImageTag = "stackvibed/reporter:${project.version}"

tasks.register<Exec>("dockerImage") {
    group = "build"
    description = "Builds the $dockerImageTag Docker image (needs a running Docker daemon; not part of build)."
    dependsOn(tasks.bootJar)
    inputs.file(layout.projectDirectory.file("Dockerfile"))
    inputs.file(tasks.bootJar.flatMap { it.archiveFile })
    workingDir = projectDir
    commandLine("docker", "build", "-t", dockerImageTag, ".")
}
