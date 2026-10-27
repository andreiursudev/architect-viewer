package io.stackvibed.harness

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.restassured.RestAssured
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class StackBaggageFilterTest {
    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/echo") { exchange ->
            val body = listOf("baggage", "traceparent")
                .joinToString("\n") { "$it=${exchange.requestHeaders.getFirst(it) ?: ""}" }
                .toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private fun echo(): Map<String, String> =
        RestAssured.given()
            .filter(StackBaggageFilter())
            .get("http://127.0.0.1:${server.address.port}/echo")
            .then().statusCode(200)
            .extract().asString()
            .lines().associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    @StackScenario("create-order")
    fun `filter adds headers to RestAssured requests`() {
        val seen = echo()
        seen["baggage"]!! shouldMatch Regex("stack\\.scenario=create-order,stack\\.run=[0-9a-f-]{36}")
        seen["traceparent"]!! shouldMatch Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-01")
    }

    @Test
    fun `filter adds nothing outside a scenario`() {
        val seen = echo()
        seen["baggage"] shouldBe ""
        seen["traceparent"] shouldBe ""
    }
}
