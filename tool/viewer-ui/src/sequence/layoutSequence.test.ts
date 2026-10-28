import { expect, test } from 'vitest';
import type { Flow, FlowStep } from '../api/types';
import { createOrderFlow } from '../board/testData';
import { layoutSequence } from './layoutSequence';

test('sequence rows are request, nested request, nested response, response', () => {
  const l = layoutSequence(createOrderFlow);
  expect(l.participants.map((p) => p.id)).toEqual(['e2e-client', 'orders-service', 'postgresql-orders']);
  expect(l.messages.map((m) => `${m.kind}:${m.label}`)).toEqual([
    'request:POST /orders',
    `request:${createOrderFlow.steps[1].label}`,
    'response:1 rows',
    'response:201',
  ]);
});

test('columns are 220 apart and rows 48 apart', () => {
  const l = layoutSequence(createOrderFlow);
  expect(l.participants.map((p) => p.x - l.participants[0].x)).toEqual([0, 220, 440]);
  const ys = l.messages.map((m) => m.y);
  expect(ys.slice(1).map((y, i) => y - ys[i])).toEqual([48, 48, 48]);
  expect(l.height).toBeGreaterThan(ys[ys.length - 1]);
});

test('a request runs from caller to callee and its response comes back', () => {
  const l = layoutSequence(createOrderFlow);
  const x = Object.fromEntries(l.participants.map((p) => [p.id, p.x]));
  const [req, dbReq, dbRes, res] = l.messages;
  expect([req.fromX, req.toX]).toEqual([x['e2e-client'], x['orders-service']]);
  expect([dbReq.fromX, dbReq.toX]).toEqual([x['orders-service'], x['postgresql-orders']]);
  expect([dbRes.fromX, dbRes.toX]).toEqual([x['postgresql-orders'], x['orders-service']]);
  expect([res.fromX, res.toX]).toEqual([x['orders-service'], x['e2e-client']]);
  expect(req.stepId).toBe(res.stepId);
  expect(dbReq.stepId).toBe(dbRes.stepId);
});

function step(over: Partial<FlowStep> & Pick<FlowStep, 'stepId' | 'from' | 'to' | 'startEpochNanos'>): FlowStep {
  return { ...createOrderFlow.steps[0], parentStepId: null, label: over.stepId, ...over } as FlowStep;
}

test('siblings are ordered by start time and each response follows its own children', () => {
  const flow: Flow = {
    ...createOrderFlow,
    // listed out of order on purpose
    steps: [
      step({ stepId: 'b', parentStepId: 'a', from: 'api', to: 'cache', startEpochNanos: 30 }),
      step({ stepId: 'a', from: 'client', to: 'api', startEpochNanos: 10 }),
      step({ stepId: 'c', parentStepId: 'a', from: 'api', to: 'db', startEpochNanos: 20 }),
    ],
  };
  const l = layoutSequence(flow);
  expect(l.messages.map((m) => `${m.kind}:${m.stepId}`)).toEqual([
    'request:a', 'request:c', 'response:c', 'request:b', 'response:b', 'response:a',
  ]);
  expect(l.participants.map((p) => p.id)).toEqual(['client', 'api', 'db', 'cache']);
});

test('a step whose parent is not in the flow is a root', () => {
  const flow: Flow = { ...createOrderFlow, steps: [step({ stepId: 'x', parentStepId: 'gone', from: 'a', to: 'b', startEpochNanos: 1 })] };
  expect(layoutSequence(flow).messages.map((m) => m.kind)).toEqual(['request', 'response']);
});

test('a database response without a row count says ok', () => {
  const [, insert] = createOrderFlow.steps;
  const noCount = { ...insert, event: { ...insert.event, rowCount: null } } as FlowStep;
  const l = layoutSequence({ ...createOrderFlow, steps: [noCount] });
  expect(l.messages[1].label).toBe('ok');
});

test('an empty flow has no participants or messages', () => {
  const l = layoutSequence({ ...createOrderFlow, steps: [] });
  expect(l.participants).toEqual([]);
  expect(l.messages).toEqual([]);
});
