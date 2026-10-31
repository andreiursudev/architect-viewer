# Step 4 — a browser frontend, recorded inside the browser

Date: 2026-10-31
Status: approved in conversation, written spec for review
Parent spec: `2026-10-27-stack-tool-design.md` (goals 1, 3 and 4, D1, D3, D8, D9, D13, D24)

## 1. Intent

The owner asked for a frontend web app in the sandbox, visible in the viewer. Today the only outside caller the tool
knows is the e2e client: every server request with no recorded parent is drawn from `e2e-client`. This step adds the
first non-JVM component and records what happens inside the browser, not only its HTTP calls.

Success: the board shows `frontend` calling `orders-service` and `inventory-service`; opening a
`place-order-in-browser` run in the sequence view shows the page loads and user actions in the browser (load `/`,
→ `/order`, submit the order form), the fetch each action triggered nested under it, and from there the existing hops
through orders-service, inventory-service, the databases and (expanded) their classes and methods.

Decided with the owner:

- **Inside the frontend**: page loads, SPA route changes, user actions (click, submit) and the fetches they trigger are
  recorded and drawn as steps.
- **Runs**: tagged Java Playwright tests in `sandbox/e2e-tests`, and manual clicking in the owner's own browser inside
  a reporter recording window (D8 layer 2).
- **Stack**: React + TypeScript + Vite single-page app, built to static files, served by nginx.
- **Features**: a stock list (from inventory-service, through a new `GET /stock`), an order form, an order page. The
  browser calls two backends.
- **Capture approach A**: the OpenTelemetry web SDK, wrapped in our own browser package, exports OTLP to the reporter,
  which maps browser spans to our own model events (D1: the viewer never sees OTel). Rejected: recording from the
  Playwright side (records what the test did, not what the app did, and nothing for manual runs) and an instrumented
  proxy (HTTP only, nothing inside the browser).
- **CORS on the reporter** rather than proxying the export through the frontend's server, so any frontend dev setup
  (nginx, Vite dev server) works without a project-side proxy route.
- The existing API-level scenarios and `e2e-client` stay unchanged.

## 2. Sandbox

### 2.1 `services/frontend`

React + TS + Vite (Node 22), React Router. Knows nothing about the tool except one call at startup:
`startStackRecording({ component: 'frontend' })` (§3.1). Routes:

| Route | What it does |
|---|---|
| `/` | Stock list: `GET /stock`, one row per SKU with its quantity; a link to the order form |
| `/order` | Form (customer, SKU, quantity), `data-testid="order-form"`; submit sends `POST /orders`; `201` → navigate to `/orders/{id}`; `409` → shows "out of stock" on the form |
| `/orders/:id` | `GET /orders/{id}`, shows the order |

Built by the sandbox Gradle build (Gradle Node plugin with its own Node download, as `tool/viewer-ui`) into
`services/frontend/dist`; `assemble` includes it. The Dockerfile only copies `dist/` and an `nginx.conf` into
`nginx:1.27-alpine`, the way the service Dockerfiles copy their jars.

### 2.2 Same-origin API through nginx

`nginx.conf` serves the SPA (unknown paths fall back to `index.html`) and proxies, with paths unchanged:

- `/orders` and `/orders/…` → `http://orders-service:8080`, except `GET` requests that accept `text/html` (a reload of
  `/orders/42` in the browser), which get `index.html`.
- `/stock` → `http://inventory-service:8081`.

The services need no CORS; `traceparent` and `baggage` reach them untouched. nginx is not instrumented and not a
component: the hop is frontend → service. `vite.config.ts` has the same proxy (to `localhost:8080`/`8081`) for running
the app with `npm run dev` outside compose.

### 2.3 inventory-service: `GET /stock`

`StockController.list()` → `StockRepository.findAll()` → `[{ "sku": "sku-1", "quantity": … }, …]`, ordered by SKU,
from the existing stock table. Covered by method capture (`io.stackvibed.sandbox.inventory`).

### 2.4 Compose

New service `frontend`: `build: ./services/frontend`, port `127.0.0.1:3000:80`, `depends_on` both services healthy.
Healthcheck: `wget -q -O /dev/null http://127.0.0.1/` (static, not traced). The frontend reaches the reporter from the
host's browser, at `http://localhost:4318`, which is already published.

### 2.5 Scenarios and tests

Workspace scenarios (`stack/scenarios/`):

| Scenario | Journey |
|---|---|
| `browse-stock` | Open `/`, see the stock list |
| `place-order-in-browser` | Open `/`, follow the link to `/order`, submit `c-1`/`sku-1`/`2`, see the order page |
| `place-order-out-of-stock-in-browser` | Open `/order`, submit `sku-out`, see "out of stock" |

`FrontendE2ETest` (JUnit 5, Java Playwright with Chromium, `@StackScenario`) drives them against
`http://localhost:3000`, calling `StackBrowser.tag(page)` from the test harness (§3.2). `e2eTest` runs it with
`OrdersE2ETest`. Playwright downloads its Chromium on the first run.

`SandboxAcceptanceTest` starts the frontend too and asserts, per browser run (§6): `ui.navigation` and `ui.action`
events from `frontend`; the fetches as `http.client` events from `frontend`; the services' server events in the same
run with the fetch span as parent; `409` on the out-of-stock fetch and no order stored.

## 3. Capture

### 3.1 `tool/browser-sdk` (`@stackvibed/browser`)

A TypeScript package in the tool build, built with the Gradle Node plugin like `viewer-ui`. Published as an npm tarball
wrapped in a Maven artifact (`io.stackvibed:stack-browser:<version>`, classifier none, extension `tgz`) by
`publishToMavenLocal`. A new sandbox task `stackBrowser` copies it to `sandbox/build/stack-browser/stack-browser.tgz`
(as `stackAgent` copies the agent jars), and the frontend's `package.json` depends on
`"@stackvibed/browser": "file:../../build/stack-browser/stack-browser.tgz"`. The frontend's `npmInstall` depends on
`stackBrowser`.

API: `startStackRecording({ component, reporterUrl = 'http://localhost:4318' })`, called once before React renders.
It sets up the OTel web tracer provider (OTel JS versions pinned in the package) with:

- **service name** = `component` (the reporter's component id, as `OTEL_SERVICE_NAME` for the agents).
- **document-load** instrumentation: one span per page load.
- **user-interaction** instrumentation for `click` and `submit`, with `ZoneContextManager`. The target is described
  by `data-testid`, else the accessible name (`aria-label`, then the button/link text, trimmed to 80 characters), else
  the tag name.
- **fetch** instrumentation: client spans; `traceparent` and `baggage` on same-origin requests only.
- **route changes**: a `stack.navigation` span on every `history.pushState`/`replaceState`/`popstate` that changes the
  path (SPA navigations have no document load).
- **stack attributes**: a span processor sets `stack.scenario` and `stack.run` on every span when known, and the
  baggage propagator puts them on fetch `baggage`, so the services' spans join the same run (the same names the test
  harness and agent extension use).
- **export**: batch span processor, OTLP/HTTP protobuf, to `reporterUrl` + `/v1/traces`.

Where the scenario comes from: `window.__STACK__ = { scenario, run }` when set before the app loads (§3.2); absent in
manual runs, where the reporter's recording window assigns the spans.

### 3.2 Test harness: `StackBrowser`

`StackBrowser.tag(page: com.microsoft.playwright.Page)` adds an init script setting `window.__STACK__` to the
scenario and run of the current test, which `@StackScenario` already provides; it fails when called outside a tagged
test. Call it before the first `page.navigate`. Playwright is a `compileOnly` dependency of the
harness, so API-only users do not get it.

### 3.3 Reporter

- **CORS** on `POST /v1/traces` only: preflight (`OPTIONS`) and `POST` allowed for origins matching
  `http://localhost:<port>` and `http://127.0.0.1:<port>`; `Content-Type` allowed as a request header. Other origins
  get no CORS headers (the browser blocks them); the control API stays without CORS.
- **Mapping** of spans whose resource has `telemetry.sdk.language = webjs`:

| Browser span | Model event |
|---|---|
| document-load root span (`documentLoad`) | `ui.navigation`, kind `load` |
| `stack.navigation` | `ui.navigation`, kind `route` |
| user-interaction span (`event_type` `click`/`submit`) | `ui.action` |
| fetch client span | `http.client` (existing), component = the service name |

Other web spans (document-fetch, resource-fetch children) are dropped, as unrecognised JVM spans are. Scenario
assignment is unchanged (baggage attributes first, then the recording window, then `_unassigned`).

## 4. Model

`schemaVersion` 4; readers accept 1–4. JSON-schema snapshot and generated TS types updated.

- `ComponentType.FRONTEND` (`"frontend"`).
- `UiNavigationEvent` (`ui.navigation`): common event fields (ids, trace/span/parent, component, timestamp,
  duration), `url`, `route` (the path), `kind` (`load | route`).
- `UiActionEvent` (`ui.action`): common fields, `event` (`click | submit`), `target`, `route`.
- Browser fetches are `HttpClientEvent`s. No browser-side body capture: the callee records the bodies (step 2).

## 5. Viewer

### 5.1 Topology (`TopologyService`)

- A component that emits `ui.*` events is discovered as type `frontend` (not the `service` default).
- Its answered fetches give `frontend → orders-service` and `frontend → inventory-service` `http` connections,
  labelled with the server routes as today (`GET /orders/{id}, POST /orders`; `GET /stock`).
- Its ui events give one `e2e-client → frontend` connection, kind `ui` (new `ConnectionKind.UI`), labelled with the
  routes seen, numeric and UUID path segments shown as `{id}`, sorted (`/, /order, /orders/{id}`).
- Discovered and accepted like the others (D24); new nodes are placed by the existing auto-layout, saved positions
  stay.

### 5.2 Flow (`FlowBuilder`)

- Each `ui.navigation` and `ui.action` is a step from `e2e-client` to the frontend: `load /`, `→ /order`,
  `submit order-form`, `click place-order`.
- A frontend fetch nests under the ui step whose span is its parent. A fetch with no ui parent nests under the latest
  ui step of the same component that started before it (fallback when zone.js loses the context across native
  `async`/`await`; see §7).
- The fetch is the hop frontend → service; everything below it follows the existing rules (server, database and
  method steps, failed hops).
- An unanswered frontend fetch (network error, nginx `502`) is a failed hop to a component named after the target
  path's first segment on the frontend's own origin (`/orders` → `orders`), not after `localhost:3000`.

### 5.3 UI

- Board: a node style for `frontend` (distinct icon/shape); `ui` connections drawn like `http` ones.
- Sequence view: a frontend lifeline; ui steps as arrows from the e2e client; no `▸ N classes` toggle on the frontend
  (level 2 is JVM-only, parent spec §3).
- Details panel: `ui.action` shows event, target, route; `ui.navigation` shows URL, kind, duration.

## 6. Error handling

- **Reporter unreachable from the browser**: the SDK drops the failed batch, logs one `console.warn` per page load, and
  keeps exporting later batches. The app is never affected.
- **No recording window, no tag**: browser spans go to `recordings/_unassigned`, as untagged JVM traffic does.
- **Fetch never reaches a service**: unanswered `http.client`, failed hop (§5.2).
- **Disallowed origin**: no CORS headers; the browser drops the export.
- **Unknown web span shapes**: dropped by the mapping.

## 7. Testing (test-first)

1. **Spikes first** (throwaway, results recorded in the step-4 notes):
   - does `ZoneContextManager` parent fetches to the click/submit span with React 19 and native `async`/`await` in a
     Vite build? This decides whether the §5.2 fallback is the main path. If it does not, the SDK may instead lower
     the target (`es2016`) for the app, or rely on the fallback; the spike picks one.
   - browser → reporter export through CORS from `localhost:3000`.
2. **Acceptance**: `SandboxAcceptanceTest` extended (§2.5), red first.
3. **browser-sdk**: Vitest for the stack span processor, baggage and target description; Playwright against a fixture
   page with a fake OTLP receiver, asserting the exact spans (load, route change, click/submit, fetch with its parent,
   `stack.*` attributes).
4. **Reporter**: golden-file tests from recorded browser OTLP payloads; CORS preflight and origin tests.
5. **Viewer backend**: `TopologyService` (frontend type, `ui` connection and label) and `FlowBuilder` (ui steps,
   nesting by parent and by fallback, unanswered frontend fetch) unit tests.
6. **Viewer UI**: Vitest for the node, lifeline and details; Playwright on a fixture workspace with a browser run.
7. **Contract**: schema snapshot change, `schemaVersion` 4.

## 8. Docs

- Sandbox README: the frontend, the new scenarios, `GET /stock`, port 3000, and a manual run in the owner's browser
  inside a recording window.
- `docs/integrate-a-project.md`: adding `@stackvibed/browser` to a frontend (the call, `window.__STACK__`, CORS
  origins, same-origin fetches for propagation).
- Parent spec §11: a step 4 entry pointing here; §9 component types gain `frontend`.

## 9. Out of scope

Browser-side body capture; frameworks other than a React SPA (the SDK is framework-neutral, but only React is tested);
Firefox/WebKit; console logs and JS errors as events; Web Vitals; level 2 inside the browser; a separate "user" actor
distinct from the e2e client.
