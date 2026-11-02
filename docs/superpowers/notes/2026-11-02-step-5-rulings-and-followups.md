# Step 5 — rulings and deferred follow-ups

Decisions made on the owner's behalf while building screenshots of the frontend's ui steps (spec
`docs/superpowers/specs/2026-11-02-step-5-frontend-screenshots-design.md`, plan
`docs/superpowers/plans/2026-11-02-step-5-frontend-screenshots.md`), and minor review findings deliberately deferred.
Source: the execution ledger (deleted after completion). Branch `step-5-screenshots`.

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
Ruling G7 (below) fixes it.
