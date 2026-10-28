import { useAcceptComponent, useSetConnectionState, useWorkspace } from '../api/queries';
import type { BoardComponent, BoardConnection, WorkspaceView } from '../api/types';
import { TypeIcon } from '../board/icons';
import { useUiStore } from '../store/uiStore';
import { NotesPanel } from './NotesPanel';

export function SidePanel() {
  const { data: view } = useWorkspace();
  const componentId = useUiStore((s) => s.selectedComponentId);
  const connectionId = useUiStore((s) => s.selectedConnectionId);

  if (!view) return <aside className="side-panel" aria-label="Details" />;

  const component = view.components.find((c) => c.id === componentId);
  const connection = view.connections.find((c) => c.id === connectionId);

  return (
    <aside className="side-panel" aria-label="Details">
      {component ? (
        <ComponentDetails key={component.id} component={component} view={view} />
      ) : connection ? (
        <ConnectionDetails key={connection.id} connection={connection} view={view} />
      ) : (
        <Overview view={view} />
      )}
    </aside>
  );
}

function Overview({ view }: { view: WorkspaceView }) {
  const discovered = view.components.filter((c) => c.discovered).length;
  const pending = view.connections.filter((c) => c.state === 'discovered').length;
  return (
    <div className="panel-body">
      <h2>{view.project.name}</h2>
      <p className="muted">Select a component or a connection to see its details and notes.</p>
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

      <section>
        <h3>Connections</h3>
        {related.length === 0 ? (
          <p className="muted">None.</p>
        ) : (
          <ul className="connection-list">
            {related.map((c) => (
              <li key={c.id}>
                <span className="direction">{c.from === component.id ? '→' : '←'}</span>
                <span>{nameOf(c.from === component.id ? c.to : c.from)}</span>
                <span className="muted">{c.label ?? c.kind}</span>
                {c.state === 'discovered' && <span className="badge badge-discovered">discovered</span>}
                {c.unverified && <span className="badge badge-unverified">unverified</span>}
              </li>
            ))}
          </ul>
        )}
      </section>

      <NotesPanel key={component.id} targetId={component.id} />
    </div>
  );
}

function ConnectionDetails({ connection, view }: { connection: BoardConnection; view: WorkspaceView }) {
  const setState = useSetConnectionState();
  const nameOf = (id: string) => view.components.find((c) => c.id === id)?.displayName ?? id;

  return (
    <div className="panel-body">
      <header className="panel-title">
        <div>
          <h2>
            {nameOf(connection.from)} → {nameOf(connection.to)}
          </h2>
          <p className="muted">
            {connection.kind}
            {connection.label ? ` · ${connection.label}` : ''} · <code>{connection.id}</code>
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
