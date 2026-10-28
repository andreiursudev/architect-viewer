import type { Flow, FlowStep } from '../api/types';
import { responseText } from '../status';

export const COLUMN_GAP = 220;
export const ROW_GAP = 48;
/** Room above the first message for the participant headers; the diagram draws them there. */
export const HEADER_HEIGHT = 64;
/** Room left of the first lifeline and below the last row. */
export const MARGIN_X = 110;
/** Gap between the headers and the first message, so its label is not under a sticky header. */
const FIRST_ROW_OFFSET = 36;
const BOTTOM_PADDING = 40;

export interface SequenceParticipant {
  id: string;
  x: number;
}

export interface SequenceMessage {
  stepId: string;
  kind: 'request' | 'response';
  fromX: number;
  toX: number;
  y: number;
  label: string;
}

export interface SequenceLayout {
  participants: SequenceParticipant[];
  messages: SequenceMessage[];
  height: number;
}

/**
 * Turns a flow into sequence-diagram geometry. Participants are columns in the order they first appear; every step is
 * a request row, then (after the rows of its child steps) a response row. Siblings are ordered by start time.
 */
export function layoutSequence(flow: Flow): SequenceLayout {
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

  const order: string[] = [];
  const see = (id: string) => {
    if (!order.includes(id)) order.push(id);
  };
  const rows: { step: FlowStep; kind: 'request' | 'response' }[] = [];
  const visit = (step: FlowStep) => {
    see(step.from);
    see(step.to);
    rows.push({ step, kind: 'request' });
    for (const child of children.get(step.stepId) ?? []) visit(child);
    rows.push({ step, kind: 'response' });
  };
  for (const root of children.get(null) ?? []) visit(root);

  const participants = order.map((id, i) => ({ id, x: MARGIN_X + i * COLUMN_GAP }));
  const x = new Map(participants.map((p) => [p.id, p.x]));
  const messages = rows.map(({ step, kind }, i): SequenceMessage => {
    const caller = x.get(step.from)!;
    const callee = x.get(step.to)!;
    return {
      stepId: step.stepId,
      kind,
      fromX: kind === 'request' ? caller : callee,
      toX: kind === 'request' ? callee : caller,
      y: HEADER_HEIGHT + FIRST_ROW_OFFSET + i * ROW_GAP,
      label: kind === 'request' ? step.label : responseText(step),
    };
  });
  const height = participants.length === 0 ? 0 : HEADER_HEIGHT + FIRST_ROW_OFFSET + Math.max(rows.length - 1, 0) * ROW_GAP + BOTTOM_PADDING;
  return { participants, messages, height };
}
