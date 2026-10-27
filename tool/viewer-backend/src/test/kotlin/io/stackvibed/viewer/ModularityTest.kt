package io.stackvibed.viewer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTest {
    private val modules = ApplicationModules.of(ViewerApplication::class.java)

    @Test
    fun `modules respect boundaries`() {
        modules.verify()
    }

    @Test
    fun `all modules are detected`() {
        modules.stream().map<String> { it.identifier.toString() }.toList().toSet() shouldBe setOf("workspace", "recordings", "topology", "web")
    }
}
