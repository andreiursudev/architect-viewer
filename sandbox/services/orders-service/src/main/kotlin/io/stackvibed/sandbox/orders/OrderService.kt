package io.stackvibed.sandbox.orders

import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The order logic: reserves stock at inventory-service, stores the order and announces it on Kafka. The controller keeps the HTTP concerns. */
@Service
class OrderService(
    private val orders: OrderRepository,
    private val inventory: InventoryClient,
    private val events: OrderEvents,
) {

    /** Reserves the stock, stores the order and publishes `order-placed`; when the stock cannot be reserved, nothing is stored. */
    fun create(newOrder: NewOrder): Order {
        // Before the insert: an order whose stock cannot be reserved is never stored.
        inventory.reserve(newOrder.sku, newOrder.quantity)
        val order = Order(
            id = UUID.randomUUID(),
            customerId = newOrder.customerId,
            sku = newOrder.sku,
            quantity = newOrder.quantity,
            status = OrderStatus.CREATED,
            // Postgres keeps microseconds; truncating here makes the response match what a later GET returns.
            createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
        )
        orders.insert(order)
        events.placed(order)
        return order
    }

    /** The order with this id, or null when there is none. */
    fun find(id: UUID): Order? = orders.find(id)
}
