package io.stackvibed.fixture

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * A Spring bean whose public methods the agent records when `stack.methods.packages` lists this package.
 * `@Transactional` on [count] makes Spring wrap the bean in a CGLIB proxy, which must add no spans of its own.
 */
@Service
class CatalogService(private val jdbc: JdbcClient) {

    fun price(sku: String, quantity: Int): Long = (sku.length * 100L) * quantity - discount(quantity)

    fun discount(quantity: Int): Int = if (quantity >= 3) 10 else 0

    // An accessor-style name and an Object method: both executed, neither recorded.
    fun getName(): String = toString()

    override fun toString(): String = "catalog"

    fun fail(reason: String): Nothing = throw IllegalStateException(reason)

    @Transactional
    fun count(): Long = jdbc.sql("select count(*) from items").query(Long::class.java).single()

    fun echo(value: Any?): Int = when (value) {
        null -> 0
        is CharSequence -> value.length
        else -> 1
    }
}
