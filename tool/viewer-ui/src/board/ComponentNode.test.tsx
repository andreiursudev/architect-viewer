import { ReactFlowProvider, type NodeProps } from '@xyflow/react';
import { cleanup, render } from '@testing-library/react';
import { expect, test } from 'vitest';
import { ComponentNode } from './ComponentNode';
import { HighlightContext, highlightFor } from './highlight';
import type { BoardComponent } from '../api/types';
import { comp, createOrderFlow } from './testData';
import type { ComponentNode as ComponentNodeType } from './toFlowElements';

function renderNode(id: string, flow: typeof createOrderFlow | null, over: Partial<BoardComponent> = {}) {
  const props = { id, data: comp(id, over) } as unknown as NodeProps<ComponentNodeType>;
  render(
    <ReactFlowProvider>
      <HighlightContext.Provider value={flow ? highlightFor(flow) : null}>
        <ComponentNode {...props} />
      </HighlightContext.Provider>
    </ReactFlowProvider>,
  );
  return document.querySelector('.component-node') as HTMLElement;
}

test('a component on the path is emphasised', () => {
  const node = renderNode('orders-service', createOrderFlow);
  expect(node).toHaveClass('on-path');
  expect(node).not.toHaveClass('is-dimmed');
});

test('a component off the path is dimmed', () => {
  expect(renderNode('billing-service', createOrderFlow)).toHaveClass('is-dimmed');
});

test('without a selected run no component is dimmed', () => {
  const node = renderNode('billing-service', null);
  expect(node).not.toHaveClass('is-dimmed');
  expect(node).not.toHaveClass('on-path');
});

test('frontend node shows the browser icon', () => {
  const frontend = renderNode('frontend', null, { type: 'frontend' }).querySelector('svg')!;
  expect(frontend).toHaveAttribute('data-icon', 'frontend');
  const frontendDrawing = frontend.innerHTML;
  cleanup();
  const service = renderNode('orders-service', null).querySelector('svg')!;
  expect(service).toHaveAttribute('data-icon', 'service');
  expect(frontendDrawing).not.toBe(service.innerHTML);
});

test('a topic node shows the stream icon and its own type class', () => {
  const topic = renderNode('topic-order-events', null, { type: 'topic' });
  expect(topic).toHaveClass('type-topic');
  expect(topic.querySelector('svg')).toHaveAttribute('data-icon', 'topic');
});
