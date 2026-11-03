import type { FlowStep } from './api/types';

export type StatusTone = 'is-ok' | 'is-client-error' | 'is-server-error';

export function statusTone(status: number): StatusTone {
  if (status >= 500) return 'is-server-error';
  if (status >= 400) return 'is-client-error';
  return 'is-ok';
}

const simpleName = (type: string) => type.slice(type.lastIndexOf('.') + 1);

/** How much of a returned value fits in a response label. */
const MAX_RETURN_LABEL = 40;

/** A step failed when it answered with an error status, when a call to an unrecorded host never got an answer, or when a method threw. */
export function isFailedStep(step: FlowStep): boolean {
  if (step.kind === 'ui') return false;
  if (step.status != null && step.status >= 400) return true;
  const event = step.event;
  if (event.type === 'method') return event.exceptionType != null;
  if (event.type === 'kafka.produce' || event.type === 'kafka.consume') return event.errorType != null;
  return event.type === 'http.client' && event.errorType != null;
}

/** What a step answers with: the HTTP status, the error that stopped the call, the rows a statement touched, what a method returned or threw, or `ok`. */
export function responseText(step: FlowStep): string {
  if (step.kind === 'ui' || step.kind === 'kafka') return ''; // an arrow only: nothing answers a browser step or a record sent to a topic
  if (step.status != null) return String(step.status);
  const event = step.event;
  if (event.type === 'http.client' && event.errorType != null) return simpleName(event.errorType);
  if (event.type === 'db.client' && event.rowCount != null) return event.rowCount === 1 ? '1 row' : `${event.rowCount} rows`;
  if (event.type === 'method') {
    if (event.exceptionType != null) return `throws ${simpleName(event.exceptionType)}`;
    if (event.returnValue == null) return 'returned';
    const flat = event.returnValue.replace(/\s+/g, ' ').trim();
    return flat.length > MAX_RETURN_LABEL ? `${flat.slice(0, MAX_RETURN_LABEL - 1)}…` : flat;
  }
  return 'ok';
}
