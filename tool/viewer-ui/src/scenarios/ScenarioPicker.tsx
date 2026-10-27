import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { useFlow, useScenarioRuns, useWorkspace } from '../api/queries';
import type { RecordingManifest, ScenarioSummary } from '../api/types';
import { useUiStore } from '../store/uiStore';

function when(startedAt: string): string {
  const date = new Date(startedAt);
  return Number.isNaN(date.getTime())
    ? startedAt
    : date.toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

function runLabel(run: RecordingManifest): string {
  return `${when(run.startedAt)} · ${run.runId} · ${run.kind}`;
}

/** The bar above the board: pick a recorded scenario and run to highlight its path, or open its sequence. */
export function ScenarioPicker() {
  const { data: view } = useWorkspace();
  const scenarioId = useUiStore((s) => s.selectedScenarioId);
  const runId = useUiStore((s) => s.selectedRunId);
  const selectScenario = useUiStore((s) => s.selectScenario);
  const selectRun = useUiStore((s) => s.selectRun);
  const navigate = useNavigate();
  const runs = useScenarioRuns(scenarioId);
  const flow = useFlow(runId);

  const scenarios = view?.scenarios ?? [];
  const current = scenarios.find((s) => s.id === scenarioId);

  return (
    <div className="scenario-bar">
      <ScenarioList
        scenarios={scenarios}
        current={current?.id ?? scenarioId}
        onPick={(s) => selectScenario(s.id, s.latestRunId ?? null)}
      />
      {scenarioId !== null && (
        <>
          <label className="run-select">
            <span>Run</span>
            <select value={runId ?? ''} onChange={(e) => selectRun(e.target.value)} disabled={!runs.data}>
              {(runs.data ?? []).map((run) => (
                <option key={run.runId} value={run.runId}>
                  {runLabel(run)}
                </option>
              ))}
            </select>
          </label>
          <button type="button" className="btn btn-primary" disabled={runId === null} onClick={() => navigate(`/sequence/${runId}`)}>
            Open sequence
          </button>
          <button type="button" className="btn btn-quiet" onClick={() => selectScenario(null, null)}>
            Clear
          </button>
        </>
      )}
      <span className="scenario-status" role="status">
        {runs.isError && <span className="error">Could not load the runs: {runs.error.message}</span>}
        {flow.isError && <span className="error">Could not load the flow: {flow.error.message}</span>}
        {flow.isLoading && <span className="muted">Loading flow…</span>}
        {flow.data && flow.data.steps.length === 0 && <span className="muted">This run recorded no calls.</span>}
        {scenarios.length === 0 && view && <span className="muted">No scenarios yet: record a run of one.</span>}
      </span>
    </div>
  );
}

function ScenarioList({
  scenarios,
  current,
  onPick,
}: {
  scenarios: ScenarioSummary[];
  current: string | null;
  onPick: (scenario: ScenarioSummary) => void;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const outside = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  const pick = (scenario: ScenarioSummary) => {
    if (!scenario.latestRunId) return; // nothing recorded: nothing to show
    onPick(scenario);
    setOpen(false);
  };

  return (
    <div className="scenario-picker" ref={root}>
      <button
        type="button"
        className="btn scenario-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <span className="muted">Scenario</span>
        <strong>{current ?? 'Choose a scenario'}</strong>
        <span aria-hidden="true" className="caret">▾</span>
      </button>
      {open && (
        <div className="scenario-list" role="listbox" aria-label="Scenarios">
          {scenarios.map((scenario) => {
            const disabled = !scenario.latestRunId;
            return (
              <div
                key={scenario.id}
                role="option"
                aria-selected={scenario.id === current}
                aria-disabled={disabled}
                tabIndex={disabled ? -1 : 0}
                className={`scenario-option${disabled ? ' is-disabled' : ''}${scenario.id === current ? ' is-current' : ''}`}
                onClick={() => pick(scenario)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    pick(scenario);
                  }
                }}
              >
                <span className="scenario-name">{scenario.id}</span>
                <span className="scenario-runs">{disabled ? 'no runs' : `${scenario.runCount} ${scenario.runCount === 1 ? 'run' : 'runs'}`}</span>
                {scenario.description && <span className="scenario-description">{scenario.description}</span>}
              </div>
            );
          })}
          {scenarios.length === 0 && <p className="muted scenario-empty">No scenarios defined.</p>}
        </div>
      )}
    </div>
  );
}
