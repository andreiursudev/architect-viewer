import { MarkerType, type Edge, type Node } from '@xyflow/react';
import type { BoardComponent, BoardConnection, BoardLayout, WorkspaceView } from '../api/types';

export type ComponentNode = Node<BoardComponent, 'component'>;
export type ConnectionEdgeType = Edge<BoardConnection, 'connection'>;

export const NODE_WIDTH = 208;
export const NODE_HEIGHT = 68;

export const EDGE_COLOR = '#64748b';
export const DISCOVERED_EDGE_COLOR = '#d97706';
export const UNVERIFIED_DASH = '7 5';

export function edgeColor(connection: BoardConnection): string {
  return connection.state === 'discovered' ? DISCOVERED_EDGE_COLOR : EDGE_COLOR;
}

/**
 * Maps the workspace view onto React Flow elements. Hidden connections are not drawn, and neither are connections
 * whose endpoints are not among the components (the API can return manual or accepted connections to components
 * that are not listed). Components the layout has no position for sit at the origin and are reported as `unplaced`.
 */
export function toFlowElements(
  view: WorkspaceView,
  layout: BoardLayout | null,
): { nodes: ComponentNode[]; edges: ConnectionEdgeType[]; unplaced: string[] } {
  const positions = layout?.nodes ?? {};
  const unplaced: string[] = [];

  const nodes: ComponentNode[] = view.components.map((component) => {
    const position = positions[component.id];
    if (!position) unplaced.push(component.id);
    return {
      id: component.id,
      type: 'component',
      position: position ? { x: position.x, y: position.y } : { x: 0, y: 0 },
      data: component,
    };
  });

  const known = new Set(view.components.map((c) => c.id));
  const edges: ConnectionEdgeType[] = view.connections
    .filter((c) => c.state !== 'hidden' && known.has(c.from) && known.has(c.to))
    .map((connection) => ({
      id: connection.id,
      type: 'connection',
      source: connection.from,
      target: connection.to,
      data: connection,
      markerEnd: { type: MarkerType.ArrowClosed, width: 18, height: 18, color: edgeColor(connection) },
      ...(connection.unverified ? { style: { strokeDasharray: UNVERIFIED_DASH } } : {}),
    }));

  return { nodes, edges, unplaced };
}
