package io.stackvibed.sandbox.e2e

import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import io.stackvibed.harness.StackBaggageFilter
import org.hamcrest.Matchers.equalTo
import java.time.Duration
import java.time.Instant

/**
 * The orders user journeys. Every request carries the active `@StackScenario` (via [StackBaggageFilter]), so the
 * agent in orders-service files what it records under that scenario's run; outside a scenario they are untagged.
 * A placed order is also published to Kafka; shipping-service consumes it and [awaitShipment] waits for that.
 */
class OrdersScenarios(private val baseUrl: String, private val shippingUrl: String = "http://localhost:8082") {

    /** Places an order and returns its id. */
    fun createOrder(): String =
        request()
            .contentType(ContentType.JSON)
            .body("""{"customerId":"c-1","sku":"sku-1","quantity":2}""")
            .post("/orders")
            .then()
            .statusCode(201)
            .body("status", equalTo("CREATED"))
            .extract()
            .path("id")

    /** Orders a sku inventory-service has none of: the order is refused (409) and nothing is stored. */
    fun createOrderOutOfStock() {
        request()
            .contentType(ContentType.JSON)
            .body("""{"customerId":"c-1","sku":"sku-out","quantity":1}""")
            .post("/orders")
            .then()
            .statusCode(409)
    }

    fun getOrder(id: String) {
        request()
            .get("/orders/{id}", id)
            .then()
            .statusCode(200)
            .body("id", equalTo(id))
    }

    /**
     * Waits until shipping-service has consumed the order's `order-placed` event, i.e. `GET /shipments/{orderId}`
     * answers 200 (404 until then). Every poll carries the scenario's baggage like any other request, so the run
     * holds the polls too (at least the one that succeeds).
     */
    fun awaitShipment(orderId: String, timeout: Duration = Duration.ofSeconds(10)) {
        val deadline = Instant.now().plus(timeout)
        var last: Any? = null
        while (Instant.now().isBefore(deadline)) {
            try {
                val status = request(shippingUrl).get("/shipments/{orderId}", orderId).statusCode()
                if (status == 200) return
                last = "status $status"
            } catch (e: Exception) {
                last = e
            }
            Thread.sleep(100)
        }
        throw AssertionError("no shipment for order $orderId at $shippingUrl within $timeout; last: $last")
    }

    private fun request(uri: String = baseUrl): RequestSpecification =
        RestAssured.given()
            .baseUri(uri)
            .filter(StackBaggageFilter())
            .accept(ContentType.JSON)
}
