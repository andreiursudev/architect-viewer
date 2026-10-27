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
    fun `workspace is detected as a module`() {
        modules.getModuleByName("workspace").isPresent shouldBe true
    }
}
