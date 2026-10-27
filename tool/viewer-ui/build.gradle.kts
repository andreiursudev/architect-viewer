import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
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

tasks.assemble { dependsOn(npmBuild) }
tasks.check { dependsOn(npmTest) }
