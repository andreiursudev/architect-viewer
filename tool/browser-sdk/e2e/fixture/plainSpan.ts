/** A finished span as the fixture page hands it to the test. */
export interface PlainSpan {
  name: string;
  spanId: string;
  kind: string;
  /** The span's start, in milliseconds since the epoch. */
  startMillis: number;
  attributes: Record<string, unknown>;
  resource: Record<string, unknown>;
}

declare global {
  interface Window {
    /** Set by the fixture page: flushes, then returns every span the in-memory exporter received. */
    __SPANS__: () => Promise<PlainSpan[]>;
  }
}
