import { describe, expect, test } from 'vitest';
import type { Flow, FlowStep } from '../api/types';
import { createOrderFlow, kafkaStep, loadStep, methodFlow, methodStep, routeStep, submitStep, unrecordedHostStep } from '../board/testData';
import { GROUP_BAND, HEADER_HEIGHT, MARGIN_X, SHOT_ROW_GAP, classCounts, layoutSequence } from './layoutSequence';

test('sequence rows are request, nested request, nested response, response', () => {
  const l = layoutSequence(createOrderFlow);
  expect(l.participants.map((p) => p.id)).toEqual(['e2e-client', 'orders-service', 'postgresql-orders']);
  expect(l.messages.map((m) => `${m.kind}:${m.label}`)).toEqual([
    'request:POST /orders',
    `request:${createOrderFlow.steps[1].label}`,
    'response:1 row',
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

test('a database response counts its rows in the plural unless there is one', () => {
  const [, insert] = createOrderFlow.steps;
  const withRows = (rowCount: number) => ({ ...insert, event: { ...insert.event, rowCount } }) as FlowStep;
  expect(layoutSequence({ ...createOrderFlow, steps: [withRows(0)] }).messages[1].label).toBe('0 rows');
  expect(layoutSequence({ ...createOrderFlow, steps: [withRows(1)] }).messages[1].label).toBe('1 row');
  expect(layoutSequence({ ...createOrderFlow, steps: [withRows(3)] }).messages[1].label).toBe('3 rows');
});

test('an empty flow has no participants or messages', () => {
  const l = layoutSequence({ ...createOrderFlow, steps: [] });
  expect(l.participants).toEqual([]);
  expect(l.messages).toEqual([]);
});

test('fourServicesNestTwoDeep', () => {
  const flow: Flow = {
    ...createOrderFlow,
    steps: [
      step({ stepId: 'orders', from: 'e2e-client', to: 'orders-service', startEpochNanos: 1 }),
      step({ stepId: 'inventory', parentStepId: 'orders', from: 'orders-service', to: 'inventory-service', startEpochNanos: 2 }),
      step({ stepId: 'db', parentStepId: 'inventory', from: 'inventory-service', to: 'postgresql-inventory', startEpochNanos: 3 }),
    ],
  };
  const l = layoutSequence(flow);
  expect(l.participants.map((p) => p.id)).toEqual(['e2e-client', 'orders-service', 'inventory-service', 'postgresql-inventory']);
  expect(l.messages.map((m) => `${m.kind}:${m.stepId}`)).toEqual([
    'request:orders', 'request:inventory', 'request:db', 'response:db', 'response:inventory', 'response:orders',
  ]);
});

test('the response of a client call that failed names the error', () => {
  const l = layoutSequence({ ...createOrderFlow, steps: [unrecordedHostStep] });
  expect(l.messages.map((m) => m.label)).toEqual(['POST /tax/quote', 'ConnectException']);
});

test('a step that lists itself among its own ancestors is laid out once', () => {
  // two steps share an id, so the second is a child of the first and of itself
  const flow: Flow = {
    ...createOrderFlow,
    steps: [
      step({ stepId: 'a', from: 'client', to: 'api', startEpochNanos: 1 }),
      step({ stepId: 'a', parentStepId: 'a', from: 'api', to: 'db', startEpochNanos: 2 }),
    ],
  };
  expect(layoutSequence(flow).messages.map((m) => `${m.kind}:${m.stepId}`)).toEqual(['request:a', 'request:a', 'response:a', 'response:a']);
});

const withoutMethods = (flow: Flow): Flow => {
  const byId = new Map(flow.steps.map((s) => [s.stepId, s]));
  const nearest = (id: string | null | undefined): string | null => {
    const parent = id != null ? byId.get(id) : undefined;
    return parent == null ? null : parent.kind === 'method' ? nearest(parent.parentStepId) : parent.stepId;
  };
  return { ...flow, steps: flow.steps.filter((s) => s.kind !== 'method').map((s) => ({ ...s, parentStepId: nearest(s.parentStepId) })) };
};

test('collapsed run with methods lays out like the run without them', () => {
  const collapsed = layoutSequence(methodFlow);
  const plain = layoutSequence(withoutMethods(methodFlow));
  expect(collapsed.participants).toEqual(plain.participants);
  expect(collapsed.messages).toEqual(plain.messages);
  expect(collapsed.groups).toEqual([]);
  expect(collapsed.headerHeight).toBe(HEADER_HEIGHT);
  expect(collapsed.height).toBe(plain.height);
});

const CLASSES = ['x.OrdersController', 'x.OrderService', 'x.InventoryClient', 'x.OrderRepository'];

test('expanded service gets one lifeline per class in order of appearance', () => {
  const l = layoutSequence(methodFlow, new Set(['orders-service']));
  expect(l.participants.map((p) => p.id)).toEqual([
    'e2e-client',
    ...CLASSES.map((c) => `orders-service::${c}`),
    'inventory-service',
    'postgresql-orders',
  ]);
  expect(l.participants.slice(1, 5).map((p) => [p.component, p.unit])).toEqual(CLASSES.map((c) => ['orders-service', c]));
  expect(l.participants.map((p) => p.x - MARGIN_X)).toEqual([0, 1, 2, 3, 4, 5, 6].map((i) => i * 220));
});

test('request lands on the handler class and calls leave from their owner class', () => {
  const l = layoutSequence(methodFlow, new Set(['orders-service']));
  const x = Object.fromEntries(l.participants.map((p) => [p.id, p.x]));
  const at = (stepId: string, kind: 'request' | 'response') => l.messages.find((m) => m.stepId === stepId && m.kind === kind)!;
  const unit = (c: string) => x[`orders-service::${c}`];
  expect([at('req', 'request').fromX, at('req', 'request').toX]).toEqual([x['e2e-client'], unit('x.OrdersController')]);
  expect([at('place', 'request').fromX, at('place', 'request').toX]).toEqual([unit('x.OrdersController'), unit('x.OrderService')]);
  expect([at('total', 'request').fromX, at('total', 'request').toX]).toEqual([unit('x.OrderService'), unit('x.OrderService')]);
  expect([at('inv', 'request').fromX, at('inv', 'request').toX]).toEqual([unit('x.InventoryClient'), x['inventory-service']]);
  expect([at('ins', 'request').fromX, at('ins', 'request').toX]).toEqual([unit('x.OrderRepository'), x['postgresql-orders']]);
  expect([at('req', 'response').fromX, at('req', 'response').toX]).toEqual([unit('x.OrdersController'), x['e2e-client']]);
});

test('two classes with the same simple name get two lifelines', () => {
  const flow: Flow = {
    ...createOrderFlow,
    steps: [
      step({ stepId: 'r', from: 'client', to: 'api', toUnit: 'a.Handler', startEpochNanos: 1 }),
      methodStep('m', 'r', 'api', 'a.Handler', 'b.Handler', 'go', 2),
    ],
  };
  const l = layoutSequence(flow, new Set(['api']));
  expect(l.participants.map((p) => p.id)).toEqual(['client', 'api::a.Handler', 'api::b.Handler']);
});

test('a step with no class on an expanded service uses the service lifeline', () => {
  const flow: Flow = {
    ...createOrderFlow,
    steps: [
      step({ stepId: 'r', from: 'client', to: 'api', startEpochNanos: 1 }),
      step({ stepId: 'q', parentStepId: 'r', from: 'api', to: 'db', startEpochNanos: 2 }),
    ],
  };
  const l = layoutSequence(flow, new Set(['api']));
  expect(l.participants.map((p) => p.id)).toEqual(['client', 'api', 'db']);
  expect(l.groups).toHaveLength(1);
});

test('group spans its classes and raises the header', () => {
  const l = layoutSequence(methodFlow, new Set(['orders-service']));
  const x = Object.fromEntries(l.participants.map((p) => [p.id, p.x]));
  expect(l.groups).toEqual([
    { component: 'orders-service', fromX: x['orders-service::x.OrdersController'], toX: x['orders-service::x.OrderRepository'] },
  ]);
  expect(l.headerHeight).toBe(HEADER_HEIGHT + GROUP_BAND);
  expect(l.messages[0].y).toBe(layoutSequence(methodFlow).messages[0].y + GROUP_BAND);
});

test('a component that is expanded but not in the run adds no group', () => {
  expect(layoutSequence(methodFlow, new Set(['elsewhere'])).groups).toEqual([]);
});

test('classCounts counts the distinct classes of each service that has method steps', () => {
  expect(classCounts(methodFlow)).toEqual(new Map([['orders-service', 4]]));
  expect(classCounts(createOrderFlow)).toEqual(new Map());
});

test('ui step has a request arrow and no response', () => {
  const http = { ...createOrderFlow.steps[0], parentStepId: submitStep.stepId, from: 'frontend', startEpochNanos: submitStep.startEpochNanos + 1 } as FlowStep;
  const l = layoutSequence({ ...createOrderFlow, steps: [submitStep, http] });
  expect(l.messages).toHaveLength(3);
  expect(l.messages[0].kind).toBe('request');
  expect(l.messages[0].label).toBe('submit order-form');
  expect(l.messages.map((m) => `${m.kind}:${m.stepId}`)).toEqual(['request:submit', 'request:' + http.stepId, 'response:' + http.stepId]);
  expect(l.participants.map((p) => p.id)).toEqual(['e2e-client', 'frontend', 'orders-service']);
});

const shotA = '9a8b7c6d5e4f3021';
const shotB = '6d5e4f302192a3b4';
const flowOf = (steps: FlowStep[]): Flow => ({ ...createOrderFlow, runId: 'run-6', steps });
/** The POST a submit sends, nested under the ui step before it. */
const fetchUnder = (parent: FlowStep) =>
  step({ stepId: 'post', from: 'frontend', to: 'orders-service', startEpochNanos: parent.startEpochNanos + 1, parentStepId: parent.stepId });

test('a ui row with a screenshot is taller', () => {
  const load = { ...loadStep, screenshot: shotA };
  const l = layoutSequence(flowOf([load, fetchUnder(load)]));
  expect(SHOT_ROW_GAP).toBe(76);
  expect(l.messages.map((m) => `${m.kind}:${m.stepId}`)).toEqual(['request:load', 'request:post', 'response:post']);
  expect(l.messages.map((m) => m.y)).toEqual([100, 176, 224]);
  expect(l.height).toBe(264);
});

test('a row after a screenshot row is also kept apart', () => {
  const l = layoutSequence(flowOf([{ ...loadStep, screenshot: shotA }, { ...routeStep, screenshot: shotB }, submitStep]));
  expect(l.messages.map((m) => m.y)).toEqual([100, 176, 252]);
});

test('a run without screenshots keeps its rows', () => {
  const l = layoutSequence(flowOf([loadStep, routeStep, submitStep]));
  expect(l.messages.map((m) => m.y)).toEqual([100, 148, 196]);
  expect(l.messages.map((m) => m.screenshot)).toEqual([null, null, null]);
});

test('only the request row of a ui step carries its screenshot', () => {
  const load = { ...loadStep, screenshot: shotA };
  const l = layoutSequence(flowOf([load, fetchUnder(load)]));
  expect(l.messages.map((m) => m.screenshot)).toEqual([shotA, null, null]);
});

describe('kafka steps', () => {
  const MS = 1_000_000;
  /** A produce under `parent`, and the consume it caused; times are relative to the flow start, in ms. */
  const produceAt = (parent: string | null, at: number, over: Partial<FlowStep> = {}): FlowStep => ({
    ...kafkaStep('produce', 'produce'),
    parentStepId: parent,
    startEpochNanos: createOrderFlow.steps[0].startEpochNanos + at * MS,
    durationNanos: 4 * MS,
    ...over,
  });
  const consumeAt = (at: number, over: Partial<FlowStep> = {}): FlowStep => ({
    ...kafkaStep('consume', 'consume'),
    causedBy: 'produce',
    startEpochNanos: createOrderFlow.steps[0].startEpochNanos + at * MS,
    ...over,
  });
  const dbUnder = (parent: string, at: number): FlowStep =>
    step({ stepId: 'ship-db', parentStepId: parent, from: 'shipping-service', to: 'postgresql-shipping', startEpochNanos: createOrderFlow.steps[0].startEpochNanos + at * MS });
  const request = step({ stepId: 'req', from: 'e2e-client', to: 'orders-service', startEpochNanos: createOrderFlow.steps[0].startEpochNanos });
  const tag = (l: ReturnType<typeof layoutSequence>) => l.messages.map((m) => `${m.kind}:${m.stepId}`);

  test('a kafka step has a request row and no response row, drawn async', () => {
    const l = layoutSequence({ ...createOrderFlow, steps: [request, produceAt('req', 2), consumeAt(20)] });
    expect(tag(l)).toEqual(['request:req', 'request:produce', 'response:req', 'request:consume']);
    expect(l.messages.map((m) => m.async)).toEqual([false, true, false, true]);
    expect(l.participants.map((p) => p.id)).toEqual(['e2e-client', 'orders-service', 'topic-order-events', 'shipping-service']);
  });

  test('the produce row runs from the producer to the topic and the consume row from the topic to the consumer', () => {
    const l = layoutSequence({ ...createOrderFlow, steps: [request, produceAt('req', 2), consumeAt(20)] });
    const x = Object.fromEntries(l.participants.map((p) => [p.id, p.x]));
    const [, produce, , consume] = l.messages;
    expect([produce.fromX, produce.toX]).toEqual([x['orders-service'], x['topic-order-events']]);
    expect([consume.fromX, consume.toX]).toEqual([x['topic-order-events'], x['shipping-service']]);
    expect(produce.label).toBe('produce order-events');
    expect(consume.label).toBe('consume order-events');
  });

  test('the produce has no bar and the consume bar runs to the last row of its subtree', () => {
    const flow = { ...createOrderFlow, steps: [request, produceAt('req', 2), consumeAt(20), dbUnder('consume', 22)] };
    const l = layoutSequence(flow);
    expect(tag(l)).toEqual(['request:req', 'request:produce', 'response:req', 'request:consume', 'request:ship-db', 'response:ship-db']);
    const x = Object.fromEntries(l.participants.map((p) => [p.id, p.x]));
    const y = (i: number) => l.messages[i].y;
    expect(l.bars.map((b) => b.stepId)).toEqual(['req', 'consume', 'ship-db']);
    expect(l.bars).toEqual([
      { stepId: 'req', x: x['orders-service'], from: y(0), to: y(2) },
      { stepId: 'consume', x: x['shipping-service'], from: y(3), to: y(5) },
      { stepId: 'ship-db', x: x['postgresql-shipping'], from: y(4), to: y(5) },
    ]);
  });

  test('a consume without children has a bar of its own row', () => {
    const l = layoutSequence({ ...createOrderFlow, steps: [consumeAt(20, { causedBy: null })] });
    expect(l.bars).toEqual([{ stepId: 'consume', x: l.participants[1].x, from: l.messages[0].y, to: l.messages[0].y }]);
  });

  test('every other step keeps its bar from request to response, a ui step on its own row', () => {
    const l = layoutSequence(createOrderFlow);
    const x = Object.fromEntries(l.participants.map((p) => [p.id, p.x]));
    const [req, dbReq, dbRes, res] = l.messages;
    expect(l.bars).toEqual([
      { stepId: req.stepId, x: x['orders-service'], from: req.y, to: res.y },
      { stepId: dbReq.stepId, x: x['postgresql-orders'], from: dbReq.y, to: dbRes.y },
    ]);
    const ui = layoutSequence({ ...createOrderFlow, steps: [submitStep] });
    expect(ui.bars).toEqual([{ stepId: 'submit', x: ui.participants[1].x, from: ui.messages[0].y, to: ui.messages[0].y }]);
  });

  test('the consume row carries the gap from the end of its produce, rounded to whole ms', () => {
    // the produce starts at 2 ms and lasts 4 ms: it ends at 6 ms; the consume starts at 20 ms
    const l = layoutSequence({ ...createOrderFlow, steps: [request, produceAt('req', 2), consumeAt(20)] });
    expect(l.messages.map((m) => m.note)).toEqual([null, null, null, '+14 ms']);
    const rounded = layoutSequence({ ...createOrderFlow, steps: [request, produceAt('req', 2), consumeAt(20.4)] });
    expect(rounded.messages[3].note).toBe('+14 ms');
  });

  test('a consume that started before its produce ended shows the real signed gap', () => {
    const l = layoutSequence({ ...createOrderFlow, steps: [request, produceAt('req', 2), consumeAt(4)] });
    expect(l.messages[3].note).toBe('-2 ms');
  });

  test('a consume with no cause, or one that is not in the flow, has no note', () => {
    expect(layoutSequence({ ...createOrderFlow, steps: [consumeAt(20, { causedBy: null })] }).messages[0].note).toBeNull();
    expect(layoutSequence({ ...createOrderFlow, steps: [consumeAt(20, { causedBy: 'gone' })] }).messages[0].note).toBeNull();
  });

  test('the consume comes after the whole subtree of the request that produced it, even if it started earlier than the response', () => {
    const late = step({ stepId: 'late', parentStepId: 'req', from: 'orders-service', to: 'postgresql-orders', startEpochNanos: request.startEpochNanos + 30 * MS });
    const l = layoutSequence({ ...createOrderFlow, steps: [consumeAt(10), request, produceAt('req', 2), late] });
    expect(tag(l)).toEqual([
      'request:req', 'request:produce', 'request:late', 'response:late', 'response:req', 'request:consume',
    ]);
  });
});
