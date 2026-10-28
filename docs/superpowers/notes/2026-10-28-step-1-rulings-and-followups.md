# Step 1 — rulings and deferred follow-ups

Decisions the build agent made on the owner's behalf during step 1 execution, and minor review findings deliberately deferred. Source: the execution ledger (deleted after completion).

## Rulings

- Ruling R1: Gradle wrappers live in `tool/` and `sandbox/`; every plan command `./gradlew -p tool X` is run from repo root as `tool/gradlew -p tool X` (sandbox: `sandbox/gradlew -p sandbox X`); CI uses `shell: bash` on all runners so the same command works on Windows — why: plan places wrapper in tool/ but writes root-relative commands — cost if wrong: trivial path edits.
- Ruling R2: `stack.db.row_count` is a long span attribute; test helper `attr()` renders attribute values as strings, so `"1"` in T3 tests means long 1; reporter reads it as long — why: OTel numeric attrs are typed — cost if wrong: one-line mapper change.
- Ruling R3: BodyCapture short-circuits Resource/InputStreamSource (by class name), InputStream, Reader, byte[] to placeholders before any mapper call — why: spec anti-goal + "capture must never break the app" outrank D10 object-capture for binary/stream payloads — cost if wrong: download bodies show placeholder instead of content.
- Ruling R4: FakeOtlpReceiver.awaitSpan must be (predicate, timeout) per brief interface — Task 3 consumes it — fixed in this round.
- Ruling R5: sanitizer key otel.instrumentation.common.db.query-sanitization.enabled=false (agent 2.33.1 name) accepted over brief's deprecated key; Task 3 adds a literal-SQL assertion to prove it — cost if wrong: SQL literals masked as ?.
- Ruling R6: root .gitignore `**/recordings/` (plan-mandated) also swallows source packages/fixtures named recordings (Task 11 has e2e/fixtures/recordings) — replace with anchored `/recordings/` and `/sandbox/recordings/`, delete tool/.gitignore re-include — cost if wrong: a stray recordings dir elsewhere shows in git status.
- Ruling R7: Task 9 toFlowElements omits edges whose from/to is not among components (Task 8 minor: manual/accepted connections to unknown components) — why: React Flow can't render dangling edges — cost if wrong: such connections invisible until component is curated.
- Ruling R8: Task 11 adds a backend SPA fallback (forward non-/api, non-file paths to index.html) — why: /sequence/:runId deep link/reload would 404 (Task 9 minor) — cost if wrong: small controller in web module.
- Ruling R9: Task 11 flushes PrimaryBoard's pending debounced layout save on unmount — why: navigating to /sequence within 500ms of a drag drops the save (Task 9 minor) — cost if wrong: none.
- Ruling R10: sandbox acceptance test parses events.jsonl/manifest generically (Jackson tree/maps) and does not depend on stack-model (model isn't published; plan's "beyond published stack-model" is moot) — cost if wrong: none.
- Ruling R11: do not modify the owner's ~/.docker/config.json (credsStore desktop hangs on this Mac); implementer may use a scratch DOCKER_CONFIG for its own runs; flag to owner at finish.
- Ruling R12: viewer default server.port → 8090 (Vite dev proxy target updated accordingly) — why: 8080 is the conventional service port; the sandbox uses it — cost if wrong: owner passes --server.port.
- Ruling R13: reporter drops db.client spans with blank statement — why: pool validation queries are noise and show as unlabelled steps — cost if wrong: a genuinely empty statement isn't shown.
- Ruling R14: while a run is highlighted, a path edge chip shows that run's step label(s) for the hop instead of the connection's stored label — why: misleading "GET /orders/{id}" on the create-order path — cost if wrong: small UI change.
- Ruling R15: TopologyService.components() adds endpoints of non-hidden curated connections (type inferred) so D24 "unverified" edges render — refines R7.
- Ruling R16: viewer binds server.address=127.0.0.1; sandbox compose publishes ports on 127.0.0.1 — why: D4 localhost, no auth — cost if wrong: owner can't reach viewer from another machine (not a goal).
- Ruling R17: discovered HTTP connection label = distinct evidence routes joined (", "), not first step; chip shows run step labels when highlighted (R14) — why: label depended on test order and was persisted on Accept.
- Ruling R18: sequence DB response label pluralised ("1 row"/"N rows") overriding plan's "1 rows" — why: consistency with details panels — cost if wrong: trivial.
- Ruling R19: Stream/BaseStream/Iterator bodies → "<stream>" placeholder; README e2e step waits for health (compose healthcheck + --wait); launcher CDPATH= ; agent version pin comment in sandbox catalog + README. prettyBody >2^53 deferred.

## Deferred minor findings

- Task 1: ComponentIds.slug can return "" for names with no [a-z0-9] — carry to Task 5 (reporter maps component ids)
- Task 1: schema marks Kotlin-defaulted props required (fine for TS output; revisit if schema validates YAML)
- Task 1: tests missing for ConnectionKind/State lowercase + ConnectionDefinition.id JsonIgnore
- Task 1: schema snapshot refresh is manual (no task)
- Task 1: build duplication (testImplementation databind, double toolchain, empty [plugins])
- Task 2: StackAttributes not listed as helper class (works via constant inlining)
- Task 2: BoundedValueSerializer lacks overall output budget
- Task 2: header capture unbounded in size
- Task 2: container-added response headers (Date, Content-Length, security headers) missing — capture at commit time later
- Task 2: reflection per body not cached; nested service() double header capture; serializer test written after impl
- Task 2: placeholders only for top-level body; nested Resource/byte[] in DTOs, StreamingResponseBody, Path/File still go through mapper
- Task 3: JDBC parameter values not truncated (cap at MAX_BODY_CHARS for consistency?)
- Task 3: unbound index renders as null like SQL NULL; LOB/SQLXML String.valueOf could be expensive — placeholders safer
- Task 3: internal OTel APIs (ExperimentalInstrumentationModule, SpanKey) — capture stops silently under span-suppression-strategy=none; add comment
- Task 3: no agent tests for clearParameters / statement reuse / failing execute; no batch or plain-Statement row counts
- Task 4: no deterministic test that afterEach clears context when test throws
- Task 4: StackBaggageFilter appends headers — duplicates if caller already set traceparent/baggage (replace or merge)
- Task 4: repeated-run test uses static set; empty/newline/leading-dash id cases untested; KDoc on thread scope
- Task 5: malformed/wrong-typed attribute paths untested; JacksonException swallowed without log
- Task 5: empty body string → null (indistinguishable from not captured); missing status → 0 untested
- Task 5: unused jackson-module-kotlin/kotlin-reflect deps; FQN in test; per-byte hex format
- Task 6: uniqueFolder collision guard racy across different runs (use createDirectory loop)
- Task 6: start/stop race → 500 + manifest without stoppedAt; ensureRun failure leaves window open
- Task 6: spans arriving after stop (arrival-time window; agent bsp delay 200ms mitigates); E2E run w/o scenario tag frozen as manual
- Task 6: no fsync before atomic manifest rename; unassigned kind MANUAL; duplicated slug regex; golden test temp dir leak; shared window state in HTTP tests
- Task 7: directory-level IOException in filesIn escapes load(); problem messages drop line/column
- Task 7: unknown YAML keys hide entity (FAIL_ON_UNKNOWN_PROPERTIES); temp files 0600 → final files 0600
- Task 7: no fsync; silent non-atomic fallback; stray .tmp never cleaned; no slug length cap; connection id '--' ambiguity with trailing '-'
- Task 8: exception advice maps all IllegalArgument/NoSuchElement app-wide; listing logic in RecordingsController
- Task 8: reference recordings not listable via /runs; connections to components absent from components list (UI may drop edges)
- Task 8: manifests re-read per evidence run; deleted-run race → 500; -Xshare:off workaround; ApiTest temp dir leak; non-ASCII note untested
- Task 9: stale positions in first save when new components appear; autoLayout promise no catch; pending layout save dropped on unmount (carry to Task 11 — new route)
- Task 9: no un-hide; connection notes dropped on accept/hide (harmless while only discovered offered); gen:types drift unchecked; no SPA fallback (carry to Task 11)
- Task 9: no tests for debounce save / edge accept-hide / clear-note; weak autoLayout test; build coupling to npm; @types/node 26 vs Node 22
- Task 9: note save error lost / draft left in store if panel unmounts mid-save; banner errors not auto-cleared; drafts not persisted
- Task 10: epoch nanos > 2^53 lose precision in JS (test +1 no-op; ordering relies on backend order + stable sort)
- Task 10: highlight flashes off while new run loads; selected scenario/run not reconciled on refresh; HopDetails non-exhaustive event branch
- Task 10: hard-coded path blue; popover shrinks at low zoom; listbox keyboard nav; hover on chip not path line; badges per connection (×N) not per step
- Task 10: path-note JSX untested at render level; badge "1" not asserted; hover tests share zustand state
- Task 11: e2e specs not run red first; tests share one mutable workspace (order-dependent); playwright launches bare `java` from PATH; temp workspace copies never cleaned
- Task 11: statusTone duplicated x3; Headers component shadows DOM global; nested self-call activation bars overlap; HEAD not forwarded by SPA fallback; dotted runId treated as file; details show duration only
- Task 12: test task lacks dist as input (may stay UP-TO-DATE); bad-JAVA_HOME test asserts same text as old-Java; CDPATH cd pitfall; @Value flag outside ViewerPaths
- Task 13: acceptance readRecording aborts scan on a corrupt stale folder; polls on event count not predicate; seedOrder duplicated; test recordings mix with manual; no input validation in orders-service; README workarounds to update after R12/R13; catalog header comment
- Final fix wave: actuator health probes can land in an open manual recording window; connection route-label order ties when runs start in the same instant.
- prettyBody in MessageDetails re-parses JSON, so integers above 2^53 display rounded.

## Environment notes

- This Mac's Docker credential helper (credsStore: desktop) hangs; builds used a scratch DOCKER_CONFIG without credsStore. See sandbox/README.md troubleshooting.
