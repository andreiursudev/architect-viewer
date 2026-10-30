# Step 3 — Methods Inside Services Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The sequence view can expand a service into one lifeline per class and show the method calls between them, with arguments and return values or exceptions, recorded by our agent extension (level 2).

**Architecture:** A new ByteBuddy instrumentation module in `agent-extension` opens one INTERNAL span per public method of Spring stereotype beans in the base packages named by `stack.methods.packages`; because the span is current, JDBC and HTTP client spans become its children. The reporter maps those spans to a new `method` event (schema version 3). `FlowBuilder` finds each event's owning method through the span tree, merges the controller method into its request as the `handler`, and gives every step `fromUnit`/`toUnit` class names. The sequence UI lays services out collapsed (as today) and expands them into class lifelines on demand.

**Tech Stack:** As in step 2 (Gradle 9.8.2, Java 25, Kotlin 2.4.21, Spring Boot 4.1.2, OTel Java agent 2.33.1 extension API with ByteBuddy, Jackson 3, React 19, Vitest, Playwright, Testcontainers ComposeContainer). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-30-step-3-method-capture-design.md` (parent: `2026-10-27-stack-tool-design.md`, D1, D11, D13, D26).

## Global Constraints

- Runs on macOS and Windows (D26): `Path`/`path.join`, no `:` in written file names, build logic in Gradle. No CI (D25); verification is `tool/gradlew -p tool build` and `sandbox/gradlew -p sandbox acceptanceTest`, run from the repo root.
- `agent-contract` and `agent-extension` stay **plain Java, `--release 17`**; helpers referenced by advice are listed in `getAdditionalHelperClassNames()` (no muzzle generation in this build). The viewer never imports OTel types.
- `SCHEMA_VERSION` becomes **3**; readers keep accepting 1 and 2.
- Method capture is **off unless `stack.methods.packages`** (system property) or `STACK_METHODS_PACKAGES` (environment) lists base packages, comma-separated.
- Excluded methods, always: constructors, static, synthetic, bridge, non-public; `get*`/`is*` with no parameters; `set*` with one parameter; `toString`, `equals`, `hashCode`, `copy`, `component1..N`; names ending in `$default`. Excluded classes: interfaces, synthetic, names containing `$$`.
- Stereotypes matched by name, directly on the class: `org.springframework.stereotype.Component`, `.Service`, `.Repository`, `.Controller`, `org.springframework.web.bind.annotation.RestController`.
- Values go through `BoundedValueSerializer` with `BodyCapture`'s bounds (depth 8, collections 100, strings 4096), `placeholder` first.
- Method step label: `methodName(arg1, arg2)` from the rendered arguments, cut to 60 characters (same `LABEL_LENGTH` as SQL).
- A run with every service collapsed lays out exactly as a run without method events.
- Kotlin test names: backtick sentences, except agent tests (camelCase, as the existing agent tests). Test-first in every task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Push only when the owner asks.

## Review Focus

1. **Two classes with the same simple name in one service** (`api.OrderMapper`, `db.OrderMapper`) must stay two lifelines. Ruling for this plan: `fromUnit`/`toUnit` hold the **fully qualified** class name; the UI shows the simple name in headers and the full one in the tooltip (the spec said simple name; this is the only change). Pinned in Task 6 (`two classes with the same simple name get two lifelines`).
2. **Arguments that are huge, cyclic or streams** (a 1 MB string, a self-referencing object, an `InputStream`) must not break the request or lose the span. Pinned in Task 3 (`hostileArgumentsAreBounded`).
3. **An exception thrown through several methods** must reach the caller unchanged (the out-of-stock request still answers 409) and mark each method on the way. Pinned in Task 3 (`exceptionIsRecordedAndRethrownUnchanged`), Task 5 (`an exception thrown through two methods marks both`), Task 1 (out-of-stock acceptance).
4. **A method event outside any request** (a scheduled job, a startup call, a run without an HTTP entry) must not fail the flow; it adds no step. Pinned in Task 5 (`a method outside any request adds no step`).
5. **Opening a run recorded before step 3**, or one with method capture off, must look exactly as today and offer no expand toggle. Pinned in Task 5 (`flow without method events is unchanged`) and Task 6 (`collapsed run with methods lays out like the run without them`, `no toggle without method steps`).

---

## File Structure

```
sandbox/
  services/orders-service/.../OrderService.kt          # NEW: create(newOrder), find(id); controller delegates
  services/orders-service/.../OrdersController.kt      # thin
  docker-compose.yml                                   # -Dstack.methods.packages=… per service
  README.md                                            # method capture note
  e2e-tests/src/acceptanceTest/.../SandboxAcceptanceTest.kt   # method expectations
tool/
  agent-contract/  StackAttributes.java (+ stack.method.*)
  agent-extension/ src/main/java/io/stackvibed/agent/method/{MethodCaptureConfig,MethodMatchers,MethodInstrumentationModule,MethodSpans}.java
                   META-INF/services/…InstrumentationModule (+1 line)
                   src/test/kotlin/…/method/{MethodCaptureConfigTest,MethodMatchersTest}.kt
                   src/agentTest/kotlin/…/{ForkedAgentApp.kt (jvmArgs), MethodCaptureAgentTest.kt}
  agent-fixture-app/ src/main/kotlin/io/stackvibed/fixture/{CatalogService,CatalogController}.kt
  model/           Events.kt (+MethodEvent, v3), view/Views.kt (FlowStepKind, FlowStep fields), tests, snapshot
  reporter/        SpanEventMapper.kt (+methodDraft), tests, golden/create-order-methods/
  viewer-backend/  topology/{FlowBuilder,TopologyService}.kt, tests, fixtures/recordings/<run-5 with methods>/
  viewer-ui/       src/generated/model.ts, src/status.ts, src/board/highlight.ts,
                   src/sequence/{layoutSequence.ts,SequenceDiagram.tsx,SequenceView.tsx,MessageDetails.tsx}, styles.css,
                   e2e/fixtures/recordings/<run-5>/, e2e/methods.spec.ts
docs/integrate-a-project.md, docs/superpowers/notes/2026-10-30-step-3-rulings-and-followups.md
```

---

### Task 1: Sandbox — `OrderService`, method capture switched on, failing acceptance test

**Files:**
- Create: `sandbox/services/orders-service/src/main/kotlin/io/stackvibed/sandbox/orders/OrderService.kt`
- Modify: `…/orders/OrdersController.kt`, `sandbox/docker-compose.yml`, `sandbox/e2e-tests/src/acceptanceTest/kotlin/io/stackvibed/sandbox/e2e/SandboxAcceptanceTest.kt`

**Interfaces:**
- Produces: `@Service class OrderService(orders: OrderRepository, inventory: InventoryClient)` with `create(newOrder: NewOrder): Order` (reserve, then insert, as the controller does today) and `find(id: UUID): Order?`. `OrdersController.create`/`get` delegate to it; HTTP behaviour unchanged.
- Produces (compose): `JAVA_TOOL_OPTIONS` of orders-service gains `-Dstack.methods.packages=io.stackvibed.sandbox.orders`, of inventory-service `-Dstack.methods.packages=io.stackvibed.sandbox.inventory`.

- [ ] **Step 1: Failing acceptance tests.** In `create order is recorded across both services`, add:

```kotlin
val methods = run.events.filter { it["type"] == "method" }
methods.map { "${(it["className"] as String).substringAfterLast('.')}.${it["methodName"]}" }.toSet() shouldBe setOf(
    "OrdersController.create", "OrderService.create", "InventoryClient.reserve", "OrderRepository.insert",
    "ReservationsController.reserve", "StockRepository.reserve",
)
val reserveMethod = methods.single { it["methodName"] == "reserve" && it["component"] == "orders-service" }
client["parentSpanId"] shouldBe reserveMethod["spanId"]
(reserveMethod["arguments"] as List<*>) shouldBe listOf("\"sku-1\"", "2")   // the values createOrder() posts
```

In `out of stock is recorded as a failed hop and no insert`, add: the orders-service `InventoryClient.reserve`, `OrderService.create` and `OrdersController.create` events all have `exceptionType == "org.springframework.web.server.ResponseStatusException"`, and there is no `OrderRepository.insert` event.

- [ ] **Step 2: Extract `OrderService`**, switch capture on in compose.
- [ ] **Step 3: Run and see the expected red.**

Run: `tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage && sandbox/gradlew -p sandbox stackAgent assemble acceptanceTest`
Expected: the stack starts with the unknown system property ignored; HTTP behaviour is unchanged (201, 409); the new assertions FAIL because no `method` events exist. `get order is recorded` passes.

- [ ] **Step 4: Commit** — `feat(sandbox): OrderService and method capture switched on; failing step 3 acceptance test`

---

### Task 2: Model — `method` event, step kinds and units, schema version 3

**Files:**
- Modify: `tool/model/src/main/kotlin/io/stackvibed/model/Events.kt`, `…/model/view/Views.kt`
- Modify: `tool/model/src/test/kotlin/io/stackvibed/model/{Samples.kt,EventJsonTest.kt}`, `src/test/resources/schema-snapshot/model.schema.json`
- Modify: `tool/viewer-ui/src/generated/model.ts` (`npm run gen:types`)
- Modify (compile only): `tool/viewer-backend/.../topology/{FlowBuilder,TopologyService}.kt`, `tool/viewer-ui/src/board/highlight.ts`, UI test data, reporter golden files

**Interfaces:**
- Produces: `const val SCHEMA_VERSION = 3`, `JsonSubTypes.Type(value = MethodEvent::class, name = "method")`, and

```kotlin
data class MethodEvent(
    /* schemaVersion, eventId, runId, traceId, spanId, parentSpanId, component, startEpochNanos, durationNanos */
    val className: String,             // fully qualified
    val methodName: String,
    val parameterNames: List<String>,  // empty when the class file has none
    val arguments: List<String>,       // one rendered JSON value per argument
    val returnValue: String?,          // null for void/Unit or when it threw
    val exceptionType: String?,        // fully qualified
    val exceptionMessage: String?,
    val thread: String?,
) : RecordingEvent

enum class FlowStepKind { @JsonProperty("http") HTTP, @JsonProperty("db") DB, @JsonProperty("method") METHOD }

data class FlowStep(
    val stepId: String, val parentStepId: String?, val from: String, val to: String,
    val fromUnit: String?,             // fully qualified class at the calling end, null when not a recorded class
    val toUnit: String?,
    val connectionId: String?,         // null for method steps
    val kind: FlowStepKind,
    val label: String, val startEpochNanos: Long, val durationNanos: Long, val status: Int?,
    val event: RecordingEvent,
    val handler: MethodEvent?,         // on an http step: the method its request landed on
)
```

- [ ] **Step 1: Failing tests** in `EventJsonTest`: `` `method event round-trips with type discriminator` `` (a `sampleMethodEvent` serialises with `"type":"method"`, `"schemaVersion":3`, reads back equal); `` `version 2 lines still read` `` (a literal `http.client` line from `golden/create-order-reserves/events.jsonl` deserialises with `schemaVersion == 2`); `` `flow step kind serialises lowercase` `` (`FlowStepKind.METHOD` → `"method"`).
- [ ] **Step 2: Run** `tool/gradlew -p tool :model:test`. Expected: FAIL (no `MethodEvent`).
- [ ] **Step 3: Implement**, refresh the schema snapshot as `SchemaSnapshotTest` says, regenerate `model.ts`. Keep the rest compiling with no behaviour change: `FlowBuilder` sets `fromUnit = toUnit = handler = null` and maps its kinds to `FlowStepKind`; it ignores `MethodEvent` (`is MethodEvent -> null`). `TopologyService` skips steps with `connectionId == null` and maps `FlowStepKind` to `ConnectionKind`. `highlight.ts` skips steps with a null `connectionId`. UI `testData.ts` gains the new fields.
- [ ] **Step 4: Run** `tool/gradlew -p tool :model:test :reporter:test :viewer-backend:test :viewer-ui:npmTest :viewer-ui:build`. Expected: PASS once the reporter golden files read `"schemaVersion":3`.
- [ ] **Step 5: Commit** — `feat(model): method event, flow step kinds and units, schema version 3`

---

### Task 3: Agent — one span per method call

**Files:**
- Modify: `tool/agent-contract/src/main/java/io/stackvibed/agent/contract/StackAttributes.java`
- Create: `tool/agent-extension/src/main/java/io/stackvibed/agent/method/{MethodCaptureConfig,MethodMatchers,MethodInstrumentationModule,MethodSpans}.java`
- Modify: `tool/agent-extension/src/main/resources/META-INF/services/io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule`
- Create: `tool/agent-extension/src/test/kotlin/io/stackvibed/agent/method/{MethodCaptureConfigTest,MethodMatchersTest}.kt`
- Create: `tool/agent-fixture-app/src/main/kotlin/io/stackvibed/fixture/{CatalogService,CatalogController}.kt`
- Modify: `tool/agent-extension/src/agentTest/kotlin/io/stackvibed/agent/ForkedAgentApp.kt`
- Create: `tool/agent-extension/src/agentTest/kotlin/io/stackvibed/agent/MethodCaptureAgentTest.kt`

**Interfaces:**
- Produces (contract): `METHOD_CLASS = "stack.method.class"`, `METHOD_NAME = "stack.method.name"`, `METHOD_PARAM_NAMES = "stack.method.param_names"`, `METHOD_ARGS = "stack.method.args"`, `METHOD_RETURN = "stack.method.return"`, `METHOD_EXCEPTION_TYPE = "stack.method.exception.type"`, `METHOD_EXCEPTION_MESSAGE = "stack.method.exception.message"`, `METHOD_THREAD = "stack.method.thread"`.
- Produces: `MethodCaptureConfig.basePackages(String property, String env): List<String>` (property wins when non-blank; split on `,`, trim, drop blanks) and `MethodCaptureConfig.basePackages()` reading `stack.methods.packages` / `STACK_METHODS_PACKAGES`.
- Produces: `MethodMatchers.types(List<String> packages): ElementMatcher<TypeDescription>` (`none()` when empty) and `MethodMatchers.methods(): ElementMatcher<MethodDescription>`, both per Global Constraints; `MethodMatchers.isExcludedName(String name, int parameterCount): boolean`.
- Produces: `MethodSpans.start(Class<?> type, String method, Object[] args, String[] paramNames)` → span + scope (stored in advice locals); `MethodSpans.end(…, Object returned, boolean isVoid, Throwable thrown)`. Tracer `GlobalOpenTelemetry.getTracer("stack-methods")`, span name `SimpleClass.method`, kind INTERNAL, status ERROR on throw.
- Produces (agentTest): `ForkedAgentApp.start(receiverPort: Int, jvmArgs: List<String> = emptyList())`.
- Produces (fixture): `@Service CatalogService` (package `io.stackvibed.fixture`) with `price(sku: String, quantity: Int): Long` (calls `this.discount(quantity)`), `discount(quantity: Int): Int`, `getName(): String`, `fail(reason: String): Nothing` (throws `IllegalStateException(reason)`), `@Transactional count(): Long` (JDBC `select count(*) from items`, makes Spring proxy the bean), `echo(value: Any?): Int`. `CatalogController` exposes `GET /catalog/price?sku=&quantity=`, `GET /catalog/fail` (→ 500), `GET /catalog/count`, `POST /catalog/echo` (body passed to `echo` as a big string; plus a cyclic node and a `ByteArrayInputStream` built in the controller).

- [ ] **Step 1: Failing unit tests.**
  - `MethodCaptureConfigTest`: `` `blank property falls back to env` ``, `` `list is split trimmed and blanks dropped` `` (`" a.b , ,c "` → `["a.b","c"]`), `` `nothing set gives no packages` ``.
  - `MethodMatchersTest` (on small Kotlin/Java test classes in the test source set, via `TypeDescription.ForLoadedType`): `` `stereotype class in base package matches` ``, `` `sub package matches and sibling package does not` `` (`a.b` matches `a.b.c.X`, not `a.bc.X`), `` `class without stereotype does not match` ``, `` `cglib proxy name does not match` ``, `` `no packages matches nothing` ``, and `isExcludedName` cases: `getName/0`, `isOpen/0`, `setName/1`, `toString/0`, `equals/1`, `hashCode/0`, `copy/3`, `component2/0`, `create$default/4` excluded; `getOrCreate/1`, `get/1`, `issue/0`, `settle/0`, `create/1` kept.
- [ ] **Step 2: Failing agent tests** in `MethodCaptureAgentTest` (app started with `-Dstack.methods.packages=io.stackvibed.fixture`; requests carry `baggage: stack.scenario=s-1,stack.run=r-1`):
  - `methodSpanCarriesClassNameArgumentsAndReturn`: `GET /catalog/price?sku=a&quantity=3` → INTERNAL span `CatalogService.price` with `stack.method.class=io.stackvibed.fixture.CatalogService`, `param_names=["sku","quantity"]`, `args=["\"a\"","3"]`, a `stack.method.return`, a `stack.method.thread`, and `stack.run=r-1`.
  - `methodsNestByCallStack`: `CatalogService.price`'s parent is `CatalogController.price`, whose parent is the SERVER span; `CatalogService.discount` (self-call) is a child of `CatalogService.price`.
  - `jdbcSpanIsChildOfTheRepositoryMethod`: for `/catalog/count`, the CLIENT db span's parent is the `CatalogService.count` span; exactly one `CatalogService.count` span exists (the CGLIB proxy adds none).
  - `gettersAndObjectMethodsAreNotRecorded`: no span named `CatalogService.getName`, `*.toString`, `*.hashCode`, `*.equals` in any trace.
  - `exceptionIsRecordedAndRethrownUnchanged`: `/catalog/fail` answers 500; the `CatalogService.fail` span has status ERROR, `exception.type=java.lang.IllegalStateException`, the message, no `stack.method.return`.
  - `hostileArgumentsAreBounded`: `POST /catalog/echo` with a 1 000 000-character string answers 200; the `CatalogService.echo` spans' string argument renders in at most 4 200 characters, the cyclic node renders without error, the stream renders as `"<stream>"`.
  - `noPackagesNoMethodSpans` (separate app started without the property): `/catalog/price` gives SERVER and no INTERNAL span with `stack.method.class`.
- [ ] **Step 3: Run** `tool/gradlew -p tool :agent-extension:test :agent-extension:agentTest --tests '*MethodCapture*'`. Expected: FAIL.
- [ ] **Step 4: Implement.** Advice is `@Advice.OnMethodEnter` / `@Advice.OnMethodExit(onThrowable = Throwable.class)` on `MethodMatchers.methods()`, inlined, reading `@Advice.Origin Method` for parameter names (`Parameter.isNamePresent()`; none → empty) and `@Advice.AllArguments`. Every serialisation is wrapped so that any failure leaves a placeholder and never touches the application's flow. List `MethodCaptureConfig`, `MethodMatchers`, `MethodSpans`, `BoundedValueSerializer`, `BodyCapture` as helpers. Register the module in `META-INF/services`.
- [ ] **Step 5: Run** `tool/gradlew -p tool :agent-extension:test :agent-extension:agentTest`. Expected: PASS, and the existing HTTP/JDBC/propagation agent tests still pass (they start the app without the property).
- [ ] **Step 6: Commit** — `feat(agent): one span per method of Spring beans in configured packages`

---

### Task 4: Reporter — record method spans

**Files:**
- Modify: `tool/reporter/src/main/kotlin/io/stackvibed/reporter/otlp/SpanEventMapper.kt`
- Modify: `tool/reporter/src/test/kotlin/io/stackvibed/reporter/otlp/{OtlpFixtures.kt,SpanEventMapperTest.kt}`, `…/ReporterGoldenTest.kt`
- Create: `tool/reporter/src/test/resources/golden/create-order-methods/{events.jsonl,manifest.json}`

**Interfaces:**
- Consumes: `MethodEvent` (Task 2), `StackAttributes.METHOD_*` (Task 3).
- Produces: INTERNAL spans with `stack.method.class` map to `MethodEvent`; `param_names` and `args` decode as JSON string arrays (malformed → empty, as for parameters); every other INTERNAL span is dropped.

- [ ] **Step 1: Failing tests.** `` `internal span with method attributes becomes method event` `` (every field mapped, `component` from `service.name`); `` `method span that threw has exception and no return` ``; `` `internal span without method attributes is dropped` ``; `ReporterGoldenTest` `` `create order with methods is written in call order` `` (orders-service: server, `OrdersController.create`, `OrderService.create`, `InventoryClient.reserve`, client, `OrderRepository.insert`, db; one folder; `events.jsonl` equals the golden file).
- [ ] **Step 2: Run** `tool/gradlew -p tool :reporter:test`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** `methodDraft` beside the other drafts; `Span.SpanKind.SPAN_KIND_INTERNAL -> methodDraft(…)`; update the class KDoc.
- [ ] **Step 4: Run** `tool/gradlew -p tool :reporter:test`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(reporter): record method spans as method events`

---

### Task 5: Viewer backend — owners, handlers and method steps

**Files:**
- Modify: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/topology/FlowBuilder.kt`
- Modify: `tool/viewer-backend/src/test/kotlin/io/stackvibed/viewer/topology/FlowBuilderTest.kt`, `…/web/ApiTest.kt`
- Create: `tool/viewer-backend/src/test/resources/fixtures/recordings/2026-10-30T10-00-00_create-order_run-5/{manifest.json,events.jsonl}` (schemaVersion 3, hand-written in the Task-1 shape, both services, ids and times consistent)

**Interfaces:**
- Consumes: `MethodEvent`, `FlowStep`, `FlowStepKind` (Task 2).
- Produces: `FlowBuilder.build(manifest, events, aliases): Flow` (same signature) per spec §3:
  - `owner(e)`: the `MethodEvent` whose `(traceId, spanId)` equals `e`'s parent. If the parent is another recorded event that is not a method → none. If the parent is not recorded → the innermost `MethodEvent` of the same component and trace containing `e` in time (the `enclosingRequest` ordering), excluding `e` itself.
  - For each request, the earliest ownerless method enclosed by it is its `handler`: no step; the request step gets `toUnit = handler.className`, `handler = handler`. `stepOf(method)` = the request's step id for a handler, else the method's own event id.
  - Other methods: step `kind = METHOD`, `from = to = alias(component)`, `fromUnit` = owner's class (an ownerless non-handler: the handler's class), `toUnit` = own class, `connectionId = null`, `parentStepId = stepOf(owner)` (ownerless: the request step), label per Global Constraints, `status = null`. A method enclosed by no request → no step.
  - DB and unanswered client steps: `fromUnit` = owner's class; parent = `stepOf(owner)` when there is an owner, else the enclosing request (unchanged). Server step called by a recorded client: `fromUnit` = class of the client's owner; parent = `stepOf(owner of client)` when present, else as today.

- [ ] **Step 1: Failing tests** in `FlowBuilderTest`:
  - `` `controller method is merged into its request as the handler` ``: request lands with `toUnit = "x.OrdersController"`, `handler.methodName == "create"`, and no step has `event` == that method.
  - `` `methods become steps between classes nested by their owners` ``: controller → `OrderService.create` → `InventoryClient.reserve` → called inventory server; `OrderService.create` step `fromUnit = "x.OrdersController"`, `toUnit = "x.OrderService"`, parent = request step; the inventory hop's `fromUnit = "x.InventoryClient"`, parent = the `reserve` step.
  - `` `db step hangs under the repository method` ``: `fromUnit = "x.OrderRepository"`, parent = the `insert` step, `toUnit == null`.
  - `` `owner falls back to time containment when the parent span is not recorded` ``.
  - `` `self call is a step from a class to itself` ``.
  - `` `an exception thrown through two methods marks both` ``: both steps' events carry `exceptionType`; labels unchanged.
  - `` `a method outside any request adds no step` ``.
  - `` `flow without method events is unchanged` ``: the step-2 cases' expected steps, with null units and handler.
  - `ApiTest`: `` `run with methods is served with handler and units` `` on the run-5 fixture.
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-backend:test`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement**; update the KDoc of `build` and `enclosingRequest`. If a fixture used by `TopologyServiceTest` gains the run-5 folder, its expectations must stay the same (method steps add no connections).
- [ ] **Step 4: Run** `tool/gradlew -p tool :viewer-backend:test`. Expected: PASS, Modulith and ArchUnit included.
- [ ] **Step 5: Commit** — `feat(viewer): method steps between classes; requests land on their handler`

---

### Task 6: Viewer UI — class lifelines, expand and collapse

**Files:**
- Modify: `tool/viewer-ui/src/sequence/{layoutSequence.ts,SequenceDiagram.tsx,SequenceView.tsx}`, `src/styles.css`, and their tests

**Interfaces:**
- Consumes: `FlowStep.fromUnit/toUnit/kind` (Task 2).
- Produces:
  - `layoutSequence(flow: Flow, expanded: ReadonlySet<string> = new Set()): SequenceLayout` where `SequenceParticipant` gains `component: string`, `unit: string | null` (fully qualified), and `SequenceLayout` gains `groups: { component: string; fromX: number; toX: number }[]` (one per expanded service) and `headerHeight: number` (`HEADER_HEIGHT`, plus `GROUP_BAND = 28` when any group exists). Participant id: `` `${component}::${unit}` `` when `expanded.has(component) && unit != null`, else `component`.
  - `classCounts(flow: Flow): Map<string, number>`: per component with at least one method step, the number of distinct units on that component's steps.
  - `SequenceDiagram` props gain `expanded: ReadonlySet<string>` and `onToggle(component: string)`.

- [ ] **Step 1: Failing tests.**
  - `layoutSequence.test.ts`: `collapsed run with methods lays out like the run without them` (same participants and messages as the flow with method steps removed and children re-parented); `expanded service gets one lifeline per class in order of appearance` (`orders-service::x.OrdersController, ::x.OrderService, ::x.InventoryClient, ::x.OrderRepository`, adjacent, between `e2e-client` and `inventory-service`); `request lands on the handler class and calls leave from their owner class`; `two classes with the same simple name get two lifelines`; `group spans its classes and raises the header`.
  - `SequenceDiagram.test.tsx`: `collapsed service with methods shows a class count toggle` (`▸ 4 classes`, clicking calls `onToggle('orders-service')`); `no toggle without method steps`; `expanded group shows its name and collapses`; class header text is the simple name and its `title` the full name.
  - `SequenceView.test.tsx`: `expand all and collapse all`; for a flow with 4 non-method and 4 method steps the bar reads `4 calls · 4 method calls`; without method steps it reads `4 calls` as today.
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-ui:npmTest`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.** In `visit`, a method step whose component is collapsed adds no rows but still visits its children. Order participants by component first appearance, then by unit first appearance within an expanded component. `SequenceView` holds `expanded` in `useState<Set<string>>`, not persisted. Group band and toggle styles in `styles.css`, using existing tokens.
- [ ] **Step 4: Run** `tool/gradlew -p tool :viewer-ui:npmTest :viewer-ui:build`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(viewer-ui): expand services into class lifelines in the sequence`

---

### Task 7: Viewer UI — method labels, failures and details

**Files:**
- Modify: `tool/viewer-ui/src/status.ts`, `src/sequence/MessageDetails.tsx`, and their tests

**Interfaces:**
- Produces: `isFailedStep` is also true for a `method` event with `exceptionType != null`. `responseText` for a `method` event: `` `throws ${simpleName(exceptionType)}` ``, else the return value with whitespace collapsed, cut to 40 characters with `…`, else `returned`.

- [ ] **Step 1: Failing tests.**
  - `status.test.ts`: `method that threw is failed and says what it threw` (`throws ResponseStatusException`); `method return is shortened` (a 100-char JSON → 40 chars ending `…`); `void method says returned`.
  - `MessageDetails.test.tsx`: `method step shows class method thread and arguments by name` (`sku: "sku-1"`, `quantity: 2`); `arguments without names are argN`; `method that threw shows Threw with type and message`; `http step with a handler shows Handled by OrdersController.create with its arguments`.
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-ui:npmTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Argument values go through the existing `prettyBody`.
- [ ] **Step 4: Run** `tool/gradlew -p tool :viewer-ui:npmTest :viewer-ui:build`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(viewer-ui): method labels, thrown methods and method details`

---

### Task 8: Viewer e2e — expand a service in the browser

**Files:**
- Create: `tool/viewer-ui/e2e/fixtures/recordings/2026-10-30T10-00-00_create-order_run-5/` (copy of the Task-5 fixture)
- Create: `tool/viewer-ui/e2e/methods.spec.ts`

- [ ] **Step 1: Failing spec** `methods.spec.ts`:
  - `run opens collapsed`: headers read `e2e-client, orders-service, inventory-service, postgresql-inventory, postgresql-orders` (the reservation comes before the insert), and `orders-service` shows `▸ 4 classes`.
  - `expanding orders-service shows its classes and a method call`: click the toggle; headers include `OrdersController, OrderService, InventoryClient, OrderRepository`; clicking the `reserve("sku-1", 2)` request shows `quantity: 2` in the details panel.
  - `collapse all returns to the collapsed diagram`.
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-ui:e2eTest`. Expected: FAIL before the fixture exists. If existing specs count runs, update them for the new run.
- [ ] **Step 3: Add the fixture**, make it pass.
- [ ] **Step 4: Run** `tool/gradlew -p tool build`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `test(viewer-ui): expand a service into classes end to end`

---

### Task 9: Green acceptance, docs, owner validation

**Files:**
- Modify: `sandbox/README.md`, `docs/integrate-a-project.md`
- Create: `docs/superpowers/notes/2026-10-30-step-3-rulings-and-followups.md`

- [ ] **Step 1: Full chain from clean recordings.**

Run: `rm -rf sandbox/recordings && tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage :viewer-backend:viewerDist && sandbox/gradlew -p sandbox stackAgent assemble acceptanceTest`
Expected: `SandboxAcceptanceTest` passes 3/3. Compare a real `create-order` recording with the run-5 fixtures; if field shapes differ, fix the fixtures and rerun `tool/gradlew -p tool build`.

- [ ] **Step 2: Docs.** `sandbox/README.md`: method capture is on for both services and how to turn it off. `docs/integrate-a-project.md`: add `-Dstack.methods.packages=<base package>` to the agent arguments, off by default. Notes file: the FQN-unit ruling (Review Focus 1), any rulings made during execution, and the spec's deferrals (coroutines, workspace patterns, layer facts, component view, export).
- [ ] **Step 3: Commit** — `docs: step 3 method capture in the README, integration guide and notes`
- [ ] **Step 4: Owner validation** (not automated): run the README flow, open a `create-order` run in the viewer, expand `orders-service` and `inventory-service`, click method arrows, and check the out-of-stock run's thrown path.
