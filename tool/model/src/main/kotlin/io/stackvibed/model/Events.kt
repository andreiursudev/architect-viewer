package io.stackvibed.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Version of the recorded-event / manifest JSON format. Any change to the schema snapshot is deliberate.
 * Version 2 added [HttpClientEvent], version 3 added [MethodEvent], version 4 added [UiNavigationEvent] and
 * [UiActionEvent], version 5 added [KafkaProduceEvent] and [KafkaConsumeEvent]; older recordings still deserialise
 * unchanged.
 */
const val SCHEMA_VERSION = 5

enum class ComponentType {
    @JsonProperty("service") SERVICE,
    @JsonProperty("database") DATABASE,
    @JsonProperty("client") CLIENT,
    @JsonProperty("frontend") FRONTEND,
    @JsonProperty("topic") TOPIC,
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
    JsonSubTypes.Type(value = HttpClientEvent::class, name = "http.client"),
    JsonSubTypes.Type(value = MethodEvent::class, name = "method"),
    JsonSubTypes.Type(value = UiNavigationEvent::class, name = "ui.navigation"),
    JsonSubTypes.Type(value = UiActionEvent::class, name = "ui.action"),
    JsonSubTypes.Type(value = KafkaProduceEvent::class, name = "kafka.produce"),
    JsonSubTypes.Type(value = KafkaConsumeEvent::class, name = "kafka.consume"),
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

/** An outgoing HTTP call as observed by the caller; joined with the callee's [HttpServerEvent] into a hop by the viewer. */
data class HttpClientEvent(
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val eventId: String,
    override val runId: String,
    override val traceId: String,
    override val spanId: String,
    override val parentSpanId: String?,
    /** The caller. */
    override val component: String,
    override val startEpochNanos: Long,
    override val durationNanos: Long,
    val method: String,
    /** `url.full` as the caller sent it. */
    val url: String,
    val serverAddress: String?,
    val serverPort: Int?,
    /** Null when no response arrived. */
    val status: Int?,
    /** OTel `error.type`, e.g. `java.net.ConnectException`. */
    val errorType: String?,
) : RecordingEvent

/** A call of a method of the application's own code, recorded by the instrumentation agent. */
data class MethodEvent(
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val eventId: String,
    override val runId: String,
    override val traceId: String,
    override val spanId: String,
    override val parentSpanId: String?,
    override val component: String,
    override val startEpochNanos: Long,
    override val durationNanos: Long,
    /** Fully qualified. */
    val className: String,
    val methodName: String,
    /** Empty when the class file has none. */
    val parameterNames: List<String>,
    /** One rendered JSON value per argument. */
    val arguments: List<String>,
    /** Null for void/Unit, or when the method threw. */
    val returnValue: String?,
    /** Fully qualified. */
    val exceptionType: String?,
    val exceptionMessage: String?,
    val thread: String?,
) : RecordingEvent

enum class UiNavigationKind {
    @JsonProperty("load") LOAD,
    @JsonProperty("route") ROUTE,
}

enum class UiActionKind {
    @JsonProperty("click") CLICK,
    @JsonProperty("submit") SUBMIT,
}

/** A page load or a client-side route change in a frontend, recorded by the browser package. */
data class UiNavigationEvent(
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val eventId: String,
    override val runId: String,
    override val traceId: String,
    override val spanId: String,
    override val parentSpanId: String?,
    /** The frontend. */
    override val component: String,
    override val startEpochNanos: Long,
    override val durationNanos: Long,
    /** `location.href` after the navigation; empty when the span carried none. */
    val url: String,
    /** The pathname the navigation landed on; empty when the span carried none. */
    val route: String,
    val kind: UiNavigationKind,
) : RecordingEvent

/** A user click or form submit in a frontend, recorded by the browser package. */
data class UiActionEvent(
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val eventId: String,
    override val runId: String,
    override val traceId: String,
    override val spanId: String,
    override val parentSpanId: String?,
    /** The frontend. */
    override val component: String,
    override val startEpochNanos: Long,
    override val durationNanos: Long,
    val event: UiActionKind,
    /**
     * Describes the element acted on: the `data-testid` of the nearest ancestor-or-self of the event target that has
     * one; else the accessible name (`aria-label`, else trimmed text content) of the nearest `button`, `a` or `form`,
     * cut to 80 characters; else the target's lower-case tag name.
     */
    val target: String,
    /** The route the page was on when the action happened. */
    val route: String,
) : RecordingEvent

/** A record sent to Kafka, observed by the producer; the consumer's [KafkaConsumeEvent] is its other end. */
data class KafkaProduceEvent(
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val eventId: String,
    override val runId: String,
    override val traceId: String,
    override val spanId: String,
    override val parentSpanId: String?,
    /** The producer. */
    override val component: String,
    override val startEpochNanos: Long,
    override val durationNanos: Long,
    val topic: String,
    /** Null when no ack arrived. */
    val partition: Int?,
    /** Null when no ack arrived. */
    val offset: Long?,
    val key: String?,
    /** Header values are UTF-8 strings when they decode, else `<n bytes>`. */
    val headers: Map<String, List<String>>,
    val value: String?,
    val valueTruncated: Boolean = false,
    /** Serialised size of the value, when the agent reports it. */
    val valueBytes: Long?,
    /** Set when the send failed. */
    val errorType: String?,
) : RecordingEvent

/** A record handed to a consumer's listener, observed by the consumer. */
data class KafkaConsumeEvent(
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val eventId: String,
    override val runId: String,
    override val traceId: String,
    override val spanId: String,
    override val parentSpanId: String?,
    /** The consumer. */
    override val component: String,
    override val startEpochNanos: Long,
    override val durationNanos: Long,
    val topic: String,
    val partition: Int?,
    val offset: Long?,
    val key: String?,
    val consumerGroup: String?,
    /** Header values are UTF-8 strings when they decode, else `<n bytes>`. */
    val headers: Map<String, List<String>>,
    val value: String?,
    val valueTruncated: Boolean = false,
    /** Serialised size of the value, when the agent reports it. */
    val valueBytes: Long?,
    /** Set when the listener threw. */
    val errorType: String?,
) : RecordingEvent
