import { useCallback, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { useFlow } from '../api/queries';
import { highlightFor } from '../board/highlight';
import { MessageDetails } from '../sequence/MessageDetails';
import { ScreenshotCarousel, type ScreenshotSlide } from '../sequence/Screenshot';
import { useUiStore } from '../store/uiStore';
import { useWorkspaceName } from '../workspaces/useWorkspaceName';

/** Where the sequence view of a run opens with one of its steps selected. */
export function sequenceLink(ws: string, runId: string, stepId: string): string {
  return `/w/${encodeURIComponent(ws)}/sequence/${encodeURIComponent(runId)}?step=${encodeURIComponent(stepId)}`;
}

/**
 * The step picked by its number on the board, with everything the sequence view's details panel shows about it. The run
 * is walked from here one number at a time, and a number that stands for several calls in a row lists them to pick from.
 */
export function StepDetails({ runId, stepId }: { runId: string; stepId: string }) {
  const ws = useWorkspaceName();
  const flow = useFlow(runId);
  const selectStep = useUiStore((s) => s.selectStep);
  const [viewing, setViewing] = useState(false);
  const highlight = useMemo(() => (flow.data ? highlightFor(flow.data) : null), [flow.data]);
  const slides = useMemo(
    () =>
      (flow.data?.steps ?? []).flatMap((s): ScreenshotSlide[] =>
        s.kind === 'ui' && s.screenshot != null ? [{ stepId: s.stepId, label: s.label, screenshot: s.screenshot }] : [],
      ),
    [flow.data],
  );
  const closeViewing = useCallback(() => setViewing(false), []);

  if (flow.isError) return <p className="error panel-message">Could not load the run: {flow.error.message}</p>;
  if (!flow.data || !highlight) return <p className="muted panel-message">Loading the run…</p>;

  const steps = flow.data.steps;
  const step = steps.find((s) => s.stepId === stepId);
  if (!step) return <p className="muted panel-message">This step is not part of the run.</p>;

  const hops = highlight.hops;
  const hop = hops.find((h) => h.steps.some((s) => s.stepId === stepId));
  const previous = hop && hops[hop.order - 2];
  const next = hop && hops[hop.order];

  return (
    <div className="step-details">
      <nav className="step-nav" aria-label="Steps of the run">
        <button
          type="button"
          className="btn btn-small"
          disabled={!previous}
          onClick={() => previous && selectStep(previous.steps[0].stepId)}
          aria-label="Previous step"
        >
          ‹
        </button>
        <span className="step-nav-title">{hop ? `Step ${hop.order} of ${hops.length}` : 'Step of the run'}</span>
        <button
          type="button"
          className="btn btn-small"
          disabled={!next}
          onClick={() => next && selectStep(next.steps[0].stepId)}
          aria-label="Next step"
        >
          ›
        </button>
        <Link to={sequenceLink(ws, runId, stepId)} className="btn btn-small step-nav-open">
          Sequence view
        </Link>
      </nav>
      {hop && hop.steps.length > 1 && (
        <ol className="step-calls" aria-label={`Calls of step ${hop.order}`}>
          {hop.steps.map((s, i) => (
            <li key={s.stepId}>
              <button
                type="button"
                className={`step-call${s.stepId === stepId ? ' is-selected' : ''}`}
                aria-pressed={s.stepId === stepId}
                onClick={() => selectStep(s.stepId)}
                title={s.label}
              >
                <span className="step-call-index">{i + 1}</span>
                <span className="step-call-label">{s.label}</span>
              </button>
            </li>
          ))}
        </ol>
      )}
      <MessageDetails runId={runId} step={step} kind="request" steps={steps} onOpenScreenshot={() => setViewing(true)} />
      {viewing && (
        <ScreenshotCarousel runId={runId} slides={slides} current={step.stepId} onMove={selectStep} onClose={closeViewing} />
      )}
    </div>
  );
}
