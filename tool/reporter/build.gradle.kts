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
    // Read at configuration time: the filter runs at execution time, when Task.project is off limits.
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("application.properties") {
        filter<ReplaceTokens>("tokens" to mapOf("version" to version))
    }
}

// The JVM prints a class-data-sharing warning when a Spring Boot test attaches its agent; sharing is only a startup optimisation.
tasks.test { jvmArgs("-Xshare:off") }

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
    // --load: with a buildx builder on the docker-container driver the image would otherwise stay in the build cache.
    commandLine("docker", "build", "--load", "-t", dockerImageTag, ".")
}
