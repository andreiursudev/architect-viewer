import { expect, test } from 'vitest';
import { endpointsOf } from './endpoints';

test('an observed http label is split into method and path, sorted by path', () => {
  expect(endpointsOf({ label: 'POST /orders, GET /orders/{id}, GET /orders' })).toEqual([
    { method: 'GET', path: '/orders' },
    { method: 'POST', path: '/orders' },
    { method: 'GET', path: '/orders/{id}' },
  ]);
});

test('ui routes and curated labels have no method', () => {
  expect(endpointsOf({ label: '/stock, /orders/{id}' })).toEqual([
    { method: null, path: '/orders/{id}' },
    { method: null, path: '/stock' },
  ]);
  expect(endpointsOf({ label: 'reserves stock' })).toEqual([{ method: null, path: 'reserves stock' }]);
});

test('no label, no endpoints; repeats are listed once', () => {
  expect(endpointsOf({ label: null })).toEqual([]);
  expect(endpointsOf({ label: '  ' })).toEqual([]);
  expect(endpointsOf({ label: 'GET /a, GET /a' })).toEqual([{ method: 'GET', path: '/a' }]);
});
