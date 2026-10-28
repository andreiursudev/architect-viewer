import type { FlowStep } from '../api/types';
import { clientCallNote, formatMillis } from '../board/HopDetails';
import { statusTone } from '../status';

type Kind = 'request' | 'response';

/** JSON is shown indented; anything else (including a body the recording cut short) exactly as recorded. */
export function prettyBody(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

/** Everything recorded about one step of the run, with the section of the clicked message marked. */
export function MessageDetails({ step, kind }: { step: FlowStep; kind: Kind }) {
  const event = step.event;
  return (
    <div className="message-details">
      <header className="md-head">
        <h2 title={step.label}>{step.label}</h2>
        {step.status != null && <span className={`hop-status ${statusTone(step.status)}`}>{step.status}</span>}
      </header>

      <dl className="facts md-facts">
        <dt>From</dt>
        <dd>{step.from}</dd>
        <dt>To</dt>
        <dd>{step.to}</dd>
        <dt>Duration</dt>
        <dd>{formatMillis(step.durationNanos)}</dd>
        {event.type === 'http.server' ? (
          <>
            <dt>Method</dt>
            <dd>{event.method}</dd>
            <dt>Route</dt>
            <dd>{event.route ?? event.path}</dd>
            {event.route && event.route !== event.path && (
              <>
                <dt>Path</dt>
                <dd>{event.path}</dd>
              </>
            )}
          </>
        ) : event.type === 'db.client' ? (
          <>
            <dt>Database</dt>
            <dd>{event.dbSystem}</dd>
            <dt>Name</dt>
            <dd>{event.dbName ?? 'unknown'}</dd>
          </>
        ) : (
          <>
            <dt>Method</dt>
            <dd>{event.method}</dd>
            <dt>URL</dt>
            <dd>{event.url}</dd>
            {event.errorType && (
              <>
                <dt>Error</dt>
                <dd>{event.errorType}</dd>
              </>
            )}
          </>
        )}
      </dl>

      {event.type === 'http.server' ? (
        <>
          <Section title="Request" selected={kind === 'request'}>
            <Headers title="Request headers" headers={event.requestHeaders} />
            <Body what="request" text={event.requestBody} truncated={event.requestBodyTruncated} />
          </Section>
          <Section title="Response" selected={kind === 'response'}>
            <Headers title="Response headers" headers={event.responseHeaders} />
            <Body what="response" text={event.responseBody} truncated={event.responseBodyTruncated} />
          </Section>
        </>
      ) : event.type === 'db.client' ? (
        <>
          <Section title="Request" selected={kind === 'request'}>
            <h4>SQL statement</h4>
            <pre className="md-code is-sql">{event.statement}</pre>
            <Parameters values={event.parameters} />
          </Section>
          <Section title="Response" selected={kind === 'response'}>
            <p className="md-note">
              {event.rowCount == null ? 'ok' : event.rowCount === 1 ? '1 row' : `${event.rowCount} rows`}
            </p>
          </Section>
        </>
      ) : (
        <Section title={kind === 'request' ? 'Request' : 'Response'} selected>
          <p className="muted md-note">{clientCallNote(step)}</p>
        </Section>
      )}
    </div>
  );
}

function Section({ title, selected, children }: { title: string; selected: boolean; children: React.ReactNode }) {
  return (
    <section className={`md-section${selected ? ' is-selected' : ''}`} role="region" aria-label={title}>
      <h3>{title}</h3>
      {children}
    </section>
  );
}

function Headers({ title, headers }: { title: string; headers: Record<string, string[]> | undefined }) {
  const entries = Object.entries(headers ?? {});
  return (
    <>
      <h4>Headers</h4>
      {entries.length === 0 ? (
        <p className="muted md-note">no headers</p>
      ) : (
        <table className="md-table" aria-label={title}>
          <tbody>
            {entries.map(([name, values]) => (
              <tr key={name}>
                <th scope="row">{name}</th>
                <td>{values.join(', ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

function Body({ what, text, truncated }: { what: string; text: string | null | undefined; truncated: boolean }) {
  return (
    <>
      <h4>
        Body
        {truncated && <span className="hop-truncated"> (recording cut it short)</span>}
      </h4>
      {text ? <pre className="md-code">{prettyBody(text)}</pre> : <p className="muted md-note">no {what} body</p>}
    </>
  );
}

function Parameters({ values }: { values: (string | null)[] }) {
  return (
    <>
      <h4>Parameters</h4>
      {values.length === 0 ? (
        <p className="muted md-note">none</p>
      ) : (
        <table className="md-table" aria-label="Parameters">
          <tbody>
            {values.map((value, i) => (
              <tr key={i}>
                <th scope="row">{i + 1}</th>
                <td>{value ?? <em className="md-null">NULL</em>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
