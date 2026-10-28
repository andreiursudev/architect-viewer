import type { BoardComponent, BoardConnection, BoardLayout, ConnectionState, Flow, FlowStep, WorkspaceView } from '../api/types';

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

/**
 * The flow the backend builds from the reporter's golden `create-order` recording
 * (`tool/reporter/src/test/resources/golden/create-order/events.jsonl`): the HTTP request, and the insert
 * nested under it (its recorded parent is an unrecorded internal span, so the request is found by time containment).
 */
export const createOrderFlow: Flow = {
  runId: 'run-1',
  scenarioId: 'create-order',
  steps: [
    {
      stepId: '0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331',
      parentStepId: null,
      from: 'e2e-client',
      to: 'orders-service',
      connectionId: 'e2e-client--orders-service',
      kind: 'http',
      label: 'POST /orders',
      startEpochNanos: 1760000000000000000,
      durationNanos: 42000000,
      status: 201,
      event: {
        type: 'http.server',
        schemaVersion: 1,
        eventId: '0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331',
        runId: 'run-1',
        traceId: '0af7651916cd43dd8448eb211c80319c',
        spanId: 'b7ad6b7169203331',
        parentSpanId: null,
        component: 'orders-service',
        startEpochNanos: 1760000000000000000,
        durationNanos: 42000000,
        method: 'POST',
        route: '/orders',
        path: '/orders',
        status: 201,
        requestHeaders: { 'content-type': ['application/json'], 'x-multi': ['a', 'b'] },
        responseHeaders: { 'content-type': ['application/json'] },
        requestBody: '{"customerId":"c-1","items":[{"sku":"A-1","quantity":2}]}',
        responseBody: '{"id":1,"status":"CREATED"}',
        requestBodyTruncated: false,
        responseBodyTruncated: false,
      },
    },
    {
      stepId: '0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7',
      parentStepId: '0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331',
      from: 'orders-service',
      to: 'postgresql-orders',
      connectionId: 'orders-service--postgresql-orders',
      kind: 'db',
      label: 'insert into orders (customer_id, status, total, created_at) ',
      startEpochNanos: 1760000000010000000,
      durationNanos: 5000000,
      status: null,
      event: {
        type: 'db.client',
        schemaVersion: 1,
        eventId: '0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7',
        runId: 'run-1',
        traceId: '0af7651916cd43dd8448eb211c80319c',
        spanId: '00f067aa0ba902b7',
        parentSpanId: 'c1d2e3f405162738',
        component: 'orders-service',
        startEpochNanos: 1760000000010000000,
        durationNanos: 5000000,
        dbSystem: 'postgresql',
        dbName: 'orders',
        statement: 'insert into orders (customer_id, status, total, created_at) values (?, ?, ?, ?)',
        parameters: ['c-1', 'CREATED', '19.98', '2026-10-27T10:00:00Z'],
        rowCount: 1,
      },
    },
  ],
};

/** A call to a host the project does not record: the backend keeps the caller's `http.client` event as the step. */
export const unrecordedHostStep: FlowStep = {
  stepId: 'trace-client-span',
  parentStepId: null,
  from: 'orders-service',
  to: 'tax-service',
  connectionId: 'orders-service--tax-service',
  kind: 'http',
  label: 'POST /tax/quote',
  startEpochNanos: 1760000000020000000,
  durationNanos: 3000000,
  status: null,
  event: {
    type: 'http.client',
    schemaVersion: 2,
    eventId: 'trace-client-span',
    runId: 'run-1',
    traceId: 'trace',
    spanId: 'client-span',
    parentSpanId: null,
    component: 'orders-service',
    startEpochNanos: 1760000000020000000,
    durationNanos: 3000000,
    method: 'POST',
    url: 'http://tax-service:9000/tax/quote',
    serverAddress: 'tax-service',
    serverPort: 9000,
    status: null,
    errorType: 'java.net.ConnectException',
  },
};
