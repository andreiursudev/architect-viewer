package io.stackvibed.harness

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.FunctionCallback
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
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

    private val run = ScenarioRun("browse-stock", "6f1c1f4e-0b9e-4c55-9a43-8d5e2a1c7b10")
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @AfterEach
    fun forgetReporter() {
        System.clearProperty("stack.reporter.url")
    }

    /** Tags [page] inside [run] and returns the function it exposed as `__STACK_SCREENSHOT__`. */
    private fun tagAndCapture(): FunctionCallback {
        val callback = slot<FunctionCallback>()
        StackContext.begin(run)
        try {
            StackBrowser.tag(page)
        } finally {
            StackContext.end()
        }
        verify { page.exposeFunction("__STACK_SCREENSHOT__", capture(callback)) }
        return callback.captured
    }

    @Test
    fun `tag exposes the screenshot function by default`() {
        tagAndCapture()
    }

    @Test
    fun `tag without screenshots exposes nothing`() {
        StackContext.begin(run)
        try {
            StackBrowser.tag(page, screenshots = false)
        } finally {
            StackContext.end()
        }

        verify(exactly = 0) { page.exposeFunction(any(), any()) }
    }

    @Test
    fun `the screenshot function shoots the page and uploads it`() {
        FakeReporter().use { reporter ->
            System.setProperty("stack.reporter.url", reporter.url)
            every { page.screenshot() } returns png

            val answer = tagAndCapture().call(listOf("04b7ffff7cd2b57d"))

            answer shouldBe true
            val upload = reporter.uploads.single()
            upload.path shouldBe "/api/runs/${run.runId}/screenshots"
            upload.query shouldBe "scenarioId=browse-stock&spanIds=04b7ffff7cd2b57d"
            upload.body shouldBe png
        }
    }

    @Test
    fun `a failing upload does not fail the page's call`() {
        FakeReporter(status = 500).use { reporter ->
            System.setProperty("stack.reporter.url", reporter.url)
            every { page.screenshot() } returns png

            tagAndCapture().call(listOf("04b7ffff7cd2b57d")) shouldBe false
            reporter.uploads.size shouldBe 1
        }
    }

    @Test
    fun `tagging a page again exposes the function once and shoots for the latest run`() {
        FakeReporter().use { reporter ->
            System.setProperty("stack.reporter.url", reporter.url)
            every { page.screenshot() } returns png
            val callback = tagAndCapture()
            val next = ScenarioRun("place-order-in-browser", "0d9fe9b6-1c2a-4f3e-8a6b-2c4d5e6f7a8b")
            StackContext.begin(next)
            try {
                StackBrowser.tag(page)
            } finally {
                StackContext.end()
            }

            verify(exactly = 1) { page.exposeFunction(any(), any()) }
            callback.call(listOf("04b7ffff7cd2b57d")) shouldBe true
            reporter.uploads.single().path shouldBe "/api/runs/${next.runId}/screenshots"
        }
    }

    @Test
    fun `tagging a page again without screenshots stops them`() {
        val callback = tagAndCapture()
        StackContext.begin(run)
        try {
            StackBrowser.tag(page, screenshots = false)
        } finally {
            StackContext.end()
        }

        callback.call(listOf("04b7ffff7cd2b57d")) shouldBe false
        verify(exactly = 0) { page.screenshot() }
    }
}
