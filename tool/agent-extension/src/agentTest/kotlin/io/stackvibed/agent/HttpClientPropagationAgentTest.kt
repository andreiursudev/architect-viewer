package io.stackvibed.agent

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT as CLIENT
import io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER as SERVER
import io.stackvibed.agent.contract.StackAttributes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.HexFormat
import java.util.concurrent.ThreadLocalRandom

/**
 * Step 2 relies on the agent creating exactly one HTTP CLIENT span for a Spring `RestClient` call and on
 * traceparent plus baggage reaching the callee. The fixture's `/relay` endpoints call the app's own `/items`
 * through `RestClient`, which is a real hop through the HTTP stack.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpClientPropagationAgentTest {
    private lateinit var receiver: FakeOtlpReceiver
    private lateinit var app: ForkedAgentApp
    private val http = HttpClient.newHttpClient()

    @BeforeAll
    fun start() {
        receiver = FakeOtlpReceiver()
        app = ForkedAgentApp.start(receiver.port)
    }

    @AfterAll
    fun stop() {
        app.close()
        receiver.close()
    }

    @Test
    fun clientSpanIsRecordedForRestClientCall() {
        val call = relay("POST", "/relay/items", """{"name":"widget","quantity":2}""")
        call.status shouldBe 201

        val client = hop(call, "/items").client
        client.attr("http.request.method") shouldBe "POST"
        client.attr("url.full").orEmpty() shouldEndWith "/items"
        client.attr("server.address") shouldBe "127.0.0.1"
        client.attr("http.response.status_code") shouldBe "201"
    }

    @Test
    fun baggageCrossesTheHop() {
        val call = relay("POST", "/relay/items", """{"name":"widget-baggage","quantity":2}""")

        val hop = hop(call, "/items")
        for (span in listOf(hop.client, hop.callee)) {
            span.attr(StackAttributes.SCENARIO) shouldBe SCENARIO
            span.attr(StackAttributes.RUN) shouldBe RUN
        }
        hop.callee.parentSpanId shouldBe hop.client.spanId
        hop.callee.attr(StackAttributes.HTTP_REQUEST_BODY).orEmpty() shouldContain "widget-baggage"
    }

    @Test
    fun oneClientSpanPerCall() {
        val call = relay("POST", "/relay/items", """{"name":"widget","quantity":2}""")

        val hop = hop(call, "/items")
        val relayServer = serverSpan(call, "/relay/items")
        val httpClients = receiver.spans().filter { it.traceIdHex == call.traceId && it.kind == CLIENT && it.attr("http.request.method") != null }
        httpClients shouldHaveSize 1
        httpClients.single().parentSpanId shouldBe relayServer.spanId
        hop.callee.parentSpanId shouldBe httpClients.single().spanId
    }

    @Test
    fun clientSeesCalleeErrorStatus() {
        val call = relay("GET", "/relay/items/nope")
        call.status shouldBe 404

        hop(call, "/items/{id}").client.attr("http.response.status_code") shouldBe "404"
    }

    private class Hop(val client: Span, val callee: Span)

    /** The CLIENT span of the relay's call and the SERVER span it reached, both in [call]'s trace. */
    private fun hop(call: Call, calleeRoute: String): Hop {
        val callee = serverSpan(call, calleeRoute)
        val client = receiver.awaitSpan { it.traceIdHex == call.traceId && it.kind == CLIENT && it.attr("http.request.method") != null }
        return Hop(client, callee)
    }

    private fun serverSpan(call: Call, route: String): Span =
        receiver.awaitSpan { it.traceIdHex == call.traceId && it.kind == SERVER && it.attr("http.route") == route }

    /** One HTTP exchange, sent with its own W3C trace id and the scenario/run baggage. */
    private class Call(val traceId: String, val status: Int)

    private fun relay(method: String, path: String, body: String? = null): Call {
        val random = ThreadLocalRandom.current()
        val traceId = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
        val parentId = HexFormat.of().formatHex(ByteArray(8).also(random::nextBytes))
        val request = HttpRequest.newBuilder(URI.create(app.baseUrl + path))
            .header("traceparent", "00-$traceId-$parentId-01")
            .header("baggage", "${StackAttributes.SCENARIO}=$SCENARIO,${StackAttributes.RUN}=$RUN")
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Call(traceId, response.statusCode())
    }

    private companion object {
        const val SCENARIO = "s-1"
        const val RUN = "r-1"
    }
}
