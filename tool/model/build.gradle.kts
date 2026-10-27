plugins {
    id("stack.kotlin-library")
}

// JSON-schema generation is build/test tooling, not part of the model's runtime contract:
// it is compile-only for main and kept off the published dependencies.
val schemaTooling = configurations.create("schemaTooling")
configurations.compileOnly { extendsFrom(schemaTooling) }
configurations.testImplementation { extendsFrom(schemaTooling) }

dependencies {
    api(libs.jackson.annotations)

    schemaTooling(libs.victools.generator)
    schemaTooling(libs.victools.module.jackson)
    schemaTooling(libs.jackson.databind)
    schemaTooling(libs.kotlin.reflect)
    schemaTooling(libs.slf4j.nop)

    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.module.kotlin)
}

tasks.register<JavaExec>("generateSchema") {
    group = "build"
    description = "Writes the JSON schema of the model to build/schema/model.schema.json."
    val output = layout.buildDirectory.file("schema/model.schema.json")
    outputs.file(output)
    classpath(sourceSets.main.get().runtimeClasspath, schemaTooling)
    mainClass = "io.stackvibed.model.schema.SchemaGenerator"
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath) })
}
