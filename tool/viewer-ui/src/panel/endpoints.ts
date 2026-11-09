import type { BoardConnection } from '../api/types';

/** One endpoint a connection carries: an HTTP route (`POST /orders/{id}`), or a ui route without a method. */
export interface Endpoint {
  method: string | null;
  path: string;
}

const METHOD = /^([A-Z]+)\s+(\S.*)$/;

/**
 * The endpoints of a connection, read from its label: an observed HTTP connection's label is its distinct routes joined
 * with `, `, a ui connection's its routes. Sorted by path, then method, so the routes under one prefix sit together. A
 * curated label that is not a list of routes comes back as one entry per comma-separated part.
 */
export function endpointsOf(connection: Pick<BoardConnection, 'label'>): Endpoint[] {
  const label = connection.label?.trim();
  if (!label) return [];
  const seen = new Set<string>();
  return label
    .split(/,\s+/)
    .map((part) => part.trim())
    .filter((part) => part !== '' && !seen.has(part) && seen.add(part))
    .map((part) => {
      const m = METHOD.exec(part);
      return m ? { method: m[1], path: m[2] } : { method: null, path: part };
    })
    .sort((a, b) => a.path.localeCompare(b.path) || (a.method ?? '').localeCompare(b.method ?? ''));
}
