import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { HopDetails } from './HopDetails';
import { createOrderFlow, unrecordedHostStep } from './testData';

const [request, insert] = createOrderFlow.steps;

test('an HTTP hop shows label, status, duration and both bodies', () => {
  render(<HopDetails steps={[request]} />);
  expect(screen.getByText('POST /orders')).toBeInTheDocument();
  expect(screen.getByText('201')).toBeInTheDocument();
  expect(screen.getByText('42 ms')).toBeInTheDocument();
  expect(screen.getByText(/"customerId":"c-1"/)).toBeInTheDocument();
  expect(screen.getByText(/"status":"CREATED"/)).toBeInTheDocument();
});

test('a database hop shows the SQL, its parameters and the row count', () => {
  render(<HopDetails steps={[insert]} />);
  expect(screen.getByText('5 ms')).toBeInTheDocument();
  expect(screen.getByText(/values \(\?, \?, \?, \?\)/)).toBeInTheDocument();
  expect(screen.getByText(/c-1, CREATED, 19.98/)).toBeInTheDocument();
  expect(screen.getByText('1 row')).toBeInTheDocument();
});

test('long bodies are cut after 200 characters', () => {
  const long = 'x'.repeat(500);
  const step = { ...request, event: { ...request.event, requestBody: long } } as typeof request;
  render(<HopDetails steps={[step]} />);
  expect(screen.getByText(`${'x'.repeat(200)}…`)).toBeInTheDocument();
});

test('a missing body is said so rather than left blank', () => {
  const step = { ...request, event: { ...request.event, requestBody: null } } as typeof request;
  render(<HopDetails steps={[step]} />);
  expect(screen.getAllByText('no body')).toHaveLength(1);
});

test('a call to an unrecorded host shows its URL, the error type and that no body was recorded', () => {
  render(<HopDetails steps={[unrecordedHostStep]} />);
  expect(screen.getByText('http://tax-service:9000/tax/quote')).toBeInTheDocument();
  expect(screen.getByText('java.net.ConnectException')).toBeInTheDocument();
  expect(screen.getByText('Body not recorded: the called service is not instrumented.')).toBeInTheDocument();
});

test('a call to an unrecorded host that answered shows its status', () => {
  const answered = { ...unrecordedHostStep, status: 502, event: { ...unrecordedHostStep.event, status: 502, errorType: null } } as typeof request;
  render(<HopDetails steps={[answered]} />);
  expect(screen.getAllByText('502').length).toBeGreaterThan(0);
  expect(screen.queryByText('java.net.ConnectException')).not.toBeInTheDocument();
});
