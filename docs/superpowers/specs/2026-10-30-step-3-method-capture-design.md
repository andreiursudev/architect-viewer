# Step 3 — methods inside services in the sequence view (level 2)

Date: 2026-10-30
Status: approved in conversation, written spec for review
Parent spec: `2026-10-27-stack-tool-design.md` (goals 2 and 4, D1, D11, D13)

## 1. Intent

The owner asked to see, in the sequence view, the methods called inside services. Today the agent records level 1
only (HTTP server, HTTP client, JDBC), so a service is a single lifeline with nothing between its incoming request and
its outgoing calls. This step records level-2 method calls and draws them.

Success: opening a `create-order` run shows the diagram exactly as today; expanding `orders-service` shows the request
landing on `OrdersController`, the calls `OrderService.create` → `InventoryClient.reserve` → `POST /reservations`, and
clicking a method arrow shows its arguments and its return value or exception.

Decided with the owner:

- **Class lifelines.** Every class called inside a service gets its own lifeline, grouped under the service. A method
  call is an arrow between classes.
- **Collapsed by default.** A run opens with one lifeline per service, as today; the owner expands a service to see its
  classes.
- **Capture approach A**: our own ByteBuddy advice in the agent extension opens one internal span per method call
  (D1 "our own agent extension captures … method I/O"). Rejected: OTel's `otel.instrumentation.methods.include` (every
  `Class[method]` listed by hand, no arguments or return values, fails D11) and a side channel outside spans (loses the
  parent links).

## 2. Capture

### 2.1 Agent extension

New `io.stackvibed.agent.method.MethodInstrumentationModule`.

- **Enabled by** the system property `stack.methods.packages` (or the environment variable `STACK_METHODS_PACKAGES`):
  a comma-separated list of base packages. Unset or blank → the type matcher matches nothing; the module costs nothing.
- **Classes matched**: in one of the base packages or their sub-packages, and carrying a Spring stereotype annotation
  directly: `@Component`, `@Service`, `@Repository`, `@Controller`, `@RestController`, matched by annotation name so no
  Spring type is loaded. Interfaces, synthetic classes and classes whose name contains `$$` (CGLIB proxies such as
  `$$SpringCGLIB$$`) are skipped.
- **Methods matched**: public, non-static, non-synthetic, non-bridge, not constructors. Always excluded:
  `get*` / `is*` with no arguments, `set*` with one argument, `toString`, `equals`, `hashCode`, `copy`, `componentN`,
  and names ending in `$default`.
- **Advice**:
  - enter: start an INTERNAL span named `SimpleClass.method` with the current context as parent and make it current, so
    JDBC and HTTP client spans created inside the method become its children;
  - exit: set the attributes below and end the span. A thrown exception sets the exception attributes and the span
    status to error, and is rethrown unchanged.
- **Values**: arguments (a JSON array) and the return value go through `BoundedValueSerializer` with the bounds used for
  bodies; `BoundedValueSerializer.placeholder` applies first. Parameter names are recorded when the class file has
  them (`-parameters`, the default for Kotlin and Spring Boot builds); otherwise the attribute is absent. A `void` or
  `Unit` method records no return value.
- **Coroutines**: `suspend` functions are not handled specially in this step (see §6).

### 2.2 Contract (`agent-contract`)

New `StackAttributes` keys:

| Key | Value |
|-----|-------|
| `stack.method.class` | fully qualified class name |
| `stack.method.name` | method name |
| `stack.method.param_names` | JSON array of strings |
| `stack.method.args` | JSON array, one rendered value per argument |
| `stack.method.return` | rendered return value |
| `stack.method.exception.type` | fully qualified exception class |
| `stack.method.exception.message` | exception message |
| `stack.method.thread` | thread name |

### 2.3 Model

New event type `method`:

```kotlin
data class MethodEvent(
    /* the RecordingEvent fields */
    val className: String,          // fully qualified
    val methodName: String,
    val parameterNames: List<String>,  // empty when not recorded
    val arguments: List<String>,       // rendered JSON values
    val returnValue: String?,          // null for void/Unit or when it threw
    val exceptionType: String?,
    val exceptionMessage: String?,
    val thread: String?,
) : RecordingEvent
```

`SCHEMA_VERSION` becomes 3; readers accept 1, 2 and 3. The schema snapshot and the generated TypeScript types are
regenerated.

### 2.4 Reporter

`SpanEventMapper` maps INTERNAL spans that carry `stack.method.class` to `MethodEvent`. Every other INTERNAL span is still
dropped. Scenario and run come from the baggage attributes, as for the other events.

## 3. Flow (`FlowBuilder`)

### 3.1 Step shape

- `FlowStep.kind` becomes its own enum `FlowStepKind { http, db, method }` instead of the workspace's
  `ConnectionKind`; a method call is not a board connection.
- `FlowStep.connectionId` becomes nullable; it is `null` for method steps.
- New `fromUnit: String?` and `toUnit: String?`: the simple class name at each end, `null` where the end is not a
  recorded class (a database, the e2e client, an uninstrumented host, a service without method capture).
- New `handler: MethodEvent?` on HTTP server steps (see 3.3).

### 3.2 Owner of an event

For a method, DB or `http.client` event, its **owner** is the recorded method event that is its parent span (same trace).
When the parent span is not recorded, the owner is the innermost method event of the same component and trace whose
time interval contains the event (the rule `enclosingRequest` uses today). An event may have no owner.

### 3.3 Steps

- **Handler**: within one request, the earliest method event with no owner. It gets no step of its own: the request
  step's `toUnit` is its class and the request step carries it as `handler`.
- Any other ownerless method inside a request: a method step from the handler's class.
- **Method step** for every other method event: `kind = method`, `from = to = component`, `fromUnit` = owner's class,
  `toUnit` = own class (equal for a self-call), parent = owner's step (the request step when the owner is the handler),
  label = `methodName` alone (the arguments are in the details panel; the owner chose this after trying step 3), `status = null`.
- **DB and `http.client` steps, and server steps called by another service**: `fromUnit` = class of the owner (of the
  client event, for a server step); parent = the owner's step when there is an owner, else the enclosing request as
  today.
- Without method events the flow is identical to today's apart from the new fields being `null`.

### 3.4 Board

The primary board's path highlight and hover details skip steps with a `null` `connectionId`.

## 4. Sequence UI

### 4.1 Expand and collapse

- `SequenceView` holds the set of expanded service ids as page state, empty on load, not persisted.
- A collapsed service's header shows a toggle "▸ N classes" when the run has method steps on that service.
- The bar gets **Expand all** / **Collapse all**.

### 4.2 Layout (`layoutSequence(flow, expanded)`)

- Participants: a collapsed service, a database or a client is one lifeline (id = component id). An expanded service
  has one lifeline per class seen in its steps (id = `component::Class`, header = simple class name). A step on an
  expanded service whose unit is `null` uses a service-level lifeline.
- Order: services by first appearance; within an expanded service its classes by first appearance, always adjacent.
- Method steps on a collapsed service produce no rows; their child steps are still visited. A fully collapsed run lays
  out exactly as today.
- An expanded service gets a group band above its class headers with the service name and a ▾ collapse button. The
  header area is taller only while some service is expanded.
- Arrows attach to class lifelines when that end's service is expanded, else to the service lifeline. Activation bars
  nest by depth as today.

### 4.3 Labels and failures

- Method response label: a short form of the return value, `throws SimpleExceptionName`, or `returned` for no value.
- `isFailedStep` is true for a method step with an `exceptionType`; its response arrow takes the error tone. An
  exception passing up through several methods marks each of their responses.

### 4.4 Details panel (`MessageDetails`)

- Method step: class (fully qualified), method, thread, duration; **Arguments** one per parameter (`name: value`, JSON
  indented; `argN` when names are missing); **Returned** or **Threw** (type and message). The clicked request/response
  section is marked as today.
- HTTP server step with a `handler`: a section "Handled by `SimpleClass.method`" with its arguments and return value.
- The bar's count stays "N calls" for non-method steps and adds "· M method calls" when there are any.

## 5. Sandbox

- `orders-service`: extract `OrderService` (`@Service`) from `OrdersController` so the chain is controller → service
  → `InventoryClient` / `OrderRepository`. Behaviour unchanged.
- `inventory-service`: unchanged (`ReservationsController` → `StockRepository`).
- `docker-compose.yml`: `-Dstack.methods.packages=io.stackvibed.sandbox.orders` (and `io.stackvibed.sandbox.inventory`)
  in each service's `JAVA_TOOL_OPTIONS`. The Podman override does not touch these options. README updated.

## 6. Departures from the parent spec and deferrals

- D11 says include/exclude patterns come from the workspace. Here method capture is enabled per service by an agent
  property listing base packages, because nothing yet carries workspace configuration to a running JVM. Patterns arrive
  when something does.
- Deferred: coroutine-aware `suspend` capture (no sandbox service uses coroutines), annotation facts for layers (D12),
  the component view, method calls in Mermaid/PlantUML export.

## 7. Testing (test-first per module)

- **Sandbox acceptance**: `create-order` records method events, and its flow shows `POST /orders` handled by
  `OrdersController.create`, `OrderService.create` → `InventoryClient.reserve` → `POST /reservations` handled by
  `ReservationsController.reserve` → `StockRepository.reserve` → the `UPDATE`. In `create-order-out-of-stock`,
  `InventoryClient.reserve` throws `ResponseStatusException`.
- **Agent (forked JVM, `agent-fixture-app`)**: a fixture `@Service` with a plain method, a getter, a self-call, a method
  that throws, and a repository method doing JDBC. `MethodCaptureAgentTest` asserts span names, arguments with
  parameter names, return value, exception, that the JDBC span's parent is the repository method's span, that getters,
  `toString` and CGLIB proxies are not recorded, and that a run without the property records no method spans.
- **Agent unit**: method exclusion rules and package-list parsing.
- **Model**: `EventJsonTest` round-trips `method`; schema snapshot regenerated; version 2 files still read.
- **Reporter**: `SpanEventMapperTest` for INTERNAL spans with and without `stack.method.*`; a golden fixture of a
  create-order run with methods.
- **Viewer backend**: `FlowBuilderTest` for the handler merge, owner by parent span, owner by time containment, a nested
  exception, a self-call, and an unchanged flow without method events; a recording fixture with methods served through
  `ApiTest`.
- **UI**: Vitest for `layoutSequence` (collapsed equals today, expanded participants and order, nesting),
  `SequenceDiagram` toggle and Expand all, `MessageDetails` method step and handler section; Playwright `sequence.spec`
  expands `orders-service` and clicks a method arrow.
