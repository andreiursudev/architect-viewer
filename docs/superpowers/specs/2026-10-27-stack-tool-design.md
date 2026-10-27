# Stack Tool — Design

Date: 2026-10-27
Status: draft for review

## 1. Intent

A personal tool for a senior Java/Kotlin engineer who works on microservice + event-driven systems on GCP/GKE. It should express and support *their* way of understanding a project: see the architecture, see how real flows move through it, and hold all the descriptive knowledge about the project in one place.

Two parts:

- **stack-reporter-vibed** — while the whole system runs locally (services, databases, Kafka, mocked 3rd parties) and e2e tests or manual interactions run against it, records the input and output of every component (level 1) and, optionally, of methods inside services (level 2).
- **stack-viewer-vibed** — turns recordings plus a hand-curated workspace into an interactive, Miro-like board and a set of views.

The tool is built **incrementally against a sample system** (the sandbox) that grows one component at a time: build a step, run it, validate it looks as desired, add the next component.

## 2. Goals

1. Record level-1 traffic (HTTP, Kafka, DB, mocked 3rd parties) across a locally running stack, grouped by scenario.
2. Record level-2 method I/O inside chosen services.
3. **Primary view**: all components on a draggable, zoomable board; pick a scenario and its path highlights with details.
4. **Sequence view**: full-detail sequence diagram of one scenario run.
5. **Notes**: free text attached to components, flows, and the board.
6. **Component view**: inside one component, the methods e2e scenarios called, plus unit/integration tests that touch them.
7. **Vertical layers** (security, logging, retry, caching, …) highlightable in primary and component views.
8. **Infrastructure view**: Kubernetes/GKE and managed GCP resources per component.
9. **Production view**: production logs/metrics/traces matching a scenario, from Datadog.
10. Runs on **macOS**. Windows support is **deferred** (D26): not developed or tested for now, but the design stays portable so it can be added later.

## 3. Non-goals

- Not a production APM or monitoring system; recording is a local, dev-time activity.
- Not a general whiteboard (no freehand drawing).
- No write access to Kubernetes, GCP or Datadog — ever.
- No support for non-JVM services at level 2 (level 1 sees them from the caller's side only).
- No multi-user/collaborative server; collaboration happens through git on the workspace.

## 4. Anti-goals (would be a failure even if it works)

- The viewer depending on OpenTelemetry's data model (OTel must stay replaceable behind the reporter).
- The board showing a wrong architecture silently (e.g. a missed Kafka hop). Capture correctness is tested hard (§12).
- Production data copied into recordings or the workspace.
- The tool's own credentials (Datadog keys, kubeconfig, GCP Application Default Credentials) written to disk, logs, or the workspace by the tool. Recorded traffic is **not** redacted (D23): the local stack only carries test data, so headers, bodies and SQL parameters are recorded as-is, including in committed reference recordings.
- The sandbox depending on tool internals instead of using the tool like a real project would.

## 5. Constraints

- macOS now; Windows later (D26). Docker Desktop available; Java 25 installed locally; Node 22 installed.
- Target systems: Spring Boot (Java/Kotlin) microservices, Kafka, Postgres-like databases, mocked 3rd parties (WireMock-style), deployed on GKE.
- Local stack runs in docker-compose; services are also run/debugged from IntelliJ.
- Production observability is **Datadog**. Whether production logs carry trace IDs is **unknown** (to be checked: a "Trace" tab / `dd.trace_id` on a production log).
- `gcloud` is not installed on the dev machine yet (needed only for the infrastructure view).

## 6. Decisions (made by the owner)

| # | Decision |
|---|----------|
| D1 | **Capture backbone: hybrid OpenTelemetry.** OTel Java agent for context propagation and library hooks; **our own agent extension** captures payloads and method I/O; **our own model** is written to disk. The viewer never sees OTel types. LGTM (Grafana/Tempo/Loki) is at most an optional debug sink via the OTel Collector. |
| D2 | **Local runtime:** docker-compose + IntelliJ debugging. Agent attached via `JAVA_TOOL_OPTIONS`; components named by `OTEL_SERVICE_NAME`. Reporter is shipped as a kit: agent jar + compose snippet. |
| D3 | **Components: inferred, then curated.** Recordings propose components/edges; a version-controlled **workspace** holds the curated truth (names, aliases, types, positions, notes, layer tags). New items appear flagged "discovered" until accepted. Recordings are disposable evidence; the workspace is the description of the project. |
| D4 | **Viewer is a local web app**: backend on localhost, UI in the browser. |
| D5 | **Backend: Spring Boot 4 + Kotlin on Java 25 LTS**, **Spring Modulith** for module boundaries (recordings, workspace, topology, kubernetes, production-logs), ports & adapters per external system. |
| D6 | **Frontend: React + TypeScript + Vite + React Flow** (xyflow) for the board. |
| D7 | **Dedicated reporter service** (Kotlin/Spring Boot): OTLP/HTTP receiver on `:4318`, scenario assignment, recording writer, control API, live stream to viewer. OTel Collector optional (only for fan-out). |
| D8 | **Scenario assignment, three layers by priority:** (1) `stack.scenario` baggage set by our test harness (JUnit 5 extension / RestAssured filter / Cucumber hook), (2) active recording window (start/stop via viewer, REST, CLI), (3) "unassigned" inbox in the viewer. A **scenario** is a stable workspace entry; a **recording** is one run of it. |
| D9 | **Recording format:** one folder per recording — `manifest.json`, append-only `events.jsonl`, `payloads/` for bodies over a threshold (e.g. 64 KB) referenced by hash. Every event carries `schemaVersion`. Schema defined once as Kotlin in a shared `model` module; TypeScript types generated from it. Recordings are gitignored; a run can be **promoted to a reference recording**, which is copied into the workspace and committed. |
| D10 | **Payloads captured as objects inside the app** (after deserialisation / before serialisation), rendered to JSON by one bounded value serialiser in the agent (limits on depth, collection size, string length). Kafka events also carry topic, partition, offset, key, headers, byte size, schema ID. Raw bytes only behind a per-topic debug flag. |
| D11 | **Level 2 scope:** public methods of Spring beans in configured base packages; getters/setters/`toString`/`equals`/`hashCode`/Kotlin synthetics/`copy()` always excluded; include/exclude patterns from the workspace. Event = args, return/exception, duration, thread, parent interaction. Coroutine-aware for `suspend` functions. Enabled **per service**, **off by default** for running stacks. |
| D12 | **Layers are workspace rules over recorded facts.** Agent records facts (class/method annotations, servlet filter names that ran, implemented interfaces, exceptions). Built-in rule pack (Spring Security, Spring Retry, Resilience4j, Spring Cache, `@Transactional`, Micrometer, SLF4J logging) + user rules + manual tags. Applied by the viewer at render time, so rule changes re-highlight all past recordings. |
| D13 | **Sequence diagrams: our own React/SVG renderer** (click message → payload/headers/timing, layer highlighting, collapse/expand method calls, retry grouping, async gaps for Kafka, virtualised scrolling, live growth). Starts minimal and grows per step. Export to Mermaid and PlantUML text. |
| D14 | **Unit/integration tests recorded with the same agent.** A `stack-reporter` **Gradle plugin** attaches the agent to test tasks; an auto-registered **JUnit Platform listener** turns each test into a recording tagged class/method/kind (unit/integration). Level 2 on by default for test runs. Test recordings are kept separate from the e2e scenario picker. Component view shows tests and e2e scenarios per method, and gaps. Maven plugin only if ever needed. |
| D15 | **Infrastructure view:** Fabric8 Kubernetes client against the user's kubeconfig (contexts, `gke-gcloud-auth-plugin`), with live watches; **Cloud Asset Inventory** via Application Default Credentials for managed GCP resources. Workload ↔ component mapping by `app.kubernetes.io/name`, fallback Deployment name, exceptions as workspace aliases. **Hard read-only** (no write calls in the codebase); Secret values never read, only names/keys. |
| D16 | **Production view: Datadog adapter first.** Scenario **signature** (entry point, ordered components/topics, route templates) derived from the reference recording → Datadog spans search → logs by trace ID → metrics per service/resource. Fallback when logs lack trace IDs: entry point + time window. Per-scenario saved Datadog queries as overrides. Datadog service names mapped via workspace aliases. Read-only, queried live, never persisted. GCP/LGTM adapters only if ever needed, behind the same port. |
| D17 | **Datadog credentials from environment variables only:** `DD_API_KEY`, `DD_APP_KEY`, `DD_SITE`. Production view disabled when missing. Never logged, never written, only sent to the configured Datadog site. (Recommended, not enforced by the tool: Application key with read-only scopes.) |
| D18 | **Viewer distribution:** self-contained Spring Boot jar with the React UI bundled, started via the launcher script `stack-viewer` (sh; a Windows `.cmd` launcher comes with D26), e.g. `stack-viewer --workspace ./stack`. Dev mode: `./gradlew :viewer-backend:bootRun` + Vite dev server with hot reload. `jpackage` installer possible later. Reporter runs as a container in the system's docker-compose. |
| D19 | **Workspace:** a `stack/` folder in whichever repo holds the system's docker-compose; viewer takes `--workspace <path>`. Layout below (§8). One file per item. YAML written by the viewer does **not** preserve hand-written comments; prose lives in Markdown notes. |
| D20 | **Repo layout:** one repo, two independent Gradle builds — `tool/` and `sandbox/`. Sandbox consumes the tool only through published artifacts (`mavenLocal` / composite build for convenience). Kotlin DSL + version catalogs in both. |
| D21 | **Testing strategy: test-first.** See §12. |
| D22 | **First step scope** — see §11. |
| D23 | **No redaction of recorded traffic.** Local stacks only carry test data; the credentials anti-goal covers only the tool's own credentials. |
| D24 | **Connections are workspace files** (`connections/<from>--<to>.yaml`: kind, label, state, notes). **Evidence = latest recording per scenario + all reference recordings**; older recordings stay viewable but don't shape topology. States: `discovered` (in evidence, not accepted), `accepted`, `manual` (user-drawn, no evidence required), `hidden` (in evidence, suppressed on the board). The board flags **both** mismatches: evidence without an accepted connection → "discovered" badge; accepted connection without current evidence → dashed "unverified". |
| D25 | **CI:** GitHub Actions on Linux and macOS runs the full `tool/` build — unit, agent (forked JVM), reporter golden files, viewer backend, Playwright UI — on every push. Sandbox compose acceptance test runs on Linux in CI (GitHub macOS runners have no Docker) and on macOS locally. Requires adding a GitHub remote. *(Revised 2026-10-27: Windows runners and manual Windows checks removed — see D26.)* |
| D26 | **Windows deferred.** No Windows development, CI, launcher or manual checks for now; the owner will want to run the tool on Windows at some point. Until then, keep it portable at no extra cost: file paths via `java.nio.file.Path` / `path.join`; ids and folder names the tool writes are slugs without characters Windows forbids (`:`, `\`, `/`, `?`, `*`, `"`, `<`, `>`, `|`); no POSIX-only assumptions in Java/Kotlin/TS code; build logic in Gradle, not shell. Adding Windows later = Windows CI runner, a `.cmd` launcher, and a manual docker-compose check. |

## 7. Architecture

```
            ┌───────────────────────── local stack (docker-compose / IntelliJ) ──────────────────────────┐
 e2e tests  │  service JVM                                                                                │
 (harness   │  ┌───────────────────────────────┐        ┌──────────┐  ┌───────┐  ┌──────────────┐        │
  sets      │  │ app + OTel Java agent         │──JDBC─▶│ Postgres │  │ Kafka │  │ WireMock     │        │
  baggage)──┼─▶│   + stack agent-extension     │──────────────────────▶       │  │ (3rd-party)  │        │
            │  └──────────────┬────────────────┘                     └───────┘  └──────────────┘        │
            │                 │ OTLP/HTTP (spans + our attributes/events)                                 │
            │                 ▼                                                                           │
            │        ┌──────────────────┐  writes   recordings/<run>/manifest.json, events.jsonl, payloads/│
            │        │ reporter (:4318) │──────────▶                                                     │
            │        │ control API      │  (optional Collector fan-out → Tempo/Grafana for debugging)     │
            │        └────────┬─────────┘                                                                │
            └─────────────────┼──────────────────────────────────────────────────────────────────────────┘
                              │ live stream (later)
                              ▼
   ┌────────────────────── stack-viewer (java -jar, localhost) ───────────────────────┐
   │ viewer-backend (Spring Boot 4 + Modulith)                                         │
   │   recordings · workspace · topology/model · layers · kubernetes · production-logs │
   │       ▲ reads recordings  ▲ reads/writes stack/   ▲ kubeconfig/ADC  ▲ DD_* env    │
   │ viewer-ui (React + React Flow + own sequence renderer)                            │
   └───────────────────────────────────────────────────────────────────────────────────┘
```

### Modules (`tool/`)

| Module | Responsibility |
|---|---|
| `model` | Kotlin definitions of recording events, manifest, workspace entities; JSON schema snapshot; TS type generation source. |
| `agent-extension` | OTel Java agent extension: payload capture (HTTP, JDBC, Kafka, HTTP clients), level-2 method advice (ByteBuddy), fact capture for layers, bounded value serialiser. |
| `reporter` | Spring Boot: OTLP/HTTP receiver, span → model conversion, scenario assignment (D8), recording writer (D9), control API, live stream. |
| `viewer-backend` | Spring Boot 4 + Modulith: workspace read/write, recording read/index, topology merge (inferred + curated), layer rule engine, Kubernetes adapter, Datadog adapter; serves the UI. |
| `viewer-ui` | React/TS/Vite: primary board (React Flow), sequence renderer, component/infra/production views, notes editor. Bundled into the viewer jar. |
| `gradle-plugin` | Attaches agent to test tasks, registers JUnit Platform listener, configures reporter endpoint. |
| `test-harness` | JUnit 5 extension, RestAssured filter (and later Cucumber hook) setting `stack.scenario` baggage. |

## 8. Workspace layout (`stack/`)

```
stack/
  workspace.yaml              # project name, services + repo paths, aliases (OTel / k8s / Datadog names), level-2 config per service
  components/<id>.yaml        # type, display name, tags, links, accepted/discovered state
  connections/<from>--<to>.yaml  # kind, label, state (discovered|accepted|manual|hidden), notes (D24)
  scenarios/<id>.yaml         # stable id, description, prod signature overrides, saved Datadog queries
  layers/<id>.yaml            # layer rules (built-in pack copied in + user rules), manual tags
  notes/<id>.md               # Markdown notes for components, flows, board
  boards/<view>.layout.json   # positions/zoom; machine-written, sorted keys
  reference-recordings/       # promoted runs (same folder format as recordings)
```

Recordings themselves live outside the workspace (default `recordings/` next to the compose file, gitignored).

## 9. Data model (outline — builder refines)

- **Component**: id, type (`service | database | kafka-cluster | topic | external-mock | client`), display name, aliases, tags.
- **Interaction event** (level 1): id, recording id, trace/span ids, parent id, timestamp, duration, source component, target component, kind (`http | kafka-produce | kafka-consume | db | ...`), protocol details (method/route/status, topic/partition/offset/key, SQL/params/rows), request/response payload (inline or payload ref), headers, facts (for layers).
- **Method event** (level 2): id, parent interaction or parent method, component, class, method, args, return or exception, duration, thread, annotations.
- **Recording manifest**: run id, scenario id, kind (`e2e | manual | test`), test identity when kind=test, start/stop, reporter version, schemaVersion, components seen.
- Kafka topics are modelled so the board can show them either as a node or as a labelled channel between producer and consumer (presentation is a builder/UI call).

## 10. Views (target state)

- **Primary**: React Flow board; components draggable, zoom/pan, layout persisted; scenario picker; highlighted path with per-hop details on hover; layer overlay; notes.
- **Sequence**: own renderer (D13).
- **Component**: method call trees from e2e scenarios and tests; tests ↔ methods mapping; gaps; layer overlay.
- **Infrastructure**: D15.
- **Production**: D16.
- **Unassigned inbox**: name/assign/discard unassigned flows.

## 11. Incremental delivery

The tool grows with the sandbox. Each step: extend sandbox → failing acceptance test → build tool slice → run → owner validates visually.

### Step 1 (agreed scope)

Sandbox: `orders-service` (Spring Boot 4, Kotlin, Spring MVC) with `POST /orders` and `GET /orders/{id}`, Postgres, docker-compose, `e2e-tests` with two scenarios (create order, get order).

Tool:
- `agent-extension`: HTTP server in/out (method, route template, status, headers, body) and JDBC (SQL, params, row count). Level 1 only.
- `test-harness`: JUnit 5 extension setting `stack.scenario` baggage.
- `reporter`: OTLP/HTTP, harness tag + recording windows via REST start/stop, writes `manifest.json` + `events.jsonl`.
- `model`: schema v1 + generated TS types.
- Viewer primary view: board with e2e client, `orders-service`, Postgres; drag/zoom/pan; layout saved to `boards/primary.layout.json`; scenario picker with path highlight and hover details.
- Viewer sequence view: basic diagram for one run; click a message to see payload.
- Workspace: `workspace.yaml`, `components/`, `connections/` (with discovered/accepted states and both mismatch flags, D24), `scenarios/`, Markdown notes on components.

### Explicitly later (each when the sandbox needs it)

Level 2 + component view; Kafka, WireMock, second service; layers; test recording (Gradle plugin); unassigned inbox; live streaming; reference recordings; infrastructure view; production view; Mermaid/PlantUML export; optional Collector fan-out.

## 12. Testing strategy (D21)

- Test-first (red-green-refactor); each sandbox step starts with a failing acceptance test.
- Unit: JUnit 5 + Kotest assertions + MockK (model, scenario assignment, layer rules, signature, value serialiser).
- **Agent: fixture Spring Boot app started in a forked JVM with the real OTel agent + our extension**, exporting to an in-process fake reporter; assert exact events (HTTP, JDBC, later Kafka, methods, coroutines).
- Reporter: Spring Boot tests feeding recorded OTLP payloads; **golden-file** comparison of `events.jsonl` (stable ids/timestamps).
- Viewer backend: Spring Modulith verification + ArchUnit (module boundaries, ports & adapters); Fabric8 mock server; WireMock for Datadog.
- UI: Vitest + React Testing Library; Playwright e2e against fixture workspaces/recordings.
- Sandbox acceptance: compose up → run e2e with agent → assert recording describes the expected flow.
- Contract: JSON-schema snapshot of `model`; any change is deliberate and bumps `schemaVersion`.
- CI: see D25. Windows: deferred (D26).

## 13. Builder's calls (made by Claude, owner to check)

- OTel Java agent version pinned in the version catalog; extension built against its extension API; upgrades deliberate.
- Payloads and facts travel from agent to reporter as span attributes/events under a `stack.*` namespace over OTLP/HTTP (protobuf); attribute length limits raised in agent config; reporter offloads large values to `payloads/`.
- Spring MVC body capture by hooking the framework's message-conversion step (object level, per D10), not by buffering raw streams. WebFlux deferred until a sandbox service needs it.
- The test harness injects W3C `baggage` (`stack.scenario`, `stack.run`) and `traceparent` headers directly, so the e2e test JVM does not need the agent in step 1. The e2e client component is inferred from server root spans with no parent.
- Each harness-tagged run gets its own `stack.run` id → one recording folder per run.
- Until the inbox exists, unassigned data is written to `recordings/_unassigned/` and ignored by the viewer.
- JSON: Jackson (Spring Boot 4 default). TS types generated via JSON Schema from the Kotlin model.
- UI state: TanStack Query for server data, a small store (e.g. Zustand) for UI state; initial auto-layout of discovered components with ELK.js before the user arranges them; Markdown via a standard React Markdown renderer.
- Live stream (later): Server-Sent Events from reporter/viewer backend.
- Backend ↔ UI API: REST/JSON.
- The agent's value serialiser respects the app's Jackson annotations (`@JsonIgnore`, `@JsonProperty`, naming strategy) — preferring the app's own `ObjectMapper` when one is reachable — so recorded payloads match what went over the wire. Falls back to the bounded reflective serialiser otherwise.
- Agent configuration (level-2 on/off and patterns, per-topic raw-bytes flag) reaches agents by **pull from the reporter's control API** at startup and on a short poll; the reporter mounts the workspace read-only. Only the reporter URL and service name are set via `JAVA_TOOL_OPTIONS`/env. Pattern changes apply to newly loaded classes; a change that needs retransformation is applied on JVM restart (documented). Not needed until level 2, but the step-1 agent–reporter interface reserves the endpoint.
