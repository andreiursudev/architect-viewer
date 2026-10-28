import { render, screen, within } from '@testing-library/react';
import { expect, test } from 'vitest';
import type { FlowStep } from '../api/types';
import { createOrderFlow, unrecordedHostStep } from '../board/testData';
import { SequenceDiagram } from './SequenceDiagram';

function renderSteps(steps: FlowStep[]) {
  render(<SequenceDiagram flow={{ ...createOrderFlow, steps }} components={{}} selected={null} onSelect={() => {}} />);
  return screen.getByRole('group', { name: /sequence diagram/i });
}

const labelOf = (diagram: HTMLElement, name: string) => within(diagram).getByRole('button', { name }).querySelector('.seq-label');

test('the response of a failed client call is labelled with the error type and has the error tone', () => {
  const diagram = renderSteps([unrecordedHostStep]);
  const label = labelOf(diagram, 'response ConnectException');
  expect(label).toHaveTextContent('ConnectException');
  expect(label).toHaveClass('is-server-error');
});

test('a 409 response has the client error tone and a 201 has none', () => {
  const [request] = createOrderFlow.steps;
  const conflict = renderSteps([{ ...request, status: 409 }]);
  expect(labelOf(conflict, 'response 409')).toHaveClass('is-client-error');
});

test('a successful response has no error tone', () => {
  const diagram = renderSteps(createOrderFlow.steps);
  const label = labelOf(diagram, 'response 201');
  expect(label).not.toHaveClass('is-client-error');
  expect(label).not.toHaveClass('is-server-error');
});
