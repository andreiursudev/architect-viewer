package io.stackvibed.sandbox.shipping

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/shipments")
class ShipmentsController(private val shipments: ShipmentRepository) {

    @GetMapping("/{orderId}")
    fun find(@PathVariable orderId: UUID): ShipmentView {
        val shipment = shipments.find(orderId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no shipment for $orderId")
        return ShipmentView(shipment.orderId, shipment.status)
    }
}
