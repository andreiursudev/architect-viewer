# Step 3 — rulings and deferred follow-ups

Decisions made on the owner's behalf while building method capture (methods inside services in the sequence view, spec `docs/superpowers/specs/2026-10-30-step-3-method-capture-design.md`), and follow-ups deliberately left open. Branch `step-3-method-capture`.

## Rulings

- Ruling M1: `fromUnit`/`toUnit` of method steps hold fully qualified class names, not simple names as the spec says — why: two classes with the same simple name in one service would otherwise merge into one lifeline — cost if wrong: the UI shows simple names anyway; only the JSON field contents differ.
- Ruling M2: `stack.method.param_names` is omitted when no parameter names were found (spec §2.1) over the plan's "none → empty"; the reporter maps an absent attribute to an empty list — cost if wrong: none.
- Ruling M3: a selected method message is cleared when collapsing its service hides it — cost if wrong: trivial.
- Ruling M4: the hand-written fixtures were aligned with the first real recording only in how a Spring `ResponseEntity` return value renders (`{"status":"CREATED","headers":{"headers":{…}},"body":…}`, from the Jackson `ResponseEntity` serialisation, instead of the hand-written `statusCode` / flat headers) in `reporter/…/golden/create-order-methods`, `OtlpFixtures.kt` and both copies of the run-5 recording (kept byte-identical) — why: the fixtures should show what the agent really writes — cost if wrong: none; thread names and ids stay as written.
- Ruling M5: the acceptance counts (11 events for `create-order`, 9 for `create-order-out-of-stock`, 5 for `get-order`) were confirmed against the first real recording and did not change — cost if wrong: none.

## Deferred follow-ups

- Coroutine-aware capture: a `suspend` function is recorded as an ordinary method (spec §6), so its span ends at the first suspension, not when the coroutine finishes.
- Workspace include/exclude patterns for method capture (today a base-package list on the agent command line only), and per-class or per-annotation opt-in/out.
- Layer facts (D12): annotation facts for layers, deferred by the spec.
- A component view (classes as components of a service) — only the sequence view shows methods.
- Mermaid / PlantUML export of method calls.
- A total-size cap on rendered values: today each argument and return value is bounded on its own, so a method with many large arguments can still produce a large event (now per method call, not per HTTP body).
- Per-call reflection cost: the advice takes `@Advice.Origin Method` for every call; caching the reflective lookup per method would lower the overhead on hot methods.
- Owner time-containment fallback: when a service calls itself, matching an event to its owner by time containment can cross request boundaries (the parent-span link is exact, only the fallback is fuzzy).
- Trailing-dot package entries (`a.b.`) silently match nothing; normalise or log a warning.
- The `▸ N classes` toggle lacks `aria-expanded` and a service-specific accessible label ("Expand orders-service into classes").
- Controller return values that are Spring `ResponseEntity` render with Jackson's bean view (`headers.headers`), noisy but complete; a friendlier rendering would need a Spring-aware serializer in the agent.
