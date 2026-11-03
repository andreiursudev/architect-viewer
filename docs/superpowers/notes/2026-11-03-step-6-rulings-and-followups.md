# Step 6 — rulings and deferred follow-ups

Decisions made on the owner's behalf while following a scenario across a Kafka hop (spec
`docs/superpowers/specs/2026-11-03-step-6-kafka-design.md`, plan `docs/superpowers/plans/2026-11-03-step-6-kafka.md`),
and findings later tasks depend on. Branch `step-6-kafka`.

## Rulings

- Ruling K1 (Task 3 spike): the consumer hook is Spring Kafka's
  `RecordMessagingMessageListenerAdapter.onMessage(ConsumerRecord, Acknowledgment, Consumer)` (enter advice; argument 0
  typed `ConsumerRecord`, which leaves out the generic bridge `onMessage(Object, …)`). The plan's fallback
  (`KafkaMessageListenerContainer$ListenerConsumer.doInvokeOnMessage`) was not needed — why: against Spring Kafka 4.1.2
  (Boot 4.1.2) under OTel agent 2.33.1 the agent's `process` span is current there; `KafkaAgentTest` finds the value
  and headers on the CONSUMER span whose `messaging.operation` is `process` — cost if wrong: a batch `@KafkaListener`
  (`BatchMessagingMessageListenerAdapter`) or a hand-written `MessageListener` set on a container records the OTel span
  without a value (spec §9 already leaves plain `poll()` loops out).
- Ruling K2 (Task 3 spike): the consumer's `process` span is a child of the producer span (`parentSpanId` = the
  producer's `spanId`, no span links) — evidence: the dump below and `baggageAndParentCrossTheTopic` passing on the first
  (red) run, before any extension code. The agent's experimental receive telemetry is off by default, so no `receive`
  span sits between them; `stack.scenario`/`stack.run` arrive through the `baggage` record header, no change to
  `BaggageToAttributesSpanProcessor` — cost if wrong: with
  `otel.instrumentation.messaging.experimental.receive-telemetry.enabled=true` the process span would hang under a
  `receive` span with a link to the producer; the viewer's topic/partition/offset pairing (Task 6) covers it.
- Ruling K3 (Task 3): the producer's `stack.kafka.headers` are those present when `doSend` is entered: the
  application's own and the agent's injected `traceparent` and `baggage`, but not the ones the value serializer adds
  while serializing inside `doSend` (Spring's `__TypeId__`, `__KeyTypeId__`, `__ContentTypeId__`), which the consumer
  does see — why: the brief's enter advice, and the value must be read before serialization to be rendered as the
  application's object — cost if wrong: the producer and consumer header lists of one record differ by the serializer's
  type headers.
- Ruling K4 (Task 3, R3): the fixture's `fixture-events` producer sends a `Map` through Spring Kafka's
  `JacksonJsonSerializer`; its consumer uses Boot's default `StringDeserializer`. The producer's value is rendered by
  `BoundedValueSerializer` (`{"name":"widget-kafka"}`), the consumer's recorded as the String it received — the same
  text — cost if wrong: none.
- Ruling K5 (Task 3): Kafka's own `org.apache.kafka.common.utils.Bytes` value (the `BytesSerializer` type) is a
  `<n bytes>` placeholder too, like `byte[]` and `ByteBuffer` — why: D10, raw bytes only behind the per-topic flag;
  without the rule it would have been rendered field by field as a JSON array of numbers — cost if wrong: none.
- Ruling K6 (Task 3): `stack.kafka.value_truncated` is written beside every value (`false` when whole), as the HTTP body
  flags are; a null value (tombstone) writes neither attribute. A header with a null value is a JSON `null` in its list.
  Header values are not cut — cost if wrong: an application that puts a huge header on a record records it whole.
- Ruling K7 (Task 3): the extension compiles against `kafka-clients` 4.1.0 (compileOnly) while Boot 4.1.2 brings 4.2.2
  at runtime; the private `doSend(ProducerRecord, Callback)` has the same signature in both — cost if wrong: a
  kafka-clients version that renames `doSend` records producer spans without a value (the advice simply does not match).

## OTel Kafka span attributes

As exported by OTel Java agent 2.33.1 (default, old messaging semantic conventions: `messaging.operation`, not
`messaging.operation.type`) for a Spring Kafka 4.1.2 / kafka-clients 4.2.2 app, captured by
`KafkaAgentTest.dumpAttributes` into `tool/agent-extension/build/agent-test-logs/kafka-span-attributes.txt`. Each line
is `key = value (OTLP value type)`.

- Producer: span name `<topic> publish`, kind PRODUCER, parent = the span current at `send` (the HTTP server span here).
- Consumer: span name `<topic> process`, kind CONSUMER, parent = the producer span (Ruling K2), no links.
- Not present in this setup: `messaging.operation.type`, `messaging.consumer.group.name`,
  `messaging.kafka.destination.partition`, `error.type` (no failed send was recorded). `messaging.message.body.size`
  is on the consumer only.
- `messaging.kafka.message.key` is absent when the record has no key (the `fixture-bytes` send has a null key;
  `KafkaAgentTest.bytesAreAPlaceholder` pins it): the reporter must not assume a key.

```text
== producer: name=fixture-events publish kind=SPAN_KIND_PRODUCER links=0
spanId=e646b75d67bd006b parentSpanId=1ea434bf6389e351
messaging.client_id = producer-1 (STRING_VALUE)
messaging.destination.name = fixture-events (STRING_VALUE)
messaging.destination.partition.id = 0 (STRING_VALUE)
messaging.kafka.cluster.id = 4L6g3nShT-eMCtK--X86sw (STRING_VALUE)
messaging.kafka.message.key = widget-dump (STRING_VALUE)
messaging.kafka.message.offset = 1 (INT_VALUE)
messaging.operation = publish (STRING_VALUE)
messaging.system = kafka (STRING_VALUE)
stack.kafka.headers = {"traceparent":["00-48a5c1d829c130845c35dddc0b48e0cb-e646b75d67bd006b-01"],"baggage":["stack.run=r-1,stack.scenario=s-1"]} (STRING_VALUE)
stack.kafka.value = {"name":"widget-dump"} (STRING_VALUE)
stack.kafka.value_truncated = false (BOOL_VALUE)
stack.run = r-1 (STRING_VALUE)
stack.scenario = s-1 (STRING_VALUE)
thread.id = 59 (INT_VALUE)
thread.name = http-nio-127.0.0.1-auto-1-exec-3 (STRING_VALUE)

== consumer: name=fixture-events process kind=SPAN_KIND_CONSUMER links=0
spanId=8b8aac56829b06ec parentSpanId=e646b75d67bd006b
messaging.client_id = consumer-fixture-1 (STRING_VALUE)
messaging.destination.name = fixture-events (STRING_VALUE)
messaging.destination.partition.id = 0 (STRING_VALUE)
messaging.kafka.cluster.id = 4L6g3nShT-eMCtK--X86sw (STRING_VALUE)
messaging.kafka.consumer.group = fixture (STRING_VALUE)
messaging.kafka.message.key = widget-dump (STRING_VALUE)
messaging.kafka.message.offset = 1 (INT_VALUE)
messaging.message.body.size = 22 (INT_VALUE)
messaging.operation = process (STRING_VALUE)
messaging.system = kafka (STRING_VALUE)
stack.kafka.headers = {"traceparent":["00-48a5c1d829c130845c35dddc0b48e0cb-e646b75d67bd006b-01"],"baggage":["stack.run=r-1,stack.scenario=s-1"],"__TypeId__":["java.util.Collections$SingletonMap"],"__ContentTypeId__":["java.lang.Object"],"__KeyTypeId__":["java.lang.Object"]} (STRING_VALUE)
stack.kafka.value = {"name":"widget-dump"} (STRING_VALUE)
stack.kafka.value_truncated = false (BOOL_VALUE)
stack.run = r-1 (STRING_VALUE)
stack.scenario = s-1 (STRING_VALUE)
thread.id = 70 (INT_VALUE)
thread.name = org.springframework.kafka.KafkaListenerEndpointContainer#0-0-C-1 (STRING_VALUE)
```

## Acceptance (Task 9)

All 7 acceptance tests pass on the first run, no fix needed (Testcontainers stack, macOS, Chromium headless):
`create-order` 0.28 s (createOrder, one `GET /shipments/{orderId}` poll that already found the shipment, and the wait
for the recording), `create-order-out-of-stock` 0.36 s, `get-order` 0.24 s, `browse-stock` 0.97 s,
`place-order-in-browser` 1.02 s, `place-order-out-of-stock-in-browser` 0.60 s, `order page survives a reload` 0.62 s.

The `create-order` recording: 21 events over 58 ms (`POST /orders` 18.0 ms; the poll 26 ms). The produce took 8.2 ms
(inside `OrderEvents.placed`, 8.4 ms); the consume started 1.9 ms after it ended (the sequence note reads `+2 ms`) and
took 2.0 ms, with shipping-service's insert inside it; produce and consume agree on partition 0, offset 2. In
`place-order-in-browser` the gap was 6.1 ms (produce 10.7 ms, consume 3.8 ms).

Ruling K8 (Task 9): the e2e fixture run 7 (`2026-11-03T10-00-00_create-order-shipping_run-7`) is that real
`create-order` recording minus its `GET /shipments/{orderId}` poll (the http.server span on shipping-service, its
select, `ShipmentsController.find` and `ShipmentRepository.find`: 17 of the 21 events). The rest is as recorded, except
that the run id (`runId` and the `stack.run` baggage in its headers) is renamed `run-7` and the scenario (`scenarioId`
and the `stack.scenario` baggage) `create-order-shipping`, so the `create-order` specs keep run 5 as their latest. The
manifest is unchanged (its `lastEventAt` is still the poll's). Why: with the poll, the fixture board has an
e2e-client → shipping-service connection. On a fresh auto-laid-out board, that long edge's chip lands on the
orders-service node and takes the pointer from the board specs that drag it or open its notes. This is an older layout
issue (see Deferred follow-ups), not fixed in this step. Cost if wrong: the e2e fixture does not show the poll that the
real scenario makes.

## Deferred follow-ups

Out of scope for step 6 (spec §9):

- Retries and dead-letter topics.
- Consumers without Spring Kafka (plain `poll()` loops): the OTel agent's span is recorded, without a value or headers.
- Batch listeners (`BatchMessagingMessageListenerAdapter`) and hand-written container `MessageListener`s: no value
  (Ruling K1).
- Schema registry, Avro and protobuf, and with them a schema id on the record.
- More than one Kafka cluster.
- The per-topic raw-bytes debug flag (D10): byte values stay `<n bytes>` (Ruling K5).
- Kafka Streams.

Found while building:

- Producer headers lack the serializer's type headers (Ruling K3); an exit advice on `doSend` would see them.
- Header values are not cut (Ruling K6): a record with a huge header is recorded whole, unlike the value, which is cut
  at the body limit.
- Edge chips can cover nodes (Ruling K8): the board draws each connection's chip at its bezier midpoint, which ELK's
  auto-layout knows nothing about. A connection that skips an even number of layers puts its chip on the column of the
  middle layer, over whatever node sits there. With run 7's poll, the `GET /shipments/{orderId}` chip
  (e2e-client → shipping-service) covered orders-service almost entirely, and `POST /orders, GET /orders/{id}` partly
  covers orders-service and the frontend on the fixture board. The owner's sandbox board, with its saved layout, has no
  overlap.
