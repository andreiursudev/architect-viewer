package io.stackvibed.sandbox.e2e

import io.stackvibed.harness.StackScenario
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * The tagged e2e scenarios, run against a stack that is already up (`docker compose up -d --build --wait`, then
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
    @StackScenario("create-order-out-of-stock")
    fun `create order out of stock`() {
        scenarios.createOrderOutOfStock()
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
            awaitHealthy()
            seededOrderId = OrdersScenarios(BASE_URL).createOrder()
        }

        /** A stack started without `--wait` may still be starting orders-service; give it a minute. */
        private fun awaitHealthy(timeout: Duration = Duration.ofSeconds(60)) {
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI.create("$BASE_URL/actuator/health")).timeout(Duration.ofSeconds(2)).build()
            val deadline = Instant.now().plus(timeout)
            while (true) {
                val status = try {
                    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                } catch (_: IOException) {
                    null
                }
                if (status == 200) return
                check(Instant.now().isBefore(deadline)) {
                    "orders-service at $BASE_URL is not healthy after $timeout; is the stack up (docker compose up -d --build --wait)?"
                }
                Thread.sleep(500)
            }
        }
    }
}
