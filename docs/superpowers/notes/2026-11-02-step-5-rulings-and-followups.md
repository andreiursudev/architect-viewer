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
