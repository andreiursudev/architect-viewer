import { useMemo } from 'react';
import type { ComponentType, Flow, FlowStep } from '../api/types';
import { HEADER_HEIGHT, MARGIN_X, layoutSequence, type SequenceMessage } from './layoutSequence';

export interface ParticipantInfo {
  displayName: string;
  type: ComponentType;
}

export interface MessageSelection {
  stepId: string;
  kind: SequenceMessage['kind'];
}

const HEAD_WIDTH = 176;
const ARROW_GAP = 7; // the arrow stops short of the lifeline so activation bars stay visible
const BAR_WIDTH = 10;
const LABEL_CHAR_WIDTH = 6.7;
const LOOP_WIDTH = 34;

function fit(label: string, room: number): string {
  const max = Math.max(6, Math.floor(room / LABEL_CHAR_WIDTH));
  return label.length > max ? `${label.slice(0, max - 1).trimEnd()}…` : label;
}

function tone(step: FlowStep | undefined, kind: SequenceMessage['kind']): string {
  if (kind !== 'response' || step?.status == null) return '';
  if (step.status >= 500) return ' is-server-error';
  if (step.status >= 400) return ' is-client-error';
  return '';
}

/** The run as a sequence diagram: a lifeline per component, solid arrows for calls, dashed arrows for what came back. */
export function SequenceDiagram({
  flow,
  components,
  selected,
  onSelect,
}: {
  flow: Flow;
  /** Display name and type per component id; a component the workspace does not list is drawn with its id. */
  components: Record<string, ParticipantInfo | undefined>;
  selected: MessageSelection | null;
  onSelect: (selection: MessageSelection) => void;
}) {
  const layout = useMemo(() => layoutSequence(flow), [flow]);
  const steps = useMemo(() => new Map(flow.steps.map((s) => [s.stepId, s])), [flow]);
  const { participants, messages, height } = layout;
  const width = participants.length === 0 ? 0 : participants[participants.length - 1].x + MARGIN_X;

  // each step's activation bar: the callee is busy from the request until the response
  const bars = useMemo(() => {
    const byStep = new Map<string, { x: number; from: number; to: number }>();
    for (const m of messages) {
      const bar = byStep.get(m.stepId);
      if (m.kind === 'request') byStep.set(m.stepId, { x: m.toX, from: m.y, to: m.y });
      else if (bar) bar.to = m.y;
    }
    return [...byStep.entries()];
  }, [messages]);

  return (
    <div className="seq-canvas" style={{ width }}>
      <div className="seq-heads" style={{ height: HEADER_HEIGHT }}>
        {participants.map((p) => {
          const info = components[p.id];
          return (
            <div
              key={p.id}
              className={`seq-head type-${info?.type ?? 'service'}`}
              style={{ left: p.x - HEAD_WIDTH / 2, width: HEAD_WIDTH }}
              title={p.id}
            >
              <span className="seq-head-name">{info?.displayName ?? p.id}</span>
              <span className="seq-head-type">{info?.type ?? 'unknown'}</span>
            </div>
          );
        })}
      </div>
      <svg
        className="seq-svg"
        role="group"
        aria-label="Sequence diagram"
        width={width}
        height={height}
        viewBox={`0 0 ${width} ${height}`}
        style={{ marginTop: -HEADER_HEIGHT }}
      >
        <defs>
          <marker id="seq-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9" markerHeight="9" orient="auto">
            <path d="M0 0.5 L10 5 L0 9.5 z" className="seq-head-solid" />
          </marker>
          <marker id="seq-arrow-open" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9" markerHeight="9" orient="auto">
            <path d="M1 1 L9 5 L1 9" className="seq-head-open" />
          </marker>
          <marker id="seq-arrow-selected" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9" markerHeight="9" orient="auto">
            <path d="M0 0.5 L10 5 L0 9.5 z" className="seq-head-selected" />
          </marker>
        </defs>

        {participants.map((p) => (
          <line key={p.id} className="seq-lifeline" x1={p.x} x2={p.x} y1={HEADER_HEIGHT - 12} y2={height - 12} />
        ))}
        {bars.map(([stepId, bar]) => (
          <rect
            key={stepId}
            className="seq-bar"
            x={bar.x - BAR_WIDTH / 2}
            y={bar.from - 8}
            width={BAR_WIDTH}
            height={Math.max(bar.to - bar.from, 0) + 16}
            rx={2}
          />
        ))}

        {messages.map((m, i) => {
          const isSelected = selected?.stepId === m.stepId && selected.kind === m.kind;
          const response = m.kind === 'response';
          const self = m.fromX === m.toX;
          const dir = Math.sign(m.toX - m.fromX);
          const x1 = m.fromX + (self ? BAR_WIDTH / 2 : dir * ARROW_GAP);
          const x2 = m.toX - (self ? -BAR_WIDTH / 2 : dir * ARROW_GAP);
          const label = fit(m.label, self ? 170 : Math.abs(m.toX - m.fromX) - 28);
          const classes = ['seq-message', response ? 'is-response' : 'is-request', isSelected ? 'is-selected' : ''];
          const marker = isSelected ? 'seq-arrow-selected' : response ? 'seq-arrow-open' : 'seq-arrow';
          const path = self
            ? `M${x1} ${m.y - 7} H${x1 + LOOP_WIDTH} V${m.y + 7} H${x1 + 2}`
            : `M${x1} ${m.y} H${x2}`;
          const mid = self ? x1 + LOOP_WIDTH + 8 : (m.fromX + m.toX) / 2;
          const step = steps.get(m.stepId);
          return (
            <g
              key={`${m.stepId}:${m.kind}:${i}`}
              className={classes.join(' ')}
              role="button"
              tabIndex={0}
              aria-label={`${m.kind} ${m.label}`}
              aria-pressed={isSelected}
              onClick={() => onSelect({ stepId: m.stepId, kind: m.kind })}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onSelect({ stepId: m.stepId, kind: m.kind });
                }
              }}
            >
              <title>{m.label}</title>
              <path className="seq-hit" d={path} />
              <path className="seq-arrow-line" d={path} markerEnd={`url(#${marker})`} />
              <text
                className={`seq-label${tone(step, m.kind)}`}
                x={mid}
                y={m.y - (self ? 0 : 7)}
                textAnchor={self ? 'start' : 'middle'}
                dominantBaseline={self ? 'middle' : undefined}
              >
                {label}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
