import type { Flow, FlowStep } from '../api/types';
import { responseText } from '../status';

export const COLUMN_GAP = 220;
export const ROW_GAP = 48;
/** A screenshot thumbnail beside its ui arrow, in the margin left of the first lifeline. */
export const THUMB_WIDTH = 96;
export const THUMB_HEIGHT = 54;
/** The gap between two rows when either carries a thumbnail, so thumbnails never overlap. */
export const SHOT_ROW_GAP = 76;
/** Room above the first message for the participant headers; the diagram draws them there. */
export const HEADER_HEIGHT = 64;
/** The extra header height while a service is expanded: the band with its name and collapse button. */
export const GROUP_BAND = 28;
/** Room left of the first lifeline and below the last row. */
export const MARGIN_X = 110;
/** Gap between the headers and the first message, so its label is not under a sticky header. */
const FIRST_ROW_OFFSET = 36;
const BOTTOM_PADDING = 40;

export interface SequenceParticipant {
  /** The component id, or `component::unit` for the class lifeline of an expanded service. */
  id: string;
  x: number;
  component: string;
  /** Fully qualified class name; only set on the class lifelines of an expanded service. */
  unit: string | null;
}

export interface SequenceMessage {
  stepId: string;
  kind: 'request' | 'response';
  fromX: number;
  toX: number;
  y: number;
  label: string;
  /** On the request row of a ui step with a screenshot: its id; null on every other row. */
  screenshot: string | null;
  /** The request row of a kafka step: a record sent without waiting for an answer, drawn with an open arrowhead. */
  async: boolean;
  /** On a consume's request row: how long after its produce ended it started (`+14 ms`); null on every other row. */
  note: string | null;
}

/** The activation bar of a step on the lifeline at `x`, from the y of its first row to the y of its last. */
export interface SequenceBar {
  stepId: string;
  x: number;
  from: number;
  to: number;
}

/** The band above the class lifelines of an expanded service. */
export interface SequenceGroup {
  component: string;
  fromX: number;
  toX: number;
}

export interface SequenceLayout {
  participants: SequenceParticipant[];
  groups: SequenceGroup[];
  /** `HEADER_HEIGHT`, plus `GROUP_BAND` while some service is expanded. */
  headerHeight: number;
  messages: SequenceMessage[];
  bars: SequenceBar[];
  height: number;
}

const NANOS_PER_MILLI = 1_000_000;

/**
 * The gap between a produce and the consume it caused, `+14 ms`: from the end of the produce to the start of the
 * consume, in whole ms. The two ran on different hosts, so the clocks may differ and the gap may be negative (`-2 ms`).
 */
export function consumeGap(produce: FlowStep, consume: FlowStep): string {
  const ms = Math.round((consume.startEpochNanos - (produce.startEpochNanos + produce.durationNanos)) / NANOS_PER_MILLI);
  return `${ms < 0 ? '-' : '+'}${Math.abs(ms)} ms`;
}

/** For each service with method steps, how many distinct classes its steps involve: what expanding it would show. */
export function classCounts(flow: Flow): Map<string, number> {
  const units = new Map<string, Set<string>>();
  for (const step of flow.steps) {
    if (step.kind === 'method' && !units.has(step.to)) units.set(step.to, new Set());
  }
  for (const step of flow.steps) {
    if (step.fromUnit != null) units.get(step.from)?.add(step.fromUnit);
    if (step.toUnit != null) units.get(step.to)?.add(step.toUnit);
  }
  return new Map([...units].map(([component, set]) => [component, set.size]));
}

/**
 * Turns a flow into sequence-diagram geometry. Participants are columns in the order they first appear (an expanded
 * service has one column per class, side by side); every step is a request row, then (after the rows of its child
 * steps) a response row, except a `ui` or `kafka` step, which is a request arrow only. Siblings are ordered by start
 * time. A method step of a collapsed service has no rows, but its children still do. A consume is a root, so it comes
 * after the whole subtree of the request that produced it.
 *
 * A step's activation bar runs from its request row to its response row, a one-row bar for a `ui` step; a produce has
 * none (the topic is not busy), and a consume's runs to the last row of its subtree.
 */
export function layoutSequence(flow: Flow, expanded: ReadonlySet<string> = new Set()): SequenceLayout {
  const ids = new Set(flow.steps.map((s) => s.stepId));
  const children = new Map<string | null, FlowStep[]>();
  for (const step of flow.steps) {
    // a step whose parent is not part of the flow is a root
    const parent = step.parentStepId != null && ids.has(step.parentStepId) ? step.parentStepId : null;
    const siblings = children.get(parent);
    if (siblings) siblings.push(step);
    else children.set(parent, [step]);
  }
  for (const siblings of children.values()) siblings.sort((a, b) => a.startEpochNanos - b.startEpochNanos);

  const found: { id: string; component: string; unit: string | null }[] = [];
  /** The lifeline an end of a step sits on; it is noted the first time it is used. */
  const lifeline = (component: string, unit: string | null): string => {
    const onClass = unit != null && expanded.has(component);
    const id = onClass ? `${component}::${unit}` : component;
    if (!found.some((f) => f.id === id)) found.push({ id, component, unit: onClass ? unit : null });
    return id;
  };
  const rows: { step: FlowStep; kind: 'request' | 'response'; from: string; to: string }[] = [];
  /** Each step's bar by row index: the lifeline it is on, and its first and last row. */
  const spans: { step: FlowStep; to: string; first: number; last: number }[] = [];
  const visited = new Set<FlowStep>(); // a corrupt recording must not loop
  const isProduce = (step: FlowStep) => step.kind === 'kafka' && step.event.type === 'kafka.produce';
  const visit = (step: FlowStep) => {
    if (visited.has(step)) return;
    visited.add(step);
    const hidden = step.kind === 'method' && !expanded.has(step.to);
    const ends = hidden ? null : { from: lifeline(step.from, step.fromUnit ?? null), to: lifeline(step.to, step.toUnit ?? null) };
    const first = rows.length;
    if (ends) rows.push({ step, kind: 'request', ...ends });
    for (const child of children.get(step.stepId) ?? []) visit(child);
    // a browser step and a record sent to a topic have no answer
    if (ends && step.kind !== 'ui' && step.kind !== 'kafka') rows.push({ step, kind: 'response', ...ends });
    if (ends && !isProduce(step)) spans.push({ step, to: ends.to, first, last: step.kind === 'ui' ? first : rows.length - 1 });
  };
  for (const root of children.get(null) ?? []) visit(root);

  // services in order of first appearance, the lifelines of each one side by side
  const components = [...new Set(found.map((f) => f.component))];
  const ordered = components.flatMap((c) => found.filter((f) => f.component === c));
  const participants = ordered.map((f, i): SequenceParticipant => ({ ...f, x: MARGIN_X + i * COLUMN_GAP }));
  const x = new Map(participants.map((p) => [p.id, p.x]));
  const groups = components.filter((c) => expanded.has(c)).map((component): SequenceGroup => {
    const xs = participants.filter((p) => p.component === component).map((p) => p.x);
    return { component, fromX: Math.min(...xs), toX: Math.max(...xs) };
  });
  const headerHeight = HEADER_HEIGHT + (groups.length > 0 ? GROUP_BAND : 0);

  const byId = new Map(flow.steps.map((s) => [s.stepId, s]));
  const noteOf = (row: (typeof rows)[number]): string | null => {
    if (row.kind !== 'request' || row.step.event.type !== 'kafka.consume' || row.step.causedBy == null) return null;
    const produce = byId.get(row.step.causedBy);
    return produce ? consumeGap(produce, row.step) : null;
  };
  const shotOf = (row: (typeof rows)[number] | undefined) =>
    row && row.kind === 'request' && row.step.kind === 'ui' ? (row.step.screenshot ?? null) : null;
  // a row is SHOT_ROW_GAP below the one before when either of the two has a thumbnail
  let y = headerHeight + FIRST_ROW_OFFSET;
  const messages = rows.map((row, i): SequenceMessage => {
    const { step, kind, from, to } = row;
    if (i > 0) y += shotOf(row) != null || shotOf(rows[i - 1]) != null ? SHOT_ROW_GAP : ROW_GAP;
    const caller = x.get(from)!;
    const callee = x.get(to)!;
    return {
      stepId: step.stepId,
      kind,
      fromX: kind === 'request' ? caller : callee,
      toX: kind === 'request' ? callee : caller,
      y,
      label: kind === 'request' ? step.label : responseText(step),
      screenshot: shotOf(row),
      async: kind === 'request' && step.kind === 'kafka',
      note: noteOf(row),
    };
  });
  const bars = spans
    .sort((a, b) => a.first - b.first) // a step is recorded after its children; the bars are listed in row order
    .map((span): SequenceBar => ({
      stepId: span.step.stepId,
      x: x.get(span.to)!,
      from: messages[span.first].y,
      to: messages[span.last].y,
    }));
  const height = participants.length === 0 ? 0 : y + BOTTOM_PADDING;
  return { participants, groups, headerHeight, messages, bars, height };
}
