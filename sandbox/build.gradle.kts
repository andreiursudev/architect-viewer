plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.node.gradle) apply false
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

// The browser SDK (@stackvibed/browser) as the tarball the tool publishes to mavenLocal; the frontend installs it.
val stackBrowserTgz = configurations.create("stackBrowserTgz") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    otelAgentJar(libs.opentelemetry.javaagent)
    stackExtensionJar(libs.stack.agent.extension)
    stackBrowserTgz(libs.stack.browser) { artifact { type = "tgz" } }
}

tasks.register<Sync>("stackAgent") {
    group = "stack"
    description = "Copies opentelemetry-javaagent.jar and stack-agent-extension.jar into build/stack-agent."
    from(otelAgentJar) { rename { "opentelemetry-javaagent.jar" } }
    from(stackExtensionJar) { rename { "stack-agent-extension.jar" } }
    into(layout.buildDirectory.dir("stack-agent"))
}

// The tarball under a fixed name, for services/frontend (`npm install ../../build/stack-browser/stack-browser.tgz`).
tasks.register<Sync>("stackBrowser") {
    group = "stack"
    description = "Copies the @stackvibed/browser tarball into build/stack-browser/stack-browser.tgz."
    from(stackBrowserTgz) { rename { "stack-browser.tgz" } }
    into(layout.buildDirectory.dir("stack-browser"))
}
