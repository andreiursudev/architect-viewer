import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';
import { useSetConnectionState } from '../api/queries';
import { useUiStore } from '../store/uiStore';
import { edgeColor, type ConnectionEdgeType } from './toFlowElements';

export function ConnectionEdge(props: EdgeProps<ConnectionEdgeType>) {
  const { id, data, style, markerEnd, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition } = props;
  const hovered = useUiStore((s) => s.hoveredConnectionId === id);
  const selected = useUiStore((s) => s.selectedConnectionId === id);
  const hover = useUiStore((s) => s.hoverConnection);
  const select = useUiStore((s) => s.selectConnection);
  const setState = useSetConnectionState();

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

  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        markerEnd={markerEnd}
        interactionWidth={24}
        style={{
          ...style,
          stroke: edgeColor(data),
          strokeWidth: hovered || selected ? 3 : 2,
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
            <button type="button" className="edge-label" onClick={() => select(id)} title={data.id}>
              <span className="edge-kind">{data.kind}</span>
              {text && <span>{text}</span>}
            </button>
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
