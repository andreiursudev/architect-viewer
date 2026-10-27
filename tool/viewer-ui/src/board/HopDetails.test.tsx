import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { HopDetails } from './HopDetails';
import { createOrderFlow } from './testData';

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
