package io.stackvibed.sandbox.e2e

import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import io.stackvibed.harness.StackBaggageFilter
import org.hamcrest.Matchers.equalTo

/**
 * The orders user journeys. Every request carries the active `@StackScenario` (via [StackBaggageFilter]), so the
 * agent in orders-service files what it records under that scenario's run; outside a scenario they are untagged.
 */
class OrdersScenarios(private val baseUrl: String) {

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

    fun getOrder(id: String) {
        request()
            .get("/orders/{id}", id)
            .then()
            .statusCode(200)
            .body("id", equalTo(id))
    }

    private fun request(): RequestSpecification =
        RestAssured.given()
            .baseUri(baseUrl)
            .filter(StackBaggageFilter())
            .accept(ContentType.JSON)
}
