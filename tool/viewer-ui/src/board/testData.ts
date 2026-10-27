import type { BoardComponent, BoardConnection, BoardLayout, ConnectionState, WorkspaceView } from '../api/types';

/** Builders for the board tests. */
export function comp(id: string, over: Partial<BoardComponent> = {}): BoardComponent {
  return { id, type: 'service', displayName: id, discovered: false, hasNotes: false, ...over };
}

export function conn(
  id: string,
  state: ConnectionState = 'accepted',
  over: Partial<BoardConnection> = {},
): BoardConnection {
  const [from, to] = id.split('--');
  return { id, from, to, kind: 'http', label: null, state, unverified: false, ...over };
}

export function viewWith(over: Partial<WorkspaceView> = {}): WorkspaceView {
  return {
    project: { name: 'demo' },
    components: [comp('a'), comp('b')],
    connections: [],
    scenarios: [],
    problems: [],
    ...over,
  };
}

export function layoutOf(nodes: BoardLayout['nodes']): BoardLayout {
  return { nodes, viewport: null };
}
