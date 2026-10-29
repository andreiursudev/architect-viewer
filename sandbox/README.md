# Sandbox

The sample system the stack tool is built against: `orders-service` (Spring Boot 4, Kotlin, Spring MVC, Postgres via
`JdbcClient` and Flyway) and, since step 2, `inventory-service` (same stack, its own Postgres), which orders-service
calls over HTTP to reserve stock before it stores an order, so orders-service needs inventory-service up (the compose
stack starts it first). Three tagged e2e scenarios: `create-order`, `create-order-out-of-stock` (the `sku-out` item
has no stock, inventory-service answers `409` and orders-service stores nothing) and `get-order`.

The sandbox is a separate Gradle build and uses the tool like any outside project would: the OpenTelemetry agent
extension and the test harness come from `mavenLocal`, the reporter from its Docker image. It never depends on
`tool/` sources.

| Path | What it is |
|---|---|
| `services/orders-service` | `POST /orders` (reserves stock at inventory-service first; `409` when it is short), `GET /orders/{id}`; knows nothing about the tool |
| `services/inventory-service` | `POST /reservations` (`201`, or `409` when the stock is short); seeded SKUs `sku-1` (plenty) and `sku-out` (none) |
| `e2e-tests` | `OrdersScenarios` (the user journeys), `OrdersE2ETest` (tagged with `@StackScenario`), `SandboxAcceptanceTest` |
| `docker-compose.yml` | `postgres` (15432), `inventory-db` (15433), `reporter` (4318, writes `./recordings`), `orders-service` (8080) and `inventory-service` (8081) under the agent |
| `stack/` | the committed workspace the viewer opens (`workspace.yaml`, `scenarios/`, and later components, connections, notes) |
| `recordings/` | what the reporter recorded; gitignored |

All commands below run from the repository root, unchanged on macOS, Linux and Windows (PowerShell or Git Bash:
there `tool/gradlew` runs `gradlew.bat`, and `stack-viewer` runs `stack-viewer.cmd`). Requirements: Docker Desktop or
Podman (see [With Podman](#with-podman)), and Java 25 for the viewer. The viewer launcher takes Java from
`STACK_VIEWER_JAVA_HOME` when set, else `JAVA_HOME`, else `java` on `PATH`: to keep another Java as the machine
default, install Java 25 without adding it to `PATH` and point `STACK_VIEWER_JAVA_HOME` (a user environment variable)
at it. On Windows, `winget install EclipseAdoptium.Temurin.25.JDK --override "/quiet ADDLOCAL=FeatureMain"` installs
it without touching `PATH` or `JAVA_HOME`. The Gradle builds need no particular local JDK: when no Java 25 is installed they download one into `~/.gradle/jdks`
(`tool/gradle/gradle-daemon-jvm.properties` and the foojay toolchain resolver in the `settings.gradle.kts` files).

## Run it

1. Build and publish the tool (agent extension and test harness to `mavenLocal`, the reporter image, the viewer):

   ```sh
   tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage :viewer-backend:viewerDist
   ```

   The sandbox runs these built copies, not the tool sources: after any change under `tool/`, run this again (at
   least `tool/gradlew -p tool :reporter:dockerImage` for a reporter change), then steps 2 and 3, so the stack picks
   up the new reporter image and agent extension.

2. Put the agent jars in `sandbox/build/stack-agent/` and build the service jars:

   ```sh
   sandbox/gradlew -p sandbox stackAgent assemble
   ```

3. Start the stack. `--wait` returns once both services report healthy (their `/actuator/health`; orders-service
   waits for inventory-service), so the tests below never race their startup:

   ```sh
   docker compose -f sandbox/docker-compose.yml up -d --build --wait
   ```

4. Run the tagged e2e scenarios against it. Each test becomes one recorded run in `sandbox/recordings/`, and the
   two orders-service scenarios that reserve stock also record the hop to inventory-service:

   ```sh
   sandbox/gradlew -p sandbox e2eTest
   ```

5. Open the viewer on the workspace and the recordings. It opens on http://localhost:8090 (it listens on this
   machine only):

   ```sh
   tool/viewer-backend/build/stack-viewer/stack-viewer --workspace=sandbox/stack --recordings=sandbox/recordings
   ```

6. Stop the stack when done:

   ```sh
   docker compose -f sandbox/docker-compose.yml down -v
   ```

All published ports (8080 orders-service, 8081 inventory-service, 4318 reporter, 15432 orders Postgres, 15433
inventory Postgres) are bound to `127.0.0.1` only.

The viewer discovers `inventory-service`, `postgresql-inventory` and the connections `orders-service` to
`inventory-service` (`POST /reservations`) and `inventory-service` to `postgresql-inventory` from the recordings;
accept them in the viewer to add them to `stack/`. In the `create-order-out-of-stock` run the hop to inventory-service
is the failed one (`409`).

The OpenTelemetry agent version in `sandbox/gradle/libs.versions.toml` must equal the one in
`tool/gradle/libs.versions.toml`: the stack extension uses the agent's internal APIs, so it only works in that version.

Traffic that is not part of a tagged test goes to `recordings/_unassigned`: health checks, plain curl calls, and
the order that `get-order` reads (the e2e tests create it before any scenario starts, so the `get-order` run holds
only the `GET`). To record manual traffic as a run, open a recording window on the reporter around it:

```sh
curl -X POST localhost:4318/api/recordings/start -H 'Content-Type: application/json' -d '{"scenarioId":"create-order"}'
curl -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{"customerId":"c-1","sku":"sku-1","quantity":2}'
curl -X POST localhost:4318/api/recordings/stop
```

In Windows PowerShell, where `curl` is an alias of `Invoke-WebRequest`, the same with `Invoke-RestMethod`:

```powershell
Invoke-RestMethod -Method Post http://localhost:4318/api/recordings/start -ContentType application/json -Body '{"scenarioId":"create-order"}'
Invoke-RestMethod -Method Post http://localhost:8080/orders -ContentType application/json -Body '{"customerId":"c-1","sku":"sku-1","quantity":2}'
Invoke-RestMethod -Method Post http://localhost:4318/api/recordings/stop
```

## With Podman

The stack also runs on Podman instead of Docker Desktop, through the `docker` CLI and Compose talking to Podman's
Docker-compatible API (on Windows the `podman machine` forwards it to Docker's default pipe, so no `DOCKER_HOST` is
needed). Two differences, for the whole session (PowerShell; `export DOCKER_BUILDKIT=0 COMPOSE_BAKE=false` in sh):

```powershell
$env:DOCKER_BUILDKIT = '0'
$env:COMPOSE_BAKE = 'false'
```

- Compose builds the service images with the classic builder, which Podman runs itself. Through BuildKit the images
  are built in a separate builder container and streamed into Podman, which can hang its API.
- Add `-f sandbox/docker-compose.podman.yml` to every `docker compose` command, e.g.
  `docker compose -f sandbox/docker-compose.yml -f sandbox/docker-compose.podman.yml up -d --build --wait`. Compose
  refuses `healthcheck.start_interval` on Podman, so the override drops it and probes the services every 10s.

`tool/gradlew -p tool :reporter:dockerImage` needs no change: it builds with BuildKit and `--load`, whatever
`DOCKER_BUILDKIT` says. The acceptance test needs none of this: it detects Podman and adds the override and the
classic builder itself.

## Acceptance test

```sh
sandbox/gradlew -p sandbox stackAgent acceptanceTest
```

It starts its own copy of the stack with Docker Compose (Testcontainers), runs all three scenarios, checks the
recordings the reporter wrote, and tears the stack down. On Podman it uses `docker-compose.podman.yml` and the classic
builder (see [With Podman](#with-podman)), and waits for the services on their published ports instead of through
Testcontainers' ambassador container, which needs container links that Podman does not support. It uses the same fixed ports (4318, 15432, 15433, 8080, 8081), so stop a
stack started by hand first. Neither `e2eTest` nor `acceptanceTest` is part of `build`.

## Running orders-service from IntelliJ

Start only the databases, inventory-service (orders-service calls it) and the reporter, then run orders-service from
the IDE under the agent:

```sh
sandbox/gradlew -p sandbox stackAgent :services:inventory-service:assemble
docker compose -f sandbox/docker-compose.yml up -d --build --wait postgres inventory-service reporter
```

Run configuration for `io.stackvibed.sandbox.orders.OrdersApplicationKt` (module `orders-service.main`), environment
variables (replace `<repo>` with the absolute path of this repository):

```
JAVA_TOOL_OPTIONS=-javaagent:<repo>/sandbox/build/stack-agent/opentelemetry-javaagent.jar -Dotel.javaagent.extensions=<repo>/sandbox/build/stack-agent/stack-agent-extension.jar
OTEL_SERVICE_NAME=orders-service
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

`http://localhost:4318` is also the extension's default endpoint, so that variable only makes it explicit.
`application.yaml` already points at the compose Postgres, published on `localhost:15432` (not 5432, so a Postgres
installed on the host cannot shadow it). Then run `e2eTest` from the IDE or Gradle as
above.

## Troubleshooting

- **`docker pull` / `docker compose up` / the acceptance test hangs on an image pull.** On some Macs the Docker
  Desktop credential helper (`"credsStore": "desktop"` in `~/.docker/config.json`) blocks. Without changing your
  own config, point `DOCKER_CONFIG` at a directory whose `config.json` has no `credsStore`, and link the CLI
  plugins and contexts so `docker compose` and the Desktop context still work:

  ```sh
  mkdir -p /tmp/docker-nocreds
  echo '{"auths":{},"currentContext":"desktop-linux"}' > /tmp/docker-nocreds/config.json
  ln -sfn ~/.docker/cli-plugins /tmp/docker-nocreds/cli-plugins
  ln -sfn ~/.docker/contexts /tmp/docker-nocreds/contexts
  export DOCKER_CONFIG=/tmp/docker-nocreds
  ```

  Only public images are pulled (`postgres:17`, `eclipse-temurin:25-jre`, and Testcontainers' own helpers), so no
  credentials are needed. Pulling them once (`docker pull postgres:17`) is enough; later runs use the local copies.
- **orders-service or inventory-service exits at start with `Error opening zip file` / agent not found.** `sandbox/build/stack-agent/`
  is empty: run `sandbox/gradlew -p sandbox stackAgent`, then `docker compose -f sandbox/docker-compose.yml up -d --build --wait` again.
- **`reporter` image not found, or the recordings do not reflect a tool change.** The image is built locally only,
  and only when asked: `tool/gradlew -p tool :reporter:dockerImage`, then `docker compose -f sandbox/docker-compose.yml up -d --build --wait`.
- **Podman: `docker compose` or `docker ps` hangs, while `podman ps` still answers.** Podman's Docker-compatible API is
  stuck, typically on an image streamed in by a BuildKit build. `podman machine stop`, `podman machine start`, and use
  the classic builder as in [With Podman](#with-podman).
- **Podman: `can't set healthcheck.start_interval as feature require Docker Engine v25 or later`.** Add
  `-f sandbox/docker-compose.podman.yml` (see [With Podman](#with-podman)).
- **Podman: the first request right after `up --wait` fails (`connection reset`, `connection was aborted`).** The
  services are up, but Podman forwards the published ports to them only some seconds later; retry.
- **Port already in use.** Another stack (a manual `docker compose up` or a running acceptance test) holds 4318,
  15432, 15433, 8080 or 8081: `docker compose -f sandbox/docker-compose.yml down -v`. The viewer uses 8090 (`--server.port=<n>`
  to move it).
- **`POST /orders` answers `5xx` and the hop to inventory-service is marked failed.** orders-service cannot reach
  inventory-service, so no inventory-service server span is recorded; the orders-service to inventory-service hop
  still shows in the viewer, marked failed, with the error and no status. Start the whole stack
  (`docker compose -f sandbox/docker-compose.yml up -d --build --wait`), or, with orders-service run from the IDE, inventory-service on
  `localhost:8081`.
- **A run is missing from the viewer.** Spans are exported in batches (200 ms); a run appears a moment after the
  test finishes. Untagged traffic is in `recordings/_unassigned`.
