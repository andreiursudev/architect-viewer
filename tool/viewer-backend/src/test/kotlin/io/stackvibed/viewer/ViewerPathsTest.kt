package io.stackvibed.viewer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

class ViewerPathsTest {
    @Configuration
    @EnableConfigurationProperties(ViewerPaths::class)
    class Config

    private val runner = ApplicationContextRunner().withUserConfiguration(Config::class.java)

    @Test
    fun `defaults are stack and recordings in the working directory`() {
        runner.run { ctx ->
            ctx.getBean(ViewerPaths::class.java) shouldBe ViewerPaths(Path.of("./stack"), Path.of("./recordings"))
        }
    }

    @Test
    fun `top-level workspace and recordings properties bind`() {
        runner.withPropertyValues("workspace=/w/stack", "recordings=/w/rec").run { ctx ->
            ctx.getBean(ViewerPaths::class.java) shouldBe ViewerPaths(Path.of("/w/stack"), Path.of("/w/rec"))
        }
    }
}
