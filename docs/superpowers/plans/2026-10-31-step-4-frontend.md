# Step 4 — A Browser Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A React SPA `frontend` joins the sandbox; its page loads, user actions and fetches are recorded by our own browser package and shown on the board and in the sequence view, nested above the existing service, database and method steps.

**Architecture:** `tool/browser-sdk` (`@stackvibed/browser`) sets up the OpenTelemetry web tracer with the fetch instrumentation plus our own `stack.navigation` and `stack.action` spans, tags every span and every fetch's `baggage` with the scenario and run from `window.__STACK__`, and exports OTLP/HTTP protobuf to the reporter, which now answers CORS for localhost origins and maps those spans to new `ui.navigation` / `ui.action` events (schema version 4); fetches stay `http.client`. `FlowBuilder` makes each ui event a top-level step from the e2e client to the frontend and nests every frontend fetch under the latest ui step before it; the existing rules do the rest. Playwright tests in the sandbox tag the page through a new `StackBrowser` helper in the test harness.

**Tech Stack:** As in step 3 (Gradle 9.8.2, Java 25, Kotlin 2.4.21, Spring Boot 4.1.2, OTel Java agent 2.33.1, Jackson 3, React 19.3.1, Vite 8.3.5, Vitest 5.0.5, Playwright 1.64.1, Testcontainers). New: OpenTelemetry JS (`@opentelemetry/api`, `sdk-trace-web`, `sdk-trace-base`, `resources`, `core`, `exporter-trace-otlp-proto`, `instrumentation`, `instrumentation-fetch`); Java Playwright `com.microsoft.playwright:playwright` 1.64.0 (the Java release of viewer-ui's 1.64 line); nginx `1.27-alpine`; the Gradle Node plugin in the sandbox build.

**Spec:** `docs/superpowers/specs/2026-10-31-step-4-frontend-design.md` (parent: `2026-10-27-stack-tool-design.md`, D1, D8, D9, D13, D24, D26).

## Rulings made while planning (copied into the step-4 notes in Task 9)

- **F1: no zone.js, no user-interaction instrumentation.** The SDK records actions with its own capture-phase `click`/`submit` listener on `document` (`stack.action` spans) and page loads with its own `stack.navigation` span. The spec §7 spike resolves without code: OTel's user-interaction instrumentation describes the element the listener is attached to, which in React (root event delegation) is the root container, not the clicked button; and `ZoneContextManager` cannot follow native `async`/`await`. So the §5.2 fallback (nest a fetch under the latest ui step before it) is the main path; a parent-span link is still honoured when present. The reporter recognises browser spans by these span names, not by the `telemetry.sdk.language = webjs` resource attribute (spec §3.3).
- **F2:** `GET /stock` answers `[{"sku": …, "available": …}]`, the stock table's column, not `quantity` as the spec says.
- **F3:** the frontend does not list `@stackvibed/browser` in `package.json`. A Gradle task installs the tarball with `npm install --no-save` after `npmInstall`, because the tarball changes on every tool build and a locked `file:` tarball fails npm's integrity check.
- **F4:** ui steps draw a request arrow only (no response row); they never fail.

## Global Constraints

- Runs on macOS and Windows (D26): `Path`/`path.join`, no `:` in written names, build logic in Gradle, no shell scripts. No CI (D25); verification is `tool/gradlew -p tool build` and `sandbox/gradlew -p sandbox acceptanceTest`, from the repo root.
- `SCHEMA_VERSION` becomes **4**; readers keep accepting 1–3. The viewer never imports OTel types; the sandbox never depends on `tool/` sources (only `mavenLocal` artifacts and the reporter image).
- npm dependencies are pinned exactly (no `^`/`~`). For the new `@opentelemetry/*` packages: the newest release at least two weeks old when Task 5 starts, all from one release train; record the versions in the step-4 notes.
- Span names and attributes (agent-contract `StackAttributes`, mirrored as TS constants in the SDK): `stack.navigation` with `stack.ui.navigation.kind` (`load`|`route`), `stack.ui.url`, `stack.ui.route`; `stack.action` with `stack.ui.action.event` (`click`|`submit`), `stack.ui.action.target`, `stack.ui.route`. Both are INTERNAL spans.
- Browser globals: `window.__STACK__ = { scenario, run }` (set by the harness), `window.__STACK_FLUSH__(): Promise<void>` (set by the SDK).
- Target description: `data-testid` of the nearest ancestor-or-self of the event target that has one; else the accessible name (`aria-label`, else trimmed text content) of the nearest `button`, `a` or `form`, cut to 80 characters; else the target's lower-case tag name.
- CORS on `POST /v1/traces` only, origin patterns `http://localhost:[*]` and `http://127.0.0.1:[*]`, methods `POST`, header `Content-Type`.
- Ports: frontend `127.0.0.1:3000` → nginx `80`. Everything else as before.
- Kotlin test names: backtick sentences. TS test names: plain sentences. Test-first in every task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Work on branch `step-4-frontend`; push only when the owner asks; merge only after the owner has tried it (Task 9).

## Review Focus

1. **The test ends before the browser exported its spans** (batch export, page closed by the test). The scenarios must call `StackBrowser.flush(page)` before returning, and the SDK must also flush on `pagehide`. Pinned in Task 1 (`flush awaits the page's flush hook`), Task 5 (`spans are exported on pagehide`), Task 2/6 acceptance.
2. **The exporter's own requests must not be traced** (a fetch span per export, which exports again, forever) and must not get `baggage`. Pinned in Task 5 (`export requests to the reporter are not traced`).
3. **A reload of `/orders/42` in the browser** must load the app, not orders-service's JSON; the app's own `GET /orders/{id}` fetch must still reach orders-service. Pinned in Task 2 (acceptance `order page survives a reload`).
4. **A fetch before any ui step** (a component that fetches on import, a recording window opened mid-page) must not fail the flow: it is a top-level hop with no parent. Pinned in Task 7 (`a frontend fetch before any ui step has no parent`).
5. **A frontend whose fetch is observed before its ui events**, or a component seen only in `componentsSeen`, must still be typed `frontend`, never downgraded to `service`. Pinned in Task 7 (`frontend stays frontend whatever is observed first`).

---

## File Structure

```
sandbox/
  services/inventory-service/.../StockController.kt (NEW), StockRepository.kt (+findAll), Stock.kt (NEW)
  services/frontend/ (NEW)  package.json, package-lock.json, tsconfig.json, vite.config.ts, index.html, nginx.conf,
                            Dockerfile, build.gradle.kts, src/{main.tsx,App.tsx,api.ts,StockPage.tsx,OrderFormPage.tsx,OrderPage.tsx}
  settings.gradle.kts, build.gradle.kts (+stackBrowser), gradle/libs.versions.toml, docker-compose.yml, README.md
  e2e-tests/build.gradle.kts, src/main/.../FrontendScenarios.kt (NEW), src/test/.../FrontendE2ETest.kt (NEW),
           src/acceptanceTest/.../SandboxAcceptanceTest.kt
  stack/scenarios/{browse-stock,place-order-in-browser,place-order-out-of-stock-in-browser}.yaml (NEW)
tool/
  gradle/libs.versions.toml, settings.gradle.kts (+browser-sdk)
  agent-contract/  StackAttributes.java (+ stack.ui.*)
  test-harness/    StackBrowser.kt (NEW), StackBrowserTest.kt (NEW)
  model/           Events.kt (FRONTEND, UiNavigationEvent, UiActionEvent, v4), view/Views.kt (FlowStepKind.UI),
                   workspace/WorkspaceDefinitions.kt (ConnectionKind.UI), tests, schema snapshot
  reporter/        otlp/SpanEventMapper.kt (+uiDraft), CorsConfig.kt (NEW), tests, OtlpFixtures.kt, golden/place-order-in-browser/
  browser-sdk/ (NEW) package.json, package-lock.json, tsconfig.json, vite.config.ts, playwright.config.ts, build.gradle.kts,
                   src/{index.ts,attributes.ts,stackContext.ts,StackSpanProcessor.ts,StackBaggagePropagator.ts,
                        describeTarget.ts,navigation.ts,actions.ts}, src/*.test.ts, e2e/{fixture/index.html,fixture/main.ts,sdk.spec.ts}
  viewer-backend/  topology/{FlowBuilder,TopologyService}.kt, tests, fixtures/recordings/<run-6>/
  viewer-ui/       src/generated/model.ts, board/{icons.tsx,ConnectionEdge.tsx,HopDetails.tsx}, sequence/{layoutSequence.ts,MessageDetails.tsx},
                   status.ts, tests, e2e/fixtures/recordings/<run-6>/, e2e/frontend.spec.ts (NEW)
docs/integrate-a-project.md, docs/superpowers/specs/2026-10-27-stack-tool-design.md (§9, §11),
docs/superpowers/notes/2026-10-31-step-4-rulings-and-followups.md (NEW)
```

---

### Task 1: Test harness — `StackBrowser`

**Files:**
- Create: `tool/test-harness/src/main/kotlin/io/stackvibed/harness/StackBrowser.kt`, `tool/test-harness/src/test/kotlin/io/stackvibed/harness/StackBrowserTest.kt`
- Modify: `tool/test-harness/build.gradle.kts` (`compileOnly(libs.playwright)`, `testImplementation(libs.playwright)`, `testImplementation(libs.mockk)`), `tool/gradle/libs.versions.toml` (`playwright = "1.64.0"`, library `com.microsoft.playwright:playwright`)

**Interfaces:**
- Consumes: `StackContext.current(): ScenarioRun?`, `StackContext.begin/end` (internal, usable from the module's tests).
- Produces: `object StackBrowser { fun tag(page: Page); fun flush(page: Page) }`. `tag` calls `page.addInitScript("window.__STACK__ = {\"scenario\":\"<id>\",\"run\":\"<uuid>\"};")`; outside a scenario it throws `IllegalStateException("StackBrowser.tag needs an active @StackScenario")`. `flush` calls `page.evaluate("() => window.__STACK_FLUSH__ ? window.__STACK_FLUSH__() : null")`.

- [ ] **Step 1: Failing tests** (MockK `Page`): `` `tag adds an init script with the current scenario and run` `` (inside `StackContext.begin(ScenarioRun("browse-stock", "r-1"))`, the captured script equals `window.__STACK__ = {"scenario":"browse-stock","run":"r-1"};`); `` `tag outside a scenario fails` ``; `` `flush awaits the page's flush hook` `` (verifies `evaluate` with the exact string above).
- [ ] **Step 2: Run** `tool/gradlew -p tool :test-harness:test`. Expected: FAIL (no `StackBrowser`).
- [ ] **Step 3: Implement.** Ids are slugs and UUIDs (validated by `StackScenarioExtension`), so plain interpolation is safe; say so in a comment.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(harness): StackBrowser tags a Playwright page with the current scenario`

---

### Task 2: Sandbox — `GET /stock`, the frontend, browser scenarios, failing acceptance test

**Files:**
- Create: `sandbox/services/inventory-service/src/main/kotlin/io/stackvibed/sandbox/inventory/{StockController.kt,Stock.kt}`
- Modify: `…/inventory/StockRepository.kt`
- Create: everything under `sandbox/services/frontend/` (see File Structure), `sandbox/e2e-tests/src/main/kotlin/io/stackvibed/sandbox/e2e/FrontendScenarios.kt`, `sandbox/e2e-tests/src/test/kotlin/io/stackvibed/sandbox/e2e/FrontendE2ETest.kt`, `sandbox/stack/scenarios/{browse-stock,place-order-in-browser,place-order-out-of-stock-in-browser}.yaml`
- Modify: `sandbox/settings.gradle.kts` (`include("services:frontend")`), `sandbox/gradle/libs.versions.toml` (`node-gradle = "7.1.0"`, `nodejs = "22.23.4"`, `playwright = "1.64.0"`, plugin `com.github.node-gradle.node`, library playwright), `sandbox/e2e-tests/build.gradle.kts` (`implementation(libs.playwright)`; `acceptanceTest` also depends on `:services:frontend:assemble`), `sandbox/docker-compose.yml`, `…/SandboxAcceptanceTest.kt`

**Interfaces:**
- Consumes: `StackBrowser.tag/flush` (Task 1).
- Produces (inventory): `data class Stock(val sku: String, val available: Int)`; `StockRepository.findAll(): List<Stock>` (`select sku, available from stock order by sku`); `@RestController @RequestMapping("/stock") class StockController(stock: StockRepository)` with `@GetMapping fun list(): List<Stock>`.
- Produces (frontend): routes `/` (`StockPage`: a `table` with one row per SKU, link `data-testid="to-order"` text "Place an order"), `/order` (`OrderFormPage`: `form data-testid="order-form"` with inputs `customerId`, `sku`, `quantity` and a submit button "Place order"; `201` → `navigate('/orders/' + id)`; `409` → an element `role="alert"` with text "Out of stock"), `/orders/:id` (`OrderPage`: shows id, SKU, quantity, status in a `dl data-testid="order"`). `api.ts`: `getStock()`, `createOrder(newOrder)`, `getOrder(id)`, all `fetch` with `Accept: application/json` to same-origin paths `/stock`, `/orders`, `/orders/{id}`.
- Produces (nginx.conf): SPA fallback `try_files $uri /index.html`; `location = /stock` → `proxy_pass http://inventory-service:8081`; `location /orders` → `proxy_pass http://orders-service:8080`, except when `$http_accept` contains `text/html`, which serves `/index.html`. `vite.config.ts` has the same two proxies to `localhost:8081`/`localhost:8080`.
- Produces (build): `services/frontend/build.gradle.kts` with the Node plugin (`download = true`, `version = libs.versions.nodejs`), `npmBuild` (`npm run build` → `dist/`), `assemble` depends on it. Dockerfile: `FROM nginx:1.27-alpine`, `COPY nginx.conf /etc/nginx/conf.d/default.conf`, `COPY dist/ /usr/share/nginx/html/`.
- Produces (compose): service `frontend` (`build: ./services/frontend`, `127.0.0.1:3000:80`, depends on both services `service_healthy`, healthcheck `wget -q -O /dev/null http://127.0.0.1/`, interval 2s, retries 30).
- Produces (e2e): `class FrontendScenarios(private val page: Page, private val baseUrl: String)` with `browseStock()`, `placeOrder(): String` (returns the order id read from the URL), `placeOrderOutOfStock()`; each asserts what the user sees (Playwright `assertThat`) and ends with `StackBrowser.flush(page)`. `FrontendE2ETest` owns one `Playwright`/`Browser` (`@BeforeAll`), a fresh `BrowserContext` and `Page` per test, calls `StackBrowser.tag(page)` first, base URL `http://localhost:3000`; tests `browse stock`, `place order in browser`, `place order out of stock in browser`, tagged with the three scenario ids.
- Produces (workspace): one yaml per scenario with `id` and a one-sentence `description` of the journey from spec §2.5.

- [ ] **Step 1: Failing acceptance tests.** In `SandboxAcceptanceTest`: a `Playwright`/`Browser` in `@BeforeAll` (closed in `@AfterAll`), `FRONTEND_URL = "http://localhost:3000"`; on Docker add `withExposedService("frontend", 80, Wait.forHttp("/"))`; on Podman `awaitHttp(FRONTEND_URL)`. New tests:
  - `` `browsing stock is recorded from the browser` `` (`browse-stock`): `awaitRecording(minEvents = 4)`; one `ui.navigation` from `frontend` with `kind == "load"` and `route == "/"`; one `http.client` from `frontend` with `method == "GET"` and `url` ending `/stock`; the inventory-service `http.server` `GET /stock` has `status == 200`, `responseBody` containing `sku-1` and `parentSpanId == ` that client's `spanId`; `componentsSeen` contains `frontend` and `inventory-service`.
  - `` `placing an order is recorded from the browser` `` (`place-order-in-browser`, `minEvents = 16`): `ui.navigation` routes `["/", "/order", "/orders/<id>"]` in start order (kinds `load`, `route`, `route`); `ui.action` events include `click` on `to-order` and `submit` on `order-form`; frontend `http.client`s are `GET …/stock`, `POST …/orders` (status 201), `GET …/orders/<id>` (status 200); the orders-service `POST /orders` server event's `parentSpanId` is the frontend POST's `spanId`; the inventory-service `/reservations` server event is in the same run.
  - `` `an out-of-stock order is recorded from the browser` `` (`place-order-out-of-stock-in-browser`): frontend `POST …/orders` client has status 409, the orders-service server event 409, no `db.client` with a statement starting `insert into orders`.
  - `` `order page survives a reload` `` (untagged): place an order through `FrontendScenarios`, `page.reload()`, the order `dl` is still visible (nginx served the app, the app's fetch reached orders-service).
- [ ] **Step 2: Build the sandbox pieces** (inventory endpoint, frontend app without any tool code, nginx, compose, scenarios, `FrontendScenarios`, `FrontendE2ETest`, scenario yaml files).
- [ ] **Step 3: Run and see the expected red.**

Run: `tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage && sandbox/gradlew -p sandbox stackAgent assemble acceptanceTest`
Expected: the stack starts, the frontend works (the reload test and every user-visible assertion in `FrontendScenarios` pass); the three recording tests FAIL in `awaitRecording` (no run: nothing tags the services' spans, no browser spans exist). The existing three tests pass.

- [ ] **Step 4: Commit** — `feat(sandbox): frontend SPA and GET /stock; failing step 4 acceptance tests`

---

### Task 3: Model — `frontend` type, ui events, ui step and connection kinds, schema version 4

**Files:**
- Modify: `tool/model/src/main/kotlin/io/stackvibed/model/{Events.kt,view/Views.kt,workspace/WorkspaceDefinitions.kt}`, `tool/model/src/test/kotlin/io/stackvibed/model/{Samples.kt,EventJsonTest.kt}`, `src/test/resources/schema-snapshot/model.schema.json`
- Modify: `tool/agent-contract/src/main/java/io/stackvibed/agent/contract/StackAttributes.java`
- Modify: `tool/viewer-ui/src/generated/model.ts` (`npm run gen:types`); compile-only fixes in `FlowBuilder.kt`, `TopologyService.kt`, viewer-ui `status.ts`/`MessageDetails.tsx`/`HopDetails.tsx`/`icons.tsx`, reporter golden files (`"schemaVersion":4`)

**Interfaces:**
- Produces:

```kotlin
const val SCHEMA_VERSION = 4   // KDoc: "version 4 added [UiNavigationEvent] and [UiActionEvent]"
enum class ComponentType { …, @JsonProperty("frontend") FRONTEND }
enum class UiNavigationKind { @JsonProperty("load") LOAD, @JsonProperty("route") ROUTE }
enum class UiActionKind { @JsonProperty("click") CLICK, @JsonProperty("submit") SUBMIT }
data class UiNavigationEvent(/* the 9 common fields */, val url: String, val route: String, val kind: UiNavigationKind) : RecordingEvent   // "ui.navigation"
data class UiActionEvent(/* the 9 common fields */, val event: UiActionKind, val target: String, val route: String) : RecordingEvent          // "ui.action"
enum class FlowStepKind { …, @JsonProperty("ui") UI }
enum class ConnectionKind { …, @JsonProperty("ui") UI }
```

- Produces (`StackAttributes`): `NAVIGATION_SPAN = "stack.navigation"`, `ACTION_SPAN = "stack.action"`, `UI_NAVIGATION_KIND`, `UI_URL`, `UI_ROUTE`, `UI_ACTION_EVENT`, `UI_ACTION_TARGET` with the names in Global Constraints, and a Javadoc paragraph saying the browser package writes them.

- [ ] **Step 1: Failing tests** in `EventJsonTest`: `` `ui navigation event round-trips with type discriminator` `` (`"type":"ui.navigation"`, `"kind":"route"`, `"schemaVersion":4`); `` `ui action event round-trips with type discriminator` `` (`"type":"ui.action"`, `"event":"submit"`); `` `version 3 lines still read` `` (a literal `method` line from `golden/create-order-methods/events.jsonl` reads with `schemaVersion == 3`); `` `frontend type and ui kinds serialise lowercase` ``.
- [ ] **Step 2: Run** `tool/gradlew -p tool :model:test`. Expected: FAIL.
- [ ] **Step 3: Implement**, refresh the snapshot as `SchemaSnapshotTest` says, regenerate `model.ts`. Keep everything compiling with no behaviour change: `FlowBuilder` maps ui events to `null` (no step yet); `TopologyService.observe` returns for `FlowStepKind.UI`; the UI's exhaustive switches get a ui branch that renders nothing new yet; `TypeIcon` falls through to the default for `frontend`.
- [ ] **Step 4: Run** `tool/gradlew -p tool :model:test :reporter:test :viewer-backend:test :viewer-ui:npmTest :viewer-ui:build`. Expected: PASS once the golden files read `"schemaVersion":4`.
- [ ] **Step 5: Commit** — `feat(model): frontend component, ui navigation and action events, schema version 4`

---

### Task 4: Reporter — browser spans and CORS

**Files:**
- Modify: `tool/reporter/src/main/kotlin/io/stackvibed/reporter/otlp/SpanEventMapper.kt`
- Create: `tool/reporter/src/main/kotlin/io/stackvibed/reporter/api/CorsConfig.kt`
- Modify: `tool/reporter/src/test/kotlin/io/stackvibed/reporter/otlp/{OtlpFixtures.kt,SpanEventMapperTest.kt}`, `ReporterGoldenTest.kt`
- Create: `tool/reporter/src/test/resources/golden/place-order-in-browser/{manifest.json,events.jsonl}`, `tool/reporter/src/test/kotlin/io/stackvibed/reporter/api/CorsTest.kt`

**Interfaces:**
- Consumes: Task 3 model and `StackAttributes`.
- Produces: `SpanEventMapper` maps INTERNAL spans named `StackAttributes.NAVIGATION_SPAN` to `UiNavigationEvent` (`url`/`route` from the attributes, empty string when absent; `kind` from `stack.ui.navigation.kind`, span dropped when it is neither `load` nor `route`) and named `ACTION_SPAN` to `UiActionEvent` (dropped when the event is neither `click` nor `submit`); other INTERNAL spans go to `methodDraft` as today. The browser's fetch CLIENT spans already map through `clientDraft` (they carry `http.request.method`, `url.full`, `http.response.status_code` because the SDK opts into stable HTTP semconv, Task 5).
- Produces: `@Configuration class CorsConfig : WebMvcConfigurer` with the mapping from Global Constraints. `OtlpFixtures.browserPlaceOrderRun(scenario = "place-order-in-browser", run = "run-6"): ExportTraceServiceRequest` — resource `service.name = frontend`, `telemetry.sdk.language = webjs`; spans: navigation load `/`, fetch `GET http://localhost:3000/stock` (200), navigation route `/order` (preceded by a click action on `to-order`), submit action on `order-form`, fetch `POST http://localhost:3000/orders` (201), navigation route `/orders/7c9e6679-7425-40de-944b-e07fc1f90ae7`, fetch `GET` of that path (200); plus one `documentFetch` INTERNAL span with no stack name (must be dropped).

- [ ] **Step 1: Failing tests.** `SpanEventMapperTest`: `` `browser navigation and action spans map to ui events` ``, `` `browser fetch maps to http client event of the frontend` `` (component `frontend`, url as sent, status), `` `unknown web spans are dropped` ``, `` `ui span with an unknown kind is dropped` ``. `ReporterGoldenTest`: `` `browser run matches golden recording` `` against `golden/place-order-in-browser`. `CorsTest` (`@SpringBootTest(RANDOM_PORT)`, `java.net.http`): `` `preflight from a localhost origin is allowed` `` (`OPTIONS /v1/traces`, `Origin: http://localhost:3000`, `Access-Control-Request-Method: POST`, `Access-Control-Request-Headers: content-type` → 200, `Access-Control-Allow-Origin: http://localhost:3000`); same for `http://127.0.0.1:5173`; `` `preflight from another origin is refused` `` (`http://example.com` → 403, no `Access-Control-Allow-Origin`); `` `control api has no cors` `` (preflight on `/api/recordings/start` from `http://localhost:3000` → no `Access-Control-Allow-Origin`).
- [ ] **Step 2: Run** `tool/gradlew -p tool :reporter:test`. Expected: FAIL.
- [ ] **Step 3: Implement**; write the golden files from the first green run only after reading them line by line against the fixture.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(reporter): map browser navigation and action spans; CORS for localhost on the OTLP endpoint`

---

### Task 5: Browser package — `@stackvibed/browser`

**Files:**
- Create: everything under `tool/browser-sdk/` (see File Structure)
- Modify: `tool/settings.gradle.kts` (`include("browser-sdk")`)

**Interfaces:**
- Produces (TS):

```ts
export interface StackRecordingOptions {
  component: string;                 // service.name, e.g. 'frontend'
  reporterUrl?: string;              // default 'http://localhost:4318'
  exporter?: SpanExporter;           // tests only; default OTLPTraceExporter({ url: reporterUrl + '/v1/traces' })
}
export function startStackRecording(options: StackRecordingOptions): void;   // idempotent: a second call is a no-op
// internal modules, each unit-tested:
export function currentRun(): { scenario: string; run: string } | null;      // stackContext.ts, from window.__STACK__
export class StackSpanProcessor implements SpanProcessor { }                  // sets stack.scenario / stack.run on start when currentRun() != null
export class StackBaggagePropagator implements TextMapPropagator { }          // injects 'baggage: stack.scenario=<id>,stack.run=<run>' when currentRun() != null
export function describeTarget(target: EventTarget | null): string;           // rule in Global Constraints
export function routeOf(location: Location): string;                          // location.pathname
```

- `startStackRecording`: `WebTracerProvider` with resource `service.name = component`; processors `StackSpanProcessor` then `BatchSpanProcessor(exporter, { scheduledDelayMillis: 200 })`; propagator `CompositePropagator([W3CTraceContextPropagator, StackBaggagePropagator])`; `FetchInstrumentation({ semconvStabilityOptIn: 'http', ignoreUrls: [reporterUrl prefix] })`; `navigation.ts` starts a `stack.navigation` `load` span with `startTime = performance.timeOrigin` ended at the window `load` event (immediately when `document.readyState === 'complete'`), and a zero-length `route` span after every `pushState`/`replaceState`/`popstate` that changes `location.pathname`; `actions.ts` adds capture-phase `click` and `submit` listeners on `document`, each a zero-length `stack.action` span; `window.__STACK_FLUSH__ = () => provider.forceFlush()`; `pagehide` calls it. Export failures: one `console.warn('stack recording: export to <url> failed')` per page, nothing thrown.
- Produces (Gradle, like `viewer-ui`): `npmBuild` (`tsc -p tsconfig.json` → `dist/` ESM + `.d.ts`), `npmTest` (Vitest, jsdom), `playwrightInstall`, `e2eTest` (Playwright), `npmPack` (`npm pack --pack-destination build/npm` → `stackvibed-browser-0.1.0.tgz`); `check` depends on `npmTest` and `e2eTest`. `maven-publish` publication `browser`: `groupId = "io.stackvibed"`, `artifactId = "stack-browser"`, `version = "0.1.0-SNAPSHOT"`, artifact = the `npmPack` output with `extension = "tgz"`, so `publishToMavenLocal` publishes it.

- [ ] **Step 1: Failing unit tests** (Vitest): `currentRun reads window.__STACK__ and is null without it`; `processor tags spans when a run is known and leaves them alone otherwise`; `propagator injects stack baggage and nothing without a run`; `describeTarget prefers the nearest data-testid` (a `span` inside `button[data-testid=place-order]` → `place-order`); `describeTarget falls back to the button text, cut to 80 characters`; `describeTarget falls back to the tag name`.
- [ ] **Step 2: Failing browser tests** (`e2e/sdk.spec.ts`; the fixture page calls `startStackRecording({ component: 'fixture', exporter: new InMemorySpanExporter() })`, exposes `window.__SPANS__()` returning the finished spans as plain objects, and has a `button[data-testid=go]` that does `history.pushState({}, '', '/next')` then `fetch('/api/echo')`; `page.route('**/api/echo')` answers 200 and records the request headers):
  - `page load is one navigation span` (`stack.navigation`, kind `load`, route `/`)
  - `a click records an action, a route change and a traced fetch` (`stack.action` `click` target `go`; `stack.navigation` `route` `/next`; a CLIENT span with `http.request.method = GET` and `http.response.status_code = 200`; the echo request carried `traceparent`)
  - `a tagged page puts the run on every span and on fetch baggage` (init script sets `window.__STACK__`; every span has `stack.scenario`/`stack.run`; echo request header `baggage` = `stack.scenario=s-1,stack.run=r-1`)
  - `export requests to the reporter are not traced` (options `{ component: 'fixture', exporter: inMemory, reporterUrl: 'http://reporter.test' }`; the page tagged with `window.__STACK__`; `fetch('http://reporter.test/v1/traces', { method: 'POST' })`, routed to 200 → no CLIENT span for that URL in `__SPANS__()`, and the routed request carried neither `traceparent` nor `baggage`)
  - `the real exporter posts protobuf to the reporter` (no `exporter` option, `reporterUrl` routed; click `go`, `await __STACK_FLUSH__()` → at least one request to `/v1/traces` with `Content-Type: application/x-protobuf`)
  - `spans are exported on pagehide` (routed reporter; `page.close({ runBeforeUnload: true })` → one export request received)
  - `a failing reporter only warns` (routed reporter answers 503; the page keeps working; exactly one `console.warn` starting `stack recording:`)
- [ ] **Step 3: Run** `tool/gradlew -p tool :browser-sdk:check`. Expected: FAIL.
- [ ] **Step 4: Implement**, pinning the `@opentelemetry/*` versions per Global Constraints.
- [ ] **Step 5: Run** `tool/gradlew -p tool :browser-sdk:check :browser-sdk:publishToMavenLocal` and check `~/.m2/repository/io/stackvibed/stack-browser/0.1.0-SNAPSHOT/` holds a `.tgz`. Expected: PASS.
- [ ] **Step 6: Commit** — `feat(browser-sdk): record page loads, user actions and fetches from the browser`

---

### Task 6: Sandbox — record the frontend; acceptance turns green

**Files:**
- Modify: `sandbox/build.gradle.kts` (configuration `stackBrowserTgz`, dependency `libs.stack.browser` with `artifact { type = "tgz" }`, `Sync` task `stackBrowser` → `build/stack-browser/stack-browser.tgz`), `sandbox/gradle/libs.versions.toml` (`stack-browser = { module = "io.stackvibed:stack-browser", version.ref = "stack-tool" }`), `sandbox/services/frontend/build.gradle.kts` (NpmTask `installStackBrowser`: `npm install --no-save ../../build/stack-browser/stack-browser.tgz`, depends on `npmInstall` and `:stackBrowser`; `npmBuild` depends on it), `sandbox/services/frontend/src/main.tsx`, `sandbox/README.md` (Run it: `stackAgent stackBrowser assemble`)

**Interfaces:**
- Consumes: `startStackRecording` (Task 5), ui events (Tasks 3–4), `StackBrowser` (Task 1).
- Produces: `main.tsx` calls `startStackRecording({ component: 'frontend' })` before `createRoot(...)`. A comment in `build.gradle.kts` says why the tarball is installed `--no-save` (Ruling F3).

- [ ] **Step 1: Wire the SDK in.**
- [ ] **Step 2: Run the acceptance test.**

Run: `tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage && sandbox/gradlew -p sandbox stackAgent stackBrowser assemble acceptanceTest`
Expected: all seven tests PASS. If the `minEvents` counts are off against the real recording, set them from it and record the counts in the step-4 notes (as Ruling M5 did in step 3).

- [ ] **Step 3: Rebuild check.** Change nothing, run `tool/gradlew -p tool :browser-sdk:publishToMavenLocal && sandbox/gradlew -p sandbox :services:frontend:assemble` twice. Expected: both succeed (no npm integrity error).
- [ ] **Step 4: Commit** — `feat(sandbox): the frontend records through @stackvibed/browser; step 4 acceptance passes`

---

### Task 7: Viewer backend — ui steps, frontend topology

**Files:**
- Modify: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/topology/{FlowBuilder,TopologyService}.kt`, `tool/viewer-backend/src/test/kotlin/io/stackvibed/viewer/topology/{FlowBuilderTest,TopologyServiceTest}.kt`
- Create: `tool/viewer-backend/src/test/resources/fixtures/recordings/2026-10-31T10-00-00_place-order-in-browser_run-6/{manifest.json,events.jsonl}` — the reporter's golden `place-order-in-browser` events plus the service-side events of a `create-order` run (from run-5) re-parented so the orders-service `POST /orders` server span's parent is the frontend POST fetch, an inventory-service `GET /stock` server event under the frontend GET fetch, and an orders-service `GET /orders/{id}` under the last fetch.

**Interfaces:**
- Consumes: Task 3 model.
- Produces (`FlowBuilder`, add to its KDoc rule list):
  - `UiNavigationEvent` → step `from = e2e-client`, `to = alias(component)`, `kind = UI`, `parentStepId = null`, `status = null`, label `load <route>` for `LOAD` and `→ <route>` for `ROUTE`.
  - `UiActionEvent` → same shape, label `<event> <target>` (`submit order-form`).
  - `CallTree.parentStepOf(client)` for an `HttpClientEvent` whose component has ui events in the run: the ui event whose span is its parent span, else the latest ui event of the same component with `startEpochNanos <= client.startEpochNanos` (ties: the later in the list), else null. An answered frontend fetch's callee step therefore nests under that ui step, exactly as a service-to-service call nests under its caller's step.
  - An unanswered `HttpClientEvent` of such a component: `to = ComponentIds.slug(first path segment of url)`, else `unknown-host`.
- Produces (`TopologyService`): ui steps give `ConnectionKind.UI` connections; their label is the distinct `route`s of the ui events with every path segment that is all digits or a UUID replaced by `{id}`, sorted, joined with `, `. A ui step's `to` is typed `FRONTEND`; `FRONTEND` wins over `SERVICE` in both merges (like `DATABASE` today), including in `curatedEndpoints` for a curated `ui` connection's `to`.

- [ ] **Step 1: Failing tests.** `FlowBuilderTest` on run-6: `` `browser run starts with ui steps from the e2e client` `` (first step `load /`, `kind == UI`, `from == e2e-client`, `to == frontend`, `connectionId == "e2e-client--frontend"`, `parentStepId == null`); `` `fetch callee nests under the latest ui step before it` `` (inventory-service `GET /stock` step's parent is the `load /` step; orders-service `POST /orders` step's parent is the `submit order-form` step, `from == frontend`); `` `fetch callee nests under its parent ui span when there is one` `` (in-test events: a fetch whose `parentSpanId` is an earlier action than the latest one); `` `a frontend fetch before any ui step has no parent` ``; `` `unanswered frontend fetch is a failed hop to its path` `` (`url = http://localhost:3000/orders`, no status, `errorType = "TypeError"` → `to == "orders"`). `TopologyServiceTest`: `` `browser run discovers frontend and its connections` `` (`frontend` has type `FRONTEND`; connections `e2e-client--frontend` kind `UI` labelled `/, /order, /orders/{id}`, `frontend--inventory-service` labelled `GET /stock`, `frontend--orders-service` labelled `POST /orders, GET /orders/{id}`); `` `frontend stays frontend whatever is observed first` `` (in-test run whose fetch starts before its first ui event).
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-backend:test`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS, existing tests unchanged.
- [ ] **Step 5: Commit** — `feat(viewer): ui steps from the e2e client; frontend fetches nest under them`

---

### Task 8: Viewer UI — frontend node, ui steps and details

**Files:**
- Modify: `tool/viewer-ui/src/board/{icons.tsx,ConnectionEdge.tsx,HopDetails.tsx}`, `src/sequence/{layoutSequence.ts,MessageDetails.tsx}`, `src/status.ts`, their tests, `src/board/testData.ts`
- Create: `tool/viewer-ui/e2e/fixtures/recordings/2026-10-31T10-00-00_place-order-in-browser_run-6/` (byte-identical copy of Task 7's), `tool/viewer-ui/e2e/frontend.spec.ts`

**Interfaces:**
- Consumes: `FlowStepKind 'ui'`, `ComponentType 'frontend'`, `ConnectionKind 'ui'`, `UiNavigationEvent`, `UiActionEvent` from `model.ts`.
- Produces: `TypeIcon` case `'frontend'` (a browser window: frame, top bar with three dots); `layoutSequence` pushes no response row for `kind === 'ui'` (Ruling F4); `isFailedStep` is false and `responseText` is `''` for ui steps; `MessageDetails` and `HopDetails` show, for `ui.action`: Event, Target, Route; for `ui.navigation`: URL, Kind (`page load` / `route change`), Duration (`formatMillis`).

- [ ] **Step 1: Failing tests.** Vitest: `frontend node shows the browser icon` (`ComponentNode` with `type: 'frontend'` renders an `svg` distinct from the service one; assert on a `data-icon="frontend"` attribute added to each icon); `ui step has a request arrow and no response` (layout of `[uiStep, httpStep]` has 3 messages, the first `kind === 'request'` labelled `submit order-form`); `ui steps never fail`; `details of an action show event target and route`; `details of a navigation show url kind and duration`. Playwright `frontend.spec.ts` on run-6: `board shows the frontend between the e2e client and the services` (picking `place-order-in-browser` highlights `e2e-client--frontend`, `frontend--orders-service`, `frontend--inventory-service`); `sequence shows the browser steps first` (heads start `e2e-client`, `frontend`; first message `request load /`; clicking `request submit order-form` shows Target `order-form` in Message details); `frontend has no class toggle` (no `▸` button in the frontend head; orders-service still has `▸ 4 classes`).
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-ui:npmTest :viewer-ui:e2eTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `tool/gradlew -p tool build`. Expected: PASS (the whole tool build).
- [ ] **Step 5: Commit** — `feat(viewer-ui): frontend node, ui steps and their details`

---

### Task 9: Docs, notes, and the owner's try-out

**Files:**
- Modify: `sandbox/README.md`, `docs/integrate-a-project.md`, `docs/superpowers/specs/2026-10-27-stack-tool-design.md` (§9 component types + `frontend`; §11 "Step 4 — a browser frontend" pointing to the step-4 spec)
- Create: `docs/superpowers/notes/2026-10-31-step-4-rulings-and-followups.md`

- [ ] **Step 1: README.** Intro and path table: `services/frontend`, `GET /stock`, the three browser scenarios, port 3000 in the ports line and in Troubleshooting "Port already in use"; Run it step 2 becomes `sandbox/gradlew -p sandbox stackAgent stackBrowser assemble`; a paragraph "Recording your own clicks": open http://localhost:3000, wrap the clicks in the recording window `curl` calls already shown, scenario `place-order-in-browser`; note that Playwright downloads Chromium on the first `e2eTest`.
- [ ] **Step 2: Integration guide.** A "Frontends" section: install the tarball, call `startStackRecording({ component })` before rendering, same-origin API calls (cross-origin fetches get no `traceparent`/`baggage`), the reporter's CORS origins, `StackBrowser.tag(page)` and `StackBrowser.flush(page)` in Playwright tests.
- [ ] **Step 3: Notes.** Rulings F1–F4 in the step-3 notes' format (what, why, cost if wrong), plus the pinned `@opentelemetry/*` versions and the acceptance counts; deferred follow-ups: cross-origin APIs, frameworks other than React, Firefox/WebKit, JS errors and console logs as events, a "user" actor distinct from the e2e client.
- [ ] **Step 4: Verify** `tool/gradlew -p tool build` and `sandbox/gradlew -p sandbox stackAgent stackBrowser acceptanceTest`. Expected: PASS.
- [ ] **Step 5: Commit** — `docs: step 4 frontend in the README, integration guide and notes`
- [ ] **Step 6: Owner try-out (do not merge before their OK).** Start the stack (`docker compose -f sandbox/docker-compose.yml up -d --build --wait`), run `sandbox/gradlew -p sandbox e2eTest`, start the viewer on `sandbox/stack` and `sandbox/recordings`, open http://localhost:8090 and http://localhost:3000 for the owner, and wait for their approval.
