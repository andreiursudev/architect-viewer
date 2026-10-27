import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';
import { useSetConnectionState } from '../api/queries';
import { useUiStore } from '../store/uiStore';
import { HopDetails } from './HopDetails';
import { useHighlight } from './highlight';
import { edgeColor, PATH_COLOR, type ConnectionEdgeType } from './toFlowElements';

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
  const text = data.label ?? (data.kind === 'db' ? 'SQL' : '');
  const classes = ['edge-chip'];
  if (discovered) classes.push('is-discovered');
  if (data.unverified) classes.push('is-unverified');
  if (selected) classes.push('is-selected');
  const steps = highlight?.stepsByEdge.get(id);
  const order = highlight?.orderByEdge.get(id);
  if (highlight) classes.push(steps ? 'on-path' : 'is-dimmed');
  if (steps && hovered) classes.push('is-hovered');

  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        markerEnd={markerEnd}
        interactionWidth={24}
        className={highlight ? (steps ? 'path-edge' : 'dimmed-edge') : undefined}
        style={{
          ...style,
          stroke: steps ? PATH_COLOR : edgeColor(data),
          strokeWidth: steps ? (hovered ? 5 : 4) : hovered || selected ? 3 : 2,
        }}
      />
      <EdgeLabelRenderer>
        <div
          className={`${classes.join(' ')} nodrag nopan`}
          style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          onMouseEnter={() => hover(id)}
          onMouseLeave={() => hover(null)}
        >
          <div className="edge-row">
            {order !== undefined && steps && (
              <span className="step-badge" title={`Step ${order} of the run`}>
                {order}
                {steps.length > 1 && <small>×{steps.length}</small>}
              </span>
            )}
            <button type="button" className="edge-label" onClick={() => select(id)} title={data.id}>
              <span className="edge-kind">{data.kind}</span>
              {text && <span>{text}</span>}
            </button>
            {data.unverified && <span className="badge badge-unverified">unverified</span>}
            {discovered && <span className="badge badge-discovered">discovered</span>}
          </div>
          {steps && hovered && <HopDetails steps={steps} />}
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
