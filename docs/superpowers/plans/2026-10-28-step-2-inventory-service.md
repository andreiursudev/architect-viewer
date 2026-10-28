# Step 2 — Second Service over HTTP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One scenario is followed across two services. `orders-service` calls a new `inventory-service` over HTTP, and the recording, board and sequence view show that hop, including a failed one.

**Architecture:** The OTel agent already creates HTTP client spans and propagates `traceparent` plus `baggage`. Step 2 proves that in an agent test, then records client spans as a new `http.client` event in the reporter. The viewer's `FlowBuilder` joins each callee server event to the caller's client event (by parent span id), producing one hop between services. A client call whose callee was not recorded becomes a hop to a component named after the target host. The UI gains a details branch for `http.client` and marks failed hops on the board.

**Tech Stack:** As in step 1 (Gradle 9.8.2, Java 25, Kotlin 2.4.21, Spring Boot 4.1.2, OTel Java agent 2.33.1, Jackson 3, React 19, @xyflow/react 12, Vitest, Playwright, Testcontainers ComposeContainer, Postgres 17). New: Spring's `RestClient` in the sandbox and the agent fixture app (Boot 4 starter `spring-boot-starter-restclient`; the implementer checks the exact artifact name against the Boot 4.1.2 BOM).

**Spec:** `docs/superpowers/specs/2026-10-27-stack-tool-design.md`, §11 "Step 2 — a second service called over HTTP", plus decisions D1–D26.

## Global Constraints

- **macOS only; Windows is deferred (D26)**. No CI (D25). Verification is local: `tool/gradlew -p tool build` and `sandbox/gradlew -p sandbox acceptanceTest`. Keep portability at no extra cost: no `:` in written file names, `Path`/`path.join` rather than string concatenation.
- Commands run from the repo root as `tool/gradlew -p tool …` and `sandbox/gradlew -p sandbox …` (R1).
- `agent-contract` and `agent-extension` stay **plain Java, `--release 17`**. The viewer never imports OTel types.
- Pin new dependencies in `tool/gradle/libs.versions.toml` / `sandbox/gradle/libs.versions.toml`; no dynamic versions.
- `SCHEMA_VERSION` becomes **2** (the spec's contract: a schema snapshot change bumps it). Readers keep accepting version 1 files; they already parse events by `type` and skip lines they cannot read.
- IDs are slugs `^[a-z0-9][a-z0-9-]*$`; connection id `<from>--<to>`; component ids come from `ComponentIds`.
- Sandbox ports are published on `127.0.0.1` only (R16). Recorded traffic is not redacted (D23).
- Test-first in every task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Push only when the owner asks.
- Docker on this Mac: if `docker` pulls/builds hang, use a scratch `DOCKER_CONFIG` without `credsStore` (R11). Never edit `~/.docker/config.json`.

## Review Focus

1. **Callee unreachable**: if `inventory-service` is down, the client span has no status and carries `error.type` (e.g. `java.net.ConnectException`). The hop must still appear, go to `inventory-service`, be marked failed and show the error type, not `ok`. Pinned in Task 5 (`unreachableCalleeIsAFailedHopToTheHost`) and Task 6 (`clientErrorIsFailed`).
2. **Two client spans for one call**: Spring's and the JDK client's instrumentation could both open a client span. One call must give exactly one `http.client` event. Pinned in Task 3 (`oneClientSpanPerCall`).
3. **Callee in a different run**: if baggage did not cross the hop, the callee's spans would land in `_unassigned` and the board would show a dangling call. Pinned in Task 3 (`baggageCrossesTheHop`) and Task 4 (`twoServicesSameRunShareOneFolder`).
4. **Old recordings**: version-1 folders recorded in step 1 must still open in the viewer after the upgrade. Pinned in Task 5 (`version1RecordingStillBuildsAFlow`).
5. **Odd host names**: a call to `Inventory-Service.internal:8081` must become the slug component `inventory-service-internal`, not an invalid file name. Pinned in Task 5 (`hostNameBecomesSlugComponent`).

---

## File Structure

```
sandbox/
  settings.gradle.kts                         # + include("services:inventory-service")
  docker-compose.yml                          # + inventory-db, inventory-service; orders-service gets INVENTORY_BASE_URL
  README.md                                   # ports, new scenario
  services/inventory-service/                 # NEW: Dockerfile, build.gradle.kts, Reservation API, Flyway V1 (table + seed)
  services/orders-service/                    # + InventoryClient (RestClient); OrdersController reserves before insert
  e2e-tests/src/main/.../OrdersScenarios.kt   # + createOrderOutOfStock()
  e2e-tests/src/test/.../OrdersE2ETest.kt     # + out-of-stock scenario
  e2e-tests/src/acceptanceTest/.../SandboxAcceptanceTest.kt   # renamed from StepOneAcceptanceTest, step 2 expectations
  stack/scenarios/create-order-out-of-stock.yaml
tool/
  model/      Events.kt (+HttpClientEvent, SCHEMA_VERSION=2), schema snapshot, Samples/EventJsonTest
  viewer-ui/  src/generated/model.ts (regenerated)
  agent-fixture-app/  RelayController.kt (calls its own /items through RestClient)
  agent-extension/    src/agentTest/.../HttpClientPropagationAgentTest.kt
  reporter/   SpanEventMapper.kt (+client draft), tests + golden/create-order-reserves/
  viewer-backend/ topology/FlowBuilder.kt, topology/TopologyService.kt, tests
  viewer-ui/  src/status.ts (NEW), board/HopDetails.tsx, board/highlight.ts, board/ConnectionEdge.tsx,
              sequence/MessageDetails.tsx, sequence/SequenceDiagram.tsx, sequence/layoutSequence.ts, styles.css,
              e2e/fixtures/recordings/<out-of-stock run>/, e2e/two-services.spec.ts
```

---

### Task 1: Sandbox — inventory-service, the reservation call, and a failing acceptance test

**Files:**
- Create: `sandbox/services/inventory-service/{build.gradle.kts,Dockerfile,.dockerignore}`, `src/main/kotlin/io/stackvibed/sandbox/inventory/{InventoryApplication.kt,ReservationsController.kt,StockRepository.kt,Reservation.kt}`, `src/main/resources/application.yaml`, `src/main/resources/db/migration/V1__stock.sql`
- Create: `sandbox/services/orders-service/src/main/kotlin/io/stackvibed/sandbox/orders/InventoryClient.kt`
- Modify: `sandbox/services/orders-service/.../OrdersController.kt`, `sandbox/services/orders-service/build.gradle.kts`, `application.yaml`
- Modify: `sandbox/settings.gradle.kts`, `sandbox/gradle/libs.versions.toml`, `sandbox/docker-compose.yml`
- Modify: `sandbox/e2e-tests/src/main/kotlin/io/stackvibed/sandbox/e2e/OrdersScenarios.kt`, `src/test/.../OrdersE2ETest.kt`
- Rename + modify: `src/acceptanceTest/.../StepOneAcceptanceTest.kt` → `SandboxAcceptanceTest.kt`
- Create: `sandbox/stack/scenarios/create-order-out-of-stock.yaml`

**Interfaces:**
- Produces (HTTP): `POST /reservations` on inventory-service, body `{"sku": String, "quantity": Int}` → `201 {"id": UUID, "sku", "quantity"}`, or `409` problem response when the stock is short. `POST /orders` → `409` when inventory answers `409`, and inserts nothing.
- Produces (data): seeded SKUs `sku-1` (available 1000000) and `sku-out` (available 0).
- Produces (compose): service `inventory-service` on `127.0.0.1:8081` (container port 8081), `inventory-db` on `127.0.0.1:15433`, database/user/password `inventory`. orders-service env `INVENTORY_BASE_URL=http://inventory-service:8081`; `application.yaml` default `inventory.base-url: http://localhost:8081` for IDE runs.

- [ ] **Step 1: Write the failing acceptance test.** Rename the class to `SandboxAcceptanceTest`, add `inventory-service` to what the `ComposeContainer` waits for, and change/add these tests:

```kotlin
@Test @StackScenario("create-order")
fun `create order is recorded across both services`() {
    OrdersScenarios(BASE_URL).createOrder()
    val run = awaitRecording("create-order", minEvents = 5)
    (run.manifest["componentsSeen"] as List<*>).toSet() shouldBe setOf("orders-service", "inventory-service")
    val servers = run.events.filter { it["type"] == "http.server" }
    servers.map { it["component"] }.toSet() shouldBe setOf("orders-service", "inventory-service")
    val reserve = servers.single { it["component"] == "inventory-service" }
    reserve["route"] shouldBe "/reservations"; reserve["status"] shouldBe 201
    (reserve["requestBody"] as String) shouldContain "sku-1"
    val client = run.events.single { it["type"] == "http.client" }
    client["component"] shouldBe "orders-service"; client["method"] shouldBe "POST"; client["status"] shouldBe 201
    (client["url"] as String) shouldEndWith "/reservations"
    reserve["parentSpanId"] shouldBe client["spanId"]
    run.events.filter { it["type"] == "db.client" }.map { it["dbName"] }.toSet() shouldBe setOf("orders", "inventory")
}

@Test @StackScenario("create-order-out-of-stock")
fun `out of stock is recorded as a failed hop and no insert`() {
    OrdersScenarios(BASE_URL).createOrderOutOfStock()
    val run = awaitRecording("create-order-out-of-stock", minEvents = 4)
    run.events.single { it["type"] == "http.server" && it["component"] == "orders-service" }["status"] shouldBe 409
    run.events.single { it["type"] == "http.client" }["status"] shouldBe 409
    val statements = run.events.filter { it["type"] == "db.client" }
    statements.single()["dbName"] shouldBe "inventory"
    statements.single()["rowCount"] shouldBe 0
}
```

Keep `get order is recorded` as it is.

- [ ] **Step 2: Build inventory-service.** Copy orders-service's build file, Dockerfile and actuator/healthcheck setup (port 8081, jar `inventory-service.jar`). `V1__stock.sql` creates `stock(sku text primary key, available int not null check (available >= 0))` and inserts the two seeded SKUs. `StockRepository.reserve(sku: String, quantity: Int): Boolean` runs exactly one statement, `update stock set available = available - ? where sku = ? and available >= ?`, and returns whether one row changed. `ReservationsController` answers `201` with a new random-UUID `Reservation`, or throws `ResponseStatusException(CONFLICT, "not enough stock for <sku>")`. No reservations table: the reservation id is not stored, so the sandbox stays minimal.

- [ ] **Step 3: Make orders-service call it.** Add `spring-boot-starter-restclient` to the catalog and to orders-service. `InventoryClient(builder: RestClient.Builder, @Value("\${inventory.base-url}") baseUrl: String)` has `reserve(sku: String, quantity: Int)`, which posts to `/reservations` and throws `ResponseStatusException(CONFLICT, …)` on a 409 response (`onStatus`). `OrdersController.create` calls `inventory.reserve(newOrder.sku, newOrder.quantity)` before `orders.insert(order)`. Use Boot's auto-configured `RestClient.Builder` bean (keep the default JDK request factory) so the agent sees the same client a real service would.

- [ ] **Step 4: Compose, scenarios, workspace.** Add `inventory-db` (postgres:17, healthcheck like `postgres`) and `inventory-service` (same agent `JAVA_TOOL_OPTIONS`, `OTEL_SERVICE_NAME: inventory-service`, datasource env pointing to `inventory-db`, healthcheck on 8081). orders-service `depends_on` inventory-service `service_healthy`. Add `OrdersScenarios.createOrderOutOfStock()`: post `{"customerId":"c-1","sku":"sku-out","quantity":1}` and expect `409`. Add an `@StackScenario("create-order-out-of-stock")` test to `OrdersE2ETest`. Add `stack/scenarios/create-order-out-of-stock.yaml` in the same shape as `create-order.yaml`.

- [ ] **Step 5: Run it and see the expected red.**

Run: `tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage && sandbox/gradlew -p sandbox stackAgent assemble acceptanceTest`
Expected: the stack comes up and the HTTP calls succeed (201 and 409). Both new tests FAIL at `single { it["type"] == "http.client" }` because the reporter drops client spans today. `get order is recorded` passes. If anything else fails, fix the sandbox before committing.

- [ ] **Step 6: Commit** — `feat(sandbox): inventory-service with stock reservation called by orders-service; failing step 2 acceptance test`

---

### Task 2: Model — `http.client` event, schema version 2

**Files:**
- Modify: `tool/model/src/main/kotlin/io/stackvibed/model/Events.kt`
- Modify: `tool/model/src/test/kotlin/io/stackvibed/model/{Samples.kt,EventJsonTest.kt}`, `tool/model/src/test/resources/schema-snapshot/model.schema.json`
- Modify: `tool/viewer-ui/src/generated/model.ts` (run `npm run gen:types` in `tool/viewer-ui` through the Gradle-downloaded Node)

**Interfaces:**
- Produces: `const val SCHEMA_VERSION = 2`, and

```kotlin
data class HttpClientEvent(
    override val schemaVersion: Int = SCHEMA_VERSION,
    override val eventId: String, override val runId: String, override val traceId: String,
    override val spanId: String, override val parentSpanId: String?,
    override val component: String,          // the caller
    override val startEpochNanos: Long, override val durationNanos: Long,
    val method: String,
    val url: String,                         // url.full as the caller sent it
    val serverAddress: String?,
    val serverPort: Int?,
    val status: Int?,                        // null when no response arrived
    val errorType: String?,                  // OTel error.type, e.g. "java.net.ConnectException"
) : RecordingEvent
```

registered as `JsonSubTypes.Type(value = HttpClientEvent::class, name = "http.client")`.

- [ ] **Step 1: Failing tests** in `EventJsonTest`: `httpClientEventRoundTrips` (a `sampleClientEvent` in `Samples.kt` serialises with `"type":"http.client"` and `"schemaVersion":2` and reads back equal), and `version1LinesStillRead` (the literal step-1 `http.server` line from `tool/reporter/src/test/resources/golden/create-order/events.jsonl` still deserialises, with `schemaVersion == 1`). Update the existing `shouldBe 1` assertions to `SCHEMA_VERSION`.
- [ ] **Step 2: Run** `tool/gradlew -p tool :model:test`. Expected: FAIL (no `HttpClientEvent`).
- [ ] **Step 3: Implement**, refresh the schema snapshot the way step 1 does (`SchemaSnapshotTest` tells how), and regenerate `model.ts`.
- [ ] **Step 4: Run** `tool/gradlew -p tool :model:test :reporter:test :viewer-ui:build`. Expected: PASS once the reporter golden files (`golden/*/events.jsonl`, `manifest.json`) are updated to `"schemaVersion":2`, since they are what the reporter now writes. The UI compiles (narrowing on `event.type` may now need an `http.client` branch: add a minimal one that renders the label only, and Task 6 fills it in).
- [ ] **Step 5: Commit** — `feat(model): http.client event, schema version 2`

---

### Task 3: Agent — prove client spans and propagation across a hop

**Files:**
- Create: `tool/agent-fixture-app/src/main/kotlin/io/stackvibed/fixture/RelayController.kt`
- Modify: `tool/agent-fixture-app/build.gradle.kts`, `tool/gradle/libs.versions.toml` (restclient starter)
- Create: `tool/agent-extension/src/agentTest/kotlin/io/stackvibed/agent/HttpClientPropagationAgentTest.kt`

**Interfaces:**
- Consumes: `ForkedAgentApp.start(receiverPort)`, `FakeOtlpReceiver.awaitSpan(predicate, timeout)` (existing).
- Produces (fixture): `POST /relay/items`, which forwards its JSON body to the app's own `POST /items` through `RestClient` and answers with the callee's status and body. `GET /relay/items/{id}`, which forwards to `GET /items/{id}` (404 passes through). The base URL is the app's own port, read from `WebServerApplicationContext.webServer.port` at call time.

- [ ] **Step 1: Failing tests** (all with `baggage: stack.scenario=s-1,stack.run=r-1` on the incoming request):
  - `clientSpanIsRecordedForRestClientCall`: a CLIENT span exists in the trace with `http.request.method=POST`, `url.full` ending `/items`, `server.address=127.0.0.1`, `http.response.status_code=201`.
  - `baggageCrossesTheHop`: the client span **and** the callee SERVER span for `/items` both carry `stack.scenario=s-1` and `stack.run=r-1`. The callee server span's `parentSpanId` equals the client span's `spanId`, and its request body attribute contains the posted name.
  - `oneClientSpanPerCall`: exactly one CLIENT span with `http.request.method` set exists between the relay server span and the callee server span.
  - `clientSeesCalleeErrorStatus`: `GET /relay/items/nope` gives a CLIENT span with `http.response.status_code=404`.
- [ ] **Step 2: Run** `tool/gradlew -p tool :agent-extension:agentTest --tests '*HttpClientPropagation*'`. Expected: FAIL (no `/relay` endpoint).
- [ ] **Step 3: Implement `RelayController`** only. No extension change is expected. **If a test still fails after the fixture exists** (no client span, two client spans, or no baggage on the callee), stop and report `BLOCKED` with the exported spans: the spec's assumption is wrong and the fix (agent config or a new instrumentation) needs a decision.
- [ ] **Step 4: Run** the same command, then `tool/gradlew -p tool :agent-extension:agentTest`. Expected: PASS, and the step-1 agent tests still pass.
- [ ] **Step 5: Commit** — `test(agent): client spans and baggage propagate across an HTTP hop`

---

### Task 4: Reporter — record HTTP client spans

**Files:**
- Modify: `tool/reporter/src/main/kotlin/io/stackvibed/reporter/otlp/SpanEventMapper.kt`
- Modify: `tool/reporter/src/test/kotlin/io/stackvibed/reporter/otlp/{OtlpFixtures.kt,SpanEventMapperTest.kt}`, `.../ReporterGoldenTest.kt`
- Create: `tool/reporter/src/test/resources/golden/create-order-reserves/{events.jsonl,manifest.json}`

**Interfaces:**
- Consumes: `HttpClientEvent` (Task 2).
- Produces: CLIENT spans map as follows: a DB draft when `db.system(.name)` is present (unchanged), otherwise an `HttpClientEvent` draft when `http.request.method` is present, otherwise nothing. Attribute sources: `url.full`, `server.address`, `server.port`, `http.response.status_code` (absent → `null`), `error.type`.

- [ ] **Step 1: Failing tests**
  - `SpanEventMapperTest.clientSpanBecomesHttpClientEvent`: every field is mapped, `component` comes from the resource's `service.name`.
  - `clientSpanWithoutResponseHasNullStatusAndErrorType`: no status code, `error.type=java.net.ConnectException` → `status == null`, `errorType == "java.net.ConnectException"`.
  - `ReporterGoldenTest.twoServicesSameRunShareOneFolder`: one OTLP request carries resource `orders-service` (server + client span) and a second request carries resource `inventory-service` (server + db span), all tagged `stack.scenario=create-order`, `stack.run=run-2`. Exactly one folder results, its `manifest.componentsSeen == ["orders-service","inventory-service"]`, and `events.jsonl` equals `golden/create-order-reserves/events.jsonl`.
- [ ] **Step 2: Run** `tool/gradlew -p tool :reporter:test`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** `clientDraft` beside `httpDraft`/`dbDraft`, and update the class KDoc (client spans are no longer dropped).
- [ ] **Step 4: Run** `tool/gradlew -p tool :reporter:test`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(reporter): record HTTP client spans as http.client events`

---

### Task 5: Viewer backend — service-to-service hops

**Files:**
- Modify: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/topology/{FlowBuilder.kt,TopologyService.kt}`
- Modify: `tool/viewer-backend/src/test/kotlin/io/stackvibed/viewer/topology/{FlowBuilderTest.kt,TopologyServiceTest.kt}`

**Interfaces:**
- Consumes: `HttpClientEvent` (Task 2).
- Produces: `FlowBuilder.build(manifest, events, aliases): Flow` (same signature) with these rules:
  1. A server event S is **called** when some client event C has `C.traceId == S.traceId && C.spanId == S.parentSpanId`. Its step goes `from = alias(C.component)`, `to = alias(S.component)`, with `parentStepId` = the caller's enclosing request of C (the existing time-containment rule, widened from `DbClientEvent` to any `RecordingEvent`), and `event = S`. C produces no step of its own.
  2. A client event C with no such S becomes a step `from = alias(C.component)`, `to = alias(ComponentIds.service(C.serverAddress ?: "unknown-host"))`, `kind = HTTP`, `label = "${C.method} ${URI(C.url).path}"` (raw `url` when it does not parse), `status = C.status`, `event = C`, parent = enclosing request of C.
  3. A server event with no recorded caller stays a step from the e2e client (unchanged).
  - `TopologyService`: the `from` of an HTTP step is typed `SERVICE`, except the e2e client, which stays `CLIENT`. The `to` of a rule-2 step is `SERVICE`.

- [ ] **Step 1: Failing tests** in `FlowBuilderTest`:
  - `calledServerIsOneHopFromCaller`: e2e→orders POST (server), orders client POST /reservations, inventory server POST /reservations (parent = client span), inventory db update. Expect exactly 3 steps: `e2e-client--orders-service`, `orders-service--inventory-service` (parent = the orders step, event type `http.server`, label `POST /reservations`), and `inventory-service--postgresql-inventory` (parent = the inventory step).
  - `unreachableCalleeIsAFailedHopToTheHost`: a client event with `serverAddress = "inventory-service"`, `status = null`, `errorType = "java.net.ConnectException"` and no callee → one step `orders-service--inventory-service` with `status == null` and `event is HttpClientEvent`.
  - `hostNameBecomesSlugComponent`: `serverAddress = "Inventory-Service.internal"` → `to == "inventory-service-internal"`.
  - `version1RecordingStillBuildsAFlow`: the step-1 fixture run (`fixtures/recordings/2026-10-27T22-41-05_create-order_run-1`) still yields its two steps unchanged.
  - `TopologyServiceTest.callerOfServiceHopIsAService`: with only the out-of-stock run as evidence, the components are typed `e2e-client: client`, `orders-service: service`, `inventory-service: service`, `postgresql-inventory: database`, and the connection `orders-service--inventory-service` is `DISCOVERED` with label `POST /reservations`.
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-backend:test`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** the rules above; update the KDoc of `build` and of `enclosingRequest`.
- [ ] **Step 4: Run** `tool/gradlew -p tool :viewer-backend:test`. Expected: PASS (Modulith and ArchUnit tests included).
- [ ] **Step 5: Commit** — `feat(viewer): hops between services; calls to unrecorded hosts`

---

### Task 6: Viewer UI — client hops and failed hops

**Files:**
- Create: `tool/viewer-ui/src/status.ts` (+ `status.test.ts`)
- Modify: `tool/viewer-ui/src/board/{HopDetails.tsx,highlight.ts,ConnectionEdge.tsx}`, `src/sequence/{MessageDetails.tsx,SequenceDiagram.tsx,layoutSequence.ts}`, `src/styles.css`, and their tests

**Interfaces:**
- Produces (`src/status.ts`):
  - `statusTone(status: number): 'is-ok' | 'is-client-error' | 'is-server-error'`, moved from the two copies in `HopDetails.tsx` / `MessageDetails.tsx` (they import it).
  - `isFailedStep(step: FlowStep): boolean`, true when `status >= 400`, or when the event is `http.client` with a non-null `errorType`.
  - `responseText(step: FlowStep): string`: the status, the simple class name of `errorType` (`ConnectException`), the row count, or `ok`. `layoutSequence`'s `responseLabel` is replaced by it.
- Produces: `Highlight.failedEdgeIds: Set<string>`, the connections on which at least one step of the run failed.

- [ ] **Step 1: Failing tests**
  - `status.test.ts`: `clientErrorIsFailed` (http.client step, `status: null`, `errorType: 'java.net.ConnectException'` → `isFailedStep` true, `responseText` `'ConnectException'`), `409IsFailed`, `201IsNotFailed`, `dbStepUsesRowCount` (`'0 rows'`).
  - `layoutSequence.test.ts`: `fourServicesNestTwoDeep`. With the Task-5 shape (e2e→orders, orders→inventory, inventory→db), participants appear in order `e2e-client, orders-service, inventory-service, postgresql-inventory`, and the rows read: request orders, request inventory, request db, response db, response inventory, response orders.
  - `highlight.test.ts`: `failedEdgesAreCollected`.
  - `ConnectionEdge` test: a highlighted edge in `failedEdgeIds` renders its chip with class `is-failed`.
  - `HopDetails`/`MessageDetails` tests: an `http.client` step shows its URL, its status or error type, and the exact text `Body not recorded: the called service is not instrumented.`
  - `SequenceDiagram` test: the response of a failed `http.client` step is labelled `ConnectException` and has the error tone.
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-ui:npmTest`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.** The failed chip gets a red border via `.edge-chip.is-failed` in `styles.css`, using the colour the existing `is-client-error` status badge uses. The sequence label tone uses `isFailedStep`/`statusTone` instead of its local `tone` logic.
- [ ] **Step 4: Run** `tool/gradlew -p tool :viewer-ui:npmTest :viewer-ui:build`. Expected: PASS, no type errors.
- [ ] **Step 5: Commit** — `feat(viewer-ui): client hops, failed hops on board and sequence; one statusTone`

---

### Task 7: Viewer e2e — a two-service run in the browser

**Files:**
- Create: `tool/viewer-ui/e2e/fixtures/recordings/2026-10-28T09-00-00_create-order-out-of-stock_run-4/{manifest.json,events.jsonl}` (schemaVersion 2, hand-written in the Task-4 golden shape: orders server 409, orders client 409, inventory server 409, inventory db update rowCount 0)
- Create: `tool/viewer-ui/e2e/two-services.spec.ts`

**Interfaces:**
- Consumes: the board/sequence selectors the existing `board.spec.ts`/`sequence.spec.ts` use.

- [ ] **Step 1: Failing spec** `two-services.spec.ts`:
  - `board shows inventory-service and its database`: nodes `inventory-service` and `postgresql-inventory` are visible.
  - `out-of-stock path crosses both services and is marked failed`: pick `create-order-out-of-stock`. The edges `e2e-client--orders-service`, `orders-service--inventory-service` and `inventory-service--postgresql-inventory` are on the path, and the chip of `orders-service--inventory-service` has class `is-failed`.
  - `sequence shows four participants and the 409`: open the sequence. The headers read `e2e-client, orders-service, inventory-service, postgresql-inventory`, a response labelled `409` is visible, and clicking `POST /reservations` shows the request body containing `sku-out`.
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-ui:e2eTest`. Expected: FAIL before the fixture exists, then green only together with Tasks 5–6. If the existing specs count runs or components, update their expectations to include the new fixture run.
- [ ] **Step 3: Add the fixture** and make the spec pass.
- [ ] **Step 4: Run** `tool/gradlew -p tool build`. Expected: BUILD SUCCESSFUL, all vitest and Playwright specs green.
- [ ] **Step 5: Commit** — `test(viewer-ui): two-service out-of-stock run end to end`

---

### Task 8: Green acceptance, docs, owner validation

**Files:**
- Modify: `sandbox/README.md`, `docs/superpowers/notes/` (new `2026-10-28-step-2-rulings-and-followups.md` if any rulings were made during execution)

- [ ] **Step 1: Run the full chain from clean recordings.**

Run: `rm -rf sandbox/recordings && tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage :viewer-backend:viewerDist && sandbox/gradlew -p sandbox stackAgent assemble acceptanceTest`
Expected: `SandboxAcceptanceTest` passes 3/3.

- [ ] **Step 2: README flow.** Update `sandbox/README.md`: inventory-service on 8081, its Postgres on 15433, the third scenario, and that `orders-service` needs inventory-service up. Then follow the README exactly (`docker compose up -d --build --wait`, `e2eTest`, start the viewer). Check `/api/workspace`: components `e2e-client, orders-service, inventory-service, postgresql-orders, postgresql-inventory`, and a discovered connection `orders-service--inventory-service` labelled `POST /reservations`. Check `/api/runs/<out-of-stock run>/flow` has the 409 hop. `docker compose down` afterwards.
- [ ] **Step 3: Commit** — `docs(sandbox): step 2 stack in the README`
- [ ] **Step 4: Owner validation** (not automated): the owner opens the viewer, accepts the new connections, and checks the board, the out-of-stock highlight and the four-participant sequence.
