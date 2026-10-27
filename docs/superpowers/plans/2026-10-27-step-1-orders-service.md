# Step 1 — Orders Service Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A thin slice through every layer: `orders-service` + Postgres run locally with the agent attached, e2e tests tagged with scenarios produce recordings, and the viewer shows the board, highlighted scenario paths and a basic sequence diagram.

**Architecture:** OTel Java agent + our Java extension enrich HTTP-server and JDBC spans with `stack.*` attributes and export OTLP/HTTP to a Kotlin/Spring Boot reporter, which maps spans to our own model and writes one folder per run (`manifest.json` + `events.jsonl`). A Spring Boot 4 + Modulith viewer merges recordings (evidence) with the curated `stack/` workspace and serves a React + React Flow UI. The sandbox is a separate Gradle build that consumes the tool only through `mavenLocal` artifacts and the reporter Docker image.

**Tech Stack:** Gradle 9 (Kotlin DSL, version catalogs), Java 25, Kotlin 2, Spring Boot 4 + Spring Modulith, Jackson 3, OpenTelemetry Java agent 2.x + extension API, ByteBuddy, OTLP protobuf, JUnit 5, Kotest assertions, MockK, ArchUnit, Testcontainers, React 19 + TypeScript + Vite, @xyflow/react 12, TanStack Query, Zustand, elkjs, react-markdown, react-router, Vitest + React Testing Library, Playwright, Postgres 17, RestAssured.

**Spec:** `docs/superpowers/specs/2026-10-27-stack-tool-design.md` (decisions D1–D25; this plan implements §11 Step 1).

## Global Constraints

- macOS **and** Windows: no shell-only build steps, no `:` or other Windows-illegal characters in any file or folder name the tool writes; paths via `java.nio.file.Path` / `path.join`, never string concatenation with `/`.
- Java toolchain 25 for all modules; **`agent-contract` and `agent-extension` compile with `--release 17`** and are **plain Java** (no Kotlin stdlib inside the agent).
- Pin every dependency in `tool/gradle/libs.versions.toml` (and `sandbox/gradle/libs.versions.toml`) to the latest stable version available at Task 1; no dynamic versions. Group id `io.stackvibed`, version `0.1.0-SNAPSHOT`. Base package `io.stackvibed`.
- The viewer never imports OTel types; only `reporter` and `agent-*` know OTel (anti-goal §4).
- Recording events carry `schemaVersion = 1`; any change to the JSON schema snapshot is deliberate (D9, D21).
- IDs used as file names (component, scenario, connection) are slugs matching `^[a-z0-9][a-z0-9-]*$`; connection id is `<from>--<to>`.
- Recordings and `_unassigned` are gitignored; workspace (`stack/`) is committed.
- Every task is test-first: failing test → minimal code → green → commit. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Tool credentials never touch disk/logs (step 1 has none; keep it that way). Recorded traffic is **not** redacted (D23).

## Review Focus

1. **Windows-illegal names** — a DB named `orders` on `postgresql` or a service name with dots/uppercase must still produce valid slug ids (`postgresql-orders`, `orders-service`); pinned in Task 1 (`ComponentIdsTest`).
2. **Large or non-JSON bodies** — a 5 MB response or a `text/plain` body must not break the request or the export; the agent caps body strings at 262 144 chars and sets the `*_truncated` flag; pinned in Task 2 (`largeBodyIsTruncated`, `plainTextBodyIsCaptured`).
3. **Hand-edited, malformed workspace YAML** — one broken `components/x.yaml` must not take the viewer down; it is reported in `WorkspaceView.problems` and the rest loads; pinned in Task 7 (`malformedFileIsReportedNotFatal`).
4. **Concurrent span batches for the same run** — two services/threads exporting at once must not interleave partial lines in `events.jsonl`; pinned in Task 6 (`concurrentAppendsProduceWholeLines`).
5. **Reporter restart mid-run** — spans for a run that started before a restart must land in the existing folder, not a new one; pinned in Task 6 (`restartReusesExistingRunFolder`).

---

## File Structure

```
.github/workflows/tool.yml              # matrix ubuntu/macos/windows: ./gradlew -p tool build + UI e2e
.github/workflows/sandbox.yml           # ubuntu: publish tool, build reporter image, sandbox acceptance
.gitignore
tool/
  settings.gradle.kts  gradle/libs.versions.toml  gradlew(.bat)  gradle/wrapper/
  build-logic/                          # convention plugins: stack.kotlin-library, stack.java-agent-lib, stack.spring-boot-app
  model/                                # Kotlin: events, manifest, workspace defs, view DTOs, ComponentIds; schema snapshot
  agent-contract/                       # Java: StackAttributes constants (shared by agent + reporter)
  agent-extension/                      # Java: OTel extension (shadow jar); agentTest source set
  agent-fixture-app/                    # Kotlin Spring Boot MVC + H2 app used only by agent tests
  test-harness/                         # Kotlin: @StackScenario, JUnit 5 extension, RestAssured filter
  reporter/                             # Kotlin Spring Boot: OTLP receiver, run resolution, recording store, control API; Dockerfile
  viewer-backend/                       # Kotlin Spring Boot 4 + Modulith: workspace, recordings, topology, web; src/dist launchers
  viewer-ui/                            # React + TS + Vite; e2e/ Playwright + fixtures
sandbox/
  settings.gradle.kts  gradle/libs.versions.toml  build.gradle.kts (stackAgent copy task)
  services/orders-service/              # Spring Boot 4 Kotlin MVC + JdbcClient + Flyway; Dockerfile
  e2e-tests/                            # RestAssured scenarios + acceptanceTest source set (Testcontainers Compose)
  docker-compose.yml  README.md
  stack/                                # workspace.yaml, scenarios/
```

---

### Task 1: Tool build skeleton + `model`

**Files:**
- Create: `.gitignore`, `.github/workflows/tool.yml`, `tool/settings.gradle.kts`, `tool/gradle/libs.versions.toml`, `tool/build-logic/**`, wrapper files
- Create: `tool/model/build.gradle.kts`, `tool/model/src/main/kotlin/io/stackvibed/model/{Events.kt,Manifest.kt,ComponentIds.kt}`, `.../model/workspace/WorkspaceDefinitions.kt`, `.../model/view/Views.kt`, `tool/model/src/main/kotlin/io/stackvibed/model/schema/SchemaGenerator.kt`
- Test: `tool/model/src/test/kotlin/io/stackvibed/model/{EventJsonTest.kt,ComponentIdsTest.kt,SchemaSnapshotTest.kt}`, `tool/model/src/test/resources/schema-snapshot/model.schema.json`

**Interfaces:**
- Produces (package `io.stackvibed.model`):
  - `const val SCHEMA_VERSION = 1`
  - `enum class ComponentType { SERVICE, DATABASE, CLIENT }`, `enum class RecordingKind { E2E, MANUAL, TEST }` — serialised lowercase.
  - `sealed interface RecordingEvent { schemaVersion: Int; eventId: String; runId: String; traceId: String; spanId: String; parentSpanId: String?; component: String; startEpochNanos: Long; durationNanos: Long }` with Jackson `@JsonTypeInfo(use = NAME, property = "type")`.
  - `data class HttpServerEvent(..., method: String, route: String?, path: String, status: Int, requestHeaders: Map<String, List<String>>, responseHeaders: Map<String, List<String>>, requestBody: String?, responseBody: String?, requestBodyTruncated: Boolean = false, responseBodyTruncated: Boolean = false)` — type name `"http.server"`.
  - `data class DbClientEvent(..., dbSystem: String, dbName: String?, statement: String, parameters: List<String?>, rowCount: Long?)` — type name `"db.client"`.
  - `data class RecordingManifest(schemaVersion: Int = SCHEMA_VERSION, runId: String, scenarioId: String?, kind: RecordingKind, startedAt: Instant, lastEventAt: Instant?, stoppedAt: Instant?, reporterVersion: String, componentsSeen: List<String>)`
  - `object ComponentIds { const val E2E_CLIENT = "e2e-client"; fun slug(raw: String): String; fun service(serviceName: String): String; fun database(dbSystem: String, dbName: String?): String }`
  - `io.stackvibed.model.workspace`: `WorkspaceConfig(project: ProjectInfo, aliases: Map<String, String> = emptyMap())`, `ProjectInfo(name: String)`, `ComponentDefinition(id, type: ComponentType, displayName: String, tags: List<String> = emptyList())`, `enum ConnectionKind { HTTP, DB }`, `enum ConnectionState { DISCOVERED, ACCEPTED, MANUAL, HIDDEN }`, `ConnectionDefinition(from, to, kind, label: String?, state: ConnectionState, notes: String? = null)` with `val id get() = "$from--$to"`, `ScenarioDefinition(id, description: String?)`, `BoardLayout(nodes: Map<String, NodePosition>, viewport: Viewport?)`, `NodePosition(x: Double, y: Double)`, `Viewport(x: Double, y: Double, zoom: Double)`.
  - `io.stackvibed.model.view`: `BoardComponent(id, type, displayName, discovered: Boolean, hasNotes: Boolean)`, `BoardConnection(id, from, to, kind, label: String?, state: ConnectionState, unverified: Boolean)`, `ScenarioSummary(id, description: String?, latestRunId: String?, runCount: Int)`, `WorkspaceProblem(file: String, message: String)`, `WorkspaceView(project: ProjectInfo, components, connections, scenarios, problems)`, `FlowStep(stepId: String, parentStepId: String?, from: String, to: String, connectionId: String, kind: ConnectionKind, label: String, startEpochNanos: Long, durationNanos: Long, status: Int?, event: RecordingEvent)`, `Flow(runId: String, scenarioId: String?, steps: List<FlowStep>)`.
  - Gradle task `:model:generateSchema` writes `tool/model/build/schema/model.schema.json` (victools jsonschema-generator + Jackson module) covering all of the above.

- [ ] **Step 1: Bootstrap the build.** If `gradle` is not on PATH, install it once (`brew install gradle`) only to run `gradle wrapper --gradle-version <latest 9.x>` in `tool/`. Create convention plugins: `stack.kotlin-library` (Kotlin JVM, toolchain 25, JUnit Platform, Kotest assertions, MockK), `stack.java-agent-lib` (java-library, `options.release = 17`), `stack.spring-boot-app` (kotlin-library + Spring Boot 4 + kotlin-spring). `.gitignore`: `build/`, `.gradle/`, `node_modules/`, `dist/`, `**/recordings/`, `.idea/`. `.github/workflows/tool.yml`: matrix `[ubuntu-latest, macos-latest, windows-latest]`, temurin 25, node 22, run `./gradlew -p tool build`.

- [ ] **Step 2: Write failing tests**

```kotlin
class ComponentIdsTest {
    @Test fun `service names become slugs`() {
        ComponentIds.service("Orders.Service") shouldBe "orders-service"
    }
    @Test fun `database id joins system and name`() {
        ComponentIds.database("postgresql", "orders") shouldBe "postgresql-orders"
        ComponentIds.database("h2", null) shouldBe "h2"
    }
    @Test fun `slug strips windows illegal characters`() {
        ComponentIds.slug("db:orders/main?") shouldBe "db-orders-main"
    }
}

class EventJsonTest {
    @Test fun `http event round-trips with type discriminator`() {
        val json = mapper.writeValueAsString(sampleHttpEvent)
        json shouldContain "\"type\":\"http.server\""
        mapper.readValue<RecordingEvent>(json) shouldBe sampleHttpEvent
    }
    @Test fun `db event round-trips`()  // same for sampleDbEvent, "db.client"
    @Test fun `enums serialise lowercase`() {
        mapper.writeValueAsString(RecordingKind.E2E) shouldBe "\"e2e\""
    }
}

class SchemaSnapshotTest {
    @Test fun `schema matches committed snapshot`() {
        SchemaGenerator.generate() shouldBe readResource("schema-snapshot/model.schema.json")
    }
}
```

- [ ] **Step 3: Run** `./gradlew -p tool :model:test` — Expected: FAIL (unresolved references).
- [ ] **Step 4: Implement** the types above, `ComponentIds` (lowercase, replace any run of chars outside `[a-z0-9]` with `-`, trim leading/trailing `-`), `SchemaGenerator.generate(): String` (pretty-printed, keys sorted, deterministic) and the `generateSchema` task. Generate the snapshot once and commit it.
- [ ] **Step 5: Run** `./gradlew -p tool build` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** `feat(model): build skeleton and recording/workspace model v1`

---

### Task 2: Agent extension — HTTP server capture

**Files:**
- Create: `tool/agent-contract/src/main/java/io/stackvibed/agent/contract/StackAttributes.java`
- Create: `tool/agent-extension/build.gradle.kts` (shadow jar `stack-agent-extension-<version>.jar`, `compileOnly` OTel extension API + ByteBuddy, `implementation(project(":agent-contract"))`; configuration `otelAgent` resolving `io.opentelemetry.javaagent:opentelemetry-javaagent`)
- Create: `tool/agent-extension/src/main/java/io/stackvibed/agent/{StackAutoConfiguration.java,BaggageToAttributesSpanProcessor.java,BodyCapture.java,BoundedValueSerializer.java,HeaderCapture.java}`, `.../agent/http/{SpringMvcBodyInstrumentationModule.java,ServletHeadersInstrumentationModule.java}`, `META-INF/services` registrations
- Create: `tool/agent-fixture-app/**` (Kotlin Spring Boot MVC + H2; endpoints below)
- Test: `tool/agent-extension/src/agentTest/kotlin/io/stackvibed/agent/{ForkedAgentApp.kt,FakeOtlpReceiver.kt,HttpCaptureAgentTest.kt}`

**Interfaces:**
- Produces `StackAttributes` (Java `public static final String`): `SCENARIO = "stack.scenario"`, `RUN = "stack.run"`, `HTTP_REQUEST_HEADERS = "stack.http.request.headers"`, `HTTP_RESPONSE_HEADERS = "stack.http.response.headers"`, `HTTP_REQUEST_BODY = "stack.http.request.body"`, `HTTP_RESPONSE_BODY = "stack.http.response.body"`, `HTTP_REQUEST_BODY_TRUNCATED = "stack.http.request.body_truncated"`, `HTTP_RESPONSE_BODY_TRUNCATED = "stack.http.response.body_truncated"`, `DB_PARAMETERS = "stack.db.parameters"`, `DB_ROW_COUNT = "stack.db.row_count"`, `MAX_BODY_CHARS = 262_144`. Header maps and parameter lists are JSON strings.
- Produces artifacts: `io.stackvibed:stack-agent-extension` (shadow jar) published to mavenLocal.
- Extension defaults (via `AutoConfigurationCustomizerProvider.addPropertiesSupplier`, overridable by env/system props): `otel.traces.exporter=otlp`, `otel.exporter.otlp.protocol=http/protobuf`, `otel.exporter.otlp.endpoint=http://localhost:4318`, `otel.metrics.exporter=none`, `otel.logs.exporter=none`, `otel.traces.sampler=always_on`, `otel.bsp.schedule.delay=200`, `otel.instrumentation.common.db-statement-sanitizer.enabled=false`.
- Fixture app endpoints: `POST /items` (JSON body `{name, quantity}` → 201 echo with id), `GET /items/{id}` (200 / 404), `GET /big` (5 MB JSON array), `GET /text` (`text/plain` "hello"), JDBC via `JdbcClient` against H2 (used in Task 3).
- Test infra: `ForkedAgentApp.start(receiverPort: Int): ForkedAgentApp` (launches `java -javaagent:<otel> -Dotel.javaagent.extensions=<ext> -Dotel.exporter.otlp.endpoint=http://localhost:<port> -Dotel.service.name=fixture-app -jar <fixture> --server.port=0`, parses the bound port from stdout, `baseUrl: String`, `close()`); `FakeOtlpReceiver` (JDK `HttpServer` on `/v1/traces`, decodes `ExportTraceServiceRequest`, `awaitSpan(predicate, timeout): Span`). Gradle `agentTest` task passes jar paths as system properties and is part of `check`.

- [ ] **Step 1: Write failing tests** (`HttpCaptureAgentTest`, one forked app per class)

```kotlin
@Test fun baggageIsCopiedToSpanAttributes() {
    post("/items", """{"name":"a","quantity":1}""", baggage = "stack.scenario=create-item,stack.run=run-1")
    val span = receiver.awaitSpan { it.kind == SERVER && it.attr("http.route") == "/items" }
    span.attr(StackAttributes.SCENARIO) shouldBe "create-item"
    span.attr(StackAttributes.RUN) shouldBe "run-1"
}
@Test fun requestAndResponseBodiesAreCapturedAsJson() {
    // request body JSON contains "name":"a"; response body JSON contains "id"; truncated flags absent/false
}
@Test fun allRequestAndResponseHeadersAreCaptured() {
    // custom request header X-Probe: 1 appears in decoded HTTP_REQUEST_HEADERS map as ["1"]; response Content-Type present
}
@Test fun largeBodyIsTruncated() {
    get("/big")  // body length of HTTP_RESPONSE_BODY == MAX_BODY_CHARS, HTTP_RESPONSE_BODY_TRUNCATED == true, HTTP 200 still returned
}
@Test fun plainTextBodyIsCaptured() {
    get("/text")  // HTTP_RESPONSE_BODY == "hello" (string serialised as-is, not quoted)
}
@Test fun notFoundStillRecordsStatusAndBody() {
    get("/items/does-not-exist")  // span http.response.status_code == 404, HTTP_RESPONSE_BODY not null
}
```

- [ ] **Step 2: Run** `./gradlew -p tool :agent-extension:agentTest` — Expected: FAIL (attributes missing).
- [ ] **Step 3: Implement.** `BaggageToAttributesSpanProcessor.onStart` copies `stack.scenario`/`stack.run` from `Baggage.fromContext(parentContext)`. `ServletHeadersInstrumentationModule` advises `jakarta.servlet.http.HttpServlet#service(HttpServletRequest, HttpServletResponse)`: enter → request headers, exit → response headers, both set on `Span.current()` (the server span). `SpringMvcBodyInstrumentationModule` advises `AbstractMessageConverterMethodArgumentResolver#readWithMessageConverters` (exit: returned body) and `AbstractMessageConverterMethodProcessor#writeWithMessageConverters` (enter: value). `BodyCapture.toJson(value, converters)` first finds a Jackson converter in the `messageConverters` field and reflectively calls `getMapper()` (Jackson 3) or `getObjectMapper()` (Jackson 2) → `writeValueAsString`; `CharSequence` values are captured as-is; otherwise falls back to `BoundedValueSerializer.toJson(value, maxDepth = 8, maxCollection = 100, maxString = 4096)`. Truncate to `MAX_BODY_CHARS` and set the flag. Capture must never throw into the app (catch `Throwable`, skip).
- [ ] **Step 4: Run** `./gradlew -p tool :agent-extension:agentTest` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(agent): capture HTTP server headers, bodies and scenario baggage`

---

### Task 3: Agent extension — JDBC capture

**Files:**
- Create: `tool/agent-extension/src/main/java/io/stackvibed/agent/jdbc/{JdbcParametersInstrumentationModule.java,ParameterRecorder.java}`
- Modify: `tool/agent-fixture-app` — `POST /items` inserts via `JdbcClient` (`insert into items(id, name, quantity) values (?, ?, ?)`), `GET /items/{id}` selects by id
- Test: `tool/agent-extension/src/agentTest/kotlin/io/stackvibed/agent/JdbcCaptureAgentTest.kt`

**Interfaces:**
- Consumes: `StackAttributes.DB_PARAMETERS`, `DB_ROW_COUNT`; `ForkedAgentApp`, `FakeOtlpReceiver` (Task 2).
- Produces: JDBC CLIENT spans carrying `stack.db.parameters` (JSON array of strings, `null` for SQL NULL, values rendered with `String.valueOf`, bytes as `"<n bytes>"`) and, for `executeUpdate`/`executeLargeUpdate`, `stack.db.row_count`.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun preparedStatementParametersAreCaptured() {
    post("/items", """{"name":"widget","quantity":3}""")
    val span = receiver.awaitSpan { it.kind == CLIENT && it.dbStatement().startsWith("insert into items") }
    decodeJsonArray(span.attr(StackAttributes.DB_PARAMETERS)) shouldContainExactly listOf(anyUuid, "widget", "3")
    span.attr(StackAttributes.DB_ROW_COUNT) shouldBe "1"
}
@Test fun selectHasParametersButNoRowCount() {
    // GET /items/{id}: DB_PARAMETERS == [id], DB_ROW_COUNT absent
}
```
`dbStatement()` reads `db.query.text` and falls back to `db.statement`.

- [ ] **Step 2: Run** `./gradlew -p tool :agent-extension:agentTest --tests '*Jdbc*'` — Expected: FAIL.
- [ ] **Step 3: Implement.** `ParameterRecorder` stores parameters per statement in `VirtualField<PreparedStatement, Map<Integer,String>>` from advice on `PreparedStatement#set*(int, ..)` and `clearParameters`. Advice on `execute*` sets the attributes on the active JDBC CLIENT span; set `InstrumentationModule.order()` so our advice runs **inside** OTel's JDBC advice (the span must be current at our enter/exit — the tests prove it). Row count from the `int`/`long` return of update methods.
- [ ] **Step 4: Run** `./gradlew -p tool :agent-extension:agentTest` — Expected: PASS (all agent tests).
- [ ] **Step 5: Commit** `feat(agent): capture JDBC parameters and update row counts`

---

### Task 4: Test harness

**Files:**
- Create: `tool/test-harness/build.gradle.kts` (published `io.stackvibed:stack-test-harness`; `compileOnly` RestAssured, `api` JUnit Jupiter API)
- Create: `tool/test-harness/src/main/kotlin/io/stackvibed/harness/{StackScenario.kt,StackScenarioExtension.kt,StackContext.kt,StackBaggageFilter.kt}`
- Test: `tool/test-harness/src/test/kotlin/io/stackvibed/harness/{StackScenarioExtensionTest.kt,StackBaggageFilterTest.kt}`

**Interfaces:**
- Produces:
  - `@Target(FUNCTION) annotation class StackScenario(val id: String)` (meta-annotated `@ExtendWith(StackScenarioExtension::class)`)
  - `object StackContext { fun current(): ScenarioRun?; fun headers(): Map<String, String> }`, `data class ScenarioRun(val scenarioId: String, val runId: String)`
  - `class StackBaggageFilter : io.restassured.filter.Filter` — adds `headers()` to every request.
  - `headers()` returns `baggage: stack.scenario=<id>,stack.run=<runId>` and a fresh `traceparent: 00-<32 hex>-<16 hex>-01` per call; empty map when no scenario is active.
- Rules: `runId` = random UUID per test invocation; scenario id must match `^[a-z0-9][a-z0-9-]*$` or the extension fails the test with a clear message.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test @StackScenario("create-order") fun `context is set inside an annotated test`() {
    StackContext.current()!!.scenarioId shouldBe "create-order"
    StackContext.headers()["baggage"] shouldMatch Regex("stack\\.scenario=create-order,stack\\.run=[0-9a-f-]{36}")
    StackContext.headers()["traceparent"] shouldMatch Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-01")
}
@Test fun `no context outside an annotated test`() { StackContext.headers() shouldBe emptyMap() }
@Test fun `each invocation gets a new run id`()  // @RepeatedTest(2) collecting runIds into a companion set; size == 2
@Test fun `invalid scenario id fails fast`()     // EngineTestKit runs a fixture class with @StackScenario("Bad Id") → failure message contains "must match"
@Test fun `filter adds headers to RestAssured requests`()  // RestAssured against a JDK HttpServer echoing request headers
```

- [ ] **Step 2: Run** `./gradlew -p tool :test-harness:test` — Expected: FAIL.
- [ ] **Step 3: Implement** with a `ThreadLocal<ScenarioRun>` set in `beforeEach`, cleared in `afterEach`.
- [ ] **Step 4: Run** `./gradlew -p tool :test-harness:test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(harness): JUnit scenario tagging and RestAssured baggage filter`

---

### Task 5: Reporter — span → event mapping

**Files:**
- Create: `tool/reporter/build.gradle.kts` (Spring Boot 4 web, `io.opentelemetry.proto:opentelemetry-proto`, `project(":model")`, `project(":agent-contract")`)
- Create: `tool/reporter/src/main/kotlin/io/stackvibed/reporter/otlp/SpanEventMapper.kt`
- Test: `tool/reporter/src/test/kotlin/io/stackvibed/reporter/otlp/{OtlpFixtures.kt,SpanEventMapperTest.kt}`

**Interfaces:**
- Consumes: model events (Task 1), `StackAttributes` (Task 2).
- Produces:
  - `data class SpanTags(val scenarioId: String?, val runId: String?)`
  - `data class MappedSpan(val tags: SpanTags, val toEvent: EventDraft)` where `fun interface EventDraft { fun withRun(runId: String): RecordingEvent }`
  - `class SpanEventMapper { fun map(request: ExportTraceServiceRequest): List<MappedSpan> }`
  - `object OtlpFixtures { fun createOrderRun(scenario: String = "create-order", run: String = "run-1"): ExportTraceServiceRequest }` — one SERVER span `POST /orders` 201 + one child-of-internal CLIENT span `insert into orders ...` (postgresql/orders), fixed ids and timestamps. Reused by Task 6 golden tests and Task 8.
- Mapping rules: `component = ComponentIds.service(resource service.name)`; `eventId = "<traceId>-<spanId>"` (hex); SERVER span with `http.request.method` → `HttpServerEvent` (`route = http.route`, `path = url.path`, `status = http.response.status_code`, headers decoded from JSON attributes, missing → empty map); CLIENT span with `db.system.name` or `db.system` → `DbClientEvent` (`dbName = db.namespace ?: db.name`, `statement = db.query.text ?: db.statement`, `parameters` decoded, missing → empty list, `rowCount` from `stack.db.row_count`); every other span is dropped.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun `server span becomes http event`() {
    val event = mapper.map(OtlpFixtures.createOrderRun()).single { it.toEvent.withRun("r") is HttpServerEvent }.toEvent.withRun("r") as HttpServerEvent
    event.component shouldBe "orders-service"; event.method shouldBe "POST"; event.route shouldBe "/orders"
    event.status shouldBe 201; event.requestBody shouldContain "\"customerId\""
}
@Test fun `db client span becomes db event with parameters`()   // dbSystem "postgresql", dbName "orders", parameters size 4, rowCount 1
@Test fun `tags come from stack attributes`()                    // tags == SpanTags("create-order", "run-1")
@Test fun `old db semconv attribute names are accepted`()        // fixture variant using db.system/db.name/db.statement
@Test fun `internal and unknown spans are dropped`()             // INTERNAL span in fixture → not in output
```

- [ ] **Step 2: Run** `./gradlew -p tool :reporter:test` — Expected: FAIL.
- [ ] **Step 3: Implement** `SpanEventMapper` per the rules (Jackson 3 for decoding JSON attributes).
- [ ] **Step 4: Run** `./gradlew -p tool :reporter:test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(reporter): map OTLP spans to recording events`

---

### Task 6: Reporter — run resolution, recording store, API, image

**Files:**
- Create: `tool/reporter/src/main/kotlin/io/stackvibed/reporter/{ReporterApplication.kt,ReporterProperties.kt}`, `.../runs/{RunResolver.kt,RecordingWindow.kt}`, `.../store/{RecordingStore.kt,FolderNames.kt}`, `.../api/{OtlpTraceController.kt,RecordingControlController.kt}`, `tool/reporter/Dockerfile`
- Test: `tool/reporter/src/test/kotlin/io/stackvibed/reporter/{runs/RunResolverTest.kt,store/RecordingStoreTest.kt,ReporterGoldenTest.kt}`, `tool/reporter/src/test/resources/golden/create-order/{manifest.json,events.jsonl}`

**Interfaces:**
- Consumes: `SpanEventMapper`, `OtlpFixtures` (Task 5).
- Produces:
  - `ReporterProperties` (`stack.reporter.recordings-dir`, default `./recordings`; `server.port` default `4318`)
  - `data class RunAssignment(val runId: String, val scenarioId: String?, val kind: RecordingKind)`; `const val UNASSIGNED_RUN = "_unassigned"`
  - `class RunResolver(window: RecordingWindow) { fun resolve(tags: SpanTags): RunAssignment }` — tag run → `E2E`; else active window → `MANUAL`; else `UNASSIGNED_RUN`.
  - `class RecordingWindow { fun start(scenarioId: String): RunAssignment; fun stop(): RunAssignment?; fun active(): RunAssignment? }` — `start` while active throws `WindowAlreadyActive`.
  - `class RecordingStore(root: Path, clock: Clock, reporterVersion: String) { fun append(assignment: RunAssignment, events: List<RecordingEvent>); fun markStopped(runId: String): RecordingManifest; fun folderOf(runId: String): Path? }` — folder name `FolderNames.of(startedAt, scenarioId, runId)` = `yyyy-MM-dd'T'HH-mm-ss` (UTC) + `_` + scenario slug (or `manual`) + `_` + first 8 chars of the slugged runId; `_unassigned` events go to `root/_unassigned/events.jsonl` (no manifest). Manifest rewritten atomically (temp file + `ATOMIC_MOVE`) on every append (`lastEventAt`, `componentsSeen` sorted). On construction, scans `root/*/manifest.json` to rebuild the runId → folder index.
  - HTTP: `POST /v1/traces` (`application/x-protobuf`) → 200 with empty `ExportTraceServiceResponse`; `POST /api/recordings/start` `{"scenarioId":"x"}` → 201 `{"runId","folder"}` / 409 when active / 400 when id isn't a slug; `POST /api/recordings/stop` → 200 manifest / 404; `GET /api/recordings/active` → 200 / 204.
  - `Dockerfile`: `eclipse-temurin:25-jre`, copies the boot jar, `EXPOSE 4318`, `stack.reporter.recordings-dir=/recordings`. Gradle task `:reporter:dockerImage` runs `docker build -t stackvibed/reporter:0.1.0-SNAPSHOT` (not part of `build`).

- [ ] **Step 1: Write failing tests**

```kotlin
// RunResolverTest
@Test fun `tag wins over active window`()       // window active("manual-x"); resolve(SpanTags("create-order","run-1")) == RunAssignment("run-1","create-order",E2E)
@Test fun `window catches untagged spans`()     // == RunAssignment(window.runId, "manual-x", MANUAL)
@Test fun `untagged without window is unassigned`()

// RecordingStoreTest (temp dir, fixed clock 2026-10-27T22:41:05Z)
@Test fun `first append creates folder and manifest`()   // folder name "2026-10-27T22-41-05_create-order_run-1"; manifest.kind == E2E
@Test fun `events are appended one json per line`()
@Test fun `concurrentAppendsProduceWholeLines`()        // 8 threads × 200 appends to one run → 1600 lines, each parses as RecordingEvent
@Test fun `restartReusesExistingRunFolder`()            // new RecordingStore on same root; append same runId → same folder, line count grows
@Test fun `unassigned goes to its own folder without manifest`()
@Test fun `folder names contain no windows illegal characters`()  // runId "a:b/c" → name matches ^[A-Za-z0-9_-]+$

// ReporterGoldenTest (@SpringBootTest, fixed Clock bean, temp recordings dir)
@Test fun `create-order fixture produces golden recording`() {
    postProtobuf("/v1/traces", OtlpFixtures.createOrderRun())
    recordingFolder().resolve("events.jsonl").readText() shouldBe golden("create-order/events.jsonl")
    recordingFolder().resolve("manifest.json").readText() shouldBe golden("create-order/manifest.json")
}
@Test fun `start and stop window over http`()           // 201 → untagged fixture → manifest kind MANUAL, stop → stoppedAt set; second start → 409
```

- [ ] **Step 2: Run** `./gradlew -p tool :reporter:test` — Expected: FAIL.
- [ ] **Step 3: Implement.** One `ReentrantLock` per runId; write each event as a single `Files.write(..., APPEND)` of `line + "\n"`. Golden files are produced once from a passing implementation, reviewed by eye and committed.
- [ ] **Step 4: Run** `./gradlew -p tool :reporter:test` and `./gradlew -p tool :reporter:dockerImage` — Expected: PASS; image `stackvibed/reporter:0.1.0-SNAPSHOT` exists (`docker image ls`).
- [ ] **Step 5: Commit** `feat(reporter): scenario runs, recording store, control API and image`

---

### Task 7: Viewer backend — app shell + workspace module

**Files:**
- Create: `tool/viewer-backend/build.gradle.kts` (Spring Boot 4 web, Spring Modulith, Jackson 3 YAML, `project(":model")`; boot jar named `stack-viewer.jar`)
- Create: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/{ViewerApplication.kt,ViewerPaths.kt}`, `.../workspace/{WorkspaceStore.kt,LoadedWorkspace.kt}`, `.../workspace/internal/FileWorkspaceStore.kt`
- Test: `tool/viewer-backend/src/test/kotlin/io/stackvibed/viewer/{ModularityTest.kt,ArchitectureTest.kt}`, `.../workspace/FileWorkspaceStoreTest.kt`

**Interfaces:**
- Produces:
  - `ViewerPaths(workspace: Path, recordings: Path)` bound from top-level properties `workspace` (default `./stack`) and `recordings` (default `./recordings`), so the CLI is `--workspace=<path> --recordings=<path>`.
  - `data class LoadedWorkspace(config: WorkspaceConfig, components: List<ComponentDefinition>, connections: List<ConnectionDefinition>, scenarios: List<ScenarioDefinition>, problems: List<WorkspaceProblem>, notedTargets: Set<String>)`
  - `interface WorkspaceStore { fun load(): LoadedWorkspace; fun saveComponent(def: ComponentDefinition); fun saveConnection(def: ConnectionDefinition); fun readNote(targetId: String): String?; fun writeNote(targetId: String, markdown: String); fun readLayout(board: String): BoardLayout?; fun writeLayout(board: String, layout: BoardLayout) }`
  - Files (spec §8): `workspace.yaml`, `components/<id>.yaml`, `connections/<from>--<to>.yaml`, `scenarios/<id>.yaml`, `notes/<id>.md`, `boards/<board>.layout.json` (sorted keys, pretty). Missing `workspace.yaml` → `WorkspaceConfig(ProjectInfo(<workspace dir name>))`. Missing folders are fine; folders are created on first write only. All writes atomic. Ids that aren't slugs → `IllegalArgumentException`.
  - `ConnectionState.DISCOVERED` is never written to disk (reject on save).
  - Modulith modules: `workspace`, `recordings`, `topology`, `web` (direct sub-packages of `io.stackvibed.viewer`); `internal` sub-packages are private.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun `empty workspace loads with defaults`()            // temp dir "orders-sandbox" → config.project.name == "orders-sandbox", all lists empty, no files created
@Test fun `saved component is loaded back`()
@Test fun `connection file name is from--to`()               // saveConnection(from="e2e-client", to="orders-service") → connections/e2e-client--orders-service.yaml
@Test fun `discovered state is never persisted`()            // shouldThrow<IllegalArgumentException>
@Test fun `malformedFileIsReportedNotFatal`()                // components/broken.yaml = ":::" → problems[0].file == "components/broken.yaml", other components still loaded
@Test fun `notes round-trip and are listed`()                // writeNote("orders-service","# Hi") → readNote == "# Hi", notedTargets contains id
@Test fun `layout json has sorted keys`()                    // nodes {b,a} written → file text has "a" before "b"
@Test fun `non-slug id is rejected`()                        // saveComponent(id="Orders Service") throws

// ModularityTest
@Test fun `modules respect boundaries`() { ApplicationModules.of(ViewerApplication::class.java).verify() }
// ArchitectureTest (ArchUnit)
@Test fun `no module depends on web`()
```

- [ ] **Step 2: Run** `./gradlew -p tool :viewer-backend:test` — Expected: FAIL.
- [ ] **Step 3: Implement** `FileWorkspaceStore` (Jackson YAML for `.yaml`, plain text for `.md`, Jackson JSON with `ORDER_MAP_ENTRIES_BY_KEYS` for layouts).
- [ ] **Step 4: Run** `./gradlew -p tool :viewer-backend:test` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(viewer): app shell, modulith structure and file workspace store`

---

### Task 8: Viewer backend — recordings, topology, REST API

**Files:**
- Create: `.../viewer/recordings/{RecordingRepository.kt,internal/FileRecordingRepository.kt}`, `.../viewer/topology/{FlowBuilder.kt,TopologyService.kt,Evidence.kt}`, `.../viewer/web/{WorkspaceController.kt,NotesController.kt,LayoutController.kt,RecordingsController.kt}`
- Test: `.../recordings/FileRecordingRepositoryTest.kt`, `.../topology/{FlowBuilderTest.kt,TopologyServiceTest.kt}`, `.../web/ApiTest.kt`; fixtures `src/test/resources/fixtures/recordings/**` (copy of Task 6 golden create-order folder + a second, newer create-order run without the DB call + a get-order run)

**Interfaces:**
- Consumes: `WorkspaceStore`, `LoadedWorkspace` (Task 7); model types (Task 1).
- Produces:
  - `interface RecordingRepository { fun manifests(): List<RecordingManifest>; fun referenceManifests(): List<RecordingManifest>; fun events(runId: String): List<RecordingEvent> }` — reads `recordings/*/` and `<workspace>/reference-recordings/*/`; skips `_unassigned`; ignores a trailing partial line in `events.jsonl`; unknown `runId` → `NoSuchElementException`.
  - `object Evidence { fun select(manifests: List<RecordingManifest>, references: List<RecordingManifest>): List<RecordingManifest> }` — latest (`startedAt`) per `scenarioId` (manifests with null scenario excluded) + all references, de-duplicated by `runId`.
  - `class FlowBuilder { fun build(manifest: RecordingManifest, events: List<RecordingEvent>, aliases: Map<String, String>): Flow }` — aliases map raw component id → curated id. `HttpServerEvent` → step `from = E2E_CLIENT`, `to = component`, kind `HTTP`, label `"<METHOD> <route ?: path>"`, `status`. `DbClientEvent` → `from = component`, `to = ComponentIds.database(...)`, kind `DB`, label = statement collapsed to single spaces and cut to 60 chars, `parentStepId` = the innermost `HttpServerEvent` step of the same component and trace whose interval contains it. Steps sorted by `startEpochNanos`; `stepId = eventId`; `connectionId = "<from>--<to>"`.
  - `class TopologyService { fun workspaceView(): WorkspaceView; fun flow(runId: String): Flow }` — components: curated ∪ evidence-derived (`discovered = true` when no file; `E2E_CLIENT` type `CLIENT`, databases `DATABASE`, others `SERVICE`; `displayName` defaults to id); connections per D24: evidence ∖ files → `DISCOVERED`; file `ACCEPTED` with no evidence → `unverified = true`; `MANUAL` never unverified; `HIDDEN` returned as-is. Scenarios: curated ∪ scenario ids in manifests, with `latestRunId`/`runCount`. `problems` passed through.
  - REST (JSON, Jackson 3):
    - `GET /api/workspace` → `WorkspaceView`
    - `PUT /api/components/{id}` body `ComponentDefinition` → 204 (accept/rename)
    - `PUT /api/connections/{id}` body `ConnectionDefinition` → 204; 400 if `state = DISCOVERED` or id ≠ `from--to`
    - `GET|PUT /api/notes/{targetId}` (`text/markdown`) → 200 / 404 / 204
    - `GET|PUT /api/boards/{board}/layout` → `BoardLayout` / 404 / 204
    - `GET /api/scenarios/{id}/runs` → `List<RecordingManifest>` newest first
    - `GET /api/runs/{runId}/flow` → `Flow` / 404

- [ ] **Step 1: Write failing tests**

```kotlin
// FlowBuilderTest (golden create-order events)
@Test fun `create order flow has http then nested db step`() {
    val flow = builder.build(manifest, events, emptyMap())
    flow.steps.map { it.connectionId } shouldBe listOf("e2e-client--orders-service", "orders-service--postgresql-orders")
    flow.steps[0].label shouldBe "POST /orders"; flow.steps[0].status shouldBe 201
    flow.steps[1].parentStepId shouldBe flow.steps[0].stepId
}
@Test fun `aliases rename components`()              // aliases {"postgresql-orders" to "orders-db"} → steps[1].to == "orders-db"

// TopologyServiceTest
@Test fun `evidence is latest run per scenario`()     // newer create-order run has no DB call → accepted "orders-service--postgresql-orders" is unverified
@Test fun `unaccepted evidence is discovered`()       // empty workspace → both components discovered, connections DISCOVERED
@Test fun `manual connection is never unverified`()
@Test fun `scenarios include ids seen only in recordings`()

// FileRecordingRepositoryTest
@Test fun `partial trailing line is ignored`()
@Test fun `unassigned folder is skipped`()

// ApiTest (@SpringBootTest + MockMvc, temp workspace, fixture recordings)
@Test fun `workspace view endpoint`()                 // 200, JSON has components[*].id containing "orders-service"
@Test fun `accepting a connection persists it`()      // PUT then GET → state ACCEPTED, unverified false
@Test fun `discovered state is rejected`()            // 400
@Test fun `flow endpoint returns 404 for unknown run`()
```

- [ ] **Step 2: Run** `./gradlew -p tool :viewer-backend:test` — Expected: FAIL.
- [ ] **Step 3: Implement** the repository, `Evidence`, `FlowBuilder`, `TopologyService`, controllers.
- [ ] **Step 4: Run** `./gradlew -p tool :viewer-backend:test` — Expected: PASS (including `ModularityTest`).
- [ ] **Step 5: Commit** `feat(viewer): recordings, evidence-based topology and REST API`

---

### Task 9: Viewer UI — scaffold + primary board

**Files:**
- Create: `tool/viewer-ui/{package.json,vite.config.ts,tsconfig.json,index.html,build.gradle.kts}`, `src/{main.tsx,App.tsx}`, `src/api/{client.ts,queries.ts}`, `src/generated/model.ts` (generated, committed), `src/board/{PrimaryBoard.tsx,ComponentNode.tsx,ConnectionEdge.tsx,autoLayout.ts,toFlowElements.ts}`, `src/panel/{SidePanel.tsx,NotesPanel.tsx}`, `src/store/uiStore.ts`
- Modify: `tool/viewer-backend/build.gradle.kts` — `processResources` copies `viewer-ui/dist` into `static/`
- Test: `src/board/{toFlowElements.test.ts,autoLayout.test.ts}`, `src/panel/NotesPanel.test.tsx`

**Interfaces:**
- Consumes: REST API (Task 8); `model.schema.json` snapshot (Task 1).
- Produces:
  - npm scripts: `gen:types` (`json-schema-to-typescript` on `../model/src/test/resources/schema-snapshot/model.schema.json` → `src/generated/model.ts`), `dev` (Vite, proxies `/api` → `http://localhost:8080`), `build`, `test` (Vitest), `e2e` (Playwright, Task 11).
  - Gradle (`com.github.node-gradle.node`, Node 22 download): `:viewer-ui:npmBuild` (part of `build`), `:viewer-ui:npmTest` (part of `check`).
  - `api/queries.ts`: `useWorkspace()`, `useSaveLayout(board)`, `useLayout(board)`, `useAcceptComponent()`, `useSetConnectionState()`, `useNote(targetId)`, `useSaveNote(targetId)`, `useScenarioRuns(id)`, `useFlow(runId)` (TanStack Query).
  - `toFlowElements(view: WorkspaceView, layout: BoardLayout | null): { nodes: Node[]; edges: Edge[]; unplaced: string[] }` — hidden connections excluded; node `data` = `BoardComponent`; edge `data` = `BoardConnection`; ids as in API.
  - `autoLayout(nodes, edges, existing: BoardLayout | null): Promise<Record<string, NodePosition>>` — elkjs `layered`, left-to-right; positions only for `unplaced` ids, existing positions untouched.
  - Rendering rules: discovered component → "discovered" badge + Accept button in side panel; `DISCOVERED` edge → badge with Accept / Hide; `unverified` edge → dashed stroke; node with `hasNotes` → note icon. Drag stop → debounced (500 ms) `PUT /api/boards/primary/layout` with all positions + viewport.
  - `uiStore` (Zustand): `selectedComponentId`, `selectedRunId`, `hoveredConnectionId`.

- [ ] **Step 1: Write failing tests**

```ts
test('hidden connections are not drawn', () => {
  const { edges } = toFlowElements(viewWith({ connections: [conn('a--b', 'HIDDEN')] }), null);
  expect(edges).toHaveLength(0);
});
test('components without layout are reported as unplaced', () => { /* layout has a only → unplaced == ['b'] */ });
test('unverified edge is dashed', () => { /* edge.style.strokeDasharray defined */ });
test('autoLayout keeps existing positions', async () => { /* a fixed at (10,20) stays; b gets a position */ });
test('notes panel renders markdown and saves edits', async () => { /* "# Title" renders <h1>; edit + Save calls PUT with new text */ });
```

- [ ] **Step 2: Run** `./gradlew -p tool :viewer-ui:npmTest` — Expected: FAIL.
- [ ] **Step 3: Implement** scaffold, board, side panel (component details, accept/hide actions, notes via `react-markdown` view + textarea edit), routing `/` → board (react-router).
- [ ] **Step 4: Run** `./gradlew -p tool :viewer-ui:npmTest :viewer-backend:bootJar` — Expected: PASS; `stack-viewer.jar` contains `static/index.html`.
- [ ] **Step 5: Commit** `feat(viewer-ui): primary board with curated/discovered rendering and notes`

---

### Task 10: Viewer UI — scenario picker + path highlight

**Files:**
- Create: `src/scenarios/ScenarioPicker.tsx`, `src/board/highlight.ts`, `src/board/HopDetails.tsx`
- Modify: `src/board/PrimaryBoard.tsx`, `ConnectionEdge.tsx`, `ComponentNode.tsx`
- Test: `src/board/highlight.test.ts`, `src/scenarios/ScenarioPicker.test.tsx`

**Interfaces:**
- Consumes: `useWorkspace`, `useScenarioRuns`, `useFlow`, `uiStore` (Task 9); `Flow`, `FlowStep` (Task 1).
- Produces:
  - `highlightFor(flow: Flow): { nodeIds: Set<string>; edgeIds: Set<string>; stepsByEdge: Map<string, FlowStep[]> }`
  - Picker lists `WorkspaceView.scenarios` (description as subtitle); selecting a scenario selects its `latestRunId`; a run dropdown lists `useScenarioRuns(id)` newest first. Scenarios with no runs are shown disabled.
  - Highlighted path: highlighted edges thick + animated with a step-order badge (`1`, `2`, …); non-path nodes/edges dimmed. Hovering a highlighted edge shows `HopDetails` (per step: label, status, duration ms, first 200 chars of request/response body or SQL parameters). A "Open sequence" button navigates to `/sequence/:runId`.

- [ ] **Step 1: Write failing tests**

```ts
test('highlight covers every hop of the flow', () => {
  const h = highlightFor(createOrderFlow);
  expect([...h.edgeIds]).toEqual(['e2e-client--orders-service', 'orders-service--postgresql-orders']);
  expect(h.nodeIds).toEqual(new Set(['e2e-client', 'orders-service', 'postgresql-orders']));
  expect(h.stepsByEdge.get('orders-service--postgresql-orders')).toHaveLength(1);
});
test('selecting a scenario selects its latest run', async () => { /* click "create-order" → uiStore.selectedRunId == latestRunId */ });
test('scenario without runs is disabled', () => { /* option has aria-disabled */ });
```
`createOrderFlow` is a TS fixture mirroring the Task 6 golden recording.

- [ ] **Step 2: Run** `./gradlew -p tool :viewer-ui:npmTest` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew -p tool :viewer-ui:npmTest` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(viewer-ui): scenario picker and highlighted path with hop details`

---

### Task 11: Viewer UI — sequence view + Playwright e2e

**Files:**
- Create: `src/sequence/{SequenceView.tsx,SequenceDiagram.tsx,layoutSequence.ts,MessageDetails.tsx}`, `playwright.config.ts`, `e2e/{board.spec.ts,sequence.spec.ts}`, `e2e/fixtures/stack/**` (workspace with `orders-service` accepted + a note), `e2e/fixtures/recordings/**` (copy of golden create-order run)
- Modify: `src/App.tsx` (route `/sequence/:runId`), `tool/viewer-ui/build.gradle.kts` (`:viewer-ui:e2eTest` dependsOn `:viewer-backend:bootJar`, part of `check`)
- Test: `src/sequence/layoutSequence.test.ts`

**Interfaces:**
- Consumes: `useFlow` (Task 9), `Flow` (Task 1).
- Produces:
  - `layoutSequence(flow: Flow): { participants: { id: string; x: number }[]; messages: { stepId: string; kind: 'request' | 'response'; fromX: number; toX: number; y: number; label: string }[]; height: number }` — participants ordered by first appearance; each step emits a request row and, after all its child steps, a response row (label = HTTP status, or `rowCount` rows / `ok` for DB); constant column gap 220, row gap 48.
  - `SequenceDiagram` renders SVG (lifelines, solid request arrows, dashed response arrows); clicking a message opens `MessageDetails` (method/route/status, headers table, pretty-printed request/response body, SQL statement + parameters, timing).
  - Playwright `webServer`: `java -jar ../viewer-backend/build/libs/stack-viewer.jar --workspace=e2e/fixtures/stack --recordings=e2e/fixtures/recordings --server.port=18080` (workspace copied to a temp dir first so writes don't dirty fixtures).

- [ ] **Step 1: Write failing tests**

```ts
test('sequence rows are request, nested request, nested response, response', () => {
  const l = layoutSequence(createOrderFlow);
  expect(l.participants.map(p => p.id)).toEqual(['e2e-client', 'orders-service', 'postgresql-orders']);
  expect(l.messages.map(m => `${m.kind}:${m.label}`)).toEqual([
    'request:POST /orders', `request:${createOrderFlow.steps[1].label}`, 'response:1 rows', 'response:201']);
});
```
```ts
// e2e/board.spec.ts
test('board shows components and highlights create-order', async ({ page }) => { /* 3 nodes visible; pick create-order; 2 highlighted edges */ });
test('dragging a node persists its position', async ({ page }) => { /* drag, reload, same position ±2px */ });
test('editing a note persists', async ({ page }) => { /* edit orders-service note, reload, text shown */ });
// e2e/sequence.spec.ts
test('clicking a message shows its payload', async ({ page }) => { /* open sequence; click "POST /orders"; details contain "customerId" */ });
```

- [ ] **Step 2: Run** `./gradlew -p tool :viewer-ui:npmTest :viewer-ui:e2eTest` — Expected: FAIL.
- [ ] **Step 3: Implement.** Add `npx playwright install --with-deps chromium` to `tool.yml` before the build.
- [ ] **Step 4: Run** `./gradlew -p tool check` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(viewer-ui): sequence view with message details and Playwright e2e`

---

### Task 12: Viewer distribution — launchers

**Files:**
- Create: `tool/viewer-backend/src/dist/stack-viewer` (POSIX sh, executable), `tool/viewer-backend/src/dist/stack-viewer.cmd`
- Modify: `tool/viewer-backend/build.gradle.kts` — `viewerDist` (Sync bootJar + `src/dist` → `build/stack-viewer/`), `ViewerApplication` logs `Stack viewer started on http://localhost:<port>` and exits when `stack.viewer.exit-after-startup=true`
- Test: `tool/viewer-backend/src/test/kotlin/io/stackvibed/viewer/LauncherScriptTest.kt`

**Interfaces:**
- Produces: `build/stack-viewer/{stack-viewer.jar,stack-viewer,stack-viewer.cmd}`; usage `stack-viewer --workspace=<path> [--recordings=<path>] [--server.port=<n>]`. Scripts resolve the jar relative to their own location, pass all args through, require `java` on PATH (or `JAVA_HOME`), and print a clear error when Java < 25.

- [ ] **Step 1: Write failing test**

```kotlin
@Test fun `launcher starts the viewer on this OS`() {
    val script = if (isWindows) "stack-viewer.cmd" else "stack-viewer"
    val workspace = createTempDirectory("ws with space")  // path containing a space
    val result = run(distDir.resolve(script), "--workspace=$workspace", "--server.port=0", "--stack.viewer.exit-after-startup=true")
    result.exitCode shouldBe 0
    result.output shouldContain "Stack viewer started on http://localhost:"
}
```
`test` task dependsOn `viewerDist`; runs on all three CI OSes.

- [ ] **Step 2: Run** `./gradlew -p tool :viewer-backend:test --tests '*Launcher*'` — Expected: FAIL.
- [ ] **Step 3: Implement** the scripts and `viewerDist`.
- [ ] **Step 4: Run** `./gradlew -p tool build` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(viewer): launcher scripts for macOS and Windows`

---

### Task 13: Sandbox — orders-service, compose, e2e scenarios, acceptance

**Files:**
- Create: `sandbox/{settings.gradle.kts,build.gradle.kts,gradle/libs.versions.toml,gradlew(.bat),docker-compose.yml,README.md,.gitignore}`
- Create: `sandbox/services/orders-service/{build.gradle.kts,Dockerfile}`, `src/main/kotlin/io/stackvibed/sandbox/orders/{OrdersApplication.kt,OrdersController.kt,OrderRepository.kt,Order.kt}`, `src/main/resources/{application.yaml,db/migration/V1__orders.sql}`
- Create: `sandbox/e2e-tests/build.gradle.kts`, `src/main/kotlin/io/stackvibed/sandbox/e2e/OrdersScenarios.kt`, `src/test/kotlin/.../OrdersE2ETest.kt`, `src/acceptanceTest/kotlin/.../StepOneAcceptanceTest.kt`
- Create: `sandbox/stack/{workspace.yaml,scenarios/create-order.yaml,scenarios/get-order.yaml}`
- Create: `.github/workflows/sandbox.yml`

**Interfaces:**
- Consumes: `io.stackvibed:stack-agent-extension`, `io.stackvibed:stack-test-harness` from mavenLocal; image `stackvibed/reporter:0.1.0-SNAPSHOT`; model JSON format (acceptance test parses `events.jsonl` with Jackson, no dependency on tool internals beyond the published `stack-model` artifact).
- Produces:
  - `orders-service`: `POST /orders` `{"customerId":"c-1","sku":"sku-1","quantity":2}` → 201 `{"id":<uuid>,"status":"CREATED", ...}`; `GET /orders/{id}` → 200 order / 404. Table `orders(id uuid pk, customer_id text, sku text, quantity int, status text, created_at timestamptz)` via Flyway; data access with `JdbcClient` (prepared statements).
  - Root task `stackAgent`: copies `opentelemetry-javaagent.jar` and `stack-agent-extension.jar` (fixed names) into `sandbox/build/stack-agent/`.
  - `docker-compose.yml`: `postgres` (`postgres:17`, db `orders`, healthcheck), `reporter` (image above, `4318:4318`, volume `./recordings:/recordings`), `orders-service` (built from its Dockerfile, `8080:8080`, `JAVA_TOOL_OPTIONS=-javaagent:/stack-agent/opentelemetry-javaagent.jar -Dotel.javaagent.extensions=/stack-agent/stack-agent-extension.jar`, `OTEL_SERVICE_NAME=orders-service`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://reporter:4318`, volume `./build/stack-agent:/stack-agent:ro`, depends on healthy postgres).
  - `OrdersScenarios(baseUrl: String)`: `fun createOrder(): String` (returns id) and `fun getOrder(id: String)`, both with `StackBaggageFilter`.
  - `OrdersE2ETest`: `@StackScenario("create-order")` and `@StackScenario("get-order")` tests against `http://localhost:8080` (task `e2eTest`, not part of `build`).
  - `workspace.yaml`: `project: { name: orders-sandbox }`.
  - README: run steps (publish tool, build image, `stackAgent`, `docker compose up -d --build`, `./gradlew e2eTest`, start viewer with `--workspace=sandbox/stack --recordings=sandbox/recordings`) and the IntelliJ run configuration (same `JAVA_TOOL_OPTIONS`, endpoint `http://localhost:4318`).
  - `sandbox.yml` (ubuntu-latest): `./gradlew -p tool publishToMavenLocal :reporter:dockerImage`, then `./gradlew -p sandbox stackAgent acceptanceTest`.

- [ ] **Step 1: Write the failing acceptance test**

```kotlin
@Testcontainers
class StepOneAcceptanceTest {
    @Container val stack = ComposeContainer(File("../docker-compose.yml")).withLocalCompose(true)
        .waitingFor("orders-service", Wait.forHttp("/actuator/health").forPort(8080))

    @Test @StackScenario("create-order") fun `create order is recorded end to end`() {
        OrdersScenarios("http://localhost:8080").createOrder()
        val run = awaitRecording(scenario = "create-order", minEvents = 2)   // polls ../recordings for up to 10 s
        run.manifest.kind shouldBe "e2e"
        run.events.single { it["type"] == "http.server" }.let {
            it["route"] shouldBe "/orders"; it["status"] shouldBe 201; (it["requestBody"] as String) shouldContain "c-1"
        }
        run.events.single { it["type"] == "db.client" && (it["statement"] as String).startsWith("insert into orders") }
            .let { (it["parameters"] as List<*>) shouldContain "sku-1" }
    }
    @Test @StackScenario("get-order") fun `get order is recorded`()   // route "/orders/{id}", status 200, one select db.client event
}
```

- [ ] **Step 2: Run** `./gradlew -p sandbox stackAgent acceptanceTest` — Expected: FAIL (service does not exist yet / compose fails).
- [ ] **Step 3: Implement** `orders-service`, Dockerfile (`eclipse-temurin:25-jre`), compose file, `stackAgent` task, scenarios, workspace, README, workflow.
- [ ] **Step 4: Run** `./gradlew -p sandbox stackAgent acceptanceTest` — Expected: PASS. Then run manually: `docker compose up -d --build`, `./gradlew -p sandbox e2eTest`, `tool/viewer-backend/build/stack-viewer/stack-viewer --workspace=sandbox/stack --recordings=sandbox/recordings`, open the printed URL.
- [ ] **Step 5: Commit** `feat(sandbox): orders-service with Postgres, tagged e2e scenarios and step-1 acceptance test`
- [ ] **Step 6: Owner validation.** Hand over to the owner to check the board, highlight and sequence view look as desired, and do the Windows milestone check (D25).
