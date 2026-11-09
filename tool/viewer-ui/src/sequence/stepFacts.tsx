import type { FlowStep } from '../api/types';

/** Said wherever a call has no payloads: the callee is not one of the recorded components. */
export const NOT_RECORDED = 'Body not recorded: the called service is not instrumented.';
/** Said instead when the call never got an answer: there was nothing to record, instrumented or not. */
export const NO_RESPONSE = 'No response: the call failed before reaching the service.';

/** The note under an `http.client` step: it either never got an answer, or the answering service is not recorded. */
export function clientCallNote(step: FlowStep): string {
  const unanswered = step.status == null && step.event.type === 'http.client' && step.event.errorType != null;
  return unanswered ? NO_RESPONSE : NOT_RECORDED;
}

export function formatMillis(nanos: number): string {
  const ms = nanos / 1_000_000;
  return `${Number(ms.toFixed(ms >= 10 ? 0 : 1))} ms`;
}

/** The facts of a browser step: for an action its event, target and route; for a navigation its URL and kind (the duration is shown beside the step). */
export function UiFacts({ event }: { event: FlowStep['event'] }) {
  if (event.type === 'ui.action') {
    return (
      <>
        <dt>Event</dt>
        <dd>{event.event}</dd>
        <dt>Target</dt>
        <dd>{event.target}</dd>
        <dt>Route</dt>
        <dd>{event.route}</dd>
      </>
    );
  }
  if (event.type === 'ui.navigation') {
    return (
      <>
        <dt>URL</dt>
        <dd>{event.url}</dd>
        <dt>Kind</dt>
        <dd>{event.kind === 'load' ? 'page load' : 'route change'}</dd>
      </>
    );
  }
  return null;
}
