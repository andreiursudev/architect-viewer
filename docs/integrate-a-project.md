# Integrating a project with the stack tool — instructions for an agent

You are setting up the stack tool for a real project the user works on. When you are done, the user can start
the project's services locally under the tool's agent, run a set of e2e scenarios, and look at what each scenario
did (every HTTP hop, request/response bodies, SQL) on the stack viewer's board and in its sequence view.

Everything you create goes into a **separate personal repository** (called the *stack repo* below). The project's
own repositories stay untouched: the scenarios are personal documentation and exploration, not part of the
production code.

Read the whole file before you start. Steps 3 and 7 end in a stop: report to the user and wait for their answer.

## 0. Ground rules

- **Never change the service repositories.** Don't add, edit, commit or push anything there: no Gradle or Maven
  changes, no `application*.yaml` edits, no `.run/` or `.idea/` files, no `.gitignore` entries. You may read them,
  and you may build them with their own build (`gradlew bootJar`, `mvn package -DskipTests`), which only writes to
  their ignored build output. Afterwards, `git status` in every service repository must be as clean as before.
- **Never change the stack tool's repository** (`<TOOL_REPO>`). If the tool lacks something the project needs,
  write it down for the user (step 9) instead of working around it in the tool.
- **Local only.** The agent and reporter run on this machine against local containers. Never point a service under
  the agent at shared, staging or production databases, brokers or APIs, and never use real credentials. Recorded
  traffic is **not redacted**: headers (including `Authorization`), bodies and SQL parameters are stored as-is.
- **Keep the services' published ports bound to `127.0.0.1`.**
- **Don't commit `recordings/`.** Commit only in the stack repo, and push it nowhere unless the user asks.
- **Ask the user, don't guess,** when a service needs secrets, licences or access you don't have, and before you
  delete Docker volumes you didn't create.

## 1. Inputs

Get these from the user if they're not already in your instructions:

| Name | What it is | Default |
|---|---|---|
| `<TOOL_REPO>` | The stack tool's repository on this machine | `D:\andrei\architect-viewer` |
| `<SERVICE_REPOS>` | Path(s) of the service repositories to integrate | none, ask |
| `<STACK_REPO>` | Where to create the personal stack repo | a sibling of the service repos, named `<system>-stack` |
| Flows | The user journeys worth documenting | propose them yourself in step 3 |

All commands below are PowerShell on Windows. The same commands work in sh with `/` paths.

## 2. Check the tool is built

Requirements on this machine: Docker Desktop or Podman, and Java 25 for the viewer. The viewer launcher takes Java
from `STACK_VIEWER_JAVA_HOME`, else `JAVA_HOME`, else `java` on `PATH`. The Gradle builds download their own
JDK 25 when none is installed.

The tool is built when all of these exist:

- `~/.m2/repository/io/stackvibed/stack-agent-extension/0.1.0-SNAPSHOT/` and
  `~/.m2/repository/io/stackvibed/stack-test-harness/0.1.0-SNAPSHOT/`
- the image `stackvibed/reporter:0.1.0-SNAPSHOT` (`docker image ls stackvibed/reporter`)
- `<TOOL_REPO>/tool/viewer-backend/build/stack-viewer/stack-viewer.cmd`

If any is missing, build the tool from `<TOOL_REPO>` (this only writes build output):

```powershell
tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage :viewer-backend:viewerDist
```

`<TOOL_REPO>/sandbox/` is a working example of everything below: two Spring Boot services, compose, e2e tests and
a workspace. When this file and the sandbox disagree, the sandbox is what was tested; follow it and tell the user.

## 3. Survey the project and report (then stop)

Read the service repositories and find out, for each service, how it fits what the tool records today:

| The tool records | The tool does not record (yet) |
|---|---|
| HTTP endpoints of **Spring MVC on Spring Boot 3+** (jakarta servlet): method, route, status, headers, request and response bodies | Request/response bodies and headers of WebFlux or Spring Boot 2 (`javax.servlet`) services: the hop shows, without them |
| Database calls over **JDBC** (JdbcTemplate, JdbcClient, JPA/Hibernate, jOOQ…): SQL with parameter values, row count. Only Postgres has been tested | Kafka, RabbitMQ, Pub/Sub and other messaging: the async part of a flow is **missing** from the board |
| Outgoing HTTP calls (RestClient, RestTemplate, WebClient, Feign…). Callee under the agent: one hop with the callee's bodies. Callee not under the agent: a box named after its host, status only | gRPC: dropped entirely |
| Scenario attribution through the W3C `baggage` header | Scheduled jobs and other work no test request starts (it lands in `recordings/_unassigned`) |
| | Method calls inside a service, and non-JVM services (only their callers' view of them) |

Also check each service for:

- **Java version**: the agent needs the service to run on Java 17+.
- **Other tracing**: `dd-java-agent` or another `-javaagent`, Micrometer tracing with an OTLP exporter, or
  `OTEL_*` settings (a ratio sampler especially). They conflict with or override the tool's agent, so they must be
  off for local runs. Do it through environment variables or system properties in the stack repo, never by
  editing the service.
- **What it needs to start**: databases (engine, version, schema migrations), brokers, other services, cloud
  services (GCP clients, secrets), and auth. Look at `application*.yaml`/`.properties`, any compose files and
  Testcontainers setup in the repo, and its README.
- **How it's built and run**: Gradle or Maven, the jar name, the main class, the port, and a local profile if it
  has one.
- **Endpoints and flows**: list the main controllers. Propose **3–6 flows** that are synchronous HTTP + database,
  at most 2–4 hops deep, plus **one that fails on purpose** (a 4xx); the viewer marks failed hops red.

**Stop and report to the user**: one short table per service (fits / partly / doesn't fit, with the reason), what
each needs to start locally and how you plan to provide it (container, stub, or another service under the agent),
the proposed flows, and any blockers. Continue only once the user has confirmed the services and flows.

## 4. Scaffold the stack repo

```
<STACK_REPO>/
  settings.gradle.kts
  build.gradle.kts               the stackAgent task
  gradle/libs.versions.toml
  gradle/wrapper/…, gradlew, gradlew.bat     copied from <TOOL_REPO>/sandbox
  compose.yaml                   step 5
  compose.podman.yml             step 5, only if the machine uses Podman
  scripts/run-<service>.ps1      step 6
  e2e-tests/
    build.gradle.kts
    src/main/kotlin/<pkg>/<Area>Scenarios.kt   the journeys (plain HTTP calls)
    src/test/kotlin/<pkg>/<Area>E2ETest.kt     one @StackScenario test per journey
  stack/workspace.yaml           the viewer's workspace (committed)
  recordings/                    written by the reporter (ignored)
  README.md                      how to run all of this (step 8)
  .gitignore
```

Run `git init` in it. Copy `gradlew`, `gradlew.bat` and `gradle/wrapper/` from `<TOOL_REPO>/sandbox/`.

**`.gitignore`**

```
build/
.gradle/
.kotlin/
recordings/
node_modules/
```

**`settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the Java 25 toolchain into ~/.gradle/jdks when no local JDK 25 is found.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        // The stack tool's artifacts, published by `tool/gradlew -p tool publishToMavenLocal`.
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroup("io.stackvibed") }
        }
        mavenCentral()
    }
}

rootProject.name = "<system>-stack"

include("e2e-tests")
```

**`gradle/libs.versions.toml`**: take the version values from `<TOOL_REPO>/sandbox/gradle/libs.versions.toml`.
`opentelemetry-javaagent` **must** equal the tool's version: the extension only works inside that agent version.

```toml
[versions]
kotlin = "2.4.21"
junit = "6.1.3"
rest-assured = "6.3.0"
opentelemetry-javaagent = "2.33.1"
stack-tool = "0.1.0-SNAPSHOT"

[libraries]
stack-agent-extension = { module = "io.stackvibed:stack-agent-extension", version.ref = "stack-tool" }
stack-test-harness = { module = "io.stackvibed:stack-test-harness", version.ref = "stack-tool" }
opentelemetry-javaagent = { module = "io.opentelemetry.javaagent:opentelemetry-javaagent", version.ref = "opentelemetry-javaagent" }
rest-assured = { module = "io.rest-assured:rest-assured", version.ref = "rest-assured" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

**`build.gradle.kts`**: puts the agent jars under fixed names in `build/stack-agent/`.

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

val otelAgentJar = configurations.create("otelAgentJar") {
    isCanBeConsumed = false
    isTransitive = false
}
val stackExtensionJar = configurations.create("stackExtensionJar") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    otelAgentJar(libs.opentelemetry.javaagent)
    stackExtensionJar(libs.stack.agent.extension)
}

tasks.register<Sync>("stackAgent") {
    group = "stack"
    description = "Copies opentelemetry-javaagent.jar and stack-agent-extension.jar into build/stack-agent."
    from(otelAgentJar) { rename { "opentelemetry-javaagent.jar" } }
    from(stackExtensionJar) { rename { "stack-agent-extension.jar" } }
    into(layout.buildDirectory.dir("stack-agent"))
}
```

**`e2e-tests/build.gradle.kts`**: the test harness is built for Java 25, so this project uses a Java 25
toolchain. The services can still run on 17+.

```kotlin
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.stack.test.harness)
    implementation(libs.rest.assured)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The e2e tests need the running stack, so they are not part of `build`.
tasks.test {
    enabled = false
}

tasks.register<Test>("e2eTest") {
    description = "Runs the tagged e2e scenarios against the running stack."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    // They talk to live services, so their outcome is never "up to date".
    outputs.upToDateWhen { false }
}
```

**`stack/workspace.yaml`**

```yaml
project:
  name: <system>
```

Nothing else goes into `stack/` by hand. The viewer adds components, connections and the board layout when the
user accepts what the recordings show.

Check: `./gradlew stackAgent` leaves exactly two jars in `build/stack-agent/`.

## 5. compose.yaml: the reporter and everything the services depend on

```yaml
services:
  reporter:
    image: stackvibed/reporter:0.1.0-SNAPSHOT
    pull_policy: never              # built locally by the tool; it is on no registry
    ports: ["127.0.0.1:4318:4318"]
    volumes: ["./recordings:/recordings"]

  # One container per dependency the survey found (databases, brokers, stubs), e.g.:
  <service>-db:
    image: postgres:17              # the engine and major version production uses
    environment:
      POSTGRES_DB: <db>
      POSTGRES_USER: <user>
      POSTGRES_PASSWORD: <password>  # local throwaway values only
    ports: ["127.0.0.1:15432:5432"]  # not 5432, so a Postgres on the host cannot shadow it
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U <user> -d <db>"]
      interval: 2s
      timeout: 5s
      retries: 30
```

- Port 4318 must be free on the host (no other OpenTelemetry collector or Datadog agent on it).
- Schema migrations (Flyway/Liquibase) normally run when the service starts; let them, rather than recreating the
  schema here.
- **Other services the flows call**: either run them under the agent too (step 6; they become part of the board,
  with bodies), or stub them with WireMock (`wiremock/wiremock`). A stub appears as a box named after its host,
  without bodies. Prefer the real service when its own dependencies are cheap to run.
- **Brokers** a service needs just to start (Kafka…): run them, but tell the user the messaging part isn't recorded.
- **Podman**: compose refuses `healthcheck.start_interval` there. If you use it, add a `compose.podman.yml` that
  resets it, as `<TOOL_REPO>/sandbox/docker-compose.podman.yml` does. Also set `$env:DOCKER_BUILDKIT = '0'` and
  `$env:COMPOSE_BAKE = 'false'` before building images (see the sandbox README, "With Podman").

Check: `docker compose up -d --wait` from `<STACK_REPO>` returns with every container healthy.

## 6. Run the services under the agent

Every service JVM needs the OpenTelemetry agent with the stack extension and a service name. The extension
already defaults to exporting traces to `http://localhost:4318`, with metrics and logs off and every trace
sampled.

**Default: a run script per service** (`scripts/run-<service>.ps1`). It builds the jar with the service's own
build, then starts it under the agent with the stack repo's configuration:

```powershell
# Builds <service> from its own repository (unchanged) and runs it under the stack agent.
$ErrorActionPreference = 'Stop'
$stack = Split-Path -Parent $PSScriptRoot
$repo = '<SERVICE_REPO>'

& "$repo\gradlew.bat" -p $repo bootJar        # Maven: & mvn -f "$repo\pom.xml" package -DskipTests
$jar = Get-ChildItem "$repo\build\libs\*.jar" -Exclude '*-plain.jar' | Select-Object -First 1

$env:OTEL_SERVICE_NAME = '<service>'           # the box's name on the board
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:4318'
# Point the service at the compose containers, e.g.:
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:15432/<db>'
$env:SPRING_DATASOURCE_USERNAME = '<user>'
$env:SPRING_DATASOURCE_PASSWORD = '<password>'
# Plus: switch off other tracing found in step 3, URLs of other services or stubs, a local profile.

java "-javaagent:$stack\build\stack-agent\opentelemetry-javaagent.jar" `
     "-Dotel.javaagent.extensions=$stack\build\stack-agent\stack-agent-extension.jar" `
     -jar $jar.FullName
```

- Pass the agent as `java` arguments, as above. **Don't set `JAVA_TOOL_OPTIONS`** in a shell that also runs Gradle
  or Maven: every JVM reads it, so the agent would attach to the build too.
- Override configuration only through environment variables or `-D` properties (Spring relaxed binding:
  `SPRING_PROFILES_ACTIVE`, `SPRING_DATASOURCE_URL`, `<PREFIX>_BASE_URL`…).
- `java` on `PATH` must be a version the service runs on.

**For the user's IntelliJ run configurations** (to debug a service), write the equivalent into the stack repo's
README: VM options `-javaagent:<STACK_REPO>/build/stack-agent/opentelemetry-javaagent.jar
-Dotel.javaagent.extensions=<STACK_REPO>/build/stack-agent/stack-agent-extension.jar`, plus the same environment
variables. Tell the user to leave "Store as project file" unchecked, so the configuration never lands in the
service repo.

**Alternative: the service as a compose container**, when the user only explores and doesn't change its code. Use
`build: <relative path to SERVICE_REPO>` if the repo has a Dockerfile, mount `./build/stack-agent:/stack-agent:ro`,
and set `JAVA_TOOL_OPTIONS` (safe inside a container), `OTEL_SERVICE_NAME`, and
`OTEL_EXPORTER_OTLP_ENDPOINT: http://reporter:4318`. See `orders-service` in
`<TOOL_REPO>/sandbox/docker-compose.yml`. Don't combine the service repo's own compose file with yours through
`-f`: Compose resolves relative paths against the first file's folder.

Check: each service starts, its health endpoint answers, and after one request to it a folder appears under
`<STACK_REPO>/recordings/_unassigned/` (untagged traffic lands there).

## 7. Write the scenarios

Default: Kotlin, JUnit 5, RestAssured and the tool's harness, as in `<TOOL_REPO>/sandbox/e2e-tests`.

```kotlin
// src/main/kotlin/…/OrdersScenarios.kt — the journeys, reusable, readable as documentation
class OrdersScenarios(private val baseUrl: String) {
    /** Places an order and returns its id. */
    fun createOrder(): String =
        request()
            .contentType(ContentType.JSON)
            .body("""{"customerId":"c-1","sku":"sku-1","quantity":2}""")
            .post("/orders")
            .then()
            .statusCode(201)
            .extract()
            .path("id")

    private fun request(): RequestSpecification =
        RestAssured.given()
            .baseUri(baseUrl)
            .filter(StackBaggageFilter())   // carries the active @StackScenario to the service
            .accept(ContentType.JSON)
}
```

```kotlin
// src/test/kotlin/…/OrdersE2ETest.kt — one tagged test per journey; each test run is one recording
class OrdersE2ETest {
    private val scenarios = OrdersScenarios("http://localhost:8080")

    @Test
    @StackScenario("create-order")
    fun `create order`() {
        scenarios.createOrder()
    }
}
```

Rules:

- **Black box.** No dependency on the service's code or its DTOs: use JSON strings or small classes of your own.
- **One journey per test.** Scenario ids match `^[a-z0-9][a-z0-9-]*$` and read as names (`create-order`,
  `create-order-out-of-stock`).
- **Seed data in `@BeforeAll`, outside any `@StackScenario`.** Those requests carry no scenario, so they stay out
  of the runs. Seed through the API; use SQL init scripts only for reference data the API can't create.
- **Wait for health in `@BeforeAll`**, as `OrdersE2ETest.awaitHealthy` does in the sandbox.
- **Auth**: get test tokens inside the journeys, from a local or stubbed identity provider, never a real one.
- **Another HTTP client**: add the headers from `StackContext.headers()` to each request. Any client works as long
  as each request carries `baggage: stack.scenario=<id>,stack.run=<uuid>`.

Playwright (only if the user asks for it). For API tests, create one `request` context per test that sends the
baggage header:

```ts
const api = await playwright.request.newContext({
  baseURL: 'http://localhost:8080',
  extraHTTPHeaders: { baggage: `stack.scenario=create-order,stack.run=${randomUUID()}` },
});
```

For browser tests, don't add headers to everything the browser sends: they also reach the frontend and third
parties, and can trigger CORS preflights. Open a recording window around each test instead:
`POST http://localhost:4318/api/recordings/start` with body `{"scenarioId":"<id>"}`, wait about 1 s after the test
so late spans arrive, then `POST http://localhost:4318/api/recordings/stop`. Only one window can be open at a time,
so set `workers: 1`.

**Stop and show the user** the list of scenarios with a one-line description each before you run them all.

## 8. Run, verify and document

From `<STACK_REPO>`:

```powershell
./gradlew stackAgent
docker compose up -d --wait
./scripts/run-<service>.ps1          # one terminal per service
./gradlew e2eTest
```

Verify the recordings yourself before you hand over:

- `recordings/` has one folder per test, named `<timestamp>_<scenario>_<run>`, each with `manifest.json` and
  `events.jsonl`.
- `manifest.json` → `componentsSeen` lists every service the flow should cross.
- `events.jsonl` has `http.server` events with `requestBody`/`responseBody`, `db.client` events with `statement`
  and `parameters`, and `http.client` events for outgoing calls.
- Nothing from a tagged test is only in `recordings/_unassigned/`. If something is, the scenario baggage didn't
  reach that service; find out why.

Then open the viewer and look at the board and each scenario's sequence view:

```powershell
<TOOL_REPO>/tool/viewer-backend/build/stack-viewer/stack-viewer.cmd --workspace=stack --recordings=recordings
```

It serves http://localhost:8090. Leave accepting components and connections to the user: that's their curation.

Write the stack repo's `README.md`: prerequisites, the commands above, the IntelliJ run configuration values, what
each scenario documents, and what isn't recorded for this project. Commit everything except `recordings/` and
`build/`.

## 9. Hand over

Report to the user:

- What runs where: containers, run scripts, ports.
- The scenarios, and which ones pass.
- What the tool can't show for this project (messaging, WebFlux bodies, gRPC…), with the flows it affects.
- Anything you couldn't verify, and anything the tool would need. Name the tool gap; don't patch around it.
- That `git status` in every service repository is unchanged.

## Troubleshooting

- **The service exits with `Error opening zip file` / agent not found**: `build/stack-agent/` is empty; run
  `./gradlew stackAgent`.
- **`reporter` image not found**: build the tool (step 2).
- **No recordings at all**: the reporter isn't reachable on 4318, another process holds the port, or the service
  overrides `OTEL_*` settings (exporter, endpoint, sampler).
- **A run is missing right after a test**: spans are exported every 200 ms; it appears a moment later.
- **A hop to another service shows only a host name**: that service doesn't run under the agent (or has no
  `OTEL_SERVICE_NAME`), so only the caller's view of the call is recorded.
- **Podman: the first request after `up --wait` fails** (`connection reset`): the ports are forwarded a few
  seconds late; retry.
