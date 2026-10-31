import { defaultTextMapSetter, ROOT_CONTEXT } from '@opentelemetry/api';
import { afterEach, expect, it } from 'vitest';
import { StackBaggagePropagator } from './StackBaggagePropagator.js';

afterEach(() => {
  delete window.__STACK__;
});

it('propagator injects stack baggage and nothing without a run', () => {
  const propagator = new StackBaggagePropagator();

  const untagged: Record<string, string> = {};
  propagator.inject(ROOT_CONTEXT, untagged, defaultTextMapSetter);
  expect(untagged).toEqual({});

  window.__STACK__ = { scenario: 's-1', run: 'r-1' };
  const tagged: Record<string, string> = {};
  propagator.inject(ROOT_CONTEXT, tagged, defaultTextMapSetter);
  expect(tagged).toEqual({ baggage: 'stack.scenario=s-1,stack.run=r-1' });
  expect(propagator.fields()).toEqual(['baggage']);
});
