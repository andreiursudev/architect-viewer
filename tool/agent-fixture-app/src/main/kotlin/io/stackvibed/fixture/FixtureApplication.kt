package io.stackvibed.fixture

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FixtureApplication

fun main(args: Array<String>) {
    runApplication<FixtureApplication>(*args)
}
