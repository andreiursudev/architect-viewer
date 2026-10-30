import type { ParticipantInfo } from './SequenceDiagram';
import { HEADER_HEIGHT, type SequenceLayout } from './layoutSequence';

export const HEAD_WIDTH = 176;

const simpleName = (unit: string) => unit.slice(unit.lastIndexOf('.') + 1);

/** The sticky header row: a head per lifeline, and above the classes of each expanded service its group band. */
export function SequenceHeads({
  layout,
  components,
  counts,
  expanded,
  onToggle,
}: {
  layout: SequenceLayout;
  components: Record<string, ParticipantInfo | undefined>;
  /** Classes per service that has method steps: what its toggle offers. */
  counts: ReadonlyMap<string, number>;
  expanded: ReadonlySet<string>;
  onToggle: (component: string) => void;
}) {
  const { participants, groups, headerHeight } = layout;
  const top = 10 + headerHeight - HEADER_HEIGHT; // the heads sit below the group band, if there is one
  return (
    <div className="seq-heads" style={{ height: headerHeight }}>
      {groups.map((g) => {
        const name = components[g.component]?.displayName ?? g.component;
        return (
          <div key={g.component} className="seq-group" style={{ left: g.fromX - HEAD_WIDTH / 2, width: g.toX - g.fromX + HEAD_WIDTH }}>
            <span className="seq-group-name">{name}</span>
            <button type="button" className="seq-toggle" aria-label={`Collapse ${name}`} onClick={() => onToggle(g.component)}>
              ▾
            </button>
          </div>
        );
      })}
      {participants.map((p) => {
        const info = components[p.component];
        const classes = !expanded.has(p.component) ? counts.get(p.component) : undefined;
        return (
          <div
            key={p.id}
            className={`seq-head type-${info?.type ?? 'service'}${p.unit != null ? ' is-class' : ''}`}
            style={{ left: p.x - HEAD_WIDTH / 2, width: HEAD_WIDTH, top }}
            title={p.unit ?? p.id}
          >
            <span className="seq-head-name">{p.unit != null ? simpleName(p.unit) : (info?.displayName ?? p.id)}</span>
            <span className="seq-head-foot">
              <span className="seq-head-type">{p.unit != null ? 'class' : (info?.type ?? 'unknown')}</span>
              {classes != null && (
                <button type="button" className="seq-toggle" onClick={() => onToggle(p.component)}>
                  ▸ {classes} {classes === 1 ? 'class' : 'classes'}
                </button>
              )}
            </span>
          </div>
        );
      })}
    </div>
  );
}
