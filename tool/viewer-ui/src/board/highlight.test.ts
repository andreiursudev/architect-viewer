import { expect, test } from 'vitest';
import { MarkerType, type Edge } from '@xyflow/react';
import { emphasizePath, highlightFor, hopsOffBoard } from './highlight';
import { PATH_COLOR } from './toFlowElements';
import { createOrderFlow } from './testData';

test('highlight covers every hop of the flow', () => {
  const h = highlightFor(createOrderFlow);
  expect([...h.edgeIds]).toEqual(['e2e-client--orders-service', 'orders-service--postgresql-orders']);
  expect(h.nodeIds).toEqual(new Set(['e2e-client', 'orders-service', 'postgresql-orders']));
  expect(h.stepsByEdge.get('orders-service--postgresql-orders')).toHaveLength(1);
});

test('hops are numbered in the order they started, whatever order the steps arrive in', () => {
  const [request, insert] = createOrderFlow.steps;
  const h = highlightFor({ ...createOrderFlow, steps: [insert, request] });
  expect([...h.edgeIds]).toEqual(['e2e-client--orders-service', 'orders-service--postgresql-orders']);
  expect(h.orderByEdge.get('e2e-client--orders-service')).toBe(1);
  expect(h.orderByEdge.get('orders-service--postgresql-orders')).toBe(2);
});

test('repeated calls over one connection share a hop and keep every step', () => {
  const [request, insert] = createOrderFlow.steps;
  const again = { ...insert, stepId: 'again', startEpochNanos: insert.startEpochNanos + 1_000_000 };
  const h = highlightFor({ ...createOrderFlow, steps: [request, insert, again] });
  expect(h.edgeIds.size).toBe(2);
  expect(h.stepsByEdge.get('orders-service--postgresql-orders')?.map((s) => s.stepId)).toEqual([insert.stepId, 'again']);
});

test('an empty flow highlights nothing', () => {
  const h = highlightFor({ runId: 'r', scenarioId: null, steps: [] });
  expect(h.nodeIds.size).toBe(0);
  expect(h.edgeIds.size).toBe(0);
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
