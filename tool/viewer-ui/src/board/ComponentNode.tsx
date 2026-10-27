import { Handle, Position, type NodeProps } from '@xyflow/react';
import { useUiStore } from '../store/uiStore';
import { TypeIcon, NoteIcon } from './icons';
import type { ComponentNode as ComponentNodeType } from './toFlowElements';

export function ComponentNode({ id, data }: NodeProps<ComponentNodeType>) {
  const selected = useUiStore((s) => s.selectedComponentId === id);
  const classes = ['component-node', `type-${data.type}`];
  if (data.discovered) classes.push('is-discovered');
  if (selected) classes.push('is-selected');

  return (
    <div className={classes.join(' ')} title={data.id}>
      <Handle type="target" position={Position.Left} />
      <span className="node-icon">
        <TypeIcon type={data.type} />
      </span>
      <span className="node-text">
        <span className="node-name">{data.displayName}</span>
        <span className="node-meta">
          <span className="node-type">{data.type}</span>
          {data.discovered && <span className="badge badge-discovered">discovered</span>}
        </span>
      </span>
      {data.hasNotes && (
        <span className="node-note" role="img" aria-label="Has notes" title="Has notes">
          <NoteIcon />
        </span>
      )}
      <Handle type="source" position={Position.Right} />
    </div>
  );
}
