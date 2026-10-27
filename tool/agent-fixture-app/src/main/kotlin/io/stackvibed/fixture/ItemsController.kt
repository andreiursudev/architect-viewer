package io.stackvibed.fixture

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class NewItem(val name: String, val quantity: Int)

data class Item(val id: String, val name: String, val quantity: Int)

@RestController
class ItemsController(private val jdbc: JdbcClient) {

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody newItem: NewItem): Item {
        val item = Item(UUID.randomUUID().toString(), newItem.name, newItem.quantity)
        jdbc.sql("insert into items(id, name, quantity) values (?, ?, ?)")
            .param(item.id)
            .param(item.name)
            .param(item.quantity)
            .update()
        return item
    }

    @GetMapping("/items/{id}")
    fun get(@PathVariable id: String): Item =
        jdbc.sql("select id, name, quantity from items where id = ?")
            .param(id)
            .query { rs, _ -> Item(rs.getString("id"), rs.getString("name"), rs.getInt("quantity")) }
            .optional()
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "item $id not found") }

    /** Runs a statement whose literal is part of the SQL text, to prove recorded SQL is not sanitized. */
    @GetMapping("/items/widget-count")
    fun widgetCount(): Long =
        jdbc.sql("select count(*) from items where name = 'widget'").query(Long::class.java).single()
}
