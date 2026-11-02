# Step 5 — Screenshots of the Frontend's UI Steps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every ui step of a tagged Playwright run gets a screenshot of its result, stored with the run and shown as a thumbnail beside its arrow in the sequence view and at panel width in the details panel.

**Architecture:** The browser package batches ui steps until the page settles (no span open, two frames, at most 2 s), holds Playwright's next click behind an invisible shield, and calls `window.__STACK_SCREENSHOT__(spanIds)`, which `StackBrowser.tag` exposes. The harness's handler shoots the viewport and POSTs the PNG to the reporter, which writes `screenshots/<id>.png` and a `screenshots.jsonl` line into the run's folder. The viewer backend puts the screenshot id on each ui `FlowStep` and serves the PNG; the UI draws thumbnails in the diagram's left margin (taller rows) and the image in `MessageDetails`, with a full-size overlay.

**Tech Stack:** As in step 4 (Gradle 9.8.2, Java 25, Kotlin 2.4.21, Spring Boot 4.1.2, Jackson 3, React 19.3.1, Vite 8.3.5, Vitest 5.0.5, Playwright 1.64.1 / Java 1.64.0, OpenTelemetry JS 2.12.0 / 0.223.0). Nothing new: the harness uploads with `java.net.http.HttpClient`, its tests fake the reporter with `com.sun.net.httpserver.HttpServer`.

**Spec:** `docs/superpowers/specs/2026-11-02-step-5-frontend-screenshots-design.md` (parent: `2026-10-27-stack-tool-design.md`, D1, D9, D26; builds on the step-4 spec).

## Rulings made while planning (copied into the step-5 notes in Task 10)

- **G1: the shield** (the owner's choice, spec §2.1). Without it the screenshot of `load /` races the test's click on `to-order` and shows the order form.
- **G2: settled = `StackSpanProcessor.whenNoSpanOpen`**, the wait `__STACK_FLUSH__` uses. A fetch's span ends 100–300 ms after its response, so each ui step adds up to ~0.5 s to a browser test; the shield makes that wait safe.
- **G3: the record id is the first span id of its batch; `spanIds` are in the order the steps ended** (a click before the submit it triggers, before the route change that follows).
- **G4: `ScreenshotRecord` joins `ModelSchemaRoot`** (`screenshotRecord`), so the file format is in the contract snapshot although the UI never reads it.
- **G5: thumbnails are SVG `<image>` elements**, not `<img loading="lazy">` (spec §4.2): the diagram is one SVG and a run has a handful of screenshots.
- **G6: row spacing.** The distance from one row to the next is `SHOT_ROW_GAP` (76) when either of the two is a screenshot row, else `ROW_GAP` (48). The existing `FIRST_ROW_OFFSET` (36) and `BOTTOM_PADDING` (40) already exceed half a thumbnail (27).

## Global Constraints

- Runs on macOS and Windows (D26): `Path`/`path.join`, no `:` in written names, no shell scripts. No CI; verification is `tool/gradlew -p tool build` and `sandbox/gradlew -p sandbox stackAgent stackBrowser assemble acceptanceTest`, from the repo root.
- `SCHEMA_VERSION` stays **4**. A run without `screenshots.jsonl` reads exactly as before.
- Browser globals: `window.__STACK_SCREENSHOT__?: (spanIds: string[]) => Promise<void>` (exposed by the harness), shield element `<div data-stack-shield>` with `position: fixed; inset: 0; z-index: 2147483647; background: transparent`.
- Values: settle cap **2000 ms** (whole wait, frames included); shield timeout **10 000 ms**; screenshot = viewport PNG; upload ≤ **5 MiB** (5 × 1024 × 1024 bytes) and **1–20** span ids; span id / screenshot id `^[0-9a-f]{16}$`; run id `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`; scenario id `^[a-z0-9][a-z0-9-]*$`; thumbnail **96 × 54** at x = `(MARGIN_X - 96) / 2`; screenshot row gap **76**; system property `stack.reporter.url`, default `http://localhost:4318`.
- Endpoints: reporter `POST /api/runs/{runId}/screenshots?scenarioId=…&spanIds=a,b` (body `image/png`, `201 {"id": …}`, `400 {"error": …}`); viewer `GET /api/runs/{runId}/screenshots/{id}` (`image/png`, `Cache-Control: max-age=31536000, immutable`, else `404`).
- Files in a run folder: `screenshots/<id>.png`, `screenshots.jsonl` (one `ScreenshotRecord` per line).
- Kotlin test names: backtick sentences. TS test names: plain sentences. Test-first in every task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Branch `step-5-screenshots`; push only when the owner asks; merge only after the owner has tried it (Task 10).

## Review Focus

1. **The test navigates away while a screenshot is pending** (`page.navigate` is not held by the shield): neither the test nor the next page may hang or fail. Pinned in Task 6 (`navigating away while a screenshot is pending neither hangs nor fails`).
2. **A screenshot reaches the reporter before the run's first span**: the spans must then join the folder the screenshot created, not open a second one. Pinned in Task 4 (`` `spans after a screenshot join its run` ``).
3. **Two uploads to one run at once**: both PNGs and both `screenshots.jsonl` lines intact. Pinned in Task 4 (`` `concurrent screenshots of one run keep whole lines` ``).
4. **The viewer reads `screenshots.jsonl` while the reporter appends**: a half-written last line is ignored, the rest served. Pinned in Task 8 (`` `a half-written screenshot line is ignored` ``).
5. **A screenshot id that is not a listed span id** (`..%2Fmanifest`, an uppercase id, an id of another run): `404`, never a file outside the run's `screenshots/`. Pinned in Task 8 (`` `unknown or unlisted screenshot is 404` ``).

---

## File Structure

```
sandbox/e2e-tests/src/acceptanceTest/.../SandboxAcceptanceTest.kt   screenshot assertions for the three browser runs
tool/
  model/          Screenshots.kt (NEW: ScreenshotRecord), view/Views.kt (FlowStep.screenshot), schema/SchemaGenerator.kt,
                  test ScreenshotRecordJsonTest.kt (NEW), schema snapshot
  reporter/       api/ScreenshotController.kt (NEW), store/RecordingStore.kt (+addScreenshot),
                  tests api/ScreenshotControllerTest.kt (NEW), store/RecordingStoreTest.kt
  test-harness/   StackBrowser.kt (tag(page, screenshots)), ScreenshotUploader.kt (NEW), tests
  browser-sdk/    src/screenshots.ts (NEW: ScreenshotRequester), src/screenshots.test.ts (NEW), src/stackContext.ts,
                  src/index.ts, e2e/sdk.spec.ts, e2e/fixture/{main.ts,plainSpan.ts}
  viewer-backend/ recordings/{RecordingRepository,internal/FileRecordingRepository}.kt, topology/{FlowBuilder,TopologyService}.kt,
                  web/RecordingsController.kt, tests, fixtures/recordings/<run-6>/{screenshots.jsonl,screenshots/*.png}
  viewer-ui/      src/generated/model.ts, sequence/{layoutSequence.ts,SequenceDiagram.tsx,MessageDetails.tsx,SequenceView.tsx,
                  Screenshot.tsx (NEW)}, styles.css, tests, e2e/fixtures/recordings/<run-6>/ (same files), e2e/frontend.spec.ts
sandbox/README.md, docs/integrate-a-project.md, docs/superpowers/specs/2026-10-27-stack-tool-design.md (§11),
docs/superpowers/notes/2026-11-02-step-5-rulings-and-followups.md (NEW)
```

---

### Task 1: Spike — a screenshot from inside a held click

Throwaway: the test is deleted at the end, only its result is kept (spec §6.1).

**Files:**
- Create (not committed): `tool/test-harness/src/test/kotlin/io/stackvibed/harness/ScreenshotSpikeTest.kt`
- Create: `docs/superpowers/notes/2026-11-02-step-5-rulings-and-followups.md` (a "Spike" section; Task 10 adds the rest)

- [ ] **Step 1: Write the spike.** Real Chromium (`Playwright.create()`, `chromium().launch()`). `page.exposeFunction("__shot") { page.screenshot().size }`; `page.setContent` with a button that records its clicks in `window.clicks` and a script that, on load, adds the shield (`div` with the Global Constraints style), then after 300 ms calls `await __shot()` and only then removes the shield. The test calls `button.click()` immediately and asserts: the click returns, `window.clicks == 1`, the click landed after the shield came down (timestamps on `window`), `__shot` returned a non-zero size, all within 10 s.
- [ ] **Step 2: Run** `tool/gradlew -p tool :test-harness:test --tests '*ScreenshotSpikeTest*'`. Expected: PASS. If it deadlocks, times out, or the click fails on the shield instead of retrying: stop and take the result to the owner (the design depends on both).
- [ ] **Step 3: Record** in the notes: Playwright version, what the click did while the shield was up (its retry log line), how long it waited, that the nested `page.screenshot()` worked.
- [ ] **Step 4: Delete the spike test**, commit the notes — `docs: step 5 spike, a screenshot from inside a click the shield holds`

---

### Task 2: Sandbox — failing screenshot assertions

**Files:**
- Modify: `sandbox/e2e-tests/src/acceptanceTest/kotlin/io/stackvibed/sandbox/e2e/SandboxAcceptanceTest.kt`

**Interfaces:**
- Produces (test-private): `Recording` gains `folder: Path`; `awaitScreenshots(recording: Recording, records: Int, timeout = 10 s): List<Map<String, Any?>>` polls `<folder>/screenshots.jsonl` until it has `records` lines; `uiLabel(event)` gives `load <route>`, `→ <route>` or `<event> <target>`, as the viewer labels steps.

- [ ] **Step 1: Failing assertions** added at the end of the three browser tests. For each run: every `ui.*` event's `spanId` appears in exactly one record; each record's `id` equals its first span id and its `file` is `screenshots/<id>.png`; that file exists and starts with the PNG signature `89 50 4E 47 0D 0A 1A 0A`. The groups, as lists of `uiLabel`s of each record's `spanIds` in order:
  - `browse-stock`: `[["load /"]]`
  - `place-order-in-browser`: `[["load /"], ["click to-order", "→ /order"], ["click place-order", "submit order-form", "→ /orders/<id>"]]`
  - `place-order-out-of-stock-in-browser`: `[["load /order"], ["click place-order", "submit order-form"]]`
- [ ] **Step 2: Run and see the expected red.**

Run: `tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage && sandbox/gradlew -p sandbox stackAgent stackBrowser assemble acceptanceTest`
Expected: the three browser tests FAIL in `awaitScreenshots` (no `screenshots.jsonl`); every other assertion and test passes.

- [ ] **Step 3: Commit** — `test(sandbox): failing step 5 screenshot assertions`

---

### Task 3: Model — `ScreenshotRecord`, `FlowStep.screenshot`

**Files:**
- Create: `tool/model/src/main/kotlin/io/stackvibed/model/Screenshots.kt`, `tool/model/src/test/kotlin/io/stackvibed/model/ScreenshotRecordJsonTest.kt`
- Modify: `tool/model/src/main/kotlin/io/stackvibed/model/{view/Views.kt,schema/SchemaGenerator.kt}`, `tool/model/src/test/resources/schema-snapshot/model.schema.json`, `tool/viewer-ui/src/generated/model.ts` (`npm run gen:types`)

**Interfaces:**
- Produces:

```kotlin
/** One line of a run's `screenshots.jsonl`: the screenshot the harness took once the ui steps [spanIds] had settled. */
data class ScreenshotRecord(val id: String, val file: String, val spanIds: List<String>, val takenAt: Instant) {
    companion object {
        /** A span id, and so a screenshot id (the first span id of its batch). */
        val ID: Regex = Regex("^[0-9a-f]{16}$")
        fun fileOf(id: String): String = "screenshots/$id.png"
    }
}
// FlowStep, last parameter:
/** On a ui step: the id of the screenshot of its result; null on other steps and when none was taken. */
val screenshot: String? = null,
// ModelSchemaRoot: val screenshotRecord: ScreenshotRecord
```

- [ ] **Step 1: Failing tests.** `` `screenshot record round-trips` `` (exactly `{"id":"04b7ffff7cd2b57d","file":"screenshots/04b7ffff7cd2b57d.png","spanIds":["04b7ffff7cd2b57d","d6c8a8bdd007c68c"],"takenAt":"2026-11-02T10:00:00Z"}`); `` `screenshot ids are span ids only` `` (`04b7ffff7cd2b57d` matches; `04B7FFFF7CD2B57D`, `04b7ffff7cd2b57`, `../manifest`, `04b7ffff7cd2b57d.png` do not).
- [ ] **Step 2: Run** `tool/gradlew -p tool :model:test`. Expected: FAIL (no `ScreenshotRecord`).
- [ ] **Step 3: Implement**, refresh the snapshot as `SchemaSnapshotTest` says, `npm run gen:types` in `tool/viewer-ui`.
- [ ] **Step 4: Run** `tool/gradlew -p tool :model:test :reporter:test :viewer-backend:test :viewer-ui:npmTest :viewer-ui:npmBuild`. Expected: PASS (nothing sets `screenshot` yet).
- [ ] **Step 5: Commit** — `feat(model): screenshot records and the screenshot of a ui step`

---

### Task 4: Reporter — storing screenshots

**Files:**
- Create: `tool/reporter/src/main/kotlin/io/stackvibed/reporter/api/ScreenshotController.kt`, `tool/reporter/src/test/kotlin/io/stackvibed/reporter/api/ScreenshotControllerTest.kt`
- Modify: `tool/reporter/src/main/kotlin/io/stackvibed/reporter/store/RecordingStore.kt`, `tool/reporter/src/test/kotlin/io/stackvibed/reporter/store/RecordingStoreTest.kt`

**Interfaces:**
- Consumes: `ScreenshotRecord` (Task 3), `RunAssignment(runId, scenarioId, RecordingKind.E2E)`.
- Produces: `RecordingStore.addScreenshot(assignment: RunAssignment, spanIds: List<String>, png: ByteArray): ScreenshotRecord`. Under the run's lock: `openRun` (so a screenshot creates the run as a span would), write `screenshots/<spanIds.first()>.png`, append one line to `screenshots.jsonl`, `takenAt = clock.instant()`. The manifest is not touched.
- Produces: `@RestController class ScreenshotController(store: RecordingStore)` with `@PostMapping("/api/runs/{runId}/screenshots", consumes = ["image/png"])`, params `scenarioId: String`, `spanIds: List<String>` (comma-separated), body `ByteArray`. Validates in the order run id, scenario id, span ids (pattern each, 1–20), body (≤ 5 MiB, PNG signature); answers `400 {"error": "<what is wrong>"}` or `201 {"id": "<id>"}`.

- [ ] **Step 1: Failing tests.** Store: `` `a screenshot is written with its record` `` (file bytes, one line with `id`, `file`, `spanIds`, `takenAt` from the fixed clock); `` `spans after a screenshot join its run` `` (`addScreenshot` then `append` of an event with the same run id → one folder, holding the PNG, `screenshots.jsonl` and `events.jsonl`); `` `concurrent screenshots of one run keep whole lines` `` (20 threads, distinct span ids → 20 parseable lines, 20 files). Controller (`@SpringBootTest(RANDOM_PORT)`, `java.net.http`, as `CorsTest`): `` `an uploaded screenshot is stored` `` (`201`, `{"id":…}`, file in the run folder); `` `a screenshot before any span creates the run` `` (manifest with the scenario id and kind `e2e`); `` `invalid uploads are refused` `` (each → `400`: run id `run-1`, scenario `Browse`, span id `04B7FFFF7CD2B57D`, no span ids, 21 span ids, a JPEG's bytes, 5 MiB + 1 bytes of PNG).
- [ ] **Step 2: Run** `tool/gradlew -p tool :reporter:test`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(reporter): store screenshots of a run`

---

### Task 5: Test harness — shoot and upload

**Files:**
- Create: `tool/test-harness/src/main/kotlin/io/stackvibed/harness/ScreenshotUploader.kt`, `tool/test-harness/src/test/kotlin/io/stackvibed/harness/ScreenshotUploaderTest.kt`
- Modify: `tool/test-harness/src/main/kotlin/io/stackvibed/harness/StackBrowser.kt`, `tool/test-harness/src/test/kotlin/io/stackvibed/harness/StackBrowserTest.kt`

**Interfaces:**
- Consumes: `StackContext.current()`, reporter endpoint (Task 4).
- Produces: `internal class ScreenshotUploader(reporterUrl: String = System.getProperty("stack.reporter.url", "http://localhost:4318"), http: HttpClient = HttpClient.newHttpClient())` with `fun upload(run: ScenarioRun, spanIds: List<String>, png: ByteArray)`: POST as in Global Constraints, `Content-Type: image/png`, 10 s timeout, throws `IOException` on any status but 201.
- Produces: `@JvmOverloads fun tag(page: Page, screenshots: Boolean = true)`. With screenshots, it captures the run at tag time and calls `page.exposeFunction("__STACK_SCREENSHOT__") { args -> … ; null }`: `args[0]` is the list of span ids, the handler shoots `page.screenshot()` and uploads; any exception is logged once per page (`System.getLogger(StackBrowser::class.java.name)`, WARNING) and swallowed, so the page's call resolves.

- [ ] **Step 1: Failing tests.** `ScreenshotUploaderTest` (fake reporter on `HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)`): `` `posts the png with the run, scenario and span ids` `` (path `/api/runs/<runId>/screenshots`, query `scenarioId=browse-stock&spanIds=04b7ffff7cd2b57d,d6c8a8bdd007c68c`, content type, body); `` `a refused upload throws` `` (400 → `IOException`). `StackBrowserTest` (MockK `Page`, reporter URL set as the system property to a fake): `` `tag exposes the screenshot function by default` ``; `` `tag without screenshots exposes nothing` ``; `` `the screenshot function shoots the page and uploads it` `` (invoke the captured `FunctionCallback` with `listOf("04b7ffff7cd2b57d")`; the fake receives `page.screenshot()`'s bytes for the run tagged); `` `a failing upload does not fail the page's call` `` (fake answers 500; the callback returns null, nothing thrown). The existing three tests keep passing.
- [ ] **Step 2: Run** `tool/gradlew -p tool :test-harness:test`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(harness): StackBrowser shoots each settled ui step and uploads it`

---

### Task 6: Browser package — batches, settle, shield

**Files:**
- Create: `tool/browser-sdk/src/screenshots.ts`, `tool/browser-sdk/src/screenshots.test.ts`
- Modify: `tool/browser-sdk/src/{stackContext.ts,index.ts}`, `tool/browser-sdk/e2e/{sdk.spec.ts,fixture/main.ts,fixture/plainSpan.ts}`

**Interfaces:**
- Consumes: `NAVIGATION_SPAN`, `ACTION_SPAN` (`attributes.ts`), `StackSpanProcessor.whenNoSpanOpen`.
- Produces (`screenshots.ts`): `SETTLE_CAP_MILLIS = 2000`, `SHIELD_TIMEOUT_MILLIS = 10_000`, `SHIELD_ATTRIBUTE = 'data-stack-shield'`, and

```ts
/** Asks the harness for one screenshot per settle of the page's ui steps, holding Playwright's clicks meanwhile. */
export class ScreenshotRequester implements SpanProcessor {
  /** `settled` resolves once the page has no span open (it may resolve late: the cap is applied here). */
  constructor(settled: () => Promise<void>);
  onStart(): void;
  /** A ui span (NAVIGATION_SPAN or ACTION_SPAN) joins the pending batch, when window.__STACK_SCREENSHOT__ is a function. */
  onEnd(span: ReadableSpan): void;
  /** Resolves once no batch is settling and no screenshot call is in flight. */
  whenTaken(): Promise<void>;
  forceFlush(): Promise<void>;
  shutdown(): Promise<void>;
}
```

  The first step of a batch lays the shield (one element, appended to `document.documentElement`) and starts the settle: `settled()` raced against the cap, then two `requestAnimationFrame`s (still within the cap); the batch is then closed and passed to `__STACK_SCREENSHOT__`, whose promise is raced against `SHIELD_TIMEOUT_MILLIS`; a rejection is ignored. The shield is up while a batch is settling or a call is in flight. Steps after a batch closed start the next one.
- Produces (`stackContext.ts`): `__STACK_SCREENSHOT__?: (spanIds: string[]) => Promise<void>` on `Window`, documented as exposed by `StackBrowser.tag`.
- Produces (`index.ts`): processors `[stackProcessor, screenshots, batch]` with `screenshots = new ScreenshotRequester(() => stackProcessor.whenNoSpanOpen(SETTLE_CAP_MILLIS))`; `__STACK_FLUSH__` first awaits `screenshots.whenTaken()`.
- Produces (fixture): `PlainSpan.spanId`.

- [ ] **Step 1: Failing unit tests** (jsdom, a `TracerProvider` with the requester and a `StackSpanProcessor`, fake timers including `requestAnimationFrame` where time matters): `steps that settle together share one screenshot call` (a click span and a navigation span end while a fetch span is open; ending it gives one call with both span ids, click first); `a step after the call starts a new batch`; `a page that never settles is shot after 2 s`; `the shield covers the page from the step until the call answers`; `the shield comes down after 10 s when the call never answers`; `a rejected call is ignored` (no throw, shield down, `whenTaken` resolves); `other spans start no batch`; `without the harness function nothing waits and no shield is laid`; `whenTaken waits for a batch settling and a call in flight`.
- [ ] **Step 2: Failing browser tests** in `e2e/sdk.spec.ts` (the fixture page, `page.exposeFunction('__STACK_SCREENSHOT__', …)` before `goto`, recording each call's ids and answering after 300 ms): `screenshot calls group the steps as they settle` (`[load]`, then `[click go, → /next]` by span id); `a Playwright click waits for the shield` (the click span starts after the first call answered); `flush waits for a screenshot in flight`; `navigating away while a screenshot is pending neither hangs nor fails` (the function never answers; `page.goto` to the fixture again succeeds within 5 s).
- [ ] **Step 3: Run** `tool/gradlew -p tool :browser-sdk:npmTest :browser-sdk:e2eTest`. Expected: FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run** the same command. Expected: PASS, and the existing tests unchanged (no harness function there: no shield, no waits).
- [ ] **Step 6: Commit** — `feat(browser-sdk): ask for a screenshot once ui steps settle, shield clicks until it is taken`

---

### Task 7: Sandbox — the screenshot assertions turn green

**Files:** none expected; fixes go to the task that owns the code, with its own test.

- [ ] **Step 1: Run** `tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage && sandbox/gradlew -p sandbox stackAgent stackBrowser assemble acceptanceTest`. Expected: PASS, all tests.
- [ ] **Step 2: Look at the PNGs** of one `place-order-in-browser` run in `sandbox/recordings/`: `load /` shows the stock table, the second the order form, the third the order page. Note in the step-5 notes how long each browser test took before and after (G2).
- [ ] **Step 3: Keep three of them** (one per record of that run) for the fixtures in Task 8.
- [ ] **Step 4: Commit** the notes — `docs: step 5 acceptance timings`

---

### Task 8: Viewer backend — screenshot on ui steps, PNG endpoint

**Files:**
- Modify: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/recordings/{RecordingRepository.kt,internal/FileRecordingRepository.kt}`, `…/topology/{FlowBuilder.kt,TopologyService.kt}`, `…/web/RecordingsController.kt`
- Modify: tests `recordings/FileRecordingRepositoryTest.kt`, `topology/FlowBuilderTest.kt`, `web/ApiTest.kt`
- Create: `tool/viewer-backend/src/test/resources/fixtures/recordings/2026-10-31T10-00-00_place-order-in-browser_run-6/{screenshots.jsonl,screenshots/<id>.png}`: three records over run-6's ui span ids, grouped as `place-order-in-browser` in Task 2, PNGs from Task 7 renamed to those ids.

**Interfaces:**
- Consumes: `ScreenshotRecord`, `FlowStep.screenshot` (Task 3).
- Produces: `RecordingRepository.screenshots(runId: String): List<ScreenshotRecord>` (file order; none when the file is absent; unreadable lines skipped like event lines, a half-written last one silently) and `RecordingRepository.screenshot(runId: String, id: String): Path?` (null unless `id` matches `ScreenshotRecord.ID`, is a listed record id and its file exists). Both throw `NoSuchElementException` for an unknown run, as `events` does.
- Produces: `FlowBuilder.build(manifest, events, aliases, screenshots: List<ScreenshotRecord> = emptyList())`: a ui step gets the id of the record whose `spanIds` contain its event's `spanId`. `TopologyService.flow` passes `recordings.screenshots(runId)`.
- Produces: `@GetMapping("/api/runs/{runId}/screenshots/{id}")` returning the file as `image/png` with `CacheControl.maxAge(365, DAYS).cachePublic().immutable()`; `404` when `screenshot` is null.

- [ ] **Step 1: Failing tests.** Repository: `` `screenshots of a run are read in order` ``; `` `a run without screenshots has none` ``; `` `a half-written screenshot line is ignored` ``; `` `screenshot files are found by listed id only` `` (listed → path; unlisted valid id, `../manifest`, uppercase → null). Flow: `` `ui steps carry the screenshot that lists their span` `` (two steps in one record share its id); `` `other steps never carry a screenshot` `` (a record listing an http span id leaves that step null); `` `a run without screenshots builds as before` ``. API: `` `screenshot of a run is served as png` `` (run-6, a listed id: `200`, `image/png`, the file's bytes, `Cache-Control` containing `immutable`); `` `unknown or unlisted screenshot is 404` `` (`..%2Fmanifest.json`, an unlisted id, an unknown run); `` `flow endpoint returns the steps of a run` `` also checks `screenshot` on run-6's `load /` step.
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-backend:test`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS (including `ArchitectureTest`/`ModularityTest`: the controller reaches recordings only through `RecordingRepository`).
- [ ] **Step 5: Commit** — `feat(viewer): screenshots on ui steps and served per run`

---

### Task 9: Viewer UI — thumbnails on the timeline, image in the panel

**Files:**
- Create: `tool/viewer-ui/src/sequence/Screenshot.tsx`
- Modify: `tool/viewer-ui/src/sequence/{layoutSequence.ts,SequenceDiagram.tsx,MessageDetails.tsx,SequenceView.tsx}`, `src/styles.css`, tests `layoutSequence.test.ts`, `SequenceDiagram.test.tsx`, `MessageDetails.test.tsx`
- Create: `tool/viewer-ui/e2e/fixtures/recordings/2026-10-31T10-00-00_place-order-in-browser_run-6/{screenshots.jsonl,screenshots/*.png}` (the Task 8 files)
- Modify: `tool/viewer-ui/e2e/frontend.spec.ts`

**Interfaces:**
- Consumes: `FlowStep.screenshot`, `GET /api/runs/{runId}/screenshots/{id}` (Task 8).
- Produces (`layoutSequence.ts`): `THUMB_WIDTH = 96`, `THUMB_HEIGHT = 54`, `SHOT_ROW_GAP = 76`; `SequenceMessage.screenshot: string | null`, set on the request row of a ui step with a screenshot; row positions and height by G6.
- Produces (`Screenshot.tsx`): `screenshotUrl(runId: string, id: string): string` (`/api/runs/${encodeURIComponent(runId)}/screenshots/${id}`); `ScreenshotImage({ runId, id, alt })`: the image at panel width; a click opens an overlay (`role="dialog"`, `aria-label="Screenshot"`) with the image at its own size, scrollable, closed by Esc, a close button or a click outside; a load error replaces the image with the text "Screenshot not available".
- Produces: `MessageDetails` takes `runId: string`; on a ui step with a screenshot it renders `ScreenshotImage` between the header and the facts. `SequenceView` passes `runId`.
- Produces (`SequenceDiagram`): per message with a screenshot, a `<g role="button" aria-label="screenshot <label>">` holding an `<image>` at `x = (MARGIN_X - THUMB_WIDTH) / 2`, `y = m.y - THUMB_HEIGHT / 2`, 96 × 54, `preserveAspectRatio="xMidYMid slice"`, and a border `rect` with class `is-selected` when its step's request is selected; click and Enter/Space select `{ stepId, kind: 'request' }`. An image that fails to load is dropped, leaving the border dashed (class `is-missing`) with the `<title>` "Screenshot not available".

- [ ] **Step 1: Failing tests.** Layout: `a ui row with a screenshot is taller` (rows: ui step with screenshot, then an http request and its response → `y` 100, 176, 224; height 264); `a row after a screenshot row is also kept apart` (two ui steps with screenshots → 100, 176); `a run without screenshots keeps its rows` (as today's expectations); `only the request row of a ui step carries its screenshot`. Diagram: `a thumbnail sits beside its ui arrow and selects the step` (`href` `/api/runs/run-6/screenshots/<id>`, x 7, y `m.y - 27`; click → `onSelect({ stepId, kind: 'request' })`); `a selected step's thumbnail is outlined`; `a thumbnail that fails to load says so` (error event on the image → no `<image>`, border `is-missing`, title "Screenshot not available"). Details: `a ui step shows its screenshot and opens it full size` (img `src`; click → dialog "Screenshot"; Esc closes it); `a screenshot that fails to load says so`; `a ui step without a screenshot shows no image`.
- [ ] **Step 2: Failing e2e** in `frontend.spec.ts`: `sequence shows a screenshot beside each browser step and in the details` (`/sequence/run-6`: one `screenshot …` button per ui step; its image loads (`naturalWidth > 0`); clicking `screenshot load /` selects `request load /` and the details show the image; clicking it opens the dialog).
- [ ] **Step 3: Run** `tool/gradlew -p tool :viewer-ui:npmTest`. Expected: FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run** `tool/gradlew -p tool :viewer-ui:npmTest :viewer-ui:e2eTest`. Expected: PASS, the existing sequence tests unchanged.
- [ ] **Step 6: Commit** — `feat(viewer-ui): screenshots beside ui steps and in the details panel`

---

### Task 10: Docs, notes, and the owner's try-out

**Files:**
- Modify: `sandbox/README.md`, `docs/integrate-a-project.md`, `docs/superpowers/specs/2026-10-27-stack-tool-design.md` (§11 "Step 5 — screenshots of the frontend's ui steps", pointing to the step-5 spec), `docs/superpowers/notes/2026-11-02-step-5-rulings-and-followups.md`

- [ ] **Step 1: README.** In the frontend paragraph: browser runs record a screenshot of each ui step's result, shown beside the arrows and in the details of the sequence view; manual runs have none; `-Dstack.reporter.url=<url>` when the reporter is not on `localhost:4318`.
- [ ] **Step 2: Integration guide.** In "Frontends": `StackBrowser.tag(page, screenshots = false)` to turn them off; the shield (why a click may wait up to ~2 s after a step); `StackBrowser.flush(page)` at the end of a journey also waits for the last screenshot; the reporter URL property.
- [ ] **Step 3: Notes.** Rulings G1–G6 in the step-4 notes' format (what, why, cost if wrong), the spike and timings from Tasks 1 and 7; deferred follow-ups: keyboard-only steps under the shield, screenshots of manual runs, a size or age limit on stored screenshots, merging identical screenshots.
- [ ] **Step 4: Verify** `tool/gradlew -p tool build` and `sandbox/gradlew -p sandbox stackAgent stackBrowser assemble acceptanceTest`. Expected: PASS.
- [ ] **Step 5: Commit** — `docs: step 5 screenshots in the README, integration guide and notes`
- [ ] **Step 6: Owner try-out (do not merge before their OK).** Rebuild the tool (`tool/gradlew -p tool publishToMavenLocal :reporter:dockerImage :viewer-backend:viewerDist`), restart the stack (`docker compose -f sandbox/docker-compose.yml up -d --build --wait`), run `sandbox/gradlew -p sandbox e2eTest`, restart the viewer on `sandbox/stack` and `sandbox/recordings`, open a `place-order-in-browser` run in the sequence view for the owner, and wait for their approval.
