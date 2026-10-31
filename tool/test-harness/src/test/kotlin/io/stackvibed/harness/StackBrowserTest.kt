package io.stackvibed.harness

import com.microsoft.playwright.Page
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

class StackBrowserTest {
    private val page = mockk<Page>(relaxed = true)

    @Test
    fun `tag adds an init script with the current scenario and run`() {
        val script = slot<String>()
        StackContext.begin(ScenarioRun("browse-stock", "r-1"))
        try {
            StackBrowser.tag(page)
        } finally {
            StackContext.end()
        }

        verify { page.addInitScript(capture(script)) }
        script.captured shouldBe """window.__STACK__ = {"scenario":"browse-stock","run":"r-1"};"""
    }

    @Test
    fun `tag outside a scenario fails`() {
        val failure = shouldThrow<IllegalStateException> { StackBrowser.tag(page) }

        failure.message shouldBe "StackBrowser.tag needs an active @StackScenario"
        verify(exactly = 0) { page.addInitScript(any<String>()) }
    }

    @Test
    fun `flush awaits the page's flush hook`() {
        StackBrowser.flush(page)

        verify { page.evaluate("() => window.__STACK_FLUSH__ ? window.__STACK_FLUSH__() : null") }
    }
}
