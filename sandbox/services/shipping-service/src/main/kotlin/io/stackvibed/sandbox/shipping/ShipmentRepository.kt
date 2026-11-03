package io.stackvibed.sandbox.shipping

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Plain SQL through [JdbcClient]: every statement is a prepared statement with bound parameters. */
@Repository
class ShipmentRepository(private val jdbc: JdbcClient) {

    fun insert(shipment: Shipment) {
        jdbc.sql("insert into shipments (order_id, status, created_at) values (?, ?, ?)")
            .params(
                shipment.orderId,
                shipment.status,
                // The Postgres driver binds OffsetDateTime (not Instant) to timestamptz.
                shipment.createdAt.atOffset(ZoneOffset.UTC),
            )
            .update()
    }

    /** The shipment of this order, or null when there is none. */
    fun find(orderId: UUID): Shipment? =
        jdbc.sql("select order_id, status, created_at from shipments where order_id = ?")
            .param(orderId)
            .query { rs, _ ->
                Shipment(rs.getObject("order_id", UUID::class.java), rs.getString("status"), rs.getObject("created_at", OffsetDateTime::class.java).toInstant())
            }
            .optional()
            .orElse(null)
}
