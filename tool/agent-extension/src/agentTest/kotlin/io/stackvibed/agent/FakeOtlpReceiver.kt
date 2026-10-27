package io.stackvibed.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.trace.v1.Span
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Minimal OTLP/HTTP (protobuf) trace endpoint that keeps every span it receives. */
class FakeOtlpReceiver : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val lock = ReentrantLock()
    private val received = lock.newCondition()
    private val spans = mutableListOf<Span>()

    val port: Int get() = server.address.port

    init {
        server.createContext("/v1/traces", ::handleTraces)
        server.start()
    }

    private fun handleTraces(exchange: HttpExchange) {
        exchange.use {
            val raw = exchange.requestBody
            val input = if (exchange.requestHeaders.getFirst("Content-Encoding") == "gzip") GZIPInputStream(raw) else raw
            val request = ExportTraceServiceRequest.parseFrom(input)
            val batch = request.resourceSpansList.flatMap { rs -> rs.scopeSpansList.flatMap { it.spansList } }
            lock.withLock {
                spans += batch
                received.signalAll()
            }
            val response = ExportTraceServiceResponse.getDefaultInstance().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/x-protobuf")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.write(response)
        }
    }

    fun awaitSpan(timeout: Duration = 20.seconds, predicate: (Span) -> Boolean): Span {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        lock.withLock {
            while (true) {
                spans.firstOrNull(predicate)?.let { return it }
                val left = -deadline.elapsedNow()
                if (!left.isPositive()) {
                    throw AssertionError("no matching span within $timeout; received ${spans.map { it.describe() }}")
                }
                received.await(left.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun close() {
        server.stop(0)
    }
}

/** The attribute rendered as a string (numbers and booleans via toString), or null when absent. */
fun Span.attr(key: String): String? = attributesList.firstOrNull { it.key == key }?.value?.render()

val Span.traceIdHex: String get() = HexFormat.of().formatHex(traceId.toByteArray())

private fun AnyValue.render(): String = when (valueCase) {
    AnyValue.ValueCase.STRING_VALUE -> stringValue
    AnyValue.ValueCase.BOOL_VALUE -> boolValue.toString()
    AnyValue.ValueCase.INT_VALUE -> intValue.toString()
    AnyValue.ValueCase.DOUBLE_VALUE -> doubleValue.toString()
    AnyValue.ValueCase.ARRAY_VALUE -> arrayValue.valuesList.joinToString(",", "[", "]") { it.render() }
    else -> toString()
}

private fun Span.describe(): String = "$name/$kind/${traceIdHex.take(8)}"
