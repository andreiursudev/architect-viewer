import type { Edge } from '@xyflow/react';
import { createContext, useContext } from 'react';
import type { Flow, FlowStep } from '../api/types';
import { stepsInRunOrder } from '../runOrder';
import { isFailedStep } from '../status';
import { PATH_COLOR } from './toFlowElements';

/**
 * One numbered step of a run on the board: a call over a connection, together with the calls right after it over the
 * same connection. A connection the run comes back to later gets another hop, so the numbers follow the run.
 */
export interface Hop {
  connectionId: string;
  /** 1-based position along the run: its number on the board. */
  order: number;
  /** The calls of the hop in run order; more than one when the run used the connection several times in a row. */
  steps: FlowStep[];
}

/** What a recorded run touches: the components and connections of its path, and its hops in run order. */
export interface Highlight {
  runId: string;
  nodeIds: Set<string>;
  /** Connection ids in the order the run first used them. */
  edgeIds: Set<string>;
  hops: Hop[];
  /** The hops over each connection of the path, in run order: the numbers on its edge. */
  hopsByConnection: Map<string, Hop[]>;
  /** Connections on which at least one step of the run failed. */
  failedEdgeIds: Set<string>;
}

/** Steps are taken in the order the sequence diagram draws them, whatever order the backend listed them in. */
export function highlightFor(flow: Flow): Highlight {
  const nodeIds = new Set<string>();
  const edgeIds = new Set<string>();
  const hops: Hop[] = [];
  const hopsByConnection = new Map<string, Hop[]>();
  const failedEdgeIds = new Set<string>();

  for (const step of stepsInRunOrder(flow)) {
    // A method step happens inside a component: it adds no connection to the board, nor does it break a run of calls.
    const connectionId = step.connectionId;
    if (connectionId == null) continue;
    nodeIds.add(step.from);
    nodeIds.add(step.to);
    edgeIds.add(connectionId);
    if (isFailedStep(step)) failedEdgeIds.add(connectionId);
    const last = hops.at(-1);
    if (last?.connectionId === connectionId) {
      last.steps.push(step);
    } else {
      const hop = { connectionId, order: hops.length + 1, steps: [step] };
      hops.push(hop);
      hopsByConnection.set(connectionId, [...(hopsByConnection.get(connectionId) ?? []), hop]);
    }
  }
  return { runId: flow.runId, nodeIds, edgeIds, hops, hopsByConnection, failedEdgeIds };
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

/** What the board is focused on: it decides which connections are drawn. */
export interface EdgeFocus {
  highlight: Highlight | null;
  componentId: string | null;
  connectionId: string | null;
  showAll: boolean;
}

/**
 * Hides the connections the user is not looking at, so a large workspace does not drown in lines and labels. With a run
 * selected only its path is drawn; otherwise only the connections of the selected component. The selected connection
 * always stays, and [EdgeFocus.showAll] draws everything.
 */
export function focusEdges<E extends Edge>(edges: E[], focus: EdgeFocus): E[] {
  if (focus.showAll) return edges;
  const shown = (e: E) => {
    if (e.id === focus.connectionId) return true;
    if (focus.highlight) return focus.highlight.edgeIds.has(e.id);
    return focus.componentId !== null && (e.source === focus.componentId || e.target === focus.componentId);
  };
  return edges.map((e) => (shown(e) ? e : { ...e, hidden: true }));
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
