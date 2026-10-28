import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Position, type EdgeProps } from '@xyflow/react';
import type { ReactNode } from 'react';
import { expect, test, vi } from 'vitest';
import { createQueryClient } from '../api/queryClient';
import { ConnectionEdge } from './ConnectionEdge';
import { highlightFor, HighlightContext } from './highlight';
import { conn, createOrderFlow, unrecordedHostStep } from './testData';
import type { ConnectionEdgeType } from './toFlowElements';

// The edge label portal only exists inside a mounted canvas; render it in place instead.
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  EdgeLabelRenderer: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

function edgeProps(id: string, label?: string | null): EdgeProps<ConnectionEdgeType> {
  const isDb = id.includes('postgres');
  return {
    id,
    data: conn(id, 'accepted', { label: label !== undefined ? label : isDb ? null : 'POST /orders', kind: isDb ? 'db' : 'http' }),
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
      <HighlightContext.Provider value={highlight}>
        <svg>
          <ConnectionEdge {...edgeProps(id, label)} />
        </svg>
      </HighlightContext.Provider>
    </QueryClientProvider>,
  );
}

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

test('without a run the chip shows the stored connection label', () => {
  renderEdge('e2e-client--orders-service', null, 'POST /orders, GET /orders/{id}');
  expect(chipLabel()).toHaveTextContent('POST /orders, GET /orders/{id}');
});

test('on the path of a run the chip shows what the run did on that hop, once per distinct label', () => {
  const [request, insert] = createOrderFlow.steps;
  const read = { ...request, stepId: 'read', label: 'GET /orders/{id}', startEpochNanos: request.startEpochNanos + 100_000_000 };
  const again = { ...request, stepId: 'again', startEpochNanos: request.startEpochNanos + 200_000_000 };
  renderEdge('e2e-client--orders-service', { ...createOrderFlow, steps: [request, insert, read, again] }, 'Orders API');
  expect(chipLabel()).toHaveTextContent('POST /orders, GET /orders/{id}');
  expect(chipLabel()).not.toHaveTextContent('Orders API');
  expect(document.querySelector('.step-badge')).toHaveTextContent('1×3');
});

test('off the path of a run the chip keeps the stored label', () => {
  renderEdge('orders-service--billing-service', createOrderFlow, 'Invoices');
  expect(chipLabel()).toHaveTextContent('Invoices');
});

test('hovering a path edge shows its hop details, and leaving hides them', async () => {
  const user = userEvent.setup();
  renderEdge('e2e-client--orders-service', createOrderFlow);
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();

  await user.hover(chip());
  expect(await screen.findByRole('tooltip')).toHaveTextContent('POST /orders');
  expect(screen.getByRole('tooltip')).toHaveTextContent('201');

  await user.unhover(chip());
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
});

test('hovering an edge outside the path shows no hop details', async () => {
  const user = userEvent.setup();
  renderEdge('orders-service--billing-service', createOrderFlow);
  await user.hover(chip());
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
});

test('a hop that failed on the path is marked on its chip', () => {
  const [request, insert] = createOrderFlow.steps;
  renderEdge('e2e-client--orders-service', { ...createOrderFlow, steps: [{ ...request, status: 409 }, insert] });
  expect(chip()).toHaveClass('is-failed');
});

test('a failed chip shows the response of the first failed step in run order', () => {
  const [request, insert] = createOrderFlow.steps;
  const conflict = { ...request, stepId: 'conflict', status: 409, startEpochNanos: request.startEpochNanos + 100_000_000 };
  const error = { ...request, stepId: 'error', status: 500, startEpochNanos: request.startEpochNanos + 200_000_000 };
  const ok = { ...request, stepId: 'ok', startEpochNanos: request.startEpochNanos + 300_000_000 };
  // listed out of run order on purpose
  renderEdge('e2e-client--orders-service', { ...createOrderFlow, steps: [error, ok, conflict, insert] });
  expect(document.querySelector('.edge-response')).toHaveTextContent('409');
  expect(document.querySelector('.edge-response')).not.toHaveTextContent('500');
});

test('a failed chip shows the error type of a call that got no answer', () => {
  renderEdge('orders-service--tax-service', { ...createOrderFlow, steps: [unrecordedHostStep] });
  expect(chip()).toHaveClass('is-failed');
  expect(document.querySelector('.edge-response')).toHaveTextContent('ConnectException');
});

test('a hop that succeeded is not marked as failed and shows no response text', () => {
  renderEdge('e2e-client--orders-service', createOrderFlow);
  expect(chip()).not.toHaveClass('is-failed');
  expect(document.querySelector('.edge-response')).toBeNull();
});

test('a failed hop elsewhere on the path does not mark this chip', () => {
  const [request, insert] = createOrderFlow.steps;
  renderEdge('orders-service--postgresql-orders', { ...createOrderFlow, steps: [{ ...request, status: 409 }, insert] });
  expect(chip()).not.toHaveClass('is-failed');
});
