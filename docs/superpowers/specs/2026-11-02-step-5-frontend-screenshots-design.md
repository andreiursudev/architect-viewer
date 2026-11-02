# Step 5 — screenshots of the frontend's ui steps in the sequence view

Date: 2026-11-02
Status: approved in conversation, written spec for review
Parent spec: `2026-10-27-stack-tool-design.md` (D1, D8, D9); builds on `2026-10-31-step-4-frontend-design.md`

## 1. Intent

The owner wants to see the frontend's screens in the sequence view. A browser run already reads as the user would
(`load /`, `click to-order`, `→ /order`, `submit order-form`, `→ /orders/<id>`), but only as labels: what the page
looked like at each step is not recorded.

Success: opening a `place-order-in-browser` run in the sequence view shows a thumbnail of the page beside each ui
arrow, and selecting a ui step shows its screenshot large in the details panel, from which it opens at full size.

Decided with the owner:

- **Placement A + B**: a thumbnail on the timeline, in the margin left of the client lifeline, on the row of its ui
  step (B); the screenshot at panel width in the details panel when the step is selected (A). Rejected: a filmstrip
  above the diagram (C), which separates the screens from the arrows they belong to.
- **Moment: the result of the step.** A screenshot is taken once the step and the calls it triggered have settled:
  `load /` shows the filled stock table, `submit order-form` the order page. Rejected: the page just before a click,
  with the clicked spot marked (only the harness can shoot before a click lands, so scenarios would have to drive a
  wrapped `Page`), and both before and after.
- **Transport: the harness uploads to the reporter.** Only the test JVM can take a real screenshot (it holds the
  Playwright `Page`); only the browser package knows when a ui step happened and when it settled. The page asks, the
  harness shoots and uploads, the reporter stores. The reporter stays the only writer of `recordings/` (D9).
  Rejected: the harness writing into `recordings/` itself (it would need the folder and the reporter's naming, and
  breaks when the reporter is not on the same disk), and the image as a span attribute through OTLP (a click's span is
  exported before the step settles, and 50–100 KB of base64 per span batch).
- **Tagged Playwright runs only.** Manual clicking inside a recording window gets no screenshots: a page cannot take a
  real screenshot of itself.

## 2. Capture

### 2.1 Browser package (`tool/browser-sdk`)

- After each ui step ends (`ui.navigation` load or route, `ui.action` click or submit), its span id joins the pending
  batch, and the package waits for the page to settle: no span open (`StackSpanProcessor.whenNoSpanOpen`, the wait
  `__STACK_FLUSH__` already uses), then two animation frames so React has painted, capped at 2 s in all.
- Then it calls `window.__STACK_SCREENSHOT__(spanIds)` with every span id in the batch, and starts a new batch. Steps
  that settle together share one screenshot: `click to-order` and `→ /order` both get the order form.
- Only when `window.__STACK_SCREENSHOT__` is a function, which only the harness defines (§2.2). Without it nothing
  waits and nothing is called: manual runs and the app in production are unchanged.
- The package keeps the promises of the calls still in flight; `__STACK_FLUSH__` waits for them, and for a batch still
  settling, before it exports. Scenarios already end with `StackBrowser.flush`, so the last screenshot is stored before
  the test (and the run) ends.
- A rejected call is ignored; the page is never affected.

### 2.2 Test harness: `StackBrowser`

- `StackBrowser.tag(page, screenshots = true)` also exposes `__STACK_SCREENSHOT__` on the page
  (`page.exposeFunction`), before the first navigation like the init script.
- Its handler takes a viewport screenshot (`page.screenshot()`, PNG, the context's viewport, not the full page) and
  sends it to the reporter (§2.3) with the run and scenario of the current test, from `StackContext`.
- The reporter URL is the system property `stack.reporter.url`, default `http://localhost:4318`: the default of the
  agent extension and the browser package.
- `screenshots = false` exposes nothing, so the page never waits for a screenshot.
- A failed screenshot or upload is logged (one warning per page) and never fails the test; the step has no image.

### 2.3 Reporter

- `POST /api/runs/{runId}/screenshots?scenarioId=<slug>&spanIds=<id>,<id>…`, body `image/png`.
  - Validated: `runId` a UUID, `scenarioId` a slug (as the control API), each span id 16 lowercase hex characters, 1–20
    span ids, the body a PNG (signature) of at most 5 MB. Otherwise `400`.
  - Creates the run's folder and manifest when no span of the run has arrived yet (spans come in 200 ms batches):
    `ensureRun(RunAssignment(runId, scenarioId, E2E))`, the assignment the baggage path builds.
  - Writes `screenshots/<id>.png`, where `<id>` is the first span id, and appends one line to `screenshots.jsonl`
    (§3) under the run's lock. Answers `201` with the id.
- No CORS: the caller is the test JVM, not a page. Like the rest of the reporter, it is published on `127.0.0.1` only.

## 3. Model

- New `ScreenshotRecord`, one line of `screenshots.jsonl`: `id`, `file` (`screenshots/<id>.png`), `spanIds`,
  `takenAt`.
- `FlowStep` gains `screenshot: String?`, the record id, set on ui steps only.
- `schemaVersion` stays 4: the new file is additive, and a run without it (every run so far, manual runs) reads as
  before. JSON-schema snapshot and generated TS types updated.

## 4. Viewer

### 4.1 Backend

- `RecordingRepository` reads a run's `screenshots.jsonl` (absent: none); a malformed line is skipped, as malformed
  event lines are.
- `FlowBuilder` sets `screenshot` on each ui step whose span id a record lists.
- `GET /api/runs/{runId}/screenshots/{id}` serves the PNG (`image/png`, cacheable: a stored screenshot never
  changes). `id` must be 16 lowercase hex characters and listed in the run's records, so the path can reach no other
  file; otherwise `404`.

### 4.2 Timeline (`layoutSequence`, `SequenceDiagram`)

- A ui row with a screenshot is taller, 76 px instead of `ROW_GAP` (48 px); every other row keeps its height. Rows
  therefore get their own heights: the main change to `layoutSequence`.
- Its 96×54 thumbnail sits in the existing left margin (`MARGIN_X`, 110 px), centred on the row's arrow, with
  `loading="lazy"`.
- Clicking the thumbnail selects the step, as clicking its request arrow does; the selected step's thumbnail is
  outlined.
- A screenshot shared by several steps shows on each of their rows.
- A run with no screenshots lays out exactly as today.

### 4.3 Details panel (`MessageDetails`)

- A selected ui step with a screenshot shows it at panel width between the header and the facts.
- Clicking it opens it at its own size in an overlay inside the viewer (scrollable when larger than the window);
  Esc, the close button or a click outside closes it.
- A screenshot that fails to load shows "Screenshot not available" in its place.

## 5. Error handling

- **Reporter unreachable from the harness**: one warning per page, the test goes on, the steps have no images.
- **Page never settles** (a polling fetch, a span left open): the 2 s cap; the screenshot shows the page as it is then.
- **Screenshot arrives before the run's first span**: the reporter creates the run (§2.3).
- **Invalid upload**: `400`, logged by the harness.
- **Missing or unreadable image in a run**: the step's thumbnail and panel image say so; the run still opens.

## 6. Testing (test-first)

1. **Spike first** (throwaway, results in the step-5 notes): can the `exposeFunction` handler call
   `page.screenshot()` from Playwright Java while the test thread is inside a `click`/`fill`, without deadlocking? If
   not, the handler queues the request and the harness shoots it on the test thread at the next Playwright call or
   at `flush`; the spike picks one.
2. **Acceptance**: `SandboxAcceptanceTest` checks that the three browser scenarios recorded a screenshot for each of
   their ui steps (one record per settle, every ui span id covered), red first.
3. **browser-sdk**: Vitest for the batching (steps that settle together share one call; a step after the call starts
   a new batch), the 2 s cap, flush waiting for calls in flight, and no wait without `__STACK_SCREENSHOT__`; the
   Playwright fixture test asserts the calls the page makes.
4. **Harness**: `StackBrowserTest` against a fake reporter (the request, the PNG, `screenshots = false`, upload failure
   not failing the test).
5. **Reporter**: controller tests (validation, `201`), store tests (folder created by a screenshot, record lines,
   concurrent uploads to one run).
6. **Viewer backend**: `FlowBuilder` (screenshot on ui steps, shared records, none) and the endpoint (served, unknown
   id, id not in the run).
7. **Viewer UI**: Vitest for the row heights and thumbnail positions, the thumbnail selecting its step, the panel
   image and overlay; Playwright on a fixture run with screenshots.
8. **Contract**: schema snapshot change.

## 7. Docs

- Sandbox README: screenshots in the browser runs, where to see them, `stack.reporter.url`, and that manual runs have
  none.
- `docs/integrate-a-project.md`: `StackBrowser.tag(page, screenshots = …)`, `flush` at the end of a journey, the
  reporter URL.
- Parent spec §11: a step 5 entry pointing here.

## 8. Out of scope

Screenshots of manual runs; the page before a click and a click marker; full-page screenshots; video or traces;
visual diffing between runs; merging identical screenshots of steps that did not settle together; a size or age limit
on stored screenshots.
