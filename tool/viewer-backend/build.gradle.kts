plugins {
    id("stack.spring-boot-app")
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(project(":model"))

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.archunit)
}

// A fixed file name keeps the launcher independent of the project version.
tasks.bootJar { archiveFileName = "stack-viewer.jar" }
