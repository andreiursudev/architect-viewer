# Step 5 — rulings and deferred follow-ups

Decisions made on the owner's behalf while building screenshots of the frontend's ui steps (spec
`docs/superpowers/specs/2026-11-02-step-5-frontend-screenshots-design.md`, plan
`docs/superpowers/plans/2026-11-02-step-5-frontend-screenshots.md`), and minor review findings deliberately deferred.
Source: the execution ledger (deleted after completion). Branch `step-5-screenshots`.

## Rulings

- Ruling G1: the shield, a transparent full-viewport element laid from each ui step until its screenshot is taken, so Playwright's next click waits (spec §2.1, the owner's choice while planning) — why: the screenshot is requested by the page but taken by the test JVM, asynchronously, and a Playwright test does not wait: it clicked `to-order` milliseconds after the stock table showed, so `load /` would have been shot showing the order form — cost if wrong: a click under the harness waits up to ~2 s after each step (10 s if the harness never answers); a step started from the keyboard alone is not held and joins the pending batch.
- Ruling G2: settled = no span open (`StackSpanProcessor.whenNoSpanOpen`, as `__STACK_FLUSH__` waits) — why: the package already tracks open spans; a fetch's span ends 100–300 ms after its response — cost if wrong: each batch costs that wait (about 0.4 s per three-batch journey, see Acceptance).
- Ruling G3: a record's id is the first span id of its batch; `spanIds` are in the order the steps ended (a click before the submit it triggers, before the route change that follows) — cost if wrong: none.
- Ruling G4: `ScreenshotRecord` joins `ModelSchemaRoot` (`screenshotRecord`), so the file format is in the contract snapshot although the UI never reads it — cost if wrong: none.
- Ruling G5: thumbnails are SVG `<image>` elements, not `<img loading="lazy">` (spec §4.2) — why: the diagram is one SVG and a run has a handful of screenshots — cost if wrong: every thumbnail of a run loads when it opens (16–21 KB each).
- Ruling G6: two neighbouring rows are `SHOT_ROW_GAP` (76 px) apart when either has a thumbnail, else `ROW_GAP` (48 px); the existing first-row offset (36) and bottom padding (40) already exceed half a thumbnail (27) — cost if wrong: none.
- Ruling G7: a batch settles only when no span is open after its two frames as well, looping to the 2 s deadline; `ScreenshotRequester` takes the `StackSpanProcessor` (new `hasOpenSpan()`) instead of a `settled()` function as the plan said — why: the first green attempt shot `[click place-order, submit order-form]` before `→ /orders/<id>`: the settle began in the click's capture-phase listener, before the submit handler opened its `POST`, so "no span open" held at once; pinned by the unit test `a fetch the step's own handler starts holds the batch` and the acceptance grouping — cost if wrong: a page that keeps a span open every frame is shot at the 2 s cap.
- Ruling G8: the harness's exposed function answers `true` (stored) or `false` (failed), not `null` — why: Playwright's `FunctionCallback` is non-null in Kotlin; the page ignores the answer — cost if wrong: none.
- Ruling G9: Docker builds ran with `DOCKER_CONFIG` pointing at a config without `credsStore` (README troubleshooting): the desktop credential helper hung `buildx` on the `eclipse-temurin:25-jre` pull — cost if wrong: none; the owner's `~/.docker/config.json` is untouched.
- Ruling G10: the viewer-ui e2e test was written after the code; its power was checked by switching the panel image off (that test failed, the other 17 passed) and restoring it. Thumbnails are drawn after the arrows in the SVG, so a row's arrow comes first for keyboard and screen reader (the existing e2e `sequence shows the browser steps first` relies on it) — cost if wrong: none.
- Ruling G11: the run-6 fixtures (viewer-backend and viewer-ui) use three real screenshots from an acceptance run, renamed to run-6's span ids; the order page shows another order id than run-6's events — cost if wrong: a cosmetic mismatch in a fixture.
- Ruling G12 (final review): input that skips Playwright's hit-test (a double click's second click, a `force` click, `page.mouse`) within ~2 s of a ui step lands on the shield; the app never sees it. The recorder no longer records such a click as a ui step (it was a phantom `click div`), the shield warns once on the console naming `screenshots = false`, and the integration guide documents it — why: the shield cannot tell these apart from a click it must hold; forwarding them to the element underneath would mean re-dispatching untrusted events into the app — cost if wrong: an integrator's tagged test with a double click breaks until it opts out (the warning says how). Pinned by `a click that lands on the shield is no ui step and warns once`.
- Ruling G13 (final review): elements in the browser's top layer (a native modal `<dialog>`, a `popover`, fullscreen) sit above the shield, so clicks inside them are not held and their screenshots can race the next click; documented, not fixed — why: making the shield a popover would not help inside a modal dialog, which makes everything outside it inert — cost if wrong: screenshots of steps inside such dialogs may show the state after the next click. The sandbox has none.
- Ruling G14 (final review, a Minor re-graded Important): `StackBrowser.tag` on a page already tagged no longer throws ("already registered") and no longer shoots for the first test's run: a page exposes the function once and a weak page→run map says which run it shoots for (`screenshots = false` on a re-tag stops them) — why: a suite reusing one page across tests broke on upgrade — pinned by `tagging a page again exposes the function once and shoots for the latest run` and `tagging a page again without screenshots stops them`.

## Deferred minors (final review)

- The `whenNoSpanOpen` doc comment sits above `hasOpenSpan` in `StackSpanProcessor.ts` (two comments back to back).
- More than 20 ui steps settling in one batch exceed the reporter's limit and lose that screenshot (the page neither caps nor splits a batch).
- The reporter reads the whole upload into memory before its 5 MB check (no `Content-Length` check first); loopback only.
- A wrong `Content-Type` gets 415 from Spring, not the spec's 400.
- Each screenshot GET rescans every run's manifest and the run's `screenshots.jsonl` (O(runs) per image; the immutable cache hides repeats).
- `ScreenshotUploader` makes an `HttpClient` per tagged page and never closes it.
- An over-long comment line in `screenshots.ts`.
- A navigation while the harness is shooting may label the new page's screenshot with the old steps' ids.

## Deferred follow-ups

- Keyboard-only steps (a submit by Enter in a field) are not held by the shield.
- Screenshots of manual runs (a page cannot take a real screenshot of itself).
- A size or age limit on stored screenshots.
- Merging identical screenshots of steps that did not settle together.

## Spike (spec §6.1)

Playwright Java 1.64.0, Chromium, macOS. A page that lays the shield on load, asks `__shot()` (exposed with
`page.exposeFunction`) for a screenshot 300 ms later and removes the shield only once it answered; the test clicks a
button under the shield at once.

- The click waited, retrying, instead of failing: it returned after 384 ms, and the button saw it 36 ms after the shield
  came down (one click, `clicks == 1`). Playwright logs the retries only with `DEBUG=pw:api` ("… intercepts pointer
  events, retrying click action"); without it the wait is silent.
- The nested `page.screenshot()` inside the exposed function worked: it ran on the test thread (`Test worker`), while
  that thread was inside the held `click`, and returned a 4995-byte PNG to the page. No deadlock.

So the design stands: the handler shoots directly, and a held click is what lets it.

## Acceptance (Task 7)

All 7 acceptance tests pass. Browser test durations with screenshots (Testcontainers stack, macOS, Chromium headless):
`browse-stock` 0.80 s, `place-order-in-browser` 1.01 s, `place-order-out-of-stock-in-browser` 0.61 s; the untagged
`order page survives a reload` (no screenshots, also places an order) 0.62 s. No timing from before step 5 survived
(the red run of Task 2 waits out its 10 s timeouts), so the reload test is the nearest baseline: settling and shooting
add roughly 0.4 s to a journey with three batches. Screenshots are 16–21 KB each (1280 × 720 viewport PNG).

The first green attempt found `[click place-order, submit order-form]` shot before `→ /orders/<id>`: the settle began in
the click's capture-phase listener, before the submit handler opened its `POST`, so "no span open" held at once.
Ruling G7 (above) fixes it.
