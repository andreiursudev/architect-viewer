package io.stackvibed.sandbox.shipping

import java.util.UUID

/** The `order-placed` record orders-service publishes (JSON, keyed by order id). Declared again here: the services share no code. */
data class OrderPlaced(val orderId: UUID, val sku: String, val quantity: Int)
