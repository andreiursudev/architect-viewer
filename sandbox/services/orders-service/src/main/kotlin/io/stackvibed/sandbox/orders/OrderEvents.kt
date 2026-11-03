package io.stackvibed.sandbox.orders

import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The `order-placed` record: JSON on the topic, keyed by the order id. */
data class OrderPlaced(val orderId: UUID, val sku: String, val quantity: Int)

/** Announces orders on Kafka. */
@Component
class OrderEvents(private val kafka: KafkaTemplate<String, OrderPlaced>) {

    /** Publishes [order] to `order-placed` and waits for the broker's acknowledgement, so a failed publish fails the request. */
    fun placed(order: Order) {
        kafka.send("order-placed", order.id.toString(), OrderPlaced(order.id, order.sku, order.quantity)).get(5, TimeUnit.SECONDS)
    }
}
