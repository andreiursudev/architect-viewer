package io.stackvibed.sandbox.e2e

import io.stackvibed.harness.StackScenario
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The tagged e2e scenarios, run against a stack that is already up (`docker compose up -d --build`, then
 * `sandbox/gradlew -p sandbox e2eTest`). Each test is one recorded run of its scenario.
 */
class OrdersE2ETest {
    private val scenarios = OrdersScenarios(BASE_URL)

    @Test
    @StackScenario("create-order")
    fun `create order`() {
        scenarios.createOrder()
    }

    @Test
    @StackScenario("get-order")
    fun `get order`() {
        scenarios.getOrder(seededOrderId)
    }

    companion object {
        private const val BASE_URL = "http://localhost:8080"

        private lateinit var seededOrderId: String

        /**
         * get-order needs an existing order. It is created here, outside any @StackScenario, so the request is
         * untagged and the get-order run holds only the GET (the seed lands in the reporter's `_unassigned`).
         */
        @JvmStatic
        @BeforeAll
        fun seedOrder() {
            seededOrderId = OrdersScenarios(BASE_URL).createOrder()
        }
    }
}
