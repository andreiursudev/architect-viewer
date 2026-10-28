import { expect, test } from 'vitest';
import type { FlowStep } from './api/types';
import { createOrderFlow, unrecordedHostStep } from './board/testData';
import { isFailedStep, responseText, statusTone } from './status';

const [request, insert] = createOrderFlow.steps;
const withStatus = (status: number) => ({ ...request, status }) as FlowStep;

test('clientErrorIsFailed', () => {
  expect(isFailedStep(unrecordedHostStep)).toBe(true);
  expect(responseText(unrecordedHostStep)).toBe('ConnectException');
});

test('409IsFailed', () => {
  expect(isFailedStep(withStatus(409))).toBe(true);
  expect(responseText(withStatus(409))).toBe('409');
});

test('201IsNotFailed', () => {
  expect(isFailedStep(withStatus(201))).toBe(false);
  expect(isFailedStep(insert)).toBe(false);
});

test('dbStepUsesRowCount', () => {
  const zero = { ...insert, event: { ...insert.event, rowCount: 0 } } as FlowStep;
  expect(responseText(zero)).toBe('0 rows');
  expect(responseText(insert)).toBe('1 row');
  expect(responseText({ ...insert, event: { ...insert.event, rowCount: null } } as FlowStep)).toBe('ok');
});

test('a client step that got an answer is failed only by its status', () => {
  const answered = { ...unrecordedHostStep, status: 200, event: { ...unrecordedHostStep.event, status: 200, errorType: null } } as FlowStep;
  expect(isFailedStep(answered)).toBe(false);
  expect(responseText(answered)).toBe('200');
});

test('statusTone picks the tone of the status class', () => {
  expect(statusTone(204)).toBe('is-ok');
  expect(statusTone(404)).toBe('is-client-error');
  expect(statusTone(503)).toBe('is-server-error');
});
