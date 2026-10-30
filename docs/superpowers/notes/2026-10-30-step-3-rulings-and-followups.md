# Step 3 — rulings and deferred follow-ups

Decisions made on the owner's behalf while building method capture (methods inside services in the sequence view, spec `docs/superpowers/specs/2026-10-30-step-3-method-capture-design.md`), and follow-ups deliberately left open. Branch `step-3-method-capture`.

## Rulings

- Ruling M1: `fromUnit`/`toUnit` of method steps hold fully qualified class names, not simple names as the spec says — why: two classes with the same simple name in one service would otherwise merge into one lifeline — cost if wrong: the UI shows simple names anyway; only the JSON field contents differ.
- Ruling M2: `stack.method.param_names` is omitted when no parameter names were found (spec §2.1) over the plan's "none → empty"; the reporter maps an absent attribute to an empty list — cost if wrong: none.
- Ruling M3: a selected method message is cleared when collapsing its service hides it — cost if wrong: trivial.
- Ruling M4: the hand-written fixtures were aligned with the first real recording only in how a Spring `ResponseEntity` return value renders (`{"status":"CREATED","headers":{"headers":{…}},"body":…}`, from `BoundedValueSerializer`'s field walk over `ResponseEntity` — its `status`, `headers` (whose own field is `HttpHeaders.headers`) and `body` fields — not from Jackson, instead of the hand-written `statusCode` / flat headers) in `reporter/…/golden/create-order-methods`, `OtlpFixtures.kt` and both copies of the run-5 recording (kept byte-identical) — why: the fixtures should show what the agent really writes — cost if wrong: none; thread names and ids stay as written.
- Ruling M5: the acceptance counts (11 events for `create-order`, 9 for `create-order-out-of-stock`, 5 for `get-order`) were confirmed against the first real recording and did not change — cost if wrong: none.
- Ruling M6: each rendered method argument and return value is cut at 16384 characters (`BodyCapture.MAX_METHOD_VALUE_CHARS`), keeping its first 16384 characters followed by `…<truncated>` (no longer valid JSON; the viewer shows non-JSON text as is) — why: `BoundedValueSerializer` detects cycles only on the current path, so a shared object graph re-renders once per path (a Spring `DefaultListableBeanFactory` with two beans rendered to 271,907 characters), reachable from a `HandlerInterceptor`'s `HandlerMethod` argument, a `Filter` or an `@EventListener` event; HTTP bodies keep `MAX_BODY_CHARS` — cost if wrong: a legitimately large value loses its tail; the budget is one constant.
- Ruling M7: Hibernate entity proxies (`org.hibernate.proxy.HibernateProxy`) and uninitialized lazy collections (`org.hibernate.collection.spi.PersistentCollection` whose `wasInitialized()`, called reflectively, returns false or fails) render as `"<lazy>"`; both are matched by name, with no Hibernate dependency — why: rendering them loaded data and issued SQL (misattributed DB steps) or threw `LazyInitializationException`, turning the whole argument into `<Order>` — cost if wrong: an initialized proxy's state is hidden; initialized collections still render.
- Ruling M8: the agent defaults `otel.bsp.max.queue.size` to 65536 (overridable like the other defaults) — why: the SDK's 2048 silently drops spans of a method-heavy request, and the server span ends last, so it is the one lost — cost if wrong: more memory held by queued spans under a burst, bounded by the queue size.
- Ruling M9: bare `get()` and `is()` with no name after the prefix are kept as ordinary methods; only `getX`/`isX` without parameters and `setX` with one are excluded as accessors — why: without a property name after the prefix the method is not a JavaBeans accessor but an operation (a supplier, a cache or client lookup) worth showing — cost if wrong: a few extra steps for such methods.

## Deferred follow-ups

- Coroutine-aware capture: a `suspend` function is recorded as an ordinary method (spec §6), so its span ends at the first suspension, not when the coroutine finishes.
- Workspace include/exclude patterns for method capture (today a base-package list on the agent command line only), and per-class or per-annotation opt-in/out.
- Layer facts (D12): annotation facts for layers, deferred by the spec.
- A component view (classes as components of a service) — only the sequence view shows methods.
- Mermaid / PlantUML export of method calls.
- A total-size cap per method call: each argument and return value is cut at 16384 characters (ruling M6), so a method with many large arguments can still produce a large event.
- Per-call reflection cost: the advice takes `@Advice.Origin Method` for every call; caching the reflective lookup per method would lower the overhead on hot methods.
- Owner time-containment fallback: when a service calls itself, matching an event to its owner by time containment can cross request boundaries (the parent-span link is exact, only the fallback is fuzzy).
- Trailing-dot package entries (`a.b.`) silently match nothing; normalise or log a warning.
- The `▸ N classes` toggle lacks `aria-expanded` and a service-specific accessible label ("Expand orders-service into classes").
- Controller return values that are Spring `ResponseEntity` render through `BoundedValueSerializer`'s field walk (`status`, `headers` → `HttpHeaders.headers`, `body`), noisy but complete; a friendlier rendering would need a Spring-aware serializer in the agent.
- An interceptor or filter `@Component` (`HandlerInterceptor`, `Filter`) in a base package becomes the request's handler (the earliest ownerless method event of the request, so the request step points at the interceptor's class instead of the controller); prefer `@Controller` classes once annotation facts exist (D12).
- `@Async` work that outlives its request gets no step of its own, and its DB calls appear as root events.
- Per-call overhead of method capture (a span, reflection and value rendering for every call of every public method in the base packages) is unmeasured; measure it and consider sampling or cheaper rendering for hot methods.
