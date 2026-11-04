import { useCallback, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { useFlow, useWorkspace } from '../api/queries';
import { MessageDetails } from './MessageDetails';
import { ScreenshotCarousel, type ScreenshotSlide } from './Screenshot';
import { classCounts } from './layoutSequence';
import { SequenceDiagram, type MessageSelection, type ParticipantInfo } from './SequenceDiagram';
import { useDragToScroll } from './useDragToScroll';

/** The page of one recorded run: its calls as a sequence diagram, and the payload of the message clicked. */
export function SequenceView() {
  const { runId = '' } = useParams();
  const flow = useFlow(runId);
  const workspace = useWorkspace();
  const [selected, setSelected] = useState<MessageSelection | null>(null);
  // the selected step's screenshot at its own size, a carousel over the run's ui steps
  const [viewing, setViewing] = useState(false);
  // services drawn as one lifeline per class; every run opens fully collapsed
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  // a large diagram is dragged around from its empty spots, not only by its scrollbars
  const scroll = useRef<HTMLDivElement>(null);
  useDragToScroll(scroll);

  const components = useMemo(() => {
    const byId: Record<string, ParticipantInfo> = {};
    for (const c of workspace.data?.components ?? []) byId[c.id] = { displayName: c.displayName, type: c.type };
    return byId;
  }, [workspace.data]);

  const expandable = useMemo(() => [...(flow.data ? classCounts(flow.data).keys() : [])], [flow.data]);
  const steps = flow.data?.steps ?? [];
  // a method step only exists while its service is expanded, so collapsing the service drops it from the details panel
  const dropSelectionOfCollapsed = (stillExpanded: ReadonlySet<string>) => {
    const open = steps.find((s) => s.stepId === selected?.stepId);
    if (open?.kind === 'method' && !stillExpanded.has(open.from)) setSelected(null);
  };
  const toggle = (component: string) => {
    const next = new Set(expanded);
    if (!next.delete(component)) next.add(component);
    setExpanded(next);
    dropSelectionOfCollapsed(next);
  };
  const collapseAll = () => {
    setExpanded(new Set());
    dropSelectionOfCollapsed(new Set());
  };

  const methodCalls = steps.filter((s) => s.kind === 'method').length;
  const calls = steps.length - methodCalls;
  const step = steps.find((s) => s.stepId === selected?.stepId);
  const slides = useMemo(
    () =>
      (flow.data?.steps ?? []).flatMap((s): ScreenshotSlide[] =>
        s.kind === 'ui' && s.screenshot != null ? [{ stepId: s.stepId, label: s.label, screenshot: s.screenshot }] : [],
      ),
    [flow.data],
  );
  const closeViewing = useCallback(() => setViewing(false), []);
  const showStep = useCallback((stepId: string) => setSelected({ stepId, kind: 'request' }), []);

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
          {expandable.length > 0 && (
            <>
              <button type="button" className="btn btn-small" onClick={() => setExpanded(new Set(expandable))}>
                Expand all
              </button>
              <button type="button" className="btn btn-small" onClick={collapseAll}>
                Collapse all
              </button>
            </>
          )}
          {steps.length > 0 && (
            <span className="sequence-count muted">
              {calls} {calls === 1 ? 'call' : 'calls'}
              {methodCalls > 0 && ` · ${methodCalls} method ${methodCalls === 1 ? 'call' : 'calls'}`}
            </span>
          )}
        </div>
        <div className="sequence-scroll" ref={scroll}>
          {flow.isLoading && <p className="muted sequence-message">Loading the run…</p>}
          {flow.isError && <p className="error sequence-message">Could not load the run: {flow.error.message}</p>}
          {flow.data && flow.data.steps.length === 0 && <p className="muted sequence-message">This run recorded no calls.</p>}
          {flow.data && flow.data.steps.length > 0 && (
            <SequenceDiagram
              flow={flow.data}
              components={components}
              expanded={expanded}
              onToggle={toggle}
              selected={selected}
              onSelect={setSelected}
            />
          )}
        </div>
      </div>
      <aside className="side-panel sequence-details" aria-label="Message details">
        {step && selected ? (
          <MessageDetails runId={runId} step={step} kind={selected.kind} steps={steps} onOpenScreenshot={() => setViewing(true)} />
        ) : (
          <p className="muted sequence-hint">Select a message to see its payload.</p>
        )}
      </aside>
      {viewing && step && (
        <ScreenshotCarousel runId={runId} slides={slides} current={step.stepId} onMove={showStep} onClose={closeViewing} />
      )}
    </main>
  );
}
