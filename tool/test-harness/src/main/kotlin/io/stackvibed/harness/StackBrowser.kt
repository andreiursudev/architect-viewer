package io.stackvibed.harness

import com.microsoft.playwright.Page
import java.util.Collections
import java.util.WeakHashMap

/** Playwright counterpart of [StackBaggageFilter]: puts the active scenario into a page so the recorder can attribute its events. */
object StackBrowser {
    /**
     * Makes every document the [page] loads from now on see `window.__STACK__` with the current scenario and run.
     * Call it before navigating.
     *
     * With [screenshots], the page also gets `window.__STACK_SCREENSHOT__(spanIds)`: the recorder calls it once its ui
     * steps have settled, and it shoots the viewport and sends it to the reporter (`stack.reporter.url`, default
     * `http://localhost:4318`) for those steps. A screenshot that fails is logged once per page and never fails the test.
     */
    @JvmOverloads
    fun tag(page: Page, screenshots: Boolean = true) {
        val run = checkNotNull(StackContext.current()) { "StackBrowser.tag needs an active @StackScenario" }
        // Plain interpolation is safe: scenario ids are slugs and run ids are UUIDs, both validated by StackScenarioExtension.
        page.addInitScript("""window.__STACK__ = {"scenario":"${run.scenarioId}","run":"${run.runId}"};""")
        // A page is exposed once: tagging it again (a page reused across tests) only changes the run it shoots for.
        if (page in screenshotRuns) screenshotRuns[page] = if (screenshots) run else null
        else if (screenshots) {
            screenshotRuns[page] = run
            exposeScreenshots(page)
        }
    }

    // The page calls it while the test thread waits inside a Playwright call (a click the recorder's shield holds),
    // which is when Playwright runs the callback; shooting the page from inside it is safe (step-5 notes, spike).
    private fun exposeScreenshots(page: Page) {
        val uploader = ScreenshotUploader()
        var warned = false
        // Answers the page's call with whether the screenshot was stored; the page ignores it either way.
        page.exposeFunction(SCREENSHOT_FUNCTION) { args ->
            val run = screenshotRuns[page] ?: return@exposeFunction false
            try {
                val spanIds = (args[0] as List<*>).map { it as String }
                uploader.upload(run, spanIds, page.screenshot())
                true
            } catch (e: Exception) {
                if (!warned) {
                    warned = true
                    log.log(System.Logger.Level.WARNING, "Screenshots of run ${run.runId} are not recorded: ${e.message}")
                }
                false
            }
        }
    }

    /** The run each tagged page shoots for (null: screenshots turned off); weak, so a closed page is forgotten. */
    private val screenshotRuns: MutableMap<Page, ScenarioRun?> = Collections.synchronizedMap(WeakHashMap())

    private const val SCREENSHOT_FUNCTION = "__STACK_SCREENSHOT__"
    private val log: System.Logger = System.getLogger(StackBrowser::class.java.name)

    /** Waits until the page has delivered its recorded events; a no-op on a page the recorder never loaded into. */
    fun flush(page: Page) {
        page.evaluate("() => window.__STACK_FLUSH__ ? window.__STACK_FLUSH__() : null")
    }
}
