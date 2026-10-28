# Step 2 — rulings and deferred follow-ups

Decisions the build agent made on the owner's behalf during step 2 execution (inventory-service, `http.client` events, hops between services), and minor review findings deliberately deferred. Source: the execution ledger (deleted after completion).

## Rulings

- Ruling P1: Task 5 adds a backend fixture run `2026-10-28T09-00-00_create-order-out-of-stock_run-4` (schemaVersion 2: orders server 409, orders client 409, inventory server 409 with parent = client, inventory db update rowCount 0) under `tool/viewer-backend/src/test/resources/fixtures/recordings/` and a `Fixtures.OUT_OF_STOCK_4` constant, updating tests that use `copyAll` if their expectations change; Task 7 copies the same two files into `tool/viewer-ui/e2e/fixtures/recordings/` — why: the Topology test needs real evidence and one hand-written run serves both — cost if wrong: one duplicated fixture folder.
- Ruling T2a: accept the out-of-brief FlowBuilder change (`is HttpClientEvent -> null` via mapNotNull) — why: the exhaustive `when` stopped compiling; Task 5 replaces it — cost if wrong: none, Task 5 rewrote that code.
- Ruling T2b: manifests also become schemaVersion 2 (RecordingManifest defaults to SCHEMA_VERSION) — why: one version for the whole recording format — cost if wrong: trivial.
- Ruling T4a: manifest `componentsSeen` stays sorted (`["inventory-service","orders-service"]`), overriding the plan's arrival order — why: step 1 designed it sorted and the acceptance test compares as a set — cost if wrong: none for consumers; the golden reflects sorted order.
- Ruling T4b: a client span with neither db nor http attributes is still dropped; the existing drop test was adjusted accordingly — why: such a span has nothing to show — cost if wrong: none.
- Ruling T5a: FlowBuilder/Topology test names follow the repo's backtick sentence style instead of the plan's camelCase names — why: consistency with the codebase — cost if wrong: renames only.
- Ruling T5b: a parseable URL with an empty path labels as `/` (not an empty string) — why: reads better on the board — cost if wrong: trivial.
- Ruling T6a (reversed by F1): the failed board chip used the is-client-error colour (amber), as the plan's explicit colour reference said, over its "red border" wording — why: matched the 409 badge colour — cost if wrong: one CSS variable swap to `var(--danger)`, which is what F1 did.
- Ruling T6b: an unanswered `http.client` response (status null, errorType set) takes the server-error tone in the sequence — why: no response is at least as bad as a 5xx — cost if wrong: trivial.
- Ruling F1: reverse Ruling T6a — a failed chip on the path gets a red (`var(--danger)`) border whatever the status (amber already means "discovered"), shows the failing step's status or error text in the chip (e.g. `409`, `ConnectException`; the first failed step in run order), and the legend gains a "failed" item — why: amber was already taken by "discovered" and a colour alone does not say what failed — cost if wrong: a CSS/legend tweak.
- Ruling F2: fold into the fix wave the cheap correctness items: empty-slug guard (UNKNOWN_HOST fallback), "No response: the call failed before reaching the service." for unanswered client calls, README wording for a down inventory-service, sequence tone for status < 400 + errorType — why: small, user-visible, and each has a clear right answer — cost if wrong: small.
- Ruling F3: keep deferred — Rule-1 caller-side status/timeouts (follow-up when a sandbox needs timeouts), replacing hand-written fixtures with real recordings, raw-path label growth (step 3 / WireMock) — why: none shows up with the step-2 sandbox — cost if wrong: misleading green hop only on client timeouts after a server answer.

## Deferred minor findings

- Task 1: quantity not validated in inventory-service (negative quantity raises stock; huge → 500)
- Task 1: no compensation if the order insert fails after the reservation (sandbox; maybe a comment)
- Task 1: the JDK HTTP client sends h2c upgrade headers that appear in recordings
- Task 2: new EventJsonTest names are camelCase vs the sentence style of the file
- Task 3: RelayController mutates the shared injected RestClient.Builder per request (use clone() or build once)
- Task 3: traceparent/baggage send helper duplicated between agent test classes
- Task 4: missing `url.full` → "" silently (comment, or fall back to server.address/port)
- Task 4: no test pins the precedence "client span with db.system AND http.request.method → db event"
- Task 5: no test that a server with parentSpanId == client spanId but a different traceId does not match
- Task 5: rule-2 labels use the raw path, so the connection label set grows with ids in paths (cap, or reword the KDoc "routes"); stays until WireMock / step 3
- Task 5: FlowBuilderTest import order; TopologyService comment one line off
- Task 7: the `/^create-order(?!-)/` option regex is duplicated across specs and breaks if another `create-order-*` scenario appears
- Task 7: `response 409` uses `.first()` without asserting the count
- Final review: Rule-1 (answered hop) shows the server's status, so caller-side status and timeouts (client timed out or saw another status after the server answered) are not reflected; fix when a sandbox needs timeouts
- Final review: hand-written fixtures (`create-order-out-of-stock_run-4`) differ from what the real sandbox API records; replace with real recordings
- Final review: raw-path labels grow with ids in paths until WireMock gives routes (see Task 5)

## Environment notes

- This Mac's Docker credential helper (credsStore: desktop) hangs; builds and the acceptance runs used a scratch DOCKER_CONFIG without credsStore. See sandbox/README.md troubleshooting.
