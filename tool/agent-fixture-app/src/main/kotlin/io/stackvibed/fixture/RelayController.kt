package io.stackvibed.fixture

import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient

/**
 * Forwards requests to this app's own `/items` endpoints through Boot's auto-configured [RestClient.Builder],
 * so the agent sees a real outbound HTTP call (client span, trace and baggage propagation) like a service calling another.
 */
@RestController
class RelayController(private val builder: RestClient.Builder, private val context: ApplicationContext) {

    @PostMapping("/relay/items")
    fun create(@RequestBody body: String): ResponseEntity<String> =
        client().post().uri("/items").contentType(MediaType.APPLICATION_JSON).body(body).exchange(::passThrough)

    @GetMapping("/relay/items/{id}")
    fun get(@PathVariable id: String): ResponseEntity<String> =
        client().get().uri("/items/{id}", id).exchange(::passThrough)

    // The port is only known once the server is listening, so the base URL is resolved per call.
    private fun client(): RestClient {
        val port = checkNotNull((context as WebServerApplicationContext).webServer) { "web server is not running" }.port
        return builder.baseUrl("http://127.0.0.1:$port").build()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun passThrough(request: HttpRequest, response: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse): ResponseEntity<String> =
        ResponseEntity.status(response.statusCode).contentType(MediaType.APPLICATION_JSON).body(response.bodyTo(String::class.java))
}
