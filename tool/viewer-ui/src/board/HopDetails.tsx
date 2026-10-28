import type { FlowStep } from '../api/types';
import { statusTone } from '../status';

const BODY_LIMIT = 200;

/** Said wherever a hop has no payloads: the callee is not one of the recorded components. */
export const NOT_RECORDED = 'Body not recorded: the called service is not instrumented.';
/** Said instead when the call never got an answer: there was nothing to record, instrumented or not. */
export const NO_RESPONSE = 'No response: the call failed before reaching the service.';

/** The note under an `http.client` step: it either never got an answer, or the answering service is not recorded. */
export function clientCallNote(step: FlowStep): string {
  const unanswered = step.status == null && step.event.type === 'http.client' && step.event.errorType != null;
  return unanswered ? NO_RESPONSE : NOT_RECORDED;
}

function cut(text: string): string {
  return text.length > BODY_LIMIT ? `${text.slice(0, BODY_LIMIT)}…` : text;
}

export function formatMillis(nanos: number): string {
  const ms = nanos / 1_000_000;
  return `${Number(ms.toFixed(ms >= 10 ? 0 : 1))} ms`;
}

/** What happened on one highlighted connection: every call of the run over it, with a peek at the payloads. */
export function HopDetails({ steps }: { steps: FlowStep[] }) {
  return (
    <div className="hop-details" role="tooltip">
      {steps.map((step) => (
        <section key={step.stepId} className="hop-step">
          <header className="hop-head">
            <span className="hop-label" title={step.label}>{step.label}</span>
            {step.status != null && <span className={`hop-status ${statusTone(step.status)}`}>{step.status}</span>}
            <span className="hop-duration">{formatMillis(step.durationNanos)}</span>
          </header>
          {step.event.type === 'http.server' ? (
            <>
              <Payload title="Request" text={step.event.requestBody} truncated={step.event.requestBodyTruncated} />
              <Payload title="Response" text={step.event.responseBody} truncated={step.event.responseBodyTruncated} />
            </>
          ) : step.event.type === 'db.client' ? (
            <>
              <Payload title="SQL" text={step.event.statement} sql />
              <Payload
                title="Parameters"
                text={step.event.parameters.length === 0 ? null : step.event.parameters.map((p) => p ?? 'NULL').join(', ')}
                empty="none"
              />
              {step.event.rowCount != null && (
                <p className="hop-rows">{step.event.rowCount === 1 ? '1 row' : `${step.event.rowCount} rows`}</p>
              )}
            </>
          ) : (
            <>
              <p className="hop-url">{step.event.url}</p>
              {step.event.errorType && <p className="hop-error">{step.event.errorType}</p>}
              <p className="muted">{clientCallNote(step)}</p>
            </>
          )}
        </section>
      ))}
    </div>
  );
}

function Payload({
  title,
  text,
  truncated = false,
  sql = false,
  empty = 'no body',
}: {
  title: string;
  text: string | null | undefined;
  truncated?: boolean;
  sql?: boolean;
  empty?: string;
}) {
  return (
    <div className="hop-payload">
      <h4>
        {title}
        {truncated && <span className="hop-truncated"> (recording cut it short)</span>}
      </h4>
      {text ? <pre className={sql ? 'is-sql' : undefined}>{cut(text)}</pre> : <p className="muted">{empty}</p>}
    </div>
  );
}
