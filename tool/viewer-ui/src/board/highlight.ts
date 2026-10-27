import type { Edge } from '@xyflow/react';
import { createContext, useContext } from 'react';
import type { Flow, FlowStep } from '../api/types';
import { PATH_COLOR } from './toFlowElements';

/** What a recorded run touches: the components and connections of its path, and what happened on each connection. */
export interface Highlight {
  nodeIds: Set<string>;
  /** Connection ids in the order the run first used them. */
  edgeIds: Set<string>;
  stepsByEdge: Map<string, FlowStep[]>;
  /** 1-based position of each highlighted connection along the path: the number of its badge. */
  orderByEdge: Map<string, number>;
}

/** Steps are taken in the order they started, whatever order the backend listed them in. */
export function highlightFor(flow: Flow): Highlight {
  const steps = [...flow.steps].sort((a, b) => a.startEpochNanos - b.startEpochNanos);
  const nodeIds = new Set<string>();
  const edgeIds = new Set<string>();
  const stepsByEdge = new Map<string, FlowStep[]>();
  const orderByEdge = new Map<string, number>();

  for (const step of steps) {
    nodeIds.add(step.from);
    nodeIds.add(step.to);
    edgeIds.add(step.connectionId);
    const onEdge = stepsByEdge.get(step.connectionId);
    if (onEdge) {
      onEdge.push(step);
    } else {
      stepsByEdge.set(step.connectionId, [step]);
      orderByEdge.set(step.connectionId, orderByEdge.size + 1);
    }
  }
  return { nodeIds, edgeIds, stepsByEdge, orderByEdge };
}

/** Path edges are drawn above the dimmed ones and their arrowheads take the path colour; the others stay as they are. */
export function emphasizePath<E extends Edge>(edges: E[], highlight: Highlight | null): E[] {
  if (!highlight) return edges;
  return edges.map((e) =>
    highlight.edgeIds.has(e.id)
      ? {
          ...e,
          zIndex: 10,
          markerEnd: typeof e.markerEnd === 'object' ? { ...e.markerEnd, color: PATH_COLOR } : e.markerEnd,
        }
      : e,
  );
}

/** How many hops of the run have no edge on the board (a hidden connection, or a component that is not listed). */
export function hopsOffBoard(highlight: Highlight | null, drawnEdgeIds: Iterable<string>): number {
  if (!highlight) return 0;
  const drawn = new Set(drawnEdgeIds);
  return [...highlight.edgeIds].filter((id) => !drawn.has(id)).length;
}

/** The highlighted path of the selected run; `null` while no run is selected (nothing is dimmed then). */
export const HighlightContext = createContext<Highlight | null>(null);

export function useHighlight(): Highlight | null {
  return useContext(HighlightContext);
}
