import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("stack.kotlin-library")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
}
