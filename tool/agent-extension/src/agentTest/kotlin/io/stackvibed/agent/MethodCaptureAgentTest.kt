package io.stackvibed.agent

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT as CLIENT
import io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_INTERNAL as INTERNAL
import io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER as SERVER
import io.opentelemetry.proto.trace.v1.Status
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

/**
 * Step 3: with `stack.methods.packages` set, every public method of a Spring stereotype bean in those packages
 * becomes an INTERNAL span nested by call stack, carrying its class, arguments and return value.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MethodCaptureAgentTest {
    private lateinit var receiver: FakeOtlpReceiver
    private lateinit var app: ForkedAgentApp
    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    @BeforeAll
    fun start() {
        receiver = FakeOtlpReceiver()
        app = ForkedAgentApp.start(receiver.port, listOf("-Dstack.methods.packages=io.stackvibed.fixture"))
    }

    @AfterAll
    fun stop() {
        app.close()
        receiver.close()
    }

    @Test
    fun methodSpanCarriesClassNameArgumentsAndReturn() {
        val call = get(app, "/catalog/price?sku=a&quantity=3")
        call.status shouldBe 200

        val span = methodSpan(call, "CatalogService.price")
        span.kind shouldBe INTERNAL
        span.attr(StackAttributes.METHOD_CLASS) shouldBe "io.stackvibed.fixture.CatalogService"
        span.attr(StackAttributes.METHOD_NAME) shouldBe "price"
        decode(span.attr(StackAttributes.METHOD_PARAM_NAMES).shouldNotBeNull()) shouldContainExactly listOf("sku", "quantity")
        decode(span.attr(StackAttributes.METHOD_ARGS).shouldNotBeNull()) shouldContainExactly listOf("\"a\"", "3")
        span.attr(StackAttributes.METHOD_RETURN) shouldBe "290"
        span.attr(StackAttributes.METHOD_THREAD).shouldNotBeNull().shouldNotBeBlank()
        span.attr(StackAttributes.SCENARIO) shouldBe SCENARIO
        span.attr(StackAttributes.RUN) shouldBe RUN
    }

    @Test
    fun methodsNestByCallStack() {
        val call = get(app, "/catalog/price?sku=b&quantity=1")
        call.status shouldBe 200

        val server = serverSpan(call)
        val controller = methodSpan(call, "CatalogController.price")
        val service = methodSpan(call, "CatalogService.price")
        val discount = methodSpan(call, "CatalogService.discount")
        controller.parentSpanId shouldBe server.spanId
        service.parentSpanId shouldBe controller.spanId
        discount.parentSpanId shouldBe service.spanId
    }

    @Test
    fun jdbcSpanIsChildOfTheRepositoryMethod() {
        val call = get(app, "/catalog/count")
        call.status shouldBe 200

        serverSpan(call)
        val count = methodSpan(call, "CatalogService.count")
        val db = receiver.awaitSpan {
            it.traceIdHex == call.traceId && it.kind == CLIENT &&
                (it.attr("db.query.text") ?: it.attr("db.statement")).orEmpty().startsWith("select count(*) from items")
        }
        db.parentSpanId shouldBe count.spanId
        trace(call).filter { it.name == "CatalogService.count" } shouldHaveSize 1
    }

    @Test
    fun gettersAndObjectMethodsAreNotRecorded() {
        val call = get(app, "/catalog/price?sku=c&quantity=2")
        call.status shouldBe 200
        call.body shouldContain "\"catalog\":\"catalog\""
        serverSpan(call)
        methodSpan(call, "CatalogService.price")

        val names = receiver.spans().map { it.name }
        names.filter {
            it == "CatalogService.getName" || it.endsWith(".toString") || it.endsWith(".hashCode") || it.endsWith(".equals")
        }.shouldBeEmpty()
    }

    @Test
    fun exceptionIsRecordedAndRethrownUnchanged() {
        val call = get(app, "/catalog/fail")
        call.status shouldBe 500
        call.body shouldBe "java.lang.IllegalStateException: no stock for sku-9"

        val span = methodSpan(call, "CatalogService.fail")
        span.status.code shouldBe Status.StatusCode.STATUS_CODE_ERROR
        span.attr(StackAttributes.METHOD_EXCEPTION_TYPE) shouldBe "java.lang.IllegalStateException"
        span.attr(StackAttributes.METHOD_EXCEPTION_MESSAGE) shouldBe "no stock for sku-9"
        span.attr(StackAttributes.METHOD_RETURN).shouldBeNull()
        val event = span.eventsList.single { it.name == "exception" }
        event.attributesList.single { it.key == "exception.type" }.value.stringValue shouldBe "java.lang.IllegalStateException"
        event.attributesList.single { it.key == "exception.message" }.value.stringValue shouldBe "no stock for sku-9"

        // The exception passes through the controller method too, which is marked the same way.
        methodSpan(call, "CatalogController.fail").status.code shouldBe Status.StatusCode.STATUS_CODE_ERROR
    }

    @Test
    fun hostileArgumentsAreBounded() {
        val call = send(
            app,
            HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1_000_000)))
                .header("Content-Type", "text/plain"),
            "/catalog/echo",
        )
        call.status shouldBe 200
        call.body shouldBe "1000002"

        serverSpan(call)
        val args = trace(call).filter { it.name == "CatalogService.echo" }
            .map { decode(it.attr(StackAttributes.METHOD_ARGS).shouldNotBeNull()).single().shouldNotBeNull() }
        args shouldHaveSize 3
        args.single { it.startsWith("\"x") }.length shouldBeLessThanOrEqual 4200
        args.single { it.startsWith("{") } shouldContain "\"<cycle>\""
        args shouldContain "\"<stream>\""
    }

    @Test
    fun noPackagesNoMethodSpans() {
        ForkedAgentApp.start(receiver.port).use { plain ->
            val call = get(plain, "/catalog/price?sku=a&quantity=3")
            call.status shouldBe 200

            serverSpan(call)
            withClue(trace(call).map { it.name }) {
                trace(call).filter { it.kind == INTERNAL && it.attr(StackAttributes.METHOD_CLASS) != null }.shouldBeEmpty()
            }
        }
    }

    private fun trace(call: Call): List<Span> = receiver.spans().filter { it.traceIdHex == call.traceId }

    private fun serverSpan(call: Call): Span = receiver.awaitSpan { it.traceIdHex == call.traceId && it.kind == SERVER }

    private fun methodSpan(call: Call, name: String): Span =
        receiver.awaitSpan { it.traceIdHex == call.traceId && it.name == name && it.attr(StackAttributes.METHOD_CLASS) != null }

    private fun decode(raw: String): List<String?> = json.readValue(raw, object : TypeReference<List<String?>>() {})

    /** One HTTP exchange, sent with its own W3C trace id and the scenario/run baggage. */
    private class Call(val traceId: String, val status: Int, val body: String)

    private fun get(target: ForkedAgentApp, path: String) = send(target, HttpRequest.newBuilder().GET(), path)

    private fun send(target: ForkedAgentApp, builder: HttpRequest.Builder, path: String): Call {
        val random = ThreadLocalRandom.current()
        val traceId = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
        val parentId = HexFormat.of().formatHex(ByteArray(8).also(random::nextBytes))
        builder.uri(URI.create(target.baseUrl + path))
            .header("traceparent", "00-$traceId-$parentId-01")
            .header("baggage", "${StackAttributes.SCENARIO}=$SCENARIO,${StackAttributes.RUN}=$RUN")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Call(traceId, response.statusCode(), response.body())
    }

    private companion object {
        const val SCENARIO = "s-1"
        const val RUN = "r-1"
    }
}
