package io.stackvibed.harness

import com.microsoft.playwright.Page

/** Playwright counterpart of [StackBaggageFilter]: puts the active scenario into a page so the recorder can attribute its events. */
object StackBrowser {
    /**
     * Makes every document the [page] loads from now on see `window.__STACK__` with the current scenario and run.
     * Call it before navigating.
     */
    fun tag(page: Page) {
        val run = checkNotNull(StackContext.current()) { "StackBrowser.tag needs an active @StackScenario" }
        // Plain interpolation is safe: scenario ids are slugs and run ids are UUIDs, both validated by StackScenarioExtension.
        page.addInitScript("""window.__STACK__ = {"scenario":"${run.scenarioId}","run":"${run.runId}"};""")
    }

    /** Waits until the page has delivered its recorded events; a no-op on a page the recorder never loaded into. */
    fun flush(page: Page) {
        page.evaluate("() => window.__STACK_FLUSH__ ? window.__STACK_FLUSH__() : null")
    }
}
