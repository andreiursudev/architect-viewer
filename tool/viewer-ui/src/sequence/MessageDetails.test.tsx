import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { consumeStep, createOrderFlow, kafkaStep, loadStep, methodStep, produceStep, routeStep, submitStep, unrecordedHostStep } from '../board/testData';
import { MessageDetails } from './MessageDetails';

const [request, insert] = createOrderFlow.steps;

test('an HTTP step shows method, route, status and timing', () => {
  render(<MessageDetails runId="run-1" step={request} kind="request" />);
  expect(screen.getByRole('heading', { name: 'POST /orders' })).toBeInTheDocument();
  expect(screen.getByText('201')).toBeInTheDocument();
  expect(screen.getByText('42 ms')).toBeInTheDocument();
  expect(screen.getByText('e2e-client')).toBeInTheDocument();
  expect(screen.getByText('orders-service')).toBeInTheDocument();
});

test('headers are listed as a table, repeated values joined', () => {
  render(<MessageDetails runId="run-1" step={request} kind="request" />);
  const table = screen.getByRole('table', { name: 'Request headers' });
  expect(within(table).getByText('content-type')).toBeInTheDocument();
  expect(within(table).getByText('a, b')).toBeInTheDocument();
  expect(screen.getByRole('table', { name: 'Response headers' })).toBeInTheDocument();
});

test('JSON bodies are pretty-printed', () => {
  render(<MessageDetails runId="run-1" step={request} kind="request" />);
  const pre = screen.getByText(/"customerId": "c-1"/);
  expect(pre.textContent).toContain('{\n  "customerId": "c-1",');
  expect(screen.getByText(/"status": "CREATED"/)).toBeInTheDocument();
});

test('a body that is not JSON is shown as it was recorded', () => {
  const step = { ...request, event: { ...request.event, requestBody: '{"cut":', requestBodyTruncated: true } } as typeof request;
  render(<MessageDetails runId="run-1" step={step} kind="request" />);
  expect(screen.getByText('{"cut":')).toBeInTheDocument();
  expect(screen.getByText(/recording cut it short/)).toBeInTheDocument();
});

test('a missing body says so', () => {
  const step = { ...request, event: { ...request.event, responseBody: null } } as typeof request;
  render(<MessageDetails runId="run-1" step={step} kind="response" />);
  expect(screen.getByText('no response body')).toBeInTheDocument();
});

test('a database step shows the SQL statement, parameters, row count and timing', () => {
  render(<MessageDetails runId="run-1" step={insert} kind="request" />);
  expect(screen.getByText('5 ms')).toBeInTheDocument();
  expect(screen.getByText(/insert into orders \(customer_id, status, total, created_at\) values/)).toBeInTheDocument();
  const params = screen.getByRole('table', { name: 'Parameters' });
  expect(within(params).getByText('CREATED')).toBeInTheDocument();
  expect(screen.getByText('1 row')).toBeInTheDocument();
  expect(screen.getByText('postgresql')).toBeInTheDocument();
});

test('null parameters are shown as NULL', () => {
  const step = { ...insert, event: { ...insert.event, parameters: [null] } } as typeof insert;
  render(<MessageDetails runId="run-1" step={step} kind="request" />);
  expect(screen.getByText('NULL')).toBeInTheDocument();
});

test('the section of the clicked message is marked', () => {
  const { rerender } = render(<MessageDetails runId="run-1" step={request} kind="response" />);
  expect(screen.getByRole('region', { name: 'Response' })).toHaveClass('is-selected');
  expect(screen.getByRole('region', { name: 'Request' })).not.toHaveClass('is-selected');
  rerender(<MessageDetails runId="run-1" step={request} kind="request" />);
  expect(screen.getByRole('region', { name: 'Request' })).toHaveClass('is-selected');
});

test('a call that got no answer shows its URL, the error type and that it never reached the service', () => {
  render(<MessageDetails runId="run-1" step={unrecordedHostStep} kind="response" />);
  expect(screen.getByRole('heading', { name: 'POST /tax/quote' })).toBeInTheDocument();
  expect(screen.getByText('http://tax-service:9000/tax/quote')).toBeInTheDocument();
  expect(screen.getByText('java.net.ConnectException')).toBeInTheDocument();
  expect(screen.getByText('No response: the call failed before reaching the service.')).toBeInTheDocument();
  expect(screen.queryByText('Body not recorded: the called service is not instrumented.')).not.toBeInTheDocument();
});

test('an answered call to an unrecorded host says the called service is not instrumented', () => {
  const answered = { ...unrecordedHostStep, status: 502, event: { ...unrecordedHostStep.event, status: 502, errorType: null } } as typeof unrecordedHostStep;
  render(<MessageDetails runId="run-1" step={answered} kind="response" />);
  expect(screen.getByText('Body not recorded: the called service is not instrumented.')).toBeInTheDocument();
  expect(screen.queryByText(/No response/)).not.toBeInTheDocument();
});

const ORDERS = 'io.shop.orders.OrdersController';
function methodDetails(over: Record<string, unknown>) {
  const step = methodStep('m', null, 'orders-service', null, ORDERS, 'create', 1);
  return { ...step, event: { ...step.event, ...over } } as typeof step;
}

test('method step shows class method thread and arguments by name', () => {
  const step = methodDetails({ parameterNames: ['sku', 'quantity'], arguments: ['"sku-1"', '2'], returnValue: '{"id":7}', thread: 'http-nio-8080-exec-1' });
  render(<MessageDetails runId="run-1" step={step} kind="request" />);
  expect(screen.getByRole('heading', { name: 'OrdersController.create' })).toBeInTheDocument();
  expect(screen.getByText(ORDERS)).toBeInTheDocument();
  expect(screen.getByText('create')).toBeInTheDocument();
  expect(screen.getByText('http-nio-8080-exec-1')).toBeInTheDocument();
  const args = screen.getByRole('table', { name: 'Arguments' });
  expect(within(args).getByText('sku')).toBeInTheDocument();
  expect(within(args).getByText('"sku-1"')).toBeInTheDocument();
  expect(within(args).getByText('quantity')).toBeInTheDocument();
  expect(within(args).getByText('2')).toBeInTheDocument();
  const returned = screen.getByRole('region', { name: 'Returned' });
  expect(returned.textContent).toContain('{\n  "id": 7\n}');
});

test('arguments without names are argN', () => {
  render(<MessageDetails runId="run-1" step={methodDetails({ arguments: ['"a"', '1'] })} kind="request" />);
  const args = screen.getByRole('table', { name: 'Arguments' });
  expect(within(args).getByText('arg0')).toBeInTheDocument();
  expect(within(args).getByText('arg1')).toBeInTheDocument();
});

test('a method without arguments says none, and one without a return value says it returned nothing', () => {
  render(<MessageDetails runId="run-1" step={methodDetails({})} kind="response" />);
  expect(screen.getByRole('region', { name: 'Arguments' })).toHaveTextContent('none');
  expect(screen.getByRole('region', { name: 'Returned' })).toHaveTextContent('no value');
});

test('method that threw shows Threw with type and message', () => {
  const step = methodDetails({ exceptionType: 'org.springframework.web.server.ResponseStatusException', exceptionMessage: '409 CONFLICT "out of stock"' });
  render(<MessageDetails runId="run-1" step={step} kind="response" />);
  const threw = screen.getByRole('region', { name: 'Threw' });
  expect(threw).toHaveClass('is-selected');
  expect(threw).toHaveTextContent('org.springframework.web.server.ResponseStatusException');
  expect(threw).toHaveTextContent('409 CONFLICT "out of stock"');
  expect(screen.queryByRole('region', { name: 'Returned' })).not.toBeInTheDocument();
});

test('the clicked request or response section of a method step is marked', () => {
  const step = methodDetails({ arguments: ['1'] });
  const { rerender } = render(<MessageDetails runId="run-1" step={step} kind="request" />);
  expect(screen.getByRole('region', { name: 'Arguments' })).toHaveClass('is-selected');
  expect(screen.getByRole('region', { name: 'Returned' })).not.toHaveClass('is-selected');
  rerender(<MessageDetails runId="run-1" step={step} kind="response" />);
  expect(screen.getByRole('region', { name: 'Returned' })).toHaveClass('is-selected');
});

test('http step with a handler shows Handled by OrdersController.create with its arguments', () => {
  const handler = methodDetails({ parameterNames: ['request'], arguments: ['{"sku":"sku-1"}'], returnValue: '"ok"' }).event;
  const step = { ...request, handler } as typeof request;
  render(<MessageDetails runId="run-1" step={step} kind="request" />);
  const handled = screen.getByRole('region', { name: 'Handled by OrdersController.create' });
  expect(within(handled).getByText('request')).toBeInTheDocument();
  expect(handled.textContent).toContain('{\n  "sku": "sku-1"\n}');
  expect(handled).toHaveTextContent('"ok"');
});

test('http step without a handler has no Handled by section', () => {
  render(<MessageDetails runId="run-1" step={request} kind="request" />);
  expect(screen.queryByRole('region', { name: /Handled by/ })).not.toBeInTheDocument();
});

test('details of an action show event target and route', () => {
  render(<MessageDetails runId="run-1" step={submitStep} kind="request" />);
  expect(screen.getByRole('heading', { name: 'submit order-form' })).toBeInTheDocument();
  expect(screen.getByText('Event').nextSibling).toHaveTextContent('submit');
  expect(screen.getByText('Target').nextSibling).toHaveTextContent('order-form');
  expect(screen.getByText('Route').nextSibling).toHaveTextContent('/order');
});

test('details of a navigation show url kind and duration', () => {
  const { rerender } = render(<MessageDetails runId="run-1" step={loadStep} kind="request" />);
  expect(screen.getByText('URL').nextSibling).toHaveTextContent('http://localhost:5173/');
  expect(screen.getByText('Kind').nextSibling).toHaveTextContent('page load');
  expect(screen.getByText('Duration').nextSibling).toHaveTextContent('120 ms');
  rerender(<MessageDetails runId="run-1" step={routeStep} kind="request" />);
  expect(screen.getByText('Kind').nextSibling).toHaveTextContent('route change');
  expect(screen.getByText('Duration').nextSibling).toHaveTextContent('2 ms');
});

const shot = '9a8b7c6d5e4f3021';

test('a ui step shows its screenshot, and a click on it asks to open it full size', async () => {
  const open = vi.fn();
  render(<MessageDetails runId="run-6" step={{ ...loadStep, screenshot: shot }} kind="request" onOpenScreenshot={open} />);
  const image = screen.getByRole('img', { name: 'Screenshot after load /' });
  expect(image).toHaveAttribute('src', `/api/runs/run-6/screenshots/${shot}`);

  await userEvent.click(image);
  expect(open).toHaveBeenCalledOnce();
});

test('a screenshot that fails to load says so', () => {
  render(<MessageDetails runId="run-6" step={{ ...loadStep, screenshot: shot }} kind="request" />);
  fireEvent.error(screen.getByRole('img', { name: 'Screenshot after load /' }));
  expect(screen.queryByRole('img')).toBeNull();
  expect(screen.getByText('Screenshot not available')).toBeInTheDocument();
});

test('a ui step without a screenshot shows no image', () => {
  render(<MessageDetails runId="run-6" step={loadStep} kind="request" />);
  expect(screen.queryByRole('img')).toBeNull();
});

describe('kafka', () => {
  const MS = 1_000_000;
  const produce = { ...produceStep, startEpochNanos: consumeStep.startEpochNanos - 18 * MS, durationNanos: 4 * MS } as typeof produceStep;
  const consume = { ...consumeStep, causedBy: 'produce' } as typeof consumeStep;
  const fact = (name: string) => screen.getByText(name, { selector: 'dt' }).nextSibling;

  test('a produce lists topic, partition, offset, key and size', () => {
    render(<MessageDetails runId="run-1" step={produce} kind="request" steps={[produce, consume]} />);
    expect(screen.getByRole('heading', { name: 'produce order-events' })).toBeInTheDocument();
    expect(fact('Topic')).toHaveTextContent('order-events');
    expect(fact('Partition')).toHaveTextContent('0');
    expect(fact('Offset')).toHaveTextContent('7');
    expect(fact('Key')).toHaveTextContent('order-1');
    expect(fact('Size')).toHaveTextContent('31 bytes');
    expect(fact('Duration')).toHaveTextContent('4 ms');
  });

  test('a produce shows its headers and its value as formatted JSON', () => {
    const withHeaders = { ...produce, event: { ...produce.event, headers: { traceparent: ['00-abc'] } } } as typeof produce;
    render(<MessageDetails runId="run-1" step={withHeaders} kind="request" steps={[withHeaders]} />);
    const table = screen.getByRole('table', { name: 'Record headers' });
    expect(within(table).getByText('traceparent')).toBeInTheDocument();
    expect(within(table).getByText('00-abc')).toBeInTheDocument();
    expect(screen.getByText(/"status": "CREATED"/).textContent).toContain('{\n  "orderId": 1,');
  });

  test('a value the recording cut short is flagged', () => {
    const cut = { ...produce, event: { ...produce.event, value: '{"cut":', valueTruncated: true } } as typeof produce;
    render(<MessageDetails runId="run-1" step={cut} kind="request" steps={[cut]} />);
    expect(screen.getByText('{"cut":')).toBeInTheDocument();
    expect(screen.getByText(/recording cut it short/)).toBeInTheDocument();
  });

  test('a record without a key or a value says so', () => {
    const bare = kafkaStep('produce', 'produce', { key: null, value: null });
    render(<MessageDetails runId="run-1" step={bare} kind="request" steps={[bare]} />);
    expect(fact('Key')).toHaveTextContent('none');
    expect(screen.getByText('no value')).toBeInTheDocument();
  });

  test('a produce says who consumed it and how long after', () => {
    render(<MessageDetails runId="run-1" step={produce} kind="request" steps={[produce, consume]} />);
    expect(screen.getByText('Consumed by shipping-service (group shipping), +14 ms')).toBeInTheDocument();
  });

  test('the group is left out when the consumer has none, and a negative gap is shown as it is', () => {
    const early = { ...consume, startEpochNanos: produce.startEpochNanos + 2 * MS, event: { ...consume.event, consumerGroup: null } } as typeof consume;
    render(<MessageDetails runId="run-1" step={produce} kind="request" steps={[produce, early]} />);
    expect(screen.getByText('Consumed by shipping-service, -2 ms')).toBeInTheDocument();
  });

  test('every consume caused by the produce is listed', () => {
    const second = { ...consume, stepId: 'consume-2', to: 'audit-service', event: { ...consume.event, consumerGroup: 'audit' } } as typeof consume;
    render(<MessageDetails runId="run-1" step={produce} kind="request" steps={[produce, consume, second]} />);
    expect(screen.getByText('Consumed by shipping-service (group shipping), +14 ms')).toBeInTheDocument();
    expect(screen.getByText('Consumed by audit-service (group audit), +14 ms')).toBeInTheDocument();
  });

  test('a produce nobody consumed says so', () => {
    render(<MessageDetails runId="run-1" step={produce} kind="request" steps={[produce]} />);
    expect(screen.getByText('Not consumed in this run')).toBeInTheDocument();
  });

  test('a consume names its producer and its group', () => {
    render(<MessageDetails runId="run-1" step={consume} kind="request" steps={[produce, consume]} />);
    expect(screen.getByRole('heading', { name: 'consume order-events' })).toBeInTheDocument();
    expect(screen.getByText('Produced by orders-service')).toBeInTheDocument();
    expect(fact('Consumer group')).toHaveTextContent('shipping');
    expect(screen.queryByText(/Consumed by|Not consumed/)).not.toBeInTheDocument();
  });

  test('a consume with no cause has no producer line', () => {
    const orphan = { ...consume, causedBy: null } as typeof consume;
    render(<MessageDetails runId="run-1" step={orphan} kind="request" steps={[orphan]} />);
    expect(screen.queryByText(/Produced by/)).not.toBeInTheDocument();
    expect(fact('Consumer group')).toHaveTextContent('shipping');
  });

  test('a consume shows the listener method that handled it', () => {
    const handler = methodDetails({ parameterNames: ['event'], arguments: ['{"orderId":1}'], returnValue: null }).event;
    const handled = { ...consume, handler } as typeof consume;
    render(<MessageDetails runId="run-1" step={handled} kind="request" steps={[handled]} />);
    expect(screen.getByRole('region', { name: 'Handled by OrdersController.create' })).toBeInTheDocument();
  });

  test('a failed record shows its error', () => {
    const failed = kafkaStep('produce', 'produce', { errorType: 'org.apache.kafka.common.errors.TimeoutException' });
    render(<MessageDetails runId="run-1" step={failed} kind="request" steps={[failed]} />);
    expect(fact('Error')).toHaveTextContent('org.apache.kafka.common.errors.TimeoutException');
  });
});
