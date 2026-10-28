import type { FlowStep } from './api/types';

export type StatusTone = 'is-ok' | 'is-client-error' | 'is-server-error';

export function statusTone(status: number): StatusTone {
  if (status >= 500) return 'is-server-error';
  if (status >= 400) return 'is-client-error';
  return 'is-ok';
}

/** A step failed when it answered with an error status, or when a call to an unrecorded host never got an answer. */
export function isFailedStep(step: FlowStep): boolean {
  if (step.status != null && step.status >= 400) return true;
  return step.event.type === 'http.client' && step.event.errorType != null;
}

/** What a step answers with: the HTTP status, the error that stopped the call, the rows a statement touched, or `ok`. */
export function responseText(step: FlowStep): string {
  if (step.status != null) return String(step.status);
  const event = step.event;
  if (event.type === 'http.client' && event.errorType != null) return event.errorType.slice(event.errorType.lastIndexOf('.') + 1);
  if (event.type === 'db.client' && event.rowCount != null) return event.rowCount === 1 ? '1 row' : `${event.rowCount} rows`;
  return 'ok';
}
