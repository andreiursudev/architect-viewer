# Step 6 — Kafka: a scenario followed across an asynchronous hop

Date: 2026-11-03
Status: approved in conversation, written spec for review
Parent spec: `2026-10-27-stack-tool-design.md` (goal 1, D10, D13, §4 anti-goals); builds on
`2026-10-28-step-2-inventory-service.md` (plan) and `2026-10-30-step-3-method-capture-design.md`

## 1. Intent

The tool is for microservice and event-driven systems, but every step so far recorded synchronous traffic only (HTTP,
JDBC, the browser). Its core claim, that one scenario is followed across the whole stack, is unproven across a Kafka
hop, and a missed Kafka hop is the parent spec's example of the board showing a wrong architecture silently.

Success: a tagged `create-order` run records the order being published to `order-placed` and consumed by a new
`shipping-service`; the board shows orders-service → `order-placed` → shipping-service with the topic as its own node,
and the sequence view shows the produce, the consume with what it did inside, and the gap between the two.

Decided with the owner:

- **Sandbox: a new consumer service.** orders-service publishes `OrderPlaced` after storing an order; a new
  `shipping-service` consumes it and stores a shipment. Existing flows stay synchronous. Rejected: a reply event back to
  orders-service (two hops and a cycle, more to build) and turning the inventory reservation into an event (changes the
  existing scenarios; the out-of-stock path becomes eventual).
- **A topic is a node** (component type `topic`): orders-service → `order-placed` → shipping-service, two connections and
  two steps. It has room for fan-out and for a topic nobody consumes. Rejected: a labelled channel between producer and
  consumer (a topic with no consumer, or three, has nowhere to live) and one Kafka-cluster node with labelled
  connections (hides who talks to whom).
- **Happy path only.** No failing consumer, retries or dead-letter topic in this step.
- **Values captured at the Kafka client** (`ProducerRecord` / `ConsumerRecord`), rendered by the bounded value
  serialiser: framework-agnostic, as the HTTP client capture is. Rejected: capture at Spring Kafka (`KafkaTemplate.send`,
  the listener's converted argument; Spring only, and the producer span does not exist yet when `KafkaTemplate.send` is
  entered) and serialised bytes in Kafka interceptors (records wire bytes, not objects, against D10).

## 2. Sandbox

### 2.1 Compose

- `kafka`: `apache/kafka`, single node in KRaft mode; listener `127.0.0.1:9092` for services run from the IDE and
  `kafka:29092` inside compose; a healthcheck, so `docker compose up --wait` returns once it takes connections.
- `shipping-db`: `postgres:17`, database `shipping`, on `127.0.0.1:15434` (not 5432, for the reason given for 15432).
- `shipping-service`: on `127.0.0.1:8082`, under the agent like the other services
  (`-Dstack.methods.packages=io.stackvibed.sandbox.shipping`), with the same actuator healthcheck; depends on
  `shipping-db`, `kafka` and `reporter`.
- orders-service gets the bootstrap servers by environment variable and now also depends on `kafka`.
- The Podman override and the README gain the new containers and ports.

### 2.2 orders-service

- After the order is inserted, publishes `OrderPlaced {orderId, sku, quantity}` to `order-placed`, keyed by `orderId`,
  through `KafkaTemplate` with Spring's `JsonSerializer`.
- Waits for the broker's ack inside the request (`send(...).get(5, SECONDS)`), so the produce lies inside
  `POST /orders` as the inventory call does. A stand-in, not a pattern: no outbox, no transaction.
- A `409` from inventory publishes nothing.
- The topic is declared by a `NewTopic` bean (1 partition, replication 1) in both services, so startup order does not
  matter.

### 2.3 shipping-service

Spring Boot 4, Kotlin, Spring MVC, Flyway, the conventions of the other two services.

- `@KafkaListener(topics = ["order-placed"], groupId = "shipping")` with `JsonDeserializer` (trusted package
  `io.stackvibed.sandbox.*`; the event class is shipping's own copy) inserts a shipment `{orderId, status: "PENDING"}`.
- `GET /shipments/{orderId}` answers `200` with the shipment, or `404` until it exists.

### 2.4 e2e scenarios and acceptance (red first)

- `create-order`: after the `201`, polls `GET /shipments/{orderId}` (every 100 ms, 5 s cap) until `200`. The consume
  carries the run's baggage, so it lands in the same run; polling makes the run complete before the test ends. The
  polls are steps of the scenario, which is honest: the test does make them.
- `create-order-out-of-stock`: unchanged scenario; acceptance asserts the run has no `kafka.produce` or `kafka.consume`.
- `place-order-in-browser`: unchanged scenario (it does not poll); acceptance waits for the produce and consume through
  `awaitRecording`'s `minEvents`.
- New acceptance assertions for `create-order`: one `kafka.produce` on orders-service for `order-placed` with key
  `orderId` and the order in its value; one `kafka.consume` on shipping-service in group `shipping` with the same topic,
  partition and offset, whose parent span is the produce's span; the shipment `INSERT` on shipping-service inside the
  consume's time interval.

## 3. Model (schema version 5)

Versions 1–4 still deserialise unchanged. The contract snapshot moves deliberately.

```kotlin
/** A record sent to Kafka, observed by the producer. */
data class KafkaProduceEvent(/* common fields */,
    val topic: String,
    val partition: Int?,        // null when no ack arrived
    val offset: Long?,          // null when no ack arrived
    val key: String?,
    val headers: Map<String, List<String>>,
    val value: String?,
    val valueTruncated: Boolean = false,
    val valueBytes: Long?,      // serialised size, when the agent reports it
    val errorType: String?,     // the send failed
) : RecordingEvent             // "kafka.produce"

/** A record handed to a consumer's listener, observed by the consumer. */
data class KafkaConsumeEvent(/* common fields */,
    val topic: String, val partition: Int?, val offset: Long?, val key: String?,
    val consumerGroup: String?,
    val headers: Map<String, List<String>>,
    val value: String?, val valueTruncated: Boolean = false, val valueBytes: Long?,
    val errorType: String?,     // the listener threw
) : RecordingEvent             // "kafka.consume"
```

- Header values are UTF-8 strings when they decode, else `<n bytes>`. `traceparent` and `baggage` are kept, as for
  HTTP headers.
- `ComponentType.TOPIC` (`"topic"`); `ComponentIds.topic(name) = "topic-" + slug(name)`: the prefix keeps topic
  `orders` apart from service `orders`. A discovered topic's display name is the topic name.
- `ConnectionKind.KAFKA` (`"kafka"`), `FlowStepKind.KAFKA` (`"kafka"`).
- `FlowStep.causedBy: String?`: on a consume step, the step id of the produce that sent its record; null otherwise and
  when the produce was not recorded.

## 4. Agent extension

### 4.1 Producer

- Advice on `KafkaProducer.send(ProducerRecord, Callback)`, ordered after the OTel agent's own Kafka advice so that its
  producer span is current (instrumentation module `order()`).
- Records on that span `stack.kafka.value` (+ `stack.kafka.value.truncated`) and `stack.kafka.headers` (JSON).
- Value rendering: a `String` as is; `byte[]` and `ByteBuffer` as `<n bytes>` (D10: raw bytes only behind the per-topic
  debug flag, which is not built); anything else through `BoundedValueSerializer`, cut to
  `StackAttributes.MAX_BODY_CHARS`. The application's Jackson mapper is not reachable here, as it is through Spring
  MVC's converters; the bounded serialiser is the fallback D10 already names.
- The attributes must land on the producer span, not its parent: the module order guarantees it, and the agent test
  (§7) pins it by asserting the value on the producer span and not on the request span around it.

### 4.2 Consumer

- Advice at Spring Kafka's record-listener invocation, the first point where the agent's `process` span is current and
  the `ConsumerRecord` is in hand. Records the same attributes as §4.1 from `ConsumerRecord.value()` and its headers.
  The exact method is fixed by the plan's spike against the Spring Kafka version Spring Boot 4 brings.
- A consumer without Spring Kafka (a plain `poll()` loop) records the OTel span without a value: out of scope (§9).

### 4.3 Both

- Every hook swallows its own errors; capture never breaks the application.
- No change to `BaggageToAttributesSpanProcessor`: the OTel agent's Kafka instrumentation injects `traceparent` and
  `baggage` into the record headers with the global propagators and extracts them for the `process` span, so the
  consume span starts with the run's baggage in its parent context. The agent test (§7) proves it.

## 5. Reporter

- `SPAN_KIND_PRODUCER` with `messaging.system = kafka` → `KafkaProduceEvent`; `SPAN_KIND_CONSUMER` with
  `messaging.system = kafka` and operation `process` → `KafkaConsumeEvent`. Other messaging spans (`receive`, other
  systems) are dropped, as unknown spans are now.
- Topic, partition, offset, key, consumer group, body size and error from OTel's messaging attributes. Both the
  `messaging.kafka.*` names and the newer `messaging.destination.partition.id`, `messaging.consumer.group.name` are
  read, the newer first: messaging semantic conventions are still moving.
- Value and headers from `stack.kafka.*` (new constants in `agent-contract` `StackAttributes`).
- A span with no topic is dropped.

## 6. Viewer

### 6.1 Flow (`FlowBuilder`)

- **Produce:** a `kafka.produce` is a step from its component to `topic-<name>`, label `produce <topic>`. It nests like
  an outgoing HTTP call: under its owner method's step, else under the entry (§ below) enclosing it in time.
- **Consume:** a `kafka.consume` is a **root** step from `topic-<name>` to its component, label `consume <topic>`. Its
  `causedBy` is the produce whose span is its parent span; else the produce with the same topic, partition and offset;
  else null.
- **Entries:** `CallTree.enclosingRequest` becomes `enclosingEntry`: the innermost `http.server` or `kafka.consume` of
  the same component and trace containing the event in time. A consume therefore gets a handler (the listener method,
  merged into its step as a controller method is into a request's) and its database calls, methods and outgoing calls
  nest under it. A server event's caller lookup is unchanged.
- A produce with no recorded consume stays a produce step; nothing invents a consumer.
- Both steps have a null status; an `errorType` marks them failed in the UI, as a 5xx does.

### 6.2 Board and topology

- Connections `<producer>--topic-<name>` and `topic-<name>--<consumer>` are discovered with kind `kafka`, and accepted
  like any other connection.
- `TopologyService` infers type `topic` for the topic end of a `kafka` connection; the other end is a service.
- The topic node has its own shape and a stream icon; `kafka` connections have their own line style and a legend
  entry.
- A scenario's highlighted path follows the steps: orders-service → topic → shipping-service → `postgresql-shipping`.

### 6.3 Sequence view (`layoutSequence`, `SequenceDiagram`)

- The topic is a participant, placed in order of first appearance like the others (between orders-service and
  shipping-service in `create-order`).
- A `kafka` step is a request row only (no response row, as a `ui` step), drawn with an open arrowhead.
- A produce starts no activation bar on the topic. A consume's activation bar on its consumer runs from its arrow to
  the last row of its subtree.
- Roots stay ordered by start time and laid out depth-first, so a consume follows the whole subtree of the request
  that produced it even when it started before that request answered. Its arrow carries a gap note, `+<n> ms`, from
  the end of its `causedBy` produce to its start (none when `causedBy` is null).

### 6.4 Details panel (`MessageDetails`, `HopDetails`)

- Both: topic, partition, offset, key, size, headers, value as formatted JSON (a truncated value is flagged).
- Produce: "consumed by <consumer> (group <group>), +<n> ms" for each consume whose `causedBy` is it, else "not
  consumed in this run".
- Consume: "produced by <producer>" and the consumer group.

## 7. Testing (test-first)

- **Sandbox acceptance** (§2.4), red before any tool code.
- **Agent:** a fixture app produces and consumes through Spring Kafka against a Testcontainers Kafka, under the real
  OTel agent and the extension, exporting to the in-process fake reporter. Asserts: the consume span's parent is the
  produce span; both carry `stack.scenario` and `stack.run` from the baggage set before the send; value, key and
  headers are captured on both; a `String` value is recorded as is, a `byte[]` as `<n bytes>`.
- **Reporter:** golden files from recorded OTLP payloads of a producer and a consumer span, in both attribute
  namings.
- **Model:** contract snapshot at version 5; version 4 recordings still read.
- **Viewer backend:** `FlowBuilder` unit tests (pairing by parent span and by offset, an unconsumed produce, nesting and
  the handler under a consume, `causedBy`); `TopologyService` typing of topics.
- **Viewer UI:** Vitest for `layoutSequence` (no response row, activation extent, gap note) and the details panel;
  Playwright e2e on a new fixture run made from a real acceptance recording, as run 6 was.

## 8. Docs

README (new containers, ports, troubleshooting for the broker), the integration guide (Kafka: what is captured, what
needs Spring Kafka, header and byte rules), and a rulings-and-follow-ups note for the step.

## 9. Out of scope

Retries and dead-letter topics; consumers without Spring Kafka (plain `poll()` loops); batch listeners; schema
registry, Avro and protobuf (and with them a schema id); more than one Kafka cluster; the per-topic raw-bytes debug
flag; Kafka Streams.
