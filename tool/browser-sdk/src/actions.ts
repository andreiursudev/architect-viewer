import { SpanKind, type Tracer } from '@opentelemetry/api';
import { hrTime } from '@opentelemetry/core';
import { ACTION_SPAN, UI_ACTION_EVENT, UI_ACTION_TARGET, UI_ROUTE } from './attributes.js';
import { describeTarget } from './describeTarget.js';
import { routeOf } from './navigation.js';

/**
 * Records every click and form submit as a zero-length `stack.action` span. The listeners sit on the document in the
 * capture phase, so they see the real target before any handler (React's root delegation included) can stop it.
 */
export function recordActions(tracer: Tracer): void {
  const record = (event: Event) => {
    const at = hrTime();
    tracer
      .startSpan(ACTION_SPAN, {
        kind: SpanKind.INTERNAL,
        startTime: at,
        attributes: {
          [UI_ACTION_EVENT]: event.type,
          [UI_ACTION_TARGET]: describeTarget(event.target),
          [UI_ROUTE]: routeOf(location),
        },
      })
      .end(at);
  };
  document.addEventListener('click', record, { capture: true });
  document.addEventListener('submit', record, { capture: true });
}
