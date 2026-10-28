import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.npm.task.NpxTask

plugins {
    base
    `jvm-toolchains`
    alias(libs.plugins.node.gradle)
}

// Gradle downloads its own Node, so the build does not depend on what is installed locally.
node {
    download = true
    version = libs.versions.nodejs.get()
    nodeProjectDir = layout.projectDirectory
}

val npmBuild = tasks.register<NpmTask>("npmBuild") {
    group = "build"
    description = "Type-checks and bundles the UI into dist/."
    dependsOn(tasks.npmInstall)
    args = listOf("run", "build")
    inputs.files("package.json", "package-lock.json", "vite.config.ts", "tsconfig.json", "index.html")
    inputs.dir("src")
    outputs.dir("dist")
}

val npmTest = tasks.register<NpmTask>("npmTest") {
    group = "verification"
    description = "Runs the Vitest unit tests."
    dependsOn(tasks.npmInstall)
    args = listOf("run", "test")
    inputs.files("package.json", "package-lock.json", "vite.config.ts", "tsconfig.json")
    inputs.dir("src")
    outputs.upToDateWhen { false }
}

// Playwright drives its own Chromium; this downloads it once (a no-op when the pinned build is already in the cache).
val playwrightInstall = tasks.register<NpxTask>("playwrightInstall") {
    group = "verification"
    description = "Installs the Chromium build the Playwright e2e tests run in."
    dependsOn(tasks.npmInstall)
    command = "playwright"
    args = listOf("install", "chromium")
    inputs.file("package-lock.json")
    outputs.upToDateWhen { false }
}

// The e2e tests run the real stack-viewer jar (UI bundled) against fixture folders under e2e/fixtures.
val e2eTest = tasks.register<NpmTask>("e2eTest") {
    group = "verification"
    description = "Runs the Playwright end-to-end tests against the built stack-viewer jar."
    dependsOn(tasks.npmInstall, playwrightInstall, ":viewer-backend:bootJar")
    args = listOf("run", "e2e")
    // The jar needs the Java the tool builds with (stack.kotlin-library), whatever `java` is first on PATH.
    val viewerJava = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    environment.put("STACK_VIEWER_JAVA", viewerJava.map { it.executablePath.asFile.absolutePath })
    inputs.files("package.json", "package-lock.json", "playwright.config.ts")
    inputs.dir("e2e")
    inputs.dir("src")
    outputs.upToDateWhen { false }
}

tasks.assemble { dependsOn(npmBuild) }
tasks.check { dependsOn(npmTest, e2eTest) }
