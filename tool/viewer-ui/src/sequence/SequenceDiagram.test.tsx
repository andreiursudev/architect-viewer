import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import type { FlowStep } from '../api/types';
import userEvent from '@testing-library/user-event';
import { consumeStep, createOrderFlow, kafkaStep, loadStep, methodFlow, produceStep, unrecordedHostStep } from '../board/testData';
import { SequenceDiagram, type MessageSelection } from './SequenceDiagram';

function renderSteps(steps: FlowStep[], expanded: string[] = [], onToggle: (component: string) => void = () => {}) {
  render(
    <SequenceDiagram
      flow={{ ...createOrderFlow, steps }}
      components={{ 'orders-service': { displayName: 'Orders service', type: 'service' } }}
      expanded={new Set(expanded)}
      onToggle={onToggle}
      selected={null}
      onSelect={() => {}}
    />,
  );
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

test('a client call that failed after a non-error status is not drawn as ok', () => {
  const failedAfterOk = {
    ...unrecordedHostStep,
    status: 200,
    event: { ...unrecordedHostStep.event, status: 200 },
  } as FlowStep;
  const label = labelOf(renderSteps([failedAfterOk]), 'response 200');
  expect(label).not.toHaveClass('is-ok');
  expect(label).toHaveClass('is-server-error');
});

test('collapsed service with methods shows a class count toggle', async () => {
  const onToggle = vi.fn();
  renderSteps(methodFlow.steps, [], onToggle);
  await userEvent.click(screen.getByRole('button', { name: '▸ 4 classes' }));
  expect(onToggle).toHaveBeenCalledWith('orders-service');
});

test('no toggle without method steps', () => {
  renderSteps(createOrderFlow.steps);
  expect(screen.queryByText(/classes?$/)).not.toBeInTheDocument();
});

test('expanded group shows its name and collapses', async () => {
  const onToggle = vi.fn();
  renderSteps(methodFlow.steps, ['orders-service'], onToggle);
  expect(screen.getByText('Orders service', { selector: '.seq-group-name' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /classes$/ })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Collapse Orders service' }));
  expect(onToggle).toHaveBeenCalledWith('orders-service');
});

test('a class lifeline is headed by its simple name and titled with the full one', () => {
  renderSteps(methodFlow.steps, ['orders-service']);
  const head = screen.getByText('OrdersController').closest('.seq-head');
  expect(head).toHaveAttribute('title', 'x.OrdersController');
  expect(screen.getAllByText(/^Order(s?Controller|Service|Repository)$|^InventoryClient$/)).toHaveLength(4);
});

const shot = '9a8b7c6d5e4f3021';

function renderBrowserRun(selected: MessageSelection | null = null, onSelect: (s: MessageSelection) => void = () => {}) {
  render(
    <SequenceDiagram
      flow={{ ...createOrderFlow, runId: 'run-6', steps: [{ ...loadStep, screenshot: shot }] }}
      components={{}}
      expanded={new Set()}
      onToggle={() => {}}
      selected={selected}
      onSelect={onSelect}
    />,
  );
  return within(screen.getByRole('group', { name: /sequence diagram/i })).getByRole('button', { name: 'screenshot load /' });
}

test('a thumbnail sits beside its ui arrow and selects the step', async () => {
  const onSelect = vi.fn();
  const thumbnail = renderBrowserRun(null, onSelect);
  const image = thumbnail.querySelector('image')!;
  expect(image.getAttribute('href')).toBe(`/api/runs/run-6/screenshots/${shot}`);
  expect(image.getAttribute('x')).toBe('7');
  expect(image.getAttribute('y')).toBe('73'); // the arrow's row (100) minus half a thumbnail
  expect(image.getAttribute('width')).toBe('96');
  expect(image.getAttribute('height')).toBe('54');

  await userEvent.click(thumbnail);
  expect(onSelect).toHaveBeenCalledWith({ stepId: 'load', kind: 'request' });
});

test("an unselected step's thumbnail is not outlined", () => {
  expect(renderBrowserRun().querySelector('rect')).not.toHaveClass('is-selected');
});

test("a selected step's thumbnail is outlined", () => {
  expect(renderBrowserRun({ stepId: 'load', kind: 'request' }).querySelector('rect')).toHaveClass('is-selected');
});

test('a thumbnail that fails to load says so', () => {
  const thumbnail = renderBrowserRun();
  fireEvent.error(thumbnail.querySelector('image')!);
  expect(thumbnail.querySelector('image')).toBeNull();
  expect(thumbnail.querySelector('rect')).toHaveClass('is-missing');
  expect(thumbnail.querySelector('title')).toHaveTextContent('Screenshot not available');
});

const MS = 1_000_000;
/** A produce that ended 14 ms before the consume it caused started. */
const earlyProduce = { ...produceStep, startEpochNanos: consumeStep.startEpochNanos - 18 * MS, durationNanos: 4 * MS };
const caused = { ...consumeStep, causedBy: 'produce' } as FlowStep;

test('a kafka row is async: an open arrowhead and no response row', () => {
  const diagram = renderSteps([earlyProduce, caused]);
  const produce = within(diagram).getByRole('button', { name: 'request produce order-events' });
  expect(produce.querySelector('.seq-arrow-line')).toHaveAttribute('marker-end', 'url(#seq-arrow-open)');
  expect(produce).toHaveClass('is-async');
  expect(within(diagram).queryByRole('button', { name: /^response/ })).not.toBeInTheDocument();
});

test('a consume row carries its gap note beside the label; a produce row has none', () => {
  const diagram = renderSteps([earlyProduce, caused]);
  const consume = within(diagram).getByRole('button', { name: 'request consume order-events' });
  expect(consume.querySelector('.seq-note')).toHaveTextContent('+14 ms');
  expect(consume.querySelector('.seq-label')).toHaveTextContent('consume order-events');
  expect(within(diagram).getByRole('button', { name: 'request produce order-events' }).querySelector('.seq-note')).toBeNull();
});

test('a consume without a known produce has no note', () => {
  const diagram = renderSteps([{ ...consumeStep, causedBy: null } as FlowStep]);
  expect(within(diagram).getByRole('button', { name: 'request consume order-events' }).querySelector('.seq-note')).toBeNull();
});

test('a failed kafka step is drawn with the error tone, as a failed response is', () => {
  const diagram = renderSteps([kafkaStep('produce', 'produce', { errorType: 'org.apache.kafka.common.errors.TimeoutException' })]);
  expect(labelOf(diagram, 'request produce order-events')).toHaveClass('is-server-error');
});

test('a consume draws an activation bar on its consumer and a produce draws none', () => {
  const diagram = renderSteps([earlyProduce, caused]);
  expect(diagram.querySelectorAll('.seq-bar')).toHaveLength(1);
});
