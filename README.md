# Stack tool

A personal tool for understanding a microservice system by watching it run. While the system runs locally and e2e
tests (or your own clicks) go through it, the tool records what every component received and sent: HTTP requests and
responses with bodies, SQL with parameters, Kafka records, and, when switched on, method calls inside the services and
steps in the browser. A local web viewer turns those recordings and a hand-curated workspace into a board of the
architecture and a sequence view of each scenario.

It has two halves:

- **Recording**: the OpenTelemetry Java agent with the tool's own extension in each service JVM, a browser package for
  frontends, a test harness that tags each e2e test as a scenario, and a **reporter** container that receives the
  traces on `:4318` and writes one folder per run (`manifest.json`, `events.jsonl`, screenshots).
- **Viewing**: the **stack viewer**, a Spring Boot app with a React UI on http://localhost:8090. It proposes components
  and connections from the recordings; you accept them into a version-controlled workspace (`stack/`), arrange the
  board, write notes, and open any run as a sequence diagram.

Recording is a dev-time activity on local containers. Recorded traffic is **not redacted**: headers, bodies and SQL
parameters are stored as they were sent, so run it with test data only.

## What it records today

| Recorded | Not recorded (yet) |
|---|---|
| Spring MVC on Spring Boot 3+: method, route, status, headers, request and response bodies | WebFlux and Spring Boot 2 bodies and headers (the hop shows, without them) |
| JDBC: SQL with parameter values and row count (tested on Postgres) | Messaging other than Kafka; gRPC |
| Outgoing HTTP calls, joined to the callee's side when it runs under the agent | Scheduled jobs and other work no test starts (it lands in `recordings/_unassigned`) |
| Kafka sends and Spring Kafka record listeners, with values and headers, through a topic node | Plain `poll()` consumers and batch listeners (no value); retries, dead-letter topics, schema registries |
| Public methods of Spring stereotype beans in chosen packages, with arguments and return values (off by default) | Non-JVM services, except as their callers see them |
| A browser app using `@stackvibed/browser`: page loads, clicks, submits, their fetches, and screenshots | |

## Repository layout

The repository holds two independent Gradle builds. The sandbox uses the tool only through its published artifacts,
the way any other project would.

| Path | What it is |
|---|---|
| `tool/` | The tool itself (Gradle build `stack-tool`) |
| `tool/model` | Kotlin model of recording events and manifests; the JSON schema snapshot the UI's TypeScript types are generated from |
| `tool/agent-contract` | The span attribute names shared by the agent extension and the reporter |
| `tool/agent-extension` | OpenTelemetry Java agent extension: HTTP bodies and headers, JDBC parameters, Kafka values, method capture |
| `tool/agent-fixture-app` | A Spring Boot app the agent tests run in a forked JVM under the real agent |
| `tool/reporter` | OTLP/HTTP receiver on `:4318`: assigns spans to scenario runs, writes recordings, serves the recording-window API; shipped as a Docker image |
| `tool/test-harness` | JUnit 5 `@StackScenario`, a RestAssured filter that sends the scenario as W3C `baggage`, and Playwright helpers (`StackBrowser`) |
| `tool/browser-sdk` | `@stackvibed/browser`, the npm package a frontend calls (`startStackRecording`) to record in the browser |
| `tool/viewer-backend` | The stack viewer: Spring Boot 4 + Spring Modulith, reads recordings, reads and writes the workspace, serves the UI; `stack-viewer` launchers |
| `tool/viewer-ui` | The viewer's React + TypeScript + Vite UI: the board (React Flow) and the sequence view |
| `tool/build-logic` | Shared Gradle convention plugins |
| `sandbox/` | A sample system the tool is built and tested against: orders, inventory and shipping services, Postgres, Kafka, a React frontend, e2e scenarios and a workspace. See [sandbox/README.md](sandbox/README.md) |
| `docs/integrate-a-project.md` | Step-by-step instructions (written for an agent) to set the tool up for a real project |
| `docs/superpowers/` | Design specs, implementation plans and rulings, one set per step |

## Requirements

- macOS, Linux or Windows. Commands below run unchanged in sh and PowerShell (there `tool/gradlew` runs
  `gradlew.bat`).
- Docker Desktop or Podman (see the sandbox README, [With Podman](sandbox/README.md#with-podman)).
- Java 25 to run the viewer. The launcher takes it from `STACK_VIEWER_JAVA_HOME`, else `JAVA_HOME`, else `java` on
  `PATH`.
- Nothing else for the builds: Gradle downloads a JDK 25 when none is installed, and the Node it runs npm with.

## Quick start: the sandbox

From the repository root:

```sh
tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage :viewer-backend:viewerDist
sandbox/gradlew -p sandbox stackAgent stackBrowser assemble
docker compose -f sandbox/docker-compose.yml up -d --build --wait
sandbox/gradlew -p sandbox e2eTest
tool/viewer-backend/build/stack-viewer/stack-viewer --workspace=sandbox/stack --recordings=sandbox/recordings
```

That builds and publishes the tool, builds the sandbox against it, starts the stack, records six scenarios, and opens
the viewer on http://localhost:8090. Stop the stack with `docker compose -f sandbox/docker-compose.yml down -v`.
[sandbox/README.md](sandbox/README.md) explains each step, what to look at in the viewer, recording manual traffic,
running a service from IntelliJ, and troubleshooting.

## Using it on your own project

The tool never changes the project's own repositories. Everything goes into a separate personal *stack repo*: a
compose file with the reporter and the project's dependencies, a run script per service that starts it under the agent,
e2e scenarios tagged with `@StackScenario`, and the `stack/` workspace. [docs/integrate-a-project.md](docs/integrate-a-project.md)
walks through it, including frontends and Kafka.

Then point the viewer at it:

```sh
tool/viewer-backend/build/stack-viewer/stack-viewer --workspace=<stack repo>/stack --recordings=<stack repo>/recordings
```

One viewer serves several workspaces: the project name in its header switches between them, and `Add workspace…`
adds a folder. The list is kept in `~/.stack-viewer/workspaces.yaml`.

## Building and testing the tool

```sh
tool/gradlew -p tool build
```

runs everything: unit tests, the agent tests (the fixture app in a forked JVM under the real agent, plus a Kafka
broker through Testcontainers), the reporter's golden-file tests, the viewer backend's tests, and the Vitest and
Playwright suites of the UI and the browser package. There is no CI; verification is local.

The sandbox runs the published copies, not the tool sources: after a change under `tool/`, run the first two quick
start commands again and restart the stack. The sandbox's own acceptance test starts a fresh stack, runs every
scenario and checks the recordings:

```sh
sandbox/gradlew -p sandbox stackAgent stackBrowser assemble acceptanceTest
```

## Status

Built so far, one step at a time against the sandbox: HTTP and JDBC capture, calls between services, method capture,
a recorded browser frontend with screenshots, Kafka, and a viewer that serves several workspaces. Planned and not
built: a component view, layers (security, retry, caching…), recording unit and integration tests, an inbox for
unassigned traffic, live streaming, reference recordings, an infrastructure view (Kubernetes/GCP) and a production view
(Datadog). The overall design is in
[docs/superpowers/specs/2026-10-27-stack-tool-design.md](docs/superpowers/specs/2026-10-27-stack-tool-design.md).
