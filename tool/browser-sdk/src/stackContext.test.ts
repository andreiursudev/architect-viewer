import { afterEach, expect, it } from 'vitest';
import { currentRun } from './stackContext.js';

afterEach(() => {
  delete window.__STACK__;
});

it('currentRun reads window.__STACK__ and is null without it', () => {
  expect(currentRun()).toBeNull();

  window.__STACK__ = { scenario: 'place-order', run: 'run-1' };
  expect(currentRun()).toEqual({ scenario: 'place-order', run: 'run-1' });

  window.__STACK__ = { scenario: 'place-order' } as never;
  expect(currentRun()).toBeNull();
});
