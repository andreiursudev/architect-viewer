# Sandbox

The sample system the stack tool is built against. Step 1 is `orders-service` (Spring Boot 4, Kotlin, Spring MVC,
Postgres via `JdbcClient` and Flyway) with two tagged e2e scenarios, `create-order` and `get-order`.

The sandbox is a separate Gradle build and uses the tool like any outside project would: the OpenTelemetry agent
extension and the test harness come from `mavenLocal`, the reporter from its Docker image. It never depends on
`tool/` sources.

| Path | What it is |
|---|---|
| `services/orders-service` | `POST /orders`, `GET /orders/{id}`; knows nothing about the tool |
| `e2e-tests` | `OrdersScenarios` (the user journeys), `OrdersE2ETest` (tagged with `@StackScenario`), `StepOneAcceptanceTest` |
| `docker-compose.yml` | `postgres`, `reporter` (writes `./recordings`), `orders-service` under the agent |
| `stack/` | the committed workspace the viewer opens (`workspace.yaml`, `scenarios/`, and later components, connections, notes) |
| `recordings/` | what the reporter recorded; gitignored |

All commands below run from the repository root. Requirements: Java 25 and Docker Desktop.

## Run it

1. Build and publish the tool (agent extension and test harness to `mavenLocal`, the reporter image, the viewer):

   ```sh
   tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage :viewer-backend:viewerDist
   ```

   The sandbox runs these built copies, not the tool sources: after any change under `tool/`, run this again (at
   least `tool/gradlew -p tool :reporter:dockerImage` for a reporter change), then steps 2 and 3, so the stack picks
   up the new reporter image and agent extension.

2. Put the agent jars in `sandbox/build/stack-agent/` and build the orders-service jar:

   ```sh
   sandbox/gradlew -p sandbox stackAgent assemble
   ```

3. Start the stack. `--wait` returns once orders-service reports healthy (its `/actuator/health`), so the tests
   below never race its startup:

   ```sh
   (cd sandbox && docker compose up -d --build --wait)
   ```

4. Run the tagged e2e scenarios against it. Each test becomes one recorded run in `sandbox/recordings/`:

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
   (cd sandbox && docker compose down -v)
   ```

All published ports (8080, 4318, 15432) are bound to `127.0.0.1` only.

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

## Acceptance test

```sh
sandbox/gradlew -p sandbox stackAgent acceptanceTest
```

It starts its own copy of the stack with Docker Compose (Testcontainers), runs both scenarios, checks the
recordings the reporter wrote, and tears the stack down. It uses the same fixed ports (4318, 15432, 8080), so stop a
stack started by hand first. Neither `e2eTest` nor `acceptanceTest` is part of `build`.

## Running orders-service from IntelliJ

Start only the database and the reporter, then run the service from the IDE under the agent:

```sh
sandbox/gradlew -p sandbox stackAgent
(cd sandbox && docker compose up -d postgres reporter)
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
- **orders-service exits at start with `Error opening zip file` / agent not found.** `sandbox/build/stack-agent/`
  is empty: run `sandbox/gradlew -p sandbox stackAgent`, then `docker compose up -d --build --wait` again.
- **`reporter` image not found, or the recordings do not reflect a tool change.** The image is built locally only,
  and only when asked: `tool/gradlew -p tool :reporter:dockerImage`, then `docker compose up -d --build --wait`.
- **Port already in use.** Another stack (a manual `docker compose up` or a running acceptance test) holds 4318,
  15432 or 8080: `(cd sandbox && docker compose down -v)`. The viewer uses 8090.
- **A run is missing from the viewer.** Spans are exported in batches (200 ms); a run appears a moment after the
  test finishes. Untagged traffic is in `recordings/_unassigned`.
