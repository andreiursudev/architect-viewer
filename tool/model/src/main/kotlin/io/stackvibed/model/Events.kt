package io.stackvibed.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** Version of the recorded-event / manifest JSON format. Any change to the schema snapshot is deliberate. */
const val SCHEMA_VERSION = 1

enum class ComponentType {
    @JsonProperty("service") SERVICE,
    @JsonProperty("database") DATABASE,
    @JsonProperty("client") CLIENT,
}

enum class RecordingKind {
    @JsonProperty("e2e") E2E,
    @JsonProperty("manual") MANUAL,
    @JsonProperty("test") TEST,
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = HttpServerEvent::class, name = "http.server"),
    JsonSubTypes.Type(value = DbClientEvent::class, name = "db.client"),
)
sealed interface RecordingEvent {
    val schemaVersion: Int
    val eventId: String
    val runId: String
    val traceId: String
    val spanId: String
    val parentSpanId: String?

    /** Id of the component the event was observed on (see [ComponentIds]). */
    val component: String
    val startEpochNanos: Long
    val durationNanos: Long
}

data class HttpServerEvent(
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val eventId: String,
    override val runId: String,
    override val traceId: String,
    override val spanId: String,
    override val parentSpanId: String?,
    override val component: String,
    override val startEpochNanos: Long,
    override val durationNanos: Long,
    val method: String,
    val route: String?,
    val path: String,
    val status: Int,
    val requestHeaders: Map<String, List<String>>,
    val responseHeaders: Map<String, List<String>>,
    val requestBody: String?,
    val responseBody: String?,
    val requestBodyTruncated: Boolean = false,
    val responseBodyTruncated: Boolean = false,
) : RecordingEvent

data class DbClientEvent(
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val eventId: String,
    override val runId: String,
    override val traceId: String,
    override val spanId: String,
    override val parentSpanId: String?,
    override val component: String,
    override val startEpochNanos: Long,
    override val durationNanos: Long,
    val dbSystem: String,
    val dbName: String?,
    val statement: String,
    val parameters: List<String?>,
    val rowCount: Long?,
) : RecordingEvent
