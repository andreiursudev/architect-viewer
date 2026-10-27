import { ReactFlowProvider, type NodeProps } from '@xyflow/react';
import { render } from '@testing-library/react';
import { expect, test } from 'vitest';
import { ComponentNode } from './ComponentNode';
import { HighlightContext, highlightFor } from './highlight';
import { comp, createOrderFlow } from './testData';
import type { ComponentNode as ComponentNodeType } from './toFlowElements';

function renderNode(id: string, flow: typeof createOrderFlow | null) {
  const props = { id, data: comp(id) } as unknown as NodeProps<ComponentNodeType>;
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
