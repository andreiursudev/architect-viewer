package io.stackvibed.sandbox.orders

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException

/** The call to inventory-service, made with Boot's auto-configured [RestClient.Builder] like any real service would. */
@Component
class InventoryClient(builder: RestClient.Builder, @Value("\${inventory.base-url}") baseUrl: String) {
    private val client = builder.baseUrl(baseUrl).build()

    /** Reserves stock; throws a 409 [ResponseStatusException] when inventory-service says there is not enough. */
    fun reserve(sku: String, quantity: Int) {
        client.post()
            .uri("/reservations")
            .body(ReservationRequest(sku, quantity))
            .retrieve()
            .onStatus({ it.value() == HttpStatus.CONFLICT.value() }) { _, _ ->
                throw ResponseStatusException(HttpStatus.CONFLICT, "not enough stock for $sku")
            }
            .toBodilessEntity()
    }

    private data class ReservationRequest(val sku: String, val quantity: Int)
}
