import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// An OpenTelemetry Java agent extension: plain Java main code shadowed into one jar, plus a Kotlin
// `agentTest` source set whose tests run real apps under the agent. Wired into `check`.
plugins {
    id("stack.java-agent-lib")
    kotlin("jvm")
    id("com.gradleup.shadow")
    `maven-publish`
}

val libs = versionCatalogs.named("libs")

kotlin {
    jvmToolchain(25)
}

// The extension jar is loaded into the instrumented JVM: the Kotlin stdlib that the Kotlin plugin
// adds for the tests must never reach the shadowed runtime classpath.
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.kotlin")
}

val agentTest: SourceSet = sourceSets.create("agentTest")

// Only main runs inside the instrumented JVM and needs Java 17 bytecode; test code targets the toolchain.
listOf("compileTestJava", agentTest.compileJavaTaskName).forEach { name ->
    tasks.named<JavaCompile>(name) {
        options.release = 25
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("kotest-assertions-core").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())

    "agentTestImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "agentTestImplementation"(libs.findLibrary("junit-jupiter").get())
    "agentTestImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "agentTestRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

val agentTestTask = tasks.register<Test>("agentTest") {
    description = "Runs apps under the OpenTelemetry agent with this extension and checks the exported spans."
    group = "verification"
    testClassesDirs = agentTest.output.classesDirs
    classpath = agentTest.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(agentTestTask)
}
