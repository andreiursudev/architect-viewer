package io.stackvibed.agent.jdbc

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.math.BigDecimal

class ParameterRecorderTest {

    @Test
    fun valuesAreRenderedWithStringValueOf() {
        ParameterRecorder.render("setString", "widget") shouldBe "widget"
        ParameterRecorder.render("setInt", 3) shouldBe "3"
        ParameterRecorder.render("setBigDecimal", BigDecimal("1.50")) shouldBe "1.50"
        ParameterRecorder.render("setBoolean", true) shouldBe "true"
    }

    @Test
    fun sqlNullIsNull() {
        // setNull's second argument is the SQL type, never the value.
        ParameterRecorder.render("setNull", java.sql.Types.VARCHAR).shouldBeNull()
        ParameterRecorder.render("setObject", null).shouldBeNull()
        ParameterRecorder.render("setString", null).shouldBeNull()
    }

    @Test
    fun bytesAndStreamsAreNeverRead() {
        ParameterRecorder.render("setBytes", byteArrayOf(1, 2, 3)) shouldBe "<3 bytes>"
        ParameterRecorder.render("setObject", ByteArray(0)) shouldBe "<0 bytes>"
        val unreadable = object : InputStream() {
            override fun read(): Int = throw AssertionError("parameter stream must not be read")
        }
        ParameterRecorder.render("setBinaryStream", unreadable) shouldBe "<stream>"
        ParameterRecorder.render("setAsciiStream", ByteArrayInputStream(byteArrayOf(1))) shouldBe "<stream>"
        ParameterRecorder.render("setCharacterStream", StringReader("x")) shouldBe "<reader>"
    }

    @Test
    fun parametersBecomeAJsonArrayByIndex() {
        ParameterRecorder.toJson(mapOf(2 to "b\"\n", 1 to "a")) shouldBe """["a","b\"\n"]"""
        ParameterRecorder.toJson(mapOf(1 to null, 3 to "c")) shouldBe """[null,null,"c"]"""
    }
}
