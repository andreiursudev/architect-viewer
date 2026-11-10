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
    fun `defaults are no workspace and the list file in the home folder`() {
        runner.run { ctx ->
            ctx.getBean(ViewerPaths::class.java) shouldBe ViewerPaths(
                null,
                null,
                Path.of(System.getProperty("user.home"), ".stack-viewer", "workspaces.yaml"),
            )
        }
    }

    @Test
    fun `top-level workspace and recordings properties bind`() {
        runner.withPropertyValues("workspace=/w/stack", "recordings=/w/rec").run { ctx ->
            ctx.getBean(ViewerPaths::class.java) shouldBe ViewerPaths(Path.of("/w/stack"), Path.of("/w/rec"))
        }
    }

    @Test
    fun `workspaces-file binds`() {
        runner.withPropertyValues("workspaces-file=/w/list.yaml").run { ctx ->
            ctx.getBean(ViewerPaths::class.java) shouldBe ViewerPaths(workspacesFile = Path.of("/w/list.yaml"))
        }
    }
}
