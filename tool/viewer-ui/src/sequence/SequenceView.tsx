import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router';
import { useFlow, useWorkspace } from '../api/queries';
import { MessageDetails } from './MessageDetails';
import { SequenceDiagram, type MessageSelection, type ParticipantInfo } from './SequenceDiagram';

/** The page of one recorded run: its calls as a sequence diagram, and the payload of the message clicked. */
export function SequenceView() {
  const { runId = '' } = useParams();
  const flow = useFlow(runId);
  const workspace = useWorkspace();
  const [selected, setSelected] = useState<MessageSelection | null>(null);

  const components = useMemo(() => {
    const byId: Record<string, ParticipantInfo> = {};
    for (const c of workspace.data?.components ?? []) byId[c.id] = { displayName: c.displayName, type: c.type };
    return byId;
  }, [workspace.data]);

  const step = flow.data?.steps.find((s) => s.stepId === selected?.stepId);

  return (
    <main className="sequence-page">
      <div className="sequence-main">
        <div className="sequence-bar">
          <Link to="/" className="btn">
            ← Board
          </Link>
          <h2>Sequence</h2>
          <span className="muted">{flow.data?.scenarioId ?? ''}</span>
          <code className="sequence-run">{runId}</code>
          {flow.data && flow.data.steps.length > 0 && (
            <span className="sequence-count muted">
              {flow.data.steps.length} {flow.data.steps.length === 1 ? 'call' : 'calls'}
            </span>
          )}
        </div>
        <div className="sequence-scroll">
          {flow.isLoading && <p className="muted sequence-message">Loading the run…</p>}
          {flow.isError && <p className="error sequence-message">Could not load the run: {flow.error.message}</p>}
          {flow.data && flow.data.steps.length === 0 && <p className="muted sequence-message">This run recorded no calls.</p>}
          {flow.data && flow.data.steps.length > 0 && (
            <SequenceDiagram flow={flow.data} components={components} selected={selected} onSelect={setSelected} />
          )}
        </div>
      </div>
      <aside className="side-panel sequence-details" aria-label="Message details">
        {step && selected ? (
          <MessageDetails step={step} kind={selected.kind} />
        ) : (
          <p className="muted sequence-hint">Select a message to see its payload.</p>
        )}
      </aside>
    </main>
  );
}
