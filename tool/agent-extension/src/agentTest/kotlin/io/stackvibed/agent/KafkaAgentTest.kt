package io.stackvibed.agent

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CONSUMER as CONSUMER
import io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_PRODUCER as PRODUCER
import io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER as SERVER
import io.stackvibed.agent.contract.StackAttributes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.kafka.KafkaContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration.Companion.seconds

/**
 * Step 6: the fixture app produces to and consumes from a real broker through Spring Kafka. The OTel agent's Kafka
 * instrumentation carries traceparent and baggage in the record headers; the extension puts the record value and
 * headers on the agent's PRODUCER span and on its CONSUMER `process` span.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaAgentTest {
    private val kafka = KafkaContainer("apache/kafka:4.1.0")
    private lateinit var receiver: FakeOtlpReceiver
    private lateinit var app: ForkedAgentApp
    private val http = HttpClient.newHttpClient()

    @BeforeAll
    fun start() {
        kafka.start()
        receiver = FakeOtlpReceiver()
        app = ForkedAgentApp.start(
            receiver.port,
            listOf(
                "-Dspring.profiles.active=kafka",
                "-Dspring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
                "-Dspring.kafka.consumer.auto-offset-reset=earliest",
            ),
        )
    }

    @AfterAll
    fun stop() {
        if (::app.isInitialized) app.close()
        if (::receiver.isInitialized) receiver.close()
        kafka.stop()
    }

    @Test
    fun baggageAndParentCrossTheTopic() {
        val call = post("/kafka/events", """{"name":"widget-kafka"}""")
        call.status shouldBe 202

        val hop = hop(call)
        for (span in listOf(hop.producer, hop.consumer)) {
            span.attr(StackAttributes.SCENARIO) shouldBe SCENARIO
            span.attr(StackAttributes.RUN) shouldBe RUN
        }
        hop.consumer.parentSpanId shouldBe hop.producer.spanId
    }

    @Test
    fun valueAndHeadersAreCaptured() {
        val call = post("/kafka/events", """{"name":"widget-kafka"}""")
        call.status shouldBe 202

        val hop = hop(call)
        hop.producer.attr(StackAttributes.KAFKA_VALUE).orEmpty() shouldContain "widget-kafka"
        hop.consumer.attr(StackAttributes.KAFKA_VALUE).orEmpty() shouldContain "widget-kafka"
        hop.consumer.attr(StackAttributes.KAFKA_HEADERS).orEmpty() shouldContain "traceparent"
        serverSpan(call, "/kafka/events").attr(StackAttributes.KAFKA_VALUE).shouldBeNull()
    }

    @Test
    fun bytesAreAPlaceholder() {
        val call = post("/kafka/bytes", "")
        call.status shouldBe 202

        producerSpan(call, "fixture-bytes").attr(StackAttributes.KAFKA_VALUE) shouldBe "<5 bytes>"
    }

    /** Always passes: the attributes as exported, for the step 6 notes and the reporter's mapping. */
    @Test
    fun dumpAttributes() {
        val call = post("/kafka/events", """{"name":"widget-dump"}""")
        val hop = hop(call)
        val dump = buildString {
            for ((label, span) in listOf("producer" to hop.producer, "consumer" to hop.consumer)) {
                appendLine("== $label: name=${span.name} kind=${span.kind} links=${span.linksCount}")
                appendLine("spanId=${span.spanId.hex()} parentSpanId=${span.parentSpanId.hex()}")
                for (attribute in span.attributesList.sortedBy { it.key }) {
                    appendLine("${attribute.key} = ${span.attr(attribute.key)} (${attribute.value.valueCase})")
                }
                appendLine()
            }
        }
        val dir = Files.createDirectories(Path.of(System.getProperty("stack.agentTestLogDir")))
        Files.writeString(dir.resolve("kafka-span-attributes.txt"), dump)
    }

    private class Hop(val producer: Span, val consumer: Span)

    /** The PRODUCER span of [call]'s send to `fixture-events` and the CONSUMER `process` span of its record. */
    private fun hop(call: Call): Hop {
        val producer = producerSpan(call, "fixture-events")
        val consumer = receiver.awaitSpan({ it.traceIdHex == call.traceId && it.kind == CONSUMER && it.isProcess() }, 30.seconds)
        return Hop(producer, consumer)
    }

    private fun producerSpan(call: Call, topic: String): Span =
        receiver.awaitSpan { it.traceIdHex == call.traceId && it.kind == PRODUCER && it.attr("messaging.destination.name") == topic }

    private fun serverSpan(call: Call, route: String): Span =
        receiver.awaitSpan { it.traceIdHex == call.traceId && it.kind == SERVER && it.attr("http.route") == route }

    private fun Span.isProcess(): Boolean =
        attr("messaging.operation") == "process" || attr("messaging.operation.type") == "process"

    private fun com.google.protobuf.ByteString.hex(): String = HexFormat.of().formatHex(toByteArray())

    /** One HTTP exchange, sent with its own W3C trace id and the scenario/run baggage. */
    private class Call(val traceId: String, val status: Int)

    private fun post(path: String, body: String): Call {
        val random = ThreadLocalRandom.current()
        val traceId = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
        val parentId = HexFormat.of().formatHex(ByteArray(8).also(random::nextBytes))
        val request = HttpRequest.newBuilder(URI.create(app.baseUrl + path))
            .header("traceparent", "00-$traceId-$parentId-01")
            .header("baggage", "${StackAttributes.SCENARIO}=$SCENARIO,${StackAttributes.RUN}=$RUN")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Call(traceId, response.statusCode())
    }

    private companion object {
        const val SCENARIO = "s-1"
        const val RUN = "r-1"
    }
}
