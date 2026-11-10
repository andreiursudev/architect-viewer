import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Position, type EdgeProps } from '@xyflow/react';
import type { ReactNode } from 'react';
import { InWorkspace } from '../test/inWorkspace';
import { afterEach, expect, test, vi } from 'vitest';
import { createQueryClient } from '../api/queryClient';
import { useUiStore } from '../store/uiStore';
import { ConnectionEdge } from './ConnectionEdge';
import { highlightFor, HighlightContext } from './highlight';
import { conn, createOrderFlow, unrecordedHostStep } from './testData';
import { edgeColor } from './toFlowElements';
import type { ConnectionEdgeType } from './toFlowElements';

// The edge label portal only exists inside a mounted canvas; render it in place instead.
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  EdgeLabelRenderer: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

function edgeProps(id: string, label?: string | null): EdgeProps<ConnectionEdgeType> {
  const isDb = id.includes('postgres');
  const isKafka = id.includes('topic-');
  return {
    id,
    data: conn(id, 'accepted', {
      label: label !== undefined ? label : isDb || isKafka ? null : 'POST /orders',
      kind: isKafka ? 'kafka' : isDb ? 'db' : 'http',
    }),
    source: 'a',
    target: 'b',
    sourceX: 0,
    sourceY: 0,
    targetX: 200,
    targetY: 0,
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    selected: false,
    animated: false,
    selectable: true,
    deletable: false,
    markerEnd: undefined,
    style: {},
  } as unknown as EdgeProps<ConnectionEdgeType>;
}

function renderEdge(id: string, flow: typeof createOrderFlow | null, label?: string | null) {
  const highlight = flow ? highlightFor(flow) : null;
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <InWorkspace>
        <HighlightContext.Provider value={highlight}>
          <svg>
            <ConnectionEdge {...edgeProps(id, label)} />
          </svg>
        </HighlightContext.Provider>
      </InWorkspace>
    </QueryClientProvider>,
  );
}

afterEach(() => useUiStore.getState().selectConnection(null));
afterEach(() => useUiStore.getState().selectStep(null));

const chip = () => document.querySelector('.edge-chip') as HTMLElement;
const path = () => document.querySelector('.react-flow__edge-path') as SVGPathElement;

test('without a selected run nothing is dimmed and there is no badge', () => {
  renderEdge('e2e-client--orders-service', null);
  expect(chip()).not.toHaveClass('is-dimmed');
  expect(chip()).not.toHaveClass('on-path');
  expect(document.querySelector('.step-badge')).toBeNull();
});

test('a path edge is highlighted and badged with its position along the path', () => {
  renderEdge('orders-service--postgresql-orders', createOrderFlow);
  expect(chip()).toHaveClass('on-path');
  expect(path()).toHaveClass('path-edge');
  expect(path().style.strokeWidth).toBe('4');
  expect(document.querySelector('.step-badge')).toHaveTextContent('2');
});

test('an edge outside the path is dimmed and has no badge', () => {
  renderEdge('orders-service--billing-service', createOrderFlow);
  expect(chip()).toHaveClass('is-dimmed');
  expect(path()).toHaveClass('dimmed-edge');
  expect(document.querySelector('.step-badge')).toBeNull();
});

test('repeated calls over one connection add a multiplier to the badge', () => {
  const [request, insert] = createOrderFlow.steps;
  const again = { ...insert, stepId: 'again', startEpochNanos: insert.startEpochNanos + 1_000_000 };
  renderEdge('orders-service--postgresql-orders', { ...createOrderFlow, steps: [request, insert, again] });
  expect(document.querySelector('.step-badge')).toHaveTextContent('2×2');
});

const chipLabel = () => document.querySelector('.edge-label') as HTMLElement;
const badges = () => [...document.querySelectorAll<HTMLElement>('.step-badge')];

test('without a run the chip shows the stored connection label', () => {
  renderEdge('e2e-client--orders-service', null, 'POST /orders, GET /orders/{id}');
  expect(chipLabel()).toHaveTextContent('POST /orders, GET /orders/{id}');
});

test('on the path of a run the chip carries only the numbers of its steps, in run order', () => {
  const [request, insert] = createOrderFlow.steps;
  const read = { ...request, stepId: 'read', label: 'GET /orders/{id}', startEpochNanos: request.startEpochNanos + 100_000_000 };
  const again = { ...request, stepId: 'again', startEpochNanos: request.startEpochNanos + 200_000_000 };
  // the request (1), its insert (2), then the read and the repeated request in a row (3)
  renderEdge('e2e-client--orders-service', { ...createOrderFlow, steps: [request, insert, read, again] }, 'Orders API');
  expect(badges().map((b) => b.textContent)).toEqual(['1', '3×2']);
  expect(document.querySelector('.edge-label')).toBeNull(); // no label: the sequence view tells what the steps did
  expect(chip()).not.toHaveTextContent('Orders API');
  expect(chip()).not.toHaveTextContent('POST /orders');
});

test('a step number selects its first call, for the side panel to show, and is marked as picked', async () => {
  const user = userEvent.setup();
  const [request, insert] = createOrderFlow.steps;
  const read = { ...request, stepId: 'read', label: 'GET /orders/{id}', startEpochNanos: request.startEpochNanos + 100_000_000 };
  const again = { ...request, stepId: 'again', startEpochNanos: request.startEpochNanos + 200_000_000 };
  useUiStore.getState().selectConnection('e2e-client--orders-service');
  renderEdge('e2e-client--orders-service', { ...createOrderFlow, steps: [request, insert, read, again] });

  await user.click(screen.getByRole('button', { name: 'Step 3' }));
  expect(useUiStore.getState().selectedStepId).toBe('read');
  expect(useUiStore.getState().selectedConnectionId).toBeNull(); // the panel shows the step, not the connection
  expect(screen.getByRole('button', { name: 'Step 3' })).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByRole('button', { name: 'Step 1' })).toHaveAttribute('aria-pressed', 'false');
  expect(screen.queryByRole('link')).not.toBeInTheDocument(); // nothing leaves the board
});

test('a step number keeps its click from the board, which would select the connection instead', async () => {
  const user = userEvent.setup();
  const boardClick = vi.fn(); // React Flow's edge click, which the portalled label still bubbles to
  render(
    <QueryClientProvider client={createQueryClient()}>
      <InWorkspace>
        <HighlightContext.Provider value={highlightFor(createOrderFlow)}>
          <svg onClick={boardClick}>
            <ConnectionEdge {...edgeProps('e2e-client--orders-service')} />
          </svg>
        </HighlightContext.Provider>
      </InWorkspace>
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole('button', { name: 'Step 1' }));
  expect(boardClick).not.toHaveBeenCalled();
  expect(useUiStore.getState().selectedStepId).toBe(createOrderFlow.steps[0].stepId);
});

test('hovering a step number tells its calls and what they answered, without a details box', async () => {
  const user = userEvent.setup();
  const [request, insert] = createOrderFlow.steps;
  const again = { ...insert, stepId: 'again', label: 'select * from orders', startEpochNanos: insert.startEpochNanos + 1_000_000 };
  renderEdge('orders-service--postgresql-orders', { ...createOrderFlow, steps: [request, insert, again] });
  expect(badges()[0].getAttribute('title')!.split('\n')).toEqual([
    'Step 2 of the run',
    `${insert.label} → 1 row`,
    'select * from orders → 1 row',
  ]);
  await user.hover(chip());
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
});

test('off the path of a run the chip keeps the stored label, and clicking it selects the connection', async () => {
  const user = userEvent.setup();
  renderEdge('orders-service--billing-service', createOrderFlow, 'Invoices');
  expect(chipLabel()).toHaveTextContent('Invoices');
  await user.click(chipLabel());
  expect(useUiStore.getState().selectedConnectionId).toBe('orders-service--billing-service');
});

test('a step that failed is marked on its number and on the chip, and its number tells the response', () => {
  const [request, insert] = createOrderFlow.steps;
  const read = { ...request, stepId: 'read', startEpochNanos: request.startEpochNanos + 100_000_000 };
  renderEdge('e2e-client--orders-service', { ...createOrderFlow, steps: [{ ...request, status: 409 }, insert, read] });
  expect(chip()).toHaveClass('is-failed');
  const [failed, fine] = badges();
  expect(failed).toHaveClass('is-failed');
  expect(failed.getAttribute('title')).toContain('POST /orders → 409');
  expect(fine).not.toHaveClass('is-failed');
});

test('a call that got no answer marks its number with the error type', () => {
  renderEdge('orders-service--tax-service', { ...createOrderFlow, steps: [unrecordedHostStep] });
  expect(chip()).toHaveClass('is-failed');
  expect(badges()[0]).toHaveClass('is-failed');
  expect(badges()[0].getAttribute('title')!).toContain('ConnectException');
});

test('a step that succeeded is not marked as failed', () => {
  renderEdge('e2e-client--orders-service', createOrderFlow);
  expect(chip()).not.toHaveClass('is-failed');
  expect(badges()[0]).not.toHaveClass('is-failed');
});

test('a failed hop elsewhere on the path does not mark this chip', () => {
  const [request, insert] = createOrderFlow.steps;
  renderEdge('orders-service--postgresql-orders', { ...createOrderFlow, steps: [{ ...request, status: 409 }, insert] });
  expect(chip()).not.toHaveClass('is-failed');
});

test('a kafka connection is drawn in its own colour, distinct from http and db', () => {
  renderEdge('orders-service--topic-order-events', null);
  const kafka = edgeColor(conn('a--b', 'accepted', { kind: 'kafka' }));
  const probe = document.createElement('span');
  probe.style.color = kafka; // the browser normalises a colour the way it did the stroke
  expect(path().style.stroke).toBe(probe.style.color);
  expect(kafka).not.toBe(edgeColor(conn('a--b', 'accepted', { kind: 'http' })));
  expect(kafka).not.toBe(edgeColor(conn('a--b', 'accepted', { kind: 'db' })));
  expect(chipLabel()).toHaveTextContent('kafka');
});
