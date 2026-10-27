plugins {
    id("stack.agent-extension")
}

val otelAgent = configurations.create("otelAgent") {
    isCanBeConsumed = false
    isTransitive = false
}
val fixtureApp = configurations.create("fixtureApp") {
    isCanBeConsumed = false
}

dependencies {
    implementation(project(":agent-contract"))

    // Provided at runtime by the OpenTelemetry Java agent and by the instrumented application.
    compileOnly(platform(libs.opentelemetry.instrumentation.bom.alpha))
    compileOnly(libs.opentelemetry.javaagent.extension.api)
    compileOnly(libs.opentelemetry.instrumentation.api)
    compileOnly(libs.opentelemetry.sdk.extension.autoconfigure.spi)
    compileOnly(libs.bytebuddy)
    compileOnly(libs.jakarta.servlet.api)

    // Unit tests load helpers that reference instrumentation-api types (e.g. VirtualField).
    testImplementation(platform(libs.opentelemetry.instrumentation.bom.alpha))
    testImplementation(libs.opentelemetry.instrumentation.api)

    otelAgent(libs.opentelemetry.javaagent)
    fixtureApp(project(":agent-fixture-app", "fixtureJar"))

    "agentTestImplementation"(project(":agent-contract"))
    "agentTestImplementation"(libs.opentelemetry.proto)
    "agentTestImplementation"(libs.jackson.databind)
}

tasks.shadowJar {
    archiveBaseName = "stack-agent-extension"
    archiveClassifier = ""
}

tasks.named<Test>("agentTest") {
    val extensionJar = tasks.shadowJar.flatMap { it.archiveFile }
    val logDir = layout.buildDirectory.dir("agent-test-logs")
    inputs.files(otelAgent).withPropertyName("otelAgent")
    inputs.files(fixtureApp).withPropertyName("fixtureApp")
    inputs.file(extensionJar).withPropertyName("extensionJar")
    outputs.dir(logDir).withPropertyName("logDir")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dstack.otelAgentJar=${otelAgent.singleFile.absolutePath}",
            "-Dstack.extensionJar=${extensionJar.get().asFile.absolutePath}",
            "-Dstack.fixtureJar=${fixtureApp.singleFile.absolutePath}",
            "-Dstack.agentTestLogDir=${logDir.get().asFile.absolutePath}",
        )
    })
}

publishing {
    publications {
        create<MavenPublication>("extension") {
            artifactId = "stack-agent-extension"
            from(components["shadow"])
        }
    }
}
