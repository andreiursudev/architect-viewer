import { describe, expect, test } from 'vitest';
import { MarkerType, type Edge } from '@xyflow/react';
import type { FlowStep } from '../api/types';
import { emphasizePath, focusEdges, highlightFor, hopsOffBoard } from './highlight';
import { PATH_COLOR } from './toFlowElements';
import { createOrderFlow, methodFlow, unrecordedHostStep } from './testData';

/** A copy of the order request over `connectionId`, `ms` after the run started, under the step `parent`. */
function at(ms: number, connectionId: string, parent: string | null, stepId: string): FlowStep {
  const [request] = createOrderFlow.steps;
  const [from, to] = connectionId.split('--');
  return { ...request, stepId, parentStepId: parent, from, to, connectionId, startEpochNanos: request.startEpochNanos + ms * 1_000_000 };
}

test('highlight covers every hop of the flow', () => {
  const h = highlightFor(createOrderFlow);
  expect([...h.edgeIds]).toEqual(['e2e-client--orders-service', 'orders-service--postgresql-orders']);
  expect(h.nodeIds).toEqual(new Set(['e2e-client', 'orders-service', 'postgresql-orders']));
  expect(h.runId).toBe(createOrderFlow.runId);
  expect(h.hops.map((hop) => [hop.connectionId, hop.order, hop.steps.length])).toEqual([
    ['e2e-client--orders-service', 1, 1],
    ['orders-service--postgresql-orders', 2, 1],
  ]);
});

test('hops are numbered in run order, whatever order the steps arrive in', () => {
  const [request, insert] = createOrderFlow.steps;
  const h = highlightFor({ ...createOrderFlow, steps: [insert, request] });
  expect([...h.edgeIds]).toEqual(['e2e-client--orders-service', 'orders-service--postgresql-orders']);
  expect(h.hops.map((hop) => hop.connectionId)).toEqual(['e2e-client--orders-service', 'orders-service--postgresql-orders']);
});

test('calls in a row over one connection share a hop and keep every step', () => {
  const [request, insert] = createOrderFlow.steps;
  const again = { ...insert, stepId: 'again', startEpochNanos: insert.startEpochNanos + 1_000_000 };
  const h = highlightFor({ ...createOrderFlow, steps: [request, insert, again] });
  expect(h.hops).toHaveLength(2);
  expect(h.hops[1].steps.map((s) => s.stepId)).toEqual([insert.stepId, 'again']);
});

test('a connection the run comes back to gets a new hop each time, numbered in sequence order', () => {
  // client -> auth; client -> idam; client -> auth, which calls idam, then the db twice
  const steps = [
    at(0, 'client--auth', null, 'a'),
    at(10, 'client--idam', null, 'b'),
    at(20, 'client--auth', null, 'c'),
    at(21, 'auth--idam', 'c', 'd'),
    at(22, 'auth--db', 'c', 'e'),
    at(23, 'auth--db', 'c', 'f'),
  ];
  const h = highlightFor({ ...createOrderFlow, steps: [...steps].reverse() });
  expect(h.hops.map((hop) => [hop.order, hop.connectionId, hop.steps.map((s) => s.stepId).join('')])).toEqual([
    [1, 'client--auth', 'a'],
    [2, 'client--idam', 'b'],
    [3, 'client--auth', 'c'],
    [4, 'auth--idam', 'd'],
    [5, 'auth--db', 'ef'],
  ]);
  // the numbers each edge carries
  expect([...h.hopsByConnection].map(([id, hops]) => [id, hops.map((hop) => hop.order)])).toEqual([
    ['client--auth', [1, 3]],
    ['client--idam', [2]],
    ['auth--idam', [4]],
    ['auth--db', [5]],
  ]);
});

test('hops follow the sequence diagram: a nested call comes before a later sibling of its parent', () => {
  // the child started after the next root (another host's clock), but the sequence diagram draws it under its parent
  const steps = [at(0, 'client--auth', null, 'a'), at(5, 'client--idam', null, 'b'), at(9, 'auth--db', 'a', 'c')];
  expect(highlightFor({ ...createOrderFlow, steps }).hops.map((hop) => hop.steps[0].stepId)).toEqual(['a', 'c', 'b']);
});

test('a method step between two calls over one connection does not split their hop', () => {
  const [request, insert] = createOrderFlow.steps;
  const method = { ...methodFlow.steps.find((s) => s.kind === 'method')!, parentStepId: request.stepId, startEpochNanos: insert.startEpochNanos + 1 };
  const again = { ...insert, stepId: 'again', startEpochNanos: insert.startEpochNanos + 2 };
  const h = highlightFor({ ...createOrderFlow, steps: [request, insert, method, again] });
  expect(h.hops.map((hop) => hop.steps.length)).toEqual([1, 2]);
});

test('an empty flow highlights nothing', () => {
  const h = highlightFor({ runId: 'r', scenarioId: null, steps: [] });
  expect(h.nodeIds.size).toBe(0);
  expect(h.edgeIds.size).toBe(0);
  expect(h.hops).toEqual([]);
});

test('emphasizePath raises path edges and recolours their arrowheads, leaving the others alone', () => {
  const h = highlightFor(createOrderFlow);
  const onPath: Edge = {
    id: 'e2e-client--orders-service',
    source: 'e2e-client',
    target: 'orders-service',
    markerEnd: { type: MarkerType.ArrowClosed, color: '#64748b' },
  };
  const other: Edge = { id: 'orders-service--billing', source: 'orders-service', target: 'billing' };

  const [a, b] = emphasizePath([onPath, other], h);
  expect(a.zIndex).toBe(10);
  expect(a.markerEnd).toMatchObject({ color: PATH_COLOR });
  expect(b).toBe(other);
  expect(emphasizePath([onPath], null)).toEqual([onPath]);
});

test('hops without an edge on the board are counted', () => {
  const h = highlightFor(createOrderFlow);
  expect(hopsOffBoard(h, ['e2e-client--orders-service', 'orders-service--postgresql-orders'])).toBe(0);
  expect(hopsOffBoard(h, ['e2e-client--orders-service'])).toBe(1); // the insert's connection is hidden
  expect(hopsOffBoard(h, [])).toBe(2);
  expect(hopsOffBoard(null, [])).toBe(0);
});

test('failedEdgesAreCollected', () => {
  const [request, insert] = createOrderFlow.steps;
  const conflict = { ...request, stepId: 'conflict', status: 409, startEpochNanos: request.startEpochNanos + 1 };
  const h = highlightFor({ ...createOrderFlow, steps: [request, insert, conflict, unrecordedHostStep] });
  expect(h.failedEdgeIds).toEqual(new Set(['e2e-client--orders-service', 'orders-service--tax-service']));
  expect(highlightFor(createOrderFlow).failedEdgeIds.size).toBe(0);
});

test('method steps have no connection and add nothing to the path', () => {
  const h = highlightFor(methodFlow);
  expect([...h.edgeIds]).toEqual(['e2e-client--orders-service', 'orders-service--inventory-service', 'orders-service--postgresql-orders']);
  expect(h.nodeIds).toEqual(new Set(['e2e-client', 'orders-service', 'inventory-service', 'postgresql-orders']));
  expect(h.hops.flatMap((hop) => hop.steps).every((s) => s.kind !== 'method')).toBe(true);
  expect(h.hops).toHaveLength(3);
});

test('a flow of method steps alone highlights nothing', () => {
  const h = highlightFor({ ...methodFlow, steps: methodFlow.steps.filter((s) => s.kind === 'method') });
  expect(h.nodeIds.size).toBe(0);
  expect(h.edgeIds.size).toBe(0);
  expect(h.failedEdgeIds.size).toBe(0);
});

describe('focusEdges', () => {
  const edge = (id: string): Edge => {
    const [source, target] = id.split('--');
    return { id, source, target };
  };
  const edges = [
    edge('e2e-client--orders-service'),
    edge('orders-service--postgresql-orders'),
    edge('orders-service--inventory-service'),
    edge('inventory-service--postgresql-inventory'),
  ];
  const drawn = (shown: Edge[]) => shown.filter((e) => !e.hidden).map((e) => e.id);
  const none = { highlight: null, componentId: null, connectionId: null, showAll: false };

  test('with nothing selected no connection is drawn', () => {
    expect(drawn(focusEdges(edges, none))).toEqual([]);
  });

  test('a selected component draws its own connections, both ways', () => {
    expect(drawn(focusEdges(edges, { ...none, componentId: 'orders-service' }))).toEqual([
      'e2e-client--orders-service',
      'orders-service--postgresql-orders',
      'orders-service--inventory-service',
    ]);
  });

  test('a selected run draws only its path, whatever component is selected', () => {
    const highlight = highlightFor(createOrderFlow);
    expect(drawn(focusEdges(edges, { ...none, highlight, componentId: 'inventory-service' }))).toEqual([
      'e2e-client--orders-service',
      'orders-service--postgresql-orders',
    ]);
  });

  test('the selected connection stays drawn, and show-all draws everything', () => {
    const highlight = highlightFor(createOrderFlow);
    expect(drawn(focusEdges(edges, { ...none, highlight, connectionId: 'inventory-service--postgresql-inventory' }))).toContain(
      'inventory-service--postgresql-inventory',
    );
    expect(drawn(focusEdges(edges, { ...none, showAll: true }))).toHaveLength(4);
  });
});
