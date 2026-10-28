import { render, screen, within } from '@testing-library/react';
import { expect, test } from 'vitest';
import { createOrderFlow, unrecordedHostStep } from '../board/testData';
import { MessageDetails } from './MessageDetails';

const [request, insert] = createOrderFlow.steps;

test('an HTTP step shows method, route, status and timing', () => {
  render(<MessageDetails step={request} kind="request" />);
  expect(screen.getByRole('heading', { name: 'POST /orders' })).toBeInTheDocument();
  expect(screen.getByText('201')).toBeInTheDocument();
  expect(screen.getByText('42 ms')).toBeInTheDocument();
  expect(screen.getByText('e2e-client')).toBeInTheDocument();
  expect(screen.getByText('orders-service')).toBeInTheDocument();
});

test('headers are listed as a table, repeated values joined', () => {
  render(<MessageDetails step={request} kind="request" />);
  const table = screen.getByRole('table', { name: 'Request headers' });
  expect(within(table).getByText('content-type')).toBeInTheDocument();
  expect(within(table).getByText('a, b')).toBeInTheDocument();
  expect(screen.getByRole('table', { name: 'Response headers' })).toBeInTheDocument();
});

test('JSON bodies are pretty-printed', () => {
  render(<MessageDetails step={request} kind="request" />);
  const pre = screen.getByText(/"customerId": "c-1"/);
  expect(pre.textContent).toContain('{\n  "customerId": "c-1",');
  expect(screen.getByText(/"status": "CREATED"/)).toBeInTheDocument();
});

test('a body that is not JSON is shown as it was recorded', () => {
  const step = { ...request, event: { ...request.event, requestBody: '{"cut":', requestBodyTruncated: true } } as typeof request;
  render(<MessageDetails step={step} kind="request" />);
  expect(screen.getByText('{"cut":')).toBeInTheDocument();
  expect(screen.getByText(/recording cut it short/)).toBeInTheDocument();
});

test('a missing body says so', () => {
  const step = { ...request, event: { ...request.event, responseBody: null } } as typeof request;
  render(<MessageDetails step={step} kind="response" />);
  expect(screen.getByText('no response body')).toBeInTheDocument();
});

test('a database step shows the SQL statement, parameters, row count and timing', () => {
  render(<MessageDetails step={insert} kind="request" />);
  expect(screen.getByText('5 ms')).toBeInTheDocument();
  expect(screen.getByText(/insert into orders \(customer_id, status, total, created_at\) values/)).toBeInTheDocument();
  const params = screen.getByRole('table', { name: 'Parameters' });
  expect(within(params).getByText('CREATED')).toBeInTheDocument();
  expect(screen.getByText('1 row')).toBeInTheDocument();
  expect(screen.getByText('postgresql')).toBeInTheDocument();
});

test('null parameters are shown as NULL', () => {
  const step = { ...insert, event: { ...insert.event, parameters: [null] } } as typeof insert;
  render(<MessageDetails step={step} kind="request" />);
  expect(screen.getByText('NULL')).toBeInTheDocument();
});

test('the section of the clicked message is marked', () => {
  const { rerender } = render(<MessageDetails step={request} kind="response" />);
  expect(screen.getByRole('region', { name: 'Response' })).toHaveClass('is-selected');
  expect(screen.getByRole('region', { name: 'Request' })).not.toHaveClass('is-selected');
  rerender(<MessageDetails step={request} kind="request" />);
  expect(screen.getByRole('region', { name: 'Request' })).toHaveClass('is-selected');
});

test('a call that got no answer shows its URL, the error type and that it never reached the service', () => {
  render(<MessageDetails step={unrecordedHostStep} kind="response" />);
  expect(screen.getByRole('heading', { name: 'POST /tax/quote' })).toBeInTheDocument();
  expect(screen.getByText('http://tax-service:9000/tax/quote')).toBeInTheDocument();
  expect(screen.getByText('java.net.ConnectException')).toBeInTheDocument();
  expect(screen.getByText('No response: the call failed before reaching the service.')).toBeInTheDocument();
  expect(screen.queryByText('Body not recorded: the called service is not instrumented.')).not.toBeInTheDocument();
});

test('an answered call to an unrecorded host says the called service is not instrumented', () => {
  const answered = { ...unrecordedHostStep, status: 502, event: { ...unrecordedHostStep.event, status: 502, errorType: null } } as typeof unrecordedHostStep;
  render(<MessageDetails step={answered} kind="response" />);
  expect(screen.getByText('Body not recorded: the called service is not instrumented.')).toBeInTheDocument();
  expect(screen.queryByText(/No response/)).not.toBeInTheDocument();
});
