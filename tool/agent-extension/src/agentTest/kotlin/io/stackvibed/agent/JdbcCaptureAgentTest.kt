package io.stackvibed.agent

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT as CLIENT
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
class JdbcCaptureAgentTest {
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
    fun preparedStatementParametersAreCaptured() {
        val call = post("/items", """{"name":"widget","quantity":3}""")
        call.status shouldBe 201
        val id = json.readTree(call.body)["id"].asString()

        val span = clientSpan(call) { it.dbStatement().startsWith("insert into items") }
        decodeJsonArray(span.attr(StackAttributes.DB_PARAMETERS).shouldNotBeNull()) shouldContainExactly
            listOf(id, "widget", "3")
        span.attr(StackAttributes.DB_ROW_COUNT) shouldBe "1"

        // Pool proxies wrap the driver's statement: their execute* exits after the JDBC span has
        // ended, so nothing may leak onto the enclosing server span.
        val server = receiver.awaitSpan { it.traceIdHex == call.traceId && it.kind == SERVER }
        server.attr(StackAttributes.DB_PARAMETERS).shouldBeNull()
        server.attr(StackAttributes.DB_ROW_COUNT).shouldBeNull()
    }

    @Test
    fun selectHasParametersButNoRowCount() {
        val id = json.readTree(post("/items", """{"name":"gadget","quantity":5}""").body)["id"].asString()
        val call = get("/items/$id")
        call.status shouldBe 200

        val span = clientSpan(call) { it.dbStatement().startsWith("select id, name, quantity from items") }
        decodeJsonArray(span.attr(StackAttributes.DB_PARAMETERS).shouldNotBeNull()) shouldContainExactly listOf(id)
        span.attr(StackAttributes.DB_ROW_COUNT).shouldBeNull()
    }

    @Test
    fun sqlLiteralsAreNotSanitized() {
        post("/items", """{"name":"widget","quantity":1}""").status shouldBe 201
        val call = get("/items/widget-count")
        call.status shouldBe 200

        val span = clientSpan(call) { it.dbStatement().startsWith("select count(*) from items") }
        span.dbStatement() shouldContain "'widget'"
    }

    private fun clientSpan(call: Call, predicate: (Span) -> Boolean): Span =
        receiver.awaitSpan { it.traceIdHex == call.traceId && it.kind == CLIENT && predicate(it) }

    /** The recorded SQL: stable semconv `db.query.text`, falling back to the older `db.statement`. */
    private fun Span.dbStatement(): String = attr("db.query.text") ?: attr("db.statement") ?: ""

    private fun decodeJsonArray(raw: String): List<String?> =
        json.readValue(raw, object : TypeReference<List<String?>>() {})

    /** One HTTP exchange, sent with its own W3C trace id so its spans can be told apart. */
    private class Call(val traceId: String, val status: Int, val body: String)

    private fun get(path: String) = send(HttpRequest.newBuilder().GET(), path)

    private fun post(path: String, body: String) =
        send(
            HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json"),
            path,
        )

    private fun send(builder: HttpRequest.Builder, path: String): Call {
        val random = ThreadLocalRandom.current()
        val traceId = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
        val parentId = HexFormat.of().formatHex(ByteArray(8).also(random::nextBytes))
        builder.uri(URI.create(app.baseUrl + path)).header("traceparent", "00-$traceId-$parentId-01")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Call(traceId, response.statusCode(), response.body())
    }
}
