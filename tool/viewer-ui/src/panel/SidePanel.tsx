import { useAcceptComponent, useSetConnectionState, useWorkspace } from '../api/queries';
import type { BoardComponent, BoardConnection, WorkspaceView } from '../api/types';
import { TypeIcon } from '../board/icons';
import { useUiStore } from '../store/uiStore';
import { endpointsOf, type Endpoint } from './endpoints';
import { NotesPanel } from './NotesPanel';
import { StepDetails } from './StepDetails';
import { useResizableWidth } from './useResizableWidth';

export function SidePanel() {
  const { data: view } = useWorkspace();
  const componentId = useUiStore((s) => s.selectedComponentId);
  const connectionId = useUiStore((s) => s.selectedConnectionId);
  const runId = useUiStore((s) => s.selectedRunId);
  const stepId = useUiStore((s) => s.selectedStepId);
  const { width, dragging, handleProps } = useResizableWidth();

  const component = view?.components.find((c) => c.id === componentId);
  const connection = view?.connections.find((c) => c.id === connectionId);

  return (
    <aside className="side-panel" aria-label="Details" style={{ width }}>
      <div className={`panel-resizer${dragging ? ' is-dragging' : ''}`} title="Drag to resize · double-click to reset" {...handleProps} />
      <div className="side-panel-scroll">
        {runId && stepId ? (
          <StepDetails runId={runId} stepId={stepId} />
        ) : !view ? null : component ? (
          <ComponentDetails key={component.id} component={component} view={view} />
        ) : connection ? (
          <ConnectionDetails key={connection.id} connection={connection} view={view} />
        ) : (
          <Overview view={view} />
        )}
      </div>
    </aside>
  );
}

function Overview({ view }: { view: WorkspaceView }) {
  const discovered = view.components.filter((c) => c.discovered).length;
  const pending = view.connections.filter((c) => c.state === 'discovered').length;
  return (
    <div className="panel-body">
      <h2>{view.project.name}</h2>
      <p className="muted">
        Select a component to see its connections, endpoints and notes. Connections are drawn for the selected component
        or the path of the selected scenario.
      </p>
      <dl className="facts">
        <dt>Components</dt>
        <dd>{view.components.length}</dd>
        <dt>Connections</dt>
        <dd>{view.connections.filter((c) => c.state !== 'hidden').length}</dd>
        <dt>Discovered, not yet accepted</dt>
        <dd>{discovered + pending}</dd>
      </dl>
      {view.problems.length > 0 && (
        <section>
          <h3>Workspace problems</h3>
          <ul className="problems">
            {view.problems.map((p) => (
              <li key={`${p.file}:${p.message}`}>
                <code>{p.file}</code> {p.message}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

function ComponentDetails({ component, view }: { component: BoardComponent; view: WorkspaceView }) {
  const accept = useAcceptComponent();
  const related = view.connections.filter(
    (c) => c.state !== 'hidden' && (c.from === component.id || c.to === component.id),
  );
  const nameOf = (id: string) => view.components.find((c) => c.id === id)?.displayName ?? id;
  const byPeer = (a: BoardConnection, b: BoardConnection, peer: (c: BoardConnection) => string) =>
    nameOf(peer(a)).localeCompare(nameOf(peer(b)));
  const incoming = related.filter((c) => c.to === component.id).sort((a, b) => byPeer(a, b, (c) => c.from));
  const outgoing = related.filter((c) => c.from === component.id).sort((a, b) => byPeer(a, b, (c) => c.to));

  return (
    <div className="panel-body">
      <header className="panel-title">
        <span className={`node-icon type-${component.type}`}>
          <TypeIcon type={component.type} />
        </span>
        <div>
          <h2>{component.displayName}</h2>
          <p className="muted">
            <code>{component.id}</code> · {component.type}
          </p>
        </div>
      </header>

      {component.discovered && (
        <div className="callout">
          <p>
            <span className="badge badge-discovered">discovered</span> Seen in recordings, but not part of the workspace
            yet.
          </p>
          <button type="button" className="btn btn-primary" disabled={accept.isPending} onClick={() => accept.mutate(component)}>
            Accept component
          </button>
          {accept.isError && <p className="error">{accept.error.message}</p>}
        </div>
      )}

      {related.length === 0 ? (
        <section>
          <h3>Connections</h3>
          <p className="muted">None.</p>
        </section>
      ) : (
        <>
          <ConnectionGroup title="Incoming" arrow="←" connections={incoming} peerOf={(c) => c.from} nameOf={nameOf} />
          <ConnectionGroup title="Outgoing" arrow="→" connections={outgoing} peerOf={(c) => c.to} nameOf={nameOf} />
        </>
      )}

      <NotesPanel key={component.id} targetId={component.id} />
    </div>
  );
}

function ConnectionDetails({ connection, view }: { connection: BoardConnection; view: WorkspaceView }) {
  const setState = useSetConnectionState();
  const nameOf = (id: string) => view.components.find((c) => c.id === id)?.displayName ?? id;
  const endpoints = endpointsOf(connection);

  return (
    <div className="panel-body">
      <header className="panel-title">
        <div>
          <h2>
            {nameOf(connection.from)} → {nameOf(connection.to)}
          </h2>
          <p className="muted">
            {connection.kind} · <code>{connection.id}</code>
          </p>
        </div>
      </header>

      <p className="badge-row">
        <span className={`badge badge-state-${connection.state}`}>{connection.state}</span>
        {connection.unverified && <span className="badge badge-unverified">unverified</span>}
      </p>
      {connection.unverified && (
        <p className="muted">
          No current evidence for this connection: neither the latest run of a scenario nor a reference recording goes
          through it.
        </p>
      )}

      {endpoints.length > 0 && (
        <section>
          <h3>
            Endpoints <span className="count">{endpoints.length}</span>
          </h3>
          <EndpointList endpoints={endpoints} />
        </section>
      )}

      {connection.state === 'discovered' && (
        <div className="callout">
          <p>Seen in recordings, but not part of the workspace yet.</p>
          <div className="button-row">
            <button
              type="button"
              className="btn btn-primary"
              disabled={setState.isPending}
              onClick={() => setState.mutate({ connection, state: 'accepted' })}
            >
              Accept
            </button>
            <button
              type="button"
              className="btn"
              disabled={setState.isPending}
              onClick={() => setState.mutate({ connection, state: 'hidden' })}
            >
              Hide
            </button>
          </div>
        </div>
      )}

      <NotesPanel key={connection.id} targetId={connection.id} />
    </div>
  );
}

/** The connections on one side of a component, each with its peer and the endpoints it carries. */
function ConnectionGroup({
  title,
  arrow,
  connections,
  peerOf,
  nameOf,
}: {
  title: string;
  arrow: string;
  connections: BoardConnection[];
  peerOf: (c: BoardConnection) => string;
  nameOf: (id: string) => string;
}) {
  const select = useUiStore((s) => s.selectConnection);
  if (connections.length === 0) return null;
  return (
    <section aria-label={`${title} connections`}>
      <h3>
        {title} <span className="count">{connections.length}</span>
      </h3>
      <ul className="connection-list">
        {connections.map((c) => {
          const endpoints = endpointsOf(c);
          return (
            <li key={c.id} className="connection-card">
              <button type="button" className="connection-head" onClick={() => select(c.id)} title={`Show ${c.id}`}>
                <span className="direction" aria-hidden="true">
                  {arrow}
                </span>
                <span className="connection-peer">{nameOf(peerOf(c))}</span>
                <span className={`kind-tag kind-${c.kind}`}>{c.kind}</span>
                {c.state === 'discovered' && <span className="badge badge-discovered">discovered</span>}
                {c.unverified && <span className="badge badge-unverified">unverified</span>}
              </button>
              {endpoints.length > 0 && <EndpointList endpoints={endpoints} />}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function EndpointList({ endpoints }: { endpoints: Endpoint[] }) {
  return (
    <ul className="endpoint-list">
      {endpoints.map((e) => (
        <li key={`${e.method ?? ''} ${e.path}`} className="endpoint">
          {e.method && <span className={`http-method method-${e.method.toLowerCase()}`}>{e.method}</span>}
          <code className="endpoint-path">{withBreaksAfterSlashes(e.path)}</code>
        </li>
      ))}
    </ul>
  );
}

/** A long path wraps after a `/` rather than in the middle of a segment. */
function withBreaksAfterSlashes(path: string) {
  return path.split('/').flatMap((segment, i) => (i === 0 ? [segment] : ['/', <wbr key={i} />, segment]));
}
