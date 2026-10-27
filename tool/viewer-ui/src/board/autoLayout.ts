import type { Edge, Node } from '@xyflow/react';
import type { BoardLayout, NodePosition } from '../api/types';
import { NODE_HEIGHT, NODE_WIDTH } from './toFlowElements';

// ELK is ~1.5 MB: load it on first use so it is a chunk of its own and the app shell starts quickly.
let elkPromise: Promise<InstanceType<typeof import('elkjs/lib/elk.bundled.js').default>> | undefined;
function getElk() {
  elkPromise ??= import('elkjs/lib/elk.bundled.js').then((m) => new m.default());
  return elkPromise;
}

/**
 * Lays the graph out left to right with ELK's layered algorithm and returns positions for the nodes the layout
 * does not know yet; nodes that already have a position are left exactly where they are. The graph is laid out as
 * a whole, then shifted so the placed nodes keep their relation to the new ones.
 */
export async function autoLayout(
  nodes: Node[],
  edges: Edge[],
  existing: BoardLayout | null,
): Promise<Record<string, NodePosition>> {
  const placed = existing?.nodes ?? {};
  const unplaced = nodes.filter((n) => !placed[n.id]);
  if (unplaced.length === 0) return {};

  const ids = new Set(nodes.map((n) => n.id));
  const laidOut = await (await getElk()).layout({
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.spacing.nodeNode': '56',
      'elk.layered.spacing.nodeNodeBetweenLayers': '260',
    },
    children: nodes.map((n) => ({ id: n.id, width: NODE_WIDTH, height: NODE_HEIGHT })),
    edges: edges
      .filter((e) => ids.has(e.source) && ids.has(e.target))
      .map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  });

  const computed = new Map((laidOut.children ?? []).map((c) => [c.id, { x: c.x ?? 0, y: c.y ?? 0 }]));

  // Shift by the average offset between where ELK put the placed nodes and where they really are.
  const anchors = nodes.filter((n) => placed[n.id] && computed.has(n.id));
  const dx = average(anchors.map((n) => placed[n.id].x - computed.get(n.id)!.x));
  const dy = average(anchors.map((n) => placed[n.id].y - computed.get(n.id)!.y));

  const result: Record<string, NodePosition> = {};
  for (const n of unplaced) {
    const p = computed.get(n.id) ?? { x: 0, y: 0 };
    result[n.id] = { x: Math.round(p.x + dx), y: Math.round(p.y + dy) };
  }
  return result;
}

function average(values: number[]): number {
  return values.length === 0 ? 0 : values.reduce((a, b) => a + b, 0) / values.length;
}
