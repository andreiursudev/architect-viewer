plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

// The OpenTelemetry Java agent and the tool's extension, under fixed names: docker-compose.yml mounts
// build/stack-agent into orders-service, and an IntelliJ run configuration can point at the same files.
val otelAgentJar = configurations.create("otelAgentJar") {
    isCanBeConsumed = false
    isTransitive = false
}
val stackExtensionJar = configurations.create("stackExtensionJar") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    otelAgentJar(libs.opentelemetry.javaagent)
    stackExtensionJar(libs.stack.agent.extension)
}

tasks.register<Sync>("stackAgent") {
    group = "stack"
    description = "Copies opentelemetry-javaagent.jar and stack-agent-extension.jar into build/stack-agent."
    from(otelAgentJar) { rename { "opentelemetry-javaagent.jar" } }
    from(stackExtensionJar) { rename { "stack-agent-extension.jar" } }
    into(layout.buildDirectory.dir("stack-agent"))
}
