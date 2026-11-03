package io.stackvibed.sandbox.shipping

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Consumes `order-placed` and records a pending shipment for each order. */
@Component
class ShipmentListener(private val shipments: ShipmentRepository) {

    @KafkaListener(topics = ["order-placed"], groupId = "shipping")
    fun onOrderPlaced(event: OrderPlaced) {
        shipments.insert(Shipment(event.orderId, "PENDING", Instant.now().truncatedTo(ChronoUnit.MICROS)))
    }
}
