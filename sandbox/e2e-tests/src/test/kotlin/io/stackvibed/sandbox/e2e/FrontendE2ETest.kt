package io.stackvibed.sandbox.e2e

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.stackvibed.harness.StackBrowser
import io.stackvibed.harness.StackScenario
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The frontend journeys in Chromium, against a stack that is already up (`docker compose up -d --build --wait`, then
 * `sandbox/gradlew -p sandbox e2eTest`). Playwright downloads its Chromium on the first run.
 */
class FrontendE2ETest {
    private lateinit var context: BrowserContext
    private lateinit var scenarios: FrontendScenarios

    // JUnit runs @BeforeEach inside the test's @StackScenario, so the page can be tagged with it here.
    @BeforeEach
    fun openPage() {
        context = browser.newContext()
        val page: Page = context.newPage()
        StackBrowser.tag(page)
        scenarios = FrontendScenarios(page, BASE_URL)
    }

    @AfterEach
    fun closePage() {
        context.close()
    }

    @Test
    @StackScenario("browse-stock")
    fun `browse stock`() {
        scenarios.browseStock()
    }

    @Test
    @StackScenario("place-order-in-browser")
    fun `place order in browser`() {
        scenarios.placeOrder()
    }

    @Test
    @StackScenario("place-order-out-of-stock-in-browser")
    fun `place order out of stock in browser`() {
        scenarios.placeOrderOutOfStock()
    }

    companion object {
        private const val BASE_URL = "http://localhost:3000"

        private lateinit var playwright: Playwright
        private lateinit var browser: Browser

        @JvmStatic
        @BeforeAll
        fun launchBrowser() {
            playwright = Playwright.create()
            browser = playwright.chromium().launch()
        }

        @JvmStatic
        @AfterAll
        fun closeBrowser() {
            playwright.close()
        }
    }
}
