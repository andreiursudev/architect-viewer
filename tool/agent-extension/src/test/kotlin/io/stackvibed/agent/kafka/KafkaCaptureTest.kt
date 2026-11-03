package io.stackvibed.agent.kafka

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.stackvibed.agent.contract.StackAttributes
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.utils.Bytes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.ByteBuffer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaCaptureTest {
    // The SDK hands out non-recording spans when no processor is registered, so one that needs nothing is added.
    private val tracerProvider = SdkTracerProvider.builder()
        .setSampler(Sampler.alwaysOn())
        .addSpanProcessor(object : SpanProcessor {
            override fun onStart(parentContext: Context, span: ReadWriteSpan) {}
            override fun isStartRequired() = false
            override fun onEnd(span: ReadableSpan) {}
            override fun isEndRequired() = false
        })
        .build()
    private val tracer = tracerProvider.get("kafka-capture-test")

    @AfterAll
    fun close() {
        tracerProvider.close()
    }

    /** Runs [record] with a recording SDK span current and returns that span. */
    private fun captured(record: () -> Unit): ReadableSpan {
        val span = tracer.spanBuilder("kafka").setNoParent().startSpan()
        span.makeCurrent().use { record() }
        span.end()
        return span as ReadableSpan
    }

    private fun ReadableSpan.string(key: String): String? = getAttribute(AttributeKey.stringKey(key))
    private fun ReadableSpan.boolean(key: String): Boolean? = getAttribute(AttributeKey.booleanKey(key))

    @Test
    fun aStringIsRecordedAsIs() {
        KafkaCapture.renderValue("text") shouldBe "text"
    }

    @Test
    fun bytesAreAPlaceholder() {
        KafkaCapture.renderValue(byteArrayOf(1, 2, 3)) shouldBe "<3 bytes>"
        KafkaCapture.renderValue(ByteBuffer.wrap(ByteArray(7))) shouldBe "<7 bytes>"
        KafkaCapture.renderValue(Bytes.wrap(ByteArray(4))) shouldBe "<4 bytes>"
    }

    @Test
    fun nullIsNull() {
        KafkaCapture.renderValue(null).shouldBeNull()
    }

    @Test
    fun otherValuesAreJson() {
        KafkaCapture.renderValue(mapOf("a" to 1)) shouldBe """{"a":1}"""
    }

    @Test
    fun headersAreUtf8OrAPlaceholder() {
        val headers = RecordHeaders(
            arrayOf(
                RecordHeader("traceparent", "00-ab".toByteArray()),
                RecordHeader("bin", byteArrayOf(0xC3.toByte(), 0x28)),
            ),
        )
        KafkaCapture.headersJson(headers) shouldBe """{"traceparent":["00-ab"],"bin":["<2 bytes>"]}"""
    }

    @Test
    fun repeatedHeadersAreOneList() {
        val headers = RecordHeaders(
            arrayOf(
                RecordHeader("x", "1".toByteArray()),
                RecordHeader("y", null as ByteArray?),
                RecordHeader("x", "2".toByteArray()),
            ),
        )
        KafkaCapture.headersJson(headers) shouldBe """{"x":["1","2"],"y":[null]}"""
    }

    @Test
    fun aValueIsRecordedWithItsFlag() {
        val span = captured { KafkaCapture.recordProduce(ProducerRecord("t", "k", "text")) }
        span.string(StackAttributes.KAFKA_VALUE) shouldBe "text"
        span.boolean(StackAttributes.KAFKA_VALUE_TRUNCATED) shouldBe false
        span.string(StackAttributes.KAFKA_HEADERS) shouldBe "{}"
    }

    @Test
    fun anOverLongValueIsCutAndFlagged() {
        val long = "x".repeat(StackAttributes.MAX_BODY_CHARS + 10)
        val span = captured { KafkaCapture.recordConsume(ConsumerRecord("t", 0, 0L, "k", long)) }
        span.string(StackAttributes.KAFKA_VALUE) shouldBe long.take(StackAttributes.MAX_BODY_CHARS)
        span.boolean(StackAttributes.KAFKA_VALUE_TRUNCATED) shouldBe true
    }

    @Test
    fun aTombstoneWritesNoValue() {
        val span = captured { KafkaCapture.recordConsume(ConsumerRecord<String, String?>("t", 0, 0L, "k", null)) }
        span.string(StackAttributes.KAFKA_VALUE).shouldBeNull()
        span.boolean(StackAttributes.KAFKA_VALUE_TRUNCATED).shouldBeNull()
        span.string(StackAttributes.KAFKA_HEADERS) shouldBe "{}"
    }
}
