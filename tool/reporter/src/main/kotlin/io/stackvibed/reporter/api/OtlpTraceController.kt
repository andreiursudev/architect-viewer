package io.stackvibed.reporter.api

import com.google.protobuf.InvalidProtocolBufferException
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import io.stackvibed.reporter.otlp.SpanEventMapper
import io.stackvibed.reporter.runs.RunResolver
import io.stackvibed.reporter.store.RecordingStore
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** OTLP/HTTP trace receiver (protobuf encoding only). */
@RestController
class OtlpTraceController(
    private val mapper: SpanEventMapper,
    private val resolver: RunResolver,
    private val store: RecordingStore,
) {
    @PostMapping("/v1/traces", consumes = ["application/x-protobuf"])
    fun export(@RequestBody body: ByteArray): ResponseEntity<ByteArray> {
        val request = try {
            ExportTraceServiceRequest.parseFrom(body)
        } catch (_: InvalidProtocolBufferException) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
        mapper.map(request)
            .groupBy({ resolver.resolve(it.tags) }, { it })
            .forEach { (assignment, spans) -> store.append(assignment, spans.map { it.toEvent.withRun(assignment.runId) }) }
        return ResponseEntity.ok()
            .header("Content-Type", "application/x-protobuf")
            .body(ExportTraceServiceResponse.getDefaultInstance().toByteArray())
    }
}
