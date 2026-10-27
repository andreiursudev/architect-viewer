package io.stackvibed.fixture

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class NewItem(val name: String, val quantity: Int)

data class Item(val id: String, val name: String, val quantity: Int)

@RestController
class ItemsController {
    private val items = ConcurrentHashMap<String, Item>()

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody newItem: NewItem): Item {
        val item = Item(UUID.randomUUID().toString(), newItem.name, newItem.quantity)
        items[item.id] = item
        return item
    }

    @GetMapping("/items/{id}")
    fun get(@PathVariable id: String): Item =
        items[id] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "item $id not found")
}
