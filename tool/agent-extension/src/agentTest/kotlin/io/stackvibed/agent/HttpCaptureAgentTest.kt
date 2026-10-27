package io.stackvibed.agent

import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER as SERVER
import io.stackvibed.agent.contract.StackAttributes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.HexFormat
import java.util.concurrent.ThreadLocalRandom

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpCaptureAgentTest {
    private lateinit var receiver: FakeOtlpReceiver
    private lateinit var app: ForkedAgentApp
    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

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
    fun baggageIsCopiedToSpanAttributes() {
        val call = post("/items", """{"name":"a","quantity":1}""", baggage = "stack.scenario=create-item,stack.run=run-1")
        val span = serverSpan(call) { it.attr("http.route") == "/items" }
        span.attr(StackAttributes.SCENARIO) shouldBe "create-item"
        span.attr(StackAttributes.RUN) shouldBe "run-1"
    }

    @Test
    fun requestAndResponseBodiesAreCapturedAsJson() {
        val call = post("/items", """{"name":"a","quantity":1}""")
        call.status shouldBe 201
        val span = serverSpan(call) { it.attr("http.route") == "/items" }

        val request = json.readTree(span.attr(StackAttributes.HTTP_REQUEST_BODY).shouldNotBeNull())
        request["name"].asString() shouldBe "a"
        request["quantity"].asInt() shouldBe 1
        val response = json.readTree(span.attr(StackAttributes.HTTP_RESPONSE_BODY).shouldNotBeNull())
        response["id"].asString() shouldBe json.readTree(call.body)["id"].asString()
        span.attr(StackAttributes.HTTP_REQUEST_BODY_TRUNCATED) shouldBeIn listOf(null, "false")
        span.attr(StackAttributes.HTTP_RESPONSE_BODY_TRUNCATED) shouldBeIn listOf(null, "false")
    }

    @Test
    fun allRequestAndResponseHeadersAreCaptured() {
        val call = get("/text", headers = mapOf("X-Probe" to "1"))
        val span = serverSpan(call)

        val requestHeaders = headerMap(span.attr(StackAttributes.HTTP_REQUEST_HEADERS).shouldNotBeNull())
        requestHeaders["x-probe"] shouldBe listOf("1")
        val responseHeaders = headerMap(span.attr(StackAttributes.HTTP_RESPONSE_HEADERS).shouldNotBeNull())
        responseHeaders["content-type"].shouldNotBeNull().single() shouldStartWith "text/plain"
    }

    @Test
    fun largeBodyIsTruncated() {
        val call = get("/big")
        call.status shouldBe 200
        (call.body.length > StackAttributes.MAX_BODY_CHARS) shouldBe true
        val span = serverSpan(call)

        val body = span.attr(StackAttributes.HTTP_RESPONSE_BODY).shouldNotBeNull()
        body.length shouldBe StackAttributes.MAX_BODY_CHARS
        body shouldBe call.body.take(StackAttributes.MAX_BODY_CHARS)
        span.attr(StackAttributes.HTTP_RESPONSE_BODY_TRUNCATED) shouldBe "true"
    }

    @Test
    fun plainTextBodyIsCaptured() {
        val call = get("/text")
        call.body shouldBe "hello"
        serverSpan(call).attr(StackAttributes.HTTP_RESPONSE_BODY) shouldBe "hello"
    }

    @Test
    fun notFoundStillRecordsStatusAndBody() {
        val call = get("/items/does-not-exist")
        call.status shouldBe 404
        val span = serverSpan(call)
        span.attr("http.response.status_code") shouldBe "404"
        span.attr(StackAttributes.HTTP_RESPONSE_BODY).shouldNotBeNull()
    }

    /** One HTTP exchange, sent with its own W3C trace id so its spans can be told apart. */
    private class Call(val traceId: String, val status: Int, val body: String)

    private fun serverSpan(call: Call, predicate: (Span) -> Boolean = { true }): Span =
        receiver.awaitSpan { it.traceIdHex == call.traceId && it.kind == SERVER && predicate(it) }

    private fun headerMap(raw: String): Map<String, List<String>> =
        json.readValue(raw, object : TypeReference<Map<String, List<String>>>() {})

    private fun get(path: String, headers: Map<String, String> = emptyMap()) =
        send(HttpRequest.newBuilder().GET(), path, headers)

    private fun post(path: String, body: String, baggage: String? = null): Call {
        val headers = buildMap {
            put("Content-Type", "application/json")
            baggage?.let { put("baggage", it) }
        }
        return send(HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString(body)), path, headers)
    }

    private fun send(builder: HttpRequest.Builder, path: String, headers: Map<String, String>): Call {
        val random = ThreadLocalRandom.current()
        val traceId = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
        val parentId = HexFormat.of().formatHex(ByteArray(8).also(random::nextBytes))
        builder.uri(URI.create(app.baseUrl + path)).header("traceparent", "00-$traceId-$parentId-01")
        headers.forEach(builder::header)
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Call(traceId, response.statusCode(), response.body())
    }
}
