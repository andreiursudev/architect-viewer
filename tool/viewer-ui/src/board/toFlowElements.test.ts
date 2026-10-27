import { describe, expect, test } from 'vitest';
import { comp, conn, layoutOf, viewWith } from './testData';
import { toFlowElements } from './toFlowElements';

describe('toFlowElements', () => {
  test('hidden connections are not drawn', () => {
    const { edges } = toFlowElements(viewWith({ connections: [conn('a--b', 'hidden')] }), null);
    expect(edges).toHaveLength(0);
  });

  test('components without layout are reported as unplaced', () => {
    const { nodes, unplaced } = toFlowElements(viewWith(), layoutOf({ a: { x: 5, y: 6 } }));
    expect(unplaced).toEqual(['b']);
    expect(nodes.find((n) => n.id === 'a')?.position).toEqual({ x: 5, y: 6 });
  });

  test('every component is unplaced without a layout', () => {
    expect(toFlowElements(viewWith(), null).unplaced).toEqual(['a', 'b']);
  });

  test('unverified edge is dashed', () => {
    const { edges } = toFlowElements(viewWith({ connections: [conn('a--b', 'accepted', { unverified: true })] }), null);
    expect(edges[0].style?.strokeDasharray).toBeDefined();
  });

  test('verified edge is solid', () => {
    const { edges } = toFlowElements(viewWith({ connections: [conn('a--b')] }), null);
    expect(edges[0].style?.strokeDasharray).toBeUndefined();
  });

  test('node data is the board component and edge data the board connection, ids as in the API', () => {
    const c = comp('a', { discovered: true });
    const e = conn('a--b', 'discovered');
    const { nodes, edges } = toFlowElements(viewWith({ components: [c, comp('b')], connections: [e] }), null);
    expect(nodes[0].id).toBe('a');
    expect(nodes[0].data).toEqual(c);
    expect(edges[0]).toMatchObject({ id: 'a--b', source: 'a', target: 'b', data: e });
  });

  test('edges pointing at components that are not in the list are omitted', () => {
    const { edges } = toFlowElements(
      viewWith({ connections: [conn('a--ghost', 'manual'), conn('ghost--b', 'accepted'), conn('a--b')] }),
      null,
    );
    expect(edges.map((e) => e.id)).toEqual(['a--b']);
  });
});
