import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Position, type EdgeProps } from '@xyflow/react';
import type { ReactNode } from 'react';
import { expect, test, vi } from 'vitest';
import { createQueryClient } from '../api/queryClient';
import { ConnectionEdge } from './ConnectionEdge';
import { highlightFor, HighlightContext } from './highlight';
import { conn, createOrderFlow } from './testData';
import type { ConnectionEdgeType } from './toFlowElements';

// The edge label portal only exists inside a mounted canvas; render it in place instead.
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  EdgeLabelRenderer: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

function edgeProps(id: string): EdgeProps<ConnectionEdgeType> {
  return {
    id,
    data: conn(id, 'accepted', { label: id === 'orders-service--postgresql-orders' ? null : 'POST /orders', kind: id.includes('postgres') ? 'db' : 'http' }),
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

function renderEdge(id: string, flow: typeof createOrderFlow | null) {
  const highlight = flow ? highlightFor(flow) : null;
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <HighlightContext.Provider value={highlight}>
        <svg>
          <ConnectionEdge {...edgeProps(id)} />
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
