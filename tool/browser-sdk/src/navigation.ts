import { SpanKind, type Tracer } from '@opentelemetry/api';
import { NAVIGATION_SPAN, UI_NAVIGATION_KIND, UI_ROUTE, UI_URL } from './attributes.js';

/** The route a ui step is on: the path, without query or hash. */
export function routeOf(location: Location): string {
  return location.pathname;
}

/**
 * Records the page load (from the start of the navigation to the window's load event) and every client-side route
 * change (pushState, replaceState or popstate that changes the path) as zero-length `stack.navigation` spans.
 * Route changes are on the fetch instrumentation's clock (Date.now()), as actions are; the load starts at the
 * navigation's start (performance.timeOrigin, milliseconds since the epoch).
 */
export function recordNavigations(tracer: Tracer): void {
  const load = tracer.startSpan(NAVIGATION_SPAN, {
    kind: SpanKind.INTERNAL,
    startTime: performance.timeOrigin,
    attributes: { [UI_NAVIGATION_KIND]: 'load', [UI_URL]: location.href, [UI_ROUTE]: routeOf(location) },
  });
  if (document.readyState === 'complete') load.end();
  else window.addEventListener('load', () => load.end(), { once: true });

  let route = routeOf(location);
  const routeChanged = () => {
    if (routeOf(location) === route) return;
    route = routeOf(location);
    const at = Date.now();
    tracer
      .startSpan(NAVIGATION_SPAN, {
        kind: SpanKind.INTERNAL,
        startTime: at,
        attributes: { [UI_NAVIGATION_KIND]: 'route', [UI_URL]: location.href, [UI_ROUTE]: route },
      })
      .end(at);
  };

  for (const method of ['pushState', 'replaceState'] as const) {
    const original = history[method];
    history[method] = function (this: History, ...args: Parameters<History['pushState']>) {
      const result = original.apply(this, args);
      routeChanged();
      return result;
    };
  }
  window.addEventListener('popstate', routeChanged);
}
