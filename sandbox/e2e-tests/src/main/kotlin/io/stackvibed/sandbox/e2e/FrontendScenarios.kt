package io.stackvibed.sandbox.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import io.stackvibed.harness.StackBrowser
import java.util.regex.Pattern

/**
 * The frontend user journeys, driven in a browser [page] the way a person would use the app. Each one checks what the
 * user sees and ends by waiting until the page has delivered the events it recorded ([StackBrowser.flush]), so the
 * test can finish (and the scenario end) without losing them. The caller tags the page ([StackBrowser.tag]) first.
 */
class FrontendScenarios(private val page: Page, private val baseUrl: String) {

    /** Opens the shop front: the stock list shows the seeded SKUs. */
    fun browseStock() {
        page.navigate("$baseUrl/")
        assertThat(page.getByRole(AriaRole.ROW).filter(Locator.FilterOptions().setHasText("sku-1"))).isVisible()
        assertThat(page.getByRole(AriaRole.ROW).filter(Locator.FilterOptions().setHasText("sku-out"))).isVisible()
        StackBrowser.flush(page)
    }

    /** Follows the link from the stock list to the order form, orders 2 x sku-1 and lands on the order page. Returns the order id. */
    fun placeOrder(): String {
        page.navigate("$baseUrl/")
        assertThat(page.getByRole(AriaRole.TABLE)).isVisible()
        page.getByTestId("to-order").click()
        assertThat(page.getByTestId("order-form")).isVisible()
        submitOrder(sku = "sku-1", quantity = 2)

        page.waitForURL(Pattern.compile("/orders/[^/]+$"))
        val id = page.url().substringAfterLast('/')
        val order = page.getByTestId("order")
        assertThat(order).isVisible()
        assertThat(order).containsText(id)
        assertThat(order).containsText("sku-1")
        assertThat(order).containsText("CREATED")
        StackBrowser.flush(page)
        return id
    }

    /** Opens the order form directly and orders a sku that is out of stock: the form says so and nothing else happens. */
    fun placeOrderOutOfStock() {
        page.navigate("$baseUrl/order")
        assertThat(page.getByTestId("order-form")).isVisible()
        submitOrder(sku = "sku-out", quantity = 1)

        assertThat(page.getByRole(AriaRole.ALERT)).hasText("Out of stock")
        StackBrowser.flush(page)
    }

    private fun submitOrder(sku: String, quantity: Int) {
        page.getByLabel("Customer").fill("c-1")
        page.getByLabel("SKU").fill(sku)
        page.getByLabel("Quantity").fill(quantity.toString())
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Place order")).click()
    }
}
