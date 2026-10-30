import type { Flow, FlowStep } from '../api/types';
import { responseText } from '../status';

export const COLUMN_GAP = 220;
export const ROW_GAP = 48;
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
  height: number;
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
 * steps) a response row. Siblings are ordered by start time. A method step of a collapsed service has no rows, but
 * its children still do.
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
  const visited = new Set<FlowStep>(); // a corrupt recording must not loop
  const visit = (step: FlowStep) => {
    if (visited.has(step)) return;
    visited.add(step);
    const hidden = step.kind === 'method' && !expanded.has(step.to);
    const ends = hidden ? null : { from: lifeline(step.from, step.fromUnit ?? null), to: lifeline(step.to, step.toUnit ?? null) };
    if (ends) rows.push({ step, kind: 'request', ...ends });
    for (const child of children.get(step.stepId) ?? []) visit(child);
    if (ends) rows.push({ step, kind: 'response', ...ends });
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

  const messages = rows.map(({ step, kind, from, to }, i): SequenceMessage => {
    const caller = x.get(from)!;
    const callee = x.get(to)!;
    return {
      stepId: step.stepId,
      kind,
      fromX: kind === 'request' ? caller : callee,
      toX: kind === 'request' ? callee : caller,
      y: headerHeight + FIRST_ROW_OFFSET + i * ROW_GAP,
      label: kind === 'request' ? step.label : responseText(step),
    };
  });
  const height = participants.length === 0 ? 0 : headerHeight + FIRST_ROW_OFFSET + Math.max(rows.length - 1, 0) * ROW_GAP + BOTTOM_PADDING;
  return { participants, groups, headerHeight, messages, height };
}
