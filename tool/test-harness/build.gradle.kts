plugins {
    id("stack.kotlin-library")
    `maven-publish`
}

dependencies {
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)

    // The consuming e2e build brings its own RestAssured and Playwright.
    compileOnly(libs.rest.assured)
    compileOnly(libs.playwright)

    testImplementation(libs.rest.assured)
    testImplementation(libs.playwright)
    testImplementation(libs.junit.platform.testkit)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("harness") {
            artifactId = "stack-test-harness"
            from(components["java"])
        }
    }
}

tasks.test {
    // Fixture classes are only run on purpose, through EngineTestKit.
    useJUnitPlatform { excludeTags("fixture") }
}
