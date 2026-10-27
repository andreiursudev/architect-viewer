import { expect, test } from 'vitest';
import { autoLayout } from './autoLayout';
import { comp, conn, layoutOf, viewWith } from './testData';
import { toFlowElements } from './toFlowElements';

test('autoLayout keeps existing positions', async () => {
  const view = viewWith({ components: [comp('a'), comp('b')], connections: [conn('a--b')] });
  const existing = layoutOf({ a: { x: 10, y: 20 } });
  const { nodes, edges, unplaced } = toFlowElements(view, existing);

  const positions = await autoLayout(nodes, edges, existing);

  expect(unplaced).toEqual(['b']);
  expect(Object.keys(positions)).toEqual(['b']);
  expect(Number.isFinite(positions.b.x)).toBe(true);
  expect(Number.isFinite(positions.b.y)).toBe(true);
});

test('autoLayout lays out left to right', async () => {
  const view = viewWith({
    components: [comp('a'), comp('b'), comp('c')],
    connections: [conn('a--b'), conn('b--c')],
  });
  const { nodes, edges } = toFlowElements(view, null);

  const p = await autoLayout(nodes, edges, null);

  expect(Object.keys(p).sort()).toEqual(['a', 'b', 'c']);
  expect(p.a.x).toBeLessThan(p.b.x);
  expect(p.b.x).toBeLessThan(p.c.x);
});

test('autoLayout with nothing unplaced returns no positions', async () => {
  const view = viewWith();
  const existing = layoutOf({ a: { x: 0, y: 0 }, b: { x: 1, y: 1 } });
  const { nodes, edges } = toFlowElements(view, existing);
  expect(await autoLayout(nodes, edges, existing)).toEqual({});
});
