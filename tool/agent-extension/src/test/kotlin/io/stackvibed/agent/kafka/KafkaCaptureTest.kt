package io.stackvibed.agent.kafka

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.utils.Bytes
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class KafkaCaptureTest {

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
}
