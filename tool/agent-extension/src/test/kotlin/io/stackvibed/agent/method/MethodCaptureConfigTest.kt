package io.stackvibed.agent.method

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class MethodCaptureConfigTest {

    @Test
    fun `property wins when set`() {
        MethodCaptureConfig.basePackages("a.b", "c.d") shouldContainExactly listOf("a.b")
    }

    @Test
    fun `blank property falls back to env`() {
        MethodCaptureConfig.basePackages("  ", "c.d") shouldContainExactly listOf("c.d")
        MethodCaptureConfig.basePackages(null, "c.d") shouldContainExactly listOf("c.d")
    }

    @Test
    fun `list is split trimmed and blanks dropped`() {
        MethodCaptureConfig.basePackages(" a.b , ,c ", null) shouldContainExactly listOf("a.b", "c")
    }

    @Test
    fun `nothing set gives no packages`() {
        MethodCaptureConfig.basePackages(null, null).shouldBeEmpty()
        MethodCaptureConfig.basePackages("", " ").shouldBeEmpty()
    }
}
