import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';
import { Link } from 'react-router';
import { useSetConnectionState } from '../api/queries';
import { isFailedStep, responseText } from '../status';
import { useUiStore } from '../store/uiStore';
import { useHighlight, type Hop } from './highlight';
import { edgeColor, PATH_COLOR, type ConnectionEdgeType } from './toFlowElements';

/** What a step number tells on hover: each call of the hop and what it answered. */
function hopTitle(hop: Hop): string {
  const calls = hop.steps.map((s) => {
    const response = responseText(s);
    return response ? `${s.label} → ${response}` : s.label;
  });
  return [`Step ${hop.order} of the run`, ...calls].join('\n');
}

/** Where a step number leads: the sequence view of the run, with the step's first call selected. */
export function sequenceLink(runId: string, hop: Hop): string {
  return `/sequence/${encodeURIComponent(runId)}?step=${encodeURIComponent(hop.steps[0].stepId)}`;
}

export function ConnectionEdge(props: EdgeProps<ConnectionEdgeType>) {
  const { id, data, style, markerEnd, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition } = props;
  const hovered = useUiStore((s) => s.hoveredConnectionId === id);
  const selected = useUiStore((s) => s.selectedConnectionId === id);
  const hover = useUiStore((s) => s.hoverConnection);
  const select = useUiStore((s) => s.selectConnection);
  const setState = useSetConnectionState();
  const highlight = useHighlight();

  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
  });
  if (!data) return null;

  const discovered = data.state === 'discovered';
  // On the path of a run the edge carries only the numbers of its steps; the sequence view tells what they did.
  const hops = highlight?.hopsByConnection.get(id);
  const text = data.label ?? (data.kind === 'db' ? 'SQL' : '');
  const classes = ['edge-chip'];
  if (discovered) classes.push('is-discovered');
  if (data.unverified) classes.push('is-unverified');
  if (selected) classes.push('is-selected');
  if (highlight) classes.push(hops ? 'on-path' : 'is-dimmed');
  if (hops && hovered) classes.push('is-hovered');
  if (highlight?.failedEdgeIds.has(id)) classes.push('is-failed');

  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        markerEnd={markerEnd}
        data-connection={data.id}
        interactionWidth={24}
        className={highlight ? (hops ? 'path-edge' : 'dimmed-edge') : undefined}
        style={{
          ...style,
          stroke: hops ? PATH_COLOR : edgeColor(data),
          strokeWidth: hops ? (hovered ? 5 : 4) : hovered || selected ? 3 : 2,
        }}
      />
      <EdgeLabelRenderer>
        <div
          className={`${classes.join(' ')} nodrag nopan`}
          data-connection={data.id}
          style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          onMouseEnter={() => hover(id)}
          onMouseLeave={() => hover(null)}
        >
          <div className="edge-row">
            {hops && highlight ? (
              hops.map((hop) => (
                <Link
                  key={hop.order}
                  to={sequenceLink(highlight.runId, hop)}
                  className={`step-badge${hop.steps.some(isFailedStep) ? ' is-failed' : ''}`}
                  title={hopTitle(hop)}
                  aria-label={`Step ${hop.order}, open in the sequence view`}
                >
                  {hop.order}
                  {hop.steps.length > 1 && <small>×{hop.steps.length}</small>}
                </Link>
              ))
            ) : (
              <button type="button" className="edge-label" onClick={() => select(id)} title={data.id}>
                <span className="edge-kind">{data.kind}</span>
                {text && <span className="edge-text" title={text}>{text}</span>}
              </button>
            )}
            {data.unverified && <span className="badge badge-unverified">unverified</span>}
            {discovered && <span className="badge badge-discovered">discovered</span>}
          </div>
          {discovered && (
            <div className="edge-row">
              <button
                type="button"
                className="btn btn-small btn-primary"
                disabled={setState.isPending}
                onClick={() => setState.mutate({ connection: data, state: 'accepted' })}
              >
                Accept
              </button>
              <button
                type="button"
                className="btn btn-small"
                disabled={setState.isPending}
                onClick={() => setState.mutate({ connection: data, state: 'hidden' })}
              >
                Hide
              </button>
            </div>
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}
