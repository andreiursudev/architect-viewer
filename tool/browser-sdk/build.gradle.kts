import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.npm.task.NpxTask

plugins {
    base
    `maven-publish`
    alias(libs.plugins.node.gradle)
}

group = "io.stackvibed"
version = "0.1.0-SNAPSHOT"

// Gradle downloads its own Node, so the build does not depend on what is installed locally.
node {
    download = true
    version = libs.versions.nodejs.get()
    nodeProjectDir = layout.projectDirectory
}

val distDir = layout.projectDirectory.dir("dist")
val npmBuild = tasks.register<NpmTask>("npmBuild") {
    group = "build"
    description = "Compiles the package into dist/ (ES modules and type declarations); type-checks the tests."
    dependsOn(tasks.npmInstall)
    args = listOf("run", "build")
    inputs.files("package.json", "package-lock.json", "tsconfig.json", "playwright.config.ts", "vite.config.ts")
    inputs.dir("src")
    inputs.dir("e2e")
    outputs.dir(distDir)
    // tsc never removes output, so a module deleted from src/ would otherwise stay in the tarball.
    doFirst { distDir.asFile.deleteRecursively() }
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
    description = "Installs the Chromium build the Playwright browser tests run in."
    dependsOn(tasks.npmInstall)
    command = "playwright"
    args = listOf("install", "chromium")
    inputs.file("package-lock.json")
    outputs.upToDateWhen { false }
}

// The browser tests load a fixture page (served by Vite from the sources) that starts the recording.
val e2eTest = tasks.register<NpmTask>("e2eTest") {
    group = "verification"
    description = "Runs the Playwright browser tests against the fixture page."
    dependsOn(tasks.npmInstall, playwrightInstall)
    args = listOf("run", "e2e")
    inputs.files("package.json", "package-lock.json", "playwright.config.ts")
    inputs.dir("e2e")
    inputs.dir("src")
    outputs.upToDateWhen { false }
}

// The tarball a frontend installs; npm names it after the package: @stackvibed/browser 0.1.0 -> stackvibed-browser-0.1.0.tgz.
val packDir = layout.buildDirectory.dir("npm")
val packFile = packDir.map { it.file("stackvibed-browser-0.1.0.tgz") }
val npmPack = tasks.register<NpmTask>("npmPack") {
    group = "build"
    description = "Packs dist/ into build/npm/stackvibed-browser-0.1.0.tgz."
    dependsOn(npmBuild)
    args = packDir.map { listOf("pack", "--pack-destination", it.asFile.absolutePath, "--loglevel=warn") }
    inputs.files("package.json")
    inputs.dir(distDir)
    outputs.file(packFile)
    doFirst { packDir.get().asFile.mkdirs() }
}

tasks.assemble { dependsOn(npmBuild, npmPack) }
tasks.check { dependsOn(npmTest, e2eTest) }

publishing {
    publications {
        create<MavenPublication>("browser") {
            groupId = "io.stackvibed"
            artifactId = "stack-browser"
            version = "0.1.0-SNAPSHOT"
            artifact(packFile) {
                builtBy(npmPack)
                extension = "tgz"
            }
        }
    }
}
