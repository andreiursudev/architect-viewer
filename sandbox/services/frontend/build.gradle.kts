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

// Ruling F3: the tool's browser SDK is installed with --no-save. Its tarball comes from mavenLocal, so a lockfile entry
// would pin the integrity hash of one particular build and fail with EINTEGRITY as soon as the tool is rebuilt; and
// package.json would name a path that only exists after :stackBrowser ran. npmInstall prunes what the lockfile does not
// list, so this runs after it every time and before npmBuild, which needs the package's types.
val installStackBrowser = tasks.register<NpmTask>("installStackBrowser") {
    group = "build"
    description = "Installs @stackvibed/browser from the tool's tarball without recording it in package.json or the lockfile."
    dependsOn(tasks.npmInstall, ":stackBrowser")
    args = listOf("install", "--no-save", "../../build/stack-browser/stack-browser.tgz")
    inputs.file(rootProject.layout.buildDirectory.file("stack-browser/stack-browser.tgz"))
    outputs.upToDateWhen { false }
}

// docker-compose.yml builds the nginx image from dist/, the way the services' images are built from their jars.
val npmBuild = tasks.register<NpmTask>("npmBuild") {
    group = "build"
    description = "Type-checks and bundles the app into dist/."
    dependsOn(installStackBrowser)
    args = listOf("run", "build")
    inputs.files("package.json", "package-lock.json", "vite.config.ts", "tsconfig.json", "index.html")
    inputs.dir("src")
    inputs.file(rootProject.layout.buildDirectory.file("stack-browser/stack-browser.tgz"))
    outputs.dir("dist")
}

tasks.assemble { dependsOn(npmBuild) }
